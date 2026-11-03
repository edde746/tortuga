package com.edde746.tortuga.presentation.calendar

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.AggrRequest
import com.edde746.tortuga.addons.mockClient
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.CacheHints
import com.edde746.tortuga.model.CountedVideos
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.model.TransportUrl
import com.edde746.tortuga.net.AddonResponseCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The Calendar of a library with a series of a thousand episodes, built again at every change (a month moved, an
 * answer, a kids verdict): its videos are gone through once, not at every change.
 */
class LongSeriesCalendarTest {
    @Test
    fun movingThroughTheMonthsOfALongSeriesGoesThroughItsVideosOnce() = runTest {
        // An episode a day from 1 January 2024, the thousandth on 26 September 2026.
        val videos = CountedVideos.episodes(ID, 1_000, firstRelease = Instant.parse("2024-01-01T05:00:00Z"))
        val meta = MetaItem(MetaItemPreview(id = ID, type = "series", name = "One Piece"), videos)
        val library = MutableStateFlow(listOf(libraryItem(ID)))
        val request = AggrRequest.CatalogsFiltered(Extras.CALENDAR_VIDEOS_IDS, listOf(ID to "series"), EpisodeFeed.LIMIT).plan(listOf(EPISODES)).single().request
        val cache = AddonResponseCache()
        cache.store(
            TransportUrl.resourceUrl(request.base, request.path),
            AddonResponse(ResourceContent.MetasDetailed(listOf(meta)), CacheHints(cacheMaxAge = 86_400)),
            bodySize = 1,
        )
        val transport = AddonTransport(mockClient { error("the answer is cached") }, cache, parseDispatcher = StandardTestDispatcher(testScheduler))
        val feed = EpisodeFeed(
            Extras.CALENDAR_VIDEOS_IDS, LibraryItem::onCalendar, library, MutableStateFlow(listOf(EPISODES)), MutableStateFlow(true),
            AddonAggregator(transport), backgroundScope, testScheduler.timeSource,
        )
        val store = CalendarStore(
            feed,
            library,
            object : CalendarHost {
                override fun openEpisode(type: String, id: String, videoId: String) = Unit
            },
            object : Clock {
                override fun now() = Instant.parse("2025-03-15T12:00:00Z")
            },
            backgroundScope,
        )
        runCurrent()
        assertEquals(31, store.state.value.days.sumOf { it.episodes }, "March 2025: an episode a day")

        repeat(25) { store.nextMonth() }
        assertEquals("April 2027", store.state.value.month)
        assertEquals(0, store.state.value.days.sumOf { it.episodes })
        repeat(25) { store.previousMonth() }
        assertEquals("March 2025", store.state.value.month)
        assertEquals((1..31).toList(), store.state.value.days.filter { it.episodes == 1 }.map { it.day })

        assertTrue(videos.reads <= 5 * 1_000, "${videos.reads} reads of 1000 videos")
    }

    private companion object {
        const val ID = "tt0388629"
    }
}
