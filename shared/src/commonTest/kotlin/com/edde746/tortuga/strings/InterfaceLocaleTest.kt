package com.edde746.tortuga.strings

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InterfaceLocaleTest {
    @AfterTest
    fun reset() {
        InterfaceLocale.setChosen(null)
        InterfaceLocale.setSystemLanguages(listOf("en"))
    }

    @Test
    fun theSystemsFirstLanguageWithStringsIsUsed() {
        InterfaceLocale.setSystemLanguages(listOf("xx-YY", "de-AT", "fr"))
        assertEquals("de-AT", T.locale)
        assertEquals("de-AT", InterfaceLocale.tag.value)
    }

    @Test
    fun anEnglishFirstListShowsEnglish() {
        InterfaceLocale.setSystemLanguages(listOf("en-US", "es-US"))
        assertEquals("en-US", InterfaceLocale.tag.value)
        assertEquals("Play", T.player.play)
    }

    @Test
    fun aFirstLanguageWithoutStringsGivesWayToTheNextEvenEnglish() {
        InterfaceLocale.setSystemLanguages(listOf("fi-FI", "en-US", "de-DE"))
        assertEquals("en-US", InterfaceLocale.tag.value)
        assertEquals("Play", T.player.play)
    }

    @Test
    fun englishOfAnyRegionIsEnglish() {
        InterfaceLocale.setSystemLanguages(listOf("en-GB", "es-ES"))
        assertEquals("en-GB", InterfaceLocale.tag.value)
        assertEquals("Play", T.player.play)
    }

    @Test
    fun aChosenLanguageOverridesTheSystemUntilItIsCleared() {
        InterfaceLocale.setSystemLanguages(listOf("de"))
        InterfaceLocale.setChosen("ja")
        assertEquals("ja", InterfaceLocale.tag.value)
        InterfaceLocale.setChosen(null)
        assertEquals("de", InterfaceLocale.tag.value)
    }

    @Test
    fun noSystemLanguageWithStringsKeepsTheFirstTag() {
        InterfaceLocale.setSystemLanguages(listOf("xx-YY"))
        assertEquals("xx-YY", T.locale)
        assertEquals("Play", T.player.play)
    }

    /**
     * The strings' language set straight on `T` (as the tests that go through every language do, Arabic first, then
     * back to English) is the tag the UI lays out by, both ways: a UI drawn after them is in English, left to right.
     */
    @Test
    fun theStringsLanguageSetOnTIsTheTag() {
        T.locale = "ar"
        assertEquals("ar", InterfaceLocale.tag.value)
        T.locale = "en"
        assertEquals("en", InterfaceLocale.tag.value)
        assertFalse(InterfaceLocale.isRightToLeft(InterfaceLocale.tag.value))
    }

    @Test
    fun onlyArabicStringsLayOutRightToLeft() {
        assertTrue(InterfaceLocale.isRightToLeft("ar"))
        assertTrue(InterfaceLocale.isRightToLeft("ar-EG"))
        assertFalse(InterfaceLocale.isRightToLeft("de"))
        assertFalse(InterfaceLocale.isRightToLeft("en"))
        // Hebrew has no strings here: the interface is English, left to right.
        assertFalse(InterfaceLocale.isRightToLeft("he"))
    }

    @Test
    fun brazilianPortugueseServesEveryPortugueseRegion() {
        for (tag in listOf("pt-BR", "pt-PT", "pt")) {
            InterfaceLocale.setChosen(tag)
            assertEquals(T.player.play, "Reproduzir", tag)
        }
    }
}
