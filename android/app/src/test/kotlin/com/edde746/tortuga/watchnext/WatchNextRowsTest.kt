package com.edde746.tortuga.watchnext

import android.media.tv.TvContract.WatchNextPrograms
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.presentation.homescreen.HomeScreenItem
import kotlin.test.Test
import kotlin.test.assertEquals

/** Continue Watching as Watch Next rows, and what a publish inserts, updates and deletes. */
class WatchNextRowsTest {
    private fun item(
        id: String,
        profileId: String = ALICE,
        videoId: String? = "$id:1:2",
        kind: HomeScreenItem.Kind = HomeScreenItem.Kind.EPISODE,
        positionMillis: Long = 600_000,
        durationMillis: Long = 2_900_000,
        posterUrl: String? = "https://images.example/$id.jpg",
        posterShape: PosterShape = PosterShape.POSTER,
        engaged: Long = 1_000,
    ) = HomeScreenItem(
        profileId = profileId, type = "series", id = id, videoId = videoId, kind = kind, title = "Title $id",
        season = 1, episode = 2, positionMillis = positionMillis, durationMillis = durationMillis, progress = null,
        posterUrl = posterUrl, posterShape = posterShape, lastEngagedMillis = engaged, link = "tortuga://play?id=$id", detailLink = "",
    )

    private fun program(item: HomeScreenItem) = item.toWatchNextProgram("intent:${item.id}")

    /** The table after [plan] ran on [rows]: deleted rows gone, updated ones changed, inserted ones given new ids. */
    private fun apply(rows: List<PublishedRow>, plan: WatchNextPlan): List<PublishedRow> {
        var next = (rows.maxOfOrNull { it.id } ?: 0) + 1
        val updated = plan.update.toMap()
        return rows.filter { it.id !in plan.delete }.map { row -> updated[row.id]?.let { row.copy(program = it) } ?: row } +
            plan.insert.map { PublishedRow(next++, it, browsable = true) }
    }

    @Test
    fun anEpisodeContinuesAndAMovieAtItsStartIsNext() {
        val episode = program(item("tt1"))
        assertEquals(
            WatchNextProgram(
                key = "$ALICE/tt1", type = WatchNextPrograms.TYPE_TV_EPISODE, watchNextType = WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
                title = "Title tt1", seasonNumber = "1", episodeNumber = "2", posterUri = "https://images.example/tt1.jpg",
                posterAspectRatio = WatchNextPrograms.ASPECT_RATIO_2_3, durationMillis = 2_900_000, lastPlaybackPositionMillis = 600_000,
                lastEngagementMillis = 1_000, intentUri = "intent:tt1",
            ),
            episode,
        )
        val movie = program(item("tt2", videoId = "tt2", kind = HomeScreenItem.Kind.MOVIE, positionMillis = 0, posterShape = PosterShape.LANDSCAPE))
        assertEquals(WatchNextPrograms.TYPE_MOVIE, movie.type)
        assertEquals(WatchNextPrograms.WATCH_NEXT_TYPE_NEXT, movie.watchNextType)
        assertEquals(listOf(null, null), listOf(movie.seasonNumber, movie.episodeNumber))
        assertEquals(WatchNextPrograms.ASPECT_RATIO_16_9, movie.posterAspectRatio)
        assertEquals(null, program(item("tt3", posterUrl = null)).posterAspectRatio)
        // A position means nothing without a duration; a length past an Int is clamped.
        assertEquals(0, program(item("tt4", durationMillis = 0)).lastPlaybackPositionMillis)
        assertEquals(Int.MAX_VALUE, program(item("tt5", durationMillis = 3_000_000_000)).durationMillis)
    }

    @Test
    fun aPublishTouchesOnlyWhatChanged() {
        val first = planWatchNext(ALICE, listOf(program(item("a")), program(item("b"))), emptyList(), emptyMap())
        assertEquals(listOf("$ALICE/a", "$ALICE/b"), first.insert.map { it.key })
        val rows = apply(emptyList(), first)

        assertEquals(WatchNextPlan(emptyList(), emptyList(), emptyList(), emptyMap()), planWatchNext(ALICE, listOf(program(item("a")), program(item("b"))), rows, emptyMap()))

        // Saved progress updates the one row; an item that left Continue Watching goes.
        val moved = program(item("a", positionMillis = 700_000, engaged = 2_000))
        val plan = planWatchNext(ALICE, listOf(moved), rows, emptyMap())
        assertEquals(listOf(rows[0].id to moved), plan.update)
        assertEquals(listOf(rows[1].id), plan.delete)
        assertEquals(emptyList(), plan.insert)

        // A key's extra rows (a write that half failed before) go too.
        val doubled = rows + PublishedRow(9, rows[0].program, browsable = true)
        assertEquals(listOf(9L), planWatchNext(ALICE, listOf(program(item("a")), program(item("b"))), doubled, emptyMap()).delete)
    }

    @Test
    fun anotherProfileReplacesTheRowsAndNoProfileClearsThem() {
        val rows = apply(emptyList(), planWatchNext(ALICE, listOf(program(item("a"))), emptyList(), emptyMap()))
        val bob = planWatchNext(BOB, listOf(program(item("a", profileId = BOB))), rows, emptyMap())
        assertEquals(listOf(rows.single().id), bob.delete)
        assertEquals(listOf("$BOB/a"), bob.insert.map { it.key })
        assertEquals(listOf(rows.single().id), planWatchNext(null, emptyList(), rows, emptyMap()).delete)
    }

    @Test
    fun anItemTheUserRemovedStaysOffUntilItIsWatchedAgain() {
        val rows = apply(emptyList(), planWatchNext(ALICE, listOf(program(item("a")), program(item("b"))), emptyList(), emptyMap()))
        // The launcher hid "a"'s row: it goes, and "a" is recorded with the engagement it had.
        val hidden = listOf(rows[0].copy(browsable = false), rows[1])
        val removal = planWatchNext(ALICE, listOf(program(item("a")), program(item("b"))), hidden, emptyMap())
        assertEquals(listOf(rows[0].id), removal.delete)
        assertEquals(emptyList(), removal.insert)
        assertEquals(mapOf("$ALICE/a" to 1_000L), removal.removed)
        val left = apply(hidden, removal)

        // Saved again without being watched (a sync, a restart): still off.
        assertEquals(emptyList(), planWatchNext(ALICE, listOf(program(item("a")), program(item("b"))), left, removal.removed).insert)
        // Another profile, a PIN, signing out: the record waits for its profile.
        assertEquals(removal.removed, planWatchNext(BOB, listOf(program(item("c", profileId = BOB))), left, removal.removed).removed)
        assertEquals(removal.removed, planWatchNext(null, emptyList(), left, removal.removed).removed)

        // Watched again: back on the home screen, the record gone.
        val watched = planWatchNext(ALICE, listOf(program(item("a", engaged = 5_000)), program(item("b"))), left, removal.removed)
        assertEquals(listOf("$ALICE/a"), watched.insert.map { it.key })
        assertEquals(emptyMap(), watched.removed)

        // A record goes once its item leaves the profile's Continue Watching, so a later watch starts clean.
        assertEquals(emptyMap(), planWatchNext(ALICE, listOf(program(item("b"))), left, removal.removed).removed)
    }

    private companion object {
        const val ALICE = "8c0e2f4a-alice"
        const val BOB = "3d9b7c1e-bob"
    }
}
