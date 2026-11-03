package com.edde746.tortuga.playback.session

import com.edde746.tortuga.downloads.DownloadManager
import com.edde746.tortuga.downloads.LocalVideo
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.playback.resolve.ResolvedStream

/**
 * One profile's completed downloads as playback uses them ([DownloadManager]): the next episode plays from its download
 * when there is one, a watch-together guest tries its download of the room's video first, and the download playing is
 * left alone by auto-remove.
 */
internal interface LocalVideos {
    /** [videoId] of [metaId], ready to play from the device; null when it isn't downloaded. */
    suspend fun localVideo(metaId: String, videoId: String): LocalVideo?

    /** [downloadId] started playing (until [playbackEnded]). */
    fun playbackStarted(downloadId: Long)

    fun playbackEnded(downloadId: Long)
}

/** This device's downloads as [profileId]'s playback sees them. */
internal fun DownloadManager.localVideos(profileId: String): LocalVideos {
    val manager = this
    return object : LocalVideos {
        override suspend fun localVideo(metaId: String, videoId: String): LocalVideo? = manager.localVideo(profileId, metaId, videoId)

        override fun playbackStarted(downloadId: Long) = manager.playbackStarted(downloadId)

        override fun playbackEnded(downloadId: Long) = manager.playbackEnded(downloadId)
    }
}

/** Plays [videoId] of [meta] from its download [local]: the stream it was downloaded from is the request's stream. */
internal fun localPlayRequest(meta: MetaItem, videoId: String, local: LocalVideo): PlayRequest =
    PlayRequest(meta, videoId, local.stream, local = local)

/**
 * The file as the session opens it and reports it ([OpenedFile.resolved]): its path, no headers, and the size, hash and
 * name watch together matches files by.
 */
internal fun LocalVideo.resolved(): ResolvedStream =
    ResolvedStream(url = path, headers = emptyMap(), size = size, videoHash = videoHash, filename = filename)
