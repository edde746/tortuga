package com.edde746.tortuga.kids

import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.testData
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgeStepTest {
    private val data = testData()

    @AfterTest
    fun close() = data.close()

    @Test
    fun suggestedAgesMapToSteps() {
        val steps = mapOf(0 to AgeStep.LITTLE, 6 to AgeStep.KIDS, 12 to AgeStep.TEEN, 16 to AgeStep.ADULT, 18 to AgeStep.ADULT)
        for ((age, step) in steps) assertEquals(step, ageStepOf(age), "$age")
        for (age in listOf(16, 18)) {
            for (cap in listOf(AgeStep.LITTLE, AgeStep.KIDS, AgeStep.TEEN)) assertTrue(ageStepOf(age) > cap, "$age above $cap")
        }
    }

    @Test
    fun addonCertificationsMapToTheirStep() {
        val steps = mapOf(
            AgeStep.LITTLE to listOf("G", "TV-Y", "TV-G", "Approved"),
            AgeStep.KIDS to listOf("TV-Y7", "TV-Y7-FV", "PG", "TV-PG"),
            AgeStep.TEEN to listOf("PG-13", "TV-14"),
            AgeStep.ADULT to listOf("R", "NC-17", "TV-MA", "X", "TV-X", "TV-AO"),
        )
        for ((step, certifications) in steps) {
            for (certification in certifications) assertEquals(step, ageStepOf(certification), certification)
        }
        assertEquals(AgeStep.TEEN, ageStepOf(" pg-13 "), "case and surrounding blanks don't count")
        assertEquals(AgeStep.LITTLE, ageStepOf("approved"))
    }

    @Test
    fun unratedAndUnknownCertificationsHaveNoStep() {
        for (certification in listOf("NR", "Not Rated", "Unrated", "", "  ", "15", "FSK 12", "PG13")) {
            assertNull(ageStepOf(certification), "'$certification'")
        }
    }

    @Test
    fun anAdultCapIsKeptAsTeen() = runTest {
        data.settings.set("p", ProfileSetting.KidsCap, AgeStep.ADULT)
        assertEquals(AgeStep.TEEN, data.settings.settings("p").kidsCap)
        data.settings.set("p", ProfileSetting.KidsCap, AgeStep.LITTLE)
        assertEquals(AgeStep.LITTLE, data.settings.settings("p").kidsCap)
    }

    @Test
    fun allowedAndBlockedTitlesKeepOneEntryPerKey() = runTest {
        val bluey = KidsTitle("tt7678620", "series", "Bluey")
        val again = KidsTitle("tt7678620", "series", "Bluey (2018)")
        val anime = KidsTitle("kitsu:12", "series", "Pokémon")
        data.settings.set("p", ProfileSetting.KidsAllowed, listOf(bluey, anime, again))
        data.settings.set("p", ProfileSetting.KidsBlocked, listOf(anime, anime))
        val settings = data.settings.settings("p")
        assertEquals(listOf(bluey, anime), settings.kidsAllowed, "the first entry of a key stays")
        assertEquals(listOf(anime), settings.kidsBlocked)
    }
}
