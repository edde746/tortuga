package com.edde746.tortuga.trackers.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Stremio ids tracker catalog items open by. */
class CatalogItemsTest {
    @Test
    fun aServicesMalformedImdbIdIsNoCardIdAndTheTmdbIdIsUsedInstead() {
        assertEquals("tt0944947", CatalogItems.stremioId("tt0944947", 1399))
        assertEquals("tmdb:1399", CatalogItems.stremioId("ttabc", 1399))
        assertEquals("tmdb:1399", CatalogItems.stremioId("tt0944947:1:2", 1399))
        assertNull(CatalogItems.stremioId("ttabc", null))
    }
}
