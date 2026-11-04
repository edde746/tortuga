package com.edde746.tortuga.data.settings

import com.edde746.tortuga.collections.COLLECTION_SETTING_PREFIX
import com.edde746.tortuga.collections.Collection
import com.edde746.tortuga.collections.collectionSettingKey
import com.edde746.tortuga.input.VideoClickAction
import com.edde746.tortuga.kids.AgeStep
import com.edde746.tortuga.kids.KidsTitle
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.trackers.TrackerService
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * A per-profile setting: synced as one record per [key], [default] while unset. Values are stored as JSON; one that
 * doesn't decode (a type changed between app versions, a bug) is logged and reads as the default, and stays stored
 * until the user changes the setting ([ProfileSettings.unreadable]). Sync and import refuse such a value
 * ([requireSettingValue]).
 */
sealed class ProfileSetting<T>(val key: String, val default: T, internal val serializer: KSerializer<T>) {
    /** Cleans a value before it's stored and after it's read. */
    internal open fun normalize(value: T): T = value

    /**
     * Percent of a video's duration that playback has to reach (by position: seeking there counts) for it to count as
     * watched, unless its credits start sooner (`PlaybackRules.countsAsWatched`). It drives the local watched state,
     * Continue Watching's move to the next episode and tracker marks alike; 70 is stremio-core's
     * `WATCHED_THRESHOLD_COEF`. A profile that never set it follows this default; one that set it keeps its value.
     */
    data object WatchedThresholdPercent : ProfileSetting<Int>("playback.watchedThresholdPercent", 70, Int.serializer()) {
        val range = SettingRange(1, 100)
        override fun normalize(value: Int) = range.clamp(value)
    }

    /** Audio languages, most preferred first, as ISO 639-2 codes (Stremio's); empty keeps the file's default track. */
    data object AudioLanguages : ProfileSetting<List<String>>("playback.audioLanguages", emptyList(), languageList) {
        override fun normalize(value: List<String>) = normalizeLanguages(value)
    }

    /** Subtitle languages, most preferred first, as ISO 639-2 codes; empty turns subtitles off unless forced. */
    data object SubtitleLanguages : ProfileSetting<List<String>>("playback.subtitleLanguages", emptyList(), languageList) {
        override fun normalize(value: List<String>) = normalizeLanguages(value)
    }

    /**
     * The language of the second subtitle, shown at the top of the picture with the first, as an ISO 639-2 code; null
     * shows none unless the user picks one in the player.
     */
    data object SecondSubtitleLanguage : ProfileSetting<String?>("playback.secondSubtitleLanguage", null, String.serializer().nullable) {
        override fun normalize(value: String?) = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    }

    /** When subtitles turn on by themselves ([SubtitleMode]): following the audio, or always, SDH first. */
    data object ShowSubtitles : ProfileSetting<SubtitleMode>("playback.showSubtitles", SubtitleMode.FOLLOW_AUDIO, SubtitleMode.serializer())

    /** After an episode, play the next one, from the same binge group when an addon marks one. */
    data object BingeWatching : ProfileSetting<Boolean>("playback.bingeWatching", true, Boolean.serializer())

    /**
     * Episode stills blurred and descriptions hidden until the episode is watched or started, so they give nothing
     * away; titles stay as they are. Settings → Appearance, kids profiles included.
     */
    data object BlurUnwatched : ProfileSetting<Boolean>("appearance.blurUnwatched", false, Boolean.serializer())

    /**
     * Home's catalog and tracker rows and Discover leave out the titles the profile has finished
     * ([com.edde746.tortuga.presentation.board.finishedTitles]): a movie watched, a series whose released episodes are
     * all watched. Settings → Appearance, kids profiles included.
     */
    data object HideWatched : ProfileSetting<Boolean>("appearance.hideWatched", false, Boolean.serializer())

    /**
     * Poster cards show their artwork alone, without the title and subtitle under it (screen readers still read them),
     * everywhere but Continue Watching, whose line is the episode and time left. Settings → Appearance, kids profiles
     * included.
     */
    data object HidePosterCaptions : ProfileSetting<Boolean>("appearance.hidePosterCaptions", false, Boolean.serializer())

    /** Seconds the up-next prompt counts down before the next episode starts; 0 starts it at once. */
    data object UpNextCountdownSeconds : ProfileSetting<Int>("playback.upNextCountdownSeconds", 10, Int.serializer()) {
        val range = SettingRange(0, 60)
        override fun normalize(value: Int) = range.clamp(value)
    }

    /** What the player does with intros found in a video's chapters ([SkipMode]). */
    data object SkipIntros : ProfileSetting<SkipMode>("playback.skipIntros", SkipMode.BUTTON, SkipMode.serializer())

    /** What the player does with recaps ("Previously on…") found in a video's chapters. */
    data object SkipRecaps : ProfileSetting<SkipMode>("playback.skipRecaps", SkipMode.BUTTON, SkipMode.serializer())

    /** What the player does with credits (and next-episode previews after them) found in a video's chapters. */
    data object SkipCredits : ProfileSetting<SkipMode>("playback.skipCredits", SkipMode.BUTTON, SkipMode.serializer())

    /** Seconds a short seek (arrow keys, D-pad, the player's skip buttons) jumps. */
    data object SeekShortSeconds : ProfileSetting<Int>("playback.seekShortSeconds", 10, Int.serializer()) {
        val range = SettingRange(1, 600)
        override fun normalize(value: Int) = range.clamp(value)
    }

    /** Seconds a long seek (Shift+arrow, a long press) jumps. */
    data object SeekLongSeconds : ProfileSetting<Int>("playback.seekLongSeconds", 30, Int.serializer()) {
        val range = SettingRange(1, 600)
        override fun normalize(value: Int) = range.clamp(value)
    }

    /** What a click on the video does with a mouse ([VideoClickAction]): pause, or show and hide the controls. */
    data object VideoClick : ProfileSetting<VideoClickAction>("playback.videoClick", VideoClickAction.PAUSE, VideoClickAction.serializer())

    /** The interface language as a BCP 47 tag (`pt-BR`), or `null` to follow the system. Settings → Appearance. */
    data object InterfaceLanguage : ProfileSetting<String?>("app.interfaceLanguage", null, String.serializer().nullable) {
        override fun normalize(value: String?) = value?.trim()?.takeIf { it.isNotEmpty() }
    }

    // ---- Streams: automatic stream selection and the stream list's filters ----

    /** What Play, Resume and an episode on Detail, and the next episode without a binge match, play ([AutoPlayMode]). */
    data object StreamAutoPlay : ProfileSetting<AutoPlayMode>("streams.autoPlay", AutoPlayMode.OFF, AutoPlayMode.serializer())

    /**
     * Stream suggestions: each hand pick of a stream is recorded and synced, and the stream lists mark the one the
     * profile's picks suggest. While off nothing is recorded and no row is marked; the picks already recorded stay.
     */
    data object StreamSuggestions : ProfileSetting<Boolean>("streams.suggestions", false, Boolean.serializer())

    /** The resolutions the stream lists hide; empty shows every one. */
    data object HiddenStreamQualities : ProfileSetting<List<StreamQuality>>(
        "streams.hiddenQualities", emptyList(), KeyedListSerializer(StreamQuality::fromKey) { it.key },
    ) {
        override fun normalize(value: List<StreamQuality>) = value.distinct().sorted()
    }

    /** The dynamic ranges the stream lists hide ([StreamRange]); empty shows every one. */
    data object HiddenStreamRanges : ProfileSetting<List<StreamRange>>(
        "streams.hiddenRanges", emptyList(), KeyedListSerializer(StreamRange::fromKey) { it.key },
    ) {
        override fun normalize(value: List<StreamRange>) = value.distinct().sorted()
    }

    /** The video codecs the stream lists hide ([StreamCodec]); empty shows every one. */
    data object HiddenStreamCodecs : ProfileSetting<List<StreamCodec>>(
        "streams.hiddenCodecs", emptyList(), KeyedListSerializer(StreamCodec::fromKey) { it.key },
    ) {
        override fun normalize(value: List<StreamCodec>) = value.distinct().sorted()
    }

    /** The stream lists hide files smaller than this many MiB; 0 hides none. A stream of no known size passes. */
    data object StreamMinSizeMb : ProfileSetting<Int>("streams.minSizeMb", 0, Int.serializer()) {
        override fun normalize(value: Int) = value.coerceAtLeast(0)
    }

    /** The stream lists hide files larger than this many MiB; 0 hides none. A stream of no known size passes. */
    data object StreamMaxSizeMb : ProfileSetting<Int>("streams.maxSizeMb", 0, Int.serializer()) {
        override fun normalize(value: Int) = value.coerceAtLeast(0)
    }

    /**
     * The order the stream lists and auto-play go by, most important key first ([StreamSortKey]); empty keeps the
     * addons' own order (an aggregator's sort stays as its user set it there). Each key once; largest and smallest
     * size first exclude each other, the one listed first staying.
     */
    data object StreamSortKeys : ProfileSetting<List<StreamSortKey>>(
        "streams.sort", emptyList(), KeyedListSerializer(StreamSortKey::fromKey) { it.key },
    ) {
        override fun normalize(value: List<StreamSortKey>): List<StreamSortKey> {
            val sizes = setOf(StreamSortKey.SIZE_LARGEST, StreamSortKey.SIZE_SMALLEST)
            val size = value.firstOrNull { it in sizes }
            return value.distinct().filter { it !in sizes || it == size }
        }
    }

    /**
     * A regular expression a stream's name, description or file name must match to be listed, ignoring case; empty
     * lists every stream. One that doesn't compile is kept as typed (the setting says so) and filters nothing.
     */
    data object StreamIncludePattern : ProfileSetting<String>("streams.includePattern", "", String.serializer()) {
        override fun normalize(value: String) = value.trim()
    }

    /** A regular expression whose matches the stream lists hide, ignoring case; it wins over [StreamIncludePattern]. */
    data object StreamExcludePattern : ProfileSetting<String>("streams.excludePattern", "", String.serializer()) {
        override fun normalize(value: String) = value.trim()
    }

    /** Home's rows as the profile arranged them: their order and the ones left off ([HomeLayout]). */
    data object HomeRows : ProfileSetting<HomeLayout>("home.rows", HomeLayout(), HomeLayout.serializer()) {
        override fun normalize(value: HomeLayout) = HomeLayout(value.order.distinct(), value.hidden.distinct())
    }

    /**
     * A URL template for titles' posters ([com.edde746.tortuga.presentation.board.PosterTemplate]); empty uses the
     * addons' posters. It usually holds a key of the poster service: Redactor removes it from logs as any URL's path.
     */
    data object PosterTemplate : ProfileSetting<String>("appearance.posterTemplate", "", String.serializer()) {
        override fun normalize(value: String) = value.trim()
    }

    /**
     * One of the profile's switches for a tracker service ([TrackerSwitches]), under `trackers.<service id>.<switch>`
     * (`trackers.trakt.sending`); on while unset.
     */
    data class Tracker(val service: TrackerService, val switch: TrackerSwitch) :
        ProfileSetting<Boolean>("trackers.${service.id}.${switch.key}", true, Boolean.serializer())

    // ---- Kids profile: the rating cap, unrated titles, and the titles a parent allowed or blocked ----

    /** The highest age step a kids profile shows ([AgeStep]); [AgeStep.ADULT] is stored as [AgeStep.TEEN]. */
    data object KidsCap : ProfileSetting<AgeStep>("kids.cap", AgeStep.KIDS, AgeStep.serializer()) {
        override fun normalize(value: AgeStep) = if (value == AgeStep.ADULT) AgeStep.TEEN else value
    }

    /** A kids profile shows titles with no suggested age; while off, a parent allows them one by one ([KidsAllowed]). */
    data object KidsAllowUnrated : ProfileSetting<Boolean>("kids.allowUnrated", false, Boolean.serializer())

    /** Titles a parent allowed on a kids profile whatever their suggested age, one per [KidsTitle.key]. */
    data object KidsAllowed : ProfileSetting<List<KidsTitle>>("kids.allowed", emptyList(), kidsTitleList) {
        override fun normalize(value: List<KidsTitle>) = value.distinctBy { it.key }
    }

    /** Titles a parent blocked on a kids profile whatever their rating, one per [KidsTitle.key]. */
    data object KidsBlocked : ProfileSetting<List<KidsTitle>>("kids.blocked", emptyList(), kidsTitleList) {
        override fun normalize(value: List<KidsTitle>) = value.distinctBy { it.key }
    }
}

/**
 * Every per-profile setting, resolved against its default.
 *
 * @param unreadable the keys whose stored value doesn't decode: their default reads in their place (a kids profile's
 *   cap and block list fail closed instead, `KidsPolicy`).
 */
data class ProfileSettings(
    val watchedThresholdPercent: Int,
    val audioLanguages: List<String>,
    val subtitleLanguages: List<String>,
    val secondSubtitleLanguage: String?,
    val showSubtitles: SubtitleMode,
    val bingeWatching: Boolean,
    val blurUnwatched: Boolean,
    val hideWatched: Boolean,
    val hidePosterCaptions: Boolean,
    val upNextCountdownSeconds: Int,
    val skipIntros: SkipMode,
    val skipRecaps: SkipMode,
    val skipCredits: SkipMode,
    val seekShortSeconds: Int,
    val seekLongSeconds: Int,
    val videoClick: VideoClickAction,
    val interfaceLanguage: String?,
    val streamAutoPlay: AutoPlayMode,
    val streamSuggestions: Boolean,
    val hiddenStreamQualities: List<StreamQuality>,
    val hiddenStreamRanges: List<StreamRange>,
    val hiddenStreamCodecs: List<StreamCodec>,
    val streamMinSizeMb: Int,
    val streamMaxSizeMb: Int,
    val streamIncludePattern: String,
    val streamExcludePattern: String,
    val streamSort: List<StreamSortKey>,
    val homeRows: HomeLayout,
    val posterTemplate: String,
    /** Every tracker service's switches, whether connected or not. */
    val trackers: Map<TrackerService, TrackerSwitches>,
    val kidsCap: AgeStep,
    val kidsAllowUnrated: Boolean,
    val kidsAllowed: List<KidsTitle>,
    val kidsBlocked: List<KidsTitle>,
    val unreadable: Set<String>,
) {
    /**
     * The subtitle languages a download saves unless this device chose its own: the subtitle languages, then the
     * second subtitle's, so a downloaded video has both to show.
     */
    val savedSubtitleLanguages: List<String> get() = (subtitleLanguages + listOfNotNull(secondSubtitleLanguage)).distinct()

    companion object {
        val DEFAULT: ProfileSettings = fromStored(emptyMap())

        /** From stored JSON values by key. */
        internal fun fromStored(values: Map<String, String>): ProfileSettings {
            val unreadable = HashSet<String>()
            fun <T> ProfileSetting<T>.value(): T {
                val json = values[key] ?: return default
                val decoded = decodeStored(key, json, serializer) ?: return default.also { unreadable += key }
                return normalize(decoded.value)
            }
            return ProfileSettings(
                watchedThresholdPercent = ProfileSetting.WatchedThresholdPercent.value(),
                audioLanguages = ProfileSetting.AudioLanguages.value(),
                subtitleLanguages = ProfileSetting.SubtitleLanguages.value(),
                secondSubtitleLanguage = ProfileSetting.SecondSubtitleLanguage.value(),
                showSubtitles = ProfileSetting.ShowSubtitles.value(),
                bingeWatching = ProfileSetting.BingeWatching.value(),
                blurUnwatched = ProfileSetting.BlurUnwatched.value(),
                hideWatched = ProfileSetting.HideWatched.value(),
                hidePosterCaptions = ProfileSetting.HidePosterCaptions.value(),
                upNextCountdownSeconds = ProfileSetting.UpNextCountdownSeconds.value(),
                skipIntros = ProfileSetting.SkipIntros.value(),
                skipRecaps = ProfileSetting.SkipRecaps.value(),
                skipCredits = ProfileSetting.SkipCredits.value(),
                seekShortSeconds = ProfileSetting.SeekShortSeconds.value(),
                seekLongSeconds = ProfileSetting.SeekLongSeconds.value(),
                videoClick = ProfileSetting.VideoClick.value(),
                interfaceLanguage = ProfileSetting.InterfaceLanguage.value(),
                streamAutoPlay = ProfileSetting.StreamAutoPlay.value(),
                streamSuggestions = ProfileSetting.StreamSuggestions.value(),
                hiddenStreamQualities = ProfileSetting.HiddenStreamQualities.value(),
                hiddenStreamRanges = ProfileSetting.HiddenStreamRanges.value(),
                hiddenStreamCodecs = ProfileSetting.HiddenStreamCodecs.value(),
                streamMinSizeMb = ProfileSetting.StreamMinSizeMb.value(),
                streamMaxSizeMb = ProfileSetting.StreamMaxSizeMb.value(),
                streamIncludePattern = ProfileSetting.StreamIncludePattern.value(),
                streamExcludePattern = ProfileSetting.StreamExcludePattern.value(),
                streamSort = ProfileSetting.StreamSortKeys.value(),
                homeRows = ProfileSetting.HomeRows.value(),
                posterTemplate = ProfileSetting.PosterTemplate.value(),
                trackers = TrackerService.entries.associateWith { service ->
                    TrackerSwitches(
                        sending = ProfileSetting.Tracker(service, TrackerSwitch.SENDING).value(),
                        movies = ProfileSetting.Tracker(service, TrackerSwitch.MOVIES).value(),
                        series = ProfileSetting.Tracker(service, TrackerSwitch.SERIES).value(),
                        anime = ProfileSetting.Tracker(service, TrackerSwitch.ANIME).value(),
                    )
                },
                kidsCap = ProfileSetting.KidsCap.value(),
                kidsAllowUnrated = ProfileSetting.KidsAllowUnrated.value(),
                kidsAllowed = ProfileSetting.KidsAllowed.value(),
                kidsBlocked = ProfileSetting.KidsBlocked.value(),
                unreadable = unreadable,
            )
        }
    }
}

/** The value stored as [json], [default] while unset or when it doesn't decode (logged, [decodeStored]). */
internal fun <T> ProfileSetting<T>.decodeSetting(json: String?): T {
    val decoded = json?.let { decodeStored(key, it, serializer) } ?: return default
    return normalize(decoded.value)
}

internal fun <T> ProfileSetting<T>.encodeSetting(value: T): String = settingsJson.encodeToString(serializer, normalize(value))

/** A decoded value; lets a setting whose values include `null` tell a stored `null` from an unreadable value. */
internal class Decoded<T>(val value: T)

/** The setting [key]'s stored [json] decoded as [serializer]'s type; `null` when it doesn't decode, which is logged. */
internal fun <T> decodeStored(key: String, json: String, serializer: KSerializer<T>): Decoded<T>? = try {
    Decoded(settingsJson.decodeFromString(serializer, json))
} catch (e: IllegalArgumentException) {
    // SerializationException is one too.
    Log.w(TAG, e) { "the stored value of $key doesn't decode: its default reads in its place" }
    null
}

/**
 * Checks that [json] is a value of the per-profile setting [key], as sync and import take one in: a collection's
 * (`collection.<id>`, [collectionSettingKey]) is that collection; for a key this build doesn't know (a newer build's
 * setting), that it is JSON.
 *
 * @throws IllegalArgumentException when it isn't.
 */
internal fun requireSettingValue(key: String, json: String) {
    try {
        val setting = settingsByKey[key]
        when {
            setting != null -> settingsJson.decodeFromString(setting.serializer, json)
            key.startsWith(COLLECTION_SETTING_PREFIX) -> {
                val collection = settingsJson.decodeFromString(Collection.serializer(), json)
                require(collectionSettingKey(collection.id) == key) { "Collection ${collection.id} stored under another key" }
            }
            else -> settingsJson.parseToJsonElement(json)
        }
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("The value of setting $key doesn't decode", e)
    }
}

/**
 * Every [ProfileSetting] by key. Lazy: the settings' objects read this file's values (their serializers) as they are
 * made, so an eager map here would see them half made.
 */
private val settingsByKey: Map<String, ProfileSetting<*>> by lazy {
    listOf(
        ProfileSetting.WatchedThresholdPercent, ProfileSetting.AudioLanguages, ProfileSetting.SubtitleLanguages,
        ProfileSetting.SecondSubtitleLanguage,
        ProfileSetting.ShowSubtitles, ProfileSetting.BingeWatching, ProfileSetting.BlurUnwatched, ProfileSetting.HideWatched,
        ProfileSetting.HidePosterCaptions, ProfileSetting.UpNextCountdownSeconds,
        ProfileSetting.SkipIntros, ProfileSetting.SkipRecaps, ProfileSetting.SkipCredits, ProfileSetting.SeekShortSeconds,
        ProfileSetting.SeekLongSeconds, ProfileSetting.VideoClick, ProfileSetting.InterfaceLanguage,
        ProfileSetting.StreamAutoPlay, ProfileSetting.StreamSuggestions, ProfileSetting.HiddenStreamQualities,
        ProfileSetting.HiddenStreamRanges, ProfileSetting.HiddenStreamCodecs, ProfileSetting.StreamMinSizeMb,
        ProfileSetting.StreamMaxSizeMb, ProfileSetting.StreamIncludePattern,
        ProfileSetting.StreamExcludePattern, ProfileSetting.StreamSortKeys, ProfileSetting.HomeRows, ProfileSetting.PosterTemplate,
        ProfileSetting.KidsCap, ProfileSetting.KidsAllowUnrated, ProfileSetting.KidsAllowed, ProfileSetting.KidsBlocked,
    ).associateBy { it.key } + TrackerService.entries.flatMap { service ->
        TrackerSwitch.entries.map { ProfileSetting.Tracker(service, it) }
    }.associateBy { it.key }
}

/**
 * The keys of a kids profile's settings, a parent's choices: [ProfileSetting.KidsCap], [ProfileSetting.KidsAllowUnrated],
 * [ProfileSetting.KidsAllowed] and [ProfileSetting.KidsBlocked]. No export file holds them and no import changes them.
 */
internal val kidsSettingKeys: Set<String> by lazy {
    listOf(ProfileSetting.KidsCap, ProfileSetting.KidsAllowUnrated, ProfileSetting.KidsAllowed, ProfileSetting.KidsBlocked).mapTo(HashSet()) { it.key }
}

private const val TAG = "settings"

internal val settingsJson = Json { ignoreUnknownKeys = true }

private val languageList = ListSerializer(String.serializer())

private val kidsTitleList = ListSerializer(KidsTitle.serializer())

private fun normalizeLanguages(value: List<String>) =
    value.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
