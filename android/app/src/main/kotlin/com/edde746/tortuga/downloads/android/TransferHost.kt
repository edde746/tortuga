package com.edde746.tortuga.downloads.android

import com.edde746.tortuga.downloads.DownloadTransport
import com.edde746.tortuga.downloads.KtorTransport
import com.edde746.tortuga.downloads.TransferListener
import com.edde746.tortuga.downloads.TransferRequest
import com.edde746.tortuga.downloads.TransferResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * A graph's [DownloadTransport] on Android: [KtorTransport]'s transfers, run by the [TransferHost] inside the download
 * worker. [token] names it in the notification's Cancel action.
 */
internal class WorkerTransport(val ktor: KtorTransport, private val host: TransferHost) : DownloadTransport {
    val token: Int = tokens.incrementAndGet()

    override fun start(request: TransferRequest, listener: TransferListener) = host.start(this, request, listener)

    override fun stop(id: String) = host.stop(this, id)

    private companion object {
        val tokens = AtomicInteger()
    }
}

/**
 * Runs transfers in the download worker's coroutine ([attach]) and nowhere else, so bytes only move while its foreground
 * service runs. A transfer started while no worker runs waits for the next one; the worker ending (or the system
 * stopping it) cancels what runs, which reports [TransferResult.Stopped] and keeps its `.part` file: the manager
 * queues it again. Callable from any thread.
 */
internal class TransferHost {
    /** One transfer, waiting or running: what its notification shows, and its progress on the way to the manager. */
    class Transfer(val owner: WorkerTransport, val request: TransferRequest, private val listener: TransferListener) : TransferListener {
        /** The file's bytes in place now, the request's offset included. */
        @Volatile
        var bytes: Long = request.offset
            private set

        /** The file's size, once known. */
        @Volatile
        var totalBytes: Long? = request.expectedSize
            private set

        internal val key = Key(owner, request.id)

        override fun onProgress(id: String, bytes: Long, totalBytes: Long?) {
            this.bytes = bytes
            if (totalBytes != null) this.totalBytes = totalBytes
            listener.onProgress(id, bytes, totalBytes)
        }

        override fun onResponse(id: String, validator: String?) = listener.onResponse(id, validator)

        override fun onResult(id: String, result: TransferResult) = listener.onResult(id, result)
    }

    internal data class Key(val owner: WorkerTransport, val id: String)

    private val lock = Any()
    private var scope: CoroutineScope? = null
    private val waiting = LinkedHashMap<Key, Transfer>()
    private val jobs = HashMap<Key, Job>()
    private val mutableRunning = MutableStateFlow<List<Transfer>>(emptyList())

    /** The transfers running in the worker, in the order they started. */
    val running: StateFlow<List<Transfer>> = mutableRunning.asStateFlow()

    fun start(owner: WorkerTransport, request: TransferRequest, listener: TransferListener) {
        val transfer = Transfer(owner, request, listener)
        synchronized(lock) {
            val scope = scope
            if (scope != null) launch(scope, transfer) else waiting[transfer.key] = transfer
        }
    }

    fun stop(owner: WorkerTransport, id: String) {
        val key = Key(owner, id)
        val waited: Transfer?
        val job: Job?
        synchronized(lock) {
            waited = waiting.remove(key)
            job = jobs[key]
        }
        waited?.onResult(id, TransferResult.Stopped)
        job?.cancel()
    }

    /** The worker runs: transfers run in [scope] from now on, the waiting ones first. */
    fun attach(scope: CoroutineScope) {
        synchronized(lock) {
            this.scope = scope
            waiting.values.forEach { launch(scope, it) }
            waiting.clear()
        }
    }

    /** The worker ends: transfers started from now on wait for the next one. */
    fun detach(scope: CoroutineScope) {
        synchronized(lock) {
            if (this.scope === scope) this.scope = null
        }
    }

    /**
     * Under [lock]. Atomic start: a transfer launched into a worker that is just being cancelled still runs up to its
     * first suspension, so it reports [TransferResult.Stopped] rather than nothing.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun launch(scope: CoroutineScope, transfer: Transfer) {
        jobs[transfer.key] = scope.launch(start = CoroutineStart.ATOMIC) { run(transfer) }
        mutableRunning.value += transfer
    }

    /**
     * [KtorTransport.transfer] turns every failure into a result; cancelling it (a stop, the worker ending) is
     * [TransferResult.Stopped].
     */
    private suspend fun run(transfer: Transfer) {
        val id = transfer.request.id
        val result = try {
            transfer.owner.ktor.transfer(transfer.request, transfer)
        } catch (_: CancellationException) {
            TransferResult.Stopped
        }
        synchronized(lock) {
            jobs.remove(transfer.key)
            mutableRunning.value -= transfer
        }
        transfer.onResult(id, result)
    }
}
