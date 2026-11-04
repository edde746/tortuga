package com.edde746.tortuga.presentation.downloads

import com.edde746.tortuga.downloads.Download
import com.edde746.tortuga.downloads.DownloadFailure
import com.edde746.tortuga.downloads.DownloadFailureKind
import com.edde746.tortuga.downloads.DownloadStatus
import com.edde746.tortuga.strings.T

/** Waiting for its turn, resolving or moving bytes. */
internal val DownloadStatus.active: Boolean
    get() = this == DownloadStatus.QUEUED || this == DownloadStatus.RESOLVING || this == DownloadStatus.DOWNLOADING

/** Failed, and `DownloadManager.retry` can queue it again: it has a stream (one without needs a pick by hand). */
internal val Download.retryable: Boolean
    get() = status == DownloadStatus.FAILED && failure?.kind != DownloadFailureKind.NO_STREAM

/** Failed for want of a stream: an episode of a season download no stream could be picked for. */
internal val Download.needsStream: Boolean
    get() = status == DownloadStatus.FAILED && failure?.kind == DownloadFailureKind.NO_STREAM

/** The texts downloads share between screens. */
internal object DownloadText {
    /** A fraction as a whole percentage, "45". */
    fun percent(fraction: Double): String = (fraction * 100).toInt().coerceIn(0, 100).toString()

    /**
     * Why a download failed, worded. [cacheWording]: the build may speak of instant (cached) streams (not on Apple,
     * whose builds never mention instant or ready state).
     */
    fun failure(failure: DownloadFailure, cacheWording: Boolean): String {
        val text = T.downloads.failure
        return when (failure.kind) {
            DownloadFailureKind.NO_STREAM -> if (cacheWording) text.noStreamInstant else text.noStream
            DownloadFailureKind.NETWORK -> text.network
            DownloadFailureKind.HTTP -> failure.httpStatus?.let { text.http(it.toString()) } ?: text.network
            DownloadFailureKind.STREAM_GONE -> text.streamGone
            DownloadFailureKind.DISK_FULL -> text.diskFull
            DownloadFailureKind.NOT_ENOUGH_SPACE -> text.notEnoughSpace
            DownloadFailureKind.SIZE_MISMATCH -> text.sizeMismatch
            DownloadFailureKind.UNSUPPORTED -> text.unsupported
            DownloadFailureKind.ERROR_VIDEO -> T.player.errorVideo.sent
            DownloadFailureKind.LOCATION_UNAVAILABLE -> text.locationUnavailable
            DownloadFailureKind.FILE_MISSING -> text.fileMissing
            DownloadFailureKind.STORAGE -> text.storage
        }
    }
}
