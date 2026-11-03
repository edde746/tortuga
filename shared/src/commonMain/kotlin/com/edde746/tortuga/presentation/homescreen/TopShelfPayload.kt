package com.edde746.tortuga.presentation.homescreen

import com.edde746.tortuga.data.library.libraryName
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The Apple TV's Top Shelf as the tvOS app hands it to its extension: the running profile's Continue Watching
 * ([HomeScreenItem]s, newest first) as the JSON the app writes to the App Group the two share, which the extension
 * (apple/TopShelf/TopShelfContent.swift) turns into the shelf's one row. The extension has none of the app's strings,
 * so the row's title and each item's label come in the interface language.
 *
 * Each item: `id` (`<type>/<id>`, one per title), `title` ("S1E2 · Title" for an episode, the title isolated in it;
 * the title alone for a movie), `progress` (0 to 1, absent for none), `imageUrl` (the title's poster, absent without
 * one), `imageShape` (`poster`, `square` or `landscape`), `playUrl` (the Play/Pause button: resumes,
 * [HomeScreenItem.link]) and `displayUrl` (Select: the title's Detail, [HomeScreenItem.detailLink]). No items: the
 * extension shows the app's static image.
 */
object TopShelfPayload {
    /** The format's version (TopShelfContent.schemaVersion): the extension shows nothing of a payload with another. */
    const val SCHEMA_VERSION = 1

    fun json(items: List<HomeScreenItem>): String = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("sectionTitle", T.browse.continueWatching.title)
        putJsonArray("items") { items.forEach { add(it.shelfItem()) } }
    }.toString()

    private fun HomeScreenItem.shelfItem(): JsonObject = buildJsonObject {
        put("id", "$type/$id")
        put("title", label())
        progress?.let { put("progress", it) }
        posterUrl?.let { put("imageUrl", it) }
        put("imageShape", posterShape.libraryName)
        put("playUrl", link)
        put("displayUrl", detailLink)
    }

    /** The episode first, as Home's card reads ("S1E2 · Title"), so the shelf's short label shows it before the title. */
    private fun HomeScreenItem.label(): String {
        val episode = episode ?: return title
        val code = season?.let { T.session.episode(it.toString(), episode.toString()) }
            ?: T.browse.continueWatching.episode(episode.toString())
        return T.browse.continueWatching.shelfTitle(code, isolate(title))
    }
}
