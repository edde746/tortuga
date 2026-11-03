package com.edde746.tortuga.ui.image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import coil3.Bitmap
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.CrossfadePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.decode.DataSource
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.edde746.tortuga.ui.probe.perfImage
import io.ktor.client.HttpClient

/**
 * Makes Coil load images through the app's [http] client (`AppGraph.http`: its User-Agent, proxy and TLS settings)
 * and decode them through the platform's [addDecoders], with a short crossfade ([RemoteImage] fades its own in). Call
 * once, at the root of the app, before any [RemoteImage].
 */
// setSingletonImageLoaderFactory is Coil's documented Compose Multiplatform setup; it is marked experimental.
@OptIn(ExperimentalCoilApi::class)
@Composable
fun ProvideImageLoader(http: HttpClient) {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = http))
                addDecoders()
            }
            .crossfade(true)
            .build()
    }
}

/** This platform's decoders, ahead of Coil's own: each image decoded at the size it is drawn, shrunk without aliasing. */
internal expect fun ComponentRegistry.Builder.addDecoders()

/**
 * An image from [url] (posters, backgrounds, logos), cropped to the bounds by default, placed by [alignment] where it
 * doesn't fill them; without [url], or once it fails to load, the one from [fallbackUrl] instead (unless it is the
 * same). [fallback] shows while there is no URL, while it loads and when it fails, so a card is never an empty box;
 * [blank] (a card's own fill) fills the bounds under it. A loaded image fades in over [blank] ([fadingIn]), which goes
 * when the fade ends: nothing is drawn under an image once it is in. [blurred] shows [url]'s image blurred past
 * recognition ([StillBlur]); [fallbackUrl]'s stays sharp. Its first successful load marks `image` to the perf probe
 * ([perfImage]); each gives [onAspect] the image's width over height.
 */
@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    blank: Color = Color.Unspecified,
    fallbackUrl: String? = null,
    blurred: Boolean = false,
    onAspect: ((Float) -> Unit)? = null,
    fallback: @Composable () -> Unit = {},
) {
    var failed by remember(url, fallbackUrl) { mutableStateOf(false) }
    val shown = if (failed || url == null) fallbackUrl else url
    val blur = blurred && shown == url
    var loaded by remember(shown) { mutableStateOf(false) }
    Box(if (loaded || blank.isUnspecified) modifier.perfImage(loaded) else modifier.perfImage(loaded).background(blank)) {
        if (!loaded) fallback()
        if (shown != null) {
            // Coil's crossfade fades an image in over nothing, whatever is behind the card: fadingIn's is over the blank.
            val context = LocalPlatformContext.current
            val request = remember(shown, context, blur) {
                ImageRequest.Builder(context).data(shown).crossfade(false).apply { if (blur) transformations(StillBlur) }.build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = Modifier.matchParentSize(),
                transform = { it.fadingIn(blank, contentScale) },
                alignment = alignment,
                contentScale = contentScale,
                onState = {
                    loaded = it is AsyncImagePainter.State.Success
                    if (it is AsyncImagePainter.State.Success && onAspect != null) {
                        val image = it.result.image
                        if (image.width > 0 && image.height > 0) onAspect(image.width.toFloat() / image.height)
                    }
                    if (it is AsyncImagePainter.State.Error && !failed && fallbackUrl != null && fallbackUrl != url) failed = true
                },
            )
        }
    }
}

/**
 * An image just loaded from the network or the disk fading in over 200 ms over [blank], which stays whole under it
 * until the fade ends and is then dropped ([CrossfadePainter] lets go of its start). One from the memory cache shows at
 * once, as with Coil's crossfade.
 */
private fun AsyncImagePainter.State.fadingIn(blank: Color, contentScale: ContentScale): AsyncImagePainter.State =
    if (this is AsyncImagePainter.State.Success && result.dataSource != DataSource.MEMORY_CACHE) {
        val start = if (blank.isSpecified) ColorPainter(blank) else null
        copy(painter = CrossfadePainter(start, painter, contentScale, fadeStart = false, preferEndFirstIntrinsicSize = true))
    } else {
        this
    }

/** Coil's software bitmap (a [coil3.transform.Transformation]'s input) as Compose's, to draw it into another. */
internal expect fun Bitmap.toComposeBitmap(): ImageBitmap

/** Compose's bitmap as Coil's (a [coil3.transform.Transformation]'s output); on Android a hardware bitmap. */
internal expect fun ImageBitmap.toCoilBitmap(): Bitmap
