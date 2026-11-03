package com.edde746.tortuga.ui.focus

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A scrolling block of text with no control in it (the player's info card, an addon's install preview) as a stop for
 * the remote while it scrolls: a ring on its [shape] while it has focus, and Up and Down scroll it by half its height
 * while it can go that way; at its top or bottom they move focus on, as from any control. Place it on the block only
 * while its content is taller than its room, before the block's scroll.
 */
fun Modifier.remoteScroll(scroll: ScrollState, scope: CoroutineScope, shape: Shape): Modifier =
    focusGrowth(shape, ring = true, grows = false)
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val by = when (event.key) {
                Key.DirectionDown -> halfDown(scroll, scroll.viewportSize)
                Key.DirectionUp -> halfUp(scroll, scroll.viewportSize)
                else -> return@onKeyEvent false
            }
            if (by != 0f) scope.launch { scroll.animateScrollBy(by) }
            by != 0f
        }
        .focusable()

/**
 * A scrolling column with controls among its text (an addon's details: its switches above its facts) on a remote: Up
 * and Down from one of its controls move focus to the next control of the column that way, as ever; where none is
 * (past its first or last) while the column can still scroll that way, they scroll it by half its height instead and
 * focus stays, so the lines before its first control and after its last come into view. At its top or bottom (or
 * while it fits its room) focus moves on. Place it before the column's scroll.
 */
@Composable
fun Modifier.scrollPastControls(scroll: ScrollState): Modifier = scrollPastControls(scroll) { scroll.viewportSize }

/** [scrollPastControls] for a lazy column: place it on the column itself. */
@Composable
fun Modifier.scrollPastControls(list: LazyListState): Modifier = scrollPastControls(list) { list.layoutInfo.viewportSize.height }

@Composable
private fun Modifier.scrollPastControls(scroll: ScrollableState, viewport: () -> Int): Modifier {
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    return onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        val (direction, by) = when (event.key) {
            Key.DirectionDown -> FocusDirection.Down to halfDown(scroll, viewport())
            Key.DirectionUp -> FocusDirection.Up to halfUp(scroll, viewport())
            else -> return@onKeyEvent false
        }
        if (by == 0f) return@onKeyEvent false
        // A move out of the column is cancelled below, and one finding nothing that way fails: either way, the scroll.
        if (!focus.moveFocus(direction)) scope.launch { scroll.animateScrollBy(by) }
        true
    }.focusProperties {
        onExit = {
            val scrolls = when (requestedFocusDirection) {
                FocusDirection.Down -> scroll.canScrollForward
                FocusDirection.Up -> scroll.canScrollBackward
                else -> false
            }
            if (scrolls) cancelFocusChange()
        }
    }.focusGroup()
}

/** Half the [viewport] down, or 0 at the end. */
private fun halfDown(scroll: ScrollableState, viewport: Int): Float = if (scroll.canScrollForward) viewport / 2f else 0f

/** Half the [viewport] up, or 0 at the start. */
private fun halfUp(scroll: ScrollableState, viewport: Int): Float = if (scroll.canScrollBackward) -viewport / 2f else 0f
