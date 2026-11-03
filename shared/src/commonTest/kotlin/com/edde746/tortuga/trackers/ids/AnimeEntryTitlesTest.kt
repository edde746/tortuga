package com.edde746.tortuga.trackers.ids

import com.edde746.tortuga.model.StremioId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * The titles MyAnimeList and AniList entries open as, over the dataset excerpts in `fixtures/trackers/` (see
 * [AnimeMappingIndexTest]): My Hero Academia's first and second seasons, Black Lagoon's second cour (TVDB's season 1
 * from episode 13), Your Name, and an entry the excerpts don't have.
 */
class AnimeEntryTitlesTest {
    private val mappings = FixtureMappings()
    private val titles = AnimeEntryTitles(mappings)

    private suspend fun of(id: String, type: String = "series") = assertNotNull(titles.of(type, StremioId.parse(id) as StremioId.Anime))

    @Test
    fun aFirstSeasonOpensOnItsFirstEpisode() = runTest {
        assertEquals(
            listOf(
                EntryTitle("series", "tt5626028", "tt5626028:1:1"),
                EntryTitle("series", "tmdb:65930", "tmdb:65930:1:1"),
                EntryTitle("series", "kitsu:11469", "kitsu:11469:1"),
                EntryTitle("series", "anilist:21459", "anilist:21459:1"),
                EntryTitle("series", "anidb:11739", "anidb:11739:1"),
            ),
            of("mal:31964"),
        )
    }

    @Test
    fun aSequelOpensItsShowOnItsSeason() = runTest {
        // My Hero Academia 2 is its own entry on MyAnimeList and AniList, and season 2 of one IMDb, TVDB and TMDB show.
        assertEquals(
            listOf(
                EntryTitle("series", "tt5626028", "tt5626028:2:1"),
                EntryTitle("series", "tmdb:65930", "tmdb:65930:2:1"),
                EntryTitle("series", "kitsu:12268", "kitsu:12268:1"),
                EntryTitle("series", "anilist:21856", "anilist:21856:1"),
                EntryTitle("series", "anidb:12233", "anidb:12233:1"),
            ),
            of("mal:33486"),
        )
        // The same entry from AniList, at a later episode of it.
        assertEquals(
            listOf(
                EntryTitle("series", "tt5626028", "tt5626028:2:5"),
                EntryTitle("series", "tmdb:65930", "tmdb:65930:2:5"),
                EntryTitle("series", "kitsu:12268", "kitsu:12268:5"),
                EntryTitle("series", "mal:33486", "mal:33486:5"),
                EntryTitle("series", "anidb:12233", "anidb:12233:5"),
            ),
            of("anilist:21856:5"),
        )
        assertEquals(EntryTitle("series", "tt5626028", "tt5626028:3:1"), of("anilist:100166").first())
    }

    @Test
    fun anEntryStartingMidSeasonOpensAtItsOwnFirstEpisode() = runTest {
        // Black Lagoon: The Second Barrage is episodes 13 to 24 of TVDB's and TMDB's season 1.
        assertEquals(
            listOf(EntryTitle("series", "tt0962826", "tt0962826:1:13"), EntryTitle("series", "tmdb:12598", "tmdb:12598:1:13")),
            of("mal:1519").take(2),
        )
        assertEquals(EntryTitle("series", "tt0962826", "tt0962826:1:15"), of("anilist:1519:3").first())
        // Without Anime-Lists, Fribb's own season and episode offset say the same.
        mappings.animeListsAvailable = false
        assertEquals(EntryTitle("series", "tt0962826", "tt0962826:1:13"), of("mal:1519").first())
    }

    @Test
    fun aFilmOpensAsAFilm() = runTest {
        assertEquals(
            listOf(
                EntryTitle("movie", "tt5311514", null),
                EntryTitle("movie", "tmdb:372058", null),
                EntryTitle("movie", "kitsu:11614", null),
                EntryTitle("movie", "anilist:21519", null),
                EntryTitle("movie", "anidb:11829", null),
            ),
            of("mal:32281", type = "movie"),
        )
    }

    @Test
    fun anEntryWithoutAMappingIsNoOtherTitle() = runTest {
        // Aoashi 2nd Season (MyAnimeList 61603, AniList 191788) isn't in the excerpt.
        assertEquals(emptyList(), of("mal:61603"))
        assertEquals(emptyList(), of("anilist:191788"))
        // Nor is any entry while Fribb's file can't be had: there are no titles to give.
        mappings.fribbAvailable = false
        assertNull(titles.of("series", StremioId.parse("mal:33486") as StremioId.Anime))
    }

    @Test
    fun bothDatasetsLoadAtOnce() = runTest {
        // Fribb's comes only once Anime-Lists' is asked for too, as two downloads under way side by side.
        val listsAsked = CompletableDeferred<Unit>()
        val sideBySide = object : AnimeMappingSource {
            override suspend fun fribb(): FribbIndex? {
                listsAsked.await()
                return mappings.fribb()
            }

            override suspend fun animeLists(): AnimeListsIndex? {
                listsAsked.complete(Unit)
                return mappings.animeLists()
            }
        }
        val found = withTimeout(1.seconds) { AnimeEntryTitles(sideBySide).of("series", StremioId.parse("mal:1519") as StremioId.Anime) }
        assertEquals(EntryTitle("series", "tt0962826", "tt0962826:1:13"), found?.first())
    }
}
