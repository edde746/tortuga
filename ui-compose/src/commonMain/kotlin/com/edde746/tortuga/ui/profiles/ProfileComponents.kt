package com.edde746.tortuga.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.profiles.ProfileAvatar
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.SystemBackHandler
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.components.TortugaSurface
import com.edde746.tortuga.ui.focus.focusEntry
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors

/** A profile's avatar: its [initial] on the avatar's colours (from the shared avatar set), in a circle [size] wide. */
@Composable
fun ProfileAvatarTile(initial: String, avatar: ProfileAvatar, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size).background(Color(avatar.background), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val style = when {
            size >= 88.dp -> MaterialTheme.typography.displaySmall
            size >= 56.dp -> MaterialTheme.typography.headlineMedium
            else -> MaterialTheme.typography.titleMedium
        }
        Text(initial, style = style, color = Color(avatar.foreground), textAlign = TextAlign.Center)
    }
}

/** The avatar size of the picker's tiles: larger on TV (10-foot viewing). */
@Composable
internal fun tileAvatarSize(): Dp = if (LocalWindowLayout.current.formFactor == FormFactor.TV) 120.dp else 96.dp

/**
 * A four-digit PIN pad (entering a profile's PIN, or setting one): [title], an optional [subtitle] (what the PIN is
 * asked for), one dot per digit with [entered] filled (spoken as the store's [enteredText]), [message] (a wrong PIN,
 * the lockout countdown, a mismatch), digits 0–9 and backspace, and cancel. The digit and Backspace keys work while the
 * pad has focus (desktop keyboards, remotes with number keys); Back cancels. The keys take focus at 1 as the pad opens;
 * a move into them from Cancel lands on the key that had focus last, else 1, not on the key level with Cancel, which
 * the title's and the message's wrap would decide ([focusEntry]).
 *
 * TalkBack speaks [message] as it shows, wherever its focus is, but not a text that replaces another without the line
 * emptying in between: a wrong PIN, a mismatch and the start of a lockout are spoken (the stores clear a wrong PIN and
 * a mismatch as the next digit is typed, so a second one shows anew); the lockout's countdown, whose text changes
 * every second, isn't.
 */
@Composable
fun PinPad(
    title: String,
    entered: Int,
    length: Int,
    enteredText: String,
    message: String?,
    acceptsInput: Boolean,
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    header: (@Composable () -> Unit)? = null,
) {
    SystemBackHandler(onBack = onCancel)
    val first = remember { FocusRequester() }
    val firstMessage = remember(message != null) { message }
    val dimens = LocalTortugaDimens.current
    val key = if (LocalWindowLayout.current.formFactor == FormFactor.TV) 76.dp else 64.dp
    BoxWithConstraints(modifier.fillMaxSize().background(TortugaColors.Background)) {
        val minHeight = maxHeight
        val page = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .heightIn(min = minHeight)
            .padding(dimens.screenPadding)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = digitOf(event.key)
                when {
                    digit != null -> onDigit(digit)
                    event.key == Key.Backspace -> onBackspace()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
        val heading: @Composable () -> Unit = {
            header?.invoke()
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Text(subtitle, Modifier.layoutProbe("pin.subtitle"), style = MaterialTheme.typography.bodyLarge, color = TortugaColors.TextMuted, textAlign = TextAlign.Center)
            }
            Row(
                Modifier.layoutProbe("pin.dots").semantics { contentDescription = enteredText },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                repeat(length) { index ->
                    val filled = index < entered
                    Box(Modifier.size(14.dp).background(if (filled) TortugaColors.Text else TortugaColors.SurfaceHighest, CircleShape))
                }
            }
            Text(
                message.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = TortugaColors.Error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .heightIn(min = 24.dp)
                    .layoutProbe("pin.message")
                    .semantics { if (message == firstMessage) liveRegion = LiveRegionMode.Polite },
            )
        }
        val keys: @Composable () -> Unit = {
            Column(Modifier.focusEntry(first), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in DIGIT_ROWS) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (digit in row) {
                            DigitKey(digit, key, acceptsInput, onDigit, if (digit == 1) Modifier.focusRequester(first) else Modifier)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Spacer(Modifier.size(key))
                    DigitKey(0, key, acceptsInput, onDigit)
                    TortugaIconButton(
                        TortugaIcons.Backspace,
                        contentDescription = T.profiles.pin.backspace,
                        onClick = onBackspace,
                        modifier = Modifier.layoutProbe("pin.backspace"),
                        style = IconButtonStyle.PLAIN,
                        size = key,
                    )
                }
            }
        }
        val cancel: @Composable () -> Unit = {
            TortugaButton(T.profiles.pin.cancel, onCancel, Modifier.layoutProbe("pin.cancel"), style = ButtonStyle.TEXT)
        }
        if (maxWidth > maxHeight && maxHeight < STACKED_PAD_HEIGHT) {
            // A wide, short window (a TV, a phone on its side) has no room for the pad under its title: side by side.
            Row(page, horizontalArrangement = Arrangement.spacedBy(64.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.widthIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    heading()
                    cancel()
                }
                keys()
            }
        } else {
            Column(page, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                heading()
                keys()
                cancel()
            }
        }
    }
    LaunchedEffect(Unit) { first.requestFocus() }
}

/** The window height under which the pad goes beside its title rather than under it (the stacked pad needs about this). */
private val STACKED_PAD_HEIGHT = 640.dp

/**
 * One digit of the pad. It stays focusable while input is paused (checking, locked out) so focus, and with it the
 * keyboard, stays on the pad; the store ignores digits then, and the key shows dimmed.
 */
@Composable
private fun DigitKey(digit: Int, size: Dp, accepts: Boolean, onDigit: (Int) -> Unit, modifier: Modifier = Modifier) {
    TortugaSurface(
        onClick = { onDigit(digit) },
        shape = CircleShape,
        contentColor = if (accepts) TortugaColors.Text else TortugaColors.TextMuted,
        modifier = modifier.size(size).layoutProbe("pin.digit.$digit"),
        contentAlignment = Alignment.Center,
    ) {
        Text(digit.toString(), style = MaterialTheme.typography.headlineSmall)
    }
}

private val DIGIT_ROWS = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))

private fun digitOf(key: Key): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}
