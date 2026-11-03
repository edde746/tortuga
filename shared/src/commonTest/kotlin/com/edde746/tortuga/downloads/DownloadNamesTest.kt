package com.edde746.tortuga.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class DownloadNamesTest {
    @Test
    fun aDownloadKeepsOnlyAVideoContainersExtension() {
        assertEquals("mp4", DownloadNames.extension("Movie.2026.1080p.MP4", "Movie.mkv"))
        assertEquals("webm", DownloadNames.extension(null, "clip.webm"))
        assertEquals("m2ts", DownloadNames.extension("setup.exe", "Movie.m2ts"), "the hint's container, where the file's name has none")
        for (name in listOf("setup.exe", "run.bat", "page.hta", "script.js", "Movie.mkv.lnk", "Movie")) {
            assertEquals("mkv", DownloadNames.extension(name, name), name)
        }
    }
}
