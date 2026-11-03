package com.edde746.tortuga.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.player.InfoRow
import com.edde746.tortuga.presentation.player.InfoSection
import com.edde746.tortuga.presentation.player.InfoSectionId
import com.edde746.tortuga.presentation.player.PlayerPrompt
import com.edde746.tortuga.presentation.player.PlayerUiState
import com.edde746.tortuga.presentation.player.PromptKind
import com.edde746.tortuga.presentation.player.StillWatchingState
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.probe.FocusedProbe
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class InfoPanelTest {
    /**
     * On a 1920 × 1080 TV (960 × 540 dp) the info shows whole once the controls hide, for each of the test matrix's
     * streams as the Android TV emulator shows them and for an HDR10 remux with Atmos: three columns side by side under
     * the top bar, the card within the screen and its text within the safe area (48 dp in from the sides), and every
     * line of it (the title, the headings, each row's label and value) whole inside the card. A row the card's edge
     * cuts (an Output row half shown, or gone below it) fails.
     */
    @Test
    fun onTvEveryRowShowsWholeOnceTheControlsHide() = runComposeUiTest {
        val probe = PlayerProbe()
        var info by mutableStateOf(remux)
        player(FormFactor.TV, probe = probe) { testPlayerState(episodes = null).copy(controlsVisible = false, infoVisible = true, info = info) }
        for ((name, sections) in matrix + ("hdr10-atmos" to remux)) {
            info = sections
            waitForIdle()
            val card = probe.bounds.getValue("player.info.panel")
            val columns = sections.map { probe.bounds.getValue("player.info.${it.id.name.lowercase()}").left }.distinct()
            assertEquals(3, columns.size, "$name: three columns, ${sections.associate { it.id to probe.bounds["player.info.${it.id.name.lowercase()}"] }}")
            assertTrue(card.left >= 0f && card.top >= 0f && card.right <= px(960) && card.bottom <= px(540 - 28), "$name: the card $card within the screen")
            val lines = infoLines()
            assertEquals(1 + sections.sumOf { 1 + 2 * it.rows.size }, lines.size, "$name: the title, the headings and the rows' labels and values")
            for (line in lines) {
                assertTrue(line.bounds.within(card), "$name: \"${line.text}\" at ${line.bounds} whole inside the card $card")
                assertTrue(line.bounds.left >= px(48) - 0.5f && line.bounds.right <= px(960 - 48) + 0.5f, "$name: \"${line.text}\" at ${line.bounds} within the safe area")
            }
        }
    }

    /**
     * Each column's labels are as wide as its longest label, measured: none wraps ("MaxCLL / MaxFALL", "Mastering
     * display", "Download speed" on one line), and the longest of a column fills the labels' width, its values lined up
     * right after it. On TV, for each of the test matrix's streams.
     */
    @Test
    fun onTvLabelsKeepToOneLine() = runComposeUiTest {
        var info by mutableStateOf(remux)
        player(FormFactor.TV) { testPlayerState(episodes = null).copy(controlsVisible = false, infoVisible = true, info = info) }
        for ((name, sections) in matrix + ("hdr10-atmos" to remux)) {
            info = sections
            waitForIdle()
            assertLabelsOnOneLine(name, sections)
        }
    }

    /**
     * On TV with the controls up, the card ends above them, too short for the Video section's rows: the remote reaches
     * the card (Up from play/pause), Down scrolls it until each of its lines has shown whole inside it, and at its end
     * moves focus on to the controls below.
     */
    @Test
    fun onTvTheRemoteScrollsTheInfoAboveTheControls() = runComposeUiTest {
        val probe = PlayerProbe()
        player(FormFactor.TV, probe = probe) { testPlayerState(episodes = null).copy(infoVisible = true, info = matrix.getValue("dv8")) }
        waitForIdle()
        runOnIdle { assertEquals("player.playPause", probe.focused) }
        val card = probe.bounds.getValue("player.info.panel")
        val seen = BooleanArray(infoLines().size)
        fun look() = infoLines().forEachIndexed { index, line -> if (line.bounds.within(card)) seen[index] = true }
        look()
        assertTrue(!seen.all { it }, "the card's rows don't all fit above the controls")
        var presses = 0
        while (runOnIdle { probe.focused } != "player.info.panel" && presses++ < 4) press(Key.DirectionUp)
        runOnIdle { assertEquals("player.info.panel", probe.focused, "Up from play/pause reached the card") }
        presses = 0
        while (runOnIdle { probe.focused } == "player.info.panel" && presses++ < 20) {
            press(Key.DirectionDown)
            look()
        }
        val lines = infoLines()
        assertTrue(seen.all { it }, "the remote showed every line whole: never ${lines.filterIndexed { index, _ -> !seen[index] }.map { it.text }}")
        val focused = runOnIdle { probe.focused }
        assertTrue(focused != null && focused != "player.info.panel" && focused in probe.bounds, "Down at the card's end moved focus on, to $focused")
    }

    /**
     * On TV with the controls up and the bottom-trailing corner showing, Down at the end of the info card's scroll lands
     * on the sleep timer's Continue where its question shows, else on the prompt button, whatever the corner's height
     * and the button's width: the skip button, up next, up next with a name cut short, the question alone and over up
     * next. Never on the timeline, which a narrow button leaves nearer to the middle of the card.
     */
    @Test
    fun onTvDownAtTheInfosEndLandsOnTheCornersQuestionElseItsButtonWhateverTheCornersSize() {
        val skip = PlayerPrompt(PromptKind.INTRO, lead = null, text = T.player.skip.intro, progress = null, secondsLeft = null, command = PlayerCommand.SkipSegment, withControls = false)
        val upNext = PlayerPrompt(PromptKind.UP_NEXT, lead = T.session.episodeTitle("S1E2", ""), text = "Please Remain Calm", progress = null, secondsLeft = null, command = PlayerCommand.NextEpisode, withControls = false)
        val asking = StillWatchingState(countdown = "Pausing in 25 seconds", secondsLeft = 25, progress = 1f / 6)
        val corners = listOf(
            Triple("the skip button", testPlayerState(prompt = skip), "player.skip"),
            Triple("up next", testPlayerState(prompt = upNext), "player.upNext"),
            Triple(
                "up next with a long name",
                testPlayerState(prompt = upNext.copy(text = "The Unbearably Long Name of an Episode That Goes On and On and On")),
                "player.upNext",
            ),
            Triple("the sleep timer's question", testPlayerState(stillWatching = asking), "player.stillWatching.continue"),
            Triple("the sleep timer's question over up next", testPlayerState(prompt = upNext, stillWatching = asking), "player.stillWatching.continue"),
        )
        val card = "player.info.panel"
        val wrong = mutableListOf<String>()
        for ((corner, state, lands) in corners) runComposeUiTest {
            val probe = FocusedProbe()
            player(FormFactor.TV, probe = probe) { state.copy(episodes = null, infoVisible = true, info = matrix.getValue("dv8")) }
            waitForIdle()
            val start = runOnIdle { probe.focused }
            var presses = 0
            while (runOnIdle { probe.focused } != card && presses++ < 4) press(Key.DirectionUp)
            if (runOnIdle { probe.focused } != card) {
                wrong += "$corner: Up from $start went to ${probe.focused}, not the info card"
            } else {
                presses = 0
                while (runOnIdle { probe.focused } == card && presses++ < 20) press(Key.DirectionDown)
                val landed = runOnIdle { probe.focused }
                if (landed != lands) wrong += "$corner: Down at the info card's end went to $landed, not $lands"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    /**
     * Live values change in place: a bitrate, a dropped-frame count and the download speed growing a digit move no other
     * row, no section and not the card's edges.
     */
    @Test
    fun liveValuesMoveNothingElse() = runComposeUiTest {
        val probe = PlayerProbe()
        var state by mutableStateOf(infoState())
        player(FormFactor.TV, probe = probe) { state }
        waitForIdle()
        val before = HashMap(probe.bounds)
        val labels = remux.flatMap { it.rows }.map { it.label }.distinct().associateWith { labelBounds(it) }
        state = infoState(videoBitrate = "102.4 Mb/s", dropped = "12 (decoder 3)", speed = "112.5 MB/s")
        waitForIdle()
        onNodeWithText("102.4 Mb/s", useUnmergedTree = true).assertExists()
        for (name in listOf("player.info.panel") + sectionNames) {
            assertEquals(before.getValue(name), probe.bounds.getValue(name), "$name moved")
        }
        for ((label, bounds) in labels) assertEquals(bounds, labelBounds(label), "the $label rows moved")
    }

    /**
     * An upright phone has room for one column: the card stays within the screen's width and above the controls, its
     * labels on one line, and what doesn't fit scrolls into view (the network, last).
     */
    @Test
    fun onAnUprightPhoneOneColumnScrolls() = runComposeUiTest {
        val probe = PlayerProbe()
        player(FormFactor.HANDHELD, probe = probe, size = DpSize(360.dp, 740.dp)) { infoState() }
        waitForIdle()
        val card = probe.bounds.getValue("player.info.panel")
        assertTrue(card.left >= 0f && card.right <= px(360), "the card $card within the screen's width")
        val lefts = listOf("player.info.stream", "player.info.video").map { probe.bounds.getValue(it).left }
        assertEquals(1, lefts.distinct().size, "one column: $lefts")
        val controlsTop = listOf("player.position", "player.scrub", "player.playPause").minOf { probe.bounds.getValue(it).top }
        assertTrue(card.bottom <= controlsTop, "the card's bottom ${card.bottom} above the controls' top $controlsTop")
        assertLabelsOnOneLine("upright", remux)
        val speed = onNodeWithText("12.3 MB/s", useUnmergedTree = true)
        speed.performScrollTo()
        waitForIdle()
        val shown = with(density) { speed.getBoundsInRoot().let { Rect(it.left.toPx(), it.top.toPx(), it.right.toPx(), it.bottom.toPx()) } }
        assertTrue(shown.top >= card.top && shown.bottom <= card.bottom, "the download speed $shown scrolled into the card $card")
    }

    /**
     * A phone in landscape lays the sections out in two columns across the card's fixed width, its labels on one line
     * (what doesn't fit under the top bar and above the controls scrolls).
     */
    @Test
    fun onAPhoneInLandscapeTwoColumns() = runComposeUiTest {
        val probe = PlayerProbe()
        player(FormFactor.HANDHELD, probe = probe, size = DpSize(800.dp, 360.dp)) { infoState() }
        waitForIdle()
        val card = probe.bounds.getValue("player.info.panel")
        val stream = probe.bounds.getValue("player.info.stream")
        val video = probe.bounds.getValue("player.info.video")
        assertTrue(video.left > stream.right && abs(video.top - stream.top) < 1f, "Video $video beside Stream $stream")
        assertEquals(px(2 * 300 + 20 + 2 * 16), card.width, 1f, "two columns' width")
        assertTrue(card.right <= px(800) && card.left >= 0f, "the card $card within the screen")
        assertLabelsOnOneLine("landscape", remux)
    }

    private val sectionNames = InfoSectionId.entries.map { "player.info.${it.name.lowercase()}" }

    /** One line of the info card's text and where all of it lies (window pixels; not cut to what the card shows). */
    private class Line(val text: String, val bounds: Rect)

    /** The info card's lines, in order, each where all of it lies. */
    private fun ComposeUiTest.infoLines(): List<Line> = infoTexts().map { Line(it.text, Rect(it.positionInRoot, it.size.toSize())) }

    /** Every text in the info card (the title, the headings, the rows' labels and values), in order: the texts in its scroll. */
    private fun ComposeUiTest.infoTexts(): List<SemanticsNode> =
        onAllNodes(hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()

    private val SemanticsNode.text: String get() = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString()

    private fun Rect.within(outer: Rect): Boolean =
        left >= outer.left - 0.5f && top >= outer.top - 0.5f && right <= outer.right + 0.5f && bottom <= outer.bottom + 0.5f

    /**
     * Every label of [sections] in the card on one line, each column's labels as wide as the longest of them (its text
     * reaching the labels' right edge), and each value starting where its column's labels end.
     */
    private fun ComposeUiTest.assertLabelsOnOneLine(name: String, sections: List<InfoSection>) {
        val labels = sections.flatMap { section -> section.rows.map { it.label } }.toSet()
        val values = sections.flatMap { section -> section.rows.map { it.value } }.toSet()
        val texts = infoTexts()
        val labelNodes = texts.filter { it.text in labels && it.text !in values }
        assertTrue(labelNodes.isNotEmpty(), "$name: labels")
        for (node in labelNodes) assertEquals(1, node.layout().lineCount, "$name: \"${node.text}\" on one line")
        for ((left, column) in labelNodes.groupBy { it.positionInRoot.x }) {
            val width = column.first().size.width
            assertTrue(column.all { it.size.width == width }, "$name: the labels at $left one width")
            val longest = column.maxOf { it.layout().getLineRight(0) }
            assertEquals(width.toFloat(), longest, 1f, "$name: the labels at $left as wide as the longest of them")
        }
        val valueLefts = texts.filter { it.text in values && it.text !in labels }.map { it.positionInRoot.x }.distinct()
        assertEquals(labelNodes.map { it.positionInRoot.x }.distinct().size, valueLefts.size, "$name: one value column per label column ($valueLefts)")
    }

    private fun SemanticsNode.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** Where every row labelled [label] is (several sections have a Bitrate, a Codec, an Output). */
    private fun ComposeUiTest.labelBounds(label: String): List<Rect> =
        onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun ComposeUiTest.px(dp: Int): Float = with(density) { dp.dp.toPx() }

    private fun infoState(
        videoBitrate: String = "48.5 Mb/s",
        dropped: String = "0 (decoder 0)",
        speed: String = "12.3 MB/s",
    ): PlayerUiState = testPlayerState(episodes = null).copy(infoVisible = true, info = remuxSections(videoBitrate, dropped, speed))

    private val remux = remuxSections("48.5 Mb/s", "0 (decoder 0)", "12.3 MB/s")

    /** The info of an HEVC HDR10 remux with TrueHD Atmos playing on an Android TV, as the store lays it out. */
    private fun remuxSections(videoBitrate: String, dropped: String, speed: String) = listOf(
        InfoSection(
            InfoSectionId.STREAM, "Stream",
            listOf(
                InfoRow("Name", "Tortuga Test HDR10 remux"),
                InfoRow("Addon", "Tortuga Test"),
                InfoRow("File", "Movie.2160p.UHD.BluRay.REMUX.HDR.HEVC.TrueHD.7.1.Atmos.mkv"),
                InfoRow("Container", "MKV"),
                InfoRow("Size", "58.3 GB"),
                InfoRow("Bitrate", "62.6 Mb/s"),
            ),
        ),
        InfoSection(
            InfoSectionId.VIDEO, "Video",
            listOf(
                InfoRow("Codec", "HEVC Main 10"),
                InfoRow("Resolution", "3840×2160 · 23.976 fps"),
                InfoRow("Pixel format", "10-bit"),
                InfoRow("Dynamic range", "HDR10"),
                InfoRow("Color", "BT.2020 · PQ"),
                InfoRow("MaxCLL / MaxFALL", "1,000 / 400 nits"),
                InfoRow("Mastering display", "0.005–1,000 nits"),
                InfoRow("Bitrate", videoBitrate),
                InfoRow("Decoder", "Hardware (MediaCodec)"),
                InfoRow("Dropped frames", dropped),
                InfoRow("Delayed frames", "0"),
                InfoRow("Output", "3840×2160 · 23.976 Hz · HDR10"),
            ),
        ),
        InfoSection(
            InfoSectionId.AUDIO, "Audio",
            listOf(
                InfoRow("Codec", "TrueHD Atmos"),
                InfoRow("Channels", "7.1"),
                InfoRow("Sample rate", "48 kHz"),
                InfoRow("Bitrate", "4.2 Mb/s"),
                InfoRow("Track", "English · TrueHD Atmos 7.1"),
                InfoRow("Output", "AudioTrack · Passthrough (TrueHD)"),
            ),
        ),
        InfoSection(InfoSectionId.SUBTITLES, "Subtitles", listOf(InfoRow("Track", "English"), InfoRow("Format", "PGS · Embedded"))),
        InfoSection(InfoSectionId.NETWORK, "Network", listOf(InfoRow("Cache", "42 s · 240 MB"), InfoRow("Download speed", speed))),
    )

    /**
     * The test matrix's streams (tools/media: the HDR10 remux with TrueHD, Dolby Vision 8.1, DTS-HD MA 7.1) as the
     * Android TV emulator shows their info: the longest values the panel meets there ("Tortuga Test Dolby Vision 8",
     * "BT.2020 · PQ · Dolby Vision", "English · English - TrueHD 5.1", the Dolby Vision stream's 45-character audio
     * track title).
     */
    private val matrix = mapOf(
        "remux" to listOf(
            section(
                InfoSectionId.STREAM, "Stream",
                "Name" to "Tortuga Test HDR10 remux", "Addon" to "Tortuga Test", "File" to "remux.mkv", "Container" to "MKV",
                "Size" to "14.6 MB", "Bitrate" to "4.1 Mb/s",
            ),
            section(
                InfoSectionId.VIDEO, "Video",
                "Codec" to "HEVC Main 10", "Resolution" to "3840×2160 · 23.976 fps", "Pixel format" to "10-bit · 4:2:0",
                "Dynamic range" to "HDR10", "Color" to "BT.2020 · PQ", "MaxCLL / MaxFALL" to "1,000 / 41 nits",
                "Mastering display" to "0.0001–1,000 nits", "Bitrate" to "573 kb/s", "Decoder" to "Software",
                "Dropped frames" to "259 (decoder 0)", "Delayed frames" to "0", "Output" to "1920×1080 · 60 Hz · SDR",
            ),
            section(
                InfoSectionId.AUDIO, "Audio",
                "Codec" to "TrueHD", "Channels" to "5.1 (side)", "Sample rate" to "48 kHz", "Bitrate" to "2.6 Mb/s",
                "Track" to "English · English - TrueHD 5.1", "Output" to "AudioTrack",
            ),
            section(InfoSectionId.SUBTITLES, "Subtitles", "Track" to "English · English (forced)", "Format" to "PGS · Embedded"),
            section(InfoSectionId.NETWORK, "Network", "Cache" to "13 s · 13.0 MB", "Download speed" to "0 B/s"),
        ),
        "dv8" to listOf(
            section(
                InfoSectionId.STREAM, "Stream",
                "Name" to "Tortuga Test Dolby Vision 8", "Addon" to "Tortuga Test", "File" to "dv-p81.mkv",
                "Container" to "MKV", "Size" to "1.8 MB", "Bitrate" to "753 kb/s",
            ),
            section(
                InfoSectionId.VIDEO, "Video",
                "Codec" to "HEVC Main 10", "Resolution" to "3840×2160 · 23.976 fps", "Pixel format" to "10-bit · 4:2:0",
                "Dynamic range" to "Dolby Vision profile 8", "Color" to "BT.2020 · PQ · Dolby Vision",
                "MaxCLL / MaxFALL" to "1,000 / 41 nits", "Mastering display" to "0.0001–1,001 nits", "Bitrate" to "559 kb/s",
                "Decoder" to "Software", "Dropped frames" to "220 (decoder 0)", "Delayed frames" to "0",
                "Output" to "1920×1080 · 60 Hz · SDR",
            ),
            section(
                InfoSectionId.AUDIO, "Audio",
                "Codec" to "AAC LC", "Channels" to "Stereo", "Sample rate" to "48 kHz", "Bitrate" to "192 kb/s",
                "Track" to "English · AAC 2.0 - tones L 440 Hz, R 660 Hz", "Output" to "AudioTrack",
            ),
            section(InfoSectionId.NETWORK, "Network", "Cache" to "5 s · 773 KB", "Download speed" to "0 B/s"),
        ),
        "dts" to listOf(
            section(
                InfoSectionId.STREAM, "Stream",
                "Name" to "Tortuga Test DTS-HD MA", "Addon" to "Tortuga Test", "File" to "dts-hdma.mkv", "Container" to "MKV",
                "Size" to "5.1 MB", "Bitrate" to "4.3 Mb/s",
            ),
            section(
                InfoSectionId.VIDEO, "Video",
                "Codec" to "H.264 High", "Resolution" to "1920×1080 · 23.976 fps", "Dynamic range" to "SDR",
                "Color" to "BT.709 · BT.1886", "Bitrate" to "271 kb/s", "Decoder" to "Hardware (MediaCodec)",
                "Dropped frames" to "17 (decoder 0)", "Delayed frames" to "0", "Output" to "1920×1080 · 60 Hz · SDR",
            ),
            section(
                InfoSectionId.AUDIO, "Audio",
                "Codec" to "DTS-HD MA", "Channels" to "7.1", "Sample rate" to "48 kHz", "Bitrate" to "5.0 Mb/s",
                "Track" to "English · DTS-HD MA 7.1", "Output" to "AudioTrack",
            ),
            section(InfoSectionId.NETWORK, "Network", "Cache" to "6 s · 3.7 MB", "Download speed" to "0 B/s"),
        ),
    )

    private fun section(id: InfoSectionId, title: String, vararg rows: Pair<String, String>) =
        InfoSection(id, title, rows.map { (label, value) -> InfoRow(label, value) })
}
