package com.edde746.tortuga.downloads

import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.playback.resolve.lcgBytes
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Who a download belongs to, and when it goes: deletes, profile deletion, sign-out, auto-remove watched. */
class DownloadOwnersTest {
    private val bytes = lcgBytes(100_000, seed = 11)

    @Test
    fun aDownloadGoesWithItsLastOwner() = downloadsTest { h ->
        h.serveCdn(bytes)
        val other = h.createProfile("Sam")
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        assertEquals(id, manager.enqueue(other, FILM, FILM.id, cdnChoice("other.mkv")).id)
        h.settle()
        assertEquals(setOf(h.profileId, other), h.download(id)?.owners)
        val path = assertNotNull(manager.localVideo(other, FILM.id, FILM.id)).path

        h.data.profiles.delete(h.profileId)
        h.settle()

        assertEquals(setOf(other), h.download(id)?.owners)
        assertTrue(manager.downloads(h.profileId).first().isEmpty())
        assertNull(manager.localVideo(h.profileId, FILM.id, FILM.id))

        manager.delete(other, id)
        h.settle()

        assertNull(h.download(id))
        assertFalse(SystemFileSystem.exists(Path(path)))
        assertFalse(h.exists("Movies"), "empty folders go too")
        assertNull(manager.savedMeta("movie", FILM.id))
    }

    @Test
    fun aDeleteThatThrowsIsTriedAgainWithTheNextChangeAndTheListIsStillFollowed() = downloadsTest { h ->
        h.serveCdn(bytes)
        val other = h.createProfile("Sam")
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        val path = assertNotNull(manager.localVideo(h.profileId, FILM.id, FILM.id)).path

        // Its last owner goes while the location can't be read: deleting it throws.
        h.platform.locationFailures = 1
        h.data.profiles.delete(h.profileId)
        h.settle()
        assertEquals(0, h.platform.locationFailures, "the delete read the locations")
        assertNotNull(h.download(id))

        val next = manager.enqueue(other, SHOW, "tt0903747:1:1", cdnChoice("1.mkv")).id
        h.settle()

        assertNull(h.download(id), "the next change deleted it")
        assertFalse(SystemFileSystem.exists(Path(path)))
        assertEquals(DownloadStatus.COMPLETED, h.download(next)?.status)
    }

    @Test
    fun signingOutDeletesEveryDownloadAndItsFiles() = downloadsTest { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice())
        manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("e1.mkv"))
        h.settle()
        h.data.downloads.setRule(h.profileId, SHOW.id, "series", 3, SHOW.name, null)
        assertEquals(2, manager.all.value.count { it.status == DownloadStatus.COMPLETED })

        h.data.sync.dropSyncedData()
        h.settle()

        assertTrue(manager.all.value.isEmpty())
        assertFalse(h.exists("Movies"))
        assertFalse(h.exists("TV Shows"))
        assertTrue(h.data.downloads.rules().isEmpty())
        assertNull(manager.savedMeta("series", SHOW.id))
    }

    @Test
    fun deleteMetaDeletesASeasonOfTheProfile() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val s1 = manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1.mkv")).id
        val s2 = manager.enqueue(h.profileId, SHOW, "tt0903747:2:1", cdnChoice("2.mkv")).id
        h.settle()

        manager.deleteMeta(h.profileId, SHOW.id, season = 1)
        h.settle()

        assertNull(h.download(s1))
        assertNotNull(h.download(s2))
        assertEquals(listOf(s1.toString()), h.fakeTransport.stopped)
    }

    @Test
    fun autoRemoveLetsGoOfWatchedDownloadsButNotTheOnePlaying() = downloadsTest { h ->
        h.serveCdn(bytes)
        h.data.deviceSettings.set(DeviceSetting.DownloadAutoRemoveWatched, true)
        val manager = h.install()
        val first = manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1.mkv")).id
        val second = manager.enqueue(h.profileId, SHOW, "tt0903747:1:2", cdnChoice("2.mkv")).id
        h.settle()

        manager.playbackStarted(second)
        h.data.library.markVideoWatched(h.profileId, SHOW, "tt0903747:1:1", true)
        h.data.library.markVideoWatched(h.profileId, SHOW, "tt0903747:1:2", true)
        h.settle()

        assertNull(h.download(first))
        assertEquals(DownloadStatus.COMPLETED, h.download(second)?.status)

        manager.playbackEnded(second)
        h.settle()
        assertNull(h.download(second))
    }

    @Test
    fun autoRemoveIsOffByDefault() = downloadsTest { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1.mkv")).id
        h.settle()

        h.data.library.markVideoWatched(h.profileId, SHOW, "tt0903747:1:1", true)
        h.settle()

        assertEquals(DownloadStatus.COMPLETED, h.download(id)?.status)
    }

    @Test
    fun deleteAllDeletesEveryProfilesDownloads() = downloadsTest { h ->
        h.serveCdn(bytes)
        val other = h.createProfile("Sam")
        val manager = h.install()
        manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice())
        manager.enqueue(other, SHOW, "tt0903747:1:1", cdnChoice("1.mkv"))
        h.settle()

        manager.deleteAll()
        h.settle()

        assertTrue(manager.all.value.isEmpty())
        assertFalse(h.exists("Movies"))
        assertFalse(h.exists("TV Shows"))
        assertEquals(0L, manager.storage.value.usedBytes)
    }

    /**
     * A download deleted while its location isn't there (a card taken out) leaves the profile's downloads at once, and
     * its files go once the location is back: nothing is left on the volume that nothing knows.
     */
    @Test
    fun aDownloadDeletedWhileItsLocationIsMissingLosesItsFilesOnceItIsBack() = downloadsTest(fake = true) { h ->
        h.serveCdn(bytes)
        val manager = h.install()
        val id = manager.enqueue(h.profileId, FILM, FILM.id, cdnChoice()).id
        h.settle()
        val request = h.fakeTransport.started.single()
        h.complete(request)
        val video = Path(request.partPath.removeSuffix(DownloadNames.PART))
        assertTrue(SystemFileSystem.exists(video))
        val locations = h.platform.locationList

        h.platform.locationList = emptyList()
        manager.delete(h.profileId, id)
        h.settle()
        assertTrue(manager.downloads(h.profileId).first().isEmpty())

        h.platform.locationList = locations
        manager.enqueue(h.profileId, SHOW, "tt0903747:1:1", cdnChoice("1.mkv"))
        h.settle()

        assertFalse(SystemFileSystem.exists(video), "deleted once its location is back")
        assertNull(manager.all.value.firstOrNull { it.id == id })
    }
}
