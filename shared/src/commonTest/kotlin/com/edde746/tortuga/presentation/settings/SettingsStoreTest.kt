@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.settings

import com.edde746.tortuga.addons.AddonRegistry
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.mockClient
import com.edde746.tortuga.app.BundledText
import com.edde746.tortuga.app.ProfileAddonStore
import com.edde746.tortuga.app.addProfile
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.profile.Profile
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.api.DevicePlatform
import com.edde746.tortuga.sync.engine.SyncEngine
import com.edde746.tortuga.trackers.TrackerAccounts
import com.edde746.tortuga.trackers.TrackerFixture
import com.edde746.tortuga.trackers.TrackerService
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SettingsStoreTest {
    private val server = FakeSyncServer()

    /** The app around the store; its bundled resources are [bundled], by folder, and [read] lists the folders asked for. */
    private class Host(private val bundled: Map<String, List<BundledText>> = emptyMap()) : SettingsHost {
        val edited = ArrayList<String>()
        val opened = ArrayList<String>()
        val read = ArrayList<String>()
        override fun editProfile(profileId: String) {
            edited += profileId
        }
        override fun manageProfiles() = Unit
        override fun openUrl(url: String) {
            opened += url
        }
        override fun bundledTexts(folder: String): List<BundledText> {
            read += folder
            return bundled[folder].orEmpty()
        }
    }

    /** The profile's device; the account (for [TestDevice.engine]) is another install's. */
    private fun TestScope.localData() = LocalData(
        inMemoryDatabase(), { 1_790_000_000_000L },
        StandardTestDispatcher(testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
    )

    /**
     * The store of [profileId] (a new profile's without it), starting from [startProfile] as a session would give it;
     * [read] false returns it before it has read anything.
     */
    private suspend fun TestScope.store(
        data: LocalData,
        engine: SyncEngine?,
        trackers: TrackerAccounts? = null,
        profileId: String? = null,
        host: Host = Host(),
        offersDownloads: Boolean = false,
        offersData: Boolean = false,
        startProfile: Profile? = null,
        read: Boolean = true,
    ): SettingsStore {
        val profile = profileId ?: data.profiles.create("Alice", avatar = null).id
        val transport = AddonTransport(
            mockClient { respond("", HttpStatusCode.NotFound) },
            parseDispatcher = StandardTestDispatcher(testScheduler),
        )
        val registry = AddonRegistry(ProfileAddonStore(data.addons, profile), transport)
        return SettingsStore(
            profile, data.profiles, registry, engine, trackers, "1.0.0", DevicePlatform.LINUX, host, backgroundScope,
            offersDownloads = offersDownloads, offersData = offersData, startProfile = startProfile,
            io = StandardTestDispatcher(testScheduler),
        ).also { if (read) runCurrent() }
    }

    @Test
    fun withoutAnAccountTheAccountSectionIsLeftOut() = runTest {
        val data = localData()
        val store = store(data, engine = null)
        val state = store.state.value
        assertEquals(
            listOf(
                SettingsSection.PLAYBACK, SettingsSection.APPEARANCE, SettingsSection.DEVICE, SettingsSection.SUBTITLES, SettingsSection.ADDONS, SettingsSection.HOME,
                SettingsSection.ABOUT,
            ),
            state.sections.map { it.section },
        )
        assertEquals("Alice", state.profile?.name)
        store.release()
        data.close()
    }

    /** The card shows the avatar chosen in the editor and the initial the profile picker shows. */
    @Test
    fun theProfileCardShowsTheProfilesOwnAvatar() = runTest {
        val data = localData()
        val profile = data.profiles.create("🇸🇪 Kai", avatar = "moss")
        val store = store(data, engine = null, profileId = profile.id)
        val card = store.state.value.profile
        assertEquals("moss", card?.avatar?.id)
        assertEquals("🇸🇪", card?.initial)
        store.release()
        data.close()
    }

    @Test
    fun withAnAccountTheAccountSectionShowsTheSyncStatus() = runTest {
        TestDevice("Desktop", server, this, backgroundScope).use { device ->
            device.account.restore()
            val data = localData()
            val store = store(data, device.engine)
            val account = store.state.value.sections.single { it.section == SettingsSection.ACCOUNT }
            assertEquals(T.settings.sections.account, account.title)
            assertEquals(T.account.status.signedOut, account.summary)
            store.release()
            data.close()
        }
    }

    @Test
    fun theTrackersSectionIsOfferedOnlyWithAServiceAndSummarizesTheConnections() = runTest {
        val none = TrackerFixture(this, services = emptyList())
        none.data.addProfile(none.profileId)
        val without = store(none.data, engine = null, trackers = none.hub.accounts, profileId = none.profileId)
        assertFalse(SettingsSection.TRACKERS in without.state.value.sections.map { it.section }, "a build without registrations offers none")
        without.release()
        none.close()

        val f = TrackerFixture(this, listOf(TrackerService.TRAKT, TrackerService.SIMKL))
        f.data.addProfile(f.profileId)
        val store = store(f.data, engine = null, trackers = f.hub.accounts, profileId = f.profileId)
        fun trackers() = store.state.value.sections.single { it.section == SettingsSection.TRACKERS }
        assertEquals(
            listOf(
                SettingsSection.PLAYBACK, SettingsSection.APPEARANCE, SettingsSection.DEVICE, SettingsSection.SUBTITLES, SettingsSection.ADDONS, SettingsSection.HOME,
                SettingsSection.TRACKERS, SettingsSection.ABOUT,
            ),
            store.state.value.sections.map { it.section },
        )
        assertEquals(listOf("Trackers", "Scrobbling, watch history and lists"), listOf(trackers().title, trackers().summary))
        f.connect(TrackerService.SIMKL)
        f.connect(TrackerService.TRAKT)
        runCurrent()
        assertEquals("Connected to Trakt, Simkl", trackers().summary)
        store.release()
        f.close()
    }

    @Test
    fun aSectionOpensInPlace() = runTest {
        val data = localData()
        val store = store(data, engine = null)
        assertNull(store.state.value.section)
        store.open(SettingsSection.DEVICE)
        assertEquals(SettingsSection.DEVICE, store.state.value.section)
        store.open(SettingsSection.ADDONS)
        assertEquals(SettingsSection.ADDONS, store.state.value.section)
        store.open(SettingsSection.ACCOUNT)
        assertEquals(SettingsSection.ADDONS, store.state.value.section, "a section this session doesn't offer doesn't open")
        store.closeSection()
        assertNull(store.state.value.section)
        store.release()
        data.close()
    }

    /**
     * A notice's page: its name, licence and link at once, its texts once read from the app's resources (each file's
     * path and paragraphs), and its link still opening the project's page.
     */
    @Test
    fun aNoticesPageShowsItsBundledTextsOnceRead() = runTest {
        val data = localData()
        val host = Host(
            mapOf(
                "licenses/skiko" to listOf(
                    BundledText("LICENSE", "Apache License\n\nTERMS AND CONDITIONS"),
                    BundledText("NOTICE", "This project contains code from AOSP."),
                ),
            ),
        )
        val store = store(data, engine = null, host = host)
        val index = store.state.value.about.notices.indexOfFirst { it.name == "Skiko" }
        store.showNotice(index)
        val reading = assertNotNull(store.state.value.about.notice)
        assertEquals(NoticePage(index, "Skiko", "Apache-2.0", T.settings.about.noticeLink, texts = null), reading)
        runCurrent()
        assertEquals(listOf("licenses/skiko"), host.read)
        assertEquals(
            listOf(
                NoticeText("LICENSE", listOf("Apache License", "TERMS AND CONDITIONS")),
                NoticeText("NOTICE", listOf("This project contains code from AOSP.")),
            ),
            store.state.value.about.notice?.texts,
        )
        store.openNotice(index)
        assertEquals(listOf(ThirdPartyNotices.forPlatform(DevicePlatform.LINUX)[index].url), host.opened)
        assertEquals("Skiko", store.state.value.about.notice?.name, "the link leaves the page open")
        store.closeNotice()
        assertNull(store.state.value.about.notice)
        store.release()
        data.close()
    }

    /** A notice whose folder this build lacks has its licence and link only. */
    @Test
    fun aNoticeWithoutBundledTextsShowsItsLicenceAndLinkOnly() = runTest {
        val data = localData()
        val host = Host()
        val store = store(data, engine = null, host = host)
        store.showNotice(store.state.value.about.notices.indexOfFirst { it.name == "Skia" })
        runCurrent()
        assertEquals("Skia", store.state.value.about.notice?.name)
        assertEquals(emptyList(), store.state.value.about.notice?.texts)
        assertEquals(listOf("licenses/skia"), host.read)
        store.release()
        data.close()
    }

    /** Opening a section or going back to the list closes the page, and a read still under way publishes nothing. */
    @Test
    fun openingASectionClosesTheNoticesPage() = runTest {
        val data = localData()
        val host = Host(mapOf("licenses/skiko" to listOf(BundledText("LICENSE", "Apache License"))))
        val store = store(data, engine = null, host = host)
        val skiko = store.state.value.about.notices.indexOfFirst { it.name == "Skiko" }
        store.open(SettingsSection.ABOUT)
        store.showNotice(skiko)
        runCurrent()
        store.open(SettingsSection.ABOUT)
        assertEquals(SettingsSection.ABOUT, store.state.value.section)
        assertNull(store.state.value.about.notice, "About opens on its list")
        store.showNotice(skiko)
        store.closeSection()
        runCurrent()
        assertNull(store.state.value.about.notice, "the read that was under way doesn't bring the page back")
        store.release()
        data.close()
    }

    /** A kids profile's settings: playback, the display, subtitles and About only, and no Edit profile. */
    @Test
    fun aKidsProfilesSettingsKeepOnlyPlaybackTheDisplayAndSubtitles() = runTest {
        TestDevice("Desktop", server, this, backgroundScope).use { device ->
            device.account.restore()
            val data = localData()
            val mia = data.profiles.create("Mia", avatar = null, kids = true)
            val host = Host()
            val kids = store(data, device.engine, profileId = mia.id, host = host, offersDownloads = true, offersData = true)
            val state = kids.state.value
            assertEquals(
                listOf(SettingsSection.PLAYBACK, SettingsSection.APPEARANCE, SettingsSection.DEVICE, SettingsSection.SUBTITLES, SettingsSection.ABOUT),
                state.sections.map { it.section },
            )
            assertNull(state.profile?.editLabel)
            assertEquals(T.settings.profile.manage, state.profile?.manageLabel)
            kids.open(SettingsSection.ADDONS)
            assertNull(kids.state.value.section, "a cut section doesn't open")
            kids.editProfile()
            assertEquals(emptyList(), host.edited)
            kids.release()

            data.close()
        }
    }

    /**
     * A section asked for before the profile is read (a followed install link's Addons as Settings first opens) shows
     * once the profile is read and turns out not to be a kids profile, and not on a kids profile.
     */
    @Test
    fun aSectionAskedForBeforeTheProfileIsReadShowsOnceItIsUnlessTheProfileIsAKidsProfile() = runTest {
        val data = localData()
        val alice = data.profiles.create("Alice", avatar = null)
        val mia = data.profiles.create("Mia", avatar = null, kids = true)
        for ((profile, shown) in listOf(alice to SettingsSection.ADDONS, mia to null)) {
            val store = store(data, engine = null, profileId = profile.id, read = false)
            store.open(SettingsSection.ADDONS)
            runCurrent()
            assertEquals(shown, store.state.value.section, profile.name)
            store.release()
        }
        data.close()
    }

    /**
     * Started from the session's profile, a profile that isn't a kids profile has every section and its Edit profile
     * from the first state, before the store has read anything: nothing shows cut and then grows.
     */
    @Test
    fun theSessionsProfileGivesItsSectionsBeforeTheStoreReadsIt() = runTest {
        val data = localData()
        val alice = data.profiles.create("Alice", avatar = null)
        val store = store(data, engine = null, profileId = alice.id, offersDownloads = true, startProfile = alice, read = false)
        assertEquals(
            listOf(
                SettingsSection.PLAYBACK, SettingsSection.APPEARANCE, SettingsSection.DEVICE, SettingsSection.SUBTITLES, SettingsSection.DOWNLOADS,
                SettingsSection.ADDONS, SettingsSection.HOME, SettingsSection.ABOUT,
            ),
            store.state.value.sections.map { it.section },
        )
        assertEquals(T.settings.profile.edit, store.state.value.profile?.editLabel)
        store.release()
        data.close()
    }
}
