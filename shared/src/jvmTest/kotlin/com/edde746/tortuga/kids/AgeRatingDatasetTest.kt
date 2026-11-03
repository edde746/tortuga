package com.edde746.tortuga.kids

import com.edde746.tortuga.trackers.ids.FixtureMappings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * agerating's packed list end to end: the latest release's tag, its gzipped file, checked against its `SHA256SUMS`,
 * kept as downloaded, read into memory and looked up by [AgeRatings].
 */
class AgeRatingDatasetTest {
    private val directory = Files.createTempDirectory("tortuga-agerating")
    private val requests = mutableListOf<String>()

    @AfterTest
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun deleteDirectory() = directory.deleteRecursively()

    @Test
    fun theReleasesPackedListIsCheckedKeptGzippedAndLookedUp() = runTest {
        val asset = gzip(packAgeList(listOf("imdb" to listOf(114709L to 0, 133093L to 16), "tmdb_tv" to listOf(1399L to 16))))
        val ratings = AgeRatings(dataset(asset, sha256(asset)), FixtureMappings())

        assertEquals(TitleRating.Rated(16), ratings.rating("movie", "tt0133093", imdbId = null))
        assertEquals(TitleRating.Rated(0), ratings.rating("movie", "tt0114709", imdbId = null))
        assertEquals(TitleRating.Rated(16), ratings.rating("series", "tmdb:1399", imdbId = null))
        assertEquals(TitleRating.Unrated, ratings.rating("movie", "tmdb:1399", imdbId = null), "TMDB numbers films apart from series")
        assertEquals(TitleRating.Unrated, ratings.rating("movie", "tt0000001", imdbId = null))
        assertEquals(
            listOf("releases/latest", "releases/download/v2026.11.08/SHA256SUMS", "releases/download/v2026.11.08/agerating-ids.bin.gz"),
            requests,
            "one download, from the latest release's tag, for every lookup",
        )

        // One copy on disk, the release's file as it is, beside the dataset's state.
        assertTrue(directory.resolve("agerating-ids.bin.gz").readBytes().contentEquals(asset))
        assertEquals(listOf("agerating-ids.bin.gz", "agerating-ids.bin.gz.state"), directory.listDirectoryEntries().map { it.name }.sorted())
    }

    @Test
    fun aDownloadThatFailsItsChecksumOrIsntAPackedListIsNotKept() = runTest {
        val list = packAgeList(listOf("imdb" to listOf(133093L to 16)))
        val asset = gzip(list)
        val wrong = AgeRatings(dataset(asset, sha256(byteArrayOf(1))), FixtureMappings())
        assertEquals(TitleRating.Failed, wrong.rating("movie", "tt0133093", imdbId = null))
        assertFalse(directory.resolve("agerating-ids.bin.gz").exists())

        val rejected = mapOf(
            "cut short" to asset.copyOf(asset.size / 2),
            "not gzip" to list,
            "another version" to gzip(list.copyOf().also { it[4] = 2 }),
            "empty" to gzip(packAgeList(listOf("imdb" to emptyList()))),
        )
        for ((case, file) in rejected) {
            directory.toFile().listFiles()!!.forEach { it.delete() }
            assertEquals(TitleRating.Failed, AgeRatings(dataset(file, sha256(file)), FixtureMappings()).rating("movie", "tt0133093", imdbId = null), case)
            assertFalse(directory.resolve("agerating-ids.bin.gz").exists(), case)
        }
    }

    private fun TestScope.dataset(asset: ByteArray, listed: String): AgeRatingDataset {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath.removePrefix("/edde746/agerating/")
            requests += path
            when (path) {
                "releases/latest" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "$REPOSITORY/releases/tag/v2026.11.08"))
                "releases/download/v2026.11.08/SHA256SUMS" -> respond("$listed  ${AgeRatingDataset.ASSET_NAME}\n", HttpStatusCode.OK)
                "releases/download/v2026.11.08/${AgeRatingDataset.ASSET_NAME}" -> respond(asset, HttpStatusCode.OK)
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        return AgeRatingDataset(
            HttpClient(engine) { install(HttpTimeout) },
            Path(directory.toString()),
            backgroundScope,
            ioDispatcher = dispatcher,
            repository = REPOSITORY,
        )
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

    private companion object {
        const val REPOSITORY = "https://github.test/edde746/agerating"
    }
}
