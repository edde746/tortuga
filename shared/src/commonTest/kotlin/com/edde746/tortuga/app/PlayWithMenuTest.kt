package com.edde746.tortuga.app

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.downloads.FILM
import com.edde746.tortuga.downloads.cdnChoice
import com.edde746.tortuga.downloads.downloadsTest
import com.edde746.tortuga.playback.players.CustomPlayer
import com.edde746.tortuga.playback.players.PlayerApp
import com.edde746.tortuga.playback.players.PlayerAppCapabilities
import com.edde746.tortuga.playback.players.PlayerAppProgress
import com.edde746.tortuga.playback.resolve.lcgBytes
import com.edde746.tortuga.playback.session.MOVIE
import com.edde746.tortuga.presentation.downloads.downloaded
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.players.PlayWithEntry
import com.edde746.tortuga.presentation.players.PlayWithMenu
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** "Play with…" in the stream and download rows' menus: Tortuga and every player app this device has. */
class PlayWithMenuTest {
    private val tortuga = PlayWithEntry(PlayerApp.TORTUGA_ID, "Tortuga")

    @Test
    fun streamsListTortugaThenEveryPlayerAppButNotToDownload() = runTest {
        val w = PlayerAppsWorld(this)
        val app = w.open()
        app.navigator.openStreams(MOVIE, MOVIE.id)
        val streams = app.streamsStore(Route.Streams("movie", MOVIE.id, MOVIE.id))
        w.settle()
        val detected = listOf(PlayWithEntry(VLC.id, "VLC"), PlayWithEntry(MX_PLAYER.id, "MX Player"), PlayWithEntry(INFUSE.id, "Infuse"))
        assertEquals(PlayWithMenu("Play with…", listOf(tortuga) + detected), streams.state.value.playWith)

        // A custom player joins the menu, its capabilities read from its arguments.
        w.data.deviceSettings.set(DeviceSetting.CustomPlayers, listOf(CustomPlayer("1", "My mpv", "mpv", "--start={start} --sub-file={subtitle} {url}")))
        w.settle()
        assertEquals(listOf(tortuga) + detected + PlayWithEntry("custom:1", "My mpv"), streams.state.value.playWith?.entries)
        assertEquals(
            PlayerAppCapabilities(startPosition = true, subtitles = true, progress = PlayerAppProgress.NONE),
            assertNotNull(app.playerApps?.find("custom:1")).capabilities,
        )
        streams.release()

        app.navigator.openStreams(MOVIE, MOVIE.id, download = true)
        val downloading = app.streamsStore(Route.Streams("movie", MOVIE.id, MOVIE.id, download = true))
        w.settle()
        assertNull(downloading.state.value.playWith, "a pick there downloads")
        downloading.release()
        app.close()

        // A device without player apps has no menu.
        val graph = testGraph()
        val plain = AppSession(graph, "profile-1", FakeAppPlatform(), start = null, profileAccount = null, startProfile = graph.data.addProfile("profile-1"))
        plain.navigator.openStreams(MOVIE, MOVIE.id)
        val none = plain.streamsStore(Route.Streams("movie", MOVIE.id, MOVIE.id))
        runCurrent()
        assertNull(none.state.value.playWith)
        assertNull(plain.playerApps)
        none.release()
        plain.close()
    }

    @Test
    fun aDownloadPlaysWithAPlayerAppWhereTheAppsCanReadItsFile() = downloadsTest { h ->
        h.serveCdn(lcgBytes(100_000, seed = 23))
        val manager = h.install()
        val movie = h.downloaded(FILM, FILM.id, cdnChoice())
        val launcher = FakePlayerLauncher()
        val profile = h.data.profiles.profile(h.profileId)
        val app = AppSession(h.graph, h.profileId, FakeAppPlatform(playerApps = launcher), start = null, profileAccount = null, startProfile = profile)
        val downloads = assertNotNull(app.downloadsStore())
        h.settle()
        assertEquals(PlayerApp.TORTUGA_ID, downloads.state.value.playWith?.entries?.first()?.playerId)
        assertEquals(4, downloads.state.value.playWith?.entries?.size)

        downloads.playWith(movie.id, VLC.id)
        h.settle()
        val local = assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id))
        assertEquals(VLC, launcher.launches.single().first)
        assertEquals(local.path, launcher.media.url)
        assertEquals(true, launcher.media.local)
        downloads.release()
        app.close()

        // iPhone, iPad: other apps can't read Tortuga's files.
        val phone = AppSession(h.graph, h.profileId, FakeAppPlatform(playerApps = FakePlayerLauncher(handsOffFiles = false)), start = null, profileAccount = null, startProfile = profile)
        val phoneDownloads = assertNotNull(phone.downloadsStore())
        h.settle()
        assertNull(phoneDownloads.state.value.playWith)
        phoneDownloads.release()
        phone.close()
    }
}
