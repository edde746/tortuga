@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.kids

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.model.MetaItemPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * A kids profile's gate looks a title's suggested age up as the title is asked about, and a failed lookup again a while
 * later; rules it can't read close it.
 */
class KidsGateTest {
    @Test
    fun aTitleWhoseLookupFailedIsLookedUpAgainAMinuteLaterNotBefore() = runTest {
        val w = kidsWorld()
        val data = w.graph.data
        val gate = KidsGate(
            w.kids.id,
            data.profiles.observeProfile(w.kids.id),
            data.settings.observe(w.kids.id),
            data.settings,
            { w.graph.ageRatings },
            backgroundScope,
            testScheduler.timeSource,
        )
        val unchecked = KidsVerdict.Blocked(KidsBlockReason.Unchecked)
        w.ages.offline = true
        assertEquals(unchecked, gate.verdict("movie", TOY_STORY.id, null))
        w.ages.offline = false

        advanceTimeBy(1.minutes - 1.milliseconds)
        assertEquals(unchecked, gate.verdict("movie", TOY_STORY.id, null))
        assertEquals(unchecked, gate.verdictNow("movie", TOY_STORY.id, null))
        runCurrent()
        assertEquals(0, w.ages.reads, "not looked up again yet")

        advanceTimeBy(1.milliseconds)
        assertEquals(unchecked, gate.verdictNow("movie", TOY_STORY.id, null), "the failure stands while the lookup runs")
        runCurrent()
        assertEquals(KidsVerdict.Allowed, gate.verdictNow("movie", TOY_STORY.id, null))
        assertEquals(1, w.ages.reads)
    }

    @Test
    fun aBlockListThatCantBeReadBlocksEveryTitleAndAnAllowLeavesItStored() = runTest {
        val w = kidsWorld()
        val data = w.graph.data
        data.settings.set(w.kids.id, ProfileSetting.KidsBlocked, listOf(KidsTitle(DEADPOOL.id, "movie", DEADPOOL.name)))
        val unreadable = "{\"a newer build's list\":true}"
        data.storeRaw(w.kids.id, ProfileSetting.KidsBlocked, unreadable)
        val gate = gate(w)

        assertEquals(KidsVerdict.Blocked(KidsBlockReason.Unchecked), gate.verdict("movie", TOY_STORY.id, null))
        gate.allow(KidsTitle(BLUEY.id, "series", BLUEY.name))
        assertEquals(listOf(BLUEY.id), data.settings.settings(w.kids.id).kidsAllowed.map { it.key })
        assertEquals(unreadable, data.storedRaw(w.kids.id, ProfileSetting.KidsBlocked), "the allow didn't rewrite the list as empty")
    }

    @Test
    fun aCapThatCantBeReadBlocksWhatItWouldDecide() = runTest {
        val w = kidsWorld()
        val data = w.graph.data
        data.settings.set(w.kids.id, ProfileSetting.KidsCap, AgeStep.LITTLE)
        data.storeRaw(w.kids.id, ProfileSetting.KidsCap, "\"a newer step\"")
        val gate = gate(w)

        // Suggested 6+: the default cap would show it; the parent's (unreadable) one may not.
        assertEquals(KidsVerdict.Blocked(KidsBlockReason.Unchecked), gate.verdict("movie", GHOSTBUSTERS.id, null))
    }

    /** A rating looked up without the IMDb id a title's catalog item adds is looked up again once it comes with it. */
    @Test
    fun aTitleSeenWithoutItsImdbIdIsLookedUpAgainWithIt() = runTest {
        val w = kidsWorld()
        w.graph.data.settings.set(w.kids.id, ProfileSetting.KidsAllowUnrated, true)
        w.ages.ages[AgeIdTable.IMDB to 9_000_001L] = 18
        val gate = gate(w)

        // Continue Watching knows it by its addon's TMDB id only, which the database hasn't.
        assertEquals(KidsVerdict.Allowed, gate.verdict("movie", "tmdb:555", null))
        val preview = MetaItemPreview("tmdb:555", "movie", "A film", imdbId = "tt9000001")
        assertEquals(KidsVerdict.Blocked(KidsBlockReason.Suggested(18)), gate.verdict("movie", "tmdb:555", preview))
        assertEquals(2, w.ages.reads)
    }

    /**
     * A profile not read yet counts as a kids profile, and so does one that is gone: the gate is closed for it, and a
     * title passes only as a kids profile's would.
     */
    @Test
    fun aProfileNotReadYetOrGoneCountsAsAKidsProfile() = runTest {
        val w = kidsWorld()
        val settings = w.graph.data.settings
        val unread = KidsGate("unread", MutableSharedFlow<Profile?>(), settings.observe("unread"), settings, { w.graph.ageRatings }, backgroundScope, testScheduler.timeSource)
        runCurrent()
        assertTrue(unread.active.value, "a profile not read yet")
        assertEquals(null, unread.verdictNow("movie", TOY_STORY.id, null), "no verdict before the profile is read")

        w.ages.offline = true
        val gone = KidsGate("gone", flowOf(null), settings.observe("gone"), settings, { w.graph.ageRatings }, backgroundScope, testScheduler.timeSource)
        assertEquals(KidsVerdict.Blocked(KidsBlockReason.Unchecked), gone.verdict("movie", TOY_STORY.id, null), "a profile that is gone")
        assertTrue(gone.active.value)
    }

    private fun TestScope.gate(w: KidsWorld) = KidsGate(
        w.kids.id,
        w.graph.data.profiles.observeProfile(w.kids.id),
        w.graph.data.settings.observe(w.kids.id),
        w.graph.data.settings,
        { w.graph.ageRatings },
        backgroundScope,
        testScheduler.timeSource,
    )

    /** Stores [json] as [setting]'s value of [profileId] as it is, as a build that wrote another type would have. */
    private suspend fun LocalData.storeRaw(profileId: String, setting: ProfileSetting<*>, json: String) = db.write {
        q.settingQueries.upsert(q.settingQueries.select(profileId, setting.key).executeAsOne().copy(value_json = json))
    }

    private suspend fun LocalData.storedRaw(profileId: String, setting: ProfileSetting<*>) =
        db.read { settingQueries.select(profileId, setting.key).executeAsOne().value_json }
}
