package com.edde746.tortuga.watch.e2e

import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.input.WatchCommand
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.watch.WatchText
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.watch.WatchControlMode
import com.edde746.tortuga.watch.WatchFailure
import com.edde746.tortuga.watch.WatchPlayback
import kotlinx.coroutines.runBlocking
import party.openwatch.Control
import party.openwatch.EndReason
import party.openwatch.Match
import party.openwatch.MatchState
import party.openwatch.Media
import party.openwatch.MediaKind
import party.openwatch.Phase
import party.openwatch.PlayerState
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Watch together end to end on a real Open Watch Party relay (`-Ptortuga.e2e.watchRelay`): whole apps (graph, profile
 * session, player store and session, the room) on main threads of their own, fake addons whose files differ in
 * duration and size, and simulated players that play in real time ([WatchWorld]). Sam hosts with his addon's file;
 * Anna's addon has a file of the same length among others, Ben's only files of other lengths, Cleo's fails every
 * stream request, and Dana's file has the host's length but can't seek; Olly is another app.
 */
class WatchTogetherEndToEndTest {
    private val movieHost = MediaFile("host-movie.mkv", 1_000_000, 5400.0, seed = 1)
    private val movieNear = MediaFile("guest-movie-near.mkv", 1_000_500, 5430.0, seed = 2)
    private val movieMatch = MediaFile("guest-movie-match.mkv", 1_400_000, 5400.5, seed = 3)
    private val movieFar = MediaFile("guest-movie-far.mkv", 2_000_000, 5100.0, seed = 4)

    /** The host's length, in a stream without an index: it plays, but can't seek. */
    private val movieNoIndex = MediaFile("guest-movie-noindex.mkv", 1_400_100, 5400.5, seed = 5, seekable = false)

    private val metas = mapOf(MOVIE to """{"id":"$MOVIE","type":"movie","name":"$TITLE"}""")

    private val hostAddon = FakeAddon("host", "Host streams", metas, mapOf(MOVIE to listOf(Offer(movieHost, "Host 1080p"))))

    /** Anna's addon: a file of the host's length among others, listed in an order the ranking overrides. */
    private val annaAddon = FakeAddon(
        "anna", "Anna streams", metas,
        mapOf(MOVIE to listOf(Offer(movieFar, "Anna far"), Offer(movieNear, "Anna near"), Offer(movieMatch, "Anna match"))),
    )

    /** Ben's addon: none of its files is within 1.5 s of the host's. */
    private val benAddon = FakeAddon("ben", "Ben streams", metas, mapOf(MOVIE to listOf(Offer(movieFar, "Ben far"), Offer(movieNear, "Ben near"))))

    /** Cleo's addon: its metas answer, every stream request fails. */
    private val cleoAddon = FakeAddon("cleo", "Cleo streams", metas, emptyMap(), streamsFail = true)

    /** Dana's addon: the host's length in a file that can't seek. */
    private val danaAddon = FakeAddon("dana", "Dana streams", metas, mapOf(MOVIE to listOf(Offer(movieNoIndex, "Dana no index"))))

    private fun world(test: suspend WatchWorld.() -> Unit) = runBlocking {
        WatchWorld.start(
            listOf(movieHost, movieNear, movieMatch, movieFar, movieNoIndex),
            listOf(hostAddon, annaAddon, benAddon, cleoAddon, danaAddon),
        ).use { it.test() }
    }

    private suspend fun WatchWorld.host() = app("Sam", listOf("host"))

    private suspend fun WatchWorld.anna(name: String = "Anna") = app(name, listOf("anna"))

    private suspend fun WatchWorld.ben() = app("Ben", listOf("ben"))

    /**
     * The host starts a room in [mode] before playing anything, and each of [guests] joins it with the code as the
     * host's lobby shows it ("K7M-Q2X"), typed in their own lobby.
     */
    private suspend fun WatchWorld.openRoom(host: TestApp, mode: WatchControlMode, vararg guests: TestApp): String {
        host.on { host.watch.create(mode) }
        until("${host.name}'s room") { host.room != null }
        val code = host.room!!.code
        val hostLobby = host.lobby()
        until("${host.name}'s lobby shows the room") { hostLobby.state.value.room != null }
        val shown = hostLobby.state.value.room!!.code
        assertEquals(code.substring(0, 3) + "-" + code.substring(3), shown)
        for (guest in guests) {
            val guestLobby = guest.lobby()
            guest.on {
                guestLobby.setJoinText(shown.lowercase())
                guestLobby.join()
            }
            until("${guest.name} in the room") { guest.room?.code == code }
        }
        val everyone = listOf(host) + guests
        until("everyone in everyone's roster") { everyone.all { it.room?.participants?.size == everyone.size } }
        return code
    }

    /** The host plays the movie; every guest finds its file and they all play it together. */
    private suspend fun WatchWorld.playMovieTogether(host: TestApp, vararg guests: TestApp) {
        host.play("movie", MOVIE, MOVIE)
        val everyone = listOf(host) + guests
        until("everyone playing the movie together", 40.seconds) { everyone.all { it.playing() && it.attached() } && inSync(everyone) }
    }

    /** Within [TOLERANCE] of each other at one instant. */
    private fun inSync(apps: List<TestApp>) = apps.all { it.active != null } && spread(apps) <= TOLERANCE

    private fun WatchWorld.urls(vararg files: MediaFile) = files.map(::fileUrl)

    private suspend fun TestApp.dispatch(command: PlayerCommand) = on { store.dispatch(command) }

    private suspend fun TestApp.watchCommand(command: WatchCommand) = dispatch(PlayerCommand.Watch(command))

    @Test
    fun aGuestFindsItsOwnFileByLengthAndFollowsTheHostsPlayPauseSeekAndSpeed() = world {
        val host = host()
        val anna = anna()
        openRoom(host, WatchControlMode.HOST_ONLY, anna)
        val both = listOf(host, anna)
        playMovieTogether(host, anna)

        // Anna's addon: the candidate closest in size to the host's file first (30 s longer), then the one of the same
        // length; never the farthest, though it is first in her addon's list.
        assertEquals(urls(movieNear, movieMatch), anna.engine.loads, "Anna's probes")
        assertEquals(urls(movieHost), host.engine.loads)
        // The room waited for Anna's file, then started both together.
        val startSpread = both.map { it.engine.starts.first().second }.let { it.max() - it.min() }
        assertTrue(startSpread < 300_000_000, "started within 300 ms of each other: ${startSpread / 1e6} ms")

        // The descriptor: the host's file, its IMDb id, and Tortuga's own ids for a Tortuga guest.
        val media = assertNotNull(anna.party.media)
        assertEquals(MediaKind.MOVIE, media.kind)
        assertEquals(TITLE, media.title)
        assertEquals(5_400_000L, media.durationMs)
        assertEquals(MOVIE, media.ids["imdb"])
        assertEquals(movieHost.size.toLong(), media.file?.sizeBytes)
        assertEquals(movieHost.name, media.file?.name)
        assertTrue(media.ext?.containsKey("app.usetortuga") == true, "${media.ext}")
        val annaId = assertNotNull(host.participant("Anna")).peerId
        until("the host sees Anna's match") { host.participant("Anna")?.match == Match(MatchState.MATCHED, 500) }
        val hostLobby = host.lobby()
        until("the host's lobby says so") {
            hostLobby.state.value.room?.participants?.firstOrNull { it.peerId == annaId }?.status == T.watch.status.sameLength(WatchText.delta(500))
        }

        // Probing recorded nothing: Anna's trackers heard one video start (the file she plays), her stream history holds it.
        until("Anna's stream history") {
            anna.on { anna.graph.data.streamHistory.lastStream(anna.profileId, MOVIE, MOVIE) }?.stream?.behaviorHints?.filename == movieMatch.name
        }
        val heard = anna.trackerEvents()
        assertEquals(1, heard.count { it.startsWith("started") }, "Anna's trackers: $heard")

        // A guest in a host-only room can't control playback.
        assertFalse(assertNotNull(anna.store.state.value.watch).canControl)
        assertTrue(assertNotNull(host.store.state.value.watch).canControl)

        host.dispatch(PlayerCommand.Pause)
        until("Anna paused with the host") { host.paused() && anna.paused() && inSync(both) }
        until("Anna told") { anna.heard("pause", "Sam") }

        host.dispatch(PlayerCommand.Play)
        until("both playing again") { host.playing() && anna.playing() && inSync(both) }
        until("Anna told") { anna.heard("play", "Sam") }

        host.dispatch(PlayerCommand.SeekTo(1800.0))
        until("Anna at the host's new position") { anna.playing() && anna.position() in 1799.0..1815.0 && inSync(both) }
        until("Anna told") { anna.heard("seek", "Sam") }

        host.dispatch(PlayerCommand.SetSpeed(1.5))
        until("Anna at the host's speed") { anna.room?.rate == 1.5 && abs(anna.engine.state.speed - 1.5) <= 1.5 * NUDGE + 1e-6 }
        until("in sync at 1.5×") { host.playing() && anna.playing() && inSync(both) }
        until("Anna told") { anna.heard("rate", "Sam") }

        host.dispatch(PlayerCommand.SetSpeed(1.0))
        until("Anna back at 1×") { anna.room?.rate == 1.0 && abs(anna.engine.state.speed - 1.0) <= NUDGE + 1e-6 && inSync(both) }
    }

    @Test
    fun aRoomStartedOverAPausedPlayerStaysPausedUntilTheHostPlays() = world {
        val host = host()
        val anna = anna()
        host.play("movie", MOVIE, MOVIE)
        until("Sam playing alone") { host.playing() }
        host.dispatch(PlayerCommand.Pause)
        until("Sam paused") { host.paused() }
        val starts = host.engine.starts.size

        // Started from the player's options, the room shares the movie, paused where Sam left it.
        host.watchCommand(WatchCommand.Start(WatchControlMode.HOST_ONLY))
        until("the room paused on Sam's movie") { host.attached() && host.party.media != null && host.room?.phase == Phase.PAUSED }
        val code = host.room!!.code
        val annaLobby = anna.lobby()
        anna.on {
            annaLobby.setJoinText(code)
            annaLobby.join()
        }
        until("Anna paused on her file with the room", 40.seconds) { anna.attached() && anna.paused() && anna.room?.phase == Phase.PAUSED }
        assertTrue(host.paused())
        assertEquals(starts, host.engine.starts.size, "Sam's player never resumed")

        val both = listOf(host, anna)
        host.dispatch(PlayerCommand.Play)
        until("both playing together") { both.all { it.playing() } && inSync(both) }
    }

    @Test
    fun aGuestWithoutAFileOfTheSameLengthIsAskedAndPlaysTheClosestAnywayWithAnOffset() = world {
        val host = host()
        val ben = ben()
        openRoom(host, WatchControlMode.HOST_ONLY, ben)
        val both = listOf(host, ben)
        host.play("movie", MOVIE, MOVIE)

        // Neither of Ben's files is within 1.5 s of the host's: he is asked about the closest, 30 s longer.
        until("Ben asked") { ben.prompt() != null }
        val prompt = assertNotNull(ben.prompt())
        assertEquals(T.watch.player.longer(WatchText.delta(30_000), isolate("Sam")), prompt.message)
        assertEquals(listOf(WatchCommand.PlayAnyway, WatchCommand.ChooseStream, WatchCommand.DontPlay), prompt.actions.map { it.command })
        assertEquals(urls(movieNear, movieFar), ben.engine.loads, "both opened, the closer in size first")
        assertTrue(ben.engine.state.paused)
        assertFalse(ben.attached())
        // The room waits for his answer.
        val benId = assertNotNull(host.participant("Ben")).peerId
        until("the room waiting for Ben") { host.room?.phase == Phase.WAITING && host.room?.waitingOn == listOf(benId) }
        assertFalse(host.playing())

        // Play anyway: he plays the closest file with the room.
        ben.watchCommand(prompt.actions.first { it.command == WatchCommand.PlayAnyway }.command)
        until("both playing together") { host.playing() && ben.playing() && ben.plays(movieNear) && ben.attached() && inSync(both) }
        until("the prompt gone") { ben.prompt() == null }
        assertEquals(urls(movieNear, movieFar, movieNear), ben.engine.loads)
        until("the host sees Ben's mismatch") { host.participant("Ben")?.match == Match(MatchState.MISMATCHED, 30_000) }
        val hostLobby = host.lobby()
        until("the host's lobby says so") {
            hostLobby.state.value.room?.participants?.firstOrNull { it.peerId == benId }?.status == T.watch.status.longer(WatchText.delta(30_000))
        }

        // His file starts later: the offset panel moves him 5 s ahead of the room...
        until("Ben's offset panel") { ben.store.state.value.watch?.offset != null }
        val offset = assertNotNull(ben.store.state.value.watch?.offset)
        assertEquals(WatchText.signedDelta(0), offset.value)
        val ahead = offset.actions.single { it.command == WatchCommand.NudgeOffset(5_000) }
        ben.watchCommand(ahead.command)
        until("Ben 5 s ahead of the host") {
            ben.party.offsetMs == 5_000L && host.playing() && ben.playing() && abs(ben.position() - host.position() - 5.0) <= TOLERANCE
        }
        until("the host sees his offset") { host.participant("Ben")?.offsetMs == 5_000L }
        until("the host's lobby shows it") {
            hostLobby.state.value.room?.participants?.firstOrNull { it.peerId == benId }?.offset == T.watch.status.offset(WatchText.signedDelta(5_000))
        }
        until("Ben's panel shows it") { ben.store.state.value.watch?.offset?.value == WatchText.signedDelta(5_000) }

        // ...and Reset puts him back in step.
        ben.watchCommand(WatchCommand.ResetOffset)
        until("Ben back in step") { ben.party.offsetMs == 0L && host.participant("Ben")?.offsetMs == 0L && ben.playing() && inSync(both) }
    }

    @Test
    fun aGuestWhoDoesntPlayTheClosestFileDoesntHoldTheRoomAndAStreamPickedByHandBecomesItsFile() = world {
        val host = host()
        val ben = ben()
        openRoom(host, WatchControlMode.HOST_ONLY, ben)
        val both = listOf(host, ben)
        host.play("movie", MOVIE, MOVIE)

        until("Ben asked") { ben.prompt()?.actions?.any { it.command == WatchCommand.DontPlay } == true }
        ben.watchCommand(WatchCommand.DontPlay)
        until("the room playing without him") { host.playing() && host.room?.phase == Phase.PLAYING }
        until("the host sees he declined") { host.participant("Ben")?.match?.state == MatchState.UNAVAILABLE }
        until("Ben told what he declined") { ben.prompt()?.message == T.watch.player.declined(isolate(TITLE)) }
        assertEquals(listOf(WatchCommand.ChooseStream), ben.prompt()!!.actions.map { it.command })
        assertFalse(ben.attached())
        assertEquals(emptyList(), ben.trackerEvents(), "nothing of his recorded")
        val hostLobby = host.lobby()
        val benId = assertNotNull(host.participant("Ben")).peerId
        until("the host's lobby says so") { hostLobby.state.value.room?.participants?.firstOrNull { it.peerId == benId }?.status == T.watch.status.declined }

        // Choose another stream: the Streams sheet over the player, where the stream he picks (300 s shorter, which the
        // room never offered him) becomes his file.
        ben.watchCommand(WatchCommand.ChooseStream)
        assertEquals(Route.Streams("movie", MOVIE, MOVIE), ben.on { ben.session.navigator.current })
        val (meta, streams) = ben.title("movie", MOVIE, MOVIE)
        val far = streams.single { it.stream.behaviorHints.filename == movieFar.name }
        ben.on { ben.session.playback.play(PlayRequest(meta, MOVIE, far)) }
        until("Ben playing his pick with the room") { ben.plays(movieFar) && ben.playing() && ben.attached() && inSync(both) }
        assertEquals(Route.Player, ben.on { ben.session.navigator.current })
        assertEquals(urls(movieNear, movieFar, movieFar), ben.engine.loads, "the two probes, then his pick")
        assertEquals(movieFar.name, ben.party.choice?.ref?.choice?.stream?.behaviorHints?.filename)
        until("the host sees his pick") { host.participant("Ben")?.match == Match(MatchState.MISMATCHED, -300_000) }
        until("no prompt") { ben.prompt() == null }
        until("his pick recorded") { ben.trackerEvents().count { it.startsWith("started") } == 1 }
    }

    @Test
    fun aNewHostKeepsTheRoomsMediaAndTheFormerHostRejoinsWithItsOwnFile() = world {
        val host = host()
        val anna = anna()
        openRoom(host, WatchControlMode.HOST_ONLY, anna)
        val both = listOf(host, anna)
        playMovieTogether(host, anna)
        val epoch = host.room!!.epoch

        val hostLobby = host.lobby()
        val annaId = assertNotNull(host.participant("Anna")).peerId
        until("Anna can be made host") { hostLobby.state.value.room?.participants?.firstOrNull { it.peerId == annaId }?.canMakeHost == true }
        host.on { hostLobby.makeHost(annaId) }
        until("Anna hosts") { anna.party.isHost && !host.party.isHost && host.room?.participants?.single { it.host }?.peerId == annaId }

        // The room's media stays: the same epoch, Anna's own file plays on, and Sam plays his as the room's media.
        assertEquals(epoch, anna.room!!.epoch)
        until("both playing on together") { host.playing() && anna.playing() && host.attached() && anna.attached() && inSync(both) }
        assertEquals(urls(movieNear, movieMatch), anna.engine.loads)
        assertEquals(urls(movieHost), host.engine.loads)
        assertEquals(movieHost.name, host.party.choice?.ref?.choice?.stream?.behaviorHints?.filename)
        assertEquals(5_400_000L, anna.party.media?.durationMs)

        // Anna controls the room now.
        until("Anna's player lets her control") { anna.store.state.value.watch?.canControl == true }
        anna.dispatch(PlayerCommand.Pause)
        until("Sam paused by Anna") { host.paused() && anna.paused() && inSync(both) }
        until("Sam told") { host.heard("pause", "Anna") }
        anna.dispatch(PlayerCommand.Play)
        until("both playing again") { host.playing() && anna.playing() && inSync(both) }

        // Sam leaves the player: the room plays on with Anna, and his lobby offers the way back.
        host.on { host.session.navigator.pop(Route.Player) }
        until("Sam out of the player") { host.active == null }
        until("the room sees him out of it") { anna.participant("Sam")?.player == PlayerState.IDLE }
        until("his lobby offers to join playback") { hostLobby.state.value.room?.joinPlayback == T.watch.room.joinPlayback }
        assertTrue(anna.playing())

        // Join playback: his own file again, with the room, nothing searched.
        host.on { hostLobby.joinPlayback() }
        until("Sam back with his file") { host.plays(movieHost) && host.playing() && host.attached() && inSync(both) }
        assertEquals(urls(movieHost), host.engine.loads)
        assertEquals(epoch, host.room!!.epoch)
        until("his lobby without the way back") { hostLobby.state.value.room?.let { it.joinPlayback == null } == true }
    }

    @Test
    fun theHostRemovesAGuest() = world {
        val host = host()
        val anna = anna()
        val ben = ben()
        openRoom(host, WatchControlMode.HOST_ONLY, anna, ben)
        val hostLobby = host.lobby()
        val annaLobby = anna.lobby()
        val benLobby = ben.lobby()
        val benId = assertNotNull(host.participant("Ben")).peerId

        // Only the host may remove someone, and not itself.
        until("the host's lobby offers to remove Anna and Ben") {
            hostLobby.state.value.room?.participants?.map { it.kick != null } == listOf(false, true, true)
        }
        until("Anna's lobby offers nothing of the kind") {
            annaLobby.state.value.room?.participants?.let { list -> list.size == 3 && list.none { it.kick != null } } == true
        }

        host.on { hostLobby.kick(benId) }
        until("Ben out of the room") { ben.room == null && ben.state.failure == WatchFailure.KICKED }
        assertEquals(EndReason.KICKED, ben.party.endReason)
        until("Ben's lobby says why") { benLobby.state.value.room == null && benLobby.state.value.failure == T.watch.failure.kicked }
        until("the room goes on without him") {
            listOf(host, anna).all { app -> app.room?.participants?.map { it.name } == listOf("Sam", "Anna") }
        }
        assertNull(host.state.failure)
        assertNull(anna.state.failure)
    }

    @Test
    fun aLeavingHostHandsTheRoomOnAndEndEndsItForEveryone() = world {
        val host = host()
        val anna = anna()
        val ben = anna("Ben")
        val code = openRoom(host, WatchControlMode.HOST_ONLY, anna)

        // Ben joins with the invite link the host shares (its relay with it).
        val hostLobby = host.lobby()
        val invite = assertNotNull(hostLobby.state.value.room).inviteUrl
        assertTrue(invite.startsWith("tortuga://watch?code=$code&relay="), invite)
        assertTrue(ben.on { ben.session.openIncomingLink(invite) })
        until("Ben in the room") { ben.room?.code == code && host.room?.participants?.size == 3 && anna.room?.participants?.size == 3 }
        playMovieTogether(host, anna, ben)
        val epoch = host.room!!.epoch

        val hostRoom = assertNotNull(hostLobby.state.value.room)
        assertEquals(T.watch.room.leave, hostRoom.leave.label)
        assertEquals(T.watch.room.end, hostRoom.end?.label)
        val annaLobby = anna.lobby()
        until("a guest's lobby has Leave only") { annaLobby.state.value.room?.let { it.end == null && it.leave.label == T.watch.room.leave } == true }

        // Leave: Sam goes, and the room goes on with Anna, next in join order, as host and its media as it was.
        host.on { hostLobby.leave() }
        until("Sam out of the room") { host.room == null && host.party.endReason == EndReason.LEFT }
        assertNull(host.state.failure)
        until("Anna hosts") {
            anna.party.isHost && ben.room?.participants?.map { it.name } == listOf("Anna", "Ben") &&
                ben.room?.participants?.single { it.host }?.name == "Anna"
        }
        assertEquals(epoch, anna.room!!.epoch)
        until("Anna and Ben play on together") { anna.playing() && ben.playing() && inSync(listOf(anna, ben)) }
        until("Anna's lobby offers End now") { annaLobby.state.value.room?.end != null }

        // End: Anna ends the room for everyone.
        anna.on { annaLobby.end() }
        until("the room over for Ben") { ben.room == null && ben.state.failure == WatchFailure.ENDED }
        until("and for Anna, who has nothing to be told") { anna.room == null && anna.party.endReason == EndReason.ENDED }
        assertNull(anna.state.failure)
        val benLobby = ben.lobby()
        until("Ben's lobby says the host ended it") { benLobby.state.value.failure == T.watch.failure.ended }

        // The relay forgot the code.
        ben.on {
            benLobby.dismissFailure()
            benLobby.setJoinText(code)
            benLobby.join()
        }
        until("no such room") { ben.state.failure == WatchFailure.ROOM_NOT_FOUND }
        until("Ben's lobby says so") { benLobby.state.value.failure == T.watch.failure.roomNotFound }
    }

    @Test
    fun aGuestFindsItsFileForAnotherAppsDescriptorWithOnlyAnImdbId() = world {
        val anna = anna()
        val olly = other("Olly", "Example/1.0")
        // Another app hosts, out of its player: the room never waits for it.
        val code = olly.on { create().also { detachPlayer() } }
        val annaLobby = anna.lobby()
        anna.on {
            annaLobby.setJoinText(code)
            annaLobby.join()
        }
        until("Anna in Olly's room") { anna.room?.code == code && olly.state.room?.participants?.size == 2 }
        assertEquals("Example/1.0", anna.participant("Olly")?.client)

        // Its descriptor: a movie's IMDb id and length, nothing else.
        olly.on { setMedia(Media(kind = MediaKind.MOVIE, title = TITLE, durationMs = 5_400_000, ids = mapOf("imdb" to MOVIE)), "olly's file") }
        until("Anna playing her file of it", 40.seconds) { anna.plays(movieMatch) && anna.playing() && anna.attached() }
        // No host file to compare sizes with: her own order, until the one of the same length.
        assertEquals(urls(movieFar, movieNear, movieMatch), anna.engine.loads, "Anna's probes")
        assertEquals("movie", anna.state.media?.type)
        assertEquals(MOVIE, anna.state.media?.metaId)
        until("Anna in step with the room") { abs(anna.position() - olly.roomPosition()) <= TOLERANCE }
        until("Olly sees Anna's match") { olly.participant("Anna")?.match == Match(MatchState.MATCHED, 500) }
        until("Anna's lobby names the title") { annaLobby.state.value.room?.nowPlaying == T.watch.room.nowPlaying(isolate(TITLE)) }

        // The other app's controls move her too.
        olly.on { pause() }
        until("Anna paused with the room") {
            anna.paused() && olly.state.room?.phase == Phase.PAUSED && abs(anna.position() - olly.roomPosition()) <= TOLERANCE
        }
        until("Anna told") { anna.heard("pause", "Olly") }
        olly.on {
            seek(1_200_000)
            play()
        }
        until("Anna playing from the room's new position") {
            anna.playing() && anna.position() in 1199.0..1215.0 && abs(anna.position() - olly.roomPosition()) <= TOLERANCE
        }
    }

    /**
     * A guest's call (the system's pause) pauses her device alone: her player leaves the room's playback, which plays on
     * without waiting for her. Each reason to stay out of it holds on its own: back in sight during the call she stays
     * out, and with the call over (the system's resume) while the app is out of sight she stays out until it is in
     * sight, then catches up with the room. After another stop (headphones gone) her own Play catches her up.
     */
    @Test
    fun aGuestsCallPausesHerAloneUntilBothTheCallAndTheAppComeBack() = world {
        val host = host()
        val anna = anna()
        openRoom(host, WatchControlMode.HOST_ONLY, anna)
        val both = listOf(host, anna)
        playMovieTogether(host, anna)
        anna.on { anna.graph.setWindowVisible(true) }
        fun away() = anna.watch.playback.away.value
        // 3 s on: past the 2 s behind which the SDK seeks a player to the room rather than nudging its speed.
        suspend fun roomPlaysOn(what: String) {
            val from = host.position()
            until(what) { host.playing() && host.room?.phase == Phase.PLAYING && host.position() > from + 3 }
        }

        anna.dispatch(PlayerCommand.SystemPause)
        until("Anna paused alone, out of the room's playback") { anna.paused() && !anna.attached() }
        val pausedAt = anna.position()
        roomPlaysOn("the room playing on without her")
        assertTrue(anna.paused() && !anna.attached() && anna.position() == pausedAt, "Anna stays paused where she was")
        assertEquals(T.watch.player.pausedHere, anna.store.state.value.watch?.pausedHere)
        assertTrue(host.heard("pause", "Anna").not(), "the room heard no pause of hers")

        anna.on { anna.graph.setWindowVisible(false) }
        until("Anna out of sight") { WatchPlayback.Away.OUT_OF_SIGHT in away() }
        anna.on { anna.graph.setWindowVisible(true) }
        until("Anna in sight again") { WatchPlayback.Away.OUT_OF_SIGHT !in away() }
        roomPlaysOn("the room on further")
        assertTrue(anna.paused() && !anna.attached(), "in sight during the call, Anna stays out of the room's playback")

        anna.on { anna.graph.setWindowVisible(false) }
        until("Anna out of sight") { WatchPlayback.Away.OUT_OF_SIGHT in away() }
        anna.dispatch(PlayerCommand.SystemResume)
        until("the call over") { WatchPlayback.Away.STOPPED !in away() }
        roomPlaysOn("the room on further")
        assertTrue(anna.paused() && !anna.attached(), "the call over out of sight, Anna stays out of the room's playback")
        anna.on { anna.graph.setWindowVisible(true) }
        until("Anna caught up with the room") { anna.attached() && anna.playing() && inSync(both) }

        anna.dispatch(PlayerCommand.SystemPause)
        until("Anna paused alone again") { anna.paused() && !anna.attached() }
        roomPlaysOn("the room playing on without her")
        anna.dispatch(PlayerCommand.TogglePause)
        until("her Play caught her up") { anna.attached() && anna.playing() && inSync(both) }
        assertTrue(host.heard("pause", "Anna").not() && host.heard("play", "Anna").not(), "the room heard nothing of hers")
    }

    /**
     * A guest whose file fails for good leaves the room's playback: the room plays on without holding anyone for her, and
     * Join playback brings her back with the room.
     */
    @Test
    fun aGuestWhoseFileFailsLeavesTheRoomsPlaybackAndJoinsAgain() = world {
        val host = host()
        val anna = anna()
        openRoom(host, WatchControlMode.HOST_ONLY, anna)
        val both = listOf(host, anna)
        playMovieTogether(host, anna)
        val annaId = assertNotNull(host.participant("Anna")).peerId

        anna.on { anna.engine.fail() }
        until("Anna's failed file out of the room's playback") { !anna.attached() }
        until("the room sees her out of it") { host.participant("Anna")?.player == PlayerState.IDLE }

        // A pause and a play hold the room for every attached player: not for her.
        host.dispatch(PlayerCommand.Pause)
        until("the room paused") { host.paused() && host.room?.phase == Phase.PAUSED }
        host.dispatch(PlayerCommand.Play)
        until("the room playing again at once", 5.seconds) { host.playing() && host.room?.phase == Phase.PLAYING }
        assertFalse(annaId in host.room!!.waitingOn)

        val annaLobby = anna.lobby()
        until("her lobby offers to join playback") { annaLobby.state.value.room?.joinPlayback == T.watch.room.joinPlayback }
        anna.on { annaLobby.joinPlayback() }
        until("Anna back with the room", 40.seconds) { anna.plays(movieMatch) && anna.attached() && anna.playing() && inSync(both) }
    }

    /**
     * A recent room the relay forgot (everyone left) is made again under its code, with this device as host in the
     * control mode it had and its open file as the room's media; a guest then joins it by that code.
     */
    @Test
    fun anEndedRecentRoomIsMadeAgainUnderItsCode() = world {
        val host = host()
        val anna = anna()
        host.play("movie", MOVIE, MOVIE)
        until("Sam playing alone") { host.playing() }
        host.watchCommand(WatchCommand.Start(WatchControlMode.ANYONE))
        until("Sam's room") { host.room != null && host.party.media != null }
        val code = host.room!!.code
        until("the room remembered with its control mode") {
            host.watch.recentRooms.value.firstOrNull()?.let { it.code == code && it.anyoneControls } == true
        }

        val hostLobby = host.lobby()
        host.on { hostLobby.leave() }
        until("Sam out of the room") { host.room == null }
        val recent = host.watch.recentRooms.value.first { it.code == code }
        host.on { host.watch.enter(recent) }
        until("the room made again under its code") { host.room?.code == code && host.party.isHost }
        assertNull(host.state.failure)
        until("in its control mode, sharing Sam's movie") {
            host.room?.control == Control.EVERYONE && host.party.media?.title == TITLE && host.attached()
        }

        val annaLobby = anna.lobby()
        anna.on {
            annaLobby.setJoinText(code)
            annaLobby.join()
        }
        until("Anna on her file in the room made again", 40.seconds) { anna.room?.code == code && anna.attached() }
        host.dispatch(PlayerCommand.Play)
        until("both playing together") { host.playing() && anna.playing() && inSync(listOf(host, anna)) }
    }

    /** A guest whose addons failed is told the search failed, not that none of them has a stream. */
    @Test
    fun aGuestWhoseAddonsFailedIsToldTheSearchFailed() = world {
        val host = host()
        val cleo = app("Cleo", listOf("cleo"))
        openRoom(host, WatchControlMode.HOST_ONLY, cleo)
        host.play("movie", MOVIE, MOVIE)

        until("Cleo found nothing", 40.seconds) { cleo.party.match?.state == MatchState.UNAVAILABLE && cleo.prompt() != null }
        val message = assertNotNull(cleo.prompt()).message
        assertTrue(TITLE in message, message)
        assertNotEquals(T.watch.player.noStream(isolate(TITLE)), message)
        val cleoLobby = cleo.lobby()
        until("her row in the room") { cleoLobby.state.value.room?.participants?.single { it.isYou }?.status != null }
        assertNotEquals(T.watch.status.noStream, cleoLobby.state.value.room!!.participants.single { it.isYou }.status)
    }

    /**
     * A guest's file that plays but can't seek follows the room by its speed alone: the room's seeks don't reach it,
     * and it speeds up to catch up instead.
     */
    @Test
    fun aFileThatCantSeekFollowsTheRoomBySpeedAlone() = world {
        val host = host()
        val dana = app("Dana", listOf("dana"))
        openRoom(host, WatchControlMode.HOST_ONLY, dana)
        playMovieTogether(host, dana)
        assertTrue(dana.plays(movieNoIndex))
        val seeks = dana.engine.seeks.size

        host.dispatch(PlayerCommand.SeekTo(60.0))
        until("Dana catching up by speed") { dana.attached() && dana.playing() && dana.engine.state.speed > 1.0 + NUDGE / 2 }
        assertEquals(seeks, dana.engine.seeks.size, "no seek asked of a file that can't seek")
    }

    private companion object {
        const val MOVIE = "tt9000001"
        const val TITLE = "The Long Night"

        /** How far apart two players following one room may be at one instant, in seconds. */
        const val TOLERANCE = 0.75

        /** How far the SDK moves a player's rate to catch up (Open Watch Party's NUDGE_FACTOR). */
        const val NUDGE = 0.04
    }
}
