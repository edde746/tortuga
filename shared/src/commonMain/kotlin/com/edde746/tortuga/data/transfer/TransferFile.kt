package com.edde746.tortuga.data.transfer

import com.edde746.tortuga.data.settings.kidsSettingKeys
import com.edde746.tortuga.data.sync.AddonListBody
import com.edde746.tortuga.data.sync.LibraryItemBody
import com.edde746.tortuga.data.sync.ProfileBody
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.data.sync.SettingBody
import com.edde746.tortuga.data.sync.StreamHistoryBody
import com.edde746.tortuga.data.sync.SyncRecord
import com.edde746.tortuga.log.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.time.Instant

/**
 * What an export can hold, besides the profiles themselves. Each is a part of the
 * synced records of a profile; [LIBRARY] and [PROGRESS] are the two halves of one library item.
 */
@Serializable
enum class DataCategory {
    /** The installed addons in their order, with their transport URLs (which can hold API keys). */
    @SerialName("addons")
    ADDONS,

    /** The saved titles: library items in the library, without their watch state. */
    @SerialName("library")
    LIBRARY,

    /** Library items' watch state: the resume position, the watched marks, the play counts. */
    @SerialName("progress")
    PROGRESS,

    /** The profile's settings that sync (playback, streams, Home rows, ...), every key as stored. */
    @SerialName("settings")
    SETTINGS,

    /** The stream last played per video, with the addon that returned it. */
    @SerialName("streams")
    STREAMS,
}

/**
 * Tortuga's export file: one UTF-8 JSON object with the [FORMAT_NAME] and [version] first, the app [version][app]
 * that wrote it, the export time, the [categories] it holds and the [records]: every exported profile's own record
 * first, then their data, each record in the shape sync pushes ([SyncRecord]: key, HLC, body). A record belongs to
 * a profile of the file and to one of its categories; tombstones are never written and are skipped on import.
 */
@Serializable
internal class TransferFile(
    val format: String,
    val version: Int,
    val app: String,
    /** ISO-8601 UTC. */
    val exportedAt: String,
    val categories: List<DataCategory>,
    val records: List<SyncRecord>,
) {
    /** The file's profiles in its order: their records, which every other record's profile has. */
    val profiles: List<Pair<String, ProfileBody>>
        get() = records.mapNotNull { record -> (record.body as? ProfileBody)?.let { record.key.profileId to it } }

    fun encode(): String = SyncRecord.json.encodeToString(serializer(), this)

    companion object {
        const val FORMAT_NAME = "tortuga-export"
        const val VERSION = 1

        /**
         * Reads and checks a whole file before anything uses it. A record whose content this build can't read (a
         * setting's value, a watched field: [SyncRecord.requireReadableContent]) is left out, logged, as sync leaves
         * out such a record: the file's other records still import.
         *
         * @throws TransferFileException for a file that isn't Tortuga's, is of a newer version, or is damaged
         *   (unreadable JSON, a truncated file, a record that doesn't fit its key, its categories or its profiles).
         *   The exception carries no part of the file.
         */
        @Throws(TransferFileException::class)
        fun decode(text: String): TransferFile {
            val root = try {
                SyncRecord.json.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                // A truncated export still starts with its format name.
                throw TransferFileException(if (FORMAT_NAME in text.take(HEAD_LENGTH)) Reason.DAMAGED else Reason.NOT_TORTUGA)
            } ?: throw TransferFileException(Reason.NOT_TORTUGA)
            if ((root["format"] as? JsonPrimitive)?.content != FORMAT_NAME) throw TransferFileException(Reason.NOT_TORTUGA)
            val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: throw TransferFileException(Reason.DAMAGED)
            if (version > VERSION) throw TransferFileException(Reason.NEWER)
            val file = try {
                SyncRecord.json.decodeFromJsonElement(serializer(), root)
            } catch (_: SerializationException) {
                throw TransferFileException(Reason.DAMAGED)
            } catch (_: IllegalArgumentException) {
                throw TransferFileException(Reason.DAMAGED)
            }
            file.check()
            return file.readable()
        }

        private const val HEAD_LENGTH = 64
    }

    private fun check() {
        if (records.any { it.format > SyncRecord.FORMAT }) throw TransferFileException(Reason.NEWER)
        val profileIds = profiles.map { it.first }.toSet()
        val ids = HashSet<String>(records.size * 2)
        for (record in records) {
            val body = record.body
            val fits = (body == null || body.matches(record.key)) &&
                record.key.profileId in profileIds &&
                record.key.categories()?.let { it.isEmpty() || it.any(categories::contains) } == true &&
                ids.add(record.key.recordId())
            if (!fits) throw TransferFileException(Reason.DAMAGED)
        }
    }

    /** This file less the records whose content doesn't read ([SyncRecord.requireReadableContent]), each logged. */
    private fun readable(): TransferFile {
        val kept = records.filter { record ->
            try {
                record.requireReadableContent()
                true
            } catch (e: IllegalArgumentException) {
                Log.w("transfer", e) { "an imported record is left out: ${e.message}" }
                false
            }
        }
        return if (kept.size == records.size) this else TransferFile(format, version, app, exportedAt, categories, kept)
    }
}

/** Why [TransferFile.decode] refused a file. */
internal class TransferFileException(val reason: Reason) : Exception("Export file refused: $reason")

internal enum class Reason {
    /** Not JSON, or JSON of another format. */
    NOT_TORTUGA,

    /** Written by a newer Tortuga, in a format this one can't read. */
    NEWER,

    /** Tortuga's format, but not whole or not consistent. */
    DAMAGED,
}

/**
 * The categories a record of this key can carry; none for a profile's own record, null for a kind an export never
 * holds. Stream picks aren't exported: an older build's import fails the whole file on a record kind it doesn't know
 * ([TransferFile.decode] decodes every record), and they reach the profile's other devices through sync.
 */
private fun RecordKey.categories(): List<DataCategory>? = when (this) {
    is RecordKey.Profile -> emptyList()
    is RecordKey.AddonList -> listOf(DataCategory.ADDONS)
    is RecordKey.LibraryItem -> listOf(DataCategory.LIBRARY, DataCategory.PROGRESS)
    is RecordKey.StreamHistory -> listOf(DataCategory.STREAMS)
    is RecordKey.Setting -> listOf(DataCategory.SETTINGS)
    is RecordKey.StreamPick -> null
}

/** In the library: added and not removed (as Library lists it). */
internal val LibraryItemBody.inLibrary: Boolean get() = !removed && type != "other"

/** Has watch state: a position, time played, a play count or watched marks (`lastWatched` alone is set on creation). */
internal val LibraryItemBody.hasProgress: Boolean
    get() = with(state) { timeOffset > 0 || timeWatched > 0 || overallTimeWatched > 0 || timesWatched > 0 || flaggedWatched > 0 || watched != null }

/**
 * How much of [category] the file holds for [profileIds]: addons, titles in the library, titles with progress,
 * settings (a kids profile's never: the import leaves them out, [kidsSettingKeys]), streams.
 */
internal fun TransferFile.count(category: DataCategory, profileIds: Set<String>): Int {
    if (category !in categories) return 0
    return records.filter { it.key.profileId in profileIds }.sumOf { record ->
        when (val body = record.body) {
            is AddonListBody -> if (category == DataCategory.ADDONS) body.addons.size else 0
            is LibraryItemBody -> when (category) {
                DataCategory.LIBRARY -> if (body.inLibrary) 1 else 0
                DataCategory.PROGRESS -> if (body.hasProgress) 1 else 0
                else -> 0
            }
            is StreamHistoryBody -> if (category == DataCategory.STREAMS) 1 else 0
            is SettingBody -> if (category == DataCategory.SETTINGS && (record.key as RecordKey.Setting).key !in kidsSettingKeys) 1 else 0
            else -> 0
        }
    }
}

/** The export time as an instant; null when the file's text isn't one. */
internal fun TransferFile.exportedInstant(): Instant? = try {
    Instant.parse(exportedAt)
} catch (_: IllegalArgumentException) {
    null
}
