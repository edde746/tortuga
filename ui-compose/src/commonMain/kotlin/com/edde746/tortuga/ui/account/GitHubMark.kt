package com.edde746.tortuga.ui.account

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.edde746.tortuga.ui.icons.pathNodes

/**
 * GitHub's mark, the Invertocat, on the GitHub sign-in and link buttons and on a linked GitHub sign-in in the account
 * settings. Its path is the one in `GitHub Logos/SVG/GitHub_Invertocat_White.svg` of GitHub's logo files
 * (https://brand.github.com/GitHub_Logos.zip, linked from https://github.com/logos), unchanged, in the SVG's 98 × 96
 * viewBox, which the mark fills.
 *
 * It is GitHub's trademark, not this app's GPL. GitHub's brand guidelines allow a GitHub logo to show that a product
 * integrates with GitHub, unmodified and in white, black or grey: draw it with a tinting `Icon` (TortugaButton's
 * `icon`), white on the dark buttons and black on the primary one, as the Apple logo.
 */
internal val GitHubMark: ImageVector by lazy(LazyThreadSafetyMode.NONE) {
    ImageVector.Builder(name = "GitHubMark", defaultWidth = 20.dp, defaultHeight = 19.6.dp, viewportWidth = 98f, viewportHeight = 96f)
        .addPath(pathNodes(GITHUB_MARK_PATH), fill = SolidColor(Color.White))
        .build()
}

/** The SVG's path. */
private const val GITHUB_MARK_PATH =
    "M41.4395 69.3848C28.8066 67.8535 19.9062 58.7617 19.9062 46.9902C19.9062 42.2051 21.6289 37.0371 24.5 33.5918" +
        "C23.2559 30.4336 23.4473 23.7344 24.8828 20.959C28.7109 20.4805 33.8789 22.4902 36.9414 25.2656" +
        "C40.5781 24.1172 44.4062 23.543 49.0957 23.543C53.7852 23.543 57.6133 24.1172 61.0586 25.1699" +
        "C64.0254 22.4902 69.2891 20.4805 73.1172 20.959C74.457 23.543 74.6484 30.2422 73.4043 33.4961" +
        "C76.4668 37.1328 78.0937 42.0137 78.0937 46.9902C78.0937 58.7617 69.1934 67.6621 56.3691 69.2891" +
        "C59.623 71.3945 61.8242 75.9883 61.8242 81.252L61.8242 91.2051C61.8242 94.0762 64.2168 95.7031 67.0879 94.5547" +
        "C84.4102 87.9512 98 70.6289 98 49.1914C98 22.1074 75.9883 6.69539e-07 48.9043 4.309e-07" +
        "C21.8203 1.92261e-07 -1.9479e-07 22.1074 -4.3343e-07 49.1914C-6.20631e-07 70.4375 13.4941 88.0469 31.6777 94.6504" +
        "C34.2617 95.6074 36.75 93.8848 36.75 91.3008L36.75 83.6445C35.4102 84.2188 33.6875 84.6016 32.1562 84.6016" +
        "C25.8398 84.6016 22.1074 81.1563 19.4277 74.7441C18.375 72.1602 17.2266 70.6289 15.0254 70.3418" +
        "C13.877 70.2461 13.4941 69.7676 13.4941 69.1934C13.4941 68.0449 15.4082 67.1836 17.3223 67.1836" +
        "C20.0977 67.1836 22.4902 68.9063 24.9785 72.4473C26.8926 75.2227 28.9023 76.4668 31.2949 76.4668" +
        "C33.6875 76.4668 35.2187 75.6055 37.4199 73.4043C39.0469 71.7773 40.291 70.3418 41.4395 69.3848Z"
