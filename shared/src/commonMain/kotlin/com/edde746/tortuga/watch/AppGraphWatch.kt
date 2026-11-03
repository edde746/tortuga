package com.edde746.tortuga.watch

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.net.TortugaHttpClient
import com.edde746.tortuga.watch.media.fileMatcher
import com.edde746.tortuga.watch.media.roomMediaBuilder
import com.edde746.tortuga.watch.media.roomTitleResolver

/**
 * Watch together for [profileId], over this graph's HTTP client (the relays' WebSockets) and device settings (the relay,
 * recent rooms), with the profile's name as its name in rooms and `Tortuga/<version>` as its client; its player side
 * follows the playbacks [host] opens, the room's media and the guest's files over this graph's addons. Its scope is
 * the graph's, so a room's leave outlives the profile's session ([WatchTogether.close]).
 */
internal fun AppGraph.watchTogether(profileId: String, host: WatchPlaybackHost): WatchTogether =
    WatchTogether(
        http = http,
        deviceSettings = data.deviceSettings,
        profileId = profileId,
        displayName = { data.profiles.profile(profileId)?.name?.takeIf { it.isNotBlank() } ?: device.name },
        client = TortugaHttpClient.userAgent(appVersion),
        wallClock = clock,
        visible = visible,
        parentScope = scope,
        host = host,
        mediaBuilder = roomMediaBuilder(),
        titles = roomTitleResolver(profileId),
        matcher = fileMatcher(profileId),
    )
