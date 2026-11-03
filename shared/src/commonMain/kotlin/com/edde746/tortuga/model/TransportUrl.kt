package com.edde746.tortuga.model

import com.edde746.tortuga.net.UriComponent
import com.edde746.tortuga.net.WebUrl

/**
 * An addon transport URL (`…/manifest.json`) checked where it comes in, so what holds one needn't check it again: [value]
 * [isSupported]. A typed or followed install link ([parse]) is normalized too; a stored or listed addon's URL
 * ([checked]) stays as written, since it is the addon's key. Follows stremio-core's HTTP transport
 * (`addon_transport/http_transport/http_transport.rs`) and install-link handling (`models/addon_details.rs`). Only the
 * HTTP transport is supported; the legacy `/stremio/v1` transport is not.
 */
class TransportUrl private constructor(val value: String) {
    override fun equals(other: Any?): Boolean = other is TransportUrl && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        const val MANIFEST_PATH = "/manifest.json"

        /** [input] as a transport URL ([normalize]d, and [isSupported]); null when it isn't one. */
        fun parse(input: String): TransportUrl? = normalize(input)?.takeIf(::isSupported)?.let(::TransportUrl)

        /** [value], a stored or listed addon's URL, as written; null when it isn't [isSupported]. */
        fun checked(value: String): TransportUrl? = value.takeIf(::isSupported)?.let(::TransportUrl)

        /**
         * Normalizes an install link: `stremio://host/…` becomes `https://host/…` (stremio-core `Selected`), then the URL
         * is parsed and serialized as the `url` crate does ([WebUrl.normalize]). Null when [input] isn't an HTTP(S) URL.
         */
        fun normalize(input: String): String? {
            val trimmed = input.trim { it <= ' ' }
            val candidate = if (WebUrl.scheme(trimmed) == "stremio" && trimmed.substring(8).startsWith("//")) {
                "https:" + trimmed.substring(8)
            } else {
                trimmed
            }
            return WebUrl.normalize(candidate)?.takeIf { WebUrl.scheme(it) == "http" || WebUrl.scheme(it) == "https" }
        }

        /** Whether [transportUrl] can be requested: HTTP(S) and its path ends in [MANIFEST_PATH]. */
        fun isSupported(transportUrl: String): Boolean {
            val scheme = WebUrl.scheme(transportUrl)
            if (scheme != "http" && scheme != "https") return false
            return WebUrl.path(transportUrl)?.endsWith(MANIFEST_PATH) == true
        }

        /**
         * The URL of [path] on the addon at [transportUrl]: every [MANIFEST_PATH] in the URL replaced by
         * `/{resource}/{type}/{id}[/{extra}].json`, with path components [UriComponent.encode]d and the extra
         * [UriComponent.encodeQuery]-encoded (exactly `AddonHTTPTransport::resource`).
         */
        fun resourceUrl(transportUrl: TransportUrl, path: ResourcePath): String {
            val resource = buildString {
                append('/').append(UriComponent.encode(path.resource))
                append('/').append(UriComponent.encode(path.type))
                append('/').append(UriComponent.encode(path.id))
                if (path.extra.isNotEmpty()) {
                    append('/').append(UriComponent.encodeQuery(path.extra.map { it.name to it.value }))
                }
                append(".json")
            }
            return transportUrl.value.replace(MANIFEST_PATH, resource)
        }

        /**
         * The addon's configuration page, `{base}/configure` (what Stremio opens for "Configure"): the trailing
         * [MANIFEST_PATH] of the path replaced by `/configure`, query kept.
         */
        fun configureUrl(transportUrl: String): String? {
            if (!isSupported(transportUrl)) return null
            val authorityStart = transportUrl.indexOf("//") + 2
            val pathStart = transportUrl.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
            val pathEnd = transportUrl.indexOfAny(charArrayOf('?', '#'), pathStart).let { if (it < 0) transportUrl.length else it }
            return transportUrl.substring(0, pathEnd - MANIFEST_PATH.length) + "/configure" + transportUrl.substring(pathEnd)
        }
    }
}
