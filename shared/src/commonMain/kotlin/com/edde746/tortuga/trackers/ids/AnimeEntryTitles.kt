package com.edde746.tortuga.trackers.ids

import com.edde746.tortuga.model.StremioId
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * A title an anime list entry can open as: [type] and [id] as a catalog addon takes them, and for a series [videoId],
 * the entry's episode on that title.
 */
internal data class EntryTitle(val type: String, val id: String, val videoId: String?)

/**
 * The titles an anime list entry (`mal:…`, `anilist:…`, `kitsu:…`, `anidb:…`: the MyAnimeList and AniList rows'
 * items) is, by Fribb's mapping, for Detail to open where no installed addon has the entry itself. Best first:
 *
 * - its IMDb title (Cinemeta's ids), a series at the episode's place on TVDB's seasons, which Cinemeta numbers by;
 * - its TMDB title (`tmdb:…`, the TMDB addons' ids), a series at the episode's place on TMDB's seasons;
 * - the entry's ids on the other anime lists, Kitsu's first, a series at the entry's own episode.
 *
 * The episode is placed as the trackers place it ([FribbRow.episodeOn]): an entry that is a sequel season opens on
 * that season, one that starts mid-season (a second cour) on its own first episode. An entry Fribb doesn't know has
 * none; with Fribb's dataset not to be had (offline with no copy, its download failed) there are none to give: null.
 * For a series both datasets load at once, each taking a while on a slow device.
 */
internal class AnimeEntryTitles(private val mappings: AnimeMappingSource) {
    /** The titles of [entry] of the Stremio [type], at the entry's [StremioId.Anime.episode] (none: its first); null without Fribb's dataset. */
    suspend fun of(type: String, entry: StremioId.Anime): List<EntryTitle>? = coroutineScope {
        // Its load goes on in the datasets' own scope when it isn't waited for.
        val animeLists = if (type == MOVIE) null else async { mappings.animeLists() }
        val fribb = mappings.fribb()
        val row = fribb?.byEntry(entry)?.firstOrNull()
        if (row?.anidb == null) animeLists?.cancel()
        if (fribb == null) return@coroutineScope null
        if (row == null) return@coroutineScope emptyList()
        val episode = entry.episode?.takeIf { it > 0 } ?: 1
        val others = listOfNotNull(
            row.kitsu?.let { StremioId.AnimeList.KITSU to it },
            row.mal?.let { StremioId.AnimeList.MAL to it },
            row.anilist?.let { StremioId.AnimeList.ANILIST to it },
            row.anidb?.let { StremioId.AnimeList.ANIDB to it },
        ).filter { (list, _) -> list != entry.list }.map { (list, id) ->
            val title = "${list.prefix}:$id"
            EntryTitle(type, title, if (type == MOVIE) null else "$title:$episode")
        }
        if (animeLists == null) {
            return@coroutineScope listOfNotNull(row.imdb, row.tmdbMovie?.let { "$TMDB_PREFIX$it" }).map { EntryTitle(MOVIE, it, null) } + others
        }
        val lists = if (row.anidb != null) animeLists.await() else null
        fun at(id: String, provider: AnimeListProvider) =
            EntryTitle(SERIES, id, row.episodeOn(provider, episode, lists)?.let { "$id:${it.season}:${it.episode}" })
        listOfNotNull(
            row.imdb?.let { at(it, AnimeListProvider.TVDB) },
            row.tmdbTv?.let { at("$TMDB_PREFIX$it", AnimeListProvider.TMDB) },
        ) + others
    }

    private companion object {
        const val MOVIE = "movie"
        const val SERIES = "series"
        const val TMDB_PREFIX = "tmdb:"
    }
}
