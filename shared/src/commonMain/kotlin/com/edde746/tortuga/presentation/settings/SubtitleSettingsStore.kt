package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.DeviceSettingsRepository
import com.edde746.tortuga.data.settings.SubtitleAppearance
import com.edde746.tortuga.data.settings.SubtitleColor
import com.edde746.tortuga.data.settings.SubtitleOutline
import com.edde746.tortuga.playback.SubtitleStyle
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * This device's subtitle style screen: size, text colour and bold, outline and its colour, a box behind
 * the text, the height above the bottom, and whether styled subtitles take the style too ([SubtitleAppearance]).
 * Values are never synced; every change is written at once, and the screen and a playing video ([SubtitleStyle])
 * follow the stored values. A step applies to the stored value, so quick steps add up. The preview draws a sample
 * line with the values the player gets.
 *
 * Call everything on the main thread; [release] ends it.
 */
class SubtitleSettingsStore internal constructor(
    private val settings: DeviceSettingsRepository,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    private var stored: SubtitleAppearance? = null

    private val mutableState = MutableStateFlow(buildState())
    val state: StateFlow<SubtitleSettingsUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            settings.observe().collect {
                stored = it.subtitles
                publish()
            }
        }
    }

    /** One step up or down ([SettingStepperRow]: a stepper, D-pad left and right); nothing past the row's bounds. */
    fun stepNumber(field: SubtitleNumber, up: Boolean) {
        scope.launch { settings.update(field.setting) { field.spec.stepped(it, up) } }
    }

    /**
     * Sets the choice row [key] ([SettingChoiceRow.id]) to its option [optionId], the stored value's name; an option the
     * row doesn't offer changes nothing.
     */
    fun choose(key: String, optionId: String) {
        when (key) {
            "textColor" -> color(optionId)?.let { write(DeviceSetting.SubtitleTextColor, it) }
            "outline" -> SubtitleOutline.entries.firstOrNull { it.name == optionId }?.let { write(DeviceSetting.SubtitleOutlineThickness, it) }
            "outlineColor" -> color(optionId)?.let { write(DeviceSetting.SubtitleOutlineColor, it) }
            else -> error("Subtitle settings have no choice row $key")
        }
    }

    /** Switches the row [key] ([SettingToggleRow.id]) on or off. */
    fun toggle(key: String, checked: Boolean) {
        val setting = when (key) {
            "bold" -> DeviceSetting.SubtitleBold
            "styledOverride" -> DeviceSetting.SubtitleStyledOverride
            else -> error("Subtitle settings have no switch $key")
        }
        write(setting, checked)
    }

    fun release() {
        scope.cancel()
    }

    private fun color(id: String): SubtitleColor? = SubtitleColor.entries.firstOrNull { it.name == id }

    private fun <T> write(setting: DeviceSetting<T>, value: T) {
        scope.launch { settings.set(setting, value) }
    }

    private fun current(): SubtitleAppearance = stored ?: SubtitleAppearance.DEFAULT

    private fun numberValue(field: SubtitleNumber): Int = current().let {
        when (field) {
            SubtitleNumber.SIZE -> it.sizePercent
            SubtitleNumber.BACKGROUND -> it.backgroundPercent
            SubtitleNumber.POSITION -> it.liftPercent
        }
    }

    private fun publish() {
        mutableState.value = buildState()
    }

    private fun buildState(): SubtitleSettingsUiState {
        val values = current()
        val text = T.settings.subtitles
        fun number(field: SubtitleNumber, title: String, subtitle: String?) = field.spec.row(title, subtitle, numberValue(field))
        return SubtitleSettingsUiState(
            loading = stored == null,
            title = text.title,
            note = text.note,
            preview = subtitlePreview(values),
            textHeader = text.textHeader,
            size = number(SubtitleNumber.SIZE, text.size, null),
            textColor = colorRow("textColor", text.textColor, values.textColor),
            bold = SettingToggleRow("bold", text.bold, null, values.bold),
            outlineHeader = text.outlineHeader,
            outline = choiceRow("outline", text.outline, SubtitleOutline.entries.map { it.name to it.label }, values.outline.name),
            outlineColor = if (values.outline == SubtitleOutline.NONE) null else colorRow("outlineColor", text.outlineColor, values.outlineColor),
            background = number(SubtitleNumber.BACKGROUND, text.background, text.backgroundHint),
            positionHeader = text.positionHeader,
            position = number(SubtitleNumber.POSITION, text.position, text.positionHint),
            styledHeader = text.styledHeader,
            styledOverride = SettingToggleRow("styledOverride", text.styledOverride, text.styledOverrideHint, values.styledOverride),
        )
    }

    private fun colorRow(id: String, title: String, selected: SubtitleColor) =
        choiceRow(id, title, SubtitleColor.entries.map { it.name to it.label }, selected.name)

    private fun choiceRow(id: String, title: String, options: List<Pair<String, String>>, selected: String) =
        SettingChoiceRow(id, title, null, options.map { (optionId, label) -> SettingChoiceOption(optionId, label, optionId == selected) })
}

/** The sample line drawn with [appearance]'s [SubtitleStyle], its lengths as shares of the picture's height. */
internal fun subtitlePreview(appearance: SubtitleAppearance): SubtitlePreview {
    val style = SubtitleStyle.of(appearance)
    val height = SubtitleStyle.SCALED_HEIGHT
    return SubtitlePreview(
        text = T.settings.subtitles.previewText,
        fontSize = style.fontSize / height,
        bold = style.bold,
        textColor = opaque(appearance.textColor),
        outlineColor = opaque(appearance.outlineColor),
        outlineWidth = style.outlineSize / height,
        boxColor = if (appearance.backgroundPercent > 0) (appearance.backgroundPercent * 255 / 100.0).roundToLong() shl 24 else null,
        boxPadding = style.shadowOffset / height,
        bottom = SubtitleStyle.MARGIN_Y / height + style.lift,
    )
}

private fun opaque(color: SubtitleColor): Long = 0xFF000000L or color.rgb.toLong()

private val SubtitleColor.label: String
    get() = T.settings.subtitles.colors.let {
        when (this) {
            SubtitleColor.WHITE -> it.white
            SubtitleColor.YELLOW -> it.yellow
            SubtitleColor.GREEN -> it.green
            SubtitleColor.CYAN -> it.cyan
            SubtitleColor.GRAY -> it.gray
            SubtitleColor.BLACK -> it.black
        }
    }

private val SubtitleOutline.label: String
    get() = T.settings.subtitles.outlines.let {
        when (this) {
            SubtitleOutline.NONE -> it.none
            SubtitleOutline.THIN -> it.thin
            SubtitleOutline.THICK -> it.thick
        }
    }

private val SubtitleNumber.setting: DeviceSetting<Int>
    get() = when (this) {
        SubtitleNumber.SIZE -> DeviceSetting.SubtitleSize
        SubtitleNumber.BACKGROUND -> DeviceSetting.SubtitleBackground
        SubtitleNumber.POSITION -> DeviceSetting.SubtitleLift
    }

private val SubtitleNumber.spec: StepperSpec
    get() = when (this) {
        SubtitleNumber.SIZE -> SIZE_SPEC
        SubtitleNumber.BACKGROUND -> BACKGROUND_SPEC
        SubtitleNumber.POSITION -> POSITION_SPEC
    }

// The steppers cover the settings' stored ranges (DeviceSetting).

private val SIZE_SPEC = StepperSpec(min = 50, max = 200, step = 10) { T.settings.value.percent(it.toString()) }

private val BACKGROUND_SPEC = StepperSpec(min = 0, max = 100, step = 25) {
    if (it == 0) T.settings.subtitles.backgroundOff else T.settings.value.percent(it.toString())
}

private val POSITION_SPEC = StepperSpec(min = 0, max = 50, step = 5) {
    if (it == 0) T.settings.subtitles.positionBottom else T.settings.value.percent(it.toString())
}
