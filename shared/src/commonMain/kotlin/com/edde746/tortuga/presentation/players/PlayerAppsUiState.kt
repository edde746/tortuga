package com.edde746.tortuga.presentation.players

import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.strings.T

/**
 * "Play with…" (a stream row's menu, a download row's menu): plays the row once in the player picked, Tortuga or a
 * player app, whatever Settings → Player says (the setting doesn't change).
 *
 * @param title "Play with…": the submenu's title.
 * @param entries Tortuga first, then every player app on this device ([com.edde746.tortuga.playback.players.PlayerApp]:
 *   detected, custom, Android's chooser); each entry's [PlayWithEntry.playerId] is what the store's `playWith` takes.
 */
data class PlayWithMenu(val title: String, val entries: List<PlayWithEntry>)

/** One player of a [PlayWithMenu]: "Tortuga", "VLC", "mpv". */
data class PlayWithEntry(val playerId: String, val label: String)

/** "Play with…" over this device's player apps [players]: Tortuga first; null when there are none. */
internal fun playWithMenu(players: List<PlayerApp>): PlayWithMenu? {
    if (players.isEmpty()) return null
    val tortuga = PlayWithEntry(PlayerApp.TORTUGA_ID, T.playerApps.tortuga)
    return PlayWithMenu(T.playerApps.playWith, listOf(tortuga) + players.map { PlayWithEntry(it.id, it.name) })
}
