package com.edde746.tortuga

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration

/**
 * The player's orientation on a phone, one per activity. While the player shows on a phone the activity asks for
 * landscape (`SCREEN_ORIENTATION_USER_LANDSCAPE`): on either side as the device is held while auto-rotate is on, and
 * on the side the user's rotation lock holds while it is off, never turned by the sensor against the lock. A video
 * taller than wide, its rotation counted ([videoShown]: the shared
 * [com.edde746.tortuga.playback.VideoInfo.tall]), gives the lock up: the activity asks again for what it asked before
 * the player (the manifest's `UNSPECIFIED`), so the screen follows the device as the user's auto-rotate setting has it,
 * and stays where the user locked it. Leaving the player asks for that too.
 *
 * A phone is a screen under 600 dp at its smallest (`smallestScreenWidthDp`, Android's line for tablets), read again
 * at each configuration change: unfolding the Fold with the player up gives the lock up on its inner screen, folding
 * it takes the lock again on the cover screen. A TV has no orientation to ask for. Nothing is asked for in picture in
 * picture, whose window has none; what the player wants then is asked for once the activity fills the screen again.
 *
 * The player's video surfaces report in ([playerShown], [playerHidden]); the request for the earlier orientation waits
 * for the frame after the last one left, since the next playback's player takes the last one's place in the same frame
 * and keeps the lock without a turn between them. Main thread.
 */
internal class PlayerOrientation(private val activity: Activity, private val television: Boolean) {
    private var players = 0
    private var tall = false
    private var phone = isPhone(activity.resources.configuration)
    private var inPictureInPicture = false

    /** What the activity asked for before the player, from the first player shown until it has been asked for again. */
    private var before: Int? = null
    private val updateLater = Runnable { update() }

    fun playerShown() {
        players++
        if (before == null) before = activity.requestedOrientation
        activity.window.decorView.removeCallbacks(updateLater)
        update()
    }

    fun playerHidden() {
        players--
        if (players == 0) tall = false
        activity.window.decorView.post(updateLater)
    }

    /** The playing video's picture is taller than wide ([tall]), or isn't (false while none is up). */
    fun videoShown(tall: Boolean) {
        if (tall == this.tall) return
        this.tall = tall
        update()
    }

    fun onConfigurationChanged(configuration: Configuration) {
        val phone = isPhone(configuration)
        if (phone == this.phone) return
        this.phone = phone
        update()
    }

    fun onPictureInPictureChanged(inPictureInPicture: Boolean) {
        this.inPictureInPicture = inPictureInPicture
        update()
    }

    private fun update() {
        val before = before ?: return
        if (inPictureInPicture) return
        val wanted = if (players > 0 && phone && !tall) ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE else before
        if (players == 0) this.before = null
        if (activity.requestedOrientation != wanted) activity.requestedOrientation = wanted
    }

    private fun isPhone(configuration: Configuration): Boolean =
        !television && configuration.smallestScreenWidthDp < 600
}
