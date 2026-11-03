package com.edde746.tortuga.ui.streams

import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface

/** The size the metrics are read at; they are returned in ems of it. */
private const val SIZE = 100f

/**
 * Compose's default family on desktop: the first of the names its text looks the sans serif family up by (Compose
 * 1.12's own list, which it keeps internal) that this system has.
 */
private val defaultTypeface: Typeface by lazy {
    val os = System.getProperty("os.name").lowercase()
    val names = when {
        "windows" in os -> listOf("Segoe UI", "Arial")
        "mac" in os -> listOf(".AppleSystemUIFont", "Helvetica Neue", "Helvetica")
        else -> listOf("Noto Sans", "DejaVu Sans", "Arial")
    }
    names.firstNotNullOfOrNull { FontMgr.default.matchFamilyStyle(it, FontStyle.NORMAL) }
        ?: error("none of the sans serif fonts $names is installed")
}

internal actual val defaultFont: FontEms by lazy {
    val metrics = Font(defaultTypeface, SIZE).metrics
    FontEms(-metrics.ascent / SIZE, metrics.descent / SIZE, metrics.capHeight / SIZE, metrics.xHeight / SIZE)
}

internal actual fun fallbackGlyph(symbol: String): FallbackGlyph? {
    val codePoint = symbol.codePointAt(0)
    if (defaultTypeface.getUTF32Glyph(codePoint) != 0.toShort()) return null
    val typeface = FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.NORMAL, null, codePoint) ?: return null
    val font = Font(typeface, SIZE)
    val ink = font.getBounds(shortArrayOf(typeface.getUTF32Glyph(codePoint)))[0]
    val metrics = font.metrics
    return FallbackGlyph(-ink.top / SIZE, -ink.bottom / SIZE, ink.width / SIZE, -metrics.ascent / SIZE, metrics.descent / SIZE)
}
