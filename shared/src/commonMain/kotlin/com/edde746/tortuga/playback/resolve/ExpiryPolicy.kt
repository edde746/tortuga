package com.edde746.tortuga.playback.resolve

import com.edde746.tortuga.playback.EngineError
import com.edde746.tortuga.playback.PlaybackFailure

/**
 * Where a playing stream stands with respect to mid-play expiry. Resolved CDN links expire, so an HTTP
 * 403, 404 or 410 while playing usually means the link died, not the stream.
 */
sealed interface ExpiryState {
    /** Playing the stream's first resolution; one re-resolve is left. */
    data object FirstResolution : ExpiryState

    /** Resolving the stream's original URL again, to resume at [resumeAtSeconds]. */
    data class ReResolving(val resumeAtSeconds: Double) : ExpiryState

    /** Playing the re-resolved URL; no re-resolve is left. */
    data object ReResolved : ExpiryState

    /** Final: the session shows [failure] and offers the next stream. */
    data class Failed(val failure: PlaybackFailure) : ExpiryState
}

/** What the playback session does next. */
sealed interface ExpiryDecision {
    /** Resolve the stream's original [StreamRequest] again; pass the result to [ExpiryPolicy.onReResolved]. */
    data class ReResolve(val resumeAtSeconds: Double) : ExpiryDecision

    /** Load [stream] in the engine, starting at [resumeAtSeconds]. */
    data class Reload(val stream: ResolvedStream, val resumeAtSeconds: Double) : ExpiryDecision

    /** Stop, show [failure] and offer the next stream. */
    data class Surface(val failure: PlaybackFailure) : ExpiryDecision

    /** The event doesn't apply in this state (a late engine error while re-resolving, or after failing). */
    data object Ignore : ExpiryDecision
}

data class ExpiryStep(val state: ExpiryState, val decision: ExpiryDecision)

/**
 * Mid-play expiry, as a pure state machine the playback session drives:
 * - an engine error with HTTP 403/404/410 after the file had loaded re-resolves the original URL once and resumes at
 *   the last position;
 * - any other error, an expiry error before the file loaded (the link was fresh, so resolving again wouldn't help),
 *   a failed re-resolve, or an error after the one re-resolve surfaces the error and offers the next stream.
 */
object ExpiryPolicy {
    /** HTTP statuses that mean the link expired. */
    val EXPIRY_STATUSES: Set<Int> = setOf(403, 404, 410)

    /**
     * @param loaded whether the engine had loaded the current URL (it played) before [error].
     * @param lastPositionSeconds the last position the session saw while playing, where playback resumes.
     */
    fun onEngineError(state: ExpiryState, error: EngineError, loaded: Boolean, lastPositionSeconds: Double): ExpiryStep {
        val surface = PlaybackFailure.Engine(error)
        return when (state) {
            ExpiryState.FirstResolution ->
                if (loaded && error.httpStatus in EXPIRY_STATUSES) {
                    val resumeAt = lastPositionSeconds.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                    ExpiryStep(ExpiryState.ReResolving(resumeAt), ExpiryDecision.ReResolve(resumeAt))
                } else {
                    fail(surface)
                }
            ExpiryState.ReResolved -> fail(surface)
            is ExpiryState.ReResolving, is ExpiryState.Failed -> ExpiryStep(state, ExpiryDecision.Ignore)
        }
    }

    /** The result of the [ExpiryDecision.ReResolve] the policy asked for. */
    fun onReResolved(state: ExpiryState, result: ResolveResult): ExpiryStep {
        if (state !is ExpiryState.ReResolving) return ExpiryStep(state, ExpiryDecision.Ignore)
        return when (result) {
            is ResolveResult.Resolved ->
                ExpiryStep(ExpiryState.ReResolved, ExpiryDecision.Reload(result.stream, state.resumeAtSeconds))
            is ResolveResult.Failed -> fail(PlaybackFailure.Resolve(result.failure))
        }
    }

    private fun fail(failure: PlaybackFailure) = ExpiryStep(ExpiryState.Failed(failure), ExpiryDecision.Surface(failure))
}
