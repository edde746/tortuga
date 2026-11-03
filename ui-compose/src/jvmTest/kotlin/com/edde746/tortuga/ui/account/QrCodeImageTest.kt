package com.edde746.tortuga.ui.account

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.sync.account.DeviceLinkQr
import com.edde746.tortuga.sync.account.ServerUrl
import com.edde746.tortuga.ui.theme.TortugaTheme
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class QrCodeImageTest {
    /** The link page's QR code, drawn at a TV's size in the app's theme, reads back as the link a phone's camera opens. */
    @Test
    fun theSignInQrCodeScansBackToItsPayload() = runComposeUiTest {
        val payload = DeviceLinkQr(ServerUrl.HOSTED, "7ZSNNHKR").encode()
        setContent { TortugaTheme { QrCodeImage(payload, "QR code", 280.dp) } }
        val image = onNodeWithContentDescription("QR code").captureToImage().toAwtImage()
        val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
        assertEquals(payload, QRCodeReader().decode(bitmap).text)
    }
}
