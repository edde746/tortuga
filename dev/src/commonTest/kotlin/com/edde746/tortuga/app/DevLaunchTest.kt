package com.edde746.tortuga.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevLaunchTest {
    private fun parse(vararg args: String) = DevLaunch.parse(args.toList())

    private fun refused(vararg args: String) {
        assertFailsWith<IllegalArgumentException>(args.joinToString(" ")) { parse(*args) }
    }

    @Test
    fun anUnknownDevOptionFailsTheLaunch() {
        refused("--dev", "--dev-keep")
        refused("--dev", "--dev-opens", "settings")
        refused("--devx")
        refused("--dev-replay", "/data/local/tmp/replay")
    }

    @Test
    fun thePlatformsOwnArgumentsAreSkipped() {
        // The Mac's AppKit pairs and a harness's own options go past the parser untouched.
        val launch = parse("-NSTreatUnknownArgumentsAsOpen", "NO", "--dev", "--report", "x.jsonl", "--dev-open", "settings", "stremio:///detail/movie/tt0111161")
        assertTrue(launch.dev)
        assertEquals(listOf(DevOpen.parse("settings")), launch.open)
        assertFalse(parse().dev)
    }

    @Test
    fun theOptionsAreReadInTheOrderGiven() {
        val launch = parse(
            "--dev", "--dev-addon", "http://h/a.json", "--dev-addon", "http://h/b.json", "--dev-addons-only", "--dev-open", "detail:movie:tt0111161",
            "--dev-open", "streams:series:tt0944947:tt0944947:1:1", "--dev-data", "tour-1", "--dev-language", "ar", "--dev-kids", "2468",
            "--dev-trackers", "http://h:8772/", "--dev-relay", "http://h:18180", "--dev-discord", "424242424242424242",
            "--dev-crash-reports", "http://public@h:8768/1", "--dev-setting", "offline=true",
        )
        assertEquals(listOf("http://h/a.json", "http://h/b.json"), launch.addons)
        assertTrue(launch.addonsOnly)
        assertEquals(listOf(DevOpen.Detail("movie", "tt0111161"), DevOpen.Streams("series", "tt0944947", "tt0944947:1:1")), launch.open)
        assertEquals("tour-1", launch.data)
        assertEquals("ar", launch.language)
        assertEquals(DevKids("2468"), launch.kids)
        assertEquals(listOf("http://h:8772", "http://h:18180"), listOf(launch.trackers?.baseUrl, launch.trackers?.relay))
        assertEquals("424242424242424242", launch.discord)
        assertEquals("http://public@h:8768/1", launch.crashReports)
        assertEquals(listOf<DevSetting>(DevSetting.Offline(true)), launch.settings)
        assertNull(launch.url)
        assertEquals(2_500L, parse("--dev-slow-start", "2.5").slowStartMillis)
    }

    @Test
    fun aSessionOptionNeedsDev() {
        for (option in listOf(
            arrayOf("--url", "http://h/a.mkv"), arrayOf("--dev-addon", "http://h/a.json"), arrayOf("--dev-watch-activity"),
            arrayOf("--dev-open", "settings"), arrayOf("--dev-language", "de"), arrayOf("--dev-kids", "2468"), arrayOf("--dev-data", "a"),
            arrayOf("--dev-setting", "offline=true"), arrayOf("--dev-trackers", "http://h:8772"), arrayOf("--dev-discord", "1"),
        )) {
            refused(*option)
            parse("--dev", *option)
        }
    }

    @Test
    fun aProductOptionRefusesDev() {
        for (option in listOf(arrayOf("--dev-slow-start", "3"), arrayOf("--dev-sign-in-server", "http://h/signin"), arrayOf("--dev-browser", "login.ps1"))) {
            parse(*option)
            refused("--dev", *option)
        }
    }

    @Test
    fun optionsThatDontGoTogetherFailTheLaunch() {
        refused("--dev", "--dev-trackers-connected")
        refused("--dev", "--dev-relay", "http://h:18180")
        refused("--dev", "--dev-trackers", "http://h:8772", "--dev-trackers-connected", "--dev-relay", "http://h:18180")
        refused("--dev", "--url", "http://h/a.mkv", "--dev-open", "settings")
        refused("--dev", "--dev-open", "restored", "--dev-open", "settings")
        refused("--dev", "--dev-kids", "2468", "--dev-profile", "Parent")
        assertEquals(DevKids("2468"), parse("--dev", "--dev-kids", "2468", "--dev-profile", "Mia").kids)
    }

    @Test
    fun aValueTheOptionDoesntTakeFailsTheLaunch() {
        refused("--dev", "--dev-open")
        refused("--dev", "--dev-language", "xx")
        refused("--dev", "--dev-language", "pt")
        refused("--dev", "--dev-data", "a/b")
        refused("--dev", "--dev-discord", " ")
        refused("--dev", "--dev-crash-reports", "public@h:8768/1")
        refused("--dev", "--dev-trackers", "h:8772")
        refused("--dev-slow-start", "0")
    }

    @Test
    fun aPlatformsParserKnowsWhichOptionsTakeAValue() {
        assertTrue(DevLaunch.isOption("--url"))
        assertTrue(DevLaunch.isOption("--dev-anything"))
        assertFalse(DevLaunch.isOption("--report"))
        assertFalse(DevLaunch.takesValue("--dev"))
        assertFalse(DevLaunch.takesValue("--dev-trackers-connected"))
        assertTrue(DevLaunch.takesValue("--dev-open"))
        assertTrue(DevLaunch.takesValue("--url"))
    }
}
