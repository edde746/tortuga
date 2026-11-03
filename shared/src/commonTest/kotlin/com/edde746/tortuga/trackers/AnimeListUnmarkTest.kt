package com.edde746.tortuga.trackers

import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.trackers.TrackerService.MYANIMELIST
import com.edde746.tortuga.trackers.client.Answer
import com.edde746.tortuga.trackers.client.ScriptedService
import com.edde746.tortuga.trackers.client.json
import com.edde746.tortuga.trackers.ids.AnimeListsIndex
import com.edde746.tortuga.trackers.ids.AnimeMappingSource
import com.edde746.tortuga.trackers.ids.FixtureMappings
import com.edde746.tortuga.trackers.ids.FribbIndex
import com.edde746.tortuga.trackers.ids.FribbRow
import com.edde746.tortuga.trackers.ids.StremioTrackerIdResolver
import com.edde746.tortuga.trackers.mal.MalProvider
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A season or show unmarked in Detail, as MyAnimeList's list gets it: through the
 * coordinator, the id resolver on the dataset excerpts (`fixtures/trackers/`) and MyAnimeList's client, to a fake of
 * the user's list. An entry spanning several seasons keeps the progress before the season unmarked, an entry that is
 * the season goes back to 0, and an entry the mappings can't number is left as it is. A mark whose entry can't be
 * read is queued, never written blind or counted done.
 */
class AnimeListUnmarkTest {
    private val fixtures = mutableListOf<TrackerFixture>()

    @AfterTest
    fun close() = fixtures.forEach { it.close() }

    /** Hunter x Hunter (2011) on Cinemeta: one list entry of 148 episodes over three seasons. */
    private val hunterXHunter = show("tt2098220", 58, 78, 12)

    /** My Hero Academia on Cinemeta: a list entry per season. */
    private val heroAcademia = show("tt5626028", 13, 25, 25)

    private suspend fun TestScope.connected(list: MalList, mappings: AnimeMappingSource = FixtureMappings()): TrackerFixture {
        val f = TrackerFixture(
            this,
            services = emptyList(),
            realProviders = listOf(MalProvider(list.service.http(MYANIMELIST), "client-id") { 0 }),
            idResolver = StremioTrackerIdResolver(mappings),
        ).also { fixtures += it }
        f.connect(MYANIMELIST)
        return f
    }

    private fun TrackerFixture.unmark(meta: MetaItem, season: Int) {
        coordinator.marks.onMarked(WatchedMark.Season(meta, season, watched = false))
        drain()
    }

    @Test
    fun anEntrySpanningSeveralSeasonsKeepsTheProgressBeforeTheSeasonUnmarked() = runTest {
        val list = MalList(this, mutableMapOf(HUNTER_X_HUNTER to 100))
        val f = connected(list)
        // Season 3 is the entry's episodes 137-148, which it hasn't reached: nothing to undo.
        f.unmark(hunterXHunter, 3)
        assertEquals(emptyList(), list.writes)
        // Season 2 is 59-136: season 1's 58 stay.
        f.unmark(hunterXHunter, 2)
        assertEquals(listOf("$HUNTER_X_HUNTER=58"), list.writes)
    }

    @Test
    fun anEntryWithoutSeasonMappingIsNumberedByTheMetasEarlierSeasons() = runTest {
        // Without Anime-Lists, Fribb's row (no season) is all there is: the meta counts season 1's episodes.
        val list = MalList(this, mutableMapOf(HUNTER_X_HUNTER to 100))
        val f = connected(list, FixtureMappings().apply { animeListsAvailable = false })
        f.unmark(hunterXHunter, 2)
        assertEquals(listOf("$HUNTER_X_HUNTER=58"), list.writes)
    }

    @Test
    fun anEntryThatIsTheSeasonGoesBackToZeroAndNoOtherEntryMoves() = runTest {
        val list = MalList(this, mutableMapOf(HERO_ACADEMIA_1 to 13, HERO_ACADEMIA_2 to 10, HERO_ACADEMIA_3 to 3))
        val f = connected(list)
        f.unmark(heroAcademia, 2)
        assertEquals(listOf("$HERO_ACADEMIA_2=0"), list.writes)
    }

    @Test
    fun anEntryTheMappingsCanNotNumberIsLeftAsItIs() = runTest {
        // Two entries Fribb maps to one IMDb title and neither to a season: which part of an entry a season is, is unknown.
        val mappings = InlineMappings(
            FribbRow(type = "TV", anidb = 90001, anilist = 90001, imdb = "tt9000001", mal = 90001, tmdbTv = 900001, tvdb = 900001),
            FribbRow(type = "TV", anidb = 90002, anilist = 90002, imdb = "tt9000001", mal = 90002, tmdbTv = 900001, tvdb = 900001),
        )
        val twoEntries = show("tt9000001", 12, 12)
        val list = MalList(this, mutableMapOf(90001 to 20, 90002 to 5))
        val f = connected(list, mappings)
        f.unmark(twoEntries, 2)
        assertEquals(emptyList(), list.writes)

        // The whole show unmarked covers every episode it has on the entry, wherever they lie.
        f.coordinator.marks.onMarked(WatchedMark.Title(twoEntries.id, twoEntries.type, twoEntries, watched = false))
        f.drain()
        assertEquals(listOf("90001=0"), list.writes)
    }

    @Test
    fun aQueuedSeasonUnmarkIsReplayedFromTheSameEpisode() = runTest {
        val list = MalList(this, mutableMapOf(HUNTER_X_HUNTER to 100))
        val f = connected(list)
        list.failNextWrite = true
        f.unmark(hunterXHunter, 2)
        assertEquals(1, f.queued().size)
        f.coordinator.replayQueuedWrites()
        f.drain()
        assertEquals(listOf("$HUNTER_X_HUNTER=58"), list.writes)
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun aSeasonUnmarkWhoseTokenTheSecretStoreCanNotReadIsQueuedNotDropped() = runTest {
        val list = MalList(this, mutableMapOf(HUNTER_X_HUNTER to 100))
        val f = connected(list)
        f.store.failReads = true
        f.unmark(hunterXHunter, 2)
        assertEquals(1, f.queued().size)

        f.store.failReads = false
        f.coordinator.replayQueuedWrites()
        f.drain()
        assertEquals(listOf("$HUNTER_X_HUNTER=58"), list.writes)
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun aSeasonUnmarkDuringARateLimitIsQueuedAndWaitsTheLimitOut() = runTest {
        val list = MalList(this, mutableMapOf(HUNTER_X_HUNTER to 100))
        val f = connected(list)
        list.failNextRead = { Answer(429, headers = mapOf("Retry-After" to "30")) }
        f.unmark(hunterXHunter, 2)
        assertEquals(emptyList(), list.writes)
        assertEquals(listOf("mal mal|series|$HUNTER_X_HUNTER watched=false attempts=0"), f.queued())

        // Within the 30 s MyAnimeList asked for, a replay sends nothing.
        val asked = list.service.requests.size
        f.coordinator.replayQueuedWrites()
        f.drain()
        assertEquals(asked, list.service.requests.size)

        f.advance(30.seconds)
        f.coordinator.replayQueuedWrites()
        f.drain()
        assertEquals(listOf("$HUNTER_X_HUNTER=58"), list.writes)
        assertEquals(emptyList(), f.queued())
    }

    @Test
    fun aSeasonUnmarkWhoseEntryAnswersAServerErrorOrNothingIsQueued() = runTest {
        val failures = listOf<() -> Answer>({ Answer(503) }, { throw IOException("connection reset") })
        for (failure in failures) {
            val list = MalList(this, mutableMapOf(HUNTER_X_HUNTER to 100))
            val f = connected(list)
            list.failNextRead = failure
            f.unmark(hunterXHunter, 2)
            assertEquals(emptyList(), list.writes)
            // Neither says anything about the write: it keeps its attempts.
            assertEquals(listOf("mal mal|series|$HUNTER_X_HUNTER watched=false attempts=0"), f.queued())

            f.coordinator.replayQueuedWrites()
            f.drain()
            assertEquals(listOf("$HUNTER_X_HUNTER=58"), list.writes)
            assertEquals(emptyList(), f.queued())
        }
    }

    @Test
    fun anEpisodeMarkedOnACompletedEntryThatCanNotBeReadIsReplayedAsARewatch() = runTest {
        val list = MalList(this, mutableMapOf(HERO_ACADEMIA_2 to 25), completed = setOf(HERO_ACADEMIA_2))
        val f = connected(list)
        list.failNextRead = { Answer(503) }
        f.coordinator.marks.onMarked(WatchedMark.Video(heroAcademia, "tt5626028:2:3", watched = true))
        f.drain()
        // Written blind it would be "watching", taking the show off the completed list.
        assertEquals(emptyList(), list.writes)
        assertEquals(1, f.queued().size)

        f.coordinator.replayQueuedWrites()
        f.drain()
        assertEquals(listOf("$HERO_ACADEMIA_2=3 rewatching"), list.writes)
        assertEquals(emptyList(), f.queued())
    }

    private fun show(id: String, vararg episodesPerSeason: Int) = MetaItem(
        MetaItemPreview(id = id, type = "series", name = "A show"),
        videos = episodesPerSeason.withIndex().flatMap { (index, count) ->
            (1..count).map { episode -> Video(id = "$id:${index + 1}:$episode", seriesInfo = SeriesInfo(index + 1, episode)) }
        },
    )

    private companion object {
        /** MyAnimeList ids of the fixtures' entries. */
        const val HUNTER_X_HUNTER = 11061
        const val HERO_ACADEMIA_1 = 31964
        const val HERO_ACADEMIA_2 = 33486
        const val HERO_ACADEMIA_3 = 36456
    }
}

/**
 * The user's MyAnimeList list as its API answers it (`GET /anime/{id}?fields=…`, `PUT /anime/{id}/my_list_status`):
 * [progress] per entry on it, the [completed] entries answering as completed, each write it took in [writes] as
 * `id=episodes` (` rewatching` added for a rewatch's). [failNextRead] answers the next read in the entry's place, or
 * throws for a transport failure; [failNextWrite] answers the next write 503.
 */
private class MalList(scope: TestScope, private val progress: MutableMap<Int, Int>, private val completed: Set<Int> = emptySet()) {
    val writes = mutableListOf<String>()
    var failNextRead: (() -> Answer)? = null
    var failNextWrite = false

    val service = ScriptedService(scope) { request ->
        val id = request.path.removePrefix("/v2/anime/").substringBefore('/').toInt()
        val episodes = progress[id] ?: return@ScriptedService Answer(404)
        when (request.method) {
            "GET" -> failNextRead?.let { failure ->
                failNextRead = null
                failure()
            } ?: json("""{"id":$id,"my_list_status":{"status":"${if (id in completed) "completed" else "watching"}","num_episodes_watched":$episodes,"is_rewatching":false}}""")
            "PUT" -> if (failNextWrite) {
                failNextWrite = false
                Answer(503)
            } else {
                val form = request.form()
                val written = form.getValue("num_watched_episodes").toInt()
                progress[id] = written
                writes += "$id=$written" + if (form["is_rewatching"] == "true") " rewatching" else ""
                json("""{"status":"watching","num_episodes_watched":$written,"is_rewatching":false}""")
            }
            else -> error("unexpected $request")
        }
    }
}

/** Fribb rows given inline, which Anime-Lists has no entry for. */
private class InlineMappings(vararg rows: FribbRow) : AnimeMappingSource {
    private val fribb = FribbIndex.Builder().apply { rows.forEach { add(it) } }.build()

    override suspend fun fribb(): FribbIndex = fribb

    override suspend fun animeLists(): AnimeListsIndex? = null
}
