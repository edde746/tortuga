package com.edde746.tortuga.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.edde746.tortuga.MainActivity
import com.edde746.tortuga.presentation.player.MediaCommand
import com.edde746.tortuga.presentation.player.MediaCommandHandler
import com.edde746.tortuga.presentation.player.MediaControlsHost
import com.edde746.tortuga.presentation.player.NowPlaying
import com.edde746.tortuga.presentation.player.NowPlayingStatus
import kotlin.math.roundToLong

/**
 * The OS media controls on Android: the platform's [MediaSession] with the shared [NowPlaying] and its
 * media notification ([PlaybackNotification], shown by [PlaybackService]) while a playback runs: the shade, the lock
 * screen, Android TV's now-playing card, and media buttons from headsets, Bluetooth and remotes when the app's window
 * doesn't take them itself. The session's commands (its callback, the notification's actions) become [MediaCommand]s
 * for the playback. The playback's audio focus and noisy handling ([PlaybackAudioFocus]) follow the same state. While
 * the display switches mode for the playback ([displaySwitching]) HDMI renegotiates, and the audio becoming noisy as
 * its audio route drops is the switch's, not the viewer's: it doesn't pause, so the playback plays on once the display
 * has settled. Every other pause (the session's, a loss of audio focus) pauses as usual.
 *
 * The service is in the foreground while the playback plays and for [LINGER_MS] after it stops playing, so that it
 * can play again from the background (Android 12+ lets an app in the background into the foreground only on a user's
 * action); after that the notification stays, dismissable, and Android may stop the idle service: the next play
 * starts it again. Android 8+ refuses to start a service while the app is in the background (a watch-together room
 * opens its new media while the app is away): the session then goes on without the notification until the app is in
 * front again ([onForeground]) or a play comes from the session's controls (media buttons and the notification's
 * actions come with Android's leave to start one).
 *
 * One per process ([com.edde746.tortuga.AndroidPlatform]); a session exists from a playback's first [show] to its
 * [hide]. [listener] (picture-in-picture) hears every state. Main thread.
 *
 * @param seekStepSeconds the player's seek step, for the session's fast forward and rewind.
 */
internal class AndroidMediaControls(context: Context, private val seekStepSeconds: Double) : MediaControlsHost {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var session: MediaSession? = null
    private var commands: MediaCommandHandler? = null
    private val focus = PlaybackAudioFocus(this.context, { displaySwitching }, ::perform)
    private val notification = PlaybackNotification(this.context)
    private var service: PlaybackService? = null

    /** A start of [PlaybackService] went out and it hasn't come up ([attach]) yet. */
    private var serviceStarting = false
    private var lingering = false
    private val lingered = Runnable {
        lingering = false
        showNotification()
    }
    private var artworkUrl: String? = null
    private var artwork: Bitmap? = null
    private var artworkRequest: Disposable? = null
    private var metadata: Metadata? = null
    private val open = PendingIntent.getActivity(
        this.context,
        0,
        Intent(this.context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = perform(MediaCommand.Play)
        override fun onPause() = perform(MediaCommand.Pause)
        override fun onStop() = perform(MediaCommand.Stop)
        override fun onSeekTo(pos: Long) = perform(MediaCommand.SeekTo(pos.coerceAtLeast(0) / 1000.0))
        override fun onFastForward() = perform(MediaCommand.SeekBy(seekStepSeconds))
        override fun onRewind() = perform(MediaCommand.SeekBy(-seekStepSeconds))
        override fun onSkipToNext() = perform(MediaCommand.Next)
        override fun onSkipToPrevious() = perform(MediaCommand.Previous)
    }

    /** What the controls show now; null between playbacks. */
    var nowPlaying: NowPlaying? = null
        private set

    /** Hears each [NowPlaying] of a playback and, with null, its end. */
    var listener: ((NowPlaying?) -> Unit)? = null

    override fun show(nowPlaying: NowPlaying, commands: MediaCommandHandler) {
        val wasPlaying = this.nowPlaying?.playing == true
        this.nowPlaying = nowPlaying
        this.commands = commands
        val started = session == null
        val session = session ?: start()
        if (nowPlaying.playing) {
            handler.removeCallbacks(lingered)
            lingering = false
        } else if (wasPlaying) {
            lingering = true
            handler.postDelayed(lingered, LINGER_MS)
        }
        if (started || (nowPlaying.playing && !wasPlaying)) startService()
        session.setPlaybackState(state(nowPlaying))
        loadArtwork(nowPlaying.artworkUrl)
        publish()
        session.isActive = true
        focus.update(nowPlaying.playing)
        listener?.invoke(nowPlaying)
    }

    override fun hide() {
        val session = session ?: return
        this.session = null
        service = null
        serviceStarting = false
        nowPlaying = null
        commands = null
        handler.removeCallbacks(lingered)
        lingering = false
        artworkRequest?.dispose()
        artworkRequest = null
        artworkUrl = null
        artwork = null
        metadata = null
        focus.release()
        context.stopService(Intent(context, PlaybackService::class.java))
        notification.cancel()
        session.release()
        listener?.invoke(null)
        Log.i(TAG, "Media session released")
    }

    /** A command from outside the session (picture-in-picture and the notification's actions): the same way in. */
    fun perform(command: MediaCommand) {
        Log.i(TAG, "Media command: $command")
        commands?.perform(command)
    }

    /** The display is switching mode for the playback, until it has settled (the playback's output says so). */
    var displaySwitching = false

    /**
     * The app's task was removed (swiped away from Recents) while the service ran: nothing is left to show the
     * playback, so it stops. If the player can't close (a dev session's direct play has no screen to return to), it
     * pauses, the system's pause rather than the viewer's, and the controls go.
     */
    fun onTaskRemoved() {
        val commands = commands ?: return
        commands.perform(MediaCommand.Stop)
        if (session != null) {
            commands.perform(MediaCommand.SystemPause)
            hide()
        }
    }

    /** The app is in front ([com.edde746.tortuga.MainActivity.onStart]): a playback without its service gets it now. */
    fun onForeground() {
        if (session != null) startService()
    }

    /** [PlaybackService] came up: it shows the notification from now on, or stops if the playback has already ended. */
    fun attach(service: PlaybackService) {
        serviceStarting = false
        if (session == null) {
            service.stopSelf()
            return
        }
        this.service = service
        showNotification()
    }

    fun detach(service: PlaybackService) {
        if (this.service === service) this.service = null
    }

    private fun start(): MediaSession {
        notification.ensureChannel()
        val session = MediaSession(context, TAG)
        session.setSessionActivity(open)
        // A film's soundtrack: the volume keys change the media stream.
        session.setPlaybackToLocal(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        session.setCallback(callback, handler)
        this.session = session
        Log.i(TAG, "Media session started")
        return session
    }

    /**
     * Starts [PlaybackService], which shows the notification once up, unless it runs or is on its way. Android 8+
     * refuses the start while the app is in the background (an [IllegalStateException]; from Android 12
     * `BackgroundServiceStartNotAllowedException`): the session goes on without it until the next try.
     */
    private fun startService() {
        if (service != null || serviceStarting) return
        try {
            context.startService(Intent(context, PlaybackService::class.java))
            serviceStarting = true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Android refused the playback service while the app is in the background; the session goes on without it", e)
        }
    }

    /** The metadata and the notification as [nowPlaying] and the artwork are now. */
    private fun publish() {
        val session = session ?: return
        val now = nowPlaying ?: return
        val durationMs = if (now.durationSeconds > 0) (now.durationSeconds * 1000).roundToLong() else -1
        val next = Metadata(now.title, now.subtitle, durationMs, artworkUrl, artwork)
        if (next != metadata) {
            metadata = next
            val builder = MediaMetadata.Builder()
                .putText(MediaMetadata.METADATA_KEY_TITLE, now.title)
                .putText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, now.title)
                .putText(MediaMetadata.METADATA_KEY_ARTIST, now.subtitle)
                .putText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, now.subtitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            artworkUrl?.let { url -> ARTWORK_URIS.forEach { builder.putString(it, url) } }
            artwork?.let { bitmap -> ARTWORK_BITMAPS.forEach { builder.putBitmap(it, bitmap) } }
            session.setMetadata(builder.build())
        }
        showNotification()
    }

    private fun showNotification() {
        val service = service ?: return
        val session = session ?: return
        val now = nowPlaying ?: return
        service.show(notification.build(now, artwork, session.sessionToken, open), now.playing || lingering)
    }

    /** Loads [url] (the UI's image loader: its cache, the app's HTTP client) for the metadata and the notification. */
    private fun loadArtwork(url: String?) {
        if (url == artworkUrl) return
        artworkRequest?.dispose()
        artworkUrl = url
        artwork = null
        artworkRequest = url?.let {
            val size = (ARTWORK_DP * context.resources.displayMetrics.density).toInt()
            val request = ImageRequest.Builder(context)
                .data(it)
                .size(size)
                // Parcelled into the session's metadata and the notification.
                .allowHardware(false)
                .listener(onError = { _, result -> Log.w(TAG, "The artwork didn't load: ${result.throwable::class.simpleName}") })
                .target(onSuccess = { image ->
                    artwork = image.toBitmap()
                    publish()
                })
                .build()
            SingletonImageLoader.get(context).enqueue(request)
        }
    }

    /** What the session's metadata was last built from. */
    private data class Metadata(val title: String, val subtitle: String?, val durationMs: Long, val artworkUrl: String?, val artwork: Bitmap?)

    private companion object {
        const val TAG = "MediaControls"

        /** How long the service stays in the foreground once the playback stops playing: a pause, not for good. */
        const val LINGER_MS = 10 * 60 * 1000L

        /** The largest artwork the system keeps in a session's metadata (`config_mediaMetadataBitmapMaxSize`). */
        const val ARTWORK_DP = 320

        val ARTWORK_URIS = listOf(MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val ARTWORK_BITMAPS = listOf(MediaMetadata.METADATA_KEY_DISPLAY_ICON, MediaMetadata.METADATA_KEY_ALBUM_ART)

        fun state(now: NowPlaying): PlaybackState {
            var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
            if (now.canSeek) actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
            if (now.canSkipPrevious) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
            if (now.canSkipNext) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
            val state = when (now.status) {
                NowPlayingStatus.BUFFERING -> PlaybackState.STATE_BUFFERING
                NowPlayingStatus.PLAYING -> PlaybackState.STATE_PLAYING
                NowPlayingStatus.PAUSED -> PlaybackState.STATE_PAUSED
                NowPlayingStatus.STOPPED -> PlaybackState.STATE_STOPPED
            }
            // While it plays, the system carries the position on at this speed from now.
            val speed = if (now.status == NowPlayingStatus.PLAYING) now.rate.toFloat() else 0f
            return PlaybackState.Builder()
                .setActions(actions)
                .setState(state, (now.positionSeconds * 1000).roundToLong(), speed, SystemClock.elapsedRealtime())
                .build()
        }
    }
}
