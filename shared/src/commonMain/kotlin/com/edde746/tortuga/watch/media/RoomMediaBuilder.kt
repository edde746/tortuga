package com.edde746.tortuga.watch.media

import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.StremioId
import com.edde746.tortuga.playback.resolve.ResolvedStream
import com.edde746.tortuga.playback.session.StreamChoice
import com.edde746.tortuga.playback.session.episodeLine
import com.edde746.tortuga.playback.streams.StreamBadgeParser
import com.edde746.tortuga.trackers.ResolvedIds
import com.edde746.tortuga.trackers.TrackerIdResolver
import com.edde746.tortuga.trackers.TrackerItem
import com.edde746.tortuga.trackers.ids.MetaIds
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The host's side of a room's media: what its player opened, as a [RoomMedia] the guests find their own files of.
 *
 * The mapped ids, for guests whose addons don't answer the host's own ids, come from what is known without asking
 * anyone, the first source that places the video winning:
 * - an IMDb or TMDB video id itself (`tt…:1:2`, `tmdb:…:1:2`; a video id of the addon's own naming is placed by its
 *   season and episode under an IMDb or TMDB meta id), the meta adding the other catalog id ([MetaIds]);
 * - the video's IMDb episode that anime addons add ([com.edde746.tortuga.model.Video.imdb]);
 * - for an anime id (`kitsu:`, `mal:`, `anilist:`, `anidb:`), [ids] (the trackers' resolver, when the trackers are
 *   installed) with anime ids, waited for [idTimeout] at most: it may have to download its mapping datasets, which
 *   carry on for the next time;
 * - else the meta's own IMDb and TMDB ids (`imdb_id`, links), with no season and episode.
 *
 * Whatever none of them knows is left out.
 */
class RoomMediaBuilder internal constructor(
    private val ids: TrackerIdResolver?,
    private val idTimeout: Duration = ID_TIMEOUT,
) {
    /**
     * The media for [videoId] of [meta] played from [choice], once its file is open: [resolved] is the stream
     * resolver's answer for it when there was one, [durationMs] the engine's duration. The title and episode line are
     * the ones the player shows.
     */
    suspend fun build(meta: MetaItem, videoId: String, choice: StreamChoice, resolved: ResolvedStream?, durationMs: Long): RoomMedia {
        val video = meta.videos.firstOrNull { it.id == videoId }
        val mapped = mappedIds(meta, videoId)
        return RoomMedia(
            type = meta.type,
            metaId = meta.id,
            videoId = videoId,
            imdbId = mapped.imdb,
            season = mapped.season,
            episode = mapped.episode,
            tmdbId = mapped.tmdb?.toString(),
            title = meta.name.ifBlank { video?.title.orEmpty() },
            episodeLine = video?.episodeLine(),
            fingerprint = fingerprint(choice, resolved, durationMs),
            year = meta.preview.year,
            episodeTitle = video?.title?.takeIf { it.isNotBlank() },
        )
    }

    private class MappedIds(val imdb: String?, val tmdb: Int?, val season: Int? = null, val episode: Int? = null)

    private suspend fun mappedIds(meta: MetaItem, videoId: String): MappedIds {
        val known = MetaIds.of(meta, videoId)
        val tmdb = if (meta.type == MOVIE) known.tmdbMovie else known.tmdbTv
        val id = StremioId.ofVideo(meta.preview.stremioId, videoId, meta)
        if (id is StremioId.Imdb) return MappedIds(id.id.value, tmdb, id.season, id.episode)
        if (id is StremioId.Tmdb) return MappedIds(known.imdb, id.id, id.season, id.episode)
        known.video?.let { return MappedIds(it.id, tmdb, it.season, it.episode) }
        val resolved = if (id is StremioId.Anime) animeIds(meta, videoId) else null
        val placed = resolved?.season != null && resolved.episode != null
        return MappedIds(
            imdb = resolved?.ids?.imdb ?: known.imdb,
            tmdb = resolved?.ids?.tmdb ?: tmdb,
            season = resolved?.season?.takeIf { placed },
            episode = resolved?.episode?.takeIf { placed },
        )
    }

    /** The trackers' resolver's ids for an anime video, or null when it isn't there, knows none or takes too long. */
    private suspend fun animeIds(meta: MetaItem, videoId: String): ResolvedIds? {
        val resolver = ids ?: return null
        return try {
            withTimeoutOrNull(idTimeout) { resolver.resolve(TrackerItem(meta.id, meta.type, videoId, meta), animeIds = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, e) { "id mapping failed for $videoId" }
            null
        }
    }

    internal companion object {
        /** How long the host's media waits for the trackers' resolver. */
        val ID_TIMEOUT: Duration = 5.seconds

        private const val MOVIE = "movie"
        private const val TAG = "watch"

        /**
         * The file [choice] plays: [resolved]'s size, hash and name where it has them, else the stream's hints; the
         * badges its text names. A size the stream's text names is rounded, so it is never sent as the file's.
         */
        fun fingerprint(choice: StreamChoice, resolved: ResolvedStream?, durationMs: Long): FileFingerprint {
            val stream = choice.stream
            val hints = stream.behaviorHints
            val badges = StreamBadgeParser.parse(stream)
            return FileFingerprint(
                durationMs = durationMs,
                sizeBytes = resolved?.size ?: hints.videoSize,
                videoHash = resolved?.videoHash ?: hints.videoHash,
                filename = hints.filename ?: resolved?.filename,
                resolution = badges.resolution?.label,
                source = badges.source?.label,
                codec = badges.videoCodec?.label,
            )
        }
    }
}
