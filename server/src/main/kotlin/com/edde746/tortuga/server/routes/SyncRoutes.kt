package com.edde746.tortuga.server.routes

import com.edde746.tortuga.server.Services
import com.edde746.tortuga.server.http.ApiException
import com.edde746.tortuga.server.http.PARSE_COST
import com.edde746.tortuga.server.http.badRequest
import com.edde746.tortuga.server.http.bearer
import com.edde746.tortuga.server.http.reserveBytes
import com.edde746.tortuga.server.http.tooLarge
import com.edde746.tortuga.server.service.IncomingWrite
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import com.fasterxml.jackson.core.JsonEncoding
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import com.fasterxml.jackson.core.json.JsonWriteFeature
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readBuffer
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.asInputStream

/**
 * Pull and push of the account's records; their answers come as JSON already written (SyncRecords), and a push's body
 * is read without building its JSON ([receivePush]). Each reserves what it holds of the server's byte budget.
 */
fun Route.syncRoutes(services: Services) {
    get(SyncPaths.SYNC_PULL) {
        val bearer = call.bearer()
        val since = call.request.queryParameters[SyncPaths.PULL_SINCE]?.let { it.toLongOrNull() ?: throw badRequest(SyncPaths.PULL_SINCE) } ?: 0L
        val limit = call.request.queryParameters[SyncPaths.PULL_LIMIT]?.let { it.toIntOrNull() ?: throw badRequest(SyncPaths.PULL_LIMIT) }
            ?: SyncProtocol.DEFAULT_PULL_LIMIT
        call.respondBytes(services.records.pull(bearer, since, limit, call::reserveBytes).toByteArray(), JSON)
    }

    post(SyncPaths.SYNC_PUSH) {
        val bearer = call.bearer()
        call.respondBytes(services.records.push(bearer, call.receivePush(), call::reserveBytes).toByteArray(), JSON)
    }
}

private val JSON = ContentType.Application.Json.withCharset(Charsets.UTF_8)

/**
 * Reads push bodies and writes their payloads. A string longer than a record can't belong to a valid write, and the
 * parser holds a string whole while it reads it. Field names aren't kept in a shared table: a body of many distinct
 * keys would hold one String for each. Escapes in lowercase hex, as kotlinx.serialization writes them.
 */
private val PUSH_JSON: JsonFactory = JsonFactory.builder()
    .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(SyncProtocol.MAX_RECORD_BYTES).build())
    .disable(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES)
    .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
    .build()

/**
 * The writes of a [com.edde746.tortuga.sync.api.PushRequest] body, as kotlinx.serialization would decode it with
 * [SyncProtocol.json] (unknown keys skipped), but without a tree of its JSON: each payload is copied as compact JSON while
 * the parser goes by, so a body holds about its own length whatever its records' shape (a tree of small values takes
 * tens of times theirs). 400 for malformed JSON or a missing field, 413 for a payload over
 * [SyncProtocol.MAX_RECORD_BYTES].
 *
 * The parse runs on [Dispatchers.Default], so the call threads stay free to admit or refuse the calls behind it before
 * the engine buffers their bodies.
 */
private suspend fun ApplicationCall.receivePush(): List<IncomingWrite> {
    if (!request.contentType().match(ContentType.Application.Json)) {
        throw ApiException(HttpStatusCode.UnsupportedMediaType, ApiErrorCode.INVALID_REQUEST, "Send application/json")
    }
    // Read whole first, so no thread waits on a slow client; the parser then consumes it as it goes.
    val body = receiveChannel().readBuffer()
    reserveBytes(PARSE_COST * minOf(body.size, SyncProtocol.MAX_RECORD_BYTES.toLong()))
    return withContext(Dispatchers.Default) {
        try {
            PUSH_JSON.createParser(body.asInputStream()).use(::readPush)
        } catch (_: StreamConstraintsException) {
            throw tooLarge("Push body holds a string, number or nesting past the parser's limits")
        } catch (_: JsonProcessingException) {
            throw malformed()
        }
    }
}

private fun malformed() = badRequest("Malformed JSON or missing field")

private fun readPush(p: JsonParser): List<IncomingWrite> {
    if (p.nextToken() != JsonToken.START_OBJECT) throw malformed()
    var writes: List<IncomingWrite>? = null
    while (p.nextToken() == JsonToken.FIELD_NAME) {
        val name = p.currentName()
        p.nextToken()
        if (name == "writes") writes = readWrites(p) else p.skipChildren()
    }
    if (p.nextToken() != null) throw malformed()
    return writes ?: throw malformed()
}

/** The writes array, refused (400) past [SyncProtocol.MAX_WRITES_PER_PUSH] before the rest is read. */
private fun readWrites(p: JsonParser): List<IncomingWrite> {
    if (p.currentToken() != JsonToken.START_ARRAY) throw malformed()
    val writes = ArrayList<IncomingWrite>()
    while (p.nextToken() != JsonToken.END_ARRAY) {
        if (writes.size == SyncProtocol.MAX_WRITES_PER_PUSH) throw badRequest("Too many writes")
        writes += readWrite(p)
    }
    return writes
}

private fun readWrite(p: JsonParser): IncomingWrite {
    if (p.currentToken() != JsonToken.START_OBJECT) throw malformed()
    var recordId: String? = null
    var baseVersion: Long? = null
    var deleted = false
    var payload: ByteArray? = null
    while (p.nextToken() == JsonToken.FIELD_NAME) {
        val name = p.currentName()
        val token = p.nextToken()
        when (name) {
            "recordId" -> recordId = if (token == JsonToken.VALUE_STRING) p.text else throw malformed()
            "baseVersion" -> baseVersion = if (token == JsonToken.VALUE_NUMBER_INT) p.longValue else throw malformed()
            "deleted" -> deleted = when (token) {
                JsonToken.VALUE_TRUE -> true
                JsonToken.VALUE_FALSE -> false
                else -> throw malformed()
            }
            "payload" -> payload = compactObject(p)
            else -> p.skipChildren()
        }
    }
    return IncomingWrite(recordId ?: throw malformed(), baseVersion ?: throw malformed(), deleted, payload ?: throw malformed())
}

/** The object at [p] as compact JSON, refused (413) once it passes [SyncProtocol.MAX_RECORD_BYTES]. */
private fun compactObject(p: JsonParser): ByteArray {
    if (p.currentToken() != JsonToken.START_OBJECT) throw malformed()
    val out = RecordBytes()
    var depth = 0
    PUSH_JSON.createGenerator(out, JsonEncoding.UTF8).use { g ->
        do {
            when (p.currentToken()) {
                JsonToken.START_OBJECT -> {
                    g.writeStartObject()
                    depth++
                }
                JsonToken.END_OBJECT -> {
                    g.writeEndObject()
                    depth--
                }
                JsonToken.START_ARRAY -> {
                    g.writeStartArray()
                    depth++
                }
                JsonToken.END_ARRAY -> {
                    g.writeEndArray()
                    depth--
                }
                JsonToken.FIELD_NAME -> g.writeFieldName(p.currentName())
                JsonToken.VALUE_STRING -> g.writeString(p.textCharacters, p.textOffset, p.textLength)
                // A number's text as written, as kotlinx.serialization keeps it.
                JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> g.writeNumber(p.text)
                JsonToken.VALUE_TRUE -> g.writeBoolean(true)
                JsonToken.VALUE_FALSE -> g.writeBoolean(false)
                JsonToken.VALUE_NULL -> g.writeNull()
                else -> throw malformed()
            }
        } while (depth > 0 && p.nextToken() != null)
    }
    if (depth != 0) throw malformed()
    return out.toByteArray()
}

/** A record's compact JSON as it is written: 413 before it passes [SyncProtocol.MAX_RECORD_BYTES]. */
private class RecordBytes : ByteArrayOutputStream() {
    override fun write(b: Int) {
        if (count + 1 > SyncProtocol.MAX_RECORD_BYTES) throw tooLarge("Record too large")
        super.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (count + len > SyncProtocol.MAX_RECORD_BYTES) throw tooLarge("Record too large")
        super.write(b, off, len)
    }
}
