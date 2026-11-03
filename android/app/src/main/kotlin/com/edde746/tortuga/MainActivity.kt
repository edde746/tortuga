package com.edde746.tortuga

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.edde746.tortuga.app.AppBootstrap
import com.edde746.tortuga.ui.app.TortugaApp
import com.edde746.tortuga.ui.app.TortugaRoot
import com.edde746.tortuga.ui.probe.LocalLayoutProbe
import com.edde746.tortuga.ui.probe.LocalPerfProbe
import com.edde746.tortuga.ui.probe.LocalStoreProbe

/**
 * The app's one activity: the product's account shell ([TortugaRoot]) and every screen of the running profile, or a
 * debug launch's dev session ([TortugaApp]). The app draws edge to edge inside the system bars and the display
 * cutout; the player hides the bars while it shows and lays its video out under them and the cutout
 * (AndroidPlatform.VideoSurface).
 *
 * Back goes through the navigation event dispatcher: the screens' back handlers (menus and overlays first, then the
 * back stack), and at the root the system's default (the task moves to the background). The video's SurfaceViews
 * hand mpv to its placeholder surface when the activity stops (playback pauses) and back when it returns.
 *
 * A playing video goes to picture-in-picture on Home ([PictureInPicture]); the activity stays started there, so the
 * surfaces and the playback carry on. On a phone the player turns to landscape ([PlayerOrientation]). A
 * watch-together invite or a `stremio:` link opens the app (or comes to the running one) and is followed there
 * ([openLink]). A player app a play hands off to reports back as an activity result
 * ([com.edde746.tortuga.players.AndroidPlayerApps]). The network and the system's settings reach the shared cores from
 * the process ([ProcessNetwork], [TortugaApplication]), not from here.
 */
class MainActivity : ComponentActivity() {
    private val tortuga: TortugaApplication get() = application as TortugaApplication
    private var launch: TestLaunch? = null
    private var product: ProductApp? = null
    private var pictureInPicture: PictureInPicture? = null
    private var playerOrientation: PlayerOrientation? = null

    /** The shared core on screen: the product's, or a debug launch's dev session once it is built. */
    private val boot: AppBootstrap? get() = product?.boot ?: launch?.boot

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val tortuga = tortuga
        tortuga.platform.activity = this
        // Before the activity starts: a player app's or the document picker's result reaches the launcher this
        // activity registers, also one started from an activity this one replaces.
        tortuga.platform.playerApps.attach(this)
        tortuga.platform.files.attach(this)
        pictureInPicture = PictureInPicture(this, tortuga.platform, tortuga.platform.mediaControls).also {
            tortuga.platform.pictureInPicture = it
            it.attach()
        }
        playerOrientation = PlayerOrientation(this, tortuga.platform.isTelevision).also { tortuga.platform.playerOrientation = it }
        // A fresh launch starts over; a recreated activity goes on with the launch it showed.
        if (savedInstanceState == null) {
            tortuga.testLaunch?.close()
            tortuga.testLaunch = testLaunch(tortuga, intent)
        }
        val launch = tortuga.testLaunch
        this.launch = launch
        val product = if (launch?.dev == true) null else tortuga.product.also { launch?.attachProduct(it.root) }
        this.product = product
        // A link that launched the app; the root follows it once a profile runs.
        if (savedInstanceState == null) openLink(intent)

        setContent {
            InterceptPlatformTextInput(UnaskedFocusActions) {
                CompositionLocalProvider(
                    LocalLayoutProbe provides launch?.probe,
                    LocalStoreProbe provides launch?.storeProbe,
                    LocalPerfProbe provides launch?.perfProbe,
                ) {
                    val modifier = Modifier.safeDrawingPadding()
                    if (product != null) {
                        TortugaRoot(product.root, tortuga.platform, modifier)
                    } else {
                        launch?.devApp?.let { TortugaApp(it, tortuga.platform, modifier) }
                    }
                }
            }
        }
    }

    /** A link opened while the app runs (singleTask: the running activity gets it). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openLink(intent)
    }

    /**
     * A link [intent] views (the manifest's intent filters): a watch-together invite (`tortuga://watch?code=…&relay=…`)
     * joins its room, a device link's QR code (`tortuga://link?server=…&code=…`) opens Settings → Account to approve the
     * new device, a title's link (`https://usetortuga.app/t/…`, `tortuga://detail?type=…&id=…`) opens its Detail, a
     * `stremio:` link opens its screen (an addon's install preview, a title's Detail). The product's root
     * follows it ([com.edde746.tortuga.app.RootSession.openUrl]), after sign-in or the profile picker when it comes
     * before them; a debug launch's dev app at once.
     */
    private fun openLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val url = intent.dataString ?: return
        val product = product
        if (product != null) product.root.openUrl(url) else launch?.devApp?.openIncomingLink(url)
    }

    override fun onStart() {
        super.onStart()
        boot?.onForeground()
        tortuga.downloads.onForeground()
        tortuga.platform.mediaControls.onForeground()
        launch?.event("onStart")
    }

    override fun onStop() {
        launch?.event("onStop")
        boot?.onBackground()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pictureInPicture?.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Leaving while stopped: the window was closed rather than expanded.
        val stopped = !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        launch?.event("pictureInPicture", mapOf("inPictureInPicture" to isInPictureInPictureMode, "stopped" to stopped))
        playerOrientation?.onPictureInPictureChanged(isInPictureInPictureMode)
        pictureInPicture?.onModeChanged(isInPictureInPictureMode, stopped)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding or unfolding: the cover screen is a phone's, the inner one a tablet's.
        playerOrientation?.onConfigurationChanged(newConfig)
        launch?.event(
            "configuration",
            mapOf(
                "rotation" to ContextCompat.getDisplayOrDefault(this).rotation,
                "widthDp" to newConfig.screenWidthDp,
                "heightDp" to newConfig.screenHeightDp,
            ),
        )
    }

    override fun onDestroy() {
        val tortuga = tortuga
        pictureInPicture?.detach()
        tortuga.platform.playerApps.detach(this)
        tortuga.platform.files.detach(this)
        if (tortuga.platform.pictureInPicture === pictureInPicture) tortuga.platform.pictureInPicture = null
        if (tortuga.platform.playerOrientation === playerOrientation) tortuga.platform.playerOrientation = null
        if (tortuga.platform.activity === this) tortuga.platform.activity = null
        if (isFinishing) {
            launch?.close()
            if (tortuga.testLaunch === launch) tortuga.testLaunch = null
        }
        super.onDestroy()
    }
}
