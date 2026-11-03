package com.edde746.tortuga.input

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a single click on the video does with a mouse ([DesktopKeymap]; the profile's `ProfileSetting.VideoClick`). A
 * double click toggles fullscreen either way, and the keys and the play button still pause. Touch screens and remotes
 * don't follow it: a tap always shows or hides the controls, and a remote has no clicks.
 */
@Serializable
enum class VideoClickAction {
    /** Pauses, or plays when paused. */
    @SerialName("pause")
    PAUSE,

    /** Hides the controls when they show and shows them when they don't, as a tap does; never pauses. */
    @SerialName("controls")
    CONTROLS,
}
