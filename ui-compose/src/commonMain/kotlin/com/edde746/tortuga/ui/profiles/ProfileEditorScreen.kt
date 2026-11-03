package com.edde746.tortuga.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.kids.AgeStep
import com.edde746.tortuga.presentation.profiles.KidsSectionUiState
import com.edde746.tortuga.presentation.profiles.KidsTitleRow
import com.edde746.tortuga.presentation.profiles.ProfileEditorHost
import com.edde746.tortuga.presentation.profiles.ProfileEditorStore
import com.edde746.tortuga.presentation.profiles.ProfileEditorUiState
import com.edde746.tortuga.presentation.profiles.profileEditorStore
import com.edde746.tortuga.presentation.settings.SettingToggleRow
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.account.AccountPage
import com.edde746.tortuga.ui.account.ErrorLine
import com.edde746.tortuga.ui.account.ProgressLine
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.GroupHeader
import com.edde746.tortuga.ui.components.GroupRow
import com.edde746.tortuga.ui.components.GroupSpacing
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.RowGroup
import com.edde746.tortuga.ui.components.TextAndTrailing
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.components.groupShape
import com.edde746.tortuga.ui.focus.OnTextChange
import com.edde746.tortuga.ui.focus.focusEntry
import com.edde746.tortuga.ui.focus.focusRing
import com.edde746.tortuga.ui.focus.inViewAboveKeyboard
import com.edde746.tortuga.ui.focus.rememberKeyboardOnSelect
import com.edde746.tortuga.ui.focus.remoteLeavesField
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.probe.rememberLiveStore
import com.edde746.tortuga.ui.settings.NoteLine
import com.edde746.tortuga.ui.settings.RowText
import com.edde746.tortuga.ui.settings.SettingToggleItem
import com.edde746.tortuga.ui.theme.TortugaColors

/**
 * The profile editor: a new profile for a null [profileId], else that profile (its PIN, if any, already entered). Its
 * [ProfileEditorStore] lives while the editor shows; saving, cancelling or the profile's deletion calls [onClose].
 * Opened from the picker ([ProfilesRoute]) and over the app ([com.edde746.tortuga.app.AppSession.editProfile]).
 */
@Composable
fun ProfileEditorRoute(graph: AppGraph, profileId: String?, onClose: () -> Unit) {
    val store = rememberLiveStore(
        ProfileEditorStore.KEY,
        {
            graph.profileEditorStore(
                profileId,
                object : ProfileEditorHost {
                    override fun closeEditor() = onClose()

                    override fun profileDeleted(profileId: String) = onClose()
                },
            )
        },
        ProfileEditorStore::release,
    )
    val state by store.state.collectAsState()
    ProfileEditorScreen(
        state = state,
        intents = ProfileEditorIntents(
            setName = store::setName,
            selectAvatar = store::selectAvatar,
            startPinSetup = store::startPinSetup,
            pinDigit = store::pinDigit,
            pinBackspace = store::pinBackspace,
            cancelPinSetup = store::cancelPinSetup,
            removePin = store::removePin,
            save = store::save,
            cancel = store::cancel,
            requestDelete = store::requestDelete,
            cancelDelete = store::cancelDelete,
            confirmDelete = store::confirmDelete,
            setKids = store::setKids,
            setKidsCap = store::setKidsCap,
            setAllowUnrated = store::setAllowUnrated,
            setHomeCatalog = store::setHomeCatalog,
            removeAllowed = store::removeAllowed,
            removeBlocked = store::removeBlocked,
        ),
    )
}

/** What the editor reports; each maps to the [ProfileEditorStore] intent of the same name. */
class ProfileEditorIntents(
    val setName: (String) -> Unit,
    val selectAvatar: (id: String) -> Unit,
    val startPinSetup: () -> Unit,
    val pinDigit: (Int) -> Unit,
    val pinBackspace: () -> Unit,
    val cancelPinSetup: () -> Unit,
    val removePin: () -> Unit,
    val save: () -> Unit,
    val cancel: () -> Unit,
    val requestDelete: () -> Unit,
    val cancelDelete: () -> Unit,
    val confirmDelete: () -> Unit,
    val setKids: (Boolean) -> Unit,
    val setKidsCap: (AgeStep) -> Unit,
    val setAllowUnrated: (Boolean) -> Unit,
    val setHomeCatalog: (index: Int, selected: Boolean) -> Unit,
    val removeAllowed: (key: String) -> Unit,
    val removeBlocked: (key: String) -> Unit,
)

/**
 * Creating or editing a profile: name, avatar (the shared set), setting, changing or removing its PIN (the pad takes
 * the screen: the PIN, then the same again), the Kids profile section where the store has one ([KidsSection]), saving,
 * and deleting after the store's confirmation. The header's back button (and Back) leaves without saving: the page's
 * one way out. Every text comes from [state] or `T`. A move into the avatars (Down from the name, Up from the PIN's
 * buttons) lands on the one that had focus last, else the chosen one; into the PIN's buttons on the one that had focus
 * last, else Set or Change PIN: neither goes by how many avatars a line holds or how wide the buttons' labels are
 * ([focusEntry]). On TV the keyboard waits for the name to be pressed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditorScreen(state: ProfileEditorUiState, intents: ProfileEditorIntents, modifier: Modifier = Modifier) {
    // The name takes focus when the editor opens; back from the PIN pad the PIN's button does, so the keyboard doesn't
    // come up over the PIN just set and a remote carries on where it was.
    var backFromPinPad by remember { mutableStateOf(false) }
    state.pinSetup?.let { setup ->
        LaunchedEffect(Unit) { backFromPinPad = true }
        PinPad(
            title = setup.title,
            entered = setup.entered,
            length = setup.length,
            enteredText = setup.enteredText,
            message = setup.error,
            acceptsInput = true,
            onDigit = intents.pinDigit,
            onBackspace = intents.pinBackspace,
            onCancel = intents.cancelPinSetup,
            modifier = modifier,
            header = { ProfileAvatarTile(state.initial, state.avatar, 72.dp) },
        )
        return
    }
    if (!state.loaded) {
        LoadingState(modifier)
        return
    }
    val nameFocus = remember { FocusRequester() }
    val pinFocus = remember { FocusRequester() }
    val avatarFocus = remember { FocusRequester() }
    // The field keeps its own text and cursor (the cursor a remote leaves the field by), the store hears each change.
    val name = remember { TextFieldState(state.name) }
    val keyboard = rememberKeyboardOnSelect()
    AccountPage(state.title, null, modifier, onBack = intents.cancel, backProbe = "editor.back") {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatarTile(state.initial, state.avatar, 72.dp)
            OutlinedTextField(
                state = name,
                label = { Text(T.profiles.editor.name) },
                lineLimits = TextFieldLineLimits.SingleLine,
                readOnly = keyboard.readOnly,
                isError = state.nameError != null,
                supportingText = state.nameError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                onKeyboardAction = { if (state.canSave) intents.save() },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(nameFocus)
                    .then(keyboard.modifier)
                    .remoteLeavesField(name)
                    .inViewAboveKeyboard()
                    .layoutProbe("editor.name"),
            )
        }
        Text(T.profiles.editor.avatar, style = MaterialTheme.typography.titleSmall, color = TortugaColors.TextMuted)
        FlowRow(Modifier.focusEntry(avatarFocus), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val chosen = state.avatars.indexOfFirst { it.selected }.coerceAtLeast(0)
            state.avatars.forEachIndexed { index, choice ->
                Surface(
                    onClick = { intents.selectAvatar(choice.avatar.id) },
                    shape = CircleShape,
                    color = Color(choice.avatar.background),
                    modifier = (if (index == chosen) Modifier.focusRequester(avatarFocus) else Modifier)
                        .size(48.dp)
                        .focusRing(CircleShape)
                        .layoutProbe("editor.avatar.$index")
                        .semantics {
                            contentDescription = choice.avatar.name
                            selected = choice.selected
                        },
                ) {
                    if (choice.selected) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(TortugaIcons.Check, contentDescription = null, tint = Color(choice.avatar.foreground))
                        }
                    }
                }
            }
        }
        PinSection(state, intents, pinFocus)
        KidsSection(state.kids, intents)
        state.error?.let { ErrorLine(it) }
        val confirmation = state.deleteConfirmation
        if (confirmation != null) {
            DeleteConfirmation(confirmation, state.saving, intents)
        } else {
            if (state.saving) ProgressLine(null)
            TortugaButton(
                if (state.creating) T.profiles.editor.create else T.profiles.editor.save,
                intents.save,
                Modifier.layoutProbe("editor.save"),
                style = ButtonStyle.PRIMARY,
                icon = TortugaIcons.Check,
                enabled = state.canSave,
                fill = true,
            )
            if (state.canDelete) {
                TortugaButton(T.profiles.editor.delete, intents.requestDelete, Modifier.layoutProbe("editor.delete"), style = ButtonStyle.DESTRUCTIVE, icon = TortugaIcons.Delete, fill = true)
            }
        }
    }
    OnTextChange(name, intents.setName)
    LaunchedEffect(Unit) { if (backFromPinPad) pinFocus.requestFocus() else nameFocus.requestFocus() }
}

/** "No PIN" / "Locked with a PIN", set or change it, remove it, and what a PIN is. */
@Composable
private fun PinSection(state: ProfileEditorUiState, intents: ProfileEditorIntents, focus: FocusRequester) {
    Box(Modifier.fillMaxWidth().background(TortugaColors.SurfaceHigh, MaterialTheme.shapes.large)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(TortugaIcons.Lock, contentDescription = null, Modifier.size(20.dp))
                Text(state.pinStatus, style = MaterialTheme.typography.titleMedium, modifier = Modifier.layoutProbe("editor.pin.status"))
            }
            Text(T.profiles.editor.pinNote, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
            Row(Modifier.focusEntry(focus), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TortugaButton(
                    if (state.hasPin) T.profiles.editor.changePin else T.profiles.editor.setPin,
                    intents.startPinSetup,
                    Modifier.layoutProbe("editor.pin.set").focusRequester(focus),
                    style = ButtonStyle.SECONDARY,
                )
                if (state.hasPin) {
                    TortugaButton(T.profiles.editor.removePin, intents.removePin, Modifier.layoutProbe("editor.pin.remove"), style = ButtonStyle.TEXT)
                }
            }
        }
    }
}

/**
 * The Kids profile section: the switch, why it can't be turned on ([KidsSectionUiState.blockedReason]), then while it
 * is on the highest rating (Little, Kids or Teen, each with the ratings it takes in), Allow unrated titles, the Home
 * rows a kids profile can start with (optional), the titles a parent allowed and blocked one by one, each with its
 * remove button, and what a kids profile does: leaving asks for a parent's PIN, links, add-ons and most settings are
 * off, videos come from the profile's addons. Rows in the settings' groups; on TV every control is a stop of the page's
 * Up and Down (a title's remove button too: its row's text and button are one focus group, [TextAndTrailing]).
 *
 * Harness names: `editor.kids` (the switch), `editor.kids.reason`, `editor.kids.cap.<i>`, `editor.kids.unrated`,
 * `editor.kids.home.<i>`, `editor.kids.allowed.<i>`, `editor.kids.blocked.<i>` (each title's remove button),
 * `editor.kids.allowed.none`, `editor.kids.blocked.none`.
 */
@Composable
private fun KidsSection(kids: KidsSectionUiState, intents: ProfileEditorIntents) {
    val text = T.profiles.editor.kids
    Column(Modifier.fillMaxWidth()) {
        SettingToggleItem(
            SettingToggleRow("kids", text.title, text.subtitle, kids.enabled, enabled = kids.canSwitch),
            intents.setKids,
            groupShape(0, 1),
            Modifier.layoutProbe("editor.kids"),
        )
        kids.blockedReason?.let { KidsNote(it, TortugaIcons.Info, Modifier.layoutProbe("editor.kids.reason")) }
        if (!kids.enabled) return@Column
        GroupHeader(text.capTitle)
        RowGroup {
            kids.caps.forEachIndexed { index, cap ->
                // As the player choices: one of a set (a radio button to TalkBack), the chosen one checked.
                TortugaSurface(
                    onClick = { intents.setKidsCap(cap.step) },
                    modifier = Modifier.fillMaxWidth().layoutProbe("editor.kids.cap.$index"),
                    shape = groupShape(index, kids.caps.size),
                    role = Role.RadioButton,
                    selected = cap.selected,
                    grows = false,
                ) {
                    Row(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)) {
                        TextAndTrailing(
                            text = { RowText(cap.title, cap.ages) },
                            trailing = { if (cap.selected) Icon(TortugaIcons.Check, contentDescription = null, Modifier.size(20.dp)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(GroupSpacing))
        SettingToggleItem(
            SettingToggleRow("kids.unrated", text.allowUnrated, text.allowUnratedNote, kids.allowUnrated),
            intents.setAllowUnrated,
            groupShape(0, 1),
            Modifier.layoutProbe("editor.kids.unrated"),
        )
        if (kids.homeCatalogs.isNotEmpty()) {
            GroupHeader(text.homeRows)
            RowGroup {
                kids.homeCatalogs.forEachIndexed { index, catalog ->
                    SettingToggleItem(
                        SettingToggleRow("kids.home.$index", catalog.title, catalog.subtitle, catalog.selected),
                        { intents.setHomeCatalog(index, it) },
                        groupShape(index, kids.homeCatalogs.size),
                        Modifier.layoutProbe("editor.kids.home.$index"),
                    )
                }
            }
            KidsNote(text.homeRowsNote, TortugaIcons.Home)
        }
        KidsTitles(text.allowed, text.allowedNone, kids.allowed, intents.removeAllowed, "editor.kids.allowed")
        KidsTitles(text.blocked, text.blockedNone, kids.blocked, intents.removeBlocked, "editor.kids.blocked")
        Spacer(Modifier.height(GroupSpacing))
        KidsNote(text.leaving, TortugaIcons.Lock, Modifier.layoutProbe("editor.kids.leaving"))
        KidsNote(text.off, TortugaIcons.Block)
        KidsNote(text.addons, TortugaIcons.Extension)
    }
}

/**
 * The titles a parent allowed or blocked one by one under [header]: each its name and kind with a remove button named
 * by [KidsTitleRow.removeLabel] ([onRemove] with its key), or [none] when there are none. Harness names
 * `[probe].<i>` (the remove buttons), `[probe].none`.
 */
@Composable
private fun KidsTitles(header: String, none: String, titles: List<KidsTitleRow>, onRemove: (key: String) -> Unit, probe: String) {
    GroupHeader(header)
    if (titles.isEmpty()) {
        KidsNote(none, TortugaIcons.Info, Modifier.layoutProbe("$probe.none"))
        return
    }
    RowGroup {
        titles.forEachIndexed { index, title ->
            GroupRow(title.title, onClick = null, shape = groupShape(index, titles.size), subtitle = title.subtitle) {
                TortugaIconButton(
                    TortugaIcons.RemoveCircle,
                    title.removeLabel,
                    { onRemove(title.key) },
                    Modifier.layoutProbe("$probe.$index"),
                    style = IconButtonStyle.PLAIN,
                )
            }
        }
    }
}

/** A note of the Kids profile section ([NoteLine]) under the row or group it concerns, its text where a row's starts. */
@Composable
private fun KidsNote(text: String, icon: ImageVector, modifier: Modifier = Modifier) {
    NoteLine(text, icon, modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp))
}

/** The store's delete question with Delete and Cancel. */
@Composable
private fun DeleteConfirmation(text: String, deleting: Boolean, intents: ProfileEditorIntents) {
    val cancel = remember { FocusRequester() }
    Box(Modifier.fillMaxWidth().background(TortugaColors.SurfaceHigh, MaterialTheme.shapes.large)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (deleting) ProgressLine(null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TortugaButton(
                    T.profiles.editor.deleteAction,
                    intents.confirmDelete,
                    Modifier.layoutProbe("editor.delete.confirm"),
                    style = ButtonStyle.DESTRUCTIVE,
                    icon = TortugaIcons.Delete,
                    enabled = !deleting,
                )
                TortugaButton(
                    T.profiles.editor.cancel,
                    intents.cancelDelete,
                    Modifier.layoutProbe("editor.delete.cancel").focusRequester(cancel),
                    style = ButtonStyle.TEXT,
                    enabled = !deleting,
                )
            }
        }
    }
    // The safe choice takes focus, so a stray Enter doesn't delete.
    LaunchedEffect(Unit) { cancel.requestFocus() }
}
