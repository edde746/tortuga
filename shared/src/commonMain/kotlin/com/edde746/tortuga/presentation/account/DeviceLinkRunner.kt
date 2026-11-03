package com.edde746.tortuga.presentation.account

import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.DeviceLinkOutcome
import com.edde746.tortuga.sync.account.PendingDeviceLink
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.account.message
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs the new device's side of a device link for a screen: gets a code ([SyncAccount.startDeviceLink]), counts down
 * to its expiry, polls for the answer, and reports each change through [onChange] (null once cancelled). A linked
 * device's account turns [com.edde746.tortuga.sync.account.AccountState.SignedIn], which moves the app on by itself.
 *
 * @param serverUrl the server to sign in on.
 * @param discardUnsynced asked before an approval by another account deletes changes that never synced
 *   ([PendingDeviceLink.awaitResult]).
 */
internal class DeviceLinkRunner(
    private val scope: CoroutineScope,
    private val account: SyncAccount,
    private val clock: () -> Long,
    private val serverUrl: () -> ServerUrl,
    private val discardUnsynced: suspend (Long) -> Boolean,
    private val onChange: (DeviceLinkUiState?) -> Unit,
) {
    private var job: Job? = null
    private var pending: PendingDeviceLink? = null

    val active: Boolean get() = job?.isActive == true

    /** Starts a link with a new code, abandoning any running one. */
    fun start() {
        job?.cancel()
        onChange(DeviceLinkUiState.Starting(T.account.link.starting))
        job = scope.launch {
            val link = try {
                account.startDeviceLink(serverUrl())
            } catch (e: AccountException) {
                onChange(DeviceLinkUiState.Failed(e.error.message))
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                // The device signed in meanwhile; the gate is moving on.
                onChange(null)
                return@launch
            }
            pending = link
            val ticker = launch {
                while (true) {
                    val now = clock()
                    onChange(
                        DeviceLinkUiState.Waiting(
                            code = link.code,
                            qrPayload = link.qrPayload,
                            expiresText = T.account.link.expires(remainingText(link.expiresAtMillis, now)),
                            progress = T.account.link.waiting,
                        ),
                    )
                    delay(nextTickMillis(link.expiresAtMillis, now))
                }
            }
            val outcome = try {
                link.awaitResult(discardUnsynced)
            } catch (e: AccountException) {
                ticker.cancel()
                pending = null
                onChange(DeviceLinkUiState.Failed(if (e.error is AccountError.UnsyncedChanges) T.account.switchAccount.kept else e.error.message))
                return@launch
            }
            ticker.cancel()
            pending = null
            when (outcome) {
                // The account is ready now; the screen is about to be replaced.
                DeviceLinkOutcome.LINKED -> Unit
                DeviceLinkOutcome.REJECTED ->
                    onChange(DeviceLinkUiState.Failed(T.account.link.rejected))
                DeviceLinkOutcome.EXPIRED ->
                    onChange(DeviceLinkUiState.Failed(T.account.link.expired))
            }
        }
    }

    /** Abandons the link; its code expires on the server. */
    fun cancel() {
        job?.cancel()
        job = null
        pending = null
        onChange(null)
    }

    /** Polls at once (the app came back to the foreground). */
    fun pollNow() {
        pending?.pollNow()
    }
}
