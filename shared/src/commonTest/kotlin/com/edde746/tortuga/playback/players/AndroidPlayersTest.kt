package com.edde746.tortuga.playback.players

import com.edde746.tortuga.playback.players.AndroidExtra.Flag
import com.edde746.tortuga.playback.players.AndroidExtra.IntValue
import com.edde746.tortuga.playback.players.AndroidExtra.LongValue
import com.edde746.tortuga.playback.players.AndroidExtra.Text
import com.edde746.tortuga.playback.players.AndroidExtra.Texts
import com.edde746.tortuga.playback.players.AndroidExtra.Uris
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidPlayersTest {
    private val english = PlayerAppSubtitle("https://subs.example/en.srt", local = false, title = "OpenSubtitles", language = "en", selected = true)
    private val german = PlayerAppSubtitle("/data/no_backup/downloads/1/de.srt", local = true, title = "German", language = "de")
    private val subtitles = listOf(
        AndroidSubtitle("content://com.edde746.tortuga.playerfiles/de.srt", german),
        AndroidSubtitle("https://subs.example/en.srt", english),
    )
    private val media = PlayerAppMedia(
        url = "https://cdn.example/video.mkv?key=secret",
        local = false,
        headers = mapOf("User-Agent" to "Tortuga/1", "Authorization" to "Bearer k"),
        title = "Chernobyl S1E2",
        startSeconds = 754.25,
        subtitles = listOf(german, english),
        filename = "Chernobyl.S01E02.mkv",
    )
    private val fromTheBeginning = media.copy(startSeconds = 0.0, headers = emptyMap(), filename = null, subtitles = emptyList())

    private fun extras(api: AndroidPlayerApi, media: PlayerAppMedia = this.media, subtitles: List<AndroidSubtitle> = this.subtitles) =
        AndroidPlayers.extras(api, media, subtitles, "en")

    @Test
    fun knownPackagesSpeakTheirOwnInterfaceAndTheRestMxPlayers() {
        assertEquals(AndroidPlayerApi.VLC, AndroidPlayers.of("org.videolan.vlc").api)
        assertEquals(AndroidPlayerApi.MPV, AndroidPlayers.of("is.xyz.mpv").api)
        assertEquals(AndroidPlayer("com.mxtech.videoplayer.ad", AndroidPlayerApi.MX, "com.mxtech.videoplayer.ad.ActivityScreen"), AndroidPlayers.of("com.mxtech.videoplayer.ad"))
        assertEquals(AndroidPlayer("com.mxtech.videoplayer.pro", AndroidPlayerApi.MX, "com.mxtech.videoplayer.ActivityScreen"), AndroidPlayers.of("com.mxtech.videoplayer.pro"))
        assertEquals(AndroidPlayerApi.JUST_PLAYER, AndroidPlayers.of("com.brouken.player").api)
        assertEquals(AndroidPlayerApi.VIMU, AndroidPlayers.of("net.gtvbox.videoplayer").api)
        assertEquals(AndroidPlayerApi.VIMU, AndroidPlayers.of("net.gtvbox.vimuhd").api)
        assertEquals(AndroidPlayerApi.KODI, AndroidPlayers.of("org.xbmc.kodi").api)
        assertEquals(AndroidPlayerApi.NOVA, AndroidPlayers.of("org.courville.nova").api)
        assertEquals(AndroidPlayer("com.example.player", AndroidPlayerApi.GENERIC), AndroidPlayers.of("com.example.player"))
        assertEquals(PlayerAppProgress.NONE, AndroidPlayers.chooserCapabilities.progress)
        assertFalse(AndroidPlayerApi.VLC.capabilities.subtitles, "VLC's subtitle extra is a file path")
        assertEquals(PlayerAppCapabilities.URL_ONLY, AndroidPlayerApi.KODI.capabilities)
    }

    @Test
    fun onlyAppsWhoseStartExtraIsDocumentedAreHandedTheResumePoint() {
        val resuming = AndroidPlayerApi.entries.filter { it.capabilities.startPosition }
        assertEquals(listOf(AndroidPlayerApi.MX, AndroidPlayerApi.JUST_PLAYER, AndroidPlayerApi.MPV, AndroidPlayerApi.VLC, AndroidPlayerApi.VIMU), resuming)
        assertFalse(AndroidPlayerApi.GENERIC.capabilities.startPosition, "an unknown app (Google Photos) starts from the beginning")
        assertTrue(AndroidPlayers.chooserCapabilities.startPosition, "the app picked in the chooser may read the resume point")
        // A custom package is the app it names.
        assertEquals(AndroidPlayerApi.VLC.capabilities, CustomPlayer("1", "VLC", " org.videolan.vlc ", "").asPlayerApp(CustomPlayerKind.PACKAGE).capabilities)
        assertEquals(AndroidPlayerApi.GENERIC.capabilities, CustomPlayer("2", "Photos", "com.google.android.apps.photos", "").asPlayerApp(CustomPlayerKind.PACKAGE).capabilities)
    }

    @Test
    fun mxPlayerGetsTheStartTitleHeadersAndNamedSubtitles() {
        assertEquals(
            listOf(
                IntValue("position", 754_250),
                Flag("return_result", true),
                Flag("secure_uri", true),
                Text("title", "Chernobyl S1E2"),
                Text("filename", "Chernobyl.S01E02.mkv"),
                Texts("headers", listOf("User-Agent", "Tortuga/1", "Authorization", "Bearer k")),
                Uris("subs", listOf("content://com.edde746.tortuga.playerfiles/de.srt", "https://subs.example/en.srt")),
                Texts("subs.name", listOf("German", "English · \u2068OpenSubtitles\u2069")),
                Uris("subs.enable", listOf("https://subs.example/en.srt")),
            ),
            extras(AndroidPlayerApi.MX),
        )
        assertEquals(extras(AndroidPlayerApi.MX), extras(AndroidPlayerApi.GENERIC), "any other app gets MX Player's extras")
    }

    @Test
    fun fromTheBeginningMxPlayerStillGetsAPositionSoItDoesNotOfferItsOwnResume() {
        assertEquals(
            listOf(IntValue("position", 0), Flag("return_result", true), Flag("secure_uri", true), Text("title", "Chernobyl S1E2")),
            extras(AndroidPlayerApi.MX, fromTheBeginning, emptyList()),
        )
    }

    @Test
    fun justPlayerAndMpvGetTheirOwnSubsets() {
        assertEquals(
            listOf(
                IntValue("position", 754_250),
                Flag("return_result", true),
                Text("title", "Chernobyl S1E2"),
                Uris("subs", listOf("content://com.edde746.tortuga.playerfiles/de.srt", "https://subs.example/en.srt")),
                Texts("subs.name", listOf("German", "English · \u2068OpenSubtitles\u2069")),
                Uris("subs.enable", listOf("https://subs.example/en.srt")),
            ),
            extras(AndroidPlayerApi.JUST_PLAYER),
        )
        assertEquals(
            listOf(
                IntValue("position", 0),
                Text("title", "Chernobyl S1E2"),
                Uris("subs", listOf("content://com.edde746.tortuga.playerfiles/de.srt", "https://subs.example/en.srt")),
                Uris("subs.enable", listOf("https://subs.example/en.srt")),
            ),
            extras(AndroidPlayerApi.MPV, fromTheBeginning),
        )
    }

    @Test
    fun mpvResumesWithoutSubtitlesSinceItAddsThemBeforeItSetsTheStart() {
        assertEquals(listOf(IntValue("position", 754_250), Text("title", "Chernobyl S1E2")), extras(AndroidPlayerApi.MPV))
    }

    @Test
    fun noSubtitleIsEnabledWhenNoneIsSelected() {
        val unselected = subtitles.map { it.copy(subtitle = it.subtitle.copy(selected = false)) }
        val mpv = extras(AndroidPlayerApi.MPV, fromTheBeginning, unselected)
        assertEquals(listOf("position", "title", "subs"), mpv.map { it.key })
        assertEquals(listOf("startfrom", "forceresume", "forcename"), extras(AndroidPlayerApi.VIMU, subtitles = unselected).map { it.key })
    }

    @Test
    fun vlcTakesALongPositionOrStartsFromTheBeginning() {
        assertEquals(
            listOf(LongValue("position", 754_250L), Flag("from_start", false), Text("title", "Chernobyl S1E2")),
            extras(AndroidPlayerApi.VLC),
        )
        assertEquals(listOf(Flag("from_start", true), Text("title", "Chernobyl S1E2")), extras(AndroidPlayerApi.VLC, fromTheBeginning))
    }

    @Test
    fun vimuGetsItsOwnKeysAndTheSelectedSubtitle() {
        assertEquals(
            listOf(
                IntValue("startfrom", 754_250),
                Flag("forceresume", false),
                Text("forcename", "Chernobyl S1E2"),
                Text("forcedsrt", "https://subs.example/en.srt"),
            ),
            extras(AndroidPlayerApi.VIMU),
        )
        assertEquals(listOf(Flag("forceresume", false), Text("forcename", "Chernobyl S1E2")), extras(AndroidPlayerApi.VIMU, fromTheBeginning, emptyList()))
    }

    @Test
    fun kodiGetsOnlyTheUrlAndNovaTheTitle() {
        assertEquals(emptyList(), extras(AndroidPlayerApi.KODI))
        assertEquals(listOf(Text("title", "Chernobyl S1E2")), extras(AndroidPlayerApi.NOVA))
    }

    @Test
    fun aStartBeyondWhatAnIntHoldsIsClampedAndABlankTitleLeftOut() {
        val long = media.copy(startSeconds = 3_000_000.0, title = " ", subtitles = emptyList(), headers = emptyMap(), filename = null)
        assertEquals(listOf(IntValue("position", Int.MAX_VALUE)), extras(AndroidPlayerApi.MPV, long, emptyList()))
    }

    @Test
    fun subtitleNamesFollowTheTrackMenu() {
        assertEquals("English", AndroidPlayers.subtitleName(english.copy(title = null), "en"))
        assertEquals("English", AndroidPlayers.subtitleName(english.copy(title = "english"), "en"))
        assertEquals("English · \u2068OpenSubtitles\u2069", AndroidPlayers.subtitleName(english, "en"), "the title isolated beside the language")
        assertEquals("Addon", AndroidPlayers.subtitleName(english.copy(language = null, title = "Addon"), "en"))
        assertEquals(null, AndroidPlayers.subtitleName(english.copy(language = null, title = null), "en"))
    }

    // Results

    private fun ended(api: AndroidPlayerApi, action: String?, resultCode: Int, vararg extras: Pair<String, Any?>) =
        AndroidPlayers.ended(api, action, resultCode, mapOf(*extras))

    @Test
    fun mxPlayerSaysWhereTheUserLeftOrThatItPlayedToTheEnd() {
        val mx = "com.mxtech.intent.result.VIEW"
        assertEquals(
            PlayerAppEvent.Ended(754_000, 3_600_000, completed = false),
            ended(AndroidPlayerApi.MX, mx, AndroidPlayers.RESULT_OK, "position" to 754_000, "duration" to 3_600_000, "end_by" to "user"),
        )
        assertEquals(
            PlayerAppEvent.Ended(null, null, completed = true),
            ended(AndroidPlayerApi.MX, mx, AndroidPlayers.RESULT_OK, "end_by" to "playback_completion"),
        )
        assertEquals(PlayerAppEvent.Ended(null, null, completed = false), ended(AndroidPlayerApi.MX, null, AndroidPlayers.RESULT_CANCELED))
    }

    @Test
    fun justPlayerAnswersInMxPlayersInterface() {
        assertEquals(
            PlayerAppEvent.Ended(null, null, completed = true),
            ended(AndroidPlayerApi.JUST_PLAYER, "com.mxtech.intent.result.VIEW", AndroidPlayers.RESULT_OK, "end_by" to "playback_completion"),
        )
        assertEquals(
            PlayerAppEvent.Ended(1_000, 120_000, completed = false),
            ended(AndroidPlayerApi.JUST_PLAYER, "com.mxtech.intent.result.VIEW", AndroidPlayers.RESULT_OK, "end_by" to "user", "position" to 1_000, "duration" to 120_000),
        )
    }

    @Test
    fun mpvLeavesThePositionOutWhenPlaybackFinishedNormally() {
        val mpv = "is.xyz.mpv.MPVActivity.result"
        assertEquals(
            PlayerAppEvent.Ended(754_000, 3_600_000, completed = false),
            ended(AndroidPlayerApi.MPV, mpv, AndroidPlayers.RESULT_OK, "position" to 754_000, "duration" to 3_600_000),
        )
        assertEquals(PlayerAppEvent.Ended(null, null, completed = true), ended(AndroidPlayerApi.MPV, mpv, AndroidPlayers.RESULT_OK))
        assertEquals(PlayerAppEvent.Ended(null, null, completed = false, failed = true), ended(AndroidPlayerApi.MPV, mpv, AndroidPlayers.RESULT_CANCELED), "playback didn't start")
    }

    @Test
    fun vlcReportsLongExtrasAndNothingOnceItStopped() {
        val vlc = "org.videolan.vlc.player.result"
        assertEquals(
            PlayerAppEvent.Ended(754_000, 3_600_000, completed = false),
            ended(AndroidPlayerApi.VLC, vlc, AndroidPlayers.RESULT_OK, "extra_position" to 754_000L, "extra_duration" to 3_600_000L, "extra_uri" to "https://cdn.example/video.mkv"),
        )
        // Its playback stopped (the end of the video): VLC zeroes both.
        assertEquals(PlayerAppEvent.Ended(null, null, completed = false), ended(AndroidPlayerApi.VLC, vlc, AndroidPlayers.RESULT_OK, "extra_position" to 0L, "extra_duration" to 0L))
        assertEquals(PlayerAppEvent.Ended(0, 3_600_000, completed = false), ended(AndroidPlayerApi.VLC, vlc, AndroidPlayers.RESULT_OK, "extra_position" to 0L, "extra_duration" to 3_600_000L))
    }

    @Test
    fun vimuAnswersWithACodeWithOrWithoutItsAction() {
        val vimu = "net.gtvbox.videoplayer.result"
        assertEquals(PlayerAppEvent.Ended(null, null, completed = true), ended(AndroidPlayerApi.VIMU, vimu, 1))
        assertEquals(PlayerAppEvent.Ended(5_000, 60_000, completed = false), ended(AndroidPlayerApi.VIMU, vimu, 0, "position" to 5_000, "duration" to 60_000))
        assertEquals(PlayerAppEvent.Ended(null, null, completed = false, failed = true), ended(AndroidPlayerApi.VIMU, vimu, 4, "position" to 5_000))
        // Older versions set no action: the launched package's interface reads the code.
        assertEquals(PlayerAppEvent.Ended(null, null, completed = true), ended(AndroidPlayerApi.VIMU, null, 1))
    }

    @Test
    fun aPlayerThatSaysItFailedIsToldFromOneThatSaysNothing() {
        val mx = "com.mxtech.intent.result.VIEW"
        val vlc = "org.videolan.vlc.player.result"
        val notStarted = PlayerAppEvent.Ended(null, null, completed = false, failed = true)
        assertEquals(notStarted, ended(AndroidPlayerApi.MX, mx, AndroidPlayers.RESULT_CANCELED), "cancelled before any playback started")
        assertEquals(notStarted, ended(AndroidPlayerApi.MX, mx, 1), "an error before it played")
        assertEquals(
            PlayerAppEvent.Ended(754_000, 3_600_000, completed = false, failed = true),
            ended(AndroidPlayerApi.MX, mx, 1, "position" to 754_000, "duration" to 3_600_000, "end_by" to "user"),
            "an error once it played: where it stopped",
        )
        assertEquals(notStarted, ended(AndroidPlayerApi.VLC, vlc, 2), "its service connection failed")
        assertEquals(
            PlayerAppEvent.Ended(754_000, 3_600_000, completed = false, failed = true),
            ended(AndroidPlayerApi.VLC, vlc, 4, "extra_position" to 754_000L, "extra_duration" to 3_600_000L),
            "the video track lost",
        )
        // Without the player's answer, Android's default code says nothing.
        assertEquals(PlayerAppEvent.Ended(null, null, completed = false), ended(AndroidPlayerApi.MPV, null, AndroidPlayers.RESULT_CANCELED))
        assertEquals(PlayerAppEvent.Ended(null, null, completed = false), ended(AndroidPlayerApi.MX, null, AndroidPlayers.RESULT_CANCELED))
    }

    @Test
    fun anotherAppIsReadForMxOrVlcKeysInAnyNumberForm() {
        assertEquals(PlayerAppEvent.Ended(null, null, completed = false), ended(AndroidPlayerApi.GENERIC, null, AndroidPlayers.RESULT_CANCELED))
        assertEquals(
            PlayerAppEvent.Ended(12_000, 90_000, completed = false),
            ended(AndroidPlayerApi.GENERIC, "com.example.result", AndroidPlayers.RESULT_OK, "extra_position" to "12000", "extra_duration" to 90_000L),
        )
        assertEquals(
            PlayerAppEvent.Ended(null, 90_000, completed = true),
            ended(AndroidPlayerApi.GENERIC, null, AndroidPlayers.RESULT_OK, "end_by" to "playback_completion", "duration" to 90_000),
        )
        assertEquals(PlayerAppEvent.Ended(null, null, completed = false), ended(AndroidPlayerApi.GENERIC, null, AndroidPlayers.RESULT_OK, "position" to "soon", "duration" to -1))
    }

    @Test
    fun aPositionPastTheDurationIsTheEndAndAZeroDurationUnknown() {
        assertEquals(PlayerAppEvent.Ended(60_000, 60_000, completed = true), ended(AndroidPlayerApi.MX, null, AndroidPlayers.RESULT_OK, "position" to 61_000, "duration" to 60_000))
        assertEquals(PlayerAppEvent.Ended(61_000, null, completed = false), ended(AndroidPlayerApi.VLC, null, AndroidPlayers.RESULT_OK, "extra_position" to 61_000L, "extra_duration" to 0L))
    }

    @Test
    fun aPlayerThatStoppedAtTheEndOrPastTheCreditsFinishedWhateverItSaid() {
        val mx = "com.mxtech.intent.result.VIEW"
        val vlc = "org.videolan.vlc.player.result"
        assertEquals(
            PlayerAppEvent.Ended(3_598_000, 3_600_000, completed = true),
            ended(AndroidPlayerApi.MX, mx, AndroidPlayers.RESULT_OK, "position" to 3_598_000, "duration" to 3_600_000, "end_by" to "user"),
        )
        assertEquals(
            PlayerAppEvent.Ended(3_250_000, 3_600_000, completed = true),
            ended(AndroidPlayerApi.VLC, vlc, AndroidPlayers.RESULT_OK, "extra_position" to 3_250_000L, "extra_duration" to 3_600_000L),
        )
        assertEquals(
            PlayerAppEvent.Ended(3_240_000, 3_600_000, completed = false),
            ended(AndroidPlayerApi.VIMU, null, 0, "position" to 3_240_000, "duration" to 3_600_000),
        )
    }
}
