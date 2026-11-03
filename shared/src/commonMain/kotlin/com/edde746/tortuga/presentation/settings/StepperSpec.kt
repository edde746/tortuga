package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.strings.T

/**
 * How a settings screen offers one bounded whole-number setting as a stepper ([SettingStepperRow]): the values from
 * [min] to [max] in steps of [step] (the steps start at [min]), and how a value reads ([label]). The bounds lie inside
 * the setting's stored range (`SettingRange`), which only guards what is stored.
 */
internal class StepperSpec(val min: Int, val max: Int, val step: Int, val label: (Int) -> String) {
    init {
        require(step > 0 && min <= max) { "Bad stepper $min..$max by $step" }
    }

    fun row(title: String, subtitle: String?, value: Int): SettingStepperRow = SettingStepperRow(
        title = title,
        subtitle = subtitle,
        value = value,
        valueLabel = label(value),
        min = min,
        max = max,
        step = step,
        canDecrease = value > min,
        canIncrease = value < max,
        decreaseLabel = T.settings.decreaseNamed(title),
        increaseLabel = T.settings.increaseNamed(title),
    )

    /**
     * The value one step up or down from [value]: the next step above or below it within [min]..[max]. A value below
     * [min] steps up to [min] and one above [max] down to [max]; one between steps moves to the step next to it; a
     * step past a bound changes nothing.
     */
    fun stepped(value: Int, up: Boolean): Int = if (up) {
        when {
            value >= max -> value
            value < min -> min
            else -> minOf(min + ((value - min) / step + 1) * step, max)
        }
    } else {
        when {
            value <= min -> value
            value > max -> max
            else -> min + ((value - min - 1) / step) * step
        }
    }
}
