package com.edde746.tortuga.playback.session

import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.AddonLoadable
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.StreamQuality
import com.edde746.tortuga.data.settings.StreamSortKey
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.ResourcePath
import com.edde746.tortuga.model.ResourceRequest
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamBehaviorHints
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.streams.StreamSort
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Auto-play's choice over the addons' answers ([AutoPickRule]): order, filters, playability, reuse last link. */
class StreamAutoPickerTest {
    private fun addon(name: String) = Descriptor(
        Manifest.decode("""{"id":"org.example.$name","version":"1.0.0","name":"$name","types":["series"],"resources":["stream"]}"""),
        "https://$name.example.com/manifest.json".transportUrl(),
    )

    private val first = addon("first")
    private val second = addon("second")

    private fun answer(addon: Descriptor, state: Loadable<List<Stream>>) =
        AddonLoadable(addon, ResourceRequest(addon.transport, ResourcePath("stream", "series", "tt1:1:2")), state)

    private fun ready(addon: Descriptor, vararg streams: Stream) = answer(addon, Loadable.Ready(streams.toList()))

    private fun loading(addon: Descriptor) = answer(addon, Loadable.Loading)

    private fun failed(addon: Descriptor) = answer(addon, Loadable.Error(AddonError.Timeout))

    private fun url(path: String, name: String) = Stream(StreamSource.Url("https://cdn.example/$path"), name = name)

    private val p2p4k = Stream(StreamSource.PeerToPeer("0123456789abcdef0123456789abcdef01234567", fileIdx = 1), name = "P2P 2160p")
    private val trailer = Stream(StreamSource.YouTube("dQw4w9WgXcQ"), name = "Trailer 1080p")
    private val uhd = url("uhd", "2160p")
    private val fhd = url("fhd", "1080p")
    private val hd = url("hd", "720p")
    private val sample = url("sample", "1080p sample")

    private val no4k = StreamFilter(setOf(StreamQuality.UHD_4K), "", "")

    private fun Decision.picked() = assertIs<AutoPickRule.Decision.Picked>(this)

    @Test
    fun theFirstPlayableStreamThatPassesInTheListsOrder() {
        val states = listOf(ready(first, p2p4k, trailer, uhd, sample, fhd), ready(second, hd))
        val pick = AutoPickRule.decide(states, StreamFilter(setOf(StreamQuality.UHD_4K), "", "sample"), remembered = null).picked()
        assertEquals(fhd, pick.choice.stream, "never a peer-to-peer or an external stream; 4K and the sample are filtered")
        assertEquals("first", pick.choice.addonName)
        assertEquals(AutoPlayPick.FIRST_MATCH, pick.how)
        assertEquals(listOf(hd), pick.alternatives.map { it.stream }, "the others that pass, never the filtered ones")

        val unfiltered = AutoPickRule.decide(states, StreamFilter.NONE, remembered = null).picked()
        assertEquals(uhd, unfiltered.choice.stream)
        assertEquals(listOf(sample, fhd, hd), unfiltered.alternatives.map { it.stream })
    }

    @Test
    fun anAddonStillAnsweringBeforeTheMatchKeepsTheChoiceOpenAndAFailedOneIsSkipped() {
        assertEquals(AutoPickRule.Decision.Pending, AutoPickRule.decide(listOf(loading(first), ready(second, fhd)), no4k, null))
        assertEquals(fhd, AutoPickRule.decide(listOf(failed(first), ready(second, fhd)), no4k, null).picked().choice.stream)
        // A match in the first addon doesn't wait for the later ones.
        assertEquals(fhd, AutoPickRule.decide(listOf(ready(first, uhd, fhd), loading(second)), no4k, null).picked().choice.stream)
        assertEquals(AutoPickRule.Decision.Pending, AutoPickRule.decide(listOf(ready(first, uhd), loading(second)), no4k, null))
        assertEquals(AutoPickRule.Decision.None, AutoPickRule.decide(listOf(ready(first, uhd, p2p4k), failed(second)), no4k, null))
        assertEquals(AutoPickRule.Decision.None, AutoPickRule.decide(emptyList(), StreamFilter.NONE, null))
    }

    @Test
    fun reuseLastLinkPlaysTheRememberedStreamWhileItsAddonOffersIt() {
        val remembered = StreamChoice(url("hd", "720p (as remembered)"), second.transportUrl, addonName = "")
        val states = listOf(ready(first, fhd), ready(second, sample, hd))
        val pick = AutoPickRule.decide(states, no4k, remembered).picked()
        assertEquals(hd, pick.choice.stream, "the addon's stream as offered now, matched by its source")
        assertEquals("second", pick.choice.addonName)
        assertEquals(AutoPlayPick.LAST_LINK, pick.how)
        assertEquals(listOf(fhd, sample), pick.alternatives.map { it.stream })

        // Remembered streams skip the filters: they were chosen before.
        val filtered = AutoPickRule.decide(states, StreamFilter(emptySet(), "", "720p"), remembered).picked()
        assertEquals(hd, filtered.choice.stream)

        // Its addon still answering: wait, even with a match before it.
        assertEquals(AutoPickRule.Decision.Pending, AutoPickRule.decide(listOf(ready(first, fhd), loading(second)), no4k, remembered))
    }

    @Test
    fun reuseLastLinkFallsBackToTheFirstMatch() {
        val elsewhere = StreamChoice(url("hd", "720p"), first.transportUrl, addonName = "")
        val states = listOf(ready(first, uhd, fhd), ready(second, hd))
        val sameUrlOtherAddon = AutoPickRule.decide(states, no4k, elsewhere).picked()
        assertEquals(fhd, sameUrlOtherAddon.choice.stream, "the same URL from another addon isn't the remembered stream")
        assertEquals(AutoPlayPick.FIRST_MATCH, sameUrlOtherAddon.how)

        val gone = StreamChoice(url("expired", "720p"), second.transportUrl, addonName = "")
        assertEquals(fhd, AutoPickRule.decide(states, no4k, gone).picked().choice.stream, "no longer offered")
        assertEquals(fhd, AutoPickRule.decide(listOf(ready(first, fhd), failed(second)), no4k, gone).picked().choice.stream, "its addon failed")
        val uninstalled = StreamChoice(url("hd", "720p"), "https://gone.example.com/manifest.json", addonName = "")
        assertEquals(fhd, AutoPickRule.decide(states, no4k, uninstalled).picked().choice.stream, "its addon is gone")
    }

    @Test
    fun aLinkListedTwiceReusesTheOneThatPlayed() {
        // One file under two names (a binge group's and a plain one): the stream as stored, else the first with its URL.
        val inGroup = url("same", "1080p")
        val plain = url("same", "720p")
        val states = listOf(ready(first, inGroup, plain))
        val exact = AutoPickRule.decide(states, StreamFilter.NONE, StreamChoice(plain, first.transportUrl, addonName = "")).picked()
        assertEquals("720p", exact.choice.stream.name)
        val renamed = StreamChoice(url("same", "720p (renamed since)"), first.transportUrl, addonName = "")
        assertEquals("1080p", AutoPickRule.decide(states, StreamFilter.NONE, renamed).picked().choice.stream.name)
    }

    @Test
    fun aLinkMadeForEachAnswerIsFoundAgainByItsFileAndTheNewLinkPlays() {
        // Per-answer links: every answer gives each file a new URL, good only for the device and network that
        // asked. The stream saved on another device (or from an earlier answer) is found again by its file.
        fun file(path: String, filename: String?, size: Long? = null, hash: String? = null) =
            url(path, "1080p").copy(behaviorHints = StreamBehaviorHints(filename = filename, videoSize = size, videoHash = hash))
        val saved = StreamChoice(file("playback/home/e2", "Show.S01E02.1080p.mkv", 1_000), second.transportUrl, addonName = "")
        val other = file("playback/now/e2-720", "Show.S01E02.720p.mkv", 500)
        val now = file("playback/now/e2", "Show.S01E02.1080p.mkv", 1_000)
        val pick = AutoPickRule.decide(listOf(ready(first, fhd), ready(second, other, now)), no4k, saved).picked()
        assertEquals(now, pick.choice.stream, "the file as its addon links it now, never the saved link")
        assertEquals(AutoPlayPick.LAST_LINK, pick.how)

        // A file of that name but another size is another release; so is another hash, whatever the name.
        val resized = file("playback/now/e2", "Show.S01E02.1080p.mkv", 2_000)
        assertEquals(AutoPlayPick.FIRST_MATCH, AutoPickRule.decide(listOf(ready(first, fhd), ready(second, resized)), no4k, saved).picked().how)
        val hashed = StreamChoice(file("playback/home/e2", "Show.S01E02.1080p.mkv", 1_000, "8e245d9679d31e12"), second.transportUrl, addonName = "")
        val rehashed = file("playback/now/e2", "Show.S01E02.1080p.mkv", 1_000, "0000000000000001")
        assertEquals(AutoPlayPick.FIRST_MATCH, AutoPickRule.decide(listOf(ready(first, fhd), ready(second, rehashed)), no4k, hashed).picked().how)
        // The same hash is the same file under a new name.
        val renamed = file("playback/now/e2", "Show S01E02 (renamed).mkv", null, "8E245D9679D31E12")
        assertEquals(renamed, AutoPickRule.decide(listOf(ready(second, renamed)), no4k, hashed).picked().choice.stream)
        // A saved stream with neither file name nor hash is found only by its link.
        val bare = StreamChoice(url("playback/home/e2", "1080p"), second.transportUrl, addonName = "")
        assertEquals(AutoPlayPick.FIRST_MATCH, AutoPickRule.decide(listOf(ready(first, fhd), ready(second, now)), no4k, bare).picked().how)
    }

    @Test
    fun theLastLinkAloneWaitsForItsAddonAndElsePicksNothing() {
        val saved = StreamChoice(url("hd", "720p"), second.transportUrl, addonName = "")
        assertEquals(hd, AutoPickRule.lastLink(listOf(ready(first, fhd), ready(second, sample, hd)), no4k, saved).picked().choice.stream)
        assertEquals(AutoPickRule.Decision.Pending, AutoPickRule.lastLink(listOf(ready(first, fhd), loading(second)), no4k, saved))
        // Not offered, failed, or another addon's: nothing (the list opens), never the first match.
        assertEquals(AutoPickRule.Decision.None, AutoPickRule.lastLink(listOf(ready(first, hd), ready(second, sample)), no4k, saved))
        assertEquals(AutoPickRule.Decision.None, AutoPickRule.lastLink(listOf(ready(first, hd), failed(second)), no4k, saved))
        assertEquals(AutoPickRule.Decision.None, AutoPickRule.lastLink(listOf(ready(first, hd)), no4k, saved))
    }

    @Test
    fun reuseLastLinkOnAnEpisodeNotPlayedTakesTheTitlesLastBingeGroup() {
        fun grouped(path: String, name: String, group: String) = url(path, name).copy(behaviorHints = StreamBehaviorHints(bingeGroup = group))
        val remux = grouped("e2-remux", "2160p remux", "remux")
        val web = grouped("e2-web", "1080p web", "web")
        // Episode 1 last played from the addon's 2160p remux group; episode 2 has no stream of its own in the history.
        val titleLast = StreamChoice(grouped("e1-remux", "2160p remux", "remux"), second.transportUrl, addonName = "")
        val states = listOf(ready(first, fhd), ready(second, web, remux))
        val pick = AutoPickRule.decide(states, no4k, remembered = null, titleLast).picked()
        assertEquals(remux, pick.choice.stream, "the same group from the same addon, though the filters hide 2160p")
        assertEquals(AutoPlayPick.LAST_LINK, pick.how)

        val own = StreamChoice(web, second.transportUrl, addonName = "")
        assertEquals(web, AutoPickRule.decide(states, no4k, own, titleLast).picked().choice.stream, "the episode's own last stream comes first")
        val ownGone = StreamChoice(url("e2-expired", "1080p"), second.transportUrl, addonName = "")
        assertEquals(remux, AutoPickRule.decide(states, no4k, ownGone, titleLast).picked().choice.stream, "its own gone, the title's group")

        assertEquals(AutoPickRule.Decision.Pending, AutoPickRule.decide(listOf(ready(first, fhd), loading(second)), no4k, null, titleLast), "its addon answering")
        val groupGone = AutoPickRule.decide(listOf(ready(first, fhd), ready(second, web)), no4k, null, titleLast).picked()
        assertEquals(fhd, groupGone.choice.stream, "the group not offered: the first match")
        assertEquals(AutoPlayPick.FIRST_MATCH, groupGone.how)
        assertEquals(fhd, AutoPickRule.decide(listOf(ready(first, fhd), failed(second)), no4k, null, titleLast).picked().choice.stream, "its addon failed")
        val ungrouped = StreamChoice(url("e1", "2160p remux"), second.transportUrl, addonName = "")
        assertEquals(fhd, AutoPickRule.decide(states, no4k, null, ungrouped).picked().choice.stream, "a last stream without a group matches nothing")
    }

    @Test
    fun firstMatchGoesByTheSortOverEveryAddonOnceAllAnswered() {
        val byResolution = StreamSort(listOf(StreamSortKey.RESOLUTION))
        val states = listOf(ready(first, p2p4k, hd, sample), ready(second, fhd, uhd))
        // The best of every addon's streams, not the first addon's first: never a peer-to-peer one, the sample filtered.
        val pick = AutoPickRule.decide(states, StreamFilter(emptySet(), "", "sample"), remembered = null, sort = byResolution).picked()
        assertEquals(uhd, pick.choice.stream)
        assertEquals("second", pick.choice.addonName)
        assertEquals(AutoPlayPick.FIRST_MATCH, pick.how)
        assertEquals(listOf(fhd, hd), pick.alternatives.map { it.stream }, "the others that pass, in the sort's order")

        // An addon still answering could have a better one: nothing is decided; a failed one is skipped.
        assertEquals(AutoPickRule.Decision.Pending, AutoPickRule.decide(listOf(ready(first, uhd), loading(second)), no4k, null, sort = byResolution))
        assertEquals(fhd, AutoPickRule.decide(listOf(failed(first), ready(second, hd, fhd)), no4k, null, sort = byResolution).picked().choice.stream)
        assertEquals(AutoPickRule.Decision.None, AutoPickRule.decide(listOf(ready(first, uhd), failed(second)), no4k, null, sort = byResolution))

        // Ties keep the list's order: the first addon's 1080p before the second's.
        val other = url("fhd2", "1080p")
        assertEquals(other, AutoPickRule.decide(listOf(ready(first, hd, other), ready(second, fhd)), no4k, null, sort = byResolution).picked().choice.stream)
    }

    @Test
    fun aRememberedStreamStillWinsUnderASortItsAlternativesSorted() {
        val byResolution = StreamSort(listOf(StreamSortKey.RESOLUTION))
        val states = listOf(ready(first, hd, fhd), ready(second, uhd))
        val remembered = StreamChoice(hd, first.transportUrl, addonName = "")
        val pick = AutoPickRule.decide(states, StreamFilter.NONE, remembered, sort = byResolution).picked()
        assertEquals(hd, pick.choice.stream)
        assertEquals(AutoPlayPick.LAST_LINK, pick.how)
        // After the pick in the sorted list (none ranks under 720p), then the ones before it.
        assertEquals(listOf(uhd, fhd), pick.alternatives.map { it.stream })
    }

    @Test
    fun answersThatEndUndecidedPickNothing() = runTest {
        val h = SessionHarness(this)
        val picker = StreamAutoPicker(h.aggregator, h.data.streamHistory)
        val undecided = flowOf(listOf(loading(first), ready(second, fhd)))
        assertNull(picker.pick(h.profileId, SERIES, "tt1:1:2", h.installed, AutoPlayMode.FIRST_MATCH, no4k, StreamSort.ADDON_ORDER, undecided))
        assertNull(picker.pick(h.profileId, SERIES, "tt1:1:2", h.installed, AutoPlayMode.FIRST_MATCH, no4k, StreamSort.ADDON_ORDER, emptyFlow()))
    }
}

private typealias Decision = AutoPickRule.Decision
