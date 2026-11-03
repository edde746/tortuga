package com.edde746.tortuga.strings

import com.edde746.tortuga.strings.PluralCategory.FEW
import com.edde746.tortuga.strings.PluralCategory.MANY
import com.edde746.tortuga.strings.PluralCategory.ONE
import com.edde746.tortuga.strings.PluralCategory.OTHER
import com.edde746.tortuga.strings.PluralCategory.TWO
import com.edde746.tortuga.strings.PluralCategory.ZERO
import kotlin.test.Test
import kotlin.test.assertEquals

/** Expected values from CLDR 48's plurals.json (integer samples). */
class PluralRuleTest {
    private val edges = intArrayOf(0, 1, 2, 5, 21, 22, 101)

    private fun categories(tag: String, vararg numbers: Int) = numbers.map { PluralRule.forTag(tag).select(it) }

    @Test
    fun english() {
        assertEquals(listOf(OTHER, ONE, OTHER, OTHER, OTHER, OTHER, OTHER), categories("en", *edges))
    }

    @Test
    fun french() {
        assertEquals(listOf(ONE, ONE, OTHER, OTHER, OTHER, OTHER, OTHER), categories("fr", *edges))
        assertEquals(listOf(MANY, MANY, OTHER), categories("fr", 1_000_000, 2_000_000, 1_000_001))
    }

    @Test
    fun polish() {
        assertEquals(listOf(MANY, ONE, FEW, MANY, MANY, FEW, MANY), categories("pl", *edges))
        assertEquals(listOf(MANY, MANY, FEW, MANY), categories("pl", 12, 112, 104, 111))
    }

    @Test
    fun russian() {
        assertEquals(listOf(MANY, ONE, FEW, MANY, ONE, FEW, ONE), categories("ru", *edges))
        assertEquals(listOf(MANY, MANY, MANY, FEW), categories("ru", 11, 111, 12, 1_002))
        assertEquals(categories("ru", *edges), categories("uk", *edges))
    }

    @Test
    fun arabic() {
        assertEquals(listOf(ZERO, ONE, TWO, FEW, MANY, MANY, OTHER), categories("ar", *edges))
        assertEquals(listOf(FEW, FEW, MANY, MANY, OTHER, OTHER, FEW, MANY), categories("ar", 3, 10, 11, 99, 100, 102, 103, 111))
    }

    @Test
    fun portugueseDependsOnTheRegion() {
        assertEquals(listOf(ONE, ONE, OTHER, MANY), categories("pt", 0, 1, 2, 1_000_000))
        assertEquals(listOf(ONE, ONE), categories("pt-BR", 0, 1))
        assertEquals(listOf(OTHER, ONE, OTHER, MANY), categories("pt-PT", 0, 1, 2, 1_000_000))
        assertEquals(listOf(OTHER, ONE), categories("pt_pt", 0, 1))
    }

    @Test
    fun otherLanguages() {
        assertEquals(listOf(OTHER, ONE, OTHER, MANY), categories("es", 0, 1, 2, 1_000_000))
        assertEquals(listOf(OTHER, ONE, MANY), categories("it", 0, 1, 3_000_000))
        assertEquals(listOf(OTHER, OTHER, OTHER), categories("ja", 0, 1, 2))
        assertEquals(listOf(OTHER, OTHER), categories("zh-Hant", 1, 2))
        assertEquals(listOf(OTHER, ONE, OTHER, OTHER), categories("de", 0, 1, 2, 1_000_000))
    }

    @Test
    fun newLanguages() {
        assertEquals(listOf(OTHER, OTHER, OTHER), categories("id", 0, 1, 2))
        assertEquals(listOf(OTHER, OTHER), categories("th", 1, 2))
        assertEquals(listOf(OTHER, OTHER), categories("vi", 1, 2))
        assertEquals(listOf(ONE, ONE, OTHER), categories("hi", 0, 1, 2))
        assertEquals(listOf(OTHER, ONE, FEW, FEW, OTHER), categories("cs", 0, 1, 2, 4, 5))
        assertEquals(listOf(FEW, ONE, FEW, FEW, OTHER, OTHER, FEW), categories("ro", 0, 1, 2, 19, 20, 100, 101))
        assertEquals(listOf(OTHER, ONE, OTHER), categories("tr", 0, 1, 2))
        assertEquals(listOf(OTHER, ONE, OTHER), categories("nl", 0, 1, 2))
        assertEquals(listOf(OTHER, ONE, OTHER), categories("sv", 0, 1, 2))
    }

    @Test
    fun unknownLanguagesUseEnglishRules() {
        assertEquals(categories("en", *edges), categories("tlh", *edges))
        assertEquals(categories("en", *edges), categories("", *edges))
    }

    @Test
    fun negativeCountsUseTheAbsoluteValue() {
        assertEquals(listOf(ONE, ONE, FEW), categories("ru", -1, -21, -22))
        assertEquals(listOf(ONE), categories("en", -1))
        // |Int.MIN_VALUE| overflows Int; 2147483648 % 100 = 48.
        assertEquals(listOf(MANY), categories("ar", Int.MIN_VALUE))
    }
}
