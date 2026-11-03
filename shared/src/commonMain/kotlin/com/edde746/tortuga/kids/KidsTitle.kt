package com.edde746.tortuga.kids

import kotlinx.serialization.Serializable

/**
 * A title a parent allowed or blocked on a kids profile (the profile settings `kids.allowed` and `kids.blocked`).
 *
 * @param key the title's IMDb id (`tt…`) when known, else its addon id as given.
 * @param type the addon type (`movie`, `series`).
 * @param name the title's name when it was added, for the editor's lists.
 */
@Serializable
data class KidsTitle(val key: String, val type: String, val name: String)
