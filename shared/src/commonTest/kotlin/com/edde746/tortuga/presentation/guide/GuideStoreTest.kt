@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.guide

import com.edde746.tortuga.addons.json
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.presentation.board.BrowseEnv
import com.edde746.tortuga.presentation.board.catalogPath
import com.edde746.tortuga.presentation.calendar.localDay
import com.edde746.tortuga.strings.T
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class GuideStoreTest {
    private class Host : GuideHost {
        val played = mutableListOf<MetaItemPreview>()
        override fun playChannel(channel: MetaItemPreview) {
            played += channel
        }
    }

    /**
     * The guide at 12:30 on 12 Sep 2026 in UTC+2 (10:30 UTC): the local day runs from 11 Sep 22:00 UTC to 12 Sep 22:00
     * UTC. [pages] answers a catalog request's path (`tv/channels/date=2026-09-12`); null: HTTP 500.
     */
    private class Fixture(scope: TestScope, addons: List<Descriptor>, offset: (Long) -> Int = { 7200 }, val pages: MutableMap<String, String> = mutableMapOf()) {
        val env = BrowseEnv(scope) { request ->
            pages[request.catalogPath]?.let { json(it) } ?: respond("", HttpStatusCode.InternalServerError)
        }
        val host = Host()
        val addons = MutableStateFlow(addons)
        val store = GuideStore(
            this.addons, env.aggregator, host, scope.backgroundScope,
            { Instant.fromEpochMilliseconds(NOW + scope.testScheduler.currentTime) }, offset,
        )
        val requests: List<String> get() = env.http.requests.map { it.catalogPath }
        val state: GuideUiState get() = store.state.value
    }

    private fun Fixture.programmes(channel: String) = state.channels.single { it.id == channel }.programmes.map { it.id }

    @Test
    fun aLocalDayAsksForEachUtcDayItOverlapsAndMergesTheirChannels() = runTest {
        val f = Fixture(this, listOf(LIVE), pages = day())
        runCurrent()
        assertEquals(listOf("tv/channels/date=2026-09-11", "tv/channels/date=2026-09-12"), f.requests)
        val state = f.state
        assertIs<GuideBody.Grid>(state.body)
        assertEquals(listOf("live:news", "live:sports"), state.channels.map { it.id }, "each channel once, in the order it first came")
        assertEquals(
            listOf("news:evening", "news:late", "news:morning", "news:noon", "news:night"),
            f.programmes("live:news"),
            "the programme across UTC midnight once, the one across local midnight in, the one before left out, by start",
        )
        val news = state.channels.first()
        assertEquals("news:morning", news.onNow?.let { news.programmes[it].id })
        assertEquals("news:noon", news.next?.let { news.programmes[it].id })
        val morning = news.programmes[2]
        assertTrue(Regex("12:00[\u0020\u202F]PM").matches(morning.startText), "12-hour English time: ${morning.startText}")
        assertEquals("${clock(12, 0)} – ${clock(13, 0)}", morning.timeText, "in the device's time zone")
        assertEquals("\u2068News\u2069 · 45 min · \u2068PG\u2069", morning.infoText, "the addon's genre and rating isolated, this app's runtime not")
        assertEquals("With \u2068Jane Doe\u2069, \u2068John Roe\u2069", morning.creditsText)
        assertEquals(Instant.parse("2026-09-11T22:00:00Z").toEpochMilliseconds(), state.dayStartMillis)
        assertEquals(Instant.parse("2026-09-12T22:00:00Z").toEpochMilliseconds(), state.dayEndMillis)
        assertEquals(listOf("Yesterday", "Today", "Tomorrow", "Mon 14"), state.days.take(4).map { it.label })
        assertEquals(listOf(false, true), state.days.take(2).map { it.selected })
        assertEquals(listOf(GuideCatalogChoice("org.example.livetv/tv/channels", "\u2068Channels\u2069 · \u2068Example Live TV\u2069", selected = true)), state.catalogs)
    }

    @Test
    fun aProgrammeOfHalfAnHourOrLessSaysOnlyItsStartInItsCell() = runTest {
        val pages = mutableMapOf(
            "tv/channels/date=2026-09-12" to page(
                channel(
                    "live:news",
                    programme("news:short", "2026-09-12T10:00:00Z", "2026-09-12T10:15:00Z"),
                    programme("news:half", "2026-09-12T10:15:00Z", "2026-09-12T10:45:00Z"),
                    programme("news:longer", "2026-09-12T10:45:00Z", "2026-09-12T11:16:00Z"),
                ),
            ),
        )
        val f = Fixture(this, listOf(LIVE), pages = pages)
        runCurrent()
        val programmes = f.state.channels.single().programmes
        assertEquals(
            listOf(clock(12, 0), clock(12, 15), "${clock(12, 45)} – ${clock(13, 16)}"),
            programmes.map { it.cellTimeText },
            "15 and 30 minutes: the start alone; 31 minutes: the range",
        )
        assertEquals(
            listOf("${clock(12, 0)} – ${clock(12, 15)}", "${clock(12, 15)} – ${clock(12, 45)}", "${clock(12, 45)} – ${clock(13, 16)}"),
            programmes.map { it.timeText },
            "the details keep the range",
        )
        assertEquals(listOf(clock(12, 0), clock(12, 15), clock(12, 45)), programmes.map { it.startText }, "every start alone")
    }

    @Test
    fun aProgrammeIsOnAirFromItsStartUntilItsEndAndItsHeadlineSaysSo() = runTest {
        val pages = mutableMapOf(
            "tv/channels/date=2026-09-11" to page(),
            "tv/channels/date=2026-09-12" to page(
                channel(
                    "live:news",
                    programme("news:ended", "2026-09-12T09:00:00Z", "2026-09-12T10:30:00Z"),
                    programme("news:on", "2026-09-12T10:30:00Z", "2026-09-12T11:00:00Z", ""","cast":["Jane Doe"],"directors":["John Roe"]"""),
                    programme("news:later", "2026-09-12T11:00:00Z", "2026-09-12T12:00:00Z"),
                ),
            ),
        )
        val f = Fixture(this, listOf(LIVE), pages = pages)
        runCurrent()
        val (ended, on, later) = f.state.channels.single().programmes
        assertEquals(listOf(false, true, false), listOf(ended.onAir, on.onAir, later.onAir), "on from its start (now), off at its end (now)")
        assertEquals(listOf(true, false, false), listOf(ended.past, on.past, later.past))
        assertEquals("On now · ${clock(12, 30)} – ${clock(13, 0)} · \u2068live:news\u2069", on.headline, "on now, the time, the channel isolated")
        assertEquals("${clock(11, 0)} – ${clock(12, 30)} · \u2068live:news\u2069", ended.headline)
        assertEquals("${clock(13, 0)} – ${clock(14, 0)} · \u2068live:news\u2069", later.headline)
        assertEquals("With \u2068Jane Doe\u2069 · Directed by \u2068John Roe\u2069", on.creditsText, "the cast, then the directors")
        assertNull(later.creditsText)

        advanceTimeBy(30 * MINUTE)
        runCurrent()
        val moved = f.state.channels.single().programmes
        assertEquals(listOf(false, false, true), moved.map { it.onAir }, "the minute's republish moves on air on")
        assertEquals("On now · ${clock(13, 0)} – ${clock(14, 0)} · \u2068live:news\u2069", moved[2].headline)
    }

    @Test
    fun aChannelOpensOnItsProgrammeOnNowElseTheNextElseItsFirst() = runTest {
        val pages = mutableMapOf(
            "tv/channels/date=2026-09-11" to page(),
            "tv/channels/date=2026-09-12" to page(
                channel("on", programme("on:a", "2026-09-12T09:00:00Z", "2026-09-12T10:00:00Z"), programme("on:b", "2026-09-12T10:00:00Z", "2026-09-12T11:00:00Z")),
                channel("next", programme("next:a", "2026-09-12T08:00:00Z", "2026-09-12T09:00:00Z"), programme("next:b", "2026-09-12T12:00:00Z", "2026-09-12T13:00:00Z")),
                channel("ended", programme("ended:a", "2026-09-12T08:00:00Z", "2026-09-12T09:00:00Z"), programme("ended:b", "2026-09-12T09:00:00Z", "2026-09-12T10:00:00Z")),
                channel("none"),
            ),
        )
        val f = Fixture(this, listOf(LIVE), pages = pages)
        runCurrent()
        assertEquals(listOf(1, 1, 0, null), f.state.channels.map { it.startIndex })
    }

    @Test
    fun theTimeShownOpensHalfAnHourBeforeNowOnTodayElseOnTheFirstProgrammeNeverBeforeTheDay() = runTest {
        val f = Fixture(this, listOf(LIVE), pages = day())
        runCurrent()
        assertEquals(true, f.state.nowInDay)
        assertEquals(Instant.parse("2026-09-12T10:00:00Z").toEpochMilliseconds(), f.state.openingMillis)

        f.pages["tv/channels/date=2026-09-12"] = page()
        f.pages["tv/channels/date=2026-09-13"] = page(channel("live:news", programme("news:dawn", "2026-09-13T06:00:00Z", "2026-09-13T07:00:00Z")))
        f.store.selectDay(f.state.days.single { it.label == "Tomorrow" }.epochDay)
        runCurrent()
        assertEquals(false, f.state.nowInDay)
        assertEquals(Instant.parse("2026-09-13T06:00:00Z").toEpochMilliseconds(), f.state.openingMillis, "another day: its first channel's first programme")

        // UTC−10:20: now is ten past midnight, half an hour before it is yesterday.
        val early = Fixture(this, listOf(LIVE), offset = { -37_200 })
        runCurrent()
        assertEquals(early.state.dayStartMillis, early.state.openingMillis)
    }

    @Test
    fun onlyAnEpgProvidersDateCatalogsAreGuides() = runTest {
        val plain = addon("plain", epgProvider = false, catalogs = """[{"type":"tv","id":"channels","extra":[{"name":"date"}]}]""")
        val f = Fixture(this, listOf(plain))
        runCurrent()
        assertEquals(GuideBody.Empty(T.guide.noGuide), f.state.body)
        assertEquals(emptyList(), f.requests, "a date catalog without epgProvider isn't asked for")

        val two = addon("two", catalogs = """[{"type":"tv","id":"all","name":"All"},{"type":"tv","id":"sports","name":"Sports","extra":[{"name":"date"},{"name":"region","isRequired":true,"options":["eu","us"]}]},{"type":"tv","id":"pick","extra":[{"name":"date"},{"name":"region","isRequired":true}]}]""")
        f.pages["tv/sports/date=2026-09-11&region=eu"] = """{"metasDetailed":[]}"""
        f.pages["tv/sports/date=2026-09-12&region=eu"] = """{"metas":[{"id":"live:sports","type":"tv","name":"Sports"}]}"""
        f.addons.value = listOf(plain, LIVE, two)
        runCurrent()
        assertEquals(
            listOf("org.example.livetv/tv/channels", "org.example.two/tv/sports"),
            f.state.catalogs.map { it.key },
            "no catalog without date, none with a required extra that has no options",
        )
        f.store.selectCatalog("org.example.two/tv/sports")
        runCurrent()
        assertEquals(listOf("tv/sports/date=2026-09-11&region=eu", "tv/sports/date=2026-09-12&region=eu"), f.requests.takeLast(2))
        assertIs<GuideBody.Grid>(f.state.body, "plain metas: channels without programmes")
        assertEquals(emptyList(), f.programmes("live:sports"))
        assertNull(f.state.channels.single().onNow)
    }

    @Test
    fun pagesComeWhileTheyHaveChannelsSkipCountingThatDaysChannels() = runTest {
        val f = Fixture(this, listOf(LIVE), offset = { 0 })
        f.pages["tv/channels/date=2026-09-12"] = page(channel("a"), channel("b"))
        f.pages["tv/channels/date=2026-09-12&skip=2"] = page(channel("c"))
        f.pages["tv/channels/date=2026-09-12&skip=3"] = page()
        runCurrent()
        assertEquals(listOf("tv/channels/date=2026-09-12"), f.requests, "UTC: one day")
        assertEquals(true, f.state.hasMore)
        f.store.loadMore()
        runCurrent()
        assertEquals(listOf("a", "b", "c"), f.state.channels.map { it.id })
        assertEquals(true, f.state.hasMore, "a short page isn't the last")
        f.store.loadMore()
        runCurrent()
        assertEquals("tv/channels/date=2026-09-12&skip=3", f.requests.last())
        assertEquals(false, f.state.hasMore, "an empty page is")
        f.store.loadMore()
        runCurrent()
        assertEquals(3, f.requests.size)
    }

    @Test
    fun nowMovesOnEveryMinuteAndTheDayIsAskedAgainEveryFifteenKeepingWhatFailsToCome() = runTest {
        val f = Fixture(this, listOf(LIVE), pages = day())
        runCurrent()
        val news = { f.state.channels.first().let { channel -> channel.onNow?.let { channel.programmes[it].id } } }
        assertEquals("news:morning", news())
        advanceTimeBy(15 * MINUTE)
        runCurrent()
        assertEquals(4, f.requests.size, "the day asked again after 15 minutes")
        advanceTimeBy(15 * MINUTE)
        runCurrent()
        assertEquals("news:noon", news(), "11:00 UTC: the next one is on")

        f.pages.clear()
        advanceTimeBy(15 * MINUTE)
        runCurrent()
        assertEquals(8, f.requests.size)
        assertIs<GuideBody.Grid>(f.state.body)
        assertEquals(listOf("live:news", "live:sports"), f.state.channels.map { it.id }, "a failed refresh keeps the day's answers")
    }

    @Test
    fun aDayAsksForItsDatesAndAChannelPlaysByItsOwnId() = runTest {
        val f = Fixture(this, listOf(LIVE), pages = day())
        runCurrent()
        f.pages["tv/channels/date=2026-09-13"] = page()
        val tomorrow = f.state.days.single { it.label == "Tomorrow" }.epochDay
        f.store.selectDay(tomorrow)
        assertEquals(GuideBody.Loading, f.state.body)
        assertEquals(emptyList(), f.state.channels, "another day's channels don't show meanwhile")
        runCurrent()
        assertEquals(listOf("tv/channels/date=2026-09-12", "tv/channels/date=2026-09-13"), f.requests.takeLast(2))
        assertEquals(listOf("news:night"), f.programmes("live:news"), "the programme across local midnight belongs to both days")
        assertIs<GuideBody.Grid>(f.state.body)

        f.store.play("live:news")
        assertEquals(listOf("live:news"), f.host.played.map { it.id })

        f.pages.clear()
        f.store.selectDay(tomorrow + 1)
        runCurrent()
        assertEquals(GuideBody.Failed(T.guide.failed("The addon returned an error (HTTP 500).")), f.state.body)
        f.pages["tv/channels/date=2026-09-14"] = page(channel("live:news"))
        f.store.retry()
        runCurrent()
        assertIs<GuideBody.Grid>(f.state.body)
    }

    @Test
    fun aLocalDayAcrossAClockChangeIsTwentyThreeHours() {
        // Central Europe: UTC+1 until 29 Mar 2026 01:00 UTC, UTC+2 after.
        val change = Instant.parse("2026-03-29T01:00:00Z").toEpochMilliseconds()
        val offset = { millis: Long -> if (millis < change) 3600 else 7200 }
        val day = localDay(change, offset)
        val start = Guides.dayStart(day, offset)
        val end = Guides.dayStart(day + 1, offset)
        assertEquals(Instant.parse("2026-03-28T23:00:00Z").toEpochMilliseconds(), start)
        assertEquals(Instant.parse("2026-03-29T22:00:00Z").toEpochMilliseconds(), end)
        assertEquals(listOf("2026-03-28", "2026-03-29"), Guides.utcDates(start, end))
        assertEquals(listOf("2026-03-29"), Guides.utcDates(start + 3_600_000, end), "a window within one UTC day")
    }

    @Test
    fun aLocalDayWhoseClockChangesAtMidnightStartsAtItsFirstInstant() {
        // Chile: UTC-4 until 6 Sep 2026 04:00 UTC, when 00:00 jumps to 01:00 (UTC-3); its 6 Sep starts at 01:00.
        val forward = Instant.parse("2026-09-06T04:00:00Z").toEpochMilliseconds()
        val chile = { millis: Long -> if (millis < forward) -4 * 3600 else -3 * 3600 }
        val sixth = localDay(forward, chile)
        assertEquals(forward, Guides.dayStart(sixth, chile))
        assertEquals(sixth, localDay(Guides.dayStart(sixth, chile), chile))
        assertEquals(Instant.parse("2026-09-05T04:00:00Z").toEpochMilliseconds(), Guides.dayStart(sixth - 1, chile), "the day before starts as ever")

        // UTC-2 until 02:00 UTC, when 00:00 goes back to 23:00 (UTC-3): the day starts at its one 00:00, 03:00 UTC.
        val back = Instant.parse("2026-02-15T02:00:00Z").toEpochMilliseconds()
        val summerEnds = { millis: Long -> if (millis < back) -2 * 3600 else -3 * 3600 }
        val fifteenth = localDay(back + 3_600_000, summerEnds)
        assertEquals(Instant.parse("2026-02-15T03:00:00Z").toEpochMilliseconds(), Guides.dayStart(fifteenth, summerEnds))
    }

    @Test
    fun eachInstallationOfAnAddonIsAGuideOfItsOwnAndStaysChosenAsTheOthersComeAndGo() = runTest {
        fun live(host: String) = Descriptor(LIVE.manifest, "https://$host/manifest.json".transportUrl())
        val home = live("live.example.com/home")
        val away = live("live.example.com/away")
        val f = Fixture(this, listOf(home, away), pages = day())
        runCurrent()
        val keys = f.state.catalogs.map { it.key }
        assertEquals(listOf("org.example.livetv/tv/channels", "org.example.livetv/tv/channels#2"), keys)

        f.store.selectCatalog(keys[1])
        runCurrent()
        assertEquals(listOf(false, true), f.state.catalogs.map { it.selected })
        assertTrue(f.env.http.requests.takeLast(2).all { it.url.encodedPath.startsWith("/away/") }, "the second installation is asked")

        // The first installation goes: the one chosen stays chosen under its new key, and isn't asked again.
        val asked = f.requests.size
        f.addons.value = listOf(away)
        runCurrent()
        assertEquals(listOf(true), f.state.catalogs.map { it.selected })
        assertEquals(asked, f.requests.size)
    }

    private companion object {
        val NOW = Instant.parse("2026-09-12T10:30:00Z").toEpochMilliseconds()
        const val MINUTE = 60_000L

        /**
         * [hour]:[minute] local time on the fixture's day as this platform's CLDR data writes it: `12:00 PM` in English,
         * whose space before the day period is U+0020 in older data (Android 13 and before, JDK 19 and before) and U+202F
         * in newer.
         */
        fun clock(hour: Int, minute: Int): String =
            localizedTime(Instant.parse("2026-09-12T00:00:00Z").toEpochMilliseconds() + (hour * 60L + minute) * MINUTE, 0, T.locale)

        fun addon(name: String, epgProvider: Boolean = true, catalogs: String) = Descriptor(
            Manifest.decode(
                """{"id":"org.example.$name","version":"1.0.0","name":"$name","types":["tv"],"resources":["catalog","meta","stream"],"catalogs":$catalogs,"behaviorHints":{"epgProvider":$epgProvider}}""",
            ),
            "https://$name.example.com/manifest.json".transportUrl(),
        )

        /** The addon SDK's `docs/epg.md` manifest. */
        val LIVE = Descriptor(
            Manifest.decode(
                """{"id":"org.example.livetv","version":"1.0.0","name":"Example Live TV","resources":["catalog","meta","stream"],"types":["tv"],"idPrefixes":["live:"],"catalogs":[{"type":"tv","id":"channels","name":"Channels","extra":[{"name":"skip"},{"name":"date"}]}],"behaviorHints":{"epgProvider":true}}""",
            ),
            "https://live.example.com/manifest.json".transportUrl(),
        )

        fun programme(id: String, start: String, end: String, extra: String = "") =
            """{"id":"$id","title":"$id","released":"$start","startTime":"$start","endTime":"$end"$extra}"""

        fun channel(id: String, vararg programmes: String) =
            """{"id":"$id","type":"tv","name":"$id","behaviorHints":{"hasScheduledVideos":true},"videos":[${programmes.joinToString(",")}]}"""

        fun page(vararg channels: String) = """{"metasDetailed":[${channels.joinToString(",")}]}"""

        /** The pages of 11 and 12 Sep (UTC), as the SDK's example addon answers them: each day's overlapping programmes. */
        fun day(): MutableMap<String, String> {
            val late = programme("news:late", "2026-09-11T23:00:00Z", "2026-09-12T01:00:00Z")
            return mutableMapOf(
                "tv/channels/date=2026-09-11" to page(
                    channel(
                        "live:news",
                        programme("news:before", "2026-09-11T18:00:00Z", "2026-09-11T19:00:00Z"),
                        programme("news:evening", "2026-09-11T21:00:00Z", "2026-09-11T23:00:00Z"),
                        late,
                    ),
                ),
                "tv/channels/date=2026-09-12" to page(
                    channel(
                        "live:news",
                        late,
                        programme("news:noon", "2026-09-12T11:00:00Z", "2026-09-12T12:00:00Z"),
                        programme(
                            "news:morning", "2026-09-12T10:00:00Z", "2026-09-12T11:00:00Z",
                            ""","genres":["News"],"runtime":"45 min","ratings":[{"value":"PG","system":"TVPG"}],"cast":["Jane Doe","John Roe"]""",
                        ),
                        programme("news:night", "2026-09-12T21:00:00Z", "2026-09-12T23:00:00Z"),
                    ),
                    channel("live:sports"),
                ),
            )
        }
    }
}
