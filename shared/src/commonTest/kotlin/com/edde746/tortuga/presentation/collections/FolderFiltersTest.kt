@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.collections

import com.edde746.tortuga.collections.CollectionSource
import com.edde746.tortuga.collections.CollectionViewMode
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.kids.DEADPOOL
import com.edde746.tortuga.kids.GHOSTBUSTERS
import com.edde746.tortuga.kids.TOY_STORY
import com.edde746.tortuga.kids.kidsWorld
import com.edde746.tortuga.presentation.board.rows
import com.edde746.tortuga.presentation.navigation.Route
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A folder's titles leave out what Home's rows leave out: the titles hidden as watched ("Hide watched titles") and
 * those a kids profile's gate refuses.
 */
class FolderFiltersTest {
    @Test
    fun aFolderLeavesOutHiddenWatchedTitlesAndWhatTheKidsGateRefusesAsHomesRowsDo() = runTest {
        val w = kidsWorld()
        w.catalog("movie/family", TOY_STORY, DEADPOOL, GHOSTBUSTERS)
        val data = w.graph.data
        data.settings.set(w.kids.id, ProfileSetting.HideWatched, true)
        data.library.markWatched(w.kids.id, TOY_STORY.snapshot(), watched = true)
        val family = CollectionSource.AddonCatalog("org.example.kids", "movie", "family")
        for (mode in listOf(CollectionViewMode.TABS, CollectionViewMode.ROWS)) {
            data.collections.save(w.kids.id, collection(family, mode = mode))
            val app = w.open()
            val board = app.boardStore()
            val folder = app.folderStore(Route.CollectionFolder("c", "f"))
            runCurrent()
            board.state.value.rows.indices.forEach(board::rowVisible)
            folder.rowVisible(0)
            runCurrent()

            val home = board.state.value.rows.single { it.title == "\u2068Family\u2069 · Movies" }.items.map { it.id }
            val shown = (folder.state.value.grid?.items ?: folder.state.value.rows.single().items).map { it.id }
            assertEquals(listOf(GHOSTBUSTERS.id), home, "Toy Story is watched, Deadpool is for 16")
            assertEquals(home, shown, "$mode: the folder shows what Home's row shows")
            folder.release()
            app.close()
        }
    }
}
