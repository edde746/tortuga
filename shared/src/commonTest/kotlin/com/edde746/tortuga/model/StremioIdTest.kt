package com.edde746.tortuga.model

import com.edde746.tortuga.model.StremioId.AnimeList
import kotlin.test.Test
import kotlin.test.assertEquals

/** Stremio ids as Cinemeta, TMDB addons and the anime addons (Kitsu, MAL, AniList, AniDB ids) send them. */
class StremioIdTest {
    private fun imdb(id: String) = checkNotNull(ImdbId.parse(id))

    @Test
    fun parsesEveryShapeTheTrackersTake() {
        val cases = mapOf(
            "tt0944947" to StremioId.Imdb(imdb("tt0944947")),
            "tt0944947:1:2" to StremioId.Imdb(imdb("tt0944947"), 1, 2),
            "tt0944947:0:1" to StremioId.Imdb(imdb("tt0944947"), 0, 1),
            "tt13544716" to StremioId.Imdb(imdb("tt13544716")),
            "tmdb:1399" to StremioId.Tmdb(1399),
            "tmdb:1399:3:10" to StremioId.Tmdb(1399, 3, 10),
            "kitsu:1376" to StremioId.Anime(AnimeList.KITSU, 1376),
            "kitsu:1376:5" to StremioId.Anime(AnimeList.KITSU, 1376, 5),
            "mal:21" to StremioId.Anime(AnimeList.MAL, 21),
            "mal:21:1000" to StremioId.Anime(AnimeList.MAL, 21, 1000),
            "anilist:21:3" to StremioId.Anime(AnimeList.ANILIST, 21, 3),
            "anidb:69:12" to StremioId.Anime(AnimeList.ANIDB, 69, 12),
        )
        for ((id, expected) in cases) assertEquals(expected, StremioId.parse(id), id)
    }

    @Test
    fun rejectsWhatNoTrackerCanPlace() {
        val rejected = listOf(
            "", "tt", "tt12ab", "TT0944947", "tt0944947:1", "tt0944947:1:2:3", "tt0944947:a:1", "tt0944947:1:-2",
            "tmdb:", "tmdb:0", "tmdb:abc", "tmdb:1399:1", "tmdb:1399:1:2:3",
            "kitsu:", "kitsu:0", "kitsu:12:1:2", "kitsu:12:x", "mal:-3",
            "tvdb:121361", "yt_id:UC123", "local:abc", "tt0944947 ", "kitsu:١٢",
            "tt0944947:1:99999999999", "tt12345678901", "tt١٢٣",
        )
        for (id in rejected) assertEquals(null, StremioId.parse(id), id)
    }
}
