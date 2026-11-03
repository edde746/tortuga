package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.WatchCommand
import com.edde746.tortuga.presentation.watch.PlayerWatchUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Watch together as the player store sees it ([PlayerStore.attachWatch]): what the player shows of the room, the
 * room's toasts for its OSD, and where the viewer's play, pause, seeks and speed changes go while this player follows
 * a room ([routesControls]).
 */
interface PlayerWatch {
    /** What the player shows: null hides watch together (a build or session without it). */
    val state: StateFlow<PlayerWatchUi?>

    /** Toasts ("Sam paused"), shown as the player's OSD. */
    val notices: Flow<String>

    /** This player is attached to a room: play and pause go to the room ([play], [pause]), which commands the player. */
    val routesControls: Boolean

    /** The room's speed, which the player shows while the room nudges its own; null outside a room. */
    val roomRate: Double?

    /** This player paused on this device alone ([stopHere]) and stays out of the room's playback until [resumeHere]. */
    val stoppedHere: Boolean

    /**
     * The playback stopped on this device for one who doesn't control the room's playback (a call, the audio output
     * gone, the sleep timer): the player leaves the room's playback, which plays on without it.
     */
    fun stopHere()

    /** Back from [stopHere]: the player joins the room's playback again, and the room catches it up. */
    fun resumeHere()

    /** The viewer pressed play: the room starts everyone together. */
    fun play()

    /** The viewer pressed pause: the room pauses everyone. */
    fun pause()

    /** The viewer moved the position (a seek, a scrub, a chapter). */
    fun onLocalSeek(seconds: Double)

    /** The viewer changed the speed. */
    fun onLocalRate(rate: Double)

    fun execute(command: WatchCommand)
}
