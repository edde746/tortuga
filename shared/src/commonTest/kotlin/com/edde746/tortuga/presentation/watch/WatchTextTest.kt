package com.edde746.tortuga.presentation.watch

import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.watch.WatchState
import com.edde746.tortuga.watch.relay.WatchRelayEndpoint
import party.openwatch.Connection
import party.openwatch.Control
import party.openwatch.Participant
import party.openwatch.PartyEvent
import party.openwatch.PartyState
import party.openwatch.Phase
import party.openwatch.PlayerState
import party.openwatch.Room
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Names in the room's rows and toasts ([WatchText]): each sits whole inside the app's words, in any language. */
class WatchTextTest {
    @AfterTest
    fun resetLocale() {
        T.locale = "en"
    }

    @Test
    fun theOwnRowSaysItIsYoursAndAnothersRowIsTheirNameAlone() {
        val (me, anna) = assertNotNull(WatchText.room(hosting(), inPlayer = false, searchFailed = false)).participants
        assertEquals(T.watch.room.you(isolate("Developer")) to true, me.name to me.isYou)
        assertEquals("Anna" to false, anna.name to anna.isYou)
        assertEquals(T.watch.room.kickTitle(isolate("Anna")), anna.kick?.title)
    }

    @Test
    fun anArabicToastKeepsALatinNameWhole() {
        T.locale = "ar"
        val joined = PartyEvent.ParticipantJoined(participant("rdj", "Robert Downey Jr.", host = false))
        assertEquals("انضم \u2068Robert Downey Jr.\u2069", WatchText.event(joined, mediaTitle = null, myPeerId = "me"))
    }

    private fun participant(peerId: String, name: String, host: Boolean) =
        Participant(peerId, name, "Tortuga/test", host, connected = true, player = PlayerState.IDLE, match = null, offsetMs = 0)

    /** This device, Developer, hosting room ABCDEF on the public relays with Anna. */
    private fun hosting(): WatchState {
        val room = Room(
            rev = 1,
            code = "ABCDEF",
            epoch = 1,
            phase = Phase.IDLE,
            positionMs = 0,
            atMs = 0,
            rate = 1.0,
            control = Control.HOST,
            locked = false,
            waitingOn = emptyList(),
            participants = listOf(participant("me", "Developer", host = true), participant("anna", "Anna", host = false)),
        )
        return WatchState(
            relay = WatchRelayEndpoint.HOSTED,
            roomRelay = WatchRelayEndpoint.HOSTED,
            party = PartyState(connection = Connection.CONNECTED, peerId = "me", room = room),
        )
    }
}
