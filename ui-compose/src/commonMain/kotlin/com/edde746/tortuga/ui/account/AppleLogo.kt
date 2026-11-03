package com.edde746.tortuga.ui.account

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.ui.icons.TortugaIcons
import com.edde746.tortuga.ui.icons.pathNodes

/**
 * The icon of [provider]'s sign-in and link buttons and of its linked sign-in in the account settings: Apple's logo
 * for Apple, GitHub's mark for GitHub, the generic sign-in icon otherwise (Google's draw [providerLogo] in its place).
 */
internal fun providerIcon(provider: String): ImageVector = when (provider) {
    IdentityProvider.APPLE -> AppleLogo
    IdentityProvider.GITHUB -> GitHubMark
    else -> TortugaIcons.Login
}

/**
 * The Apple logo of Sign in with Apple's buttons on Android, Windows and Linux (the Apple apps draw the system's
 * `apple.logo`). Its path is the one in the "Logo - SIWA - Logo-only" SVG of Apple's Sign in with Apple logo kit
 * (https://devimages-cdn.apple.com/design/resources/download/Logo-Sign-in-with-Apple.dmg), unchanged; the viewport is
 * the logo's bounds (the SVG's 56-unit viewBox is a button around it), so the logo fills an icon's box.
 *
 * It is Apple's artwork under the kit's licence, not this app's GPL. Section 2A allows it in the interface of an app
 * for Apple's systems and of the app's versions for other platforms with the same title and equivalent features, to
 * show that the app signs in with Apple, following Apple's Human Interface Guidelines. Those want the logo black or
 * white, in the button title's colour: draw it with a tinting `Icon` (TortugaButton's `icon`).
 */
internal val AppleLogo: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(name = "AppleLogo", defaultWidth = 15.dp, defaultHeight = 19.dp, viewportWidth = 15f, viewportHeight = 19f)
        .addGroup(translationX = -20.5f, translationY = -16f)
        .addPath(pathNodes(APPLE_LOGO_PATH), fill = SolidColor(Color.White))
        .build()
}

/** The kit's path, in its SVG's viewBox: the logo spans x 20.5–35.5 and y 16–35. */
private const val APPLE_LOGO_PATH =
    "M28.2226562,20.3846154 C29.0546875,20.3846154 30.0976562,19.8048315 30.71875,19.0317864 " +
        "C31.28125,18.3312142 31.6914062,17.352829 31.6914062,16.3744437 " +
        "C31.6914062,16.2415766 31.6796875,16.1087095 31.65625,16 " +
        "C30.7304687,16.0362365 29.6171875,16.640178 28.9492187,17.4494596 " +
        "C28.421875,18.06548 27.9414062,19.0317864 27.9414062,20.0222505 " +
        "C27.9414062,20.1671964 27.9648438,20.3121424 27.9765625,20.3604577 " +
        "C28.0351562,20.3725366 28.1289062,20.3846154 28.2226562,20.3846154 Z M25.2929688,35 " +
        "C26.4296875,35 26.9335938,34.214876 28.3515625,34.214876 " +
        "C29.7929688,34.214876 30.109375,34.9758423 31.375,34.9758423 " +
        "C32.6171875,34.9758423 33.4492188,33.792117 34.234375,32.6325493 " +
        "C35.1132812,31.3038779 35.4765625,29.9993643 35.5,29.9389701 " +
        "C35.4179688,29.9148125 33.0390625,28.9122695 33.0390625,26.0979021 " +
        "C33.0390625,23.6579784 34.9140625,22.5588048 35.0195312,22.474253 " +
        "C33.7773438,20.6382708 31.890625,20.5899555 31.375,20.5899555 " +
        "C29.9804688,20.5899555 28.84375,21.4596313 28.1289062,21.4596313 " +
        "C27.3554688,21.4596313 26.3359375,20.6382708 25.1289062,20.6382708 " +
        "C22.8320312,20.6382708 20.5,22.5950413 20.5,26.2911634 " +
        "C20.5,28.5861411 21.3671875,31.013986 22.4335938,32.5842339 " +
        "C23.3476562,33.9129053 24.1445312,35 25.2929688,35 Z"
