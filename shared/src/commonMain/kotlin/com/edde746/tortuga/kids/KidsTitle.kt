package com.edde746.tortuga.kids

import com.edde746.tortuga.model.ImdbId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A title a parent allowed or blocked on a kids profile (the profile settings `kids.allowed` and `kids.blocked`).
 *
 * @param id the title's IMDb id (`tt…`) when known, else its addon id as given; stored as `key`.
 * @param type the addon type (`movie`, `series`).
 * @param name the title's name when it was added, for the editor's lists.
 */
@Serializable
data class KidsTitle(@SerialName("key") val id: String, val type: String, val name: String) {
    /** Which titles the parent's rule is about: one per key ([keyOf]). */
    val key: String get() = keyOf(type, id)

    companion object {
        /**
         * The rule key of the title [id] of [type]: an IMDb id names one title whatever its type; any other id only
         * within its type (TMDB numbers its films and its series apart: `tmdb:123` is a film and a series).
         */
        fun keyOf(type: String, id: String): String = if (ImdbId.parse(id) != null) id else "$type/$id"
    }
}
