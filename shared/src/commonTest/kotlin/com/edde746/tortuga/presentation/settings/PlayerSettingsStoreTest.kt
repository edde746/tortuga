package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.app.AppSession
import com.edde746.tortuga.app.FakeAppPlatform
import com.edde746.tortuga.app.FakePlayerLauncher
import com.edde746.tortuga.app.INFUSE
import com.edde746.tortuga.app.MX_PLAYER
import com.edde746.tortuga.app.PlayerAppsWorld
import com.edde746.tortuga.app.VLC
import com.edde746.tortuga.app.addProfile
import com.edde746.tortuga.app.testGraph
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.playback.players.AndroidPlayers
import com.edde746.tortuga.playback.players.CustomPlayer
import com.edde746.tortuga.playback.players.CustomPlayerKind
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.players.PlayerAppKind
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings → Playback → Player: choosing this device's player, custom players, and a chosen one gone missing. */
class PlayerSettingsStoreTest {
    private val text = T.playerApps.settings
    private val form = T.playerApps.settings.form

    private suspend fun TestScope.store(launcher: FakePlayerLauncher = FakePlayerLauncher()): Pair<PlayerAppsWorld, PlayerSettingsStore> {
        val w = PlayerAppsWorld(this, launcher)
        val app = w.open()
        app.navigator.navigate(Route.Settings)
        val store = assertNotNull(app.settingsSession()?.player)
        w.settle()
        return w to store
    }

    @Test
    fun choosingAPlayerAppStoresItWithItsName() = runTest {
        val (w, store) = store()
        var state = store.state.value
        assertEquals(PlayerSettingRow("Player", "Tortuga", "This device only"), state.row)
        assertFalse(state.pageOpen)

        store.openPage()
        w.settle()
        state = store.state.value
        assertTrue(state.pageOpen)
        assertEquals(2, w.launcher.detections, "found again as the page opens")
        assertEquals(listOf(PlayerApp.TORTUGA_ID, VLC.id, MX_PLAYER.id, INFUSE.id), state.players.map { it.id })
        assertEquals(listOf(true, false, false, false), state.players.map { it.selected })
        assertEquals(
            listOf(null, text.followsProgress, text.savesOnReturn, text.asksAfterFromStart),
            state.players.map { it.detail },
        )
        assertEquals(listOf(false, false, false, false), state.players.map { it.hasIcon }, "desktop draws no app icons")

        store.select(VLC.id)
        w.settle()
        assertEquals(VLC.id, w.data.deviceSettings.value(DeviceSetting.Player))
        assertEquals("VLC", w.data.deviceSettings.value(DeviceSetting.PlayerName))
        state = store.state.value
        assertEquals("VLC", state.row.valueLabel)
        assertEquals(VLC.id, state.players.single { it.selected }.id)

        store.select(PlayerApp.TORTUGA_ID)
        w.settle()
        assertNull(w.data.deviceSettings.value(DeviceSetting.Player))
        assertEquals("Tortuga", store.state.value.row.valueLabel)
        store.closePage()
        assertFalse(store.state.value.pageOpen)
    }

    @Test
    fun aChosenPlayerNoLongerFoundIsNamedAsMissing() = runTest {
        val (w, store) = store()
        store.select(VLC.id)
        w.settle()
        w.launcher.players = listOf(MX_PLAYER, INFUSE)
        store.openPage()
        w.settle()
        val state = store.state.value
        assertEquals("\u2068VLC\u2069 (not found)", state.row.valueLabel, "the player's name isolated")
        assertEquals(PlayerChoiceRow(VLC.id, "\u2068VLC\u2069 (not found)", detail = null, selected = true, missing = true, hasIcon = false, deleteText = null), state.players.last())
        assertEquals(listOf(PlayerApp.TORTUGA_ID, MX_PLAYER.id, INFUSE.id, VLC.id), state.players.map { it.id })
    }

    @Test
    fun androidAppsSayTheyResumeOnlyWhenTheirStartExtraIsDocumented() = runTest {
        fun app(packageName: String, name: String) = PlayerApp(packageName, name, PlayerAppKind.DETECTED, AndroidPlayers.of(packageName).api.capabilities)
        val chooser = PlayerApp(PlayerApp.CHOOSER_ID, T.playerApps.chooser, PlayerAppKind.CHOOSER, AndroidPlayers.chooserCapabilities)
        val players = listOf(app("org.xbmc.kodi", "Kodi"), app("is.xyz.mpv", "mpv"), app("com.google.android.apps.photos", "Photos"), chooser)
        val (w, store) = store(FakePlayerLauncher(players = players, customPlayers = CustomPlayerKind.PACKAGE))
        store.openPage()
        w.settle()
        assertEquals(
            listOf(null, text.asksAfterFromStart, text.savesOnReturn, text.savesOnReturnFromStart, text.chooserDetail),
            store.state.value.players.map { it.detail },
        )
        assertEquals("Resumes where you left off and saves where you stopped", store.state.value.players[2].detail)
        assertEquals("Starts from the beginning, then saves where you stopped or asks whether you finished", store.state.value.players[3].detail)
    }

    @Test
    fun customProgramsAreCheckedAddedChosenAndDeleted() = runTest {
        val (w, store) = store()
        var state = store.state.value
        assertEquals("Custom players", state.customHeader)
        assertEquals("Add custom player", state.addCustomText)
        assertNull(state.customForm)

        store.openAddCustom()
        val open = assertNotNull(store.state.value.customForm)
        assertEquals(CustomPlayerKind.COMMAND, open.kind)
        assertEquals(form.program, open.commandLabel)
        assertEquals(form.arguments, open.argumentsLabel)
        assertEquals("{url}", open.argumentsDefault)
        assertEquals(form.programPlaceholders, open.placeholders)

        store.saveCustom(" ", "mpv", "{url}")
        assertEquals(form.nameMissing, store.state.value.customForm?.error)
        store.saveCustom("mpv", "", "{url}")
        assertEquals(form.programMissing, store.state.value.customForm?.error)
        store.saveCustom("mpv", "mpv", "--fs")
        assertEquals("The arguments need {url}.", store.state.value.customForm?.error)
        store.saveCustom(" My mpv ", "mpv", "--fs {url}")
        assertNull(store.state.value.customForm)
        w.settle()
        assertEquals(listOf(CustomPlayer("1", "My mpv", "mpv", "--fs {url}")), w.data.deviceSettings.value(DeviceSetting.CustomPlayers))
        state = store.state.value
        assertEquals(
            listOf(PlayerChoiceRow("custom:1", "My mpv", "mpv --fs {url}", selected = false, missing = false, hasIcon = false, deleteText = "Delete \u2068My mpv\u2069")),
            state.customPlayers,
        )
        assertEquals(4, state.players.size, "custom players are listed apart")

        store.select("custom:1")
        w.settle()
        assertEquals("My mpv", store.state.value.row.valueLabel)
        store.deleteCustom("custom:1")
        w.settle()
        assertEquals(emptyList(), w.data.deviceSettings.value(DeviceSetting.CustomPlayers))
        assertNull(w.data.deviceSettings.value(DeviceSetting.Player), "deleting the chosen one brings Tortuga back")
        assertNull(w.data.deviceSettings.value(DeviceSetting.PlayerName))
        assertEquals("Tortuga", store.state.value.row.valueLabel)
        assertEquals(emptyList(), store.state.value.customPlayers)
    }

    @Test
    fun twoCustomPlayersAddedOneRightAfterTheOtherAreBothKept() = runTest {
        val (w, store) = store()
        store.openAddCustom()
        store.saveCustom("mpv", "mpv", "{url}")
        store.openAddCustom()
        store.saveCustom("VLC", "vlc", "{url}")
        w.settle()
        assertEquals(listOf("1" to "mpv", "2" to "VLC"), w.data.deviceSettings.value(DeviceSetting.CustomPlayers).map { it.id to it.name })
    }

    @Test
    fun urlTemplatesAndPackagesAreCheckedAsTheirPlatformsReadThem() = runTest {
        val (apple, templates) = store(FakePlayerLauncher(customPlayers = CustomPlayerKind.URL_TEMPLATE, handsOffFiles = false))
        templates.openAddCustom()
        val template = assertNotNull(templates.state.value.customForm)
        assertNull(template.argumentsLabel)
        assertEquals(form.urlTemplatePlaceholders, template.placeholders)
        templates.saveCustom("Handoff", "https://handoff.example/?u=", "ignored")
        assertEquals(form.urlTemplateNeedsUrl, templates.state.value.customForm?.error)
        templates.saveCustom("Handoff", "https://handoff.example/?u={url_raw}", "ignored")
        apple.settle()
        assertEquals(listOf(CustomPlayer("1", "Handoff", "https://handoff.example/?u={url_raw}", "")), apple.data.deviceSettings.value(DeviceSetting.CustomPlayers))

        val (android, packages) = store(FakePlayerLauncher(players = listOf(MX_PLAYER), customPlayers = CustomPlayerKind.PACKAGE))
        assertEquals(listOf(false, true), packages.state.value.players.map { it.hasIcon }, "Android draws the apps' icons")
        packages.openAddCustom()
        val pkg = assertNotNull(packages.state.value.customForm)
        assertNull(pkg.placeholders)
        assertEquals(form.packageName, pkg.commandLabel)
        packages.saveCustom("VLC", "vlc", "")
        assertEquals(form.packageNameInvalid, packages.state.value.customForm?.error)
        packages.saveCustom("VLC", "org.videolan.vlc", "")
        android.settle()
        assertEquals("org.videolan.vlc", android.data.deviceSettings.value(DeviceSetting.CustomPlayers).single().command)

        val (_, none) = store(FakePlayerLauncher(customPlayers = null))
        assertNull(none.state.value.customHeader)
        assertNull(none.state.value.addCustomText)
        none.openAddCustom()
        assertNull(none.state.value.customForm)
    }

    @Test
    fun aDeviceWithoutPlayerAppsHasNoPlayerSetting() = runTest {
        val graph = testGraph()
        val app = AppSession(graph, "profile-1", FakeAppPlatform(), start = null, profileAccount = null, startProfile = graph.data.addProfile("profile-1"))
        app.navigator.navigate(Route.Settings)
        assertNull(app.settingsSession()?.player)
        app.close()
    }
}
