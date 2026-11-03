package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.addons.PlannedRequest
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.library.TitleMark
import com.edde746.tortuga.data.library.libraryName
import com.edde746.tortuga.data.library.posterShapeOf
import com.edde746.tortuga.model.ManifestCatalog
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.StreamPlayability
import com.edde746.tortuga.model.videoIsTitle
import com.edde746.tortuga.playback.session.PlaybackSession
import com.edde746.tortuga.presentation.detail.DetailRatings
import com.edde746.tortuga.presentation.menu.DownloadMarks
import com.edde746.tortuga.presentation.menu.WatchlistMarks
import com.edde746.tortuga.presentation.menu.menuDownload
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.trackers.ratings.RatingSource
import com.edde746.tortuga.trackers.ratings.TitleRating

// Text and cards shared by the Board, Discover, Search and Library stores.

/**
 * A content type as a heading or a filter: "Movies", "Series", "All" (Stremio's type of a catalog that mixes them, and
 * Library's chip for no type); a type this app doesn't know is shown as the addon wrote it.
 */
internal fun typeLabel(type: String): String = knownTypeLabel(type) ?: type.replaceFirstChar(Char::uppercaseChar)

/** [typeLabel] inside a longer text: a type this app doesn't know, the addon's word, isolated. */
internal fun typeLabelInText(type: String): String = knownTypeLabel(type) ?: isolate(typeLabel(type))

private fun knownTypeLabel(type: String): String? = when (type) {
    "all" -> T.browse.type.all
    "movie" -> T.browse.type.movie
    "series" -> T.browse.type.series
    "channel" -> T.browse.type.channel
    "tv" -> T.browse.type.tv
    "other" -> T.browse.type.other
    else -> null
}

/** stremio-core `TYPE_PRIORITIES`: known types first, `other` last, anything else in between. */
private val TYPE_PRIORITIES = mapOf("all" to 5, "movie" to 4, "series" to 3, "channel" to 2, "tv" to 1, "other" to Int.MIN_VALUE)

/**
 * Content types in Stremio's order (stremio-core `compare_with_priorities` over [TYPE_PRIORITIES], highest first): all,
 * movie, series, channel, tv, then the types it doesn't know by name, `other` last. Discover's types and Library's.
 */
internal val typeOrder = Comparator<String> { a, b ->
    val pa = TYPE_PRIORITIES[a]
    val pb = TYPE_PRIORITIES[b]
    when {
        pa != null && pb != null -> pb.compareTo(pa)
        pa != null -> if (pa == Int.MIN_VALUE) 1 else -1
        pb != null -> if (pb == Int.MIN_VALUE) -1 else 1
        else -> a.compareTo(b)
    }
}

/** The catalog's name, else its id (as Stremio shows it). */
internal val ManifestCatalog.title: String get() = name?.trim()?.takeIf { it.isNotEmpty() } ?: id

/** "Popular · Movies": a catalog's or a list's [name] (isolated) and its [type] as a row's title. */
internal fun rowTitle(name: String, type: String): String = T.browse.rowTitle(isolate(name), typeLabelInText(type))

/** "Popular · Movies". */
internal fun catalogRowTitle(catalog: ManifestCatalog): String = rowTitle(catalog.title, catalog.type)

/** The catalog [planned] asks for, by type and id. */
internal fun PlannedRequest.catalog(): ManifestCatalog? =
    addon.manifest.catalogs.firstOrNull { it.type == request.path.type && it.id == request.path.id }

/**
 * A stable key per planned catalog, without the transport URL (a secret): `addonId/type/catalogId`, and `#2`, `#3`
 * for the same catalog of an addon installed more than once.
 */
internal fun rowKeys(plan: List<PlannedRequest>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return plan.map { planned ->
        val base = "${planned.addon.manifest.id}/${planned.request.path.type}/${planned.request.path.id}"
        val count = (seen[base] ?: 0) + 1
        seen[base] = count
        if (count == 1) base else "$base#$count"
    }
}

internal fun MetaItemPreview.toCard(): MetaCard = MetaCard(
    id = id,
    type = type,
    title = name.trim(),
    year = releaseInfo?.trim()?.takeIf { it.isNotEmpty() } ?: this.year?.toString(),
    // As Detail's ratings row writes the addon's IMDb rating.
    rating = TitleRating.of(RatingSource.IMDB, imdbRating, votes = null, origin = null)?.let(DetailRatings::text),
    posterUrl = poster,
    posterShape = posterShape,
    backgroundUrl = background,
    preview = this,
    trailer = trailerStreams.any { it.playability !is StreamPlayability.Unsupported },
)

/** Cards for a catalog page; an id listed twice is shown once, where it first appears. */
internal fun List<MetaItemPreview>.toCards(): List<MetaCard> = distinctBy { it.id }.map { it.toCard() }

/**
 * The cards with their titles' library state from [marks] ([MetaCard.watched], [MetaCard.inLibrary]), their
 * downloads' from [downloads] ([MetaCard.download]), their Watchlist entries from [watchlists]
 * ([MetaCard.watchlist]) and [caption] ([MetaCard.caption]); the same list when no card's differs, the same card where
 * it doesn't.
 */
internal fun List<MetaCard>.marked(
    marks: Map<String, TitleMark>,
    downloads: DownloadMarks?,
    watchlists: WatchlistMarks?,
    caption: Boolean,
): List<MetaCard> {
    fun MetaCard.marked(): MetaCard {
        val mark = marks[id]
        val watched = mark?.watched == true
        val inLibrary = mark?.inLibrary == true
        val download = downloads.menuDownload(type, id, videoId = null)
        val watchlist = watchlists?.menu(type, id)
        if (this.watched == watched && this.inLibrary == inLibrary && this.download == download && this.watchlist == watchlist && this.caption == caption) {
            return this
        }
        return copy(watched = watched, inLibrary = inLibrary, download = download, watchlist = watchlist, caption = caption)
    }
    return if (all { it.marked() === it }) this else map { it.marked() }
}

/**
 * The Continue Watching cards with their videos' downloads from [downloads] ([ContinueWatchingCard.download]) and their
 * titles' Watchlist entries from [watchlists] ([ContinueWatchingCard.watchlist]); the same list when no card's differs.
 */
internal fun List<ContinueWatchingCard>.withMenuMarks(downloads: DownloadMarks?, watchlists: WatchlistMarks?): List<ContinueWatchingCard> {
    fun ContinueWatchingCard.marked(): ContinueWatchingCard {
        val download = downloads.menuDownload(type, id, videoId)
        val watchlist = watchlists?.menu(type, id)
        return if (this.download == download && this.watchlist == watchlist) this else copy(download = download, watchlist = watchlist)
    }
    return if (all { it.marked() === it }) this else map { it.marked() }
}

/** What the library keeps of the card's title ([com.edde746.tortuga.presentation.menu.MediaActions]). */
internal fun MetaCard.snapshot() = MetaSnapshot(id, type, title, posterUrl, posterShape.libraryName)

internal fun ContinueWatchingCard.snapshot() = MetaSnapshot(id, type, title, posterUrl, posterShape.libraryName)

internal fun LibraryItem.toContinueWatchingCard(): ContinueWatchingCard {
    // Once an episode counts as watched the library queues the next one at 1 ms (LibraryRepository.advanceToVideo).
    val upNext = state.timeOffset <= 1
    val remaining = if (!upNext && state.duration > 0) state.duration - state.timeOffset else 0
    val code = episodeCode(id, state.videoId)
    val status = when {
        upNext -> T.browse.continueWatching.upNext
        remaining > 0 -> timeLeft(remaining)
        else -> null
    }
    return ContinueWatchingCard(
        id = id,
        type = type,
        videoId = state.videoId,
        title = name,
        detail = if (code != null && status != null) T.browse.continueWatching.detail(code, status) else code ?: status,
        progress = shownProgress()?.toFloat() ?: 0f,
        posterUrl = poster,
        posterShape = posterShapeOf(posterShape),
        watched = videoWatched(),
        inLibrary = !removed,
        resumes = resumes(),
    )
}

/** Positions under a second show no progress (`advanceToVideo` leaves 1 ms). */
private const val MIN_PROGRESS_MILLIS = 1000L

/** How far into its video the item got, 0 to 1, for a progress bar; null under a second in or without a duration. */
internal fun LibraryItem.shownProgress(): Double? =
    progress.takeIf { state.timeOffset >= MIN_PROGRESS_MILLIS && it > 0 }?.coerceAtMost(1.0)

/**
 * Play resumes the item's video (where [PlaybackSession.resumePoint] starts it): its menu offers Play from the
 * beginning.
 */
internal fun LibraryItem.resumes(): Boolean = state.videoId?.let { PlaybackSession.resumePoint(this, it) > 0 } == true

/**
 * The video to resume counts as watched: an episode played past the watched threshold
 * ([com.edde746.tortuga.data.library.LibraryItemState.flaggedWatched]), a movie watched before. What a Continue
 * Watching item's menu marks.
 */
internal fun LibraryItem.videoWatched(): Boolean =
    if (videoIsTitle(id, state.videoId)) isWatched else state.flaggedWatched == 1L

/**
 * The numbers in a video id that follows Stremio's `{metaId}:{season}:{episode}` convention (season and episode), or
 * `{metaId}:{episode}` (the episode alone, as Kitsu ids go); null for a movie or any other id, a negative number
 * included.
 */
internal fun episodeNumbers(metaId: String, videoId: String?): List<Int>? {
    if (videoId == null || !videoId.startsWith("$metaId:")) return null
    return videoId.substring(metaId.length + 1).split(':').map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }.takeIf { it.size in 1..2 }
}

/** The episode in a video id ([episodeNumbers]) as text: "S1E2", or "Episode 5"; null for a movie or any other id. */
internal fun episodeCode(metaId: String, videoId: String?): String? {
    val numbers = episodeNumbers(metaId, videoId) ?: return null
    return if (numbers.size == 2) T.session.episode(numbers[0].toString(), numbers[1].toString()) else T.browse.continueWatching.episode(numbers[0].toString())
}

/** "12 min left", "1 h left", "1 h 5 min left"; a part of a minute counts as a minute. */
internal fun timeLeft(millis: Long): String {
    val minutes = ((millis + 59_999) / 60_000).coerceAtLeast(1)
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0L -> T.browse.continueWatching.minutesLeft(rest.toString())
        rest == 0L -> T.browse.continueWatching.hoursLeft(hours.toString())
        else -> T.browse.continueWatching.hoursMinutesLeft(hours.toString(), rest.toString())
    }
}
