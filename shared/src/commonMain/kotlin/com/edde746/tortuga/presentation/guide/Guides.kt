package com.edde746.tortuga.presentation.guide

import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.ManifestCatalog
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.Programme
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.Resources
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.presentation.calendar.civilDate
import com.edde746.tortuga.presentation.calendar.isoDate
import com.edde746.tortuga.presentation.calendar.localDay
import kotlin.time.Instant

/**
 * A guide catalog of an installed addon: a catalog declaring the `date` extra on an addon whose manifest says
 * `epgProvider` (the addon SDK's `docs/epg.md`). [required] holds the first option of each of its other required
 * extras; `date` and `skip` are the guide's own.
 *
 * [key] is stable across updates and never holds the transport URL (a secret): `org.example.livetv/tv/channels`, and
 * `#2`, `#3` for the same catalog of an addon installed more than once ([Guides.catalogs]).
 */
internal class GuideCatalog(val addon: Descriptor, val catalog: ManifestCatalog, private val required: List<ExtraValue>, val key: String) {
    /** The same catalog of the same installation as [other] (whose manifest may have changed). */
    fun sameAs(other: GuideCatalog): Boolean =
        addon.transportUrl == other.addon.transportUrl && catalog.type == other.catalog.type && catalog.id == other.catalog.id

    val pages: Boolean get() = catalog.extraProp(Extras.SKIP) != null

    /** The request for the UTC day [date] (`2026-09-12`), `date` first, then `skip` (when past the first page). */
    fun request(date: String, skip: Int = 0): ResourceRequest = ResourceRequest(
        addon.transport,
        ResourcePath(
            Resources.CATALOG,
            catalog.type,
            catalog.id,
            listOf(ExtraValue(Extras.DATE, date)) + listOfNotNull(skip.takeIf { it > 0 }?.let { ExtraValue(Extras.SKIP, it.toString()) }) + required,
        ),
    )
}

/** One channel of a guide and its programmes, ordered by start. */
internal class ChannelSchedule(val channel: MetaItemPreview, val programmes: List<Pair<Video, Programme>>)

/**
 * The guide's rules (stremio-core `LiveTvGuide` and `MetaItem::programs_at`): which catalogs are guides, which UTC days
 * a local day asks for, and how the pages' channels and programmes merge into the day's schedule.
 */
internal object Guides {
    const val DAY_MILLIS = 86_400_000L

    /**
     * The guide catalogs of [addons], in the installed order and each addon's catalog order. A catalog with another
     * required extra that has no options can't be asked for and isn't one.
     */
    fun catalogs(addons: List<Descriptor>): List<GuideCatalog> {
        val seen = mutableMapOf<String, Int>()
        return addons
            .filter { it.manifest.behaviorHints.epgProvider }
            .flatMap { addon ->
                addon.manifest.catalogs.filter { it.declaresDate }.mapNotNull { catalog ->
                    val required = catalog.extra.filter { it.isRequired && it.name != Extras.DATE && it.name != Extras.SKIP }.map { prop ->
                        ExtraValue(prop.name, prop.options.firstOrNull() ?: return@mapNotNull null)
                    }
                    val base = "${addon.manifest.id}/${catalog.type}/${catalog.id}"
                    val count = (seen[base] ?: 0) + 1
                    seen[base] = count
                    GuideCatalog(addon, catalog, required, if (count == 1) base else "$base#$count")
                }
            }
    }

    /**
     * When the local day [epochDay] starts: its midnight at the offset in force at UTC midnight or at the one in force
     * then, whichever is the first instant of that day, so a change of offset at local midnight (summer time starting
     * or ending there) moves it, as [com.edde746.tortuga.presentation.calendar.nextLocalMidnight] does.
     */
    fun dayStart(epochDay: Long, offsetSeconds: (Long) -> Int): Long {
        val utcMidnight = epochDay * DAY_MILLIS
        val guess = utcMidnight - offsetSeconds(utcMidnight) * 1000L
        val corrected = utcMidnight - offsetSeconds(guess) * 1000L
        return listOf(guess, corrected).filter { localDay(it, offsetSeconds) >= epochDay }.minOrNull() ?: corrected
    }

    /**
     * The UTC days (`2026-09-12`) the local window from [start] to [end] (excluded) overlaps: one when local midnight
     * is UTC midnight, else two. The addon SDK's `date` is a UTC day.
     */
    fun utcDates(start: Long, end: Long): List<String> =
        (start.floorDiv(DAY_MILLIS)..(end - 1).floorDiv(DAY_MILLIS)).map(::isoDate)

    /** ISO day of week of [epochDay]: 1 Monday … 7 Sunday (1970-01-01 was a Thursday). */
    fun isoDayOfWeek(epochDay: Long): Int = (epochDay + 3).mod(7) + 1

    /** Day of the month of [epochDay]. */
    fun dayOfMonth(epochDay: Long): Int = civilDate(epochDay).third

    /**
     * The schedule from [pages] (the ready pages in request order) between [start] and [end]: each channel once, in
     * the order it first came, its programmes from every page merged (a programme spanning UTC midnight comes in both
     * days' pages: once by id), those overlapping the window only, by start. A video that isn't a programme is left
     * out; a channel without programmes stays.
     */
    fun schedule(pages: List<List<MetaItem>>, start: Instant, end: Instant): List<ChannelSchedule> {
        val channels = LinkedHashMap<String, Pair<MetaItemPreview, MutableList<Pair<Video, Programme>>>>()
        for (item in pages.flatten()) {
            val entry = channels.getOrPut(item.id) { item.preview to mutableListOf() }
            item.videos.forEach { video ->
                val programme = video.programme ?: return@forEach
                if (programme.start < end && programme.end > start) entry.second += video to programme
            }
        }
        return channels.values.map { (channel, programmes) ->
            ChannelSchedule(channel, programmes.distinctBy { it.first.id }.sortedBy { it.second.start })
        }
    }

    /**
     * The programme on air at [nowMillis] (the latest to start, should two overlap) and the next one: the first to
     * start after [nowMillis], and not before the current one ends. Indices into [programmes], ordered by start.
     */
    fun nowAndNext(programmes: List<GuideProgramme>, nowMillis: Long): Pair<Int?, Int?> {
        val current = programmes.indices.lastOrNull { programmes[it].startMillis <= nowMillis && nowMillis < programmes[it].endMillis }
        val currentEnd = current?.let { programmes[it].endMillis }
        val next = programmes.indices.firstOrNull { i ->
            programmes[i].startMillis > nowMillis && (currentEnd == null || programmes[i].startMillis >= currentEnd)
        }
        return current to next
    }
}
