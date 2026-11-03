package com.edde746.tortuga.trackers

import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.trackers.TrackerService.ANILIST
import com.edde746.tortuga.trackers.TrackerService.MDBLIST
import com.edde746.tortuga.trackers.TrackerService.MYANIMELIST
import com.edde746.tortuga.trackers.TrackerService.SIMKL
import com.edde746.tortuga.trackers.TrackerService.TRAKT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** The user's own rating of a title: one score to every tracker that rates it, read back per tracker. */
class TrackerRatingTest {
    private val fixtures = mutableListOf<TrackerFixture>()

    @AfterTest
    fun close() = fixtures.forEach { it.close() }

    private suspend fun TestScope.fixture(vararg services: TrackerService): TrackerFixture {
        val f = TrackerFixture(this).also { fixtures += it }
        for (service in services) f.connect(service)
        f.log.clear()
        return f
    }

    /**
     * Every list [flow] showed while the channels drained, each line `service score state`, in the services' order;
     * with [following], also every list it shows later in the test, as a page still open would see them.
     */
    private fun TrackerFixture.shown(flow: Flow<List<UserScore>>, following: Boolean = false): List<List<String>> {
        val seen = mutableListOf<List<String>>()
        val collecting = scope.backgroundScope.launch {
            flow.collect { lines -> seen += lines.sortedBy { it.service.ordinal }.map { "${it.service.id} ${it.score} ${it.state}" } }
        }
        drain()
        if (!following) collecting.cancel()
        return seen
    }

    private fun TrackerFixture.rated(meta: MetaItem, score: Int?): List<String> = shown(coordinator.rate(meta, score)).last()

    @Test
    fun aScoreGoesToEveryTrackerThatRatesTheTitle() = runTest {
        val f = fixture(*TrackerService.entries.toTypedArray())
        f.hub.accounts.setContentFilter(f.profileId, MDBLIST, TrackerContentType.MOVIE, on = false)
        f.settle()

        assertEquals(listOf("trakt 8 SAVED", "simkl 8 SAVED"), f.rated(movieMeta(), 8), "MDBList takes no movies; an anime list can't name a title without anime ids")
        assertEquals(listOf("rate movie imdb=tt0111161 8"), f.calls(TRAKT))
        assertEquals(listOf("rate movie imdb=tt0111161 8"), f.calls(SIMKL))
        for (service in listOf(MDBLIST, MYANIMELIST, ANILIST)) assertEquals(emptyList(), f.calls(service), service.id)

        // A show is rated as a show; an anime film by the anime lists too, by their entries.
        f.log.clear()
        assertEquals(listOf("trakt 7 SAVED", "simkl 7 SAVED", "mdblist 7 SAVED"), f.rated(seriesMeta("tt0903747", 3), 7))
        assertEquals(listOf("rate show imdb=tt0903747 7"), f.calls(TRAKT))
        f.resolver.animeTt["tt0245429"] = 199
        f.log.clear()
        assertEquals(
            listOf("trakt 9 SAVED", "simkl 9 SAVED", "mdblist 9 SAVED", "mal 9 SAVED", "anilist 9 SAVED"),
            f.rated(movieMeta("tt0245429"), 9),
            "an anime film is anime, which MDBList takes",
        )
        assertEquals(listOf("rate movie 199 9"), f.calls(MYANIMELIST))

        // A channel: no service rates one.
        f.log.clear()
        assertEquals(emptyList(), f.rated(MetaItem(MetaItemPreview(id = "tt0000001", type = "channel", name = "A channel")), 5))
        assertEquals(emptyList(), f.log)
    }

    @Test
    fun eachTrackerAnswersForItselfAndOneThatFailsStays() = runTest {
        val f = fixture(TRAKT, SIMKL, MDBLIST, MYANIMELIST)
        f.tracker(TRAKT).scores["movie imdb=tt0111161"] = 8
        f.tracker(SIMKL).scores["movie imdb=tt0111161"] = 7
        f.tracker(MDBLIST).failNext("rating", TrackerException.Api(MDBLIST, 503))

        val shown = f.shown(f.coordinator.userScores(movieMeta()))
        assertEquals(listOf("trakt null READING", "simkl null READING", "mdblist null READING"), shown.first(), "listed before any answers")
        assertEquals(listOf("trakt 8 READ", "simkl 7 READ", "mdblist null UNREADABLE"), shown.last(), "MyAnimeList can't name the title")
        assertEquals(emptyList(), f.calls(MYANIMELIST))
    }

    @Test
    fun aTrackerThatFailsGetsTheScoreLaterAndTheOthersKeepIt() = runTest {
        val f = fixture(TRAKT, SIMKL, MDBLIST)
        f.tracker(SIMKL).failNext("rate", TrackerException.Api(SIMKL, 503))
        assertEquals(listOf("trakt 8 SAVED", "simkl 8 QUEUED", "mdblist 8 SAVED"), f.rated(movieMeta(), 8))
        assertEquals(listOf("simkl simkl|rating|movie|imdb=tt0111161 watched=false attempts=0"), f.queued())

        // Read before the retry: Simkl's line is the score waiting for it, and Simkl isn't asked.
        f.log.clear()
        assertEquals(listOf("trakt 8 READ", "simkl 8 QUEUED", "mdblist 8 READ"), f.shown(f.coordinator.userScores(movieMeta())).last())
        assertEquals(emptyList(), f.calls(SIMKL))

        f.coordinator.replayQueuedWrites()
        f.drain()
        assertEquals(listOf("rate movie imdb=tt0111161 8"), f.calls(SIMKL))
        assertEquals(8, f.tracker(SIMKL).scores["movie imdb=tt0111161"])
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun aQueuedLineShowsWhatTheTrackerHoldsOnceTheRetrySendsIt() = runTest {
        val f = fixture(TRAKT, SIMKL, MDBLIST)
        f.tracker(SIMKL).failNext("rate", TrackerException.Api(SIMKL, 503))
        val rating = f.shown(f.coordinator.rate(movieMeta(), 8), following = true)
        assertEquals(listOf("trakt 8 SAVED", "simkl 8 QUEUED", "mdblist 8 SAVED"), rating.last())
        val read = f.shown(f.coordinator.userScores(movieMeta()), following = true)
        assertEquals(listOf("trakt 8 READ", "simkl 8 QUEUED", "mdblist 8 READ"), read.last())

        // The page that rated and one opened later both learn of the retry: Simkl is read again.
        f.coordinator.replayQueuedWrites()
        f.drain()
        assertEquals(listOf("trakt 8 SAVED", "simkl 8 READ", "mdblist 8 SAVED"), rating.last())
        assertEquals(listOf("trakt 8 READ", "simkl 8 READ", "mdblist 8 READ"), read.last())
    }

    @Test
    fun duringARateLimitTheLatestIntentWaitsAndIsTheOneSent() = runTest {
        val f = fixture(TRAKT)
        f.tracker(TRAKT).failNext("rate", TrackerException.RateLimited(TRAKT, 60))
        assertEquals(listOf("trakt 8 QUEUED"), f.rated(movieMeta(), 8))
        // Within the minute Trakt asked for: nothing is sent, and each intent replaces the one queued.
        assertEquals(listOf("trakt 9 QUEUED"), f.rated(movieMeta(), 9))
        assertEquals(listOf("trakt null QUEUED"), f.rated(movieMeta(), null))
        assertEquals(listOf("rate movie imdb=tt0111161 8"), f.calls(TRAKT))
        assertEquals(listOf("trakt trakt|rating|movie|imdb=tt0111161 watched=false attempts=0"), f.queued())

        f.advance(60.seconds)
        f.coordinator.replayQueuedWrites()
        f.drain()
        assertEquals(listOf("rate movie imdb=tt0111161 8", "unrate movie imdb=tt0111161"), f.calls(TRAKT))
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun aScoreThatGoesThroughDropsAnOlderOneStillQueued() = runTest {
        val f = fixture(SIMKL)
        f.tracker(SIMKL).failNext("rate", TrackerException.Api(SIMKL, 503))
        f.rated(movieMeta(), 8)
        assertEquals(listOf("simkl 9 SAVED"), f.rated(movieMeta(), 9))
        assertEquals(emptyList(), f.queued())

        f.coordinator.replayQueuedWrites()
        f.drain()
        assertEquals(listOf("rate movie imdb=tt0111161 8", "rate movie imdb=tt0111161 9"), f.calls(SIMKL), "the 8 never replays over the 9")
    }
}
