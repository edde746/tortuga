package com.edde746.tortuga.ui.components

import androidx.compose.ui.graphics.Color

/**
 * Gradient stops taking [color] from alpha [from] at [start] to alpha [to] at [end] (either way round), eased at both
 * ends ([eased]): the fade meets what lies past its ends without a change of slope, so it shows no edge there. Every
 * backdrop's fade is one of these.
 */
internal fun fadeStops(color: Color, start: Float, end: Float, from: Float, to: Float = 0f): Array<Pair<Float, Color>> {
    val stops = Array(FADE_STEPS + 1) { i ->
        val t = i / FADE_STEPS.toFloat()
        (start + (end - start) * t) to color.copy(alpha = from + (to - from) * eased(t))
    }
    return if (end < start) stops.reversedArray() else stops
}

/** A fade's share done at [t] of its way (smoothstep): flat at both ends. */
internal fun eased(t: Float): Float = t * t * (3 - 2 * t)

/** The straight pieces of a [fadeStops] curve: enough that a fade across a TV's width shows no corners. */
private const val FADE_STEPS = 16
