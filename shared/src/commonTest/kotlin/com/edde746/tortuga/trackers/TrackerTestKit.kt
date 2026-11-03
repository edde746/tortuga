@file:OptIn(ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.trackers

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.data.tracker.QueuedTrackerContent
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.session.PlaybackPosition
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.sync.FailingSecretStore
import com.edde746.tortuga.trackers.catalog.TrackerCatalog
import com.edde746.tortuga.trackers.catalog.TrackerCatalogPage
import com.edde746.tortuga.trackers.catalog.TrackerCatalogProvider
import com.edde746.tortuga.trackers.catalog.TrackerCatalogSource
import com.edde746.tortuga.trackers.catalog.TrackerWatchlist
import com.edde746.tortuga.trackers.catalog.WatchlistTarget
import com.edde746.tortuga.trackers.ids.AnimeEntryTitles
import com.edde746.tortuga.trackers.ids.FixtureMappings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** One call a fake tracker received, at virtual time [atMillis]. */
internal data class Call(val service: TrackerService, val op: String, val atMillis: Long, val context: TrackerContext? = null)

/**
 * A fake tracker service for one connection: it records every call on the shared [log] (so tests see the order across
 * services) and fails or stalls as a test asks. Each call takes its access token from [credentials] before it goes
 * out, as a real tracker's requests do, and [latency] is the time the service then takes to answer.
 */
internal open class FakeTracker(
    override val service: TrackerService,
    private val scope: TestScope,
    private val log: MutableList<Call>,
    val credentials: TrackerCredentials,
) : Rating {
    override val needsAnimeIds: Boolean get() = false
    override val writeSpacing: Duration get() = if (service == TrackerService.TRAKT) 1.seconds else Tracker.DEFAULT_WRITE_SPACING

    /**
     * What the next calls throw, by operation prefix (`scrobble`, `watched`, `unwatched`, `reconcile`, `rating`, `rate`,
     * `unrate`); consumed in order.
     */
    val failures = mutableMapOf<String, ArrayDeque<Throwable>>()

    /** The account's scores, by the title as [ratingId] names it. */
    val scores = mutableMapOf<String, Int>()

    /** How long each call takes. */
    var latency: Duration = Duration.ZERO

    fun failNext(op: String, vararg errors: Throwable) {
        failures.getOrPut(op) { ArrayDeque() }.addAll(errors)
    }

    protected suspend fun call(op: String, detail: String, context: TrackerContext?) {
        credentials.accessToken()
        if (latency > Duration.ZERO) delay(latency)
        log += Call(service, if (detail.isEmpty()) op else "$op $detail", scope.testScheduler.currentTime, context)
        failures[op]?.removeFirstOrNull()?.let { throw it }
    }

    override suspend fun markWatched(context: TrackerContext, watchedAt: Instant?) =
        call("watched", "${context.videoId}${watchedAt?.let { " at=${it.toEpochMilliseconds()}" }.orEmpty()}", context)

    override suspend fun markUnwatched(context: TrackerContext) = call("unwatched", context.videoId, context)

    /** The title as the service names it: its kind and external ids, as [ExternalIds.rowIdentity] picks one. */
    protected open fun ratingId(target: RatingTarget): String =
        "${target.kind.name.lowercase()} ${target.ids.rowIdentity() ?: throw TrackerException.Unsupported(service, "rating without ids")}"

    override suspend fun rating(target: RatingTarget): Int? {
        val id = ratingId(target)
        call("rating", id, null)
        return scores[id]
    }

    override suspend fun rate(target: RatingTarget, score: Int) {
        val id = ratingId(target)
        call("rate", "$id $score", null)
        scores[id] = score
    }

    override suspend fun clearRating(target: RatingTarget) {
        val id = ratingId(target)
        call("unrate", id, null)
        scores.remove(id)
    }
}

/** Trakt, Simkl or MDBList: live reports and per-item history. */
internal class FakeRealtimeTracker(service: TrackerService, scope: TestScope, log: MutableList<Call>, credentials: TrackerCredentials) :
    FakeTracker(service, scope, log, credentials), RealtimeScrobble, EpisodeHistory {
    override val scrobblePolicy: ScrobblePolicy = when (service) {
        TrackerService.SIMKL -> ScrobblePolicy(resendThrottle = 20.seconds, seekThrottle = null)
        else -> ScrobblePolicy(resendThrottle = 30.seconds, seekThrottle = 5.seconds)
    }

    override suspend fun scrobble(context: TrackerContext, state: ScrobbleState, progressPercent: Double) =
        call("scrobble", "${state.name} ${progressPercent.toInt()}", context)

    override suspend fun reconcileWatchedAfterStop(context: TrackerContext, progressPercent: Double) =
        call("reconcile", progressPercent.toInt().toString(), context)

    override fun historyRowIdentity(context: TrackerContext): String? = context.ids.rowIdentity()

    /** One call, as one request, with the items as [markWatched] and [markUnwatched] name theirs, comma-separated. */
    override suspend fun writeHistory(items: List<HistoryItem>, watched: Boolean) = call(
        if (watched) "watched" else "unwatched",
        items.joinToString(", ") { item -> "${item.context.videoId}${item.watchedAt?.let { " at=${it.toEpochMilliseconds()}" }.orEmpty()}" },
        items.first().context,
    )
}

/** MyAnimeList or AniList: one progress counter per anime entry. */
internal class FakeAnimeTracker(service: TrackerService, scope: TestScope, log: MutableList<Call>, credentials: TrackerCredentials) :
    FakeTracker(service, scope, log, credentials), SeriesProgress {
    override val needsAnimeIds: Boolean get() = true

    override fun seriesEntryId(context: TrackerContext): String? =
        (if (service == TrackerService.MYANIMELIST) context.anime?.mal else context.anime?.anilist)?.toString()

    override fun seriesProgress(context: TrackerContext): Int? = context.animeProgress

    override suspend fun markWatched(context: TrackerContext, watchedAt: Instant?) =
        call("watched", "${seriesEntryId(context)}@${context.animeProgress}", context)

    /** Named by the first episode the unmark covers on the entry, as [SeriesProgress] reads it. */
    override suspend fun markUnwatched(context: TrackerContext) = call("unwatched", "${seriesEntryId(context)}@${context.animeEpisode}", context)

    /** An anime list names a title by its entry; one without an entry here can't be rated. */
    override fun ratingId(target: RatingTarget): String {
        val entry = if (service == TrackerService.MYANIMELIST) target.anime?.mal else target.anime?.anilist
        return "${target.kind.name.lowercase()} ${entry ?: throw TrackerException.Unsupported(service, "rating without an anime id")}"
    }
}

/**
 * A provider for one service whose trackers are fakes; [trackers] holds each binding's tracker, newest last. Devices
 * never refresh or revoke (the account server does), so it has no refresher and revoking fails the test.
 */
internal open class FakeProvider(
    override val service: TrackerService,
    private val scope: TestScope,
    private val log: MutableList<Call>,
) : TrackerProvider {
    val trackers = mutableListOf<FakeTracker>()
    var deviceCode: FakeDeviceCode? = null
    var relaySignIn: TrackerSignIn.Relay? = null

    override val signIn: TrackerSignIn get() = deviceCode ?: relaySignIn ?: error("no sign-in set up")

    override val refresher: TokenRefresher? get() = error("a device asked for a refresher: the account server refreshes")

    override fun tracker(credentials: TrackerCredentials): Tracker {
        val tracker = when (service) {
            TrackerService.MYANIMELIST, TrackerService.ANILIST -> FakeAnimeTracker(service, scope, log, credentials)
            else -> FakeRealtimeTracker(service, scope, log, credentials)
        }
        trackers += tracker
        return tracker
    }

    override suspend fun revoke(session: TrackerSession) = error("a device revoked: the account server revokes")
}

/** A [FakeProvider] whose service also has a watchlist: [lists] holds each binding's, newest last. */
internal class FakeListProvider(service: TrackerService, private val scope: TestScope, private val log: MutableList<Call>) :
    FakeProvider(service, scope, log), TrackerCatalogProvider {
    val lists = mutableListOf<FakeWatchlist>()

    override fun catalog(credentials: TrackerCredentials): TrackerCatalogSource = FakeWatchlist(service, scope, log, credentials).also { lists += it }
}

/**
 * One connection's watchlist on a fake service: one row, [titles] in one page, and adds and removes recorded on the
 * shared log as `watchlist add <title>` (the IMDb id, or on an anime list its entry); fails as [failNext] asks.
 */
internal class FakeWatchlist(
    override val service: TrackerService,
    private val scope: TestScope,
    private val log: MutableList<Call>,
    private val credentials: TrackerCredentials,
) : TrackerCatalogSource, TrackerWatchlist {
    /** The titles on the list, as its page lists them. */
    val titles = mutableListOf<MetaItemPreview>()
    private val failures = mutableMapOf<String, ArrayDeque<Throwable>>()

    /** What the next calls of [op] (`add`, `remove`) throw, in order. */
    fun failNext(op: String, vararg errors: Throwable) {
        failures.getOrPut(op) { ArrayDeque() }.addAll(errors)
    }

    override suspend fun catalogs(): List<TrackerCatalog> = listOf(TrackerCatalog(service, "watchlist", TrackerCatalog.Kind.WATCHLIST, type = null))

    override suspend fun page(catalog: TrackerCatalog, cursor: String?): TrackerCatalogPage = TrackerCatalogPage(titles.toList(), next = null)

    override suspend fun addToWatchlist(target: WatchlistTarget) = call("add", target)

    override suspend fun removeFromWatchlist(target: WatchlistTarget) = call("remove", target)

    private suspend fun call(op: String, target: WatchlistTarget) {
        credentials.accessToken()
        val anime = service == TrackerService.MYANIMELIST || service == TrackerService.ANILIST
        val title = if (anime) target.anime?.mal?.let { "entry=$it" } else target.ids.imdb
        title ?: throw TrackerException.Unsupported(service, "watchlist item without ids")
        log += Call(service, "watchlist $op $title", scope.testScheduler.currentTime)
        failures[op]?.removeFirstOrNull()?.let { throw it }
    }
}

/**
 * A device-code service that answers [answers] in turn (then Pending), recording when each poll came; [failRequest]
 * makes asking for a code throw it.
 */
internal class FakeDeviceCode(
    private val scope: TestScope,
    private val answers: List<DevicePoll>,
    val grant: DeviceCodeGrant,
    private val failRequest: Throwable? = null,
) : TrackerSignIn.DeviceCode {
    val polls = mutableListOf<Long>()

    override suspend fun requestCode(): DeviceCodeGrant {
        failRequest?.let { throw it }
        return grant
    }

    override suspend fun probe(grant: DeviceCodeGrant): DevicePoll {
        polls += scope.testScheduler.currentTime
        return answers.getOrElse(polls.size - 1) { DevicePoll.Pending }
    }

    override suspend fun session(token: JsonObject): TrackerSession =
        TrackerSession(accessToken = "granted", refreshToken = "r0", username = "alex", createdAt = 1)
}

/** A relay service's sign-in: the relay's tokens become a session of user "kai". */
internal object FakeRelaySignIn : TrackerSignIn.Relay {
    override suspend fun session(result: RelayResult): TrackerSession =
        TrackerSession(accessToken = result.accessToken, refreshToken = result.refreshToken, username = "kai", createdAt = 1)
}

/**
 * An OAuth relay whose session's answer is [result], once the test completes it (null: the user declined). A start
 * with a return also gets the URL for this device's browser, whose return's proof collects [proofResult].
 */
internal class FakeRelay : OAuthRelay {
    val result = CompletableDeferred<RelayResult?>()

    /** Each start's return for this device's browser (null: none). */
    val returns = mutableListOf<String?>()

    /** The proofs collected with. */
    val collected = mutableListOf<String>()
    var proofResult = RelayResult(accessToken = "own", refreshToken = "r")

    override suspend fun start(service: TrackerService, redirectUri: String?): RelayStart {
        returns += redirectUri
        val url = "https://relay.example/v1/trackers/oauth/${service.id}/s1"
        if (redirectUri == null) return RelayStart(session = "s1", url = url)
        return RelayStart(session = "s1", url = url, browserUrl = "https://relay.example/v1/trackers/oauth/${service.id}/own", browserState = "own")
    }

    override suspend fun poll(service: TrackerService, session: String): RelayResult? = result.await()

    override suspend fun collect(service: TrackerService, session: String, proof: String): RelayResult {
        collected += proof
        return proofResult
    }
}

/**
 * Stremio ids to tracker ids: `tt…` ids are IMDb ids, their episodes numbered as in the id; `kitsu:N` ids map to
 * MAL/AniList entry N with the episode as progress, and the IMDb id in [kitsuImdb] with season 1. Unknown ids are
 * unmapped; [failing] ids throw.
 */
internal class FakeResolver : TrackerIdResolver {
    val calls = mutableListOf<Pair<TrackerItem, Boolean>>()
    val failing = mutableSetOf<String>()
    val kitsuImdb = mutableMapOf<String, String>()

    /** `tt` ids that are anime by the datasets, with their entry id. */
    val animeTt = mutableMapOf<String, Int>()

    override suspend fun resolve(item: TrackerItem, animeIds: Boolean): ResolvedIds? {
        calls += item to animeIds
        if (item.metaId in failing) throw IllegalStateException("mapping download failed")
        val parts = item.videoId.split(":")
        return when {
            item.metaId.startsWith("tt") -> {
                val episode = if (parts.size == 3) parts[1].toInt() to parts[2].toInt() else null
                val entry = animeTt[item.metaId]?.takeIf { animeIds }
                ResolvedIds(
                    ids = ExternalIds(imdb = item.metaId),
                    anime = entry?.let { AnimeIds(mal = it, anilist = it) },
                    season = episode?.first,
                    episode = episode?.second,
                    animeEpisode = entry?.let { episode?.second },
                )
            }
            item.metaId.startsWith("kitsu:") -> {
                val entry = parts[1].toInt()
                val episode = parts.getOrNull(2)?.toInt()
                ResolvedIds(
                    ids = ExternalIds(imdb = kitsuImdb[item.metaId]),
                    anime = AnimeIds(mal = entry, anilist = entry, kitsu = entry),
                    season = kitsuImdb[item.metaId]?.let { 1 }.takeIf { episode != null },
                    episode = episode.takeIf { kitsuImdb[item.metaId] != null },
                    animeEpisode = episode,
                )
            }
            else -> null
        }
    }
}

internal fun movieMeta(id: String = "tt0111161") = MetaItem(MetaItemPreview(id = id, type = "movie", name = "A movie"))

internal fun seriesMeta(id: String, episodes: Int, season: Int = 1, released: (Int) -> Instant? = { null }) = MetaItem(
    MetaItemPreview(id = id, type = "series", name = "A show"),
    videos = (1..episodes).map { Video(id = "$id:$season:$it", released = released(it), seriesInfo = SeriesInfo(season, it)) },
)

/** A queued watched write of the movie [imdb], or with [watched] `false` its removal. */
internal fun movieMark(imdb: String = "tt1", watched: Boolean = true) =
    QueuedTrackerContent.Mark(TrackerContext(imdb, imdb, TrackerMediaKind.MOVIE, TrackerContentType.MOVIE, ExternalIds(imdb)), watched)

/** Seconds as the observer's millisecond positions. */
internal fun at(positionSeconds: Int, durationSeconds: Int = 1000) = PlaybackPosition(positionSeconds * 1000L, durationSeconds * 1000L)

/**
 * A profile's trackers on the test's virtual clock: an in-memory database, an in-memory secret store ([store] makes it
 * fail as a test asks), fake providers for [services] (plus [realProviders], a service's own client against a
 * scripted service), a [FakeResolver] unless [idResolver] is given, and the account's tracker connections on [server]
 * (a new account's unless another fixture's is given: then this one is another device of that account), wired
 * through a real [TrackerHub]. With [watchlists] each fake service also has a watchlist ([FakeListProvider]).
 */
internal class TrackerFixture(
    val scope: TestScope,
    services: List<TrackerService> = TrackerService.entries,
    private val relay: OAuthRelay? = null,
    server: FakeTrackerAccountServer? = null,
    private val realProviders: List<TrackerProvider> = emptyList(),
    private val idResolver: TrackerIdResolver? = null,
    watchlists: Boolean = false,
) {
    val profileId = "p1"
    val wallClock = TestClock()
    val data = LocalData(
        inMemoryDatabase(),
        { wallClock.now },
        StandardTestDispatcher(scope.testScheduler),
        HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
    )
    val secrets = MemorySecretStore()
    val store = FailingSecretStore(secrets)
    val tokens = TrackerTokenStore(store)
    val log = mutableListOf<Call>()
    val providers = services.associateWith { if (watchlists) FakeListProvider(it, scope, log) else FakeProvider(it, scope, log) }
    val resolver = FakeResolver()
    val metas = mutableMapOf<String, MetaItem>()

    /** How long fetching a meta from the addons takes (a library row's mark waits for it). */
    var metaLatency: Duration = Duration.ZERO

    val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(wallClock.now + scope.testScheduler.currentTime)
    }
    val server = server ?: FakeTrackerAccountServer(scope, clock)

    val hub = newHub()

    val coordinator: TrackerCoordinator get() = hub.coordinator(profileId)
    val observer by lazy { coordinator.observer() }

    /** Another hub over this device's database and secret store: the app's next launch. */
    fun newHub() = TrackerHub(
        accountRepository = data.trackerAccounts,
        queue = data.trackerWriteQueue,
        settings = data.settings,
        tokens = tokens,
        server = server,
        api = server.api,
        providers = providers.values.toList() + realProviders,
        clientIds = (providers.keys + realProviders.map { it.service }).associateWith { "client-${it.id}" },
        resolver = idResolver ?: resolver,
        animeTitles = AnimeEntryTitles(FixtureMappings()),
        relay = relay,
        loadMeta = { _, _, id ->
            delay(metaLatency)
            metas[id]
        },
        clock = clock,
        timeSource = scope.testScheduler.timeSource,
        scope = scope.backgroundScope,
    )

    /** Connects [service] with a session that doesn't expire. */
    suspend fun connect(service: TrackerService, session: TrackerSession = TrackerSession("a0", "r0", null, "alex", 1)) {
        hub.accounts.connect(profileId, service, session)
        settle()
    }

    /** The watchlist of [service]'s current binding (a fixture made with `watchlists`). */
    fun watchlist(service: TrackerService): FakeWatchlist = (providers.getValue(service) as FakeListProvider).lists.last()

    /** Reads every connected service's lists' first pages, as the Board does. */
    suspend fun readLists() {
        for (source in hub.accounts.catalogSources(profileId).first()) {
            for (catalog in source.catalogs()) source.page(catalog)
        }
        settle()
    }

    /** The tracker of [service]'s current binding. */
    fun tracker(service: TrackerService): FakeTracker = providers.getValue(service).trackers.last()

    /** The calls [service] got, without their times. */
    fun calls(service: TrackerService): List<String> = log.filter { it.service == service }.map { it.op }

    /** [calls] without the dates replays carry. */
    fun undated(service: TrackerService): List<String> = calls(service).map { it.replace(Regex(" at=\\d+"), "") }

    fun settle() = scope.runCurrent()

    fun advance(duration: Duration) {
        scope.advanceTimeBy(duration)
        scope.runCurrent()
    }

    /** Lets every channel drain, spacing included. */
    fun drain() = advance(10.seconds)

    suspend fun queued() = data.trackerWriteQueue.pending(profileId).map {
        val watched = (it.content as? QueuedTrackerContent.Mark)?.watched == true
        "${it.service.id} ${it.coalesceKey} watched=$watched attempts=${it.attempts}"
    }

    fun close() = data.close()
}

