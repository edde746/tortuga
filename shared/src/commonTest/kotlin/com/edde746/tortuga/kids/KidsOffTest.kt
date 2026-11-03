@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.kids

import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.presentation.board.BoardRow
import com.edde746.tortuga.presentation.board.groups
import com.edde746.tortuga.presentation.board.items
import com.edde746.tortuga.presentation.board.rows
import com.edde746.tortuga.presentation.detail.DetailBody
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.search.SearchGroup
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** On a profile that isn't a kids profile, the app is as it is without kids rules. */
class KidsOffTest {
    /** What [profile] sees: Home's rows, Search's results for "deadpool", Discover's Family, Deadpool's page. */
    private class Seen(val rows: List<BoardRow>, val groups: List<SearchGroup>, val discover: List<String>, val title: String?, val kidsBlock: Any?)

    private fun TestScope.seen(w: KidsWorld, profile: Profile): Seen {
        val app = w.open(profile)
        val board = app.boardStore()
        val search = app.searchStore()
        app.navigator.openDiscover(DeepLink.Discover(KIDS_ADDON_URL, "movie", "family"))
        val discover = app.discoverStore()
        val detail = app.detailStore(Route.Detail("movie", DEADPOOL.id))
        search.search("deadpool")
        runCurrent()
        val seen = Seen(
            board.state.value.rows,
            search.state.value.groups,
            discover.state.value.items.map { it.id },
            (detail.state.value.body as? DetailBody.Page)?.header?.title,
            detail.state.value.body as? DetailBody.Blocked ?: detail.state.value.kidsBlockAction,
        )
        app.close()
        return seen
    }

    private fun KidsWorld.catalogs() {
        catalog("movie/family", TOY_STORY, DEADPOOL, TWELVE_ANGRY_MEN)
        catalog("movie/family/skip=3", GHOSTBUSTERS)
        catalog("movie/search/search=deadpool", DEADPOOL, TOY_STORY)
    }

    @Test
    fun anAdultProfileSeesEveryTitleAsksForNoRatingAndLoadsNoExtraPage() = runTest {
        val w = kidsWorld()
        w.catalogs()
        val adult = seen(w, w.parent)
        val family = listOf(TOY_STORY, DEADPOOL, TWELVE_ANGRY_MEN).map { it.id }
        assertEquals(family, adult.rows.single { it.title == "\u2068Family\u2069 · Movies" }.items.map { it.id })
        assertEquals(listOf(listOf(DEADPOOL.id, TOY_STORY.id)), adult.groups.map { group -> group.items.map { it.id } })
        assertEquals(family, adult.discover)
        assertEquals(DEADPOOL.name, adult.title)
        assertNull(adult.kidsBlock)
        assertTrue(w.addonPaths.none { "skip=" in it }, "no page loaded to fill a row: ${w.addonPaths}")
        assertEquals(0, w.ages.reads)
    }
}
