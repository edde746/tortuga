@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.account

import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.ProviderCredential
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.message
import com.edde746.tortuga.sync.account.signin.ALL_PROVIDERS
import com.edde746.tortuga.sync.account.signin.SignInMethod
import com.edde746.tortuga.sync.account.signin.TestSignInPlatform
import com.edde746.tortuga.sync.account.signin.cancelled
import com.edde746.tortuga.sync.account.signin.handoffHttp
import com.edde746.tortuga.sync.account.signin.handoffProof
import com.edde746.tortuga.sync.account.signin.json
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.SyncPaths
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** "Add a sign-in method": which providers are offered, and how a failed attempt reads. */
class IdentityLinkStoreTest {
    private val server = FakeSyncServer().apply { serverInfo = ALL_PROVIDERS }

    private class Host : IdentityLinkHost {
        var linked = 0
        var closed = 0
        override fun identityLinked() {
            linked++
        }
        override fun closeIdentityLink() {
            closed++
        }
    }

    /** Signed in on the hosted server, where Tortuga's own clients (the native sheets, Google) sign in. */
    private suspend fun TestScope.signedIn(platform: TestSignInPlatform = TestSignInPlatform.appleTv(), block: suspend (AccountTestApp) -> Unit) {
        AccountTestApp(server, this, platform).use { app ->
            app.gate.awaitDestination(AccountDestination.SignIn)
            app.account.signIn(ServerUrl.HOSTED) { TestDevice.credential() }
            block(app)
        }
    }

    private fun TestScope.store(app: AccountTestApp, host: IdentityLinkHost) =
        IdentityLinkStore(app.account, app.flows(), host, app.clock, backgroundScope)

    /** The providers once read. */
    private suspend fun IdentityLinkStore.loaded(): IdentityLinkOptions =
        assertIs<IdentityLinkStep.Choose>(state.await { (it.step as? IdentityLinkStep.Choose)?.options != IdentityLinkOptions.Loading }.step).options

    @Test
    fun onlyProvidersTheAccountDoesntHaveAreOffered() = runTest {
        signedIn { app ->
            // The account signed in with the self-hosted provider (the fake server's only identity).
            val loaded = assertIs<IdentityLinkOptions.Buttons>(store(app, Host()).loaded())
            assertEquals(listOf(IdentityProvider.APPLE, IdentityProvider.GOOGLE, IdentityProvider.GITHUB), loaded.options.map { it.provider })

            server.serverInfo = ALL_PROVIDERS.copy(providers = ALL_PROVIDERS.providers.filter { it.id == IdentityProvider.OIDC })
            assertEquals(IdentityLinkOptions.Empty(T.account.identityLink.allLinked), store(app, Host()).loaded())
        }
    }

    @Test
    fun aClosedSheetIsQuietAndAnUnreachableServerSaysSo() = runTest {
        signedIn { app ->
            val host = Host()
            val store = store(app, host)
            store.loaded()
            app.platform.native = { _, _ -> throw cancelled() }
            store.link(IdentityProvider.APPLE)
            runCurrent()
            assertNull(assertIs<IdentityLinkStep.Choose>(store.state.value.step).error)

            app.platform.native = { provider, nonce ->
                server.down = true
                ProviderCredential(provider, "alice-apple", nonce)
            }
            store.link(IdentityProvider.APPLE)
            val failed = store.state.await { (it.step as? IdentityLinkStep.Choose)?.error != null }
            assertEquals(AccountError.Network.message, assertIs<IdentityLinkStep.Choose>(failed.step).error)
            assertEquals(0, host.linked)

            store.close()
            assertEquals(1, host.closed)
        }
    }

    @Test
    fun linkingGitHubSendsItsAccessTokenToTheServerAndClosesTheBrowser() = runTest {
        val github = handoffHttp { request ->
            assertEquals(SyncPaths.GITHUB_HANDOFF, request.url.encodedPath)
            json("""{"accessToken":"gho_link"}""")
        }
        signedIn(TestSignInPlatform.desktop()) { app ->
            app.platform.redirect = handoffProof
            val host = Host()
            val store = IdentityLinkStore(app.account, app.flows(githubHttp = github), host, app.clock, backgroundScope)
            val option = assertIs<IdentityLinkOptions.Buttons>(store.loaded()).options.single { it.provider == IdentityProvider.GITHUB }
            assertEquals(SignInMethod.GITHUB_WEB, option.method)
            assertEquals(T.account.signIn.github, option.label)

            store.link(IdentityProvider.GITHUB)
            runCurrent()
            assertEquals(1, host.linked)
            assertEquals(1, app.platform.browserClosed)
            val linked = server.account().identities.last()
            assertEquals(IdentityProvider.GITHUB, linked.info.provider)
            assertEquals("gho_link", linked.subject)
        }
    }
}
