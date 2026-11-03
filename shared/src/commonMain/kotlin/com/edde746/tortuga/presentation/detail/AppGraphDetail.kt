package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.addonEvents
import com.edde746.tortuga.app.addonRegistry
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.playback.players.PlayerAppHandoff
import com.edde746.tortuga.presentation.menu.mediaActions
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The Detail screen for the title [id] of [type] (a `stremio:///detail/{type}/{id}[/{videoId}]` link, a catalog
 * item, a library item). With a [videoId], its season is selected and [videoAction] is done with it once the title is
 * known (its stream list, or it plays); a card's menu entry Detail carries out comes as its [videoAction] too, done on
 * [videoId] or else the video Play plays. With a [shownVideoId], its season is selected and the episodes brought to it.
 * Opened from a catalog item, its header shows from [preview] while the title loads. While
 * [covered] (a screen over it), its state waits. Where this device has downloads, it plays and manages the title's;
 * offline it shows the title saved with them. With [playerApps], it shows the title's playback in a player app and its
 * "Finished watching?" prompt. Where the trackers are installed, an anime list entry no installed addon has opens as
 * the title it is (an IMDb title for Cinemeta). With [sharing] it offers the title's link ([TitleLink]). "More like this"
 * asks the profile's addons' `similar` catalogs and, where this build has Trakt, Trakt's related titles.
 */
fun AppGraph.detailStore(
    profileId: String,
    type: String,
    id: String,
    videoId: String?,
    host: DetailHost,
    playerApps: PlayerAppHandoff? = null,
    shownVideoId: String? = null,
    preview: MetaItemPreview? = null,
    covered: Flow<Boolean> = flowOf(false),
    videoAction: DetailVideoAction = DetailVideoAction.STREAMS,
    sharing: Boolean = true,
): DetailStore {
    val registry = addonRegistry(profileId)
    val trackers = trackers
    return DetailStore(
        profileId = profileId,
        type = type,
        id = id,
        videoId = videoId,
        shownVideoId = shownVideoId,
        preview = preview,
        covered = covered,
        aggregator = aggregator,
        installedAddons = { registry.current() },
        library = data.library,
        streamHistory = data.streamHistory,
        settings = data.settings.observe(profileId),
        autoPicker = autoPicker,
        host = host,
        clock = clock,
        parentScope = scope,
        network = network,
        trackerMarks = trackers?.marks(profileId),
        addonEvents = addonEvents(profileId),
        trackerRatings = trackers?.let { hub -> { meta -> hub.ratings.ratings(profileId, meta) } },
        coordinator = trackers?.coordinator(profileId),
        downloads = downloads,
        playerApps = playerApps,
        animeTitles = trackers?.animeTitles,
        videoAction = videoAction,
        sharing = sharing,
        relatedTitles = RelatedTitles(aggregator, trackers?.related),
        titleMarks = data.library.observeMarks(profileId),
        actions = mediaActions(profileId),
        kids = kidsGate(profileId),
    )
}
