package com.edde746.tortuga.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamPlayabilityTest {
    private val playable = StreamPlayability.Playable("https://cdn.example/a.mkv")

    private fun stream(whitelist: List<String>?) =
        Stream(StreamSource.Url("https://cdn.example/a.mkv"), behaviorHints = StreamBehaviorHints(countryWhitelist = whitelist))

    @Test
    fun aCountryWhitelistPlaysOnlyInItsCountries() {
        val unsupported = StreamPlayability.Unsupported(UnsupportedSource.REGION)
        assertEquals(unsupported, stream(listOf("gbr", "irl")).playabilityIn("usa"))
        assertEquals(playable, stream(listOf("gbr", "USA")).playabilityIn("usa"), "any case")
        assertEquals(playable, stream(emptyList()).playabilityIn("usa"), "an empty list restricts nothing")
        assertEquals(playable, stream(null).playabilityIn("usa"))
        assertEquals(playable, stream(listOf("gbr")).playabilityIn(null), "nor does an unknown region")
    }

    /** What a stream whose only source is [field] = [link] does, as decoded from an addon's answer. */
    private fun linked(field: String, link: String) =
        AddonJson.decodeFromString(Stream.serializer(), buildJsonObject { put(field, link) }.toString()).playabilityIn(null)

    @Test
    fun onlyWebAndStremioLinksOpenOutsideThePlayer() {
        val other = StreamPlayability.Unsupported(UnsupportedSource.OTHER)
        val elsewhere = listOf(
            "file:///C:/Windows/System32/calc.exe",
            "file://host/share/setup.exe",
            "C:\\Windows\\System32\\calc.exe",
            "smb://host/share/setup.exe",
            "search-ms:query=setup&crumb=location:\\\\host\\share",
            "vlc://https://cdn.example/a.mkv",
            "intent://cdn.example/a.mkv#Intent;scheme=https;package=org.videolan.vlc;end",
        )
        for (link in elsewhere) {
            assertEquals(other, linked("externalUrl", link), link)
            assertEquals(other, linked("playerFrameUrl", link), link)
        }
        assertEquals(other, linked("externalUrl", "\\\\host\\share\\setup.exe"), "a Windows share's path is counted, not dropped")
        val peerToPeer = StreamPlayability.Unsupported(UnsupportedSource.PEER_TO_PEER)
        assertEquals(peerToPeer, linked("externalUrl", "magnet:?xt=example"))

        assertEquals(StreamPlayability.External("https://www.example.com/watch"), linked("externalUrl", "https://www.example.com/watch"))
        assertEquals(StreamPlayability.External("http://player.example.com/"), linked("playerFrameUrl", "HTTP://Player.Example.com"))
        assertEquals(StreamPlayability.External("stremio:///detail/movie/tt1"), linked("externalUrl", "stremio:///detail/movie/tt1"))
        val unrouted = StreamPlayability.External("stremio:///")
        assertEquals(unrouted, linked("externalUrl", "stremio:///"), "a stremio: link the app doesn't route stays listed")
        assertNull(unrouted.deepLink)
    }

    @Test
    fun theDevicesRegionIsWrittenAsTheWhitelistsAre() {
        assertEquals("usa", DeviceRegion.alpha3("US"))
        assertEquals("gbr", DeviceRegion.alpha3("gb"))
        assertNull(DeviceRegion.alpha3("419"), "an area, not a country")
    }
}
