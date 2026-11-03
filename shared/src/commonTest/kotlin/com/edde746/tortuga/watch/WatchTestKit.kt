package com.edde746.tortuga.watch

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.watch.media.FileMatcher
import com.edde746.tortuga.watch.media.RoomMediaBuilder
import com.edde746.tortuga.watch.media.RoomTitleResolver
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Clock

/**
 * Watch together for [profileId] over [http] and [data], the app in sight, for rooms without a player: no playback
 * opens, and the profile has no addons to find a room's files with.
 */
internal fun testWatchTogether(
    http: HttpClient,
    data: LocalData,
    profileId: String,
    displayName: String,
    client: String,
    wallClock: Clock,
    parentScope: CoroutineScope,
): WatchTogether {
    val aggregator = AddonAggregator(AddonTransport(http))
    return WatchTogether(
        http = http,
        deviceSettings = data.deviceSettings,
        profileId = profileId,
        displayName = { displayName },
        client = client,
        wallClock = wallClock,
        visible = MutableStateFlow(true),
        parentScope = parentScope,
        host = NoPlaybacks,
        mediaBuilder = RoomMediaBuilder(ids = null),
        titles = RoomTitleResolver(aggregator) { emptyList() },
        matcher = FileMatcher(profileId, aggregator, { emptyList() }, data.streamHistory, data.settings),
    )
}

/** An app with no player to open. */
private object NoPlaybacks : WatchPlaybackHost {
    override val active: RoomPlayback? get() = null

    override fun openRoomPlayer(): RoomPlayback? = null

    override fun closePlayer() = Unit

    override fun openStreams(meta: MetaItem, videoId: String) = Unit
}
