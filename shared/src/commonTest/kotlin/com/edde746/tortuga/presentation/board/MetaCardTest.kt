package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.presentation.library.LibraryRow
import com.edde746.tortuga.strings.T
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What a card shows and says beside its artwork, the same on every UI. */
class MetaCardTest {
    private fun percent(value: Int) = T.settings.value.percent(value.toString())

    @Test
    fun anItemSaysItsBadgeItsDetailItsProgressThenWatchedInThatOrder() {
        assertEquals(
            listOf("2 new episodes", "S1E3 · 12 min left", percent(45), T.library.watched).joinToString(T.detail.listSeparator),
            itemStateText(badge = "2 new episodes", detail = "S1E3 · 12 min left", progress = 0.45, watched = true),
        )
        assertEquals(listOf("1994 · ★ 9.3", T.library.watched).joinToString(T.detail.listSeparator), itemStateText(null, "1994 · ★ 9.3", null, watched = true))
        assertNull(itemStateText(null, null, null, watched = false), "nothing to say")
    }

    @Test
    fun progressIsSaidToTheNearestPercentFromNoneToAll() {
        assertEquals(percent(44), itemStateText(null, null, 0.444, watched = false))
        assertEquals(percent(45), itemStateText(null, null, 0.446, watched = false))
        assertEquals(percent(0), itemStateText(null, null, 0.004, watched = false))
        assertEquals(percent(0), itemStateText(null, null, -0.2, watched = false))
        assertEquals(percent(100), itemStateText(null, null, 1.3, watched = false))
    }

    @Test
    fun aCardCountsItsNewEpisodesOnlyWhenThereAreAnyAndDrawsNoBarBeforeAnythingPlayed() {
        val upNext = ContinueWatchingCard("tt1", "series", "tt1:1:3", "Chernobyl", detail = null, progress = 0f, posterUrl = null, posterShape = PosterShape.POSTER)
        assertNull(upNext.badge)
        assertNull(upNext.badgeDescription)
        assertNull(upNext.shownProgress, "\"Up next\" has nothing played yet")
        val playing = upNext.copy(progress = 0.3f, newEpisodes = 2)
        assertEquals("2", playing.badge)
        assertEquals(T.calendar.newEpisodes(2), playing.badgeDescription)
        assertEquals(0.3f, playing.shownProgress)

        val row = LibraryRow("tt1", "series", "Chernobyl", null, PosterShape.POSTER, progress = null, watched = false, menu = emptyList())
        assertNull(row.badge)
        assertNull(row.badgeDescription)
        assertEquals("3", row.copy(newEpisodes = 3).badge)
        assertEquals(T.calendar.newEpisodes(3), row.copy(newEpisodes = 3).badgeDescription)
    }

    @Test
    fun aCardsSubtitleIsItsYearAndRatingOrWhicheverTheAddonSent() {
        val card = MetaCard("tt1", "movie", "The Shawshank Redemption", year = "1994", rating = "9.3", posterUrl = null, posterShape = PosterShape.POSTER, backgroundUrl = null)
        assertEquals(T.browse.card.yearAndRating("1994", "9.3"), card.subtitle)
        assertEquals("1994", card.copy(rating = null).subtitle)
        assertEquals(T.browse.card.rating("9.3"), card.copy(year = null).subtitle)
        assertNull(card.copy(year = null, rating = null).subtitle)
    }

    @Test
    fun aCardShowsTheAddonsImdbRatingWhateverTheCategorysCase() {
        fun card(category: String) = (
            AddonResponse.decode(
                """{"metas":[{"id":"tt1","type":"movie","name":"X","links":[{"name":"8.6","category":"$category","url":"https://imdb.com/title/tt1"}]}]}""",
            ).content as ResourceContent.Metas
            ).items.single().toCard()
        assertEquals("8.6", card("imdb").rating)
        assertEquals("8.6", card("IMDb").rating)
        assertEquals("8.6", card(" IMDB ").rating)
        assertNull(card("Genres").rating)
    }
}
