package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.net.UriComponent
import io.ktor.http.encodeURLParameter

/**
 * A title's link, which Detail's Share sends: `https://usetortuga.app/t/<type>/<id>[?name=<name>]`. The app opens it
 * from [type] and [id] alone, as a `stremio:///detail/<type>/<id>` link, so it works for any addon's ID (an IMDb ID,
 * `kitsu:1`, a custom prefix) wherever the receiving profile has an addon for it. [name] is only for the site's page,
 * which shows it, and is never read in the app.
 *
 * Where Tortuga is installed the OS hands the link to it (Universal Links on iPhone, iPad and the Mac; App Links on
 * Android). Elsewhere the site's page offers `tortuga://detail?type=<type>&id=<id>` ([appUrl]), which opens the title
 * in the running profile. Values are percent-encoded in full but for `A-Z a-z 0-9 - . _ ~`, so a chat app's link
 * detection doesn't cut a link at a `(` or `'`.
 */
data class TitleLink(val type: String, val id: String, val name: String? = null) {
    fun toUrl(): String =
        "https://$HOST/$PATH/${type.encodeURLParameter()}/${id.encodeURLParameter()}" +
            name?.let { "?$NAME=${it.encodeURLParameter()}" }.orEmpty()

    /** The site page's Open in Tortuga link. */
    fun appUrl(): String = "$SCHEME://$DETAIL?type=${type.encodeURLParameter()}&id=${id.encodeURLParameter()}"

    companion object {
        private const val HOST = "usetortuga.app"
        private const val PATH = "t"
        private const val NAME = "name"
        private const val SCHEME = "tortuga"
        private const val DETAIL = "detail"

        /**
         * The title [url] links to: [toUrl]'s form (`http` or `https`, `www.` too) or [appUrl]'s; null for anything else,
         * or one missing its type or ID. A `tortuga://detail` link with a profile is a TV home screen item's
         * ([com.edde746.tortuga.presentation.homescreen.HomeScreenLink]), not this.
         */
        fun parse(url: String): TitleLink? {
            val trimmed = url.trim()
            val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
            val rest = trimmed.substringAfter("://").substringBefore('#')
            val target = rest.substringBefore('?')
            val params = rest.substringAfter('?', missingDelimiterValue = "").split('&').mapNotNull { pair ->
                val name = pair.substringBefore('=', missingDelimiterValue = "")
                if (name.isEmpty()) null else name to UriComponent.decode(pair.substringAfter('='))
            }.toMap()
            return when (scheme) {
                "https", "http" -> {
                    val parts = target.removeSuffix("/").split('/')
                    val host = parts[0].lowercase()
                    if ((host != HOST && host != "www.$HOST") || parts.size != 4 || parts[1] != PATH) return null
                    val (type, id) = parts.drop(2).map(UriComponent::decode)
                    if (type.isEmpty() || id.isEmpty()) return null
                    TitleLink(type, id, params[NAME]?.takeIf { it.isNotEmpty() })
                }
                SCHEME -> {
                    if (target.removeSuffix("/").lowercase() != DETAIL || "profile" in params) return null
                    val type = params["type"]?.takeIf { it.isNotEmpty() } ?: return null
                    val id = params["id"]?.takeIf { it.isNotEmpty() } ?: return null
                    TitleLink(type, id)
                }
                else -> null
            }
        }
    }
}
