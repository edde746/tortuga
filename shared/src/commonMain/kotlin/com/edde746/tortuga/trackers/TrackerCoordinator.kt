package com.edde746.tortuga.trackers

import com.edde746.tortuga.data.settings.SettingsRepository
import com.edde746.tortuga.data.tracker.QueuedRating
import com.edde746.tortuga.data.tracker.QueuedTrackerContent
import com.edde746.tortuga.data.tracker.QueuedTrackerWrite
import com.edde746.tortuga.data.tracker.QueuedWatchlist
import com.edde746.tortuga.data.tracker.TrackerWriteQueue
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.StremioId
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.model.videoIsTitle
import com.edde746.tortuga.playback.session.PlaybackObserver
import com.edde746.tortuga.playback.session.PlaybackPosition
import com.edde746.tortuga.playback.session.PlaybackSession
import com.edde746.tortuga.playback.session.countsAsWatched
import com.edde746.tortuga.trackers.catalog.WatchlistMembership
import com.edde746.tortuga.trackers.catalog.WatchlistTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * The only place tracker writes leave the app, for one profile. Every connected, enabled tracker whose content filter
 * takes the item gets each write, in parallel across services and in order within one ([ServiceChannel]).
 *
 * - **Playback** ([follow] a [PlaybackSession]): start, pause, resume, a seek (a position jump of more than 5 s) and
 *   stop reach the [RealtimeScrobble] trackers (Trakt, Simkl, MDBList) as live reports, within each service's
 *   [ScrobblePolicy] (Trakt: a re-start at most every 30 s, a seek checkpoint every 5 s) and a 1 s debounce of
 *   duplicate states. Once the video counts as watched (`PlaybackRules.countsAsWatched`: the profile's
 *   `WatchedThresholdPercent` by position, or the credits' start) the other trackers mark it watched, once per
 *   playback, or at stop if it got there between two reports.
 * - **One watch, one write per service:** a realtime tracker whose playback report started owns the watch; its stop
 *   and [RealtimeScrobble.reconcileWatchedAfterStop] record it, and the threshold mark leaves it out. A pause or stop
 *   of a session whose start never landed isn't sent (it would only invent one); if its start or its stop didn't
 *   land, the watch falls back to a history write.
 * - **Manual marks** from Detail and Library ([marks]) go out one at a time, in the order they were made, to
 *   [Tracker.markWatched]/[Tracker.markUnwatched]: a movie or episode to every tracker (an unwatched episode only to
 *   [EpisodeHistory] ones, since a series counter can't lose one episode); a season or show to [EpisodeHistory]
 *   trackers as [EpisodeHistory.writeHistory] batches of [HISTORY_BATCH] episodes, one request each, and one entry
 *   update per series to [SeriesProgress] ones. Unmarked, that entry goes back to before the first of its episodes
 *   the mark covers (a whole show: to its start), and a season whose episodes can't all be numbered on the entry
 *   leaves it as it is: an entry can span several seasons, and a counter set blind would take theirs too.
 * - **Ratings** from Detail ([userScores], [rate]): the user gives one score, 1–10, and every [Rating] tracker that
 *   takes the title gets it (a movie or a show; an anime list only for a title with anime ids), in its service's
 *   channel like a mark. Reads ask the trackers in parallel, each answering for itself; a rating still queued for one
 *   shows in place of its answer.
 * - **Watchlists** from Detail and the cards' menus ([watchlist], [setWatchlisted]): the user puts a title on, or
 *   takes it off, one tracker's list at a time (Trakt's and MDBList's watchlist, Simkl's plan-to-watch, MyAnimeList's
 *   and AniList's planning, the anime lists only for anime), in its service's channel like a mark. Whether a title is
 *   on a list is never asked of the service: it is what the list's pages the Board and Discover read say, and the
 *   user's writes ([com.edde746.tortuga.trackers.catalog.WatchlistMembership]); a write on its way or queued shows
 *   what it writes, and one that fails for good goes back.
 * - **Retries:** a failed watched/unwatched write, rating or watchlist write goes to the persisted [TrackerWriteQueue],
 *   coalesced per remote row, and is replayed by [replayQueuedWrites] (on start, on connect, on app resume and network
 *   restore), a history tracker's rows batched as a season mark's are. A replay failing transiently (rate limit, 5xx,
 *   network, no access token from the account server or the secret store) keeps its attempts and defers that service
 *   for the rest of the pass; one the service can't name the item for is dropped; any other failure counts one of its
 *   5 attempts, and after the fifth the write is kept as failed ([TrackerWriteQueue.recordFailure]), which Trackers
 *   settings shows. Live reports are never queued, only logged.
 * - **Rate limits:** a rate-limited service gets no request until the wait it asked for ends
 *   ([TrackerException.RateLimited.retryAfterSeconds]), or for [RATE_LIMIT_HOLD] when it didn't say: its live
 *   reports are skipped, its watched writes queued and its replays wait.
 * - **Isolation:** the session only posts events here; tracker work runs in this coordinator's own scope and every
 *   failure is logged, so no tracker error reaches playback.
 *
 * Main-thread confined, like the stores. Built by [TrackerHub.coordinator].
 */
class TrackerCoordinator internal constructor(
    val profileId: String,
    private val accounts: TrackerAccounts,
    private val resolver: TrackerIdResolver,
    private val queue: TrackerWriteQueue,
    private val settings: SettingsRepository,
    private val loadMeta: suspend (type: String, id: String) -> MetaItem?,
    private val clock: Clock,
    private val timeSource: TimeSource.WithComparableMarks,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val pendingMarks = Channel<WatchedMark>(Channel.UNLIMITED)
    private val loaded = CompletableDeferred<Unit>()
    private val boundFlow = MutableStateFlow<List<BoundTracker>>(emptyList())
    private val bound: List<BoundTracker> get() = boundFlow.value
    private val channels = mutableMapOf<TrackerService, ServiceChannel>()
    private var playback: Playback? = null

    /** The observer whose playback started last ([observer]); every other observer's events are dropped. */
    private var source: PlaybackObserver? = null
    private var replay: Job? = null
    private var replayAgain = false

    /** Per service, when the wait its last rate limit asked for ends. */
    private val holds = mutableMapOf<TrackerService, ComparableTimeMark>()

    /** Held while a rating finds its trackers, so a later rating of the title reaches each channel after it. */
    private val ratingOrder = Mutex()

    /** Held while a watchlist write finds its tracker, so a later write of the title reaches the channel after it. */
    private val watchlistOrder = Mutex()

    /** Where each watchlist write that isn't through stands, by its queue key ([watchlistKey]); none: [WatchlistState.IDLE]. */
    private val watchlistSends = MutableStateFlow<Map<String, WatchlistState>>(emptyMap())

    /**
     * A new observer for one producer of playbacks (a [PlaybackSession], a player app's hand-off): the coordinator
     * follows one playback at a time, the one whose observer started last, and drops the events of every other
     * observer. A start closes the playback still open with a stop at its last position. Observers only post events,
     * so they can't fail or block playback.
     */
    internal fun observer(): PlaybackObserver = object : PlaybackObserver {
        override fun onVideoStarted(meta: MetaItem, videoId: String, at: PlaybackPosition, playing: Boolean) {
            events.trySend(Event.Started(this, meta, videoId, at, playing))
        }

        override fun onPlayingChanged(playing: Boolean, at: PlaybackPosition) {
            events.trySend(Event.PlayingChanged(this, playing, at))
        }

        override fun onPosition(at: PlaybackPosition) {
            events.trySend(Event.Position(this, at))
        }

        override fun onVideoStopped(at: PlaybackPosition) {
            events.trySend(Event.Stopped(this, at))
        }
    }

    /** Where Detail and Library send the user's marks. */
    internal val marks: WatchedMarkListener = WatchedMarkListener { mark -> pendingMarks.trySend(mark) }

    init {
        scope.launch {
            accounts.bound(profileId).collect {
                boundFlow.value = it
                loaded.complete(Unit)
            }
        }
        scope.launch {
            loaded.await()
            for (event in events) {
                try {
                    handle(event)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, e) { "playback event failed" }
                }
            }
        }
        scope.launch {
            loaded.await()
            // One at a time: a mark may wait for its meta or the mapping datasets before it reaches the services'
            // channels, and a later one (an undo) must not get there first.
            for (mark in pendingMarks) {
                try {
                    applyMark(mark)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, e) { "manual mark failed" }
                }
            }
        }
        // The profile is bound: replay what an earlier run left queued.
        replayQueuedWrites()
    }

    /** Scrobbles the videos [session] plays from now on. */
    fun follow(session: PlaybackSession) {
        session.observe(observer())
    }

    /**
     * Replays the profile's queued writes. Safe to call any time: a call while a replay runs makes it run once more;
     * rows of a service that isn't connected or enabled wait untouched.
     */
    fun replayQueuedWrites() {
        if (replay?.isActive == true) {
            replayAgain = true
            return
        }
        replay = scope.launch {
            loaded.await()
            // Spans the whole burst: a service that just asked for quiet isn't asked again by the re-run.
            val deferred = mutableSetOf<TrackerService>()
            do {
                replayAgain = false
                try {
                    replayOnce(deferred)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, e) { "write queue replay failed" }
                }
            } while (replayAgain)
        }
    }

    // Playback

    private sealed interface Event {
        val source: PlaybackObserver

        class Started(override val source: PlaybackObserver, val meta: MetaItem, val videoId: String, val at: PlaybackPosition, val playing: Boolean) : Event
        class PlayingChanged(override val source: PlaybackObserver, val playing: Boolean, val at: PlaybackPosition) : Event
        class Position(override val source: PlaybackObserver, val at: PlaybackPosition) : Event
        class Stopped(override val source: PlaybackObserver, val at: PlaybackPosition) : Event
    }

    private suspend fun handle(event: Event) {
        if (event is Event.Started) {
            source = event.source
            playback?.let(::close)
            start(event.meta, event.videoId, event.at, event.playing)
            return
        }
        if (event.source !== source) {
            Log.d(TAG) { "dropped ${event::class.simpleName} of a playback a newer one replaced" }
            return
        }
        when (event) {
            is Event.Started -> Unit
            is Event.PlayingChanged -> playback?.let { p ->
                p.move(event.at)
                report(p, if (event.playing) ScrobbleState.START else ScrobbleState.PAUSE)
            }
            is Event.Position -> playback?.let { p -> moved(p, event.at) }
            is Event.Stopped -> playback?.let { p ->
                p.move(event.at)
                close(p)
            }
        }
    }

    /** Opens the playback of [videoId]; its start is reported once it plays ([playing] now, else its first resume). */
    private suspend fun start(meta: MetaItem, videoId: String, at: PlaybackPosition, playing: Boolean) {
        if (meta.preview.isLive || bound.none { it.account.enabled }) return
        val kind = when {
            videoIsTitle(meta.id, videoId) -> TrackerMediaKind.MOVIE
            meta.video(videoId)?.seriesInfo != null -> TrackerMediaKind.EPISODE
            else -> return
        }
        val context = context(TrackerItem(meta.id, meta.type, videoId, meta), kind) ?: return
        val threshold = settings.settings(profileId).watchedThresholdPercent
        // The playback belongs to the accounts connected when it began; a reconnect mid-playback doesn't inherit it.
        val targets = bound.mapNotNull { b ->
            val realtime = b.tracker as? RealtimeScrobble ?: return@mapNotNull null
            if (b.takes(context.contentType)) PlaybackTarget(b.service, realtime) else null
        }
        val p = Playback(context, threshold, targets)
        p.move(at)
        playback = p
        if (playing) report(p, ScrobbleState.START)
    }

    private fun moved(p: Playback, at: PlaybackPosition) {
        val jumped = abs(at.positionMillis - p.positionMillis) > SEEK_JUMP_MILLIS
        p.move(at)
        // A seek is a checkpoint, not a transition; services without one opt out through their policy.
        if (jumped) report(p, ScrobbleState.SEEK)
        if (!p.crossed && p.reachedThreshold) {
            p.crossed = true
            dispatch(nonOwners(p), p.context, watched = true)
        }
    }

    /** Ends [p], the open playback, at its last position: its stop, and the watch it recorded. */
    private fun close(p: Playback) {
        playback = null
        val watched = p.crossed || p.reachedThreshold
        // Decided once, so the two ways of recording a watch stay exclusive.
        val owners = p.targets.filter { it.sessionStarted }
        if (!p.crossed && p.reachedThreshold) dispatch(nonOwners(p), p.context, watched = true)
        // Not floored: a session that started is closed even at 0 %, or the service shows it playing for its runtime.
        report(p, ScrobbleState.STOP)
        if (!watched) return
        val percent = p.percent
        for (owner in owners) {
            // Behind the stop in the service's channel, so it knows whether the stop landed.
            channel(owner.service).submit(null, owner.tracker.writeSpacing) {
                val current = current(owner.tracker) ?: return@submit false
                if (owner.stopConfirmed) {
                    reconcile(current, owner.tracker, p.context, percent)
                } else {
                    // The stop never landed, or its session never started, so the service's own completion rule never
                    // ran: a history write instead.
                    applyWrite(owner.tracker, listOf(p.context), watched = true)
                }
            }
        }
    }

    /** Trackers that don't own the watch of [p]: everyone whose playback report didn't start. */
    private fun nonOwners(p: Playback): List<BoundTracker> =
        bound.filter { b -> p.targets.none { it.sessionStarted && it.tracker === b.tracker } }

    /** Queues [state] for the playback's targets that take it now. */
    private fun report(p: Playback, state: ScrobbleState) {
        val now = timeSource.markNow()
        val percent = p.percent
        for (target in p.targets) {
            // Still the same sign-in, and still enabled for this content: the user can change either mid-playback.
            val current = current(target.tracker) ?: continue
            if (!current.takes(p.context.contentType)) continue
            if (!target.accepts(state, now)) continue
            target.record(state, now)
            channel(target.service).submit(state, target.tracker.writeSpacing) {
                if (current(target.tracker) == null || held(target.service)) return@submit false
                // A pause or stop of a session the service never took would only invent one; its watch goes as a
                // history write at stop.
                if (!target.sessionOpen && (state == ScrobbleState.PAUSE || state == ScrobbleState.STOP)) return@submit false
                try {
                    target.tracker.scrobble(p.context, state, percent)
                    when (state) {
                        ScrobbleState.START, ScrobbleState.SEEK -> target.sessionOpen = true
                        ScrobbleState.STOP -> target.stopConfirmed = true
                        ScrobbleState.PAUSE -> Unit
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Live reports are never queued.
                    failed(target.service, "scrobble ${state.name.lowercase()}", e)
                }
                true
            }
        }
    }

    /** Whether a request went out, as [ServiceChannel.submit] asks; a stop's watch the service didn't take is queued. */
    private suspend fun reconcile(b: BoundTracker, tracker: RealtimeScrobble, context: TrackerContext, percent: Double): Boolean {
        if (held(b.service)) {
            retryLater(tracker, b, listOf(context), watched = true)
            return false
        }
        try {
            tracker.reconcileWatchedAfterStop(context, percent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(b.service, "watched after stop", e)
            retryLater(tracker, b, listOf(context), watched = true)
        }
        return true
    }

    // Manual marks

    private suspend fun applyMark(mark: WatchedMark) {
        if (bound.none { it.account.enabled }) return
        when (mark) {
            is WatchedMark.Title -> {
                if (mark.type == "movie") {
                    single(TrackerItem(mark.metaId, mark.type, mark.metaId, mark.meta), TrackerMediaKind.MOVIE, mark.watched)
                    return
                }
                // A library row carries no episodes: the addons have them.
                val meta = mark.meta?.takeIf { it.videos.isNotEmpty() } ?: loadMeta(mark.type, mark.metaId)
                if (meta == null || meta.videos.none { it.seriesInfo != null }) {
                    Log.i(TAG) { "no episodes known for ${mark.metaId}: not marked on trackers" }
                    return
                }
                container(meta, meta.videos, mark.watched, wholeTitle = true)
            }
            is WatchedMark.Season -> container(mark.meta, mark.meta.videosInSeason(mark.season), mark.watched, wholeTitle = false)
            is WatchedMark.Video -> {
                val kind = if (videoIsTitle(mark.meta.id, mark.videoId)) TrackerMediaKind.MOVIE else TrackerMediaKind.EPISODE
                single(TrackerItem(mark.meta.id, mark.meta.type, mark.videoId, mark.meta), kind, mark.watched)
            }
        }
    }

    private suspend fun single(item: TrackerItem, kind: TrackerMediaKind, watched: Boolean) {
        val context = context(item, kind) ?: return
        // A series counter can't lose one episode: only a season or show unmark sets it back.
        val trackers = if (watched || context.isMovie) bound else bound.filter { it.tracker is EpisodeHistory }
        dispatch(trackers, context, watched)
    }

    /**
     * A season or show: [HISTORY_BATCH] episodes a request for history trackers, one update per series entry for
     * progress ones. [wholeTitle]: every episode of the title, so an unmark takes each entry back to its start.
     */
    private suspend fun container(meta: MetaItem, videos: List<Video>, watched: Boolean, wholeTitle: Boolean) {
        val now = clock.now()
        val episodes = videos.filter { video ->
            video.seriesInfo != null && (!watched || video.released.let { it == null || it <= now })
        }
        val contexts = mutableListOf<TrackerContext>()
        val entries = LinkedHashMap<String, SeriesGroup>()
        for (video in episodes) {
            val context = context(TrackerItem(meta.id, meta.type, video.id, meta), TrackerMediaKind.EPISODE) ?: continue
            contexts += context
            val key = seriesGroupKey(context) ?: continue
            entries.getOrPut(key) { SeriesGroup(context) }.add(context)
        }
        for (b in bound) {
            if (b.tracker !is EpisodeHistory) continue
            for (batch in contexts.filter { b.takes(it.contentType) }.chunked(HISTORY_BATCH)) {
                channel(b.service).submit(null, b.tracker.writeSpacing) { applyWrite(b.tracker, batch, watched) }
            }
        }
        val progress = bound.filter { it.tracker is SeriesProgress }
        for (group in entries.values) {
            val context = if (watched) group.watchedContext() else group.unwatchedContext(wholeTitle)
            if (context == null) {
                Log.i(TAG) { "${meta.id}: an episode unmarked has no number on its list entry, the entry is left as it is" }
                continue
            }
            dispatch(progress, context, watched)
        }
    }

    /** The series entries [context] would touch, as one key, so one entry gets one write; `null` when none maps. */
    private fun seriesGroupKey(context: TrackerContext): String? = bound
        .filter { it.takes(context.contentType) }
        .mapNotNull { b -> (b.tracker as? SeriesProgress)?.seriesEntryId(context)?.let { "${b.service.id}=$it" } }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("|")

    // Ratings

    /**
     * The user's own score of [meta] on each tracker that rates it ([raters]). They are listed at once as
     * [UserScoreState.READING] and asked in parallel, each answer replacing its line as it comes; one that can't name
     * the title ([TrackerException.Unsupported]) leaves the list, one that fails stays as [UserScoreState.UNREADABLE].
     * A rating still queued for a tracker shows instead of asking it ([UserScoreState.QUEUED]): it is the latest the
     * user gave; once it leaves the queue the tracker is read ([followingQueue]). Empty when no tracker rates the title.
     */
    fun userScores(meta: MetaItem): Flow<List<UserScore>> = flow {
        loaded.await()
        val rating = ratingOf(meta)
        val raters = rating?.let(::raters).orEmpty()
        if (rating == null || raters.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val queued = queue.pending(profileId).mapNotNull { row -> (row.content as? QueuedTrackerContent.Rate)?.let { (row.service to row.coalesceKey) to it } }.toMap()
        val first = raters.map { b ->
            queued[b.service to ratingKey(b.service, rating.target)]
                ?.let { UserScore(b.service, it.score, UserScoreState.QUEUED) }
                ?: UserScore(b.service, null, UserScoreState.READING)
        }
        val asked = raters.filterIndexed { i, _ -> first[i].state == UserScoreState.READING }
        emitAll(
            asked.map { b -> flow { emit(b.service to readScore(b, rating.target)) } }.merge()
                .runningFold(first) { lines, (service, answer) -> lines.mapNotNull { if (it.service == service) answer else it } }
                .followingQueue(meta),
        )
    }

    /**
     * Rates [meta] [score] (1–10), or with `null` removes the rating, on the trackers that rate it ([raters]): the
     * user gives one score and each of them gets it. The writes run in this coordinator whether or not anyone watches
     * them, each in its service's channel, after the profile's earlier writes there (an earlier rating's too); a
     * failure, or a service waiting out a rate limit, queues the write for a retry as a watched write is, replacing a
     * rating queued earlier for the title, which a write that goes through drops. The flow is every tracker's line,
     * [UserScoreState.SAVING] at first, then [UserScoreState.SAVED], [UserScoreState.QUEUED] or
     * [UserScoreState.FAILED] as its write ends, a queued one read from its tracker once it leaves the queue
     * ([followingQueue]); a tracker that can't name the title, or whose account signed out or stopped taking it
     * meanwhile, leaves the list. Empty, nothing written, when no tracker rates the title.
     */
    fun rate(meta: MetaItem, score: Int?): Flow<List<UserScore>> {
        require(score == null || score in 1..10) { "a score is 1-10, not $score" }
        val lines = MutableStateFlow<List<UserScore>?>(null)
        scope.launch {
            try {
                val sends: List<Pair<RatingSend, Deferred<Boolean>>> = ratingOrder.withLock {
                    loaded.await()
                    val rating = ratingOf(meta) ?: return@withLock emptyList()
                    raters(rating).map { b ->
                        val send = RatingSend(b.service)
                        send to channel(b.service).submit(null, b.tracker.writeSpacing) { applyRating(b.tracker as Rating, rating, score, send) }
                    }
                }
                lines.value = sends.map { (send, _) -> UserScore(send.service, score, UserScoreState.SAVING) }
                sends.map { (send, done) -> flow { done.await(); emit(send) } }.merge().collect { send ->
                    lines.value = lines.value.orEmpty().mapNotNull { line ->
                        if (line.service != send.service) line else send.state?.let { UserScore(line.service, score, it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, e) { "rating failed" }
                lines.value = lines.value.orEmpty().map { if (it.state == UserScoreState.SAVING) it.copy(state = UserScoreState.FAILED) else it }
            }
        }
        return lines.filterNotNull().transformWhile { current ->
            emit(current)
            current.any { it.state == UserScoreState.SAVING }
        }.followingQueue(meta)
    }

    /**
     * These lines as they come, then, while some are [UserScoreState.QUEUED], each such line read from its tracker
     * again once its rating has left the queue (a retry sent it, a newer rating replaced it, or it was dropped or given
     * up on): what the tracker holds then. Every change to the queue is when one may have left.
     */
    private fun Flow<List<UserScore>>.followingQueue(meta: MetaItem): Flow<List<UserScore>> = flow {
        var lines = emptyList<UserScore>()
        collect {
            lines = it
            emit(it)
        }
        if (lines.none { it.state == UserScoreState.QUEUED }) return@flow
        val target = ratingOf(meta)?.target ?: return@flow
        emitAll(
            queue.observeCounts(profileId).transformWhile {
                val pending = queue.pending(profileId).filter { it.content is QueuedTrackerContent.Rate }.mapTo(HashSet()) { it.service to it.coalesceKey }
                val left = lines.filter { it.state == UserScoreState.QUEUED && (it.service to ratingKey(it.service, target)) !in pending }
                if (left.isNotEmpty()) {
                    val answers = left.associate { line -> line.service to bound.firstOrNull { it.service == line.service }?.let { readScore(it, target) } }
                    lines = lines.mapNotNull { line -> if (line.service in answers) answers[line.service] else line }
                    emit(lines)
                }
                lines.any { it.state == UserScoreState.QUEUED }
            },
        )
    }

    /** What rating [meta] means: the movie or the show, its ids, and the content type that decides who takes it. */
    private suspend fun ratingOf(meta: MetaItem): QueuedRating? {
        val kind = when {
            meta.type == "movie" -> RatingKind.MOVIE
            meta.type == "series" || meta.videos.any { it.seriesInfo != null } -> RatingKind.SHOW
            // A channel or a TV stream: no service rates those.
            else -> return null
        }
        val item = TrackerItem(meta.id, meta.type, meta.id, meta)
        val context = context(item, if (kind == RatingKind.MOVIE) TrackerMediaKind.MOVIE else TrackerMediaKind.EPISODE) ?: return null
        return QueuedRating(RatingTarget(kind, context.ids, context.anime), context.contentType)
    }

    /**
     * The trackers that rate [rating]'s title: each connected, enabled [Rating] tracker whose content filter takes it,
     * an anime list only when the title has anime ids.
     */
    private fun raters(rating: QueuedRating): List<BoundTracker> = bound.filter { b ->
        b.tracker is Rating && b.takes(rating.contentType) && (!b.tracker.needsAnimeIds || rating.target.anime != null)
    }

    /** [b]'s score of [target]; `null` when it can't name the title. */
    private suspend fun readScore(b: BoundTracker, target: RatingTarget): UserScore? {
        if (held(b.service)) return UserScore(b.service, null, UserScoreState.UNREADABLE)
        return try {
            UserScore(b.service, (b.tracker as Rating).rating(target), UserScoreState.READ)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerException.Unsupported) {
            Log.d(TAG) { "${b.service.id}: ${e.message}" }
            null
        } catch (e: Exception) {
            failed(b.service, "rating read", e)
            UserScore(b.service, null, UserScoreState.UNREADABLE)
        }
    }

    /**
     * [send]'s write of [score] (`null`: the removal) to [tracker], run in its service's channel; whether a request went
     * out. Queued for a retry when it fails or the service asked to wait.
     */
    private suspend fun applyRating(tracker: Rating, rating: QueuedRating, score: Int?, send: RatingSend): Boolean {
        val b = current(tracker)?.takeIf { it.takes(rating.contentType) }
        if (b == null) {
            send.state = null
            return false
        }
        val key = ratingKey(b.service, rating.target)
        if (held(b.service)) {
            Log.i(TAG) { "${b.service.id}: rating not sent, the service asked to wait" }
            send.state = retryRating(tracker, b, key, rating, score)
            return false
        }
        try {
            if (score == null) tracker.clearRating(rating.target) else tracker.rate(rating.target, score)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerException.Unsupported) {
            Log.d(TAG) { "${b.service.id}: ${e.message}" }
            send.state = null
            return false
        } catch (e: Exception) {
            failed(b.service, "rating", e)
            send.state = retryRating(tracker, b, key, rating, score)
            return true
        }
        invalidate(b.service, key, appliedProgress = null)
        send.state = UserScoreState.SAVED
        return true
    }

    /** Queues a rating that didn't go out; its line's state then, `null` when the account is gone. */
    private suspend fun retryRating(tracker: Rating, b: BoundTracker, key: String, rating: QueuedRating, score: Int?): UserScoreState? {
        return when {
            enqueue(tracker, b, key, QueuedTrackerContent.Rate(rating, score), claim = null) -> UserScoreState.QUEUED
            current(tracker) == null -> null
            else -> UserScoreState.FAILED
        }
    }

    // Watchlists

    /**
     * Each line of [type]/[id]'s Watchlist action, [meta] its title where the caller has it (else it is loaded when the
     * id alone doesn't name the title): one per tracker with a watchlist that takes the title ([listers]), in the
     * services' order, saying whether the title is on that list as far as the app knows and where the user's last
     * change there stands. A write an earlier run left queued shows as queued. It follows the connections, the list
     * pages read and the writes. Empty when no tracker takes the title.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchlist(type: String, id: String, meta: MetaItem? = null): Flow<List<WatchlistLine>> = flow {
        loaded.await()
        val title = watchlistTitle(type, id, meta)
        if (title == null) {
            emit(emptyList())
            return@flow
        }
        for (row in queue.pending(profileId)) {
            val write = row.content as? QueuedTrackerContent.Watchlist ?: continue
            if (row.coalesceKey != watchlistKey(row.service, title.target)) continue
            val list = bound.firstOrNull { it.service == row.service }?.watchlist ?: continue
            if (row.coalesceKey in watchlistSends.value) continue
            list.membership.wrote(title.factKeys, write.listed, pending = true)
            watchlistSends.update { it + (row.coalesceKey to WatchlistState.QUEUED) }
        }
        emitAll(
            boundFlow.flatMapLatest { all ->
                val listers = listers(all, title)
                combine(listers.map { (_, list) -> list.membership.changes } + watchlistSends) { _ ->
                    listers.map { (b, list) ->
                        val state = watchlistSends.value[watchlistKey(b.service, title.target)] ?: WatchlistState.IDLE
                        WatchlistLine(b.service, list.membership.listed(title.keys), state)
                    }
                }
            }.distinctUntilChanged(),
        )
    }

    /**
     * Puts [type]/[id] on [service]'s list ([listed]) or takes it off, [meta] as [watchlist]'s. The write runs in this
     * coordinator, in the service's channel after the profile's earlier writes there, whether or not anyone waits for
     * it; until it is through, the title's line shows what it writes. A failure, or the service waiting out a rate
     * limit, queues it for a retry as a watched write is; a write that can't be queued, or whose retries are given up
     * on, goes back. Answers what the write came to: [WatchlistState.IDLE] through, [WatchlistState.QUEUED] or
     * [WatchlistState.FAILED]; `null` when nothing was written (the tracker doesn't take the title, or its account
     * signed out meanwhile).
     */
    fun setWatchlisted(type: String, id: String, meta: MetaItem?, service: TrackerService, listed: Boolean): Deferred<WatchlistState?> {
        val done = CompletableDeferred<WatchlistState?>()
        scope.launch {
            try {
                val sent = watchlistOrder.withLock {
                    loaded.await()
                    val title = watchlistTitle(type, id, meta) ?: return@withLock null
                    val (b, list) = listers(bound, title).firstOrNull { it.first.service == service } ?: return@withLock null
                    val key = watchlistKey(service, title.target)
                    list.membership.wrote(title.factKeys, listed, pending = true)
                    watchlistSends.update { it + (key to WatchlistState.SAVING) }
                    val send = WatchlistSend()
                    channel(service).submit(null, b.tracker.writeSpacing) { applyWatchlist(b.tracker, list, title, key, listed, send) } to send
                }
                if (sent == null) return@launch
                sent.first.await()
                done.complete(sent.second.state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, e) { "${service.id}: watchlist write failed" }
            } finally {
                done.complete(null)
            }
        }
        return done
    }

    /**
     * [send]'s write of [listed] for [title] to [list], run in its service's channel; whether a request went out. Queued
     * for a retry when it fails or the service asked to wait.
     */
    private suspend fun applyWatchlist(tracker: Tracker, list: BoundWatchlist, title: WatchlistTitle, key: String, listed: Boolean, send: WatchlistSend): Boolean {
        val b = current(tracker)?.takeIf { it.takes(title.contentType) }
        if (b == null) {
            list.membership.forget(title.factKeys)
            watchlistSends.update { it - key }
            send.state = null
            return false
        }
        if (held(b.service)) {
            Log.i(TAG) { "${b.service.id}: watchlist write not sent, the service asked to wait" }
            send.state = retryWatchlist(b, list, title, key, listed)
            return false
        }
        try {
            if (listed) list.list.addToWatchlist(title.target) else list.list.removeFromWatchlist(title.target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TrackerException.Unsupported) {
            Log.d(TAG) { "${b.service.id}: ${e.message}" }
            send.state = watchlistFailed(list, title.factKeys, key)
            return false
        } catch (e: Exception) {
            failed(b.service, "watchlist write", e)
            send.state = retryWatchlist(b, list, title, key, listed)
            return true
        }
        invalidate(b.service, key, appliedProgress = null)
        list.membership.wrote(title.factKeys, listed, pending = false)
        watchlistSends.update { it - key }
        send.state = WatchlistState.IDLE
        return true
    }

    /** Queues a watchlist write that didn't go out; what it came to, `null` when the account is gone. */
    private suspend fun retryWatchlist(b: BoundTracker, list: BoundWatchlist, title: WatchlistTitle, key: String, listed: Boolean): WatchlistState? {
        val content = QueuedTrackerContent.Watchlist(QueuedWatchlist(title.target, title.contentType), listed)
        return when {
            enqueue(b.tracker, b, key, content, claim = null) -> {
                watchlistSends.update { it + (key to WatchlistState.QUEUED) }
                WatchlistState.QUEUED
            }
            current(b.tracker) == null -> {
                list.membership.forget(title.factKeys)
                watchlistSends.update { it - key }
                null
            }
            else -> watchlistFailed(list, title.factKeys, key)
        }
    }

    /** A watchlist write that won't go through: the title's line goes back to what the app knew before it. */
    private fun watchlistFailed(list: BoundWatchlist, keys: Set<String>, key: String): WatchlistState {
        list.membership.forget(keys)
        watchlistSends.update { it + (key to WatchlistState.FAILED) }
        return WatchlistState.FAILED
    }

    /**
     * Replays a queued watchlist write. Through, the title's line keeps it; dropped or given up on, the line goes back,
     * unless a newer write of the title is on its way.
     */
    private suspend fun replayWatchlist(
        tracker: Tracker,
        list: BoundWatchlist,
        row: QueuedTrackerWrite,
        write: QueuedTrackerContent.Watchlist,
        deferred: MutableSet<TrackerService>,
    ): Boolean {
        if (!replaying(tracker, deferred) || !queue.isQueued(row.id)) return false
        val target = write.watchlist.target
        var through = false
        val asked = replay(tracker, listOf(row), deferred) {
            if (write.listed) list.list.addToWatchlist(target) else list.list.removeFromWatchlist(target)
            through = true
        }
        val keys = WatchlistMembership.titleKeys(target)
        when {
            through -> {
                list.membership.wrote(keys, write.listed, pending = false)
                watchlistSends.update { it - row.coalesceKey }
            }
            !queue.isQueued(row.id) && watchlistSends.value[row.coalesceKey] != WatchlistState.SAVING -> watchlistFailed(list, keys, row.coalesceKey)
        }
        return asked
    }

    /** What a Watchlist action is about: the movie or the show, its ids, its content type, and the keys it goes by. */
    private suspend fun watchlistTitle(type: String, id: String, meta: MetaItem?): WatchlistTitle? {
        val movie = when {
            type == "movie" -> true
            type == "series" || meta?.videos?.any { it.seriesInfo != null } == true -> false
            // A channel or a TV stream: no list takes those.
            else -> return null
        }
        val kind = if (movie) TrackerMediaKind.MOVIE else TrackerMediaKind.EPISODE
        val context = context(TrackerItem(id, type, id, meta), kind)
            ?: (if (meta == null) loadMeta(type, id)?.let { context(TrackerItem(id, type, id, it), kind) } else null)
            ?: return null
        val target = WatchlistTarget(movie, context.ids, context.anime)
        val factKeys = WatchlistMembership.titleKeys(target)
        return WatchlistTitle(target, context.contentType, factKeys, factKeys + WatchlistMembership.titleKey(type, id))
    }

    /**
     * The trackers whose watchlist takes [title], in the services' order: connected, enabled, the content filter letting
     * it through, an anime list only for a title with anime ids.
     */
    private fun listers(all: List<BoundTracker>, title: WatchlistTitle): List<Pair<BoundTracker, BoundWatchlist>> =
        all.sortedBy { it.service.ordinal }.mapNotNull { b ->
            val list = b.watchlist ?: return@mapNotNull null
            if (!b.takes(title.contentType) || b.tracker.needsAnimeIds && title.target.anime == null) null else b to list
        }

    /**
     * The cards' menus' view of the watchlists: each connected tracker's that has one, with what is known of it, in the
     * services' order; again whenever a connection or what is known changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun watchlists(): Flow<List<BoundTracker>> = flow {
        loaded.await()
        emitAll(
            boundFlow.flatMapLatest { all ->
                val listing = all.filter { it.watchlist != null }.sortedBy { it.service.ordinal }
                if (listing.isEmpty()) flowOf(listing) else combine(listing.map { checkNotNull(it.watchlist).membership.changes }) { listing.toList() }
            },
        )
    }

    /** A title a Watchlist action is about; [factKeys] name it from its ids, [keys] from its Stremio id too. */
    private class WatchlistTitle(val target: WatchlistTarget, val contentType: TrackerContentType, val factKeys: Set<String>, val keys: Set<String>)

    /** One watchlist write: what it came to; until it ran, failed. */
    private class WatchlistSend {
        var state: WatchlistState? = WatchlistState.FAILED
    }

    // Writes

    /** Queues a watched/unwatched write of [context] on each of [trackers] that takes it. */
    private fun dispatch(trackers: List<BoundTracker>, context: TrackerContext, watched: Boolean) {
        for (b in trackers) {
            if (!b.takes(context.contentType)) continue
            channel(b.service).submit(null, b.tracker.writeSpacing) { applyWrite(b.tracker, listOf(context), watched) }
        }
    }

    /**
     * One watched/unwatched write of [contexts], run in its service's channel: several only for a history tracker,
     * as one request. A success drops queued writes it covers; a failure, or a service waiting out a rate limit,
     * queues them for a retry, unless the account was signed out or reconnected meanwhile.
     */
    private suspend fun applyWrite(tracker: Tracker, contexts: List<TrackerContext>, watched: Boolean): Boolean {
        val b = current(tracker) ?: return false
        val taken = contexts.filter { b.takes(it.contentType) }
        if (taken.isEmpty()) return false
        val what = if (watched) "mark watched" else "mark unwatched"
        if (held(b.service)) {
            Log.i(TAG) { "${b.service.id}: $what not sent, the service asked to wait" }
            retryLater(tracker, b, taken, watched)
            return false
        }
        try {
            write(tracker, taken.map { HistoryItem(it) }, watched)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(b.service, what, e)
            retryLater(tracker, b, taken, watched)
            return true
        }
        for (context in taken) {
            val key = coalesceKey(tracker, context) ?: continue
            invalidate(b.service, key, if (watched) progressClaim(tracker, context) else null)
        }
        return true
    }

    /** [items] to [tracker]: several only to a history tracker, which takes them in one request. */
    private suspend fun write(tracker: Tracker, items: List<HistoryItem>, watched: Boolean) {
        when {
            tracker is EpisodeHistory && items.size > 1 -> tracker.writeHistory(items, watched)
            watched -> tracker.markWatched(items.single().context, items.single().watchedAt)
            else -> tracker.markUnwatched(items.single().context)
        }
    }

    /** Queues the writes of [contexts] that name a remote row for a retry under the sign-in [b]. */
    private suspend fun retryLater(tracker: Tracker, b: BoundTracker, contexts: List<TrackerContext>, watched: Boolean) {
        for (context in contexts) {
            val key = coalesceKey(tracker, context) ?: continue
            enqueue(tracker, b, key, QueuedTrackerContent.Mark(context, watched), if (watched) progressClaim(tracker, context) else null)
        }
    }

    /**
     * Queues a failed write of [content] for a retry under the sign-in [b] it was made through, and returns whether it
     * was. Dropped when that sign-in ended meanwhile: its queue went with it, and the row would otherwise replay through
     * the account that replaced it.
     */
    private suspend fun enqueue(tracker: Tracker, b: BoundTracker, key: String, content: QueuedTrackerContent, claim: Long?): Boolean {
        if (current(tracker) == null) {
            Log.i(TAG) { "${b.service.id}: failed write dropped, its account signed out meanwhile" }
            return false
        }
        val queued = try {
            queue.enqueue(
                profileId = profileId,
                service = b.service,
                connectedAt = b.account.connectedAt,
                coalesceKey = key,
                content = content,
                progressClaim = claim,
                watchedAt = clock.now().toEpochMilliseconds(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, e) { "${b.service.id}: write not queued" }
            false
        }
        Log.i(TAG) { if (queued) "${b.service.id}: write queued for retry" else "${b.service.id}: write dropped (signed out, or a higher claim waits)" }
        return queued
    }

    /** Drops the queued writes for [key] that a write which went through covers ([TrackerWriteQueue.invalidate]). */
    private suspend fun invalidate(service: TrackerService, key: String, appliedProgress: Long?) {
        try {
            queue.invalidate(profileId, service, key, appliedProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The write landed; a failed cleanup must not look like a failed write.
            Log.w(TAG, e) { "${service.id}: write queue cleanup failed" }
        }
    }

    private suspend fun replayOnce(deferred: MutableSet<TrackerService>) {
        val sends = mutableListOf<Deferred<Boolean>>()
        // A history tracker's rows go out together per direction, as a season mark's do; a series entry's one by one.
        val batches = LinkedHashMap<Pair<Tracker, Boolean>, MutableList<Replay>>()
        for (row in queue.pending(profileId)) {
            val b = bound.firstOrNull { it.service == row.service } ?: continue
            if (!b.account.enabled) continue
            when (val content = row.content) {
                is QueuedTrackerContent.Rate -> {
                    // A rating: one row, one request.
                    if (!stillTakes(b, row, content.rating.contentType)) continue
                    val tracker = b.tracker
                    if (tracker is Rating) {
                        sends += channel(b.service).submit(null, tracker.writeSpacing) { replayRating(tracker, row, content, deferred) }
                    } else {
                        queue.complete(row.id)
                    }
                }
                is QueuedTrackerContent.Mark -> {
                    if (!stillTakes(b, row, content.context.contentType)) continue
                    if (b.tracker is EpisodeHistory) {
                        batches.getOrPut(b.tracker to content.watched) { mutableListOf() } += Replay(row, content)
                    } else {
                        sends += channel(b.service).submit(null, b.tracker.writeSpacing) { replayRows(b.tracker, listOf(Replay(row, content)), deferred) }
                    }
                }
                is QueuedTrackerContent.Watchlist -> {
                    val list = b.watchlist
                    if (list == null) {
                        queue.complete(row.id)
                        continue
                    }
                    if (!stillTakes(b, row, content.watchlist.contentType)) {
                        watchlistFailed(list, WatchlistMembership.titleKeys(content.watchlist.target), row.coalesceKey)
                        continue
                    }
                    sends += channel(b.service).submit(null, b.tracker.writeSpacing) { replayWatchlist(b.tracker, list, row, content, deferred) }
                }
            }
        }
        for ((key, replays) in batches) {
            val tracker = key.first
            for (batch in replays.chunked(HISTORY_BATCH)) {
                sends += channel(tracker.service).submit(null, tracker.writeSpacing) { replayRows(tracker, batch, deferred) }
            }
        }
        sends.awaitAll()
    }

    /** Whether [b] still takes [row]'s content [type]; a row it no longer does is dropped. */
    private suspend fun stillTakes(b: BoundTracker, row: QueuedTrackerWrite, type: TrackerContentType): Boolean {
        if (b.takes(type)) return true
        Log.i(TAG) { "${row.service.id}: queued write dropped, its content type is filtered out" }
        queue.complete(row.id)
        return false
    }

    /** Replays [replays], one service's rows in one direction, as one write. */
    private suspend fun replayRows(tracker: Tracker, replays: List<Replay>, deferred: MutableSet<TrackerService>): Boolean {
        if (!replaying(tracker, deferred)) return false
        // A newer write for a row may have replaced or covered it while this waited its turn.
        val live = replays.filter { queue.isQueued(it.row.id) }
        if (live.isEmpty()) return false
        val watched = live.first().mark.watched
        return replay(tracker, live.map { it.row }, deferred) {
            write(tracker, live.map { HistoryItem(it.mark.context, if (watched) Instant.fromEpochMilliseconds(it.row.watchedAt) else null) }, watched)
        }
    }

    /** Replays a queued rating: [rate]'s score, or the rating's removal. */
    private suspend fun replayRating(tracker: Rating, row: QueuedTrackerWrite, rate: QueuedTrackerContent.Rate, deferred: MutableSet<TrackerService>): Boolean {
        if (!replaying(tracker, deferred) || !queue.isQueued(row.id)) return false
        return replay(tracker, listOf(row), deferred) {
            when (val score = rate.score) {
                null -> tracker.clearRating(rate.rating.target)
                else -> tracker.rate(rate.rating.target, score)
            }
        }
    }

    /** Whether [tracker] is asked this pass: still connected, not deferred, not waiting out a rate limit. */
    private fun replaying(tracker: Tracker, deferred: Set<TrackerService>): Boolean =
        tracker.service !in deferred && !held(tracker.service) && current(tracker) != null

    /**
     * Sends [rows] with [send], and drops them once it went through; whether a request went out. A transient failure
     * keeps their attempts and defers the service for the rest of the pass, one the service can't name the item for
     * drops them, and any other failure counts an attempt each: the fifth keeps them as failed, refused with the HTTP
     * status the service answered.
     */
    private suspend fun replay(tracker: Tracker, rows: List<QueuedTrackerWrite>, deferred: MutableSet<TrackerService>, send: suspend () -> Unit): Boolean {
        val service = tracker.service
        try {
            send()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            when {
                e is TrackerException.Unsupported -> {
                    for (row in rows) queue.complete(row.id)
                    failed(service, "queued write dropped", e)
                }
                isTransientTrackerFailure(e) -> {
                    // Not an answer about the write: keep its attempts, and stop asking this service for this pass.
                    deferred += service
                    failed(service, "queued write deferred", e)
                }
                else -> {
                    val givenUp = rows.count { queue.recordFailure(it.id, (e as? TrackerException.Api)?.status) }
                    failed(service, if (givenUp > 0) "$givenUp queued write(s) given up on after ${TrackerWriteQueue.MAX_ATTEMPTS} attempts" else "queued write", e)
                }
            }
            return true
        }
        for (row in rows) queue.complete(row.id)
        Log.i(TAG) { "${service.id}: ${rows.size} queued write(s) replayed" }
        return true
    }

    /** A queued watched write with its row. */
    private class Replay(val row: QueuedTrackerWrite, val mark: QueuedTrackerContent.Mark)

    /** One tracker's rating write: what it came to, `null` once the tracker left the list; until it ran, failed. */
    private class RatingSend(val service: TrackerService) {
        var state: UserScoreState? = UserScoreState.FAILED
    }

    // Ids

    private suspend fun context(item: TrackerItem, kind: TrackerMediaKind): TrackerContext? {
        // The ids decide whether the item is anime, so they are looked up whenever that changes where it goes: an
        // account treats anime apart from other series or movies, or an anime tracker takes it.
        val plain = if (kind == TrackerMediaKind.MOVIE) TrackerContentType.MOVIE else TrackerContentType.SERIES
        val animeIds = bound.any { b ->
            val takesAnime = b.takes(TrackerContentType.ANIME)
            takesAnime != b.takes(plain) || takesAnime && b.tracker.needsAnimeIds
        }
        val resolved = try {
            resolver.resolve(item, animeIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, e) { "id resolution failed for ${item.videoId}, not tracked" }
            return null
        }
        val anime = resolved?.anime?.takeUnless { it.isEmpty }
        if (resolved == null || resolved.ids.isEmpty && anime == null) {
            Log.d(TAG) { "no tracker ids for ${item.videoId}" }
            return null
        }
        // An anime list's item is anime whatever the resolver found.
        val animeNamespace = item.stremioId is StremioId.Anime
        // Cinemeta's numbering is IMDb's; an anime id's own numbering is not, so only the resolver's counts there.
        val series = item.meta?.video(item.videoId)?.seriesInfo?.takeIf { !animeNamespace }
        return TrackerContext(
            metaId = item.metaId,
            videoId = item.videoId,
            kind = kind,
            contentType = when {
                anime != null || animeNamespace -> TrackerContentType.ANIME
                kind == TrackerMediaKind.MOVIE -> TrackerContentType.MOVIE
                else -> TrackerContentType.SERIES
            },
            ids = resolved.ids,
            anime = anime,
            season = if (kind == TrackerMediaKind.EPISODE) resolved.season ?: series?.season else null,
            episode = if (kind == TrackerMediaKind.EPISODE) resolved.episode ?: series?.episode else null,
            animeEpisode = resolved.animeEpisode,
            animeProgress = resolved.animeProgress ?: resolved.animeEpisode,
        )
    }

    // Bookkeeping

    /** [tracker]'s connection as it is now, or `null` when its sign-in ended (signed out or reconnected). */
    private fun current(tracker: Tracker): BoundTracker? = bound.firstOrNull { it.tracker === tracker }

    private fun channel(service: TrackerService): ServiceChannel = channels.getOrPut(service) { ServiceChannel(scope, service) }

    /** Logs a failed request; a rate limit holds [service]'s requests for the wait it asked for, else [RATE_LIMIT_HOLD]. */
    private fun failed(service: TrackerService, what: String, e: Exception) {
        if (e is TrackerException.RateLimited) holds[service] = timeSource.markNow() + (e.retryAfterSeconds?.seconds ?: RATE_LIMIT_HOLD)
        if (e is TrackerException) Log.w(TAG) { "${service.id}: $what failed: ${e.message}" } else Log.w(TAG, e) { "${service.id}: $what failed" }
    }

    /** Whether [service] is waiting out a rate limit. */
    private fun held(service: TrackerService): Boolean = holds[service]?.hasNotPassedNow() == true

    /** The playback in progress, with the realtime trackers pinned when it began. */
    private class Playback(val context: TrackerContext, private val thresholdPercent: Int, val targets: List<PlaybackTarget>) {
        var positionMillis = 0L
            private set
        private var durationMillis = 0L
        private var creditsStartMillis: Long? = null
        var crossed = false

        fun move(at: PlaybackPosition) {
            positionMillis = at.positionMillis
            if (at.durationMillis > 0) durationMillis = at.durationMillis
            if (at.creditsStartMillis != null) creditsStartMillis = at.creditsStartMillis
        }

        val reachedThreshold: Boolean get() = countsAsWatched(positionMillis, durationMillis, thresholdPercent, creditsStartMillis)

        /** The position as a percentage (0–100) of the duration, 0 while it is unknown. */
        val percent: Double get() = if (durationMillis <= 0) 0.0 else (positionMillis * 100.0 / durationMillis).coerceIn(0.0, 100.0)
    }

    /**
     * One realtime tracker pinned to the playback, with that playback's report bookkeeping: per playback and per
     * tracker, since each service's throttle windows are its own.
     */
    private class PlaybackTarget(val service: TrackerService, val tracker: RealtimeScrobble) {
        private var lastState: ScrobbleState? = null
        private var lastSentAt: ComparableTimeMark? = null
        private var lastSeekAt: ComparableTimeMark? = null

        /** A start was queued for this playback, so the target owns its watch (whether the start lands or not). */
        var sessionStarted = false
            private set

        /** The service accepted a start (or a seek's re-start) of this playback, so it has a session to pause or stop. */
        var sessionOpen = false

        /** The service accepted the playback's stop, and so applied its own completion rule. */
        var stopConfirmed = false

        fun accepts(state: ScrobbleState, now: ComparableTimeMark): Boolean {
            val policy = tracker.scrobblePolicy
            if (state == ScrobbleState.SEEK) {
                // A checkpoint means something only while the service thinks playback runs, and only so often.
                val throttle = policy.seekThrottle ?: return false
                if (lastState != ScrobbleState.START) return false
                val last = lastSeekAt ?: return true
                return now - last >= throttle
            }
            // A pause or stop with no session behind it would only invent one.
            if (state != ScrobbleState.START && !sessionStarted) return false
            if (lastState != state) return true
            val last = lastSentAt ?: return true
            val elapsed = now - last
            if (elapsed < DUPLICATE_STATE_DEBOUNCE) return false
            return state != ScrobbleState.START || elapsed >= policy.resendThrottle
        }

        fun record(state: ScrobbleState, now: ComparableTimeMark) {
            if (state == ScrobbleState.SEEK) {
                lastSeekAt = now
                // The session keeps playing, so this counts as the latest start.
                lastState = ScrobbleState.START
                lastSentAt = now
                return
            }
            lastState = state
            lastSentAt = now
            if (state == ScrobbleState.START) sessionStarted = true
        }
    }

    /** A container's episodes on one series entry, for the single progress write it gets. */
    private class SeriesGroup(val base: TrackerContext) {
        private var count = 0
        private var maxProgress: Int? = null
        private var firstEpisode: Int? = null
        private var unnumbered = false

        fun add(context: TrackerContext) {
            count++
            val episode = context.animeEpisode
            if (episode == null) unnumbered = true else if (firstEpisode.let { it == null || episode < it }) firstEpisode = episode
            val progress = context.animeProgress ?: return
            if (maxProgress.let { it == null || progress > it }) maxProgress = progress
        }

        fun watchedContext(): TrackerContext {
            val progress = maxProgress ?: count
            return base.copy(animeEpisode = progress, animeProgress = progress)
        }

        /**
         * The unmark of the entry from its first episode unmarked on ([wholeTitle]: from its start); `null` when an
         * episode has no number on the entry, so which part of it the mark covers can't be told.
         */
        fun unwatchedContext(wholeTitle: Boolean): TrackerContext? {
            val from = if (wholeTitle) 1 else (if (unnumbered) null else firstEpisode) ?: return null
            return base.copy(animeEpisode = from, animeProgress = null)
        }
    }

    internal companion object {
        private const val TAG = "trackers"

        const val SEEK_JUMP_MILLIS = 5_000L

        /** The player reports a state several times around one change. */
        val DUPLICATE_STATE_DEBOUNCE = 1.seconds

        /** Episodes a history request carries (MDBList takes up to 200 shows a request). */
        const val HISTORY_BATCH = 100

        /** How long a rate limit that doesn't say when to come back (no `Retry-After`) holds its service. */
        val RATE_LIMIT_HOLD = 10.seconds

        /** The remote row a write changes on its service, which queued writes coalesce on. */
        fun coalesceKey(tracker: Tracker, context: TrackerContext): String? = when (tracker) {
            is SeriesProgress -> tracker.seriesEntryId(context)?.let { "${tracker.service.id}|series|$it" }
            is EpisodeHistory -> tracker.historyRowIdentity(context)?.let { row ->
                val coordinate = if (context.isMovie) "movie" else "s${context.season}e${context.episode}"
                "${tracker.service.id}|$coordinate|$row"
            }
            else -> null
        }

        /** The absolute progress a watched write claims, for series-progress trackers; `null` for history writes. */
        fun progressClaim(tracker: Tracker, context: TrackerContext): Long? =
            (tracker as? SeriesProgress)?.seriesProgress(context)?.toLong()

        /**
         * The key a queued rating of [target] coalesces on: the service's score of the title (a season or episode of it)
         * named by one id, as [ExternalIds.rowIdentity] picks it, else an anime id.
         */
        fun ratingKey(service: TrackerService, target: RatingTarget): String {
            val coordinate = when (target.kind) {
                RatingKind.MOVIE -> "movie"
                RatingKind.SHOW -> "show"
                RatingKind.SEASON -> "s${target.season}"
                RatingKind.EPISODE -> "s${target.season}e${target.episode}"
            }
            return "${service.id}|rating|$coordinate|${titleIdentity(target.ids, target.anime)}"
        }

        /** The key a queued watchlist write of [target] coalesces on: the title on the service's list, named as [ratingKey] names it. */
        fun watchlistKey(service: TrackerService, target: WatchlistTarget): String =
            "${service.id}|watchlist|${if (target.isMovie) "movie" else "show"}|${titleIdentity(target.ids, target.anime)}"

        /** A title named by one id, as [ExternalIds.rowIdentity] picks it, else an anime id. */
        private fun titleIdentity(ids: ExternalIds, anime: AnimeIds?): String? = ids.rowIdentity() ?: listOfNotNull(
            anime?.mal?.let { "mal=$it" },
            anime?.anilist?.let { "anilist=$it" },
            anime?.simkl?.let { "simkl=$it" },
            anime?.kitsu?.let { "kitsu=$it" },
            anime?.anidb?.let { "anidb=$it" },
        ).firstOrNull()
    }
}
