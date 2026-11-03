package com.edde746.tortuga.kids

import com.edde746.tortuga.trackers.ids.FixtureMappings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A title's suggested age from the age database, by its ids in order: IMDb (its id or the `imdb_id` its addon sent),
 * TMDB and TVDB by its type, and an anime entry's ids from the Fribb mapping (`fixtures/trackers/`: kitsu:1 is Cowboy
 * Bebop, tt0213338, TMDB TV 30991, TVDB 76885; kitsu:11614 the film Your Name, tt5311514, TMDB 372058).
 */
class AgeRatingsTest {
    private val ages = FakeAges(emptyList())
    private val mappings = FixtureMappings()
    private val ratings = AgeRatings(ages, mappings)

    private fun put(table: AgeIdTable, id: Long, age: Int) {
        ages.ages[table to id] = age
    }

    @Test
    fun anImdbIdIsLookedUpByItsNumber() = runTest {
        put(AgeIdTable.IMDB, 133093, 16)
        assertEquals(TitleRating.Rated(16), ratings.rating("movie", "tt0133093", imdbId = null))
        // The addon's imdb_id for a title of its own namespace, and for a TMDB id.
        assertEquals(TitleRating.Rated(16), ratings.rating("movie", "yt_id:abc", imdbId = "tt0133093"))
        assertEquals(TitleRating.Rated(16), ratings.rating("movie", "tmdb:603", imdbId = "tt0133093"))
    }

    @Test
    fun imdbComesBeforeTmdbAndTmdbBeforeNothing() = runTest {
        put(AgeIdTable.IMDB, 133093, 16)
        put(AgeIdTable.TMDB_MOVIE, 603, 12)
        assertEquals(TitleRating.Rated(16), ratings.rating("movie", "tmdb:603", imdbId = "tt0133093"), "the IMDb id first")
        assertEquals(TitleRating.Rated(12), ratings.rating("movie", "tmdb:603", imdbId = "tt9999999"), "an IMDb id the database hasn't: the TMDB id")
        assertEquals(TitleRating.Rated(12), ratings.rating("movie", "tmdb:603", imdbId = null))
    }

    @Test
    fun tmdbAndTvdbIdsAreLookedUpAmongFilmsOrSeriesByType() = runTest {
        put(AgeIdTable.TMDB_MOVIE, 1399, 6)
        put(AgeIdTable.TMDB_TV, 1399, 16)
        put(AgeIdTable.TVDB_SERIES, 121361, 16)
        put(AgeIdTable.TVDB_MOVIE, 121361, 0)
        assertEquals(TitleRating.Rated(16), ratings.rating("series", "tmdb:1399", imdbId = null))
        assertEquals(TitleRating.Rated(6), ratings.rating("movie", "tmdb:1399", imdbId = null))
        assertEquals(TitleRating.Rated(16), ratings.rating("series", "tvdb:121361", imdbId = null))
        assertEquals(TitleRating.Rated(0), ratings.rating("movie", "tvdb:121361", imdbId = null))
        // An episode's id names its series.
        assertEquals(TitleRating.Rated(16), ratings.rating("series", "tmdb:1399:1:2", imdbId = null))
    }

    @Test
    fun animeEntriesGoThroughFribbsIdsInOrder() = runTest {
        put(AgeIdTable.TVDB_SERIES, 76885, 12)
        assertEquals(TitleRating.Rated(12), ratings.rating("series", "kitsu:1", imdbId = null), "by TVDB when the database has neither IMDb nor TMDB")
        put(AgeIdTable.TMDB_TV, 30991, 16)
        assertEquals(TitleRating.Rated(16), ratings.rating("series", "kitsu:1", imdbId = null), "TMDB before TVDB")
        put(AgeIdTable.IMDB, 213338, 6)
        assertEquals(TitleRating.Rated(6), ratings.rating("series", "mal:1", imdbId = null), "IMDb first")

        // A film's TMDB id is among films.
        put(AgeIdTable.TMDB_MOVIE, 372058, 6)
        assertEquals(TitleRating.Rated(6), ratings.rating("movie", "kitsu:11614", imdbId = null))
    }

    @Test
    fun aTitleTheDatabaseHasntIsUnratedAndOneWithoutIdsHasNoId() = runTest {
        assertEquals(TitleRating.Unrated, ratings.rating("movie", "tt0133093", imdbId = null))
        assertEquals(TitleRating.Unrated, ratings.rating("series", "kitsu:1", imdbId = null), "a mapped anime entry the database hasn't")
        assertEquals(TitleRating.NoId, ratings.rating("movie", "yt_id:abc", imdbId = null))
        assertEquals(TitleRating.NoId, ratings.rating("series", "kitsu:99999999", imdbId = null), "an anime entry Fribb doesn't map")
        assertEquals(2, ages.reads, "the titles with ids read the database, those without don't")
    }

    @Test
    fun withoutTheDatabaseOrTheAnimeMappingEveryLookupFails() = runTest {
        put(AgeIdTable.IMDB, 133093, 16)
        ages.offline = true
        assertEquals(TitleRating.Failed, ratings.rating("movie", "tt0133093", imdbId = null))
        ages.offline = false
        mappings.fribbAvailable = false
        assertEquals(TitleRating.Failed, ratings.rating("series", "kitsu:1", imdbId = null))
        assertEquals(TitleRating.Rated(16), ratings.rating("movie", "tt0133093", imdbId = null), "the mapping only matters to anime entries")
    }
}
