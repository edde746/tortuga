@file:OptIn(ExperimentalObjCName::class)

package com.edde746.tortuga.model

import com.edde746.tortuga.net.WebUrl
import com.edde746.tortuga.playback.resolve.OpenSubtitlesHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * One stream from a stream addon (stremio-core `types/resource/stream.rs`). The source fields sit at the top level of
 * the JSON object; [source] is whichever one stremio-core's untagged `StreamSource` would pick, tried in its order
 * (see [StreamSource]). A stream with no recognizable source is malformed.
 *
 * Decoding differs from stremio-core in three lenient details: `description` wins over the legacy `title` when both are
 * present (serde rejects the pair as a duplicate field), a source that isn't a direct link with a malformed optional
 * field (`fileIdx`, archive sizes) keeps the source with that field unset, and an `externalUrl` or `androidTvUrl` with
 * no scheme (a Windows path such as `\\host\share`) is kept as written. Those sources are never played or
 * opened, so this only affects how many unplayable streams are counted.
 */
@Serializable(with = StreamSerializer::class)
data class Stream(
    val source: StreamSource,
    val name: String? = null,
    /** `description`, or the legacy `title`. */
    /** `descriptionText` in Swift: `description` would clash with `NSObject.description`. */
    @ObjCName("descriptionText")
    val description: String? = null,
    val thumbnail: String? = null,
    /** Malformed entries are dropped (and counted when decoded through [AddonResponse]). */
    val subtitles: List<Subtitles> = emptyList(),
    val behaviorHints: StreamBehaviorHints = StreamBehaviorHints(),
) {
    /** What the client can do with this stream on this device: [playabilityIn] the device's region. */
    val playability: StreamPlayability get() = playabilityIn(DeviceRegion.alpha3)

    /**
     * What the client can do with this stream in [region] (ISO 3166-1 alpha-3, lowercase; null when unknown): a stream
     * whose `countryWhitelist` leaves [region] out can't be played there ([UnsupportedSource.REGION]); an empty or
     * absent list, or an unknown region, restricts nothing.
     */
    fun playabilityIn(region: String?): StreamPlayability {
        val whitelist = behaviorHints.countryWhitelist
        val outside = region != null && !whitelist.isNullOrEmpty() && whitelist.none { it.trim().equals(region, ignoreCase = true) }
        return if (outside) StreamPlayability.Unsupported(UnsupportedSource.REGION) else StreamPlayability.of(source)
    }
}

/** The source of a [Stream], in stremio-core's untagged-enum order. */
sealed interface StreamSource {
    /** `url`: must be an absolute URL, or the next variants are tried. Canonical, as `url::Url` serializes it. */
    data class Url(val url: String) : StreamSource

    /** `ytId`: a YouTube video id. */
    data class YouTube(val ytId: String) : StreamSource

    /** `rarUrls`, `zipUrls`, `7zipUrls`, `tgzUrls` or `tarUrls`. */
    data class Archive(
        val format: ArchiveFormat,
        val urls: List<ArchiveUrl>,
        val fileIdx: Int? = null,
        val fileMustInclude: List<String> = emptyList(),
    ) : StreamSource

    /** `nzbUrl` / `nzbUrls` with news `servers`. */
    data class Nntp(
        val nzbUrl: String? = null,
        val nzbUrls: List<String> = emptyList(),
        val servers: List<String> = emptyList(),
    ) : StreamSource

    /**
     * A peer-to-peer source: `infoHash` (40 hex digits, stored lowercase) with optional `fileIdx` and `announce`
     * (alias `sources`).
     */
    data class PeerToPeer(
        val infoHash: String,
        val fileIdx: Int? = null,
        val announce: List<String> = emptyList(),
        val fileMustInclude: List<String> = emptyList(),
    ) : StreamSource

    /** `playerFrameUrl`: a web page embedding a player. */
    data class PlayerFrame(val playerFrameUrl: String) : StreamSource

    /**
     * `externalUrl`, `androidTvUrl`, `tizenUrl`, `webosUrl`; at least one is set. [externalUrl] and [androidTvUrl] are
     * canonical URLs, or as written when they have no scheme (a Windows path).
     */
    data class External(
        val externalUrl: String? = null,
        val androidTvUrl: String? = null,
        val tizenUrl: String? = null,
        val webosUrl: String? = null,
    ) : StreamSource
}

enum class ArchiveFormat(val key: String) {
    RAR("rarUrls"),
    ZIP("zipUrls"),
    SEVEN_ZIP("7zipUrls"),
    TGZ("tgzUrls"),
    TAR("tarUrls"),
}

/** One archive part: stremio-core writes it as `[url]` or `[url, bytes]`; `{url, bytes}` and a bare URL also decode. */
data class ArchiveUrl(val url: String, val bytes: Long? = null)

/** `behaviorHints` of a stream. Keys this client doesn't know are kept in [other]. */
data class StreamBehaviorHints(
    /** Ignored for playback: mpv plays the container directly. */
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    /** ISO 3166-1 alpha-3 codes (`usa`) of the only countries the stream plays in ([Stream.playabilityIn]). */
    val countryWhitelist: List<String>? = null,
    val proxyHeaders: StreamProxyHeaders? = null,
    /** The file's name; never blank. */
    val filename: String? = null,
    /** The file's OpenSubtitles hash, 16 lowercase hex digits; an addon's value of any other form is dropped. */
    val videoHash: String? = null,
    /** The file's size in bytes, above 0; an addon's 0 is dropped (unknown). */
    val videoSize: Long? = null,
    val other: Map<String, JsonElement> = emptyMap(),
)

/** Headers to send with the stream request ([request]) and to expect on the response ([response]). */
data class StreamProxyHeaders(
    val request: Map<String, String> = emptyMap(),
    val response: Map<String, String> = emptyMap(),
)

internal object StreamSerializer : JsonTreeSerializer<Stream>("Stream") {
    override fun read(element: JsonElement): Stream = read(element, DropCounter())

    fun read(element: JsonElement, drops: DropCounter): Stream {
        val o = element.asObject("stream")
        return Stream(
            source = readSource(o) ?: throw SerializationException("stream has no valid source"),
            name = o.optString("name"),
            description = o.optString("description") ?: o.optString("title"),
            thumbnail = o.optString("thumbnail"),
            subtitles = decodeEach(o["subtitles"], drops, SubtitlesSerializer::read),
            behaviorHints = o["behaviorHints"]?.takeUnless { it is JsonNull }?.let(::readHints) ?: StreamBehaviorHints(),
        )
    }

    private val FORTY_HEX_DIGITS = Regex("^[0-9a-fA-F]{40}$")

    private fun readSource(o: JsonObject): StreamSource? {
        o.optStringOrNull("url")?.let(WebUrl::canonical)?.let { return StreamSource.Url(it) }
        o.optStringOrNull("ytId")?.let { return StreamSource.YouTube(it) }
        for (format in ArchiveFormat.entries) {
            val urls = readArchiveUrls(o[format.key]) ?: continue
            return StreamSource.Archive(format, urls, o.fileIdx(), o.stringListOrEmpty("fileMustInclude"))
        }
        if (o.containsKey("nzbUrl") || o.containsKey("nzbUrls") || o.containsKey("servers")) {
            val nzbUrl = o.optStringOrNull("nzbUrl")?.let(WebUrl::canonical)
            val nzbUrls = o.stringListOrEmpty("nzbUrls").mapNotNull(WebUrl::canonical)
            if (nzbUrl != null || nzbUrls.isNotEmpty() || o["servers"] is JsonArray) {
                return StreamSource.Nntp(nzbUrl, nzbUrls, o.stringListOrEmpty("servers"))
            }
        }
        o.optStringOrNull("infoHash")?.takeIf { FORTY_HEX_DIGITS.matches(it) }?.let { hash ->
            val announce = (o["announce"] ?: o["sources"]).let { if (it is JsonArray) it.strings() else emptyList() }
            return StreamSource.PeerToPeer(hash.lowercase(), o.fileIdx(), announce, o.stringListOrEmpty("fileMustInclude"))
        }
        o.optStringOrNull("playerFrameUrl")?.let(WebUrl::canonical)?.let { return StreamSource.PlayerFrame(it) }
        val externalUrl = o.optStringOrNull("externalUrl")
        val androidTvUrl = o.optStringOrNull("androidTvUrl")
        val external = StreamSource.External(
            externalUrl = externalUrl?.let { externalLink(it) ?: return null },
            androidTvUrl = androidTvUrl?.let { externalLink(it) ?: return null },
            tizenUrl = o.optStringOrNull("tizenUrl"),
            webosUrl = o.optStringOrNull("webosUrl"),
        )
        val any = external.externalUrl != null || external.androidTvUrl != null || external.tizenUrl != null || external.webosUrl != null
        return if (any) external else null
    }

    /**
     * An external link [value]: canonical, or as written when it has no scheme (a Windows path, which is never opened
     * and is counted as unplayable); null for a malformed `http`/`https` URL, which makes the stream malformed.
     */
    private fun externalLink(value: String): String? = if (WebUrl.scheme(value) == null) value else WebUrl.canonical(value)

    private fun readArchiveUrls(element: JsonElement?): List<ArchiveUrl>? {
        val array = element as? JsonArray ?: return null
        return array.map { item ->
            when (item) {
                is JsonPrimitive -> ArchiveUrl(item.takeIf { it.isString }?.content ?: return null)
                is JsonArray -> ArchiveUrl(
                    (item.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null,
                    (item.getOrNull(1) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull,
                )
                is JsonObject -> ArchiveUrl(item.optStringOrNull("url") ?: return null, item.optUnsignedLenient("bytes"))
            }.let { part -> part.copy(url = WebUrl.canonical(part.url) ?: return null) }
        }
    }

    private fun readHints(element: JsonElement): StreamBehaviorHints {
        val o = element.asObject("behaviorHints")
        return StreamBehaviorHints(
            notWebReady = o.optBoolean("notWebReady"),
            bingeGroup = o.optString("bingeGroup"),
            countryWhitelist = o.optStringList("countryWhitelist"),
            proxyHeaders = o["proxyHeaders"]?.takeUnless { it is JsonNull }?.let { headers ->
                val h = headers.asObject("proxyHeaders")
                StreamProxyHeaders(request = h.stringMap("request"), response = h.stringMap("response"))
            },
            filename = o.optString("filename")?.takeIf { it.isNotBlank() },
            videoHash = o.optString("videoHash")?.let(OpenSubtitlesHash::parse),
            videoSize = o.optUnsigned("videoSize")?.takeIf { it > 0 },
            other = o.filterKeys { it !in KNOWN_HINTS },
        )
    }

    private val KNOWN_HINTS = setOf(
        "notWebReady", "bingeGroup", "countryWhitelist", "proxyHeaders", "filename", "videoHash", "videoSize",
    )

    private fun JsonObject.stringMap(key: String): Map<String, String> = when (val e = this[key]) {
        null, JsonNull -> emptyMap()
        is JsonObject -> e.mapValues { (name, v) ->
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw SerializationException("header '$name' must be a string")
        }
        else -> throw SerializationException("'$key' must be an object")
    }

    /** A string value, or null for anything else (source detection tries the next variant instead of failing). */
    private fun JsonObject.optStringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.fileIdx(): Int? = optUnsignedLenient("fileIdx", max = 65535)?.toInt()

    private fun JsonObject.stringListOrEmpty(key: String): List<String> = (this[key] as? JsonArray)?.strings().orEmpty()

    private fun JsonArray.strings(): List<String> = mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    override fun write(value: Stream): JsonElement = buildJsonObject {
        when (val s = value.source) {
            is StreamSource.Url -> put("url", s.url)
            is StreamSource.YouTube -> put("ytId", s.ytId)
            is StreamSource.Archive -> {
                put(s.format.key, JsonArray(s.urls.map { u -> JsonArray(listOfNotNull(JsonPrimitive(u.url), u.bytes?.let(::JsonPrimitive))) }))
                putIfNotNull("fileIdx", s.fileIdx)
                if (s.fileMustInclude.isNotEmpty()) putStrings("fileMustInclude", s.fileMustInclude)
            }
            is StreamSource.Nntp -> {
                putIfNotNull("nzbUrl", s.nzbUrl)
                if (s.nzbUrls.isNotEmpty()) putStrings("nzbUrls", s.nzbUrls)
                putStrings("servers", s.servers)
            }
            is StreamSource.PeerToPeer -> {
                put("infoHash", s.infoHash)
                putIfNotNull("fileIdx", s.fileIdx)
                putStrings("announce", s.announce)
                if (s.fileMustInclude.isNotEmpty()) putStrings("fileMustInclude", s.fileMustInclude)
            }
            is StreamSource.PlayerFrame -> put("playerFrameUrl", s.playerFrameUrl)
            is StreamSource.External -> {
                putIfNotNull("externalUrl", s.externalUrl)
                putIfNotNull("androidTvUrl", s.androidTvUrl)
                putIfNotNull("tizenUrl", s.tizenUrl)
                putIfNotNull("webosUrl", s.webosUrl)
            }
        }
        putIfNotNull("name", value.name)
        putIfNotNull("description", value.description)
        putIfNotNull("thumbnail", value.thumbnail)
        if (value.subtitles.isNotEmpty()) put("subtitles", JsonArray(value.subtitles.map(SubtitlesSerializer::write)))
        val hints = value.behaviorHints
        if (hints != StreamBehaviorHints()) {
            put("behaviorHints", buildJsonObject {
                if (hints.notWebReady) put("notWebReady", true)
                putIfNotNull("bingeGroup", hints.bingeGroup)
                putStrings("countryWhitelist", hints.countryWhitelist)
                hints.proxyHeaders?.let { h ->
                    put("proxyHeaders", buildJsonObject {
                        if (h.request.isNotEmpty()) put("request", JsonObject(h.request.mapValues { JsonPrimitive(it.value) }))
                        if (h.response.isNotEmpty()) put("response", JsonObject(h.response.mapValues { JsonPrimitive(it.value) }))
                    })
                }
                putIfNotNull("filename", hints.filename)
                putIfNotNull("videoHash", hints.videoHash)
                putIfNotNull("videoSize", hints.videoSize)
                hints.other.forEach { (k, v) -> put(k, v) }
            })
        }
    }
}
