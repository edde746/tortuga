package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.lang.LanguageNames
import com.edde746.tortuga.lang.LanguageTags
import com.edde746.tortuga.lang.localizedLanguageName
import com.edde746.tortuga.presentation.player.localeContains
import com.edde746.tortuga.presentation.player.localeOrder
import com.edde746.tortuga.strings.Locales
import com.edde746.tortuga.strings.T

/** The languages settings screens offer, named in the interface language, and their language picker. */
internal object LanguageChoices {
    /** Every language a preference can name ([LanguageTags.preferenceCodes]), by name in [locale], in its order. */
    fun preferenceLanguages(locale: String): List<SettingLanguageOption> = LanguageTags.preferenceCodes
        .map { SettingLanguageOption(it, name(it, locale)) }
        .sortedWith(compareBy(localeOrder(locale)) { it: SettingLanguageOption -> it.name }.thenBy { it.code })

    /** The name of a stored preference [code] in [locale]; the code itself when it names no language. */
    fun name(code: String, locale: String): String = LanguageNames.displayName(code, locale) ?: code

    /** [codes] named in [locale] as one line, "English, Japanese"; null for none. */
    fun list(codes: List<String>, locale: String): String? =
        codes.takeIf { it.isNotEmpty() }?.joinToString(T.settings.listSeparator) { name(it, locale) }

    /**
     * The interface languages this build has strings for, each named in its own language, in the order of the
     * interface's.
     */
    fun interfaceLanguages(): List<Pair<String, String>> = Locales.available
        .map { tag -> tag to (localizedLanguageName(tag, tag)?.replaceFirstChar { it.uppercaseChar() } ?: tag) }
        .sortedWith(compareBy(localeOrder(T.locale)) { it.second })

    /**
     * The picker of a language list: [chosen] in their order, then the languages of [all] not chosen that match
     * [query] (by name as [locale] searches, case and accents aside, or by code). [noneChosenText] shows while nothing
     * is chosen.
     */
    fun picker(
        kind: LanguageListKind,
        title: String,
        explanation: String,
        noneChosenText: String,
        chosen: List<String>,
        all: List<SettingLanguageOption>,
        query: String,
        locale: String,
    ): LanguagePickerUiState {
        val text = T.settings.playback
        // Language names are the interface's own words: not isolated.
        val rows = chosen.mapIndexed { index, code ->
            val name = name(code, locale)
            SettingChosenLanguage(
                code = code,
                name = name,
                rank = (index + 1).toString(),
                canMoveUp = index > 0,
                canMoveDown = index < chosen.lastIndex,
                moveUpLabel = text.moveUpNamed(name),
                moveDownLabel = text.moveDownNamed(name),
                removeLabel = text.removeLanguageNamed(name),
                dragLabel = text.dragToReorderNamed(name),
                moreLabel = T.detail.moreActionsNamed(name),
            )
        }
        val needle = query.trim()
        val available = all.filter { option ->
            option.code !in chosen &&
                (needle.isEmpty() || localeContains(option.name, needle, locale) || option.code.equals(needle, ignoreCase = true))
        }
        return LanguagePickerUiState(
            kind = kind,
            title = title,
            explanation = explanation,
            chosenHeader = text.chosenHeader,
            chosen = rows,
            noneChosenText = if (rows.isEmpty()) noneChosenText else null,
            availableHeader = text.availableHeader,
            query = query,
            searchHint = text.searchLanguages,
            available = available,
            emptyText = if (available.isEmpty() && needle.isNotEmpty()) text.noLanguageMatches else null,
        )
    }

    /** [list] with the entry at [from] moved to [to] (kept within the list). */
    fun moved(list: List<String>, from: Int, to: Int): List<String> {
        val target = to.coerceIn(0, list.lastIndex)
        if (target == from) return list
        return list.toMutableList().apply { add(target, removeAt(from)) }
    }
}
