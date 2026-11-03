package com.edde746.tortuga.suggest

import com.edde746.tortuga.data.settings.StreamSortKey
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.playback.streams.HdrBadge
import com.edde746.tortuga.playback.streams.StreamSort
import com.edde746.tortuga.playback.suggest.LanguagePrefs
import com.edde746.tortuga.playback.suggest.Pooling
import com.edde746.tortuga.playback.suggest.ScorerOptions
import com.edde746.tortuga.playback.suggest.StreamScorer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The stream suggestions measured offline: mocked users ([MockPicks]) pick from long stream lists (shared's
 * fixtures/streams: plain-text and styled-text lists with real lists' mix of qualities and languages) by a softmax of their true utility with a
 * temperature, times how likely each row is to be looked at (position bias); their tastes drift slowly; a poor pick is
 * often left and another made. The scorer trains on a user's first N picks and suggests a stream for fresh lists.
 *
 * Measured against each user's true utility, on every fresh list:
 * - regret: the true utility of the list's best stream minus the suggested one's, over the list's spread (best minus
 *   worst); 0 is the best stream;
 * - hit@1, hit@3: the suggested stream's kind is the best kind, or among the 3 best (kinds: resolution, DV/HDR, codec,
 *   source, audio languages, size within an octave; a kind is worth its best copy);
 * - pick@1: the suggested stream's kind is the kind the user picks (their noise included).
 * Against: the addons' order (also the default sort), the profile's sort keys, a quality sort (Resolution, Source,
 * Largest size); without a suggestion (under the thresholds) the list's own first row counts.
 *
 * Strict splits: tuning titles and users, and held-out titles (40 %, by the video id's hash) and users drawn apart (3
 * per archetype, 3 of mixed tastes, 2 noisy, from other seeds). Every number is a mean over [SEEDS] seeds with its 95 %
 * confidence interval; a change against [REFERENCE] is the paired difference over the same seeds, users and lists.
 *
 * Writes `build/reports/stream-suggestions/evaluation.json`; asserts what the product claims.
 */
class StreamSuggestionEvaluationTest {
    private val fixtures = File("../shared/src/commonTest/fixtures/streams")

    private val titles: List<Pair<MockTitle, Boolean>> by lazy {
        val index = Json.parseToJsonElement(File(fixtures, "index.json").readText()).jsonObject
        index.getValue("titles").jsonArray.map { it.jsonObject }.mapNotNull { t ->
            val type = t.getValue("type").jsonPrimitive.content
            val video = t.getValue("video").jsonPrimitive.content
            val streams = listOf("styled-texts", "plain-texts").flatMap { folder ->
                val file = File(fixtures, "$folder/$type-${video.replace(':', '-')}.json")
                if (!file.exists()) emptyList() else (AddonResponse.decode(file.readText()).content as ResourceContent.Streams).items.map(::MockStream)
            }
            val minutes = t["runtime"]?.jsonPrimitive?.int?.takeIf { it >= 10 } ?: if (type == "series") 45 else 110
            val anime = t.getValue("anime").jsonPrimitive.boolean
            if (streams.size < 5) null else MockTitle(video, minutes * 60L, streams, anime) to ((video.hashCode() and 0x7fffffff) % 10 < 6)
        }
    }
    private val tuneTitles by lazy { titles.filter { it.second }.map { it.first } }
    private val heldOutTitles by lazy { titles.filterNot { it.second }.map { it.first } }

    private val quality = StreamSort(listOf(StreamSortKey.RESOLUTION, StreamSortKey.SOURCE, StreamSortKey.SIZE_LARGEST))

    /**
     * A way of training the scorer and choosing a stream ([ScorerOptions.blend]: how much the list's order counts); with
     * [showable], only among the streams the user's device shows as made ([StreamScorer.showable]), as the app does.
     */
    private class Method(
        val name: String,
        val change: String,
        val options: ScorerOptions,
        val prior: Boolean = false,
        val showable: Boolean = false,
    )

    private data class Kind(val resolution: Any?, val range: Int, val codec: Any?, val source: Any?, val audio: Set<String>, val size: Int?)

    private fun kind(s: MockStream) = s.picked.let {
        Kind(
            it.resolution, if (HdrBadge.DOLBY_VISION in it.hdr) 2 else if (it.hdr.isNotEmpty()) 1 else 0, it.videoCodec, it.source,
            it.audioLanguages.toSet() + (if (it.multiAudio) setOf("multi") else emptySet()), it.sizeBytes?.let { b -> (ln(b.toDouble()) / ln(2.0)).roundToInt() },
        )
    }

    /** One fresh list a user is suggested a stream in. */
    private class Case(val group: String, val shown: List<MockStream>, val addonOrder: List<MockStream>, val utilities: List<Double>, val picked: MockStream, val pick: com.edde746.tortuga.data.streams.StreamPick)

    /** The four measures of one suggestion. */
    private fun measure(case: Case, suggested: MockStream): DoubleArray {
        val u = case.utilities
        val best = u.max()
        val spread = best - u.min()
        val i = case.shown.indexOf(suggested)
        val kinds = case.shown.indices.groupBy { kind(case.shown[it]) }.mapValues { (_, rows) -> rows.maxOf { u[it] } }.entries.sortedByDescending { it.value }.map { it.key }
        val k = kind(suggested)
        return doubleArrayOf(
            if (spread > 0) (best - u[i]) / spread else 0.0,
            if (kinds.first() == k) 1.0 else 0.0,
            if (k in kinds.take(3)) 1.0 else 0.0,
            if (kind(case.picked) == k) 1.0 else 0.0,
        )
    }

    /** A user's history and fresh lists, the same for every method. */
    private class Subject(val user: MockUser, val history: List<MockPick>, val cases: List<Case>, val languages: LanguagePrefs)

    private fun subjects(seed: Int, heldOut: Boolean): List<Subject> {
        val pool = if (heldOut) heldOutTitles else tuneTitles
        val plan = if (heldOut) {
            listOf("phone", "tv", "anime", "desktop").flatMap { a -> List(3) { a } } + List(3) { "mixed" } + List(2) { "noisy" }
        } else {
            listOf("phone", "tv", "anime", "desktop").flatMap { a -> List(2) { a } } + List(2) { "mixed" } + listOf("noisy")
        }
        return plan.mapIndexed { n, archetype ->
            val random = Random(seed * 7919 + n + if (heldOut) 1_000_003 else 0)
            val user = MockPicks.user("$archetype-$n", archetype, random)
            val (history, now) = MockPicks.history(user, pool, SIZES.max(), random)
            val cases = cases(now, pool, random, archetype)
            Subject(now, history, cases, LanguagePrefs.of(user.audioLanguages, user.subtitleLanguages))
        }
    }

    /** Per seed, per group ("all", "steady" without the noisy, and each archetype): the mean of each measure. */
    private class Tally {
        val sums = mutableMapOf<String, DoubleArray>()
        val counts = mutableMapOf<String, Int>()
        fun add(group: String, m: DoubleArray) {
            for (g in listOfNotNull("all", group, if (group != "noisy") "steady" else null)) {
                val s = sums.getOrPut(g) { DoubleArray(4) }
                for (i in m.indices) s[i] += m[i]
                counts[g] = (counts[g] ?: 0) + 1
            }
        }
        fun mean(group: String) = sums.getValue(group).map { it / counts.getValue(group) }
    }

    /** [TEST] fresh lists [user] is suggested a stream in, from [pool], each one's [Case.group] [group]. */
    private fun cases(user: MockUser, pool: List<MockTitle>, random: Random, group: String): List<Case> = List(TEST) { c ->
        val title = MockPicks.sample(MockPicks.title(user, pool, random), random, "test-$c")
        val context = MockPicks.context(user, random)
        val shown = MockPicks.shown(user, title)
        val index = MockPicks.choose(user, shown, context, title.runtimeSeconds, random)
        val pick = MockPicks.record(user, title, shown, index, context, null, "test-$c")
        Case(group, shown, title.streams, shown.map { user.taste.utility(it.picked, context, title.runtimeSeconds) }, shown[index], pick)
    }

    /** The scorer of [method] for [subject] after its first [n] picks: the suggested row of each case. */
    private fun suggest(method: Method, subject: Subject, n: Int, gated: Boolean): (Case) -> MockStream {
        val options = if (gated) method.options else method.options.copy(minPicks = 0, minConsistency = 0.0)
        val prior = if (method.prior) StreamScorer.prior(subject.user.sortKeys, subject.languages) else null
        val picks = subject.history.take(n).map { it.pick }
        val scorer = if (n == 0 && prior == null) null else StreamScorer.train(picks, subject.languages, subject.user.deviceClass, options, prior)
        return chooser(scorer, subject.languages, method.options, method.showable)
    }

    /**
     * The suggested row of each case by [scorer] (with [showable], only among the streams the case's device can play
     * as made, as the app does); the list's first row without a suggestion.
     */
    private fun chooser(scorer: StreamScorer?, languages: LanguagePrefs, options: ScorerOptions, showable: Boolean = true): (Case) -> MockStream = { case ->
        val candidates = if (showable) case.shown.filter { StreamScorer.showable(it.picked, case.pick.context) } else case.shown
        val xs = candidates.map { StreamScorer.features(it.picked, case.pick.context, case.pick.runtimeSeconds, languages, options) }
        scorer?.takeIf { candidates.size >= 2 }?.best(xs)?.let { candidates[it.index] } ?: case.shown.first()
    }

    private fun stats(values: List<Double>): Pair<Double, Double> {
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        return mean to T_975 * sd / sqrt(values.size.toDouble())
    }

    private fun json(values: List<Double>) = stats(values).let { (m, ci) -> "[${"%.4f".format(m)},${"%.4f".format(ci)}]" }

    @Test
    fun theScorerAgainstTheSortsOnHeldOutUsersAndTitles() {
        val report = StringBuilder("{")
        report.append("\"titles\":{\"tune\":${tuneTitles.size},\"heldOut\":${heldOutTitles.size},\"streams\":${titles.sumOf { it.first.streams.size }}},")
        report.append("\"seeds\":${SEEDS.count()},\"test\":$TEST,")
        val results = mutableMapOf<String, Map<String, List<Double>>>()
        for (split in listOf("tune", "heldOut")) {
            // [method][size][group][measure] → per-seed means; baselines under the size "base".
            val perSeed = mutableMapOf<String, MutableList<Map<String, List<Double>>>>()
            val timeNanos = mutableMapOf<String, Long>()
            var trains = 0
            for (seed in SEEDS) {
                val subjects = subjects(seed, split == "heldOut")
                val baselines = mapOf(
                    "addonOrder" to { c: Case -> c.addonOrder.first { it in c.shown } },
                    "profileSort" to { c: Case -> c.shown.first() },
                    "qualitySort" to { c: Case -> quality.sorted(c.shown, { it.stream }, { it.badges }).first() },
                )
                for ((name, choose) in baselines) {
                    val tally = Tally()
                    for (s in subjects) for (c in s.cases) tally.add(c.group, measure(c, choose(c)))
                    perSeed.getOrPut("$name@base") { mutableListOf() } += tally.sums.keys.associateWith { tally.mean(it) }
                }
                for (method in METHODS) for (n in SIZES) for (gated in listOf(false, true)) {
                    if (gated && n !in GATED_SIZES) continue
                    val tally = Tally()
                    for (s in subjects) {
                        val start = System.nanoTime()
                        val choose = suggest(method, s, n, gated)
                        if (!gated && n == SIZES.max()) {
                            timeNanos[method.name] = (timeNanos[method.name] ?: 0L) + System.nanoTime() - start
                            if (method == METHODS.first()) trains++
                        }
                        for (c in s.cases) tally.add(c.group, measure(c, choose(c)))
                    }
                    perSeed.getOrPut("${method.name}@$n${if (gated) "g" else ""}") { mutableListOf() } += tally.sums.keys.associateWith { tally.mean(it) }
                }
            }
            report.append("\"$split\":{")
            report.append(
                perSeed.entries.joinToString(",") { (key, seeds) ->
                    "\"$key\":{" + seeds.first().keys.joinToString(",") { group ->
                        "\"$group\":[" + (0 until 4).joinToString(",") { m -> json(seeds.map { it.getValue(group)[m] }) } + "]"
                    } + "}"
                },
            )
            // Paired differences of regret against the reference, for every method and size.
            report.append(",\"pairedRegret\":{")
            report.append(
                METHODS.filter { it.name != REFERENCE }.flatMap { method -> SIZES.map { n -> method to n } }.joinToString(",") { (method, n) ->
                    val a = perSeed.getValue("${method.name}@$n")
                    val b = perSeed.getValue("$REFERENCE@$n")
                    "\"${method.name}@$n\":{" + listOf("all", "steady").joinToString(",") { g -> "\"$g\":" + json(a.indices.map { a[it].getValue(g)[0] - b[it].getValue(g)[0] }) } + "}"
                },
            )
            // The primary measure: regret over the curve from 20 picks on (20, 40, 80, 160), per seed; and paired against the
            // reference.
            fun curve(method: String, group: String) = SEEDS.mapIndexed { i, _ -> CURVE.map { n -> perSeed.getValue("$method@$n")[i].getValue(group)[0] }.average() }
            report.append("},\"curveRegret\":{" + METHODS.joinToString(",") { m ->
                "\"${m.name}\":{" + listOf("steady", "all").joinToString(",") { g ->
                    val ref = curve(REFERENCE, g)
                    val own = curve(m.name, g)
                    "\"$g\":${json(own)},\"${g}Paired\":${json(own.indices.map { own[it] - ref[it] })},\"${g}Seeds\":[${own.joinToString(",") { "%.5f".format(it) }}]"
                } + "}"
            } + "}")
            // The product's thresholds' measure: the gated regret of every user (the noisy too) over 5 to 160 picks.
            fun gated(method: String, group: String) =
                SEEDS.mapIndexed { i, _ -> GATED_SIZES.filter { it > 0 }.map { n -> perSeed.getValue("$method@${n}g")[i].getValue(group)[0] }.average() }
            report.append(",\"gatedCurve\":{" + METHODS.joinToString(",") { m ->
                "\"${m.name}\":{" + listOf("all", "steady", "noisy").joinToString(",") { g ->
                    val own = gated(m.name, g)
                    "\"$g\":${json(own)},\"${g}Seeds\":[${own.joinToString(",") { "%.5f".format(it) }}]"
                } + "}"
            } + "}")
            report.append(",\"trainMillisAt${SIZES.max()}\":{" + timeNanos.entries.joinToString(",") { "\"${it.key}\":${"%.1f".format(it.value / 1e6 / trains.coerceAtLeast(1))}" } + "}")
            report.append("},")
            results.putAll(perSeed.mapValues { (_, seeds) -> mapOf("regret" to seeds.map { it.getValue("steady")[0] }) }.mapKeys { "$split:${it.key}" })
        }
        report.append("\"methods\":{" + METHODS.joinToString(",") { "\"${it.name}\":\"${it.change}\"" } + "},\"reference\":\"$REFERENCE\"}")
        File("build/reports/stream-suggestions").apply { mkdirs() }.resolve("evaluation.json").writeText(report.toString())

        // The product (gated) is never worse than the profile's sort on held-out users with a steady taste, from 20 picks.
        val product = METHODS.last().name
        for (n in GATED_SIZES.filter { it >= 20 }) {
            val ours = stats(results.getValue("heldOut:$product@${n}g").getValue("regret")).first
            val sort = stats(results.getValue("heldOut:profileSort@base").getValue("regret")).first
            assertTrue(ours <= sort, "$product at $n picks: regret $ours against the profile's sort's $sort")
        }
    }

    /**
     * Picks pooled across a profile's devices ([ScorerOptions.pooling]), as the product suggests (from 5 picks, the
     * fixed rule by the device's own screen and decoders). Mocked people with two devices of different kinds
     * ([MockPicks.person]: their languages, sources and cached links travel; the rest of their taste on the second
     * device partly their own), on held-out titles and people, against tuning ones. Three cases:
     * - both: [PERSON_PICKS] picks on each device, suggestions on each;
     * - new device: the same picks, suggestions on another device of the second's kind ([MockPicks.anotherDevice]);
     * - new kind: [PERSON_PICKS] picks on one device only, suggestions on the other (both ways).
     * Today, with the picks staying on the device they were made on, a new device suggests nothing: the list's order
     * (`profileSort`). Writes `build/reports/stream-suggestions/pooling.json`: each case's regret and hit@1 per method,
     * mean and CI over [SEEDS], and the per-seed regrets for paired differences.
     */
    @Test
    fun picksPooledAcrossAProfilesDevices() {
        val report = StringBuilder("{\"methods\":{" + POOLING.joinToString(",") { (name, options) -> "\"$name\":\"${options.pooling} ${options.offsetRidge}\"" } + "},")
        for (split in listOf("tune", "heldOut")) {
            val heldOut = split == "heldOut"
            val pool = if (heldOut) heldOutTitles else tuneTitles
            // [case][method] → per seed, the regret and hit@1 sums and the count.
            val perSeed = mutableMapOf<String, MutableMap<String, MutableList<DoubleArray>>>()
            for (seed in SEEDS) {
                val tally = mutableMapOf<String, MutableMap<String, DoubleArray>>()
                fun add(case: String, method: String, cases: List<Case>, choose: (Case) -> MockStream) {
                    val t = tally.getOrPut(case) { mutableMapOf() }.getOrPut(method) { DoubleArray(3) }
                    for (c in cases) {
                        val m = measure(c, choose(c))
                        t[0] += m[0]
                        t[1] += m[1]
                        t[2] += 1.0
                    }
                }
                val people = if (heldOut) HELD_OUT_PEOPLE else TUNE_PEOPLE
                people.forEachIndexed { n, (first, second) ->
                    val random = Random(seed * 7919 + n + if (heldOut) 2_000_003 else 3_000_017)
                    val (a, b) = MockPicks.person("person-$n", first, second, random)
                    val (historyA, nowA) = MockPicks.history(a, pool, PERSON_PICKS, random)
                    val (historyB, nowB) = MockPicks.history(b, pool, PERSON_PICKS, random)
                    val picksA = historyA.map { it.pick }
                    val picksB = historyB.map { it.pick }
                    val both = interleave(picksA, picksB, random)
                    val newB = MockPicks.anotherDevice(nowB)
                    val languages = LanguagePrefs.of(a.audioLanguages, a.subtitleLanguages)
                    val prior = StreamScorer.prior(a.sortKeys, languages)
                    val casesA = cases(nowA, pool, random, first)
                    val casesB = cases(nowB, pool, random, second)
                    val casesNewB = cases(newB, pool, random, second)
                    for (case in listOf("both", "newDevice", "newKind")) {
                        add(case, "profileSort", if (case == "newDevice") casesNewB else casesA + casesB) { it.shown.first() }
                    }
                    for ((name, options) in POOLING) {
                        fun on(picks: List<com.edde746.tortuga.data.streams.StreamPick>, user: MockUser) =
                            chooser(StreamScorer.train(picks, languages, user.deviceClass, options, prior), languages, options)
                        add("both", name, casesA, on(both, nowA))
                        add("both", name, casesB, on(both, nowB))
                        add("newDevice", name, casesNewB, on(both, newB))
                        add("newKind", name, casesB, on(picksA, nowB))
                        add("newKind", name, casesA, on(picksB, nowA))
                    }
                }
                for ((case, methods) in tally) for ((method, t) in methods) {
                    perSeed.getOrPut(case) { mutableMapOf() }.getOrPut(method) { mutableListOf() } += doubleArrayOf(t[0] / t[2], t[1] / t[2])
                }
            }
            report.append("\"$split\":{" + perSeed.entries.joinToString(",") { (case, methods) ->
                "\"$case\":{" + methods.entries.joinToString(",") { (method, seeds) ->
                    "\"$method\":{\"regret\":${json(seeds.map { it[0] })},\"hit1\":${json(seeds.map { it[1] })}," +
                        "\"seeds\":[${seeds.joinToString(",") { "%.5f".format(it[0]) }}]}"
                } + "}"
            } + "},")
        }
        report.setLength(report.length - 1)
        File("build/reports/stream-suggestions").apply { mkdirs() }.resolve("pooling.json").writeText(report.append("}").toString())
    }

    /** [a] and [b] merged at random, each in its order: picks made on two devices over the same time. */
    private fun <T> interleave(a: List<T>, b: List<T>, random: Random): List<T> = buildList {
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            if (j >= b.size || (i < a.size && random.nextBoolean())) add(a[i++]) else add(b[j++])
        }
    }

    companion object {
        private val SEEDS = 1..10

        /** t(0.975) for 9 degrees of freedom. */
        private const val T_975 = 2.262
        private const val TEST = 30
        private val SIZES = listOf(0, 5, 10, 20, 40, 80, 160)
        private val GATED_SIZES = listOf(0, 5, 10, 20, 40, 160)

        /** The sizes the primary measure averages over. */
        private val CURVE = listOf(20, 40, 80, 160)

        private val PAIRWISE = ScorerOptions(
            listwise = false, interactions = false, sizeCurve = false, blend = 0.0, minPicks = 12, minConsistency = 0.78,
            pooling = Pooling.CLASS_ONLY,
        )
        private val LISTWISE = PAIRWISE.copy(listwise = true)
        private val E13 = LISTWISE.copy(interactions = true)
        private val E17 = E13.copy(blend = 0.5)
        private val E27 = E17.copy(sizeCurve = true)
        private val E28 = ScorerOptions(pooling = Pooling.CLASS_ONLY)

        /**
         * The kept changes one by one, from the first pilot to the product's [ScorerOptions] (the last); [REFERENCE] the
         * one each is paired against. The experiments log has every change tried, the rejected ones too. On these users,
         * each on one device, pooling ([Pooling]) may only not hurt.
         */
        private val METHODS = listOf(
            Method("E0-pairwise", "the first pilot: pairwise logistic, 25 above / 10 below, ridge 2, half-life 120", PAIRWISE),
            Method("E2-listwise", "a conditional logit over each pick's shown streams", LISTWISE),
            Method("E5-prior", "E2 pulled towards a prior from the profile's settings", LISTWISE, prior = true),
            Method("E13-interactions", "E5 with 1080p and 720p on a metered network weighed apart", E13, prior = true),
            Method("E17-blend", "E13 blended with the list's order (β 0.5, fading with picks)", E17, prior = true),
            Method("E27-size-curve", "E17 with the bitrate's square", E27, prior = true),
            Method("E28-size-curve-from-5", "E27 suggesting from 5 picks, no consistency gate", E28, prior = true),
            Method(
                "E39-showable", "E28 choosing only among streams the device can play as made (no Dolby Vision-only stream without a " +
                    "Dolby Vision screen, no codec without a hardware decoder)", E28, prior = true, showable = true,
            ),
            Method("E40-split", "E39 pooling picks across devices: what travels shared, what depends on the device per kind", E28.copy(pooling = Pooling.SPLIT), prior = true, showable = true),
            Method("E41-offsets", "E39 pooling picks across devices: shared weights, each kind's offsets pulled to none (ridge 5)", ScorerOptions(), prior = true, showable = true),
        )
        private const val REFERENCE = "E0-pairwise"

        /** Picks per device of each mocked person ([picksPooledAcrossAProfilesDevices]). */
        private const val PERSON_PICKS = 80

        /** The two devices of each mocked person, by archetype ([MockPicks.person]): held out, and tuning. */
        private val HELD_OUT_PEOPLE = List(3) { "phone" to "tv" } + List(2) { "desktop" to "tv" } + List(2) { "anime" to "tv" } + listOf("phone" to "desktop")
        private val TUNE_PEOPLE = listOf("phone" to "tv", "desktop" to "tv", "anime" to "tv", "phone" to "desktop")

        /** The ways of pooling compared ([picksPooledAcrossAProfilesDevices]), each with the product's other options. */
        private val POOLING = listOf(
            "classOnly" to ScorerOptions(pooling = Pooling.CLASS_ONLY),
            "split" to ScorerOptions(pooling = Pooling.SPLIT),
            "offsets1" to ScorerOptions(pooling = Pooling.OFFSETS, offsetRidge = 1.0),
            "offsets2" to ScorerOptions(pooling = Pooling.OFFSETS, offsetRidge = 2.0),
            "offsets5" to ScorerOptions(pooling = Pooling.OFFSETS, offsetRidge = 5.0),
            "offsets10" to ScorerOptions(pooling = Pooling.OFFSETS, offsetRidge = 10.0),
            "offsets20" to ScorerOptions(pooling = Pooling.OFFSETS, offsetRidge = 20.0),
        )
    }
}
