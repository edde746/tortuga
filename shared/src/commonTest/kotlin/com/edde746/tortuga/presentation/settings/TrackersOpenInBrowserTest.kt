@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.FakeAppPlatform
import com.edde746.tortuga.app.addProfile
import com.edde746.tortuga.app.testGraph
import com.edde746.tortuga.model.FormFactor
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.sync.account.signin.SignInPlatform
import com.edde746.tortuga.sync.account.signin.TestSignInPlatform
import com.edde746.tortuga.trackers.DeviceCodeGrant
import com.edde746.tortuga.trackers.FakeDeviceCode
import com.edde746.tortuga.trackers.TrackerFixture
import com.edde746.tortuga.trackers.TrackerService.TRAKT
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Settings → Trackers in the running app on each kind of device: where connecting offers "Open in browser". */
class TrackersOpenInBrowserTest {
    private val fixtures = mutableListOf<TrackerFixture>()

    @AfterTest
    fun close() {
        fixtures.forEach(TrackerFixture::close)
    }

    /** Whether Trakt's device code, once it shows on a [formFactor] device signing in with [signIn], offers "Open in browser". */
    private suspend fun TestScope.offered(formFactor: FormFactor, signIn: SignInPlatform?): Boolean {
        val f = TrackerFixture(this, listOf(TRAKT)).also { fixtures += it }
        f.providers.getValue(TRAKT).deviceCode = FakeDeviceCode(this, emptyList(), GRANT)
        val graph = testGraph(f.data).apply { trackers = f.hub }
        val profile = f.data.addProfile(f.profileId)
        val app = AppSession(graph, f.profileId, FakeAppPlatform(formFactor = formFactor), start = null, profileAccount = null, localSignIn = signIn, startProfile = profile)
        app.navigator.navigate(Route.Settings)
        val store = assertNotNull(app.settingsSession()?.trackers)
        f.settle()
        store.connect(TRAKT)
        f.settle()
        val code = assertIs<TrackerConnectStep.Code>(assertNotNull(store.state.value.connect).step)
        app.close()
        return code.canOpenInBrowser
    }

    @Test
    fun onlyADeviceWithABrowserThatIsNoTvOffersOpenInBrowser() = runTest {
        val devices = listOf(
            "an Android phone" to offered(FormFactor.HANDHELD, TestSignInPlatform(canOpenBrowser = true)),
            "a computer" to offered(FormFactor.DESKTOP, TestSignInPlatform(canOpenBrowser = true, canReceiveRedirect = true)),
            "a development session on a computer" to offered(FormFactor.DESKTOP, null),
            // Its user signs in on a phone through the QR code beside the code.
            "an Android TV without a browser" to offered(FormFactor.TV, TestSignInPlatform(isTv = true)),
            "a Fire TV with its browser" to offered(FormFactor.TV, TestSignInPlatform(isTv = true, canOpenBrowser = true)),
            "a development session on a TV" to offered(FormFactor.TV, null),
            // A page opened there would only show again as a QR code.
            "a phone without a browser" to offered(FormFactor.HANDHELD, TestSignInPlatform()),
        )
        assertEquals(
            listOf(
                "an Android phone" to true,
                "a computer" to true,
                "a development session on a computer" to true,
                "an Android TV without a browser" to false,
                "a Fire TV with its browser" to false,
                "a development session on a TV" to false,
                "a phone without a browser" to false,
            ),
            devices,
        )
    }

    private companion object {
        val GRANT = DeviceCodeGrant(
            deviceCode = "dc",
            userCode = "ABCD1234",
            verificationUrl = "https://trakt.tv/activate",
            verificationUrlComplete = "https://trakt.tv/activate/ABCD1234",
            expiresInSeconds = 600,
            intervalSeconds = 5,
        )
    }
}
