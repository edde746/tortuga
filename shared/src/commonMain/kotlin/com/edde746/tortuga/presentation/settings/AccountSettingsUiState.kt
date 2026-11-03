package com.edde746.tortuga.presentation.settings

/**
 * The account settings screen: the sync server and status, the sign-ins linked to the account, its
 * devices, approving a new device's link, sign-out and account deletion.
 *
 * @param signedIn false once the device is signed out (the app leaves the screen); lists are then empty.
 * @param status what sync is doing, worded ("Up to date", "Offline. …").
 * @param lastSynced "Last synced 2 minutes ago", once a sync completed in this run.
 * @param canSyncNow Sync now takes a tap: no sync and no action is running.
 * @param server the sync server's host ("api.usetortuga.app", or a self-hosted one's); null when signed out: no row.
 * @param loading the account's sign-ins and devices (and once, the server's sign-in providers) are being read.
 * @param loadError why reading them failed; `refresh` tries again.
 * @param canLinkIdentity this device can add a sign-in provider the server offers and the account doesn't use yet.
 * @param linkApproval approving a new device's link, while open (`startLinkApproval` to `closeLinkApproval`).
 * @param working an action is running: disable the actions.
 * @param notice what an action did or why it failed ("Pixel 7 is signed out."), until dismissed or the next action.
 */
data class AccountSettingsUiState(
    val title: String,
    val signedIn: Boolean,
    val statusLabel: String,
    val status: String,
    val lastSynced: String?,
    val syncing: Boolean,
    val syncNowLabel: String,
    val canSyncNow: Boolean,
    val serverLabel: String,
    val server: String?,
    val loading: Boolean,
    val loadError: String?,
    val identitiesHeader: String,
    val identities: List<AccountIdentityRow>,
    val canLinkIdentity: Boolean,
    val linkIdentityLabel: String,
    val devicesHeader: String,
    val devices: List<AccountDeviceRow>,
    val approveLinkLabel: String,
    val linkApproval: LinkApprovalUiState?,
    val signOutLabel: String,
    val deleteAccount: AccountActionRow,
    val dialog: AccountConfirmDialog?,
    val working: Boolean,
    val notice: String?,
)

/**
 * A sign-in linked to the account. [id] is what `unlinkIdentity` takes.
 *
 * @param providerId one of [com.edde746.tortuga.sync.api.IdentityProvider]: which mark the row shows.
 * @param provider the provider's name ("Apple", "GitHub", the server's own provider's).
 * @param detail the address the provider shared, if any.
 * @param canUnlink false for the account's only sign-in.
 * @param unlinkLabel its remove button's name: "Remove Apple sign-in".
 */
data class AccountIdentityRow(
    val id: String,
    val providerId: String,
    val provider: String,
    val detail: String?,
    val canUnlink: Boolean,
    val unlinkLabel: String,
)

/**
 * A signed-in device (a session). [id] is what `revokeDevice` takes.
 *
 * @param detail "Android TV · Active 3 days ago".
 * @param current this device; [currentLabel] says so ("This device").
 * @param revokeLabel its sign-out button's name, with the device's: "Sign out device: Pixel 7".
 */
data class AccountDeviceRow(
    val id: String,
    val name: String,
    val detail: String,
    val current: Boolean,
    val currentLabel: String?,
    val revokeLabel: String,
)

/** An action with an explanation, e.g. "Delete account". */
data class AccountActionRow(val title: String, val subtitle: String)

/** A confirmation the user answers with `confirmDialog` or `dismissDialog`. */
data class AccountConfirmDialog(
    val action: AccountSettingsAction,
    val title: String,
    val message: String,
    val confirmLabel: String,
    val cancelLabel: String,
    val destructive: Boolean,
)

enum class AccountSettingsAction { SIGN_OUT, DELETE_ACCOUNT, REVOKE_DEVICE, UNLINK_IDENTITY }

/**
 * Approving a new device's link on this signed-in device: the code typed (or scanned as a QR code), checked with the
 * server, then the requesting device shown for confirmation. Labels shown across the steps are here; each step's own
 * content is in [step].
 */
data class LinkApprovalUiState(
    val step: LinkApprovalStep,
    val title: String,
    val instructions: String,
    val codeHint: String,
    val submitLabel: String,
    val approveLabel: String,
    val rejectLabel: String,
)

/**
 * Where a link approval is: `startLinkApproval` → [EnterCode] → `submitLinkCode` (or `submitScannedLink` from any step)
 * → [Checking] → [Confirm] → `approveLink` or `rejectLink` → [Approving] / [Rejecting] → [Approved] / [Rejected]. A
 * failed check or answer goes back to [EnterCode] with its error; a link that expires while it waits for confirmation
 * does too.
 */
sealed interface LinkApprovalStep {
    /**
     * The code field.
     *
     * @param code the code as typed so far, upper-cased and grouped ("ABCD-EF").
     * @param canSubmit [code] is complete.
     * @param error why the last code or answer failed, until the code changes.
     */
    data class EnterCode(val code: String, val canSubmit: Boolean, val error: String?) : LinkApprovalStep

    /** The code is being checked with the server. */
    data object Checking : LinkApprovalStep

    /**
     * The requesting device, for a yes or no.
     *
     * @param code the link's code.
     * @param question "Link “Living room TV”?".
     * @param consequence what approving does (signs the device in to this account).
     */
    data class Confirm(
        val code: String,
        val deviceName: String,
        val devicePlatform: String,
        val question: String,
        val consequence: String,
    ) : LinkApprovalStep

    data object Approving : LinkApprovalStep

    data object Rejecting : LinkApprovalStep

    /** The device is linked; [result] says so. */
    data class Approved(val result: String) : LinkApprovalStep

    /** The device was turned away; [result] says so. */
    data class Rejected(val result: String) : LinkApprovalStep
}

/** What the account settings ask of the app around them; called on the main thread. */
interface AccountSettingsHost {
    /** Open the flow that links another sign-in (Apple, Google, the server's own provider) to the account. */
    fun linkIdentity()

    /**
     * The account was deleted and this device signed out. Show [message] where the app goes next: it says what else
     * the user has to do, if anything (removing Tortuga from their Apple ID's Sign in with Apple list).
     */
    fun accountDeleted(message: String)
}
