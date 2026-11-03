package com.edde746.tortuga.downloads

import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.data.downloads.DownloadRow
import com.edde746.tortuga.data.downloads.DownloadStream
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.playback.resolve.ResolveFailure
import com.edde746.tortuga.playback.resolve.ResolveResult
import com.edde746.tortuga.playback.resolve.ResolvedStream
import com.edde746.tortuga.playback.resolve.StreamHeaders
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How one run of a download ended ([DownloadWorker.run]). */
internal sealed interface Outcome {
    data class Completed(val bytes: Long) : Outcome

    data class Failed(val failure: DownloadFailure) : Outcome {
        constructor(kind: DownloadFailureKind, httpStatus: Int? = null) : this(DownloadFailure(kind, httpStatus))
    }

    /** The volume is full: the queue stops. */
    data object DiskFull : Outcome

    /** Back to the queue, nothing counted: the network no longer allows it, or the platform stopped the transfer. */
    data object Requeue : Outcome

    /** The download no longer exists. */
    data object Gone : Outcome
}

/**
 * Runs one download until it completes or fails (DownloadManager's transfer rules):
 * resolves the stream right before each attempt (or reuses the download's last resolve while it is [REUSE_WINDOW]
 * old and the download's stream unchanged: an addon's service may mint a new URL per resolve, and Apple's resume data
 * only resumes the URL it came from), checks the free space, transfers into the `.part` file resuming what is there,
 * recovers an expired link (re-resolving the original stream, else finding the same stream in its addon's answer
 * again; once more each time the `.part` grew since the last recovery, so a link that delivers nothing ends it),
 * restarts from zero only when the file isn't the same, retries network and server failures [MAX_RETRIES] times
 * [RETRY_DELAY] apart, verifies the size and moves the file into place. A failed write ends it as a full volume when the
 * location has less than [SPACE_MARGIN] free (the queue stops), else as the location unavailable: the transport's error
 * can't tell, its text being the OS's, in the user's language.
 */
internal class DownloadWorker(private val env: DownloadEnv, private val transfers: Transfers, private val timeSource: TimeSource) {
    /** Each download's last resolve, in memory only (its URL and headers carry keys), by download id. Main thread. */
    private val resolves = mutableMapOf<Long, LastResolve>()

    /** Forgets [id]'s last resolve: it completed, failed, was cancelled or deleted. */
    fun forget(id: Long) {
        resolves.remove(id)
    }

    /**
     * Runs [id]; [attached] is the result of a transfer the platform already runs (reattached at launch). [stopping]
     * tells a deliberate stop (pause, cancel, delete, the network) from the app closing.
     */
    suspend fun run(id: Long, attached: CompletableDeferred<TransferResult>?, stopping: () -> Boolean): Outcome {
        var row = env.repo.download(id) ?: return Outcome.Gone
        var retries = row.attempts
        // The `.part`'s size when an expired link was last recovered: another expiry recovers again only past it.
        var recoveredAt: Long? = null
        var restarts = 0
        var reattached = attached
        while (true) {
            val directory = env.locationPath(row.locationId) ?: return Outcome.Failed(DownloadFailureKind.LOCATION_UNAVAILABLE)
            val result: TransferResult
            val expected: Long?
            val path: String
            if (reattached != null) {
                path = row.relativePath ?: return Outcome.Requeue
                expected = row.totalBytes
                env.repo.setStatus(id, DownloadStatus.DOWNLOADING)
                result = transfers.await(id.toString(), reattached, stopping)
                reattached = null
            } else {
                env.repo.setStatus(id, DownloadStatus.RESOLVING)
                val reused = resolves[id]?.takeIf { it.from == row.stream && it.at.elapsedNow() < REUSE_WINDOW }?.stream
                val stream = reused ?: when (val step = resolve(row)) {
                    is Step.Go -> step.stream
                    is Step.Stop -> return step.outcome
                    is Step.Retry -> {
                        row = retry(row, ++retries, step.failure) { return it }
                        continue
                    }
                }
                row = env.repo.download(id) ?: return Outcome.Gone
                if (reused == null) resolves[id] = LastResolve(stream, row.stream, timeSource.markNow())
                val size = stream.size
                if (row.totalBytes != null && size != null && size != row.totalBytes) {
                    Log.i(TAG) { "download $id: the file changed size; starting over" }
                    row = restart(row) ?: return Outcome.Gone
                }
                path = row.relativePath ?: DownloadNames.videoPath(row, DownloadNames.extension(stream.filename, row.hintedFilename()))
                    .also { env.repo.setPath(id, it) }
                val video = child(directory, path)
                if (!env.io { env.createDirectories(directory, path.substringBeforeLast('/')) }) {
                    return Outcome.Failed(DownloadFailureKind.LOCATION_UNAVAILABLE)
                }
                val part = Path("$video${DownloadNames.PART}")
                var offset = env.io { size(part) } ?: 0L
                if (size != null && offset > size) {
                    env.io { deletePart(video) }
                    offset = 0
                }
                if (size != null) {
                    val free = env.io { env.platform.freeBytes(directory) }
                    if (free != null && free < size - offset + SPACE_MARGIN) return Outcome.Failed(DownloadFailureKind.NOT_ENOUGH_SPACE)
                }
                env.repo.setResolved(id, size, stream.videoHash, stream.filename)
                expected = size
                if (size != null && offset == size) {
                    result = TransferResult.Completed(offset, row.validator)
                } else {
                    val request = TransferRequest(
                        id = id.toString(),
                        url = stream.url,
                        headers = headers(stream),
                        partPath = part.toString(),
                        offset = offset,
                        expectedSize = size,
                        validator = row.validator.takeIf { offset > 0 },
                        title = title(row),
                        wifiOnly = env.wifiOnly(),
                    )
                    env.repo.setStatus(id, DownloadStatus.DOWNLOADING)
                    env.repo.setTransferring(id, true)
                    result = transfers.transfer(request, stopping)
                }
            }
            env.repo.setTransferring(id, false)
            val reported = transfers.live.value[id]?.bytes ?: 0L
            transfers.clear(id)
            val video = child(directory, path)
            val partBytes = if (result is TransferResult.Completed) 0L else env.io { size(Path("$video${DownloadNames.PART}")) } ?: 0L
            if (result !is TransferResult.Completed) env.repo.setProgress(id, maxOf(partBytes, reported))
            when (result) {
                is TransferResult.Completed -> return complete(id, video, expected, result)
                is TransferResult.Http -> when {
                    result.status in EXPIRED && recoveredAt.let { it == null || partBytes > it } -> {
                        Log.i(TAG) { "download $id: HTTP ${result.status}; resolving the stream again" }
                        recoveredAt = partBytes
                        forget(id)
                        row = env.repo.download(id) ?: return Outcome.Gone
                    }
                    result.status == RANGE_NOT_SATISFIABLE -> {
                        forget(id)
                        if (++restarts > MAX_RESTARTS) return Outcome.Failed(DownloadFailureKind.HTTP, result.status)
                        row = restart(row) ?: return Outcome.Gone
                    }
                    result.status >= 500 || result.status == TOO_MANY_REQUESTS ->
                        row = retry(row, ++retries, DownloadFailure(DownloadFailureKind.HTTP, result.status)) { return it }
                    else -> return Outcome.Failed(DownloadFailureKind.HTTP, result.status)
                }
                TransferResult.Changed -> {
                    Log.i(TAG) { "download $id: the server's file changed; starting over" }
                    forget(id)
                    if (++restarts > MAX_RESTARTS) return Outcome.Failed(DownloadFailureKind.NETWORK)
                    row = restart(row) ?: return Outcome.Gone
                }
                is TransferResult.Network -> {
                    Log.i(TAG) { "download $id: transfer failed (${result.kind})" }
                    if (!env.networkAllows()) return Outcome.Requeue
                    row = retry(row, ++retries, DownloadFailure(DownloadFailureKind.NETWORK)) { return it }
                }
                TransferResult.DiskFull -> return Outcome.DiskFull
                TransferResult.WriteFailed -> {
                    val free = env.io { env.platform.freeBytes(directory) }
                    return if (free != null && free < SPACE_MARGIN) Outcome.DiskFull else Outcome.Failed(DownloadFailureKind.LOCATION_UNAVAILABLE)
                }
                TransferResult.Stopped -> return Outcome.Requeue
            }
        }
    }

    /** The file is all there: its size verified against [expected], moved into place. */
    private suspend fun complete(id: Long, video: Path, expected: Long?, result: TransferResult.Completed): Outcome {
        val part = Path("$video${DownloadNames.PART}")
        val bytes = env.io { size(part) } ?: return Outcome.Failed(DownloadFailureKind.LOCATION_UNAVAILABLE)
        if (expected != null && bytes != expected) {
            Log.w(TAG) { "download $id: $bytes bytes arrived of $expected" }
            env.io { deletePart(video) }
            env.repo.resetFile(id)
            return Outcome.Failed(DownloadFailureKind.SIZE_MISMATCH)
        }
        if (!env.io { move(part, video) }) return Outcome.Failed(DownloadFailureKind.LOCATION_UNAVAILABLE)
        env.io { DownloadNames.PART_SIDECARS.forEach { delete(Path("$video$it")) } }
        env.repo.complete(id, bytes, result.validator)
        return Outcome.Completed(bytes)
    }

    /** Counts a failed attempt; past [MAX_RETRIES] it fails with [failure], else waits [RETRY_DELAY] queued. */
    private suspend inline fun retry(row: DownloadRow, attempts: Int, failure: DownloadFailure, fail: (Outcome) -> Nothing): DownloadRow {
        env.repo.setAttempts(row.id, attempts)
        if (attempts > MAX_RETRIES) fail(Outcome.Failed(failure))
        env.repo.setStatus(row.id, DownloadStatus.QUEUED)
        delay(RETRY_DELAY)
        if (!env.networkAllows()) fail(Outcome.Requeue)
        return env.repo.download(row.id) ?: fail(Outcome.Gone)
    }

    /** Nothing of the file is kept: the part goes, and the next attempt starts from zero. */
    private suspend fun restart(row: DownloadRow): DownloadRow? {
        val directory = env.locationPath(row.locationId)
        if (directory != null && row.relativePath != null) env.io { deletePart(child(directory, row.relativePath)) }
        env.repo.resetFile(row.id)
        return env.repo.download(row.id)
    }

    /** The stream's final URL, size and hash, right before an attempt. */
    private suspend fun resolve(row: DownloadRow): Step {
        val choice = when (val stored = row.stream) {
            is DownloadStream.Picked -> stored.choice
            // One that can't be read can't be resolved either: the user picks a stream again, as for none.
            DownloadStream.None, is DownloadStream.Unreadable -> return Step.Stop(Outcome.Failed(DownloadFailureKind.NO_STREAM))
        }
        val request = choice.request() ?: return Step.Stop(Outcome.Failed(DownloadFailureKind.UNSUPPORTED))
        return when (val result = env.resolver.resolve(request)) {
            is ResolveResult.Resolved -> Step.Go(result.stream)
            is ResolveResult.Failed -> {
                val failure = result.failure
                if (failure is ResolveFailure.Http && failure.status in EXPIRED) findAgain(row, choice) else step(failure)
            }
        }
    }

    /**
     * The stored stream no longer resolves (403, 404, 410): its addon's streams are fetched again (the ones the title
     * carries for the video, when that addon sends the title, then its `/stream` answer) and the same stream found as
     * the stream history finds it ([StreamChoice.indexOfRemembered]); gone when the addon no longer offers it.
     */
    private suspend fun findAgain(row: DownloadRow, choice: StreamChoice): Step {
        val addon = env.ownerAddons(row.id).firstOrNull { it.transportUrl == choice.addonUrl }
            ?: return Step.Stop(Outcome.Failed(DownloadFailureKind.STREAM_GONE))
        val sentMeta = env.aggregator.meta(listOf(addon), row.type, row.metaId).last().firstOrNull()?.state
        val carried = (sentMeta as? Loadable.Ready)?.content?.carriedStreams(row.videoId).orEmpty()
        val streams = carried.takeIf { StreamChoice.indexOfRemembered(it, addon.transportUrl, choice) >= 0 }
            ?: when (val state = env.aggregator.streams(listOf(addon), row.type, row.videoId).last().firstOrNull()?.state) {
                is Loadable.Ready -> state.content
                is Loadable.Error -> when (val error = state.error) {
                    AddonError.Timeout, is AddonError.Network -> return Step.Retry(DownloadFailure(DownloadFailureKind.NETWORK))
                    is AddonError.Http -> if (error.status >= 500) return Step.Retry(DownloadFailure(DownloadFailureKind.NETWORK)) else emptyList()
                    else -> emptyList()
                }
                Loadable.Loading, null -> emptyList()
            }
        val index = StreamChoice.indexOfRemembered(streams, addon.transportUrl, choice)
        if (index < 0) return Step.Stop(Outcome.Failed(DownloadFailureKind.STREAM_GONE))
        val found = StreamChoice(streams[index], addon.transportUrl, addon.manifest.name)
        if (found.stream != choice.stream) env.repo.setStream(row.id, found)
        val request = found.request() ?: return Step.Stop(Outcome.Failed(DownloadFailureKind.UNSUPPORTED))
        return when (val result = env.resolver.resolve(request)) {
            is ResolveResult.Resolved -> Step.Go(result.stream)
            is ResolveResult.Failed -> step(result.failure)
        }
    }

    private fun step(failure: ResolveFailure): Step = when (failure) {
        is ResolveFailure.Http -> if (failure.status >= 500 || failure.status == TOO_MANY_REQUESTS) {
            Step.Retry(DownloadFailure(DownloadFailureKind.HTTP, failure.status))
        } else {
            Step.Stop(Outcome.Failed(DownloadFailureKind.HTTP, failure.status))
        }
        ResolveFailure.Timeout, is ResolveFailure.Network -> Step.Retry(DownloadFailure(DownloadFailureKind.NETWORK))
        ResolveFailure.UnsupportedUrl -> Step.Stop(Outcome.Failed(DownloadFailureKind.UNSUPPORTED))
        ResolveFailure.TooManyRedirects -> Step.Stop(Outcome.Failed(DownloadFailureKind.NETWORK))
        // The resolver's own failure (logged there): another attempt fails the same way.
        ResolveFailure.Unexpected -> Step.Stop(Outcome.Failed(DownloadFailureKind.UNSUPPORTED))
    }

    /** The resolved stream's headers, with the app's `User-Agent` unless the addon asked for its own (as the player sends). */
    private fun headers(stream: ResolvedStream): Map<String, String> =
        if (stream.userAgent != null) stream.headers else stream.headers + (StreamHeaders.USER_AGENT to env.userAgent)

    /** The title the platform's notification shows: "S1E2 · Breaking Bad" (the name isolated), or the movie's name. */
    private fun title(row: DownloadRow): String {
        val season = row.season
        val episode = row.episode
        return if (season != null && episode != null) T.session.episodeTitle(T.session.episode(season.toString(), episode.toString()), isolate(row.title)) else row.title
    }

    /** A resolve kept for the next attempt of the download while its stream is still [from]. */
    private class LastResolve(val stream: ResolvedStream, val from: DownloadStream, val at: TimeMark)

    private sealed interface Step {
        class Go(val stream: ResolvedStream) : Step
        class Retry(val failure: DownloadFailure) : Step
        class Stop(val outcome: Outcome) : Step
    }

    companion object {
        const val MAX_RETRIES = 3

        /** How long a resolve is reused for the same download's next attempts (resolved links live for hours). */
        val REUSE_WINDOW: Duration = 30.minutes
        val RETRY_DELAY: Duration = 30.seconds

        /**
         * Room kept free beyond the file's size (its subtitles and artwork, the database, the system). A transfer of a
         * known size starts only with this much to spare beyond what it still needs, so a failed write that finds less
         * than this free is the volume filling up.
         */
        const val SPACE_MARGIN: Long = 100L * 1024 * 1024
        private const val MAX_RESTARTS = 3
        private const val RANGE_NOT_SATISFIABLE = 416
        private const val TOO_MANY_REQUESTS = 429
        private val EXPIRED = setOf(403, 404, 410)
        private const val TAG = "downloads"
    }
}

/** The file name the addon gave the stream, for the extension when the resolve has none (a resolve needs the stream). */
private fun DownloadRow.hintedFilename(): String? = (stream as? DownloadStream.Picked)?.choice?.stream?.behaviorHints?.filename
