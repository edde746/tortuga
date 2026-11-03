package com.edde746.tortuga.ui.players

import com.edde746.tortuga.presentation.players.PlayWithMenu
import com.edde746.tortuga.ui.components.ItemMenuEntry
import com.edde746.tortuga.ui.components.ItemMenuSection

/**
 * "Play with…" ([PlayWithMenu]) as a section of a row's menu ([com.edde746.tortuga.ui.components.SectionedItemMenu]),
 * titled as the store titles it: one entry per player, Tortuga first, each named `playWith.<player id>` under the
 * menu's harness name; picking one calls [onPlayWith] with its player id. The entries are players' names (content
 * text: a custom player's is the one someone typed).
 */
internal fun PlayWithMenu.menuSection(onPlayWith: (playerId: String) -> Unit): ItemMenuSection =
    ItemMenuSection(
        title,
        entries.map { entry -> ItemMenuEntry(entry.label, "playWith.${entry.playerId}", contentLabel = true) { onPlayWith(entry.playerId) } },
    )
