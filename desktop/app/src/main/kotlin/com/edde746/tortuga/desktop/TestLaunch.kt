package com.edde746.tortuga.desktop

import androidx.compose.ui.awt.ComposeWindow
import com.edde746.tortuga.app.AppBootstrap
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.RootSession
import com.edde746.tortuga.crash.CrashReports
import com.edde746.tortuga.desktop.players.PlayerAppsObserver
import com.edde746.tortuga.mpv.DesktopWindow
import com.edde746.tortuga.mpv.MpvPlayerEngine
import com.edde746.tortuga.ui.app.TortugaPlatform
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.StoreProbe
import java.nio.file.Path

/**
 * What a test launch asks of the app beyond the product: the harness launcher's (desktop/app/src/harness,
 * TestMain.kt), which parses the test-only options and runs the app through [runApp] with one of
 * these. The product's main passes none, so the release jar holds no test option, test report or dev session.
 */
internal interface TestLaunch {
    /**
     * A device of its own: its database and downloads in this directory and its secrets under a name derived from it
     * ([Bootstrap.product]), apart from the device's; with a [session], that session's (kept for its next launch).
     * Null: the device itself, or a session's in-memory database.
     */
    val dataDir: Path?

    /** An app on a core of its own instead of the product (a dev session); null for the product. */
    val session: TestSession?

    /** The content area's size in dp at startup instead of 1280×720, fitted to the work area as that is. */
    val windowSize: Pair<Int, Int>?

    /** The product's sign-in calls this with each page's URL instead of opening the system browser; null: the browser. */
    val signInBrowser: ((url: String) -> Unit)?

    /** The product's start takes this many milliseconds longer at its account step, so the splash names it. */
    val slowStartMillis: Long

    /** Collects where the named controls are and which has focus; null without a report. */
    val layoutProbe: LayoutProbe?

    /** The account screens' stores on screen; null without a report. */
    val storeProbe: StoreProbe?

    /** Hears the player apps' launches and what they report; null without a report. */
    val playerApps: PlayerAppsObserver?

    /** This process is the app of its data directory ([SingleInstance]): the launch starts its own work. */
    fun start()

    /** The start reached milestone [name] (its first time counts). */
    fun mark(name: String)

    /** An app action, with its [detail]: a media controls command, a display switch, full screen, the close. */
    fun event(name: String, detail: Map<String, Any?> = emptyMap())

    /** The run's [number]th playback starts on [engine]. */
    fun onEngine(engine: MpvPlayerEngine, number: Int)

    /**
     * [window] ([desktop]) shows [root]'s account shell or the session's [app]; [close] closes the app as the window's
     * close button does (on the UI thread).
     */
    fun onShow(window: ComposeWindow, desktop: DesktopWindow, root: RootSession?, app: AppSession?, close: () -> Unit)

    /** Runs in the window's composition from its content's first frame, on the UI thread. */
    suspend fun watchStartup()

    /** The app ends as [status]: `ok`, or `terminate-timeout` when mpv didn't terminate in time. */
    fun finish(status: String)
}

/** A test launch's app on a core of its own instead of the product's (the harness launcher's dev session). */
internal interface TestSession {
    /** The core, built on the main thread while the window opens; exits the process when the session can't start. */
    fun boot(version: String): AppBootstrap

    /** The app on [boot]'s graph, made on the UI thread with the window's [platform]. */
    fun app(boot: AppBootstrap, platform: TortugaPlatform): AppSession

    /**
     * The Discord application the session's Rich Presence shows as, instead of the build's; null: none, so the session
     * offers no Discord switch and connects to no Discord.
     */
    val discordApplicationId: String?

    /** Where [boot]'s crash reports go instead of the build's endpoint (a fake one, or none). */
    fun crashReports(boot: AppBootstrap): CrashReports
}
