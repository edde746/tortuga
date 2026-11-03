package com.edde746.tortuga

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.edde746.tortuga.media.AndroidMediaControls
import com.edde746.tortuga.mpv.MpvVideoView
import com.edde746.tortuga.presentation.player.MediaCommand
import com.edde746.tortuga.presentation.player.NowPlaying
import com.edde746.tortuga.strings.T

/**
 * Picture-in-picture for the playback on phones and tablets, one per activity:
 * - It enters on Home while the playback plays: by itself on API 31+ (`setAutoEnterEnabled`, on while playing), from
 *   [onUserLeaveHint] below.
 * - The window takes the picture's aspect ratio (within the system's 2.39:1 limits) and grows out of the picture's
 *   bounds (`sourceRectHint`).
 * - Its actions rewind, play or pause, and fast-forward, as the player's own buttons do ([AndroidMediaControls.perform]).
 * - The player shows only the video while it is in picture-in-picture ([AndroidPlatform.inPictureInPicture]).
 * - Closing the window (not expanding it) stops the playback.
 *
 * Main thread.
 */
internal class PictureInPicture(
    private val activity: ComponentActivity,
    private val platform: AndroidPlatform,
    private val controls: AndroidMediaControls,
) {
    /** The device offers picture-in-picture and this is no TV. */
    val supported: Boolean = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
        !platform.isTelevision

    private var nowPlaying: NowPlaying? = null
    private var aspectRatio: Rational? = null
    private var pictureBounds: Rect? = null
    private var shown: Shown? = null
    private val listener: (NowPlaying?) -> Unit = ::onNowPlaying

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val step = platform.playerConfig.steps.seekSeconds
            val command = when (intent.getIntExtra(EXTRA_ACTION, -1)) {
                ACTION_PLAY -> MediaCommand.Play
                ACTION_PAUSE -> MediaCommand.Pause
                ACTION_REWIND -> MediaCommand.SeekBy(-step)
                ACTION_FORWARD -> MediaCommand.SeekBy(step)
                else -> return
            }
            Log.i(TAG, "Picture-in-picture action: $command")
            controls.perform(command)
        }
    }

    /** From the activity's `onCreate`. */
    fun attach() {
        if (!supported) return
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(BROADCAST), ContextCompat.RECEIVER_NOT_EXPORTED)
        controls.listener = listener
        onNowPlaying(controls.nowPlaying)
    }

    /** From the activity's `onDestroy`. */
    fun detach() {
        if (!supported) return
        activity.unregisterReceiver(receiver)
        if (controls.listener === listener) controls.listener = null
    }

    /** The player's picture changed size or moved ([MpvVideoView.onPictureChanged]). */
    fun onPicture(view: MpvVideoView, width: Int, height: Int) {
        if (!supported || width <= 0 || height <= 0) return
        aspectRatio = clamp(Rational(width, height))
        // In picture-in-picture the view fills the small window; the hint is for entering from full screen.
        if (!activity.isInPictureInPictureMode) pictureBounds = view.pictureBoundsInWindow()
        update()
    }

    /** The activity's `onUserLeaveHint`: below API 31, Home while playing enters picture-in-picture. */
    fun onUserLeaveHint() {
        if (!supported || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || nowPlaying?.playing != true) return
        activity.enterPictureInPictureMode(params())
    }

    /**
     * The activity entered or left picture-in-picture. Leaving while [stopped] means the window was closed, not
     * expanded: the playback stops.
     */
    fun onModeChanged(inPictureInPicture: Boolean, stopped: Boolean) {
        platform.inPictureInPicture = inPictureInPicture
        if (!inPictureInPicture && stopped && nowPlaying != null) {
            Log.i(TAG, "Picture-in-picture closed: stopping the playback")
            controls.perform(MediaCommand.Stop)
        }
    }

    private fun onNowPlaying(nowPlaying: NowPlaying?) {
        this.nowPlaying = nowPlaying
        if (nowPlaying == null) {
            aspectRatio = null
            pictureBounds = null
        }
        update()
    }

    private fun update() {
        if (!supported) return
        val next = Shown(nowPlaying?.playing, nowPlaying?.canSeek, aspectRatio, pictureBounds)
        if (next == shown) return
        shown = next
        activity.setPictureInPictureParams(params())
    }

    private fun params(): PictureInPictureParams {
        val now = nowPlaying
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio ?: DEFAULT_ASPECT)
            .setSourceRectHint(pictureBounds)
            .setActions(if (now == null) emptyList() else actions(now))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(now?.playing == true)
            // A video cross-fades into the window; resizing it seamlessly would show a stretched frame.
            builder.setSeamlessResizeEnabled(false)
        }
        return builder.build()
    }

    private fun actions(now: NowPlaying): List<RemoteAction> {
        val playPause = if (now.playing) {
            action(ACTION_PAUSE, R.drawable.ic_media_pause, T.player.pause)
        } else {
            action(ACTION_PLAY, R.drawable.ic_media_play, T.player.play)
        }
        if (!now.canSeek || activity.maxNumPictureInPictureActions < 3) return listOf(playPause)
        return listOf(
            action(ACTION_REWIND, R.drawable.ic_media_rewind, T.nav.player.rewind),
            playPause,
            action(ACTION_FORWARD, R.drawable.ic_media_forward, T.nav.player.fastForward),
        )
    }

    private fun action(code: Int, icon: Int, label: String): RemoteAction {
        val intent = Intent(BROADCAST).setPackage(activity.packageName).putExtra(EXTRA_ACTION, code)
        val pending = PendingIntent.getBroadcast(activity, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(Icon.createWithResource(activity, icon), label, label, pending)
    }

    /** What the window's parameters were last built from. */
    private data class Shown(val playing: Boolean?, val canSeek: Boolean?, val aspectRatio: Rational?, val bounds: Rect?)

    private companion object {
        const val TAG = "PictureInPicture"
        const val BROADCAST = "com.edde746.tortuga.PICTURE_IN_PICTURE"
        const val EXTRA_ACTION = "action"
        const val ACTION_PLAY = 1
        const val ACTION_PAUSE = 2
        const val ACTION_REWIND = 3
        const val ACTION_FORWARD = 4
        val DEFAULT_ASPECT = Rational(16, 9)

        /** The system accepts 1:2.39 to 2.39:1. */
        fun clamp(ratio: Rational): Rational = when {
            ratio.toFloat() > 2.39f -> Rational(239, 100)
            ratio.toFloat() < 1 / 2.39f -> Rational(100, 239)
            else -> ratio
        }
    }
}
