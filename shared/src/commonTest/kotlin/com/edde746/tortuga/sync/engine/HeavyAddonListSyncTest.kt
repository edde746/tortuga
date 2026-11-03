package com.edde746.tortuga.sync.engine

import com.edde746.tortuga.data.addon.AddonDescriptor
import com.edde746.tortuga.data.sync.RecordKey
import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.DeviceLinkOutcome
import com.edde746.tortuga.sync.account.SignInResult
import com.edde746.tortuga.sync.api.SyncProtocol
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.TimeSource

/**
 * A heavy profile's addon list, `fixtures/addon-lists/heavy` (`index.json`: each addon's transport URL and manifest
 * file, in the profile's order): fifteen addons, among them a configured stream addon (its long transport URL and its
 * manifest's shape, every value invented), AIOMetadata split into four installs of about 43 KB each (its catalog
 * definitions and option lists; Stremio refuses a larger manifest, so a large catalog set is installed in parts), an
 * invented stream addon, and Cinemeta, OpenSubtitles and eight community addons as they serve their manifests.
 * Unpacked, its record is 287 KiB, over
 * [SyncProtocol.MAX_RECORD_BYTES].
 */
internal fun heavyAddonList(): List<AddonDescriptor> =
    Json.decodeFromString<List<HeavyAddon>>(FixtureCorpus.text("addon-lists/heavy/index.json"))
        .map { AddonDescriptor(it.transportUrl, FixtureCorpus.text(it.manifest)) }

@Serializable
private class HeavyAddon(val transportUrl: String, val manifest: String)

/** [heavyAddonList] syncs to the account's other devices. The sizes are printed. */
class HeavyAddonListSyncTest {
    private val server = FakeSyncServer()

    private val heavy = heavyAddonList()

    @Test
    fun aHeavyProfilesAddonListSyncsToAnotherDevice() = runTest {
        val phone = TestDevice("Phone", server, this, backgroundScope, MemorySecretStore())
        phone.account.restore()
        assertEquals(SignInResult.NEW_ACCOUNT, phone.account.signIn(server.serverUrl) { TestDevice.credential() })
        val tv = TestDevice("TV", server, this, backgroundScope, MemorySecretStore())
        tv.account.restore()
        val pending = tv.account.startDeviceLink(server.serverUrl)
        val linked = async { pending.awaitResult() }
        phone.account.claimDeviceLink(pending.code).approve()
        assertEquals(DeviceLinkOutcome.LINKED, linked.await())
        phone.use {
            tv.use {
                val profile = phone.data.profiles.create("Wednesday", null)
                phone.data.addons.replaceAll(profile.id, heavy)
                val started = TimeSource.Monotonic.markNow()
                val record = phone.data.sync.dirtyRecords(null, 10).single { it.recordId == RecordKey.AddonList(profile.id).recordId() }
                val encodedIn = started.elapsedNow()
                println(
                    "${heavy.size} addons, manifests ${heavy.sumOf { it.manifestJson.encodeToByteArray().size }} B: " +
                        "record ${record.payload.encodeToByteArray().size} B of ${SyncProtocol.MAX_RECORD_BYTES}, encoded in $encodedIn",
                )

                assertEquals(SyncStatus.Kind.Idle, phone.engine.syncNow().kind)
                assertEquals(SyncStatus.Kind.Idle, tv.engine.syncNow().kind)
                assertEquals(heavy, tv.data.addons.addons(profile.id).map { AddonDescriptor(it.transportUrl, it.manifestJson, it.enabled, it.watchActivity) })
            }
        }
    }
}
