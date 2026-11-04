package com.edde746.tortuga.presentation.streams

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.app.BuildFlavor
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.StreamQuality
import com.edde746.tortuga.data.settings.StreamSortKey
import com.edde746.tortuga.data.streams.DeviceClass
import com.edde746.tortuga.data.streams.PickContext
import com.edde746.tortuga.data.streams.PickOutcome
import com.edde746.tortuga.data.streams.PickSource
import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamPick
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.net.AddonResponseCache
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.SERIES
import com.edde746.tortuga.playback.session.json
import com.edde746.tortuga.playback.streams.BadgeKind
import com.edde746.tortuga.playback.streams.HdrBadge
import com.edde746.tortuga.playback.streams.ResolutionBadge
import com.edde746.tortuga.playback.streams.VideoCodecBadge
import com.edde746.tortuga.playback.suggest.StreamPicks
import com.edde746.tortuga.playback.suggest.StreamScorer
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StreamsStoreTest {
    private fun addon(name: String, resources: String = """["stream"]""") = Descriptor(
        Manifest.decode("""{"id":"org.example.$name","version":"1.0.0","name":"$name","types":["series"],"resources":$resources}"""),
        "https://$name.example.com/manifest.json".transportUrl(),
    )

    private val hosted = addon("hosted")
    private val slow = addon("slow")
    private val broken = addon("broken")
    private val empty = addon("empty")
    private val catalogOnly = addon("catalog", resources = """["catalog"]""")
    private val big = addon("big")
    private val huge = addon("huge")
    private val styled = addon("styled")
    private val peers = addon("peers")
    private val cached = addon("cached")
    private val fading = addon("fading")
    private val late = addon("late")
    private val filename = addon("filename")

    private class Host : StreamsHost {
        val played = mutableListOf<PlayRequest>()
        val urls = mutableListOf<String>()
        val links = mutableListOf<DeepLink>()
        override fun play(request: PlayRequest) {
            played += request
        }
        val playedWith = mutableListOf<Pair<PlayRequest, String>>()
        override fun playWith(request: PlayRequest, playerId: String) {
            playedWith += request to playerId
        }
        override fun openUrl(url: String) {
            urls += url
        }
        override fun openDeepLink(link: DeepLink) {
            links += link
        }
        override fun closeStreams() = Unit
    }

    private class Fixture(
        scope: TestScope,
        policy: StreamListPolicy,
        addons: List<Descriptor>,
        notice: String? = null,
        meta: MetaItem = SERIES,
        videoId: String = "tt1:1:1",
        fromStart: Boolean = false,
        cache: AddonResponseCache? = null,
        val data: LocalData = testData(scope),
        context: PickContext = PickContext(DeviceClass.TV, metered = false, hdrDisplay = true, dolbyVisionDisplay = false, hardwareCodecs = emptySet()),
    ) {
        private var cachedAnswers = 0
        private var fadingAnswers = 0
        val http = FakeHttp(scope) { request ->
            when (request.url.host) {
                "hosted.example.com" -> json(
                    """{"streams":[
                        {"name":"[⚡] Hosted\n2160p","description":"Example.Show.S01E01.2160p.HDR.HEVC.mkv","url":"https://hosted.example.com/play/1","behaviorHints":{"bingeGroup":"hosted|2160p"}},
                        {"name":"Hosted\npeer-to-peer","infoHash":"0123456789abcdef0123456789abcdef01234567"},
                        {"name":"[⚡] Hosted\n1080p","url":"https://hosted.example.com/play/2"},
                        {"name":"Trailer","ytId":"dQw4w9WgXcQ"},
                        {"name":"Newsgroup","nzbUrl":"https://indexer.example/get/1"}
                    ]}""",
                )
                "slow.example.com" -> {
                    delay(5.seconds)
                    json("""{"streams":[{"url":"https://slow.example.com/1.mkv"},{"externalUrl":"stremio:///detail/series/tt1"}]}""")
                }
                "empty.example.com" -> json("""{"streams":[]}""")
                "late.example.com" -> {
                    delay(5.seconds)
                    json("""{"streams":[]}""")
                }
                // An addon answering with peer-to-peer sources alone, which no build plays.
                "peers.example.com" -> json(
                    """{"streams":[
                        {"name":"Addon A\n4k","infoHash":"0123456789abcdef0123456789abcdef01234567"},
                        {"name":"Addon A\n1080p","infoHash":"89abcdef0123456789abcdef0123456789abcdef"}
                    ]}""",
                )
                // An answer the addon lets caches keep for an hour, numbered.
                "cached.example.com" -> json("""{"streams":[{"name":"Cached\nanswer ${++cachedAnswers}","url":"https://cached.example.com/1.mkv"}],"cacheMaxAge":3600}""")
                // Streams the first time it's asked, none after.
                "fading.example.com" -> json(if (++fadingAnswers == 1) """{"streams":[{"url":"https://fading.example.com/1.mkv"}]}""" else """{"streams":[]}""")
                "big.example.com", "huge.example.com" -> json(aggregatorAnswer(request.url.host))
                // An addon's custom-formatted row, then a plainly formatted one.
                "styled.example.com" -> json(
                    """{"streams":[
                        {"name":"\u00a0\u00a0\u00a04K\u00a0\u200d\u200d\u00a0\u00a0\u00a0\u00a0\u200d\u200d\u200d⚡\u200d\u200d\n\u00a0\u00a0〈Web\u200d-\u200ddl〉\u200d\u00a0\u00a0\u00a0\u00a0\u00a0\u200d\n\u00a0\u00a0★★★★★\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0\u00a0",
                         "description":"✎  Example  s₀₁·ᴇ₀₁\n▣  HEVC  ✦ DV · HDR  \n♬  Atmos · DD+  ♯ 5.1\n❖ 11.4 / 188 GB · 24.6 ᴹᵇᵖˢ · 12d\n⛉ Example Source · NOVA\n✓  ᴇɴ  · sᴜʙ  (ᴇɴ)   »   ᴡᴇʙ ᴛ₁ ᴀᴘᴘʟᴇ ᴛᴠ+ ₆₃₈₅",
                         "url":"https://styled.example.com/play/1"},
                        {"name":"[⚡] Addon A\n4k DV | HDR","description":"Example.Show.S01E01.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-NOVA\n💾 11.4 GB ⚙️ Example Source","url":"https://styled.example.com/play/2"}
                    ]}""",
                )
                // An addon's row whose resolution and group are in the file name only.
                "filename.example.com" -> json(
                    """{"streams":[{"name":"Addon B unknown",
                        "description":"📄 Example Film (1988) DC.mkv\n📹 hevc • 10bit | 🔊 Dolby Digital • 5.1\n⭐ BluRay\n💾 19.7 GB 🔎 Example Source",
                        "behaviorHints":{"bingeGroup":"filename|1080p",
                            "filename":"Example Film (1988) DC (1080p x265 HEVC 10bit BluRay AC3 5.1) [NOVA]","videoSize":21140670947},
                        "url":"https://filename.example.com/play/1"}]}""",
                )
                else -> respond("", HttpStatusCode.InternalServerError)
            }
        }
        val host = Host()
        val picks = StreamPicks(
            "p",
            data.streamPicks,
            data.settings,
            { context },
            scope.backgroundScope,
            learnDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
        val store = StreamsStore(
            meta = meta,
            videoId = videoId,
            profileId = "p",
            aggregator = AddonAggregator(AddonTransport(http.client, cache, parseDispatcher = StandardTestDispatcher(scope.testScheduler))),
            installedAddons = { addons },
            streamHistory = data.streamHistory,
            settings = data.settings,
            policy = policy,
            host = host,
            parentScope = scope.backgroundScope,
            notice = notice,
            fromStart = fromStart,
            picks = picks,
        )
    }

    private val sideloaded = BuildFlavor.SIDELOADED.streamListPolicy
    private val apple = BuildFlavor.APPLE.streamListPolicy

    @Test
    fun theStreamsTheTitleCarriesComeFirstAsTheGroupOfTheAddonThatSentIt() = runTest {
        val channels = addon("channels", resources = """["meta"]""")
        val direct = Stream(StreamSource.Url("https://channels.example.com/e1.mp4"), name = "Direct")
        val meta = SERIES.copy(videos = SERIES.videos.map { if (it.id == "tt1:1:1") it.copy(streams = listOf(direct)) else it }, addon = channels)
        val f = Fixture(this, sideloaded, listOf(hosted), meta = meta)
        runCurrent()
        val groups = f.store.state.value.groups
        assertEquals(listOf("channels", "hosted"), groups.map { it.addonName })
        assertEquals(listOf(GroupStatus.Ready, GroupStatus.Ready), groups.map { it.status })
        assertEquals(listOf("Direct"), groups[0].streams.map { it.name })
        f.store.pick(groups[0].streams[0].id)
        assertEquals(listOf("channels" to direct), f.host.played.map { it.stream.addonName to it.stream.stream })

        // A YouTube channel's video carries its own YouTube stream.
        val video = Video(id = "yt_id:UC1:abc123", title = "A video")
        val channel = MetaItem(MetaItemPreview(id = "yt_id:UC1", type = "channel", name = "A channel"), listOf(video), addon = channels)
        val youTube = Fixture(this, sideloaded, emptyList(), meta = channel, videoId = video.id)
        runCurrent()
        val group = youTube.store.state.value.groups.single()
        youTube.store.pick(group.streams.single().id)
        assertEquals(listOf("https://www.youtube.com/watch?v=abc123"), youTube.host.urls)
    }

    @Test
    fun groupsFollowTheInstalledOrderAndFillAsEachAddonAnswers() = runTest {
        val f = Fixture(this, sideloaded, listOf(slow, hosted, catalogOnly, broken, empty))
        runCurrent()
        var state = f.store.state.value
        assertEquals("Chernobyl", state.title)
        assertEquals("S1E1 · \u20681:23:45\u2069", state.subtitle)
        assertEquals(listOf("slow", "hosted", "broken"), state.groups.map { it.addonName }, "catalog-only addons aren't asked, an empty answer isn't listed")
        assertEquals(listOf(GroupStatus.Loading, GroupStatus.Ready, GroupStatus.Failed("The addon returned an error (HTTP 500).")), state.groups.map { it.status })
        assertFalse(state.answered)

        val hostedGroup = state.groups[1]
        assertEquals(listOf("[⚡] Hosted 2160p", "[⚡] Hosted 1080p", "Trailer"), hostedGroup.streams.map { it.name })
        assertEquals(listOf(false, false, true), hostedGroup.streams.map { it.external })
        assertEquals("2 streams this app can't play", hostedGroup.unplayableNote)
        // HDR and HEVC are in the description already; 4K is the 2160p it names.
        assertEquals(listOf("Instant", "4K"), hostedGroup.streams[0].badges.map { it.text })

        advanceTimeBy(6.seconds)
        runCurrent()
        state = f.store.state.value
        assertTrue(state.answered)
        assertEquals(2, state.groups[0].streams.size)
        assertNull(state.emptyText)
    }

    /**
     * A group is listed while it has something to show (its addon asked, failed, or its streams, notes): an addon that
     * answered with nothing leaves no heading alone, and one whose streams the filters all hide keeps its count.
     */
    @Test
    fun anAddonThatAnswersNothingLeavesTheListAndOneWhoseStreamsAreAllFilteredStays() = runTest {
        val f = Fixture(this, sideloaded, listOf(late, hosted, empty, styled))
        f.data.settings.set("p", ProfileSetting.StreamExcludePattern, "example source")
        runCurrent()
        var state = f.store.state.value
        // The late addon's group shows while it is asked; the empty answer has nothing to show.
        assertEquals(listOf("late", "hosted", "styled"), state.groups.map { it.addonName })
        assertEquals(listOf(GroupStatus.Loading, GroupStatus.Ready, GroupStatus.Ready), state.groups.map { it.status })

        advanceTimeBy(6.seconds)
        runCurrent()
        state = f.store.state.value
        assertTrue(state.answered)
        assertEquals(listOf("hosted", "styled"), state.groups.map { it.addonName }, "once it answered nothing, the late addon's group leaves")
        // The styled addon's streams all match the exclude pattern: its count is what its group shows, though the filter bar has it too.
        assertEquals(emptyList(), state.groups[1].streams)
        assertEquals("2 streams hidden by your filters", state.groups[1].filteredNote)
        assertEquals("2 streams hidden by your filters", state.filter?.text)
        assertNull(state.emptyText)

        f.store.toggleFiltered()
        state = f.store.state.value
        assertEquals(listOf(true, true), state.groups[1].streams.map { it.filtered })
        assertNull(state.groups[1].filteredNote)
    }

    /** A UI keys a group by its id: a group keeps it while one listed before it leaves. */
    @Test
    fun aGroupKeepsItsIdWhileAGroupBeforeItLeaves() = runTest {
        val f = Fixture(this, sideloaded, listOf(late, hosted))
        runCurrent()
        val ids = f.store.state.value.groups.map { it.id }
        assertEquals(2, ids.distinct().size)
        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(listOf(ids[1]), f.store.state.value.groups.map { it.id })
    }

    @Test
    fun appleBuildsShowNoNoteAndNoCacheState() = runTest {
        val f = Fixture(this, apple, listOf(hosted))
        runCurrent()
        val group = f.store.state.value.groups.single()
        assertNull(group.unplayableNote)
        assertTrue(group.streams.flatMap { it.badges }.none { it.kind == BadgeKind.CACHED })
        assertEquals(3, group.streams.size, "unplayable sources stay hidden")
    }

    @Test
    fun rowsShowTheAddonsTextTidiedAndOnlyTheBadgesItDoesNotWrite() = runTest {
        val f = Fixture(this, apple, listOf(styled))
        runCurrent()
        val (formatted, plain) = f.store.state.value.groups.single().streams
        assertEquals("4K ⚡ 〈Web\u200d-\u200ddl〉 ★★★★★", formatted.name)
        assertEquals(
            "✎ Example s₀₁·ᴇ₀₁\n▣ HEVC ✦ DV · HDR\n♬ Atmos · DD+ ♯ 5.1\n❖ 11.4 / 188 GB · 24.6 ᴹᵇᵖˢ · 12d\n⛉ Example Source · NOVA\n" +
                "✓ ᴇɴ · sᴜʙ (ᴇɴ) » ᴡᴇʙ ᴛ₁ ᴀᴘᴘʟᴇ ᴛᴠ+ ₆₃₈₅",
            formatted.description,
        )
        assertEquals(emptyList(), formatted.badges, "its text says all the badges would")
        assertEquals("[⚡] Addon A 4k DV | HDR", plain.name)
        assertEquals(listOf("HEVC", "DD+", "5.1"), plain.badges.map { it.text }, "what the release name only implies")

        // The filters go by every badge read, shown or not.
        f.data.settings.set("p", ProfileSetting.HiddenStreamQualities, listOf(StreamQuality.UHD_4K))
        runCurrent()
        assertEquals(emptyList(), f.store.state.value.groups.single().streams)
        assertEquals("2 streams hidden by your filters", f.store.state.value.filter?.text)
    }

    @Test
    fun rowsAndFiltersReadTheFileNameToo() = runTest {
        val f = Fixture(this, apple, listOf(filename))
        runCurrent()
        assertEquals(listOf("1080p", "DD"), f.store.state.value.groups.single().streams.single().badges.map { it.text })

        f.data.settings.set("p", ProfileSetting.StreamExcludePattern, "\\[nova]")
        runCurrent()
        assertEquals(emptyList(), f.store.state.value.groups.single().streams)
    }

    @Test
    fun storeBuildsHideUnplayableSourcesWithoutANote() = runTest {
        val f = Fixture(this, BuildFlavor.STORE.streamListPolicy, listOf(hosted))
        runCurrent()
        val group = f.store.state.value.groups.single()
        assertNull(group.unplayableNote)
        assertEquals(listOf("[⚡] Hosted 2160p", "[⚡] Hosted 1080p", "Trailer"), group.streams.map { it.name })
        assertEquals("Instant", group.streams[0].badges.single { it.kind == BadgeKind.CACHED }.text, "the instant state stays")
    }

    @Test
    fun picksPlayWithTheOtherPlayableStreamsAsAlternatives() = runTest {
        val f = Fixture(this, sideloaded, listOf(slow, hosted))
        advanceTimeBy(6.seconds)
        runCurrent()
        val groups = f.store.state.value.groups
        f.store.pick(groups[1].streams[0].id)
        val request = f.host.played.single()
        assertEquals("tt1:1:1", request.videoId)
        assertEquals("https://hosted.example.com/play/1", request.stream.request()?.url)
        assertEquals("hosted", request.stream.addonName)
        // After the pick first, then the ones before it; never the external ones.
        assertEquals(
            listOf("https://hosted.example.com/play/2", "https://slow.example.com/1.mkv"),
            request.alternatives.map { it.request()?.url },
        )

        f.store.pick(groups[1].streams[2].id)
        assertEquals(listOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), f.host.urls)
        f.store.pick(groups[0].streams[1].id)
        assertEquals(1, f.host.links.size, "stremio: links stay in the app")
    }

    @Test
    fun aTapOnARowTheListNoLongerShowsPlaysIt() = runTest {
        val f = Fixture(this, sideloaded, listOf(slow, hosted))
        advanceTimeBy(6.seconds)
        runCurrent()
        val slowRow = f.store.state.value.groups[0].streams[0].id
        // The list rebuilds between the row's render and its tap: only hosted's group shows now.
        f.store.selectAddon(f.store.state.value.addons.single { it.name == "hosted" }.id)
        f.store.pick(slowRow)
        val request = f.host.played.single()
        assertEquals("https://slow.example.com/1.mkv", request.stream.request()?.url)
        assertEquals(listOf("https://hosted.example.com/play/1", "https://hosted.example.com/play/2"), request.alternatives.map { it.request()?.url })
    }

    /**
     * While the profile's suggestions are on, a pick that plays is recorded with the playable streams the list shows, in
     * its order; external rows aren't. While they are off nothing is.
     */
    @Test
    fun aPickIsRecordedWithThePlayableStreamsShown() = runTest {
        val f = Fixture(this, sideloaded, listOf(slow, hosted))
        advanceTimeBy(6.seconds)
        runCurrent()
        val groups = f.store.state.value.groups
        f.store.pick(groups[1].streams[1].id)
        runCurrent()
        assertEquals(emptyList(), f.picks.picks(), "off: no pick recorded")
        assertNull(f.host.played.single().pickId)

        f.data.settings.set("p", ProfileSetting.StreamSuggestions, true)
        runCurrent()
        f.store.pick(groups[1].streams[1].id)
        runCurrent()
        val pick = f.picks.picks().single()
        assertEquals(pick.id, f.host.played.last().pickId)
        assertEquals(listOf(null, ResolutionBadge.UHD_2160, ResolutionBadge.FHD_1080), pick.candidates.map { it.resolution })
        assertEquals(2, pick.chosen)

        f.store.pick(groups[1].streams[2].id)
        runCurrent()
        assertEquals(1, f.picks.picks().size, "a row that opens outside the app is no pick")
    }

    /**
     * While the profile's suggestions are on and the picks are enough, the stream they suggest is marked with why, once
     * every addon answered, where the list puts it; never one the filters hide. Off, nothing is marked, and turning them
     * off takes the mark away from the open list at once.
     */
    @Test
    fun theSuggestedStreamIsMarkedWhereTheListPutsItAndNeverAFilteredOne() = runTest {
        val data = testData(this)
        val tv = PickContext(DeviceClass.TV, metered = false, hdrDisplay = true, dolbyVisionDisplay = false, hardwareCodecs = emptySet())
        val shown = listOf(PickedStream(ResolutionBadge.UHD_2160, listOf(HdrBadge.HDR), VideoCodecBadge.HEVC), PickedStream(ResolutionBadge.FHD_1080))
        repeat(20) { data.streamPicks.record("p", StreamPick("s$it", "tt$it", "tt$it", null, 0, tv, PickSource.LIST, shown, 1, PickOutcome.GOOD)) }
        val plain = Fixture(this, sideloaded, listOf(slow, hosted), data = data)
        advanceTimeBy(6.seconds)
        runCurrent()
        val unmarked = plain.store.state.value.groups.flatMap { it.streams }
        assertTrue(unmarked.none { it.suggestion != null }, "off: no mark")

        data.settings.set("p", ProfileSetting.StreamSuggestions, true)
        val f = Fixture(this, sideloaded, listOf(slow, hosted), data = data)
        runCurrent()
        assertTrue(f.store.state.value.groups.flatMap { it.streams }.none { it.suggestion != null }, "not while an addon answers")
        advanceTimeBy(6.seconds)
        runCurrent()
        val rows = f.store.state.value.groups.flatMap { it.streams }
        assertEquals(unmarked.map { it.name }, rows.map { it.name }, "the list keeps its order")
        assertEquals(listOf("[⚡] Hosted 1080p"), rows.filter { it.suggestion != null }.map { it.name })
        assertEquals(StreamSuggestion("Suggested", "Like your recent picks: 1080p"), rows.single { it.suggestion != null }.suggestion)

        f.data.settings.set("p", ProfileSetting.HiddenStreamQualities, listOf(StreamQuality.FHD_1080))
        f.store.toggleFiltered()
        runCurrent()
        val filtered = f.store.state.value.groups.flatMap { it.streams }.single { it.name == "[⚡] Hosted 1080p" }
        assertTrue(filtered.filtered)
        assertNull(filtered.suggestion, "a row the filters hide is never suggested, even shown")

        f.data.settings.set("p", ProfileSetting.HiddenStreamQualities, emptyList())
        runCurrent()
        assertEquals(listOf("[⚡] Hosted 1080p"), f.store.state.value.groups.flatMap { it.streams }.filter { it.suggestion != null }.map { it.name })
        f.data.settings.set("p", ProfileSetting.StreamSuggestions, false)
        runCurrent()
        assertTrue(f.store.state.value.groups.flatMap { it.streams }.none { it.suggestion != null }, "turned off: the mark goes at once")
    }

    /**
     * A stream this device can't play as made (here a codec with no hardware decoder) is never suggested, however much
     * the picks like it.
     */
    @Test
    fun aStreamTheDeviceCantShowAsMadeIsNeverSuggested() = runTest {
        val data = testData(this)
        val hdrTv = PickContext(DeviceClass.TV, metered = false, hdrDisplay = true, dolbyVisionDisplay = false, hardwareCodecs = emptySet())
        val shown = listOf(PickedStream(ResolutionBadge.FHD_1080), PickedStream(ResolutionBadge.UHD_2160, listOf(HdrBadge.HDR), VideoCodecBadge.HEVC))
        repeat(20) { data.streamPicks.record("p", StreamPick("s$it", "tt$it", "tt$it", null, 0, hdrTv, PickSource.LIST, shown, 1, PickOutcome.GOOD)) }
        data.settings.set("p", ProfileSetting.StreamSuggestions, true)
        fun suggested(context: PickContext): List<String> {
            val f = Fixture(this, sideloaded, listOf(slow, hosted), data = data, context = context)
            advanceTimeBy(6.seconds)
            runCurrent()
            return f.store.state.value.groups.flatMap { it.streams }.filter { it.suggestion != null }.map { it.name }
        }
        assertEquals(listOf("[⚡] Hosted 2160p"), suggested(hdrTv), "the picks' 4K HDR HEVC where it plays")
        assertTrue("[⚡] Hosted 2160p" !in suggested(hdrTv.copy(hardwareCodecs = setOf(VideoCodecBadge.AVC))), "no HEVC decoder")
    }

    @Test
    fun aListOpenedToPlayFromTheBeginningPlaysItsPickFromZero() = runTest {
        val resume = Fixture(this, sideloaded, listOf(hosted))
        val fromStart = Fixture(this, sideloaded, listOf(hosted), fromStart = true)
        runCurrent()
        resume.store.pick(resume.store.state.value.groups[0].streams[0].id)
        fromStart.store.pick(fromStart.store.state.value.groups[0].streams[0].id)
        assertNull(resume.host.played.single().startPositionSeconds, "from the library's resume point")
        assertEquals(0.0, fromStart.host.played.single().startPositionSeconds)
    }

    @Test
    fun theLastPlayedStreamIsMarked() = runTest {
        val f = Fixture(this, sideloaded, listOf(hosted))
        f.data.streamHistory.record(
            "p", SERIES.id, "tt1:1:1",
            """{"name":"[⚡] Hosted\n1080p","url":"https://hosted.example.com/play/2"}""",
            hosted.transportUrl,
        )
        f.store.reload()
        runCurrent()
        val rows = f.store.state.value.groups.single().streams
        assertEquals(listOf(false, true, false), rows.map { it.lastUsed })
        assertEquals(listOf(null, StreamNoteKind.LAST_USED, StreamNoteKind.EXTERNAL), rows.map { it.noteKind })
        assertEquals("Last played", rows[1].note)
        assertEquals("Opens outside the app", rows[2].note)
    }

    @Test
    fun nothingToShowSaysSo() = runTest {
        val f = Fixture(this, sideloaded, listOf(empty))
        runCurrent()
        assertEquals("No streams found", f.store.state.value.emptyText)
    }

    @Test
    fun aFailedAddonIsListedWithItsRetryInsteadOfNothingFound() = runTest {
        val f = Fixture(this, sideloaded, listOf(broken, empty))
        runCurrent()
        val state = f.store.state.value
        assertIs<StreamsBody.Groups>(state.body)
        assertIs<GroupStatus.Failed>(state.groups.single().status)
    }

    @Test
    fun anAddonSendingOnlyStreamsThisAppCantPlaySaysSoInsteadOfNothingFound() = runTest {
        val sideloadedList = Fixture(this, sideloaded, listOf(peers, empty))
        val appleList = Fixture(this, apple, listOf(peers, empty))
        runCurrent()
        val state = sideloadedList.store.state.value
        assertNull(state.emptyText)
        assertIs<StreamsBody.Groups>(state.body)
        assertEquals(listOf("2 streams this app can't play"), state.groups.map { it.unplayableNote })
        // Where the build shows no note, there is nothing to list.
        assertEquals(emptyList(), appleList.store.state.value.groups)
        assertEquals("No streams found", appleList.store.state.value.emptyText)
        assertIs<StreamsBody.Empty>(appleList.store.state.value.body)
    }

    @Test
    fun theFiltersHideStreamsAndSayHowManyAndShowThemOnRequest() = runTest {
        val f = Fixture(this, sideloaded, listOf(slow, hosted))
        f.data.settings.set("p", ProfileSetting.HiddenStreamQualities, listOf(StreamQuality.UHD_4K))
        f.data.settings.set("p", ProfileSetting.StreamExcludePattern, "trailer")
        advanceTimeBy(6.seconds)
        runCurrent()
        var state = f.store.state.value
        assertEquals(listOf("[⚡] Hosted 1080p"), state.groups[1].streams.map { it.name })
        assertNull(state.groups[1].filteredNote, "one group's count would repeat the filter bar's")
        assertNull(state.groups[0].filteredNote, "the slow addon's streams name no resolution and pass")
        assertEquals(StreamFilterBar("2 streams hidden by your filters", "Show", showing = false), state.filter)
        assertNull(state.emptyText)

        // A pick's alternatives are the streams listed: the hidden 2160p isn't one.
        f.store.pick(state.groups[1].streams[0].id)
        assertEquals(listOf("https://slow.example.com/1.mkv"), f.host.played.single().alternatives.map { it.request()?.url })

        f.store.toggleFiltered()
        state = f.store.state.value
        assertEquals(StreamFilterBar("Showing 2 streams your filters hide", "Hide", showing = true), state.filter)
        assertEquals(listOf("[⚡] Hosted 2160p", "[⚡] Hosted 1080p", "Trailer"), state.groups[1].streams.map { it.name })
        assertEquals(listOf("Hidden by your filters", null, "Hidden by your filters"), state.groups[1].streams.map { it.note })
        // The trailer opens outside the app too: one note per row, the filters' before that one.
        assertTrue(state.groups[1].streams[2].external)
        assertEquals(listOf(StreamNoteKind.FILTERED, null, StreamNoteKind.FILTERED), state.groups[1].streams.map { it.noteKind })
        assertEquals(listOf(true, false, true), state.groups[1].streams.map { it.filtered })
        assertNull(state.groups[1].filteredNote)
        f.store.pick(state.groups[1].streams[1].id)
        assertEquals(2, f.host.played.last().alternatives.size, "shown, the filtered ones are alternatives too")

        f.store.toggleFiltered()
        assertEquals(1, f.store.state.value.groups[1].streams.size)
    }

    @Test
    fun aFilterChangedWhileTheListIsOpenAppliesAtOnceAndAnInvalidPatternFiltersNothing() = runTest {
        val f = Fixture(this, sideloaded, listOf(hosted))
        runCurrent()
        assertNull(f.store.state.value.filter)
        assertEquals(3, f.store.state.value.groups.single().streams.size)

        f.data.settings.set("p", ProfileSetting.StreamIncludePattern, "1080P")
        runCurrent()
        assertEquals(listOf("[⚡] Hosted 1080p"), f.store.state.value.groups.single().streams.map { it.name })

        f.data.settings.set("p", ProfileSetting.StreamIncludePattern, "(1080p")
        runCurrent()
        assertNull(f.store.state.value.filter)
        assertEquals(3, f.store.state.value.groups.single().streams.size)
    }

    /**
     * Under a sort the answered addons' rows are one list in its order, ties keeping the addons' order, with the addons
     * still answering after it under their headings; a pick's alternatives follow that order, and Addon order brings
     * the groups back.
     */
    @Test
    fun aSortListsTheAnsweredAddonsRowsAsOneListAndTheAddonsStillAnsweringAfterIt() = runTest {
        val f = Fixture(this, sideloaded, listOf(hosted, filename, slow))
        runCurrent()
        assertEquals("Addon order", f.store.state.value.sort?.label)
        f.store.toggleSort(StreamSortKey.RESOLUTION.key)
        runCurrent()
        var state = f.store.state.value
        assertEquals("Resolution", state.sort?.label)
        assertEquals(listOf(true, false), state.groups.map { it.sorted })
        // Hosted's 2160p, then its 1080p and filename's (from the file name) in the addons' order, then the trailer,
        // which names no resolution.
        assertEquals(listOf("0:0", "0:2", "1:0", "0:3"), state.groups[0].streams.map { it.id })
        assertFalse(state.headed(state.groups[0]), "the sorted list is no addon's")
        assertEquals("2 streams this app can't play", state.groups[0].unplayableNote)
        assertEquals(listOf("slow"), state.groups.drop(1).map { it.addonName })
        assertEquals(GroupStatus.Loading, state.groups[1].status)

        f.store.pick("0:2")
        assertEquals(
            listOf("https://filename.example.com/play/1", "https://hosted.example.com/play/1"),
            f.host.played.single().alternatives.map { it.request()?.url },
        )

        advanceTimeBy(6.seconds)
        runCurrent()
        state = f.store.state.value
        assertEquals(listOf("0:0", "0:2", "1:0", "0:3", "2:0", "2:1"), state.groups.single().streams.map { it.id }, "a later answer joins the list")

        f.store.toggleSort("addon")
        runCurrent()
        assertEquals(listOf("hosted", "filename", "slow"), f.store.state.value.groups.map { it.addonName })
    }

    @Test
    fun everyStreamFilteredOutStillSaysSoInsteadOfNothingFound() = runTest {
        val f = Fixture(this, sideloaded, listOf(hosted))
        f.data.settings.set("p", ProfileSetting.StreamIncludePattern, "remux")
        runCurrent()
        val state = f.store.state.value
        assertEquals(emptyList(), state.groups.single().streams)
        assertNull(state.emptyText)
        assertEquals("3 streams hidden by your filters", state.filter?.text)
    }

    @Test
    fun theNoticeItOpenedWithHeadsTheList() = runTest {
        val f = Fixture(this, sideloaded, listOf(hosted), notice = "Auto-play found no stream that passes your filters.")
        runCurrent()
        assertEquals("Auto-play found no stream that passes your filters.", f.store.state.value.notice)
        assertNull(Fixture(this, sideloaded, listOf(hosted)).store.state.value.notice)
    }

    @Test
    fun aggregatorSizedListsAreFilteredOnceInTheStore() = runTest {
        val f = Fixture(this, sideloaded, listOf(big, huge))
        f.data.settings.set("p", ProfileSetting.HiddenStreamQualities, listOf(StreamQuality.UHD_4K))
        f.data.settings.set("p", ProfileSetting.StreamExcludePattern, "\\bHDTV\\b")
        runCurrent()
        val state = f.store.state.value
        // Per addon: 300 streams, 75 of them 4K, 15 more HDTV releases.
        assertEquals(listOf(210, 210), state.groups.map { it.streams.size })
        assertEquals(listOf("90 streams hidden by your filters", "90 streams hidden by your filters"), state.groups.map { it.filteredNote })
        assertEquals("180 streams hidden by your filters", state.filter?.text)
        assertTrue(state.groups.flatMap { it.streams }.none { "2160p" in it.name || "HDTV" in it.description.orEmpty() })

        // Showing and hiding them again reuses the parsed rows: nothing is parsed or filtered again.
        val filtered = state.groups[0]
        f.store.toggleFiltered()
        val all = f.store.state.value.groups[0].streams
        assertEquals(300, all.size)
        assertSame(filtered.streams[0], all.first { it.id == filtered.streams[0].id })
        f.store.toggleFiltered()
        assertSame(filtered, f.store.state.value.groups[0])

        f.store.pick(filtered.streams[0].id)
        assertEquals(419, f.host.played.single().alternatives.size)
    }

    @Test
    fun anAddonsChipListsItsGroupsAloneAndAllListsThemAll() = runTest {
        val f = Fixture(this, sideloaded, listOf(slow, hosted, broken, empty, styled))
        f.data.settings.set("p", ProfileSetting.HiddenStreamQualities, listOf(StreamQuality.UHD_4K))
        runCurrent()
        var state = f.store.state.value
        // The slow addon has yet to answer and the empty one has nothing: no chip, and the empty one no group. The failed
        // one has a chip, for its Retry, and so has styled, whose streams the filters all hide.
        assertEquals(listOf("hosted", "broken", "styled"), state.addons.map { it.name })
        assertTrue(state.addons.none { it.selected }, "All is selected")
        assertEquals(listOf("slow", "hosted", "broken", "styled"), state.groups.map { it.addonName })
        assertEquals("3 streams hidden by your filters", state.filter?.text)

        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(listOf("slow", "hosted", "broken", "styled"), f.store.state.value.addons.map { it.name }, "in the groups' order")

        f.store.selectAddon(f.store.state.value.addons[1].id)
        state = f.store.state.value
        assertEquals(listOf(false, true, false, false), state.addons.map { it.selected })
        assertEquals(listOf("hosted"), state.groups.map { it.addonName })
        assertEquals(listOf("[⚡] Hosted 1080p", "Trailer"), state.groups.single().streams.map { it.name })
        // The filters' line counts the list shown, and a pick's alternatives are its streams.
        assertEquals("1 stream hidden by your filters", state.filter?.text)
        f.store.pick(state.groups.single().streams[0].id)
        assertEquals(emptyList(), f.host.played.single().alternatives)

        f.store.selectAddon(state.addons[2].id)
        state = f.store.state.value
        assertEquals(listOf("broken"), state.groups.map { it.addonName })
        assertIs<GroupStatus.Failed>(state.groups.single().status)
        assertIs<StreamsBody.Groups>(state.body, "its error and Retry")

        f.store.selectAddon(null)
        state = f.store.state.value
        assertTrue(state.addons.none { it.selected })
        assertEquals(listOf("slow", "hosted", "broken", "styled"), state.groups.map { it.addonName })
        f.store.pick(state.groups[1].streams[0].id)
        assertEquals(listOf("https://slow.example.com/1.mkv"), f.host.played.last().alternatives.map { it.request()?.url })
    }

    @Test
    fun theSelectedAddonStaysSelectedWhileTheOthersAnswerAndThroughARefresh() = runTest {
        val f = Fixture(this, sideloaded, listOf(slow, hosted, fading))
        runCurrent()
        f.store.selectAddon(f.store.state.value.addons.single { it.name == "hosted" }.id)
        advanceTimeBy(6.seconds)
        runCurrent()
        var state = f.store.state.value
        assertEquals(listOf("slow", "hosted", "fading"), state.addons.map { it.name })
        assertEquals(listOf(false, true, false), state.addons.map { it.selected })
        assertEquals(listOf("hosted"), state.groups.map { it.addonName }, "a later answer joins the row, not the list")

        f.store.selectAddon(state.addons[0].id)
        f.store.refresh()
        runCurrent()
        state = f.store.state.value
        // The slow addon, asked again, loads; its chip and the selection stay meanwhile. The fading addon's empty answer
        // this time takes its chip and its group away.
        assertEquals(listOf("slow", "hosted"), state.addons.map { it.name })
        assertEquals(listOf(true, false), state.addons.map { it.selected })
        assertEquals(listOf("slow"), state.groups.map { it.addonName })
        assertEquals(listOf(GroupStatus.Loading), state.groups.map { it.status })
        advanceTimeBy(6.seconds)
        runCurrent()
        state = f.store.state.value
        assertEquals(listOf(true, false), state.addons.map { it.selected })
        assertEquals(listOf(GroupStatus.Ready), state.groups.map { it.status })

        // A selected addon whose chip goes leaves All selected.
        val g = Fixture(this, sideloaded, listOf(hosted, fading, styled))
        runCurrent()
        g.store.selectAddon(g.store.state.value.addons.single { it.name == "fading" }.id)
        assertEquals(listOf("fading"), g.store.state.value.groups.map { it.addonName })
        g.store.refresh()
        runCurrent()
        state = g.store.state.value
        assertEquals(listOf("hosted", "styled"), state.addons.map { it.name })
        assertTrue(state.addons.none { it.selected })
        assertEquals(listOf("hosted", "styled"), state.groups.map { it.addonName })
    }

    @Test
    fun theAddonChipsShowOnlyWhileTwoOrMoreAddonsHaveOne() = runTest {
        val one = Fixture(this, sideloaded, listOf(hosted, empty))
        val two = Fixture(this, sideloaded, listOf(hosted, empty, broken))
        runCurrent()
        // The empty answer has no chip, which leaves one addon: no chips (the row holds Refresh alone), and no group.
        assertEquals(emptyList(), one.store.state.value.addons)
        assertEquals(listOf("hosted"), one.store.state.value.groups.map { it.addonName })
        assertEquals(listOf("hosted", "broken"), two.store.state.value.addons.map { it.name })
    }

    /**
     * The list as both UIs lay it out: its rows numbered across the groups, focus's first stream, every group headed
     * under All; with an addon's chip selected its group headed only while asked or failed, the selection kept through a
     * refresh.
     */
    @Test
    fun headingsAndRowNumbersFollowTheSelectedAddon() = runTest {
        val none = Fixture(this, sideloaded, emptyList())
        val one = Fixture(this, sideloaded, listOf(cached))
        val f = Fixture(this, sideloaded, listOf(cached, fading, broken))
        runCurrent()
        var state = none.store.state.value
        assertEquals(emptyList(), state.groups)
        assertNull(state.firstStream)
        assertTrue(state.namesGroups)

        state = one.store.state.value
        assertEquals(emptyList(), state.addons)
        assertTrue(state.namesGroups, "a lone addon has no chip: its group is named")
        assertEquals(listOf(true), state.groups.map(state::headed))
        assertEquals(0, state.firstRow(0))
        assertEquals(state.groups.single().streams.single().id, state.firstStream)

        state = f.store.state.value
        assertNull(state.selectedAddon)
        assertTrue(state.namesGroups)
        assertEquals(listOf(true, true, true), state.groups.map(state::headed))
        assertEquals(listOf(0, 1, 2), state.groups.indices.map(state::firstRow))
        assertEquals(state.groups[0].streams[0].id, state.firstStream)

        val cachedChip = state.addons[0].id
        f.store.selectAddon(state.addons[1].id)
        state = f.store.state.value
        assertEquals(state.addons[1].id, state.selectedAddon)
        assertFalse(state.namesGroups, "the chip names the list")
        assertEquals(listOf(false), state.groups.map(state::headed), "an answered group has nothing to head it")
        assertEquals(0, state.firstRow(0), "the list numbers its own rows")
        assertEquals(state.groups.single().streams.single().id, state.firstStream)

        f.store.selectAddon(state.addons[2].id)
        state = f.store.state.value
        assertEquals(listOf(true), state.groups.map(state::headed), "a failed group keeps its error and Retry")
        assertNull(state.firstStream)

        f.store.selectAddon(cachedChip)
        f.store.refresh()
        runCurrent()
        state = f.store.state.value
        assertEquals(cachedChip, state.selectedAddon, "the selection stays through a refresh")
        assertFalse(state.namesGroups)
        assertEquals(listOf("cached"), state.groups.map { it.addonName })
        assertEquals(listOf(false), state.groups.map(state::headed))
        assertEquals(state.groups.single().streams.single().id, state.firstStream)
    }

    @Test
    fun refreshAsksEveryAddonAgainPastTheCache() = runTest {
        val f = Fixture(this, sideloaded, listOf(cached, hosted), cache = AddonResponseCache())
        runCurrent()
        fun asked() = f.http.requests.groupingBy { it.url.host }.eachCount()
        assertEquals(mapOf("cached.example.com" to 1, "hosted.example.com" to 1), asked())
        // A retry asks no addon while none failed.
        f.store.reload()
        runCurrent()
        assertEquals(mapOf("cached.example.com" to 1, "hosted.example.com" to 1), asked())
        assertEquals("Cached answer 1", f.store.state.value.groups[0].streams.single().name)

        f.store.refresh()
        runCurrent()
        assertEquals(mapOf("cached.example.com" to 2, "hosted.example.com" to 2), asked())
        assertEquals("Cached answer 2", f.store.state.value.groups[0].streams.single().name)
        // The cache keeps the new answer.
        f.store.reload()
        runCurrent()
        assertEquals("Cached answer 2", f.store.state.value.groups[0].streams.single().name)
        assertEquals(2, asked()["cached.example.com"])
    }

    /** Retry asks the addon that failed again, alone: an addon whose answer isn't cached isn't asked again for it. */
    @Test
    fun retryAsksOnlyTheAddonThatFailed() = runTest {
        val f = Fixture(this, sideloaded, listOf(hosted, broken), cache = AddonResponseCache())
        runCurrent()
        fun asked() = f.http.requests.groupingBy { it.url.host }.eachCount()
        assertEquals(mapOf("hosted.example.com" to 1, "broken.example.com" to 1), asked())
        assertIs<GroupStatus.Failed>(f.store.state.value.groups[1].status)
        f.store.reload()
        assertEquals(GroupStatus.Loading, f.store.state.value.groups[1].status)
        runCurrent()
        assertEquals(mapOf("hosted.example.com" to 1, "broken.example.com" to 2), asked())
        assertEquals(GroupStatus.Ready, f.store.state.value.groups[0].status)
        assertIs<GroupStatus.Failed>(f.store.state.value.groups[1].status)
    }

    private companion object {
        /** An aggregator's answer: 300 streams cycling 2160p, 1080p, 720p and no resolution, every tenth an HDTV release. */
        fun aggregatorAnswer(host: String): String = (0 until 300).joinToString(",", prefix = """{"streams":[""", postfix = "]}") { i ->
            val quality = listOf("2160p", "1080p", "720p", "")[i % 4]
            val source = if (i % 10 == 0) "HDTV" else "WEB-DL"
            """{"name":"Aggregator\n$quality","description":"Movie.$quality.$source.x265-$i.mkv","url":"https://$host/s/$i"}"""
        }

        /** A profile's database on the test's clock and scheduler. */
        fun testData(scope: TestScope) = LocalData(
            inMemoryDatabase(), { 1_790_000_000_000L },
            StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        )
    }
}

/** The text the list shows instead of streams ([StreamsBody.Empty]); null while it shows something else. */
private val StreamsUiState.emptyText: String?
    get() = (body as? StreamsBody.Empty)?.text
