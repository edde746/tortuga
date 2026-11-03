package com.edde746.tortuga.data.settings

import com.edde746.tortuga.data.Tx
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * When this device last fetched each of one profile's addons' manifests again, in epoch milliseconds by transport URL,
 * so that a return to the foreground asks each addon at most once a day
 * ([com.edde746.tortuga.app.refreshAddonManifests]). A device setting, so it never syncs; keyed by profile; it keeps
 * the installed addons' entries only. Forgotten with the profile ([forgetManifestChecks]) and when the account's data
 * leaves the device ([forgetAllManifestChecks]).
 */
internal class ManifestChecks(profileId: String) :
    DeviceSetting<Map<String, Long>>(KEY_PREFIX + profileId, emptyMap(), MapSerializer(String.serializer(), Long.serializer())) {
    companion object {
        const val KEY_PREFIX = "addons.manifestChecks."
    }
}

/** Drops [profileId]'s manifest checks (the profile is being deleted). */
internal fun Tx.forgetManifestChecks(profileId: String) {
    q.deviceSettingQueries.delete(ManifestChecks(profileId).key)
}

/** Drops every profile's manifest checks (the account's profiles are leaving this device). */
internal fun Tx.forgetAllManifestChecks() {
    q.deviceSettingQueries.selectAll().executeAsList()
        .filter { it.setting_key.startsWith(ManifestChecks.KEY_PREFIX) }
        .forEach { q.deviceSettingQueries.delete(it.setting_key) }
}
