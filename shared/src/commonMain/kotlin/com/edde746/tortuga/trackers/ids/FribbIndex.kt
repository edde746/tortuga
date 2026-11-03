package com.edde746.tortuga.trackers.ids

import com.edde746.tortuga.model.ImdbId
import com.edde746.tortuga.model.StremioId
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

/**
 * One row of Fribb's `anime-list-mini.json` (https://github.com/Fribb/anime-lists): an anime list entry (AniDB,
 * Kitsu, MyAnimeList, AniList, Simkl) with the catalog ids of the show or film it belongs to.
 *
 * @property tmdbTv the TMDB series (`themoviedb_id: {"tv": id}`).
 * @property tmdbMovie the first TMDB film (`themoviedb_id: {"movie": [ids]}`). TMDB numbers films and series apart,
 *   so the two are never mixed.
 * @property tvdbSeason the TVDB season this entry is (a split-cour show has one row per season).
 * @property tvdbEpisodeOffset TVDB episodes of that season before this entry's first.
 */
internal data class FribbRow(
    val anidb: Int? = null,
    val anilist: Int? = null,
    val kitsu: Int? = null,
    val mal: Int? = null,
    val simkl: Int? = null,
    val imdb: String? = null,
    val tmdbTv: Int? = null,
    val tmdbMovie: Int? = null,
    val tvdb: Int? = null,
    val tvdbSeason: Int? = null,
    val tmdbSeason: Int? = null,
    val tvdbEpisodeOffset: Int? = null,
    val tmdbEpisodeOffset: Int? = null,
    /** `TV`, `MOVIE`, `OVA`, `ONA`, `SPECIAL`, `UNKNOWN`, or `null`. */
    val type: String? = null,
) {
    val isMovie: Boolean get() = type == "MOVIE"

    val hasSeasonMapping: Boolean get() = tvdbSeason != null || tmdbSeason != null

    /** A regular series entry: not a film, not a special season, and TV/ONA or untyped. */
    val isRegularSeries: Boolean
        get() = !isMovie && tvdbSeason != 0 && tmdbSeason != 0 &&
            when (type?.uppercase()) {
                null, "TV", "ONA", "UNKNOWN" -> true
                else -> false
            }

    /**
     * Where [entryEpisode] of this entry lies on [provider]'s seasons: Anime-Lists' mapping when [lists] has the entry
     * (no answer where it numbers absolutely or has no series there), else Fribb's season and episode offset.
     */
    fun episodeOn(provider: AnimeListProvider, entryEpisode: Int, lists: AnimeListsIndex?): ExternalEpisode? {
        if (anidb != null && lists != null && anidb in lists.byAnidb) return lists.externalEpisode(anidb, provider, entryEpisode)
        val season = (if (provider == AnimeListProvider.TVDB) tvdbSeason else tmdbSeason) ?: return null
        val offset = (if (provider == AnimeListProvider.TVDB) tvdbEpisodeOffset else tmdbEpisodeOffset) ?: 0
        return ExternalEpisode(provider, season, entryEpisode + offset)
    }
}

/**
 * The Fribb mapping, indexed by every id a lookup starts from.
 *
 * The file is ~40 000 rows (5.8 MB), so rows are packed into one [IntArray] (IMDb ids by their number) and each index
 * is a sorted [LongArray] of `id << 32 | row`: a few MB in all, against tens of MB for maps of row objects. A row
 * can carry several IMDb ids or TMDB films (collections): it is indexed under each, and [FribbRow] shows the first.
 */
internal class FribbIndex private constructor(
    private val fields: IntArray,
    private val types: ByteArray,
    private val byAnidb: LongArray,
    private val byKitsu: LongArray,
    private val byMal: LongArray,
    private val byAnilist: LongArray,
    private val byImdb: LongArray,
    private val byTvdb: LongArray,
    private val byTmdbTv: LongArray,
    private val byTmdbMovie: LongArray,
) {
    val size: Int get() = types.size

    val isEmpty: Boolean get() = size == 0

    val summary: String get() = "$size rows"

    fun byAnidb(id: Int): List<FribbRow> = rows(byAnidb, id)
    fun byKitsu(id: Int): List<FribbRow> = rows(byKitsu, id)
    fun byMal(id: Int): List<FribbRow> = rows(byMal, id)
    fun byAnilist(id: Int): List<FribbRow> = rows(byAnilist, id)

    /** The rows of the anime list entry [entry]. */
    fun byEntry(entry: StremioId.Anime): List<FribbRow> = when (entry.list) {
        StremioId.AnimeList.KITSU -> byKitsu(entry.id)
        StremioId.AnimeList.MAL -> byMal(entry.id)
        StremioId.AnimeList.ANILIST -> byAnilist(entry.id)
        StremioId.AnimeList.ANIDB -> byAnidb(entry.id)
    }

    /**
     * Rows for a title's catalog ids: the first non-empty of AniDB, TVDB, TMDB and IMDb, in that order. A [movie] skips
     * TVDB (Fribb's `tvdb_id` is always a series) and looks TMDB up among films.
     */
    fun lookup(movie: Boolean, anidb: Int? = null, tvdb: Int? = null, tmdb: Int? = null, imdb: String? = null): List<FribbRow> {
        if (anidb != null) rows(byAnidb, anidb).takeIf { it.isNotEmpty() }?.let { return it.take(1) }
        if (tvdb != null && !movie) rows(byTvdb, tvdb).takeIf { it.isNotEmpty() }?.let { return it }
        if (tmdb != null) rows(if (movie) byTmdbMovie else byTmdbTv, tmdb).takeIf { it.isNotEmpty() }?.let { return it }
        val imdbNumber = imdb?.let(::imdbNumber)
        if (imdbNumber != null) rows(byImdb, imdbNumber).takeIf { it.isNotEmpty() }?.let { return it }
        return emptyList()
    }

    private fun rows(index: LongArray, id: Int): List<FribbRow> {
        if (id < 0) return emptyList()
        val key = id.toLong() shl 32
        var lo = 0
        var hi = index.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (index[mid] < key) lo = mid + 1 else hi = mid
        }
        var result: MutableList<FribbRow>? = null
        var i = lo
        while (i < index.size && (index[i] ushr 32).toInt() == id) {
            (result ?: ArrayList<FribbRow>(2).also { result = it }).add(row((index[i] and 0xFFFFFFFFL).toInt()))
            i++
        }
        return result ?: emptyList()
    }

    /**
     * Writes this index's arrays as they are ([unpack] reads them back): a later launch loads that in a fraction of the
     * time the JSON takes to parse (a few seconds on a TV stick).
     */
    fun pack(sink: Sink) {
        val indexes = listOf(byAnidb, byKitsu, byMal, byAnilist, byImdb, byTvdb, byTmdbTv, byTmdbMovie)
        val bytes = ByteArray(12 + fields.size * 4 + types.size + indexes.sumOf { 4 + it.size * 8 })
        var p = 0
        fun int(v: Int) {
            bytes[p] = (v ushr 24).toByte()
            bytes[p + 1] = (v ushr 16).toByte()
            bytes[p + 2] = (v ushr 8).toByte()
            bytes[p + 3] = v.toByte()
            p += 4
        }
        int(PACK_MAGIC)
        int(PACK_VERSION)
        int(types.size)
        for (v in fields) int(v)
        types.copyInto(bytes, p)
        p += types.size
        for (index in indexes) {
            int(index.size)
            for (v in index) {
                int((v ushr 32).toInt())
                int(v.toInt())
            }
        }
        sink.write(bytes)
    }

    private fun row(i: Int): FribbRow {
        val base = i * FIELD_COUNT
        fun f(field: Int) = fields[base + field].takeIf { it != NONE }
        return FribbRow(
            anidb = f(ANIDB),
            anilist = f(ANILIST),
            kitsu = f(KITSU),
            mal = f(MAL),
            simkl = f(SIMKL),
            imdb = f(IMDB)?.let(::imdbId),
            tmdbTv = f(TMDB_TV),
            tmdbMovie = f(TMDB_MOVIE),
            tvdb = f(TVDB),
            tvdbSeason = f(TVDB_SEASON),
            tmdbSeason = f(TMDB_SEASON),
            tvdbEpisodeOffset = f(TVDB_OFFSET),
            tmdbEpisodeOffset = f(TMDB_OFFSET),
            type = TYPES.getOrNull(types[i].toInt()),
        )
    }

    /** Collects rows, then sorts the indexes once. */
    class Builder {
        private var fields = IntArray(1024 * FIELD_COUNT)
        private var types = ByteArray(1024)
        private var count = 0
        private val indexes = Array(INDEX_COUNT) { LongList() }

        fun add(row: FribbRow, moreImdb: List<String> = emptyList(), moreTmdbMovies: List<Int> = emptyList()) {
            if (count == types.size) {
                fields = fields.copyOf(fields.size * 2)
                types = types.copyOf(types.size * 2)
            }
            val i = count++
            val base = i * FIELD_COUNT
            val imdb = row.imdb?.let(::imdbNumber)
            fun put(field: Int, value: Int?) {
                fields[base + field] = value ?: NONE
            }
            put(ANIDB, row.anidb)
            put(ANILIST, row.anilist)
            put(KITSU, row.kitsu)
            put(MAL, row.mal)
            put(SIMKL, row.simkl)
            put(IMDB, imdb)
            put(TMDB_TV, row.tmdbTv)
            put(TMDB_MOVIE, row.tmdbMovie)
            put(TVDB, row.tvdb)
            put(TVDB_SEASON, row.tvdbSeason)
            put(TMDB_SEASON, row.tmdbSeason)
            put(TVDB_OFFSET, row.tvdbEpisodeOffset)
            put(TMDB_OFFSET, row.tmdbEpisodeOffset)
            types[i] = TYPES.indexOf(row.type?.uppercase()).let { if (it < 0) UNKNOWN_TYPE else it }.toByte()

            fun index(which: Int, id: Int?) {
                if (id != null && id >= 0) indexes[which].add((id.toLong() shl 32) or i.toLong())
            }
            index(I_ANIDB, row.anidb)
            index(I_KITSU, row.kitsu)
            index(I_MAL, row.mal)
            index(I_ANILIST, row.anilist)
            index(I_IMDB, imdb)
            moreImdb.forEach { index(I_IMDB, imdbNumber(it)) }
            index(I_TVDB, row.tvdb)
            index(I_TMDB_TV, row.tmdbTv)
            index(I_TMDB_MOVIE, row.tmdbMovie)
            moreTmdbMovies.forEach { index(I_TMDB_MOVIE, it) }
        }

        fun build(): FribbIndex {
            val sorted = indexes.map { it.toSortedArray() }
            return FribbIndex(
                fields.copyOf(count * FIELD_COUNT),
                types.copyOf(count),
                sorted[I_ANIDB], sorted[I_KITSU], sorted[I_MAL], sorted[I_ANILIST],
                sorted[I_IMDB], sorted[I_TVDB], sorted[I_TMDB_TV], sorted[I_TMDB_MOVIE],
            )
        }
    }

    private class LongList {
        private var values = LongArray(256)
        private var size = 0

        fun add(value: Long) {
            if (size == values.size) values = values.copyOf(size * 2)
            values[size++] = value
        }

        fun toSortedArray(): LongArray = values.copyOf(size).also { it.sort() }
    }

    companion object {
        val EMPTY: FribbIndex = Builder().build()

        /**
         * The index of anime-mappings' `fribb` table: Fribb's `anime-list-mini.json`, a row per element, in its order.
         * IMDb ids are trimmed, and empty ones and ones that aren't IMDb's left out; a row's first IMDb id and TMDB film
         * are its [FribbRow]'s, and it is indexed under every one. Throws [IllegalArgumentException] when the table lacks
         * a column it reads or one is damaged.
         */
        fun from(table: PackedTables.Table): FribbIndex {
            val type = table.strings("type")
            val anidb = table.ints("anidb_id")
            val anilist = table.ints("anilist_id")
            val kitsu = table.ints("kitsu_id")
            val mal = table.ints("mal_id")
            val simkl = table.ints("simkl_id")
            val imdb = table.stringLists("imdb_id")
            val tmdbTv = table.ints("themoviedb_id.tv")
            val tmdbMovies = table.intLists("themoviedb_id.movie")
            val tvdb = table.ints("tvdb_id")
            val tvdbSeason = table.ints("season.tvdb")
            val tmdbSeason = table.ints("season.tmdb")
            val tvdbOffset = table.ints("episode_offset.tvdb")
            val tmdbOffset = table.ints("episode_offset.tmdb")
            val builder = Builder()
            for (i in 0 until table.rows) {
                val imdbIds = imdb[i]?.mapNotNull { id -> id.trim().takeIf { it.isNotEmpty() && imdbNumber(it) != null } }.orEmpty()
                val movies = tmdbMovies[i]
                val row = FribbRow(
                    anidb = anidb[i].orNull(), anilist = anilist[i].orNull(), kitsu = kitsu[i].orNull(), mal = mal[i].orNull(),
                    simkl = simkl[i].orNull(), imdb = imdbIds.firstOrNull(), tmdbTv = tmdbTv[i].orNull(),
                    tmdbMovie = movies?.firstOrNull(), tvdb = tvdb[i].orNull(), tvdbSeason = tvdbSeason[i].orNull(),
                    tmdbSeason = tmdbSeason[i].orNull(), tvdbEpisodeOffset = tvdbOffset[i].orNull(),
                    tmdbEpisodeOffset = tmdbOffset[i].orNull(), type = type[i],
                )
                builder.add(row, imdbIds.drop(1), movies?.drop(1).orEmpty())
            }
            return builder.build()
        }

        private fun Int.orNull(): Int? = takeIf { it != PackedTables.NONE }

        /** The index [pack] wrote; throws [IllegalArgumentException] for anything else (another version's, a cut file). */
        fun unpack(source: Source): FribbIndex {
            val bytes = source.readByteArray()
            require(bytes.size >= 12 && bytes.intAt(0) == PACK_MAGIC && bytes.intAt(4) == PACK_VERSION) { "not a packed index of this version" }
            val count = bytes.intAt(8)
            var p = 12
            require(count >= 0 && p + count.toLong() * (FIELD_COUNT * 4 + 1) <= bytes.size) { "the packed index is cut short" }
            // Plain loops over the bytes: a launch on a TV stick spends its time here, ~1 million numbers.
            val fields = IntArray(count * FIELD_COUNT)
            for (i in fields.indices) {
                fields[i] = bytes.intAt(p)
                p += 4
            }
            val types = bytes.copyOfRange(p, p + count)
            p += count
            val indexes = Array(INDEX_COUNT) {
                require(p + 4L <= bytes.size) { "the packed index is cut short" }
                val size = bytes.intAt(p)
                p += 4
                require(size >= 0 && p + size.toLong() * 8 <= bytes.size) { "the packed index is cut short" }
                val index = LongArray(size)
                for (i in 0 until size) {
                    index[i] = (bytes.intAt(p).toLong() shl 32) or (bytes.intAt(p + 4).toLong() and 0xFFFFFFFFL)
                    p += 8
                }
                index
            }
            require(p == bytes.size) { "the packed index has trailing bytes" }
            return FribbIndex(
                fields, types,
                indexes[I_ANIDB], indexes[I_KITSU], indexes[I_MAL], indexes[I_ANILIST],
                indexes[I_IMDB], indexes[I_TVDB], indexes[I_TMDB_TV], indexes[I_TMDB_MOVIE],
            )
        }

        /** The big-endian number at [p]. */
        private fun ByteArray.intAt(p: Int): Int =
            (this[p].toInt() and 0xFF shl 24) or (this[p + 1].toInt() and 0xFF shl 16) or
                (this[p + 2].toInt() and 0xFF shl 8) or (this[p + 3].toInt() and 0xFF)

        /** `FRBI`; [PACK_VERSION] changes with the arrays' layout. */
        private const val PACK_MAGIC = 0x46524249
        private const val PACK_VERSION = 1

        private const val NONE = Int.MIN_VALUE
        private const val ANIDB = 0
        private const val ANILIST = 1
        private const val KITSU = 2
        private const val MAL = 3
        private const val SIMKL = 4
        private const val IMDB = 5
        private const val TMDB_TV = 6
        private const val TMDB_MOVIE = 7
        private const val TVDB = 8
        private const val TVDB_SEASON = 9
        private const val TMDB_SEASON = 10
        private const val TVDB_OFFSET = 11
        private const val TMDB_OFFSET = 12
        private const val FIELD_COUNT = 13

        private const val I_ANIDB = 0
        private const val I_KITSU = 1
        private const val I_MAL = 2
        private const val I_ANILIST = 3
        private const val I_IMDB = 4
        private const val I_TVDB = 5
        private const val I_TMDB_TV = 6
        private const val I_TMDB_MOVIE = 7
        private const val INDEX_COUNT = 8

        /** Index 0 is "no type"; an unknown string keeps the row as `UNKNOWN`. */
        private val TYPES = listOf(null, "TV", "MOVIE", "OVA", "ONA", "SPECIAL", "UNKNOWN", "TV_SHORT", "MUSIC")
        private val UNKNOWN_TYPE = TYPES.indexOf("UNKNOWN")
    }
}

/** The number of an IMDb title id (`tt0213338` → 213338), or `null` for anything else. */
internal fun imdbNumber(id: String): Int? =
    ImdbId.parse(id)?.value?.substring(2)?.toLong()?.takeIf { it <= Int.MAX_VALUE }?.toInt()

/** IMDb's form of [number]: at least seven digits (`tt0213338`). */
internal fun imdbId(number: Int): String = "tt" + number.toString().padStart(7, '0')
