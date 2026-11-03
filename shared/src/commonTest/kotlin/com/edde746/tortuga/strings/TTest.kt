package com.edde746.tortuga.strings

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The generated `T` over the real shared/i18n/ files. */
class TTest {
    @AfterTest
    fun resetLocale() {
        T.locale = "en"
    }

    @Test
    fun englishByDefault() {
        assertEquals("en", T.locale)
        assertEquals("Play", T.player.play)
        assertEquals("The server responded with HTTP 404.", T.player.error.http("404"))
    }

    @Test
    fun switchingLocale() {
        T.locale = "de"
        assertEquals("Abspielen", T.player.play)
        assertEquals("Der Server hat mit HTTP 404 geantwortet.", T.player.error.http("404"))
        assertEquals("Nächste Folge", T.player.nextEpisode)
        T.locale = "en"
        assertEquals("Play", T.player.play)
    }

    @Test
    fun pluralsUseTheSelectedLanguage() {
        T.locale = "de"
        assertEquals(listOf("0 Kanäle", "1 Kanal", "2 Kanäle"), (0..2).map(T.player.track::channels))
        T.locale = "en"
        assertEquals(listOf("0 channels", "1 channel", "21 channels"), listOf(0, 1, 21).map(T.player.track::channels))
        // A count with its noun: Polish says "32 znaki", Arabic says "one other" in the singular.
        T.locale = "pl"
        assertEquals("Nazwa może mieć maksymalnie 32 znaki.", T.profiles.editor.nameTooLong(32))
        T.locale = "ar"
        assertEquals("في انتظار أ، ب وشخص آخر…", T.watch.player.waitingMore(1, "أ، ب"))
    }

    @Test
    fun localeIsABcp47TagKeptAsGiven() {
        for (tag in listOf("de-AT", "de_CH", "DE", "de-DE-u-co-phonebk")) {
            T.locale = tag
            assertEquals(tag, T.locale)
            assertEquals("Abspielen", T.player.play, tag)
        }
        for (tag in listOf("fi", "en-GB", "", "x-klingon")) {
            T.locale = tag
            assertEquals("Play", T.player.play, tag)
        }
    }
}
