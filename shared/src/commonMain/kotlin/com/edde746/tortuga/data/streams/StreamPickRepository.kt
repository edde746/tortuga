package com.edde746.tortuga.data.streams

import com.edde746.tortuga.data.Db
import com.edde746.tortuga.data.Tx
import com.edde746.tortuga.data.db.Stream_pick
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.data.sync.StreamPickBody
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.playback.streams.AudioBadge
import com.edde746.tortuga.playback.streams.ChannelsBadge
import com.edde746.tortuga.playback.streams.HdrBadge
import com.edde746.tortuga.playback.streams.ResolutionBadge
import com.edde746.tortuga.playback.streams.SourceBadge
import com.edde746.tortuga.playback.streams.StreamBadges
import com.edde746.tortuga.playback.streams.VideoCodecBadge
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The kind of device a pick was made on: a profile picks differently on a phone, a TV and a desktop. */
enum class DeviceClass(val key: String) {
    /** Phones and tablets. */
    HANDHELD("handheld"),
    TV("tv"),
    DESKTOP("desktop"),
    ;

    companion object {
        fun of(formFactor: FormFactor): DeviceClass = when (formFactor) {
            FormFactor.HANDHELD -> HANDHELD
            FormFactor.TV -> TV
            FormFactor.DESKTOP -> DESKTOP
        }

        fun fromKey(key: String): DeviceClass? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Where a pick was made: the kind of device, whether its network cost per byte, and what its screen and decoders take.
 *
 * @param hardwareCodecs the video codecs the device decodes in hardware; empty where the platform can't tell.
 */
data class PickContext(
    val deviceClass: DeviceClass,
    val metered: Boolean,
    val hdrDisplay: Boolean,
    val dolbyVisionDisplay: Boolean,
    val hardwareCodecs: Set<VideoCodecBadge>,
)

/** Where a stream was picked: in the Streams list, or as the player's next stream. */
enum class PickSource(val key: String) {
    LIST("list"),
    PLAYER("player"),
}

/** What came of a pick ([StreamPickRepository.setOutcome]). */
enum class PickOutcome(val key: String) {
    /** It played on past the first two minutes, or to its end. */
    GOOD("good"),

    /** It failed, stalled for long, or was left within 30 s for another stream of the same video. */
    BAD("bad"),

    /** Left within 30 s: bad once another stream is picked for the same video, else nothing is known. */
    BACKED_OUT("backed_out"),
}

/**
 * A stream as a pick saw it: what its text says ([StreamBadges]) and its size, compact in storage and in its synced
 * record (short keys, empty values left out). Never its URL or its addon's.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PickedStream(
    @SerialName("r") val resolution: ResolutionBadge? = null,
    @SerialName("h") @EncodeDefault(EncodeDefault.Mode.NEVER) val hdr: List<HdrBadge> = emptyList(),
    @SerialName("v") val videoCodec: VideoCodecBadge? = null,
    @SerialName("s") val source: SourceBadge? = null,
    @SerialName("a") @EncodeDefault(EncodeDefault.Mode.NEVER) val audio: List<AudioBadge> = emptyList(),
    @SerialName("c") val channels: ChannelsBadge? = null,
    @SerialName("b") val sizeBytes: Long? = null,
    @SerialName("k") val cached: Boolean? = null,
    @SerialName("la") @EncodeDefault(EncodeDefault.Mode.NEVER) val audioLanguages: List<String> = emptyList(),
    @SerialName("ls") @EncodeDefault(EncodeDefault.Mode.NEVER) val subtitleLanguages: List<String> = emptyList(),
    @SerialName("ma") @EncodeDefault(EncodeDefault.Mode.NEVER) val multiAudio: Boolean = false,
    @SerialName("ms") @EncodeDefault(EncodeDefault.Mode.NEVER) val multiSubtitles: Boolean = false,
) {
    companion object {
        /** A stream whose text says [badges], [sizeBytes] long (the addon's `videoSize`, else its text's). */
        fun of(badges: StreamBadges, sizeBytes: Long?): PickedStream = PickedStream(
            resolution = badges.resolution,
            hdr = badges.hdr,
            videoCodec = badges.videoCodec,
            source = badges.source,
            audio = badges.audio,
            channels = badges.channels,
            sizeBytes = sizeBytes,
            cached = badges.cached,
            audioLanguages = badges.languages.audio,
            subtitleLanguages = badges.languages.subtitles,
            multiAudio = badges.languages.multiAudio,
            multiSubtitles = badges.languages.multiSubtitles,
        )
    }
}

/**
 * A stream picked by hand: the [candidates] shown around it, in the order shown, [chosen] among them, the first of them
 * at list row [firstRow] (0 the list's top).
 *
 * @param runtimeSeconds the video's length as its meta gives it; null when it gives none.
 * @param pickedAt epoch milliseconds.
 * @param outcome what came of it; null until known.
 */
data class StreamPick(
    val id: String,
    val metaId: String,
    val videoId: String,
    val runtimeSeconds: Long?,
    val pickedAt: Long,
    val context: PickContext,
    val source: PickSource,
    val candidates: List<PickedStream>,
    val chosen: Int,
    val outcome: PickOutcome?,
    val firstRow: Int = 0,
)

/**
 * The streams each profile picked by hand and what came of each, for stream suggestions. Synced per profile, never
 * exported; a profile keeps its newest [KEEP] picks of the last year per kind of device.
 */
class StreamPickRepository internal constructor(private val db: Db) {
    /**
     * Records [pick] (its [StreamPick.pickedAt] and [StreamPick.outcome] are the database's: now, and none yet). A pick
     * of the same video left within 30 s ([PickOutcome.BACKED_OUT]) in the [REPICK_WINDOW_MILLIS] before went wrong:
     * it becomes [PickOutcome.BAD]. Then what the profile keeps is pruned ([prunePicks]).
     */
    suspend fun record(profileId: String, pick: StreamPick) {
        db.write {
            val now = now()
            val hlc = tick().toString()
            q.streamPickQueries.markBackedOutBad(at = now, hlc = hlc, profileId = profileId, metaId = pick.metaId, videoId = pick.videoId, since = now - REPICK_WINDOW_MILLIS)
            q.streamPickQueries.insert(
                Stream_pick(
                    pick_id = pick.id,
                    profile_id = profileId,
                    meta_id = pick.metaId,
                    video_id = pick.videoId,
                    runtime_seconds = pick.runtimeSeconds,
                    picked_at = now,
                    device_class = pick.context.deviceClass.key,
                    metered = pick.context.metered,
                    hdr_display = pick.context.hdrDisplay,
                    dolby_vision_display = pick.context.dolbyVisionDisplay,
                    hardware_codecs = pick.context.hardwareCodecs.joinToString(",") { it.name },
                    source = pick.source.key,
                    candidates_json = json.encodeToString(candidatesSerializer, pick.candidates),
                    chosen = pick.chosen.toLong(),
                    first_row = pick.firstRow.toLong(),
                    outcome = null,
                    outcome_at = null,
                    record_id = RecordKey.StreamPick(profileId, pick.id).recordId(),
                    hlc = hlc,
                    server_version = null,
                    dirty = true,
                    deleted = false,
                ),
            )
            prunePicks(profileId)
        }
    }

    /**
     * What came of the pick [pickId]; a pick already known good or bad keeps that. One left within 30 s
     * ([PickOutcome.BACKED_OUT]) after which another stream of its video was already picked (the list's pick lands
     * before the player leaves the stream) went wrong. An outcome is a new write of the pick's record.
     */
    suspend fun setOutcome(pickId: String, outcome: PickOutcome) {
        db.write {
            val hlc = tick().toString()
            q.streamPickQueries.setOutcome(outcome = outcome.key, at = now(), hlc = hlc, pickId = pickId)
            if (outcome == PickOutcome.BACKED_OUT) q.streamPickQueries.markBadIfPickedAgain(hlc = hlc, pickId = pickId, window = REPICK_WINDOW_MILLIS)
        }
    }

    /**
     * [profileId]'s picks on every kind of device ([PickContext.deviceClass] tells each one's), oldest first; a row
     * that no longer reads is logged and left out.
     */
    suspend fun picks(profileId: String): List<StreamPick> =
        db.read { streamPickQueries.selectLive(profileId).executeAsList() }.mapNotNull { it.toModel() }

    /**
     * Where [profileId]'s picks are at: it changes with any pick, outcome, prune or pulled pick, so what was learnt from
     * them is due again when it differs.
     */
    suspend fun changeMark(profileId: String): String =
        db.read { streamPickQueries.changeMark(profileId).executeAsOne() }.let { "${it.picks}/${it.clock}/${it.newest}" }

    companion object {
        /** The picks a profile keeps per kind of device, newest first. */
        const val KEEP: Int = 400

        /** How long after a pick left within 30 s picking another stream for the video makes it bad. */
        const val REPICK_WINDOW_MILLIS: Long = 30L * 60 * 1000
    }
}

/** How long a pick is kept. */
private const val MAX_AGE_MILLIS = 365L * 24 * 60 * 60 * 1000

/**
 * Drops [profileId]'s picks past what it keeps: on each kind of device its newest [StreamPickRepository.KEEP] of the
 * last year. Picks of all its devices pool here, and each kind of device keeps its own so that a phone's picks never
 * crowd out the TV's. A dropped pick becomes a tombstone without its candidates, so the other devices drop it too;
 * they choose the same picks to drop (by time, then id), so whichever prunes first, the rest agree. Runs after a pick
 * here and after picks from other devices arrive.
 */
internal fun Tx.prunePicks(profileId: String) {
    val since = now() - MAX_AGE_MILLIS
    val dropped = DeviceClass.entries.flatMap { deviceClass ->
        q.streamPickQueries.selectPrunable(profileId = profileId, deviceClass = deviceClass.key, since = since, keep = StreamPickRepository.KEEP.toLong()).executeAsList()
    }
    if (dropped.isEmpty()) return
    val hlc = tick().toString()
    for (pickId in dropped) q.streamPickQueries.tombstone(hlc = hlc, pickId = pickId)
}

private val json = Json {
    explicitNulls = false
    ignoreUnknownKeys = true
}

private val candidatesSerializer = ListSerializer(PickedStream.serializer())

private fun Stream_pick.toModel(): StreamPick? {
    val context = PickContext(
        deviceClass = DeviceClass.fromKey(device_class) ?: return null,
        metered = metered,
        hdrDisplay = hdr_display,
        dolbyVisionDisplay = dolby_vision_display,
        hardwareCodecs = hardware_codecs.split(',').mapNotNullTo(mutableSetOf()) { name -> VideoCodecBadge.entries.firstOrNull { it.name == name } },
    )
    val candidates = try {
        json.decodeFromString(candidatesSerializer, candidates_json)
    } catch (e: SerializationException) {
        Log.w("streams", e) { "the stream pick $pick_id doesn't decode" }
        return null
    }
    return StreamPick(
        id = pick_id,
        metaId = meta_id,
        videoId = video_id,
        runtimeSeconds = runtime_seconds,
        pickedAt = picked_at,
        context = context,
        source = PickSource.entries.firstOrNull { it.key == source } ?: return null,
        candidates = candidates,
        chosen = chosen.toInt().takeIf { it in candidates.indices } ?: return null,
        outcome = outcome?.let { key -> PickOutcome.entries.firstOrNull { it.key == key } },
        firstRow = first_row.toInt(),
    )
}

/** The pick's synced record content; for a live row only (a tombstone's candidates are gone). */
internal fun Stream_pick.toBody() = StreamPickBody(
    metaId = meta_id,
    videoId = video_id,
    runtimeSeconds = runtime_seconds,
    pickedAt = picked_at,
    deviceClass = device_class,
    metered = metered,
    hdrDisplay = hdr_display,
    dolbyVisionDisplay = dolby_vision_display,
    hardwareCodecs = if (hardware_codecs.isEmpty()) emptyList() else hardware_codecs.split(','),
    source = source,
    candidates = json.decodeFromString(candidatesSerializer, candidates_json),
    chosen = chosen.toInt(),
    firstRow = first_row.toInt(),
    outcome = outcome,
    outcomeAt = outcome_at,
)

/** The live row of the pick [key] as a synced record ([StreamPickBody]) carries it. */
internal fun StreamPickBody.toRow(key: RecordKey.StreamPick, recordId: String, hlc: String, serverVersion: Long?, dirty: Boolean) = Stream_pick(
    pick_id = key.pickId,
    profile_id = key.profileId,
    meta_id = metaId,
    video_id = videoId,
    runtime_seconds = runtimeSeconds,
    picked_at = pickedAt,
    device_class = deviceClass,
    metered = metered,
    hdr_display = hdrDisplay,
    dolby_vision_display = dolbyVisionDisplay,
    hardware_codecs = hardwareCodecs.joinToString(","),
    source = source,
    candidates_json = json.encodeToString(candidatesSerializer, candidates),
    chosen = chosen.toLong(),
    first_row = firstRow.toLong(),
    outcome = outcome,
    outcome_at = outcomeAt,
    record_id = recordId,
    hlc = hlc,
    server_version = serverVersion,
    dirty = dirty,
    deleted = false,
)
