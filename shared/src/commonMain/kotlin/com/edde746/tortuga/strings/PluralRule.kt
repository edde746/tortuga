package com.edde746.tortuga.strings

import kotlin.math.abs

/** CLDR plural categories (https://cldr.unicode.org/index/cldr-spec/plural-rules). */
internal enum class PluralCategory { ZERO, ONE, TWO, FEW, MANY, OTHER }

/**
 * CLDR 48 cardinal plural rules, restricted to integers (the generated plural accessors take `n: Int`), one entry per
 * distinct rule. [forTag] maps the languages the app ships; any other language gets English rules. Negative numbers use their absolute value, as CLDR's operand `n` does.
 */
internal enum class PluralRule {
    /** ja, ko, zh, id, th, vi: a single form. */
    OTHER_ONLY,

    /** en, de, nl, sv, da, nb, hu, tr, az, kk, uz, bg: one = 1. */
    ONE,

    /** es, it, ca, pt-PT: one = 1; many = non-zero multiples of 1 000 000. */
    ONE_MILLIONS,

    /** fr, pt: one = 0 and 1; many = non-zero multiples of 1 000 000. */
    ZERO_ONE_MILLIONS,

    /** ru, uk, be: one = …1 except …11; few = …2-…4 except …12-…14; many = the rest. */
    EAST_SLAVIC,

    /** hi: one = 0 and 1. */
    HINDI,

    /** cs: one = 1; few = 2-4. */
    CZECH,

    /** ro: one = 1; few = 0 and …01-…19 except 1 (mod 100); other = the rest. */
    ROMANIAN,

    /** pl: one = 1; few = …2-…4 except …12-…14; many = the rest. */
    POLISH,

    /** ar: zero = 0; one = 1; two = 2; few = …03-…10; many = …11-…99; other = the rest (100, 101, 102, …). */
    ARABIC;

    fun select(count: Int): PluralCategory {
        val n = abs(count.toLong())
        val mod10 = n % 10
        val mod100 = n % 100
        val millions = n != 0L && n % 1_000_000 == 0L
        return when (this) {
            OTHER_ONLY -> PluralCategory.OTHER
            ONE -> if (n == 1L) PluralCategory.ONE else PluralCategory.OTHER
            HINDI -> if (n <= 1L) PluralCategory.ONE else PluralCategory.OTHER
            CZECH -> when {
                n == 1L -> PluralCategory.ONE
                n in 2L..4L -> PluralCategory.FEW
                else -> PluralCategory.OTHER
            }
            ROMANIAN -> when {
                n == 1L -> PluralCategory.ONE
                n == 0L || mod100 in 1L..19L -> PluralCategory.FEW
                else -> PluralCategory.OTHER
            }
            ONE_MILLIONS -> when {
                n == 1L -> PluralCategory.ONE
                millions -> PluralCategory.MANY
                else -> PluralCategory.OTHER
            }
            ZERO_ONE_MILLIONS -> when {
                n <= 1L -> PluralCategory.ONE
                millions -> PluralCategory.MANY
                else -> PluralCategory.OTHER
            }
            EAST_SLAVIC -> when {
                mod10 == 1L && mod100 != 11L -> PluralCategory.ONE
                mod10 in 2L..4L && mod100 !in 12L..14L -> PluralCategory.FEW
                else -> PluralCategory.MANY
            }
            POLISH -> when {
                n == 1L -> PluralCategory.ONE
                mod10 in 2L..4L && mod100 !in 12L..14L -> PluralCategory.FEW
                else -> PluralCategory.MANY
            }
            ARABIC -> when {
                n == 0L -> PluralCategory.ZERO
                n == 1L -> PluralCategory.ONE
                n == 2L -> PluralCategory.TWO
                mod100 in 3L..10L -> PluralCategory.FEW
                mod100 in 11L..99L -> PluralCategory.MANY
                else -> PluralCategory.OTHER
            }
        }
    }

    companion object {
        /** The rule for a BCP 47 tag (`_` works as a separator); only European Portuguese depends on the region. */
        fun forTag(tag: String): PluralRule {
            val subtags = tag.replace('_', '-').lowercase().split('-')
            return when (subtags[0]) {
                "ja", "ko", "zh", "id", "th", "vi" -> OTHER_ONLY
                "es", "it", "ca" -> ONE_MILLIONS
                "pt" -> if ("pt" in subtags.drop(1)) ONE_MILLIONS else ZERO_ONE_MILLIONS
                "fr" -> ZERO_ONE_MILLIONS
                "ru", "uk", "be" -> EAST_SLAVIC
                "pl" -> POLISH
                "ar" -> ARABIC
                "hi" -> HINDI
                "cs" -> CZECH
                "ro" -> ROMANIAN
                else -> ONE
            }
        }
    }
}
