package com.edde746.tortuga.desktop.harness

import com.edde746.tortuga.app.DevOpen
import com.edde746.tortuga.app.DevSetting
import com.edde746.tortuga.data.DatabaseFactory
import com.edde746.tortuga.desktop.Cli
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TestOptionsTest {
    private fun parse(vararg args: String) = TestOptions.parse(arrayOf(*args)).second

    private fun cli(vararg args: String) = TestOptions.parse(arrayOf(*args)).first

    @Test
    fun theProductStartTakesNoDevEntry() {
        assertFalse(parse().dev.dev)
        val devEntries = listOf(
            arrayOf("--url", "http://h/a.mkv"),
            arrayOf("--dev-addon", "http://h/manifest.json"),
            arrayOf("--dev-watch-activity"),
            arrayOf("--dev-open", "streams:movie:a:a"),
            arrayOf("--dev-language", "de"),
            arrayOf("--dev-kids", "2468"),
            arrayOf("--dev-setting", "video.hardwareDecoding=false"),
            arrayOf("--dev-trackers", "http://h:8772"),
            arrayOf("--dev-discord", "1"),
            arrayOf("--dev-data", "a"),
        )
        for (entry in devEntries) {
            assertFailsWith<Cli.Error> { parse(*entry) }
        }
    }

    @Test
    fun anUnknownDevOptionIsAUsageError() {
        assertFailsWith<Cli.Error> { parse("--dev", "--dev-keep") }
        assertFailsWith<Cli.Error> { parse("--dev", "--dev-opens", "library") }
    }

    @Test
    fun aDevSessionTakesAtMostOneStart() {
        assertTrue(parse("--dev").dev.dev)
        assertEquals(listOf("http://h/manifest.json"), parse("--dev", "--dev-addon", "http://h/manifest.json").dev.addons)
        assertFailsWith<Cli.Error> { parse("--dev", "--url", "http://h/a.mkv", "--dev-open", "streams:movie:a:a") }
        assertEquals("http://h/a.mkv", parse("--dev", "--url", "http://h/a.mkv").dev.url)
    }

    @Test
    fun theSignInBrowserStaysOutOfDevSessions() {
        val options = parse("--data-dir", "C:\\runs\\a", "--dev-browser", "C:\\scripts\\sign-in.ps1")
        assertEquals(Path.of("C:\\runs\\a"), options.dataDir)
        assertEquals("C:\\scripts\\sign-in.ps1", options.dev.browser)
        assertFalse(options.dev.dev)
        assertFailsWith<Cli.Error> { parse("--dev", "--dev-browser", "C:\\scripts\\sign-in.ps1") }
    }

    @Test
    fun theSignInServerIsTheAppleAppsAlone() {
        assertFailsWith<Cli.Error> { parse("--dev-sign-in-server", "http://h/signin") }
    }

    @Test
    fun aSlowStartSlowsTheProductOnly() {
        assertEquals(2_500L, parse("--data-dir", "C:\\runs\\a", "--dev-slow-start", "2.5").dev.slowStartMillis)
        assertFailsWith<Cli.Error> { parse("--dev", "--dev-slow-start", "2") }
        assertFailsWith<Cli.Error> { parse("--dev-slow-start", "0") }
    }

    @Test
    fun aDevSessionKeepsItsDatabaseInADataDirOrUnderAName() {
        val options = parse("--dev", "--data-dir", "C:\\runs\\a", "--dev-open", "library")
        assertTrue(options.dev.dev)
        assertEquals(Path.of("C:\\runs\\a"), options.dataDir)
        assertEquals(DatabaseFactory.defaultDirectory().resolve("dev-data").resolve("tour-1"), parse("--dev", "--dev-data", "tour-1").dataDir)
        assertNull(parse("--dev").dataDir)
        assertFailsWith<Cli.Error> { parse("--dev", "--data-dir", "C:\\runs\\a", "--dev-data", "tour-1") }
    }

    @Test
    fun aBadDevEntryIsAUsageError() {
        assertFailsWith<Cli.Error> { parse("--dev", "--dev-open", "player:a:b:c") }
        assertFailsWith<Cli.Error> { parse("--dev", "--dev-setting", "video.hardwareDecoding=off") }
        assertFailsWith<Cli.Error> { parse("--dev", "--dev-trackers", "h:8772") }
        assertEquals("http://h:8772", parse("--dev", "--dev-trackers", "http://h:8772/").dev.trackers?.baseUrl)
        assertEquals(false, parse("--dev", "--dev-trackers", "http://h:8772").dev.trackers?.connected)
        assertEquals(true, parse("--dev", "--dev-trackers", "http://h:8772", "--dev-trackers-connected").dev.trackers?.connected)
        assertEquals("http://h:18180", parse("--dev", "--dev-trackers", "http://h:8772", "--dev-relay", "http://h:18180").dev.trackers?.relay)
        assertFailsWith<Cli.Error> { parse("--dev", "--dev-trackers-connected") }
        assertEquals("424242424242424242", parse("--dev", "--dev-discord", "424242424242424242").dev.discord)
        assertFailsWith<Cli.Error> { parse("--dev", "--dev-discord", " ") }
        assertFailsWith<Cli.Error> { parse("--dev", "--dev-open") }
    }

    @Test
    fun devOpenKeepsTheScreensInOrder() {
        assertEquals(
            listOf(DevOpen.Detail("movie", "tt0111161"), DevOpen.Detail("series", "tt7366338"), DevOpen.Streams("series", "tt7366338", "tt7366338:1:1")),
            parse("--dev", "--dev-open", "detail:movie:tt0111161", "--dev-open", "detail:series:tt7366338", "--dev-open", "streams:series:tt7366338:tt7366338:1:1").dev.open,
        )
    }

    @Test
    fun aDevSessionCanStartOffline() {
        assertEquals(listOf<DevSetting>(DevSetting.Offline(true)), parse("--dev", "--dev-setting", "offline=true").dev.settings)
        assertEquals(listOf<DevSetting>(DevSetting.Later(DevSetting.Offline(false), 30.0)), parse("--dev", "--dev-setting", "offline=false@30").dev.settings)
        assertFailsWith<Cli.Error> { parse("--dev", "--dev-setting", "offline=yes") }
        assertFailsWith<Cli.Error> { parse("--dev-setting", "offline=true") }
    }

    @Test
    fun theAppsOptionsAndLinkPassThrough() {
        val link = "stremio:///detail/movie/tt0111161"
        assertEquals(
            Cli(mpvOptions = mapOf("mute" to "yes"), link = link),
            cli("--dev", "--mpv-opt", "mute=yes", "--report", "C:\\x.jsonl", "--exit-after", "8", link),
        )
        val options = parse("--dev", "--report", "C:\\x.jsonl", "--exit-after", "8", link)
        assertTrue(options.dev.dev)
        assertEquals(TestOptions(dev = options.dev, report = Path.of("C:\\x.jsonl"), exitAfterSeconds = 8.0), options)
    }

    @Test
    fun nothingFollowsALink() {
        // Windows passes the link it opens as "%1": one holding a quote would split into more arguments after it.
        val link = "stremio:///detail/movie/tt0111161"
        assertFailsWith<Cli.Error> { parse(link, "--dev-browser", "\\\\host\\share\\x.exe") }
        assertFailsWith<Cli.Error> { parse("--data-dir", "C:\\runs\\a", link, "--report", "C:\\x.jsonl") }
        assertEquals(link, cli("--data-dir", "C:\\runs\\a", "--report", "C:\\x.jsonl", link).link)
    }
}
