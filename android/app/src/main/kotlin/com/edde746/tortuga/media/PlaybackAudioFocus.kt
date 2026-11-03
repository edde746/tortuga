package com.edde746.tortuga.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.edde746.tortuga.presentation.player.MediaCommand

/**
 * The playback's audio focus and its reaction to audio becoming noisy.
 *
 * - Playing (or about to) holds focus; a refused request (a call is up) pauses. A playback that plays again after a
 *   pause asks for focus again (a guest's Play after a loss, which the room's playback catches up): Android 12+ keeps
 *   a player that lost focus to another app muted until it asks.
 * - A permanent loss pauses and lets go. A transient loss (a call, navigation prompt) pauses and resumes on the gain if
 *   the playback was playing; the user pausing meanwhile cancels that.
 * - A loss that allows ducking is ducked by the system (API 26+): a film's dialogue stays audible under a navigation
 *   prompt, so nothing pauses for it ([AudioFocusRequest.willPauseWhenDucked] false, as Media3's ExoPlayer does for
 *   `CONTENT_TYPE_MOVIE`).
 * - The user pausing lets focus go, unless a transient loss is waiting to resume.
 * - [AudioManager.ACTION_AUDIO_BECOMING_NOISY] (headphones unplugged, Bluetooth gone) pauses while playing, except
 *   while the display switches mode for the playback ([displaySwitching]): HDMI renegotiates and its audio route drops
 *   for a moment, which is the switch's, not the viewer's, so the playback plays on once the display has settled.
 *
 * Each of these pauses is the system's, not the viewer's: it goes through [perform] as [MediaCommand.SystemPause], and
 * a resume on the gain as [MediaCommand.SystemResume], so that in a watch-together room a guest's device pauses alone
 * while the room plays on. Main thread.
 */
internal class PlaybackAudioFocus(
    private val context: Context,
    private val displaySwitching: () -> Boolean,
    private val perform: (MediaCommand) -> Unit,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(::onFocusChange, Handler(Looper.getMainLooper()))
        .build()

    /** A request is outstanding with the system (it may be lost for now, transiently). */
    private var requested = false
    private var focused = false
    private var playing = false
    private var resumeOnGain = false
    private var noisyRegistered = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY || !playing) return
            if (displaySwitching()) {
                Log.i(TAG, "Audio becoming noisy during a display switch: the switch's, not the viewer's; playing on")
                return
            }
            Log.i(TAG, "Audio becoming noisy: pausing")
            perform(MediaCommand.SystemPause)
        }
    }

    /** The playback now plays (or waits for data to play) or not. */
    fun update(playing: Boolean) {
        if (playing == this.playing) return
        this.playing = playing
        if (playing) {
            resumeOnGain = false
            if (!focused) {
                focused = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                requested = true
                if (!focused) {
                    Log.i(TAG, "Audio focus refused: pausing")
                    perform(MediaCommand.SystemPause)
                }
            }
            registerNoisy()
        } else {
            unregisterNoisy()
            if (!resumeOnGain) abandon()
        }
    }

    /** The playback ended. */
    fun release() {
        playing = false
        resumeOnGain = false
        unregisterNoisy()
        abandon()
    }

    private fun onFocusChange(change: Int) {
        Log.i(TAG, "Audio focus change $change (playing $playing)")
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                focused = true
                if (resumeOnGain) {
                    resumeOnGain = false
                    perform(MediaCommand.SystemResume)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnGain = false
                abandon()
                if (playing) perform(MediaCommand.SystemPause)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focused = false
                if (playing) {
                    resumeOnGain = true
                    perform(MediaCommand.SystemPause)
                }
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the system ducks.
        }
    }

    private fun abandon() {
        if (!requested) return
        audio.abandonAudioFocusRequest(request)
        requested = false
        focused = false
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        context.unregisterReceiver(noisy)
        noisyRegistered = false
    }

    private companion object {
        const val TAG = "AudioFocus"
    }
}
