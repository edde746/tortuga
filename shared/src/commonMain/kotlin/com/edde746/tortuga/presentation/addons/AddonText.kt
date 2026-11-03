package com.edde746.tortuga.presentation.addons

import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.presentation.board.typeLabelInText
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate

/** Worded manifest facts for the addon screens; content types are worded as everywhere ([typeLabelInText]). */
internal object AddonText {
    /**
     * What a resource gives the app ("Streams"), or, for `player` and `library`, what the app sends it; a resource this
     * app doesn't know as the addon wrote it, isolated ([isolate]) for [resources]' list.
     */
    fun resource(resource: String): String = when (resource) {
        "catalog" -> T.addons.resource.catalog
        "meta" -> T.addons.resource.meta
        "stream" -> T.addons.resource.stream
        "subtitles" -> T.addons.resource.subtitles
        "addon_catalog" -> T.addons.resource.addonCatalog
        "player" -> T.addons.resource.player
        "library" -> T.addons.resource.library
        else -> isolate(resource.replaceFirstChar { it.uppercaseChar() })
    }

    fun types(manifest: Manifest): String = manifest.types.distinct().joinToString(T.addons.listSeparator, transform = ::typeLabelInText)

    fun resources(manifest: Manifest): String =
        manifest.resources.map { it.name }.distinct().joinToString(T.addons.listSeparator, transform = { resource(it) })

    fun version(manifest: Manifest): String = T.addons.version(isolate(manifest.version))

    /**
     * Its version, types and resources on one line, "Version 1.2.0 · Movies, Series · Streams" (an empty list left
     * out): the line breaks between words or after a separator, never before one.
     */
    fun facts(manifest: Manifest): String =
        listOf(version(manifest), types(manifest), resources(manifest)).filter { it.isNotEmpty() }.reduce(T.addons::factsJoin)

    /** Each catalog as "Popular (Movies)". */
    fun catalogs(manifest: Manifest): List<String> =
        manifest.catalogs.map { T.addons.catalog(isolate(it.name?.takeIf(String::isNotBlank) ?: it.id), typeLabelInText(it.type)) }
}
