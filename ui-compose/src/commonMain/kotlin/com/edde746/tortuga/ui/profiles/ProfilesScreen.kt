package com.edde746.tortuga.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.app.RootSession
import com.edde746.tortuga.presentation.profiles.PinPromptUiState
import com.edde746.tortuga.presentation.profiles.ProfileBadge
import com.edde746.tortuga.presentation.profiles.ProfilePickerBody
import com.edde746.tortuga.presentation.profiles.ProfilePickerHost
import com.edde746.tortuga.presentation.profiles.ProfilePickerStore
import com.edde746.tortuga.presentation.profiles.ProfilePickerUiState
import com.edde746.tortuga.presentation.profiles.ProfileTile
import com.edde746.tortuga.presentation.profiles.profilePickerStore
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.largeFontScale
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.LoadingState
import com.edde746.tortuga.ui.components.SystemBackHandler
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.overlayOpener
import com.edde746.tortuga.ui.focus.focusEntry
import com.edde746.tortuga.ui.focus.focusRing
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.probe.rememberLiveStore
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection
import com.edde746.tortuga.ui.theme.hyphenated

/**
 * The profile picker of [root] ([com.edde746.tortuga.presentation.account.AccountDestination.Profiles]) and its
 * editor: the [ProfilePickerStore] lives while the picker shows; creating or editing a profile opens the editor in its
 * place, and closing the editor returns to the picker as it was.
 */
@Composable
fun ProfilesRoute(root: RootSession) {
    val editor = remember { mutableStateOf<EditorTarget?>(null) }
    val store = rememberLiveStore(
        ProfilePickerStore.KEY,
        {
            root.graph.profilePickerStore(
                root.gate,
                object : ProfilePickerHost {
                    override fun createProfile() {
                        editor.value = EditorTarget(null)
                    }

                    override fun editProfile(profileId: String) {
                        editor.value = EditorTarget(profileId)
                    }
                },
            )
        },
        ProfilePickerStore::release,
    )
    val state by store.state.collectAsState()
    val target = editor.value
    if (target != null) {
        key(target) { ProfileEditorRoute(root.graph, target.profileId, onClose = { editor.value = null }) }
        return
    }
    ProfilePickerScreen(
        state = state,
        intents = ProfilePickerIntents(
            select = store::select,
            add = store::addProfile,
            manage = store::manage,
            doneManaging = store::doneManaging,
            close = store::close,
            pinDigit = store::pinDigit,
            pinBackspace = store::pinBackspace,
            cancelPin = store::cancelPin,
        ),
    )
}

/** The profile the editor opens: a new one for a null [profileId]. */
private data class EditorTarget(val profileId: String?)

/** What the picker reports; each maps to the [ProfilePickerStore] intent of the same name. */
class ProfilePickerIntents(
    val select: (profileId: String) -> Unit,
    val add: () -> Unit,
    val manage: () -> Unit,
    val doneManaging: () -> Unit,
    val close: () -> Unit,
    val pinDigit: (Int) -> Unit,
    val pinBackspace: () -> Unit,
    val cancelPin: () -> Unit,
)

/**
 * "Who's watching?": the account's profiles as avatar tiles (locked ones marked), "Add profile",
 * managing (a tile opens its editor), going back to the running profile when switching, and the sync status line.
 * A locked profile's PIN pad takes the screen while it is asked, with the store's wrong-PIN and lockout lines. Every
 * text comes from [state] or `T`. The tiles and the buttons under them are a focus group each: a move into one lands
 * on the tile or button that had focus last, else the first, however many tiles there are and however wide the
 * buttons' labels ([focusEntry]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilePickerScreen(state: ProfilePickerUiState, intents: ProfilePickerIntents, modifier: Modifier = Modifier) {
    state.pinPrompt?.let { prompt ->
        PinPrompt(prompt, intents, modifier)
        return
    }
    if (state.canClose) SystemBackHandler(onBack = intents.close)
    val dimens = LocalTortugaDimens.current
    val first = remember { FocusRequester() }
    val firstButton = remember { FocusRequester() }
    BoxWithConstraints(modifier.fillMaxSize().background(TortugaColors.Background)) {
        val (profiles, emptyText) = when (val body = state.body) {
            ProfilePickerBody.Loading -> {
                LoadingState(text = T.profiles.loading)
                return@BoxWithConstraints
            }
            is ProfilePickerBody.Empty -> emptyList<ProfileTile>() to body.text
            is ProfilePickerBody.Tiles -> body.profiles to null
        }
        val minHeight = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(horizontal = dimens.screenPadding, vertical = dimens.sectionSpacing),
            verticalArrangement = Arrangement.spacedBy(dimens.sectionSpacing, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(state.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            emptyText?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = TortugaColors.TextMuted) }
            FlowRow(
                Modifier.widthIn(max = 960.dp).focusEntry(first),
                horizontalArrangement = Arrangement.spacedBy(dimens.gutter * 2, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(dimens.gutter * 2),
            ) {
                profiles.forEachIndexed { index, tile ->
                    ProfileTileItem(
                        tile = tile,
                        onClick = { intents.select(tile.id) },
                        modifier = (if (index == 0) Modifier.focusRequester(first) else Modifier).layoutProbe("profiles.tile.$index"),
                    )
                }
                AddTile(
                    onClick = intents.add,
                    modifier = (if (profiles.isEmpty()) Modifier.focusRequester(first) else Modifier).layoutProbe("profiles.add"),
                )
            }
            Row(Modifier.focusEntry(firstButton), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val manageShows = state.managing || profiles.isNotEmpty()
                if (state.managing) {
                    TortugaButton(T.profiles.done, intents.doneManaging, Modifier.focusRequester(firstButton).layoutProbe("profiles.done"), style = ButtonStyle.PRIMARY, icon = TortugaIcons.Check)
                } else if (profiles.isNotEmpty()) {
                    TortugaButton(T.profiles.manage, intents.manage, Modifier.focusRequester(firstButton).layoutProbe("profiles.manage"), style = ButtonStyle.SECONDARY, icon = TortugaIcons.Edit)
                }
                if (state.canClose) {
                    TortugaButton(T.nav.back, intents.close, (if (manageShows) Modifier else Modifier.focusRequester(firstButton)).layoutProbe("profiles.close"), style = ButtonStyle.TEXT, icon = TortugaIcons.ArrowBack)
                }
            }
            Text(
                state.syncStatus,
                style = MaterialTheme.typography.bodySmall,
                color = TortugaColors.TextMuted,
                modifier = Modifier.layoutProbe("profiles.sync"),
            )
        }
    }
    // Keyboards and remotes start on the first tile once the tiles are there.
    val tileCount = when (val body = state.body) {
        ProfilePickerBody.Loading -> null
        is ProfilePickerBody.Empty -> 0
        is ProfilePickerBody.Tiles -> body.profiles.size
    }
    LaunchedEffect(tileCount) { if (tileCount != null) first.requestFocus() }
}

@Composable
private fun PinPrompt(prompt: PinPromptUiState, intents: ProfilePickerIntents, modifier: Modifier) {
    PinPad(
        title = prompt.title,
        entered = prompt.entered,
        length = prompt.length,
        enteredText = prompt.enteredText,
        message = prompt.lockedOutText ?: prompt.error,
        acceptsInput = prompt.acceptsInput,
        onDigit = intents.pinDigit,
        onBackspace = intents.pinBackspace,
        onCancel = intents.cancelPin,
        modifier = modifier,
        header = { ProfileAvatarTile(prompt.initial, prompt.avatar, 72.dp) },
    )
}

/**
 * A profile: its avatar (with its [ProfileTile.badge]: a pencil while managing, a lock for a PIN, a check on the current
 * one) and name; the whole tile is the button. Focus rings the avatar and, on TV, grows it. Screen readers name it by
 * the name alone (not the avatar's initial), its state [ProfileTile.stateText].
 */
@Composable
private fun ProfileTileItem(tile: ProfileTile, onClick: () -> Unit, modifier: Modifier) {
    val badge = when (tile.badge) {
        ProfileBadge.EDIT -> TortugaIcons.Edit
        ProfileBadge.LOCK -> TortugaIcons.Lock
        ProfileBadge.CURRENT -> TortugaIcons.Check
        null -> null
    }
    Tile(
        onClick = onClick,
        label = tile.name,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = tile.name
            tile.stateText?.let { stateDescription = it }
        },
    ) { size ->
        ProfileAvatarTile(tile.initial, tile.avatar, size)
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(32.dp)
                    .background(TortugaColors.SurfaceHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(badge, contentDescription = null, Modifier.size(18.dp))
            }
        }
    }
}

/** "Add profile": an empty avatar with a plus. */
@Composable
private fun AddTile(onClick: () -> Unit, modifier: Modifier) {
    Tile(onClick = onClick, label = T.profiles.add, modifier = modifier) { size ->
        Box(Modifier.size(size).background(TortugaColors.SurfaceHigh, CircleShape), contentAlignment = Alignment.Center) {
            Icon(TortugaIcons.Add, contentDescription = null, Modifier.size(40.dp))
        }
    }
}

/**
 * A picker tile: [avatar] over [label]; focus (keyboard, remote) rings the avatar and, on TV, grows it. [label] is a
 * profile's name or "Add profile", so it takes its direction from its first letter. The label takes a second line when
 * it needs one ("Add profile" in most languages), the tiles of a row top-aligned. At a large font scale it keeps two
 * lines on every tile, so a row's tiles stay one height, in up to twice the tile's width.
 */
@Composable
private fun Tile(onClick: () -> Unit, label: String, modifier: Modifier, avatar: @Composable BoxScope.(Dp) -> Unit) {
    val size = tileAvatarSize()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val large = largeFontScale()
    val lines = if (large) 2 else 1
    Column(
        modifier
            .overlayOpener()
            .clickable(interactions, indication = null, role = Role.Button, onClick = onClick)
            .padding(12.dp)
            .then(if (large) Modifier.widthIn(min = size + 24.dp, max = (size + 24.dp) * 2) else Modifier.width(size + 24.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.focusRing(CircleShape, focused)) { avatar(size) }
        Text(label, style = MaterialTheme.typography.titleMedium.contentDirection().hyphenated(), maxLines = 2, minLines = lines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
