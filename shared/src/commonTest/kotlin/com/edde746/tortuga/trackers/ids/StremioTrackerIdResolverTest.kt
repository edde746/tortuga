package com.edde746.tortuga.trackers.ids

import com.edde746.tortuga.model.AddonJson
import com.edde746.tortuga.model.ImdbEpisode
import com.edde746.tortuga.model.Link
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.trackers.AnimeIds
import com.edde746.tortuga.trackers.ExternalIds
import com.edde746.tortuga.trackers.ResolvedIds
import com.edde746.tortuga.trackers.TrackerItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Stremio ids resolved against the dataset excerpts in `fixtures/trackers/` (see [AnimeMappingIndexTest] for their
 * source) and against addon metas.
 */
class StremioTrackerIdResolverTest {
    private val mappings = FixtureMappings()
    private val resolver = StremioTrackerIdResolver(mappings)

    private suspend fun resolve(videoId: String, type: String = "series", meta: MetaItem? = null, animeIds: Boolean = true, metaId: String? = null) =
        resolver.resolve(TrackerItem(metaId ?: videoId.metaIdOf(), type, videoId, meta), animeIds)

    @Test
    fun catalogIdsPassThroughAndNeverTouchTheDatasetsWithoutAnAnimeTracker() = runTest {
        assertEquals(ResolvedIds(ExternalIds(imdb = "tt0944947"), season = 1, episode = 2), resolve("tt0944947:1:2", animeIds = false))
        assertEquals(ResolvedIds(ExternalIds(imdb = "tt0111161")), resolve("tt0111161", "movie", animeIds = false))
        assertEquals(ResolvedIds(ExternalIds(tmdb = 1399), season = 3, episode = 10), resolve("tmdb:1399:3:10", animeIds = false))
        // Anime titles too: the IMDb numbering is what Trakt, Simkl and MDBList get.
        assertEquals(ResolvedIds(ExternalIds(imdb = "tt5626028"), season = 2, episode = 5), resolve("tt5626028:2:5", animeIds = false))
        assertEquals(0, mappings.loads)
    }

    @Test
    fun theMetaFillsTheOtherCatalogId() = runTest {
        val thrones = meta("tmdb:1399", imdbId = "tt0944947")
        assertEquals(
            ResolvedIds(ExternalIds(imdb = "tt0944947", tmdb = 1399), season = 1, episode = 2),
            resolve("tmdb:1399:1:2", meta = thrones, animeIds = false),
        )
        // Links: an IMDb title page, and a TMDB page in the item's own namespace only.
        val links = meta(
            "tt0111161", type = "movie",
            links = listOf(
                Link("7.9", "imdb", "https://imdb.com/title/tt0111161"),
                Link("TMDB", "other", "https://www.themoviedb.org/movie/278-the-shawshank-redemption"),
                Link("TMDB", "other", "https://www.themoviedb.org/tv/1399"),
            ),
        )
        assertEquals(ResolvedIds(ExternalIds(imdb = "tt0111161", tmdb = 278)), resolve("tt0111161", "movie", meta = links, animeIds = false))
        val onlyLinks = meta("tmdb:278", type = "movie", links = listOf(Link("8.7", "imdb", "https://www.imdb.com/title/tt0111161/")))
        assertEquals(ResolvedIds(ExternalIds(imdb = "tt0111161", tmdb = 278)), resolve("tmdb:278", "movie", meta = onlyLinks, animeIds = false))
    }

    @Test
    fun anAnimeIdIsItselfAnAnimeIdWithoutTheDatasets() = runTest {
        assertEquals(ResolvedIds(ExternalIds(), AnimeIds(kitsu = 12268), animeEpisode = 1), resolve("kitsu:12268:1", animeIds = false))
        assertEquals(ResolvedIds(ExternalIds(), AnimeIds(mal = 21), animeEpisode = 1100), resolve("mal:21:1100", animeIds = false))
        // With the Kitsu addon's meta, Trakt gets the IMDb episode (the sequel entry's episode 1 is IMDb's S2E1).
        assertEquals(
            ResolvedIds(ExternalIds(imdb = "tt5626028"), AnimeIds(kitsu = 12268), season = 2, episode = 1, animeEpisode = 1),
            resolve("kitsu:12268:1", meta = kitsuMeta(), animeIds = false),
        )
        assertEquals(0, mappings.loads)
    }

    @Test
    fun anAnimeEpisodeIsPlacedOnTheExternalSeasons() = runTest {
        val heroAcademia2 = ExternalIds(imdb = "tt5626028", tmdb = 65930, tvdb = 305074)
        assertEquals(
            ResolvedIds(heroAcademia2, AnimeIds(mal = 33486, anilist = 21856, simkl = 595017, kitsu = 12268, anidb = 12233), 2, 1, animeEpisode = 1),
            resolve("kitsu:12268:1"),
        )
        // One entry over three TVDB seasons (ranges).
        assertEquals(2 to 2, resolve("kitsu:6448:60").episodeAt())
        assertEquals(3 to 12, resolve("anilist:11061:148").episodeAt())
        // A default season, then a range onto the next.
        assertEquals(1 to 3, resolve("kitsu:42438:3").episodeAt())
        assertEquals(2 to 2, resolve("kitsu:42438:16").episodeAt())
        // An explicit pair: UBW's first episode is TVDB's special 1.
        assertEquals(0 to 1, resolve("anidb:9977:1").episodeAt())
        assertEquals(1 to 4, resolve("anidb:9977:5").episodeAt())
        // Absolute numbering with per-season ranges.
        val onePiece = resolve("mal:21:1100")
        assertEquals(22 to 15, onePiece.episodeAt())
        assertEquals(AnimeIds(mal = 21, anilist = 21, simkl = 38636, kitsu = 12, anidb = 69), onePiece?.anime)
        assertEquals(1100, onePiece?.animeEpisode)
    }

    @Test
    fun fribbPlacesTheEpisodeWhereAnimeListsCanNot() = runTest {
        mappings.animeListsAvailable = false
        // The second cour starts 12 episodes into the TVDB season.
        assertEquals(1 to 15, resolve("kitsu:1363:3").episodeAt())
        // Without a season in Fribb (absolute numbering), only the meta can say.
        assertNull(resolve("kitsu:6448:60").episodeAt())
        val hxh = meta("kitsu:6448", videos = listOf(Video("kitsu:6448:60", imdb = ImdbEpisode("tt2098220", 2, 2))))
        assertEquals(2 to 2, resolve("kitsu:6448:60", meta = hxh).episodeAt())
        // An addon's IMDb episode of another title than Fribb's is not used.
        val other = meta("kitsu:6448", videos = listOf(Video("kitsu:6448:60", imdb = ImdbEpisode("tt0000001", 2, 2))))
        assertNull(resolve("kitsu:6448:60", meta = other).episodeAt())
    }

    @Test
    fun anImdbEpisodeFindsTheEntryOfItsSeason() = runTest {
        val s2e5 = resolve("tt5626028:2:5")
        assertEquals(ExternalIds(imdb = "tt5626028", tmdb = 65930, tvdb = 305074), s2e5?.ids)
        assertEquals(33486 to 5, s2e5?.anime?.mal to s2e5?.animeEpisode)
        assertEquals(2 to 5, s2e5.episodeAt(), "Trakt's numbering is the Stremio id's")
        // One entry over several seasons: the episode's place on it.
        val hxh = resolve("tt2098220:2:2")
        assertEquals(11061 to 60, hxh?.anime?.mal to hxh?.animeEpisode)
        assertEquals(21 to 1100, resolve("tt0388629:22:15").let { it?.anime?.mal to it?.animeEpisode })
    }

    @Test
    fun splitCoursInOneSeasonTakeTheEntryTheirOffsetPoints() = runTest {
        // Black Lagoon's TVDB season 1 is two entries; Anime-Lists' default mappings can't tell them apart.
        val first = resolve("tt0962826:1:5")
        assertEquals(889 to 5, first?.anime?.mal to first?.animeEpisode)
        val second = resolve("tt0962826:1:15")
        assertEquals(1519 to 3, second?.anime?.mal to second?.animeEpisode)
        assertEquals(1519 to 1, resolve("tt0962826:1:13").let { it?.anime?.mal to it?.animeEpisode })
    }

    @Test
    fun specialsKeepTheirEpisodeButNoAnimeProgress() = runTest {
        // TVDB's S0E2 is an AniDB special of the 2017 entry: no list entry counts it.
        assertEquals(
            ResolvedIds(ExternalIds(imdb = "tt5626028", tmdb = 65930, tvdb = 305074), season = 0, episode = 2),
            resolve("tt5626028:0:2"),
        )
        // S0E3 is the 2016 OVA's (a regular AniDB episode): its ids, but a special season has no entry numbering.
        val ova = resolve("tt5626028:0:3")
        assertEquals(AnimeIds(simkl = 610915, anidb = 12344), ova?.anime)
        assertNull(ova?.animeEpisode)
    }

    @Test
    fun anAbsolutelyNumberedEntryCountsEarlierSeasonsFromTheMeta() = runTest {
        mappings.animeListsAvailable = false
        val videos = (1..58).map { Video("tt2098220:1:$it", seriesInfo = SeriesInfo(1, it)) } +
            (1..3).map { Video("tt2098220:2:$it", seriesInfo = SeriesInfo(2, it)) } +
            Video("tt2098220:0:1", seriesInfo = SeriesInfo(0, 1))
        val hxh = meta("tt2098220", videos = videos)
        assertEquals(60, resolve("tt2098220:2:2", meta = hxh)?.animeEpisode)
        assertEquals(7, resolve("tt2098220:1:7")?.animeEpisode)
        // Without the meta, a later season can't be counted.
        assertEquals(11061 to null, resolve("tt2098220:2:2").let { it?.anime?.mal to it?.animeEpisode })
    }

    @Test
    fun filmsResolveInTheFilmNamespace() = runTest {
        val yourName = ResolvedIds(
            ExternalIds(imdb = "tt5311514", tmdb = 372058),
            AnimeIds(mal = 32281, anilist = 21519, simkl = 543146, kitsu = 11614, anidb = 11829),
        )
        assertEquals(yourName, resolve("kitsu:11614", "movie"))
        assertEquals(yourName.anime, resolve("tt5311514", "movie")?.anime)
        assertEquals(yourName.anime, resolve("tmdb:372058", "movie")?.anime)
        // The same TMDB number as a series is another title.
        assertEquals(ResolvedIds(ExternalIds(tmdb = 372058)), resolve("tmdb:372058", "series"))
    }

    @Test
    fun aWholeShowGetsAnEntryOnlyWhenItIsOne() = runTest {
        assertEquals(1, resolve("tt0213338")?.anime?.mal)
        val heroAcademia = resolve("tt5626028")
        assertEquals(ExternalIds(imdb = "tt5626028", tmdb = 65930, tvdb = 305074), heroAcademia?.ids)
        assertNull(heroAcademia?.anime)
    }

    @Test
    fun missingDatasetsDegradeToTheIdAndTheMeta() = runTest {
        mappings.fribbAvailable = false
        mappings.animeListsAvailable = false
        assertEquals(ResolvedIds(ExternalIds(imdb = "tt5626028"), season = 2, episode = 5), resolve("tt5626028:2:5"))
        assertEquals(
            ResolvedIds(ExternalIds(imdb = "tt5626028"), AnimeIds(kitsu = 12268), season = 2, episode = 1, animeEpisode = 1),
            resolve("kitsu:12268:1", meta = kitsuMeta()),
        )
    }

    @Test
    fun addonVideoIdsArePlacedByTheirMeta() = runTest {
        val meta = meta("tt0944947", videos = listOf(Video("myaddon:got-1-3", seriesInfo = SeriesInfo(1, 3))))
        assertEquals(
            ResolvedIds(ExternalIds(imdb = "tt0944947"), season = 1, episode = 3),
            resolve("myaddon:got-1-3", meta = meta, metaId = "tt0944947", animeIds = false),
        )
        assertNull(resolve("myaddon:got-1-4", meta = meta, metaId = "tt0944947", animeIds = false))
        assertNull(resolve("yt_id:UC123", metaId = "yt_id:UC123", animeIds = false))
    }

    @Test
    fun anAddonsOwnIdsResolveByTheIdsItsMetaAdds() = runTest {
        val film = meta("myaddon:shawshank", type = "movie", imdbId = "tt0111161")
        assertEquals(
            ResolvedIds(ExternalIds(imdb = "tt0111161")),
            resolve("myaddon:shawshank", "movie", meta = film, metaId = "myaddon:shawshank", animeIds = false),
        )
        val tmdbFilm = meta("myaddon:278", type = "movie", links = listOf(Link("TMDB", "other", "https://www.themoviedb.org/movie/278")))
        assertEquals(ResolvedIds(ExternalIds(tmdb = 278)), resolve("myaddon:278", "movie", meta = tmdbFilm, metaId = "myaddon:278", animeIds = false))
        // An episode: by its season and episode on the title's id, or by the addon's IMDb episode.
        val show = meta(
            "myaddon:got",
            imdbId = "tt0944947",
            videos = listOf(
                Video("myaddon:got-1-3", seriesInfo = SeriesInfo(1, 3)),
                Video("myaddon:got-x", imdb = ImdbEpisode("tt0944947", 2, 4)),
                Video("myaddon:got-y"),
            ),
        )
        assertEquals(
            ResolvedIds(ExternalIds(imdb = "tt0944947"), season = 1, episode = 3),
            resolve("myaddon:got-1-3", meta = show, metaId = "myaddon:got", animeIds = false),
        )
        assertEquals(
            ResolvedIds(ExternalIds(imdb = "tt0944947"), season = 2, episode = 4),
            resolve("myaddon:got-x", meta = show, metaId = "myaddon:got", animeIds = false),
        )
        assertNull(resolve("myaddon:got-y", meta = show, metaId = "myaddon:got", animeIds = false), "an episode nothing places")
        assertNull(resolve("myaddon:other", "movie", meta = meta("myaddon:other", type = "movie"), metaId = "myaddon:other", animeIds = false))
    }

    /** The Kitsu addon's meta, as `https://anime-kitsu.strem.fun/meta/series/kitsu:12268.json` sends it (trimmed). */
    private fun kitsuMeta(): MetaItem {
        val meta = AddonJson.decodeFromString(
            MetaItem.serializer(),
            """
            {"id":"kitsu:12268","type":"series","name":"Boku no Hero Academia 2","imdb_id":"tt5626028","kitsu_id":"12268",
             "links":[{"name":"8.3","category":"imdb","url":"https://kitsu.io/anime/boku-no-hero-academia-2nd-season"}],
             "videos":[
              {"id":"kitsu:12268:1","title":"That's the Idea, Ochako","released":"2017-04-01T08:30:00.000Z","season":1,
               "episode":1,"imdb_id":"tt5626028","imdbSeason":2,"imdbEpisode":1},
              {"id":"kitsu:12268:2","title":"Roaring Sports Festival","released":"2017-04-08T08:30:00.000Z","season":1,
               "episode":2,"imdb_id":"tt5626028","imdbSeason":2,"imdbEpisode":2}]}
            """.trimIndent(),
        )
        assertEquals("tt5626028", meta.preview.imdbId)
        assertEquals(ImdbEpisode("tt5626028", 2, 1), meta.videos.first().imdb)
        // Round trip, as the cache stores it.
        assertEquals(meta, AddonJson.decodeFromString(MetaItem.serializer(), AddonJson.encodeToString(MetaItem.serializer(), meta)))
        return meta
    }

    private fun meta(id: String, type: String = "series", imdbId: String? = null, links: List<Link> = emptyList(), videos: List<Video> = emptyList()) =
        MetaItem(MetaItemPreview(id = id, type = type, links = links, imdbId = imdbId), videos)

    private fun ResolvedIds?.episodeAt(): Pair<Int, Int>? {
        val season = this?.season ?: return null
        val episode = episode ?: return null
        return season to episode
    }

    private fun String.metaIdOf(): String = when {
        startsWith("tt") -> substringBefore(':')
        else -> split(':').take(2).joinToString(":")
    }
}

/** The dataset excerpts as the resolver's source, each switchable off (offline with no copy). */
internal class FixtureMappings : AnimeMappingSource {
    var fribbAvailable = true
    var animeListsAvailable = true
    var loads = 0
        private set

    override suspend fun fribb(): FribbIndex? {
        loads++
        return if (fribbAvailable) ANIME_MAPPINGS_EXCERPT.fribb else null
    }

    override suspend fun animeLists(): AnimeListsIndex? {
        loads++
        return if (animeListsAvailable) ANIME_MAPPINGS_EXCERPT.animeLists else null
    }
}
