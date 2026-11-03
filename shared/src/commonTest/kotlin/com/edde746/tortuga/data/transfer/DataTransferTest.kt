package com.edde746.tortuga.data.transfer

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.movie
import com.edde746.tortuga.data.series
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.sync.LibraryItemBody
import com.edde746.tortuga.data.sync.ProfileBody
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.data.sync.RemoteRecord
import com.edde746.tortuga.data.sync.SettingBody
import com.edde746.tortuga.data.sync.SyncRecord
import com.edde746.tortuga.data.testData
import com.edde746.tortuga.kids.AgeStep
import com.edde746.tortuga.kids.KidsTitle
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.DeviceLinkOutcome
import com.edde746.tortuga.sync.account.SignInResult
import com.edde746.tortuga.sync.engine.SyncStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Export and import: what a file carries, and how an import merges it. */
class DataTransferTest {
    private val clockA = TestClock()
    private val clockB = TestClock()
    private val a = testData(clockA)
    private val b = testData(clockB)

    @AfterTest
    fun close() {
        a.close()
        b.close()
    }

    @Test
    fun exportingEverythingAndImportingItIntoAnEmptyDatabaseGivesTheSameData() = runTest {
        seed(a)
        val file = export(a)
        val outcome = b.transfer.import(file, DataCategory.entries.toSet(), newProfiles(file))

        assertEquals(2, outcome.createdProfileIds.size)
        assertEquals(normalized(a, file), normalized(b, export(b)))
        val ana = b.profiles.profiles().single { it.name == "Ana" }
        assertEquals("avatar-1", ana.avatar)
        assertEquals(listOf(CINEMETA, STREAMS), b.addons.addons(ana.id).map { AddonDescriptor(it.transportUrl, it.manifestJson) })
        assertEquals(600_000, b.library.item(ana.id, MOVIE)?.state?.timeOffset)
        assertEquals(90, b.settings.settings(ana.id).watchedThresholdPercent)
        assertEquals(STREAMS.transportUrl, b.streamHistory.lastStream(ana.id, MOVIE, MOVIE)?.transportUrl)
    }

    @Test
    fun anExportHoldsOnlyTheTickedCategoriesAndProfiles() = runTest {
        val (ana, _) = seed(a)
        val file = export(a, setOf(DataCategory.LIBRARY, DataCategory.SETTINGS), setOf(ana))

        assertEquals(listOf(DataCategory.LIBRARY, DataCategory.SETTINGS), file.categories)
        assertEquals(listOf("Ana"), file.profiles.map { it.second.name })
        assertTrue(file.records.all { it.key.profileId == ana })
        assertTrue(file.records.none { it.key is RecordKey.AddonList || it.key is RecordKey.StreamHistory })
        val items = file.records.mapNotNull { it.body as? LibraryItemBody }
        // The library without progress: the saved movie, without its position; the series only in Continue Watching stays out.
        assertEquals(listOf("The Shawshank Redemption"), items.map { it.name })
        assertFalse(items.single().hasProgress)
        assertFalse(STREAMS.transportUrl in file.encode())
    }

    @Test
    fun anImportTakesOnlyTheTickedCategoriesAndProfilesOfAFullFile() = runTest {
        val (ana, _) = seed(a)
        val file = export(a)
        b.transfer.import(file, setOf(DataCategory.PROGRESS), mapOf(ana to ImportTarget.New))

        val imported = b.profiles.profiles().single()
        assertEquals("Ana", imported.name)
        assertEquals(emptyList(), b.addons.addons(imported.id))
        assertEquals(ProfileSetting.WatchedThresholdPercent.default, b.settings.settings(imported.id).watchedThresholdPercent)
        assertNull(b.streamHistory.lastStream(imported.id, MOVIE, MOVIE))
        // Progress without the library: the movie's position, outside the library.
        val movie = assertNotNull(b.library.item(imported.id, MOVIE))
        assertEquals(600_000, movie.state.timeOffset)
        assertTrue(movie.removed)
    }

    @Test
    fun theNewerSideWinsPerRecordInBothDirections() = runTest {
        val (ana, _) = seed(a)
        copy(a, b)
        clockA.now += 1_000
        a.profiles.rename(ana, "Ana A")
        a.settings.set(ana, ProfileSetting.SeekShortSeconds, 5)
        a.settings.set(ana, ProfileSetting.SeekLongSeconds, 45)
        a.library.recordProgress(ana, MOVIE, MOVIE, 900_000, 8_520_000)
        a.library.addToLibrary(ana, movie(OTHER_MOVIE, "Heat"))
        a.streamHistory.record(ana, MOVIE, MOVIE, "{\"url\":\"a\"}", CINEMETA.transportUrl)
        clockB.now = clockA.now + 1_000
        b.settings.set(ana, ProfileSetting.SeekLongSeconds, 60)
        b.library.addToLibrary(ana, movie(OTHER_MOVIE, "Heat (1995)"))
        b.streamHistory.record(ana, SERIES, SERIES_VIDEO, "{\"url\":\"b\"}", CINEMETA.transportUrl)
        clockB.now += 1_000
        b.streamHistory.record(ana, MOVIE, MOVIE, "{\"url\":\"https://b.example/\"}", CINEMETA.transportUrl)

        export(a).let { b.transfer.import(it, DataCategory.entries.toSet(), newProfiles(it)) }

        // The file's newer writes.
        assertEquals("Ana A", b.profiles.profile(ana)?.name)
        assertEquals(5, b.settings.settings(ana).seekShortSeconds)
        assertEquals(900_000, b.library.item(ana, MOVIE)?.state?.timeOffset)
        // This device's newer writes.
        assertEquals(60, b.settings.settings(ana).seekLongSeconds)
        assertEquals("Heat (1995)", b.library.item(ana, OTHER_MOVIE)?.name)
        assertEquals(StreamSource.Url("https://b.example/"), b.streamHistory.lastStream(ana, MOVIE, MOVIE)?.stream?.source)
        // Only here: kept.
        assertNotNull(b.streamHistory.lastStream(ana, SERIES, SERIES_VIDEO))
    }

    @Test
    fun anImportDeletesNothingAndUnwatchesNothing() = runTest {
        val (ana, _) = seed(a)
        copy(a, b)
        clockA.now += 1_000
        a.library.setWatchedVideos(ana, SERIES, watched(EPISODE_1))
        a.library.markWatched(ana, movie(), watched = true)
        a.library.removeFromLibrary(ana, MOVIE)
        clockB.now = clockA.now + 1_000
        b.library.setWatchedVideos(ana, SERIES, watched(EPISODE_2))
        b.library.markWatched(ana, movie(), watched = false)
        b.addons.replaceAll(ana, listOf(OPENSUBTITLES, CINEMETA))
        b.library.addToLibrary(ana, movie(OTHER_MOVIE, "Heat"))

        export(a).let { b.transfer.import(it, DataCategory.entries.toSet(), newProfiles(it)) }

        val marks = WatchedBitField.parse(assertNotNull(b.library.item(ana, SERIES)?.state?.watchedVideos), EPISODES)
        assertTrue(marks.isWatched(EPISODE_1))
        assertTrue(marks.isWatched(EPISODE_2))
        val movie = assertNotNull(b.library.item(ana, MOVIE))
        assertTrue(movie.isWatched)
        assertFalse(movie.removed)
        assertNotNull(b.library.item(ana, OTHER_MOVIE))
        // The addons here first, then the file's new ones in the file's order.
        assertEquals(listOf(OPENSUBTITLES, CINEMETA, STREAMS).map { it.transportUrl }, b.addons.addons(ana).map { it.transportUrl })
    }

    @Test
    fun aFileCarriesEachAddonsSwitchButNotTheAgreementToSendItWatchActivity() = runTest {
        val ana = a.profiles.create("Ana", null).id
        a.addons.replaceAll(ana, listOf(CINEMETA, STREAMS.copy(enabled = false, watchActivity = true), OPENSUBTITLES.copy(watchActivity = true)))
        val file = export(a)
        b.transfer.import(file, setOf(DataCategory.ADDONS), newProfiles(file))

        val imported = b.profiles.profiles().single()
        assertEquals(
            listOf(CINEMETA, STREAMS.copy(enabled = false), OPENSUBTITLES),
            b.addons.addons(imported.id).map { AddonDescriptor(it.transportUrl, it.manifestJson, it.enabled, it.watchActivity) },
            "a file can come from anywhere: the user agrees again here",
        )
    }

    @Test
    fun aTombstoneInAFileDeletesNothing() = runTest {
        val (ana, _) = seed(a)
        val file = export(a)
        val tombstones = TransferFile(
            file.format, file.version, file.app, file.exportedAt, file.categories,
            file.records.map { if (it.key is RecordKey.Profile) it else SyncRecord(it.key, it.hlc.copy(wallMillis = it.hlc.wallMillis + 60_000), null) },
        )
        copy(a, b)

        val file2 = TransferFile.decode(tombstones.encode())
        assertEquals(ImportOutcome(emptyMap(), 0, emptyList()), b.transfer.import(file2, DataCategory.entries.toSet(), newProfiles(file2)))
        assertNotNull(b.library.item(ana, MOVIE))
        assertEquals(2, b.addons.addons(ana).size)
        assertEquals(90, b.settings.settings(ana).watchedThresholdPercent)
    }

    @Test
    fun anImportCountsWhatItChangedAsTheUserSeesIt() = runTest {
        val ana = a.profiles.create("Ana", null).id
        a.addons.replaceAll(ana, listOf(CINEMETA))
        copy(a, b)
        a.addons.replaceAll(ana, listOf(CINEMETA, STREAMS, OPENSUBTITLES))
        a.library.addToLibrary(ana, movie())
        a.library.upsertFromMeta(ana, series())
        a.library.recordProgress(ana, SERIES, SERIES_VIDEO, 60_000, 2_700_000)
        a.settings.set(ana, ProfileSetting.WatchedThresholdPercent, 90)
        val file = export(a)

        // Two of the three addons are new here; one title joins the library, one (outside it) has progress.
        val expected = mapOf(DataCategory.ADDONS to 2, DataCategory.LIBRARY to 1, DataCategory.PROGRESS to 1, DataCategory.SETTINGS to 1)
        assertEquals(ImportOutcome(expected, 0, emptyList()), b.transfer.import(file, DataCategory.entries.toSet(), newProfiles(file)))
        assertEquals(ImportOutcome(emptyMap(), 0, emptyList()), b.transfer.import(file, DataCategory.entries.toSet(), newProfiles(file)))
    }

    /** A kids profile's rating settings never leave in a file, and a file that holds them changes none here. */
    @Test
    fun aKidsProfilesRatingSettingsNeverTravelInAFile() = runTest {
        val mia = a.profiles.create("Mia", null, kids = true).id
        a.settings.set(mia, ProfileSetting.KidsCap, AgeStep.LITTLE)
        a.settings.set(mia, ProfileSetting.KidsAllowUnrated, false)
        a.settings.set(mia, ProfileSetting.KidsAllowed, listOf(KidsTitle("tt2294629", "movie", "Frozen")))
        a.settings.set(mia, ProfileSetting.KidsBlocked, listOf(KidsTitle("tt0073195", "movie", "Jaws")))
        a.settings.set(mia, ProfileSetting.WatchedThresholdPercent, 90)
        val file = export(a)
        assertEquals(listOf(ProfileSetting.WatchedThresholdPercent.key), file.records.mapNotNull { (it.key as? RecordKey.Setting)?.key })

        // A file written by hand that loosens them: the import leaves them as they are and doesn't count them.
        copy(a, b)
        val later = file.records.first { it.key is RecordKey.Setting }.hlc.let { it.copy(wallMillis = it.wallMillis + 60_000) }
        val loosened = TransferFile(
            file.format, file.version, file.app, file.exportedAt, file.categories,
            file.records + listOf(
                SyncRecord(RecordKey.Setting(mia, ProfileSetting.KidsCap.key), later, SettingBody("\"TEEN\"")),
                SyncRecord(RecordKey.Setting(mia, ProfileSetting.KidsAllowUnrated.key), later, SettingBody("true")),
                SyncRecord(RecordKey.Setting(mia, ProfileSetting.KidsBlocked.key), later, SettingBody("[]")),
            ),
        ).let { TransferFile.decode(it.encode()) }
        assertEquals(1, loosened.count(DataCategory.SETTINGS, setOf(mia)))
        assertEquals(ImportOutcome(emptyMap(), 0, emptyList()), b.transfer.import(loosened, DataCategory.entries.toSet(), newProfiles(loosened)))
        val settings = b.settings.settings(mia)
        assertEquals(AgeStep.LITTLE, settings.kidsCap)
        assertFalse(settings.kidsAllowUnrated)
        assertEquals(listOf("Jaws"), settings.kidsBlocked.map { it.name })
    }

    /** What doesn't read in a file (a setting's value, a watched field) is left out of the import; the rest imports. */
    @Test
    fun aRecordWhoseContentDoesntReadIsLeftOutAndTheRestImports() = runTest {
        val (ana, _) = seed(a)
        // Stored here before such values were refused where they arrive.
        a.library.setWatchedVideos(ana, SERIES, "$EPISODE_1:1:not-base64")
        a.settings.set(ana, ProfileSetting.SeekShortSeconds, 5)
        a.db.write {
            q.settingQueries.upsert(q.settingQueries.select(ana, ProfileSetting.SeekShortSeconds.key).executeAsOne().copy(value_json = "\"five\""))
        }
        val file = export(a)

        b.transfer.import(file, DataCategory.entries.toSet(), newProfiles(file))
        val here = b.profiles.profiles().single { it.name == "Ana" }.id
        assertEquals(90, b.settings.settings(here).watchedThresholdPercent)
        assertNull(b.db.read { settingQueries.select(here, ProfileSetting.SeekShortSeconds.key).executeAsOneOrNull() })
        assertNotNull(b.library.item(here, MOVIE))
        assertNull(b.library.item(here, SERIES))
    }

    @Test
    fun aFileProfileGoesToTheSameProfileANewOneOrTheChosenOne() = runTest {
        val (ana, ben) = seed(a)
        copy(a, b)
        val file = export(a, setOf(DataCategory.LIBRARY))
        val c = testData(TestClock())
        try {
            val kim = c.profiles.create("Kim", null)
            val outcome = c.transfer.import(file, setOf(DataCategory.LIBRARY), mapOf(ana to ImportTarget.Existing(kim.id), ben to ImportTarget.New))

            assertEquals(setOf("Kim", "Ben"), c.profiles.profiles().map { it.name }.toSet())
            assertEquals(listOf("The Shawshank Redemption"), c.library.observeLibrary(kim.id).first().map { it.name })
            assertEquals(listOf("The Godfather"), c.library.observeLibrary(outcome.createdProfileIds.single()).first().map { it.name })
        } finally {
            c.close()
        }

        // The same profile here: its data merges into it whatever the target says, and no profile is created.
        val outcome = b.transfer.import(file, setOf(DataCategory.LIBRARY), mapOf(ana to ImportTarget.New))
        assertEquals(emptyList(), outcome.createdProfileIds)
        assertEquals(2, b.profiles.profiles().size)
    }

    @Test
    fun syncPushesAnImportLikeAnyLocalChange() = runTest {
        seed(a)
        val server = FakeSyncServer()
        TestDevice("Phone", server, this, backgroundScope).use { phone ->
            TestDevice("TV", server, this, backgroundScope).use { tv ->
                phone.account.restore()
                assertEquals(SignInResult.NEW_ACCOUNT, phone.account.signIn(server.serverUrl) { TestDevice.credential() })
                tv.account.restore()
                val pending = tv.account.startDeviceLink(server.serverUrl)
                val linked = async { pending.awaitResult() }
                phone.account.claimDeviceLink(pending.code).approve()
                assertEquals(DeviceLinkOutcome.LINKED, linked.await())
                val file = export(a)

                phone.data.transfer.import(file, DataCategory.entries.toSet(), newProfiles(file))
                assertEquals(SyncStatus.Kind.Idle, phone.engine.syncNow().kind)
                assertEquals(SyncStatus.Kind.Idle, tv.engine.syncNow().kind)

                assertNull(phone.data.sync.observeNewestDirty().first())
                val ana = tv.data.profiles.profiles().single { it.name == "Ana" }
                assertEquals(listOf(CINEMETA, STREAMS).map { it.transportUrl }, tv.data.addons.addons(ana.id).map { it.transportUrl })
                assertEquals(600_000, tv.data.library.item(ana.id, MOVIE)?.state?.timeOffset)
                assertEquals(90, tv.data.settings.settings(ana.id).watchedThresholdPercent)
            }
        }
    }

    @Test
    fun aMergedImportOfAFileFromAClockAheadOutlivesTheServersCopy() = runTest {
        val server = FakeSyncServer()
        TestDevice("Phone", server, this, backgroundScope).use { phone ->
            TestDevice("Laptop", server, this, backgroundScope, clockSkewMillis = 3_600_000).use { laptop ->
                phone.account.restore()
                assertEquals(SignInResult.NEW_ACCOUNT, phone.account.signIn(server.serverUrl) { TestDevice.credential() })
                laptop.account.restore()
                val pending = laptop.account.startDeviceLink(server.serverUrl)
                val linked = async { pending.awaitResult() }
                phone.account.claimDeviceLink(pending.code).approve()
                assertEquals(DeviceLinkOutcome.LINKED, linked.await())
                val ana = phone.data.profiles.create("Ana", null).id
                phone.data.addons.replaceAll(ana, listOf(CINEMETA, OPENSUBTITLES))
                assertEquals(SyncStatus.Kind.Idle, phone.engine.syncNow().kind)
                assertEquals(SyncStatus.Kind.Idle, laptop.engine.syncNow().kind)

                // The laptop, its clock an hour ahead, swaps an addon, exports and syncs: the server's copy is its list.
                laptop.data.addons.replaceAll(ana, listOf(CINEMETA, STREAMS))
                val file = export(laptop.data)
                assertEquals(SyncStatus.Kind.Idle, laptop.engine.syncNow().kind)

                // The phone imports the file before pulling that list: the union, then a sync round.
                phone.data.transfer.import(file, DataCategory.entries.toSet(), newProfiles(file))
                assertEquals(SyncStatus.Kind.Idle, phone.engine.syncNow().kind)
                assertEquals(SyncStatus.Kind.Idle, laptop.engine.syncNow().kind)

                val union = listOf(CINEMETA, OPENSUBTITLES, STREAMS).map { it.transportUrl }
                assertEquals(union, phone.data.addons.addons(ana).map { it.transportUrl })
                assertEquals(union, laptop.data.addons.addons(ana).map { it.transportUrl })
            }
        }
    }

    /** Two profiles: Ana with addons, a saved movie with progress, a series in Continue Watching, a setting and a stream; Ben with a movie. */
    private suspend fun seed(data: LocalData): Pair<String, String> {
        val ana = data.profiles.create("Ana", "avatar-1").id
        val ben = data.profiles.create("Ben", null).id
        data.addons.replaceAll(ana, listOf(CINEMETA, STREAMS))
        data.addons.replaceAll(ben, listOf(CINEMETA))
        data.library.addToLibrary(ana, movie())
        data.library.recordProgress(ana, MOVIE, MOVIE, 600_000, 8_520_000)
        data.library.upsertFromMeta(ana, series())
        data.library.recordProgress(ana, SERIES, SERIES_VIDEO, 60_000, 2_700_000)
        data.library.setWatchedVideos(ana, SERIES, watched(EPISODE_1))
        data.settings.set(ana, ProfileSetting.WatchedThresholdPercent, 90)
        data.streamHistory.record(ana, MOVIE, MOVIE, "{\"url\":\"https://media.example/$KEY/movie.mkv\"}", STREAMS.transportUrl)
        data.library.addToLibrary(ben, movie("tt0068646", "The Godfather"))
        return ana to ben
    }

    /** All of [data] (or [categories] of [profiles]), through the file's text as an import reads it. */
    private suspend fun export(
        data: LocalData,
        categories: Set<DataCategory> = DataCategory.entries.toSet(),
        profiles: Set<String>? = null,
    ): TransferFile {
        val ids = profiles ?: data.profiles.profiles().map { it.id }.toSet()
        return TransferFile.decode(data.transfer.export(ids, categories, "1.0.0", clockA.now).encode())
    }

    private fun newProfiles(file: TransferFile) = file.profiles.associate { it.first to ImportTarget.New }

    /** A file's records without what differs between databases: profile ids (names instead), clocks, creation times. */
    private suspend fun normalized(data: LocalData, file: TransferFile): Set<Pair<String, Any?>> {
        val names = data.profiles.profiles().associate { it.id to it.name }
        return file.records.map { record ->
            val id = record.key.profileId
            val body = (record.body as? ProfileBody)?.copy(createdAt = 0) ?: record.body
            record.key.recordId().replace(id, names.getValue(id)) to body
        }.toSet()
    }

    /** [from]'s records as sync would bring them to [to]. */
    private suspend fun copy(from: LocalData, to: LocalData) {
        val records = from.sync.dirtyRecords(null, 1_000)
        assertEquals(emptyList(), to.sync.applyRemote(records.mapIndexed { i, it -> RemoteRecord(it.recordId, i + 1L, Json.parseToJsonElement(it.payload).jsonObject) }))
    }

    private fun watched(videoId: String) = WatchedBitField.empty(EPISODES).apply { set(videoId, true) }.serialize()

    private companion object {
        const val KEY = "RDKEY0123456789ABCDEF"
        const val MOVIE = "tt0111161"
        const val OTHER_MOVIE = "tt0113277"
        const val SERIES = "tt0903747"
        const val SERIES_VIDEO = "tt0903747:1:1"
        const val EPISODE_1 = "tt0903747:1:1"
        const val EPISODE_2 = "tt0903747:1:2"
        val EPISODES = listOf(EPISODE_1, EPISODE_2, "tt0903747:1:3")
        val CINEMETA = AddonDescriptor("https://v3-cinemeta.strem.io/manifest.json", "{\"id\":\"cinemeta\"}")
        val STREAMS = AddonDescriptor("https://addon.example/key=$KEY/manifest.json", "{\"id\":\"example.streams\"}")
        val OPENSUBTITLES = AddonDescriptor("https://opensubtitles-v3.strem.io/manifest.json", "{\"id\":\"opensubtitles\"}")
    }
}
