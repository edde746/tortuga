package com.edde746.tortuga.downloads

import com.edde746.tortuga.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A running transfer's progress, kept in memory ([Transfers.live]); the database gets it at most every 2 s. */
internal data class LiveProgress(
    val bytes: Long,
    val totalBytes: Long?,
    /** Bytes per second over the last second or so; null until measured. */
    val bytesPerSecond: Long?,
    val sampledAt: TimeMark,
    val sampledBytes: Long,
    val persistedAt: TimeMark?,
)

/**
 * The manager's side of the platform's [DownloadTransport]: starts transfers and suspends until they end, and takes
 * the transport's reports (from any thread) onto [scope] (the main thread), where [live] progress is kept and
 * [persist]ed at most every [PERSIST_INTERVAL].
 */
internal class Transfers(
    private val transport: DownloadTransport,
    private val scope: CoroutineScope,
    private val timeSource: TimeSource,
    private val persist: (id: Long, bytes: Long) -> Unit,
    private val validator: (id: Long, validator: String?) -> Unit,
) : TransferListener {
    private val pending = mutableMapOf<String, CompletableDeferred<TransferResult>>()
    private val mutableLive = MutableStateFlow<Map<Long, LiveProgress>>(emptyMap())

    /** Progress of the transfers running now, by download id. */
    val live: StateFlow<Map<Long, LiveProgress>> = mutableLive.asStateFlow()

    override fun onProgress(id: String, bytes: Long, totalBytes: Long?) {
        scope.launch { progress(id.toLongOrNull() ?: return@launch, bytes, totalBytes) }
    }

    override fun onResponse(id: String, validator: String?) {
        scope.launch { validator(id.toLongOrNull() ?: return@launch, validator) }
    }

    override fun onResult(id: String, result: TransferResult) {
        scope.launch {
            val waiting = pending.remove(id)
            if (waiting == null) Log.d(TAG) { "result for transfer $id, which nothing waits for: ${result::class.simpleName}" }
            waiting?.complete(result)
        }
    }

    /** Starts [request] and suspends until it ends. Cancelled, it stops the transfer ([stopping]: and waits for that). */
    suspend fun transfer(request: TransferRequest, stopping: () -> Boolean): TransferResult {
        val result = expect(request.id)
        transport.start(request, this)
        return await(request.id, result, stopping)
    }

    /** A result to wait for from a transfer the transport already runs ([DownloadTransport.reattach]). */
    fun expect(id: String): CompletableDeferred<TransferResult> = CompletableDeferred<TransferResult>().also { pending[id] = it }

    fun forget(id: String) {
        pending.remove(id)
    }

    /**
     * Suspends until [result]. Cancelled, it asks the transport to stop [id] and, when [stopping] (a pause, cancel or
     * delete, not the app closing), waits up to [STOP_WAIT] for that so the part file is released before anything
     * touches it.
     */
    suspend fun await(id: String, result: CompletableDeferred<TransferResult>, stopping: () -> Boolean): TransferResult = try {
        result.await()
    } catch (e: CancellationException) {
        transport.stop(id)
        if (stopping()) withContext(NonCancellable) { withTimeoutOrNull(STOP_WAIT) { result.await() } }
        pending.remove(id)
        throw e
    }

    /** Forgets [id]'s live progress (its transfer ended). */
    fun clear(id: Long) {
        mutableLive.update { it - id }
    }

    private fun progress(id: Long, bytes: Long, totalBytes: Long?) {
        val now = timeSource.markNow()
        var due = false
        mutableLive.update { all ->
            val previous = all[id]
            val next = if (previous == null) {
                LiveProgress(bytes, totalBytes, null, now, bytes, persistedAt = null)
            } else {
                val elapsed = previous.sampledAt.elapsedNow()
                val sampled = elapsed >= SPEED_WINDOW
                LiveProgress(
                    bytes = bytes,
                    totalBytes = totalBytes ?: previous.totalBytes,
                    bytesPerSecond = if (sampled) ((bytes - previous.sampledBytes).coerceAtLeast(0) * 1000 / elapsed.inWholeMilliseconds.coerceAtLeast(1)) else previous.bytesPerSecond,
                    sampledAt = if (sampled) now else previous.sampledAt,
                    sampledBytes = if (sampled) bytes else previous.sampledBytes,
                    persistedAt = previous.persistedAt,
                )
            }
            due = next.persistedAt == null || next.persistedAt.elapsedNow() >= PERSIST_INTERVAL
            all + (id to if (due) next.copy(persistedAt = now) else next)
        }
        if (due) persist(id, bytes)
    }

    companion object {
        val PERSIST_INTERVAL: Duration = 2.seconds
        val SPEED_WINDOW: Duration = 1.seconds
        val STOP_WAIT: Duration = 10.seconds
        private const val TAG = "downloads"
    }
}
