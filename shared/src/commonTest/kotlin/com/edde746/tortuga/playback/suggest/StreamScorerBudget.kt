package com.edde746.tortuga.playback.suggest

import com.edde746.tortuga.data.settings.StreamSortKey
import com.edde746.tortuga.data.streams.DeviceClass
import com.edde746.tortuga.data.streams.PickContext
import com.edde746.tortuga.data.streams.PickOutcome
import com.edde746.tortuga.data.streams.PickSource
import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamPick
import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.playback.streams.ResolutionBadge
import com.edde746.tortuga.playback.streams.StreamBadgeParser
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.streams.VideoCodecBadge
import kotlin.random.Random
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

/**
 * What suggesting costs where it runs, at the most a profile has: [PICKS] picks on each kind of device (every kind's
 * learnt from together, [Pooling]), each keeping as many streams as a pick keeps ([StreamPicks.ABOVE] above its chosen
 * one, [StreamPicks.BELOW] below) from the largest list in fixtures/streams (380 streams for an episode), then that
 * whole list scored as a Streams list opening scores it. Parsing the list's texts is the list's own cost, not the
 * suggestion's, and is left out.
 */
internal object StreamScorerBudget {
    const val PICKS: Int = 400

    /**
     * Learning from nothing: the first run and the fastest of [RUNS] (the runtime's code warmed up); learning again
     * after one more pick from the scorer before ([StreamScorer.train]'s `from`), as [StreamPicks] does, the fastest of
     * [RUNS]; list scoring, first and fastest; the learnt model's size; the training's arrays.
     */
    class Cost(
        val streams: Int,
        val learnFirstMillis: Double,
        val learnMillis: Double,
        val relearnMillis: Double,
        val scoreFirstMillis: Double,
        val scoreMillis: Double,
        val modelBytes: Int,
        val trainingBytes: Long,
    ) {
        override fun toString(): String =
            "$PICKS picks on each of ${DeviceClass.entries.size} kinds of device, $streams streams: learn ${learnFirstMillis.f()} ms first, " +
                "${learnMillis.f()} ms fastest; again after a pick ${relearnMillis.f()} ms; score ${scoreFirstMillis.f()} ms first, " +
                "${scoreMillis.f()} ms fastest; model $modelBytes B; training arrays ${trainingBytes / 1024} KiB"

        private fun Double.f() = (kotlin.math.round(this * 10) / 10).toString()
    }

    private const val RUNS = 5

    fun measure(): Cost {
        val items = (AddonResponse.decode(FixtureCorpus.text("streams/plain-texts/series-ex0014-1-1.json")).content as ResourceContent.Streams).items
        val streams = items.map { s -> StreamBadgeParser.parse(s).let { PickedStream.of(it, StreamFilter.sizeOf(s, it)) } }
        val tv = PickContext(DeviceClass.TV, metered = false, hdrDisplay = true, dolbyVisionDisplay = false, hardwareCodecs = setOf(VideoCodecBadge.HEVC, VideoCodecBadge.AVC))
        val languages = LanguagePrefs.of(listOf("eng"), emptyList())
        val prior = StreamScorer.prior(listOf(StreamSortKey.RESOLUTION), languages)
        val keep = StreamPicks.ABOVE + 1 + StreamPicks.BELOW
        val random = Random(7)
        // A steady taste with some noise: 1080p, smaller files; the same on every kind of device.
        val picks = DeviceClass.entries.flatMap { deviceClass ->
            List(PICKS) { i ->
                val start = random.nextInt(streams.size - keep)
                val shown = streams.subList(start, start + keep)
                val chosen = shown.indices.maxBy {
                    (if (shown[it].resolution == ResolutionBadge.FHD_1080) 2.0 else 0.0) - (shown[it].sizeBytes ?: 0L) / 1e10 + random.nextDouble()
                }
                val id = "${deviceClass.key}-$i"
                StreamPick(id, "tt$i", "tt$i:1:1", 2700, i.toLong(), tv.copy(deviceClass = deviceClass), PickSource.LIST, shown, chosen, PickOutcome.GOOD, firstRow = start)
            }
        }
        val context = tv
        val trains = List(RUNS) { timed { StreamScorer.train(picks, languages, context.deviceClass, prior = prior) } }
        val scorer = checkNotNull(trains.first().second) { "no scorer from $PICKS picks" }
        // One more pick on the TV, the oldest of its kind dropped as the cap drops it.
        val next = picks.last { it.context.deviceClass == DeviceClass.TV }.let { it.copy(id = "tv-next", chosen = (it.chosen + 1) % it.candidates.size) }
        val again = picks.filterNot { it.id == "tv-0" } + next
        val relearns = List(RUNS) { timed { StreamScorer.train(again, languages, context.deviceClass, prior = prior, from = scorer) } }
        val scores = List(RUNS) { timed { scorer.best(streams.map { StreamScorer.features(it, context, 2700, languages) }) } }
        val features = StreamFeature.entries.size
        return Cost(
            streams = streams.size,
            learnFirstMillis = trains.first().first,
            learnMillis = trains.minOf { it.first },
            relearnMillis = relearns.minOf { it.first },
            scoreFirstMillis = scores.first().first,
            scoreMillis = scores.minOf { it.first },
            modelBytes = features * Double.SIZE_BYTES,
            // Each candidate's non-zero features in two blocks (shared and its kind's), an index and a value each.
            trainingBytes = picks.sumOf { pick ->
                pick.candidates.sumOf { c -> StreamScorer.features(c, pick.context, 2700, languages).count { it != 0.0 }.toLong() }
            } * 2 * (Int.SIZE_BYTES + Double.SIZE_BYTES),
        )
    }

    private fun <T> timed(block: () -> T): Pair<Double, T> {
        val start = TimeSource.Monotonic.markNow()
        val value = block()
        return start.elapsedNow().toDouble(DurationUnit.MILLISECONDS) to value
    }
}
