package com.edde746.tortuga.ui.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.Bitmap
import coil3.ComponentRegistry

internal actual fun Bitmap.toComposeBitmap(): ImageBitmap = asImageBitmap()

// A hardware copy, as Coil's own decodes are: the software bitmap would sit in the heap and again as a texture once drawn.
internal actual fun ImageBitmap.toCoilBitmap(): Bitmap {
    val software = asAndroidBitmap()
    return software.copy(android.graphics.Bitmap.Config.HARDWARE, false)?.also { software.recycle() } ?: software
}

// Coil's own: Android's ImageDecoder (BitmapFactory before API 29) shrinks a JPEG in the codec and the rest bilinearly.
internal actual fun ComponentRegistry.Builder.addDecoders() = Unit
