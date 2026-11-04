package com.edde746.tortuga.ui.image

import coil3.BitmapImage
import coil3.PlatformContext
import coil3.decode.ImageSource
import coil3.request.Options
import coil3.size.Scale
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilteredSkiaDecoderTest {
    @Test
    fun anImageShrunkToItsViewAveragesItsPixelsInsteadOfPickingThem() = runBlocking {
        // An episode thumbnail's size in black and white columns 1 px wide, the finest detail an image has: shrunk to
        // the 299 × 168 a thumbnail is drawn at 150 %, picking pixels leaves black and white bands; averaging, grey.
        val width = 780
        val height = 439
        // Opaque: every fourth byte (alpha) full.
        val pixels = ByteArray(width * height * 4) { if (it % 4 == 3 || (it / 4) % width % 2 == 1) -1 else 0 }
        val png = Image.makeRaster(ImageInfo.makeN32Premul(width, height), pixels, width * 4)
            .encodeToData(EncodedImageFormat.PNG)!!.bytes
        val options = Options(PlatformContext.INSTANCE, size = Size(299, 168), scale = Scale.FILL)

        val result = FilteredSkiaDecoder(ImageSource(Buffer().write(png), FileSystem.SYSTEM), options).decode()

        val bitmap = (result.image as BitmapImage).bitmap
        assertEquals(299 to 168, bitmap.width to bitmap.height)
        val decoded = bitmap.readPixels()!!
        val off = decoded.indices.filter { it % 4 != 3 }.map { decoded[it].toInt() and 0xFF }.filter { it !in 112..143 }
        assertTrue(off.isEmpty(), "${off.size} channel values off grey, e.g. ${off.take(8)}")
    }

    /** A logo far wider than tall (2000 × 20) fitted into a 40 dp square keeps a pixel of height, rather than none. */
    @Test
    fun aVeryWideImageShrunkToASmallViewKeepsAPixelOfHeight() = runBlocking {
        val png = Image.makeRaster(ImageInfo.makeN32Premul(2000, 20), ByteArray(2000 * 20 * 4) { -1 }, 2000 * 4)
            .encodeToData(EncodedImageFormat.PNG)!!.bytes
        val options = Options(PlatformContext.INSTANCE, size = Size(40, 40), scale = Scale.FIT)

        val result = FilteredSkiaDecoder(ImageSource(Buffer().write(png), FileSystem.SYSTEM), options).decode()

        val bitmap = (result.image as BitmapImage).bitmap
        assertEquals(40 to 1, bitmap.width to bitmap.height)
    }
}
