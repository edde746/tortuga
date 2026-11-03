package com.edde746.tortuga.watch

import com.edde746.tortuga.watch.media.RoomFile
import com.edde746.tortuga.watch.media.RoomMedia
import com.edde746.tortuga.watch.relay.WatchInvite
import com.edde746.tortuga.watch.relay.WatchRelayEndpoint
import party.openwatch.Connection
import party.openwatch.Control
import party.openwatch.PartyState

/** Who may play, pause, seek and change the speed in a room (Open Watch Party's `control`). */
enum class WatchControlMode {
    /** The host; the others follow. */
    HOST_ONLY,

    /** Everyone in the room. */
    ANYONE,
    ;

    internal val control: Control get() = if (this == ANYONE) Control.EVERYONE else Control.HOST

    internal companion object {
        fun of(control: Control): WatchControlMode = if (control == Control.EVERYONE) ANYONE else HOST_ONLY
    }
}

/** A room operation under way. */
internal enum class WatchActivity { CREATING, JOINING }

/**
 * Why a room couldn't be created or joined, why the host's file couldn't become the room's (a live stream never can),
 * or why it is over for this device.
 */
internal enum class WatchFailure {
    CREATE,
    JOIN,
    SHARE,
    LIVE,
    ROOM_NOT_FOUND,
    ROOM_FULL,
    ROOM_LOCKED,
    RELAY_FULL,
    RATE_LIMITED,
    CONNECTION,
    BLOCKED,
    SESSION_LOST,
    ENDED,
    KICKED,
    EXPIRED,
    REPLACED,
}

/**
 * [text] as Open Watch Party takes a name or a title (its SPEC.md, section 2): a control character (general category
 * Cc: a line break, a tab) becomes a space, a bidi control character (U+061C, U+200E-F, U+202A-E, U+2066-9) and an
 * unpaired surrogate go, the ends are trimmed, and at most [maxCodePoints] code points are kept, a surrogate pair
 * whole.
 */
internal fun partyText(text: String, maxCodePoints: Int = Int.MAX_VALUE): String {
    val clean = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val code = c.code
            when {
                c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                    append(c).append(text[i + 1])
                    i++
                }
                c.isSurrogate() || code == 0x061c || code in 0x200e..0x200f || code in 0x202a..0x202e || code in 0x2066..0x2069 -> Unit
                code <= 0x1f || code in 0x7f..0x9f -> append(' ')
                else -> append(c)
            }
            i++
        }
    }.trim()
    var end = 0
    var points = 0
    while (end < clean.length && points < maxCodePoints) {
        end += if (clean[end].isHighSurrogate()) 2 else 1
        points++
    }
    return clean.substring(0, end).trim()
}

/**
 * Watch together on this device.
 *
 * @property relay this device's relay setting ([WatchRelayEndpoint.HOSTED]: the public relays).
 * @property roomRelay the relay of the room this device is in, or is joining.
 * @property party the party's state (Open Watch Party SDK): connection, room, media, match, choice, mismatch, offset.
 * @property media [party]'s media as Tortuga reads it.
 * @property activity a create or join under way.
 * @property failure the last failure.
 * @property invite an invite to another room, waiting for the viewer to leave this one first.
 */
internal data class WatchState(
    val relay: WatchRelayEndpoint,
    val roomRelay: WatchRelayEndpoint? = null,
    val party: PartyState<RoomFile> = PartyState(),
    val media: RoomMedia? = null,
    val activity: WatchActivity? = null,
    val failure: WatchFailure? = null,
    val invite: WatchInvite? = null,
) {
    /** In a room: connected, or resuming after the connection dropped. */
    val inRoom: Boolean
        get() = party.room != null && (party.connection == Connection.CONNECTED || party.connection == Connection.RECONNECTING)
}
