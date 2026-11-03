package com.edde746.tortuga.playback.suggest

import com.edde746.tortuga.data.streams.DeviceClass
import com.edde746.tortuga.data.streams.PickContext
import com.edde746.tortuga.data.streams.PickOutcome
import com.edde746.tortuga.data.streams.PickSource
import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamPick
import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.playback.streams.HdrBadge
import com.edde746.tortuga.playback.streams.ResolutionBadge
import com.edde746.tortuga.playback.streams.SourceBadge
import com.edde746.tortuga.playback.streams.StreamBadgeParser
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.streams.VideoCodecBadge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamScorerTest {
    private val phone = PickContext(DeviceClass.HANDHELD, metered = false, hdrDisplay = false, dolbyVisionDisplay = false, hardwareCodecs = emptySet())
    private val english = LanguagePrefs.of(listOf("eng"), emptyList())

    private val remux4k = PickedStream(ResolutionBadge.UHD_2160, listOf(HdrBadge.DOLBY_VISION), VideoCodecBadge.HEVC, SourceBadge.REMUX, sizeBytes = 60L shl 30)
    private val web1080 = PickedStream(ResolutionBadge.FHD_1080, videoCodec = VideoCodecBadge.HEVC, source = SourceBadge.WEB_DL, sizeBytes = 2L shl 30, audioLanguages = listOf("en"))
    private val avc1080 = PickedStream(ResolutionBadge.FHD_1080, videoCodec = VideoCodecBadge.AVC, source = SourceBadge.BLURAY, sizeBytes = 12L shl 30)
    private val italian = PickedStream(ResolutionBadge.FHD_1080, videoCodec = VideoCodecBadge.HEVC, source = SourceBadge.WEB_DL, sizeBytes = 2L shl 30, audioLanguages = listOf("it"))
    private val sd = PickedStream(ResolutionBadge.SD_480, videoCodec = VideoCodecBadge.AVC, sizeBytes = 700L shl 20)

    /** [n] picks of [web1080] from lists where it is shown below the others. */
    private fun picks(n: Int, outcome: PickOutcome? = PickOutcome.GOOD) = List(n) { i ->
        val shown = listOf(remux4k, avc1080, italian, sd, web1080).shuffled(kotlin.random.Random(i)).let { it - web1080 + web1080 }
        StreamPick("p$i", "tt$i", "tt$i", 6000, i.toLong(), phone, PickSource.LIST, shown, shown.indexOf(web1080), outcome)
    }

    private fun features(vararg streams: PickedStream) = streams.map { StreamScorer.features(it, phone, 6000, english) }

    @Test
    fun learnsTheStreamsTheProfilePicksOverTheOnesItPassesOver() {
        val scorer = StreamScorer.train(picks(12), english, phone.deviceClass)!!
        val suggestion = scorer.best(features(remux4k, avc1080, sd, italian, web1080))!!
        assertEquals(4, suggestion.index)
        val reasons = suggestion.reasons.map { it.feature }
        assertTrue(reasons.isNotEmpty())
        assertTrue(reasons.any { it == StreamFeature.AUDIO_MATCH || it == StreamFeature.WEB_DL || it == StreamFeature.BITRATE }, "$reasons")
        assertTrue(scorer.weights.getValue(StreamFeature.AUDIO_MATCH) > 0, "picked over the same stream in Italian")
    }

    @Test
    fun suggestsNothingFromTooFewPicks() {
        assertNull(StreamScorer.train(picks(StreamScorer.MIN_PICKS - 1), english, phone.deviceClass))
        // Picks that went wrong teach nothing.
        assertNull(StreamScorer.train(picks(20, PickOutcome.BAD), english, phone.deviceClass))
    }

    @Test
    fun aListOfLikeStreamsHasNoSuggestion() {
        val scorer = StreamScorer.train(picks(12), english, phone.deviceClass)!!
        assertNull(scorer.best(features(web1080, web1080)), "nothing sets one apart")
    }

    @Test
    fun aMeteredNetworkWeighsTheBitrateOnItsOwn() {
        val metered = phone.copy(metered = true)
        val x = StreamScorer.features(remux4k, metered, 6000, english)
        assertEquals(x[StreamFeature.BITRATE.ordinal], x[StreamFeature.BITRATE_METERED.ordinal])
        assertEquals(1.0, x[StreamFeature.RES_4K_METERED.ordinal])
        assertEquals(1.0, x[StreamFeature.HDR_NOT_SHOWN.ordinal], "Dolby Vision on an SDR screen")
        assertEquals(0.0, StreamScorer.features(remux4k, phone, 6000, english)[StreamFeature.BITRATE_METERED.ordinal])
    }

    /**
     * What plays wrong or stutters is ruled out before any score: Dolby Vision with no other HDR layer named (profile 5,
     * mostly) on a screen without Dolby Vision, a codec with no hardware decoder. HDR on an SDR screen is tone-mapped:
     * the score's call.
     */
    @Test
    fun onlyStreamsTheDeviceCanShowAsMadeCanBeSuggested() {
        val dvOnly = PickedStream(ResolutionBadge.UHD_2160, listOf(HdrBadge.DOLBY_VISION), VideoCodecBadge.HEVC)
        val dvHdr10 = dvOnly.copy(hdr = listOf(HdrBadge.DOLBY_VISION, HdrBadge.HDR10))
        val hdr10 = dvOnly.copy(hdr = listOf(HdrBadge.HDR10))
        val sdr = PickedStream(ResolutionBadge.FHD_1080, videoCodec = VideoCodecBadge.AV1)
        val hdrScreen = phone.copy(hdrDisplay = true)
        val dvScreen = hdrScreen.copy(dolbyVisionDisplay = true)
        fun shown(context: PickContext) = listOf(dvOnly, dvHdr10, hdr10, sdr).map { StreamScorer.showable(it, context) }
        assertEquals(listOf(false, true, true, true), shown(phone), "an SDR screen: Dolby Vision only with an HDR layer beside it")
        assertEquals(listOf(false, true, true, true), shown(hdrScreen), "an HDR screen without Dolby Vision: the same")
        assertEquals(listOf(true, true, true, true), shown(dvScreen))
        val avcHevc = dvScreen.copy(hardwareCodecs = setOf(VideoCodecBadge.AVC, VideoCodecBadge.HEVC))
        assertEquals(listOf(true, true, true, false), shown(avcHevc), "AV1 without a hardware decoder")
        assertEquals(listOf(true, true, true, true), shown(dvScreen.copy(hardwareCodecs = emptySet())), "decoders unknown: no codec ruled out")
    }

    /**
     * A profile that picks the smallest of like streams is suggested a small file in a long list (a film's styled-text
     * and plain-text lists in fixtures/streams), whatever runtime the meta gives: none, the film's,
     * or one no film has (a test addon's "1 min", a trailer's), which once made every file's bitrate the same.
     */
    @Test
    fun aSmallFilesHistoryIsSuggestedASmallFileWhateverTheRuntime() {
        fun hevc1080(gigabytes: Long) = PickedStream(ResolutionBadge.FHD_1080, videoCodec = VideoCodecBadge.HEVC, source = SourceBadge.BLURAY, sizeBytes = gigabytes shl 30)
        val history = List(20) { i ->
            val shown = listOf(hevc1080(25), hevc1080(8), remux4k, hevc1080(2), hevc1080(12)).shuffled(kotlin.random.Random(i))
            StreamPick("s$i", "tt$i", "tt$i", 8400, i.toLong(), phone, PickSource.LIST, shown, shown.indexOf(hevc1080(2)), PickOutcome.GOOD)
        }
        val scorer = StreamScorer.train(history, english, phone.deviceClass)!!
        val list = listOf("styled-texts" to 20, "plain-texts" to 30).flatMap { (folder, count) ->
            (AddonResponse.decode(FixtureCorpus.text("streams/$folder/movie-ex0001.json")).content as ResourceContent.Streams).items.take(count)
        }.map { stream -> StreamBadgeParser.parse(stream).let { PickedStream.of(it, StreamFilter.sizeOf(stream, it)) } }
        for (runtime in listOf(null, 8520L, 60L)) {
            val suggestion = assertNotNull(scorer.best(list.map { StreamScorer.features(it, phone, runtime, english) }), "runtime $runtime: no suggestion")
            val best = list[suggestion.index]
            assertTrue((best.sizeBytes ?: Long.MAX_VALUE) < 4L shl 30, "runtime $runtime: suggested ${best.resolution} ${best.sizeBytes?.shr(20)} MB")
        }
    }
}
