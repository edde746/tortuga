package com.edde746.tortuga.trackers.ids

import com.edde746.tortuga.model.ImdbId
import com.edde746.tortuga.model.StremioId
import com.edde746.tortuga.model.videoIsTitle
import com.edde746.tortuga.trackers.AnimeIds
import com.edde746.tortuga.trackers.ExternalIds
import com.edde746.tortuga.trackers.ResolvedIds
import com.edde746.tortuga.trackers.TrackerIdResolver
import com.edde746.tortuga.trackers.TrackerItem

/**
 * The [TrackerIdResolver] for Stremio ids over the Fribb and Anime-Lists mappings:
 *
 * - **IMDb and TMDB ids** (`tt…[:s:e]`, `tmdb:…[:s:e]`) go to Trakt, Simkl and MDBList unchanged, season and episode
 *   included; the meta adds the other catalog id where it names one. With anime ids wanted, Fribb finds the anime
 *   entries of that title and Anime-Lists picks the one the episode belongs to (split-cour seasons). The episode's
 *   number on that entry is the Anime-Lists match, or the episode less the entry's offset in its season, or (a single
 *   entry numbered absolutely across seasons) its place counted over the meta's earlier seasons.
 * - **Anime ids** (`kitsu:`, `mal:`, `anilist:`, `anidb:` with the entry's own episode number) are anime ids
 *   themselves. With anime ids wanted, Fribb adds the other lists' ids and the title's IMDb/TMDB/TVDB ids, and
 *   Anime-Lists (or, where it has no entry, Fribb's season and offset) places the episode on TVDB's seasons.
 * - **The meta** fills what is still missing, before giving up: `imdb_id`, IMDb and TMDB links, and the per-video IMDb
 *   episode that anime addons add. Without anime ids wanted, this is the only way a `kitsu:` episode reaches Trakt,
 *   and for an addon's own ids the only way anything does.
 *
 * Datasets are only touched when [resolve] is asked for anime ids, so a profile without an anime tracker never
 * downloads them. A dataset that can't be had (offline, no copy yet) is logged by [AnimeMappings] and the item
 * resolves without it; this resolver doesn't throw.
 */
class StremioTrackerIdResolver internal constructor(private val mappings: AnimeMappingSource) : TrackerIdResolver {
    constructor(mappings: AnimeMappings) : this(mappings.source)

    override suspend fun resolve(item: TrackerItem, animeIds: Boolean): ResolvedIds? {
        val meta = item.meta?.let { MetaIds.of(it, item.videoId) }
        val movie = item.type == MOVIE
        val id = StremioId.ofVideo(item.stremioId, item.videoId, item.meta) ?: meta?.let { videoOf(item, it, movie) } ?: return null
        val resolved = when (id) {
            is StremioId.Catalog -> catalog(id, movie, meta, animeIds)
            is StremioId.Anime -> anime(id, movie, meta, animeIds)
        }
        return resolved.takeUnless { it.ids.isEmpty && it.anime == null }
    }

    /**
     * The video [item] plays by the ids its [meta] adds, where its own ids don't say (an addon's own namespace): the
     * title's IMDb id, else its TMDB id, for the title itself; for an episode, the addon's IMDb episode, else the
     * video's season and episode on that id. Null when the meta names neither, or not the episode's place.
     */
    private fun videoOf(item: TrackerItem, meta: MetaIds, movie: Boolean): StremioId? {
        val title = meta.imdb?.let(ImdbId::parse)?.let { StremioId.Imdb(it) } ?: meta.tmdb(movie)?.let { StremioId.Tmdb(it) }
        if (videoIsTitle(item.metaId, item.videoId)) return title
        meta.video?.let { video -> ImdbId.parse(video.id)?.let { return StremioId.Imdb(it, video.season, video.episode) } }
        return StremioId.ofVideo(title ?: return null, item.videoId, item.meta)
    }

    private suspend fun catalog(id: StremioId.Catalog, movie: Boolean, meta: MetaIds?, animeIds: Boolean): ResolvedIds {
        var ids = when (id) {
            is StremioId.Imdb -> ExternalIds(imdb = id.id.value)
            is StremioId.Tmdb -> ExternalIds(tmdb = id.id)
        }
        if (meta != null) ids = ids.copy(imdb = ids.imdb ?: meta.imdb, tmdb = ids.tmdb ?: meta.tmdb(movie))
        val season = id.season
        val episode = id.episode
        val plain = ResolvedIds(ids, season = season, episode = episode)
        if (!animeIds) return plain

        val fribb = mappings.fribb() ?: return plain
        val rows = fribb.lookup(movie = movie, tmdb = ids.tmdb, imdb = ids.imdb)
        if (rows.isEmpty()) return plain
        // The title's other catalog ids, where every candidate entry names the same one.
        ids = ids.copy(
            tmdb = ids.tmdb ?: agreed(rows) { if (movie) it.tmdbMovie else it.tmdbTv },
            tvdb = ids.tvdb ?: if (movie) null else agreed(rows) { it.tvdb },
        )

        if (movie) return ResolvedIds(ids, anime = pickMovieRow(rows)?.animeIds())

        val lists = mappings.animeLists()
        if (season == null || episode == null) {
            // A whole show: an anime entry only when the show is one.
            val entries = lists?.anidbIdsForShow(ids.tvdb, ids.tmdb).orEmpty()
            val row = when (entries.size) {
                0 -> pickShowRow(rows, season = null, episode = null, match = null)
                1 -> rows.firstOrNull { it.anidb == entries.single() }
                else -> null
            }
            return ResolvedIds(ids, anime = row?.animeIds())
        }

        val match = lists?.lookupEpisode(ids.tvdb, ids.tmdb, season, episode)
        // An AniDB special (anidbseason 0) is numbered beside its entry's regular episodes: written as progress it
        // would rewind or complete the series. Trakt, Simkl and MDBList still get the episode.
        if (match != null && match.anidbSeason == 0) return ResolvedIds(ids, season = season, episode = episode)
        val row = pickShowRow(rows, season, episode, match)
        return ResolvedIds(
            ids = ids,
            anime = row?.animeIds(),
            season = season,
            episode = episode,
            animeEpisode = row?.let { entryEpisode(it, rows, season, episode, match, meta) },
        )
    }

    private suspend fun anime(id: StremioId.Anime, movie: Boolean, meta: MetaIds?, animeIds: Boolean): ResolvedIds {
        var anime = when (id.list) {
            StremioId.AnimeList.KITSU -> AnimeIds(kitsu = id.id)
            StremioId.AnimeList.MAL -> AnimeIds(mal = id.id)
            StremioId.AnimeList.ANILIST -> AnimeIds(anilist = id.id)
            StremioId.AnimeList.ANIDB -> AnimeIds(anidb = id.id)
        }
        val entryEpisode = if (movie) null else id.episode?.takeIf { it > 0 }
        var ids = ExternalIds()
        var season: Int? = null
        var episode: Int? = null

        val row = if (animeIds) mappings.fribb()?.byEntry(id)?.firstOrNull() else null
        if (row != null) {
            anime = AnimeIds(
                mal = anime.mal ?: row.mal,
                anilist = anime.anilist ?: row.anilist,
                simkl = row.simkl,
                kitsu = anime.kitsu ?: row.kitsu,
                anidb = anime.anidb ?: row.anidb,
            )
            ids = ExternalIds(imdb = row.imdb, tmdb = if (movie) row.tmdbMovie else row.tmdbTv, tvdb = if (movie) null else row.tvdb)
            if (entryEpisode != null) {
                externalEpisode(row, entryEpisode)?.let { (s, e) ->
                    season = s
                    episode = e
                }
            }
        }

        if (meta != null) {
            ids = ids.copy(imdb = ids.imdb ?: meta.imdb, tmdb = ids.tmdb ?: meta.tmdb(movie))
            val video = meta.video
            // The addon's IMDb episode, when it is about the same IMDb title as the ids.
            if (entryEpisode != null && season == null && video != null && (ids.imdb == null || ids.imdb == video.id)) {
                ids = ids.copy(imdb = video.id)
                season = video.season
                episode = video.episode
            }
        }
        return ResolvedIds(ids, anime.takeUnless { it.isEmpty }, season, episode, animeEpisode = entryEpisode)
    }

    /** Where [entryEpisode] of [row]'s entry lies on TVDB, else on TMDB ([FribbRow.episodeOn]). */
    private suspend fun externalEpisode(row: FribbRow, entryEpisode: Int): Pair<Int, Int>? {
        val lists = if (row.anidb != null) mappings.animeLists() else null
        val placed = row.episodeOn(AnimeListProvider.TVDB, entryEpisode, lists) ?: row.episodeOn(AnimeListProvider.TMDB, entryEpisode, lists)
        return placed?.let { it.season to it.episode }
    }

    /** A film: the first `MOVIE` row, else any (TMDB lists some OVAs and specials as films). */
    private fun pickMovieRow(rows: List<FribbRow>): FribbRow? = rows.firstOrNull { it.isMovie } ?: rows.firstOrNull()

    /**
     * The entry for an episode of a show with several: the Anime-Lists match's, else the one
     * mapped to [season]. Where a season holds several entries (split cours), the one whose episode offset in it is
     * the largest below [episode]: Fribb's `episode_offset`. A season no entry is mapped to
     * yet (a new cour) takes an entry without season mapping (an absolutely numbered show), never another season's,
     * and specials take none. Otherwise regular series entries come first, then anything but a film.
     */
    private fun pickShowRow(rows: List<FribbRow>, season: Int?, episode: Int?, match: AnimeEpisodeMatch?): FribbRow? {
        if (rows.isEmpty()) return null
        if (match != null) return rows.firstOrNull { it.anidb == match.anidbId }
        var candidates = rows
        if (season != null) {
            val inSeason = rows.filter { it.tvdbSeason == season || it.tmdbSeason == season }
            if (inSeason.isNotEmpty()) {
                if (episode == null) return inSeason.first()
                return inSeason.filter { it.offsetIn(season) < episode }.maxByOrNull { it.offsetIn(season) } ?: inSeason.first()
            }
            if (season <= 0) return null
            candidates = rows.filter { !it.hasSeasonMapping }
            if (candidates.isEmpty()) return null
        }
        return candidates.firstOrNull { it.isRegularSeries } ?: candidates.firstOrNull { !it.isMovie } ?: candidates.first()
    }

    /**
     * The episode's number on [selected]'s entry (numbered from the episode itself): the Anime-Lists match; for an
     * entry that is exactly this season, the episode less the entry's offset in it; for the one regular entry of an
     * absolutely numbered show, the episode's place across seasons. `null` where the entry can't be numbered (specials,
     * an entry of another season).
     */
    private fun entryEpisode(selected: FribbRow, rows: List<FribbRow>, season: Int, episode: Int, match: AnimeEpisodeMatch?, meta: MetaIds?): Int? {
        if (season <= 0) return null
        if (match != null && selected.anidb == match.anidbId) return match.anidbEpisode
        if (selected.hasSeasonMapping) {
            if (!(selected.tvdbSeason == season || selected.tmdbSeason == season) || !selected.isRegularSeries) return null
            return (episode - selected.offsetIn(season)).takeIf { it > 0 }
        }
        val regular = rows.filter { it.isRegularSeries }
        if (regular.size != 1 || regular.single() !== selected) return null
        return if (season == 1) episode else meta?.absoluteEpisode(season, episode)
    }

    private fun FribbRow.animeIds(): AnimeIds? =
        AnimeIds(mal = mal, anilist = anilist, simkl = simkl, kitsu = kitsu, anidb = anidb).takeUnless { it.isEmpty }

    /** Episodes of [season] before this entry's first (TVDB's count where it is TVDB's season, else TMDB's). */
    private fun FribbRow.offsetIn(season: Int): Int =
        (if (tvdbSeason == season) tvdbEpisodeOffset else tmdbEpisodeOffset.takeIf { tmdbSeason == season }) ?: 0

    private fun MetaIds.tmdb(movie: Boolean): Int? = if (movie) tmdbMovie else tmdbTv

    private inline fun agreed(rows: List<FribbRow>, value: (FribbRow) -> Int?): Int? =
        rows.mapNotNullTo(HashSet(), value).singleOrNull()

    private companion object {
        /** The Stremio type whose ids are films; everything else (`series`, `anime`, …) is a show. */
        const val MOVIE = "movie"
    }
}
