package com.edde746.tortuga.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edde746.tortuga.strings.InterfaceLocale
import com.edde746.tortuga.ui.focus.FocusVisibleRipple

/**
 * The OLED palette: true black screens, neutral greys for everything raised, near-white text and
 * primary fills with black content. No hue anywhere in the UI (the app icon keeps its brand colours); outlines only on
 * text inputs, dividers only as hairlines.
 */
object TortugaColors {
    /** Every screen, bar, rail and the TV navigation. */
    val Background = Color(0xFF000000)

    /** Sheets and panels. */
    val Surface = Color(0xFF0F0F0F)

    /** Solid chips, secondary buttons, icon buttons, grouped rows, badges. */
    val SurfaceHigh = Color(0xFF1C1C1C)

    /**
     * Dropdown menus, which open over [SurfaceHigh] rows; hovered or pressed [SurfaceHigh] elements;
     * selected-but-not-primary states.
     */
    val SurfaceHighest = Color(0xFF2A2A2A)

    /** Text and icons. */
    val Text = Color(0xFFEDEDED)

    /** Secondary text: captions, hints, values. */
    val TextMuted = Text.copy(alpha = 0.6f)

    /** Disabled text and placeholder icons. */
    val TextFaint = Text.copy(alpha = 0.38f)

    /**
     * Hovered or pressed, a control that rests transparent or translucent (an icon button over a picture or the video, a
     * text button, a menu's row) takes this tint over what lies under it, never a solid surface: over [Background] it
     * is [SurfaceHigh], over a picture it lets the picture through.
     */
    val HoverTint = Text.copy(alpha = 0.12f)

    /** Primary buttons, selected chips, focused TV controls; [OnPrimary] content on it. */
    val Primary = Text
    val OnPrimary = Color(0xFF000000)

    /** Dividers only: never an outline on a chip, button or badge. */
    val Hairline = Color.White.copy(alpha = 0.12f)

    val Error = Color(0xFFFF453A)

    /** The player's live dot, beside LIVE in [OnVideo]: the one red of a live playback. */
    val Live = Color(0xFFE5251B)

    /** The track under the player's timeline and volume, filled in [OnVideo]. */
    val ProgressTrack = Color.White.copy(alpha = 0.24f)

    /**
     * Progress on artwork (posters, thumbnails): [ArtworkProgressFill] on [ArtworkProgressTrack], over [ArtworkScrim]
     * at the artwork's bottom edge fading to nothing, so it reads on light artwork too.
     */
    val ArtworkProgressFill = Color.White
    val ArtworkProgressTrack = Color.White.copy(alpha = 0.3f)
    val ArtworkScrim = Color.Black.copy(alpha = 0.6f)

    /**
     * The rest of a control over a picture (Home's header over its backdrop, Detail's back button and actions): grey at
     * 60 %, the picture showing through. Over the black page it is [SurfaceHigh]; over a white picture its [Text]
     * content keeps 3.3:1.
     */
    val PictureButton = Color(0xFF2F2F2F).copy(alpha = 0.6f)

    /** Behind a sheet or dialog. */
    val Scrim = Color.Black.copy(alpha = 0.6f)

    /**
     * Text, icons and primary fills over the video (the player's controls, menus and panels, through [OnVideoTheme]):
     * pure white, not [Text]'s near-white.
     */
    val OnVideo = Color.White

    /** Secondary text over the video: [OnVideo] at [TextMuted]'s alpha. */
    val OnVideoMuted = OnVideo.copy(alpha = 0.6f)
}

/**
 * Material's roles mapped onto [TortugaColors], with [text] in every role of text, icons and primary fills, every role
 * set so no Material baseline (purple) tone leaks into a component's defaults (menus, switches, text fields).
 */
private fun colorScheme(text: Color) = darkColorScheme(
    primary = text,
    onPrimary = TortugaColors.OnPrimary,
    primaryContainer = TortugaColors.SurfaceHighest,
    onPrimaryContainer = text,
    inversePrimary = TortugaColors.OnPrimary,
    secondary = text,
    onSecondary = TortugaColors.OnPrimary,
    secondaryContainer = TortugaColors.SurfaceHighest,
    onSecondaryContainer = text,
    tertiary = text,
    onTertiary = TortugaColors.OnPrimary,
    tertiaryContainer = TortugaColors.SurfaceHighest,
    onTertiaryContainer = text,
    background = TortugaColors.Background,
    onBackground = text,
    surface = TortugaColors.Background,
    onSurface = text,
    surfaceVariant = TortugaColors.SurfaceHigh,
    onSurfaceVariant = text.copy(alpha = 0.6f),
    surfaceTint = Color.Transparent,
    inverseSurface = text,
    inverseOnSurface = TortugaColors.OnPrimary,
    error = TortugaColors.Error,
    onError = TortugaColors.OnPrimary,
    errorContainer = Color(0xFF3D0F0C),
    onErrorContainer = text,
    // Text fields are the one outlined element; dividers are hairlines.
    outline = text.copy(alpha = 0.38f),
    outlineVariant = TortugaColors.Hairline,
    scrim = Color.Black,
    surfaceBright = TortugaColors.SurfaceHighest,
    surfaceDim = TortugaColors.Background,
    surfaceContainerLowest = TortugaColors.Background,
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = TortugaColors.Surface,
    surfaceContainerHigh = TortugaColors.SurfaceHigh,
    surfaceContainerHighest = TortugaColors.SurfaceHighest,
)

private val colors = colorScheme(TortugaColors.Text)
private val onVideoColors = colorScheme(TortugaColors.OnVideo)

/** Material's type scale with heavier titles, which read better over video and from across a room. */
private val typography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.4.sp),
    )
}

/**
 * [this] with every style's text in [locales]: Compose shapes a style's text and picks its fallback fonts by the
 * platform's locales unless the style names its own, and the interface language needn't be the device's (Japanese
 * kanji in their Japanese forms, not Chinese, on a device set to English).
 */
private fun Typography.inLocale(locales: LocaleList): Typography {
    fun TextStyle.localized() = copy(localeList = locales)
    return copy(
        displayLarge = displayLarge.localized(), displayMedium = displayMedium.localized(), displaySmall = displaySmall.localized(),
        headlineLarge = headlineLarge.localized(), headlineMedium = headlineMedium.localized(), headlineSmall = headlineSmall.localized(),
        titleLarge = titleLarge.localized(), titleMedium = titleMedium.localized(), titleSmall = titleSmall.localized(),
        bodyLarge = bodyLarge.localized(), bodyMedium = bodyMedium.localized(), bodySmall = bodySmall.localized(),
        labelLarge = labelLarge.localized(), labelMedium = labelMedium.localized(), labelSmall = labelSmall.localized(),
    )
}

/**
 * [this] for content text: text from outside the app (an addon's titles, descriptions and names, a file's track and
 * chapter titles, a name someone typed), which can be in any script whatever the interface's language. Each paragraph
 * takes its direction from its first letter ([TextDirection.Content]; the interface's where it has none), so an English
 * description reads left to right in Arabic with its full stop at its end, and its lines keep to the interface's start
 * edge, level with the text around it ([TextAlign.Start] and [TextAlign.End] become the layout's left or right). The
 * typography's own direction, the interface's, stays on our strings and on those we build around content text: an
 * Arabic sentence that opens on "Trakt" or on a name still reads right to left.
 */
@Composable
@ReadOnlyComposable
fun TextStyle.contentDirection(): TextStyle {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val align = when (textAlign) {
        TextAlign.Unspecified, TextAlign.Start -> if (rtl) TextAlign.Right else TextAlign.Left
        TextAlign.End -> if (rtl) TextAlign.Left else TextAlign.Right
        else -> textAlign
    }
    return copy(textAlign = align, textDirection = TextDirection.Content)
}

/**
 * [this] for text that wraps in a narrow card (a poster's title and caption, a profile tile's name): a word wider than
 * the card ("Осталось" at Android's largest font scale on a phone's 104 dp card) breaks between its syllables with a
 * hyphen, by the rules of the app's language, rather than at whichever letter fills the line. Android only: Compose
 * Desktop's Skia paragraphs don't read [Hyphens] (1.12.1), so there such a word still breaks at any letter.
 */
fun TextStyle.hyphenated(): TextStyle = copy(hyphens = Hyphens.Auto)
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TortugaTheme(content: @Composable () -> Unit) = ColorsTheme(colors, content)

/**
 * [TortugaTheme] for what is drawn over the video (the player's overlay, and the sheets it opens): [TortugaColors.OnVideo]
 * in every role that has [TortugaColors.Text] elsewhere, so the text, icons, timeline, primary and focus fills in it,
 * Material's components and every [TortugaSurface][com.edde746.tortuga.ui.components.TortugaSurface] are pure white
 * without naming a colour.
 */
@Composable
fun OnVideoTheme(content: @Composable () -> Unit) = ColorsTheme(onVideoColors, content)

/**
 * The app's type, shapes and motion in [colors], with their text colour as the content colour wherever no Surface sets
 * its own (screens drawn on the background), the type in the interface language ([InterfaceLocale]). A sheet or dialog,
 * which the app's root composes, takes the [colors] of where it opens through it. Clickable elements without an
 * indication of their own get Material's ripple, its focus layer drawn only while focus shows ([FocusVisibleRipple]).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ColorsTheme(colors: ColorScheme, content: @Composable () -> Unit) {
    val language by InterfaceLocale.tag.collectAsState()
    val localized = remember(language) { typography.inLocale(LocaleList(language)) }
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), typography = localized, shapes = shapes) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, LocalIndication provides FocusVisibleRipple, content = content)
    }
}
