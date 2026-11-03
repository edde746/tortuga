package com.edde746.tortuga

import android.app.Application
import android.app.LocaleManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.CaptioningManager
import com.edde746.tortuga.app.AppBootstrap
import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.BuildFlavor
import com.edde746.tortuga.app.RootSession
import com.edde746.tortuga.app.SystemInputs
import com.edde746.tortuga.downloads.android.AndroidDownloads
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.DeviceRegion
import com.edde746.tortuga.playback.SystemMediaAccessibility
import com.edde746.tortuga.strings.InterfaceLocale
import com.edde746.tortuga.ui.probe.LayoutProbe
import com.edde746.tortuga.ui.probe.PerfProbe
import com.edde746.tortuga.ui.probe.StoreProbe

/**
 * The process: one [platform], and the product ([product], built when the first activity shows it, or when the
 * download worker needs its queue) or a debug build's [testLaunch]. Both outlive the activity, so a recreated or
 * relaunched activity shows the app as it was. The process hears the system's settings ([SystemInputs]) and the
 * default network ([network]) from its start, whatever starts it, and has one downloads host ([downloads]) for every
 * graph with downloads.
 */
class TortugaApplication : Application() {
    internal val platform: AndroidPlatform by lazy { AndroidPlatform(this) }

    internal val signIn: AndroidSignInPlatform by lazy { AndroidSignInPlatform(this) { platform.activity } }

    /** As shown in About. */
    internal val version: String by lazy { packageManager.getPackageInfo(packageName, 0).versionName ?: "dev" }

    /** Where this build is distributed: -Ptortuga.flavor (android/app/build.gradle.kts). */
    internal val flavor: BuildFlavor = BuildFlavor.valueOf(BuildConfig.BUILD_FLAVOR)

    // A debug launch may slow the product's start; the download worker's product starts as the product does.
    private val productApp = lazy { ProductApp(this, platform, signIn, version, flavor, network, downloads, testLaunch?.slowStartMillis ?: 0) }

    internal val product: ProductApp by productApp

    /** The default network, for every shared core in the process (from [onCreate] on). */
    internal val network: ProcessNetwork by lazy {
        ProcessNetwork(this) { listOfNotNull(productApp.takeIf { it.isInitialized() }?.value?.boot, testLaunch?.boot) }
    }

    /** Downloads' platform and worker host; the worker builds the product through it when the process has no graph. */
    internal val downloads: AndroidDownloads by lazy {
        AndroidDownloads(this, platform.formFactor, { platform.activity }) { product }
    }

    /** The debug launch the activity shows, if any; each fresh launch replaces it. */
    internal var testLaunch: TestLaunch? = null

    override fun onCreate() {
        super.onCreate()
        Log.sink = Log.Sink { level, tag, message, error ->
            val priority = when (level) {
                Log.Level.DEBUG -> android.util.Log.DEBUG
                Log.Level.INFO -> android.util.Log.INFO
                Log.Level.WARN -> android.util.Log.WARN
                Log.Level.ERROR -> android.util.Log.ERROR
            }
            android.util.Log.println(priority, tag, if (error != null) "$message\n$error" else message)
        }
        reportSystemInputs()
        network.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The system's languages or region changed, or this app's language in the system settings (Android 13+).
        InterfaceLocale.setSystemLanguages(languages(newConfig))
        DeviceRegion.setSystemRegion(systemRegion())
    }

    /**
     * The system's settings the shared code follows ([SystemInputs]), as the process starts: the activity, or WorkManager
     * for the download worker without one. The languages and the region again on each configuration change; Caption
     * preferences' switch and, on Android 13+, Audio description (for the track choice) again whenever either changes.
     */
    private fun reportSystemInputs() {
        val captioning = getSystemService(CaptioningManager::class.java)
        val accessibility = getSystemService(AccessibilityManager::class.java)
        fun audioDescriptions() = Build.VERSION.SDK_INT >= 33 && accessibility.isAudioDescriptionRequested
        SystemInputs.report(languages(resources.configuration), systemRegion(), captioning.isEnabled, audioDescriptions())
        fun reportMediaAccessibility() = SystemMediaAccessibility.report(captioning.isEnabled, audioDescriptions())
        captioning.addCaptioningChangeListener(object : CaptioningManager.CaptioningChangeListener() {
            override fun onEnabledChanged(enabled: Boolean) = reportMediaAccessibility()
        })
        if (Build.VERSION.SDK_INT >= 33) accessibility.addAudioDescriptionRequestedChangeListener(mainExecutor) { reportMediaAccessibility() }
    }

    /** The languages the system asks this app for, most preferred first (the per-app language first, where set). */
    private fun languages(configuration: Configuration): List<String> {
        val locales = configuration.locales
        return List(locales.size()) { locales[it].toLanguageTag() }
    }

    /**
     * The system's region, for streams' country lists: its first language's country. Not this process's locales, which
     * Android 13+ starts with the app's own language when the user picks one in the system settings.
     */
    private fun systemRegion(): String? {
        val locales = if (Build.VERSION.SDK_INT >= 33) getSystemService(LocaleManager::class.java).systemLocales else Resources.getSystem().configuration.locales
        return locales[0]?.country
    }
}

/**
 * What a debug build's launch intent asks for beyond the product (the test harness): a dev session instead of the
 * product, more mpv options, a test report, perf marks. `testLaunch(application, intent)` reads it from the extras
 * (src/debug); in release builds it returns null (src/release), so they contain none of it.
 */
internal interface TestLaunch {
    /** The launch runs no product: a dev session instead, or extras that can't run, which end the launch. */
    val dev: Boolean

    /** The dev session's shared core, once it is built; null for a launch of the product. */
    val boot: AppBootstrap?

    /** The dev session's app once it has started; null before. Compose state. */
    val devApp: AppSession?

    /** The product's start takes this many milliseconds longer at its account step (`--dev-slow-start`); 0 otherwise. */
    val slowStartMillis: Long

    /** Collects where the named controls are and which has focus, for the report; null without a report. */
    val probe: LayoutProbe?

    /** The account screens' stores on screen, for the report; null without a report. */
    val storeProbe: StoreProbe?

    /** Marks when the screens first draw their content, for the perf runs; null without `perf`. */
    val perfProbe: PerfProbe?

    /** The product runs: the report samples its account shell. */
    fun attachProduct(root: RootSession)

    /** An activity event (start, stop, a rotation), for the report. */
    fun event(name: String, detail: Map<String, Any?> = emptyMap())

    /** The launch is over (its activity finished): ends the dev session and the report. */
    fun close()
}
