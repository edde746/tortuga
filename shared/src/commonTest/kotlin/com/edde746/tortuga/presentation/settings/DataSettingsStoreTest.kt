@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.movie
import com.edde746.tortuga.data.sync.AddonListBody
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.data.transfer.DataCategory
import com.edde746.tortuga.data.transfer.TransferFile
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class DataSettingsStoreTest {
    private val originalSink = Log.sink
    private val originalLevel = Log.minLevel

    @AfterTest
    fun restoreLog() {
        Log.sink = originalSink
        Log.minLevel = originalLevel
    }

    /**
     * A store over an empty database at [nowMillis] in a time zone [utcOffset] seconds east of UTC; [clockAheadMillis]
     * sets the database's clock (its records' timestamps) ahead, and [seedAddons] runs for each profile an import
     * creates.
     */
    private class Fixture(
        scope: TestScope,
        clockAheadMillis: Long = 0,
        nowMillis: Long = 1_790_000_000_000L,
        utcOffset: Int = 0,
        seedAddons: suspend (String) -> Unit = {},
    ) {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val data = LocalData(inMemoryDatabase(), { nowMillis + clockAheadMillis }, dispatcher, HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        val seeded = mutableListOf<String>()
        val store = DataSettingsStore(
            data.profiles, data.transfer, "1.0.0", { nowMillis }, { utcOffset }, dispatcher,
            { seedAddons(it); seeded += it }, scope.backgroundScope,
        )
        val state get() = store.state.value

        /** [profileId]'s row here holds a clock nothing can read, as in a damaged database. */
        suspend fun damageClock(profileId: String) = data.db.write {
            q.profileQueries.upsert(q.profileQueries.select(profileId).executeAsOne().copy(hlc = "not a clock"))
        }
    }

    @Test
    fun aFileOfAnotherFormatANewerVersionOrADamagedOneIsRefusedAndNothingIsWritten() = runTest {
        val f = Fixture(this)
        val ana = f.data.profiles.create("Ana", null)
        f.data.library.addToLibrary(ana.id, movie())
        runCurrent()
        val full = exportText(f)
        val refused = mapOf(
            "{\"format\":\"stremio-backup\",\"version\":1}" to T.settings.data.notTortuga,
            "<html>not json</html>" to T.settings.data.notTortuga,
            full.replace("\"version\":1", "\"version\":2") to T.settings.data.newer,
            full.replace("\"format\":1", "\"format\":2") to T.settings.data.newer,
            full.take(full.length / 2) to T.settings.data.damaged,
            full.replace("\"kind\":\"library\"", "\"kind\":\"setting\"") to T.settings.data.damaged,
            full.replace("\"categories\":[\"addons\",\"library\",\"progress\",\"settings\",\"streams\"]", "\"categories\":[]") to T.settings.data.damaged,
        )
        val before = f.data.sync.dirtyRecords(null, 1_000).map { it.payload }

        for ((text, message) in refused) {
            f.store.open(text)
            runCurrent()
            assertEquals(DataNotice(message, error = true), f.state.notice, text.take(80))
            assertNull(f.state.review)
        }
        assertEquals(before, f.data.sync.dirtyRecords(null, 1_000).map { it.payload })
        assertEquals(listOf("Ana"), f.data.profiles.profiles().map { it.name })
        f.data.close()
    }

    @Test
    fun aFileFromAClockFarAheadIsRefusedAtImportAndNothingIsWritten() = runTest {
        val source = Fixture(this, clockAheadMillis = 2 * HybridClock.DEFAULT_MAX_DRIFT_MILLIS)
        source.data.profiles.create("Ana", null)
        runCurrent()
        val text = exportText(source)

        val f = Fixture(this)
        f.store.open(text)
        runCurrent()
        f.store.confirmImport()
        runCurrent()

        assertEquals(DataNotice(T.settings.data.clockAhead, error = true), f.state.notice)
        assertNull(f.state.review)
        assertEquals(emptyList(), f.data.profiles.profiles())
        assertEquals(emptyList(), f.data.sync.dirtyRecords(null, 1_000))
        assertEquals(emptyList(), f.seeded)
        source.data.close()
        f.data.close()
    }

    @Test
    fun anImportThatFailsOnThisDevicesDataSaysSoAndClosesTheReview() = runTest {
        val f = Fixture(this)
        val ana = f.data.profiles.create("Ana", null)
        runCurrent()
        val text = exportText(f)
        f.damageClock(ana.id)
        runCurrent()

        f.store.open(text)
        runCurrent()
        f.store.confirmImport()
        runCurrent()

        assertEquals(DataNotice("The import failed.", error = true), f.state.notice)
        assertNull(f.state.review)
        assertFalse(f.state.working)
        assertTrue(f.state.canImport, "another file can be opened")
        f.data.close()
    }

    @Test
    fun anImportWhoseNewProfileCantGetItsAddonsSaysItFailed() = runTest {
        val source = Fixture(this)
        source.data.profiles.create("Ana", null)
        runCurrent()
        val text = exportText(source)

        val f = Fixture(this, seedAddons = { throw IllegalStateException("the addon table is gone") })
        f.store.open(text)
        runCurrent()
        f.store.confirmImport()
        runCurrent()

        assertEquals(DataNotice("The import failed.", error = true), f.state.notice)
        assertNull(f.state.review)
        assertFalse(f.state.working)
        source.data.close()
        f.data.close()
    }

    @Test
    fun anExportThatFailsOnThisDevicesDataSaysSo() = runTest {
        val f = Fixture(this)
        val ana = f.data.profiles.create("Ana", null)
        runCurrent()
        f.damageClock(ana.id)
        runCurrent()

        f.store.export()
        runCurrent()

        assertEquals(DataNotice("The export failed.", error = true), f.state.notice)
        assertNull(f.state.saveRequest)
        assertTrue(f.state.canExport, "the export can be tried again")
        f.data.close()
    }

    @Test
    fun anOpenedFileShowsWhatItHoldsAndImportsIntoTheChosenProfile() = runTest {
        val source = Fixture(this)
        val ana = source.data.profiles.create("Ana", "avatar-1")
        source.data.library.addToLibrary(ana.id, movie())
        source.data.addons.replaceAll(ana.id, listOf(ADDON))
        runCurrent()
        source.store.setExportCategory(DataCategory.ADDONS, false)
        val text = exportText(source)

        val f = Fixture(this)
        val kim = f.data.profiles.create("Kim", null)
        runCurrent()
        f.store.open(text)
        runCurrent()
        val review = assertNotNull(f.state.review)
        assertEquals(listOf(DataCategory.LIBRARY, DataCategory.PROGRESS, DataCategory.SETTINGS, DataCategory.STREAMS), review.categories.map { it.category })
        assertEquals(T.settings.data.counts.titles(1), review.categories.first().toggle.subtitle)
        val row = review.profiles.single()
        assertEquals("Ana", row.toggle.title)
        assertEquals(DataImportProfileRow.NEW_PROFILE, assertNotNull(row.target).options.single { it.selected }.id)

        f.store.setImportTarget(row.fileProfileId, kim.id)
        f.store.confirmImport()
        runCurrent()

        assertNull(f.state.review)
        assertEquals(DataNotice("Import finished: 1 title.", error = false), f.state.notice)
        assertEquals(listOf("Kim"), f.data.profiles.profiles().map { it.name })
        assertEquals("The Shawshank Redemption", f.data.library.item(kim.id, "tt0111161")?.name)
        assertEquals(emptyList(), f.seeded)

        f.store.open(text)
        runCurrent()
        f.store.setImportTarget(row.fileProfileId, kim.id)
        f.store.confirmImport()
        runCurrent()
        assertEquals(DataNotice("Nothing new to import: everything in the file was already here.", error = false), f.state.notice)
        source.data.close()
        f.data.close()
    }

    @Test
    fun aProfileTheImportCreatesGetsTheDefaultAddons() = runTest {
        val source = Fixture(this)
        source.data.profiles.create("Ana", null)
        runCurrent()
        val text = exportText(source)

        val f = Fixture(this)
        f.store.open(text)
        runCurrent()
        f.store.confirmImport()
        runCurrent()

        val created = f.data.profiles.profiles().single()
        assertEquals("Ana", created.name)
        assertEquals(listOf(created.id), f.seeded)
        source.data.close()
        f.data.close()
    }

    @Test
    fun anImportWithNoCategoryTickedCantBeConfirmed() = runTest {
        val source = Fixture(this)
        source.data.profiles.create("Ana", null)
        runCurrent()
        val text = exportText(source)

        val f = Fixture(this)
        f.store.open(text)
        runCurrent()
        val review = assertNotNull(f.state.review)
        assertTrue(review.canConfirm)
        review.categories.forEach { f.store.setImportCategory(it.category, false) }
        assertFalse(assertNotNull(f.state.review).canConfirm)
        f.store.confirmImport()
        runCurrent()
        assertNotNull(f.state.review, "the review stays open")
        assertEquals(emptyList(), f.data.profiles.profiles(), "no profile is created empty")
        source.data.close()
        f.data.close()
    }

    @Test
    fun theExportIsNamedAndDatedByTheDevicesDateNotUtcs() = runTest {
        // 23:30 UTC on 27 October 2026 is 00:30 on the 28th in Central Europe (UTC+1).
        assertExportDate("2026-10-27T23:30:00Z", 3600, "tortuga-2026-10-28.json", "Exported on Oct 28, 2026 by Tortuga \u20681.0.0\u2069")
        // 02:00 UTC on 28 October 2026 is 22:00 on the 27th in New York (UTC−4).
        assertExportDate("2026-10-28T02:00:00Z", -4 * 3600, "tortuga-2026-10-27.json", "Exported on Oct 27, 2026 by Tortuga \u20681.0.0\u2069")
    }

    /** An export at [now] in a time zone [utcOffset] seconds east of UTC is proposed as [fileName] and, opened, says [source]. */
    private suspend fun TestScope.assertExportDate(now: String, utcOffset: Int, fileName: String, source: String) {
        val f = Fixture(this, nowMillis = Instant.parse(now).toEpochMilliseconds(), utcOffset = utcOffset)
        f.data.profiles.create("Ana", null)
        runCurrent()
        f.store.open(exportText(f, fileName))
        runCurrent()
        assertEquals(source, assertNotNull(f.state.review).source)
        f.data.close()
    }

    @Test
    fun anAddonLinkWithAKeyNeverReachesTheLog() = runTest {
        val lines = mutableListOf<String>()
        Log.minLevel = Log.Level.DEBUG
        Log.sink = Log.Sink { level, tag, message, error -> lines += "$level $tag $message ${error.orEmpty()}" }
        val f = Fixture(this)
        val ana = f.data.profiles.create("Ana", null)
        f.data.addons.replaceAll(ana.id, listOf(ADDON))
        runCurrent()

        val text = exportText(f)
        // The file holds the link with its key, packed with the addon list.
        val exported = TransferFile.decode(text).records.firstNotNullOf { it.body as? AddonListBody }
        assertEquals(listOf(ADDON.transportUrl), exported.addons.map { it.transportUrl })
        f.store.open(text)
        runCurrent()
        f.store.confirmImport()
        runCurrent()
        // A file cut inside the packed list doesn't read.
        f.store.open(text.take(text.indexOf("\"packed\"") + 40))
        runCurrent()

        assertTrue(lines.any { it.contains("DataTransfer") }, lines.joinToString("\n"))
        assertFalse(lines.any { KEY in it || "/key=" in it }, lines.joinToString("\n"))
        f.data.close()
    }

    /** The store's export of everything ticked, saved as the platform would, proposed as [fileName]. */
    private fun TestScope.exportText(f: Fixture, fileName: String = "tortuga-2026-09-21.json"): String {
        assertTrue(f.state.canExport, f.state.toString())
        f.store.export()
        runCurrent()
        val file = assertNotNull(f.state.saveRequest, f.state.toString())
        assertEquals(fileName, file.fileName)
        f.store.exportSaved()
        assertEquals(DataNotice(T.settings.data.saved(isolate(file.fileName)), error = false), f.state.notice)
        return file.text
    }

    private companion object {
        const val KEY = "SECRETKEY0123456789ABCDEF"
        val ADDON = AddonDescriptor("https://addon.example/key=$KEY/manifest.json", "{\"id\":\"example.streams\"}")
    }
}
