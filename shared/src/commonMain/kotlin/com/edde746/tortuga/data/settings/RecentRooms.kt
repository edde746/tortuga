package com.edde746.tortuga.data.settings

import com.edde746.tortuga.data.Tx
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A watch-together room this profile was in: its [code] on the relay at [relay] (a canonical base URL), when it was
 * [lastUsedMillis], and the control mode it was created with ([anyoneControls]), so entering it again as its host
 * keeps it.
 */
@Serializable
data class RecentRoom(
    @SerialName("code") val code: String,
    @SerialName("relay") val relay: String,
    @SerialName("lastUsed") val lastUsedMillis: Long,
    @SerialName("anyone") val anyoneControls: Boolean = false,
)

/**
 * One profile's recent watch-together rooms on this device, newest first. A device setting, so it never syncs; keyed by
 * profile. At most [LIMIT], one per code and relay. Forgotten with the profile ([forgetRecentRooms]) and when the
 * account's data leaves the device ([forgetAllRecentRooms]).
 */
internal class RecentRooms(profileId: String) :
    DeviceSetting<List<RecentRoom>>(KEY_PREFIX + profileId, emptyList(), ListSerializer(RecentRoom.serializer())) {
    override fun normalize(value: List<RecentRoom>): List<RecentRoom> =
        value.sortedByDescending { it.lastUsedMillis }.distinctBy { it.relay to it.code }.take(LIMIT)

    companion object {
        const val LIMIT = 20
        const val KEY_PREFIX = "watch.recentRooms."
    }
}

/** Drops [profileId]'s recent rooms (the profile is being deleted). */
internal fun Tx.forgetRecentRooms(profileId: String) {
    q.deviceSettingQueries.delete(RecentRooms(profileId).key)
}

/** Drops every profile's recent rooms (the account's profiles are leaving this device). */
internal fun Tx.forgetAllRecentRooms() {
    q.deviceSettingQueries.selectAll().executeAsList()
        .filter { it.setting_key.startsWith(RecentRooms.KEY_PREFIX) }
        .forEach { q.deviceSettingQueries.delete(it.setting_key) }
}
