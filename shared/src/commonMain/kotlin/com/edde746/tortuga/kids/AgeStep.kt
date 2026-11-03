package com.edde746.tortuga.kids

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An age step of a kids profile, lowest first, on the scale of the suggested ages ([ageStepOf]: 0, 6, 12, 16, 18). A
 * kids profile's cap ([com.edde746.tortuga.data.settings.ProfileSetting.KidsCap]) is one of the first three; [ADULT]
 * is only a title's.
 */
@Serializable
enum class AgeStep {
    /** Suggested 0+; an addon's G, TV-Y, TV-G or Approved. */
    @SerialName("little")
    LITTLE,

    /** Suggested 6+; an addon's PG, TV-PG, TV-Y7 or TV-Y7-FV. */
    @SerialName("kids")
    KIDS,

    /** Suggested 12+; an addon's PG-13 or TV-14. */
    @SerialName("teen")
    TEEN,

    /** Suggested 16+ and 18+; an addon's R, NC-17, TV-MA, X, TV-X or TV-AO. */
    @SerialName("adult")
    ADULT,
}

/** The step of a suggested [age]: up to 0 [AgeStep.LITTLE], up to 6 [AgeStep.KIDS], up to 12 [AgeStep.TEEN], older [AgeStep.ADULT]. */
fun ageStepOf(age: Int): AgeStep = when {
    age <= 0 -> AgeStep.LITTLE
    age <= 6 -> AgeStep.KIDS
    age <= 12 -> AgeStep.TEEN
    else -> AgeStep.ADULT
}

/**
 * The step of an addon's US [certification], ignoring case and surrounding blanks; null for NR, Not Rated, Unrated, an
 * empty one and any other text.
 */
fun ageStepOf(certification: String): AgeStep? = when (certification.trim().uppercase()) {
    "G", "TV-Y", "TV-G", "APPROVED" -> AgeStep.LITTLE
    "TV-Y7", "TV-Y7-FV", "PG", "TV-PG" -> AgeStep.KIDS
    "PG-13", "TV-14" -> AgeStep.TEEN
    "R", "NC-17", "TV-MA", "X", "TV-X", "TV-AO" -> AgeStep.ADULT
    else -> null
}
