package com.edde746.tortuga.sync.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncProtocolTest {
    @Test
    fun normalizesTypedLinkCodes() {
        assertEquals("WDW85V5N", normalizeLinkCode(" wdw8-5v5n "))
        assertEquals("WDW85V5N", normalizeLinkCode("WDW8 5V5N"))
        // Wrong length, and look-alike characters the alphabet leaves out.
        assertNull(normalizeLinkCode("WDW85V5"))
        assertNull(normalizeLinkCode("WDW85V5O"))
        assertNull(normalizeLinkCode("WDW85V51"))
    }

    @Test
    fun aTypedLinkCodeKeepsItsLettersAndDigitsAndShowsGrouped() {
        assertEquals("wdw85V5N", typedLinkCode(" wdw8-5V5N "), "case kept, dashes and spaces refused")
        assertEquals("ABCD1234", typedLinkCode("ABCD1234EF"), "nothing past the length")
        assertEquals("", typedLinkCode("ß-é"))
        assertEquals("WDW8-5V5N", formatLinkCode("wdw85v5n"))
        assertEquals("WDW8-5", formatLinkCode("wdw85"), "a dash after every fourth")
        assertEquals("WDW8", formatLinkCode("wdw8"), "none after the last")
        assertEquals("", formatLinkCode(""))
    }

    @Test
    fun ignoresFieldsFromNewerServers() {
        val json = """{"status":"pending","eta":5,"session":null}"""
        assertEquals(LinkPollResponse(LinkStatus.PENDING), SyncProtocol.json.decodeFromString(LinkPollResponse.serializer(), json))
    }
}
