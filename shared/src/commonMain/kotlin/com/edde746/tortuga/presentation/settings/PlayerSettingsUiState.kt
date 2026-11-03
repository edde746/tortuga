package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.playback.players.CustomPlayerKind

/**
 * Settings → Playback → Player ([PlayerSettingsStore]): which player plays on this device (`DeviceSetting.Player`,
 * never synced: installed apps differ per device). The Playback pane shows [row]; its press opens the page
 * ([PlayerSettingsStore.openPage], [pageOpen]), which lists the players to choose from.
 *
 * @param row Playback's row: "Player", the player chosen ("Tortuga", "VLC", "VLC (not found)") and "This device only".
 * @param pageOpen the page is shown (an overlay or sheet over the Playback pane, as the language editor).
 * @param title the page's title: "Player".
 * @param note one line under the title: what the choice does and that it is this device's only.
 * @param detecting the players on this device are being looked for (the page just opened): a spinner after [players].
 * @param players Tortuga first, then every player app detected on this device (Android: by label, with its icon), then
 *   the chosen one when it isn't found any more ([PlayerChoiceRow.missing]); one is [PlayerChoiceRow.selected].
 * @param customHeader "Custom players"; null where this platform takes none ([customPlayers] and [addCustomText] null
 *   too).
 * @param customPlayers the players the user added, each with its delete action.
 * @param addCustomText "Add custom player": [PlayerSettingsStore.openAddCustom].
 * @param customForm the Add custom player form while it is open.
 */
data class PlayerSettingsUiState(
    val row: PlayerSettingRow,
    val pageOpen: Boolean,
    val title: String,
    val note: String,
    val detecting: Boolean,
    val players: List<PlayerChoiceRow>,
    val customHeader: String?,
    val customPlayers: List<PlayerChoiceRow>,
    val addCustomText: String?,
    val customForm: CustomPlayerForm?,
)

/** Playback's Player row: [title] "Player", [valueLabel] "VLC", [note] "This device only". */
data class PlayerSettingRow(val title: String, val valueLabel: String, val note: String)

/**
 * A player to choose ([PlayerSettingsStore.select]).
 *
 * @param id `PlayerApp.TORTUGA_ID`, or a player app's `PlayerApp.id`.
 * @param name "Tortuga", "VLC"; a missing one "VLC (not found)".
 * @param detail one line under the name: what the player reports back ("Resumes where you stopped", "Opens the
 *   video only"), or a custom player's command or template; null for none.
 * @param missing chosen, but not found on this device any more: plays fail with "Play in Tortuga" on offer.
 * @param hasIcon the platform draws the app's icon beside it (Android: `TortugaPlatform.PlayerAppIcon`).
 * @param deleteText a custom player's "Delete My mpv": [PlayerSettingsStore.deleteCustom]; null for the others.
 */
data class PlayerChoiceRow(
    val id: String,
    val name: String,
    val detail: String?,
    val selected: Boolean,
    val missing: Boolean,
    val hasIcon: Boolean,
    val deleteText: String?,
)

/**
 * Adding a custom player ([PlayerSettingsStore.saveCustom]): a name and, as this platform's [kind] reads them, a
 * program and its arguments ([CustomPlayerKind.COMMAND]), a URL template ([CustomPlayerKind.URL_TEMPLATE]) or a
 * package name ([CustomPlayerKind.PACKAGE]).
 *
 * @param commandLabel "Program", "URL template" or "Package name"; [commandHint] an example of it.
 * @param argumentsLabel "Arguments" for [CustomPlayerKind.COMMAND] (prefilled with [argumentsDefault], `{url}`); null
 *   for the other kinds, which take no arguments.
 * @param placeholders one line explaining the placeholders: "{url} is the video's link, {title} its title, {start}
 *   where it starts in seconds, {subtitle} the first subtitle's link."; null for [CustomPlayerKind.PACKAGE].
 * @param error why the last save was refused ("Give the player a name.", "The arguments need {url}."); null otherwise.
 */
data class CustomPlayerForm(
    val title: String,
    val kind: CustomPlayerKind,
    val nameLabel: String,
    val nameHint: String,
    val commandLabel: String,
    val commandHint: String,
    val argumentsLabel: String?,
    val argumentsHint: String?,
    val argumentsDefault: String,
    val placeholders: String?,
    val error: String?,
    val saveText: String,
    val cancelText: String,
)
