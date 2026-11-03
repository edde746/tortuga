package com.edde746.tortuga.presentation.player

import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.lang.LanguageNames
import com.edde746.tortuga.lang.LanguageTags
import com.edde746.tortuga.playback.Track
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.session.SessionInfo
import com.edde746.tortuga.playback.subtitles.ExternalSubtitle
import com.edde746.tortuga.strings.T

/** [PlayerUiState.moreSubtitles]: the session's subtitles not added to the file ([SessionInfo.moreSubtitles]) by language. */
internal object MoreSubtitles {
    /** The key of the subtitles that name no language by code. */
    private const val OTHER = ""

    /**
     * One entry per language, in the order found within each: first the languages [tracks] already has subtitles in
     * (more of what the menu offers), then the others by name, then those without a language code ("Other").
     */
    fun languages(subtitles: List<ExternalSubtitle>, tracks: List<Track>, locale: String): List<SubtitleLanguage> {
        if (subtitles.isEmpty()) return emptyList()
        val present = tracks.filter { it.kind == TrackKind.SUBTITLE }.mapNotNullTo(HashSet()) { LanguageTags.canonical(it.language) }
        return subtitles.groupBy { it.language ?: OTHER }
            .map { (key, found) ->
                val label = if (key == OTHER) T.player.track.otherLanguage else LanguageNames.displayName(key, locale) ?: key
                val choices = found.map { OptionChoice(it.title ?: label, detail = null, selected = false, command = PlayerCommand.AddSubtitle(it.url)) }
                SubtitleLanguage(key, label, found.size.toString(), choices)
            }
            .sortedWith(compareBy<SubtitleLanguage> { it.key == OTHER }.thenBy { it.key !in present }.thenBy(localeOrder(locale)) { it.label })
    }
}
