package com.edde746.tortuga.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.PlayerMenu
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.presentation.player.DelayState
import com.edde746.tortuga.presentation.player.OptionChoice
import com.edde746.tortuga.presentation.player.PlayerOption
import com.edde746.tortuga.presentation.player.PlayerOptionId
import com.edde746.tortuga.presentation.player.PlayerUiState
import com.edde746.tortuga.presentation.player.SubtitleLanguage
import com.edde746.tortuga.presentation.player.TrackItem
import com.edde746.tortuga.presentation.probeName
import com.edde746.tortuga.presentation.watch.ConfirmAction
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.app.LocalTortugaPlatform
import com.edde746.tortuga.ui.components.Badge
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.RowSwitch
import com.edde746.tortuga.ui.components.SheetOption
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaSheet
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.TortugaToggleSurface
import com.edde746.tortuga.ui.components.menuGestures
import com.edde746.tortuga.ui.focus.focusReach
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection
import com.edde746.tortuga.ui.watch.ConfirmQuestion
import com.edde746.tortuga.ui.watch.RoomActions
import kotlin.math.min

/** Panels over bright or HDR video stay opaque: a translucent one loses its text. */
internal val PanelShape = RoundedCornerShape(20.dp)

/**
 * [menu] in a [TortugaSheet]: a bottom sheet on phones and tablets, a side panel on TV. The selected entry takes focus
 * as it opens, so a remote continues from there; a tap outside or a drag down close it, and so does Back, except on a
 * page, which Back leaves for the menu it opened from (an option's page for the options, a subtitle language for More
 * subtitles, More subtitles for the subtitles: the store's Back; phones and tablets also show a back button beside the
 * page's title). A menu that follows in the same sheet (a delay control opened from a track menu, a page, the menu it
 * came from) takes focus on its first button, its selected entry (else its first), or the entry it came back from, as
 * it replaces the one before ([FocusOnChange]).
 * Once the store has closed the menu ([visible] false) the sheet slides away showing it as it was left (the pick
 * checked). [room] is what the room panel's controls do ([PlayerMenu.WATCH_TOGETHER]).
 */
@Composable
internal fun PlayerMenuSheet(
    menu: PlayerMenu,
    visible: Boolean,
    state: PlayerUiState,
    room: RoomActions,
    onCommand: (PlayerCommand) -> Unit,
) {
    val initial = remember(menu) { FocusRequester() }
    val pages = remember { PageMemory() }
    if (visible) pages.show(menu, state) else pages.forget()
    val title = pages.title
    // TV remotes have Back; touch screens get a button for it beside the page's title.
    val pageHeader = menu.back != null && LocalWindowLayout.current.formFactor != FormFactor.TV
    TortugaSheet(
        onDismiss = { onCommand(PlayerCommand.CloseMenu) },
        title = if (pageHeader) null else title,
        visible = visible,
        initialFocus = initial,
        probe = "player.menu",
        onBack = { onCommand(if (menu.back != null) PlayerCommand.Back else PlayerCommand.CloseMenu) },
        label = title,
    ) {
        // The sheet focuses the menu it opens with; one that follows in it takes focus here, in the sheet's composition.
        FocusOnChange(menu, initial, onOpen = false)
        if (pageHeader) PageHeader(title, MaterialTheme.typography.titleLarge, onCommand)
        PlayerMenuContent(menu, state, room, initial, pages, onCommand, Modifier.weight(1f, fill = false))
    }
}

/**
 * Focus to [initial] in the change that shows [menu] in place of another, and as the first shows with [onOpen]: the
 * entry that had focus went with the menu before, and until focus is placed nothing has it, so a key pressed or
 * released meanwhile reaches nothing. An effect or the next frame would place it after the input already waiting (the
 * release of the Escape that went back to the options, so the player took Escape for still held). An empty page has
 * nothing to focus.
 */
@Composable
private fun FocusOnChange(menu: PlayerMenu, initial: FocusRequester, onOpen: Boolean) {
    val shown = remember { arrayOfNulls<PlayerMenu>(1) }
    SideEffect {
        if (shown[0] != menu && (onOpen || shown[0] != null)) initial.requestFocus()
        shown[0] = menu
    }
}

/**
 * The open menu as a panel of its own (desktop, anchored above its button by the player): its title (a page with a
 * back button to the menu it opened from) and entries. The selected entry takes focus as it opens, so the arrow keys
 * continue from there. A screen reader announces it as a pane titled by the menu (the player hides its controls from
 * it meanwhile).
 */
@Composable
internal fun PlayerMenuPanel(
    menu: PlayerMenu,
    state: PlayerUiState,
    room: RoomActions,
    onCommand: (PlayerCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initial = remember(menu) { FocusRequester() }
    val pages = remember { PageMemory() }
    pages.show(menu, state)
    val title = pages.title
    Surface(
        modifier
            .width(340.dp)
            .semantics {
                paneTitle = title
                isTraversalGroup = true
            },
        shape = PanelShape,
        color = TortugaColors.Surface,
        contentColor = TortugaColors.OnVideo,
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            if (menu.back != null) {
                PageHeader(title, MaterialTheme.typography.titleSmall, onCommand)
            } else {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TortugaColors.OnVideoMuted,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            PlayerMenuContent(menu, state, room, initial, pages, onCommand, Modifier.weight(1f, fill = false))
        }
    }
    FocusOnChange(menu, initial, onOpen = true)
}

/**
 * What the menu showed before, while it stays open: back on the menu a page opened from, focus returns to the entry
 * that opened it (an option, More subtitles, a language). It keeps the title and the subtitle language of the menu that
 * showed last, which a sheet sliding away goes on showing.
 */
private class PageMemory {
    private var shown: PlayerMenu? = null

    /** The menu shown before the one showing. */
    var from: PlayerMenu? = null
        private set

    /** The title of the menu as it showed last ([PlayerUiState.menuTitle]). */
    var title: String = ""
        private set

    /** The language of [PlayerMenu.SUBTITLE_LANGUAGE]'s page as it showed last. */
    var language: SubtitleLanguage? = null
        private set

    fun show(menu: PlayerMenu, state: PlayerUiState) {
        if (menu != shown) {
            from = shown
            shown = menu
        }
        title = state.menuTitle(menu)
        if (menu == PlayerMenu.SUBTITLE_LANGUAGE) language = state.subtitleLanguage
    }

    /** The menu closed: the next one opens from none. */
    fun forget() {
        shown = null
        from = null
    }
}

/** A page's title with a back button to the menu it opened from (the store's Back). */
@Composable
private fun PageHeader(title: String, style: TextStyle, onCommand: (PlayerCommand) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PlayerButton(TortugaIcons.ArrowBack, T.nav.back, onClick = { onCommand(PlayerCommand.Back) }, probe = "player.menu.back")
        Text(title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A track list (the subtitles' with More subtitles, each with the matching delay as its last entry), the speeds, a
 * delay control, the languages of More subtitles, one language's subtitles, the options, an option's page or the room
 * panel.
 */
@Composable
private fun PlayerMenuContent(
    menu: PlayerMenu,
    state: PlayerUiState,
    room: RoomActions,
    initial: FocusRequester,
    pages: PageMemory,
    onCommand: (PlayerCommand) -> Unit,
    modifier: Modifier,
) {
    when (menu) {
        PlayerMenu.AUDIO -> TrackList(state.audioTracks, TrackKind.AUDIO, initial, focusSelected = true, onCommand, modifier) {
            DelayEntry(state.audioDelay, onCommand)
        }
        PlayerMenu.SUBTITLES -> {
            // Back from More subtitles, focus returns to its entry.
            val fromMore = pages.from == PlayerMenu.MORE_SUBTITLES
            TrackList(state.subtitleTracks, TrackKind.SUBTITLE, initial, focusSelected = !fromMore, onCommand, modifier) {
                if (state.moreSubtitles.isNotEmpty()) {
                    PageEntry(
                        TortugaIcons.Subtitles,
                        T.player.menu.moreSubtitles,
                        state.moreSubtitlesCount,
                        Modifier.layoutProbe("player.menu.more").then(if (fromMore) Modifier.focusRequester(initial) else Modifier),
                    ) { onCommand(PlayerCommand.OpenMenu(PlayerMenu.MORE_SUBTITLES)) }
                }
                DelayEntry(state.subtitleDelay, onCommand)
            }
        }
        PlayerMenu.SPEED -> MenuList(modifier) {
            state.speeds.forEachIndexed { index, option ->
                SheetOption(
                    option.label,
                    onClick = { onCommand(PlayerCommand.SetSpeed(option.speed)) },
                    modifier = Modifier.menuItem(index, option.selected, initial),
                    selected = option.selected,
                )
            }
        }
        PlayerMenu.SUBTITLE_DELAY -> DelayControl(state.subtitleDelay, initial, onCommand)
        PlayerMenu.AUDIO_DELAY -> DelayControl(state.audioDelay, initial, onCommand)
        PlayerMenu.MORE_SUBTITLES -> {
            val backFrom = if (pages.from == PlayerMenu.SUBTITLE_LANGUAGE) pages.language?.key else null
            LanguageList(state.moreSubtitles, backFrom, initial, onCommand, modifier)
        }
        PlayerMenu.SUBTITLE_LANGUAGE -> ChoiceList(pages.language?.subtitles.orEmpty(), state.selectedEntry, initial, onCommand, modifier, contentLabels = true)
        PlayerMenu.OPTIONS -> OptionList(state.options, state.infoVisible, state.lockLabel, pages.from, initial, onCommand, modifier)
        PlayerMenu.VIDEO_FIT, PlayerMenu.ZOOM, PlayerMenu.SLEEP_TIMER, PlayerMenu.AUDIO_CHANNELS, PlayerMenu.AUDIO_OUTPUT -> ChoiceList(
            state.openOption?.choices.orEmpty(),
            state.selectedEntry,
            initial,
            onCommand,
            modifier,
            // The outputs are the devices' own names (one someone gave a headset), "System output" aside.
            contentLabels = menu == PlayerMenu.AUDIO_OUTPUT,
        )
        PlayerMenu.SHADERS -> ShaderList(state.openOption, state.selectedEntry, initial, onCommand, modifier)
        PlayerMenu.WATCH_TOGETHER -> WatchPanelContent(state.watch, room, initial, onCommand, modifier)
    }
}

/**
 * The player's options, one row each: first Playback info, a switch that shows or hides the playback info card and
 * closes the menu, and on a touch screen Lock screen ([lockLabel]), which locks it; then an option with a page shows
 * its value and opens the page; a switch flips in place. A value away from its default is marked
 * ([PlayerOption.adjusted]). Focus starts on the option whose page [lastPage] was (back from it), else on Playback info.
 */
@Composable
private fun OptionList(
    options: List<PlayerOption>,
    infoVisible: Boolean,
    lockLabel: String?,
    lastPage: PlayerMenu?,
    initial: FocusRequester,
    onCommand: (PlayerCommand) -> Unit,
    modifier: Modifier,
) {
    val focused = options.indexOfFirst { it.page != null && it.page == lastPage }
    MenuList(modifier) {
        OptionSwitch(
            TortugaIcons.Info,
            T.player.info.title,
            infoVisible,
            adjustedProbe = null,
            onToggle = {
                onCommand(PlayerCommand.ToggleInfo)
                onCommand(PlayerCommand.CloseMenu)
            },
            Modifier.layoutProbe("player.option.info").then(if (focused < 0) Modifier.focusRequester(initial) else Modifier),
        )
        lockLabel?.let { label ->
            // Locking closes the menu with the controls.
            OptionAction(TortugaIcons.Lock, label, onClick = { onCommand(PlayerCommand.LockScreen) }, Modifier.layoutProbe("player.option.lock"))
        }
        options.forEachIndexed { index, option ->
            val rowModifier = Modifier
                .layoutProbe("player.option.${option.id.probeName}")
                .then(if (index == focused) Modifier.focusRequester(initial) else Modifier)
            val page = option.page
            val toggle = option.toggle
            when {
                page != null -> OptionRow(option, onClick = { onCommand(PlayerCommand.OpenMenu(page)) }, rowModifier)
                toggle != null -> OptionSwitch(
                    option.id.icon,
                    option.label,
                    option.on == true,
                    adjustedProbe = if (option.adjusted) "player.option.${option.id.probeName}.adjusted" else null,
                    onToggle = { onCommand(toggle) },
                    rowModifier,
                )
            }
        }
    }
}

private val PlayerOptionId.icon
    get() = when (this) {
        PlayerOptionId.WATCH_TOGETHER -> TortugaIcons.Groups
        PlayerOptionId.VIDEO_FIT -> TortugaIcons.AspectRatio
        PlayerOptionId.ZOOM -> TortugaIcons.ZoomIn
        PlayerOptionId.SHADERS -> TortugaIcons.AutoFixHigh
        PlayerOptionId.SLEEP_TIMER -> TortugaIcons.Bedtime
        PlayerOptionId.AUTO_PLAY_NEXT -> TortugaIcons.SkipNext
        PlayerOptionId.AUDIO_OUTPUT -> TortugaIcons.Speaker
        PlayerOptionId.AUDIO_NORMALIZATION -> TortugaIcons.GraphicEq
        PlayerOptionId.AUDIO_CHANNELS -> TortugaIcons.SurroundSound
    }

/**
 * An option with a page: its icon and label, its value (marked when adjusted) and a chevron. The audio output's value is
 * a device's own name, which takes its direction from its first letter; the other values are ours.
 */
@Composable
private fun OptionRow(option: PlayerOption, onClick: () -> Unit, modifier: Modifier) {
    TortugaSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = LocalTortugaDimens.current.controlHeight),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        contentAlignment = Alignment.CenterStart,
        grows = false,
    ) {
        OptionLine(option.id.icon, option.label, if (option.adjusted) "player.option.${option.id.probeName}.adjusted" else null) {
            val content = LocalContentColor.current
            val style = MaterialTheme.typography.bodyMedium
            Text(
                option.value,
                style = if (option.id == PlayerOptionId.AUDIO_OUTPUT) style.contentDirection() else style,
                color = if (option.adjusted) content else content.copy(alpha = 0.6f),
                maxLines = 1,
                modifier = Modifier.widthIn(max = 160.dp),
                overflow = TextOverflow.Ellipsis,
            )
            Icon(TortugaIcons.ChevronRight, contentDescription = null, Modifier.size(20.dp))
        }
    }
}

/** An action: its icon and label; the whole row does it. */
@Composable
private fun OptionAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    TortugaSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = LocalTortugaDimens.current.controlHeight),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        contentAlignment = Alignment.CenterStart,
        grows = false,
    ) {
        OptionLine(icon, label, adjustedProbe = null) {}
    }
}

/** A switch: its icon and label, and the switch (marked when [adjustedProbe] is set); the whole row flips it. */
@Composable
private fun OptionSwitch(icon: ImageVector, label: String, on: Boolean, adjustedProbe: String?, onToggle: () -> Unit, modifier: Modifier) {
    TortugaToggleSurface(
        checked = on,
        onCheckedChange = { onToggle() },
        modifier = modifier.fillMaxWidth().heightIn(min = LocalTortugaDimens.current.controlHeight),
        color = Color.Transparent,
    ) {
        OptionLine(icon, label, adjustedProbe) { RowSwitch(on) }
    }
}

/** A row's icon, label and, with [adjustedProbe] (an option away from its default), the adjusted mark; then [trailing]. */
@Composable
private fun OptionLine(icon: ImageVector, label: String, adjustedProbe: String?, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (adjustedProbe != null) {
            Box(Modifier.size(6.dp).background(LocalContentColor.current, CircleShape).layoutProbe(adjustedProbe))
        }
        trailing()
    }
}

/**
 * The languages of More subtitles, each with how many subtitles it has, opening its page. Focus starts on the language
 * whose page [backFrom] (its key) was (back from it), else on the first.
 */
@Composable
private fun LanguageList(
    languages: List<SubtitleLanguage>,
    backFrom: String?,
    initial: FocusRequester,
    onCommand: (PlayerCommand) -> Unit,
    modifier: Modifier,
) {
    val focused = languages.indexOfFirst { it.key == backFrom }.coerceAtLeast(0)
    MenuList(modifier) {
        languages.forEachIndexed { index, language ->
            PageEntry(icon = null, language.label, language.count, Modifier.menuItem(index, index == focused, initial)) {
                onCommand(PlayerCommand.OpenSubtitleLanguage(language.key))
            }
        }
    }
}

/**
 * An option's page or a subtitle language's: its choices, the selected one checked, each with its detail line. The
 * selected one takes focus as it opens, else the first. [contentLabels]: whether the choices' labels are content text
 * (a subtitle addon's titles, a device's name), which takes its direction from its first letter ([contentDirection]).
 */
@Composable
private fun ChoiceList(
    choices: List<OptionChoice>,
    focused: Int,
    initial: FocusRequester,
    onCommand: (PlayerCommand) -> Unit,
    modifier: Modifier,
    contentLabels: Boolean,
) {
    MenuList(modifier) {
        choices.forEachIndexed { index, choice ->
            SheetOption(
                choice.label,
                onClick = { onCommand(choice.command) },
                modifier = Modifier.menuItem(index, index == focused, initial),
                selected = choice.selected,
                detail = choice.detail,
                contentLabel = contentLabels,
            )
        }
    }
}

/**
 * The Shaders page: its presets as an option page's choices ([ChoiceList]), each imported one with a delete button
 * beside it that asks first, then where the device has file dialogs ([PlayerOption.importLabel]) a last entry that
 * imports a shader through the system's open dialog. The imported shaders' names are their files', content text.
 * Harness names: `player.menu.item.<i>.delete`, `player.menu.import`, `player.menu.delete.<confirm|cancel>`.
 */
@Composable
private fun ShaderList(option: PlayerOption?, focused: Int, initial: FocusRequester, onCommand: (PlayerCommand) -> Unit, modifier: Modifier) {
    val platform = LocalTortugaPlatform.current
    var deleting by remember { mutableStateOf<OptionChoice?>(null) }
    MenuList(modifier) {
        option?.choices.orEmpty().forEachIndexed { index, choice ->
            val delete = choice.delete
            val row = @Composable { rowModifier: Modifier ->
                SheetOption(
                    choice.label,
                    onClick = { onCommand(choice.command) },
                    modifier = rowModifier.menuItem(index, index == focused, initial),
                    selected = choice.selected,
                    detail = choice.detail,
                    contentLabel = delete != null,
                )
            }
            if (delete == null) {
                row(Modifier)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    row(Modifier.weight(1f))
                    PlayerButton(
                        TortugaIcons.Delete,
                        T.player.options.shader.deleteLabel(choice.label),
                        onClick = { deleting = choice },
                        probe = "player.menu.item.$index.delete",
                    )
                }
            }
        }
        option?.importLabel?.let { label ->
            OptionAction(
                TortugaIcons.Add,
                label,
                onClick = {
                    platform.openFile(
                        opened = { name, text -> onCommand(PlayerCommand.ImportShader(name, text)) },
                        failed = { onCommand(PlayerCommand.ShaderImportFailed) },
                    )
                },
                Modifier.layoutProbe("player.menu.import"),
            )
        }
    }
    deleting?.let { choice ->
        val strings = T.player.options.shader
        ConfirmQuestion(
            ConfirmAction(strings.deleteLabel(choice.label), strings.deleteTitle(choice.label), strings.delete, strings.cancel),
            "player.menu.delete",
            onConfirm = {
                deleting = null
                choice.delete?.let(onCommand)
            },
            onCancel = { deleting = null },
        )
    }
}

/**
 * The tracks, scrolling when they don't fit, over [footer] (More subtitles, the delay), which stays in view under them.
 * The selected track takes focus as the menu opens, unless [focusSelected] is false (an entry of [footer] takes it).
 * A subtitle row's second action (a long press, a held Select, a right-click or the menu key) shows its track as the
 * second subtitle or takes it away ([PlayerCommand.ToggleSecondSubtitle]); the second's row has a "2" badge.
 */
@Composable
private fun TrackList(
    tracks: List<TrackItem>,
    kind: TrackKind,
    initial: FocusRequester,
    focusSelected: Boolean,
    onCommand: (PlayerCommand) -> Unit,
    modifier: Modifier,
    footer: @Composable () -> Unit,
) {
    // Every subtitle row has the second action, so a held Select never falls through to a pick; the store decides.
    val second = kind == TrackKind.SUBTITLE
    Column(modifier.padding(bottom = LocalTortugaDimens.current.focusReach)) {
        MenuList(Modifier.weight(1f, fill = false)) {
            tracks.forEachIndexed { index, track ->
                val toggleSecond = { onCommand(PlayerCommand.ToggleSecondSubtitle(track.id)) }
                SheetOption(
                    track.label,
                    onClick = { onCommand(PlayerCommand.SelectTrack(kind, track.id)) },
                    modifier = Modifier.menuItem(index, focusSelected && track.selected, initial)
                        .then(if (second) Modifier.menuGestures(toggleSecond) else Modifier),
                    selected = track.selected,
                    detail = track.detail,
                    // A file's track title or our language name ("Track 2" without either, which opens on a letter of
                    // the interface's own script in every language we ship).
                    contentLabel = true,
                    onLongClick = if (second) toggleSecond else null,
                    onLongClickLabel = when {
                        !second -> null
                        track.second || track.id == null -> T.player.track.removeSecond
                        track.selected -> null
                        else -> T.player.track.showAsSecond
                    },
                    badge = if (track.second) ({ SecondBadge(Modifier.layoutProbe("player.menu.item.$index.second")) }) else null,
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = TortugaColors.Hairline)
        footer()
    }
}

/** The second subtitle's mark: "2" in the primary fill, named for screen readers. */
@Composable
private fun SecondBadge(modifier: Modifier) {
    val name = T.player.track.second
    Badge("2", modifier.clearAndSetSemantics { contentDescription = name }, emphasised = true)
}

/** The entries, scrolling when they don't fit, with room around them for a focused entry's growth. */
@Composable
private fun MenuList(modifier: Modifier, content: @Composable () -> Unit) {
    val reach = LocalTortugaDimens.current.focusReach
    Column(modifier.verticalScroll(rememberScrollState()).padding(PaddingValues(vertical = reach))) { content() }
}

/** Entry [index] of a menu, for the harness; a [focused] one takes focus as the menu opens. */
private fun Modifier.menuItem(index: Int, focused: Boolean, initial: FocusRequester): Modifier =
    layoutProbe("player.menu.item.$index").then(if (focused) Modifier.focusRequester(initial) else Modifier)

/**
 * An entry that opens a page, its value beside it: a track menu's delay and More subtitles, a language of More
 * subtitles (no [icon]).
 */
@Composable
private fun PageEntry(icon: ImageVector?, label: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    TortugaSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = LocalTortugaDimens.current.controlHeight),
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        contentAlignment = Alignment.CenterStart,
        grows = false,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.6f))
            Icon(TortugaIcons.ChevronRight, contentDescription = null, Modifier.size(20.dp))
        }
    }
}

/** A track menu's last entry: its delay, with the delay's value, opening the delay's page. */
@Composable
private fun DelayEntry(delay: DelayState, onCommand: (PlayerCommand) -> Unit) {
    PageEntry(TortugaIcons.Timer, delay.title, delay.label, Modifier.layoutProbe("player.menu.delay")) {
        onCommand(PlayerCommand.OpenMenu(delay.menu))
    }
}

/**
 * − value + and Reset ([DelayState]). The buttons disable at the ends of the range, Reset at zero; screen readers name
 * − and + by the way they move the delay ("Subtitle delay: earlier").
 */
@Composable
private fun DelayControl(delay: DelayState, initial: FocusRequester, onCommand: (PlayerCommand) -> Unit) {
    val reach = LocalTortugaDimens.current.focusReach
    Column(Modifier.fillMaxWidth().padding(vertical = reach + 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PlayerButton(
                TortugaIcons.Remove,
                label = delay.decreaseLabel,
                onClick = { onCommand(delay.decrease) },
                probe = "player.delay.decrease",
                filled = true,
                enabled = delay.canDecrease,
                focusRequester = initial,
            )
            Text(
                delay.label,
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(112.dp),
            )
            PlayerButton(
                TortugaIcons.Add,
                label = delay.increaseLabel,
                onClick = { onCommand(delay.increase) },
                probe = "player.delay.increase",
                filled = true,
                enabled = delay.canIncrease,
            )
        }
        TortugaButton(
            T.player.menu.reset,
            onClick = { onCommand(delay.reset) },
            modifier = Modifier.padding(top = 12.dp),
            style = ButtonStyle.TEXT,
            enabled = delay.canReset,
        )
    }
}
