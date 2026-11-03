package com.edde746.tortuga.ui.image

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import coil3.Bitmap
import coil3.size.Size
import coil3.transform.Transformation

/**
 * Cuts an image to its art ([artBounds]): fanart.tv's clear logos (Cinemeta's) are 800 × 310 canvases with a fifth to a
 * third of their height transparent above and below the art. Coil runs it off the main thread, once per image, and
 * caches what it gives.
 */
internal object TrimToArt : Transformation() {
    override val cacheKey = "trimToArt"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val image = input.toComposeBitmap()
        val art = artBounds(image) ?: return input
        val cut = ImageBitmap(art.width, art.height)
        Canvas(cut).drawImageRect(image, IntOffset(art.left, art.top), IntSize(art.width, art.height), paint = Paint())
        return cut.toCoilBitmap()
    }
}

/**
 * The smallest rectangle of [image] (in pixels) holding every pixel at least 3 % opaque: the rows and columns at its
 * edges with none are cut. Null when that is the whole image, or nothing.
 */
internal fun artBounds(image: ImageBitmap): IntRect? {
    val row = IntArray(image.width)
    var top = -1
    var bottom = -1
    var left = image.width
    var right = -1
    for (y in 0 until image.height) {
        image.readPixels(row, startY = y, height = 1)
        val first = row.indexOfFirst { it ushr 24 >= ART_ALPHA }
        if (first < 0) continue
        if (top < 0) top = y
        bottom = y
        left = minOf(left, first)
        right = maxOf(right, row.indexOfLast { it ushr 24 >= ART_ALPHA })
    }
    if (top < 0 || (top == 0 && left == 0 && bottom == image.height - 1 && right == image.width - 1)) return null
    return IntRect(left, top, right + 1, bottom + 1)
}

/** 3 % of full opacity, rounded up: fainter pixels (a glow's last trace, compression noise) are margin. */
private const val ART_ALPHA = 8
