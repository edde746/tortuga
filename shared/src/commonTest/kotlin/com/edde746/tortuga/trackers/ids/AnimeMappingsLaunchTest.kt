@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.trackers.ids

import com.edde746.tortuga.crypto.cryptoProvider
import dev.whyoleg.cryptography.algorithms.SHA256
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

/**
 * The packed list decodes once per download: a later launch loads both indexes from the snapshot the first one kept
 * beside the downloaded file (the list takes several times as long to decode on a TV stick), and reads the file no
 * more.
 */
class AnimeMappingsLaunchTest {
    private val directory = Path(SystemTemporaryDirectory, "tortuga-mappings-" + Random.nextLong().toULong())
    private val requests = mutableListOf<String>()

    @AfterTest
    fun deleteDirectory() {
        if (!SystemFileSystem.exists(directory)) return
        SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
        SystemFileSystem.delete(directory)
    }

    @Test
    fun aLaterLaunchDoesntDecodeTheListAgain() = runTest {
        val first = launch().source
        val bebop = first.fribb()?.byMal(1)
        val hxh = first.animeLists()?.externalEpisode(8550, AnimeListProvider.TVDB, 60)
        advanceUntilIdle()
        assertEquals(listOf(LATEST, "$DOWNLOAD/SHA256SUMS", "$DOWNLOAD/${AnimeMappings.ASSET_NAME}"), requests)
        assertEquals(76885, bebop?.single()?.tvdb)

        // The downloaded file made unreadable: a launch that decoded it again would have no index.
        SystemFileSystem.sink(Path(directory, AnimeMappings.ASSET_NAME)).buffered().use { it.writeString("unreadable") }
        val later = launch().source
        assertEquals(bebop, later.fribb()?.byMal(1))
        assertEquals(hxh, later.animeLists()?.externalEpisode(8550, AnimeListProvider.TVDB, 60))
        advanceUntilIdle()
        assertEquals(3, requests.size, "nothing downloaded again")
    }

    /** The app at launch, with anime-mappings' latest release, `v1`, holding the fixture's file. */
    private fun TestScope.launch(): AnimeMappings {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val file = animeMappingsExcerpt()
        val engine = MockEngine.create {
            this.dispatcher = dispatcher
            addHandler { request ->
                val url = request.url.toString()
                requests += url
                when (url) {
                    LATEST -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "${AnimeMappings.REPOSITORY}/releases/tag/v1"))
                    "$DOWNLOAD/SHA256SUMS" -> {
                        val sum = cryptoProvider.get(SHA256).hasher().hashBlocking(file).toHexString()
                        respond("$sum  ${AnimeMappings.ASSET_NAME}\n", HttpStatusCode.OK)
                    }
                    "$DOWNLOAD/${AnimeMappings.ASSET_NAME}" -> respond(file, HttpStatusCode.OK)
                    else -> respondError(HttpStatusCode.NotFound)
                }
            }
        }
        return AnimeMappings(
            HttpClient(engine) { install(HttpTimeout) }, directory, this, Clock.System, SystemFileSystem, dispatcher, dispatcher,
        )
    }

    private companion object {
        const val LATEST = "${AnimeMappings.REPOSITORY}/releases/latest"
        const val DOWNLOAD = "${AnimeMappings.REPOSITORY}/releases/download/v1"
    }
}
