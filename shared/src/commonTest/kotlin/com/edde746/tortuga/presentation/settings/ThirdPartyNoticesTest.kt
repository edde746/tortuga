package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.sync.api.DevicePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ThirdPartyNoticesTest {
    private val apple = setOf("SKIE", "Nuke", "SQLiter", "Stately", "Sentry Cocoa SDK")

    /** iPhone's, iPad's and the Mac's, never Apple TV's, which signs in to Google by its device flow. */
    private val googleSignIn = setOf(
        "Google Sign-In", "AppAuth", "GTMAppAuth", "GTM Session Fetcher", "App Check", "GoogleUtilities", "Promises",
    )
    private val mac = setOf("Sparkle")
    private val android = setOf(
        "AndroidX Core", "AndroidX Activity", "AndroidX Browser", "Guava", "Accompanist", "libmpv-android", "LLVM libc++",
        "Sentry Android SDK", "Epitaph", "Sentry Native SDK", "libunwindstack", "Google Play services", "googleid",
    )
    private val desktop = setOf(
        "Skiko", "Android Open Source Project", "Skia", "ICU", "libpng", "libjpeg-turbo", "libwebp", "zlib", "Expat", "Wuffs",
        "SQLite JDBC", "SQLite", "kotlinx-datetime", "JetBrains Runtime API",
    )
    private val windows = setOf("OpenJDK", "SPIRV-Cross", "D3D12 Memory Allocator")
    private val linux = setOf(
        "JetBrains Runtime", "dbus-java", "Noto Sans", "Lua", "MuJS", "libdisplay-info", "bzip2", "piex", "DNG SDK",
    )

    /** Android's and the desktop's, never Apple's: Compose and the JVM libraries. */
    private val compose = setOf(
        "Compose Multiplatform", "Material 3 for Compose", "Navigation 3", "Lifecycle", "AndroidX", "Coil", "ZXing", "OkHttp", "Okio",
        "Public Suffix List", "SLF4J", "JSpecify", "JetBrains Java Annotations", "Sentry Java SDK",
    )

    /** Android's and Apple's, never the desktop's: the subtitle font libass falls back to. */
    private val subtitleFont = setOf("Go Noto Current")

    /** Every platform's: the player stack, the shared core. */
    private val common = setOf("mpv", "FFmpeg", "libass", "mpv-build", "Plezy", "stremio-official-addons", "Kotlin", "Ktor", "SQLDelight")

    private fun names(platform: String) = ThirdPartyNotices.forPlatform(platform).map { it.name }

    @Test
    fun eachPlatformListsItsOwnLibrariesAndNoneOfAnothers() {
        val own = listOf(DevicePlatform.IOS, DevicePlatform.IPADOS).associateWith { apple + googleSignIn + subtitleFont } +
            mapOf(DevicePlatform.MACOS to apple + googleSignIn + mac + subtitleFont, DevicePlatform.TVOS to apple + subtitleFont) +
            listOf(DevicePlatform.ANDROID, DevicePlatform.ANDROID_TV).associateWith { compose + android + subtitleFont } +
            mapOf(DevicePlatform.WINDOWS to compose + desktop + windows, DevicePlatform.LINUX to compose + desktop + linux)
        val platformOnly = apple + googleSignIn + mac + android + desktop + windows + linux + compose + subtitleFont
        for ((platform, expected) in own) {
            val names = names(platform)
            assertEquals(names.distinct(), names, "$platform lists each notice once")
            assertTrue(names.containsAll(common + expected), "$platform misses ${(common + expected) - names.toSet()}")
            assertEquals(emptySet(), names.toSet().intersect(platformOnly - expected), "$platform lists another platform's")
        }
    }

    @Test
    fun anUnknownPlatformHasNoList() {
        assertFailsWith<IllegalArgumentException> { ThirdPartyNotices.forPlatform("webos") }
    }
}
