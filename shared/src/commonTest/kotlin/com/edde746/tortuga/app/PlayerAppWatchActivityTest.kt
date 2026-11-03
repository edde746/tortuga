package com.edde746.tortuga.app

import com.edde746.tortuga.playback.players.PlayerAppEvent
import com.edde746.tortuga.playback.session.MOVIE
import com.edde746.tortuga.playback.session.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** The watch activity of a play handed to a player app that reports back, as the addons that receive it get it. */
class PlayerAppWatchActivityTest {
    @Test
    fun aPlayerAppThatReportsBackSendsTheAgreedAddonStartPauseTheSeeksLandingsAndOneStop() = runTest {
        val w = PlayerAppsWorld(this)
        w.serveHostedLinks()
        val sent = mutableListOf<String>()
        for (host in listOf("tracker.example", "unasked.example")) {
            w.routes[host] = { request ->
                sent += "$host ${request.url.encodedPath}"
                json("{}")
            }
        }
        // The user agreed to one of the two on another of their devices; the synced list brings it.
        w.data.pullAddons(
            w.profileId, wallMillis = 1_000, serverVersion = 1,
            SyncedAddon("https://tracker.example/manifest.json", receiverManifest("tracker.example"), watchActivity = true),
            SyncedAddon("https://unasked.example/manifest.json", receiverManifest("unasked.example")),
        )
        val app = w.open()
        app.playback.play(w.movie(), VLC.id)
        w.settle()

        // VLC reports each second as it plays.
        w.launcher.report(PlayerAppEvent.Started)
        var position = 0L
        fun report(paused: Boolean) = w.launcher.report(PlayerAppEvent.Position(position, 100_000, paused))
        fun playTo(to: Long) {
            while (position < to) {
                position += 1000
                report(paused = false)
                w.advance(1.seconds)
            }
        }
        report(paused = false)
        w.settle()
        playTo(20_000)
        report(paused = true)
        w.settle()
        // A jump in the player while paused, the play on, then a jump back while it plays: seeks.
        position = 50_000
        report(paused = true)
        w.settle()
        report(paused = false)
        w.settle()
        position = 10_000
        report(paused = false)
        w.settle()
        playTo(15_000)
        // VLC is closed while it plays.
        w.launcher.report(PlayerAppEvent.Ended(15_000, 100_000, completed = false))
        w.settle()

        val path = "tracker.example /player/movie/${MOVIE.id}"
        assertEquals(
            listOf(
                "$path/action=start&currentTime=0&duration=100000.json",
                "$path/action=pause&currentTime=20000&duration=100000.json",
                "$path/action=pause&currentTime=50000&duration=100000.json",
                "$path/action=start&currentTime=50000&duration=100000.json",
                "$path/action=start&currentTime=10000&duration=100000.json",
                "$path/action=stop&currentTime=15000&duration=100000.json",
            ),
            sent,
            "a seek lands as start or pause; closed while playing, one stop and no pause; the addon without the agreement gets nothing",
        )
        app.close()
    }
}
