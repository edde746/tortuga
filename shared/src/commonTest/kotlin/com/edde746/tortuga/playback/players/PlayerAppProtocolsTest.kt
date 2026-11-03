package com.edde746.tortuga.playback.players

import com.edde746.tortuga.playback.players.PlayerAppEvent.Ended
import com.edde746.tortuga.playback.players.PlayerAppEvent.Position
import com.edde746.tortuga.playback.players.PlayerAppEvent.Started
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerAppProtocolsTest {
    private fun property(name: String, data: String?) =
        """{"event":"property-change","id":1,"name":"$name"${data?.let { ""","data":$it""" }.orEmpty()}}"""

    @Test
    fun mpvObservesPositionDurationPauseAndTheEnd() {
        val session = MpvIpcSession()
        // Escaped: Android's regex engine (ICU) refuses a bare `]}` as a syntax error, where the JVM reads it literally.
        val name = Regex(""""([a-z-]+)"\]\}""")
        assertEquals(listOf("time-pos", "duration", "pause", "eof-reached"), session.commands.map { name.find(it)!!.groupValues[1] })
        assertTrue(session.commands.all { it.startsWith("""{"command":["observe_property",""") })
    }

    @Test
    fun mpvLinesBecomeEventsOncePositionsMoveASecond() {
        val session = MpvIpcSession()
        assertEquals(emptyList(), session.onLine("""{"request_id":0,"error":"success"}"""))
        assertEquals(emptyList(), session.onLine(property("duration", "120.5")))
        assertEquals(listOf<PlayerAppEvent>(Started), session.onLine("""{"event":"file-loaded"}"""))
        assertEquals(listOf<PlayerAppEvent>(Position(0, 120_500, paused = false)), session.onLine(property("time-pos", "0.0")))
        assertEquals(emptyList(), session.onLine(property("time-pos", "0.5")))
        assertEquals(listOf<PlayerAppEvent>(Position(1_200, 120_500, paused = false)), session.onLine(property("time-pos", "1.2")))
        assertEquals(listOf<PlayerAppEvent>(Position(1_200, 120_500, paused = true)), session.onLine(property("pause", "true")))
        assertEquals(emptyList(), session.onLine(property("pause", "true")))
        // mpv drops the position as it closes the file; the last one stays.
        assertEquals(emptyList(), session.onLine(property("time-pos", null)))
        assertEquals(emptyList(), session.onLine("not json"))
        assertEquals(emptyList(), session.onLine("""{"event":"end-file","reason":"quit"}"""))
        assertEquals(Ended(1_200, 120_500, completed = false), session.ended())
    }

    @Test
    fun mpvPlayedToTheEndEndsAtTheDuration() {
        val session = MpvIpcSession()
        assertEquals(listOf(Started, Position(3_000, 0, paused = false)), session.onLine(property("time-pos", "3")))
        session.onLine(property("duration", "30"))
        session.onLine(property("time-pos", "29.8"))
        session.onLine("""{"event":"end-file","reason":"eof"}""")
        assertEquals(Ended(30_000, 30_000, completed = true), session.ended())
    }

    @Test
    fun mpvAtTheEndFinishedWhateverItsEndFileSays() {
        // As the Windows desktop's mpv ended a 30.058 s file after its audio output failed to open: its last frame's
        // position, then reason "error".
        val session = MpvIpcSession()
        session.onLine(property("duration", "30.058"))
        session.onLine(property("time-pos", "29.947"))
        session.onLine(property("time-pos", null))
        session.onLine("""{"event":"end-file","reason":"error","playlist_entry_id":1,"file_error":"audio output initialization failed"}""")
        assertEquals(Ended(30_058, 30_058, completed = true), session.ended())
    }

    @Test
    fun mpvJustShortOfTheEndToleranceDidNotFinishAShortVideo() {
        fun stoppedAt(position: String): Ended {
            val session = MpvIpcSession()
            session.onLine(property("duration", "20"))
            session.onLine(property("time-pos", position))
            session.onLine("""{"event":"end-file","reason":"quit"}""")
            return session.ended()
        }
        assertEquals(Ended(20_000, 20_000, completed = true), stoppedAt("17.0"))
        assertEquals(Ended(16_900, 20_000, completed = false), stoppedAt("16.9"))
    }

    @Test
    fun mpvPastTheCreditsThresholdFinishedWhereItStopped() {
        fun stoppedAt(position: String): Ended {
            val session = MpvIpcSession()
            session.onLine(property("duration", "100"))
            session.onLine(property("time-pos", position))
            session.onLine("""{"event":"end-file","reason":"quit"}""")
            return session.ended()
        }
        assertEquals(Ended(90_000, 100_000, completed = false), stoppedAt("90.0"))
        assertEquals(Ended(90_100, 100_000, completed = true), stoppedAt("90.1"))
    }

    @Test
    fun mpvStoppedMidFileDidNotFinish() {
        val session = MpvIpcSession()
        session.onLine(property("duration", "30.058"))
        session.onLine(property("time-pos", "15.0"))
        session.onLine("""{"event":"end-file","reason":"error","file_error":"loading failed"}""")
        assertEquals(Ended(15_000, 30_058, completed = false), session.ended())
    }

    @Test
    fun mpvAtEofWithoutAPositionFinishedAtTheDurationIfKnown() {
        val known = MpvIpcSession()
        known.onLine(property("duration", "30"))
        known.onLine("""{"event":"end-file","reason":"eof"}""")
        assertEquals(Ended(30_000, 30_000, completed = true), known.ended())

        val unknown = MpvIpcSession()
        unknown.onLine(property("eof-reached", "true"))
        assertEquals(Ended(null, null, completed = true), unknown.ended())
    }

    @Test
    fun mpvTakenBackFromTheEndItKeepsOpenDidNotFinish() {
        // keep-open: mpv stays at the end of the file; the user may seek back from there, then quit.
        fun quitAfter(vararg lines: String): Ended {
            val session = MpvIpcSession()
            session.onLine(property("duration", "100"))
            session.onLine(property("eof-reached", "false"))
            session.onLine(property("time-pos", "99.96"))
            session.onLine(property("eof-reached", "true"))
            lines.forEach { session.onLine(it) }
            session.onLine("""{"event":"end-file","reason":"quit"}""")
            return session.ended()
        }
        assertEquals(Ended(100_000, 100_000, completed = true), quitAfter(), "quit at the end")
        assertEquals(Ended(100_000, 100_000, completed = true), quitAfter(property("time-pos", "99.96")), "its last frame again")
        assertEquals(Ended(40_000, 100_000, completed = false), quitAfter(property("eof-reached", "false"), property("time-pos", "40")))
        assertEquals(Ended(40_000, 100_000, completed = false), quitAfter(property("time-pos", "40")), "a position short of the end says so too")
    }

    @Test
    fun mpvThatNeverPlayedEndsWithNothingKnown() {
        assertEquals(Ended(null, null, completed = false), MpvIpcSession().ended())
    }

    @Test
    fun vlcStatusesBecomeEvents() {
        val session = VlcHttpSession()
        assertEquals(emptyList(), session.onStatus("""{"state":"stopped","length":0,"time":0}"""))
        assertEquals(listOf(Started, Position(5_100, 30_000, paused = false)), session.onStatus("""{"state":"playing","length":30,"time":5,"position":0.17}"""))
        assertEquals(listOf<PlayerAppEvent>(Position(5_100, 30_000, paused = true)), session.onStatus("""{"state":"paused","length":30,"time":5,"position":0.17}"""))
        assertEquals(emptyList(), session.onStatus("<html>401</html>"))
        assertEquals(Ended(5_100, 30_000, completed = false), session.ended())
    }

    @Test
    fun vlcWhoseLastAnswerWasNearTheEndPlayedToTheEnd() {
        val session = VlcHttpSession()
        session.onStatus("""{"state":"playing","length":30,"time":28,"position":0.95}""")
        session.onStatus("""{"state":"stopped","length":0,"time":0,"position":0}""")
        assertEquals(Ended(30_000, 30_000, completed = true), session.ended())
    }

    @Test
    fun vlcWhoseLastAnswerWasPastTheCreditsFinishedThere() {
        val session = VlcHttpSession()
        session.onStatus("""{"state":"playing","length":100,"time":92,"position":0.92}""")
        assertEquals(Ended(92_000, 100_000, completed = true), session.ended())
    }

    @Test
    fun vlcWithoutALengthTakesTheTimeAndNeverCompletes() {
        val session = VlcHttpSession()
        assertEquals(listOf(Started, Position(12_000, 0, paused = false)), session.onStatus("""{"state":"playing","length":-1,"time":12,"position":0}"""))
        assertEquals(Ended(12_000, null, completed = false), session.ended())
    }

    @Test
    fun vlcTakesAnEmptyUserName() {
        assertEquals("Basic OnNlY3JldA==", VlcHttpSession.authorization("secret"))
    }
}
