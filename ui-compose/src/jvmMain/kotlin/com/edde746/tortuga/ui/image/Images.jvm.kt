package com.edde746.tortuga.ui.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import coil3.Bitmap
import coil3.ComponentRegistry

internal actual fun Bitmap.toComposeBitmap(): ImageBitmap = asComposeImageBitmap()

internal actual fun ImageBitmap.toCoilBitmap(): Bitmap = asSkiaBitmap()

internal actual fun ComponentRegistry.Builder.addDecoders() {
    add(FilteredSkiaDecoder.Factory())
}
