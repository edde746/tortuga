package com.edde746.tortuga.addons

import com.edde746.tortuga.net.AddonResponseCache
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AddonAggregatorTest {
    private val movie = "tt0111161"

    @Test
    fun statesKeepInstalledOrderAndFailuresStayPerAddon() = runTest {
        val client = mockClient { request ->
            when (request.url.host) {
                "slow.example.com" -> {
                    delay(3_000)
                    json(streamsBody("https://cdn.example.com/a.mkv", "https://cdn.example.com/b.mkv"))
                }
                "broken.example.com" -> json("{}", HttpStatusCode.InternalServerError)
                "garbage.example.com" -> json("<html>")
                "confused.example.com" -> json("""{"meta":{"id":"$movie","type":"movie","name":"x"}}""")
                "empty.example.com" -> json("""{"streams":[]}""")
                else -> {
                    delay(60_000)
                    json(streamsBody("https://cdn.example.com/late.mkv"))
                }
            }
        }
        val addons = listOf("slow", "broken", "garbage", "confused", "empty", "hanging").map { streamAddon(it) }
        val aggregator = AddonAggregator(AddonTransport(client, parseDispatcher = StandardTestDispatcher(testScheduler)), perAddonTimeout = 10.seconds)

        val emissions = aggregator.streams(addons, "movie", movie).toList()

        assertEquals(1 + addons.size, emissions.size)
        assertTrue(emissions.first().all { it.state == Loadable.Loading })
        val last = emissions.last()
        assertEquals(addons, last.map { it.addon })
        val ready = last[0].state as Loadable.Ready
        assertEquals(2, ready.content.size)
        assertEquals(
            listOf(
                AddonError.Http(500).toString(),
                "InvalidResponse",
                AddonError.UnexpectedResponse("streams", "meta").toString(),
                AddonError.EmptyContent.toString(),
                AddonError.Timeout.toString(),
            ),
            last.drop(1).map { it.state.describe() },
        )
        // Partial results: the slow addon's streams arrived before the hanging one gave up.
        val slowReadyAt = emissions.indexOfFirst { it[0].state is Loadable.Ready }
        assertTrue(emissions[slowReadyAt][5].state == Loadable.Loading)
        assertEquals(10_000, currentTime)
    }

    @Test
    fun concurrencyIsCappedAndTimeoutStartsWithTheRequest() = runTest {
        var running = 0
        var maxRunning = 0
        val client = mockClient {
            running++
            maxRunning = maxOf(maxRunning, running)
            delay(1_000)
            running--
            json(streamsBody("https://cdn.example.com/a.mkv"))
        }
        val addons = (1..6).map { streamAddon("addon$it") }
        // Each request takes 1 s, under the 1.5 s timeout, though the last pair waits 2 s for a permit.
        val aggregator = AddonAggregator(
            AddonTransport(client, parseDispatcher = StandardTestDispatcher(testScheduler)),
            concurrency = 2,
            perAddonTimeout = 1.5.seconds,
        )

        val last = aggregator.streams(addons, "movie", movie).toList().last()

        assertEquals(2, maxRunning)
        assertEquals(3_000, currentTime)
        assertTrue(last.all { it.state is Loadable.Ready })
    }

    @Test
    fun droppedItemsAreReported() = runTest {
        val client = mockClient { json("""{"streams":[{"url":"https://cdn.example.com/a.mkv"},{"name":"no source"},42]}""") }
        val transport = AddonTransport(client, parseDispatcher = StandardTestDispatcher(testScheduler))
        val last = AddonAggregator(transport).streams(listOf(streamAddon("a")), "movie", movie).toList().last()
        val ready = last.single().state as Loadable.Ready
        assertEquals(1, ready.content.size)
        assertEquals(2, ready.dropped)
    }

    @Test
    fun anAnswerWhoseEveryItemIsMalformedIsAFailureNotNothingAndIsntKept() = runTest {
        var requests = 0
        val client = mockClient {
            requests++
            json("""{"streams":[{"name":"no source"},42],"cacheMaxAge":3600}""")
        }
        val transport = AddonTransport(client, AddonResponseCache(), parseDispatcher = StandardTestDispatcher(testScheduler))
        val aggregator = AddonAggregator(transport)
        repeat(2) { ask ->
            val state = aggregator.streams(listOf(streamAddon("a")), "movie", movie).toList().last().single().state
            val error = assertIs<AddonError.InvalidResponse>((state as? Loadable.Error)?.error, "ask ${ask + 1}: $state")
            assertEquals("all 2 elements malformed, the first: stream has no valid source", error.detail)
        }
        assertEquals(2, requests, "the failure isn't kept: asking again asks the addon")
    }

    @Test
    fun noApplicableAddonEmitsAnEmptyList() = runTest {
        val client = mockClient { error("no request expected") }
        val transport = AddonTransport(client, parseDispatcher = StandardTestDispatcher(testScheduler))
        val emissions = AddonAggregator(transport).streams(listOf(streamAddon("a", """["movie"]""")), "series", "tt1:1:1").toList()
        assertEquals(listOf(emptyList()), emissions)
    }

    @Test
    fun aCachedAnswerIsPreparedWhereItIsCollected() = runTest {
        val client = mockClient { json(streamsBody("https://cdn.example.com/a.mkv", extra = ""","cacheMaxAge":60""")) }
        val parse = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val aggregator = AddonAggregator(AddonTransport(client, AddonResponseCache(), parseDispatcher = parse))
        val addons = listOf(streamAddon("a"))
        val plan = AggrRequest.streams("movie", movie).plan(addons)
        fun counts() = aggregator.load(plan, ContentKind.STREAMS) { _, streams -> streams.size }

        assertEquals(Loadable.Ready(1, sent = 1), counts().toList().last().single().state)
        assertTrue(parse.dispatches > 0, "a fetched answer is read and decoded on the parse dispatcher")
        parse.dispatches = 0
        // The cached answer comes in the first emission, prepared where the flow is collected.
        assertEquals(listOf(listOf<Loadable<Int>>(Loadable.Ready(1, sent = 1))), counts().toList().map { states -> states.map { it.state } })
        assertEquals(0, parse.dispatches)
        // Streams with a prepare (their badges) prepare a cached answer on the parse dispatcher all the same.
        aggregator.streams(addons, "movie", movie) { _, streams -> streams.size }.toList()
        assertTrue(parse.dispatches > 0)
    }

    private class CountingDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatches = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            inner.dispatch(context, block)
        }
    }

    /** Error variants compared by kind where the detail is free text. */
    private fun Loadable<*>.describe(): String = when (this) {
        is Loadable.Error -> when (val e = error) {
            is AddonError.InvalidResponse -> "InvalidResponse"
            else -> e.toString()
        }
        else -> toString()
    }
}
