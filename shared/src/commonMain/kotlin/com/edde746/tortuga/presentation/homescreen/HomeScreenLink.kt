package com.edde746.tortuga.presentation.homescreen

import com.edde746.tortuga.net.UriComponent

/**
 * The links a TV home screen's items open ([HomeScreenItem.link], [HomeScreenItem.detailLink]), each in its [profileId]
 * only ([com.edde746.tortuga.app.RootSession.openUrl]): `tortuga://play?profile=…&type=…&id=…&video=…` resumes the
 * video as its Continue Watching card does, `tortuga://detail?profile=…&type=…&id=…` opens the title's Detail. Values
 * are [UriComponent]-encoded.
 */
sealed interface HomeScreenLink {
    val profileId: String

    fun toUrl(): String

    data class Play(override val profileId: String, val type: String, val id: String, val videoId: String) : HomeScreenLink {
        override fun toUrl(): String =
            "$SCHEME://$PLAY?" + UriComponent.encodeQuery(listOf("profile" to profileId, "type" to type, "id" to id, "video" to videoId))
    }

    data class Detail(override val profileId: String, val type: String, val id: String) : HomeScreenLink {
        override fun toUrl(): String =
            "$SCHEME://$DETAIL?" + UriComponent.encodeQuery(listOf("profile" to profileId, "type" to type, "id" to id))
    }

    companion object {
        private const val SCHEME = "tortuga"
        private const val PLAY = "play"
        private const val DETAIL = "detail"

        /** A `tortuga://play` or `tortuga://detail` link, or null for anything else (or one missing a value). */
        fun parse(url: String): HomeScreenLink? {
            val trimmed = url.trim()
            val prefix = "$SCHEME://"
            if (!trimmed.startsWith(prefix, ignoreCase = true)) return null
            val rest = trimmed.substring(prefix.length)
            val host = rest.substringBefore('?').removeSuffix("/").lowercase()
            val params = rest.substringAfter('?', missingDelimiterValue = "").split('&').mapNotNull { pair ->
                val name = pair.substringBefore('=', missingDelimiterValue = "")
                if (name.isEmpty()) null else name to UriComponent.decode(pair.substringAfter('='))
            }.toMap()
            fun param(name: String): String? = params[name]?.takeIf { it.isNotEmpty() }
            val profileId = param("profile") ?: return null
            val type = param("type") ?: return null
            val id = param("id") ?: return null
            return when (host) {
                PLAY -> param("video")?.let { Play(profileId, type, id, it) }
                DETAIL -> Detail(profileId, type, id)
                else -> null
            }
        }
    }
}
