package com.edde746.tortuga.watch.media

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.json
import com.edde746.tortuga.app.testData
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.StreamPlayability
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.session.StreamChoice
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration

internal const val PROFILE = "p"

/** The host's duration in the tests: an hour. */
internal const val HOUR = 3_600_000L

/** An addon at `https://<name>.example.com/manifest.json` serving [resource] for movies and series, for ids with [prefixes] (any id without). */
internal fun addon(name: String, resource: String = "stream", vararg prefixes: String): Descriptor {
    val idPrefixes = if (prefixes.isEmpty()) "" else ""","idPrefixes":[${prefixes.joinToString(",") { "\"$it\"" }}]"""
    return Descriptor(
        Manifest.decode("""{"id":"org.example.$name","version":"1.0.0","name":"$name","types":["movie","series"],"resources":["$resource"]$idPrefixes}"""),
        "https://$name.example.com/manifest.json".transportUrl(),
    )
}

/** A stream at `https://cdn.example/<path>` as an addon lists it. */
internal fun streamJson(path: String, name: String? = null, hash: String? = null, size: Long? = null, filename: String? = null): String =
    buildJsonObject {
        put("url", "https://cdn.example/$path")
        name?.let { put("name", it) }
        if (hash != null || size != null || filename != null) {
            putJsonObject("behaviorHints") {
                hash?.let { put("videoHash", it) }
                size?.let { put("videoSize", it) }
                filename?.let { put("filename", it) }
            }
        }
    }.toString()

internal fun streamsBody(vararg streams: String): String = """{"streams":[${streams.joinToString(",")}]}"""

/** The last path segment of a candidate's URL (`https://cdn.example/a` is `a`). */
internal val StreamChoice.path: String get() = (stream.playability as StreamPlayability.Playable).url.substringAfterLast('/')

/**
 * A guest's profile [PROFILE] over in-memory data and a mock HTTP world: each addon host answers through [answers]
 * (`resource`, `type`, `id`) with a JSON body, or 404 for null or no answer, after its [latency] of virtual time.
 * [requests] lists what was asked.
 */
internal class WatchMediaFixture(scope: TestScope, vararg installed: Descriptor) {
    val addons = installed.toMutableList()
    val data = scope.testData()
    val answers = mutableMapOf<String, (resource: String, type: String, id: String) -> String?>()
    val latency = mutableMapOf<String, Duration>()
    val http = FakeHttp(scope) { request ->
        val name = request.url.host.substringBefore('.')
        latency[name]?.let { delay(it) }
        val segments = request.url.segments
        val body = answers[name]?.invoke(segments[0], segments[1], segments[2].removeSuffix(".json"))
        if (body == null) respond("", HttpStatusCode.NotFound) else json(body)
    }
    val aggregator = AddonAggregator(AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(scope.testScheduler)))
    val matcher = FileMatcher(PROFILE, aggregator, { addons.toList() }, data.streamHistory, data.settings)
    val titles = RoomTitleResolver(aggregator) { addons.toList() }

    /** `<addon> <resource>/<type>/<id>` of every request, in order. */
    val requests: List<String>
        get() = http.requests.map { "${it.url.host.substringBefore('.')} ${it.url.segments.joinToString("/").removeSuffix(".json")}" }

    /** The addon [name] answers stream requests for [id] with [streams]. */
    fun streams(name: String, id: String, vararg streams: String) {
        val previous = answers[name]
        answers[name] = { resource, type, requested ->
            if (resource == "stream" && requested == id) streamsBody(*streams) else previous?.invoke(resource, type, requested)
        }
    }

    /** The addon [name] answers meta requests for [id] with [meta] (the meta object's JSON). */
    fun meta(name: String, id: String, meta: String) {
        val previous = answers[name]
        answers[name] = { resource, type, requested ->
            if (resource == "meta" && requested == id) """{"meta":$meta}""" else previous?.invoke(resource, type, requested)
        }
    }
}
