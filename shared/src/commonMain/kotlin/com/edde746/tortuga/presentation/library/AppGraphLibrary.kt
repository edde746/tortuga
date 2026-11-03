package com.edde746.tortuga.presentation.library

import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.data.library.LibraryItem
import com.edde746.tortuga.presentation.board.posterCaptions
import com.edde746.tortuga.presentation.calendar.newEpisodeCounts
import com.edde746.tortuga.presentation.calendar.newEpisodesUpNext
import com.edde746.tortuga.presentation.menu.mediaActions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The Library screen: the profile's library, filtered and sorted, with its Calendar button; series count their new
 * episodes. Its rows follow the profile's "Hide titles under posters" ([posterCaptions]).
 */
fun AppGraph.libraryStore(profileId: String, host: LibraryHost): LibraryStore = LibraryStore(
    LibraryMode.LIBRARY, data.library.observeLibrary(profileId), host, scope, mediaActions(profileId), newEpisodeCounts(profileId), kidsGate(profileId),
    posterCaptions(profileId),
)

/**
 * The Continue Watching screen: every item of [continueWatching], filtered and sorted like the library, with its
 * Calendar button; series count their new episodes.
 */
fun AppGraph.continueWatchingStore(profileId: String, host: LibraryHost): LibraryStore = LibraryStore(
    LibraryMode.CONTINUE_WATCHING, continueWatching(profileId, Int.MAX_VALUE), host, scope, mediaActions(profileId), newEpisodeCounts(profileId),
    kidsGate(profileId),
)

/**
 * The profile's Continue Watching, as Home's row, its screen and the TV's home screen show it, most recently changed
 * first, at most [limit]: the items with a position to resume
 * ([com.edde746.tortuga.data.library.LibraryRepository.observeContinueWatching]) and the library series a new episode
 * brings back on their Up next, dated by their newest episode's release ([newEpisodesUpNext]).
 */
internal fun AppGraph.continueWatching(profileId: String, limit: Int): Flow<List<LibraryItem>> =
    combine(data.library.observeContinueWatching(profileId, limit), newEpisodesUpNext(profileId)) { resuming, upNext ->
        if (upNext.isEmpty()) resuming else (resuming + upNext).sortedByDescending { it.mtime }.take(limit)
    }
