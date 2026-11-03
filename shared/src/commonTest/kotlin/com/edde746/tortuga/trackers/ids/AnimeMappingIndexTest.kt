package com.edde746.tortuga.trackers.ids

import com.edde746.tortuga.trackers.ids.PackedList.column
import com.edde746.tortuga.trackers.ids.PackedList.dict
import com.edde746.tortuga.trackers.ids.PackedList.file
import com.edde746.tortuga.trackers.ids.PackedList.intDeltas
import com.edde746.tortuga.trackers.ids.PackedList.ints
import com.edde746.tortuga.trackers.ids.PackedList.strs
import com.edde746.tortuga.trackers.ids.PackedList.table
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two mapping datasets, read from a checked-in anime-mappings packed list,
 * `fixtures/trackers/anime-mappings.excerpt.bin.gz.hex` (`xxd -p -c 15` of the gzipped file), which
 * edde746/anime-mappings' `build.py --check` built from two excerpts of the same entries, verbatim:
 *
 * - 15 rows of Fribb's `anime-list-mini.json` (https://github.com/Fribb/anime-lists, via jsDelivr, fetched
 *   2026-09-29). The repository states no licence.
 * - The Anime-Lists `anime-list.xml` entries with the same AniDB ids (https://github.com/Anime-Lists/anime-lists, same
 *   date; no licence stated either).
 */
class AnimeMappingIndexTest {
    private val fribb = ANIME_MAPPINGS_EXCERPT.fribb
    private val lists = ANIME_MAPPINGS_EXCERPT.animeLists

    @Test
    fun fribbRowsAreFoundByEveryAnimeListId() {
        val bebop = FribbRow(
            anidb = 23, anilist = 1, kitsu = 1, mal = 1, simkl = 37089, imdb = "tt0213338", tmdbTv = 30991, tvdb = 76885,
            tvdbSeason = 1, tmdbSeason = 1, type = "TV",
        )
        assertEquals(15, fribb.size)
        assertEquals(listOf(bebop), fribb.byKitsu(1))
        assertEquals(listOf(bebop), fribb.byMal(1))
        assertEquals(listOf(bebop), fribb.byAnilist(1))
        assertEquals(listOf(bebop), fribb.byAnidb(23))
        // Kitsu ids are an addition; the second My Hero Academia season is its own entry.
        assertEquals(33486, fribb.byKitsu(12268).single().mal)
        assertEquals(emptyList(), fribb.byKitsu(999_999))
    }

    @Test
    fun fribbLookupKeepsFilmsAndSeriesApart() {
        // A film's TMDB id is looked up among films only, a series' among series.
        assertEquals(11829, fribb.lookup(movie = true, tmdb = 372058).single().anidb)
        assertEquals(emptyList(), fribb.lookup(movie = false, tmdb = 372058))
        assertEquals(listOf(3395, 4597, 6645), fribb.lookup(movie = false, tmdb = 12598).map { it.anidb })
        // TVDB ids are series ids: a film never matches on one.
        assertEquals(emptyList(), fribb.lookup(movie = true, tvdb = 305074))
        assertEquals(listOf(11739, 12233, 12344, 13485, 13633), fribb.lookup(movie = false, tvdb = 305074).map { it.anidb })
        // A collection row is found under each of its IMDb ids, and shows the first.
        val railroad = fribb.lookup(movie = true, imdb = "tt0089206").single()
        assertEquals("tt1920940", railroad.imdb)
        assertEquals(37585, railroad.tmdbMovie)
        assertEquals(railroad, fribb.lookup(movie = true, imdb = "tt1920940").single())
        // The split-cour second season carries its offset in the season.
        val barrage = fribb.byKitsu(1363).single()
        assertEquals(1 to 12, barrage.tvdbSeason to barrage.tvdbEpisodeOffset)
    }

    @Test
    fun animeListsMatchesTheEpisodeToItsEntry() {
        // Default season: My Hero Academia S2E5 is the 2017 entry's episode 5.
        assertEquals(
            AnimeEpisodeMatch(12233, 1, 5, AnimeListProvider.TVDB, 2, 5, AnimeListMatchKind.DEFAULT_MAPPING),
            lists.lookupEpisode(tvdbId = 305074, tmdbId = null, season = 2, episode = 5),
        )
        // Ranges: Hunter x Hunter (2011) is one AniDB entry over three TVDB seasons.
        val hxh = lists.lookupEpisode(tvdbId = 252322, tmdbId = null, season = 2, episode = 2)
        assertEquals(8550 to 60, hxh?.anidbId to hxh?.anidbEpisode)
        assertEquals(AnimeListMatchKind.RANGE, hxh?.kind)
        // An explicit pair beats the range: UBW's episode 1 is TVDB's S0E1 (the prologue), 2..13 are S1E1..12.
        val prologue = lists.lookupEpisode(tvdbId = 278626, tmdbId = null, season = 0, episode = 1)
        assertEquals(AnimeListMatchKind.EXPLICIT to 1, prologue?.kind to prologue?.anidbEpisode)
        assertEquals(5, lists.lookupEpisode(tvdbId = 278626, tmdbId = null, season = 1, episode = 4)?.anidbEpisode)
        // TMDB when TVDB doesn't say: One Piece's TMDB season 2 starts at AniDB episode 62.
        assertEquals(70, lists.lookupEpisode(tvdbId = null, tmdbId = 37854, season = 2, episode = 9)?.anidbEpisode)
        // Two cours in one TVDB season, both by default mapping: ambiguous, so no answer.
        assertNull(lists.lookupEpisode(tvdbId = 79604, tmdbId = null, season = 1, episode = 15))
        assertNull(lists.lookupEpisode(tvdbId = 305074, tmdbId = null, season = 2, episode = 0))
    }

    @Test
    fun animeListsPlacesAnAnimeEpisodeOnTheExternalSeasons() {
        val tvdb = AnimeListProvider.TVDB
        // Default season and offset.
        assertEquals(ExternalEpisode(tvdb, 2, 1), lists.externalEpisode(12233, tvdb, 1))
        assertEquals(ExternalEpisode(tvdb, 1, 15), lists.externalEpisode(4597, tvdb, 3))
        // The same on TMDB's seasons, by the entry's TMDB defaults.
        assertEquals(ExternalEpisode(AnimeListProvider.TMDB, 1, 15), lists.externalEpisode(4597, AnimeListProvider.TMDB, 3))
        // Ranges over absolute numbering: Hunter x Hunter episode 60 is S2E2, 148 is S3E12.
        assertEquals(ExternalEpisode(tvdb, 2, 2), lists.externalEpisode(8550, tvdb, 60))
        assertEquals(ExternalEpisode(tvdb, 3, 12), lists.externalEpisode(8550, tvdb, 148))
        // Past the last range of an absolutely numbered entry there is no season to name.
        assertNull(lists.externalEpisode(8550, tvdb, 149))
        // Explicit pairs first, then the range: UBW.
        assertEquals(ExternalEpisode(tvdb, 0, 1), lists.externalEpisode(9977, tvdb, 1))
        assertEquals(ExternalEpisode(tvdb, 1, 4), lists.externalEpisode(9977, tvdb, 5))
        // Default season then a range onto the next season: Great Pretender 3 is S1E3, 16 is S2E2.
        assertEquals(ExternalEpisode(tvdb, 1, 3), lists.externalEpisode(14990, tvdb, 3))
        assertEquals(ExternalEpisode(tvdb, 2, 2), lists.externalEpisode(14990, tvdb, 16))
        // One Piece's TVDB ranges.
        assertEquals(ExternalEpisode(tvdb, 2, 2), lists.externalEpisode(69, tvdb, 10))
        assertEquals(ExternalEpisode(tvdb, 22, 15), lists.externalEpisode(69, tvdb, 1100))
        // Films have no series on either; unknown entries and episode 0 have no answer.
        assertNull(lists.externalEpisode(11829, tvdb, 1))
        assertNull(lists.externalEpisode(11829, AnimeListProvider.TMDB, 1))
        assertNull(lists.externalEpisode(424242, tvdb, 1))
        assertNull(lists.externalEpisode(12233, tvdb, 0))
    }

    @Test
    fun anEpisodeListedAsAbsentHasNoExternalEpisode() {
        // `<anime anidbid="1" tvdbid="100" defaulttvdbseason="1" episodeoffset="2">` with two mappings, and an entry
        // without an AniDB id, which is left out.
        val index = AnimeListsIndex.from(
            PackedTables.read(
                file(
                    table(
                        "anime_lists", 2,
                        column("anidbid", PackedTables.INT_DELTA, intDeltas(1, null)),
                        column("tvdbid", PackedTables.INT, ints(100, 101)),
                        column("tvdbid_text", PackedTables.DICT, dict(null, null)),
                        column("defaulttvdbseason", PackedTables.INT, ints(1, null)),
                        column("defaulttvdbseason_text", PackedTables.DICT, dict(null, null)),
                        column("episodeoffset", PackedTables.INT, ints(2, null)),
                        column("tmdbtv", PackedTables.INT, ints(null, null)),
                        column("tmdbseason", PackedTables.INT, ints(null, null)),
                        column("tmdbseason_text", PackedTables.DICT, dict(null, null)),
                        column("tmdboffset", PackedTables.INT, ints(null, null)),
                    ),
                    table(
                        "anime_lists_mappings", 2,
                        column("anidbid", PackedTables.INT_DELTA, intDeltas(1, 1)),
                        column("anidbseason", PackedTables.INT, ints(1, 1)),
                        column("tvdbseason", PackedTables.INT, ints(1, 2)),
                        column("tmdbseason", PackedTables.INT, ints(null, null)),
                        column("start", PackedTables.INT, ints(null, 10)),
                        column("end", PackedTables.INT, ints(null, 12)),
                        column("offset", PackedTables.INT, ints(null, -9)),
                        column("text", PackedTables.STR, strs(";1-0;2-3+4;", null)),
                    ),
                ),
            ),
        )
        assertEquals(1, index.byAnidb.size)
        val tvdb = AnimeListProvider.TVDB
        assertNull(index.externalEpisode(1, tvdb, 1), "-0: TVDB has no such episode, so the default offset doesn't apply")
        assertEquals(ExternalEpisode(tvdb, 1, 3), index.externalEpisode(1, tvdb, 2))
        assertEquals(ExternalEpisode(tvdb, 1, 5), index.externalEpisode(1, tvdb, 3))
        assertEquals(ExternalEpisode(tvdb, 2, 2), index.externalEpisode(1, tvdb, 11))
        // Forward, one AniDB episode as two TVDB ones.
        assertEquals(2, index.lookupEpisode(tvdbId = 100, tmdbId = null, season = 1, episode = 4)?.anidbEpisode)
    }

    @Test
    fun showsListTheirEntries() {
        // Every entry of the excerpt parsed, names and supplemental info skipped.
        assertEquals(15, lists.byAnidb.size)
        assertTrue(lists.byTvdb.getValue(305074).map { it.anidbId }.containsAll(listOf(11739, 12233, 12344, 13485, 13633)))
        assertEquals(setOf(11739, 12233, 12344, 13485, 13633), lists.anidbIdsForShow(tvdbId = 305074, tmdbId = null))
    }

    /** Fribb's index reads back from its packed form as it was: every lookup answers the same. */
    @Test
    fun fribbsPackedFormReadsBackTheSameIndex() {
        val packed = Buffer().also { fribb.pack(it) }
        val back = FribbIndex.unpack(packed)
        assertEquals(fribb.size, back.size)
        for (mal in listOf(1, 5, 20, 21, 33486, 999_999)) {
            assertEquals(fribb.byMal(mal), back.byMal(mal))
        }
        assertEquals(fribb.lookup(movie = false, imdb = "tt0213338"), back.lookup(movie = false, imdb = "tt0213338"))
        assertEquals(fribb.lookup(movie = false, tvdb = 76885), back.lookup(movie = false, tvdb = 76885))
        val cut = Buffer().also { fribb.pack(it) }.let { whole -> Buffer().apply { write(whole, whole.size - 5) } }
        assertFailsWith<IllegalArgumentException>("a cut file") { FribbIndex.unpack(cut) }
    }

    /** Anime-Lists' index reads back from its packed form as it was: every lookup answers the same. */
    @Test
    fun animeListsPackedFormReadsBackTheSameIndex() {
        val packed = Buffer().also { lists.pack(it) }
        val back = AnimeListsIndex.unpack(packed)
        assertEquals(lists.summary, back.summary)
        for ((tvdb, season, episode) in listOf(Triple(305074, 2, 5), Triple(252322, 2, 2), Triple(278626, 0, 1), Triple(278626, 1, 4), Triple(79604, 1, 15))) {
            assertEquals(lists.lookupEpisode(tvdb, null, season, episode), back.lookupEpisode(tvdb, null, season, episode))
        }
        assertEquals(lists.lookupEpisode(null, 37854, 2, 9), back.lookupEpisode(null, 37854, 2, 9))
        for ((anidb, episode) in listOf(12233 to 1, 4597 to 3, 8550 to 60, 8550 to 149, 9977 to 1, 14990 to 16, 69 to 1100, 11829 to 1)) {
            for (provider in AnimeListProvider.entries) {
                assertEquals(lists.externalEpisode(anidb, provider, episode), back.externalEpisode(anidb, provider, episode))
            }
        }
        assertEquals(lists.anidbIdsForShow(305074, null), back.anidbIdsForShow(305074, null))
        val cut = Buffer().also { lists.pack(it) }.let { whole -> Buffer().apply { write(whole, whole.size - 5) } }
        assertFails("a cut file") { AnimeListsIndex.unpack(cut) }
    }
}
