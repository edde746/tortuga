package com.edde746.tortuga

import android.app.Activity
import android.app.UiModeManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.edde746.tortuga.app.BundledText
import com.edde746.tortuga.app.PlatformPlayback
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.media.AndroidMediaControls
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.mpv.MpvPlayerEngine
import com.edde746.tortuga.mpv.MpvVideoView
import com.edde746.tortuga.net.WebUrl
import com.edde746.tortuga.output.AndroidPlaybackOutput
import com.edde746.tortuga.output.DeviceOutput
import com.edde746.tortuga.playback.HardwareDecoder
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.playback.ShaderDirectories
import com.edde746.tortuga.playback.ShaderFiles
import com.edde746.tortuga.playback.ShaderRenderer
import com.edde746.tortuga.playback.output.PlaybackOutputHost
import com.edde746.tortuga.playback.output.VideoCapabilities
import com.edde746.tortuga.players.AndroidPlayerApps
import com.edde746.tortuga.presentation.player.DeviceLevels
import com.edde746.tortuga.presentation.player.MediaControlsHost
import com.edde746.tortuga.presentation.player.PlayerConfig
import com.edde746.tortuga.presentation.settings.DeviceFeatures
import com.edde746.tortuga.ui.app.SaveResult
import com.edde746.tortuga.ui.app.TortugaPlatform
import com.edde746.tortuga.watchnext.WatchNext
import java.io.File

/**
 * The Compose app's platform on Android phones, tablets, Android TV and Fire TV, one per process
 * ([TortugaApplication]). The activity on screen attaches itself as [activity] for what needs one: launching other
 * apps, leaving, the display mode, and its [pictureInPicture]. Phones and tablets are both [FormFactor.HANDHELD]; the
 * window's size picks their layout.
 *
 * Every playback gets its own engine ([createPlayback]), shows in the media session ([mediaControls]) and follows the
 * output plan ([AndroidPlaybackOutput]).
 */
internal class AndroidPlatform(context: Context) : TortugaPlatform {
    private val context: Context = context.applicationContext

    /** The activity showing the app, while one does. */
    var activity: Activity? = null

    /** More mpv options for every engine, applied last. Only debug launches set them (test runs pass `ao=null`). */
    var mpvOptions: Map<String, String> = emptyMap()

    /** Every new engine's events (`MpvPlayerEngine`'s `events`). Only a debug launch's perf marks set them. */
    var mpvEvents: ((String) -> Unit)? = null

    override val formFactor: FormFactor = if (isTelevision(this.context)) FormFactor.TV else FormFactor.HANDHELD

    val isTelevision: Boolean get() = formFactor == FormFactor.TV

    /** The activity's picture-in-picture, while an activity shows. */
    var pictureInPicture: PictureInPicture? = null

    /** The activity's player orientation (phones turn the player to landscape), while an activity shows. */
    var playerOrientation: PlayerOrientation? = null

    /** The activity is in picture-in-picture: the player shows only its video. Compose state. */
    override var inPictureInPicture: Boolean by mutableStateOf(false)

    /** The playback asks for the display to stay awake ([PlatformPlayback.keepDisplayAwake]). Compose state. */
    private var keepScreenOn by mutableStateOf(false)

    /**
     * Touch exploration is on (TalkBack turns it on): the player keeps its controls up meanwhile. Compose state,
     * following the system's switch.
     */
    override var assistiveTechnology: Boolean by mutableStateOf(false)
        private set

    init {
        val accessibility = this.context.getSystemService(AccessibilityManager::class.java)
        assistiveTechnology = accessibility.isTouchExplorationEnabled
        accessibility.addTouchExplorationStateChangeListener { assistiveTechnology = it }
    }

    override val playerConfig: PlayerConfig = PlayerConfig.defaults(
        if (formFactor == FormFactor.TV) InputProfile.TV else InputProfile.TOUCH,
        ViewConfiguration.getDoubleTapTimeout().toLong(),
    )

    /** A phone's or tablet's brightness and media volume, for the player's swipes; a TV has no swipes. */
    override val deviceLevels: DeviceLevels? = if (isTelevision) null else AndroidDeviceLevels(this.context) { activity?.window }

    /** The media session every playback shows in (notification, lock screen, media keys). */
    val mediaControls: AndroidMediaControls by lazy { AndroidMediaControls(this.context, playerConfig.steps.seekSeconds) }

    override fun createPlayback(): PlatformPlayback {
        val mpv = MpvPlayerEngine(context, mpvOptions, mpvEvents)
        return object : PlatformPlayback {
            override val engine: PlayerEngine = mpv
            override val hardwareDecoder = HardwareDecoder.ANDROID
            override val mediaControls: MediaControlsHost = this@AndroidPlatform.mediaControls
            override val output: PlaybackOutputHost = AndroidPlaybackOutput(context, mpv, isTelevision, { activity?.window }) {
                this@AndroidPlatform.mediaControls.displaySwitching = it
            }

            override fun keepDisplayAwake(awake: Boolean) {
                keepScreenOn = awake
            }
        }
    }

    override fun deviceFeatures(): DeviceFeatures = DeviceOutput.features(context, isTelevision)

    override fun videoCapabilities(): VideoCapabilities = DeviceOutput.videoCapabilities(context)

    /** Player apps: every app that plays video, started with its intent extras; results come back to [MainActivity]. */
    override val playerApps: AndroidPlayerApps = AndroidPlayerApps(this.context)

    /** An installed player app's launcher icon; nothing for the chooser and custom players. */
    @Composable
    override fun PlayerAppIcon(playerId: String, modifier: Modifier) = playerApps.Icon(playerId, modifier)

    /**
     * mpv's video and subtitle planes ([MpvVideoView]) under the player's controls, one view per engine. The view stays
     * bound to its engine after the playback releases it, so a late `surfaceDestroyed` waits for the engine's teardown
     * to move mpv off the Surface.
     * While it is shown the system bars are hidden (immersive, back by a swipe): the player always fills the screen,
     * and the view reaches under the display cutout the app's content keeps clear of (PlayerScreen lays the video out
     * to the display's edges). The window behind it turns black meanwhile, as the video's own letterbox is, instead of
     * showing the app's background where the view doesn't cover it yet (the player fading in). The view keeps the screen
     * on while the playback asks ([keepScreenOn]): its window has `FLAG_KEEP_SCREEN_ON` only while the view is attached
     * and shown, in picture in picture too, so leaving the player or the app lets the display sleep as well. A phone
     * turns to landscape while it is shown, but for a video taller than wide ([PlayerOrientation]).
     */
    @Composable
    override fun VideoSurface(engine: PlayerEngine, modifier: Modifier) {
        val activity = LocalActivity.current
        DisposableEffect(activity) {
            val window = activity?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            val background = window?.decorView?.background
            window?.decorView?.setBackgroundColor(Color.BLACK)
            val orientation = playerOrientation
            orientation?.playerShown()
            onDispose {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                window?.decorView?.background = background
                orientation?.playerHidden()
            }
        }
        key(engine) {
            AndroidView(
                factory = {
                    MpvVideoView(it).apply {
                        setEngine(engine as MpvPlayerEngine)
                        onPictureChanged = { width, height -> pictureInPicture?.onPicture(this, width, height) }
                        onTallChanged = { tall -> playerOrientation?.videoShown(tall) }
                    }
                },
                modifier = modifier,
                update = { it.keepScreenOn = keepScreenOn },
            )
        }
    }

    /** The player always fills the screen on Android; the store never asks. */
    override fun setFullscreen(fullscreen: Boolean) = Unit

    /**
     * Opens the web link [url] in the app that handles it: YouTube links in the YouTube app where it claims them, the
     * rest in the browser ([opens]). A TV without a browser may still have YouTube, which takes `vnd.youtube:` links;
     * with no app at all it returns false, and the app shows the link as a QR code for a phone. Anything but an
     * `http`/`https` link is refused ([WebUrl.isWeb]).
     */
    override fun openUrl(url: String): Boolean {
        if (!WebUrl.isWeb(url)) {
            Log.w(TAG) { "Not opening a link with scheme ${WebUrl.scheme(url)}" }
            return false
        }
        val activity = activity ?: return false
        if (opens(activity, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) return true
        val youTube = youTubeId(url)
        if (youTube != null && start(activity, Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youTube")))) return true
        // The URL itself may carry an addon's key: log only where it points.
        Log.w(TAG) { "No app opens ${Uri.parse(url).scheme}://${Uri.parse(url).host}" }
        return false
    }

    override fun setClipboardText(text: String, sensitive: Boolean) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(null, text)
        // Android 13+ shows a copy's preview unless it is marked sensitive.
        if (sensitive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        clipboard.setPrimaryClip(clip)
    }

    /** Phones and tablets have the system's share sheet; TVs have none to speak of (the room's QR code serves there). */
    override val canShare: Boolean get() = !isTelevision

    override fun share(text: String) {
        val activity = activity ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        start(activity, Intent.createChooser(send, null))
    }

    /** The document picker (Settings → Export and import); its answers come back to [MainActivity]. */
    val files: AndroidFiles = AndroidFiles(this.context)

    /**
     * Phones and tablets. Not a TV: of the boxes checked (2026-10-11), Fire TV has no document picker, Google TV only the
     * framework's stub ("You don't have an app that can do this"), and the SHIELD's DocumentsUI can't reach its name
     * field or Save with a remote.
     */
    override val hasFileDialogs: Boolean get() = !isTelevision

    /** Android TV's Watch Next row, where the running profile's Continue Watching shows; none on phones, tablets and Fire TV ([WatchNext.create]). */
    override val homeScreen: WatchNext? = WatchNext.create(this.context)

    override fun saveFile(fileName: String, text: String, done: (SaveResult) -> Unit) = files.save(fileName, text, done)

    /**
     * The files under [folder] in the APK's assets, at any depth: an asset folder lists its entries, a file (or a path
     * that isn't there) none.
     */
    override fun bundledTexts(folder: String): List<BundledText> {
        val assets = context.assets
        fun under(path: String, relative: String): List<BundledText> {
            val entries = assets.list(path).orEmpty()
            if (entries.isEmpty()) return listOf(BundledText(relative, assets.open(path).use { it.readBytes().decodeToString() }))
            return entries.flatMap { under("$path/$it", "$relative/$it") }
        }
        return assets.list(folder).orEmpty().flatMap { under("$folder/$it", it) }.sortedBy { it.path }
    }

    override fun openFile(opened: (name: String, text: String) -> Unit, failed: () -> Unit) = files.open(opened, failed)

    /**
     * The player's shaders, run by mpv's GL vo on OpenGL ES ([ShaderRenderer.OPENGL_ES]). mpv reads files, not the APK's
     * assets, so the shipped ones ([SHADER_ASSETS]) are copied into a folder of this build's version (older versions'
     * folders deleted) before the first one runs, off the main thread; the imported ones are kept with the app's files.
     */
    override val shaderFiles: ShaderFiles = File(this.context.noBackupFilesDir, SHADER_ASSETS).let { shaders ->
        val bundled = File(shaders, BuildConfig.VERSION_CODE.toString())
        ShaderDirectories(
            ShaderRenderer.OPENGL_ES,
            bundled.path,
            File(this.context.filesDir, SHADER_ASSETS).path,
            installBundled = {
                shaders.listFiles()?.filter { it != bundled }?.forEach { it.deleteRecursively() }
                installAssets(SHADER_ASSETS, bundled)
            },
        )
    }

    /** Copies the asset folder [folder] into [target] at any depth, skipping a file already there at its size. */
    private fun installAssets(folder: String, target: File) {
        val assets = context.assets
        for (entry in assets.list(folder).orEmpty()) {
            val path = "$folder/$entry"
            val file = File(target, entry)
            if (assets.list(path).orEmpty().isNotEmpty()) {
                installAssets(path, file)
                continue
            }
            assets.open(path).use { input ->
                // An asset's stream knows its whole (uncompressed) length before a byte is read.
                if (file.length() == input.available().toLong()) return@use
                file.parentFile?.mkdirs()
                file.outputStream().use { input.copyTo(it) }
            }
        }
    }

    override fun exit() {
        activity?.finish()
    }

    /**
     * Starts [intent]; false when no app takes it, or when the system refuses it (`FileUriExposedException`,
     * `SecurityException`), which is logged by its class only: its message can carry the link.
     */
    private fun start(activity: Activity, intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (e: RuntimeException) {
        Log.w(TAG) { "The system refused to start ${intent.action}: ${e::class.simpleName}" }
        false
    }

    /**
     * Starts [view] in the app that takes it, unless only Android TV's framework stubs would ([isTvStub]). From
     * Android 11 an app that claims the link itself comes first (the system tells it from the browsers, which alone are
     * in this app's sight), then a browser where there is one; before Android 11 every app is in sight.
     */
    private fun opens(activity: Activity, view: Intent): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        start(activity, Intent(view).addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)) || (hasBrowser(activity) && start(activity, view))
    } else {
        activity.packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY).any { !isTvStub(it.activityInfo.packageName) } &&
            start(activity, view)
    }

    private companion object {
        const val TAG = "AndroidPlatform"

        /** The asset folder of the shaders the app ships, by preset (`nvscaler/`, `artcnn/`, `anime4k/`). */
        const val SHADER_ASSETS = "shaders"

        /** The video id of a `youtube.com/watch?v=` or `youtu.be/` link. */
        fun youTubeId(url: String): String? {
            val uri = Uri.parse(url)
            val host = uri.host?.removePrefix("www.")?.removePrefix("m.") ?: return null
            return when (host) {
                "youtube.com" -> uri.getQueryParameter("v")
                "youtu.be" -> uri.lastPathSegment
                else -> null
            }?.takeIf { it.isNotBlank() }
        }
    }
}

/** Android TV and Fire TV: the television UI mode. */
internal fun isTelevision(context: Context): Boolean =
    context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

/** A browser is installed (TVs often have none): an app other than Android TV's framework stubs ([isTvStub]) opens [BROWSER_PROBE]. */
internal fun hasBrowser(context: Context): Boolean =
    context.packageManager.queryIntentActivities(BROWSER_PROBE, PackageManager.MATCH_DEFAULT_ONLY).any { !isTvStub(it.activityInfo.packageName) }

/**
 * A web link as a browser takes it (the manifest's `<queries>` put the browsers in sight); made on first use, so
 * nothing here touches the framework before a browser is looked for.
 */
private val BROWSER_PROBE: Intent by lazy { Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE) }

/**
 * [packageName] is Android TV's stand-in for the apps a TV may lack (`Stubs$BrowserStub` for the browser), which takes
 * a browser's links on a TV without one only to say that no app opens them: AOSP's
 * `com.android.tv.frameworkpackagestubs`, or the `com.google.android.tv.` one some Google builds of Android TV ship.
 */
internal fun isTvStub(packageName: String): Boolean = packageName.endsWith(".frameworkpackagestubs")
