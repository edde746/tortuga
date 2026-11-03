package com.edde746.tortuga.collections

import com.edde746.tortuga.model.PosterShape
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** A collection has one tile shape: read from its folders' shapes in a file, written as every folder's. */
class CollectionShapeTest {
    private val source = """[{"provider":"addon","addonId":"a","type":"movie","catalogId":"top"}]"""

    /** A file of one collection whose folders have [shapes] (null: no `tileShape`, Nuvio's square). */
    private fun shapeOf(vararg shapes: String?): PosterShape {
        val folders = shapes.mapIndexed { i, shape ->
            val tile = shape?.let { ""","tileShape":"$it"""" }.orEmpty()
            """{"id":"f$i","title":"F$i"$tile,"sources":$source}"""
        }
        return NuvioCollections.decode("""[{"id":"c","title":"C","folders":[${folders.joinToString(",")}]}]""").collections.single().shape
    }

    @Test
    fun theShapeMostFoldersHaveWins() {
        assertEquals(PosterShape.POSTER, shapeOf("LANDSCAPE", "POSTER", "POSTER"))
        assertEquals(PosterShape.LANDSCAPE, shapeOf("square", "Landscape", "wide", "POSTER"))
        assertEquals(PosterShape.SQUARE, shapeOf("POSTER", null, null), "no tileShape is square")
    }

    @Test
    fun aTieGoesToTheFirstFolderAmongTheTied() {
        assertEquals(PosterShape.SQUARE, shapeOf("SQUARE", "POSTER", "SQUARE", "POSTER"))
        assertEquals(PosterShape.POSTER, shapeOf("POSTER", "LANDSCAPE"))
        assertEquals(PosterShape.LANDSCAPE, shapeOf("SQUARE", "LANDSCAPE", "POSTER", "POSTER", "LANDSCAPE"))
    }

    @Test
    fun aFolderLeftOutStillCountsTowardsTheShape() {
        val file = NuvioCollections.decode(
            """[{"id":"c","title":"C","folders":[
              {"id":"a","title":"A","tileShape":"POSTER","sources":$source},
              {"id":"b","title":"B","tileShape":"LANDSCAPE","sources":[{"provider":"letterboxd"}]},
              {"id":"d","title":"D","tileShape":"LANDSCAPE","sources":[]}]}]""",
        )
        assertEquals(PosterShape.LANDSCAPE, file.collections.single().shape)
        assertEquals(listOf("a"), file.collections.single().folders.map { it.id })
    }

    @Test
    fun anExportWritesTheCollectionsShapeOnEveryFolder() {
        val addon = CollectionSource.AddonCatalog("org.example", "movie", "top")
        val collection = Collection(
            id = "c",
            title = "C",
            shape = PosterShape.LANDSCAPE,
            folders = List(3) { CollectionFolder("f$it", "F$it", sources = listOf(addon)) },
        )
        val folders = Json.parseToJsonElement(NuvioCollections.encode(listOf(collection))).jsonArray.single().jsonObject.getValue("folders").jsonArray
        assertEquals(List(3) { "LANDSCAPE" }, folders.map { it.jsonObject.getValue("tileShape").jsonPrimitive.content })
    }
}
