package com.edde746.tortuga.model

import com.edde746.tortuga.net.WebUrl
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A subtitle file from a subtitles addon or a stream's `subtitles` (stremio-core `Subtitles`). [id], [lang] and an
 * absolute [url] are required. [lang] is whatever the addon sends: usually ISO 639-2 (`eng`), sometimes a name.
 */
@Serializable(with = SubtitlesSerializer::class)
data class Subtitles(
    val id: String,
    val lang: String,
    val url: String,
    val label: String? = null,
    /** Font files for ASS/SSA subtitles; every entry must be an absolute URL. */
    val fonts: List<String> = emptyList(),
)

internal object SubtitlesSerializer : JsonTreeSerializer<Subtitles>("Subtitles") {
    override fun read(element: JsonElement): Subtitles {
        val o = element.asObject("subtitles")
        return Subtitles(
            id = o.reqString("id"),
            lang = o.reqString("lang"),
            url = o.reqUrl("url"),
            label = o.optString("label"),
            fonts = (o["fonts"] as? JsonArray)?.map { font ->
                (font as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(WebUrl::canonical)
                    ?: throw SerializationException("'fonts' must hold absolute URLs")
            }.orEmpty(),
        )
    }

    override fun write(value: Subtitles): JsonElement = buildJsonObject {
        put("id", value.id)
        put("lang", value.lang)
        put("url", value.url)
        putIfNotNull("label", value.label)
        if (value.fonts.isNotEmpty()) putStrings("fonts", value.fonts)
    }
}
