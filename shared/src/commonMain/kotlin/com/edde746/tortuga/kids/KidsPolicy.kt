package com.edde746.tortuga.kids

import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.ProfileSettings

/**
 * A kids profile's rules for one title, from its settings: the highest step it shows ([cap]), whether titles without a
 * suggested age show ([allowUnrated]), and the keys ([KidsTitle.key]) a parent [allowed] or [blocked] on it. A [cap] or
 * [blocked] list that can't be read is null and fails closed: every title the parent's lists don't settle without it
 * is blocked as unchecked (a stand-in could open what the parent closed); unreadable allowed titles or unrated switch
 * read as their defaults, none and off, which only close.
 */
internal data class KidsPolicy(val cap: AgeStep?, val allowUnrated: Boolean, val allowed: Set<String>, val blocked: Set<String>?) {
    /**
     * The verdict on the title [key] (its IMDb id when known, else its addon id; null when it has neither), a [live]
     * channel or not, with its [rating] and the age ratings its addon sent ([addonCertifications]), in this order:
     * - the block list can't be read: blocked as unchecked;
     * - blocked by a parent: blocked; allowed by a parent: allowed, whatever its ratings;
     * - a live channel: not rated;
     * - the cap can't be read, or its suggested age couldn't be looked up: blocked as unchecked;
     * - suggested for an age above [cap]: blocked with that age;
     * - no suggested age (or no id): allowed only with [allowUnrated];
     * - then any addon rating above [cap] blocks as the addon's. An addon's rating never allows a title its suggested
     *   age blocks, and never stands in for a suggested age the database hasn't.
     */
    fun decide(key: String?, live: Boolean, rating: TitleRating, addonCertifications: List<String>): KidsVerdict {
        settled(key, live)?.let { return it }
        val cap = cap ?: return KidsVerdict.Blocked(KidsBlockReason.Unchecked)
        when (rating) {
            TitleRating.Failed -> return KidsVerdict.Blocked(KidsBlockReason.Unchecked)
            is TitleRating.Rated -> if (ageStepOf(rating.age) > cap) return KidsVerdict.Blocked(KidsBlockReason.Suggested(rating.age))
            TitleRating.Unrated, TitleRating.NoId -> if (!allowUnrated) return KidsVerdict.Blocked(KidsBlockReason.NotRated)
        }
        val above = addonCertifications.firstOrNull { certification -> ageStepOf(certification)?.let { it > cap } == true }
        return if (above != null) KidsVerdict.Blocked(KidsBlockReason.ByAddon(above.trim())) else KidsVerdict.Allowed
    }

    /** The verdict no rating changes (a parent's lists, a live channel); null when it takes the title's rating. */
    fun settled(key: String?, live: Boolean): KidsVerdict? = when {
        blocked == null -> KidsVerdict.Blocked(KidsBlockReason.Unchecked)
        key != null && key in blocked -> KidsVerdict.Blocked(KidsBlockReason.ByParent)
        key != null && key in allowed -> KidsVerdict.Allowed
        live -> KidsVerdict.Blocked(KidsBlockReason.NotRated)
        else -> null
    }

    companion object {
        fun of(settings: ProfileSettings) = KidsPolicy(
            cap = settings.kidsCap.takeUnless { ProfileSetting.KidsCap.key in settings.unreadable },
            allowUnrated = settings.kidsAllowUnrated,
            allowed = settings.kidsAllowed.mapTo(HashSet()) { it.key },
            blocked = settings.kidsBlocked.takeUnless { ProfileSetting.KidsBlocked.key in settings.unreadable }?.mapTo(HashSet()) { it.key },
        )
    }
}
