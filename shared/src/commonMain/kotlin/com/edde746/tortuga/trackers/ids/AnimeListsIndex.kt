package com.edde746.tortuga.trackers.ids

import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray

/**
 * The Anime-Lists mapping (`anime-list.xml`, https://github.com/Anime-Lists/anime-lists, see its README for the
 * format): how each AniDB entry's episodes lie on TVDB's and TMDB's seasons, and the reverse direction
 * ([externalEpisode]) for anime ids.
 *
 * Built from its [entries] in the file's order: the first entry of an AniDB id is that id's, and every entry of a series
 * is among the series'.
 */
internal class AnimeListsIndex(private val entries: List<AnimeListEntry>) {
    val byTvdb: Map<Int, List<AnimeListEntry>>
    val byTmdbTv: Map<Int, List<AnimeListEntry>>
    val byAnidb: Map<Int, AnimeListEntry>

    init {
        val tvdb = HashMap<Int, MutableList<AnimeListEntry>>()
        val tmdb = HashMap<Int, MutableList<AnimeListEntry>>()
        val anidb = HashMap<Int, AnimeListEntry>()
        for (entry in entries) {
            anidb.getOrPut(entry.anidbId) { entry }
            entry.tvdbId?.let { tvdb.getOrPut(it) { ArrayList(2) }.add(entry) }
            entry.tmdbTvId?.let { tmdb.getOrPut(it) { ArrayList(2) }.add(entry) }
        }
        byTvdb = tvdb
        byTmdbTv = tmdb
        byAnidb = anidb
    }

    val isEmpty: Boolean get() = byAnidb.isEmpty()

    val summary: String get() = "${byAnidb.size} entries, ${byTvdb.size} TVDB series"

    /**
     * The AniDB episode a TVDB (or, failing that, TMDB) [season]/[episode] is, when the entries of that series agree
     * on one: explicit episode lists beat ranges, which beat an entry's default season.
     */
    fun lookupEpisode(tvdbId: Int?, tmdbId: Int?, season: Int?, episode: Int?): AnimeEpisodeMatch? {
        if (season == null || episode == null || episode <= 0) return null
        if (tvdbId != null) {
            selectMatch(matches(byTvdb[tvdbId], AnimeListProvider.TVDB, season, episode))?.let { return it }
        }
        if (tmdbId != null) return selectMatch(matches(byTmdbTv[tmdbId], AnimeListProvider.TMDB, season, episode))
        return null
    }

    /** Every AniDB entry of the series. */
    fun anidbIdsForShow(tvdbId: Int?, tmdbId: Int?): Set<Int> {
        if (tvdbId != null) byTvdb[tvdbId]?.takeIf { it.isNotEmpty() }?.let { entries -> return entries.mapTo(HashSet()) { it.anidbId } }
        if (tmdbId != null) byTmdbTv[tmdbId]?.takeIf { it.isNotEmpty() }?.let { entries -> return entries.mapTo(HashSet()) { it.anidbId } }
        return emptySet()
    }

    /**
     * Where regular episode [anidbEpisode] of AniDB entry [anidbId] lies on [provider]'s seasons; `null` when the
     * mapping doesn't say (no series there, absolute numbering, an episode listed as absent, no entry).
     */
    fun externalEpisode(anidbId: Int, provider: AnimeListProvider, anidbEpisode: Int): ExternalEpisode? {
        val entry = byAnidb[anidbId] ?: return null
        val series = if (provider == AnimeListProvider.TVDB) entry.tvdbId else entry.tmdbTvId
        return if (series != null) entry.externalEpisode(provider, anidbEpisode) else null
    }

    private fun matches(entries: List<AnimeListEntry>?, provider: AnimeListProvider, season: Int, episode: Int): List<AnimeEpisodeMatch> =
        entries.orEmpty().flatMap { it.resolveEpisode(provider, season, episode) }

    private fun selectMatch(matches: List<AnimeEpisodeMatch>): AnimeEpisodeMatch? {
        if (matches.isEmpty()) return null
        val best = matches.minOf { it.kind.ordinal }
        val candidates = matches.filter { it.kind.ordinal == best }
        val first = candidates.first()
        return first.takeIf { candidates.all(first::sameEpisode) }
    }

    /**
     * Writes the entries as numbers ([unpack] reads them back): a launch loads that in a fraction of the time the XML
     * takes to parse (a second on a TV stick).
     */
    fun pack(sink: Sink) {
        sink.writeInt(PACK_MAGIC)
        sink.writeInt(PACK_VERSION)
        sink.writeInt(entries.size)
        for (e in entries) {
            sink.writeInt(e.anidbId)
            sink.writeInt(e.tvdbId ?: NONE)
            sink.writeInt(season(e.defaultTvdbSeason))
            sink.writeInt(e.episodeOffset)
            sink.writeInt(e.tmdbTvId ?: NONE)
            sink.writeInt(season(e.tmdbSeason))
            sink.writeInt(e.tmdbOffset)
            sink.writeInt(e.mappings.size)
            for (m in e.mappings) {
                sink.writeInt(m.anidbSeason)
                sink.writeInt(m.provider.ordinal)
                sink.writeInt(m.externalSeason)
                sink.writeInt(m.start ?: NONE)
                sink.writeInt(m.end ?: NONE)
                sink.writeInt(m.offset)
                sink.writeInt(m.explicit.size)
                for (x in m.explicit) {
                    sink.writeInt(x.anidbEpisode)
                    sink.writeInt(x.externalEpisodes.size)
                    for (episode in x.externalEpisodes) sink.writeInt(episode)
                }
            }
        }
    }

    companion object {
        val EMPTY = AnimeListsIndex(emptyList())

        /**
         * The index of anime-mappings' Anime-Lists tables: `anime_lists` (an `<anime>` of `anime-list.xml` a row, in its
         * order) and `anime_lists_mappings` (its `<mapping>`s, by `anidbid`). An attribute that isn't a number (a
         * `tvdbid` of `movie`, a season of `a` for absolute numbering) is in its `_text` column. Throws
         * [IllegalArgumentException] when a table or a column it reads is missing or damaged.
         */
        fun from(tables: PackedTables): AnimeListsIndex {
            val m = tables.table("anime_lists_mappings")
            val mAnidb = m.ints("anidbid")
            val anidbSeason = m.ints("anidbseason")
            val tvdbSeason = m.ints("tvdbseason")
            val tmdbSeason = m.ints("tmdbseason")
            val start = m.ints("start")
            val end = m.ints("end")
            val offset = m.ints("offset")
            val text = m.strings("text")
            val mappings = HashMap<Int, MutableList<AnimeListEpisodeMapping>>()
            for (j in 0 until m.rows) {
                val anidb = mAnidb[j].orNull() ?: continue
                val season = anidbSeason[j].orNull() ?: continue
                val explicit = parseExplicit(text[j].orEmpty())
                val into = mappings.getOrPut(anidb) { ArrayList(2) }
                tvdbSeason[j].orNull()?.let {
                    into += AnimeListEpisodeMapping(season, AnimeListProvider.TVDB, it, start[j].orNull(), end[j].orNull(), offset[j].orNull() ?: 0, explicit)
                }
                tmdbSeason[j].orNull()?.let {
                    into += AnimeListEpisodeMapping(season, AnimeListProvider.TMDB, it, start[j].orNull(), end[j].orNull(), offset[j].orNull() ?: 0, explicit)
                }
            }

            val a = tables.table("anime_lists")
            val anidb = a.ints("anidbid")
            val tvdb = a.ints("tvdbid")
            val tvdbText = a.strings("tvdbid_text")
            val defaultTvdbSeason = a.ints("defaulttvdbseason")
            val defaultTvdbSeasonText = a.strings("defaulttvdbseason_text")
            val episodeOffset = a.ints("episodeoffset")
            val tmdbTv = a.ints("tmdbtv")
            val tmdbSeasonOf = a.ints("tmdbseason")
            val tmdbSeasonText = a.strings("tmdbseason_text")
            val tmdbOffset = a.ints("tmdboffset")
            val entries = ArrayList<AnimeListEntry>(a.rows)
            for (i in 0 until a.rows) {
                val id = anidb[i].orNull() ?: continue
                entries += AnimeListEntry(
                    anidbId = id,
                    tvdbId = tvdb[i].orNull() ?: flexibleInt(tvdbText[i]),
                    defaultTvdbSeason = seasonRef(defaultTvdbSeason[i], defaultTvdbSeasonText[i]),
                    episodeOffset = episodeOffset[i].orNull() ?: 0,
                    tmdbTvId = tmdbTv[i].orNull(),
                    tmdbSeason = seasonRef(tmdbSeasonOf[i], tmdbSeasonText[i]),
                    tmdbOffset = tmdbOffset[i].orNull() ?: 0,
                    mappings = mappings[id].orEmpty(),
                )
            }
            return AnimeListsIndex(entries)
        }

        /** A default season: its number, else its text (`a`: absolute numbering). */
        private fun seasonRef(number: Int, text: String?): SeasonRef? = when {
            number != PackedTables.NONE -> SeasonRef.of(number)
            text == "a" -> SeasonRef.ABSOLUTE
            else -> flexibleInt(text)?.let(SeasonRef::of)
        }

        private fun Int.orNull(): Int? = takeIf { it != PackedTables.NONE }

        /** The index [pack] wrote; throws [IllegalArgumentException] for anything else (another version's, a cut file). */
        fun unpack(source: Source): AnimeListsIndex {
            // From the bytes at once: on a TV stick, reading each number from the source took longer than the XML.
            val r = PackedInts(source.readByteArray())
            fun season(v: Int) = when (v) {
                NONE -> null
                ABSOLUTE -> SeasonRef.ABSOLUTE
                else -> SeasonRef.of(v)
            }
            require(r.next() == PACK_MAGIC && r.next() == PACK_VERSION) { "not a packed index of this version" }
            val entries = List(r.count()) {
                AnimeListEntry(
                    anidbId = r.next(),
                    tvdbId = r.orNull(),
                    defaultTvdbSeason = season(r.next()),
                    episodeOffset = r.next(),
                    tmdbTvId = r.orNull(),
                    tmdbSeason = season(r.next()),
                    tmdbOffset = r.next(),
                    mappings = List(r.count()) {
                        AnimeListEpisodeMapping(
                            anidbSeason = r.next(),
                            provider = AnimeListProvider.entries.getOrNull(r.next()) ?: throw IllegalArgumentException("not a provider"),
                            externalSeason = r.next(),
                            start = r.orNull(),
                            end = r.orNull(),
                            offset = r.next(),
                            explicit = List(r.count()) { AnimeListExplicitEpisode(r.next(), List(r.count()) { r.next() }) },
                        )
                    },
                )
            }
            require(r.atEnd) { "the packed index has trailing bytes" }
            return AnimeListsIndex(entries)
        }

        private fun season(ref: SeasonRef?): Int = if (ref == null) NONE else ref.number ?: ABSOLUTE

        /** `ANLI`; [PACK_VERSION] changes with the layout. */
        private const val PACK_MAGIC = 0x414E4C49
        private const val PACK_VERSION = 1
        private const val NONE = Int.MIN_VALUE
        private const val ABSOLUTE = Int.MIN_VALUE + 1
    }
}

/** The big-endian numbers of [bytes], read in turn; throws [IllegalArgumentException] past their end. */
private class PackedInts(private val bytes: ByteArray) {
    private var p = 0

    val atEnd: Boolean get() = p == bytes.size

    fun next(): Int {
        require(p + 4 <= bytes.size) { "the packed index is cut short" }
        val v = (bytes[p].toInt() and 0xFF shl 24) or (bytes[p + 1].toInt() and 0xFF shl 16) or
            (bytes[p + 2].toInt() and 0xFF shl 8) or (bytes[p + 3].toInt() and 0xFF)
        p += 4
        return v
    }

    /** A count of what follows, each at least a number. */
    fun count(): Int = next().also { require(it >= 0 && it <= (bytes.size - p) / 4) { "the packed index is cut short" } }

    fun orNull(): Int? = next().takeIf { it != Int.MIN_VALUE }
}

internal enum class AnimeListProvider { TVDB, TMDB }

/** How an episode was matched; the order is the preference. */
internal enum class AnimeListMatchKind { EXPLICIT, RANGE, DEFAULT_MAPPING }

/** A default season: a number, or `a` for absolute numbering. */
internal class SeasonRef private constructor(val number: Int?) {
    val isAbsolute: Boolean get() = number == null

    companion object {
        val ABSOLUTE = SeasonRef(null)

        fun of(number: Int) = SeasonRef(number)
    }
}

/** An episode on TVDB or TMDB. */
internal data class ExternalEpisode(val provider: AnimeListProvider, val season: Int, val episode: Int)

/** One `<anime>` element (its name and supplemental info are left out). */
internal class AnimeListEntry(
    val anidbId: Int,
    val tvdbId: Int? = null,
    val defaultTvdbSeason: SeasonRef? = null,
    val episodeOffset: Int = 0,
    val tmdbTvId: Int? = null,
    val tmdbSeason: SeasonRef? = null,
    val tmdbOffset: Int = 0,
    val mappings: List<AnimeListEpisodeMapping> = emptyList(),
) {
    /** The AniDB episodes external [season]/[episode] can be. */
    fun resolveEpisode(provider: AnimeListProvider, season: Int, episode: Int): List<AnimeEpisodeMatch> {
        val explicit = mappings.filter { it.matches(provider, season) }.flatMap { mapping ->
            mapping.explicit.filter { episode in it.externalEpisodes }.map {
                AnimeEpisodeMatch(anidbId, mapping.anidbSeason, it.anidbEpisode, provider, season, episode, AnimeListMatchKind.EXPLICIT)
            }
        }
        if (explicit.isNotEmpty()) return explicit

        val ranges = mappings.filter { it.matches(provider, season) }.mapNotNull { mapping ->
            val start = mapping.start ?: return@mapNotNull null
            val anidbEpisode = episode - mapping.offset
            val end = mapping.end
            if (anidbEpisode < start || (end != null && anidbEpisode > end) || anidbEpisode <= 0) return@mapNotNull null
            AnimeEpisodeMatch(anidbId, mapping.anidbSeason, anidbEpisode, provider, season, episode, AnimeListMatchKind.RANGE)
        }
        if (ranges.isNotEmpty()) return ranges

        val default = defaultSeason(provider)
        if (default == null || default.isAbsolute || default.number != season) return emptyList()
        val anidbEpisode = episode - defaultOffset(provider)
        if (anidbEpisode <= 0) return emptyList()
        return listOf(AnimeEpisodeMatch(anidbId, 1, anidbEpisode, provider, season, episode, AnimeListMatchKind.DEFAULT_MAPPING))
    }

    /**
     * Where regular episode [anidbEpisode] lies on [provider], by the same precedence as [resolveEpisode]: an explicit
     * `anidb-external` pair (`-0` says the provider has no such episode), a range, then the default season and offset.
     */
    fun externalEpisode(provider: AnimeListProvider, anidbEpisode: Int): ExternalEpisode? {
        if (anidbEpisode <= 0) return null
        val regular = mappings.filter { it.provider == provider && it.anidbSeason == 1 }
        for (mapping in regular) {
            val pair = mapping.explicit.firstOrNull { it.anidbEpisode == anidbEpisode } ?: continue
            return pair.externalEpisodes.firstOrNull()?.let { ExternalEpisode(provider, mapping.externalSeason, it) }
        }
        for (mapping in regular) {
            val start = mapping.start ?: continue
            val end = mapping.end
            if (anidbEpisode < start || (end != null && anidbEpisode > end)) continue
            val episode = anidbEpisode + mapping.offset
            if (episode > 0) return ExternalEpisode(provider, mapping.externalSeason, episode)
        }
        val default = defaultSeason(provider)
        val season = default?.number ?: return null
        val episode = anidbEpisode + defaultOffset(provider)
        return if (episode > 0) ExternalEpisode(provider, season, episode) else null
    }

    private fun defaultSeason(provider: AnimeListProvider) = when (provider) {
        AnimeListProvider.TVDB -> defaultTvdbSeason
        AnimeListProvider.TMDB -> tmdbSeason
    }

    private fun defaultOffset(provider: AnimeListProvider) = when (provider) {
        AnimeListProvider.TVDB -> episodeOffset
        AnimeListProvider.TMDB -> tmdbOffset
    }
}

/**
 * One `<mapping>`: AniDB season [anidbSeason] (1 regular, 0 specials) onto [externalSeason] of [provider], either as
 * explicit `;anidb-external;` pairs or as the range [start]..[end] shifted by [offset].
 */
internal class AnimeListEpisodeMapping(
    val anidbSeason: Int,
    val provider: AnimeListProvider,
    val externalSeason: Int,
    val start: Int? = null,
    val end: Int? = null,
    val offset: Int = 0,
    val explicit: List<AnimeListExplicitEpisode> = emptyList(),
) {
    fun matches(provider: AnimeListProvider, season: Int) = this.provider == provider && externalSeason == season
}

/** `anidb-e1+e2`: one AniDB episode as one or more external episodes; none when the list says `-0`. */
internal class AnimeListExplicitEpisode(val anidbEpisode: Int, val externalEpisodes: List<Int>)

/** An external episode matched to an AniDB entry's episode. */
internal data class AnimeEpisodeMatch(
    val anidbId: Int,
    val anidbSeason: Int,
    val anidbEpisode: Int,
    val provider: AnimeListProvider,
    val externalSeason: Int,
    val externalEpisode: Int,
    val kind: AnimeListMatchKind,
) {
    fun sameEntry(other: AnimeEpisodeMatch) = anidbId == other.anidbId && anidbSeason == other.anidbSeason

    fun sameEpisode(other: AnimeEpisodeMatch) = sameEntry(other) && anidbEpisode == other.anidbEpisode && kind == other.kind
}

/** `;1-2;3-4+5;6-0;` → pairs; a malformed pair is skipped. */
private fun parseExplicit(raw: String): List<AnimeListExplicitEpisode> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()
    return trimmed.split(';').mapNotNull { segment ->
        val item = segment.trim()
        val separator = item.indexOf('-')
        if (separator <= 0 || separator == item.length - 1) return@mapNotNull null
        val anidbEpisode = flexibleInt(item.substring(0, separator)) ?: return@mapNotNull null
        val targets = item.substring(separator + 1).split('+').mapNotNull { flexibleInt(it.trim()) }
        if (targets.isEmpty()) return@mapNotNull null
        // `-0`: the provider has no such episode. Kept (as an empty list) so the reverse direction knows.
        AnimeListExplicitEpisode(anidbEpisode, targets.filter { it != 0 })
    }
}

/** A decimal integer, surrounding blanks allowed; anything else is `null`. */
private fun flexibleInt(value: String?): Int? {
    val t = value?.trim() ?: return null
    if (t.isEmpty() || t.length > 10) return null
    val digits = if (t[0] == '-') t.substring(1) else t
    if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
    return t.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}
