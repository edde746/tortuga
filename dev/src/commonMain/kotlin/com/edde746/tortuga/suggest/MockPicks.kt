package com.edde746.tortuga.suggest

import com.edde746.tortuga.data.settings.StreamSortKey
import com.edde746.tortuga.data.streams.DeviceClass
import com.edde746.tortuga.data.streams.PickContext
import com.edde746.tortuga.data.streams.PickOutcome
import com.edde746.tortuga.data.streams.PickSource
import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamPick
import com.edde746.tortuga.model.Stream
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.playback.streams.AudioBadge
import com.edde746.tortuga.playback.streams.ChannelsBadge
import com.edde746.tortuga.playback.streams.HdrBadge
import com.edde746.tortuga.playback.streams.ResolutionBadge
import com.edde746.tortuga.playback.streams.SourceBadge
import com.edde746.tortuga.playback.streams.StreamBadgeParser
import com.edde746.tortuga.playback.streams.StreamBadges
import com.edde746.tortuga.playback.streams.StreamFilter
import com.edde746.tortuga.playback.streams.StreamSort
import com.edde746.tortuga.playback.streams.VideoCodecBadge
import com.edde746.tortuga.playback.suggest.StreamPicks
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** A stream of a mocked list: the addon's stream, what its text says, and the pick's record of it. */
class MockStream(val stream: Stream, val badges: StreamBadges = StreamBadgeParser.parse(stream)) {
    val picked: PickedStream = PickedStream.of(badges, StreamFilter.sizeOf(stream, badges))
}

/** A title's stream list in the addons' order, [runtimeSeconds] long; [anime] when it is an anime. */
class MockTitle(val id: String, val runtimeSeconds: Long, val streams: List<MockStream>, val anime: Boolean)

/**
 * A mocked user's true taste: what a stream is worth to them where they pick it ([utility]). Sizes count as their
 * distance from a target bitrate ([sizeTarget] GB per hour, a third of it on a metered network), in octaves, times
 * [sizeWeight] (half again on a metered network); the rest are weights of what the stream's text says.
 */
data class Taste(
    val resolution: Map<ResolutionBadge, Double>,
    val dolbyVision: Double,
    val hdr: Double,
    val hdrNotShown: Double,
    val codec: Map<VideoCodecBadge, Double>,
    val notHardware: Double,
    val source: Map<SourceBadge, Double>,
    val sizeWeight: Double,
    val sizeTarget: Double,
    /** The audio language the user wants (`en`, `ja`), and the weights of having it, and of naming only others. */
    val audioLanguage: String,
    val audioMatch: Double,
    val audioOther: Double,
    val multiAudio: Double,
    val subtitleLanguage: String?,
    val subtitles: Double,
    val atmos: Double,
    val lossless: Double,
    val surround: Double,
    val cached: Double,
) {
    fun utility(s: PickedStream, context: PickContext, runtimeSeconds: Long): Double {
        var u = s.resolution?.let { resolution[it] } ?: -0.5
        val dv = HdrBadge.DOLBY_VISION in s.hdr
        when {
            dv && context.dolbyVisionDisplay -> u += dolbyVision
            s.hdr.isNotEmpty() && context.hdrDisplay -> u += hdr
            s.hdr.isNotEmpty() -> u += hdrNotShown
        }
        s.videoCodec?.let { codec ->
            u += this.codec[codec] ?: 0.0
            if (context.hardwareCodecs.isNotEmpty() && codec !in context.hardwareCodecs) u += notHardware
        }
        s.source?.let { u += source[it] ?: 0.0 }
        val hours = runtimeSeconds / 3600.0
        val gigabytesPerHour = (s.sizeBytes ?: (4L shl 30)).toDouble() / (1L shl 30) / hours
        val target = if (context.metered) sizeTarget / 3 else sizeTarget
        u -= sizeWeight * (if (context.metered) 1.5 else 1.0) * abs(log2(gigabytesPerHour / target))
        val audio = s.audioLanguages.any { it == audioLanguage || it.startsWith("$audioLanguage-") } ||
            (audioLanguage == "en" && s.audioLanguages.isEmpty())
        u += if (audio) audioMatch else if (s.multiAudio) 0.0 else audioOther
        if (s.multiAudio) u += multiAudio
        subtitleLanguage?.let { wanted ->
            if (s.subtitleLanguages.any { it == wanted }) u += subtitles else if (s.multiSubtitles) u += subtitles / 2
        }
        if (AudioBadge.ATMOS in s.audio) u += atmos
        if (s.audio.any { it == AudioBadge.TRUEHD || it == AudioBadge.DTS_HD_MA || it == AudioBadge.DTS_X || it == AudioBadge.FLAC }) u += lossless
        if (s.channels == ChannelsBadge.SURROUND_5_1 || s.channels == ChannelsBadge.SURROUND_7_1) u += surround
        u += when (s.cached) {
            true -> cached
            false -> -cached
            null -> 0.0
        }
        return u
    }

    /** This taste drawn around itself: each weight scaled by e^N(0, [spread]) and moved by N(0, [shift]). */
    fun drawn(random: Random, spread: Double = 0.35, shift: Double = 0.25): Taste {
        fun d(x: Double) = x * exp(gaussian(random) * spread) + gaussian(random) * shift
        fun <K> d(m: Map<K, Double>) = m.mapValues { d(it.value) }
        return copy(
            resolution = d(resolution), dolbyVision = d(dolbyVision), hdr = d(hdr), hdrNotShown = d(hdrNotShown), codec = d(codec),
            notHardware = d(notHardware), source = d(source), sizeWeight = abs(d(sizeWeight)), sizeTarget = sizeTarget * exp(gaussian(random) * 0.4),
            audioMatch = d(audioMatch), audioOther = d(audioOther), multiAudio = d(multiAudio), subtitles = d(subtitles), atmos = d(atmos),
            lossless = d(lossless), surround = d(surround), cached = d(cached),
        )
    }

    /** [share] of this taste and the rest of [other]'s, each weight; the languages this one's. */
    fun mixed(other: Taste, share: Double): Taste {
        fun m(a: Double, b: Double) = share * a + (1 - share) * b
        fun <K> m(a: Map<K, Double>, b: Map<K, Double>) = (a.keys + b.keys).associateWith { m(a[it] ?: 0.0, b[it] ?: 0.0) }
        return copy(
            resolution = m(resolution, other.resolution), dolbyVision = m(dolbyVision, other.dolbyVision), hdr = m(hdr, other.hdr),
            hdrNotShown = m(hdrNotShown, other.hdrNotShown), codec = m(codec, other.codec), notHardware = m(notHardware, other.notHardware),
            source = m(source, other.source), sizeWeight = m(sizeWeight, other.sizeWeight), sizeTarget = exp(m(ln(sizeTarget), ln(other.sizeTarget))),
            multiAudio = m(multiAudio, other.multiAudio), atmos = m(atmos, other.atmos), lossless = m(lossless, other.lossless),
            surround = m(surround, other.surround), cached = m(cached, other.cached),
        )
    }

    /** A step of a slow drift: the resolution and size weights move by N(0, [step]). */
    fun drifted(random: Random, step: Double): Taste =
        copy(resolution = resolution.mapValues { it.value + gaussian(random) * step }, sizeWeight = abs(sizeWeight + gaussian(random) * step))
}

/**
 * A mocked user: a [taste], the profile's settings ([audioLanguages], [subtitleLanguages], [sortKeys]), the device, and
 * how they pick. They see the list in their sort's order and pick by a softmax of [Taste.utility] over [temperature],
 * times how likely each row is to be looked at ([propensity]: [floor] for rows far down, rising to 1 at the top over
 * [reach] rows); [randomShare] of the picks are any of the first ten rows. Their taste drifts by [drift] a pick.
 */
data class MockUser(
    val name: String,
    val archetype: String,
    val taste: Taste,
    val deviceClass: DeviceClass,
    val audioLanguages: List<String>,
    val subtitleLanguages: List<String>,
    val sortKeys: List<StreamSortKey>,
    val meteredShare: Double,
    val hdrDisplay: Boolean,
    val dolbyVisionDisplay: Boolean,
    val hardwareCodecs: Set<VideoCodecBadge>,
    val animeShare: Double,
    val temperature: Double,
    val reach: Double,
    val floor: Double,
    val randomShare: Double,
    val drift: Double,
) {
    fun propensity(row: Int): Double = floor + (1 - floor) * exp(-row / reach)
}

/** One mocked pick: the [pick] as [StreamPicks] records it, the list it was made in as shown, and the chosen stream. */
class MockPick(val pick: StreamPick, val title: MockTitle, val shown: List<MockStream>, val chosen: MockStream)

/** Mocked users and the pick histories they make; test code only. */
object MockPicks {
    private val NONE = Taste(
        resolution = emptyMap(), dolbyVision = 0.0, hdr = 0.0, hdrNotShown = 0.0, codec = emptyMap(), notHardware = 0.0,
        source = emptyMap(), sizeWeight = 0.0, sizeTarget = 4.0, audioLanguage = "en", audioMatch = 0.0, audioOther = 0.0,
        multiAudio = 0.0, subtitleLanguage = null, subtitles = 0.0, atmos = 0.0, lossless = 0.0, surround = 0.0, cached = 0.0,
    )

    /** A phone, often on cellular: 1080p HEVC in English, about 2 GB an hour (a third of it on cellular). */
    val PHONE_TASTE = NONE.copy(
        resolution = mapOf(ResolutionBadge.UHD_2160 to -1.0, ResolutionBadge.FHD_1080 to 1.5, ResolutionBadge.HD_720 to 0.6, ResolutionBadge.SD_480 to -1.5),
        dolbyVision = 0.3, hdr = 0.3, hdrNotShown = -0.3, codec = mapOf(VideoCodecBadge.HEVC to 1.0, VideoCodecBadge.AV1 to 0.3), notHardware = -1.5,
        source = mapOf(SourceBadge.WEB_DL to 0.4, SourceBadge.BLURAY to 0.3, SourceBadge.REMUX to -0.5, SourceBadge.HDTV to -1.0, SourceBadge.DVDRIP to -1.0),
        sizeWeight = 1.0, sizeTarget = 2.0, audioMatch = 1.5, audioOther = -2.5, lossless = -0.2, cached = 0.5,
    )

    /** A TV, often with a Dolby Vision screen: 4K remux in Dolby Vision with Atmos, size no object. */
    val TV_TASTE = NONE.copy(
        resolution = mapOf(ResolutionBadge.UHD_2160 to 2.0, ResolutionBadge.FHD_1080 to 0.0, ResolutionBadge.HD_720 to -1.5, ResolutionBadge.SD_480 to -3.0),
        dolbyVision = 2.0, hdr = 0.8, hdrNotShown = -1.0, codec = mapOf(VideoCodecBadge.HEVC to 0.3),
        source = mapOf(SourceBadge.REMUX to 2.0, SourceBadge.BLURAY to 0.8, SourceBadge.HDTV to -2.0, SourceBadge.DVDRIP to -2.0),
        sizeWeight = 0.2, sizeTarget = 25.0, audioMatch = 1.0, audioOther = -2.0, subtitleLanguage = "en", subtitles = 0.3,
        atmos = 1.5, lossless = 0.7, surround = 0.5, cached = 1.0,
    )

    /** An anime fan: Japanese audio with English subtitles, 1080p, about 1.5 GB an hour. */
    val ANIME_TASTE = NONE.copy(
        resolution = mapOf(ResolutionBadge.UHD_2160 to -0.3, ResolutionBadge.FHD_1080 to 1.0, ResolutionBadge.HD_720 to -0.2, ResolutionBadge.SD_480 to -1.5),
        codec = mapOf(VideoCodecBadge.HEVC to 0.4, VideoCodecBadge.AV1 to 0.4), source = mapOf(SourceBadge.BLURAY to 0.4, SourceBadge.WEB_DL to 0.2),
        sizeWeight = 0.5, sizeTarget = 1.5, audioLanguage = "ja", audioMatch = 3.0, audioOther = -1.5, multiAudio = 0.8,
        subtitleLanguage = "en", subtitles = 1.5, cached = 0.5,
    )

    /** A desktop: a balance of quality and size, about 8 GB an hour, in English. */
    val DESKTOP_TASTE = NONE.copy(
        resolution = mapOf(ResolutionBadge.UHD_2160 to 0.8, ResolutionBadge.FHD_1080 to 0.6, ResolutionBadge.HD_720 to -0.5, ResolutionBadge.SD_480 to -2.0),
        codec = mapOf(VideoCodecBadge.HEVC to 0.3), source = mapOf(SourceBadge.WEB_DL to 0.5, SourceBadge.BLURAY to 0.5, SourceBadge.HDTV to -1.0),
        sizeWeight = 0.6, sizeTarget = 8.0, audioMatch = 1.2, audioOther = -2.0, surround = 0.3, cached = 0.6,
    )

    /** Someone with no steady taste: a loose liking for 1080p. */
    val NOISY_TASTE = NONE.copy(resolution = mapOf(ResolutionBadge.FHD_1080 to 0.5))

    /** The archetypes' users as they are, undrawn: what [seed] and the tests start from. */
    val PHONE = user("phone", "phone", PHONE_TASTE, Random(0), drawn = false)
    val TV = user("tv", "tv", TV_TASTE, Random(0), drawn = false)
    val ANIME = user("anime", "anime", ANIME_TASTE, Random(0), drawn = false)
    val DESKTOP = user("desktop", "desktop", DESKTOP_TASTE, Random(0), drawn = false)
    val NOISY = user("noisy", "noisy", NOISY_TASTE, Random(0), drawn = false)

    val USERS: List<MockUser> = listOf(PHONE, TV, ANIME, DESKTOP, NOISY)

    private val TASTES = mapOf("phone" to PHONE_TASTE, "tv" to TV_TASTE, "anime" to ANIME_TASTE, "desktop" to DESKTOP_TASTE, "noisy" to NOISY_TASTE)

    /**
     * A user of [archetype] (`phone`, `tv`, `anime`, `desktop`, `noisy`, or `mixed`: two of the first four's tastes
     * mixed, the first one's device and languages), its taste and how it picks drawn with [random]; [drawn] false keeps
     * the archetype's own.
     */
    fun user(name: String, archetype: String, random: Random, drawn: Boolean = true): MockUser {
        if (archetype != "mixed") return user(name, archetype, TASTES.getValue(archetype), random, drawn)
        val (a, b) = listOf("phone", "tv", "anime", "desktop").shuffled(random).take(2)
        val base = user(name, a, TASTES.getValue(a), random, drawn)
        return base.copy(archetype = "mixed", taste = base.taste.mixed(TASTES.getValue(b).drawn(random), 0.3 + 0.4 * random.nextDouble()))
    }

    private fun user(name: String, archetype: String, taste: Taste, random: Random, drawn: Boolean): MockUser {
        fun chance(p: Double) = if (drawn) random.nextDouble() < p else p >= 0.5
        fun between(low: Double, high: Double) = if (drawn) low + (high - low) * random.nextDouble() else (low + high) / 2
        val languages = chance(0.85)
        val audio = if (!languages) emptyList() else if (taste.audioLanguage == "ja") listOf("jpn") else listOf("eng")
        val subtitles = if (languages && taste.subtitleLanguage != null) listOf("eng") else emptyList()
        val sortKeys = when (archetype) {
            "phone" -> if (chance(0.3)) listOf(StreamSortKey.SIZE_SMALLEST) else emptyList()
            "tv" -> if (chance(0.6)) listOf(StreamSortKey.RESOLUTION, StreamSortKey.SOURCE) else if (chance(0.5)) listOf(StreamSortKey.RESOLUTION) else emptyList()
            "desktop" -> if (chance(0.3)) listOf(StreamSortKey.RESOLUTION) else emptyList()
            "noisy" -> if (chance(0.5)) listOf(StreamSortKey.SIZE_LARGEST) else emptyList()
            else -> emptyList()
        }
        val noisy = archetype == "noisy"
        return MockUser(
            name = name,
            archetype = archetype,
            taste = if (drawn) taste.drawn(random) else taste,
            deviceClass = when (archetype) {
                "phone", "noisy" -> DeviceClass.HANDHELD
                "tv" -> DeviceClass.TV
                else -> DeviceClass.DESKTOP
            },
            audioLanguages = audio,
            subtitleLanguages = subtitles,
            sortKeys = sortKeys,
            meteredShare = when (archetype) {
                "phone" -> between(0.3, 0.7)
                "noisy" -> 0.3
                else -> 0.0
            },
            hdrDisplay = archetype == "phone" || archetype == "tv" || (archetype == "desktop" && chance(0.4)),
            dolbyVisionDisplay = archetype == "tv" && chance(0.6),
            hardwareCodecs = when (archetype) {
                "phone", "noisy" -> setOf(VideoCodecBadge.AVC, VideoCodecBadge.HEVC)
                "tv" -> setOf(VideoCodecBadge.AVC, VideoCodecBadge.HEVC, VideoCodecBadge.AV1)
                else -> emptySet()
            },
            animeShare = if (archetype == "anime") 0.8 else 0.1,
            temperature = if (noisy) 3.0 else between(0.6, 1.4),
            reach = between(4.0, 14.0),
            floor = between(0.05, 0.25),
            randomShare = if (noisy) 0.3 else 0.03,
            drift = if (drawn) 0.02 else 0.0,
        )
    }

    /**
     * One person's two devices sharing a profile: a user of [first] and one of [second] (archetypes as [user]). The
     * profile's settings (languages, sort), what they watch, and what travels between devices in a taste (the
     * languages they want, sources, cached links) are the first's; the rest of the taste on the second device is its
     * own archetype's mixed with a share of the first's (one person's devices are alike, not the same).
     */
    fun person(name: String, first: String, second: String, random: Random): Pair<MockUser, MockUser> {
        val a = user("$name-$first", first, random)
        val own = user("$name-$second", second, random)
        val device = own.taste.mixed(a.taste, 0.5 + 0.3 * random.nextDouble())
        val taste = device.copy(
            audioLanguage = a.taste.audioLanguage, audioMatch = a.taste.audioMatch, audioOther = a.taste.audioOther,
            multiAudio = a.taste.multiAudio, subtitleLanguage = a.taste.subtitleLanguage, subtitles = a.taste.subtitles,
            source = a.taste.source, cached = a.taste.cached,
        )
        val b = own.copy(
            taste = taste, audioLanguages = a.audioLanguages, subtitleLanguages = a.subtitleLanguages, sortKeys = a.sortKeys,
            animeShare = a.animeShare,
        )
        return a to b
    }

    /**
     * Another device of [user]'s kind, its screen or decoders unlike the first's: a TV that does or doesn't show Dolby
     * Vision, a phone that does or doesn't decode AV1 in hardware, a desktop screen with or without HDR.
     */
    fun anotherDevice(user: MockUser): MockUser = when (user.deviceClass) {
        DeviceClass.TV -> user.copy(name = "${user.name}-another", dolbyVisionDisplay = !user.dolbyVisionDisplay)
        DeviceClass.HANDHELD -> user.copy(
            name = "${user.name}-another",
            hardwareCodecs = if (VideoCodecBadge.AV1 in user.hardwareCodecs) user.hardwareCodecs - VideoCodecBadge.AV1 else user.hardwareCodecs + VideoCodecBadge.AV1,
        )
        DeviceClass.DESKTOP -> user.copy(name = "${user.name}-another", hdrDisplay = !user.hdrDisplay)
    }

    /** Where [user] picks: its device, and a metered network [MockUser.meteredShare] of the time. */
    fun context(user: MockUser, random: Random) = PickContext(
        user.deviceClass, random.nextDouble() < user.meteredShare, user.hdrDisplay, user.dolbyVisionDisplay, user.hardwareCodecs,
    )

    /** [user]'s list for [title]: the addons' order, or its sort. */
    fun shown(user: MockUser, title: MockTitle): List<MockStream> =
        StreamSort(user.sortKeys).sorted(title.streams, { it.stream }, { it.badges })

    /** A title's list as a pick sees it: a random 70 % of its streams (addons answer differently each time). */
    fun sample(title: MockTitle, random: Random, id: String): MockTitle =
        MockTitle(id, title.runtimeSeconds, title.streams.filter { random.nextDouble() < 0.7 }.ifEmpty { title.streams }, title.anime)

    /** A title of [titles], anime [MockUser.animeShare] of the time when there are any. */
    fun title(user: MockUser, titles: List<MockTitle>, random: Random): MockTitle {
        val anime = titles.filter { it.anime }
        val other = titles.filterNot { it.anime }
        return if (anime.isNotEmpty() && (other.isEmpty() || random.nextDouble() < user.animeShare)) anime.random(random) else other.random(random)
    }

    /**
     * [count] picks of [user] over [titles], oldest first, recorded as [StreamPicks] records them (the candidates
     * around the chosen one). A pick far below the best of its list ([Taste.utility] 2 or more under it) is left within
     * 30 s and another stream picked from the same list, most of the time; 4 % of the links are dead (bad, and picked
     * again). Of the rest, three in four went well and one says nothing. Returns [user] as its taste drifted to.
     */
    fun history(user: MockUser, titles: List<MockTitle>, count: Int, random: Random): Pair<List<MockPick>, MockUser> {
        var now = user
        val picks = ArrayList<MockPick>()
        while (picks.size < count) {
            val base = title(now, titles, random)
            val title = sample(base, random, "${base.id}#${picks.size}")
            val context = context(now, random)
            val shown = shown(now, title)
            val excluded = mutableSetOf<Int>()
            do {
                val index = choose(now, shown, context, title.runtimeSeconds, random, excluded)
                val utilities = shown.map { now.taste.utility(it.picked, context, title.runtimeSeconds) }
                val regret = utilities.max() - utilities[index]
                val bad = random.nextDouble() < 0.04 || (regret >= 2 && random.nextDouble() < 0.7)
                val outcome = if (bad) PickOutcome.BAD else if (random.nextDouble() < 0.75) PickOutcome.GOOD else null
                picks += MockPick(record(now, title, shown, index, context, outcome, "${now.name}-${picks.size}"), title, shown, shown[index])
                excluded += index
                now = now.copy(taste = now.taste.drifted(random, now.drift))
            } while (bad && excluded.size < shown.size && picks.size < count)
        }
        return picks to now
    }

    /** A pick of row [index] of [shown] as [StreamPicks] records it. */
    fun record(user: MockUser, title: MockTitle, shown: List<MockStream>, index: Int, context: PickContext, outcome: PickOutcome?, id: String): StreamPick {
        val start = (index - StreamPicks.ABOVE).coerceAtLeast(0)
        val end = (index + StreamPicks.BELOW + 1).coerceAtMost(shown.size)
        return StreamPick(
            id = id,
            metaId = title.id,
            videoId = title.id,
            runtimeSeconds = title.runtimeSeconds,
            pickedAt = 0,
            context = context,
            source = PickSource.LIST,
            candidates = shown.subList(start, end).map { it.picked },
            chosen = index - start,
            outcome = outcome,
            firstRow = start,
        )
    }

    /** The row [user] picks from [shown]: softmax of utility over temperature, times the row's propensity. */
    fun choose(user: MockUser, shown: List<MockStream>, context: PickContext, runtimeSeconds: Long, random: Random, excluded: Set<Int> = emptySet()): Int {
        val open = shown.indices.filter { it !in excluded }
        if (random.nextDouble() < user.randomShare) return open.take(10).random(random)
        val logits = open.map { user.taste.utility(shown[it].picked, context, runtimeSeconds) / user.temperature + ln(user.propensity(it)) }
        val top = logits.max()
        val weights = logits.map { exp(it - top) }
        var r = random.nextDouble() * weights.sum()
        for (k in open.indices) {
            r -= weights[k]
            if (r <= 0) return open[k]
        }
        return open.last()
    }

    /** Releases a development session's seeded picks are made among, as release names with their sizes. */
    private val SEED_RELEASES = listOf(
        "Movie.2024.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.7.1.Atmos" to "58.3 GB",
        "Movie.2024.2160p.WEB-DL.DV.HDR10.HEVC.DDP5.1.Atmos" to "16.2 GB",
        "Movie.2024.2160p.WEB-DL.HDR10.HEVC.DDP5.1" to "12.9 GB",
        "Movie.2024.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1" to "28.4 GB",
        "Movie.2024.1080p.BluRay.x264.DTS-HD.MA.5.1" to "11.7 GB",
        "Movie.2024.1080p.BluRay.x265.10bit.AAC5.1" to "4.4 GB",
        "Movie.2024.1080p.WEB-DL.H264.DDP5.1" to "6.1 GB",
        "Movie.2024.1080p.WEB-DL.HEVC.AAC2.0" to "1.9 GB",
        "Movie.2024.1080p.WEB-DL.ITA.ENG.H264.AC3" to "3.2 GB",
        "Movie.2024.1080p.WEBRip.x265.10bit.AAC5.1" to "2.3 GB",
        "Movie.2024.720p.WEB-DL.H264.AAC" to "1.2 GB",
        "Movie.2024.720p.HDTV.x264.AAC" to "900 MB",
        "Movie.2024.FRENCH.1080p.WEB-DL.H264" to "3.5 GB",
        "Movie.2024.WEBRip.x264.AAC" to "1.4 GB",
        "Movie.2024.DVDRip.XviD.MP3" to "700 MB",
    )

    /**
     * [count] picks of [user] among [SEED_RELEASES] lists for each kind of device, for a development session
     * (`--dev-picks`): the history stream suggestions learn from, oldest first.
     */
    fun seed(user: MockUser, count: Int = 60): List<StreamPick> {
        val streams = SEED_RELEASES.map { (release, size) ->
            MockStream(Stream(StreamSource.Url("https://seed.example/$release"), name = "Seed", description = "$release\n💾 $size"))
        }
        val title = MockTitle("seed", 6600, streams, anime = false)
        val random = Random(1)
        return DeviceClass.entries.flatMap { deviceClass ->
            history(user, listOf(title), count, random).first.map { mock ->
                mock.pick.copy(id = "${mock.pick.id}-${deviceClass.key}", context = mock.pick.context.copy(deviceClass = deviceClass))
            }
        }
    }
}

/** A standard normal draw (Box–Muller). */
internal fun gaussian(random: Random): Double {
    val u = random.nextDouble().coerceAtLeast(1e-12)
    return sqrt(-2 * ln(u)) * kotlin.math.cos(2 * kotlin.math.PI * random.nextDouble())
}
