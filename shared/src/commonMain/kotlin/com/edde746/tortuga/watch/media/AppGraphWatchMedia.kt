package com.edde746.tortuga.watch.media

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.addonRegistry
import com.edde746.tortuga.playback.session.localVideos

/**
 * The host's [RoomMedia] builder, mapping anime ids through the trackers' id resolver when the trackers are installed
 * ([AppGraph.trackers]).
 */
internal fun AppGraph.roomMediaBuilder(): RoomMediaBuilder = RoomMediaBuilder(trackers?.resolver)

/** The profile's resolver of a room's media to a meta and video of its own, over its installed addons. */
internal fun AppGraph.roomTitleResolver(profileId: String): RoomTitleResolver {
    val registry = addonRegistry(profileId)
    return RoomTitleResolver(aggregator) { registry.load() }
}

/** The profile's files of a room's media, over its installed addons, stream history, settings and downloads. */
internal fun AppGraph.fileMatcher(profileId: String): FileMatcher {
    val registry = addonRegistry(profileId)
    return FileMatcher(
        profileId = profileId,
        aggregator = aggregator,
        installedAddons = { registry.load() },
        streamHistory = data.streamHistory,
        settings = data.settings,
        localVideos = downloads?.localVideos(profileId),
    )
}
