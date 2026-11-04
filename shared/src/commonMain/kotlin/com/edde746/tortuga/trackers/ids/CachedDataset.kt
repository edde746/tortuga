package com.edde746.tortuga.trackers.ids

import com.edde746.tortuga.crypto.cryptoProvider
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.readBody
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLDecodeException
import io.ktor.http.decodeURLPart
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * The file [asset] of the latest release of the GitHub [repository] (`https://github.com/<owner>/<name>`), asked for as
 * [accept], downloaded on first use or by [updateDiskCopy], kept on disk and re-checked daily by the latest release's
 * tag.
 *
 * - [get] loads the disk copy, or downloads it when there is none, and opens it off the caller's thread
 *   ([parseDispatcher]); concurrent callers share one load, and later ones get the index at once. The load runs on
 *   [ioDispatcher] from start to end, never on [scope]'s own dispatcher (the app's main thread), so a busy main thread
 *   doesn't hold it up between its steps. No index (offline with no disk copy) isn't kept, so the next call tries again.
 * - [updateDiskCopy] brings the disk copy up to date without keeping its index: it downloads the file when there is
 *   none, else makes the daily check, opening a download only to check it (and to write its snapshot). A load that
 *   starts meanwhile waits for it, then opens the disk copy without a request.
 * - After the first load, [refreshIfDue] runs in [scope] at most every [REFRESH_INTERVAL]: the tag of the latest
 *   release, which downloads only once the tag moved. An unchanged tag only records the check; a new release replaces
 *   the disk copy and the index, once its file has opened.
 * - A release's file comes from one tag: the latest release is resolved to its tag first, then its `SHA256SUMS` and
 *   the file are both fetched from that tag, so a release published in between can't pair one release's checksum
 *   with another's file. A file that doesn't match its listed checksum is therefore corrupt.
 * - Downloads stream to a temporary file, which is checked against the release's checksum and opened before it
 *   replaces the copy. A download that fails its checksum, runs past [maxBytes], is unreadable or opens empty is
 *   deleted and counts as the check: the copy in use stays, and with no copy, lookups download again only
 *   [REFRESH_INTERVAL] later. A copy on disk that is unreadable, empty or larger than [maxBytes] is deleted, so the
 *   next call downloads it again.
 * - With [snapshot], the index is also kept as `<fileName>.packed`, written once a copy has opened (after the lookups
 *   waiting for that load have the index, before its daily check; under the lock a check holds, and only while that
 *   copy is still the one in place) and deleted before another copy replaces it; a load reads it instead of opening
 *   the copy, and opens the copy (writing it again) when it is missing or unreadable.
 * - The release tag, the time of the last check and of the last unusable download are in `<fileName>.state` next to
 *   the copy.
 */
internal class CachedDataset<T : Any>(
    private val label: String,
    private val fileName: String,
    private val repository: String,
    private val asset: String,
    private val accept: String,
    private val http: HttpClient,
    private val directory: Path,
    private val fileSystem: FileSystem,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val parseDispatcher: CoroutineDispatcher,
    private val open: (Path) -> T,
    private val isEmpty: (T) -> Boolean,
    private val summary: (T) -> String,
    /** The largest file this dataset takes, a few times its release's: a download or copy past it is unusable. */
    private val maxBytes: Long,
    private val snapshot: PackedForm<T>? = null,
) {
    private val lock = Mutex()
    private var index: T? = null
    private var loading: Deferred<T?>? = null

    /** Held while the disk copy is read or changed by a load or a check, so the two never download the same file. */
    private val disk = Mutex()

    private val file = Path(directory, fileName)
    private val partial = Path(directory, "$fileName.part")
    private val stateFile = Path(directory, "$fileName.state")
    private val snapshotFile = Path(directory, "$fileName.packed")
    private val snapshotPart = Path(directory, "$fileName.packed.part")

    /** [http] without following redirects: a release's `releases/latest` answers with its tag's address. */
    private val noRedirects by lazy { http.config { followRedirects = false } }

    /** The index, or `null` when there is no usable disk copy and none could be downloaded. */
    suspend fun get(): T? {
        index?.let { return it }
        val load = lock.withLock {
            index?.let { return it }
            loading ?: scope.async(ioDispatcher) { loadOrDownload() }.also { loading = it }
        }
        return load.await()
    }

    /** The check, when the last one is [REFRESH_INTERVAL] old and an index is loaded. Never throws. */
    suspend fun refreshIfDue() {
        if (index == null) return
        check()
    }

    /**
     * Brings the disk copy up to date without keeping its index (the app's start, on every device): downloads the file
     * when there is none, else the check when the last one is [REFRESH_INTERVAL] old. An index loaded already is
     * replaced by a new release, as [refreshIfDue] does. Never throws.
     */
    suspend fun updateDiskCopy() = check()

    private suspend fun check() = disk.withLock {
        try {
            val state = readState()
            val now = clock.now().toEpochMilliseconds()
            val exists = withContext(ioDispatcher) { fileSystem.exists(file) }
            val since = if (exists) state.lastCheckMillis else state.unusableMillis
            if (since != null && now - since < REFRESH_INTERVAL.inWholeMilliseconds) return@withLock
            when (val outcome = download(state, inUse = exists)) {
                Download.NotModified -> {
                    writeState(state.copy(lastCheckMillis = now))
                    Log.d(TAG) { "$label: unchanged" }
                }
                is Download.Failed -> {
                    // An answer counts as today's check; no answer (offline) tries again next time.
                    if (outcome.answered && exists) writeState(state.copy(lastCheckMillis = now))
                    Log.d(TAG) { "$label: check failed (${outcome.reason})" }
                }
                is Download.Unusable -> writeState(state.copy(lastCheckMillis = now, unusableMillis = now))
                is Download.Fresh -> {
                    // An unusable file keeps the copy in use, and its tag, so the next check asks again.
                    val fresh = install(outcome, state, now, keep = false) ?: return@withLock
                    saveSnapshot(fresh)
                    Log.i(TAG) { "$label: ${if (exists) "refreshed" else "downloaded"} (${summary(fresh)})" }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, e) { "$label: check failed" }
            runCatching { fileSystem.delete(partial, mustExist = false) }
        }
    }

    private suspend fun loadOrDownload(): T? {
        val loaded = try {
            disk.withLock { loadFromDisk() ?: downloadAndLoad() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, e) { "$label: unreadable or empty copy, deleting it so the next lookup downloads again" }
            runCatching { withContext(ioDispatcher) { fileSystem.delete(file, mustExist = false) } }
            null
        }
        lock.withLock { loading = null }
        // Neither the snapshot nor the daily check holds up the lookups waiting for this load.
        if (loaded != null) {
            scope.launch(ioDispatcher) {
                // Under [disk], so no check puts another copy in place meanwhile; one that did since wrote its own.
                if (snapshot != null) {
                    disk.withLock { if (lock.withLock { index === loaded } && !fileSystem.exists(snapshotFile)) saveSnapshot(loaded) }
                }
                refreshIfDue()
            }
        }
        return loaded
    }

    /** The disk copy, or `null` when there is none; throws when it is unreadable or empty. */
    private suspend fun loadFromDisk(): T? {
        val exists = withContext(ioDispatcher) { fileSystem.exists(file) }
        if (!exists) return null
        val copy = unsnapshotted() ?: openChecked(file)
        lock.withLock { index = copy }
        return copy
    }

    private suspend fun downloadAndLoad(): T? {
        val state = readState()
        val now = clock.now().toEpochMilliseconds()
        val unusable = state.unusableMillis
        if (unusable != null && now - unusable < REFRESH_INTERVAL.inWholeMilliseconds) {
            Log.d(TAG) { "$label: no copy on disk, and the last download was unusable; asking again $REFRESH_INTERVAL after it" }
            return null
        }
        Log.d(TAG) { "$label: no copy on disk, downloading" }
        return when (val outcome = download(state, inUse = false)) {
            // Seeds the daily check, so a relaunch the same day doesn't ask again.
            is Download.Fresh -> install(outcome, state, now, keep = true)?.also { Log.i(TAG) { "$label: downloaded (${summary(it)})" } }
            is Download.Failed -> {
                Log.w(TAG) { "$label: download failed (${outcome.reason})" }
                null
            }
            is Download.Unusable -> {
                writeState(state.copy(lastCheckMillis = now, unusableMillis = now))
                null
            }
            // Only a check of a copy in use answers unchanged.
            Download.NotModified -> null
        }
    }

    /**
     * Opens the download in [partial] and puts it in place as the copy, and as the index when [keep] or when one is
     * loaded, or deletes it when it is unreadable or empty. Either way the answer is the check at [now]; an unusable
     * one keeps [state]'s tag.
     */
    private suspend fun install(outcome: Download.Fresh, state: State, now: Long, keep: Boolean): T? {
        val fresh = try {
            openChecked(partial)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, e) { "$label: downloaded file unusable, deleting it" }
            withContext(ioDispatcher) { fileSystem.delete(partial, mustExist = false) }
            writeState(state.copy(lastCheckMillis = now, unusableMillis = now))
            return null
        }
        lock.withLock {
            withContext(ioDispatcher) {
                // The snapshot is the old copy's until the new one's is written.
                fileSystem.delete(snapshotFile, mustExist = false)
                fileSystem.atomicMove(partial, file)
            }
            if (keep || index != null) index = fresh
        }
        writeState(State(version = outcome.version, lastCheckMillis = now))
        return fresh
    }

    private suspend fun openChecked(path: Path): T = withContext(parseDispatcher) {
        val size = fileSystem.metadataOrNull(path)?.size ?: 0
        check(size <= maxBytes) { "the file has $size bytes, more than $maxBytes" }
        open(path).also { check(!isEmpty(it)) { "the file is empty" } }
    }

    /** The snapshot's index, when there is one that reads; one that doesn't is deleted, and the copy opens again. */
    private suspend fun unsnapshotted(): T? {
        val snapshot = snapshot ?: return null
        return try {
            withContext(parseDispatcher) {
                if (fileSystem.exists(snapshotFile)) fileSystem.source(snapshotFile).buffered().use(snapshot.read).takeUnless(isEmpty) else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, e) { "$label: snapshot unreadable, opening the copy instead" }
            withContext(ioDispatcher) { fileSystem.delete(snapshotFile, mustExist = false) }
            null
        }
    }

    /**
     * Writes [index], the copy's, as the snapshot (under [disk]); when that fails there is none, and the next load
     * opens the copy.
     */
    private suspend fun saveSnapshot(index: T) {
        val snapshot = snapshot ?: return
        try {
            withContext(ioDispatcher) {
                fileSystem.sink(snapshotPart).buffered().use { snapshot.write(index, it) }
                fileSystem.atomicMove(snapshotPart, snapshotFile)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, e) { "$label: writing the snapshot failed" }
            withContext(ioDispatcher) { fileSystem.delete(snapshotPart, mustExist = false) }
        }
    }

    /**
     * The file, into [partial]: [Download.Fresh] once it is complete and checked there. For a copy [inUse], its
     * release's tag is compared with the latest first.
     */
    private suspend fun download(state: State, inUse: Boolean): Download = try {
        withContext(ioDispatcher) {
            fileSystem.createDirectories(directory)
            val tag = when (val latest = latestTag()) {
                is Latest.Tag -> latest.tag
                is Latest.Missing -> return@withContext latest.failure
            }
            if (inUse && tag == state.version) return@withContext Download.NotModified
            downloadRelease(tag)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { fileSystem.delete(partial, mustExist = false) }
        Download.Failed(e::class.simpleName ?: "error", answered = false)
    }

    /** [asset] of the release [tag] into [partial], checked against that release's `SHA256SUMS`. */
    private suspend fun downloadRelease(tag: String): Download {
        val base = "$repository/releases/download/$tag"
        val expected = when (val listed = checksum("$base/SHA256SUMS", asset)) {
            is Listed.Sum -> listed.sha256
            is Listed.Missing -> return listed.failure
        }
        val fetched = fetch("$base/$asset", accept, partial)
        if (fetched !is Download.Fresh) return fetched
        val actual = sha256Of(partial)
        if (actual != expected) {
            fileSystem.delete(partial, mustExist = false)
            Log.w(TAG) { "$label: release $tag's $asset is corrupt (SHA-256 $actual, listed $expected), deleting it" }
            return Download.Unusable
        }
        return Download.Fresh(version = tag)
    }

    /** GETs [url] into [target]: [Download.Fresh] once it is complete there, [Download.Unusable] past [maxBytes]. */
    private suspend fun fetch(url: String, accept: String, target: Path): Download =
        http.prepareGet(url) {
            header(HttpHeaders.Accept, accept)
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds }
        }.execute { response ->
            when {
                response.status != HttpStatusCode.OK -> Download.Failed("HTTP ${response.status.value}", answered = true)
                save(response, target) -> Download.Fresh(version = null)
                else -> {
                    Log.w(TAG) { "$label: $url is larger than $maxBytes bytes, dropping it" }
                    Download.Unusable
                }
            }
        }

    /**
     * The tag of [repository]'s latest release: where its `releases/latest` redirects (`…/releases/tag/<tag>`), asked
     * without following the redirect.
     */
    private suspend fun latestTag(): Latest =
        noRedirects.prepareGet("$repository/releases/latest") {
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds }
        }.execute { response ->
            val location = response.headers[HttpHeaders.Location]
            val tag = location?.substringBefore('?')?.substringAfter("/releases/tag/", "")?.let { escaped ->
                try {
                    escaped.decodeURLPart()
                } catch (e: URLDecodeException) {
                    Log.w(TAG) { "$label: the latest release's tag '$escaped' is malformed (${e.message})" }
                    null
                }
            }
            if (response.status.value in 300..399 && tag != null && RELEASE_TAG.matches(tag)) {
                Latest.Tag(tag)
            } else {
                Latest.Missing(Download.Failed("latest release: HTTP ${response.status.value}, no tag in '$location'", answered = true))
            }
        }

    /** The SHA-256 the checksum list at [url] (`sha256sum`'s lines: `<hex>  <name>`) gives [asset]. */
    private suspend fun checksum(url: String, asset: String): Listed =
        http.prepareGet(url) {
            header(HttpHeaders.Accept, "text/plain")
            timeout { requestTimeoutMillis = REQUEST_TIMEOUT.inWholeMilliseconds }
        }.execute { response ->
            if (response.status != HttpStatusCode.OK) return@execute Listed.Missing(Download.Failed("checksums: HTTP ${response.status.value}", answered = true))
            val text = response.readBody { body -> body.readBuffer(MAX_CHECKSUMS_BYTES).readString() }
            val sum = text.lineSequence().map { it.trim().split(CHECKSUM_SEPARATOR, limit = 2) }
                .firstOrNull { it.size == 2 && it[1].removePrefix("*") == asset }?.get(0)?.lowercase()
            if (sum != null && SHA256_HEX.matches(sum)) {
                Listed.Sum(sum)
            } else {
                Log.w(TAG) { "$label: the checksum list has no SHA-256 for $asset" }
                Listed.Missing(Download.Unusable)
            }
        }

    /** Streams [response] into [target]; false, with [target] deleted, once it runs past [maxBytes]. */
    private suspend fun save(response: HttpResponse, target: Path): Boolean {
        val fits = response.readBody { channel ->
            fileSystem.sink(target).buffered().use { sink ->
                var saved = 0L
                while (!channel.exhausted()) {
                    val chunk = channel.readBuffer(CHUNK_BYTES)
                    saved += chunk.size
                    if (saved > maxBytes) return@use false
                    sink.transferFrom(chunk)
                }
                true
            }
        }
        if (!fits) fileSystem.delete(target, mustExist = false)
        return fits
    }

    private fun sha256Of(path: Path): String = cryptoProvider.get(SHA256).hasher().createHashFunction().use { hash ->
        val chunk = ByteArray(CHUNK_BYTES.toInt())
        fileSystem.source(path).buffered().use { source ->
            while (true) {
                val read = source.readAtMostTo(chunk)
                if (read < 0) break
                hash.update(chunk, 0, read)
            }
        }
        hash.hashToByteArray().toHexString()
    }

    private suspend fun readState(): State = withContext(ioDispatcher) {
        try {
            if (!fileSystem.exists(stateFile)) State() else json.decodeFromString(State.serializer(), fileSystem.source(stateFile).buffered().use { it.readString() })
        } catch (e: IllegalArgumentException) {
            // Only when to check again is lost: the next check asks for the latest tag and downloads it.
            Log.w(TAG, e) { "$label: refresh state unreadable, starting over" }
            State()
        }
    }

    private suspend fun writeState(state: State) = withContext(ioDispatcher) {
        fileSystem.sink(stateFile).buffered().use { it.writeString(json.encodeToString(State.serializer(), state)) }
    }

    /**
     * @property version the copy's release tag.
     * @property unusableMillis when a download last failed its checksum, didn't open, or opened empty.
     */
    @Serializable
    private data class State(val version: String? = null, val lastCheckMillis: Long = 0, val unusableMillis: Long? = null)

    private sealed interface Download {
        class Fresh(val version: String?) : Download

        /** The latest release's tag is the copy's. */
        data object NotModified : Download
        class Failed(val reason: String, val answered: Boolean) : Download

        /** An answer that can't be used (its checksum, none listed): as one that doesn't open. */
        data object Unusable : Download
    }

    private sealed interface Listed {
        class Sum(val sha256: String) : Listed
        class Missing(val failure: Download) : Listed
    }

    private sealed interface Latest {
        class Tag(val tag: String) : Latest
        class Missing(val failure: Download) : Latest
    }

    companion object {
        /** How often a loaded file is re-checked. */
        val REFRESH_INTERVAL: Duration = 1.days

        /** One download or check. */
        val REQUEST_TIMEOUT: Duration = 60.seconds

        private const val CHUNK_BYTES = 64L * 1024
        private const val MAX_CHECKSUMS_BYTES = 64L * 1024
        private const val TAG = "TrackerIds"
        private val CHECKSUM_SEPARATOR = Regex("\\s+")
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
        private val RELEASE_TAG = Regex("[A-Za-z0-9._-]+")
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** A dataset's index as [write] stores it and [read] loads it back, far faster than its file opens. */
internal class PackedForm<T>(val write: (T, Sink) -> Unit, val read: (Source) -> T)
