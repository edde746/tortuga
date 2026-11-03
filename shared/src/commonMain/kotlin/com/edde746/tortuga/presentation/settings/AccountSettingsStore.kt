package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.AccountState
import com.edde746.tortuga.sync.account.DeviceLinkQr
import com.edde746.tortuga.sync.account.IncomingDeviceLink
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.account.message
import com.edde746.tortuga.sync.account.signin.ProviderFlows
import com.edde746.tortuga.sync.api.AccountInfo
import com.edde746.tortuga.sync.api.DeviceInfo
import com.edde746.tortuga.sync.api.LINK_CODE_LENGTH
import com.edde746.tortuga.sync.api.ServerInfo
import com.edde746.tortuga.sync.api.formatLinkCode
import com.edde746.tortuga.sync.api.typedLinkCode
import com.edde746.tortuga.sync.engine.SyncEngine
import com.edde746.tortuga.sync.engine.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The account settings screen's store over [account] and its sync [engine]: the sync status, the
 * linked sign-ins (unlinking; linking goes through [AccountSettingsHost.linkIdentity], offered while this device can
 * add a method the account lacks: [flows]), the devices (this one marked; revoking), approving a new device's link by
 * typed code or scanned QR code, sign-out and account deletion with Apple's and GitHub's revocation outcomes.
 * Destructive actions ask first ([AccountSettingsUiState.dialog]). Signing this device out (or revoking it) pushes
 * first; changes that still haven't synced then ask again, saying how many are deleted, and stay unless the user
 * confirms. A deletion, once confirmed, runs to its end and reports through the host even though signing out
 * releases this store.
 *
 * Call everything on the main thread; [release] ends it.
 */
class AccountSettingsStore internal constructor(
    private val account: SyncAccount,
    private val engine: SyncEngine,
    private val flows: ProviderFlows,
    private val host: AccountSettingsHost,
    private val now: () -> Long,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var accountState: AccountState = account.state.value
    private var syncStatus: SyncStatus = engine.status.value
    private var info: AccountInfo? = null
    private var serverInfo: ServerInfo? = null
    private var devices: List<DeviceInfo> = emptyList()
    private var loading = false
    private var loadError: String? = null
    private var refreshJob: Job? = null

    private var dialog: PendingDialog? = null
    private var working = false
    private var notice: String? = null

    /** The link approval's step while it is open. */
    private var linkStep: LinkApprovalStep? = null
    private var incoming: IncomingDeviceLink? = null
    private var linkJob: Job? = null
    private var expiryJob: Job? = null

    /** When the first "… ago" text shown next changes ([followAgo]), and the publish waiting for it. */
    private var agoChangesAt: Long? = null
    private var agoJob: Job? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<AccountSettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            var previous: AccountState? = null
            account.state.collect { state ->
                accountState = state
                if (state is AccountState.SignedOut) {
                    if (linkStep != null) closeLink()
                    refreshJob?.cancel()
                    info = null
                    devices = emptyList()
                    loading = false
                } else if (previous.let { it == null || !sameAccount(it, state) }) {
                    refresh()
                }
                previous = state
                publish()
            }
        }
        scope.launch {
            engine.status.collect {
                syncStatus = it
                publish()
            }
        }
        // A sign-in linked from any screen (the link overlay over Settings, on every platform) shows here at once.
        scope.launch { account.identityLinked.collect { refresh() } }
    }

    /** Reads the account's sign-ins and devices again: on resume, to retry, and by itself after any identity link. */
    fun refresh() {
        val server = serverUrl() ?: return
        refreshJob?.cancel()
        loading = true
        loadError = null
        publish()
        refreshJob = scope.launch {
            try {
                coroutineScope {
                    val infoCall = async { account.accountInfo() }
                    val devicesCall = async { account.devices() }
                    // The server's providers name the sign-ins and decide which this device can add; read once.
                    if (serverInfo == null) serverInfo = account.serverInfo(server)
                    info = infoCall.await()
                    devices = devicesCall.await()
                }
            } catch (e: AccountException) {
                Log.i(TAG) { "Reading the account failed: ${e.error}" }
                loadError = e.error.message
            }
            loading = false
            publish()
        }
    }

    /** Syncs now; [AccountSettingsUiState.status] follows. */
    fun syncNow() {
        scope.launch { engine.syncNow() }
    }

    fun linkIdentity() {
        if (accountState is AccountState.SignedOut) return
        host.linkIdentity()
    }

    fun unlinkIdentity(identityId: String) {
        if (info?.identities?.any { it.identityId == identityId } != true) return
        ask(PendingDialog(AccountSettingsAction.UNLINK_IDENTITY, identityId))
    }

    fun revokeDevice(deviceId: String) {
        if (devices.none { it.deviceId == deviceId }) return
        ask(PendingDialog(AccountSettingsAction.REVOKE_DEVICE, deviceId))
    }

    fun signOut() {
        if (accountState is AccountState.SignedOut) return
        ask(PendingDialog(AccountSettingsAction.SIGN_OUT, null))
    }

    fun deleteAccount() {
        if (accountState is AccountState.SignedOut) return
        ask(PendingDialog(AccountSettingsAction.DELETE_ACCOUNT, null))
    }

    fun dismissDialog() {
        if (dialog == null) return
        dialog = null
        publish()
    }

    /** Does what [AccountSettingsUiState.dialog] asked about. */
    fun confirmDialog() {
        val confirmed = dialog ?: return
        if (working) return
        dialog = null
        perform {
            when (confirmed.action) {
                AccountSettingsAction.SIGN_OUT -> leave(confirmed) { discard -> account.signOut(discard) }
                AccountSettingsAction.DELETE_ACCOUNT -> withContext(NonCancellable) {
                    // Signing out closes the app and releases this store, and a session the server ends meanwhile
                    // (the sync engine's next request) would too: the deletion, its local wipe and its notice still
                    // run to their end.
                    val deletion = account.deleteAccount()
                    notice = deletion.text
                    host.accountDeleted(deletion.text)
                }
                AccountSettingsAction.REVOKE_DEVICE -> {
                    val id = confirmed.target ?: return@perform
                    val name = devices.firstOrNull { it.deviceId == id }?.name.orEmpty()
                    val own = devices.firstOrNull { it.deviceId == id }?.current == true
                    if (own) {
                        leave(confirmed) { discard -> account.revokeDevice(id, discard) }
                    } else {
                        account.revokeDevice(id)
                        devices = devices.filterNot { it.deviceId == id }
                        notice = T.settings.account.deviceRemoved(isolate(name))
                    }
                }
                AccountSettingsAction.UNLINK_IDENTITY -> {
                    val id = confirmed.target ?: return@perform
                    val provider = info?.identities?.firstOrNull { it.identityId == id }?.provider
                    info = account.unlinkIdentity(id)
                    notice = T.settings.account.identityRemoved(isolate(AccountText.provider(provider.orEmpty(), serverInfo)))
                }
            }
        }
    }

    /**
     * Signs this device out through [signOut]: pushes first, unless the user already chose to delete what hasn't synced
     * ([PendingDialog.unsynced]); changes that still haven't synced ask again, with their count, and nothing changes.
     */
    private suspend fun leave(confirmed: PendingDialog, signOut: suspend (discardUnsynced: Boolean) -> Unit) {
        val discard = confirmed.unsynced != null
        if (!discard) engine.pushNow()
        try {
            signOut(discard)
        } catch (e: AccountException) {
            val unsynced = (e.error as? AccountError.UnsyncedChanges)?.count ?: throw e
            dialog = PendingDialog(confirmed.action, confirmed.target, unsynced)
        }
    }

    fun dismissNotice() {
        if (notice == null) return
        notice = null
        publish()
    }

    // ---- Approving a device link ---------------------------------------------------------------------------------

    fun startLinkApproval() {
        if (accountState !is AccountState.SignedIn) return
        closeLink()
        linkStep = enterCode("", error = null)
        publish()
    }

    /** The code field changed: kept as [typedLinkCode] keeps it, shown as [formatLinkCode] shows it. */
    fun updateLinkCode(text: String) {
        if (linkStep !is LinkApprovalStep.EnterCode) return
        linkStep = enterCode(formatLinkCode(typedLinkCode(text)), error = null)
        publish()
    }

    fun submitLinkCode() {
        val step = linkStep as? LinkApprovalStep.EnterCode ?: return
        if (step.canSubmit) claim { account.claimDeviceLink(step.code) }
    }

    /**
     * A device link's QR code scanned with a camera: the `tortuga://link?…` link the OS opened the app with
     * ([com.edde746.tortuga.app.AppSession.openIncomingLink]). Checks it straight away, from any step; nothing without a
     * signed-in account.
     */
    fun submitScannedLink(qr: DeviceLinkQr) {
        if (accountState !is AccountState.SignedIn) return
        claim { account.claimDeviceLink(qr) }
    }

    fun approveLink() = answer(approve = true)

    fun rejectLink() = answer(approve = false)

    /** Leaves the link flow; a link waiting for confirmation stays unanswered until it expires. */
    fun closeLinkApproval() {
        closeLink()
        publish()
    }

    fun release() {
        incoming = null
        scope.cancel()
    }

    private fun claim(claimed: suspend () -> IncomingDeviceLink) {
        closeLink()
        linkStep = LinkApprovalStep.Checking
        publish()
        linkJob = scope.launch {
            try {
                val link = claimed()
                incoming = link
                val text = T.settings.account.link
                linkStep = LinkApprovalStep.Confirm(
                    code = link.code,
                    deviceName = link.deviceName,
                    devicePlatform = AccountText.platform(link.platform),
                    question = text.question(isolate(link.deviceName)),
                    consequence = text.consequence,
                )
                expiryJob = scope.launch {
                    delay((link.expiresAtMillis - now()).coerceAtLeast(0))
                    if (incoming === link && linkStep is LinkApprovalStep.Confirm) {
                        incoming = null
                        linkStep = enterCode("", T.settings.account.link.expired)
                        publish()
                    }
                }
            } catch (e: AccountException) {
                linkStep = enterCode("", e.error.message)
            }
            publish()
        }
    }

    private fun answer(approve: Boolean) {
        val link = incoming ?: return
        if (linkStep !is LinkApprovalStep.Confirm) return
        expiryJob?.cancel()
        linkStep = if (approve) LinkApprovalStep.Approving else LinkApprovalStep.Rejecting
        publish()
        linkJob = scope.launch {
            try {
                if (approve) link.approve() else link.reject()
                val text = T.settings.account.link
                val device = isolate(link.deviceName)
                linkStep = if (approve) LinkApprovalStep.Approved(text.approved(device)) else LinkApprovalStep.Rejected(text.rejected(device))
                if (approve) refresh()
            } catch (e: AccountException) {
                linkStep = enterCode("", e.error.message)
            }
            incoming = null
            publish()
        }
    }

    private fun closeLink() {
        linkJob?.cancel()
        expiryJob?.cancel()
        incoming = null
        linkStep = null
    }

    private fun enterCode(code: String, error: String?) =
        LinkApprovalStep.EnterCode(code, canSubmit = code.count { it != '-' } == LINK_CODE_LENGTH, error = error)

    private fun ask(pending: PendingDialog) {
        if (working) return
        notice = null
        dialog = pending
        publish()
    }

    /** Runs an account action, one at a time; its failure becomes the [notice]. */
    private fun perform(action: suspend () -> Unit) {
        if (working) return
        working = true
        notice = null
        publish()
        scope.launch {
            try {
                action()
            } catch (e: AccountException) {
                notice = e.error.message
            } finally {
                working = false
                publish()
            }
        }
    }

    private fun serverUrl(): ServerUrl? = (accountState as? AccountState.SignedIn)?.serverUrl

    private fun sameAccount(a: AccountState, b: AccountState): Boolean =
        a is AccountState.SignedIn && b is AccountState.SignedIn && a.accountId == b.accountId

    private fun publish() {
        mutableState.value = buildState()
        followAgo()
    }

    /** Publishes again when the first "… ago" text shown (the last sync, a device's last activity) next changes. */
    private fun followAgo() {
        val time = now()
        val shown = listOfNotNull(syncStatus.lastSyncedAtMillis) + devices.filterNot { it.current }.map { it.lastSeenAtMillis }
        val next = shown.minOfOrNull { AccountText.agoChangesAt(it, time) }
        if (next == agoChangesAt) return
        agoChangesAt = next
        agoJob?.cancel()
        agoJob = next?.let {
            scope.launch {
                delay(it - time)
                agoChangesAt = null
                publish()
            }
        }
    }

    private fun buildState(): AccountSettingsUiState {
        val text = T.settings.account
        val state = accountState
        val signedIn = state is AccountState.SignedIn
        val server = serverUrl()
        val identities = info?.identities.orEmpty()
        val linkedProviders = identities.map { it.provider }.toSet()
        val time = now()
        return AccountSettingsUiState(
            title = text.title,
            signedIn = signedIn,
            statusLabel = text.syncLabel,
            status = syncStatus.text,
            lastSynced = syncStatus.lastSyncedAtMillis?.let { text.lastSynced(AccountText.ago(it, time)) },
            syncing = syncStatus.kind == SyncStatus.Kind.Syncing,
            syncNowLabel = text.syncNow,
            canSyncNow = !working && syncStatus.kind != SyncStatus.Kind.Syncing,
            serverLabel = text.serverLabel,
            server = server?.let { AccountText.server(it.value) }?.takeIf { it.isNotEmpty() },
            loading = loading,
            loadError = loadError,
            identitiesHeader = text.identitiesHeader,
            identities = identities.map {
                val provider = AccountText.provider(it.provider, serverInfo)
                AccountIdentityRow(
                    id = it.identityId,
                    providerId = it.provider,
                    provider = provider,
                    detail = it.email,
                    canUnlink = identities.size > 1,
                    unlinkLabel = text.unlink.action(isolate(provider)),
                )
            },
            canLinkIdentity = server != null && info != null &&
                serverInfo?.let { flows.options(server, it, exclude = linkedProviders).isNotEmpty() } == true,
            linkIdentityLabel = text.linkIdentity,
            devicesHeader = text.devicesHeader,
            devices = devices.sortedByDescending { it.current }.map { device ->
                val active = if (device.current) text.activeNow else text.lastActive(AccountText.ago(device.lastSeenAtMillis, time))
                AccountDeviceRow(
                    id = device.deviceId,
                    name = device.name,
                    detail = text.deviceDetail(isolate(AccountText.platform(device.platform)), active),
                    current = device.current,
                    currentLabel = if (device.current) text.thisDevice else null,
                    revokeLabel = text.revoke.confirmNamed(isolate(device.name)),
                )
            },
            approveLinkLabel = text.link.start,
            linkApproval = linkStep?.let(::linkState),
            signOutLabel = text.signOut.action,
            deleteAccount = AccountActionRow(text.delete.action, text.delete.explanation),
            dialog = dialog?.let(::dialogState),
            working = working,
            notice = notice,
        )
    }

    private fun linkState(step: LinkApprovalStep): LinkApprovalUiState {
        val text = T.settings.account.link
        return LinkApprovalUiState(
            step = step,
            title = text.title,
            instructions = text.instructions,
            codeHint = text.codeHint,
            submitLabel = text.submit,
            approveLabel = text.approve,
            rejectLabel = text.reject,
        )
    }

    private fun dialogState(pending: PendingDialog): AccountConfirmDialog {
        val text = T.settings.account
        pending.unsynced?.let { unsynced ->
            return AccountConfirmDialog(
                pending.action, text.unsynced.title, text.unsynced.message(unsynced.toInt()), text.unsynced.confirm, text.cancel, destructive = true,
            )
        }
        return when (pending.action) {
            AccountSettingsAction.SIGN_OUT -> AccountConfirmDialog(
                pending.action, text.signOut.title, text.signOut.message, text.signOut.confirm, text.cancel, destructive = false,
            )
            AccountSettingsAction.DELETE_ACCOUNT -> AccountConfirmDialog(
                pending.action, text.delete.title, text.delete.message, text.delete.confirm, text.cancel, destructive = true,
            )
            AccountSettingsAction.REVOKE_DEVICE -> {
                val device = devices.firstOrNull { it.deviceId == pending.target }
                val name = isolate(device?.name.orEmpty())
                AccountConfirmDialog(
                    pending.action,
                    text.revoke.title(name),
                    if (device?.current == true) text.revoke.messageThisDevice else text.revoke.message(name),
                    text.revoke.confirm,
                    text.cancel,
                    destructive = true,
                )
            }
            AccountSettingsAction.UNLINK_IDENTITY -> {
                val identity = info?.identities?.firstOrNull { it.identityId == pending.target }
                val provider = isolate(AccountText.provider(identity?.provider.orEmpty(), serverInfo))
                AccountConfirmDialog(
                    pending.action, text.unlink.title(provider), text.unlink.message(provider), text.unlink.confirm, text.cancel, destructive = true,
                )
            }
        }
    }

    /** [unsynced]: the second question of a sign-out, how many changes that haven't synced it deletes. */
    private class PendingDialog(val action: AccountSettingsAction, val target: String?, val unsynced: Long? = null)

    private companion object {
        const val TAG = "accountSettings"
    }
}
