package com.edde746.tortuga.presentation.settings

/**
 * A setting that is on or off. Not [enabled], the switch keeps [checked] and takes no change: it shows dimmed and takes
 * no focus.
 *
 * @param id the row's key, unique on its screen: what the store's `toggle` takes, where the store has one.
 */
data class SettingToggleRow(val id: String, val title: String, val subtitle: String?, val checked: Boolean, val enabled: Boolean = true)

/**
 * A bounded whole-number setting, changed one [step] at a time: a stepper (SwiftUI `Stepper`; D-pad left and right on
 * TV). The store's step intent moves the value to the next step within [min]..[max]; nothing is typed.
 *
 * @param value the stored value. One stored by an earlier version or another device can lie outside [min]..[max] or
 *   between steps; a step then moves it onto the steps.
 * @param valueLabel the value as shown: "80%", "10 seconds", "Immediately".
 * @param canDecrease a step down changes the value (it is above [min]).
 * @param canIncrease a step up changes the value (it is below [max]).
 * @param decreaseLabel the step-down button's name, with the row's title: "Decrease Seek back".
 * @param increaseLabel the step-up button's name: "Increase Seek back".
 */
data class SettingStepperRow(
    val title: String,
    val subtitle: String?,
    val value: Int,
    val valueLabel: String,
    val min: Int,
    val max: Int,
    val step: Int,
    val canDecrease: Boolean,
    val canIncrease: Boolean,
    val decreaseLabel: String,
    val increaseLabel: String,
)

/**
 * A setting with a fixed list of values: a picker, a dropdown or a list to choose from. Not [enabled], it shows its
 * value dimmed and doesn't open.
 *
 * @param id the row's key, unique on its screen: what the store's `choose` takes with an option's id, where the store
 *   has one.
 * @param unselectedLabel the value shown while no option is selected (a stored value the list doesn't offer).
 */
data class SettingChoiceRow(
    val id: String,
    val title: String,
    val subtitle: String?,
    val options: List<SettingChoiceOption>,
    val unselectedLabel: String = "",
    val enabled: Boolean = true,
) {
    /** The value as shown: the selected option's label, else [unselectedLabel]. */
    val valueLabel: String get() = options.firstOrNull { it.selected }?.label ?: unselectedLabel
}

/** One value of a [SettingChoiceRow]; [id] is what the store's `choose` takes. */
data class SettingChoiceOption(val id: String, val label: String, val selected: Boolean)
