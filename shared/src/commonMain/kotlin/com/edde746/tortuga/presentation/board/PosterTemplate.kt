package com.edde746.tortuga.presentation.board

import com.edde746.tortuga.model.ImdbId
import com.edde746.tortuga.model.StremioId
import com.edde746.tortuga.net.UriComponent

/**
 * The profile's poster URL template ([com.edde746.tortuga.data.settings.ProfileSetting.PosterTemplate]): where the
 * posters of Home, Discover, Search, Library, Detail and More like this come from in place of the addons'. Each
 * placeholder becomes the title's value, URL-encoded: `{imdb_id}` (`tt0133093`: the title's own id, else the `imdb_id`
 * its addon sent), `{tmdb_id}` (`603`, from a `tmdb:603` id), `{tvdb_id}` (`81189`, from a `tvdb:81189` id) and
 * `{type}` (`movie`, `series`). A title without an id the template names keeps its addon's poster, as does one whose
 * template image fails to load (each UI's poster). Backdrops, logos, episode stills and the TV home screen's rows keep
 * the addons' art.
 */
data class PosterTemplate internal constructor(private val template: String) {
    /** The poster of the title [id] of [type] whose addon sent [imdbId] with it; null when it lacks an id the template names. */
    fun url(type: String, id: String, imdbId: String?): String? {
        var out = template
        fun fill(placeholder: String, value: () -> String?): Boolean {
            if (placeholder !in out) return true
            out = out.replace(placeholder, UriComponent.encode(value() ?: return false))
            return true
        }
        val filled = fill(TYPE) { type } && fill(IMDB) { imdbOf(id, imdbId) } && fill(TMDB) { tmdbOf(id) } && fill(TVDB) { TVDB_ID.find(id)?.groupValues?.get(1) }
        return out.takeIf { filled }
    }

    // The template holds the poster service's key: never in a log line.
    override fun toString(): String = "PosterTemplate(<redacted>)"

    companion object {
        private const val TYPE = "{type}"
        private const val IMDB = "{imdb_id}"
        private const val TMDB = "{tmdb_id}"
        private const val TVDB = "{tvdb_id}"
        private val TVDB_ID = Regex("^tvdb:([1-9][0-9]{0,9})$")

        /**
         * The profile's [template] as it applies: none on a [kids] profile, for an empty template, or for one that
         * isn't an `http` or `https` address ([valid]).
         */
        fun of(template: String, kids: Boolean): PosterTemplate? =
            template.trim().takeIf { !kids && valid(it) }?.let(::PosterTemplate)

        /** [template] is an `http` or `https` address; an empty one is not. */
        fun valid(template: String): Boolean {
            val t = template.trim()
            val rest = when {
                t.startsWith("https://", ignoreCase = true) -> t.substring(8)
                t.startsWith("http://", ignoreCase = true) -> t.substring(7)
                else -> return false
            }
            return rest.isNotEmpty() && !rest.startsWith('/')
        }

        private fun imdbOf(id: String, imdbId: String?): String? =
            (StremioId.parse(id) as? StremioId.Imdb)?.id?.value ?: imdbId?.let(ImdbId::parse)?.value

        private fun tmdbOf(id: String): String? = (StremioId.parse(id) as? StremioId.Tmdb)?.id?.toString()
    }
}

