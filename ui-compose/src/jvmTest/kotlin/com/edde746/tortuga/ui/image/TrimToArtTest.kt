package com.edde746.tortuga.ui.image

import coil3.size.Size
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.IRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TrimToArtTest {
    @Test
    fun aLogoIsCutToThePixelsAtLeastThreePercentOpaque() = runBlocking {
        // A clear logo's shape scaled down: an 80 × 31 canvas, its art 62 × 17 from (9, 7), a faint pixel at (2, 2).
        val faint = logo { erase(0x07FFFFFF, IRect.makeXYWH(2, 2, 1, 1)) } // 7 of 255: 2.7 %
        val cut = TrimToArt.transform(faint, Size.ORIGINAL)
        assertEquals(62 to 17, cut.width to cut.height)
        assertEquals(ART, cut.getColor(0, 0))
        assertEquals(ART, cut.getColor(61, 16))

        val visible = logo { erase(0x08FFFFFF, IRect.makeXYWH(2, 2, 1, 1)) } // 8 of 255: 3.1 %
        val kept = TrimToArt.transform(visible, Size.ORIGINAL)
        assertEquals(69 to 22, kept.width to kept.height)
        assertEquals(8, kept.getColor(0, 0) ushr 24)
        assertEquals(ART, kept.getColor(7, 5))
    }

    @Test
    fun aFullyTransparentLogoIsLeftAsItIs() = runBlocking {
        val blank = bitmap(80, 31)
        assertSame(blank, TrimToArt.transform(blank, Size.ORIGINAL))
    }

    @Test
    fun aLogoWithNoTransparentMarginIsLeftAsItIs() = runBlocking {
        // Transparent but for one pixel on each edge: its art spans the canvas.
        val edges = bitmap(80, 31).apply {
            for (corner in listOf(IRect.makeXYWH(40, 0, 1, 1), IRect.makeXYWH(0, 15, 1, 1), IRect.makeXYWH(79, 15, 1, 1), IRect.makeXYWH(40, 30, 1, 1))) {
                erase(ART, corner)
            }
        }
        assertSame(edges, TrimToArt.transform(edges, Size.ORIGINAL))
    }

    private fun logo(extra: Bitmap.() -> Unit): Bitmap = bitmap(80, 31).apply {
        erase(ART, IRect.makeXYWH(9, 7, 62, 17))
        extra()
    }

    private fun bitmap(width: Int, height: Int): Bitmap = Bitmap().apply {
        allocN32Pixels(width, height)
        erase(0)
    }

    private companion object {
        /** The art's colour: an opaque amber. */
        const val ART = 0xFFFFB300.toInt()
    }
}
