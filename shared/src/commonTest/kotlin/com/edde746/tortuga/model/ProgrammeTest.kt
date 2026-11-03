package com.edde746.tortuga.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Live channels' programmes and guide catalogs, read from the addon SDK's `docs/epg.md` examples. */
class ProgrammeTest {
    private val sdkManifest = """
        {"id":"org.example.livetv","version":"1.0.0","name":"Example Live TV","description":"Live channels with a programme guide",
         "resources":["catalog","meta","stream"],"types":["tv"],"idPrefixes":["exampletv:"],
         "catalogs":[{"type":"tv","id":"channels","name":"Channels","extra":[{"name":"skip"},{"name":"date"}]},
                     {"type":"tv","id":"favourites","name":"Favourites"}],
         "behaviorHints":{"epgProvider":true}}
    """.trimIndent()

    private val sdkProgramme = """
        {"id":"exampletv:news:epg:2026-09-12T18:00:00.000Z","title":"Evening News","overview":"The day's headlines.",
         "thumbnail":"https://example.com/evening-news.jpg","released":"2026-09-12T18:00:00.000Z",
         "startTime":"2026-09-12T18:00:00.000Z","endTime":"2026-09-12T18:45:00.000Z","runtime":"45 min","releaseInfo":"2026",
         "genres":["News"],"cast":["Jane Doe"],"directors":["John Doe"],"ratings":[{"value":"PG","system":"TVPG"}]}
    """.trimIndent()

    private fun guidePage(vararg videos: String) = AddonResponse.decode(
        """
        {"metasDetailed":[{"id":"exampletv:news","type":"tv","name":"Example News","poster":"https://example.com/news.png",
          "posterShape":"square","behaviorHints":{"isLive":true,"hasScheduledVideos":true},"videos":[${videos.joinToString(",")}]}]}
        """.trimIndent(),
    )

    @Test
    fun theSdkManifestIsAnEpgProviderWhoseDateCatalogIsAGuide() {
        val manifest = Manifest.decode(sdkManifest)
        assertTrue(manifest.behaviorHints.epgProvider)
        assertEquals(listOf(true, false), manifest.catalogs.map { it.declaresDate })
        assertFalse(Manifest.decode(sdkManifest.replace("\"epgProvider\":true", "\"epgProvider\":false")).behaviorHints.epgProvider)
        assertFalse(Manifest.decode(sdkManifest.replace("\"behaviorHints\":{\"epgProvider\":true}", "\"behaviorHints\":{}")).behaviorHints.epgProvider)
    }

    @Test
    fun theSdkProgrammeReadsWithEveryField() {
        val channel = (guidePage(sdkProgramme).content as ResourceContent.MetasDetailed).items.single()
        assertTrue(channel.preview.isLive)
        val video = channel.videos.single()
        assertEquals("Evening News", video.title)
        assertEquals("The day's headlines.", video.overview)
        assertEquals(
            Programme(
                start = Instant.parse("2026-09-12T18:00:00Z"),
                end = Instant.parse("2026-09-12T18:45:00Z"),
                runtime = "45 min",
                releaseInfo = "2026",
                genres = listOf("News"),
                cast = listOf("Jane Doe"),
                directors = listOf("John Doe"),
                ratings = listOf(Programme.Rating("PG", "TVPG")),
            ),
            video.programme,
        )
    }

    @Test
    fun aVideoIsAProgrammeOnlyWithBothTimesTheEndAfterTheStart() {
        fun programme(times: String) = (guidePage("""{"id":"v","title":"T","released":"2026-09-12T18:00:00Z"$times}""").content as ResourceContent.MetasDetailed)
            .items.single().videos.single().programme
        assertNull(programme(""), "no times: an ordinary video")
        assertNull(programme(""","startTime":"2026-09-12T18:00:00Z""""), "no end")
        assertNull(programme(""","startTime":"2026-09-12T18:00:00Z","endTime":"2026-09-12T18:00:00Z""""), "the end at the start")
        assertNull(programme(""","startTime":"2026-09-12T18:00:00Z","endTime":"2026-09-12T17:00:00Z""""), "the end before the start")
        assertNull(programme(""","startTime":"evening","endTime":"2026-09-12T19:00:00Z""""), "a start that isn't a date")
        assertEquals(
            Programme(Instant.parse("2026-09-12T18:00:00Z"), Instant.parse("2026-09-12T19:00:00Z"), cast = listOf("Jane")),
            programme(""","startTime":"2026-09-12T18:00:00Z","endTime":"2026-09-12T19:00:00Z","runtime":45,"genres":"News","cast":["Jane",7," "],"ratings":[{"system":"TVPG"}]"""),
            "malformed optional fields are absent, a list keeping its good names; the programme stays",
        )
    }

    @Test
    fun aProgrammeSurvivesBeingStored() {
        val video = (guidePage(sdkProgramme).content as ResourceContent.MetasDetailed).items.single().videos.single()
        val again = VideoSerializer.read(AddonJson.encodeToJsonElement(VideoSerializer, video))
        assertEquals(video.programme, again.programme)
    }
}
