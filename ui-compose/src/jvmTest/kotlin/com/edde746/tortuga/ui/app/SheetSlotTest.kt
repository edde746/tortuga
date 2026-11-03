package com.edde746.tortuga.ui.app

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A sheet keeps its content while it slides away, and a sheet opened again (the same route, after Back closed it) gets
 * fresh content: what the closed one remembered (its route's store) was released with it.
 */
@OptIn(ExperimentalTestApi::class)
class SheetSlotTest {
    private var sheet by mutableStateOf<String?>(null)
    private val composed = mutableListOf<String>()

    @Test
    fun theSameSheetOpenedAgainIsComposedAfresh() = runComposeUiTest {
        setContent {
            SheetSlot(sheet) { route, visible ->
                remember { composed += route }
                Text(if (visible) route else "$route closing")
            }
        }
        sheet = "streams"
        onNodeWithText("streams").assertExists()

        sheet = null
        onNodeWithText("streams closing").assertExists()
        runOnIdle { assertEquals(listOf("streams"), composed) }

        sheet = "streams"
        onNodeWithText("streams").assertExists()
        runOnIdle { assertEquals(listOf("streams", "streams"), composed) }
    }

    @Test
    fun anotherSheetReplacesTheOneOpen() = runComposeUiTest {
        setContent {
            SheetSlot(sheet) { route, visible ->
                remember { composed += route }
                Text(if (visible) route else "$route closing")
            }
        }
        sheet = "first"
        onNodeWithText("first").assertExists()

        sheet = "second"
        onNodeWithText("second").assertExists()
        onNodeWithText("first", substring = true).assertDoesNotExist()
        runOnIdle { assertEquals(listOf("first", "second"), composed) }
    }
}
