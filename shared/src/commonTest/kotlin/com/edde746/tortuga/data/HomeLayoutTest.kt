package com.edde746.tortuga.data

import com.edde746.tortuga.data.settings.HomeLayout
import com.edde746.tortuga.data.settings.ProfileSetting
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeLayoutTest {
    private val data = testData()

    @AfterTest
    fun close() = data.close()

    @Test
    fun theDefaultLayoutKeepsHomesOrder() {
        val rows = listOf("trakt", "a1", "a2", "b1")
        assertEquals(rows, HomeLayout().arrange(rows))
        assertEquals(rows, HomeLayout(hidden = listOf("a2")).arrange(rows), "hidden rows keep their place")
    }

    @Test
    fun listedRowsFollowTheProfilesOrder() {
        val layout = HomeLayout(order = listOf("b1", "a1", "trakt", "a2"))
        assertEquals(listOf("b1", "a1", "trakt", "a2"), layout.arrange(listOf("trakt", "a1", "a2", "b1")))
    }

    @Test
    fun newRowsTakeTheirDefaultPlaceAmongTheListedOnes() {
        val layout = HomeLayout(order = listOf("b1", "a1", "a2"))
        assertEquals(
            listOf("trakt1", "trakt2", "b1", "a1", "a1new", "a2", "c1", "c2"),
            layout.arrange(listOf("trakt1", "trakt2", "a1", "a1new", "a2", "b1", "c1", "c2")),
            "a newly connected tracker's lists lead, as by default; a new catalog follows the row before it; a newly " +
                "installed addon's rows go last, in their own order",
        )
        assertEquals(
            listOf("b1", "a1", "a2", "c1"),
            layout.arrange(listOf("a1", "a2", "b1", "c1")),
            "a row after every listed one goes last even when the last default row moved up",
        )
    }

    @Test
    fun rowsThatAreGoneLeaveTheArrangement() {
        val layout = HomeLayout(order = listOf("b1", "gone", "a1"), hidden = listOf("gone"))
        assertEquals(listOf("b1", "a1"), layout.arrange(listOf("a1", "b1")))
    }

    @Test
    fun aKeyListedTwiceCountsOnce() = runTest {
        assertEquals(listOf("b1", "a1"), HomeLayout(order = listOf("b1", "a1", "b1")).arrange(listOf("a1", "b1")))
        data.settings.set("p", ProfileSetting.HomeRows, HomeLayout(listOf("b1", "a1", "b1"), listOf("a1", "a1")))
        assertEquals(HomeLayout(listOf("b1", "a1"), listOf("a1")), data.settings.settings("p").homeRows, "stored without repeats")
        assertEquals(HomeLayout(hidden = listOf("a1")), HomeLayout(hidden = listOf("a1")).withHidden("a1", hidden = true))
    }

    @Test
    fun reorderingKeepsTheKeysOfRowsThatMayComeBackAfterTheRowTheyFollowed() {
        val layout = HomeLayout(order = listOf("simkl1", "a1", "trakt1", "b1", "gone"), hidden = listOf("trakt1"))
        val kept = setOf("simkl1", "trakt1")
        val next = layout.reordered(listOf("b1", "a1"), kept::contains)
        assertEquals(listOf("simkl1", "b1", "a1", "trakt1"), next.order, "a key first stays first; one after a moved row follows it")
        assertEquals(listOf("trakt1"), next.hidden)
    }

    @Test
    fun pruningDropsTheKeysOfRemovedAddonsButKeepsContinueWatching() {
        val layout = HomeLayout(
            order = listOf("a1", "removed1", "trakt1", "a2"),
            hidden = listOf("removed2", HomeLayout.CONTINUE_WATCHING, "a2"),
        )
        assertEquals(
            HomeLayout(order = listOf("a1", "trakt1", "a2"), hidden = listOf(HomeLayout.CONTINUE_WATCHING, "a2")),
            layout.pruned(present = setOf("a1", "a2"), kept = { it == "trakt1" }),
        )
    }
}
