package com.edde746.tortuga.presentation.homescreen

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.data.library.posterShapeOf
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.model.videoIsTitle
import com.edde746.tortuga.presentation.board.episodeNumbers
import com.edde746.tortuga.presentation.board.resumes
import com.edde746.tortuga.presentation.board.shownProgress
import com.edde746.tortuga.presentation.library.continueWatching
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The TV's home screen outside the app ([com.edde746.tortuga.app.AppPlatform.homeScreen]): Android TV's Watch Next
 * row, the Apple TV's Top Shelf. The running profile's app publishes its [homeScreenItems] there as they change, and
 * signing out publishes nothing ([com.edde746.tortuga.app.AppSession], [com.edde746.tortuga.app.RootSession]); what
 * was published last stays while the app isn't running.
 */
interface HomeScreen {
    /** Shows [items] in place of what the app published before; an empty list shows nothing. Main thread; returns at once. */
    fun publish(items: List<HomeScreenItem>)
}

/**
 * One Continue Watching item as a TV's home screen shows it ([HomeScreen]). Times are milliseconds, instants epoch
 * milliseconds.
 *
 * @param profileId the profile whose Continue Watching it is: its links open only in that profile.
 * @param videoId the video to resume: an episode's id, or the title's own id for a movie; null when the library doesn't
 *   know it.
 * @param kind [Kind.EPISODE] when [videoId] is another video of the title (a series' episode, a channel's video).
 * @param season the season in [videoId] when it follows Stremio's `{id}:{season}:{episode}` convention; null otherwise.
 * @param episode the episode in [videoId] by that convention, or by `{id}:{episode}` (Kitsu's); null otherwise.
 * @param positionMillis where Play resumes it ([com.edde746.tortuga.playback.session.PlaybackSession.resumePoint]); 0
 *   when it plays from the start: the next episode queued after the last one's credits (Up next), a position under a
 *   second, a movie past its credits.
 * @param durationMillis the video's length; 0 when unknown.
 * @param progress how far into the video it got, 0 to 1, as Home's card draws it; null when the card draws none.
 * @param posterUrl the title's poster as the library keeps it (an addon's https artwork), drawn in [posterShape].
 * @param lastEngagedMillis when it was last watched, or else last changed: the order the home screen keeps.
 * @param link what selecting it opens: its video resuming, as its Continue Watching card does ([HomeScreenLink.Play]),
 *   or the title's Detail when the library doesn't know the video.
 * @param detailLink the title's Detail ([HomeScreenLink.Detail]).
 */
data class HomeScreenItem(
    val profileId: String,
    val type: String,
    val id: String,
    val videoId: String?,
    val kind: Kind,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val positionMillis: Long,
    val durationMillis: Long,
    val progress: Double?,
    val posterUrl: String?,
    val posterShape: PosterShape,
    val lastEngagedMillis: Long,
    val link: String,
    val detailLink: String,
) {
    enum class Kind { MOVIE, EPISODE }
}

/** How many items a home screen gets: one row's worth, as Android TV's Watch Next and the Top Shelf show. */
const val HOME_SCREEN_LIMIT: Int = 20

/**
 * Whether [profile]'s titles may show on the TV's home screen, which anyone in the room sees: not a profile with a PIN
 * (the app asks for it before it opens that profile at launch: [com.edde746.tortuga.presentation.account.AccountGate]),
 * and not a profile that is gone.
 */
internal fun publishesToHomeScreen(profile: Profile?): Boolean = profile != null && !profile.hasPin

/**
 * What [profileId] shows on the TV's home screen: its Continue Watching ([continueWatching]), the items of Home's row
 * in its order (most recently changed first), at most [HOME_SCREEN_LIMIT], while the profile may show there
 * ([publishesToHomeScreen]), and nothing otherwise. Emits again whenever that changes: progress saved, a sync pulling
 * another device's, an item finished or removed, a new episode out, a PIN set or removed.
 */
fun AppGraph.homeScreenItems(profileId: String): Flow<List<HomeScreenItem>> =
    combine(
        continueWatching(profileId, HOME_SCREEN_LIMIT),
        data.profiles.observeProfile(profileId).map(::publishesToHomeScreen).distinctUntilChanged(),
    ) { items, shown -> if (shown) items.map { it.toHomeScreenItem(profileId) } else emptyList() }
        .distinctUntilChanged()

/**
 * These items without the titles [kids] doesn't let through (nor those whose verdict isn't known yet), filtered again
 * whenever a verdict may have changed.
 */
internal fun Flow<List<HomeScreenItem>>.passing(kids: KidsGate): Flow<List<HomeScreenItem>> =
    combine(this, kids.changes) { items, _ -> items.filter { kids.shows(it.type, it.id, preview = null) } }.distinctUntilChanged()

internal fun LibraryItem.toHomeScreenItem(profileId: String): HomeScreenItem {
    val videoId = state.videoId
    val numbers = episodeNumbers(id, videoId)
    val detailLink = HomeScreenLink.Detail(profileId, type, id).toUrl()
    return HomeScreenItem(
        profileId = profileId,
        type = type,
        id = id,
        videoId = videoId,
        kind = if (videoIsTitle(id, videoId)) HomeScreenItem.Kind.MOVIE else HomeScreenItem.Kind.EPISODE,
        title = name,
        season = numbers?.takeIf { it.size == 2 }?.first(),
        episode = numbers?.last(),
        positionMillis = if (resumes()) state.timeOffset else 0,
        durationMillis = state.duration,
        progress = shownProgress(),
        posterUrl = poster,
        posterShape = posterShapeOf(posterShape),
        lastEngagedMillis = state.lastWatched ?: mtime,
        link = videoId?.let { HomeScreenLink.Play(profileId, type, id, it).toUrl() } ?: detailLink,
        detailLink = detailLink,
    )
}
