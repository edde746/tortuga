package com.edde746.tortuga.kids

import com.edde746.tortuga.model.ImdbId
import com.edde746.tortuga.model.StremioId
import com.edde746.tortuga.trackers.ids.AnimeMappingSource
import com.edde746.tortuga.trackers.ids.FribbRow

/**
 * Titles' suggested ages, looked up on this device in the age database ([database]: agerating's small database, ids
 * to ages on the scale 0, 6, 12, 16, 18); no title ever leaves the device. A title is looked up by its ids in this
 * order, the first the database has giving its age:
 * - its IMDb id: the id itself (`tt…`), or the `imdb_id` its addon sent;
 * - its TMDB id (`tmdb:…`), among films or series by its type;
 * - its TVDB id (`tvdb:…`), among series or films by its type;
 * - an anime list entry (`kitsu:`, `mal:`, `anilist:`, `anidb:`): the IMDb, TMDB and TVDB ids [anime]'s Fribb mapping
 *   gives it, in that order.
 * Any other id (an addon's own namespace, `yt_id:…`) has none to look up by.
 */
internal class AgeRatings(private val database: AgeDatabase, private val anime: AnimeMappingSource) {
    /**
     * The rating of the title [id] of [type] whose addon sent [imdbId] (the preview's `imdb_id`) with it. A database
     * that can't be had, or an anime entry whose mapping can't (offline, a download that failed), is
     * [TitleRating.Failed], not a title without an id.
     */
    suspend fun rating(type: String, id: String, imdbId: String?): TitleRating {
        val keys = keysOf(type, id, imdbId) ?: return TitleRating.Failed
        if (keys.isEmpty()) return TitleRating.NoId
        return database.read { ages ->
            keys.firstNotNullOfOrNull { (table, key) -> ages.age(table, key) }?.let { TitleRating.Rated(it) } ?: TitleRating.Unrated
        } ?: TitleRating.Failed
    }

    /** Starts the database's download, when it has none, so the first lookups don't wait for it. */
    fun prefetch() = database.prefetch()

    /** The title's ids as the database is keyed by, in lookup order; null when its anime mapping can't be had. */
    private suspend fun keysOf(type: String, id: String, imdbId: String?): List<Pair<AgeIdTable, Long>>? {
        val movie = type == MOVIE
        val parsed = StremioId.parse(id)
        val keys = ArrayList<Pair<AgeIdTable, Long>>(3)
        ((parsed as? StremioId.Imdb)?.id?.value ?: imdbId)?.let(::imdbNumber)?.let { keys += AgeIdTable.IMDB to it }
        (parsed as? StremioId.Tmdb)?.let { keys += tmdb(movie) to it.id.toLong() }
        TVDB.matchEntire(id)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it > 0 }?.let { keys += tvdb(movie) to it }
        if (parsed is StremioId.Anime) {
            val fribb = anime.fribb() ?: return null
            fribb.byEntry(parsed).firstOrNull()?.let { keys += keysOf(it) }
        }
        return keys
    }

    /** An anime entry's row of the Fribb mapping as the database is keyed: its IMDb, TMDB and TVDB ids. */
    private fun keysOf(row: FribbRow): List<Pair<AgeIdTable, Long>> = listOfNotNull(
        row.imdb?.let(::imdbNumber)?.let { AgeIdTable.IMDB to it },
        (if (row.isMovie) row.tmdbMovie else row.tmdbTv)?.let { tmdb(row.isMovie) to it.toLong() },
        // Fribb's tvdb_id is always a series.
        row.tvdb?.takeIf { !row.isMovie }?.let { AgeIdTable.TVDB_SERIES to it.toLong() },
    )

    companion object {
        private const val MOVIE = "movie"
        private val TVDB = Regex("^tvdb:([0-9]{1,10})$")

        private fun tmdb(movie: Boolean) = if (movie) AgeIdTable.TMDB_MOVIE else AgeIdTable.TMDB_TV

        private fun tvdb(movie: Boolean) = if (movie) AgeIdTable.TVDB_MOVIE else AgeIdTable.TVDB_SERIES

        /** An IMDb id as the database keys it: its number, without `tt` and leading zeros (`tt0133093` → 133093). */
        private fun imdbNumber(id: String): Long? = ImdbId.parse(id)?.value?.removePrefix("tt")?.toLongOrNull()
    }
}
