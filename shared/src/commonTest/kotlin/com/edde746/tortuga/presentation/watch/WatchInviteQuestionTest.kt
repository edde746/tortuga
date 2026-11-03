package com.edde746.tortuga.presentation.watch

import com.edde746.tortuga.watch.WatchState
import com.edde746.tortuga.watch.relay.WatchInvite
import com.edde746.tortuga.watch.relay.WatchRelayEndpoint
import party.openwatch.Connection
import party.openwatch.Control
import party.openwatch.Participant
import party.openwatch.PartyState
import party.openwatch.Phase
import party.openwatch.PlayerState
import party.openwatch.Room
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What an invite link asks before this device follows it ([WatchLobbyUiState.inviteQuestion]). */
class WatchInviteQuestionTest {
    @Test
    fun inARoomTheLeaveQuestionNamesAnotherRelaysRoom() {
        for (host in listOf(false, true)) {
            val room = assertNotNull(WatchText.room(inRoom(host), inPlayer = false, searchFailed = false))
            assertEquals(room.leave, WatchText.inviteQuestion(WatchInvite("ABCDEF", WatchRelayEndpoint.HOSTED), WatchRelayEndpoint.HOSTED, room))

            val question = WatchText.inviteQuestion(WatchInvite("K7MQ2X", STRANGER), WatchRelayEndpoint.HOSTED, room)
            assertTrue("K7M-Q2X" in question.title && "10.0.0.9" in question.title, question.title)
            assertEquals(room.leave.confirm, question.confirm)
            assertTrue(question.destructive, "leaving the room is destructive")
        }
    }

    @Test
    fun outOfARoomJoiningAnotherRelaysRoomIsNotDestructive() {
        val question = WatchText.inviteQuestion(WatchInvite("K7MQ2X", STRANGER), WatchRelayEndpoint.HOSTED, room = null)
        assertTrue("K7M-Q2X" in question.title && "10.0.0.9" in question.title, question.title)
        assertFalse(question.destructive)
    }

    /** This device in room ABCDEF on the public relays with Anna, as its host when [host]. */
    private fun inRoom(host: Boolean): WatchState {
        fun participant(peerId: String, name: String, host: Boolean) =
            Participant(peerId, name, "Tortuga/test", host, connected = true, player = PlayerState.IDLE, match = null, offsetMs = 0)
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
            participants = listOf(participant("me", "Developer", host), participant("anna", "Anna", !host)),
        )
        return WatchState(
            relay = WatchRelayEndpoint.HOSTED,
            roomRelay = WatchRelayEndpoint.HOSTED,
            party = PartyState(connection = Connection.CONNECTED, peerId = "me", room = room),
        )
    }

    private companion object {
        val STRANGER = checkNotNull(WatchRelayEndpoint.parse("http://10.0.0.9:8085"))
    }
}
