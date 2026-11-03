package com.edde746.tortuga.playback.streams

import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.ResourceContent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The badges of every stream of two plain-text stream lists (fixtures/streams/plain-texts, a film's and an episode's),
 * one line per stream in list order, as fixtures/badges/plain-texts.txt holds them. The parser tries a pattern only
 * when the text holds one of its keys: a key that misses a spelling the lists use changes a line here.
 */
class StreamBadgeCorpusTest {
    @Test
    fun plainTextListsKeepTheirBadges() {
        val lists = listOf("streams/plain-texts/movie-ex0001.json", "streams/plain-texts/series-ex0002-1-1.json")
        val actual = lists.flatMap { file ->
            (AddonResponse.decode(FixtureCorpus.text(file)).content as ResourceContent.Streams).items
                .map { StreamBadgeParser.parse(it).toString() }
        }
        assertEquals(FixtureCorpus.text("badges/plain-texts.txt").lines().filter { it.isNotEmpty() }, actual)
    }
}
