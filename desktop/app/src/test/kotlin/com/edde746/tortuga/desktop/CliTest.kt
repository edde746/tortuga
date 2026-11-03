package com.edde746.tortuga.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliTest {
    private fun parse(vararg args: String) = Cli.parse(arrayOf(*args))

    @Test
    fun theMpvOptionsAreTheUsers() {
        assertEquals(
            Cli(mpvLog = Path.of("C:\\logs\\mpv.log"), mpvOptions = mapOf("mute" to "yes", "loop-file" to "inf")),
            parse("--mpv-log", "C:\\logs\\mpv.log", "--mpv-opt", "mute=yes", "--mpv-opt", "loop-file=inf"),
        )
        assertFailsWith<Cli.Error> { parse("--mpv-opt", "mute") }
        assertFailsWith<Cli.Error> { parse("--mpv-log") }
    }

    @Test
    fun theProductTakesNoTestOption() {
        for (option in listOf(arrayOf("--dev"), arrayOf("--report", "C:\\x.jsonl"), arrayOf("--exit-after", "8"), arrayOf("--data-dir", "C:\\runs\\a"))) {
            assertFailsWith<Cli.Error> { parse(*option) }
        }
    }

    @Test
    fun theLinkTheOsPassesIsFollowed() {
        val link = "stremio://v3-cinemeta.strem.io/manifest.json"
        assertEquals(Cli(link = link), parse(link))
        assertEquals(Cli(link = "STREMIO:///detail/movie/tt0111161"), parse("STREMIO:///detail/movie/tt0111161"))
        assertEquals(link, parse("--mpv-opt", "mute=yes", link).link)
        assertFailsWith<Cli.Error> { parse(link, "stremio:///board") }
        assertFailsWith<Cli.Error> { parse("https://v3-cinemeta.strem.io/manifest.json") }
    }

    @Test
    fun nothingFollowsALink() {
        // Windows passes the link it opens as "%1": one holding a quote would split into more arguments after it.
        val link = "stremio:///detail/movie/tt0111161"
        assertFailsWith<Cli.Error> { parse(link, "--mpv-opt", "script=\\\\host\\share\\x.lua") }
        assertFailsWith<Cli.Error> { parse(link, "--mpv-log", "\\\\host\\share\\x.log") }
    }
}
