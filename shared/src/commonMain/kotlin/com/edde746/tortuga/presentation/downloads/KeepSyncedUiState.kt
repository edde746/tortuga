package com.edde746.tortuga.presentation.downloads

/**
 * The Keep synced screen ([com.edde746.tortuga.presentation.navigation.Route.KeepSynced]): the profile's Keep synced
 * rules on this device. Every text is ready to show.
 *
 * @param title "Keep synced".
 * @param note what a rule does, over the rules; null while there are none.
 * @param body what the list under [note] shows.
 * @param notice an action on a rule that failed ("Couldn't change Keep synced. Try again."), shown once:
 *   [KeepSyncedStore.dismissNotice] after it has shown.
 * @param stopDialog the question [KeepSyncedStore.stop] asks before a rule goes; null otherwise.
 */
data class KeepSyncedUiState(
    val title: String,
    val note: String?,
    val body: KeepSyncedBody,
    val notice: String? = null,
    val stopDialog: KeepSyncedStopDialog? = null,
)

/**
 * What the Keep synced list shows: a spinner until the rules have been read ([Loading]); then the rules ([Rules]), or
 * that there are none ([Empty]).
 */
sealed interface KeepSyncedBody {
    data object Loading : KeepSyncedBody

    data class Empty(val text: String) : KeepSyncedBody

    /** [rules]: each rule, by the series' name; never none. */
    data class Rules(val rules: List<KeepSyncedRow>) : KeepSyncedBody
}

/**
 * One rule.
 *
 * @param metaId the series: what [KeepSyncedStore]'s actions take, and a list key.
 * @param title the series' name.
 * @param poster its poster: a `file://` URL once saved on the device, else the addon's.
 * @param countText "Keep 3 unwatched episodes", "Keep every unwatched episode".
 * @param statusText where it is: "Paused", "Waiting for Wi-Fi", "Waiting for a connection", "Downloading 2 episodes",
 *   "1 episode failed", "3 episodes downloaded", "No episodes downloaded".
 * @param paused [KeepSyncedStore.resume] applies, not [KeepSyncedStore.pause].
 * @param pauseLabel the pause or resume button's label: "Pause Breaking Bad", "Resume Breaking Bad".
 * @param stopLabel the stop button's label: "Stop keeping Breaking Bad synced".
 */
data class KeepSyncedRow(
    val metaId: String,
    val title: String,
    val poster: String?,
    val countText: String,
    val statusText: String,
    val paused: Boolean,
    val pauseLabel: String,
    val stopLabel: String,
)

/**
 * The question before a rule stops: "Stop keeping Breaking Bad synced?" and, when the rule added episodes to the
 * profile's downloads, "3 episodes in Downloads came from Keep synced. Delete them too?" answered with [keepLabel] (stop,
 * the downloads stay), [deleteLabel] (stop and delete them, destructive) or [cancelLabel]. Without such episodes,
 * "No more episodes will download." with [keepLabel] "Stop" and no [deleteLabel].
 */
data class KeepSyncedStopDialog(
    val title: String,
    val message: String,
    val keepLabel: String,
    val deleteLabel: String?,
    val cancelLabel: String,
)
