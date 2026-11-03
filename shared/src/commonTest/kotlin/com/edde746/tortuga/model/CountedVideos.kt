package com.edde746.tortuga.model

import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * A long series' videos that count how often they are read ([reads]: each element read, by index or by an iterator),
 * so a test can bound the work a code path does per video.
 */
internal class CountedVideos(private val videos: List<Video>) : AbstractList<Video>() {
    var reads = 0L

    override val size: Int get() = videos.size

    override fun get(index: Int): Video {
        reads++
        return videos[index]
    }

    companion object {
        /** [count] episodes of season 1 of [id], `{id}:1:{n}`, released a day apart from [firstRelease] when given. */
        fun episodes(id: String, count: Int, firstRelease: Instant? = null): CountedVideos = CountedVideos(
            (1..count).map { n -> Video(id = "$id:1:$n", released = firstRelease?.plus((n - 1).days), seriesInfo = SeriesInfo(1, n)) },
        )
    }
}
