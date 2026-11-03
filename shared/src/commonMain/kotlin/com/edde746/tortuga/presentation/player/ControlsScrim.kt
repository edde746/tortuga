package com.edde746.tortuga.presentation.player

import kotlin.math.pow

/**
 * What the player's controls draw behind themselves over the video (their scrim, the black in a glass), one rule for
 * every UI.
 *
 * The scrim is one gradient over the whole player, top to bottom, [EDGE] black at the top and bottom edges, clear from
 * [REACH] of the player's height in from each, straight in alpha between; every UI draws it from [stops] except
 * Apple TV, which draws AVKit's bottom ramp (clear down to 35 % of the height, 56 % black from 95 %; `PlayerScrim` in
 * SwiftUI) through [alpha]. Other blacks (a glass's, a message's pill) are each UI's own alphas for an SDR picture;
 * while [PlayerUiState.hdrPicture] each goes through [alpha].
 *
 * The controls are SDR: their white is the display's SDR white (Windows' SDR content brightness, the EDR reference
 * white on Apple, Android's SDR level), while an HDR picture behind them reaches the display's peak: 5.8 times that
 * white on a Windows HDR display (465 cd/m² over 80), up to 16 times on an XDR screen. Over an HDR picture each alpha
 * lets through the sixth power of what it lets through over SDR: where half of the video or less gets through over
 * SDR, a thirty-second of that or less. The scrim keeps its clear middle, so no edge shows where it starts. On that
 * Windows display's composed desktop the time left measured twice what its stops let through at the fifth power (5 %
 * of a 460 cd/m² peak, 2.9:1), hence the sixth.
 */
object ControlsScrim {
    /** Black at the player's top and bottom edges. */
    private const val EDGE = 0.7f
    /** How far in from each edge the scrim reaches, as a fraction of the player's height. */
    private const val REACH = 0.2f
    private const val HDR_POWER = 6
    /** Straight segments of each of the scrim's ramps over an HDR picture: enough to follow its curve. */
    private const val HDR_STOPS = 8

    /** [alpha] of black over the video, as an SDR picture takes it, for the picture at hand. */
    fun alpha(alpha: Float, hdr: Boolean): Float = if (hdr) 1f - (1f - alpha).pow(HDR_POWER) else alpha

    /** The scrim's gradient stops down the player, for the picture at hand. */
    fun stops(hdr: Boolean): List<ScrimStop> {
        val segments = if (hdr) HDR_STOPS else 1
        val top = (0..segments).map { i ->
            val t = i.toFloat() / segments
            ScrimStop(REACH * t, alpha(EDGE * (1f - t), hdr))
        }
        return top + top.asReversed().map { ScrimStop(1f - it.position, it.alpha) }
    }

    /**
     * Whether the controls' muted text over the video (the episode line, why the controls are off) stays muted. Over
     * an HDR picture it draws at the full text colour: muted text lets the video through it, and a highlight there left
     * the time left, when muted, at 2.1:1 under the darker scrim (a Windows HDR display, 465 cd/m² behind it).
     */
    fun textMuted(hdr: Boolean): Boolean = !hdr
}

/** A stop of the scrim's gradient: its [alpha] of black at [position] (0 at the player's top edge, 1 at its bottom). */
data class ScrimStop(val position: Float, val alpha: Float)
