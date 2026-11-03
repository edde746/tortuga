package com.edde746.tortuga.ui.account

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.account.DeviceCodeUiState
import com.edde746.tortuga.presentation.account.DeviceLinkUiState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.adaptive.LocalWindowLayout
import com.edde746.tortuga.ui.adaptive.LocalWindowSize
import com.edde746.tortuga.ui.adaptive.WidthClass
import com.edde746.tortuga.ui.components.ButtonStyle
import com.edde746.tortuga.ui.components.IconButtonStyle
import com.edde746.tortuga.ui.components.SystemBackHandler
import com.edde746.tortuga.ui.components.TortugaButton
import com.edde746.tortuga.ui.components.TortugaIconButton
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * The page of an account screen (sign-in, a code page, adding a sign-in method): one group in the middle of the
 * window, at most 400 dp wide (TV: 600 dp), [title] and [intro], then [content] with 12 dp between its parts. [aside]
 * (a code page's QR code) stands to the right of the group (then at most 480 dp wide on TV), the two centred on each
 * other. Scrolls when it doesn't fit; [onBack] adds a back button and takes Back (Esc on desktop): the page's one way
 * back or out (no Cancel beside it). With [focusBack] the back button takes focus as the page opens: a page whose only
 * control is the way out (a sign-in in progress, a device code), so a remote's OK or a keyboard's Enter leaves it.
 */
@Composable
fun AccountPage(
    title: String,
    intro: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backProbe: String = "account.back",
    focusBack: Boolean = false,
    aside: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onBack != null) SystemBackHandler(onBack = onBack)
    val back = remember { FocusRequester() }
    if (onBack != null && focusBack) LaunchedEffect(Unit) { back.requestFocus() }
    val dimens = LocalTortugaDimens.current
    val tv = LocalWindowLayout.current.formFactor == FormFactor.TV
    BoxWithConstraints(modifier.fillMaxSize().background(TortugaColors.Background)) {
        val minHeight = this.maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minHeight)
                .padding(horizontal = dimens.screenPadding, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val group: @Composable (Modifier) -> Unit = { groupModifier ->
                Column(groupModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (onBack != null) {
                        TortugaIconButton(TortugaIcons.ArrowBack, T.nav.back, onBack, Modifier.focusRequester(back).layoutProbe(backProbe))
                    }
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                    if (intro != null) Text(intro, style = MaterialTheme.typography.bodyLarge, color = TortugaColors.TextMuted)
                    Spacer(Modifier.height(4.dp))
                    content()
                }
            }
            if (aside == null) {
                group(Modifier.widthIn(max = if (tv) 600.dp else 400.dp).fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(if (tv) 56.dp else 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    group(Modifier.weight(1f, fill = false).widthIn(max = if (tv) 480.dp else 400.dp).fillMaxWidth())
                    aside()
                }
            }
        }
    }
}

/**
 * A line saying what went wrong, in the error colour. It often shows after an action, away from where TalkBack's focus
 * is: TalkBack speaks it as it shows and as it changes.
 */
@Composable
fun ErrorLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(TortugaIcons.ErrorOutline, contentDescription = null, Modifier.size(20.dp), tint = TortugaColors.Error)
        Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.Error)
    }
}

/** Something running: a small loading indicator and what it waits for. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProgressLine(text: String?, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(Modifier.size(32.dp))
        if (text != null) Text(text, style = MaterialTheme.typography.bodyLarge, color = TortugaColors.TextMuted)
    }
}

/** A short code to read out or type elsewhere (`ABCD-EFGH`), large and monospaced. */
@Composable
fun CodeText(code: String, modifier: Modifier = Modifier) {
    Text(code, modifier = modifier, style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp))
}

/**
 * [payload] as a QR code (ZXing's: UTF-8, error correction level M, as Apple's), dark modules on a light square
 * whatever the theme (phone cameras read that reliably), with a four-module quiet zone, [size] wide.
 */
@Composable
fun QrCodeImage(payload: String, contentDescription: String, size: Dp, modifier: Modifier = Modifier) {
    val modules = remember(payload) { Encoder.encode(payload, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix }
    val scheme = MaterialTheme.colorScheme
    val light = if (scheme.surface.luminance() > scheme.onSurface.luminance()) scheme.surface else scheme.onSurface
    val dark = if (light == scheme.surface) scheme.onSurface else scheme.surface
    Canvas(modifier.size(size).aspectRatio(1f).semantics { this.contentDescription = contentDescription }) {
        drawRect(light)
        val quiet = 4
        val cell = this.size.width / (modules.width + 2 * quiet)
        for (y in 0 until modules.height) {
            for (x in 0 until modules.width) {
                if (modules[x, y].toInt() != 1) continue
                // A hair of overlap, so neighbouring modules don't show seams when the cell size isn't whole pixels.
                drawRect(dark, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** The QR code's width for this window: larger on TV (read across a room), smaller on phones. */
@Composable
fun qrSize(): Dp = when {
    LocalWindowLayout.current.formFactor == FormFactor.TV -> 280.dp
    LocalWindowLayout.current.widthClass == WidthClass.COMPACT -> 200.dp
    else -> 232.dp
}

/**
 * Whether a code page (or the open-on-phone dialog) stands its QR code beside the rest: on TV and on landscape windows
 * wider than a phone held upright, where the code, the QR code, the countdown and the actions stacked would run below
 * the fold.
 */
@Composable
internal fun qrBeside(): Boolean {
    val window = LocalWindowLayout.current
    if (window.formFactor == FormFactor.TV) return true
    if (window.widthClass == WidthClass.COMPACT) return false
    // Composed again as the window turns landscape or upright, not on each frame of a resize.
    val size = LocalWindowSize.current
    val landscape by remember(size) { derivedStateOf { size.value.width > size.value.height } }
    return landscape
}

/** A QR code on its solid tile, [size] wide, [caption] (how to use it) centred under it. */
@Composable
internal fun QrTile(payload: String, description: String, probe: String, caption: String? = null, size: Dp = qrSize()) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.background(TortugaColors.SurfaceHigh, MaterialTheme.shapes.large)) {
            QrCodeImage(payload, description, size, Modifier.padding(12.dp).layoutProbe(probe))
        }
        if (caption != null) {
            Text(
                caption,
                Modifier.widthIn(max = size + 24.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = TortugaColors.TextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The new device's side of a device link, as a whole page: the instructions, then while waiting the code, its QR code
 * (beside the page on TV and wide landscape windows, under the code otherwise), the countdown and the status line;
 * "Get a new code" after a decline, an expiry or a failure. The header's back button (and Back) leaves.
 */
@Composable
fun DeviceLinkPage(link: DeviceLinkUiState, onRestart: () -> Unit, onBack: () -> Unit, backProbe: String, modifier: Modifier = Modifier) {
    val beside = qrBeside()
    val aside: (@Composable () -> Unit)? = if (beside && link is DeviceLinkUiState.Waiting) {
        { QrTile(link.qrPayload, T.account.link.qrDescription, "link.code.qr") }
    } else {
        null
    }
    AccountPage(T.account.link.title, null, modifier, onBack = onBack, backProbe = backProbe, aside = aside) {
        Text(T.account.link.instructions, style = MaterialTheme.typography.bodyLarge)
        when (link) {
            is DeviceLinkUiState.Starting -> ProgressLine(link.progress)
            is DeviceLinkUiState.Waiting -> {
                CodeText(link.code, Modifier.layoutProbe("link.code"))
                if (!beside) QrTile(link.qrPayload, T.account.link.qrDescription, "link.code.qr")
                Text(link.expiresText, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
                ProgressLine(link.progress)
            }
            is DeviceLinkUiState.Failed -> {
                ErrorLine(link.error)
                TortugaButton(T.account.link.newCode, onRestart, Modifier.layoutProbe("link.restart"), style = ButtonStyle.PRIMARY, icon = TortugaIcons.Refresh, fill = true)
            }
        }
    }
}

/**
 * A TV's device-flow sign-in, as a whole page: where to go and the code to enter there, its QR code (beside the page,
 * with how to use it under it, on TV and wide landscape windows; under the code otherwise), the countdown, the flow's
 * progress; the header's back button (and Back) cancels it, and has focus as the page opens.
 */
@Composable
fun DeviceCodePage(
    title: String,
    intro: String?,
    code: DeviceCodeUiState,
    progress: String,
    onCancel: () -> Unit,
    backProbe: String,
    modifier: Modifier = Modifier,
) {
    val beside = qrBeside()
    val aside: (@Composable () -> Unit)? = if (beside) {
        { QrTile(code.qrPayload, T.account.signIn.deviceCode.qrDescription, "deviceCode.code.qr", caption = code.scanHint) }
    } else {
        null
    }
    AccountPage(title, intro, modifier, onBack = onCancel, backProbe = backProbe, focusBack = true, aside = aside) {
        Text(code.instructions, style = MaterialTheme.typography.bodyLarge)
        CodeText(code.userCode, Modifier.layoutProbe("deviceCode.code"))
        if (!beside) {
            QrTile(code.qrPayload, T.account.signIn.deviceCode.qrDescription, "deviceCode.code.qr")
            Text(code.scanHint, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
        }
        Text(code.expiresText, style = MaterialTheme.typography.bodyMedium, color = TortugaColors.TextMuted)
        ProgressLine(progress)
    }
}

/**
 * A notice at the top of a screen (why this device was signed out, why an action failed), with a dismiss button.
 * TalkBack speaks its text as it shows and as it changes, wherever its focus is.
 */
@Composable
fun NoticeCard(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(TortugaColors.SurfaceHigh, MaterialTheme.shapes.large)
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TortugaIcons.Info, contentDescription = null, Modifier.size(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TortugaIconButton(TortugaIcons.Close, T.nav.dismiss, onDismiss, Modifier.layoutProbe("notice.dismiss"), style = IconButtonStyle.PLAIN)
    }
}
