package com.edde746.tortuga.strings

import kotlinx.coroutines.flow.StateFlow

/**
 * Which language the interface is in, and the observable behind `T.locale`. The platform reports the system's
 * languages as it starts ([com.edde746.tortuga.app.SystemInputs.report]) and again when they change
 * ([setSystemLanguages]); the running profile's "Interface language" setting, when it isn't "System default", overrides
 * them ([setChosen]). The UI redraws itself when [tag] changes and lays out right to left when [isRightToLeft] says so.
 * Main thread only.
 */
object InterfaceLocale {
    private var system: List<String> = emptyList()
    private var chosen: String? = null

    /**
     * `T.locale` as the UI should see it: a new value means every string changed. It is `T.locale` whoever set it, so it
     * never shows a language the strings have left (a test that switches the strings' language and back).
     */
    val tag: StateFlow<String> = Locales.selected

    /**
     * The languages this build has strings for (`shared/i18n/<tag>.i18n.json`), English first: those a profile can
     * choose.
     */
    val available: List<String> get() = Locales.available

    /**
     * The system's preferred languages as BCP 47 tags, most preferred first. With no chosen language, the interface
     * uses the first of them that has strings ([Locales.hasStrings]: English, any region, or a language of
     * [Locales.available]); if none does, English strings under the first tag's number formats.
     */
    fun setSystemLanguages(tags: List<String>) {
        if (tags == system) return
        system = tags
        apply()
    }

    /** The profile's interface language, or null for the system's. */
    fun setChosen(tag: String?) {
        if (tag == chosen) return
        chosen = tag
        apply()
    }

    /** Whether the strings shown for [tag] (default: the current language) are in a right-to-left language. */
    fun isRightToLeft(tag: String = T.locale): Boolean {
        val shown = Locales.resolve(tag)
        return shown.substringBefore('-').lowercase() in RIGHT_TO_LEFT
    }

    private fun apply() {
        val next = chosen ?: system.firstOrNull { Locales.hasStrings(it) } ?: system.firstOrNull() ?: Locales.available[0]
        T.locale = next
    }

    // The scripts of the languages this build can show are Arabic only; the rest are for when a language is added.
    private val RIGHT_TO_LEFT = setOf("ar", "fa", "he", "ur", "ps", "sd", "ug", "yi", "dv")
}
