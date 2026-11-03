package com.edde746.tortuga.model

import com.edde746.tortuga.net.UriComponent
import com.edde746.tortuga.net.WebUrl

/**
 * `stremio:` links the app routes internally: addon install links and the `stremio:///…` links of the
 * SDK's `docs/deep-links.md`, which addons put in `links` and `externalUrl` and web pages open from outside the app.
 * Formats follow stremio-core's `deep_links` module; path components are [UriComponent]-encoded.
 */
sealed interface DeepLink {
    /** The link as stremio-core writes it. */
    fun toUrl(): String

    /** `stremio://host/…/manifest.json`: install the addon at [transportUrl] (the `https://` form). */
    data class InstallAddon(val transportUrl: TransportUrl) : DeepLink {
        override fun toUrl(): String = "stremio://" + transportUrl.value.substringAfter("://")
    }

    /**
     * `stremio:///detail/{type}/{id}[/{videoId}][?autoPlay=true]`: a meta item, optionally with a video's streams
     * open, or with [autoPlay] that video playing once the item is known.
     */
    data class Detail(val type: String, val id: String, val videoId: String? = null, val autoPlay: Boolean = false) : DeepLink {
        override fun toUrl(): String = buildString {
            append("stremio:///detail/").append(UriComponent.encode(type)).append('/').append(UriComponent.encode(id))
            if (videoId != null) append('/').append(UriComponent.encode(videoId))
            if (autoPlay) append("?autoPlay=true")
        }
    }

    /** `stremio:///board`: Home. */
    data object Board : DeepLink {
        override fun toUrl(): String = "stremio:///board"
    }

    /** `stremio:///discover`: Discover, on the catalog it opens on. */
    data object DiscoverDefault : DeepLink {
        override fun toUrl(): String = "stremio:///discover"
    }

    /** `stremio:///library`: the library. */
    data object Library : DeepLink {
        override fun toUrl(): String = "stremio:///library"
    }

    /** `stremio:///discover/{transportUrl}/{type}/{catalogId}?{extra}`: one catalog, e.g. a genre (Cinemeta links). */
    data class Discover(
        val transportUrl: String,
        val type: String,
        val catalogId: String,
        val extra: List<ExtraValue> = emptyList(),
    ) : DeepLink {
        override fun toUrl(): String =
            "stremio:///discover/" + UriComponent.encode(transportUrl) + "/" + UriComponent.encode(type) + "/" +
                UriComponent.encode(catalogId) + "?" + UriComponent.encodeQuery(extra.map { it.name to it.value })
    }

    /** `stremio:///search?query=…` (stremio-core) or `?search=…` (Cinemeta's cast links). */
    data class Search(val query: String) : DeepLink {
        override fun toUrl(): String = "stremio:///search?query=" + UriComponent.encode(query)
    }

    companion object {
        private val ROUTES = setOf("detail", "discover", "search", "board", "library")

        /**
         * Parses a `stremio:` link, or returns null for anything else (including `stremio:///` routes the app
         * doesn't handle). The host form (`stremio://detail/…`, `stremio://board`) is accepted like `stremio:///…`.
         */
        fun parse(link: String): DeepLink? {
            val trimmed = link.trim()
            if (WebUrl.scheme(trimmed) != "stremio") return null
            val rest = trimmed.substring("stremio:".length)
            if (!rest.startsWith("//")) return null
            val afterSlashes = rest.substring(2)
            val route = when {
                afterSlashes.startsWith("/") -> afterSlashes.substring(1)
                afterSlashes.takeWhile { it != '/' && it != '?' && it != '#' } in ROUTES -> afterSlashes
                else -> return TransportUrl.parse(trimmed)?.let(::InstallAddon)
            }
            val path = route.substringBefore('?').substringBefore('#').trimEnd('/')
            val query = parseQuery(route.substringAfter('?', "").substringBefore('#'))
            val segments = path.split('/').map(UriComponent::decode)
            return when (segments.first()) {
                "detail" -> when {
                    segments.size < 3 || segments[1].isEmpty() || segments[2].isEmpty() -> null
                    else -> Detail(
                        segments[1],
                        segments[2],
                        segments.getOrNull(3)?.takeIf { it.isNotEmpty() },
                        autoPlay = query.any { it.name == "autoPlay" && it.value == "true" },
                    )
                }
                "discover" -> when {
                    segments.size == 1 -> DiscoverDefault
                    segments.size < 4 || !TransportUrl.isSupported(segments[1]) -> null
                    else -> Discover(segments[1], segments[2], segments[3], query)
                }
                "search" -> query.firstOrNull { it.name == "query" || it.name == "search" }
                    ?.value?.takeIf { it.isNotBlank() }?.let(::Search)
                "board" -> Board.takeIf { segments.size == 1 }
                "library" -> Library.takeIf { segments.size == 1 }
                else -> null
            }
        }

        private fun parseQuery(query: String): List<ExtraValue> =
            if (query.isEmpty()) {
                emptyList()
            } else {
                query.split('&').filter { it.isNotEmpty() }.map { pair ->
                    ExtraValue(UriComponent.decodeForm(pair.substringBefore('=')), UriComponent.decodeForm(pair.substringAfter('=', "")))
                }
            }
    }
}
