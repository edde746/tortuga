package com.edde746.tortuga.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.Bitmap
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import coil3.transform.Transformation
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.board.FolderTile
import com.edde746.tortuga.ui.components.aspectRatio
import com.edde746.tortuga.ui.components.overlayOpener
import com.edde746.tortuga.ui.focus.focusRing
import com.edde746.tortuga.ui.image.RemoteImage
import com.edde746.tortuga.ui.image.toCoilBitmap
import com.edde746.tortuga.ui.image.toComposeBitmap
import com.edde746.tortuga.ui.probe.layoutProbe
import com.edde746.tortuga.ui.theme.LocalTortugaDimens
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.contentDirection
import com.edde746.tortuga.ui.theme.hyphenated
import kotlin.math.abs

/**
 * A collection's folder on Home: its cover in its row's [shape] (the collection's: poster, landscape or square, as
 * wide as Home's cards of that shape, so every tile of a row has one size), a still of the image (a GIF shows its
 * first frame). A cover within [COVER_TOLERANCE] of the tile's aspect is cropped from its centre to fill the tile;
 * one further off is drawn whole in the middle, over its own [CoverFill] (so the bars are the cover's colours).
 * Without a cover, the title in the middle of a tile tinted from the title ([folderTint]). Under it the caption line:
 * the title, or nothing when the folder hides it
 * ([FolderTile.showTitle]) or the tile already shows it, its room kept so the captions of a row line up (harness name
 * `<probe>.caption`); then the note of a folder whose sources can't show ([FolderTile.note]: "Needs the addon
 * AIOMetadata") on one muted line. Focus shows as on a poster card (the ring, and on TV the growth). Screen readers
 * take the tile as one button named by its title, the note its state. Harness name: [probe].
 */
@Composable
internal fun FolderTileCard(tile: FolderTile, shape: PosterShape, onClick: () -> Unit, probe: String, modifier: Modifier = Modifier) {
    val dimens = LocalTortugaDimens.current
    val corner = MaterialTheme.shapes.medium
    val cover = tile.coverUrl
    Column(modifier.layoutProbe(probe).width(dimens.cardWidth(shape))) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(shape.aspectRatio)
                .focusRing(corner)
                .clip(corner)
                .background(if (cover == null) folderTint(tile.title) else TortugaColors.SurfaceHigh)
                .semantics {
                    contentDescription = tile.title
                    tile.note?.let { stateDescription = it }
                }
                .overlayOpener()
                .clickable(role = Role.Button, onClick = onClick),
        ) {
            if (cover != null) {
                // The cover's width over height, once loaded: until then it fills the tile as most covers do.
                var aspect by remember(cover) { mutableStateOf<Float?>(null) }
                val whole = aspect?.let { abs(it / shape.aspectRatio - 1f) > COVER_TOLERANCE } == true
                if (whole) CoverFill(cover, Modifier.matchParentSize())
                RemoteImage(
                    cover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (whole) ContentScale.Fit else ContentScale.Crop,
                    alignment = Alignment.Center,
                    blank = TortugaColors.SurfaceHigh,
                    onAspect = { aspect = it },
                )
            } else {
                Text(
                    tile.title,
                    modifier = Modifier.padding(12.dp).semantics { hideFromAccessibility() },
                    style = MaterialTheme.typography.titleMedium.contentDirection().hyphenated(),
                    color = TortugaColors.Text,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(if (dimens.focusGrows) 10.dp else 6.dp))
        val hidden = Modifier.semantics { hideFromAccessibility() }
        Text(
            // A space when there's no caption: an empty text lays out with no height.
            if (tile.showTitle && cover != null) tile.title else " ",
            modifier = hidden.layoutProbe("$probe.caption"),
            style = MaterialTheme.typography.titleSmall.contentDirection().hyphenated(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        tile.note?.let { note ->
            Text(note, modifier = hidden, style = MaterialTheme.typography.bodySmall, color = TortugaColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** How far (as a fraction) a cover's aspect may be from its tile's and still be cropped to fill it. */
private const val COVER_TOLERANCE = 0.05f

/** The long side, in pixels, of the cover [CoverFill] scales up: small enough that the scaling blurs it. */
private const val FILL_PIXELS = 16

/** How much of [CoverFill]'s light is left: dimmed, so the cover in front of it stands out. */
private const val FILL_LIGHT = 0.7f

/**
 * The bars behind a cover drawn whole: the cover itself decoded at [FILL_PIXELS] on its long side (its own memory cache
 * entry), scaled up to fill with bilinear filtering, which blurs it, and dimmed to [FILL_LIGHT]. It draws pixels, not
 * a blur effect, so it looks the same on every Android version (a Compose blur does nothing before Android 12) and
 * holds a few hundred bytes. A cover with transparency (a logo) leaves it empty ([OpaqueOnly]): its blurred shape
 * behind it would ring its edges, so it stands on the tile's grey instead.
 */
@Composable
private fun CoverFill(url: String, modifier: Modifier) {
    val context = LocalPlatformContext.current
    val request = remember(url, context) {
        ImageRequest.Builder(context)
            .data(url)
            .size(FILL_PIXELS)
            .scale(Scale.FIT)
            .precision(Precision.EXACT)
            .memoryCacheKey("folderFill:$url")
            .transformations(OpaqueOnly)
            .crossfade(false)
            .build()
    }
    val dim = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(FILL_LIGHT, FILL_LIGHT, FILL_LIGHT, 1f) }) }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        colorFilter = dim,
        filterQuality = FilterQuality.Low,
    )
}

/** The image as it is when every pixel of it is opaque; else one transparent pixel, so nothing is drawn. */
private object OpaqueOnly : Transformation() {
    override val cacheKey = "opaqueOnly"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val image = input.toComposeBitmap()
        val row = IntArray(image.width)
        for (y in 0 until image.height) {
            image.readPixels(row, startY = y, height = 1)
            if (row.any { it ushr 24 < OPAQUE }) return ImageBitmap(1, 1).toCoilBitmap()
        }
        return input
    }

    /** The least alpha a pixel counts as opaque at (of 255): an edge's antialiasing is transparency. */
    private const val OPAQUE = 250
}

/** A dark tile colour of its own for [title]: the same title gets the same hue on every device. */
internal fun folderTint(title: String): Color = Color.hsv(hue = (title.hashCode().mod(360)).toFloat(), saturation = 0.35f, value = 0.3f)
