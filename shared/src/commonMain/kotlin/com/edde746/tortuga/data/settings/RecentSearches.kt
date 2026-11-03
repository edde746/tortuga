package com.edde746.tortuga.data.settings

import com.edde746.tortuga.data.Tx
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * One profile's recent searches on this device (the Search screen), newest first. A device setting, so it never
 * syncs; keyed by profile, so profiles sharing a device don't see each other's searches. At most [LIMIT] queries,
 * trimmed, without blanks or case-insensitive duplicates (the newest spelling wins). Forgotten with the profile
 * ([forgetRecentSearches]) and when the account's data leaves the device ([forgetAllRecentSearches]).
 */
internal class RecentSearches(profileId: String) :
    DeviceSetting<List<String>>(KEY_PREFIX + profileId, emptyList(), ListSerializer(String.serializer())) {
    override fun normalize(value: List<String>): List<String> =
        value.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase).take(LIMIT)

    companion object {
        const val LIMIT = 10
        const val KEY_PREFIX = "search.recent."
    }
}

/** Drops [profileId]'s recent searches (the profile is being deleted). */
internal fun Tx.forgetRecentSearches(profileId: String) {
    q.deviceSettingQueries.delete(RecentSearches(profileId).key)
}

/** Drops every profile's recent searches (the account's profiles are leaving this device). */
internal fun Tx.forgetAllRecentSearches() {
    q.deviceSettingQueries.selectAll().executeAsList()
        .filter { it.setting_key.startsWith(RecentSearches.KEY_PREFIX) }
        .forEach { q.deviceSettingQueries.delete(it.setting_key) }
}
