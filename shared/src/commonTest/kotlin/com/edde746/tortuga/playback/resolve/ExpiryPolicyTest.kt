package com.edde746.tortuga.playback.resolve

import com.edde746.tortuga.playback.EngineError
import com.edde746.tortuga.playback.PlaybackFailure
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class ExpiryPolicyTest {
    private val stream = ResolvedStream("https://cdn.example/new.mkv", emptyMap(), 1000, null, null)

    @Test
    fun anExpiredLinkIsResolvedAgainOnceAndResumesAtTheLastPosition() {
        for (status in listOf(403, 404, 410)) {
            val error = EngineError("HTTP error $status", status)
            val first = ExpiryPolicy.onEngineError(ExpiryState.FirstResolution, error, loaded = true, lastPositionSeconds = 1234.5)
            assertEquals(ExpiryStep(ExpiryState.ReResolving(1234.5), ExpiryDecision.ReResolve(1234.5)), first)

            val reloaded = ExpiryPolicy.onReResolved(first.state, ResolveResult.Resolved(stream))
            assertEquals(ExpiryStep(ExpiryState.ReResolved, ExpiryDecision.Reload(stream, 1234.5)), reloaded)

            // The re-resolved link failing too is the stream's fault, not the link's.
            val second = ExpiryPolicy.onEngineError(reloaded.state, error, loaded = true, lastPositionSeconds = 1300.0)
            val failure = PlaybackFailure.Engine(error)
            assertEquals(ExpiryStep(ExpiryState.Failed(failure), ExpiryDecision.Surface(failure)), second)
        }
    }

    @Test
    fun otherErrorsSurfaceAtOnce() {
        val errors = listOf(EngineError("HTTP error 500", 500), EngineError("HTTP error 401", 401), EngineError("Failed to recognize file format"))
        for (error in errors) {
            val step = ExpiryPolicy.onEngineError(ExpiryState.FirstResolution, error, loaded = true, lastPositionSeconds = 60.0)
            assertEquals(ExpiryDecision.Surface(PlaybackFailure.Engine(error)), step.decision, error.message)
            assertIs<ExpiryState.Failed>(step.state)
        }
    }

    @Test
    fun anExpiryStatusBeforeTheFileLoadedSurfaces() {
        // The link was resolved moments ago; resolving it again wouldn't help.
        val error = EngineError("HTTP error 403", 403)
        val step = ExpiryPolicy.onEngineError(ExpiryState.FirstResolution, error, loaded = false, lastPositionSeconds = 0.0)
        assertEquals(ExpiryDecision.Surface(PlaybackFailure.Engine(error)), step.decision)
    }

    @Test
    fun aFailedReResolveSurfacesTheResolveFailure() {
        val reResolving = ExpiryState.ReResolving(10.0)
        val step = ExpiryPolicy.onReResolved(reResolving, ResolveResult.Failed(ResolveFailure.Http(404)))
        val failure = PlaybackFailure.Resolve(ResolveFailure.Http(404))
        assertEquals(ExpiryStep(ExpiryState.Failed(failure), ExpiryDecision.Surface(failure)), step)
    }

    @Test
    fun eventsThatDontApplyAreIgnored() {
        val error = EngineError("HTTP error 403", 403)
        val reResolving = ExpiryState.ReResolving(10.0)
        val failed = ExpiryState.Failed(PlaybackFailure.Engine(error))
        // A late error from the dead link while its replacement resolves; anything after failing.
        assertEquals(ExpiryStep(reResolving, ExpiryDecision.Ignore), ExpiryPolicy.onEngineError(reResolving, error, true, 20.0))
        assertEquals(ExpiryStep(failed, ExpiryDecision.Ignore), ExpiryPolicy.onEngineError(failed, error, true, 20.0))
        // A re-resolve result nobody asked for.
        for (state in listOf(ExpiryState.FirstResolution, ExpiryState.ReResolved, failed)) {
            assertEquals(ExpiryStep(state, ExpiryDecision.Ignore), ExpiryPolicy.onReResolved(state, ResolveResult.Resolved(stream)))
        }
    }

    @Test
    fun anUnknownPositionResumesFromTheStart() {
        val error = EngineError("HTTP error 410", 410)
        for (position in listOf(-3.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val step = ExpiryPolicy.onEngineError(ExpiryState.FirstResolution, error, loaded = true, lastPositionSeconds = position)
            assertEquals(ExpiryDecision.ReResolve(0.0), step.decision, "$position")
        }
    }

    @Test
    fun reResolvingTheOriginalUrlGetsAFreshLink() = runTest {
        // An addon's host hands out a new CDN link per resolve; the first one has expired by the time it fails.
        var issued = 0
        val file = lcgBytes(200_000)
        val http = FakeHttp(this) { request ->
            when (request.url.encodedPath) {
                "/resolve/KEY/abc" -> redirect("https://cdn.example/link${++issued}/f.mkv")
                "/link1/f.mkv", "/link2/f.mkv" -> serveFile(request, file)
                else -> error("unexpected ${request.url}")
            }
        }
        val resolver = StreamResolver(http.client)
        val original = StreamRequest("https://addon.example/resolve/KEY/abc")
        val first = assertIs<ResolveResult.Resolved>(resolver.resolve(original)).stream
        assertEquals("https://cdn.example/link1/f.mkv", first.url)

        val expired = ExpiryPolicy.onEngineError(ExpiryState.FirstResolution, EngineError("HTTP error 410", 410), true, 842.0)
        val decision = assertIs<ExpiryDecision.ReResolve>(expired.decision)
        val reloaded = ExpiryPolicy.onReResolved(expired.state, resolver.resolve(original))
        val reload = assertIs<ExpiryDecision.Reload>(reloaded.decision)
        assertEquals("https://cdn.example/link2/f.mkv", reload.stream.url)
        assertEquals(first.videoHash, reload.stream.videoHash)
        assertEquals(decision.resumeAtSeconds, reload.resumeAtSeconds)
    }
}
