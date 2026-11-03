package com.edde746.tortuga.model

import com.edde746.tortuga.net.UriComponent
import com.edde746.tortuga.net.WebUrl

/**
 * What the client does with a stream. The client plays HTTP(S) URLs only; streams that aren't direct links are never
 * touched.
 */
sealed interface StreamPlayability {
    /** An `http`/`https` [url] for the player (after resolving). */
    data class Playable(val url: String) : StreamPlayability

    /** A YouTube video: opened in the YouTube app or the browser. */
    data class YouTube(val ytId: String) : StreamPlayability {
        val watchUrl: String get() = "https://www.youtube.com/watch?v=" + UriComponent.encode(ytId)
    }

    /**
     * A page to open outside the player: an `http`/`https` `externalUrl` or `playerFrameUrl`, opened in the browser
     * (`AppSession.openUrl`). A `stremio:` link is routed internally instead ([deepLink]); one the app doesn't route
     * opens nothing.
     */
    data class External(val url: String) : StreamPlayability {
        val deepLink: DeepLink? get() = DeepLink.parse(url)
    }

    /** Not playable by this client, and never handed to any resolver. */
    data class Unsupported(val reason: UnsupportedSource) : StreamPlayability

    companion object {
        fun of(source: StreamSource): StreamPlayability = when (source) {
            is StreamSource.Url -> when (WebUrl.scheme(source.url)) {
                "http", "https" -> Playable(source.url)
                "magnet" -> Unsupported(UnsupportedSource.PEER_TO_PEER)
                else -> Unsupported(UnsupportedSource.OTHER)
            }
            is StreamSource.YouTube -> YouTube(source.ytId)
            is StreamSource.External -> source.externalUrl?.let(::outside) ?: Unsupported(UnsupportedSource.OTHER)
            is StreamSource.PlayerFrame -> outside(source.playerFrameUrl)
            is StreamSource.PeerToPeer -> Unsupported(UnsupportedSource.PEER_TO_PEER)
            is StreamSource.Nntp -> Unsupported(UnsupportedSource.NNTP)
            is StreamSource.Archive -> Unsupported(UnsupportedSource.ARCHIVE)
        }

        /**
         * A link for outside the player: [External] for an `http`/`https` page or a `stremio:` link,
         * [UnsupportedSource.PEER_TO_PEER] for a peer-to-peer link, and nothing the app opens for any other link
         * (`file:`, a Windows path, `smb:`, `search-ms:`, another app's scheme such as `vlc:` or `intent:`).
         */
        private fun outside(url: String): StreamPlayability = when (WebUrl.scheme(url)) {
            "http", "https", "stremio" -> External(url)
            "magnet" -> Unsupported(UnsupportedSource.PEER_TO_PEER)
            else -> Unsupported(UnsupportedSource.OTHER)
        }
    }
}

/**
 * Why a stream can't be played. [OTHER]: a URL scheme other than HTTP(S) (`rtmp:`, `file:`), an external link that is
 * neither HTTP(S) nor `stremio:` (`file:`, a Windows path, another app's scheme), or an external link only for another
 * platform (`androidTvUrl`, `tizenUrl`, `webosUrl`). [PEER_TO_PEER] includes a peer-to-peer link. [REGION]: its
 * `countryWhitelist` leaves out the device's region.
 */
enum class UnsupportedSource { PEER_TO_PEER, NNTP, ARCHIVE, OTHER, REGION }
