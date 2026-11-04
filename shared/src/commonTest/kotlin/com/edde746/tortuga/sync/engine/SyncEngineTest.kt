@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.sync.engine

import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.TrackerSwitch
import com.edde746.tortuga.data.settings.TrackerSwitches
import com.edde746.tortuga.data.streams.DeviceClass
import com.edde746.tortuga.data.streams.PickContext
import com.edde746.tortuga.data.streams.PickOutcome
import com.edde746.tortuga.data.streams.PickSource
import com.edde746.tortuga.data.streams.PickedStream
import com.edde746.tortuga.data.streams.StreamPick
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.data.movie
import com.edde746.tortuga.data.series
import com.edde746.tortuga.log.Log
import com.edde746.tortuga.playback.streams.ResolutionBadge
import com.edde746.tortuga.playback.streams.VideoCodecBadge
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.AccountState
import com.edde746.tortuga.sync.account.DeviceLinkOutcome
import com.edde746.tortuga.sync.account.SignInResult
import com.edde746.tortuga.sync.api.ApiErrorCode
import com.edde746.tortuga.sync.api.RecordChange
import com.edde746.tortuga.sync.api.SyncPaths
import com.edde746.tortuga.sync.api.SyncProtocol
import com.edde746.tortuga.trackers.TrackerService
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sync engine against the in-memory server: convergence, merge and conflict rules, tombstones,
 * batching, restores, and scheduling in virtual time.
 */
class SyncEngineTest {
    private val server = FakeSyncServer()

    private fun TestScope.device(name: String, config: SyncEngineConfig = SyncEngineConfig(), clockSkewMillis: Long = 0) =
        TestDevice(name, server, this, backgroundScope, MemorySecretStore(), config, clockSkewMillis)

    /** A signed-up phone and a TV linked to it, both signed in. */
    private suspend fun TestScope.pair(config: SyncEngineConfig = SyncEngineConfig()): Pair<TestDevice, TestDevice> {
        val phone = device("Phone", config)
        phone.account.restore()
        assertEquals(SignInResult.NEW_ACCOUNT, phone.account.signIn(server.serverUrl) { TestDevice.credential() })
        val tv = device("TV", config)
        tv.account.restore()
        val pending = tv.account.startDeviceLink(server.serverUrl)
        val result = async { pending.awaitResult() }
        phone.account.claimDeviceLink(pending.code).approve()
        assertEquals(DeviceLinkOutcome.LINKED, result.await())
        return phone to tv
    }

    /** A third device signed in to the pair's account, its clock two days ahead: the others refuse its writes. */
    private suspend fun TestScope.laptopAhead(): TestDevice {
        val laptop = device("Laptop", clockSkewMillis = 2 * DAY_MILLIS)
        laptop.account.restore()
        assertEquals(SignInResult.EXISTING_ACCOUNT, laptop.account.signIn(server.serverUrl) { TestDevice.credential() })
        return laptop
    }

    private suspend fun TestDevice.sync() = assertEquals(SyncStatus.Kind.Idle, engine.syncNow().kind)

    private fun pulls() = server.requests.count { it == PULL }

    private fun pushes() = server.requests.count { it == PUSH }

    @Test
    fun profilesAddonsLibraryAndProgressConverge() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.use {
                val profile = phone.data.profiles.create("Wednesday", "avatar-3")
                phone.data.profiles.setPin(profile.id, "4711")
                phone.data.addons.replaceAll(
                    profile.id,
                    listOf(
                        AddonDescriptor("https://v3-cinemeta.strem.io/manifest.json", "{\"id\":\"cinemeta\"}"),
                        AddonDescriptor("https://addon.example/key=SECRETKEY/manifest.json", "{\"id\":\"example.streams\"}"),
                    ),
                )
                phone.data.library.addToLibrary(profile.id, movie())
                phone.data.library.recordProgress(profile.id, "tt0111161", "tt0111161", 600_000, 8_520_000)
                phone.sync()
                tv.sync()

                assertEquals(listOf("Wednesday"), tv.data.profiles.profiles().map { it.name })
                assertTrue(tv.data.profiles.verifyPin(profile.id, "4711"))
                assertFalse(tv.data.profiles.verifyPin(profile.id, "1174"))
                assertEquals(
                    listOf("https://v3-cinemeta.strem.io/manifest.json", "https://addon.example/key=SECRETKEY/manifest.json"),
                    tv.data.addons.addons(profile.id).map { it.transportUrl },
                )
                val item = assertNotNull(tv.data.library.item(profile.id, "tt0111161"))
                assertEquals(600_000, item.state.timeOffset)
                assertFalse(tv.data.sync.dirtyRecords(null, 10).isNotEmpty())

                // Progress played on the TV comes back to the phone.
                tv.data.library.recordProgress(profile.id, "tt0111161", "tt0111161", 900_000, 8_520_000)
                tv.sync()
                phone.sync()
                assertEquals(900_000, phone.data.library.item(profile.id, "tt0111161")?.state?.timeOffset)
            }
        }
    }

    @Test
    fun aStalePushMergesAndTheFurthestPositionWins() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.use {
                val profile = phone.data.profiles.create("Wednesday", null)
                phone.data.library.upsertFromMeta(profile.id, movie())
                phone.data.library.recordProgress(profile.id, "tt0111161", "tt0111161", 60_000, 8_520_000)
                phone.sync()
                tv.sync()

                // Both play: the phone gets further; the TV writes later and pushes just before the phone does.
                phone.data.library.recordProgress(profile.id, "tt0111161", "tt0111161", 3_000_000, 8_520_000)
                testScheduler.advanceTimeBy(60_000)
                tv.data.library.recordProgress(profile.id, "tt0111161", "tt0111161", 1_200_000, 8_520_000)
                val tvLastWatched = tv.data.library.item(profile.id, "tt0111161")!!.state.lastWatched
                server.beforeNextPush = { tv.sync() }
                phone.sync()
                assertEquals(1, server.staleAnswers, "the phone's push raced the TV's and came back stale")
                tv.sync()

                // Last writer (the TV) wins the position; time watched keeps the phone's furthest; lastWatched the latest.
                for (device in listOf(phone, tv)) {
                    val state = device.data.library.item(profile.id, "tt0111161")!!.state
                    assertEquals(2_940_000, state.timeWatched, device.name)
                    assertEquals(1_200_000, state.timeOffset, device.name)
                    assertEquals(tvLastWatched, state.lastWatched, device.name)
                }
                assertTrue(phone.data.sync.dirtyRecords(null, 10).isEmpty())
                assertTrue(tv.data.sync.dirtyRecords(null, 10).isEmpty())
            }
        }
    }

    @Test
    fun deletesTravelAsTombstones() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.use {
                val profile = phone.data.profiles.create("Wednesday", null)
                val kids = phone.data.profiles.create("Kids", null)
                phone.data.library.addToLibrary(profile.id, movie())
                phone.data.library.addToLibrary(profile.id, series())
                phone.sync()
                tv.sync()
                assertNotNull(tv.data.library.item(profile.id, "tt0903747"))

                phone.data.library.delete(profile.id, "tt0903747")
                phone.data.profiles.delete(kids.id)
                phone.sync()
                tv.sync()
                assertNull(tv.data.library.item(profile.id, "tt0903747"))
                assertNotNull(tv.data.library.item(profile.id, "tt0111161"))
                assertEquals(listOf("Wednesday"), tv.data.profiles.profiles().map { it.name })
                // The server keeps the tombstones under the records' ids.
                val tombstones = server.account().records.values.filter { it.deleted }.map { it.recordId }
                assertTrue(tombstones.containsAll(listOf("library/${profile.id}/tt0903747", "profile/${kids.id}")), "$tombstones")
            }
        }
    }

    @Test
    fun aTrackerSwitchTurnedOffOnOneDeviceIsOffOnTheOtherAndEachSwitchKeepsItsOwnChange() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.use {
                val profile = phone.data.profiles.create("Wednesday", null)
                phone.sync()
                tv.sync()

                // Each device turns off another of Trakt's switches before it hears of the other's.
                phone.data.settings.set(profile.id, ProfileSetting.Tracker(TrackerService.TRAKT, TrackerSwitch.SENDING), false)
                testScheduler.advanceTimeBy(1_000)
                tv.data.settings.set(profile.id, ProfileSetting.Tracker(TrackerService.TRAKT, TrackerSwitch.ANIME), false)
                phone.sync()
                tv.sync()
                phone.sync()
                val off = TrackerSwitches.DEFAULT.copy(sending = false, anime = false)
                for (device in listOf(phone, tv)) {
                    val switches = device.data.settings.settings(profile.id).trackers
                    assertEquals(off, switches.getValue(TrackerService.TRAKT), device.name)
                    assertEquals(TrackerSwitches.DEFAULT, switches.getValue(TrackerService.SIMKL), device.name)
                }

                // Turned back on on the TV: on on the phone too, the anime filter still off.
                testScheduler.advanceTimeBy(1_000)
                tv.data.settings.set(profile.id, ProfileSetting.Tracker(TrackerService.TRAKT, TrackerSwitch.SENDING), true)
                tv.sync()
                phone.sync()
                assertEquals(TrackerSwitches.DEFAULT.copy(anime = false), phone.data.settings.settings(profile.id).trackers.getValue(TrackerService.TRAKT))
            }
        }
    }

    @Test
    fun aRecordThatCantBeAppliedIsSkippedAndTheRestApplies() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.use {
                val profile = phone.data.profiles.create("Wednesday", null)
                phone.data.library.addToLibrary(profile.id, movie())
                phone.data.library.addToLibrary(profile.id, series())
                phone.sync()
                val account = server.account()
                // The series' record arrives in a payload format this build doesn't know.
                val victim = account.records.getValue("library/${profile.id}/tt0903747")
                val newer = JsonObject(victim.payload + ("format" to JsonPrimitive(99)))
                account.records[victim.recordId] = RecordChange(victim.recordId, victim.version, victim.deleted, newer, 0)
                tv.sync()
                assertEquals(1, tv.data.profiles.profiles().size)
                assertNotNull(tv.data.library.item(profile.id, "tt0111161"))
                assertNull(tv.data.library.item(profile.id, "tt0903747"))
            }
        }
    }

    @Test
    fun aRecordWhoseContentDoesntApplyIsSkippedAndThePullGoesOn() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.use {
                val kept = phone.data.profiles.create("Wednesday", null)
                val broken = phone.data.profiles.create("Pugsley", null)
                phone.data.library.addToLibrary(kept.id, movie())
                phone.sync()
                val account = server.account()
                // A PIN hash that isn't base64, written by a build with a bug or by hand.
                val victim = account.records.getValue("profile/${broken.id}")
                val body = JsonObject(victim.payload.getValue("body").jsonObject + ("pinHash" to JsonPrimitive("not base64!")))
                account.records[victim.recordId] = RecordChange(victim.recordId, victim.version, victim.deleted, JsonObject(victim.payload + ("body" to body)), 0)
                tv.sync()
                assertEquals(listOf("Wednesday"), tv.data.profiles.profiles().map { it.name })
                assertNotNull(tv.data.library.item(kept.id, "tt0111161"))

                // Later changes still arrive.
                phone.data.library.addToLibrary(kept.id, series())
                phone.sync()
                tv.sync()
                assertNotNull(tv.data.library.item(kept.id, "tt0903747"))
            }
        }
    }

    @Test
    fun largePushesAreSplitIntoBatchesOfAtMost500() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.close()
            val profile = phone.data.profiles.create("Wednesday", null)
            repeat(1_100) { i ->
                phone.data.library.addToLibrary(profile.id, MetaSnapshot("tt${1_000_000 + i}", "movie", "Movie $i", null, "poster"))
            }
            server.pushes.clear()
            phone.sync()
            // The profile and 1100 library items.
            assertEquals(listOf(500, 500, 101), server.pushes.map { it.writes.size })
            assertEquals(1_101, server.account().records.size)
        }
    }

    @Test
    fun aServerRestoredFromABackupGetsTheLostWritesAgain() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.use {
                val profile = phone.data.profiles.create("Wednesday", null)
                phone.data.library.addToLibrary(profile.id, movie())
                phone.sync()
                val backup = server.snapshot()
                phone.data.library.addToLibrary(profile.id, series())
                phone.data.library.recordProgress(profile.id, "tt0111161", "tt0111161", 120_000, 8_520_000)
                phone.sync()
                tv.sync()
                val before = server.account().records.size

                server.restore(backup)
                phone.sync()
                // Everything the phone had is on the server again, the lost versions re-pushed as new.
                assertEquals(before, server.account().records.size)
                assertTrue(phone.data.sync.dirtyRecords(null, 10).isEmpty())

                // The TV had pulled past the restored counter too: it re-syncs from 0 and keeps its data.
                tv.sync()
                assertNotNull(tv.data.library.item(profile.id, "tt0903747"))
                assertEquals(120_000, tv.data.library.item(profile.id, "tt0111161")?.state?.timeOffset)
            }
        }
    }

    @Test
    fun aPushOfARecordTheRestoredServerLacksReSyncsFromVersionZero() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.close()
            val profile = phone.data.profiles.create("Wednesday", null)
            phone.sync()
            val backup = server.snapshot()
            phone.data.library.addToLibrary(profile.id, movie())
            phone.sync()

            // A push alone, with no pull before it to see the counter go back: the server answers that it lacks the
            // version the change is based on.
            server.restore(backup)
            phone.data.library.recordProgress(profile.id, "tt0111161", "tt0111161", 60_000, 8_520_000)
            server.requests.clear()
            assertEquals(SyncStatus.Kind.Idle, phone.engine.pushNow().kind)
            assertEquals(listOf(PUSH, PULL, PUSH), server.requests)
            assertTrue("library/${profile.id}/tt0111161" in server.account().records)
            assertTrue(phone.data.sync.dirtyRecords(null, 10).isEmpty())
        }
    }

    @Test
    fun aPushTheServerFindsMalformedFailsLoudlyAndForgetsNoVersions() = runTest {
        val (phone, tv) = pair()
        val logged = mutableListOf<String>()
        val sink = Log.sink
        Log.sink = Log.Sink { level, _, message, _ -> logged += "$level $message" }
        try {
            phone.use {
                tv.close()
                val profile = phone.data.profiles.create("Wednesday", null)
                phone.data.library.addToLibrary(profile.id, movie())
                phone.sync()
                phone.data.library.recordProgress(profile.id, "tt0111161", "tt0111161", 60_000, 8_520_000)
                server.refusePushes = ApiErrorCode.INVALID_REQUEST
                server.requests.clear()

                // A bug in this client, not a server that lost writes: no re-sync from version 0.
                val status = phone.engine.syncNow()
                assertEquals(SyncStatus.Kind.Error(SyncStatus.Failure.UNKNOWN), status.kind)
                assertEquals(listOf(PULL, PUSH), server.requests)
                assertEquals(listOf("library/${profile.id}/tt0111161"), phone.data.sync.dirtyRecords(null, 10).map { it.recordId })
                assertTrue(logged.any { it.startsWith("ERROR The server refused a request as malformed") }, "$logged")

                server.refusePushes = null
                phone.sync()
                assertEquals(1, server.pushes.last().writes.size)
            }
        } finally {
            Log.sink = sink
        }
    }

    @Test
    fun aLocalRowThatCantBeReadFailsTheCycleAndTheRecordComesAgainOnceItCan() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.use {
                val profile = phone.data.profiles.create("Wednesday", null)
                phone.sync()
                tv.sync()
                // The TV's own row of the profile holds Home rows it can't read (a migration's bug, say).
                val stored = tv.data.db.read { profileQueries.select(profile.id).executeAsOne() }
                tv.data.db.write { q.profileQueries.upsert(stored.copy(home_catalogs = "not json")) }
                phone.data.profiles.rename(profile.id, "Thursday")
                phone.sync()

                // The phone's record isn't at fault, so it isn't skipped: the cycle fails, and the cursor stays.
                assertIs<SyncStatus.Kind.Error>(tv.engine.syncNow().kind)
                tv.data.db.write { q.profileQueries.upsert(stored) }
                tv.sync()
                assertEquals(listOf("Thursday"), tv.data.profiles.profiles().map { it.name })
            }
        }
    }

    @Test
    fun offlineIsReportedAndChangesPushOnceTheServerIsBack() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.close()
            phone.data.profiles.create("Wednesday", null)
            server.down = true
            assertEquals(SyncStatus.Kind.Offline, phone.engine.syncNow().kind)
            assertTrue(phone.data.sync.dirtyRecords(null, 10).isNotEmpty())
            server.down = false
            phone.sync()
            assertTrue(phone.data.sync.dirtyRecords(null, 10).isEmpty())
        }
    }

    @Test
    fun localChangesPushAfterTheDebounceAndProgressEveryFiveMinutesWhilePlayingWithoutPulls() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.close()
            val profile = phone.data.profiles.create("Wednesday", null)
            phone.data.library.upsertFromMeta(profile.id, movie())
            phone.sync()
            phone.engine.start()
            phone.engine.onForeground()
            runCurrent()
            server.requests.clear()

            phone.data.library.addToLibrary(profile.id, series())
            advanceTimeBy(1_000)
            assertEquals(0, pushes(), "still debouncing")
            advanceTimeBy(2_000)
            assertEquals(listOf(PUSH), server.requests, "pushed after the debounce, without a pull")

            // Fifteen minutes of playback, progress written every ten seconds.
            phone.engine.setPlaying(true)
            server.requests.clear()
            for (second in 10..900 step 10) {
                phone.data.library.recordProgress(profile.id, "tt0111161", "tt0111161", second * 1_000L, 8_520_000)
                advanceTimeBy(10_000)
            }
            assertTrue(pushes() in 2..4, "about one push every five minutes, got ${pushes()}")
            assertTrue(pulls() <= 3, "only the periodic pulls, got ${pulls()}")

            // Pause: the session flushes at once, a push without a pull.
            phone.data.library.recordProgress(profile.id, "tt0111161", "tt0111161", 905_000, 8_520_000)
            phone.engine.setPlaying(false)
            server.requests.clear()
            phone.engine.flushNow()
            advanceTimeBy(100)
            assertEquals(listOf(PUSH), server.requests)
            assertEquals(905_000, phone.data.library.item(profile.id, "tt0111161")!!.state.timeOffset)
            assertTrue(phone.data.sync.dirtyRecords(null, 10).isEmpty())
            assertEquals(SyncStatus.Kind.Idle, phone.engine.status.value.kind)
        }
    }

    @Test
    fun inTheBackgroundNothingIsPulledWhilePendingChangesAndProgressStillPush() = runTest {
        // A server without notice streams: the foreground's own pulls are all there is (SyncNoticesTest has the rest).
        server.refuseEvents = HttpStatusCode.NotFound
        val (phone, tv) = pair()
        phone.use {
            tv.close()
            server.requests.clear()
            val profile = phone.data.profiles.create("Wednesday", null)
            phone.data.library.upsertFromMeta(profile.id, movie())
            phone.engine.start()
            runCurrent()
            assertEquals(listOf(PUSH), server.requests, "started in the background: what was pending pushed, nothing pulled")
            phone.engine.onForeground()
            runCurrent()
            assertEquals(1, pulls(), "the foreground pulls at once")

            // A change, then the app leaves the foreground before the debounce: pushed at once.
            phone.data.library.addToLibrary(profile.id, series())
            runCurrent()
            server.requests.clear()
            phone.engine.onBackground()
            runCurrent()
            assertEquals(listOf(PUSH), server.requests)

            // An hour in the background: no idle sync.
            advanceTimeBy(60 * 60_000)
            assertEquals(listOf(PUSH), server.requests)

            // Playing on in the background (audio, picture in picture): progress pushes on the playing interval, and the
            // pause at once, all without pulls.
            server.requests.clear()
            phone.engine.setPlaying(true)
            for (second in 10..600 step 10) {
                phone.data.library.recordProgress(profile.id, "tt0111161", "tt0111161", second * 1_000L, 8_520_000)
                advanceTimeBy(10_000)
            }
            phone.engine.setPlaying(false)
            phone.engine.flushNow()
            advanceTimeBy(100)
            assertTrue(pushes() in 2..3, "about one push every five minutes, then the pause's, got ${server.requests}")
            assertEquals(0, pulls())
            assertTrue(phone.data.sync.dirtyRecords(null, 10).isEmpty())

            // Back in the foreground: a pull at once; back again within a minute of it: none; then every five minutes.
            server.requests.clear()
            phone.engine.onForeground()
            runCurrent()
            assertEquals(1, pulls())
            phone.engine.onBackground()
            advanceTimeBy(30_000)
            phone.engine.onForeground()
            runCurrent()
            assertEquals(1, pulls(), "a quick return doesn't pull")
            advanceTimeBy(5 * 60_000)
            assertEquals(2, pulls(), "the periodic pull")
        }
    }

    @Test
    fun theEngineFollowsTheAccountAndRetriesAfterRateLimits() = runTest {
        val (phone, tv) = pair(SyncEngineConfig(minRetryMillis = 5_000))
        phone.use {
            tv.close()
            phone.engine.start()
            runCurrent()
            assertEquals(SyncStatus.Kind.Idle, phone.engine.status.value.kind)

            phone.data.profiles.create("Wednesday", null)
            server.rateLimitNext = 1
            server.rateLimitSeconds = 90
            advanceTimeBy(2_500)
            assertEquals(SyncStatus.Kind.Error(SyncStatus.Failure.RATE_LIMITED), phone.engine.status.value.kind)
            val requestsWhileLimited = server.requests.size
            advanceTimeBy(87_000)
            assertEquals(requestsWhileLimited, server.requests.size, "nothing is sent before Retry-After")
            // The retry comes between Retry-After and twice it.
            advanceTimeBy(95_000)
            assertEquals(SyncStatus.Kind.Idle, phone.engine.status.value.kind)
            assertTrue(phone.data.sync.dirtyRecords(null, 10).isEmpty())

            phone.account.signOut()
            runCurrent()
            assertEquals(SyncStatus.Kind.SignedOut, phone.engine.status.value.kind)
            assertIs<AccountState.SignedOut>(phone.account.state.value)
        }
    }

    @Test
    fun aPushTheServerRefusesWaitsForSyncNowOrAForegroundThatPullsNotATimer() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.close()
            phone.engine.start()
            phone.engine.onForeground()
            runCurrent()
            server.requests.clear()

            val profile = phone.data.profiles.create("Wednesday", null)
            server.refusePushes = ApiErrorCode.QUOTA_EXCEEDED
            advanceTimeBy(2_500)
            assertEquals(listOf(PUSH), server.requests)
            assertEquals(SyncStatus.Kind.Error(SyncStatus.Failure.QUOTA_EXCEEDED), phone.engine.status.value.kind)

            // An hour in the foreground: the periodic pulls go on, the refused push isn't sent again, and a newer write
            // (flushed at once, as a pause does) queues behind it.
            phone.data.library.addToLibrary(profile.id, movie())
            phone.engine.flushNow()
            advanceTimeBy(60 * 60_000)
            assertEquals(1, pushes())
            assertTrue(pulls() >= 11, "the periodic pulls, got ${pulls()}")
            assertEquals(SyncStatus.Kind.Error(SyncStatus.Failure.QUOTA_EXCEEDED), phone.engine.status.value.kind)

            // Sync now sends it again; refused again, it stops again, leaving the foreground included.
            server.refusePushes = ApiErrorCode.PAYLOAD_TOO_LARGE
            assertEquals(SyncStatus.Kind.Error(SyncStatus.Failure.TOO_LARGE), phone.engine.syncNow().kind)
            assertEquals(2, pushes())
            phone.engine.onBackground()
            advanceTimeBy(60_000)
            assertEquals(2, pushes())

            // Back in the foreground a minute after the last pull: the pull sends it again, and the server takes it.
            server.refusePushes = null
            phone.engine.onForeground()
            runCurrent()
            assertEquals(3, pushes())
            assertEquals(SyncStatus.Kind.Idle, phone.engine.status.value.kind)
            assertTrue(phone.data.sync.dirtyRecords(null, 10).isEmpty())
        }
    }

    @Test
    fun recordsRefusedForClockDriftArePulledAgainUntilTheClocksAgreeAndTheStatusSaysSoMeanwhile() = runTest {
        val (phone, tv) = pair()
        val laptop = laptopAhead()
        phone.use { tv.use { laptop.use {
            laptop.data.profiles.create("Ahead", null)
            laptop.sync()
            // The phone refuses the laptop's profile too; its own goes to the server after it.
            phone.data.profiles.create("Wednesday", null)
            assertEquals(SyncStatus.Kind.Error(SyncStatus.Failure.CLOCK_WRONG), phone.engine.syncNow().kind)

            // The TV refuses the laptop's profile but takes the phone's, and keeps saying a clock is wrong.
            assertEquals(SyncStatus.Kind.Error(SyncStatus.Failure.CLOCK_WRONG), tv.engine.syncNow().kind)
            assertEquals(listOf("Wednesday"), tv.data.profiles.profiles().map { it.name })
            assertEquals(SyncStatus.Kind.Error(SyncStatus.Failure.CLOCK_WRONG), tv.engine.syncNow().kind)

            // Real time catches up with the laptop's clock: the next pull takes the record it refused.
            advanceTimeBy(DAY_MILLIS + 60_000)
            tv.sync()
            assertEquals(listOf("Ahead", "Wednesday"), tv.data.profiles.profiles().map { it.name }.sorted())
        } } }
    }

    @Test
    fun aStaleWriteWhoseCurrentRecordIsRefusedIsPushedOncePerCycle() = runTest {
        val (phone, tv) = pair()
        val laptop = laptopAhead()
        phone.use { tv.use { laptop.use {
            val profile = phone.data.profiles.create("Wednesday", null)
            phone.sync()
            tv.sync()
            laptop.sync()
            // The laptop renames the profile; the TV, which refuses that write, renames it too.
            laptop.data.profiles.rename(profile.id, "Ahead")
            laptop.sync()
            tv.data.profiles.rename(profile.id, "Behind")
            server.requests.clear()

            assertEquals(SyncStatus.Kind.Error(SyncStatus.Failure.CLOCK_WRONG), tv.engine.syncNow().kind)
            assertEquals(1, pushes(), "the stale write isn't sent again in the cycle")
            assertEquals(listOf("profile/${profile.id}"), tv.data.sync.dirtyRecords(null, 10).map { it.recordId })
        } } }
    }

    @Test
    fun aRecordThatStaysLocalDoesntHoldBackThePushOfLaterChanges() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.close()
            val profile = phone.data.profiles.create("Wednesday", null)
            phone.engine.start()
            phone.engine.onForeground()
            runCurrent()
            // An item whose id is over the server's limit stays dirty, and local.
            phone.data.library.addToLibrary(profile.id, movie("tt" + "1".repeat(SyncProtocol.MAX_RECORD_ID_LENGTH)))
            advanceTimeBy(2_500)
            assertEquals(SyncStatus.Kind.Error(SyncStatus.Failure.TOO_LARGE), phone.engine.status.value.kind)
            server.requests.clear()

            phone.data.library.addToLibrary(profile.id, series())
            advanceTimeBy(2_500)
            assertEquals(listOf(PUSH), server.requests, "the later change pushed after the debounce")
            assertTrue("library/${profile.id}/tt0903747" in server.account().records)
        }
    }

    @Test
    fun anAddonListOverTheLimitStaysLocalAndTheStatusNamesTheAddons() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.close()
            val profile = phone.data.profiles.create("Wednesday", null)
            phone.sync()
            // Manifests of text that doesn't compress: the packed list is over the limit.
            val random = Random(7)
            val manifests = List(3) { "{\"id\":\"a$it\",\"pad\":\"${CharArray(80_000) { "abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)] }.concatToString()}\"}" }
            phone.data.addons.replaceAll(profile.id, manifests.mapIndexed { i, manifest -> AddonDescriptor("https://a$i.example/manifest.json", manifest) })

            val status = phone.engine.syncNow()
            assertEquals(SyncStatus.Kind.Error(SyncStatus.Failure.ADDONS_TOO_LARGE), status.kind)
            assertEquals(T.account.status.failed.addonsTooLarge, status.text)
            assertEquals(listOf("addons/${profile.id}"), phone.data.sync.dirtyRecords(null, 10).map { it.recordId })
            assertTrue("addons/${profile.id}" !in server.account().records)
        }
    }

    @Test
    fun streamPicksTravelWithTheirOutcomesAndGoWithTheirProfile() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.use {
                val profile = phone.data.profiles.create("Wednesday", null)
                val shown = listOf(PickedStream(ResolutionBadge.UHD_2160, videoCodec = VideoCodecBadge.HEVC, sizeBytes = 8_000_000_000), PickedStream(ResolutionBadge.FHD_1080))
                val context = PickContext(DeviceClass.HANDHELD, metered = true, hdrDisplay = false, dolbyVisionDisplay = false, hardwareCodecs = setOf(VideoCodecBadge.AVC))
                phone.data.streamPicks.record(profile.id, StreamPick("k1", "tt0111161", "tt0111161", 8520, 0, context, PickSource.LIST, shown, 1, null, firstRow = 2))
                phone.sync()
                tv.sync()
                val arrived = tv.data.streamPicks.picks(profile.id).single()
                assertEquals(phone.data.streamPicks.picks(profile.id).single(), arrived)
                assertEquals(shown, arrived.candidates)
                assertEquals(context, arrived.context, "the phone's, not the TV's")

                tv.data.streamPicks.setOutcome("k1", PickOutcome.GOOD)
                tv.sync()
                phone.sync()
                assertEquals(PickOutcome.GOOD, phone.data.streamPicks.picks(profile.id).single().outcome)

                phone.data.profiles.delete(profile.id)
                phone.sync()
                tv.sync()
                assertEquals(emptyList(), tv.data.streamPicks.picks(profile.id))
                assertTrue(server.account().records.getValue(RecordKey.StreamPick(profile.id, "k1").recordId()).deleted)
                assertEquals(emptyList(), tv.data.sync.dirtyRecords(null, 10), "the pick's tombstone came before its profile's: nothing to write here")
            }
        }
    }

    /** What an older build sees of a record kind added after it, here `zz-future`. */
    @Test
    fun aRecordOfAKindThisBuildDoesntKnowIsSkippedAndThePullMovesPastIt() = runTest {
        val (phone, tv) = pair()
        phone.use {
            tv.use {
                val profile = phone.data.profiles.create("Wednesday", null)
                phone.sync()
                val account = server.account()
                val recordId = "zz-future/${profile.id}"
                val payload = JsonObject(
                    mapOf(
                        "key" to JsonObject(mapOf("kind" to JsonPrimitive("zz-future"), "profileId" to JsonPrimitive(profile.id))),
                        "hlc" to account.records.getValue("profile/${profile.id}").payload.getValue("hlc"),
                        "body" to JsonObject(mapOf("kind" to JsonPrimitive("zz-future"), "anything" to JsonPrimitive(1))),
                        "format" to JsonPrimitive(1),
                    ),
                )
                account.version += 1
                account.records[recordId] = RecordChange(recordId, account.version, false, payload, 0)
                phone.data.library.addToLibrary(profile.id, movie())
                phone.sync()

                tv.sync()
                assertEquals(listOf("Wednesday"), tv.data.profiles.profiles().map { it.name })
                assertNotNull(tv.data.library.item(profile.id, "tt0111161"), "the record after it applied")
                assertEquals(account.version, tv.data.syncState.pullCursor(account.id), "the cursor moved past it")
            }
        }
    }

    private companion object {
        const val PULL = "GET ${SyncPaths.SYNC_PULL}"
        const val PUSH = "POST ${SyncPaths.SYNC_PUSH}"
        const val DAY_MILLIS = 86_400_000L
    }
}
