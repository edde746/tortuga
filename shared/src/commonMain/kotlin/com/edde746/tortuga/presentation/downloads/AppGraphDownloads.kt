package com.edde746.tortuga.presentation.downloads

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.playback.players.PlayerApp
import kotlinx.coroutines.flow.Flow

/**
 * The Downloads screen of [profileId]; null where this device has no downloads (Apple TV). With [playerApps] (this
 * device's, as they're found, where they can open the files), its rows offer "Play with…". While the profile has Keep
 * synced rules it leads to the Keep synced screen.
 */
fun AppGraph.downloadsStore(profileId: String, host: DownloadsHost, playerApps: Flow<List<PlayerApp>>? = null): DownloadsStore? {
    val manager = downloads ?: return null
    return DownloadsStore(profileId, manager, network, flavor.streamListPolicy.cacheBadge, host, scope, playerApps)
}

/** The Keep synced screen of [profileId]; null where this device has no downloads (Apple TV). */
fun AppGraph.keepSyncedStore(profileId: String, host: DownloadsHost): KeepSyncedStore? {
    val manager = downloads ?: return null
    return KeepSyncedStore(profileId, manager, network, host, scope)
}
