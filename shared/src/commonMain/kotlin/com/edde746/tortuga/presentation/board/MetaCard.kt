package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.kids.KidsGate
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.menu.MediaMenuEntry
import com.edde746.tortuga.presentation.menu.MenuDownload
import com.edde746.tortuga.presentation.menu.MenuWatchlist
import com.edde746.tortuga.presentation.menu.choosesStream
import com.edde746.tortuga.presentation.menu.mediaMenu
import com.edde746.tortuga.strings.T
import kotlin.math.roundToInt

/**
 * One catalog item as the Board, Discover, Search and Detail's More like this show it: a poster and its text,
 * preformatted.
 *
 * @param title the item's name; empty when the addon sends none.
 * @param year the addon's release text as given ("1994", "2011–2019"), else the release date's year.
 * @param rating the IMDb rating ("8.3"), when the addon sends one.
 * @param backgroundUrl a backdrop, e.g. behind the focused item on a TV.
 * @param preview the item as the catalog sent it: Detail draws its header from it while the title loads.
 * @param watched the title is watched at least once: a mark on the poster.
 * @param inLibrary the title is in the user's library.
 * @param download where its download stands, for its menu: a movie's, where this device has downloads; else null.
 * @param trailer the catalog item names a trailer this app can open, for its menu.
 * @param watchlist its Watchlist entry, for its menu, where the profile's trackers keep lists of such titles; else null.
 * @param caption the title and [subtitle] show under the artwork; false shows the artwork alone (Settings → Appearance
 *   → Hide titles under posters), while screen readers still read them ([itemStateText]).
 */
data class MetaCard(
    override val id: String,
    override val type: String,
    val title: String,
    val year: String?,
    val rating: String?,
    val posterUrl: String?,
    val posterShape: PosterShape,
    val backgroundUrl: String?,
    override val preview: MetaItemPreview? = null,
    val watched: Boolean = false,
    val inLibrary: Boolean = false,
    val download: MenuDownload? = null,
    val trailer: Boolean = false,
    val watchlist: MenuWatchlist? = null,
    val caption: Boolean = true,
) : KidsGate.Card {
    /** The line under the title: "1994 · ★ 9.3", "1994", "★ 9.3", or null with neither. */
    val subtitle: String?
        get() = when {
            year != null && rating != null -> T.browse.card.yearAndRating(year, rating)
            year != null -> year
            rating != null -> T.browse.card.rating(rating)
            else -> null
        }

    /** The item's menu (long press, right click): its store's `menuAction` carries an entry out. */
    val menu: List<MediaMenuEntry>
        get() = mediaMenu(inLibrary, watched, chooseStream = choosesStream(type), download = download, trailer = trailer, watchlist = watchlist)
}

/**
 * One item of Continue Watching: something with a resume position, from the library.
 *
 * @param videoId the video to resume (an episode id, or the item's own id for a movie); null when the library doesn't
 *   know it.
 * @param detail the line under the title: "S1E2 · 12 min left", "12 min left", "S1E3 · Up next" (the next episode,
 *   queued after the credits of the last one), or null when there is nothing to say.
 * @param progress how far into [videoId] playback got, 0 to 1.
 * @param watched [videoId] counts as watched: an episode played past the watched threshold, a movie watched before.
 * @param inLibrary the title is in the user's library.
 * @param download where [videoId]'s download stands, for its menu, where this device has downloads; else null.
 * @param resumes the card's press resumes [videoId] (`PlaybackSession.resumePoint`), so its menu offers Play from the
 *   beginning.
 * @param newEpisodes the series' episodes out since it was last watched, as its addons list them (the badge).
 * @param watchlist the title's Watchlist entry, for its menu, as [MetaCard.watchlist].
 */
data class ContinueWatchingCard(
    val id: String,
    val type: String,
    val videoId: String?,
    val title: String,
    val detail: String?,
    val progress: Float,
    val posterUrl: String?,
    val posterShape: PosterShape,
    val watched: Boolean = false,
    val inLibrary: Boolean = false,
    val download: MenuDownload? = null,
    val resumes: Boolean = false,
    val newEpisodes: Int = 0,
    val watchlist: MenuWatchlist? = null,
) {
    /** The bar over the artwork, 0 to 1; null before anything played ("Up next"), which shows no bar. */
    val shownProgress: Float?
        get() = progress.takeIf { it > 0f }

    /** The corner's count of [newEpisodes] ("2"); null with none. */
    val badge: String?
        get() = newEpisodesBadge(newEpisodes)

    /** What [badge] says, for assistive technologies ("2 new episodes"); null with none. */
    val badgeDescription: String?
        get() = newEpisodesDescription(newEpisodes)

    /** The card's menu (long press, right click): `BoardStore.continueWatchingAction` carries an entry out. */
    val menu: List<MediaMenuEntry>
        get() = mediaMenu(inLibrary, watched, inProgress = resumes, continueWatching = true, chooseStream = true, download = download, watchlist = watchlist)
}

/** A series' card counts [newEpisodes] in its corner ("2"); null with none, where the card has no badge. */
internal fun newEpisodesBadge(newEpisodes: Int): String? = newEpisodes.takeIf { it > 0 }?.toString()

/** What [newEpisodesBadge] says, for assistive technologies ("2 new episodes"); null with none. */
internal fun newEpisodesDescription(newEpisodes: Int): String? = newEpisodes.takeIf { it > 0 }?.let(T.calendar::newEpisodes)

/**
 * What a card or an episode says after its name, to screen readers (Compose's state description, Apple's
 * accessibility value): its [badge] ("2 new episodes"), [detail] (its caption's second line: "2024 · ★ 7.8",
 * "S1E3 · 12 min left"), the [progress] its artwork shows (0 to 1, said to the nearest percent: "45%") and "Watched"
 * once [watched], in that order, joined as a list; null with none of them. Watched is a state, not a selection, which
 * is a chosen season's or tab's.
 */
fun itemStateText(badge: String?, detail: String?, progress: Double?, watched: Boolean): String? {
    val parts = listOfNotNull(
        badge,
        detail,
        progress?.let { T.settings.value.percent((it.coerceIn(0.0, 1.0) * 100).roundToInt().toString()) },
        T.library.watched.takeIf { watched },
    )
    return if (parts.isEmpty()) null else parts.joinToString(T.detail.listSeparator)
}
