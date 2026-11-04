package com.edde746.tortuga.playback.subtitles

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonError
import com.edde746.tortuga.addons.Loadable
import com.edde746.tortuga.lang.LanguageTags
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ExtraProp
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.Extras
import com.edde746.tortuga.model.Subtitles
import com.edde746.tortuga.model.withExtra
import com.edde746.tortuga.strings.T
import kotlinx.coroutines.flow.last

/**
 * An external subtitle for the player (`PlayerEngine.addSubtitle`). [url] can carry a key, so [toString] leaves it out.
 *
 * @param language canonical BCP 47 tag (`pt-BR`), or null when the addon's `lang` names no language by code.
 * @param title what the track menu shows under the language: the subtitle's label, else the addon's name, numbered
 *   when one addon offers several in a language. With no language, the label, else the addon's `lang` as it wrote it
 *   ("English"), else the addon's name.
 */
data class ExternalSubtitle(val url: String, val language: String?, val title: String?) {
    override fun toString(): String = "ExternalSubtitle(url=<redacted>, language=$language, title=$title)"
}

/**
 * Every unique subtitle a lookup found, once: [added] go to the player as the file opens; [more] wait until the viewer
 * picks one (the player's More subtitles), since each is a download and OpenSubtitles alone can answer with hundreds.
 * [complete] is false when an addon couldn't answer this time (a timeout, a network failure, a server error, a 429): a
 * later lookup may find more.
 */
data class FoundSubtitles(val added: List<ExternalSubtitle>, val more: List<ExternalSubtitle>, val complete: Boolean = true)

/**
 * What the subtitle lookup needs from a playing stream: the video, the resolved stream's hash, size and file name
 * (stremio-core's `videoHash`, `videoSize` and `filename` extras), and the stream's own subtitles.
 */
data class SubtitleRequest(
    val type: String,
    val videoId: String,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null,
    val streamSubtitles: List<Subtitles> = emptyList(),
    /** Names the stream's own subtitles in the menu. */
    val streamAddonName: String = "",
)

/**
 * Finds subtitles for a playing video: the stream's own `subtitles` merged with every subtitle addon's
 * results for the video, queried with the resolved stream's extras.
 *
 * - Order: the stream's own first (they are made for that file), then each addon's in installed order, each list in
 *   the order the addon sent it (OpenSubtitles puts hash matches first).
 * - Duplicates (the same URL twice) are dropped.
 * - Languages: the addon's code becomes a BCP 47 tag ([LanguageTags]: `eng` → `en`, `pob` → `pt-BR`). A `lang` that
 *   is no code ("English") is kept without a language and named by its text, as the SDK has clients show it.
 * - [FoundSubtitles.added]: up to [maxPerLanguage] per wanted language, most wanted language first, exact tags
 *   (`pt-BR`) before the bare language (`pt`), then the stream's own in every other language. Adding every result
 *   would bury the useful ones in the track menu.
 * - [FoundSubtitles.more]: all the others, in order.
 *
 * An addon that fails or times out only loses its own results.
 */
class SubtitleResolver(
    private val aggregator: AddonAggregator,
    private val maxPerLanguage: Int = DEFAULT_MAX_PER_LANGUAGE,
) {
    init {
        require(maxPerLanguage > 0) { "maxPerLanguage must be positive" }
    }

    /**
     * @param addons the installed addons in order; those serving subtitles for the video are asked.
     * @param wanted canonical language tags, most wanted first.
     */
    suspend fun resolve(request: SubtitleRequest, addons: List<Descriptor>, wanted: List<String>): FoundSubtitles {
        var complete = true
        val fromAddons = aggregator.subtitles(addons, request.type, request.videoId, extra(request)).last()
            .flatMap { loadable ->
                when (val state = loadable.state) {
                    is Loadable.Ready -> state.content.map { Candidate(it, loadable.addon.manifest.name, own = false) }
                    is Loadable.Error -> {
                        Log.d(TAG) { "${loadable.addon.manifest.name}: no subtitles (${state.error})" }
                        if (transient(state.error)) complete = false
                        emptyList()
                    }
                    Loadable.Loading -> emptyList()
                }
            }
        return pick(request.streamSubtitles.map { Candidate(it, request.streamAddonName, own = true) } + fromAddons, wanted).copy(complete = complete)
    }

    private class Candidate(val subtitles: Subtitles, val source: String, val own: Boolean) {
        val language: String? = LanguageTags.canonical(subtitles.lang)
        val url: String = subtitles.url.trim()

        /** The `lang` as written when it is text rather than a code ("English"): it names the subtitle instead. */
        val languageText: String? = subtitles.lang.trim().takeIf { language == null && !LanguageTags.isCode(it) && it.isNotEmpty() }

        val titleBase: String = subtitles.label?.trim()?.takeIf { it.isNotEmpty() } ?: languageText ?: source
    }

    private fun pick(candidates: List<Candidate>, wanted: List<String>): FoundSubtitles {
        val unique = candidates.distinctBy { it.url }
        val added = LinkedHashSet<Candidate>()
        for (language in wanted) {
            unique.asSequence()
                .filter { it.language != null && it !in added }
                .map { it to LanguageTags.match(language, it.language!!) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(maxPerLanguage)
                .forEach { added += it.first }
        }
        unique.filterTo(added) { it.own }
        // Numbered across everything found, so a subtitle keeps its name whether it is added now or later.
        val totals = unique.groupingBy { it.language to it.titleBase }.eachCount()
        val counts = mutableMapOf<Pair<String?, String>, Int>()
        val titles = unique.associateWith { candidate ->
            val key = candidate.language to candidate.titleBase
            val number = counts.getOrElse(key) { 0 } + 1
            counts[key] = number
            // The track's name, which is saved with downloads and matched by TrackChooser: no isolates in it. Where it
            // shares a line with the app's words, the track menus isolate it whole.
            if (totals.getValue(key) > 1) T.session.subtitleNumbered(key.second, number.toString()) else key.second
        }
        fun Candidate.external() = ExternalSubtitle(url, language, titles.getValue(this).takeIf { it.isNotBlank() })
        return FoundSubtitles(added.map { it.external() }, unique.filter { it !in added }.map { it.external() })
    }

    companion object {
        const val DEFAULT_MAX_PER_LANGUAGE = 3
        private const val TAG = "subtitles"

        /** stremio-core's order: each `extend_one` puts its property first. */
        private fun extra(request: SubtitleRequest): List<ExtraValue> = emptyList<ExtraValue>()
            .withExtra(ExtraProp(Extras.VIDEO_HASH), request.videoHash)
            .withExtra(ExtraProp(Extras.VIDEO_SIZE), request.videoSize?.toString())
            .withExtra(ExtraProp(Extras.FILENAME), request.filename)

        /** An addon's failure another lookup may not meet: it couldn't be reached, or its server failed or throttled. */
        private fun transient(error: AddonError): Boolean = when (error) {
            AddonError.Timeout, is AddonError.Network -> true
            is AddonError.Http -> error.status >= 500 || error.status == 429
            else -> false
        }
    }
}
