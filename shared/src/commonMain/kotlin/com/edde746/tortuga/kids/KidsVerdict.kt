package com.edde746.tortuga.kids

/** Whether a kids profile may list, open and play a title ([KidsPolicy.decide]). */
internal sealed interface KidsVerdict {
    data object Allowed : KidsVerdict

    data class Blocked(val reason: KidsBlockReason) : KidsVerdict
}

/** Why a title is blocked on a kids profile. */
internal sealed interface KidsBlockReason {
    /** Its suggested [age] (16 for "Suggested 16+") is above the profile's cap. */
    data class Suggested(val age: Int) : KidsBlockReason

    /** Its addon's age rating ([certification], as the addon sent it: `TV-MA`) is above the profile's cap. */
    data class ByAddon(val certification: String) : KidsBlockReason

    /** No suggested age (or no id to look one up by, or a live channel) while the profile doesn't allow unrated titles. */
    data object NotRated : KidsBlockReason

    /** A parent blocked it on this profile. */
    data object ByParent : KidsBlockReason

    /** Its suggested age couldn't be looked up (the age database not downloaded yet, offline): blocked until it can. */
    data object Unchecked : KidsBlockReason
}
