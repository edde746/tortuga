package com.edde746.tortuga.playback.players

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerAppTemplatesTest {
    private val subtitle = PlayerAppSubtitle("https://subs.example/eng.srt", local = false, title = "English", language = "eng")

    private fun media(
        title: String = "Chernobyl S1E2",
        startSeconds: Double = 754.9,
        subtitles: List<PlayerAppSubtitle> = listOf(subtitle),
        url: String = "https://cdn.example/v/file name.mkv?token=k&x=1",
    ) = PlayerAppMedia(url, local = false, headers = emptyMap(), title = title, startSeconds = startSeconds, subtitles = subtitles, filename = null)

    @Test
    fun argumentsSplitAtUnquotedWhitespaceAndQuotesGroup() {
        val template = "--a \"b c\"\t'single \"quoted\"' \"esc \\\"q\\\" \\\\ \\x\" \"\" pre\"mid dle\"post\n  end"
        assertEquals(
            listOf("--a", "b c", "single \"quoted\"", "esc \"q\" \\\\ \\x", "", "premid dlepost", "end"),
            PlayerAppTemplates.splitArguments(template),
        )
        assertEquals(emptyList(), PlayerAppTemplates.splitArguments("   "))
    }

    @Test
    fun insideDoubleQuotesBackslashesEscapeOnlyBeforeAQuote() {
        // Windows' rule: a share's path keeps both backslashes.
        assertEquals(listOf("\\\\nas\\share\\file.mkv"), PlayerAppTemplates.splitArguments("\"\\\\nas\\share\\file.mkv\""))
        // Before a quote each pair is one backslash, and an odd one makes the quote part of the argument.
        assertEquals(listOf("a\\\"b", "end\\", "x\\\\\" y"), PlayerAppTemplates.splitArguments("\"a\\\\\\\"b\" \"end\\\\\" \"x\\\\\\\\\\\" y\""))
    }

    @Test
    fun eachPlaceholderExpandsInsideItsArgument() {
        assertEquals(
            listOf("https://cdn.example/v/file name.mkv?token=k&x=1", "--title=Chernobyl S1E2", "--start=754", "--sub-file=https://subs.example/eng.srt"),
            PlayerAppTemplates.arguments("{url} \"--title={title}\" --start={start} --sub-file={subtitle}", media()),
        )
        // A title with spaces and quotes stays one argument whether or not the template quotes it.
        assertEquals(listOf("--title", "Say \"Hi\" now"), PlayerAppTemplates.arguments("--title {title}", media(title = "Say \"Hi\" now")))
    }

    @Test
    fun anArgumentTakingTheSubtitleIsLeftOutWithoutOne() {
        assertEquals(
            listOf("--fs", "https://cdn.example/v/file name.mkv?token=k&x=1"),
            PlayerAppTemplates.arguments("--fs --sub-file={subtitle} {subtitle} {url}", media(subtitles = emptyList())),
        )
    }

    @Test
    fun theStartIsWholeSecondsAndZeroFromTheBeginning() {
        assertEquals(listOf("0"), PlayerAppTemplates.arguments("{start}", media(startSeconds = 0.0)))
        assertEquals(listOf("0"), PlayerAppTemplates.arguments("{start}", media(startSeconds = Double.NaN)))
        assertEquals(listOf("59"), PlayerAppTemplates.arguments("{start}", media(startSeconds = 59.99)))
    }

    @Test
    fun valuesHoldingAPlaceholderStayAsTheyAre() {
        assertEquals(listOf("Movie {start} {url}"), PlayerAppTemplates.arguments("{title}", media(title = "Movie {start} {url}")))
        assertEquals("x://open?u=https://h/{url}", PlayerAppTemplates.url("x://open?u={url_raw}", media(url = "https://h/{url}")))
    }

    @Test
    fun aUrlTemplateEncodesUrlAndKeepsUrlRaw() {
        val m = media(url = "https://cdn.example/a b/é.mkv?t=1&u=2")
        assertEquals("vlc-x-callback://x-callback-url/stream?url=https%3A%2F%2Fcdn.example%2Fa%20b%2F%C3%A9.mkv%3Ft%3D1%26u%3D2", PlayerAppTemplates.url("vlc-x-callback://x-callback-url/stream?url={url}", m))
        assertEquals("vlc://https://cdn.example/a b/é.mkv?t=1&u=2", PlayerAppTemplates.url("vlc://{url_raw}", m))
        assertEquals("AZaz09-_.~%20%2F", PlayerAppTemplates.percentEncode("AZaz09-_.~ /"))
    }
}
