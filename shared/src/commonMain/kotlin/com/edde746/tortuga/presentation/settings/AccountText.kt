package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.api.DevicePlatform
import com.edde746.tortuga.sync.api.IdentityProvider
import com.edde746.tortuga.sync.api.ServerInfo
import io.ktor.http.Url

/** Worded account facts for the settings screens. */
internal object AccountText {
    /** The kind of device a [DevicePlatform] value names ("Android TV"), or the value itself for an unknown one. */
    fun platform(platform: String): String = when (platform) {
        DevicePlatform.IOS -> T.settings.account.platform.ios
        DevicePlatform.IPADOS -> T.settings.account.platform.ipados
        DevicePlatform.MACOS -> T.settings.account.platform.macos
        DevicePlatform.TVOS -> T.settings.account.platform.tvos
        DevicePlatform.ANDROID -> T.settings.account.platform.android
        DevicePlatform.ANDROID_TV -> T.settings.account.platform.androidTv
        DevicePlatform.WINDOWS -> T.settings.account.platform.windows
        DevicePlatform.LINUX -> T.settings.account.platform.linux
        else -> platform
    }

    /** A sign-in provider's name; the server's own provider goes by the name the server gives it. */
    fun provider(provider: String, info: ServerInfo?): String = when (provider) {
        IdentityProvider.APPLE -> T.settings.account.provider.apple
        IdentityProvider.GOOGLE -> T.settings.account.provider.google
        IdentityProvider.GITHUB -> T.settings.account.provider.github
        IdentityProvider.OIDC -> info?.providers?.firstOrNull { it.id == provider }?.displayName?.takeIf { it.isNotBlank() }
            ?: T.settings.account.provider.oidc
        else -> provider
    }

    /** The host (and port, if not the default) of [serverUrl]: what identifies the server to a person. */
    fun server(serverUrl: String): String = try {
        val url = Url(serverUrl)
        if (url.specifiedPort == 0 || url.specifiedPort == url.protocol.defaultPort) url.host else "${url.host}:${url.specifiedPort}"
    } catch (_: Exception) {
        serverUrl
    }

    /** How long ago [thenMillis] was: "just now", "5 minutes ago", "3 days ago", "2 years ago". */
    fun ago(thenMillis: Long, nowMillis: Long): String {
        val minutes = ((nowMillis - thenMillis) / 60_000).coerceAtLeast(0)
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> T.settings.time.justNow
            minutes < 60 -> T.settings.time.minutesAgo(minutes.toInt())
            hours < 24 -> T.settings.time.hoursAgo(hours.toInt())
            days < 30 -> T.settings.time.daysAgo(days.toInt())
            days < 365 -> T.settings.time.monthsAgo((days / 30).toInt())
            else -> T.settings.time.yearsAgo((days / 365).toInt())
        }
    }

    /** When [ago] of [thenMillis] next reads otherwise after [nowMillis]: the next minute, hour, day, month or year. */
    fun agoChangesAt(thenMillis: Long, nowMillis: Long): Long {
        val minutes = ((nowMillis - thenMillis) / MINUTE).coerceAtLeast(0)
        val hours = minutes / 60
        val days = hours / 24
        return thenMillis + when {
            minutes < 60 -> (minutes + 1) * MINUTE
            hours < 24 -> (hours + 1) * HOUR
            days < 30 -> (days + 1) * DAY
            days < 365 -> minOf((days / 30 + 1) * 30, 365) * DAY
            else -> (days / 365 + 1) * 365 * DAY
        }
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
}
