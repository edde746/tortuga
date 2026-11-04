package com.edde746.tortuga.data.sync

import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.TestClock
import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.movie
import com.edde746.tortuga.data.series
import com.edde746.tortuga.data.settings.DeviceSetting
import com.edde746.tortuga.data.settings.PinLockout
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.streams.DeviceClass
import com.edde746.tortuga.data.streams.PickContext
import com.edde746.tortuga.data.streams.PickOutcome
import com.edde746.tortuga.data.streams.PickSource
import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamPick
import com.edde746.tortuga.data.streams.StreamPickRepository
import com.edde746.tortuga.data.testData
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.SeriesInfo
import com.edde746.tortuga.model.Video
import com.edde746.tortuga.playback.players.PlayerAppPrompt
import com.edde746.tortuga.playback.streams.HdrBadge
import com.edde746.tortuga.playback.streams.ResolutionBadge
import com.edde746.tortuga.playback.streams.VideoCodecBadge
import com.edde746.tortuga.trackers.TrackerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sync hooks between two devices of one account, through a minimal stand-in for the server's contract: one
 * current version per record, a monotonic account version, pull since a cursor, and a stale push answered with the
 * current record.
 */
class SyncStoreTest {
    private val clockA = TestClock()
    private val clockB = TestClock()
    private val a = testData(clockA)
    private val b = testData(clockB)
    private val server = Server()
    private val cursors = mutableMapOf<LocalData, Long>()

    @AfterTest
    fun close() {
        a.close()
        b.close()
    }

    private class Server {
        var version = 0L
        val records = linkedMapOf<String, Pair<Long, JsonObject>>()

        /** Stores [record] and returns its version, or returns the current record when the push is stale. */
        fun push(record: DirtyRecord): Any {
            val current = records[record.recordId]
            if (current?.first != record.baseVersion) return RemoteRecord(record.recordId, current!!.first, current.second)
            version += 1
            records[record.recordId] = version to json(record.payload)
            return version
        }

        fun pull(since: Long) = records.filter { it.value.first > since }.map { RemoteRecord(it.key, it.value.first, it.value.second) }
    }

    /** One sync round of the device: push until nothing is dirty (merging stale answers), then pull. */
    private suspend fun LocalData.syncOnce() {
        repeat(3) {
            val dirty = sync.dirtyRecords(null, 1_000)
            if (dirty.isEmpty()) return@repeat
            val acks = mutableListOf<PushAck>()
            val current = mutableListOf<RemoteRecord>()
            for (record in dirty) {
                when (val answer = server.push(record)) {
                    is Long -> acks += PushAck(record.recordId, record.hlc, answer)
                    else -> current += answer as RemoteRecord
                }
            }
            sync.markPushed(acks)
            assertEquals(emptyList(), sync.applyRemote(current))
        }
        assertEquals(emptyList(), sync.applyRemote(server.pull(cursors[this] ?: 0)))
        cursors[this] = server.version
    }

    private suspend fun syncBoth() {
        a.syncOnce()
        b.syncOnce()
        a.syncOnce()
    }

    @Test
    fun dirtyRecordsPageInHlcOrder() = runTest {
        a.settings.set("p", ProfileSetting.BingeWatching, false)
        a.library.addToLibrary("p", movie())
        a.addons.install("p", "https://v3-cinemeta.strem.io/manifest.json", "{}")
        a.streamHistory.record("p", "tt1", "tt1", "{}", "https://addon.example/manifest.json")
        a.profiles.create("Alex", null)

        val all = a.sync.dirtyRecords(null, 100)
        assertEquals(5, all.size)
        assertEquals(all.sortedBy { it.hlc }, all)
        val first = a.sync.dirtyRecords(null, 2)
        val second = a.sync.dirtyRecords(first.last(), 2)
        val third = a.sync.dirtyRecords(second.last(), 2)
        assertEquals(all.map { it.recordId }, (first + second + third).map { it.recordId })
        assertTrue(all.all { it.baseVersion == null && !it.deleted })
    }

    @Test
    fun dirtyRecordsSharingAnHlcPageByRecordIdInSqlitesOrder() = runTest {
        val profile = a.profiles.create("Alex", null)
        a.settings.set(profile.id, ProfileSetting.BingeWatching, false)
        a.addons.install(profile.id, "https://v3-cinemeta.strem.io/manifest.json", "{}")
        a.streamHistory.record(profile.id, "tt1", "tt1", "{}", "https://addon.example/manifest.json")
        // Ids that UTF-16 and UTF-8 (SQLite's order) sort differently: a surrogate pair against U+FF01.
        for (id in listOf("tt1", "tt2\uD83D\uDE00", "tt2\uFF01", "tt3")) a.library.addToLibrary(profile.id, movie(id))
        // The deletion stamps all eight tombstones with one HLC.
        a.profiles.delete(profile.id)

        val all = a.sync.dirtyRecords(null, 100)
        assertEquals(8, all.size)
        assertEquals(1, all.map { it.hlc }.toSet().size)
        val paged = mutableListOf<DirtyRecord>()
        while (true) {
            val page = a.sync.dirtyRecords(paged.lastOrNull(), 2)
            if (page.isEmpty()) break
            paged += page
        }
        assertEquals(all.map { it.recordId }, paged.map { it.recordId })
    }

    /** One write: a drop cut short can't leave the old account's cursor to skip its records on the next sign-in. */
    @Test
    fun droppingTheSyncedDataForgetsTheirAccountAndCursor() = runTest {
        a.syncState.setPullCursor("account", 42)
        a.library.addToLibrary("p", movie())

        a.sync.dropSyncedData()
        assertNull(a.syncState.dataAccount())
        assertNull(a.syncState.pullCursor("account"))
        assertEquals(emptyList(), a.sync.dirtyRecords(null, 10))
    }

    @Test
    fun droppingTheSyncedDataForgetsThePinLockoutsAndPlayerAppPrompts() = runTest {
        a.deviceSettings.set(DeviceSetting.PinLockouts, mapOf("p" to PinLockout(5, 1)))
        a.deviceSettings.set(DeviceSetting.PlayerAppPrompts, listOf(PlayerAppPrompt("p", "tt1", "tt1", "VLC", "Movie")))
        a.deviceSettings.set(DeviceSetting.HardwareDecoding, false)

        a.sync.dropSyncedData()
        assertEquals(emptyMap(), a.deviceSettings.value(DeviceSetting.PinLockouts))
        assertEquals(emptyList(), a.deviceSettings.value(DeviceSetting.PlayerAppPrompts))
        assertFalse(a.deviceSettings.value(DeviceSetting.HardwareDecoding), "the device's own settings stay")
    }

    @Test
    fun markPushedClearsOnlyTheVersionThatWasPushed() = runTest {
        a.settings.set("p", ProfileSetting.SeekLongSeconds, 20)
        a.settings.set("p", ProfileSetting.SeekShortSeconds, 5)
        assertNotNull(a.sync.observeNewestDirty().first())
        val pushed = a.sync.dirtyRecords(null, 10)
        // One of them changes again while the push is in flight.
        a.settings.set("p", ProfileSetting.SeekShortSeconds, 6)
        a.sync.markPushed(pushed.mapIndexed { i, record -> PushAck(record.recordId, record.hlc, 10L + i) })

        val still = a.sync.dirtyRecords(null, 10).single()
        assertEquals(10L + pushed.indexOfFirst { it.recordId == still.recordId }, still.baseVersion)
        assertEquals(still.hlc, a.sync.observeNewestDirty().first())
        a.sync.markPushed(listOf(PushAck(still.recordId, still.hlc, 12)))
        assertNull(a.sync.observeNewestDirty().first())
    }

    @Test
    fun everySyncedTableRoundTripsToAnotherDevice() = runTest {
        val profile = a.profiles.create("Alex", "fox")
        a.profiles.setPin(profile.id, "2468")
        a.addons.install(profile.id, "https://v3-cinemeta.strem.io/manifest.json", """{"id":"com.linvo.cinemeta"}""")
        a.addons.install(profile.id, "https://opensubtitles-v3.strem.io/manifest.json", """{"id":"org.stremio.opensubtitlesv3"}""")
        a.library.addToLibrary(profile.id, series())
        a.library.recordProgress(profile.id, "tt0903747", "tt0903747:1:1", 120_000, 2_900_000)
        a.library.setWatchedVideos(profile.id, "tt0903747", "tt0903747:1:1:1:eJyrrgUAAXUA+Q==")
        a.streamHistory.record(profile.id, "tt0903747", "tt0903747:1:1", """{"url":"https://cdn.example/e1.mkv","bingeGroup":"g"}""", "https://addon.example/manifest.json")
        a.settings.set(profile.id, ProfileSetting.SubtitleLanguages, listOf("eng", "swe"))

        a.syncOnce()
        b.syncOnce()

        assertEquals(a.profiles.profiles(), b.profiles.profiles())
        assertTrue(b.profiles.verifyPin(profile.id, "2468"))
        assertEquals(a.addons.addons(profile.id), b.addons.addons(profile.id))
        assertEquals(a.library.item(profile.id, "tt0903747"), b.library.item(profile.id, "tt0903747"))
        assertEquals(listOf("tt0903747"), b.library.observeLibrary(profile.id).first().map { it.id })
        assertEquals(
            a.streamHistory.lastStream(profile.id, "tt0903747", "tt0903747:1:1"),
            b.streamHistory.lastStream(profile.id, "tt0903747", "tt0903747:1:1"),
        )
        assertEquals(listOf("eng", "swe"), b.settings.settings(profile.id).subtitleLanguages)
        // Applied records are clean on the receiving device, and its own echo doesn't dirty the sender.
        assertEquals(emptyList(), b.sync.dirtyRecords(null, 100))
        a.syncOnce()
        assertEquals(emptyList(), a.sync.dirtyRecords(null, 100))
    }

    @Test
    fun theLaterWriteWinsOnBothDevices() = runTest {
        a.settings.set("p", ProfileSetting.UpNextCountdownSeconds, 15)
        syncBoth()
        clockA.now += 1_000
        a.settings.set("p", ProfileSetting.UpNextCountdownSeconds, 20)
        clockB.now += 2_000
        b.settings.set("p", ProfileSetting.UpNextCountdownSeconds, 30)
        // A pushes first; B's push is stale, B keeps its later write and pushes it again on top.
        syncBoth()
        assertEquals(30, a.settings.settings("p").upNextCountdownSeconds)
        assertEquals(30, b.settings.settings("p").upNextCountdownSeconds)
        assertEquals(emptyList(), a.sync.dirtyRecords(null, 10))
        assertEquals(emptyList(), b.sync.dirtyRecords(null, 10))
    }

    @Test
    fun anEarlierWriteLosesEvenWhenItArrivesLast() = runTest {
        a.settings.set("p", ProfileSetting.SeekShortSeconds, 15)
        syncBoth()
        clockB.now += 1_000
        b.settings.set("p", ProfileSetting.SeekShortSeconds, 5)
        clockA.now += 2_000
        a.settings.set("p", ProfileSetting.SeekShortSeconds, 25)
        // B pushes first this time; A's later write wins anyway.
        b.syncOnce()
        a.syncOnce()
        b.syncOnce()
        assertEquals(25, a.settings.settings("p").seekShortSeconds)
        assertEquals(25, b.settings.settings("p").seekShortSeconds)
    }

    @Test
    fun progressKeepsTheFurthestTimeWatchedAndTheLatestLastWatched() = runTest {
        a.library.upsertFromMeta("p", movie())
        syncBoth()
        val id = "tt0111161"
        // A watches 40 minutes.
        clockA.now += 10_000
        a.library.recordProgress("p", id, id, 0, 8_520_000)
        a.library.recordProgress("p", id, id, 2_400_000, 8_520_000)
        // B, later and offline, watches 10 minutes from the start.
        clockB.now += 20_000
        b.library.recordProgress("p", id, id, 0, 8_520_000)
        b.library.recordProgress("p", id, id, 600_000, 8_520_000)

        syncBoth()

        val onA = assertNotNull(a.library.item("p", id))
        assertEquals(onA, b.library.item("p", id))
        assertEquals(600_000, onA.state.timeOffset) // B's write is the later one
        assertEquals(2_400_000, onA.state.timeWatched) // A's furthest
        assertEquals(clockB.now, onA.state.lastWatched)
        assertEquals(2_400_000, onA.state.overallTimeWatched)
        assertEquals(emptyList(), a.sync.dirtyRecords(null, 10))
        assertEquals(emptyList(), b.sync.dirtyRecords(null, 10))
    }

    @Test
    fun timeWatchedOfAnotherVideoDoesNotCarryOver() = runTest {
        a.library.upsertFromMeta("p", series())
        syncBoth()
        val id = "tt0903747"
        clockA.now += 10_000
        a.library.recordProgress("p", id, "$id:1:1", 0, 2_900_000)
        a.library.recordProgress("p", id, "$id:1:1", 2_000_000, 2_900_000)
        clockB.now += 20_000
        b.library.recordProgress("p", id, "$id:1:2", 0, 2_900_000)
        b.library.recordProgress("p", id, "$id:1:2", 300_000, 2_900_000)

        syncBoth()

        val state = b.library.item("p", id)!!.state
        assertEquals("$id:1:2", state.videoId)
        assertEquals(300_000, state.timeWatched)
        assertEquals(2_000_000, state.overallTimeWatched)
        assertEquals(state, a.library.item("p", id)!!.state)
    }

    @Test
    fun aLaterEditRevivesADeletedItemAndALaterDeleteWins() = runTest {
        a.library.addToLibrary("p", movie("tt1"))
        a.library.addToLibrary("p", movie("tt2"))
        syncBoth()

        clockA.now += 1_000
        a.library.delete("p", "tt1")
        clockB.now += 2_000
        b.library.removeFromLibrary("p", "tt1")

        clockB.now += 1_000
        b.library.recordProgress("p", "tt2", "tt2", 1_000, 100_000)
        clockA.now += 5_000
        a.library.delete("p", "tt2")

        syncBoth()

        // tt1: B's edit came after A's delete.
        assertTrue(assertNotNull(a.library.item("p", "tt1")).removed)
        assertEquals(a.library.item("p", "tt1"), b.library.item("p", "tt1"))
        // tt2: A's delete came after B's edit.
        assertNull(a.library.item("p", "tt2"))
        assertNull(b.library.item("p", "tt2"))
    }

    @Test
    fun addonListsNeverInterleave() = runTest {
        val cinemeta = "https://v3-cinemeta.strem.io/manifest.json"
        val subtitles = "https://opensubtitles-v3.strem.io/manifest.json"
        val streams = "https://addon.example/manifest.json"
        a.addons.install("p", cinemeta, "{}")
        a.addons.install("p", subtitles, "{}")
        syncBoth()

        clockA.now += 1_000
        a.addons.reorder("p", listOf(subtitles, cinemeta))
        clockB.now += 2_000
        b.addons.install("p", streams, "{}")
        syncBoth()

        // B's list is the later record and wins whole: A's reorder is gone, not merged into a third order.
        val expected = listOf(cinemeta, subtitles, streams)
        assertEquals(expected, a.addons.addons("p").map { it.transportUrl })
        assertEquals(expected, b.addons.addons("p").map { it.transportUrl })
    }

    @Test
    fun eachAddonsSwitchAndWatchActivityAgreementTravelWithTheList() = runTest {
        val cinemeta = "https://v3-cinemeta.strem.io/manifest.json"
        val tracker = "https://tracker.example/manifest.json"
        a.addons.replaceAll("p", listOf(AddonDescriptor(cinemeta, "{}"), AddonDescriptor(tracker, "{}", enabled = false, watchActivity = true)))
        val pushed = SyncRecord.decode(json(a.sync.dirtyRecords(null, 10).single().payload)).body
        assertEquals(AddonListBody(listOf(AddonEntry(cinemeta, "{}"), AddonEntry(tracker, "{}", enabled = false, watchActivity = true))), pushed)
        syncBoth()
        assertEquals(
            listOf(AddonDescriptor(cinemeta, "{}"), AddonDescriptor(tracker, "{}", enabled = false, watchActivity = true)),
            b.addons.addons("p").map { AddonDescriptor(it.transportUrl, it.manifestJson, it.enabled, it.watchActivity) },
        )

        clockB.now += 1_000
        b.addons.replaceAll("p", listOf(AddonDescriptor(cinemeta, "{}"), AddonDescriptor(tracker, "{}", enabled = true, watchActivity = false)))
        syncBoth()
        assertEquals(listOf(true to false), a.addons.addons("p").filter { it.transportUrl == tracker }.map { it.enabled to it.watchActivity })
    }

    @Test
    fun anAddonListInTheUnpackedShapeOfEarlierBuildsStillApplies() = runTest {
        val cinemeta = "https://v3-cinemeta.strem.io/manifest.json"
        val tracker = "https://tracker.example/manifest.json"
        a.addons.replaceAll("p", listOf(AddonDescriptor(cinemeta, "{}"), AddonDescriptor(tracker, "{}", enabled = false, watchActivity = true)))
        val record = a.sync.dirtyRecords(null, 10).single()
        val packed = json(record.payload)
        assertEquals(setOf("kind", "packed"), packed.getValue("body").jsonObject.keys)
        // The body as builds before packing wrote it: the entries as a JSON array.
        val unpacked = json(
            """{"kind":"addons","addons":[{"transportUrl":"$cinemeta","manifest":"{}","enabled":true,"watchActivity":false},""" +
                """{"transportUrl":"$tracker","manifest":"{}","enabled":false,"watchActivity":true}]}""",
        )

        assertEquals(emptyList(), b.sync.applyRemote(listOf(RemoteRecord(record.recordId, 1, JsonObject(packed + ("body" to unpacked))))))
        assertEquals(
            listOf(AddonDescriptor(cinemeta, "{}"), AddonDescriptor(tracker, "{}", enabled = false, watchActivity = true)),
            b.addons.addons("p").map { AddonDescriptor(it.transportUrl, it.manifestJson, it.enabled, it.watchActivity) },
        )
    }

    @Test
    fun aDeletedProfileDisappearsFromTheOtherDevice() = runTest {
        val profile = a.profiles.create("Kids", null)
        a.library.addToLibrary(profile.id, movie())
        syncBoth()
        b.trackerAccounts.connect(profile.id, TrackerService.SIMKL, "kid", emptyList(), 1)

        clockA.now += 1_000
        a.profiles.delete(profile.id)
        syncBoth()

        assertEquals(emptyList(), b.profiles.profiles())
        assertNull(b.library.item(profile.id, "tt0111161"))
        assertEquals(emptyList(), b.trackerAccounts.accounts(profile.id))
    }

    @Test
    fun aTombstoneForAnUnknownRecordIsIgnored() = runTest {
        a.streamHistory.record("p", "tt1", "tt1", "{}", "https://addon.example/manifest.json")
        a.syncOnce()
        a.streamHistory.forget("p", "tt1", "tt1")
        val tombstone = a.sync.dirtyRecords(null, 10).single()
        assertTrue(tombstone.deleted)

        assertEquals(emptyList(), b.sync.applyRemote(listOf(RemoteRecord(tombstone.recordId, 2, json(tombstone.payload)))))
        assertNull(b.streamHistory.lastStream("p", "tt1", "tt1"))
        assertEquals(emptyList(), b.sync.dirtyRecords(null, 10))
    }

    @Test
    fun recordIdsAreTheNaturalKeysAndStayDistinctWhateverTheIdsContain() = runTest {
        assertEquals("profile/p1", RecordKey.Profile("p1").recordId())
        assertEquals("addons/p1", RecordKey.AddonList("p1").recordId())
        assertEquals("library/p1/tt0111161", RecordKey.LibraryItem("p1", "tt0111161").recordId())
        assertEquals("stream/p1/tt0903747/tt0903747:1:1", RecordKey.StreamHistory("p1", "tt0903747", "tt0903747:1:1").recordId())
        assertEquals("setting/p1/bingeWatching", RecordKey.Setting("p1", "bingeWatching").recordId())
        // Keys whose parts split or escape differently never share an id.
        val keys = listOf(
            RecordKey.StreamHistory("p", "a/b", "c"),
            RecordKey.StreamHistory("p", "a", "b/c"),
            RecordKey.StreamHistory("p", "a%2Fb", "c"),
            RecordKey.StreamHistory("p", "a%252Fb", "c"),
            RecordKey.LibraryItem("p/x", "y"),
            RecordKey.LibraryItem("p", "x/y"),
        )
        assertEquals(keys.size, keys.map { it.recordId() }.toSet().size)

        // An item id with both characters syncs under its own id and arrives intact.
        val odd = "yt_id:UC/50%off"
        a.library.addToLibrary("p", MetaSnapshot(odd, "channel", "Odd", null, "square"))
        assertEquals(listOf("library/p/yt_id:UC%2F50%25off"), a.sync.dirtyRecords(null, 10).map { it.recordId })
        a.syncOnce()
        b.syncOnce()
        assertEquals("Odd", b.library.item("p", odd)?.name)
    }

    @Test
    fun recordsThatCantBeTrustedAreRejected() = runTest {
        a.settings.set("p", ProfileSetting.BingeWatching, false)
        val good = a.sync.dirtyRecords(null, 10).single()
        val payload = json(good.payload)

        // A device whose clock runs a week ahead.
        val ahead = testData(TestClock(clockA.now + 7 * 24 * 3_600_000L))
        ahead.settings.set("p", ProfileSetting.SeekLongSeconds, 60)
        val future = ahead.sync.dirtyRecords(null, 10).single()
        ahead.close()

        val rejected = b.sync.applyRemote(
            listOf(
                RemoteRecord("r1", 1, JsonObject(mapOf("hello" to JsonPrimitive("world")))),
                RemoteRecord(good.recordId, 2, JsonObject(payload + ("format" to JsonPrimitive(2)))),
                RemoteRecord("setting/other/bingeWatching", 3, payload),
                RemoteRecord(future.recordId, 4, json(future.payload)),
                RemoteRecord(good.recordId, 5, payload),
            ),
        )
        assertEquals(
            listOf(
                "r1" to RejectedRecord.Reason.UNREADABLE,
                good.recordId to RejectedRecord.Reason.NEWER_FORMAT,
                "setting/other/bingeWatching" to RejectedRecord.Reason.ID_MISMATCH,
                future.recordId to RejectedRecord.Reason.CLOCK_DRIFT,
            ),
            rejected.map { it.recordId to it.reason },
        )
        // The good record in the same batch applied.
        assertFalse(b.settings.settings("p").bingeWatching)
        assertEquals(ProfileSetting.SeekLongSeconds.default, b.settings.settings("p").seekLongSeconds)
    }

    /** A setting's value or a watched field that doesn't read is refused as it arrives: what this device has stays. */
    @Test
    fun contentThatDoesntReadIsRejectedAndTheLocalRecordStays() = runTest {
        b.settings.set("p", ProfileSetting.SeekShortSeconds, 20)
        b.library.upsertFromMeta("p", series())
        val field = WatchedBitField.empty(emptyList()).serialize()
        b.library.setWatchedVideos("p", "tt0903747", field)
        clockA.now += 60_000
        a.settings.set("p", ProfileSetting.SeekShortSeconds, 5)
        a.library.upsertFromMeta("p", series())
        a.library.setWatchedVideos("p", "tt0903747", "tt0903747:1:1:1:not-base64")
        val records = a.sync.dirtyRecords(null, 10).associateBy { it.recordId }
        val setting = assertNotNull(records[RecordKey.Setting("p", ProfileSetting.SeekShortSeconds.key).recordId()])
        val item = assertNotNull(records[RecordKey.LibraryItem("p", "tt0903747").recordId()])
        val payload = json(setting.payload)
        val wrongType = JsonObject(payload + ("body" to JsonObject(payload.getValue("body").jsonObject + ("value" to JsonPrimitive("\"five\"")))))

        val rejected = b.sync.applyRemote(listOf(RemoteRecord(setting.recordId, 1, wrongType), RemoteRecord(item.recordId, 2, json(item.payload))))
        assertEquals(listOf(setting.recordId, item.recordId), rejected.filter { it.reason == RejectedRecord.Reason.UNREADABLE }.map { it.recordId })
        assertEquals(20, b.settings.settings("p").seekShortSeconds)
        assertEquals(field, b.library.item("p", "tt0903747")?.state?.watchedVideos)
    }

    @Test
    fun aStateAnOlderBuildSyncedOntoAWatchedEpisodeMovesOnAndSyncsBack() = runTest {
        // Device A, an older build: S1E1–S1E2 marked and the pointer left on S1E2 at 93 %, written without the meta.
        val show = MetaItem(
            MetaItemPreview(id = "tt0903747", type = "series", name = "Breaking Bad"),
            (1..3).map { Video(id = "tt0903747:1:$it", seriesInfo = SeriesInfo(1, it)) },
        )
        a.library.upsertFromMeta("p", series())
        val marks = WatchedBitField.of(null, show).apply { set("tt0903747:1:1", true); set("tt0903747:1:2", true) }
        a.library.setWatchedVideos("p", show.id, marks.serialize())
        a.library.recordProgress("p", show.id, "tt0903747:1:2", 2_700_000, 2_900_000, watched = true)
        syncBoth()
        assertEquals("tt0903747:1:2", assertNotNull(b.library.item("p", show.id)).state.videoId, "the merge carries the row as stored")

        // B reads Continue Watching with the meta: the item moves on there, and A takes that from sync.
        val settling = launch(Dispatchers.Default) { b.library.settleContinueWatching("p") { _, _ -> show } }
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { b.library.observeContinueWatching("p", 10).first { it.singleOrNull()?.state?.videoId == "tt0903747:1:3" } }
        }
        settling.cancel()
        syncBoth()
        val state = assertNotNull(a.library.item("p", show.id)).state
        assertEquals("tt0903747:1:3", state.videoId)
        assertEquals(1, state.timeOffset)
    }

    private fun pick(id: String, deviceClass: DeviceClass = DeviceClass.TV) = StreamPick(
        id = id,
        metaId = "tt0903747",
        videoId = "tt0903747:1:1",
        runtimeSeconds = 2820,
        pickedAt = 0,
        context = PickContext(deviceClass, metered = false, hdrDisplay = true, dolbyVisionDisplay = false, hardwareCodecs = setOf(VideoCodecBadge.HEVC)),
        source = PickSource.LIST,
        candidates = listOf(
            PickedStream(ResolutionBadge.UHD_2160, listOf(HdrBadge.HDR), VideoCodecBadge.HEVC, sizeBytes = 9_000_000_000),
            PickedStream(ResolutionBadge.FHD_1080, audioLanguages = listOf("eng"), multiSubtitles = true),
        ),
        chosen = 1,
        outcome = null,
        firstRow = 3,
    )

    /** [count] picks on [data]'s TV, a second apart, named [prefix] and their number. */
    private suspend fun picks(data: LocalData, clock: TestClock, prefix: String, count: Int) = repeat(count) {
        clock.now += 1_000
        data.streamPicks.record("p", pick("$prefix$it"))
    }

    @Test
    fun aPickReachesTheOtherDeviceWithItsCandidatesAndContext() = runTest {
        a.streamPicks.record("p", pick("k1"))
        assertEquals(listOf("pick/p/k1"), a.sync.dirtyRecords(null, 10).map { it.recordId })
        syncBoth()
        val arrived = b.streamPicks.picks("p")
        assertEquals(a.streamPicks.picks("p"), arrived)
        assertEquals(pick("k1").candidates, arrived.single().candidates)
        assertEquals(pick("k1").context, arrived.single().context)
    }

    @Test
    fun anOutcomeIsANewWriteOfItsPickAndTheNewerOneWins() = runTest {
        a.streamPicks.record("p", pick("k1"))
        syncBoth()
        a.streamPicks.setOutcome("k1", PickOutcome.BAD)
        clockB.now = clockA.now + 60_000
        b.streamPicks.setOutcome("k1", PickOutcome.GOOD)
        assertEquals(listOf("pick/p/k1"), a.sync.dirtyRecords(null, 10).map { it.recordId }, "the pick's own record")
        syncBoth()
        assertEquals(PickOutcome.GOOD, a.streamPicks.picks("p").single().outcome)
        assertEquals(PickOutcome.GOOD, b.streamPicks.picks("p").single().outcome)
    }

    @Test
    fun aPrunedPickIsATombstoneThatLeavesTheOtherDevice() = runTest {
        picks(a, clockA, "a", StreamPickRepository.KEEP)
        syncBoth()
        clockA.now += 1_000
        a.streamPicks.record("p", pick("new"))
        val tombstone = a.sync.dirtyRecords(null, 10).single { it.deleted }
        assertEquals("pick/p/a0", tombstone.recordId)
        assertFalse("body" in json(tombstone.payload), "a tombstone carries no candidates")
        a.syncOnce()
        clockB.now = clockA.now
        b.syncOnce()

        val kept = b.streamPicks.picks("p").map { it.id }
        assertEquals((1 until StreamPickRepository.KEEP).map { "a$it" } + "new", kept)
        assertEquals(emptyList(), b.sync.dirtyRecords(null, 10), "the other device took the tombstone and wrote nothing")
        val stored = assertNotNull(b.db.database.streamPickQueries.select("a0").executeAsOneOrNull())
        assertTrue(stored.deleted)
        assertEquals("[]", stored.candidates_json)
    }

    /** Two devices that prune the same picks drop the same ones, and each kind of device keeps its own. */
    @Test
    fun devicesPruningThePooledPicksAgreeAndAPhoneDoesntCrowdOutTheTv() = runTest {
        picks(a, clockA, "a", StreamPickRepository.KEEP)
        syncBoth()
        clockB.now = clockA.now + 1_000
        b.streamPicks.record("p", pick("b"))
        b.streamPicks.record("p", pick("phone", DeviceClass.HANDHELD))
        clockA.now += 2_000
        a.streamPicks.record("p", pick("c"))
        syncBoth()
        b.syncOnce()

        val expected = (2 until StreamPickRepository.KEEP).map { "a$it" } + listOf("b", "phone", "c")
        assertEquals(expected, a.streamPicks.picks("p").map { it.id })
        assertEquals(expected, b.streamPicks.picks("p").map { it.id })
    }

    @Test
    fun aPickThatDoesntReadIsRejected() = runTest {
        a.streamPicks.record("p", pick("k1"))
        val record = a.sync.dirtyRecords(null, 10).single()
        val payload = json(record.payload)
        fun withBody(field: String, value: JsonPrimitive) =
            JsonObject(payload + ("body" to JsonObject(payload.getValue("body").jsonObject + (field to value))))

        val rejected = b.sync.applyRemote(
            listOf(
                RemoteRecord(record.recordId, 1, withBody("chosen", JsonPrimitive(2))),
                RemoteRecord(record.recordId, 2, withBody("deviceClass", JsonPrimitive("watch"))),
                RemoteRecord(record.recordId, 3, withBody("outcome", JsonPrimitive("great"))),
            ),
        )
        assertEquals(List(3) { RejectedRecord.Reason.UNREADABLE }, rejected.map { it.reason })
        assertEquals(emptyList(), b.streamPicks.picks("p"))
        assertNull(b.db.database.streamPickQueries.select("k1").executeAsOneOrNull(), "nothing stored")
    }
}

private fun json(payload: String): JsonObject = Json.parseToJsonElement(payload).jsonObject
