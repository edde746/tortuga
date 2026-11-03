package com.edde746.tortuga.data.settings

import com.edde746.tortuga.data.Tx

/**
 * The key prefix of a profile's back stacks on this device ([com.edde746.tortuga.presentation.navigation.SavedNavigation]),
 * whose key is it and the profile's id.
 */
internal const val SAVED_NAVIGATION_KEY_PREFIX = "navigation.stacks."

/** Drops [profileId]'s back stacks (the profile is being deleted). */
internal fun Tx.forgetSavedNavigation(profileId: String) {
    q.deviceSettingQueries.delete(SAVED_NAVIGATION_KEY_PREFIX + profileId)
}

/** Drops every profile's back stacks (the account's profiles are leaving this device). */
internal fun Tx.forgetAllSavedNavigation() {
    q.deviceSettingQueries.selectAll().executeAsList()
        .filter { it.setting_key.startsWith(SAVED_NAVIGATION_KEY_PREFIX) }
        .forEach { q.deviceSettingQueries.delete(it.setting_key) }
}
