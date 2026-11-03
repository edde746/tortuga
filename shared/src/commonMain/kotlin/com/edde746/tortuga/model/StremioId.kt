package com.edde746.tortuga.model

/**
 * A Stremio meta or video id:
 *
 * - `tt0944947`, `tt0944947:1:2` (Cinemeta: IMDb, with season and episode);
 * - `tmdb:1399`, `tmdb:1399:1:2`;
 * - anime addons' `kitsu:1376`, `kitsu:1376:5`, and the same shapes for `mal:`, `anilist:` and `anidb:`, whose one
 *   number after the id is the episode on that list entry.
 *
 * Anything else (an addon's own namespace, a channel's video id, a malformed number) parses to `null`.
 */
internal sealed interface StremioId {
    /** The episode's season in the id's own numbering; `null` for a movie or a whole title. */
    val season: Int?

    /** The episode in the id's own numbering; `null` for a movie or a whole title. */
    val episode: Int?

    /** `tt…` or `tmdb:…`: season and episode come together or not at all. The Stremio type decides TMDB's namespace. */
    sealed interface Catalog : StremioId

    data class Imdb(val id: ImdbId, override val season: Int? = null, override val episode: Int? = null) : Catalog

    data class Tmdb(val id: Int, override val season: Int? = null, override val episode: Int? = null) : Catalog

    /** An anime list's entry, with [episode] numbered on that entry (no seasons). */
    data class Anime(val list: AnimeList, val id: Int, override val episode: Int? = null) : StremioId {
        override val season: Int? get() = null
    }

    enum class AnimeList(val prefix: String) { KITSU("kitsu"), MAL("mal"), ANILIST("anilist"), ANIDB("anidb") }

    companion object {
        fun parse(id: String): StremioId? {
            val parts = id.split(':')
            val head = parts[0]
            ImdbId.parse(head)?.let { imdb ->
                val (season, episode) = seasonEpisode(parts, from = 1) ?: return null
                return Imdb(imdb, season, episode)
            }
            if (head == "tmdb") {
                val tmdb = parts.getOrNull(1)?.let(::positive) ?: return null
                val (season, episode) = seasonEpisode(parts, from = 2) ?: return null
                return Tmdb(tmdb, season, episode)
            }
            val list = AnimeList.entries.firstOrNull { it.prefix == head } ?: return null
            val entry = parts.getOrNull(1)?.let(::positive) ?: return null
            return when (parts.size) {
                2 -> Anime(list, entry)
                3 -> Anime(list, entry, parts[2].let(::nonNegative) ?: return null)
                else -> null
            }
        }

        /**
         * The id of video [videoId] of the title whose id is [title]: the video id itself when it parses; for a video
         * id of the addon's own naming, [title] placed by the video's season and episode in [meta]. `null` when neither
         * says what the video is.
         */
        fun ofVideo(title: StremioId?, videoId: String, meta: MetaItem?): StremioId? {
            parse(videoId)?.let { return it }
            if (title == null) return null
            val info = meta?.video(videoId)?.seriesInfo ?: return null
            return when (title) {
                is Imdb -> title.copy(season = info.season, episode = info.episode)
                is Tmdb -> title.copy(season = info.season, episode = info.episode)
                is Anime -> title.copy(episode = info.episode)
            }
        }

        /** Nothing after [from], or exactly a season and an episode; `null` for any other shape. */
        private fun seasonEpisode(parts: List<String>, from: Int): Pair<Int?, Int?>? = when (parts.size - from) {
            0 -> null to null
            2 -> {
                val season = nonNegative(parts[from]) ?: return null
                val episode = nonNegative(parts[from + 1]) ?: return null
                season to episode
            }
            else -> null
        }

        private fun nonNegative(text: String): Int? =
            text.takeIf { it.isNotEmpty() && it.length <= 9 && it.all { c -> c in '0'..'9' } }?.toInt()

        private fun positive(text: String): Int? = nonNegative(text)?.takeIf { it > 0 }
    }
}

/**
 * Video [videoId] of the title [metaId] is the title itself, not one of its episodes or a channel's videos: Stremio
 * plays a movie by its meta id, and no video at all is the title.
 */
internal fun videoIsTitle(metaId: String, videoId: String?): Boolean = videoId == null || videoId == metaId
