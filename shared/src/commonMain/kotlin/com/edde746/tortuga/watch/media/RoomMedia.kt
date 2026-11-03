package com.edde746.tortuga.watch.media

import com.edde746.tortuga.model.ImdbId
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.model.videoIsTitle
import com.edde746.tortuga.playback.session.episodeLine
import com.edde746.tortuga.watch.partyText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import party.openwatch.Media
import party.openwatch.MediaFile
import party.openwatch.MediaKind
import party.openwatch.Series
import party.openwatch.checkMedia

/**
 * What a watch-together room plays, as Tortuga reads it: a title, not a file. Each participant plays its own file of it,
 * found through its own addons. On the wire it is an Open Watch Party media descriptor ([toMedia], [roomMedia]): the
 * mapped ids go into `ids` and `series.ids`, the host's file into `durationMs` and `file`, and Tortuga's own ids (the
 * Stremio type, meta id and video id) into `ext["app.usetortuga"]`, so a Tortuga guest asks its addons the host's own
 * question. A descriptor from another app carries only ids: the IMDb or TMDB id, with the season and episode for an
 * episode, become the Cinemeta-style ids addons take.
 *
 * @property type the meta's type (`movie`, `series`, ...), as addons take it.
 * @property metaId the meta's id; empty when a descriptor gave nothing an addon can be asked about.
 * @property videoId the video's id (a movie's is its meta id).
 * @property imdbId the title's IMDb id (`tt...`) when known; with [season] and [episode] for an episode.
 * @property tmdbId the title's TMDB id when known (digits only), per type: a movie's or a series'.
 * @property title the title as the host's player shows it (the series' for an episode).
 * @property episodeLine the episode line ("S1 E3 · Title") for an episode.
 * @property fingerprint the host's file.
 * @property year the title's release year, when known.
 * @property episodeTitle the episode's own title, when known.
 */
data class RoomMedia(
    val type: String,
    val metaId: String,
    val videoId: String,
    val imdbId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val tmdbId: String? = null,
    val title: String,
    val episodeLine: String? = null,
    val fingerprint: FileFingerprint? = null,
    val year: Int? = null,
    val episodeTitle: String? = null,
)

/**
 * The host's file, as a guest ranks its candidates against it ([CandidateRanking]).
 *
 * @property durationMs the engine's duration once the file opened (the descriptor's `durationMs`).
 * @property sizeBytes the file's exact size from the stream resolver, when the server reported one.
 * @property videoHash the OpenSubtitles hash (16 lowercase hex digits) from the stream resolver, else the stream's hints.
 * @property filename the stream's file name (`behaviorHints.filename`, or the resolver's).
 * @property resolution / [source] / [codec] the badges parsed from the stream's name, description and file name
 *   ("2160p", "WEB-DL", "HEVC"); the host's own, never sent.
 */
data class FileFingerprint(
    val durationMs: Long,
    val sizeBytes: Long? = null,
    val videoHash: String? = null,
    val filename: String? = null,
    val resolution: String? = null,
    val source: String? = null,
    val codec: String? = null,
)

/**
 * The `ext` member Tortuga's own ids travel under (Open Watch Party's SPEC.md, section 10: the owning app's
 * reverse-DNS name).
 */
internal const val TORTUGA_EXT = "app.usetortuga"

/**
 * The descriptor `setMedia` sends for [this], the host's file (whose [RoomMedia.fingerprint] is required). Its titles
 * are as Open Watch Party takes text ([partyText]: no line break, tab or bidi mark, at most [MAX_TITLE] code points).
 * A file name or own ids the SDK's checks refuse are left out; anything else they refuse fails loudly.
 */
internal fun RoomMedia.toMedia(): Media {
    val file = checkNotNull(fingerprint) { "a room's media needs the host's file" }
    val placed = season != null && episode != null
    val isEpisode = type != MOVIE && (placed || !videoIsTitle(metaId, videoId))
    val titleIds = buildMap {
        imdbId?.takeIf { ImdbId.parse(it) != null }?.let { put("imdb", it) }
        tmdbId?.takeIf { TMDB.matches(it) && (isEpisode || type == MOVIE) }?.let { put("tmdb", it) }
    }
    val name = partyText(title, MAX_TITLE).ifEmpty { partyText(metaId, MAX_TITLE) }
    val episodeName = episodeTitle?.let { partyText(it, MAX_TITLE) }?.takeIf { it.isNotEmpty() }
    val fileName = file.filename?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() && it != "." && it != ".." }
    val own = listOf(type, metaId, videoId).takeIf { ids -> ids.all { it.isNotBlank() && it.length <= MAX_OWN_ID && !it.locates() } }
    fun media(withName: Boolean, withExt: Boolean) = Media(
        kind = if (isEpisode) MediaKind.EPISODE else MediaKind.MOVIE,
        title = if (isEpisode) episodeName ?: episode?.let { "Episode $it" } ?: name else name,
        durationMs = file.durationMs,
        year = year?.toLong()?.takeIf { !isEpisode },
        ids = if (isEpisode) emptyMap() else titleIds,
        series = if (isEpisode) Series(title = name, year = year?.toLong(), ids = titleIds) else null,
        season = season?.toLong()?.takeIf { isEpisode && it >= 0 },
        episode = episode?.toLong()?.takeIf { isEpisode && it >= 0 },
        file = MediaFile(
            sizeBytes = file.sizeBytes?.takeIf { it >= 0 },
            oshash = file.videoHash,
            name = fileName.takeIf { withName },
        ),
        ext = own?.takeIf { withExt }?.let { (ty, mi, vi) ->
            buildJsonObject { put(TORTUGA_EXT, buildJsonObject { put("ty", ty); put("mi", mi); put("vi", vi) }) }
        },
    )
    val full = media(withName = true, withExt = true)
    if (checkMedia(full) == null) return full
    val trimmed = media(withName = false, withExt = false)
    checkMedia(trimmed)?.let { error("the room's media fails the descriptor checks: $it") }
    return trimmed
}

/**
 * [media], a descriptor from any app, as Tortuga reads it: Tortuga's own ids when it carries them, else the IMDb id,
 * then the TMDB one, as Cinemeta-style ids (`tt...`, `tt...:season:episode`, `tmdb:...`). Without either the meta id is
 * empty, and no addon is asked.
 */
internal fun roomMedia(media: Media): RoomMedia {
    val isEpisode = media.kind == MediaKind.EPISODE
    val titleIds = if (isEpisode) media.series?.ids.orEmpty() else media.ids
    val imdb = titleIds["imdb"]?.takeIf { ImdbId.parse(it) != null }
    val tmdb = titleIds["tmdb"]?.takeIf { TMDB.matches(it) }
    val season = media.season?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    val episode = media.episode?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    val own = (media.ext?.get(TORTUGA_EXT) as? JsonObject)?.let { o ->
        listOf("ty", "mi", "vi").map { key -> (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= MAX_OWN_ID } }
    }?.takeIf { ids -> ids.all { it != null } }?.map { checkNotNull(it) }
    val metaId = imdb ?: tmdb?.let { "tmdb:$it" } ?: ""
    val placed = season != null && episode != null
    val (type, ownMeta, ownVideo) = own ?: when {
        isEpisode -> listOf(SERIES, metaId, if (placed && metaId.isNotEmpty()) "$metaId:$season:$episode" else metaId)
        else -> listOf(MOVIE, metaId, metaId)
    }
    val title = if (isEpisode) media.series?.title ?: media.title else media.title
    return RoomMedia(
        type = type,
        metaId = ownMeta,
        videoId = ownVideo,
        imdbId = imdb,
        season = season,
        episode = episode,
        tmdbId = tmdb,
        title = title,
        episodeLine = if (isEpisode) Video(ownVideo, media.title, seriesInfo = if (placed) SeriesInfo(checkNotNull(season), checkNotNull(episode)) else null).episodeLine() else null,
        fingerprint = FileFingerprint(
            durationMs = media.durationMs,
            sizeBytes = media.file?.sizeBytes,
            videoHash = media.file?.oshash,
            filename = media.file?.name,
        ),
        year = (media.year ?: media.series?.year)?.toInt(),
        episodeTitle = media.title.takeIf { isEpisode },
    )
}

/**
 * A value that would locate or fetch media (a URL or a peer-to-peer link), which a descriptor never carries (Open Watch
 * Party's SPEC.md, section 10).
 */
private fun String.locates(): Boolean = contains("://") || startsWith("magnet:", ignoreCase = true)

private const val MOVIE = "movie"
private const val SERIES = "series"
private const val MAX_OWN_ID = 200

/**
 * A title's length in a descriptor, in code points: the descriptor's whole must stay within 4 KB (Open Watch Party's
 * SDK.md, section 9.7).
 */
private const val MAX_TITLE = 200
private val TMDB = Regex("^\\d+$")
