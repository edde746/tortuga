package com.edde746.tortuga.presentation.homescreen

import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.strings.InterfaceLocale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Top Shelf's payload, the contract with the tvOS extension: what [TopShelfPayload] writes for a profile's
 * Continue Watching is `fixtures/top-shelf/continue-watching.json`, the file the extension's mapping tests read
 * (apple/TortugaUITests/tvOS/TopShelfContentTests.swift). An episode's label leads with its code (its title isolated
 * after it), a Kitsu-style one with its episode alone, a movie's is its title; the row's title and the labels are in
 * the interface language.
 */
class TopShelfPayloadTest {
    @AfterTest
    fun reset() {
        InterfaceLocale.setSystemLanguages(listOf("en"))
    }

    private val items = listOf(
        item("series", "tt0411008", "tt0411008:2:5", "Lost", season = 2, episode = 5, progress = 0.25,
            poster = "https://images.metahub.space/poster/medium/tt0411008/img", shape = PosterShape.POSTER),
        item("movie", "tt0113277", "tt0113277", "Heat", progress = 0.5,
            poster = "https://images.metahub.space/poster/medium/tt0113277/img", shape = PosterShape.SQUARE),
        // Up next on an episode-numbered (Kitsu) series: no progress, no poster.
        item("series", "kitsu:1", "kitsu:1:7", "Cowboy Bebop", episode = 7, shape = PosterShape.LANDSCAPE),
    )

    @Test
    fun aProfilesContinueWatchingIsTheFixtureTheExtensionReads() {
        assertEquals(Json.parseToJsonElement(FixtureCorpus.text("top-shelf/continue-watching.json")), Json.parseToJsonElement(TopShelfPayload.json(items)))
    }

    @Test
    fun theRowAndTheLabelsAreInTheInterfaceLanguage() {
        InterfaceLocale.setSystemLanguages(listOf("de"))
        val payload = Json.parseToJsonElement(TopShelfPayload.json(items)).jsonObject
        assertEquals("Weiterschauen", payload.getValue("sectionTitle").jsonPrimitive.content)
        val labels = payload.getValue("items").jsonArray.map { it.jsonObject.getValue("title").jsonPrimitive.content }
        assertEquals(listOf("S2 F5 · \u2068Lost\u2069", "Heat", "Folge 7 · \u2068Cowboy Bebop\u2069"), labels)
    }

    @Test
    fun noItemsIsAnEmptyRow() {
        val payload = Json.parseToJsonElement(TopShelfPayload.json(emptyList())).jsonObject
        assertEquals(TopShelfPayload.SCHEMA_VERSION.toString(), payload.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(0, payload.getValue("items").jsonArray.size)
    }

    private fun item(
        type: String,
        id: String,
        videoId: String,
        title: String,
        season: Int? = null,
        episode: Int? = null,
        progress: Double? = null,
        poster: String? = null,
        shape: PosterShape,
    ) = HomeScreenItem(
        profileId = PROFILE,
        type = type,
        id = id,
        videoId = videoId,
        kind = if (videoId == id) HomeScreenItem.Kind.MOVIE else HomeScreenItem.Kind.EPISODE,
        title = title,
        season = season,
        episode = episode,
        positionMillis = 0,
        durationMillis = 0,
        progress = progress,
        posterUrl = poster,
        posterShape = shape,
        lastEngagedMillis = 0,
        link = HomeScreenLink.Play(PROFILE, type, id, videoId).toUrl(),
        detailLink = HomeScreenLink.Detail(PROFILE, type, id).toUrl(),
    )

    private companion object {
        const val PROFILE = "profile-1"
    }
}
