package com.edde746.tortuga.trackers.ratings

import com.edde746.tortuga.presentation.detail.DetailRatings
import com.edde746.tortuga.presentation.detail.RatingLogo
import com.edde746.tortuga.trackers.AnimeIds
import com.edde746.tortuga.trackers.ExternalIds
import com.edde746.tortuga.trackers.TrackerService
import com.edde746.tortuga.trackers.anilist.AnilistRatings
import com.edde746.tortuga.trackers.mal.MalRatings
import com.edde746.tortuga.trackers.mdblist.MdblistRatings
import com.edde746.tortuga.trackers.simkl.SimklRatings
import com.edde746.tortuga.trackers.trakt.TraktRatings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Each tracker's score answers, from real responses (trimmed to the fields read):
 * - MDBList: `GET /imdb/movie/tt0111161` as recorded in lzblack/douban-rankings' test fixtures (MIT);
 * - Trakt: docs.trakt.tv's examples for "Get movie ratings" and "ID Lookup";
 * - Simkl: api.simkl.org's OpenAPI examples for `/anime/{id}` (Death Note) and `/redirect`'s `Location`;
 * - MyAnimeList: the API v2 reference's `GET /anime/{id}` example (30230);
 * - AniList: graphql.anilist.co's answer for Death Note (`idMal: 1535`), 2026-10-04.
 * Then the row's merge, order and text.
 */
class TitleRatingsTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun mdblistReadsEverySourceItMapsAndSkipsNulls() {
        val media = obj(
            """{"title":"The Shawshank Redemption","year":1994,"score":89,"ratings":[
            {"source":"imdb","value":9.3,"score":93,"votes":3207270,"url":43},
            {"source":"metacritic","value":82,"score":82,"votes":22,"url":"/the-shawshank-redemption"},
            {"source":"metacriticuser","value":9.3,"score":93,"votes":2198,"url":null},
            {"source":"trakt","value":91,"score":91,"votes":43118,"url":null},
            {"source":"tomatoes","value":89,"score":89,"votes":147,"url":"/m/shawshank_redemption","fresh":1},
            {"source":"popcorn","value":98,"score":98,"votes":53439,"url":"/m/shawshank_redemption"},
            {"source":"tmdb","value":87,"score":87,"votes":30786,"url":null},
            {"source":"letterboxd","value":4.6,"score":92,"votes":2908116,"url":"/film/the-shawshank-redemption/"},
            {"source":"rogerebert","value":3.5,"score":null,"votes":null,"url":"the-shawshank-redemption-1994"},
            {"source":"myanimelist","value":null,"score":null,"votes":null,"url":null}]}""",
        )
        val m = TrackerService.MDBLIST
        assertEquals(
            listOf(
                TitleRating(RatingSource.IMDB, 9.3, 3207270, m),
                TitleRating(RatingSource.METACRITIC, 82.0, 22, m),
                TitleRating(RatingSource.TRAKT, 91.0, 43118, m),
                TitleRating(RatingSource.ROTTEN_TOMATOES_CRITICS, 89.0, 147, m),
                TitleRating(RatingSource.ROTTEN_TOMATOES_AUDIENCE, 98.0, 53439, m),
                TitleRating(RatingSource.TMDB, 87.0, 30786, m),
                TitleRating(RatingSource.LETTERBOXD, 4.6, 2908116, m),
            ),
            MdblistRatings.parse(media),
        )
        assertEquals("/imdb/movie/tt0111161", MdblistRatings.lookupPath(RatingTitle(true, ExternalIds(imdb = "tt0111161", tmdb = 278))))
        assertEquals("/tmdb/show/1396", MdblistRatings.lookupPath(RatingTitle(false, ExternalIds(tmdb = 1396))))
        assertEquals("/mal/any/21", MdblistRatings.lookupPath(RatingTitle(false, ExternalIds(), AnimeIds(mal = 21))))
    }

    @Test
    fun traktIsAPercentageAndLooksUpTmdbIds() {
        val ratings = Json.parseToJsonElement(
            """{"rating":7.33778,"votes":7866,"distribution":{"1":298,"2":46,"3":87,"4":178,"5":446,"6":1167,"7":1855,"8":1543,"9":662,"10":1583}}""",
        )
        val rating = TraktRatings.parse(ratings)!!
        assertEquals(73.3778, rating.value, 1e-9)
        assertEquals(7866L, rating.votes)
        assertNull(TraktRatings.parse(Json.parseToJsonElement("""{"rating":0,"votes":0,"distribution":{}}""")), "no votes yet: no score")
        val lookup = Json.parseToJsonElement(
            """[{"type":"movie","score":null,"movie":{"title":"TRON: Legacy","year":2010,"ids":{"trakt":12601,"slug":"tron-legacy-2010","imdb":"tt1104001","tmdb":20526}}}]""",
        )
        assertEquals("12601", TraktRatings.lookupId(lookup, "movie"))
        assertNull(TraktRatings.lookupId(lookup, "show"))
    }

    @Test
    fun simklResolvesByRedirectAndReadsItsThreeScores() {
        assertEquals("movies" to 53536L, SimklRatings.detailPath("https://simkl.com/movies/53536/terminator-3-rise-of-the-machines"))
        assertEquals("anime" to 40190L, SimklRatings.detailPath("https://simkl.com/anime/40190/death-note"))
        assertNull(SimklRatings.detailPath("https://simkl.com/search/?q=nothing"))
        assertEquals(
            "to=simkl&imdb=tt0877057&tmdb=13916&type=tv&mal=1535&kitsu=1376",
            SimklRatings.redirectQuery(RatingTitle(false, ExternalIds(imdb = "tt0877057", tmdb = 13916), AnimeIds(mal = 1535, kitsu = 1376))),
        )
        val deathNote = obj(
            """{"title":"Death Note","year":2006,"type":"anime","ids":{"simkl":40190,"slug":"death-note","imdb":"tt0877057","mal":"1535"},
            "ratings":{"simkl":{"rating":9.0,"votes":4200},"mal":{"rating":8.6,"votes":3700000},"imdb":{"rating":9.0,"votes":410000}}}""",
        )
        val s = TrackerService.SIMKL
        assertEquals(
            listOf(
                TitleRating(RatingSource.SIMKL, 9.0, 4200, s),
                TitleRating(RatingSource.IMDB, 9.0, 410000, s),
                TitleRating(RatingSource.MYANIMELIST, 8.6, 3700000, s),
            ),
            SimklRatings.parse(deathNote),
        )
    }

    @Test
    fun animeListsReadMeanAndAverage() {
        val mal = obj(
            """{"id":30230,"title":"Diamond no Ace: Second Season","main_picture":{"medium":"https://myanimelist.cdn-dena.com/images/anime/9/74398.jpg"},
            "mean":8.42,"num_scoring_users":29027}""",
        )
        assertEquals(TitleRating(RatingSource.MYANIMELIST, 8.42, 29027, TrackerService.MYANIMELIST), MalRatings.parse(mal))
        assertNull(MalRatings.parse(obj("""{"id":1,"title":"Not yet aired"}""")))
        assertEquals(
            TitleRating(RatingSource.ANILIST, 84.0, null, TrackerService.ANILIST),
            AnilistRatings.parse(obj("""{"Media":{"id":1535,"idMal":1535,"averageScore":84}}""")),
        )
        assertNull(AnilistRatings.parse(obj("""{"Media":null}""")))
    }

    @Test
    fun theRowKeepsOneScorePerSourceInOrder() {
        val addonImdb = TitleRating(RatingSource.IMDB, 9.2, null, origin = null)
        val merged = TitleRatings.merge(
            listOf(
                TitleRating(RatingSource.SIMKL, 8.9, 100, TrackerService.SIMKL),
                addonImdb,
                TitleRating(RatingSource.IMDB, 9.3, 2, TrackerService.SIMKL),
                TitleRating(RatingSource.IMDB, 9.3, 1, TrackerService.MDBLIST),
                TitleRating(RatingSource.TRAKT, 91.0, 1, TrackerService.MDBLIST),
                TitleRating(RatingSource.TRAKT, 90.5, 2, TrackerService.TRAKT),
                TitleRating(RatingSource.MYANIMELIST, 8.6, 1, TrackerService.SIMKL),
                TitleRating(RatingSource.MYANIMELIST, 8.62, 2, TrackerService.MYANIMELIST),
                TitleRating(RatingSource.ROTTEN_TOMATOES_AUDIENCE, 98.0, 1, TrackerService.MDBLIST),
                TitleRating(RatingSource.ROTTEN_TOMATOES_CRITICS, 59.0, 1, TrackerService.MDBLIST),
            ),
        )
        assertEquals(
            listOf(
                RatingSource.ROTTEN_TOMATOES_CRITICS to TrackerService.MDBLIST,
                RatingSource.ROTTEN_TOMATOES_AUDIENCE to TrackerService.MDBLIST,
                RatingSource.IMDB to TrackerService.MDBLIST,
                RatingSource.TRAKT to TrackerService.TRAKT,
                RatingSource.SIMKL to TrackerService.SIMKL,
                RatingSource.MYANIMELIST to TrackerService.MYANIMELIST,
            ),
            merged.map { it.source to it.origin },
            "own service, then MDBList, then Simkl, the addon last",
        )
        assertEquals(listOf(addonImdb), TitleRatings.merge(listOf(addonImdb)))

        val row = merged.map { DetailRatings.of(it, imdbLink = "link:0") }
        assertEquals(listOf("59%", "98%", "9.3", "91%", "8.9", "8.62"), row.map { it.text })
        assertEquals(
            listOf(RatingLogo.RT_ROTTEN, RatingLogo.RT_UPRIGHT, RatingLogo.IMDB, RatingLogo.TRAKT, RatingLogo.SIMKL, RatingLogo.MYANIMELIST),
            row.map { it.logo },
        )
        assertEquals("Rotten Tomatoes critics 59 percent", row[0].label)
        assertEquals("IMDb 9.3", row[2].label)
        assertEquals(listOf(null, null, "link:0", null, null, null), row.map { it.linkId })
        assertEquals("82", DetailRatings.text(TitleRating(RatingSource.METACRITIC, 82.0)))
        assertEquals(RatingLogo.LETTERBOXD, DetailRatings.logo(TitleRating(RatingSource.LETTERBOXD, 4.6)))
        assertEquals("Metacritic 82", DetailRatings.of(TitleRating(RatingSource.METACRITIC, 82.0), imdbLink = null).label)
        assertEquals("8.0", DetailRatings.text(TitleRating(RatingSource.IMDB, 8.0)))
    }
}
