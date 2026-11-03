package com.edde746.tortuga.data.sync

import com.edde746.tortuga.data.library.WatchedField
import com.edde746.tortuga.data.library.Zlib
import com.edde746.tortuga.data.profile.HomeCatalog
import com.edde746.tortuga.data.settings.requireSettingValue
import com.edde746.tortuga.data.streams.DeviceClass
import com.edde746.tortuga.data.streams.PickOutcome
import com.edde746.tortuga.data.streams.PickSource
import com.edde746.tortuga.data.streams.PickedStream
import kotlin.io.encoding.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * One synced record as it travels: its natural [key], the [hlc] of the write (where last writer wins
 * is decided) and the content, or no [body] for a tombstone. JSON; field names follow Stremio's where the content is
 * Stremio's, so an import or export maps one to one.
 */
@Serializable
internal class SyncRecord(
    val key: RecordKey,
    val hlc: Hlc,
    val body: RecordBody?,
    /**
     * Payload format. A build that adds a record kind keeps it: older builds skip a record of a kind they don't know
     * (unreadable, so their pull moves on past it), and the build that adds the kind pulls once more from the start to
     * take what it skipped before. [FORMAT] goes up only when an existing kind's payload changes so that an older
     * build would misread it; older builds keep records of a newer format for when they are updated
     * ([NewerFormatException]).
     */
    val format: Int = FORMAT,
) {
    /** The record's JSON, the payload of its push. */
    fun encode(): String = json.encodeToString(serializer(), this)

    /**
     * Checks the content this build reads beyond its shape, as sync and import take a record in: a PIN hash is base64,
     * a setting's value decodes as its setting's type ([requireSettingValue]), a watched field is one
     * ([WatchedField.parse]), a stream pick's chosen stream is one of its candidates and its kind of device, source and
     * outcome are ones this build knows. What fails never reaches the tables, where it would read as a default.
     *
     * @throws IllegalArgumentException naming what doesn't read.
     */
    fun requireReadableContent() {
        when (body) {
            is ProfileBody -> body.pinHash?.let { require(Base64.decodeOrNull(it) != null) { "PIN hash isn't base64" } }
            is SettingBody -> requireSettingValue((key as RecordKey.Setting).key, body.value)
            is LibraryItemBody -> body.state.watched?.let {
                try {
                    WatchedField.parse(it)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Watched field isn't one", e)
                }
            }
            is StreamPickBody -> body.requireReadable()
            is AddonListBody, is StreamHistoryBody, null -> Unit
        }
    }

    companion object {
        /** This build's payload format ([format]). */
        const val FORMAT = 1

        /** The records' JSON settings; the export file ([com.edde746.tortuga.data.transfer.TransferFile]) uses them too. */
        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
            // Not "type": library items carry Stremio's `type` field.
            classDiscriminator = "kind"
        }

        /**
         * @throws NewerFormatException for a payload of a format newer than [FORMAT], which a later build reads.
         * @throws IllegalArgumentException for a payload that isn't a record of a known kind, or whose content is
         *   malformed ([requireReadableContent]).
         */
        @Throws(IllegalArgumentException::class)
        fun decode(payload: JsonObject): SyncRecord {
            val format = (payload["format"] as? JsonPrimitive)?.intOrNull ?: FORMAT
            if (format > FORMAT) throw NewerFormatException(format)
            val record = try {
                json.decodeFromJsonElement(serializer(), payload)
            } catch (e: Exception) {
                throw IllegalArgumentException("Unreadable record payload", e)
            }
            require(record.body == null || record.body.matches(record.key)) { "Record body doesn't match its key" }
            record.requireReadableContent()
            return record
        }
    }
}

/** A record of a newer payload format than this build reads ([SyncRecord.FORMAT]). */
internal class NewerFormatException(format: Int) : IllegalArgumentException("Record format $format is newer than ${SyncRecord.FORMAT}")

private fun Base64.decodeOrNull(text: String): ByteArray? = try {
    decode(text)
} catch (_: IllegalArgumentException) {
    null
}

@Serializable
internal sealed class RecordBody {
    abstract fun matches(key: RecordKey): Boolean
}

@Serializable
@SerialName("profile")
internal data class ProfileBody(
    val name: String,
    val avatar: String? = null,
    /** The PIN's salted hash ([com.edde746.tortuga.data.profile.PinHash]), base64. */
    val pinHash: String? = null,
    val createdAt: Long,
    /** A kids profile ([com.edde746.tortuga.data.profile.Profile.kids]) and the Home rows it starts with. */
    val kids: Boolean = false,
    val homeCatalogs: List<HomeCatalog> = emptyList(),
) : RecordBody() {
    override fun matches(key: RecordKey) = key is RecordKey.Profile
}

/**
 * A profile's addons in the user's order. The entries travel as their JSON, zlib-compressed and in base64
 * ([AddonListBodySerializer]): addons that list many catalogs repeat the same option lists, and a list of fifteen
 * popular addons, four of them catalog addons of about 43 KB each, packs from 287 KiB, over the server's
 * [com.edde746.tortuga.sync.api.SyncProtocol.MAX_RECORD_BYTES], into 32 KiB.
 */
@Serializable(with = AddonListBodySerializer::class)
@SerialName("addons")
internal data class AddonListBody(val addons: List<AddonEntry>) : RecordBody() {
    override fun matches(key: RecordKey) = key is RecordKey.AddonList
}

/** [AddonListBody] as it travels: [packed] as this build writes it, or the [addons] array that earlier builds wrote. */
@Serializable
private class AddonListWire(val packed: String? = null, val addons: List<AddonEntry>? = null)

private object AddonListBodySerializer : KSerializer<AddonListBody> {
    private val entries = ListSerializer(AddonEntry.serializer())

    override val descriptor: SerialDescriptor = SerialDescriptor("addons", AddonListWire.serializer().descriptor)

    override fun serialize(encoder: Encoder, value: AddonListBody) {
        val json = SyncRecord.json.encodeToString(entries, value.addons).encodeToByteArray()
        encoder.encodeSerializableValue(AddonListWire.serializer(), AddonListWire(packed = Base64.encode(Zlib.deflate(json))))
    }

    override fun deserialize(decoder: Decoder): AddonListBody {
        val wire = decoder.decodeSerializableValue(AddonListWire.serializer())
        val addons = if (wire.packed != null) {
            SyncRecord.json.decodeFromString(entries, Zlib.inflate(Base64.decode(wire.packed), MAX_UNPACKED_BYTES).decodeToString())
        } else {
            requireNotNull(wire.addons) { "An addon list without its addons" }
        }
        return AddonListBody(addons)
    }

    /** Bound of a packed list's JSON: 64 times the record limit, far past what JSON packs into (about 10 to 1). */
    private const val MAX_UNPACKED_BYTES = 16 shl 20
}

/**
 * One addon of the list: [enabled] off keeps it in the list unasked; [watchActivity] is the user's agreement that it
 * receives their watch activity, which travels with the profile's list to its other devices.
 */
@Serializable
internal data class AddonEntry(
    val transportUrl: String,
    val manifest: String,
    val enabled: Boolean = true,
    val watchActivity: Boolean = false,
)

/** Stremio's `libraryItem` without `_id` (in the key). Times in milliseconds, instants in epoch milliseconds. */
@Serializable
@SerialName("library")
internal data class LibraryItemBody(
    val type: String,
    val name: String,
    val poster: String? = null,
    val posterShape: String = "poster",
    val removed: Boolean,
    val temp: Boolean,
    @SerialName("_ctime") val ctime: Long? = null,
    @SerialName("_mtime") val mtime: Long,
    val state: LibraryStateBody,
) : RecordBody() {
    override fun matches(key: RecordKey) = key is RecordKey.LibraryItem
}

@Serializable
internal data class LibraryStateBody(
    val lastWatched: Long? = null,
    val timeWatched: Long = 0,
    val timeOffset: Long = 0,
    val overallTimeWatched: Long = 0,
    val timesWatched: Long = 0,
    val flaggedWatched: Long = 0,
    val duration: Long = 0,
    @SerialName("video_id") val videoId: String? = null,
    val watched: String? = null,
    val noNotif: Boolean = false,
)

@Serializable
@SerialName("stream")
internal data class StreamHistoryBody(val stream: String, val transportUrl: String, val updatedAt: Long) : RecordBody() {
    override fun matches(key: RecordKey) = key is RecordKey.StreamHistory
}

/** A setting's value as JSON text. */
@Serializable
@SerialName("setting")
internal data class SettingBody(val value: String) : RecordBody() {
    override fun matches(key: RecordKey) = key is RecordKey.Setting
}

/**
 * A stream picked by hand ([com.edde746.tortuga.data.streams.StreamPick]) and what came of it. The kind of device, the
 * source and the outcome travel as their keys ([DeviceClass.key], [PickSource.key], [PickOutcome.key]), the hardware
 * codecs as [com.edde746.tortuga.playback.streams.VideoCodecBadge] names (one this build doesn't know is left out as
 * it is read), the candidates as the database keeps them ([PickedStream]: short names, empty values left out). Times
 * in epoch milliseconds.
 */
@Serializable
@SerialName("pick")
internal data class StreamPickBody(
    val metaId: String,
    val videoId: String,
    val runtimeSeconds: Long? = null,
    val pickedAt: Long,
    val deviceClass: String,
    val metered: Boolean,
    val hdrDisplay: Boolean,
    val dolbyVisionDisplay: Boolean,
    val hardwareCodecs: List<String>,
    val source: String,
    val candidates: List<PickedStream>,
    val chosen: Int,
    val firstRow: Int,
    val outcome: String? = null,
    val outcomeAt: Long? = null,
) : RecordBody() {
    override fun matches(key: RecordKey) = key is RecordKey.StreamPick

    /** @throws IllegalArgumentException for a pick the stream suggestions couldn't read. */
    fun requireReadable() {
        require(chosen in candidates.indices) { "Stream pick's chosen stream isn't one of its candidates" }
        require(DeviceClass.fromKey(deviceClass) != null) { "Stream pick's kind of device isn't one this build knows" }
        require(PickSource.entries.any { it.key == source }) { "Stream pick's source isn't one this build knows" }
        require(outcome == null || PickOutcome.entries.any { it.key == outcome }) { "Stream pick's outcome isn't one this build knows" }
    }
}
