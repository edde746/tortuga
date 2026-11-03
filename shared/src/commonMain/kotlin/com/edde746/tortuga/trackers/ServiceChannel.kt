package com.edde746.tortuga.trackers

import com.edde746.tortuga.log.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

/**
 * One service's serial write channel for one profile. Everything sent to one account (playback reports, watched marks,
 * queued replays) goes out one request at a time, in order, with the tracker's [Tracker.writeSpacing] after each
 * request: a service may take one write per user at a time (Simkl locks for 20 s and fails what queued behind; Trakt
 * takes one write a second). Services don't wait on each other.
 *
 * A burst of playback reports behind one hung request is bounded: past [MAX_QUEUED_REPORTS] waiting reports, the
 * oldest non-terminal one (start, pause, seek) is shed. Stops are never shed, since each carries its item's watch and
 * resume point, and neither are watched marks. Main-thread confined, like the coordinator.
 */
internal class ServiceChannel(private val scope: CoroutineScope, private val service: TrackerService) {
    /** [report] is the playback report an entry carries, `null` for a watched write or replay. */
    private class Entry(val report: ScrobbleState?, val spacing: Duration, val send: suspend () -> Boolean) {
        val done = CompletableDeferred<Boolean>()
    }

    private val queue = ArrayDeque<Entry>()
    private var drain: Job? = null

    /**
     * Queues [send], which returns whether a request went out (spacing follows only then) and must not throw. The
     * answer completes with `true` once it ran, `false` when it was shed or the channel stopped.
     */
    fun submit(report: ScrobbleState?, spacing: Duration, send: suspend () -> Boolean): Deferred<Boolean> {
        val entry = Entry(report, spacing, send)
        queue.addLast(entry)
        if (queue.count { it.report != null } > MAX_QUEUED_REPORTS) {
            val shed = queue.firstOrNull { it.report != null && it.report != ScrobbleState.STOP }
            if (shed != null) {
                queue.remove(shed)
                shed.done.complete(false)
                Log.d(TAG) { "${service.id}: ${shed.report} report shed, too many waiting" }
            }
        }
        if (drain == null) drain = scope.launch { drainQueue() }
        return entry.done
    }

    private suspend fun drainQueue() {
        try {
            while (true) {
                val entry = queue.removeFirstOrNull() ?: break
                val requested = try {
                    entry.send()
                } catch (e: CancellationException) {
                    entry.done.complete(false)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, e) { "${service.id}: write failed unexpectedly" }
                    true
                }
                entry.done.complete(true)
                if (requested) delay(entry.spacing)
            }
        } finally {
            drain = null
            // Only a cancelled channel (the coordinator closed) leaves entries behind.
            while (queue.isNotEmpty()) queue.removeFirst().done.complete(false)
        }
    }

    companion object {
        private const val TAG = "trackers"

        const val MAX_QUEUED_REPORTS = 4
    }
}
