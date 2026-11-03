package com.edde746.tortuga.watch.relay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchInviteTest {
    @Test
    fun anInviteOnItsOwnServersRelayRoundTripsThroughItsLink() {
        val relay = checkNotNull(WatchRelayEndpoint.parse("https://relay.example.org:8443/owp/"))
        val invite = WatchInvite("AB2CD3", relay)
        val link = invite.toUrl()
        assertEquals("tortuga://watch?code=AB2CD3&relay=https%3A%2F%2Frelay.example.org%3A8443%2Fowp", link)
        assertEquals(invite, WatchInvite.parseLink(link))
    }

    @Test
    fun anInviteOnThePublicRelaysCarriesOnlyItsCode() {
        val invite = WatchInvite("AB2CD3", WatchRelayEndpoint.HOSTED)
        assertEquals("tortuga://watch?code=AB2CD3", invite.toUrl())
        assertEquals(invite, WatchInvite.parseLink("tortuga://watch?code=ab2-cd3"))
    }

    @Test
    fun otherLinksAndBadCodesAreNoInvites() {
        assertNull(WatchInvite.parseLink("tortuga://link?code=ABCDEF"))
        assertNull(WatchInvite.parseLink("stremio:///detail/movie/tt1"))
        // Not the relays' alphabet (1), too short (the old 5-symbol codes), a relay that isn't one.
        assertNull(WatchInvite.parseLink("tortuga://watch?code=AB1CD3"))
        assertNull(WatchInvite.parseLink("tortuga://watch?code=AB2CD"))
        assertNull(WatchInvite.parseLink("tortuga://watch?code=AB2CD3&relay=ftp%3A%2F%2Fa.example"))
        assertNull(WatchInvite.parseLink("tortuga://watch?relay=https%3A%2F%2Fa.example"))
    }

    @Test
    fun theJoinFieldTakesACodeOrALink() {
        val own = checkNotNull(WatchRelayEndpoint.parse("http://10.0.0.4:8085"))
        assertEquals(WatchInvite("AB2CD3", own), WatchInvite.parseInput(" ab2-cd3 ", own))
        assertEquals(WatchInvite("K7MQ2X", WatchRelayEndpoint.HOSTED), WatchInvite.parseInput("K7M Q2X", WatchRelayEndpoint.HOSTED))
        assertEquals(WatchInvite("XY3ZW4", WatchRelayEndpoint.HOSTED), WatchInvite.parseInput("tortuga://watch?code=XY3ZW4", own))
        assertNull(WatchInvite.parseInput("hello", own))
        assertNull(WatchInvite.parseInput("ABCDEFG", own))
    }

    @Test
    fun relayUrlsAreCanonical() {
        assertEquals("https://relay.example.org", WatchRelayEndpoint.parse("HTTPS://Relay.Example.org:443/")?.baseUrl)
        assertEquals("http://10.0.2.2:8085", WatchRelayEndpoint.parse("http://10.0.2.2:8085")?.baseUrl)
        assertNull(WatchRelayEndpoint.parse("ftp://relay.example.org"))
        assertNull(WatchRelayEndpoint.parse("https://user:pass@relay.example.org"))
        assertNull(WatchRelayEndpoint.parse("https://relay.example.org/?x=1"))
        assertNull(WatchRelayEndpoint.parse("relay.example.org"))
        assertEquals(WatchRelayEndpoint.HOSTED, WatchRelayEndpoint.resolve("not a url"))
        assertEquals(WatchRelayEndpoint.HOSTED, WatchRelayEndpoint.resolve(null))
    }
}
