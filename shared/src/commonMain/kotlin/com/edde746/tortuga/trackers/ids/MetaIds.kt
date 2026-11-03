package com.edde746.tortuga.trackers.ids

import com.edde746.tortuga.model.ImdbEpisode
import com.edde746.tortuga.model.MetaItem

/**
 * The ids an addon's meta carries besides its own id, which fill in the ids a tracker item lacks:
 * `imdb_id`, IMDb and TMDB pages among its `links`, and the per-video IMDb episode anime addons add.
 */
internal class MetaIds(
    val imdb: String?,
    val tmdbMovie: Int?,
    val tmdbTv: Int?,
    /** The played video's IMDb episode ([com.edde746.tortuga.model.Video.imdb]). */
    val video: ImdbEpisode?,
    /**
     * Episodes per season of the meta's videos ([MetaItem.episodesPerSeason]; only seasons ≥ 1 are read): an
     * episode's place in absolute numbering.
     */
    private val episodesPerSeason: Map<Int, Int>,
) {
    /** The absolute number of [season] [episode] (the episodes of seasons before it, plus [episode]), when known. */
    fun absoluteEpisode(season: Int, episode: Int): Int? {
        if (season < 1 || episode < 1) return null
        if (season == 1) return episode
        var before = 0
        for (s in 1 until season) before += episodesPerSeason[s] ?: return null
        return before + episode
    }

    companion object {
        private val IMDB_PAGE = Regex("^https?://(?:www\\.|m\\.)?imdb\\.com/title/(tt[0-9]{1,10})(?:[/?#].*)?$", RegexOption.IGNORE_CASE)
        private val TMDB_PAGE = Regex("^https?://(?:www\\.)?themoviedb\\.org/(movie|tv)/([0-9]{1,9})(?:[-/?#].*)?$", RegexOption.IGNORE_CASE)

        fun of(meta: MetaItem, videoId: String): MetaIds {
            var imdb = meta.preview.imdbId
            var tmdbMovie: Int? = null
            var tmdbTv: Int? = null
            for (link in meta.preview.links) {
                IMDB_PAGE.matchEntire(link.url)?.let { if (imdb == null) imdb = it.groupValues[1].lowercase() }
                TMDB_PAGE.matchEntire(link.url)?.let {
                    val id = it.groupValues[2].toInt()
                    if (it.groupValues[1].lowercase() == "movie") tmdbMovie = tmdbMovie ?: id else tmdbTv = tmdbTv ?: id
                }
            }
            return MetaIds(imdb, tmdbMovie, tmdbTv, meta.video(videoId)?.imdb, meta.episodesPerSeason)
        }
    }
}
