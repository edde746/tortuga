package com.edde746.tortuga

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.edde746.tortuga.app.AppBootstrap
import com.edde746.tortuga.app.BuildFlavor
import com.edde746.tortuga.app.RootSession
import com.edde746.tortuga.app.Startup
import com.edde746.tortuga.crash.crashReports
import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.downloads.android.AndroidDownloads
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.secrets.AndroidSecretStore
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import com.edde746.tortuga.sync.api.deviceName

/**
 * The product in this process: the shared core ([AppBootstrap.product]) on the device's database and the Android
 * Keystore secret store, with its account and sync engine, its downloads ([AndroidDownloads.install]), and the account
 * shell over them ([root]), whose gate restores the account, starts the engine and picks the first screen; the splash
 * names the start's steps ([Startup], begun first). Built on the main thread when the first activity needs it (or the
 * download worker, in a process WorkManager started) and kept for the life of the process, so an activity that comes
 * back (a relaunch after Back, a recreation) shows the app as it was.
 *
 * @param slowStartMillis a debug launch's slow start (`--dev-slow-start` in the `dev` extra); 0 otherwise.
 */
internal class ProductApp(
    context: Context,
    platform: AndroidPlatform,
    signIn: AndroidSignInPlatform,
    version: String,
    flavor: BuildFlavor,
    network: ProcessNetwork,
    downloads: AndroidDownloads,
    slowStartMillis: Long,
) {
    private val startup = Startup(slowStartMillis)

    /**
     * The shared core: the activity tells it when the app comes to the foreground, the process when the network changes.
     * It sends crash reports to this build's endpoint while they are on; in a debug build a coroutine that throws ends
     * the process.
     */
    val boot = AppBootstrap.product(
        DatabaseFactory(context), AndroidSecretStore(context), flavor, version, thisDevice(context, platform), context.cacheDir.path, startup,
        development = BuildConfig.DEBUG,
    ).also { boot ->
        network.tell(boot)
        downloads.install(boot.graph)
        boot.graph.followCrashReports(context, boot.crashReports())
    }
    val root = RootSession(boot.graph, checkNotNull(boot.account), checkNotNull(boot.sync), signIn, platform, startup)

    companion object {
        /** What the server lists this device as: its name in Settings (the model without one), phone or TV. */
        fun thisDevice(context: Context, platform: AndroidPlatform): DeviceDescriptor {
            val name = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
                ?: "${Build.MANUFACTURER} ${Build.MODEL}"
            val kind = if (platform.formFactor == FormFactor.TV) DevicePlatform.ANDROID_TV else DevicePlatform.ANDROID
            return DeviceDescriptor(deviceName(name), kind)
        }
    }
}
