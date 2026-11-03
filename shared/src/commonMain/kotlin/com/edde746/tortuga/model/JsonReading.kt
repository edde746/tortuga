package com.edde746.tortuga.model

import com.edde746.tortuga.net.WebUrl
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The one JSON configuration for addon payloads: unknown keys are ignored, `null` or an unknown enum
 * value for a field with a default takes the default, and nulls are left out when encoding.
 */
val AddonJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Counts list elements dropped while decoding, and keeps why the [first] one was. One malformed element costs that
 * element only; callers log the count and the reason (see [AddonResponse.dropped]).
 */
internal class DropCounter {
    var count: Int = 0
        private set

    /** The first dropped element's decoding failure, for the log; null while none was dropped. */
    var first: String? = null
        private set

    internal fun drop(reason: Exception) {
        if (count == 0) first = reason.message ?: reason::class.simpleName
        count++
    }
}

/**
 * Decodes each element of [element] (a JSON array; `null` or absent is an empty list) with [decode], dropping and
 * counting the elements that throw. A non-array value is malformed as a whole.
 */
internal inline fun <T> decodeEach(element: JsonElement?, drops: DropCounter, decode: (JsonElement) -> T): List<T> {
    val array = when (element) {
        null, JsonNull -> return emptyList()
        is JsonArray -> element
        else -> throw SerializationException("expected an array")
    }
    val out = ArrayList<T>(array.size)
    for (item in array) {
        try {
            out.add(decode(item))
        } catch (e: IllegalArgumentException) {
            // SerializationException included.
            drops.drop(e)
        }
    }
    return out
}

internal fun JsonElement.asObject(what: String): JsonObject =
    this as? JsonObject ?: throw SerializationException("$what must be an object")

/** A string field; `null` or absent is null, any other type is malformed. */
internal fun JsonObject.optString(key: String): String? = when (val e = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> if (e.isString) e.content else throw SerializationException("'$key' must be a string")
    else -> throw SerializationException("'$key' must be a string")
}

internal fun JsonObject.reqString(key: String): String =
    optString(key) ?: throw SerializationException("'$key' is required")

/** A string, or a number rendered as stremio-core's `NumberAsString` does (`1994`, `7.5`). */
internal fun JsonObject.optNumberAsString(key: String): String? = when (val e = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> if (e.isString) e.content else numberText(e)
    else -> throw SerializationException("'$key' must be a string or a number")
}

private fun numberText(e: JsonPrimitive): String {
    val value = e.content.toDoubleOrNull() ?: throw SerializationException("expected a number")
    if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e15) return value.toLong().toString()
    val text = value.toString()
    return if ('E' in text || 'e' in text) e.content else text
}

/** A boolean; `null` or absent is [default] (coerced input), any other type is malformed. */
internal fun JsonObject.optBoolean(key: String, default: Boolean = false): Boolean = when (val e = this[key]) {
    null, JsonNull -> default
    is JsonPrimitive -> if (e.isString) throw SerializationException("'$key' must be a boolean") else e.booleanOrNull
        ?: throw SerializationException("'$key' must be a boolean")
    else -> throw SerializationException("'$key' must be a boolean")
}

/** A non-negative integer; `null` or absent is null, any other value is malformed. */
internal fun JsonObject.optUnsigned(key: String): Long? = when (val e = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> e.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
        ?: throw SerializationException("'$key' must be a non-negative integer")
    else -> throw SerializationException("'$key' must be a non-negative integer")
}

/** Like [optUnsigned], but a malformed or out-of-range value is treated as absent (`DefaultOnError`). */
internal fun JsonObject.optUnsignedLenient(key: String, max: Long = Long.MAX_VALUE): Long? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..max }

/** A list of strings; `null` or absent is null, a non-string element makes the whole list malformed. */
internal fun JsonObject.optStringList(key: String): List<String>? = when (val e = this[key]) {
    null, JsonNull -> null
    is JsonArray -> e.map { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw SerializationException("'$key' must hold strings")
    }
    else -> throw SerializationException("'$key' must be an array")
}

/**
 * An absolute URL (`url::Url`), in its canonical form ([WebUrl.canonical]); absent, `null` or invalid is malformed.
 */
internal fun JsonObject.reqUrl(key: String): String =
    WebUrl.canonical(reqString(key)) ?: throw SerializationException("'$key' must be an absolute URL")

/**
 * An image-style URL field (`DefaultOnError<NoneAsEmptyString>` / `NoneAsEmptyString`), canonical: an empty string,
 * a non-string or an invalid URL is null rather than a malformed item.
 */
internal fun JsonObject.optUrlLenient(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }?.let(WebUrl::canonical)

internal fun JsonObjectBuilder.putIfNotNull(key: String, value: String?) {
    if (value != null) put(key, JsonPrimitive(value))
}

internal fun JsonObjectBuilder.putIfNotNull(key: String, value: Number?) {
    if (value != null) put(key, JsonPrimitive(value))
}

internal fun JsonObjectBuilder.putStrings(key: String, values: List<String>?) {
    if (values != null) put(key, JsonArray(values.map(::JsonPrimitive)))
}

/**
 * A JSON-only [KSerializer] for model types whose shape kotlinx.serialization can't express directly (untagged
 * unions, flattened fields, aliases): it reads and writes the [JsonElement] tree.
 */
internal abstract class JsonTreeSerializer<T>(name: String) : KSerializer<T> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor(name, JsonElement.serializer().descriptor)

    abstract fun read(element: JsonElement): T
    abstract fun write(value: T): JsonElement

    override fun deserialize(decoder: Decoder): T {
        val json = decoder as? JsonDecoder ?: throw SerializationException("$this can only be decoded from JSON")
        return read(json.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: T) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("$this can only be encoded to JSON")
        json.encodeJsonElement(write(value))
    }
}
