package com.edde746.tortuga.kids

import kotlin.test.Test
import kotlin.test.assertEquals

/** A kids profile's verdict on titles of a catalog sample, by their suggested ages and their addons' ratings. */
class KidsPolicyTest {
    /** A title as the policy judges it: its key ([KidsTitle.key]), a live channel or not, and its rating. */
    private class Row(val name: String, val key: String?, val rating: TitleRating, val live: Boolean = false)

    private val toyStory = Row("Toy Story", "tt0114709", TitleRating.Rated(0))
    private val bluey = Row("Bluey", "tt7678620", TitleRating.Rated(0))
    private val ghostbusters = Row("Ghostbusters", "tt0087332", TitleRating.Rated(6))
    private val deadpool = Row("Deadpool", "tt1431045", TitleRating.Rated(16))

    /** A series of Cinemeta's Family genre suggested for 12. */
    private val switchedAtBirth = Row("Switched at Birth", "tt1758772", TitleRating.Rated(12))
    private val breakingBad = Row("Breaking Bad", "tt0903747", TitleRating.Rated(16))
    private val twelveAngryMen = Row("12 Angry Men", "tt0050083", TitleRating.Unrated)

    private val noCertification = Row("a film the age database hasn't", "tt9000001", TitleRating.Unrated)
    private val youtube = Row("a YouTube channel's video", "yt_id:UCbCmjCuTUZos6Inko4u57UQ", TitleRating.NoId)
    private val channel = Row("a live channel", "iptv:bbc-one", TitleRating.NoId, live = true)

    private val rows = listOf(toyStory, bluey, ghostbusters, deadpool, switchedAtBirth, breakingBad, twelveAngryMen, noCertification, youtube, channel)

    private val caps = listOf(AgeStep.LITTLE, AgeStep.KIDS, AgeStep.TEEN)

    private val allowed = KidsVerdict.Allowed
    private val notRated = KidsVerdict.Blocked(KidsBlockReason.NotRated)
    private val byParent = KidsVerdict.Blocked(KidsBlockReason.ByParent)
    private val unchecked = KidsVerdict.Blocked(KidsBlockReason.Unchecked)

    private fun suggested(age: Int) = KidsVerdict.Blocked(KidsBlockReason.Suggested(age))

    private fun byAddon(certification: String) = KidsVerdict.Blocked(KidsBlockReason.ByAddon(certification))

    private fun policy(cap: AgeStep, allowUnrated: Boolean = false, allowed: Set<String> = emptySet(), blocked: Set<String> = emptySet()) =
        KidsPolicy(cap, allowUnrated, allowed, blocked)

    private fun KidsPolicy.decide(row: Row, sent: List<String> = emptyList(), rating: TitleRating = row.rating) =
        decide(row.key, row.live, rating, sent)

    @Test
    fun eachCapShowsTheSuggestedAgesUpToItAndBlocksTheRestWithTheirAge() {
        // Little, Kids, Teen; unrated titles blocked.
        val expected = mapOf(
            toyStory to listOf(allowed, allowed, allowed),
            bluey to listOf(allowed, allowed, allowed),
            ghostbusters to listOf(suggested(6), allowed, allowed),
            deadpool to listOf(suggested(16), suggested(16), suggested(16)),
            switchedAtBirth to listOf(suggested(12), suggested(12), allowed),
            breakingBad to listOf(suggested(16), suggested(16), suggested(16)),
            twelveAngryMen to listOf(notRated, notRated, notRated),
            noCertification to listOf(notRated, notRated, notRated),
            youtube to listOf(notRated, notRated, notRated),
            channel to listOf(notRated, notRated, notRated),
        )
        assertEquals(rows, expected.keys.toList())
        for ((row, verdicts) in expected) assertEquals(verdicts, caps.map { policy(it).decide(row) }, row.name)
    }

    @Test
    fun allowingUnratedTitlesLetsThoseWithoutASuggestedAgeThroughButNoLiveChannelOrOlderTitle() {
        val expected = mapOf(
            toyStory to listOf(allowed, allowed, allowed),
            bluey to listOf(allowed, allowed, allowed),
            ghostbusters to listOf(suggested(6), allowed, allowed),
            deadpool to listOf(suggested(16), suggested(16), suggested(16)),
            switchedAtBirth to listOf(suggested(12), suggested(12), allowed),
            breakingBad to listOf(suggested(16), suggested(16), suggested(16)),
            twelveAngryMen to listOf(allowed, allowed, allowed),
            noCertification to listOf(allowed, allowed, allowed),
            youtube to listOf(allowed, allowed, allowed),
            channel to listOf(notRated, notRated, notRated),
        )
        for ((row, verdicts) in expected) assertEquals(verdicts, caps.map { policy(it, allowUnrated = true).decide(row) }, row.name)
    }

    @Test
    fun aParentsAllowedTitlePassesWhateverItsRatingAndABlockedOneNeverDoes() {
        val keys = rows.mapNotNull { it.key }.toSet()
        for (cap in caps) {
            val allowing = policy(cap, allowed = keys)
            for (row in rows) {
                assertEquals(allowed, allowing.decide(row), "${row.name} allowed, cap $cap")
                assertEquals(allowed, allowing.decide(row, sent = listOf("TV-MA"), rating = TitleRating.Failed), "${row.name} allowed, unchecked")
            }
            for (allowUnrated in listOf(false, true)) {
                // On both lists, the block stands.
                val blocking = policy(cap, allowUnrated, allowed = keys, blocked = keys)
                for (row in rows) assertEquals(byParent, blocking.decide(row), "${row.name} blocked, cap $cap")
            }
        }
        // A title without a key is on neither list.
        val unkeyed = Row("an item without an id", key = null, rating = TitleRating.NoId)
        assertEquals(notRated, policy(AgeStep.TEEN, allowed = keys, blocked = keys).decide(unkeyed))
        assertEquals(allowed, policy(AgeStep.TEEN, allowUnrated = true, blocked = keys).decide(unkeyed))
    }

    @Test
    fun anAddonsRatingOnlyEverTightensTheVerdict() {
        // Suggested 6+ under a Teen cap, the addon's TV-MA: blocked by the addon's rating, as the addon sent it.
        assertEquals(byAddon("TV-MA"), policy(AgeStep.TEEN).decide(ghostbusters, sent = listOf("PG", " TV-MA ")))
        assertEquals(byAddon("PG-13"), policy(AgeStep.KIDS).decide(toyStory, sent = listOf("PG-13")))
        // An addon's G opens neither a title suggested for 16 nor one without a suggested age.
        assertEquals(suggested(16), policy(AgeStep.TEEN).decide(deadpool, sent = listOf("G")))
        assertEquals(notRated, policy(AgeStep.TEEN).decide(twelveAngryMen, sent = listOf("G")))
        assertEquals(notRated, policy(AgeStep.TEEN).decide(youtube, sent = listOf("TV-Y")))
        // With unrated titles allowed, an unrated one still stops at an addon's rating above the cap.
        assertEquals(byAddon("TV-MA"), policy(AgeStep.TEEN, allowUnrated = true).decide(twelveAngryMen, sent = listOf("TV-MA")))
        // Ratings the scale doesn't place (another country's) change nothing.
        assertEquals(allowed, policy(AgeStep.LITTLE).decide(toyStory, sent = listOf("FSK 0", "16", "Not Rated")))
    }

    @Test
    fun aRatingThatCouldntBeLookedUpBlocksUntilItCan() {
        for (cap in caps) {
            for (allowUnrated in listOf(false, true)) {
                assertEquals(unchecked, policy(cap, allowUnrated).decide(toyStory, rating = TitleRating.Failed), "cap $cap")
                assertEquals(unchecked, policy(cap, allowUnrated).decide(toyStory, sent = listOf("G"), rating = TitleRating.Failed))
            }
        }
        // A live channel has no rating to look up.
        assertEquals(notRated, policy(AgeStep.TEEN, allowUnrated = true).decide(channel, rating = TitleRating.Failed))
    }
}
