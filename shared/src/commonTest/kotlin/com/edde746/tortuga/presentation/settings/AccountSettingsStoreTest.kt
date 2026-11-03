@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.movie
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.signin.ProviderFlows
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.AccountState
import com.edde746.tortuga.sync.account.DeviceLinkOutcome
import com.edde746.tortuga.sync.account.DeviceLinkQr
import com.edde746.tortuga.sync.account.ProviderCredential
import com.edde746.tortuga.sync.account.SignInResult
import com.edde746.tortuga.sync.account.SignedOutReason
import com.edde746.tortuga.sync.account.signin.TestSignInPlatform
import com.edde746.tortuga.sync.account.signin.noProviderCalls
import com.edde746.tortuga.sync.api.AppleRevocation
import com.edde746.tortuga.sync.api.GitHubRevocation
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.ProviderInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The account settings store against the in-memory sync server (Ktor MockEngine). */
class AccountSettingsStoreTest {
    private val server = FakeSyncServer()

    private class Host : AccountSettingsHost {
        var identityLinks = 0
        val deleted = mutableListOf<String>()
        override fun linkIdentity() {
            identityLinks++
        }
        override fun accountDeleted(message: String) {
            deleted += message
        }
    }

    private fun TestScope.device(name: String, clockSkewMillis: Long = 0) =
        TestDevice(name, server, this, backgroundScope, clockSkewMillis = clockSkewMillis)

    private suspend fun TestDevice.signUp(subject: String = "alice") {
        account.restore()
        assertEquals(SignInResult.NEW_ACCOUNT, account.signIn(server.serverUrl) { TestDevice.credential(subject) })
    }

    private suspend fun TestDevice.signInExisting(subject: String = "alice") {
        account.restore()
        account.signIn(server.serverUrl) { TestDevice.credential(subject) }
    }

    private fun TestScope.settings(device: TestDevice, host: Host = Host()): AccountSettingsStore {
        val flows = ProviderFlows(noProviderCalls, TestSignInPlatform(), device.account.clock, { error("no Apple web flow") }, { error("no GitHub web flow") })
        return AccountSettingsStore(device.account, device.engine, flows, host, device.account.clock, backgroundScope).also { runCurrent() }
    }

    @Test
    fun aLinkIsCheckedByCodeThenTheRequestingDeviceIsShownForConfirmation() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            device("Living room TV").use { tv ->
                tv.account.restore()
                val pending = tv.account.startDeviceLink(server.serverUrl)
                val outcome = async { pending.awaitResult() }
                val store = settings(phone)
                fun step() = store.state.value.linkApproval?.step

                assertNull(store.state.value.linkApproval)
                store.startLinkApproval()
                assertEquals(LinkApprovalStep.EnterCode("", canSubmit = false, error = null), step())
                store.updateLinkCode("zzzz zzz")
                assertEquals(LinkApprovalStep.EnterCode("ZZZZ-ZZZ", canSubmit = false, error = null), step())
                store.submitLinkCode()
                assertIs<LinkApprovalStep.EnterCode>(step(), "an incomplete code isn't sent")

                // A well-formed code no device is waiting with.
                store.updateLinkCode("zzzzzzzz")
                store.submitLinkCode()
                assertEquals(LinkApprovalStep.Checking, step())
                runCurrent()
                assertEquals(T.account.error.linkNotFound, assertIs<LinkApprovalStep.EnterCode>(step()).error)

                store.updateLinkCode(pending.code.lowercase().replace('-', ' '))
                assertEquals(LinkApprovalStep.EnterCode(pending.code, canSubmit = true, error = null), step())
                store.submitLinkCode()
                runCurrent()
                assertEquals(
                    LinkApprovalStep.Confirm(
                        code = pending.code,
                        deviceName = "Living room TV",
                        devicePlatform = T.settings.account.platform.linux,
                        question = T.settings.account.link.question(isolate("Living room TV")),
                        consequence = T.settings.account.link.consequence,
                    ),
                    step(),
                )
                assertFalse(outcome.isCompleted, "nothing is sent before the user confirms")

                store.approveLink()
                assertEquals(LinkApprovalStep.Approving, step())
                runCurrent()
                assertEquals(LinkApprovalStep.Approved(T.settings.account.link.approved(isolate("Living room TV"))), step())
                assertEquals(DeviceLinkOutcome.LINKED, outcome.await())
                assertEquals(server.account().id, assertIs<AccountState.SignedIn>(tv.account.state.value).accountId)

                store.closeLinkApproval()
                assertNull(store.state.value.linkApproval)
            }
        }
    }

    @Test
    fun aScannedCodeIsCheckedAtOnceAndARejectionReachesTheNewDevice() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            device("Tablet").use { tablet ->
                tablet.account.restore()
                val pending = tablet.account.startDeviceLink(server.serverUrl)
                val outcome = async { pending.awaitResult() }
                val store = settings(phone)
                fun step() = store.state.value.linkApproval?.step

                // A QR code from a device on another server is refused before anything is asked.
                store.submitScannedLink(DeviceLinkQr(ServerUrl.parse("https://other.test"), pending.code.replace("-", "")))
                runCurrent()
                assertEquals(T.account.error.linkOtherServer, assertIs<LinkApprovalStep.EnterCode>(step()).error)

                store.submitScannedLink(checkNotNull(DeviceLinkQr.parse(pending.qrPayload)))
                runCurrent()
                assertEquals(pending.code, assertIs<LinkApprovalStep.Confirm>(step()).code)

                store.rejectLink()
                assertEquals(LinkApprovalStep.Rejecting, step())
                runCurrent()
                assertEquals(LinkApprovalStep.Rejected(T.settings.account.link.rejected(isolate("Tablet"))), step())
                assertEquals(DeviceLinkOutcome.REJECTED, outcome.await())
                assertEquals(AccountState.SignedOut(null), tablet.account.state.value)
            }
        }
    }

    @Test
    fun aLinkThatExpiresWhileWaitingIsNotApproved() = runTest {
        // Both clocks are hours off the server's: the confirmation still ends when the server's link does.
        device("Phone", clockSkewMillis = -3 * HOUR).use { phone ->
            phone.signUp()
            device("Living room TV", clockSkewMillis = 2 * HOUR).use { tv ->
                tv.account.restore()
                val pending = tv.account.startDeviceLink(server.serverUrl)
                val store = settings(phone)
                fun step() = store.state.value.linkApproval?.step

                store.startLinkApproval()
                store.updateLinkCode(pending.code)
                store.submitLinkCode()
                runCurrent()
                assertIs<LinkApprovalStep.Confirm>(step())
                advanceTimeBy(FakeSyncServer.LINK_TTL_MILLIS - 1)
                runCurrent()
                assertIs<LinkApprovalStep.Confirm>(step())
                advanceTimeBy(1)
                runCurrent()
                assertEquals(LinkApprovalStep.EnterCode("", canSubmit = false, error = T.settings.account.link.expired), step())
                store.approveLink()
                assertIs<LinkApprovalStep.EnterCode>(step())
            }
        }
    }

    @Test
    fun deletingTheAccountAsksFirstThenSaysWhatSignInWithAppleStillNeeds() = runTest {
        val outcomes = listOf(
            AppleRevocation.NONE to T.account.deletion.done,
            AppleRevocation.REVOKED to T.account.deletion.appleRevoked,
            AppleRevocation.NOT_CONFIGURED to T.account.deletion.appleManual,
            AppleRevocation.FAILED to T.account.deletion.appleManual,
        )
        for ((index, outcome) in outcomes.withIndex()) {
            val (revocation, expected) = outcome
            server.appleRevocation = revocation
            device("Phone $index").use { phone ->
                phone.signUp("user-$index")
                val host = Host()
                val store = settings(phone, host)
                store.deleteAccount()
                val dialog = assertNotNull(store.state.value.dialog)
                assertEquals(AccountSettingsAction.DELETE_ACCOUNT, dialog.action)
                assertTrue(dialog.destructive)
                store.dismissDialog()
                runCurrent()
                assertTrue(server.accounts.values.any { it.subject == "user-$index" }, "dismissed: nothing deleted")

                store.deleteAccount()
                store.confirmDialog()
                assertTrue(store.state.value.working)
                runCurrent()
                assertEquals(listOf(expected), host.deleted, revocation)
                assertEquals(expected, store.state.value.notice)
                assertFalse(server.accounts.values.any { it.subject == "user-$index" })
                assertEquals(AccountState.SignedOut(SignedOutReason.ACCOUNT_DELETED), phone.account.state.value)
                assertFalse(store.state.value.signedIn)
            }
        }
    }

    @Test
    fun deletingAnAccountWhoseGitHubGrantStayedSaysWhereToRemoveTortuga() = runTest {
        val withGitHub = server.serverInfo.copy(
            providers = server.serverInfo.providers + ProviderInfo(IdentityProvider.GITHUB, "https://github.example/", listOf("gh-client")),
        )
        val outcomes = listOf(
            Triple(GitHubRevocation.REVOKED, withGitHub, T.account.deletion.done),
            Triple(GitHubRevocation.FAILED, withGitHub, T.account.deletion.done + "\n" + T.account.deletion.githubManual(isolate("github.example/settings/applications"))),
            // The server no longer lists GitHub: github.com, where the grant most likely is.
            Triple(GitHubRevocation.FAILED, server.serverInfo, T.account.deletion.done + "\n" + T.account.deletion.githubManual(isolate("github.com/settings/applications"))),
        )
        for ((index, outcome) in outcomes.withIndex()) {
            val (revocation, info, expected) = outcome
            server.githubRevocation = revocation
            server.serverInfo = info
            device("Phone $index").use { phone ->
                phone.signUp("user-$index")
                val host = Host()
                val store = settings(phone, host)
                store.deleteAccount()
                store.confirmDialog()
                runCurrent()
                assertEquals(listOf(expected), host.deleted, revocation)
                assertEquals(expected, store.state.value.notice)
            }
        }
    }

    @Test
    fun devicesMarkThisOneAndRevokingAnotherSignsItOut() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            device("Tablet").use { tablet ->
                tablet.signInExisting()
                val store = settings(phone)
                val devices = store.state.value.devices
                assertEquals(listOf("Phone", "Tablet"), devices.map { it.name })
                assertEquals(listOf(true, false), devices.map { it.current })
                assertEquals(T.settings.account.thisDevice, devices[0].currentLabel)
                assertEquals(
                    T.settings.account.deviceDetail(isolate(T.settings.account.platform.linux), T.settings.account.activeNow),
                    devices[0].detail,
                )

                store.revokeDevice(devices[1].id)
                assertEquals(T.settings.account.revoke.title(isolate("Tablet")), store.state.value.dialog?.title)
                store.confirmDialog()
                runCurrent()
                assertEquals(listOf("Phone"), store.state.value.devices.map { it.name })
                assertEquals(T.settings.account.deviceRemoved(isolate("Tablet")), store.state.value.notice)
                assertEquals(1, server.sessions.size)

                store.signOut()
                store.confirmDialog()
                runCurrent()
                assertIs<AccountState.SignedOut>(phone.account.state.value)
                assertFalse(store.state.value.signedIn)
                assertEquals(emptyList(), store.state.value.devices)
            }
        }
    }

    @Test
    fun signingOutOfflinePushesFirstThenSaysWhatItDeletesAndKeepsItUnlessConfirmed() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            val profile = phone.data.profiles.create("Wednesday", null)
            phone.engine.syncNow()
            val store = settings(phone)
            server.down = true
            phone.data.library.addToLibrary(profile.id, movie())

            store.signOut()
            assertEquals(T.settings.account.signOut.message, store.state.value.dialog?.message)
            store.confirmDialog()
            runCurrent()
            val second = assertNotNull(store.state.value.dialog)
            assertEquals(T.settings.account.unsynced.message(1), second.message)
            assertTrue(second.destructive)
            store.dismissDialog()
            assertIs<AccountState.SignedIn>(phone.account.state.value)
            assertEquals(1, phone.data.sync.dirtyRecords(null, 10).size)

            // Back online, the push before the sign-out takes the change: no second question.
            server.down = false
            store.signOut()
            store.confirmDialog()
            runCurrent()
            assertNull(store.state.value.dialog)
            assertIs<AccountState.SignedOut>(phone.account.state.value)
            assertTrue(server.account().records.keys.any { it.startsWith("library/${profile.id}/") })

            // Offline again on another sign-in: confirming the second question deletes the change.
            phone.signInExisting()
            phone.engine.syncNow()
            runCurrent()
            server.down = true
            phone.data.library.addToLibrary(profile.id, movie("tt0000002"))
            store.signOut()
            store.confirmDialog()
            runCurrent()
            assertEquals(T.settings.account.unsynced.message(1), store.state.value.dialog?.message)
            store.confirmDialog()
            runCurrent()
            assertIs<AccountState.SignedOut>(phone.account.state.value)
            assertTrue(phone.data.profiles.profiles().isEmpty())
        }
    }

    @Test
    fun theOnlySignInCannotBeUnlinked() = runTest {
        device("Phone").use { phone ->
            phone.signUp()
            phone.account.linkIdentity(ProviderCredential(IdentityProvider.GOOGLE, "alice-google", nonce = null))
            val host = Host()
            val store = settings(phone, host)
            val identities = store.state.value.identities
            assertEquals(listOf("Test", T.settings.account.provider.google), identities.map { it.provider }, "the server's own provider by its name")
            assertEquals(listOf(true, true), identities.map { it.canUnlink })
            assertFalse(store.state.value.canLinkIdentity, "the server offers no provider the account lacks")

            store.unlinkIdentity(identities[1].id)
            assertEquals(T.settings.account.unlink.title(isolate(T.settings.account.provider.google)), store.state.value.dialog?.title)
            store.confirmDialog()
            runCurrent()
            assertEquals(listOf("Test"), store.state.value.identities.map { it.provider })
            assertEquals(listOf(false), store.state.value.identities.map { it.canUnlink })
            assertEquals(T.settings.account.identityRemoved(isolate(T.settings.account.provider.google)), store.state.value.notice)

            store.linkIdentity()
            assertEquals(1, host.identityLinks)
        }
    }

    @Test
    fun aSignInMethodThisDeviceCantAddIsNotOffered() = runTest {
        // Google signs in only to the hosted server; this one lists it all the same.
        server.serverInfo = server.serverInfo.copy(
            providers = server.serverInfo.providers + ProviderInfo(IdentityProvider.GOOGLE, "https://accounts.google.com", listOf("google-client")),
        )
        device("Phone").use { phone ->
            phone.signUp()
            val store = settings(phone)
            assertEquals(listOf("Test"), store.state.value.identities.map { it.provider })
            assertFalse(store.state.value.canLinkIdentity)
        }
    }

    @Test
    fun aServerInfoThatCantBeReadIsALoadErrorAndIsReadAgainOnRetry() = runTest {
        server.serverInfo = server.serverInfo.copy(
            providers = server.serverInfo.providers + ProviderInfo(IdentityProvider.GITHUB, "https://github.example", listOf("gh-client")),
        )
        device("Phone").use { phone ->
            phone.signUp()
            server.infoUnreachable = true
            val store = settings(phone)
            assertEquals(T.account.error.network, store.state.value.loadError)
            assertFalse(store.state.value.canLinkIdentity)

            server.infoUnreachable = false
            store.refresh()
            runCurrent()
            assertNull(store.state.value.loadError)
            assertTrue(store.state.value.canLinkIdentity)
        }
    }

    @Test
    fun aSignInLinkedFromAnyScreenShowsInTheSettingsAtOnce() = runTest {
        server.serverInfo = server.serverInfo.copy(
            providers = server.serverInfo.providers + ProviderInfo(IdentityProvider.GITHUB, "https://github.example", listOf("gh-client")),
        )
        device("Phone").use { phone ->
            phone.signUp()
            val store = settings(phone)
            assertEquals(listOf("Test"), store.state.value.identities.map { it.provider })
            assertTrue(store.state.value.canLinkIdentity)

            // The link screen's host only closes it (AppSession's); the store reads the identities again by itself.
            phone.account.linkIdentity(ProviderCredential(IdentityProvider.GITHUB, "gho_alice", nonce = null))
            runCurrent()
            val identities = store.state.value.identities
            assertEquals(listOf("Test", T.settings.account.provider.github), identities.map { it.provider })
            assertEquals(IdentityProvider.GITHUB, identities[1].providerId)
            assertFalse(store.state.value.canLinkIdentity, "nothing left to link")
        }
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
