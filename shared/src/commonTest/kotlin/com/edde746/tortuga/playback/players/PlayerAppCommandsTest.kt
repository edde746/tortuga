package com.edde746.tortuga.playback.players

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerAppCommandsTest {
    private val url = "https://cdn.example/v/movie.mkv?token=k"
    private val remote = PlayerAppSubtitle("https://subs.example/eng.srt", local = false, title = "English", language = "eng")
    private val saved = PlayerAppSubtitle("/home/u/Downloads/movie.fre.srt", local = true, title = "Français", language = "fre")
    private val headers = linkedMapOf("Referer" to "https://site.example/", "User-Agent" to "Tortuga/1.0", "X-Token" to "a,b")

    private fun media(
        startSeconds: Double = 90.5,
        title: String = "The Shawshank Redemption",
        headers: Map<String, String> = this.headers,
        subtitles: List<PlayerAppSubtitle> = listOf(remote, saved),
    ) = PlayerAppMedia(url, local = false, headers = headers, title = title, startSeconds = startSeconds, subtitles = subtitles, filename = null)

    @Test
    fun mpvAppendsEachHeaderAndSubtitleAndEndsWithTheUrl() {
        assertEquals(
            listOf(
                "--force-window=immediate",
                "--input-ipc-server=\\\\.\\pipe\\tortuga-mpv-1",
                "--start=90",
                "--force-media-title=The Shawshank Redemption",
                "--user-agent=Tortuga/1.0",
                "--http-header-fields-append=Referer: https://site.example/",
                "--http-header-fields-append=X-Token: a,b",
                "--sub-files-append=https://subs.example/eng.srt",
                "--sub-files-append=/home/u/Downloads/movie.fre.srt",
                "--",
                url,
            ),
            PlayerAppCommands.mpv(media(), "\\\\.\\pipe\\tortuga-mpv-1"),
        )
        assertEquals(
            listOf("--force-window=immediate", "--", url),
            PlayerAppCommands.mpv(media(startSeconds = 0.0, title = " ", headers = emptyMap(), subtitles = emptyList()), null),
        )
    }

    @Test
    fun iinaAndCelluloidPassWholeListsWithTheirSeparatorsEscaped() {
        assertEquals(
            listOf(
                "--no-stdin", "--keep-running", "-quitWhenNoOpenedWindow", "YES", url,
                "--mpv-input-ipc-server=/tmp/t/mpv.sock",
                "--mpv-start=90",
                "--mpv-force-media-title=The Shawshank Redemption",
                "--mpv-user-agent=Tortuga/1.0",
                "--mpv-http-header-fields=Referer: https://site.example/,X-Token: a\\,b",
                "--mpv-sub-files=https\\://subs.example/eng.srt:/home/u/Downloads/movie.fre.srt",
            ),
            PlayerAppCommands.iina(media(), "/tmp/t/mpv.sock"),
        )
        // Celluloid reads `\\` and `\'` back to `\` and `'` before mpv parses the lists.
        assertEquals(
            listOf(
                "--new-window",
                "--mpv-start=90",
                "--mpv-force-media-title=Schindler\\'s List",
                "--mpv-user-agent=Tortuga/1.0",
                "--mpv-http-header-fields=Referer: https://site.example/,X-Token: a\\\\,b",
                "--mpv-sub-files=https\\\\://subs.example/eng.srt:/home/u/Downloads/movie.fre.srt",
                url,
            ),
            PlayerAppCommands.celluloid(media(title = "Schindler's List")),
        )
    }

    @Test
    fun vlcListensLocallyAndTakesUserAgentAndRefererAsItemOptions() {
        val subtitles = listOf(
            remote,
            saved,
            PlayerAppSubtitle("https://subs.example/download/1234", local = false, title = null, language = "spa"),
            PlayerAppSubtitle("C:\\Users\\u\\Videos\\Movie.SRT", local = true, title = null, language = "ger"),
            PlayerAppSubtitle("https://subs.example/ita.vtt?sig=1#t", local = false, title = null, language = "ita"),
            PlayerAppSubtitle("https://subs.example/por.ass?sig=1", local = false, title = null, language = "por"),
        )
        assertEquals(
            listOf(
                "--extraintf=http",
                "--http-host=127.0.0.1",
                "--http-port=52100",
                "--http-password=secret",
                "--no-one-instance",
                "--play-and-exit",
                "--start-time=90",
                "--meta-title=The Shawshank Redemption",
                "--input-slave=https://subs.example/eng.srt#/home/u/Downloads/movie.fre.srt#https://subs.example/por.ass?sig=1",
                url,
                ":http-user-agent=Tortuga/1.0",
                ":http-referrer=https://site.example/",
            ),
            PlayerAppCommands.vlc(media(subtitles = subtitles), 52100, "secret"),
        )
        assertEquals(
            listOf("--no-one-instance", "--play-and-exit", url),
            PlayerAppCommands.vlc(media(startSeconds = 0.0, title = "", headers = emptyMap(), subtitles = emptyList()), null, null),
        )
        // The Mac's VLC, which has no --one-instance.
        assertEquals(
            listOf("--play-and-exit", url),
            PlayerAppCommands.vlc(
                media(startSeconds = 0.0, title = "", headers = emptyMap(), subtitles = emptyList()),
                httpPort = null,
                httpPassword = null,
                oneInstanceOption = false,
            ),
        )
        val shown = PlayerAppSubtitle("https://subs.example/por.ass?sig=1", local = false, title = null, language = "por", selected = true)
        assertEquals(
            "--input-slave=https://subs.example/por.ass?sig=1#https://subs.example/eng.srt",
            PlayerAppCommands.vlc(media(subtitles = listOf(remote, shown)), null, null).single { it.startsWith("--input-slave=") },
        )
    }

    @Test
    fun mpcStartsInMillisecondsWithTheSavedSubtitlesOnly() {
        assertEquals(
            listOf(url, "/new", "/play", "/start", "90000", "/sub", "/home/u/Downloads/movie.fre.srt"),
            PlayerAppCommands.mpc(media()),
        )
        assertEquals(listOf(url, "/new", "/play"), PlayerAppCommands.mpc(media(startSeconds = 0.0, subtitles = listOf(remote))))
    }

    @Test
    fun potPlayerTakesTitleSubtitleAndHeaders() {
        assertEquals(
            listOf(
                url,
                "/new",
                "/seek=90",
                "/title=The Shawshank Redemption",
                "/sub=https://subs.example/eng.srt",
                "/user_agent=Tortuga/1.0",
                "/referer=https://site.example/",
                "/headers=X-Token: a,b",
            ),
            PlayerAppCommands.potPlayer(media()),
        )
        assertEquals(
            "/headers=X-One: 1\r\nX-Two: 2",
            PlayerAppCommands.potPlayer(media(headers = linkedMapOf("X-One" to "1", "X-Two" to "2"))).last(),
        )
    }
}
