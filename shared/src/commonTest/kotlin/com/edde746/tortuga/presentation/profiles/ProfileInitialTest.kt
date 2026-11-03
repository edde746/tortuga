package com.edde746.tortuga.presentation.profiles

import kotlin.test.Test
import kotlin.test.assertEquals

/** A profile's initial is the name's first character as a reader sees it, never part of one. */
class ProfileInitialTest {
    @Test
    fun aLetterIsUpperCased() {
        assertEquals("K", profileInitial("  kai "))
        assertEquals("É", profileInitial("émile"))
        assertEquals("Ж", profileInitial("жанна"))
    }

    @Test
    fun aCombiningAccentStaysWithItsLetter() {
        assertEquals("E\u0301", profileInitial("e\u0301mile"))
        assertEquals("A\u0308\u0304", profileInitial("a\u0308\u0304x"))
    }

    @Test
    fun anEmojiIsWhole() {
        assertEquals("😀", profileInitial("😀 Fun"))
        // Variation selector, skin tone, joined emoji.
        assertEquals("❤️", profileInitial("❤️ Love"))
        assertEquals("👍🏽", profileInitial("👍🏽 Yes"))
        assertEquals("👩🏽‍💻", profileInitial("👩🏽‍💻 Dev"))
        assertEquals("👨‍👩‍👧‍👦", profileInitial("👨‍👩‍👧‍👦 Family"))
        assertEquals("🏳️‍🌈", profileInitial("🏳️‍🌈"))
        assertEquals("1️⃣", profileInitial("1️⃣ One"))
    }

    @Test
    fun aFlagIsWhole() {
        assertEquals("🇸🇪", profileInitial("🇸🇪 Sweden"))
        // Two flags: the first.
        assertEquals("🇸🇪", profileInitial("🇸🇪🇳🇴"))
        // A subdivision flag: the black flag and its tags.
        assertEquals("🏴󠁧󠁢󠁳󠁣󠁴󠁿", profileInitial("🏴󠁧󠁢󠁳󠁣󠁴󠁿 Scotland"))
    }

    @Test
    fun cjkHangulAndIndicTakeOneCharacter() {
        assertEquals("東", profileInitial("東京"))
        assertEquals("김", profileInitial("김민준"))
        assertEquals("さ", profileInitial("さくら"))
        // A consonant with its vowel sign.
        assertEquals("कि", profileInitial("किरण"))
    }

    @Test
    fun aBlankNameHasNoInitial() {
        assertEquals("", profileInitial(""))
        assertEquals("", profileInitial("   "))
    }
}
