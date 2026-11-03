package com.edde746.tortuga.presentation.calendar

import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.LibraryItemState
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.presentation.board.catalogAddon
import com.edde746.tortuga.presentation.board.catalogPath
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.decodeURLQueryComponent
import kotlin.time.Instant

// Shared by the episode feed and Calendar store tests.

/** An addon with stremio-core's two id catalogs: the series' newest episodes and their calendar, up to 100 series a request. */
internal val EPISODES: Descriptor = catalogAddon(
    "episodes",
    """[
        {"type":"series","id":"last-videos","extra":[{"name":"lastVideosIds","isRequired":true,"optionsLimit":100}]},
        {"type":"series","id":"calendar-videos","extra":[{"name":"calendarVideosIds","isRequired":true,"optionsLimit":100}]}
    ]""",
)

/** `series/last-videos/lastVideosIds=tt1,tt2` for a request of `…/catalog/series/last-videos/lastVideosIds=tt1%2Ctt2.json`. */
internal val HttpRequestData.idsPath: String
    get() = catalogPath.decodeURLQueryComponent()

/**
 * [EPISODES]' answer to [request]: each asked series of [released] (id to its episodes' release dates) with its
 * episodes `<id>:1:<n>`, titled "Episode <n>".
 */
internal fun MockRequestHandleScope.episodesAnswer(request: HttpRequestData, released: Map<String, List<String>>): HttpResponseData {
    val asked = request.idsPath.substringAfterLast('=').split(',')
    val metas = asked.filter { it in released }.joinToString(",") { id ->
        val videos = released.getValue(id).withIndex().joinToString(",") { (index, date) ->
            """{"id":"$id:1:${index + 1}","title":"Episode ${index + 1}","released":"$date","season":1,"episode":${index + 1}}"""
        }
        """{"id":"$id","type":"series","name":"Series $id","background":"https://img.example/$id.jpg","videos":[$videos]}"""
    }
    return json("""{"metasDetailed":[$metas]}""")
}

/** A library title, last watched at [lastWatched], its new-episode notifications off with [noNotif]. */
internal fun libraryItem(id: String, type: String = "series", lastWatched: Instant? = null, noNotif: Boolean = false) = LibraryItem(
    id = id, type = type, name = "Series $id", poster = null, posterShape = "poster", removed = false, temp = false,
    ctime = 0, mtime = 0,
    state = LibraryItemState(
        lastWatched = lastWatched?.toEpochMilliseconds(), timeWatched = 0, timeOffset = 0, overallTimeWatched = 0,
        timesWatched = 0, flaggedWatched = 0, duration = 0, videoId = null, watchedVideos = null, noNotif = noNotif,
    ),
)
