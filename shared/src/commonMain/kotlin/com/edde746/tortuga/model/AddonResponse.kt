package com.edde746.tortuga.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A decoded addon resource response (stremio-core `ResourceResponseCache`): the [content], the addon's cache hints,
 * how many elements the response's list had as the addon [sent] it (malformed ones included: what a catalog's `skip`
 * counts; 0 for a `meta`), and how many malformed list elements were [dropped] on the way, at any depth (response
 * items, a meta's videos, streams' subtitles, links and trailers): a count for the logs, not of items, with why the
 * first was ([firstDrop]). URL fields hold absolute URLs in the canonical form stremio-core's `url::Url` gives them
 * ([com.edde746.tortuga.net.WebUrl.canonical]).
 */
data class AddonResponse(
    val content: ResourceContent,
    val cache: CacheHints = CacheHints(),
    val dropped: Int = 0,
    val sent: Int = 0,
    val firstDrop: String? = null,
) {
    /** The addon sent a list and every element of it was malformed: an answer this app can't read, not an empty one. */
    val everyElementDropped: Boolean
        get() = sent > 0 && when (content) {
            is ResourceContent.Metas -> content.items.isEmpty()
            is ResourceContent.MetasDetailed -> content.items.isEmpty()
            is ResourceContent.Streams -> content.items.isEmpty()
            is ResourceContent.Subtitles -> content.items.isEmpty()
            is ResourceContent.Addons -> content.items.isEmpty()
            is ResourceContent.Meta, ResourceContent.NoMeta -> false
        }

    companion object {
        private val KEYS = listOf("metas", "metasDetailed", "meta", "streams", "subtitles", "addons")

        /**
         * Decodes a response body (stremio-core's `ResourceResponse` deserializer): a JSON object with exactly one of
         * `metas`, `metasDetailed`, `meta`, `streams`, `subtitles`, `addons`. A `null` list is empty; a `null` meta, or
         * one without an `id` (the SDK's `{ meta: {} }`, its way of saying "not mine"), is [ResourceContent.NoMeta]. List
         * elements are decoded one at a time; malformed ones are dropped and counted. Throws [SerializationException]
         * when the body isn't such an object or `meta` is malformed.
         */
        fun decode(body: String): AddonResponse = decode(AddonJson.parseToJsonElement(body))

        fun decode(element: JsonElement): AddonResponse {
            val o = element as? JsonObject
                ?: throw SerializationException("expected an object response")
            val present = KEYS.filter { it in o }
            if (present.isEmpty()) throw SerializationException("the response has none of the keys ${KEYS.joinToString()}")
            if (present.size > 1) throw SerializationException("the response has more than one of the keys ${KEYS.joinToString()}")
            val drops = DropCounter()
            val value = o.getValue(present.single())
            val content = when (present.single()) {
                "metas" -> ResourceContent.Metas(
                    decodeEach(value, drops) { MetaItemPreviewSerializer.read(it.asObject("meta item"), drops) },
                )
                "metasDetailed" -> ResourceContent.MetasDetailed(decodeEach(value, drops) { MetaItemSerializer.read(it, drops) })
                "meta" -> if (value is JsonNull || (value is JsonObject && "id" !in value)) {
                    ResourceContent.NoMeta
                } else {
                    ResourceContent.Meta(MetaItemSerializer.read(value, drops))
                }
                "streams" -> ResourceContent.Streams(decodeEach(value, drops) { StreamSerializer.read(it, drops) })
                "subtitles" -> ResourceContent.Subtitles(decodeEach(value, drops, SubtitlesSerializer::read))
                else -> ResourceContent.Addons(decodeEach(value, drops, ::readDescriptor))
            }
            return AddonResponse(content, CacheHints.read(o), drops.count, sent = (value as? JsonArray)?.size ?: 0, firstDrop = drops.first)
        }

        private fun readDescriptor(element: JsonElement): Descriptor {
            val o = element.asObject("addon")
            return Descriptor(
                manifest = ManifestSerializer.read(o["manifest"] ?: throw SerializationException("'manifest' is required")),
                transport = TransportUrl.checked(o.reqUrl("transportUrl")) ?: throw SerializationException("'transportUrl' isn't an addon's"),
                flags = o["flags"]?.takeUnless { it is JsonNull }
                    ?.let { AddonJson.decodeFromJsonElement(DescriptorFlags.serializer(), it) } ?: DescriptorFlags(),
            )
        }
    }
}

/** The one payload of an [AddonResponse]. */
sealed interface ResourceContent {
    data class Metas(val items: List<MetaItemPreview>) : ResourceContent
    data class MetasDetailed(val items: List<MetaItem>) : ResourceContent
    data class Meta(val item: MetaItem) : ResourceContent

    /** A `meta` answer without an item: the addon hasn't the title (an empty answer, as an empty list is). */
    data object NoMeta : ResourceContent
    data class Streams(val items: List<Stream>) : ResourceContent
    data class Subtitles(val items: List<com.edde746.tortuga.model.Subtitles>) : ResourceContent
    data class Addons(val items: List<Descriptor>) : ResourceContent
}

/**
 * The SDK's cache hints, in seconds: [cacheMaxAge] (`max-age`), [staleRevalidate] (`stale-while-revalidate`) and
 * [staleError] (`stale-if-error`). A missing or malformed value is null.
 */
data class CacheHints(
    val cacheMaxAge: Long? = null,
    val staleRevalidate: Long? = null,
    val staleError: Long? = null,
) {
    internal companion object {
        fun read(o: JsonObject) = CacheHints(o.seconds("cacheMaxAge"), o.seconds("staleRevalidate"), o.seconds("staleError"))

        private fun JsonObject.seconds(key: String): Long? =
            (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
    }
}
