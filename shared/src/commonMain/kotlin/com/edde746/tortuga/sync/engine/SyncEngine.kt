package com.edde746.tortuga.sync.engine

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.sync.DirtyRecord
import com.edde746.tortuga.data.sync.Hlc
import com.edde746.tortuga.data.sync.PushAck
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.data.sync.RejectedRecord
import com.edde746.tortuga.data.sync.RemoteRecord
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.net.Backoff
import com.edde746.tortuga.playback.session.SyncTrigger
import com.edde746.tortuga.sync.account.AccountError
import com.edde746.tortuga.sync.account.AccountException
import com.edde746.tortuga.sync.account.AccountState
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.account.SyncTarget
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.PushResponse
import com.edde746.tortuga.sync.api.RecordChange
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

/** Timing and batching of [SyncEngine]; the defaults are the app's. */
data class SyncEngineConfig(
    /** Local changes are pushed this long after the last one, so a burst of writes goes in one push. */
    val pushDebounceMillis: Long = 2_000,
    /**
     * While playing, dirty-triggered pushes (progress) happen at most this often;
     * pause, seek, stop and leaving the foreground push at once.
     */
    val playingPushIntervalMillis: Long = 5 * 60_000,
    /** Pull interval while the app is in the foreground. */
    val pullIntervalMillis: Long = 5 * 60_000,
    /**
     * Coming to the foreground pulls when the last pull is at least this old; a quicker return only resumes the timer.
     * Only against a server without the notice stream: with it, each return opens the stream again, which pulls for
     * the notices it missed while closed.
     */
    val foregroundPullAfterMillis: Long = 60_000,
    /** Bound of the first retry's random wait after a failed cycle; the bound doubles per failure up to [maxRetryMillis]. */
    val minRetryMillis: Long = 5_000,
    val maxRetryMillis: Long = 5 * 60_000,
    /**
     * Bound of the first wait before the notice stream opens again (from half of it to all of it); the bound doubles per
     * stream that failed or ended early, up to [maxRetryMillis].
     */
    val noticeRetryMillis: Long = 10_000,
    /** Changes per pull page. */
    val pullLimit: Int = SyncProtocol.DEFAULT_PULL_LIMIT,
    /** Rounds of push → stale → merge → push in one cycle before the rest waits for the next cycle. */
    val maxPushRounds: Int = 5,
)

/**
 * The offline-first sync loop between [LocalData] and the account's server, while [account] is
 * [AccountState.SignedIn]. Local writes land in SQLite and are marked dirty; the engine pushes them, and pulls and
 * merges other devices' records.
 *
 * **A cycle** (one at a time):
 * 1. Pull: pages from the stored cursor while `hasMore`. Each page's records are merged
 *    ([com.edde746.tortuga.data.sync.SyncStore.applyRemote]) and the cursor stored in one step; this device's own
 *    accepted writes coming back are skipped. A record this build can't read (malformed, or content it refuses) is
 *    skipped and logged. A local row that fails as a record merges with it fails the cycle (logged, the status an
 *    error), and nothing of the page is stored, so the next pull gets it again. A record of a newer payload format (an
 *    update of the app reads it) or refused for clock drift (its HLC more than a day ahead of this device's clock) is
 *    held back: the stored cursor stays before the first one, while later pulls of this process go on from where the
 *    last one ended. Held records are pulled again (from the stored cursor; applying a record twice changes nothing)
 *    by the first pull after a restart, an update included, and once this device's clock reaches the time a record
 *    refused for drift is accepted from; while one is refused for drift the status says a clock is wrong
 *    ([SyncStatus.Failure.CLOCK_WRONG]).
 * 2. Push: dirty records oldest first (by HLC, then record id), in batches of at most
 *    [SyncProtocol.MAX_WRITES_PER_PUSH] writes and under [SyncProtocol.MAX_REQUEST_BYTES]. Accepted writes are
 *    acknowledged; a stale write's current record is merged (furthest progress wins, else last writer by HLC), which
 *    leaves the merge dirty on the returned version, and the push repeats (at most [SyncEngineConfig.maxPushRounds]
 *    rounds). A stale write whose current record can't be merged (refused for clock drift, which the status reports
 *    as above, or unreadable) stays dirty and isn't sent again in that cycle. A record larger than
 *    [SyncProtocol.MAX_RECORD_BYTES] (or with an id over [SyncProtocol.MAX_RECORD_ID_LENGTH]) stays local and the
 *    status reports it, naming a profile's addon list when one is among them ([SyncStatus.Failure.ADDONS_TOO_LARGE]).
 *
 * **A restore from a backup on the server**: the server's `latestVersion` below the pull cursor or below a version it
 * gave this device's writes, a stale answer older than the version the write was based on, or a push refused for a
 * base version the server doesn't have ([ApiErrorCode.UNKNOWN_BASE_VERSION], also after its data key was lost): every
 * local record's server version is forgotten and marked dirty, the pull restarts at 0 (merging what the server still
 * has), and what the server lacks is pushed as new. Any other refusal of a push as malformed
 * ([ApiErrorCode.INVALID_REQUEST]: a bug in this client) fails the cycle, logged as an error.
 *
 * **Scheduling.** Pulls happen only in the foreground: when the account becomes ready, on [onForeground] (unless the
 * last pull is under [SyncEngineConfig.foregroundPullAfterMillis] old), every [SyncEngineConfig.pullIntervalMillis]
 * after the last one, on [syncNow], and when the server says the account changed. While the app is in the foreground
 * and the account ready, the engine holds the account's notice stream ([SyncPaths.SYNC_EVENTS]) open and pulls each
 * time it opens (what changed while it was closed) and on each `changed` notice; notices that come before the pull
 * starts or while one runs make one more pull, not one each. The background, a sign-out and an account change close
 * the stream ([listen] has its retries, after any failure). Every other wake only pushes, and only when something is
 * dirty: a push [SyncEngineConfig.pushDebounceMillis] after each new local change (at most once per
 * [SyncEngineConfig.playingPushIntervalMillis] while [setPlaying] is on), on [flushNow] (the playback session's pause,
 * seek, stop and exit) and on [onBackground]. A cycle that pulls also pushes. Failures retry with exponential backoff
 * and full jitter (in the background, only to push); a 429's `Retry-After` is honoured, spread over up to twice as
 * long, before any further cycle. A push the server refuses as too large or over the account's quota (413) is not sent
 * again on a timer: until [syncNow], an [onForeground] that pulls or a restart, cycles only pull and the status keeps
 * the failure. Newer local writes don't retry it, since pushes go oldest first and they queue behind the refused batch.
 * The engine starts in the background, until the platform's first [onForeground].
 *
 * [status] combines the account's state and the engine's. Every write to local data happens under the account's data
 * lock, so a sign-out or account change never interleaves with a sync write.
 */
@OptIn(ExperimentalAtomicApi::class)
class SyncEngine internal constructor(
    private val account: SyncAccount,
    private val data: LocalData,
    private val scope: CoroutineScope,
    private val config: SyncEngineConfig,
    private val clock: () -> Long,
    /** Where push bodies are encoded (CPU work). */
    private val cpu: CoroutineContext,
) : SyncTrigger {
    /** @param scope where the engine's loops run, for the process's lifetime (the app scope). */
    constructor(account: SyncAccount, data: LocalData, scope: CoroutineScope, config: SyncEngineConfig = SyncEngineConfig()) :
        this(account, data, scope, config, account.clock, Dispatchers.Default)

    private val engine = MutableStateFlow<SyncStatus.Kind>(SyncStatus.Kind.Idle)
    private val playing = MutableStateFlow(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val cycleLock = Mutex()
    private val json = SyncProtocol.json

    /** The app is in the foreground ([onForeground]); the engine starts in the background. */
    private val foreground = MutableStateFlow(false)

    /** The server that answered the notice stream with 404 (it predates it): this process only polls it. */
    @Volatile private var noNotices: String? = null

    @Volatile private var lastSyncedAt: Long? = null
    @Volatile private var lastPulledAt: Long? = null

    /** When the last pull started, so a second foreground report while it runs doesn't ask for another. */
    @Volatile private var lastPullStartedAt: Long? = null
    @Volatile private var lastPushAt: Long = 0
    @Volatile private var failures = 0
    @Volatile private var notBefore: Long = 0

    /**
     * A push the server refused as too large or over the account's quota (413): the same batch would get the same
     * answer, so cycles for that account and data generation don't push until [syncNow], an [onForeground] that pulls
     * or a restart clears it.
     */
    @Volatile private var refused: Refusal? = null

    /**
     * The last pull (or a push since) refused records for clock drift, for that account and data generation: the
     * status says a clock is wrong until a pull holds none back for drift.
     */
    @Volatile private var drift: Refusal? = null

    /** Where this process's last pull ended while it held records back ([pull]); null when it held none. */
    private var pulledAhead: PulledAhead? = null

    /** A pull is due at the next foreground cycle: the account became ready, the app came back, a pull failed. */
    private val pullWanted = AtomicBoolean(false)

    private val trackerVersions = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * The account's tracker connections counter as each pull reports it (`PullResponse.trackersVersion`; nothing from a
     * server without it): the trackers list the connections again when it moved.
     */
    val trackersVersions: SharedFlow<Long> = trackerVersions.asSharedFlow()

    /** Record id → version of this device's accepted writes not yet seen in a pull, to skip them there. */
    private val ownAccepted = HashMap<String, Long>()

    /**
     * The highest version the server gave this device's writes, for the account and data generation it was given
     * under: a server whose `latestVersion` is below it lost them, even before the pull cursor moved past them. In
     * memory only; after a restart the cursor alone detects a restore.
     */
    private var highestAccepted = 0L
    private var acceptedAccount: String? = null
    private var acceptedGeneration = -1L
    private var job: Job? = null

    /** The engine's status in the user's language; [SyncStatus.Kind.SignedOut] follows the account. */
    val status: StateFlow<SyncStatus> = combine(account.state, engine, ::statusOf)
        .stateIn(scope, SharingStarted.Eagerly, statusOf(account.state.value, engine.value))

    /** Starts the loops: the worker, the dirty-record watcher, the account watcher and the notice stream. Idempotent. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { worker() }
            launch { watchDirty() }
            launch { watchNotices() }
            launch {
                account.state.map { it is AccountState.SignedIn }.distinctUntilChanged().collect { ready ->
                    if (ready) {
                        pullWanted.store(true)
                        wake.trySend(Unit)
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Pushes as soon as possible, without the debounce or the playing interval (no pull): the playback session calls it
     * on pause, seek, stop and exit. Returns at once; safe from any thread.
     */
    override fun flushNow() {
        wake.trySend(Unit)
    }

    /** While [playing], progress writes are pushed at most once per [SyncEngineConfig.playingPushIntervalMillis]. */
    override fun setPlaying(playing: Boolean) {
        this.playing.value = playing
    }

    /**
     * The app came to the foreground: pull now unless the last pull started under
     * [SyncEngineConfig.foregroundPullAfterMillis] ago (a failed one asks again by itself), then periodically. A push
     * the server [refused] goes again with that pull. A server with the notice stream gets a pull either way when the
     * stream opens again ([listen]).
     */
    fun onForeground() {
        foreground.value = true
        val startedAt = lastPullStartedAt
        if (startedAt == null || clock() - startedAt >= config.foregroundPullAfterMillis) {
            refused = null
            pullWanted.store(true)
        }
        wake.trySend(Unit)
    }

    /** The app went to the background: push what is pending; no pulls and no notice stream until [onForeground]. */
    fun onBackground() {
        foreground.value = false
        wake.trySend(Unit)
    }

    /**
     * Runs one cycle, pull and push (a push the server [refused] included), now (after any running one) and returns the
     * resulting status.
     */
    suspend fun syncNow(): SyncStatus {
        refused = null
        runCycle(pull = true)
        return statusOf(account.state.value, engine.value)
    }

    /**
     * Pushes what is dirty now, without a pull (a push the server [refused] included), after any running cycle, and
     * returns the resulting status: what a sign-out runs first, as it deletes what stays unsynced
     * ([SyncAccount.signOut]).
     */
    internal suspend fun pushNow(): SyncStatus {
        refused = null
        runCycle(pull = false)
        return statusOf(account.state.value, engine.value)
    }

    private fun statusOf(state: AccountState, engine: SyncStatus.Kind): SyncStatus = when (state) {
        is AccountState.SignedOut -> SyncStatus.of(SyncStatus.Kind.SignedOut, null)
        is AccountState.SignedIn -> SyncStatus.of(engine, lastSyncedAt)
    }

    // ---- Scheduling ---------------------------------------------------------------------------------------------

    private suspend fun worker() {
        var retryAt = Long.MAX_VALUE
        var ready = true
        while (true) {
            val deadline = when {
                !ready -> Long.MAX_VALUE
                retryAt != Long.MAX_VALUE -> retryAt
                foreground.value -> nextPullAt()
                else -> Long.MAX_VALUE
            }
            val wait = deadline - clock()
            if (wait > 0) withTimeoutOrNull(wait) { wake.receive() }
            val blocked = notBefore - clock()
            if (blocked > 0) delay(blocked)
            // In the background a wish to pull waits for the foreground; only pushes go out.
            val pull = foreground.value && (pullWanted.exchange(false) || clock() >= nextPullAt())
            if (pull) lastPullStartedAt = clock()
            val result = runCycle(pull)
            ready = result != null
            retryAt = if (result == false) clock() + backoff() else Long.MAX_VALUE
            if (pull && result != true) pullWanted.store(true)
        }
    }

    /** When the periodic pull is due: an interval after the last one, or now if there was none. */
    private fun nextPullAt(): Long = lastPulledAt?.let { it + config.pullIntervalMillis } ?: clock()

    /**
     * A push [SyncEngineConfig.pushDebounceMillis] after each new local change: each has a newer HLC than any before. A
     * read of the changes that fails (the database reads again what finds it busy) is logged and ends the watching; the
     * worker's cycles, which fail on the same read and are retried, push the changes from then on.
     */
    private suspend fun watchDirty() {
        try {
            combine(data.sync.observeNewestDirty(), playing) { newest, playing -> newest to playing }.collectLatest { (newest, playing) ->
                if (newest == null) return@collectLatest
                val debounce = if (playing) {
                    maxOf(config.pushDebounceMillis, lastPushAt + config.playingPushIntervalMillis - clock())
                } else {
                    config.pushDebounceMillis
                }
                delay(debounce)
                wake.trySend(Unit)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, e) { "Watching the local changes failed (${e::class.simpleName}); the cycles push them" }
        }
    }

    private fun backoff(): Long = Backoff.fullJitter(failures, config.minRetryMillis, config.maxRetryMillis)

    // ---- Notices ------------------------------------------------------------------------------------------------

    /** Holds the notice stream open while the app is in the foreground and the account is ready, for that account. */
    private suspend fun watchNotices() {
        combine(foreground, account.state) { front, state -> (state as? AccountState.SignedIn)?.takeIf { front } }
            .distinctUntilChanged()
            .collectLatest { signedIn -> if (signedIn != null && signedIn.serverUrl.value != noNotices) listen(signedIn.serverUrl.value) }
    }

    /**
     * The notice stream's loop. Each open and each notice ask for a pull ([noticed]). A stream that ends or fails opens
     * again after [Backoff.equalJitter] of [SyncEngineConfig.noticeRetryMillis], whose bound doubles per stream that
     * failed or lasted under [STABLE_NOTICES_MILLIS], up to [SyncEngineConfig.maxRetryMillis]; a 429's or 503's
     * `Retry-After` takes its place. A 404 (a server older than the stream) stops it for [server] until the process
     * restarts. A session the server ended signs the account out, which ends it too.
     */
    private suspend fun listen(server: String) {
        var failures = 0
        while (true) {
            var openedAt: Long? = null
            var asked: Long? = null
            try {
                account.sessions.authorized { url, token ->
                    account.api.events(url, token, onOpen = {
                        openedAt = clock()
                        Log.i(TAG) { "The notice stream is open" }
                        noticed()
                    }, onChanged = ::noticed)
                }
                Log.i(TAG) { "The server ended the notice stream" }
            } catch (e: CancellationException) {
                if (openedAt != null) Log.i(TAG) { "The notice stream is closed" }
                throw e
            } catch (e: AccountException) {
                when (val error = e.error) {
                    AccountError.NotFound -> {
                        Log.i(TAG) { "The server has no notice stream; pulls stay periodic" }
                        noNotices = server
                        return
                    }
                    AccountError.SessionEnded, AccountError.NotSignedIn -> return
                    is AccountError.RateLimited -> asked = error.retryAfterMillis
                    else -> Log.i(TAG) { "The notice stream failed: $error" }
                }
            } catch (e: Exception) {
                // Not an answer of the server's: a fault on this device. The stream opens again after the backoff.
                Log.e(TAG, e) { "The notice stream failed" }
            }
            val stable = openedAt?.let { clock() - it >= STABLE_NOTICES_MILLIS } == true
            failures = if (stable) 1 else failures + 1
            delay(asked?.let { Backoff.afterRetryAfter(it) } ?: Backoff.equalJitter(failures, config.noticeRetryMillis, config.maxRetryMillis))
        }
    }

    /** The account changed elsewhere: a pull, one for however many notices come before it starts. */
    private fun noticed() {
        pullWanted.store(true)
        wake.trySend(Unit)
    }

    // ---- One cycle ----------------------------------------------------------------------------------------------

    /**
     * One cycle: a pull first when [pull], then a push of what is dirty unless the server [refused] it. True on success
     * (also when there was nothing to push and when the server refused the push: neither retries on a timer), false on
     * failure (retry with backoff; a database read that fails is one), null when the account isn't ready.
     */
    private suspend fun runCycle(pull: Boolean): Boolean? = cycleLock.withLock {
        val target = account.syncTarget() ?: return@withLock null
        val refusal = refused?.takeIf { it.accountId == target.accountId && it.generation == target.generation }
        try {
            if (!pull && (refusal != null || data.sync.dirtyRecords(null, 1).isEmpty())) return@withLock true
            engine.value = SyncStatus.Kind.Syncing
            sync(target, pull, pushing = refusal == null)
            failures = 0
            lastSyncedAt = clock()
            val clockWrong = drift?.takeIf { it.accountId == target.accountId && it.generation == target.generation }
            engine.value = (refusal ?: clockWrong)?.let { SyncStatus.Kind.Error(it.failure) } ?: SyncStatus.Kind.Idle
            true
        } catch (e: CancellationException) {
            engine.value = SyncStatus.Kind.Idle
            throw e
        } catch (e: TargetChanged) {
            // A sign-out or account change during the cycle: the account's state says what comes next.
            engine.value = SyncStatus.Kind.Idle
            null
        } catch (e: RecordsTooLarge) {
            failures = 0
            lastSyncedAt = clock()
            engine.value = SyncStatus.Kind.Error(e.failure)
            true
        } catch (e: AccountException) {
            failed(e, target)
        } catch (e: Exception) {
            Log.e(TAG, e) { "Sync cycle failed" }
            failures++
            engine.value = SyncStatus.Kind.Error(SyncStatus.Failure.UNKNOWN)
            false
        }
    }

    private suspend fun failed(e: AccountException, target: SyncTarget): Boolean? {
        val error = e.error
        if (error is AccountError.InvalidRequest) {
            Log.e(TAG, e) { "The server refused a request as malformed: ${error.detail}" }
        } else {
            Log.i(TAG) { "Sync cycle failed: $error" }
        }
        val refusal = when (error) {
            AccountError.QuotaExceeded -> SyncStatus.Failure.QUOTA_EXCEEDED
            AccountError.PayloadTooLarge -> SyncStatus.Failure.TOO_LARGE
            else -> null
        }
        if (refusal != null) {
            // Only a push has a body to refuse.
            refused = Refusal(target.accountId, target.generation, refusal)
            engine.value = SyncStatus.Kind.Error(refusal)
            return true
        }
        failures++
        engine.value = when (error) {
            AccountError.Network -> SyncStatus.Kind.Offline
            is AccountError.RateLimited -> {
                notBefore = clock() + Backoff.afterRetryAfter(error.retryAfterMillis)
                SyncStatus.Kind.Error(SyncStatus.Failure.RATE_LIMITED)
            }
            is AccountError.Server -> SyncStatus.Kind.Error(SyncStatus.Failure.SERVER)
            // The account already moved to signed out.
            AccountError.SessionEnded, AccountError.NotSignedIn -> return null.also {
                engine.value = SyncStatus.Kind.Idle
            }
            else -> SyncStatus.Kind.Error(SyncStatus.Failure.UNKNOWN)
        }
        return false
    }

    private suspend fun sync(target: SyncTarget, pullFirst: Boolean, pushing: Boolean) {
        var rebased = false
        var pulling = pullFirst
        while (true) {
            try {
                if (pulling) pull(target)
                if (pushing) push(target)
                return
            } catch (e: RestoreDetected) {
                if (rebased) throw AccountException(AccountError.Unexpected(0, "restore_loop"), e)
                rebased = true
                pulling = true
                Log.w(TAG) { "The server lost writes this device saw (restored from a backup?): re-syncing from version 0" }
                guarded(target) {
                    data.sync.forgetServerVersions()
                    data.syncState.setPullCursor(target.accountId, 0)
                }
                pulledAhead = null
                ownAccepted.clear()
                highestAccepted = 0
            }
        }
    }

    /**
     * Pulls every page from the stored cursor, or from where this process's last pull ended while records were held
     * back and none of them may apply yet. The cursor stored with each page stays before the first record held back
     * (a newer format, or refused for clock drift), so the first pull after a restart, and the first once this
     * device's clock reaches a drifted record's [RejectedRecord.acceptedFromMillis], gets it again; [drift] says
     * whether one is refused for drift.
     */
    private suspend fun pull(target: SyncTarget) {
        val ahead = pulledAhead?.takeIf { it.accountId == target.accountId && it.generation == target.generation && clock() < it.retryAt }
        var cursor = ahead?.cursor ?: data.syncState.pullCursor(target.accountId) ?: 0L
        // The version before the first record held back: the stored cursor goes no further.
        var held: Long? = ahead?.held
        var retryAt = ahead?.retryAt ?: Long.MAX_VALUE
        var clockWrong = ahead?.clockWrong ?: false
        while (true) {
            val since = cursor
            val page = account.sessions.authorized { url, token -> account.api.pull(url, token, since, config.pullLimit) }
            if (acceptedAccount != target.accountId || acceptedGeneration != target.generation) {
                acceptedAccount = target.accountId
                acceptedGeneration = target.generation
                highestAccepted = 0
                ownAccepted.clear()
            }
            if (maxOf(since, highestAccepted) > page.latestVersion) throw RestoreDetected()
            val records = page.changes.mapNotNull { remote(it) }
            if (records.isNotEmpty() || page.cursor != since) {
                guarded(target) {
                    if (records.isNotEmpty()) {
                        val rejected = data.sync.applyRemote(records)
                        logRejected(rejected)
                        val versions = records.associate { it.recordId to it.serverVersion }
                        for (record in rejected) {
                            when (record.reason) {
                                RejectedRecord.Reason.CLOCK_DRIFT -> {
                                    clockWrong = true
                                    retryAt = minOf(retryAt, record.acceptedFromMillis ?: Long.MAX_VALUE)
                                }
                                RejectedRecord.Reason.NEWER_FORMAT -> {}
                                RejectedRecord.Reason.UNREADABLE, RejectedRecord.Reason.ID_MISMATCH -> continue
                            }
                            val before = versions.getValue(record.recordId) - 1
                            held = minOf(held ?: before, before)
                        }
                    }
                    data.syncState.setPullCursor(target.accountId, held ?: page.cursor)
                }
            }
            cursor = page.cursor
            if (!page.hasMore) {
                lastPulledAt = clock()
                val heldFrom = held
                pulledAhead = heldFrom?.let { PulledAhead(target.accountId, target.generation, cursor, it, retryAt, clockWrong) }
                drift = if (clockWrong) Refusal(target.accountId, target.generation, SyncStatus.Failure.CLOCK_WRONG) else null
                if (heldFrom != null) Log.w(TAG) { "Records are held back (clock drift or a newer format); pulled again from version ${heldFrom + 1}" }
                page.trackersVersion?.let { trackerVersions.tryEmit(it) }
                return
            }
        }
    }

    private suspend fun push(target: SyncTarget) {
        // The records that stay local for their size (or id), by id.
        val tooLarge = HashMap<String, RecordKey>()
        // Stale writes whose current record couldn't be merged: sent again, they would get the same answer.
        val unmerged = HashSet<String>()
        repeat(config.maxPushRounds) {
            var after: DirtyRecord? = null
            var merged = false
            while (true) {
                val dirty = data.sync.dirtyRecords(after, SyncProtocol.MAX_WRITES_PER_PUSH)
                if (dirty.isEmpty()) break
                after = dirty.last()
                val batches = withContext(cpu) {
                    batches(
                        dirty.filter { it.recordId !in tooLarge && it.recordId !in unmerged }.mapNotNull { record ->
                            write(record).also { if (it == null) tooLarge[record.recordId] = record.key }
                        },
                    )
                }
                for (batch in batches) {
                    if (pushBatch(target, batch, unmerged)) merged = true
                }
            }
            if (!merged) {
                if (tooLarge.isNotEmpty()) throw RecordsTooLarge(tooLarge.values)
                return
            }
        }
        if (tooLarge.isNotEmpty()) throw RecordsTooLarge(tooLarge.values)
    }

    /**
     * Pushes one batch; true when stale answers were merged (so another round is due). The ids of stale writes whose
     * current record couldn't be merged go to [unmerged].
     */
    private suspend fun pushBatch(target: SyncTarget, batch: Batch, unmerged: MutableSet<String>): Boolean {
        guarded(target) {}
        val response: PushResponse = try {
            account.sessions.authorized { url, token -> account.api.push(url, token, batch.body) }
        } catch (e: AccountException) {
            if (e.error == AccountError.UnknownBaseVersion) throw RestoreDetected()
            throw e
        }
        lastPushAt = clock()
        if (response.stale.isEmpty()) {
            Log.d(TAG) { "Pushed ${response.accepted.size} writes" }
        } else {
            Log.i(TAG) { "Pushed ${response.accepted.size} writes; ${response.stale.size} stale, merging" }
        }
        val byId = batch.writes.associateBy { it.recordId }
        val acks = response.accepted.mapNotNull { accepted ->
            val pending = byId[accepted.recordId] ?: return@mapNotNull null
            PushAck(accepted.recordId, pending.hlc, accepted.version)
        }
        for (change in response.stale) {
            val base = byId[change.recordId]?.baseVersion ?: continue
            if (change.version < base) throw RestoreDetected()
        }
        val current = response.stale.map { RemoteRecord(it.recordId, it.version, it.payload) }
        var rejected = emptyList<RejectedRecord>()
        guarded(target) {
            if (acks.isNotEmpty()) data.sync.markPushed(acks)
            if (current.isNotEmpty()) rejected = data.sync.applyRemote(current)
        }
        logRejected(rejected)
        rejected.mapTo(unmerged) { it.recordId }
        if (drifted(rejected).isNotEmpty()) drift = Refusal(target.accountId, target.generation, SyncStatus.Failure.CLOCK_WRONG)
        for (ack in acks) {
            ownAccepted[ack.recordId] = ack.serverVersion
            highestAccepted = maxOf(highestAccepted, ack.serverVersion)
        }
        if (ownAccepted.size > MAX_OWN_ACCEPTED) ownAccepted.clear()
        return current.size > rejected.size
    }

    /**
     * [record] as one write of a push body, or null if the server would refuse it: a payload over
     * [SyncProtocol.MAX_RECORD_BYTES], or an id that isn't a valid record id (an item id over its length limit).
     */
    private fun write(record: DirtyRecord): PendingWrite? {
        if (!SyncProtocol.isValidRecordId(record.recordId)) {
            Log.w(TAG) { "A record id of ${record.recordId.length} characters isn't one the server takes; the record stays local" }
            return null
        }
        val payloadBytes = utf8Length(record.payload)
        if (payloadBytes > SyncProtocol.MAX_RECORD_BYTES) {
            Log.w(TAG) { "A record is $payloadBytes bytes, over the server's ${SyncProtocol.MAX_RECORD_BYTES}; it stays local" }
            return null
        }
        val baseVersion = record.baseVersion ?: 0
        // The encoding of a RecordWrite, around the payload JSON the store already made.
        val head = "{\"recordId\":${JsonPrimitive(record.recordId)},\"baseVersion\":$baseVersion,\"deleted\":${record.deleted},\"payload\":"
        return PendingWrite(record.recordId, baseVersion, record.hlc, head + record.payload + "}", utf8Length(head) + payloadBytes + 1)
    }

    /** Splits [writes] into requests of at most 500 writes and under the request size limit. */
    private fun batches(writes: List<PendingWrite>): List<Batch> {
        val prefix = "{\"writes\":["
        val suffix = "]}"
        val batches = ArrayList<Batch>()
        var current = ArrayList<PendingWrite>()
        var size = prefix.length + suffix.length
        fun flush() {
            if (current.isEmpty()) return
            batches += Batch(current, current.joinToString(",", prefix, suffix) { it.text })
            current = ArrayList()
            size = prefix.length + suffix.length
        }
        for (write in writes) {
            if (current.size == SyncProtocol.MAX_WRITES_PER_PUSH || size + write.bytes + 1 > MAX_BODY_BYTES) flush()
            current += write
            size += write.bytes + 1
        }
        flush()
        return batches
    }

    private fun remote(change: RecordChange): RemoteRecord? {
        if (ownAccepted[change.recordId] == change.version) {
            ownAccepted.remove(change.recordId)
            return null
        }
        return RemoteRecord(change.recordId, change.version, change.payload)
    }

    private fun logRejected(rejected: List<RejectedRecord>) {
        for (record in rejected) Log.w(TAG) { "A pulled record wasn't applied (${record.reason}): ${record.message}" }
    }

    /** The ids of [rejected] records refused for clock drift. */
    private fun drifted(rejected: List<RejectedRecord>): Set<String> =
        rejected.filter { it.reason == RejectedRecord.Reason.CLOCK_DRIFT }.mapTo(HashSet()) { it.recordId }

    private suspend fun guarded(target: SyncTarget, block: suspend () -> Unit) {
        account.withSyncData(target, block) ?: throw TargetChanged()
    }

    /** [failure] stays the status for [accountId]'s data [generation] beyond one cycle. */
    private class Refusal(val accountId: String, val generation: Long, val failure: SyncStatus.Failure)

    /**
     * A pull that held records back ended at [cursor] for [accountId]'s data [generation]: the stored cursor is [held],
     * and the next pulls go on from [cursor] until [retryAt] (a drifted record may apply then; never for a newer
     * format only, which waits for a restart).
     */
    private class PulledAhead(
        val accountId: String,
        val generation: Long,
        val cursor: Long,
        val held: Long,
        val retryAt: Long,
        val clockWrong: Boolean,
    )

    /** A write of a push: [text] is its JSON, [bytes] that text's UTF-8 size. */
    private class PendingWrite(val recordId: String, val baseVersion: Long, val hlc: Hlc, val text: String, val bytes: Int)

    private class Batch(val writes: List<PendingWrite>, val body: String)

    /** The account signed out or changed during the cycle. */
    private class TargetChanged : Exception()

    /** The server has fewer writes than this device saw. */
    private class RestoreDetected : Exception()

    /**
     * Everything else was pushed; the records of [keys] exceed the server's size limit. An addon list among them is
     * named ([SyncStatus.Failure.ADDONS_TOO_LARGE]): it is the one record a user makes large, by adding addons.
     */
    private class RecordsTooLarge(keys: Collection<RecordKey>) : Exception() {
        val failure = if (keys.any { it is RecordKey.AddonList }) SyncStatus.Failure.ADDONS_TOO_LARGE else SyncStatus.Failure.TOO_LARGE
    }

    private companion object {
        const val TAG = "SyncEngine"

        /** Below the server's 8 MiB body limit, with room for headers the limit may count. */
        const val MAX_BODY_BYTES = SyncProtocol.MAX_REQUEST_BYTES - 16 * 1024
        const val MAX_OWN_ACCEPTED = 10_000

        /** A notice stream that lasted through two keepalives worked; one that ended sooner counts as a failure. */
        const val STABLE_NOTICES_MILLIS = 2 * SyncProtocol.EVENTS_KEEPALIVE_MILLIS
    }
}

/** UTF-8 size of [text] without encoding it: how a record's payload is measured against [SyncProtocol.MAX_RECORD_BYTES]. */
internal fun utf8Length(text: String): Int {
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> 4.also { i++ }
            else -> 3
        }
        i++
    }
    return bytes
}
