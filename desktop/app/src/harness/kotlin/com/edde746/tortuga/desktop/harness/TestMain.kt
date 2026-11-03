package com.edde746.tortuga.desktop.harness

import androidx.compose.ui.awt.ComposeWindow
import com.edde746.tortuga.app.AppBootstrap
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.DevCrashException
import com.edde746.tortuga.app.DevLaunch
import com.edde746.tortuga.app.DevStart
import com.edde746.tortuga.app.DevStartException
import com.edde746.tortuga.app.DevTrackers
import com.edde746.tortuga.app.RootSession
import com.edde746.tortuga.app.applyDevSettings
import com.edde746.tortuga.app.dev
import com.edde746.tortuga.app.devAppSession
import com.edde746.tortuga.app.startDevSession
import com.edde746.tortuga.crash.CrashReports
import com.edde746.tortuga.crash.crashReports
import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.desktop.Bootstrap
import com.edde746.tortuga.desktop.Cli
import com.edde746.tortuga.desktop.DesktopSignInPlatform
import com.edde746.tortuga.desktop.TestLaunch
import com.edde746.tortuga.desktop.TestSession
import com.edde746.tortuga.desktop.runApp
import com.edde746.tortuga.mpv.DesktopWindow
import com.edde746.tortuga.mpv.MpvPlayerEngine
import com.edde746.tortuga.playback.PlayerEngine
import com.edde746.tortuga.ui.app.TortugaPlatform
import kotlinx.coroutines.runBlocking
import java.awt.Frame
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** When the launcher started (TestMainKt loads with main()): the report's `main` milestone, and `--engine-set` times count from here. */
private val startedAt = System.nanoTime()

/**
 * The harness launcher (the desktop test harness starts it, with app-desktop's writeHarnessArgs argfile): the desktop
 * app with the test-only options ([TestOptions]) on top of its own ([Cli]), run through the app's [runApp] with a
 * [TestLaunch]. The release jar has neither.
 *
 *     java @harness.args com.edde746.tortuga.desktop.harness.TestMainKt [test options] [app options] [<stremio: link>]
 */
fun main(args: Array<String>) {
    val (cli, options) = try {
        TestOptions.parse(args)
    } catch (e: Cli.Error) {
        System.err.println("tortuga: ${e.message}\n\n${TestOptions.USAGE}")
        exitProcess(2)
    }
    val launch = HarnessLaunch(options)
    runApp(cli, launch)
    options.exitAfterSeconds?.let(launch::exitAfter)
    options.crashAfterSeconds?.let(::crashAfter)
}

/** `--crash-after`: throws a [DevCrashException] on a thread of its own [seconds] from now, which nothing catches. */
private fun crashAfter(seconds: Double) {
    Thread({
        Thread.sleep((seconds * 1000).toLong())
        throw DevCrashException()
    }, "crash-after").apply { isDaemon = true; start() }
}

/** [options] as the app's [TestLaunch]: a dev session, a device of its own, the test report, the engine calls. */
private class HarnessLaunch(private val options: TestOptions) : TestLaunch {
    private val report = options.report?.let(::TestReport)

    /** The latest playback's engine, which a later `--engine-set` call goes to. */
    @Volatile private var engine: PlayerEngine? = null

    /** Closes the app as its window's close button does; null until the window shows the app. */
    @Volatile private var close: (() -> Unit)? = null

    override val dataDir: Path? = options.dataDir
    override val session: TestSession? = if (options.dev.dev) DevSession(options.dev, options.dataDir, report) else null
    override val windowSize: Pair<Int, Int>? = options.windowSize
    override val signInBrowser: ((String) -> Unit)? = options.dev.browser?.let { program -> { url -> openWith(Path.of(program), url) } }
    override val slowStartMillis: Long = options.dev.slowStartMillis
    override val layoutProbe = report?.probe
    override val storeProbe = report?.stores
    override val playerApps = report

    override fun start() {
        report?.start()
        report?.mark("main", startedAt)
    }

    override fun mark(name: String) {
        report?.mark(name)
    }

    override fun event(name: String, detail: Map<String, Any?>) {
        report?.event(name, detail)
    }

    override fun onEngine(engine: MpvPlayerEngine, number: Int) {
        this.engine = engine
        report?.onEngine(engine)
        // The calls at 0 s, on the first playback's engine as it is created (the engine holds them until mpv starts).
        if (number == 1) options.engineSets.filter { it.atSeconds == 0.0 }.forEach { call(engine, it) }
    }

    override fun onShow(window: ComposeWindow, desktop: DesktopWindow, root: RootSession?, app: AppSession?, close: () -> Unit) {
        report?.root = root
        report?.app = app
        report?.window = desktop
        report?.isFocused = { window.isFocused }
        report?.isActive = { window.isActive }
        report?.isMinimized = { window.extendedState and Frame.ICONIFIED != 0 }
        this.close = close
        scheduleEngineSets()
    }

    override suspend fun watchStartup() {
        report?.watchStartup()
    }

    override fun finish(status: String) {
        report?.finish(status)
    }

    /** `--exit-after`: closes the app [seconds] from now, and halts it 30 s later should its shutdown hang. */
    fun exitAfter(seconds: Double) {
        Thread({
            Thread.sleep((seconds * 1000).toLong())
            SwingUtilities.invokeLater { close?.invoke() }
        }, "exit-after").apply { isDaemon = true; start() }
        // Last resort if shutdown hangs (e.g. a stuck mpv teardown): the harness must always get a process exit.
        Thread({
            Thread.sleep(((seconds + 30) * 1000).toLong())
            report?.finish("timeout")
            Runtime.getRuntime().halt(3)
        }, "watchdog").apply { isDaemon = true; start() }
    }

    /** The `--engine-set` calls after 0 s, each on the engine playing at its time (skipped, and logged, when none is). */
    private fun scheduleEngineSets() {
        val later = options.engineSets.filter { it.atSeconds > 0.0 }.sortedBy { it.atSeconds }
        if (later.isEmpty()) return
        Thread({
            for (set in later) {
                val waitMs = (set.atSeconds * 1000).toLong() - (System.nanoTime() - startedAt) / 1_000_000
                if (waitMs > 0) Thread.sleep(waitMs)
                val current = engine
                if (current != null) call(current, set) else report?.event("engineSetSkipped", mapOf("name" to set.name))
            }
        }, "engine-set").apply { isDaemon = true; start() }
    }

    /** One `--engine-set` call through the [PlayerEngine] API, logged as a report event. */
    private fun call(engine: PlayerEngine, set: TestOptions.EngineSet) {
        report?.event("engineSet", mapOf("name" to set.name, "value" to set.value))
        when (set.name) {
            "speed" -> engine.setSpeed(set.value.toDouble())
            "sub-delay" -> engine.setSubtitleDelay(set.value.toDouble())
            "audio-delay" -> engine.setAudioDelay(set.value.toDouble())
            "volume" -> engine.setVolume(set.value.toDouble())
            "mute" -> engine.setMuted(set.value == "yes")
        }
    }

    /** `--dev-browser`: runs [program] with a sign-in page's [url] (a .ps1 through PowerShell) instead of the browser. */
    private fun openWith(program: Path, url: String) {
        val command = if (program.toString().endsWith(".ps1", ignoreCase = true)) {
            listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", program.toString(), url)
        } else {
            listOf(program.toString(), url)
        }
        Thread({
            runCatching {
                val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.INHERIT).start()
                System.err.println("tortuga: --dev-browser exited with ${process.waitFor()}")
            }.onFailure { System.err.println("tortuga: --dev-browser failed: $it") }
        }, "dev-browser").apply { isDaemon = true }.start()
    }
}

/**
 * `--dev`: a development session ([startDevSession]) instead of the product, as [launch] asks for it, on an in-memory
 * database or one kept in [dataDir] (`--data-dir`, `--dev-data`), with the session's addons, settings, progress,
 * trackers and screens (or `--url` playing).
 */
private class DevSession(private val launch: DevLaunch, private val dataDir: Path?, private val report: TestReport?) : TestSession {
    private lateinit var start: DevStart

    override fun boot(version: String): AppBootstrap {
        val boot = devBootstrap(version, launch.trackers, dataDir)
        start = try {
            runBlocking {
                boot.graph.applyDevSettings(launch.settings)
                // A `--dev-relay` session's tracker sign-ins open the browser, as the product's do.
                boot.graph.startDevSession(launch, DesktopSignInPlatform(browser = null))
            }
        } catch (e: DevStartException) {
            System.err.println("tortuga: --dev: ${e.message}")
            report?.finish("dev-start-failed")
            boot.close()
            exitProcess(2)
        }
        return boot
    }

    override fun app(boot: AppBootstrap, platform: TortugaPlatform): AppSession = boot.graph.devAppSession(start, platform)

    /** `--dev-discord`'s; none without it, whatever the build's, so no test run shows on a real Discord unasked. */
    override val discordApplicationId: String? = launch.discord

    /** `--dev-crash-reports`' fake Sentry, or none. */
    override fun crashReports(boot: AppBootstrap): CrashReports =
        boot.crashReports(launch.crashReports.orEmpty(), CrashReports.DEVELOPMENT)

    /**
     * The session's core: its database in [dataDir], kept for the next launch, with its downloads in `downloads` there;
     * without one, an in-memory database whose downloads go to `dev-downloads` next to the device's database.
     * [trackers] (`--dev-trackers`): Trakt against this fake server instead of the build's registrations.
     */
    private fun devBootstrap(version: String, trackers: DevTrackers?, dataDir: Path?): AppBootstrap {
        val directory = dataDir?.toAbsolutePath()
        val database = directory?.let { DatabaseFactory(it.resolve("tortuga.db")) } ?: DatabaseFactory.inMemory()
        val downloads = directory?.resolve("downloads") ?: DatabaseFactory.defaultDirectory().resolve("dev-downloads")
        val cache = Bootstrap.cacheDirectory(directory ?: DatabaseFactory.defaultDirectory())
        return with(Bootstrap) { AppBootstrap.dev(database, flavor, version, thisDevice(), cache, trackers).withDownloads(downloads) }
    }
}
