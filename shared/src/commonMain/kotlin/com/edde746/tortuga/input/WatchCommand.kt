package com.edde746.tortuga.input

import com.edde746.tortuga.watch.WatchControlMode

/**
 * What the player's watch-together controls may ask ([com.edde746.tortuga.presentation.player.PlayerWatch.execute]);
 * the lobby calls [WatchStore][com.edde746.tortuga.presentation.watch.WatchStore].
 */
sealed interface WatchCommand {
    /** Starts a room from the player, with what plays as its media. */
    data class Start(val mode: WatchControlMode) : WatchCommand

    /** A guest plays the closest file found though its duration differs. */
    data object PlayAnyway : WatchCommand

    /** A guest doesn't play the closest file found. */
    data object DontPlay : WatchCommand

    /** A guest picks its file in the Streams sheet. */
    data object ChooseStream : WatchCommand

    /** Leaves the room; a host's goes on with the next participant as host. */
    data object Leave : WatchCommand

    /** Ends the room for everyone (the host's). */
    data object End : WatchCommand

    /** The host hands the room to [peerId]. */
    data class MakeHost(val peerId: String) : WatchCommand

    /** The host removes [peerId] from the room. */
    data class Kick(val peerId: String) : WatchCommand

    /** The host sets who controls playback. */
    data class SetControlMode(val mode: WatchControlMode) : WatchCommand

    /** The host locks the room to new participants, or opens it again. */
    data class Lock(val locked: Boolean) : WatchCommand

    /** Moves this device's offset from the room by [deltaMs] ([PlayerWatchUi.offset][com.edde746.tortuga.presentation.watch.PlayerWatchUi.offset]). */
    data class NudgeOffset(val deltaMs: Long) : WatchCommand

    /** Back to no offset. */
    data object ResetOffset : WatchCommand

    /** A guest back into the room's playback ([WatchRoomUi.joinPlayback][com.edde746.tortuga.presentation.watch.WatchRoomUi.joinPlayback]), in place of what its player plays. */
    data object JoinPlayback : WatchCommand

    /** Clears [PlayerWatchUi.failure][com.edde746.tortuga.presentation.watch.PlayerWatchUi.failure] once shown. */
    data object DismissFailure : WatchCommand
}
