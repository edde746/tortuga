package com.edde746.tortuga.data.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the player does with intros, recaps or credits found in a video's chapters (`SkipSegments`; the profile's
 * [ProfileSetting.SkipIntros], [ProfileSetting.SkipRecaps] and [ProfileSetting.SkipCredits]).
 */
@Serializable
enum class SkipMode {
    /** They play as the rest of the video does. */
    @SerialName("off")
    OFF,

    /** A skip button shows as one starts. */
    @SerialName("button")
    BUTTON,

    /** The skip button counts down a few seconds and then skips, unless the viewer does anything first. */
    @SerialName("auto")
    AUTO,
}
