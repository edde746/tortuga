package com.edde746.tortuga.data.sync

import com.edde746.tortuga.sync.api.SyncProtocol
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The natural key of a synced record; [recordId] is its readable, unambiguous form. */
@Serializable
internal sealed class RecordKey {
    abstract val profileId: String

    /** Record kind, then the key parts. */
    protected abstract fun parts(): List<String>

    @Serializable
    @SerialName("profile")
    data class Profile(override val profileId: String) : RecordKey() {
        override fun parts() = listOf(SyncProtocol.PROFILE_RECORD, profileId)
    }

    /** A profile's whole addon list: one record, so the order can't split between devices. */
    @Serializable
    @SerialName("addons")
    data class AddonList(override val profileId: String) : RecordKey() {
        override fun parts() = listOf("addons", profileId)
    }

    @Serializable
    @SerialName("library")
    data class LibraryItem(override val profileId: String, val itemId: String) : RecordKey() {
        override fun parts() = listOf("library", profileId, itemId)
    }

    @Serializable
    @SerialName("stream")
    data class StreamHistory(override val profileId: String, val metaId: String, val videoId: String) : RecordKey() {
        override fun parts() = listOf("stream", profileId, metaId, videoId)
    }

    @Serializable
    @SerialName("setting")
    data class Setting(override val profileId: String, val key: String) : RecordKey() {
        override fun parts() = listOf("setting", profileId, key)
    }

    /** One hand pick of a stream ([com.edde746.tortuga.data.streams.StreamPick]): its record carries its outcome too. */
    @Serializable
    @SerialName("pick")
    data class StreamPick(override val profileId: String, val pickId: String) : RecordKey() {
        override fun parts() = listOf("pick", profileId, pickId)
    }

    /** The record id ([SyncProtocol.recordId]): `library/<profile id>/tt0111161`. */
    fun recordId(): String = SyncProtocol.recordId(parts())
}
