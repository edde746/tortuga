package com.edde746.tortuga.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.edde746.tortuga.app.AppBootstrap
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.BundledText
import com.edde746.tortuga.app.PlatformPlayback
import com.edde746.tortuga.app.RootSession
import com.edde746.tortuga.app.Startup
import com.edde746.tortuga.crash.crashReports
import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.desktop.discord.DesktopDiscordPresence
import com.edde746.tortuga.desktop.linux.LinuxDisplayAwake
import com.edde746.tortuga.desktop.linux.LinuxMediaControls
import com.edde746.tortuga.desktop.linux.LinuxPlaybackOutput
import com.edde746.tortuga.desktop.linux.inFlatpak
import com.edde746.tortuga.desktop.players.DesktopPlayerApps
import com.edde746.tortuga.desktop.windows.WindowsDisplayAwake
import com.edde746.tortuga.desktop.windows.WindowsDisplayMatching
import com.edde746.tortuga.desktop.windows.WindowsMediaControls
import com.edde746.tortuga.input.InputProfile
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.mpv.DesktopWindow
import com.edde746.tortuga.mpv.MpvEngineOptions
import com.edde746.tortuga.mpv.MpvPlayerEngine
import com.edde746.tortuga.mpv.MpvVideoSurface
import com.edde746.tortuga.mpv.mpvRenderHook
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.playback.ShaderDirectories
import com.edde746.tortuga.playback.ShaderRenderer
import com.edde746.tortuga.playback.output.PlaybackOutputHost
import com.edde746.tortuga.playback.players.PlayerAppLauncher
import com.edde746.tortuga.presentation.player.BuildDiscordConfig
import com.edde746.tortuga.presentation.player.DiscordPresenceHost
import com.edde746.tortuga.presentation.player.MediaControlsHost
import com.edde746.tortuga.presentation.player.PlayerConfig
import com.edde746.tortuga.presentation.settings.DeviceFeatures
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.ui.app.SaveResult
import com.edde746.tortuga.ui.app.StartingScreen
import com.edde746.tortuga.ui.app.TortugaApp
import com.edde746.tortuga.ui.app.TortugaPlatform
import com.edde746.tortuga.ui.app.TortugaRoot
import com.edde746.tortuga.ui.crash.SentryReports
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.probe.LocalStoreProbe
import com.edde746.tortuga.ui.theme.TortugaColors
import com.edde746.tortuga.ui.theme.TortugaTheme
import io.sentry.Sentry
import java.awt.Color
import java.awt.Dimension
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val isWindows = System.getProperty("os.name").startsWith("Windows")
private val isLinux = System.getProperty("os.name").startsWith("Linux")

/**
 * The smallest content area, in dp, the window takes (a test launch's window size included; less only on a smaller
 * screen's work area): every screen is laid out for it as for the default 1280x720.
 */
internal val MinimumWindowSize = 640 to 480

/**
 * The desktop app (Windows; Linux through Xwayland) in one window, with mpv's video under the
 * Compose layer behind the player route: the product's account shell ([TortugaRoot]). See [Cli] for its options.
 */
fun main(args: Array<String>) {
    val cli = try {
        Cli.parse(args)
    } catch (e: Cli.Error) {
        System.err.println("tortuga: ${e.message}\n\n${Cli.USAGE}")
        exitProcess(2)
    }
    runApp(cli, test = null)
}

/**
 * Runs the app with [cli]'s options: the product, or what [test] asks for instead (the harness launcher's, in
 * desktop/app/src/harness: a session of its own such as a dev session's app ([TortugaApp]), a device of its own, the
 * test report). Returns once the window and the core are on their way; the UI thread fills the window.
 */
internal fun runApp(cli: Cli, test: TestLaunch?) {
    val session = test?.session
    // One app per data directory (SingleInstance): a later start, such as the one the OS makes for a stremio: link
    // while Tortuga runs, hands its link to the running app and exits, before it touches the database or starts the
    // test launch. A test session without a data directory has a database of its own in memory: any number run side
    // by side.
    val instance = (test?.dataDir?.toAbsolutePath() ?: DatabaseFactory.defaultDirectory().takeUnless { session != null })?.let { directory ->
        try {
            SingleInstance.claim(directory, cli.link) ?: exitProcess(0)
        } catch (e: IOException) {
            System.err.println("tortuga: ${e.message}")
            exitProcess(1)
        }
    }
    // Windows: Compose draws over mpv's child window through Skiko's DirectComposition swapchain. Must be set before
    // any Compose/Skiko class initializes.
    if (isWindows) System.setProperty("compose.interop.blending", "true")
    // Where Compose draws nothing (the frames before its first, a screen fading in or out, a screen after the player),
    // the window under its swapchain shows on Windows. AWT paints Skiko's canvas window with its background colour
    // (black: Compose asks for a transparent one) only while background erasing is on; Skiko turns it off unless told
    // otherwise, which left the look and feel's light grey or the video host's last pixels there.
    if (isWindows) System.setProperty("skiko.rendering.noerasebackground", "false")
    if (isLinux && !System.getProperty("java.vm.vendor").orEmpty().contains("JetBrains")) {
        System.err.println("tortuga: warning: not running on JetBrains Runtime; window resizes by the window manager may be missed. Start Tortuga with JetBrains Runtime 25, which the Flatpak bundles.")
    }
    // Every window's title bar and frame dark, whatever the system's light or dark mode, from its first frame.
    DesktopWindow.useDarkChrome()

    // The system's languages, region and caption settings, before the window or the core reads them.
    Bootstrap.reportSystemInputs()
    test?.start()
    // The window opens on the UI thread with the splash while this thread starts the shared core (the database, the
    // account), and DesktopApp fills it once the core is up: the UI thread runs the two in order. The product's start
    // begins first, so the splash can name its steps.
    val startup = if (session != null) null else Startup(test?.slowStartMillis ?: 0)
    lateinit var window: ComposeWindow
    SwingUtilities.invokeLater {
        test?.mark("windowStart")
        window = openWindow(test?.windowSize, startup, test)
        test?.mark("windowVisible")
    }
    test?.mark("bootstrap")
    val version = DesktopApp::class.java.`package`?.implementationVersion ?: "dev"
    val boot = try {
        session?.boot(version) ?: Bootstrap.product(version, checkNotNull(startup), test?.dataDir)
    } catch (e: Exception) {
        // Without the core there is no app: the window goes, so the process ends with the error.
        SwingUtilities.invokeLater { window.dispose() }
        throw e
    }
    test?.mark("bootstrapped")
    // Crash reports through Sentry's Java SDK while this device has them on: the product's to this build's endpoint, a
    // test session's to its own. They hold uncaught exceptions on any thread, and the exceptions the shared core's
    // coroutines survived; a crash in native code (libmpv) ends the JVM before any Java code runs, so none of those is
    // reported.
    SentryReports.follow(session?.crashReports(boot) ?: boot.crashReports(), boot.graph.scope) { configure ->
        Sentry.init { options -> configure(options) }
    }
    // Online or not, for the offline states and the downloads; the shared core hears it on the UI thread.
    val network = DesktopNetwork { online -> SwingUtilities.invokeLater { boot.onNetworkChanged(online, metered = false) } }
    network.start()

    SwingUtilities.invokeLater {
        test?.mark("edt")
        DesktopApp(cli, test, boot, network, startup, instance, window).show()
    }
}

/**
 * The window, with the splash ([StartingScreen], naming [startup]'s step; a test session's has none; its first frame is
 * [test]'s `firstFrame`): the theme's background (what Swing paints where the window grows before Compose catches up,
 * and Skiko's clear colour on Linux; the look and feel's light grey otherwise), a 16:9 content area (or [windowSize])
 * scaled down to fit the screen's work area (the screen without its taskbar or panels: Windows clamps a larger frame to
 * the whole screen, which puts the player's controls under the taskbar), shown and in front. The frame is sized once
 * the peer exists and its insets are known (pack() would use Compose's intrinsic size). Physical pixels on Linux, where
 * Compose applies the desktop scale itself; AWT scales on Windows. Resizing stops at [MinimumWindowSize], or at the
 * fitted size on a work area smaller than that. Closing it does nothing until [DesktopApp] takes it, and fills it.
 */
private fun openWindow(windowSize: Pair<Int, Int>?, startup: Startup?, test: TestLaunch?): ComposeWindow {
    val window = ComposeWindow()
    val scale = DesktopWindow.uiScale
    window.title = T.app.name
    window.background = Color(TortugaColors.Background.toArgb())
    window.contentPane.background = window.background
    // Packaged builds get their icon from nativeDistributions; this covers the title bar and taskbar of `run`.
    DesktopApp::class.java.getResource("/icons/tortuga.png")?.let { window.iconImage = ImageIO.read(it) }
    window.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
    window.addNotify()
    val insets = window.insets
    val frameWidth = insets.left + insets.right
    val frameHeight = insets.top + insets.bottom
    val (width, height) = windowSize ?: (1280 to 720)
    val workArea = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val fit = minOf(1f, (workArea.width - frameWidth) / (width * scale), (workArea.height - frameHeight) / (height * scale))
    val contentWidth = (width * scale * fit).roundToInt()
    val contentHeight = (height * scale * fit).roundToInt()
    val (minWidth, minHeight) = MinimumWindowSize
    window.minimumSize = Dimension(
        minOf((minWidth * scale).roundToInt(), contentWidth) + frameWidth,
        minOf((minHeight * scale).roundToInt(), contentHeight) + frameHeight,
    )
    window.setSize(contentWidth + frameWidth, contentHeight + frameHeight)
    window.setLocationRelativeTo(null)
    window.isVisible = true
    window.toFront()
    window.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            TortugaTheme { StartingScreen(startup?.state?.collectAsState()?.value?.status) }
        }
        // Marked at the next frame's start, as the report's other milestones are.
        if (test != null) LaunchedEffect(test) { withFrameNanos { }; test.mark("firstFrame") }
    }
    return window
}

/**
 * The window and everything in it. Borderless fullscreen goes through [DesktopWindow] (Win32 on Windows),
 * never AWT's exclusive fullscreen. On Windows the playbacks show in the System Media Transport Controls
 * ([WindowsMediaControls]) and switch the display's mode and HDR state ([WindowsDisplayMatching]); on Linux they show
 * through MPRIS ([LinuxMediaControls]). On both the Passthrough setting reaches each playback's mpv
 * ([WindowsDisplayMatching], [LinuxPlaybackOutput]), and with the Discord setting on, the playbacks show on Discord
 * ([DesktopDiscordPresence]). A playing video keeps the display awake ([WindowsDisplayAwake], [LinuxDisplayAwake])
 * while the window isn't minimized. The app follows the `stremio:` link it started with and the ones later starts hand
 * it ([SingleInstance]), bringing its window forward. Closing releases the playback while the window is still up (Linux
 * frees mpv's render context on the render thread, which needs the window drawing) and puts the display back, then
 * exits.
 */
private class DesktopApp(
    private val cli: Cli,
    private val test: TestLaunch?,
    private val boot: AppBootstrap,
    private val network: DesktopNetwork,
    /** The product's start, which its account shell finishes; null for a test session. */
    private val startup: Startup?,
    private val instance: SingleInstance?,
    /** The window [openWindow] opened. */
    private val window: ComposeWindow,
) {
    private val desktop = DesktopWindow(window)
    private val closing = AtomicBoolean(false)
    /** The engines whose mpv hasn't terminated yet: the playing one, and earlier ones still tearing down. */
    private val engines = CopyOnWriteArrayList<MpvPlayerEngine>()
    /** How many playbacks this run has had, which numbers their mpv logs; written on the main thread. */
    @Volatile private var playbacks = 0
    private var app: AppSession? = null
    private var root: RootSession? = null

    private val mediaControls: DesktopMediaControls? = when {
        isWindows -> WindowsMediaControls({ window.windowHandle }) { command -> test?.event("mediaCommand", mapOf("command" to command.toString())) }
        isLinux -> LinuxMediaControls(raise = { window.toFront(); window.requestFocus() }) { command ->
            test?.event("mediaCommand", mapOf("command" to command.toString()))
        }
        else -> null
    }
    private val displayMatching = if (isWindows) WindowsDisplayMatching({ window.windowHandle }) { name, detail -> test?.event(name, detail) } else null
    private val displayAwake: DesktopDisplayAwake? = when {
        isWindows -> WindowsDisplayAwake()
        isLinux -> LinuxDisplayAwake()
        else -> null
    }

    /**
     * Discord's Rich Presence as this build's Discord application ([BuildDiscordConfig]), or a dev session's
     * ([TestSession.discordApplicationId]); none without one: Settings offers no Discord switch and nothing connects.
     */
    private val discord = test?.session.let { if (it != null) it.discordApplicationId else BuildDiscordConfig.config.applicationId }
        ?.let { DesktopDiscordPresence.system(isWindows, it) }

    /**
     * The device settings this desktop offers: hardware decoding everywhere; HDR and frame-rate matching where the
     * display under the window can switch them (Windows; the Linux path has no HDR); passthrough on Windows and Linux
     * whatever the output, which says nothing there of the formats it takes undecoded. Crash reporting isn't
     * integrated. Detected when Settings first shows.
     */
    private val deviceFeatures by lazy {
        displayMatching?.features()
            ?: DeviceFeatures(hardwareDecoding = true, hdr = false, matchFrameRate = false, audioPassthrough = isLinux)
    }

    /**
     * Where mpv keeps the GPU shaders it compiled (`gpu-shader-cache-dir`), in the data directory's cache: a user shader
     * that took seconds to compile (ArtCNN's on Direct3D 11) loads at once from the next playback on. Compiled shaders
     * hold nothing of the user's, so a test session whose database is in memory shares the device's.
     */
    private val shaderCache: Path = Bootstrap.cacheDirectory(test?.dataDir ?: DatabaseFactory.defaultDirectory()).let { Path.of(it, "mpv-shaders") }

    private val platform = object : TortugaPlatform {
        override val formFactor = FormFactor.DESKTOP

        override val playerConfig = PlayerConfig.defaults(InputProfile.DESKTOP, doubleClickIntervalMillis())

        /** False while the window is minimized (the listener in [show]). */
        override var windowVisible by mutableStateOf(true)

        override fun createPlayback(): PlatformPlayback {
            engines.removeIf { it.awaitReleased(Duration.ZERO) }
            playbacks++
            // Every playback gets its own engine; a later one logs next to the first (each starts its log file anew).
            val log = cli.mpvLog?.let { if (playbacks == 1) it else it.resolveSibling("${it.fileName.toString().substringBeforeLast('.')}-$playbacks.log") }
            val mpv = MpvPlayerEngine(MpvEngineOptions(logFile = log, shaderCache = shaderCache, extraOptions = cli.mpvOptions))
            engines += mpv
            test?.onEngine(mpv, playbacks)
            return object : PlatformPlayback {
                override val engine: PlayerEngine = mpv
                override val hardwareDecoder = mpv.hardwareDecoder
                override val mediaControls: MediaControlsHost? = this@DesktopApp.mediaControls
                override val output: PlaybackOutputHost? = displayMatching?.host(mpv) ?: if (isLinux) LinuxPlaybackOutput(mpv) else null

                override fun keepDisplayAwake(awake: Boolean) {
                    displayAwake?.keepAwake(awake)
                }
            }
        }

        @Composable
        override fun VideoSurface(engine: PlayerEngine, modifier: Modifier) = MpvVideoSurface(engine as MpvPlayerEngine, window, modifier)

        override fun deviceFeatures(): DeviceFeatures = this@DesktopApp.deviceFeatures

        override val discordPresence: DiscordPresenceHost? = this@DesktopApp.discord

        /**
         * mpv, VLC and the other players found here, and programs as custom players ([DesktopPlayerApps]). None inside
         * the Flatpak: its sandbox sees no host programs and can't start them.
         */
        override val playerApps: PlayerAppLauncher? = if (isWindows || (isLinux && !inFlatpak)) DesktopPlayerApps(observer = test?.playerApps) else null

        override fun setFullscreen(fullscreen: Boolean) {
            val before = desktop.frameRect()
            desktop.setFullscreen(fullscreen)
            test?.event(
                "fullscreen",
                mapOf("fullscreen" to desktop.isFullscreen, "frameBefore" to before, "frameAfter" to desktop.frameRect(), "monitor" to desktop.monitorRect()),
            )
        }

        /** The system browser ([browse]); false where it doesn't take [url], and the app offers it to a phone instead. */
        override fun openUrl(url: String): Boolean = browse(url)

        // AWT has no sensitive flag: a secret is copied as any other text (AppPlatform.setClipboardText).
        override fun setClipboardText(text: String, sensitive: Boolean) =
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)

        override fun chooseFolder(title: String, initial: String?, onChosen: (String) -> Unit) = chooseDirectory(window, title, initial, onChosen)

        override val hasFileDialogs = true

        /**
         * The bundled shaders in the app's resources folder's shaders/ (`compose.application.resources.dir`), the
         * imported ones in shaders/ beside the database; a test session without a data directory, whose database is in
         * memory, imports into a temporary folder. Windows renders through libplacebo (`gpu-next`), Linux through
         * mpv's OpenGL renderer (the render API), whose failing compute shaders abort libmpv.
         */
        override val shaderFiles = System.getProperty("compose.application.resources.dir")?.let { resources ->
            val data = test?.dataDir ?: DatabaseFactory.defaultDirectory().takeUnless { test?.session != null }
                ?: Files.createTempDirectory("tortuga-shaders")
            val renderer = if (isWindows) ShaderRenderer.LIBPLACEBO else ShaderRenderer.OPENGL
            ShaderDirectories(renderer, Path.of(resources, "shaders").toString(), data.resolve("shaders").toString(), installBundled = {})
        }

        override fun saveFile(fileName: String, text: String, done: (SaveResult) -> Unit) = saveTextFile(window, fileName, text, done)

        override fun openFile(opened: (name: String, text: String) -> Unit, failed: () -> Unit) = openTextFile(window, opened, failed)

        /**
         * The files under [folder] in the app's resources folder (`compose.application.resources.dir`, which the
         * packaged app, Gradle's run task and the harness launcher's argfile set), at any depth.
         */
        override fun bundledTexts(folder: String): List<BundledText> {
            val root = System.getProperty("compose.application.resources.dir")?.let { Path.of(it, folder) }
            if (root == null || !root.isDirectory()) return emptyList()
            return Files.walk(root).use { paths ->
                paths.filter { it.isRegularFile() }.map { BundledText(root.relativize(it).invariantSeparatorsPathString, it.readText()) }.toList()
            }.sortedBy { it.path }
        }

        override fun exit() = close(0)
    }

    fun show() {
        test?.mark("appCreated")
        val session = test?.session
        val content: @Composable () -> Unit = if (session != null) {
            val app = session.app(boot, platform)
            this.app = app
            ({ TortugaApp(app, platform) })
        } else {
            val root = RootSession(
                graph = boot.graph,
                account = checkNotNull(boot.account),
                sync = checkNotNull(boot.sync),
                signIn = DesktopSignInPlatform(test?.signInBrowser),
                platform = platform,
                startup = checkNotNull(startup),
            )
            this.root = root
            ({ TortugaRoot(root, platform) })
        }
        test?.onShow(window, desktop, root, app) { close(0) }

        val scale = DesktopWindow.uiScale
        window.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = close(0)

            // The desktop's foreground: sync and the trackers catch up when the window comes back (sync pulls only when
            // its last pull is a minute old, so switching windows costs nothing).
            override fun windowActivated(e: WindowEvent) = boot.onForeground()

            // Minimized, the app is in the background (sync stops its periodic pulls, playback still pushes) and the
            // player lets the display sleep (TortugaPlatform.windowVisible).
            override fun windowIconified(e: WindowEvent) {
                platform.windowVisible = false
                boot.onBackground()
            }

            override fun windowDeiconified(e: WindowEvent) {
                platform.windowVisible = true
                boot.onForeground()
            }
        })
        // What the window went through before the listener: it opened while the core started.
        platform.windowVisible = window.extendedState and Frame.ICONIFIED == 0
        if (platform.windowVisible) boot.onForeground()
        window.setContent {
            if (test != null) LaunchedEffect(test) { test.watchStartup() }
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density * scale, base.fontScale),
                LocalLayoutProbe provides test?.layoutProbe,
                LocalStoreProbe provides test?.storeProbe,
            ) {
                // The root draws mpv's GL teardown once the player is gone (Linux).
                Box(Modifier.fillMaxSize().mpvRenderHook()) { content() }
            }
        }
        test?.mark("contentSet")
        cli.link?.let(::openLink)
        instance?.receive { link ->
            SwingUtilities.invokeLater {
                raise()
                link?.let(::openLink)
            }
        }
    }

    /** Follows a link the OS opened Tortuga with ([Cli.link], or a later start's): the product's root, or the test session's app. */
    private fun openLink(url: String) {
        val root = root
        if (root != null) root.openUrl(url) else app?.openIncomingLink(url)
    }

    /** The window in front, restored if minimized: a later start (a link opened, the app started again) comes here. */
    private fun raise() {
        if (window.extendedState and Frame.ICONIFIED != 0) window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
        window.toFront()
        window.requestFocus()
    }

    /**
     * Ends the playback (the engine terminates on its own thread; the display restore is queued), waits for mpv and the
     * display, then exits with [code].
     */
    fun close(code: Int) {
        if (!closing.compareAndSet(false, true)) return
        test?.event("close")
        // A start from now on waits for this process to end and is the next app.
        instance?.stop()
        app?.close()
        root?.close()
        Thread({
            val start = System.nanoTime()
            val released = engines.all { it.awaitReleased(10.seconds) }
            if (!released) System.err.println("tortuga: mpv did not terminate within 10 s")
            displayMatching?.close()
            mediaControls?.close()
            discord?.close()
            displayAwake?.close()
            test?.event("released", mapOf("terminated" to released, "engines" to playbacks, "ms" to (System.nanoTime() - start) / 1_000_000))
            test?.finish(if (released) "ok" else "terminate-timeout")
            SwingUtilities.invokeLater { window.dispose() }
            network.close()
            boot.close()
            instance?.close()
            exitProcess(code)
        }, "shutdown").start()
    }

    companion object {
        /** The OS double-click interval (Windows `GetDoubleClickTime`, the desktop's setting on Linux). */
        private fun doubleClickIntervalMillis(): Long =
            (Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval") as? Int)?.toLong() ?: 500L
    }
}
