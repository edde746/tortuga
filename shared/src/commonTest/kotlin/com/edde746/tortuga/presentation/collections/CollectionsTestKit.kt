package com.edde746.tortuga.presentation.collections

import com.edde746.tortuga.addons.json
import com.edde746.tortuga.collections.Collection
import com.edde746.tortuga.collections.CollectionFolder
import com.edde746.tortuga.collections.CollectionSource
import com.edde746.tortuga.collections.CollectionViewMode
import com.edde746.tortuga.presentation.board.BrowseEnv
import com.edde746.tortuga.presentation.board.catalogAddon
import com.edde746.tortuga.presentation.board.catalogPath
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope

// Shared by the collection stores' tests.

/**
 * An addon with a movie and a series catalog of one studio (both paged by `skip`), a search catalog and one that
 * requires a genre.
 */
internal val STUDIO = catalogAddon(
    "studio",
    """[
        {"type":"movie","id":"ember","name":"Ember Movies","extra":[{"name":"skip"}]},
        {"type":"series","id":"ember","name":"Ember Series","extra":[{"name":"skip"}]},
        {"type":"movie","id":"find","name":"Find","extra":[{"name":"search","isRequired":true}]},
        {"type":"movie","id":"genres","name":"Genres","extra":[{"name":"genre","isRequired":true,"options":["Drama","Comedy"]}]}
    ]""",
)

internal val EMBER_MOVIES = CollectionSource.AddonCatalog("org.example.studio", "movie", "ember", catalogName = "Ember Movies")
internal val EMBER_SERIES = CollectionSource.AddonCatalog("org.example.studio", "series", "ember", catalogName = "Ember Series")

/** A source of an addon the profile doesn't have. */
internal val MISSING = CollectionSource.AddonCatalog("org.example.missing", "movie", "top", addonName = "Missing Addon", catalogName = "Missing Top")

/**
 * [STUDIO]'s catalogs: the movies `m1`, `m2`, `both` then `m4` on the page at `skip=3`; the series `s1`, `both`, `s2`.
 * Anything else is not found.
 */
internal fun TestScope.studioEnv(): BrowseEnv = BrowseEnv(this) { request ->
    fun metas(type: String, vararg ids: String) = json("""{"metas":[${ids.joinToString(",") { """{"id":"$it","type":"$type","name":"Title $it"}""" }}]}""")
    when (request.url.host to request.catalogPath) {
        "studio.example.com" to "movie/ember" -> metas("movie", "m1", "m2", "both")
        "studio.example.com" to "movie/ember/skip=3" -> metas("movie", "m4")
        "studio.example.com" to "movie/ember/skip=4" -> json("""{"metas":[]}""")
        "studio.example.com" to "series/ember" -> metas("series", "s1", "both", "s2")
        else -> respond("", HttpStatusCode.NotFound)
    }
}

/** The collection `c` titled [title] with one folder `f` of [sources] (more folders after it with [more]). */
internal fun collection(
    vararg sources: CollectionSource,
    mode: CollectionViewMode = CollectionViewMode.TABS,
    allTab: Boolean = true,
    title: String = "Studios",
    more: List<CollectionFolder> = emptyList(),
) = Collection("c", title, mode, allTab, folders = listOf(folder("f", "Ember", *sources)) + more)

internal fun folder(id: String, title: String, vararg sources: CollectionSource) =
    CollectionFolder(id, title, coverUrl = "https://img.example/$id.jpg", sources = sources.toList())
