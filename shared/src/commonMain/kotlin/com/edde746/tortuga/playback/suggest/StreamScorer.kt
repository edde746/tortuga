package com.edde746.tortuga.playback.suggest

import com.edde746.tortuga.data.settings.StreamSortKey
import com.edde746.tortuga.data.streams.DeviceClass
import com.edde746.tortuga.data.streams.PickContext
import com.edde746.tortuga.data.streams.PickOutcome
import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamPick
import com.edde746.tortuga.lang.LanguageTags
import com.edde746.tortuga.playback.streams.AudioBadge
import com.edde746.tortuga.playback.streams.ChannelsBadge
import com.edde746.tortuga.playback.streams.HdrBadge
import com.edde746.tortuga.playback.streams.ResolutionBadge
import com.edde746.tortuga.playback.streams.SourceBadge
import com.edde746.tortuga.playback.streams.VideoCodecBadge
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What a stream offers, as the scorer weighs it: each one a number from the stream's text, the device and the profile's
 * languages ([StreamScorer.features]). [nameable] ones can make a suggestion's reason ("1080p", "English audio").
 */
enum class StreamFeature(val nameable: Boolean) {
    RES_4K(true), RES_1440(true), RES_1080(true), RES_720(true), RES_SD(true),

    /** Dolby Vision on a screen that shows it. */
    DV_SHOWN(true),

    /** HDR (not Dolby Vision) on a screen that shows HDR. */
    HDR_SHOWN(true),

    /** HDR or Dolby Vision the screen can't show: tone-mapped to SDR. */
    HDR_NOT_SHOWN(false),
    HEVC(true), AV1(true), AVC(true),

    /** A codec this device's hardware doesn't decode (when the platform tells). */
    NOT_HARDWARE(false),
    REMUX(true), BLURAY(true), WEB_DL(true), WEBRIP(true),

    /** HDTV, HDRip, DVD, DVDRip, TV rip. */
    LOW_SOURCE(false),

    /** log2 of the bitrate (size over the runtime) over 8 Mbps, within ±4: higher is a larger file for its length. */
    BITRATE(true),

    /** [BITRATE] squared ([ScorerOptions.sizeCurve]): with it, a liking for files of a middle size. */
    BITRATE_SQUARED(false),
    SIZE_UNKNOWN(false),

    /** [BITRATE] on a metered network. */
    BITRATE_METERED(true),

    /** 4K on a metered network. */
    RES_4K_METERED(false),

    /** 1080p, 720p on a metered network ([ScorerOptions.interactions]). */
    RES_1080_METERED(false),
    RES_720_METERED(false),

    /** Its audio is in one of the profile's audio languages. */
    AUDIO_MATCH(true),

    /** It names audio languages, none of the profile's. */
    AUDIO_OTHER(false),
    MULTI_AUDIO(false),

    /** Its subtitles are in one of the profile's subtitle languages. */
    SUBTITLE_MATCH(true),

    /** Marked instant by its addon: +1; marked not ready: −1. */
    CACHED(true),
    SURROUND(true),

    /** TrueHD, DTS-HD MA, DTS:X or FLAC. */
    LOSSLESS_AUDIO(true),
    ATMOS(true),
}

/** The profile's audio and subtitle languages, as canonical tags, most wanted first. */
data class LanguagePrefs(val audio: List<String>, val subtitles: List<String>) {
    companion object {
        /** From the profile's settings' codes (`eng`, `pob`). */
        fun of(audio: List<String>, subtitles: List<String>): LanguagePrefs =
            LanguagePrefs(audio.mapNotNull(LanguageTags::canonical).distinct(), subtitles.mapNotNull(LanguageTags::canonical).distinct())
    }
}

/**
 * Why the scorer ranks a stream first: a feature it has that weighs for it, how much ([weight]), and how far the stream
 * stands from the list's mean in it ([lead]: below the mean for a smaller file).
 */
data class Reason(val feature: StreamFeature, val weight: Double, val lead: Double)

/**
 * The best of a list ([StreamScorer.best]): its [index], every stream's score, and the [reasons] behind it, strongest
 * first.
 */
data class Suggestion(val index: Int, val scores: List<Double>, val reasons: List<Reason>)

/** How [StreamScorer.train] learns from a profile's picks on its other kinds of device ([ScorerOptions.pooling]). */
enum class Pooling {
    /** From the picks on the device's own kind alone. */
    CLASS_ONLY,

    /**
     * From every pick, what travels (languages, source, cached) learnt from all of them, and what depends on the device
     * (resolution, HDR and Dolby Vision, codec, size, sound, the metered features) from each kind's own picks.
     */
    SPLIT,

    /**
     * From every pick, each feature a weight every kind of device shares, plus an offset per kind of device pulled to
     * none much harder ([ScorerOptions.offsetRidge]): a taste is one kind's own only where that kind's picks clearly
     * differ. The metered features, which only a metered network has, take no offset.
     */
    OFFSETS,
}

/**
 * How [StreamScorer.train] learns and [StreamScorer.best] chooses. The defaults are the product's, the offline
 * evaluation's best (dev's `StreamSuggestionEvaluationTest`, the experiments it kept); the rest are its experiments.
 *
 * @param listwise a conditional logit over each pick's shown streams (the chosen one against all of them at once)
 *   instead of logistic regression on the chosen one against each other one.
 * @param positionCorrection learn with each stream's list row as a training-only feature, so the weights take what
 *   the profile likes apart from where the list showed it (rows near the top are seen and tapped more); scoring leaves
 *   it out.
 * @param halfLifePicks a pick this many picks older counts half; null counts them all alike.
 * @param belowWeight (pairwise) the weight of a pair with a stream shown below the chosen one.
 * @param badWeight the weight of a pick that went wrong as a negative: its chosen stream below the others; 0 leaves
 *   it out.
 * @param interactions weigh 1080p and 720p on a metered network apart ([StreamFeature.RES_1080_METERED]).
 * @param dedupe (listwise) count copies of one release (the same features) once among a pick's other streams.
 * @param sizeCurve weigh the bitrate's square too ([StreamFeature.BITRATE_SQUARED]): a liking for a middle size.
 * @param blend how much the list's own order (its sort) counts in [StreamScorer.best], fading as the device's kind
 *   gets picks: [blend] × 10 / (10 + picks) × log2(1 + row) off each row's score.
 * @param minPicks the fewest usable picks it suggests from.
 * @param minConsistency the least [StreamScorer.consistency] it suggests with.
 * @param pooling how the picks on the profile's other kinds of device count ([Pooling]).
 * @param offsetRidge ([Pooling.OFFSETS]) the ridge pulling each kind of device's offsets to none.
 */
data class ScorerOptions(
    val listwise: Boolean = true,
    val positionCorrection: Boolean = false,
    val ridge: Double = 2.0,
    val halfLifePicks: Double? = 120.0,
    val belowWeight: Double = 1.0,
    val goodWeight: Double = 1.0,
    val unknownWeight: Double = 0.7,
    val backedOutWeight: Double = 0.4,
    val badWeight: Double = 0.0,
    val interactions: Boolean = true,
    val dedupe: Boolean = false,
    val sizeCurve: Boolean = true,
    val blend: Double = 0.5,
    val minPicks: Int = 5,
    val minConsistency: Double = 0.0,
    val pooling: Pooling = Pooling.OFFSETS,
    val offsetRidge: Double = 5.0,
)

/**
 * A profile's taste in streams on one kind of device, learnt from its hand picks there and on its other kinds of device
 * ([train], [Pooling]): logistic regression on what each pick says, that the chosen stream beat the others shown around
 * it ([ScorerOptions]). A pick that went well counts fully, one with nothing known yet less, one left within 30 s
 * little, one that went wrong not at all unless as a negative; newer picks count more. The weights are
 * ridge-regularised towards a prior from the profile's settings ([prior]) and readable ([weights]); a few hundred bytes.
 */
class StreamScorer private constructor(
    private val w: DoubleArray,
    /** The usable picks made on this kind of device. */
    val picksUsed: Int,
    /**
     * The weighted share of the picks' pairs the weights rank as the profile did: how steady its taste is (picks made
     * at random leave it near a half); null when nothing asked for it ([ScorerOptions.minConsistency] 0 with listwise
     * learning, which then gathers no pairs).
     */
    val consistency: Double?,
    private val options: ScorerOptions,
    /** Every trained weight, the blocks of every kind of device among them: where learning again starts from. */
    private val trained: DoubleArray,
) {
    /** Each feature's weight. */
    val weights: Map<StreamFeature, Double> get() = StreamFeature.entries.associateWith { w[it.ordinal] }

    fun score(features: DoubleArray): Double = dot(w, features)

    /**
     * The best of [candidates] (each [features], in the list's order): the highest score less the list order's share
     * ([ScorerOptions.blend]), with its reasons: its nameable features whose weight times its lead over the list's mean
     * is positive. Null for an empty list, or when nothing sets the best apart.
     */
    fun best(candidates: List<DoubleArray>): Suggestion? {
        if (candidates.isEmpty()) return null
        val scores = candidates.map(::score)
        val beta = options.blend * 10.0 / (10.0 + picksUsed)
        val index = scores.indices.maxBy { scores[it] - beta * ln(1.0 + it) / ln(2.0) }
        val mean = DoubleArray(FEATURES) { f -> candidates.sumOf { it[f] } / candidates.size }
        val best = candidates[index]
        val reasons = StreamFeature.entries
            .filter { it.nameable && best[it.ordinal] != 0.0 }
            .map { Reason(it, w[it.ordinal] * (best[it.ordinal] - mean[it.ordinal]), best[it.ordinal] - mean[it.ordinal]) }
            .filter { it.weight > MIN_REASON }
            .sortedByDescending { it.weight }
        if (reasons.isEmpty()) return null
        return Suggestion(index, scores, reasons)
    }

    companion object {
        private val FEATURES = StreamFeature.entries.size

        /** The training-only position features ([ScorerOptions.positionCorrection]): log2(1 + row), row / 20. */
        private const val POSITION = 2

        /** The fewest usable picks the product suggests from ([ScorerOptions.minPicks]). */
        val MIN_PICKS: Int get() = ScorerOptions().minPicks

        private const val MIN_REASON = 0.05

        /** The most steps [newton] takes, most of them reusing a Hessian's factor; and the step it stops below. */
        private const val MAX_STEPS = 40
        private const val TOLERANCE = 1e-6

        /** A runtime to read bitrates by when the meta gives none, or one no film or episode has. */
        private const val DEFAULT_RUNTIME_SECONDS = 90L * 60

        /**
         * The shortest runtime bitrates are read by. A shorter one (a test addon's "1 min", a trailer's) would put every
         * file of the list past the bitrate's cap, so that size no longer told them apart.
         */
        private const val MIN_RUNTIME_SECONDS = 10L * 60

        private fun ScorerOptions.weightOf(outcome: PickOutcome?): Double = when (outcome) {
            PickOutcome.GOOD -> goodWeight
            null -> unknownWeight
            PickOutcome.BACKED_OUT -> backedOutWeight
            PickOutcome.BAD -> 0.0
        }

        /**
         * The scorer for a [deviceClass] device of [picks] (oldest first, from every kind of device the profile picked on;
         * [ScorerOptions.pooling] says how the other kinds' count) for a profile wanting [languages], its weights pulled
         * towards [prior] (zero without one); null with under [ScorerOptions.minPicks] usable picks, or when the weights
         * rank under [ScorerOptions.minConsistency] of their pairs as the profile did (its picks follow no steady taste).
         * Learning starts from the weights of [from], a scorer learnt before with the same options, so learning again
         * after a few new picks takes a step or two; the result is the same.
         */
        fun train(
            picks: List<StreamPick>,
            languages: LanguagePrefs,
            deviceClass: DeviceClass,
            options: ScorerOptions = ScorerOptions(),
            prior: DoubleArray? = null,
            from: StreamScorer? = null,
        ): StreamScorer? {
            val pooled = if (options.pooling == Pooling.CLASS_ONLY) picks.filter { it.context.deviceClass == deviceClass } else picks
            val usable = pooled.filter { options.weightOf(it.outcome) > 0 || (options.badWeight > 0 && it.outcome == PickOutcome.BAD) }
            if (usable.count { it.outcome != PickOutcome.BAD } < options.minPicks) return null
            val layout = Layout(options, prior)
            val lists = ArrayList<Training>()
            val pairs = ArrayList<Training>()
            usable.forEachIndexed { i, pick ->
                val recency = options.halfLifePicks?.let { 0.5.pow((usable.size - 1 - i) / it) } ?: 1.0
                val zs = pick.candidates.mapIndexed { j, candidate ->
                    layout.expand(features(candidate, pick.context, pick.runtimeSeconds, languages, options), pick.context.deviceClass, pick.firstRow + j)
                }
                val bad = pick.outcome == PickOutcome.BAD
                val weight = recency * (if (bad) options.badWeight else options.weightOf(pick.outcome))
                val chosen = zs[pick.chosen]
                if (options.listwise && !bad) {
                    if (!options.dedupe) {
                        lists += Training(zs, pick.chosen, weight)
                    } else {
                        val others = zs.filterIndexed { j, z -> j != pick.chosen && !z.sameAs(chosen) }
                            .fold(ArrayList<Sparse>()) { kept, z -> kept.apply { if (none { it.sameAs(z) }) add(z) } }
                        lists += Training(listOf(chosen) + others, 0, weight)
                    }
                }
                // Listwise, the pairs only measure the consistency: gathered only when it gates the suggestions.
                if (options.listwise && !bad && options.minConsistency <= 0) return@forEachIndexed
                zs.forEachIndexed { j, z ->
                    if (j == pick.chosen) return@forEachIndexed
                    val d = if (bad) z.minus(chosen) else chosen.minus(z)
                    if (d.index.isEmpty()) return@forEachIndexed
                    val pairWeight = weight * if (j < pick.chosen) 1.0 else options.belowWeight
                    pairs += Training(listOf(d), 0, pairWeight, consistencyOnly = options.listwise && !bad)
                }
            }
            val start = from?.trained?.takeIf { from.options == options } ?: layout.w0
            val w = newton(lists + pairs, layout.w0, layout.ridge, start)
            val consistency = if (options.listwise && options.minConsistency <= 0) {
                null
            } else {
                val counted = pairs.filter { it.weight > 0 && layout.offers(it.zs[0]) }
                val total = counted.sumOf { it.weight }
                val right = counted.sumOf { if (layout.tasteDot(w, it.zs[0]) > 0) it.weight else 0.0 }
                if (total > 0) right / total else 0.0
            }
            if (consistency != null && consistency < options.minConsistency) return null
            return StreamScorer(layout.collapse(w, deviceClass), usable.count { it.context.deviceClass == deviceClass }, consistency, options, w)
        }

        /** A vector by its non-zero entries, [index] ascending. */
        private class Sparse(val index: IntArray, val value: DoubleArray) {
            fun dot(w: DoubleArray): Double {
                var sum = 0.0
                for (i in index.indices) sum += w[index[i]] * value[i]
                return sum
            }

            /** This minus [other], without the entries that cancel. */
            fun minus(other: Sparse): Sparse {
                val i = IntArray(index.size + other.index.size)
                val v = DoubleArray(i.size)
                var n = 0
                var a = 0
                var b = 0
                while (a < index.size || b < other.index.size) {
                    val ia = if (a < index.size) index[a] else Int.MAX_VALUE
                    val ib = if (b < other.index.size) other.index[b] else Int.MAX_VALUE
                    val at = minOf(ia, ib)
                    val d = (if (ia == at) value[a++] else 0.0) - (if (ib == at) other.value[b++] else 0.0)
                    if (d != 0.0) {
                        i[n] = at
                        v[n++] = d
                    }
                }
                return Sparse(i.copyOf(n), v.copyOf(n))
            }

            fun sameAs(other: Sparse): Boolean = index.contentEquals(other.index) && value.contentEquals(other.value)
        }

        /**
         * Where a pick's features go among the trained weights ([ScorerOptions.pooling]): a block every kind of device
         * shares, then, pooled, a block per kind of device ([DeviceClass] order), then the position features
         * ([ScorerOptions.positionCorrection]); and each weight's prior ([w0]) and [ridge].
         */
        private class Layout(options: ScorerOptions, prior: DoubleArray?) {
            private val pooling = options.pooling
            private val position = options.positionCorrection

            /** The weights of what a stream offers: the blocks, before the position features. */
            val taste = FEATURES * (if (pooling == Pooling.CLASS_ONLY) 1 else 1 + DeviceClass.entries.size)
            val w0 = DoubleArray(taste + if (position) POSITION else 0)
            val ridge = DoubleArray(w0.size) { options.ridge }

            init {
                for (f in 0 until FEATURES) {
                    val p = prior?.get(f) ?: 0.0
                    when (pooling) {
                        Pooling.CLASS_ONLY -> w0[f] = p
                        Pooling.SPLIT -> if (DEVICE_BOUND[f]) DeviceClass.entries.forEach { w0[block(it) + f] = p } else w0[f] = p
                        Pooling.OFFSETS -> {
                            w0[f] = p
                            DeviceClass.entries.forEach { ridge[block(it) + f] = options.offsetRidge }
                        }
                    }
                }
            }

            private fun block(deviceClass: DeviceClass) = FEATURES * (1 + deviceClass.ordinal)

            /** [x] (a stream's [features]) of a pick on a [deviceClass] device, shown at [row], in this layout. */
            fun expand(x: DoubleArray, deviceClass: DeviceClass, row: Int): Sparse {
                val index = IntArray(2 * FEATURES + POSITION)
                val value = DoubleArray(index.size)
                var n = 0
                fun put(i: Int, v: Double) {
                    if (v == 0.0) return
                    index[n] = i
                    value[n++] = v
                }
                for (f in 0 until FEATURES) {
                    if (pooling != Pooling.SPLIT || !DEVICE_BOUND[f]) put(f, x[f])
                }
                if (pooling != Pooling.CLASS_ONLY) {
                    val k = block(deviceClass)
                    for (f in 0 until FEATURES) {
                        val own = if (pooling == Pooling.SPLIT) DEVICE_BOUND[f] else !CONTEXT_ONLY[f]
                        if (own) put(k + f, x[f])
                    }
                }
                if (position) {
                    put(taste, ln(1.0 + row) / ln(2.0))
                    put(taste + 1, row / 20.0)
                }
                return Sparse(index.copyOf(n), value.copyOf(n))
            }

            /** Whether [z] (a pair's difference) differs in what the streams offer, not only in their rows. */
            fun offers(z: Sparse): Boolean = z.index.any { it < taste }

            /** [w] times [z] without the position features. */
            fun tasteDot(w: DoubleArray, z: Sparse): Double {
                var sum = 0.0
                for (i in z.index.indices) if (z.index[i] < taste) sum += w[z.index[i]] * z.value[i]
                return sum
            }

            /** The weights a [deviceClass] device scores by: the shared block plus its own. */
            fun collapse(w: DoubleArray, deviceClass: DeviceClass): DoubleArray =
                DoubleArray(FEATURES) { f -> w[f] + if (pooling == Pooling.CLASS_ONLY) 0.0 else w[block(deviceClass) + f] }
        }

        /**
         * The features that depend on the device a stream plays on ([Pooling.SPLIT]): resolution, HDR and Dolby Vision,
         * codec, size, sound, and the metered features.
         */
        private val DEVICE_BOUND = BooleanArray(FEATURES).also { bound ->
            listOf(
                StreamFeature.RES_4K, StreamFeature.RES_1440, StreamFeature.RES_1080, StreamFeature.RES_720, StreamFeature.RES_SD,
                StreamFeature.DV_SHOWN, StreamFeature.HDR_SHOWN, StreamFeature.HDR_NOT_SHOWN, StreamFeature.HEVC, StreamFeature.AV1,
                StreamFeature.AVC, StreamFeature.NOT_HARDWARE, StreamFeature.BITRATE, StreamFeature.BITRATE_SQUARED,
                StreamFeature.BITRATE_METERED, StreamFeature.RES_4K_METERED, StreamFeature.RES_1080_METERED,
                StreamFeature.RES_720_METERED, StreamFeature.SURROUND, StreamFeature.LOSSLESS_AUDIO, StreamFeature.ATMOS,
            ).forEach { bound[it.ordinal] = true }
        }

        /** The features only a metered network has: no kind of device has an offset for them ([Pooling.OFFSETS]). */
        private val CONTEXT_ONLY = BooleanArray(FEATURES).also { only ->
            listOf(StreamFeature.BITRATE_METERED, StreamFeature.RES_4K_METERED, StreamFeature.RES_1080_METERED, StreamFeature.RES_720_METERED)
                .forEach { only[it.ordinal] = true }
        }

        /**
         * One thing a pick says: with one entry in [zs] a pair difference (the first stream beat the second: a logistic
         * term), with several a list and the [chosen] one of it (a conditional logit term). [consistencyOnly] pairs only
         * measure [consistency] (the list terms already learn from them).
         */
        private class Training(val zs: List<Sparse>, val chosen: Int, val weight: Double, val consistencyOnly: Boolean = false)

        /**
         * Ridge-regularised (each weight towards its [w0] by its [ridge]) logistic and conditional logit terms
         * ([Training]), by Newton's method from [start]. Gathering the Hessian costs about ten times what gathering the
         * gradient does, so a step reuses the last Hessian's factor while the steps keep shrinking fast (a learnt
         * scorer's weights, [train]'s `from`, are a step or two from the new ones), and gathers a fresh one when a step
         * shrank by less than half.
         */
        private fun newton(terms: List<Training>, w0: DoubleArray, ridge: DoubleArray, start: DoubleArray): DoubleArray {
            val n = w0.size
            val w = start.copyOf()
            val used = terms.filter { !it.consistencyOnly && it.weight > 0 }
            // A list term's probability-weighted mean of its streams, over the entries it touches.
            val mean = DoubleArray(n)
            val touched = IntArray(n)
            val marked = BooleanArray(n)
            var factor: Array<DoubleArray>? = null
            var previous = Double.MAX_VALUE
            repeat(MAX_STEPS) {
                val hessian = if (factor == null) Array(n) { r -> DoubleArray(n).also { row -> row[r] = ridge[r] } } else null
                val gradient = DoubleArray(n) { ridge[it] * (w[it] - w0[it]) }
                for (term in used) {
                    if (term.zs.size == 1) {
                        val x = term.zs[0]
                        val p = 1.0 / (1.0 + exp(-x.dot(w)))
                        addOuter(gradient, hessian, x, term.weight * (p - 1.0), term.weight * p * (1.0 - p))
                    } else {
                        val s = DoubleArray(term.zs.size) { term.zs[it].dot(w) }
                        val top = s.max()
                        val e = DoubleArray(s.size) { exp(s[it] - top) }
                        val sum = e.sum()
                        var count = 0
                        for (j in term.zs.indices) {
                            val p = e[j] / sum
                            val z = term.zs[j]
                            if (hessian != null) {
                                for (k in z.index.indices) {
                                    val r = z.index[k]
                                    if (!marked[r]) {
                                        marked[r] = true
                                        touched[count++] = r
                                    }
                                    mean[r] += p * z.value[k]
                                }
                            }
                            addOuter(gradient, hessian, z, term.weight * p, term.weight * p)
                        }
                        val chosen = term.zs[term.chosen]
                        for (k in chosen.index.indices) gradient[chosen.index[k]] -= term.weight * chosen.value[k]
                        if (hessian != null) {
                            for (a in 0 until count) {
                                val row = hessian[touched[a]]
                                val mr = term.weight * mean[touched[a]]
                                for (b in 0 until count) row[touched[b]] -= mr * mean[touched[b]]
                            }
                            for (a in 0 until count) {
                                mean[touched[a]] = 0.0
                                marked[touched[a]] = false
                            }
                        }
                    }
                }
                val l = factor ?: cholesky(checkNotNull(hessian)).also { factor = it }
                val step = solve(l, gradient)
                var size = 0.0
                for (i in 0 until n) {
                    w[i] -= step[i]
                    size += step[i] * step[i]
                }
                size = sqrt(size)
                if (size < TOLERANCE) return w
                if (size > previous / 2) factor = null
                previous = size
            }
            return w
        }

        /** Adds [g] x to [gradient] and, with a [hessian], [h] x xᵀ to it. */
        private fun addOuter(gradient: DoubleArray, hessian: Array<DoubleArray>?, x: Sparse, g: Double, h: Double) {
            for (a in x.index.indices) {
                val r = x.index[a]
                val xr = x.value[a]
                gradient[r] += g * xr
                if (hessian == null) continue
                val row = hessian[r]
                for (b in x.index.indices) row[x.index[b]] += h * xr * x.value[b]
            }
        }

        /** The Cholesky factor L of a symmetric positive definite [a] (L Lᵀ = [a]). */
        private fun cholesky(a: Array<DoubleArray>): Array<DoubleArray> {
            val n = a.size
            val l = Array(n) { DoubleArray(n) }
            for (i in 0 until n) {
                for (j in 0..i) {
                    var sum = a[i][j]
                    for (k in 0 until j) sum -= l[i][k] * l[j][k]
                    l[i][j] = if (i == j) sqrt(sum) else sum / l[j][j]
                }
            }
            return l
        }

        /** x with L Lᵀ x = [b], [l] a [cholesky] factor. */
        private fun solve(l: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val n = b.size
            val y = DoubleArray(n)
            for (i in 0 until n) {
                var sum = b[i]
                for (k in 0 until i) sum -= l[i][k] * y[k]
                y[i] = sum / l[i][i]
            }
            val x = DoubleArray(n)
            for (i in n - 1 downTo 0) {
                var sum = y[i]
                for (k in i + 1 until n) sum -= l[k][i] * x[k]
                x[i] = sum / l[i][i]
            }
            return x
        }

        private fun dot(a: DoubleArray, b: DoubleArray): Double {
            var sum = 0.0
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }

        /**
         * Whether a device in [context] can show [stream] as it is made: a fixed rule, applied before any score as the
         * filters are, so a suggestion never prefers a stream that fails it. Dolby Vision with no other HDR layer needs a
         * screen that shows Dolby Vision; the video codec needs a hardware decoder, where the platform says which codecs
         * have one. HDR on a screen without it passes: the player tone-maps it to SDR, and the score weighs that
         * ([StreamFeature.HDR_NOT_SHOWN]).
         */
        fun showable(stream: PickedStream, context: PickContext): Boolean {
            val dolbyVisionOnly = stream.hdr.isNotEmpty() && stream.hdr.all { it == HdrBadge.DOLBY_VISION }
            val codec = stream.videoCodec
            return (!dolbyVisionOnly || context.dolbyVisionDisplay) &&
                (codec == null || context.hardwareCodecs.isEmpty() || codec in context.hardwareCodecs)
        }

        /**
         * Weights the profile's own settings imply before any pick: its sort keys (a resolution sort wants higher
         * resolutions, a source sort better sources, a size sort larger or smaller files, the cache sort cached ones),
         * its languages (audio and subtitles in them). [train] pulls towards them.
         */
        fun prior(sortKeys: List<StreamSortKey>, languages: LanguagePrefs): DoubleArray {
            val w = DoubleArray(FEATURES)
            operator fun DoubleArray.set(feature: StreamFeature, value: Double) {
                this[feature.ordinal] = value
            }
            for (key in sortKeys) when (key) {
                StreamSortKey.RESOLUTION -> {
                    w[StreamFeature.RES_4K] = 1.0
                    w[StreamFeature.RES_1440] = 0.7
                    w[StreamFeature.RES_1080] = 0.4
                    w[StreamFeature.RES_720] = -0.3
                    w[StreamFeature.RES_SD] = -1.0
                }
                StreamSortKey.SOURCE -> {
                    w[StreamFeature.REMUX] = 0.8
                    w[StreamFeature.BLURAY] = 0.5
                    w[StreamFeature.WEB_DL] = 0.3
                    w[StreamFeature.LOW_SOURCE] = -0.6
                }
                StreamSortKey.SIZE_LARGEST -> w[StreamFeature.BITRATE] = 0.4
                StreamSortKey.SIZE_SMALLEST -> w[StreamFeature.BITRATE] = -0.4
                StreamSortKey.CACHED -> w[StreamFeature.CACHED] = 0.8
            }
            if (languages.audio.isNotEmpty()) {
                w[StreamFeature.AUDIO_MATCH] = 1.0
                w[StreamFeature.AUDIO_OTHER] = -1.0
            }
            if (languages.subtitles.isNotEmpty()) w[StreamFeature.SUBTITLE_MATCH] = 0.5
            return w
        }

        /**
         * What [stream] offers on a device in [context], for a video [runtimeSeconds] long, to a profile wanting [languages];
         * the [options] say which of the experiments' features count ([ScorerOptions.interactions], [ScorerOptions.sizeCurve]).
         */
        fun features(
            stream: PickedStream,
            context: PickContext,
            runtimeSeconds: Long?,
            languages: LanguagePrefs,
            options: ScorerOptions = ScorerOptions(),
        ): DoubleArray {
            val x = DoubleArray(FEATURES)
            fun set(feature: StreamFeature, value: Double = 1.0) {
                x[feature.ordinal] = value
            }
            when (stream.resolution) {
                ResolutionBadge.UHD_2160 -> set(StreamFeature.RES_4K)
                ResolutionBadge.QHD_1440 -> set(StreamFeature.RES_1440)
                ResolutionBadge.FHD_1080 -> set(StreamFeature.RES_1080)
                ResolutionBadge.HD_720 -> set(StreamFeature.RES_720)
                ResolutionBadge.SD_480 -> set(StreamFeature.RES_SD)
                null -> {}
            }
            val dv = HdrBadge.DOLBY_VISION in stream.hdr
            val hdr = stream.hdr.any { it != HdrBadge.DOLBY_VISION }
            when {
                dv && context.dolbyVisionDisplay -> set(StreamFeature.DV_SHOWN)
                (dv || hdr) && context.hdrDisplay -> set(StreamFeature.HDR_SHOWN)
                dv || hdr -> set(StreamFeature.HDR_NOT_SHOWN)
            }
            when (stream.videoCodec) {
                VideoCodecBadge.HEVC -> set(StreamFeature.HEVC)
                VideoCodecBadge.AV1 -> set(StreamFeature.AV1)
                VideoCodecBadge.AVC -> set(StreamFeature.AVC)
                else -> {}
            }
            val codec = stream.videoCodec
            if (codec != null && context.hardwareCodecs.isNotEmpty() && codec !in context.hardwareCodecs) set(StreamFeature.NOT_HARDWARE)
            when (stream.source) {
                SourceBadge.REMUX -> set(StreamFeature.REMUX)
                SourceBadge.BLURAY -> set(StreamFeature.BLURAY)
                SourceBadge.WEB_DL -> set(StreamFeature.WEB_DL)
                SourceBadge.WEBRIP -> set(StreamFeature.WEBRIP)
                null, SourceBadge.DCP -> {}
                else -> set(StreamFeature.LOW_SOURCE)
            }
            val size = stream.sizeBytes
            if (size == null || size <= 0) {
                set(StreamFeature.SIZE_UNKNOWN)
            } else {
                val seconds = runtimeSeconds?.takeIf { it >= MIN_RUNTIME_SECONDS } ?: DEFAULT_RUNTIME_SECONDS
                val mbps = size * 8.0 / seconds / 1_000_000
                val bitrate = (ln(mbps / 8.0) / ln(2.0)).coerceIn(-4.0, 4.0)
                set(StreamFeature.BITRATE, bitrate)
                if (options.sizeCurve) set(StreamFeature.BITRATE_SQUARED, bitrate * bitrate / 4)
                if (context.metered) set(StreamFeature.BITRATE_METERED, bitrate)
            }
            if (context.metered && stream.resolution == ResolutionBadge.UHD_2160) set(StreamFeature.RES_4K_METERED)
            if (options.interactions && context.metered && stream.resolution == ResolutionBadge.FHD_1080) set(StreamFeature.RES_1080_METERED)
            if (options.interactions && context.metered && stream.resolution == ResolutionBadge.HD_720) set(StreamFeature.RES_720_METERED)
            if (languages.audio.isNotEmpty() && stream.audioLanguages.isNotEmpty()) {
                val match = stream.audioLanguages.any { lang -> languages.audio.any { LanguageTags.match(it, lang) > 0 } }
                if (match) set(StreamFeature.AUDIO_MATCH) else if (!stream.multiAudio) set(StreamFeature.AUDIO_OTHER)
            }
            if (stream.multiAudio) set(StreamFeature.MULTI_AUDIO)
            if (languages.subtitles.isNotEmpty()) {
                val match = stream.subtitleLanguages.any { lang -> languages.subtitles.any { LanguageTags.match(it, lang) > 0 } }
                if (match) set(StreamFeature.SUBTITLE_MATCH) else if (stream.multiSubtitles) set(StreamFeature.SUBTITLE_MATCH, 0.5)
            }
            when (stream.cached) {
                true -> set(StreamFeature.CACHED)
                false -> set(StreamFeature.CACHED, -1.0)
                null -> {}
            }
            if (stream.channels == ChannelsBadge.SURROUND_5_1 || stream.channels == ChannelsBadge.SURROUND_7_1) set(StreamFeature.SURROUND)
            if (stream.audio.any { it in LOSSLESS }) set(StreamFeature.LOSSLESS_AUDIO)
            if (AudioBadge.ATMOS in stream.audio) set(StreamFeature.ATMOS)
            return x
        }

        /** The audio formats [StreamFeature.LOSSLESS_AUDIO] counts. */
        internal val LOSSLESS = setOf(AudioBadge.TRUEHD, AudioBadge.DTS_HD_MA, AudioBadge.DTS_X, AudioBadge.FLAC)
    }
}
