package com.edde746.tortuga.players

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.playback.players.AndroidExtra
import com.edde746.tortuga.playback.players.AndroidPlayer
import com.edde746.tortuga.playback.players.AndroidPlayerApi
import com.edde746.tortuga.playback.players.AndroidPlayers
import com.edde746.tortuga.playback.players.AndroidSubtitle
import com.edde746.tortuga.playback.players.CustomPlayerKind
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.players.PlayerAppEvent
import com.edde746.tortuga.playback.players.PlayerAppEvents
import com.edde746.tortuga.playback.players.PlayerAppFailure
import com.edde746.tortuga.playback.players.PlayerAppKind
import com.edde746.tortuga.playback.players.PlayerAppLaunch
import com.edde746.tortuga.playback.players.PlayerAppLauncher
import com.edde746.tortuga.playback.players.PlayerAppMedia
import com.edde746.tortuga.playback.players.PlayerAppProgress
import com.edde746.tortuga.playback.players.PlayerAppsFound
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Player apps on Android phones, tablets and TVs (`AppPlatform.playerApps`), one per process (AndroidPlatform):
 *
 * - **Found** ([detect]): every app with an activity for `ACTION_VIEW` of any video type ([VIDEO]) on an http URL (the
 *   manifest's `<queries>` make them visible), once each, by its label, not Tortuga; then the system chooser ("Ask
 *   every time"). A custom player is a package name, started as the app it names is ([AndroidPlayers.of]).
 * - **Started** ([launch]): `ACTION_VIEW` of any video type for the app's package (MX Player: its activity class,
 *   which it returns a result to) with the extras of its intent interface ([AndroidPlayers]). A download's file and
 *   its saved subtitles go as `content://` URIs of [PlayerFiles] with a read grant. The grant reaches the intent's
 *   data and its ClipData, not its extras, so every shared URI rides in the ClipData too; the chooser
 *   passes the ClipData's grant on to the app the user picks.
 * - **Reported back:** an app whose interface answers is started for a result through the activity's launcher
 *   ([attach]), registered before the activity starts so a recreated activity's launcher gets the result. One launch
 *   at a time: a new one ends the previous one's [PlayerAppEvents] with nothing known, and so does the activity
 *   finishing before the player answered. Kodi, Nova and the chooser report nothing.
 *
 * Never logs the URL, the extras or the headers.
 */
internal class AndroidPlayerApps(context: Context) : PlayerAppLauncher {
    private val context: Context = context.applicationContext

    /** The activity showing the app and its result launcher, while one does. Main thread. */
    private var activity: ComponentActivity? = null
    private var results: ActivityResultLauncher<Intent>? = null

    /** The launch whose result is awaited. Main thread. */
    private var pending: Pending? = null

    private val icons = ConcurrentHashMap<String, LoadedIcon>()

    override val customPlayers: CustomPlayerKind = CustomPlayerKind.PACKAGE

    override val handsOffFiles: Boolean = true

    /**
     * Registers [activity]'s result launcher, in its `onCreate` (before it starts): results come back to it, also
     * those of a launch an earlier activity made.
     *
     * Lint's fragment check fires on libraries that ask for androidx.fragment before 1.3.0 (1.5.7 resolves), and is
     * about a FragmentActivity's request codes: the activity is a plain ComponentActivity.
     */
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    fun attach(activity: ComponentActivity) {
        this.activity = activity
        results = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult(), ::onResult)
    }

    /**
     * [activity] is destroyed. Recreated, the next activity's launcher gets the result; finishing, no result comes and
     * the launch ends with nothing known.
     */
    fun detach(activity: ComponentActivity) {
        if (this.activity !== activity) return
        this.activity = null
        results = null
        if (activity.isFinishing) end()
    }

    override fun detect(done: PlayerAppsFound) {
        thread(name = "PlayerApps.detect", isDaemon = true) {
            val found = try {
                installed()
            } catch (e: RuntimeException) {
                Log.w(TAG, e) { "The package manager didn't answer" }
                emptyList()
            }
            Log.i(TAG) { "Found ${found.size} player apps: ${found.joinToString { it.id }}" }
            done.onFound(found + PlayerApp(PlayerApp.CHOOSER_ID, T.playerApps.chooser, PlayerAppKind.CHOOSER, AndroidPlayers.chooserCapabilities))
        }
    }

    private fun installed(): List<PlayerApp> {
        val packages = context.packageManager
        return packages.queryIntentActivities(PROBE, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { app ->
                val name = app.loadLabel(packages).toString().ifBlank { app.packageName }
                PlayerApp(app.packageName, name, PlayerAppKind.DETECTED, AndroidPlayers.of(app.packageName).api.capabilities)
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    override suspend fun launch(app: PlayerApp, media: PlayerAppMedia, events: PlayerAppEvents): PlayerAppLaunch {
        // The previous player hasn't answered, and nothing listens for its answer any more.
        end()
        val activity = activity ?: return PlayerAppLaunch.Failed(PlayerAppFailure.OTHER)
        val player = when (app.kind) {
            PlayerAppKind.CHOOSER -> null
            PlayerAppKind.DETECTED -> AndroidPlayers.of(app.id)
            PlayerAppKind.CUSTOM -> app.custom?.command?.trim()?.takeIf { it.isNotEmpty() }?.let(AndroidPlayers::of)
                ?: return PlayerAppLaunch.Failed(PlayerAppFailure.NOT_INSTALLED)
        }
        val intent = try {
            viewIntent(player?.api ?: AndroidPlayerApi.GENERIC, media)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG) { "The download's file can't be shared: ${e.javaClass.simpleName}" }
            return PlayerAppLaunch.Failed(PlayerAppFailure.OTHER)
        }
        if (player == null) {
            return try {
                activity.startActivity(Intent.createChooser(intent, null))
                Log.i(TAG) { "Opened the chooser" }
                PlayerAppLaunch.Launched(PlayerAppProgress.NONE)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, e) { "No chooser" }
                PlayerAppLaunch.Failed(PlayerAppFailure.OTHER)
            }
        }
        return start(activity, player, intent, events)
    }

    /** Starts [player]: its activity class first where it names one, else (or when that is gone) its package. */
    private fun start(activity: ComponentActivity, player: AndroidPlayer, intent: Intent, events: PlayerAppEvents): PlayerAppLaunch {
        val progress = player.api.capabilities.progress
        val results = if (progress == PlayerAppProgress.ON_RETURN) results ?: return PlayerAppLaunch.Failed(PlayerAppFailure.OTHER) else null
        intent.setPackage(player.packageName)
        val explicit = player.activity?.let { Intent(intent).setClassName(player.packageName, it) }
        for (candidate in listOfNotNull(explicit, intent)) {
            try {
                if (results != null) results.launch(candidate) else activity.startActivity(candidate)
            } catch (_: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                Log.w(TAG, e) { "${player.packageName} refused the launch" }
                return PlayerAppLaunch.Failed(PlayerAppFailure.OTHER)
            }
            if (results != null) pending = Pending(player, events)
            Log.i(TAG) { "Started ${player.packageName} (${player.api}, ${if (candidate === explicit) "its activity" else "its package"})" }
            return PlayerAppLaunch.Launched(progress)
        }
        val installed = isInstalled(player.packageName)
        Log.w(TAG) { "${player.packageName} ${if (installed) "has no activity for the video" else "isn't installed"}" }
        return PlayerAppLaunch.Failed(if (installed) PlayerAppFailure.OTHER else PlayerAppFailure.NOT_INSTALLED)
    }

    /**
     * `ACTION_VIEW` of [media] as any video type ([VIDEO]) with [api]'s extras. A download's file and its saved
     * subtitles are shared through [PlayerFiles]; a subtitle it refuses is left out, the video it refuses throws
     * IllegalArgumentException.
     */
    private fun viewIntent(api: AndroidPlayerApi, media: PlayerAppMedia): Intent {
        val video = if (media.local) share(File(media.url)) else Uri.parse(media.url)
        val subtitles = if (!api.capabilities.subtitles) emptyList() else media.subtitles.mapNotNull { subtitle ->
            val uri = if (!subtitle.local) {
                Uri.parse(subtitle.url)
            } else {
                try {
                    share(File(subtitle.url))
                } catch (e: IllegalArgumentException) {
                    // One subtitle lost doesn't cost the play.
                    Log.w(TAG) { "A saved subtitle can't be shared: ${e.javaClass.simpleName}" }
                    null
                }
            }
            uri?.let { AndroidSubtitle(it.toString(), subtitle) }
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(video, VIDEO)
        for (extra in AndroidPlayers.extras(api, media, subtitles, T.locale)) intent.put(extra)
        val shared = (listOf(video) + subtitles.map { Uri.parse(it.uri) }).filter { it.scheme == ContentResolver.SCHEME_CONTENT }
        if (shared.isNotEmpty()) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(null, shared.first()).apply { shared.drop(1).forEach { addItem(ClipData.Item(it)) } }
        }
        return intent
    }

    private fun share(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)

    private fun Intent.put(extra: AndroidExtra) {
        when (extra) {
            is AndroidExtra.Text -> putExtra(extra.key, extra.value)
            is AndroidExtra.Texts -> putExtra(extra.key, extra.values.toTypedArray())
            is AndroidExtra.IntValue -> putExtra(extra.key, extra.value)
            is AndroidExtra.LongValue -> putExtra(extra.key, extra.value)
            is AndroidExtra.Flag -> putExtra(extra.key, extra.value)
            is AndroidExtra.Uris -> putExtra(extra.key, Array<Parcelable>(extra.uris.size) { Uri.parse(extra.uris[it]) })
        }
    }

    private fun onResult(result: ActivityResult) {
        val pending = pending ?: return
        this.pending = null
        val data = result.data
        val extras = try {
            data?.extras?.let { bundle -> bundle.keySet().associateWith { @Suppress("DEPRECATION") bundle.get(it) } }.orEmpty()
        } catch (e: RuntimeException) {
            // A value of a class only the player has.
            Log.w(TAG) { "${pending.player.packageName}'s result can't be read: ${e.javaClass.simpleName}" }
            emptyMap()
        }
        val ended = AndroidPlayers.ended(pending.player.api, data?.action, result.resultCode, extras)
        Log.i(TAG) {
            "${pending.player.packageName} returned ${result.resultCode}: position ${if (ended.positionMillis != null) "known" else "unknown"}, completed ${ended.completed}"
        }
        pending.events.onEvent(ended)
    }

    /** Ends the awaited launch, if any, with nothing known. */
    private fun end() {
        val pending = pending ?: return
        this.pending = null
        pending.events.onEvent(PlayerAppEvent.Ended(null, null, completed = false))
    }

    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * [playerId]'s launcher icon in [modifier]'s bounds: an installed app's (a detected player's id is its package);
     * nothing for the chooser and the custom players. Loaded off the main thread once, then kept.
     */
    @Composable
    fun Icon(playerId: String, modifier: Modifier) {
        val icon by produceState(icons[playerId]?.bitmap, playerId) {
            value = withContext(Dispatchers.IO) { loadIcon(playerId) }
        }
        icon?.let { Image(it, contentDescription = null, modifier = modifier) }
    }

    private fun loadIcon(playerId: String): ImageBitmap? = icons.getOrPut(playerId) {
        val bitmap = if (playerId == PlayerApp.CHOOSER_ID || playerId.startsWith(PlayerApp.CUSTOM_PREFIX)) {
            null
        } else {
            try {
                val size = (ICON_DP * context.resources.displayMetrics.density).roundToInt()
                context.packageManager.getApplicationIcon(playerId).toBitmap(size, size).asImageBitmap()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
        LoadedIcon(bitmap)
    }.bitmap

    private class Pending(val player: AndroidPlayer, val events: PlayerAppEvents)

    /** A player's icon, or that it has none (a map holds no null). */
    private class LoadedIcon(val bitmap: ImageBitmap?)

    private companion object {
        const val TAG = "PlayerApps"
        const val VIDEO = "video/*"

        /** [PlayerFiles]' authority after the application id (the manifest's `${applicationId}.playerfiles`). */
        const val AUTHORITY_SUFFIX = ".playerfiles"

        /** The icon's bitmap size: the Player page's and the menus' rows draw it at most this large. */
        const val ICON_DP = 48

        /** What [detect] asks the package manager for: a video at an http URL, as [launch] hands a stream over. */
        val PROBE: Intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("http://localhost/video"), VIDEO)
    }
}

/**
 * Shares a download's file and its saved subtitles with the player app it is handed to: only those, each to that app
 * alone, read-only (`res/xml/player_files.xml` names the download directories). Its own class, so no library's
 * FileProvider entry in the merged manifest collides with it.
 */
class PlayerFiles : FileProvider()
