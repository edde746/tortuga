@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.account

import com.edde746.tortuga.presentation.profiles.ProfilePickerHost
import com.edde746.tortuga.presentation.profiles.ProfilePickerStore
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.AccountState
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.SignInAttempt
import com.edde746.tortuga.sync.account.SyncApi
import com.edde746.tortuga.sync.account.message
import com.edde746.tortuga.sync.account.signin.ALL_PROVIDERS
import com.edde746.tortuga.sync.account.signin.AppleWebSignIn
import com.edde746.tortuga.sync.account.signin.BuildSignInConfig
import com.edde746.tortuga.sync.account.signin.GITHUB_ISSUER
import com.edde746.tortuga.sync.account.signin.GitHubWebSignIn
import com.edde746.tortuga.sync.account.signin.OAuthClientConfig
import com.edde746.tortuga.sync.account.signin.ProviderFlows
import com.edde746.tortuga.sync.account.signin.SignInConfig
import com.edde746.tortuga.sync.account.signin.SignInMethod
import com.edde746.tortuga.sync.account.signin.SyncServers
import com.edde746.tortuga.sync.account.signin.TestSignInPlatform
import com.edde746.tortuga.sync.account.signin.cancelled
import com.edde746.tortuga.sync.account.signin.handoffHttp
import com.edde746.tortuga.sync.account.signin.handoffProof
import com.edde746.tortuga.sync.account.signin.json
import com.edde746.tortuga.sync.account.signin.mockHttp
import com.edde746.tortuga.sync.account.signin.noProviderCalls
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.SyncPaths
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sign-in screen against the fake server: servers, per-platform providers, flows and outcomes. */
class SignInStoreTest {
    private val server = FakeSyncServer().apply { serverInfo = ALL_PROVIDERS }

    private fun TestScope.app(platform: TestSignInPlatform, name: String = "New device") = AccountTestApp(server, this, platform, name = name)

    @Test
    fun theButtonsNameTheirProviders() {
        val flows = ProviderFlows(noProviderCalls, TestSignInPlatform.desktop(), { 0L }, appleWeb = { error("no Apple web flow") }, githubWeb = { error("no GitHub web flow") })
        val noReturn = ALL_PROVIDERS.copy(appleWebReturn = false)
        assertEquals(
            listOf(T.account.signIn.google, T.account.signIn.github, T.account.signIn.oidc(isolate("Authentik"))),
            flows.options(ServerUrl.HOSTED, noReturn, emptySet()).map { it.toUi().label },
        )
    }

    @Test
    fun theHostedServerIsTheDefaultAndOwnServersAreCheckedFirst() = runTest {
        app(TestSignInPlatform.desktop()).use { app ->
            val store = app.signInStore()
            val hosted = store.state.await { !it.optionsLoading }
            assertEquals("api.usetortuga.app", hosted.server.label)
            assertEquals(false, hosted.server.custom)
            assertEquals(4, hosted.options.size)

            store.editServer()
            store.setServerField("ftp://files.example")
            store.submitServer()
            assertEquals(AccountError.InvalidServerUrl.message, store.state.value.server.error)

            server.down = true
            store.setServerField("sync.example.com:8443")
            store.submitServer()
            assertTrue(store.state.value.server.checking)
            assertEquals(AccountError.Network.message, store.state.await { !it.server.checking }.server.error)
            assertEquals("api.usetortuga.app", store.state.value.server.label)

            server.down = false
            server.serverInfo = ALL_PROVIDERS.copy(providers = ALL_PROVIDERS.providers.filter { it.id == IdentityProvider.OIDC }, appleWebReturn = false)
            store.submitServer()
            val own = store.state.await { !it.server.editing }
            assertEquals("sync.example.com:8443", own.server.label)
            assertTrue(own.server.custom)
            assertEquals(listOf(IdentityProvider.OIDC), own.options.map { it.provider })

            // A server with nothing this device can use says so; linking stays possible.
            server.serverInfo = ALL_PROVIDERS.copy(providers = ALL_PROVIDERS.providers.filter { it.id == IdentityProvider.APPLE }, appleWebReturn = false)
            store.useHostedServer()
            val none = store.state.await { !it.optionsLoading }
            assertEquals("api.usetortuga.app", none.server.label)
            assertEquals(emptyList(), none.options)
            assertEquals(T.account.signIn.noOptions, none.optionsNote)
        }
    }

    @Test
    fun aBlankServerAddressIsNotChecked() = runTest {
        app(TestSignInPlatform.desktop()).use { app ->
            val store = app.signInStore()
            store.state.await { !it.optionsLoading }
            store.editServer()
            // Return or Enter in a field holding only blanks, as Check server, which is off then: nothing is asked and
            // nothing is said.
            store.setServerField(" \t\n")
            store.submitServer()
            val blank = store.state.value.server
            assertNull(blank.error)
            assertFalse(blank.checking)
            assertTrue(blank.editing)
        }
    }

    @Test
    fun anUnreachableServerCanBeRetried() = runTest {
        server.down = true
        app(TestSignInPlatform.desktop()).use { app ->
            val store = app.signInStore()
            val failed = store.state.await { !it.optionsLoading }
            assertEquals(AccountError.Network.message, failed.optionsNote)
            assertTrue(failed.canRetry)
            server.down = false
            store.retry()
            assertEquals(4, store.state.await { !it.optionsLoading }.options.size)
        }
    }

    @Test
    fun theOptionsStandInTheRoomKeptForTheHostedServersButtons() = runTest {
        // The hosted server lists every provider the app has a flow for but the self-hoster's OpenID Connect: on every
        // platform the buttons it makes fill the room kept while they load, however many they are.
        val hosted = ALL_PROVIDERS.copy(providers = ALL_PROVIDERS.providers.filter { it.id != IdentityProvider.OIDC })
        val phone = TestSignInPlatform(nativeGoogle = true, canOpenBrowser = true, appleServicesId = "com.edde746.tortuga.web")
        val androidTv = TestSignInPlatform(isTv = true, googleTvClient = OAuthClientConfig("google-tv", "s"))
        for (platform in listOf(TestSignInPlatform.desktop(), TestSignInPlatform.appleTv(), phone, androidTv, TestSignInPlatform())) {
            server.serverInfo = hosted
            app(platform).use { app ->
                val store = app.signInStore()
                val loading = store.state.value
                assertTrue(loading.optionsLoading)
                val loaded = store.state.await { !it.optionsLoading }
                assertEquals(loading.optionsRoom, loaded.options.size)
                assertEquals(loading.optionsRoom, loaded.optionsRoom)
            }
        }
        app(TestSignInPlatform.desktop()).use { app ->
            // Fewer buttons than the room stand in it, and so does the note of a server that couldn't be reached.
            server.serverInfo = ALL_PROVIDERS.copy(providers = ALL_PROVIDERS.providers.filter { it.id == IdentityProvider.GOOGLE })
            val store = app.signInStore()
            val room = store.state.value.optionsRoom
            val fewer = store.state.await { !it.optionsLoading }
            assertEquals(listOf(IdentityProvider.GOOGLE), fewer.options.map { it.provider })
            assertEquals(room, fewer.optionsRoom)
            server.down = true
            store.useHostedServer()
            val unreachable = store.state.await { !it.optionsLoading }
            assertTrue(unreachable.canRetry)
            assertEquals(room, unreachable.optionsRoom)

            // An own server's options come with its check, in the server sheet: no room kept for them.
            server.down = false
            server.serverInfo = ALL_PROVIDERS.copy(providers = ALL_PROVIDERS.providers.filter { it.id == IdentityProvider.OIDC }, appleWebReturn = false)
            store.editServer()
            store.setServerField("sync.example.com")
            store.submitServer()
            assertEquals(0, store.state.await { !it.server.editing }.optionsRoom)
            store.useHostedServer()
            assertEquals(room, store.state.value.optionsRoom)
        }
    }

    @Test
    fun theServerSheetOpenedWhileTheOptionsLoadStaysUpAsTheyCome() = runTest {
        app(TestSignInPlatform.desktop()).use { app ->
            val store = app.signInStore()
            assertTrue(store.state.value.optionsLoading)
            store.editServer()
            store.setServerField("sync.example.com")
            val loaded = store.state.await { !it.optionsLoading }
            assertEquals(4, loaded.options.size)
            assertTrue(loaded.server.editing)
            assertEquals("sync.example.com", loaded.server.field)
            assertEquals("api.usetortuga.app", loaded.server.label)
        }
    }

    /** Counts the picker's requests for the new-profile editor. */
    private class CreatingHost : ProfilePickerHost {
        var creates = 0
        override fun createProfile() {
            creates++
        }
        override fun editProfile(profileId: String) = Unit
    }

    @Test
    fun aNewAccountGoesStraightToCreatingItsFirstProfile() = runTest {
        app(TestSignInPlatform.appleTv()).use { app ->
            app.gate.awaitDestination(AccountDestination.SignIn)
            val store = app.signInStore()
            store.state.await { !it.optionsLoading }
            var nonceSeen: String? = null
            app.platform.native = { provider, nonce ->
                nonceSeen = nonce
                com.edde746.tortuga.sync.account.ProviderCredential(provider, "alice", nonce)
            }
            store.signIn(IdentityProvider.APPLE)

            app.gate.awaitDestination(AccountDestination.Profiles)
            assertNotNull(nonceSeen)
            assertIs<AccountState.SignedIn>(app.account.state.value)
            assertNull(assertIs<SignInStep.Choose>(store.state.value.step).error)

            val host = CreatingHost()
            ProfilePickerStore(app.data.profiles, app.gate, host, backgroundScope)
            runCurrent()
            assertEquals(1, host.creates)
            // Only the first picker of the new account opens the editor by itself.
            val again = CreatingHost()
            ProfilePickerStore(app.data.profiles, app.gate, again, backgroundScope)
            runCurrent()
            assertEquals(0, again.creates)
        }
    }

    @Test
    fun anExistingAccountGoesToItsProfilesWithoutCreatingOne() = runTest {
        TestDevice("Phone", server, this, backgroundScope).use { phone ->
            phone.signUp(server)
            app(TestSignInPlatform.appleTv()).use { app ->
                val store = app.signInStore()
                store.state.await { !it.optionsLoading }
                store.signIn(IdentityProvider.APPLE)
                app.gate.awaitDestination(AccountDestination.Profiles)
                assertNull(assertIs<SignInStep.Choose>(store.state.value.step).error)

                val host = CreatingHost()
                ProfilePickerStore(app.data.profiles, app.gate, host, backgroundScope)
                runCurrent()
                assertEquals(0, host.creates)
            }
        }
    }

    @Test
    fun aClosedSheetIsQuietAndOtherFailuresSayWhy() = runTest {
        app(TestSignInPlatform.appleTv()).use { app ->
            val store = app.signInStore()
            store.state.await { !it.optionsLoading }
            app.platform.native = { _, _ -> throw cancelled() }
            store.signIn(IdentityProvider.APPLE)
            runCurrent()
            assertNull(assertIs<SignInStep.Choose>(store.state.await { it.step is SignInStep.Choose }.step).error)

            app.platform.native = { provider, nonce ->
                server.down = true
                com.edde746.tortuga.sync.account.ProviderCredential(provider, "alice", nonce)
            }
            store.signIn(IdentityProvider.APPLE)
            assertEquals(AccountError.Network.message, store.awaitError())
            assertEquals(AccountDestination.SignIn, app.gate.state.value.destination)
        }
    }

    @Test
    fun aTvSignsInToGoogleWithACodeThatCountsDown() = runTest {
        var polls = 0
        val google = mockHttp { request ->
            when (request.url.encodedPath) {
                "/device/code" -> json(
                    """{"device_code":"dc","user_code":"GQVQ-JKEC","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}""",
                )
                "/token" -> if (++polls < 3) {
                    json("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest)
                } else {
                    json("""{"access_token":"at","id_token":"bob"}""")
                }
                else -> error("unexpected ${request.url}")
            }
        }
        app(TestSignInPlatform.appleTv()).use { app ->
            val store = app.signInStore(google)
            store.state.await { !it.optionsLoading }
            store.signIn(IdentityProvider.GOOGLE)
            val code = store.awaitDeviceCode()
            assertEquals("GQVQ-JKEC", code.userCode)
            assertEquals("https://www.google.com/device", code.qrPayload)
            assertEquals(T.account.signIn.deviceCode.instructions(isolate("www.google.com/device")), code.instructions)
            assertEquals(T.account.signIn.deviceCode.expires("30:00"), code.expiresText)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(T.account.signIn.deviceCode.expires("29:59"), assertIs<SignInStep.DeviceCode>(store.state.value.step).code.expiresText)

            app.gate.awaitDestination(AccountDestination.Profiles)
            assertEquals(3, polls)
            assertEquals("bob", server.account().subject)
            // The device flow created the account: the TV's picker opens the first profile's editor.
            val host = CreatingHost()
            ProfilePickerStore(app.data.profiles, app.gate, host, backgroundScope)
            runCurrent()
            assertEquals(1, host.creates)
        }
    }

    @Test
    fun cancellingADeviceCodeReleasesTheScreen() = runTest {
        val google = mockHttp { request ->
            when (request.url.encodedPath) {
                "/device/code" -> json("""{"device_code":"dc","user_code":"ABCD-EFGH","verification_url":"https://www.google.com/device","expires_in":600}""")
                else -> json("""{"error":"authorization_pending"}""", HttpStatusCode.BadRequest)
            }
        }
        app(TestSignInPlatform.appleTv()).use { app ->
            val store = app.signInStore(google)
            store.state.await { !it.optionsLoading }
            store.signIn(IdentityProvider.GOOGLE)
            store.awaitDeviceCode()
            store.cancel()
            assertIs<SignInStep.Choose>(store.state.value.step)
            advanceTimeBy(60_000)
            runCurrent()
            assertIs<SignInStep.Choose>(store.state.value.step)
            assertTrue(server.accounts.isEmpty())
        }
    }

    @Test
    fun desktopSignsInToItsOwnServersProviderInTheBrowser() = runTest {
        val issuer = mockHttp { request ->
            when (request.url.encodedPath) {
                "/.well-known/openid-configuration" -> json(
                    """{"issuer":"https://id.example","authorization_endpoint":"https://id.example/auth","token_endpoint":"https://id.example/token"}""",
                )
                "/token" -> json("""{"access_token":"at","id_token":"carol"}""")
                else -> error("unexpected ${request.url}")
            }
        }
        app(TestSignInPlatform.desktop()).use { app ->
            app.platform.redirect = { query -> mapOf("code" to "the-code", SyncPaths.RETURN_STATE to query.getValue("state")) }
            val store = app.signInStore(issuer)
            store.state.await { !it.optionsLoading }
            store.signIn(IdentityProvider.OIDC)
            app.gate.awaitDestination(AccountDestination.Profiles)
            val opened = app.platform.opened.single()
            assertTrue(opened.startsWith("https://id.example/auth?"))
            assertTrue("client_id=tortuga-app" in opened)
            assertEquals("carol", server.account().subject)
        }
    }

    @Test
    fun gitHubSignsInThroughTheServersReturnPage() = runTest {
        val collected = ArrayList<String>()
        val github = handoffHttp { request ->
            assertEquals(SyncPaths.GITHUB_HANDOFF, request.url.encodedPath)
            collected += (request.body as TextContent).text
            json("""{"accessToken":"gho_alice"}""")
        }
        app(TestSignInPlatform.desktop()).use { app ->
            app.gate.awaitDestination(AccountDestination.SignIn)
            app.platform.redirect = handoffProof
            val store = app.signInStore(githubHttp = github)
            store.state.await { !it.optionsLoading }
            store.signIn(IdentityProvider.GITHUB)
            app.gate.awaitDestination(AccountDestination.Profiles)

            val opened = Url(app.platform.opened.single())
            assertEquals("$GITHUB_ISSUER/login/oauth/authorize", opened.toString().substringBefore('?'))
            assertEquals("tortuga-github", opened.parameters["client_id"])
            assertEquals(SyncServers.HOSTED + SyncPaths.GITHUB_RETURN, opened.parameters["redirect_uri"])
            assertEquals("state-1", opened.parameters["state"])
            assertTrue("\"proof\":\"pr00f\"" in collected.single())
            val signIn = server.signIns.single()
            assertEquals(IdentityProvider.GITHUB, signIn.provider)
            assertEquals("gho_alice", signIn.idToken)
            assertNull(signIn.nonce)
            assertEquals(1, app.platform.browserClosed)
        }
    }

    @Test
    fun everyEndOfAWebFlowClosesTheBrowserAndAFailedOneSaysWhy() = runTest {
        val refused = """{"code":"invalid_id_token","message":"GitHub refused the code"}"""
        val github = handoffHttp { json(refused, HttpStatusCode.Unauthorized) }
        val apple = handoffHttp { error("no collect expected") }
        app(TestSignInPlatform.desktop()).use { app ->
            val store = app.signInStore(appleHttp = apple, githubHttp = github)
            store.state.await { !it.optionsLoading }
            // Declined at GitHub.
            app.platform.redirect = { query -> mapOf(SyncPaths.RETURN_STATE to query.getValue("state"), SyncPaths.RETURN_ERROR to "access_denied") }
            store.signIn(IdentityProvider.GITHUB)
            assertEquals(AccountError.SignInCancelled.message, store.awaitError())
            assertEquals(1, app.platform.browserClosed)

            // GitHub refused the code the server exchanged.
            app.platform.redirect = handoffProof
            store.signIn(IdentityProvider.GITHUB)
            store.state.await { (it.step as? SignInStep.Choose)?.error == AccountError.SignInRejected.message }
            assertEquals(2, app.platform.browserClosed)

            // Cancelled while waiting for the browser, GitHub's and Apple's.
            app.platform.redirect = { null }
            for ((provider, closed) in listOf(IdentityProvider.GITHUB to 3, IdentityProvider.APPLE to 4)) {
                store.signIn(provider)
                runCurrent()
                assertEquals(SignInStep.Working(T.account.signIn.browser), store.state.value.step, provider)
                store.cancel()
                runCurrent()
                assertIs<SignInStep.Choose>(store.state.value.step, provider)
                assertEquals(closed, app.platform.browserClosed, provider)
            }
            assertTrue(server.signIns.isEmpty())
        }
    }

    @Test
    fun aTvSignsInToGitHubWithACode() = runTest {
        var polls = 0
        val github = mockHttp { request ->
            when (request.url.toString()) {
                "$GITHUB_ISSUER/login/device/code" -> json(
                    """{"device_code":"dc","user_code":"WDJB-MJHT","verification_uri":"$GITHUB_ISSUER/login/device","expires_in":900,"interval":5}""",
                )
                "$GITHUB_ISSUER/login/oauth/access_token" -> if (++polls < 2) {
                    json("""{"error":"authorization_pending"}""")
                } else {
                    json("""{"access_token":"gho_tv","token_type":"bearer","scope":"user:email"}""")
                }
                else -> error("unexpected ${request.url}")
            }
        }
        app(TestSignInPlatform.appleTv()).use { app ->
            val store = app.signInStore(github)
            store.state.await { !it.optionsLoading }
            store.signIn(IdentityProvider.GITHUB)
            val code = store.awaitDeviceCode()
            assertEquals("WDJB-MJHT", code.userCode)
            assertEquals("$GITHUB_ISSUER/login/device", code.qrPayload)
            assertEquals(T.account.signIn.deviceCode.instructions(isolate("github.example/login/device")), code.instructions)

            app.gate.awaitDestination(AccountDestination.Profiles)
            assertEquals(2, polls)
            val signIn = server.signIns.single()
            assertEquals(IdentityProvider.GITHUB, signIn.provider)
            assertEquals("gho_tv", signIn.idToken)
            assertNull(signIn.nonce)
            assertEquals(0, app.platform.browserClosed)
        }
    }

    @Test
    fun aDeviceWithoutASessionCanBeLinkedFromASignedInOne() = runTest {
        TestDevice("Phone", server, this, backgroundScope).use { phone ->
            phone.signUp(server)
            app(TestSignInPlatform.appleTv(), name = "Living room TV").use { tv ->
                tv.gate.awaitDestination(AccountDestination.SignIn)
                val store = tv.signInStore()
                store.state.await { !it.optionsLoading }
                store.linkDevice()
                val step = store.state.await { (it.step as? SignInStep.Link)?.link is DeviceLinkUiState.Waiting }.step
                val waiting = assertIs<DeviceLinkUiState.Waiting>(assertIs<SignInStep.Link>(step).link)
                val code = waiting.code
                assertTrue(waiting.qrPayload.startsWith("tortuga://link?server="))

                phone.account.claimDeviceLink(code).approve()
                tv.gate.awaitDestination(AccountDestination.Profiles)
                val signedIn = assertIs<AccountState.SignedIn>(tv.account.state.value)
                assertEquals(server.account().id, signedIn.accountId)
            }
        }
    }

    /** The error the choices show once a sign-in fails. */
    private suspend fun SignInStore.awaitError(): String? =
        assertIs<SignInStep.Choose>(state.await { (it.step as? SignInStep.Choose)?.error != null }.step).error

    /** The device flow's code, once it shows. */
    private suspend fun SignInStore.awaitDeviceCode(): DeviceCodeUiState =
        assertIs<SignInStep.DeviceCode>(state.await { it.step is SignInStep.DeviceCode }.step).code
}
