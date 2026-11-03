package com.edde746.tortuga.presentation.profiles

import com.edde746.tortuga.strings.T

/**
 * A profile avatar: a tile of [background] with the profile's initial in [foreground] (both `0xAARRGGBB`). The set
 * is muted greys and earth tones that sit well in the monochromatic design and stay apart from
 * each other in greyscale.
 *
 * @param id what the profile stores ([com.edde746.tortuga.data.profile.Profile.avatar]); synced, so ids never change.
 * @param name the avatar's name, for accessibility and the editor's picker.
 */
data class ProfileAvatar(val id: String, val name: String, val background: Long, val foreground: Long)

/** The avatars profiles can have. */
object ProfileAvatars {
    private const val DARK_TEXT = 0xFF1B1D1FL
    private const val LIGHT_TEXT = 0xFFF5F5F2L

    val all: List<ProfileAvatar>
        get() = listOf(
            ProfileAvatar("graphite", T.profiles.avatar.graphite, 0xFF3A3F44L, LIGHT_TEXT),
            ProfileAvatar("slate", T.profiles.avatar.slate, 0xFF5B6770L, LIGHT_TEXT),
            ProfileAvatar("silver", T.profiles.avatar.silver, 0xFFA7AFB5L, DARK_TEXT),
            ProfileAvatar("ivory", T.profiles.avatar.ivory, 0xFFE8E4DAL, DARK_TEXT),
            ProfileAvatar("sand", T.profiles.avatar.sand, 0xFFC9B79CL, DARK_TEXT),
            ProfileAvatar("clay", T.profiles.avatar.clay, 0xFFA86B55L, LIGHT_TEXT),
            ProfileAvatar("rose", T.profiles.avatar.rose, 0xFFB98A8EL, DARK_TEXT),
            ProfileAvatar("plum", T.profiles.avatar.plum, 0xFF6E5A73L, LIGHT_TEXT),
            ProfileAvatar("dusk", T.profiles.avatar.dusk, 0xFF4E5A7AL, LIGHT_TEXT),
            ProfileAvatar("sea", T.profiles.avatar.sea, 0xFF4F7A83L, LIGHT_TEXT),
            ProfileAvatar("sage", T.profiles.avatar.sage, 0xFF8FA38EL, DARK_TEXT),
            ProfileAvatar("moss", T.profiles.avatar.moss, 0xFF5E6B4EL, LIGHT_TEXT),
        )

    /**
     * The avatar a profile shows: its stored [id], or, for none or an id this build doesn't know (a newer build's), a
     * steady pick by [profileId].
     */
    fun forProfile(id: String?, profileId: String): ProfileAvatar {
        val avatars = all
        return avatars.firstOrNull { it.id == id } ?: avatars[profileId.hashCode().mod(avatars.size)]
    }

    /** The first avatar none of [taken] uses, for a new profile; the first one when all are taken. */
    fun unused(taken: Collection<String?>): ProfileAvatar {
        val avatars = all
        return avatars.firstOrNull { it.id !in taken } ?: avatars.first()
    }
}

/**
 * The initial a profile's avatar shows: the name's first character as a reader sees it, upper case; "" for a blank
 * name. Every place that draws an initial from a name takes it here (the profile picker and editor, Settings, the
 * navigation's profile button, a watch party's participants), so all of them, on every UI, cut a name alike.
 *
 * One character as a reader sees it, by the rules of Unicode's extended grapheme clusters (UAX #29) that a name's
 * first character meets: a letter with its combining accents and spacing vowel signs, an emoji with its variation
 * selector, skin tone and the emoji it joins with a zero-width joiner, a flag's two regional indicators, a
 * subdivision flag's tags, a keycap.
 */
fun profileInitial(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.substring(0, firstCharacterEnd(trimmed)).uppercase()
}

/** Where [text]'s (not empty) first user-perceived character ends: [profileInitial]'s rules. */
private fun firstCharacterEnd(text: String): Int {
    val first = text.codePointAt(0)
    var end = charCount(first)
    // A flag: two regional indicators, and no more (a second flag starts after them).
    if (first.isRegionalIndicator() && end < text.length && text.codePointAt(end).isRegionalIndicator()) end += 2
    var joined = false
    while (end < text.length) {
        val next = text.codePointAt(end)
        if (!joined && !next.isExtend() && next != ZERO_WIDTH_JOINER) break
        // After a joiner the next character is part of this one (an emoji sequence: 👩‍💻, 🏳️‍🌈).
        joined = next == ZERO_WIDTH_JOINER
        end += charCount(next)
    }
    return end
}

private const val ZERO_WIDTH_JOINER = 0x200D

/** Joins the character before it: combining and spacing marks, variation selectors, skin tones, tags. */
private fun Int.isExtend(): Boolean = when {
    this < 0x10000 -> when (toChar().category) {
        CharCategory.NON_SPACING_MARK, CharCategory.ENCLOSING_MARK, CharCategory.COMBINING_SPACING_MARK -> true
        // The zero-width non-joiner, Thai and Lao sara am, halfwidth katakana's sound marks.
        else -> this == 0x200C || this == 0x0E33 || this == 0x0EB3 || this in 0xFF9E..0xFF9F
    }
    // Emoji skin tones, tags (subdivision flags), the supplementary variation selectors.
    else -> this in 0x1F3FB..0x1F3FF || this in 0xE0020..0xE007F || this in 0xE0100..0xE01EF
}

private fun Int.isRegionalIndicator() = this in 0x1F1E6..0x1F1FF

private fun charCount(codePoint: Int) = if (codePoint >= 0x10000) 2 else 1

/** The code point at [index]; a lone surrogate as itself. */
private fun String.codePointAt(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
    }
    return high.code
}
