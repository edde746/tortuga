package com.edde746.tortuga.addons

import com.edde746.tortuga.log.Log
import kotlinx.coroutines.CancellationException

/**
 * [read], a read of the installed addons ([AddonRegistry.current], then the list as it changes); null when it fails,
 * logged under [tag]. The database already read again what found it busy, so the failure stays: a screen ends its
 * loading with `T.addons.unreadable` in its error instead, and playback goes on without the addons (no addon subtitles;
 * the next episode's stream is chosen in the list, which says so).
 */
internal inline fun <T> readingAddons(tag: String, read: () -> T): T? = try {
    read()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.e(tag, e) { "reading the installed addons failed (${e::class.simpleName})" }
    null
}
