@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.presentation.profiles

import com.edde746.tortuga.addons.DefaultAddons
import com.edde746.tortuga.app.AppGraph
import com.edde746.tortuga.app.BuildFlavor
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.profile.HomeCatalog
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.kids.AgeStep
import com.edde746.tortuga.kids.KidsTitle
import com.edde746.tortuga.playback.session.SyncTrigger
import com.edde746.tortuga.presentation.account.AccountDestination
import com.edde746.tortuga.presentation.account.AccountTestApp
import com.edde746.tortuga.presentation.account.await
import com.edde746.tortuga.presentation.account.awaitDestination
import com.edde746.tortuga.secrets.MemorySecretStore
import com.edde746.tortuga.strings.T
import com.edde746.tortuga.strings.isolate
import com.edde746.tortuga.sync.FakeSyncServer
import com.edde746.tortuga.sync.TestDevice
import com.edde746.tortuga.sync.account.SyncAccount
import com.edde746.tortuga.sync.account.SyncApi
import com.edde746.tortuga.sync.api.DeviceDescriptor
import com.edde746.tortuga.sync.api.DevicePlatform
import com.edde746.tortuga.sync.engine.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Creating, editing and deleting profiles. */
class ProfileEditorStoreTest {
    private val server = FakeSyncServer()

    private class RecordingHost : ProfileEditorHost {
        var closed = 0
        val deleted = ArrayList<String>()
        override fun closeEditor() {
            closed++
        }
        override fun profileDeleted(profileId: String) {
            deleted += profileId
        }
    }

    private suspend fun TestScope.ready(block: suspend (AccountTestApp) -> Unit) {
        AccountTestApp(server, this).use { app ->
            app.gate.awaitDestination(AccountDestination.SignIn)
            app.account.signIn(server.serverUrl) { TestDevice.credential() }
            app.gate.awaitDestination(AccountDestination.Profiles)
            block(app)
        }
    }

    private fun TestScope.editor(app: AccountTestApp, profileId: String?, host: ProfileEditorHost, fetchAgeRatings: () -> Unit = {}) =
        editor(app.data, profileId, host, fetchAgeRatings)

    private fun TestScope.editor(
        data: LocalData,
        profileId: String?,
        host: ProfileEditorHost,
        fetchAgeRatings: () -> Unit = {},
    ) = ProfileEditorStore(
        profileId,
        data.profiles,
        data.settings,
        { name, avatar, kids, homeCatalogs -> data.profiles.create(name, avatar, kids, homeCatalogs) },
        { emptyList() },
        fetchAgeRatings,
        host,
        backgroundScope,
    )

    private fun ProfileEditorStore.type(pin: String) = pin.forEach { pinDigit(it.digitToInt()) }

    @Test
    fun aNewProfileGetsAFreeAvatarAValidNameAndAConfirmedPin() = runTest {
        ready { app ->
            app.data.profiles.create("First", "graphite")
            val host = RecordingHost()
            val store = editor(app, null, host)
            val fresh = store.state.await { it.avatar.id != "graphite" }
            assertTrue(fresh.creating)
            assertEquals(T.profiles.editor.newTitle, fresh.title)
            assertEquals("slate", fresh.avatar.id)
            assertEquals(listOf("slate"), fresh.avatars.filter { it.selected }.map { it.avatar.id })
            assertFalse(fresh.canSave)
            assertNull(fresh.nameError)
            assertFalse(fresh.canDelete)

            // A blank name says so only once saving is tried; a long one at once.
            store.save()
            assertEquals(T.profiles.editor.nameRequired, store.state.value.nameError)
            store.setName("x".repeat(ProfileEditorStore.MAX_NAME_LENGTH + 1))
            assertEquals(T.profiles.editor.nameTooLong(ProfileEditorStore.MAX_NAME_LENGTH), store.state.value.nameError)
            store.setName("  wednesday ")
            assertNull(store.state.value.nameError)
            assertEquals("W", store.state.value.initial)
            store.selectAvatar("moss")
            assertEquals("moss", store.state.value.avatar.id)

            // The PIN has to be typed twice alike.
            store.startPinSetup()
            assertEquals(T.profiles.editor.pinEnter, store.state.value.pinSetup?.title)
            store.type("1357")
            assertEquals(PinSetupStep.CONFIRM, store.state.value.pinSetup?.step)
            store.type("1358")
            val mismatch = assertNotNull(store.state.value.pinSetup)
            assertEquals(PinSetupStep.ENTER, mismatch.step)
            assertEquals(T.profiles.editor.pinMismatch, mismatch.error)
            assertFalse(store.state.value.hasPin)
            store.type("1357")
            store.type("1357")
            assertNull(store.state.value.pinSetup)
            assertTrue(store.state.value.hasPin)
            assertEquals(T.profiles.editor.pinOn, store.state.value.pinStatus)

            assertTrue(store.state.value.canSave)
            store.save()
            runCurrent()
            assertEquals(1, host.closed)
            val created = app.data.profiles.profiles().single { it.name == "wednesday" }
            assertEquals("moss", created.avatar)
            assertTrue(app.data.profiles.verifyPin(created.id, "1357"))
        }
    }

    @Test
    fun editsApplyOnSaveAndDeletingAsksFirst() = runTest {
        ready { app ->
            val profile = app.data.profiles.create("Kids", "sea")
            app.data.profiles.setPin(profile.id, "1234")
            val host = RecordingHost()
            val store = editor(app, profile.id, host)
            val loaded = store.state.await { it.loaded }
            assertEquals("Kids", loaded.name)
            assertEquals("sea", loaded.avatar.id)
            assertTrue(loaded.hasPin)
            assertTrue(loaded.canDelete)

            store.setName("Children")
            store.selectAvatar("sand")
            store.removePin()
            assertFalse(store.state.value.hasPin)
            assertEquals(T.profiles.editor.pinOff, store.state.value.pinStatus)
            // Nothing changes before saving.
            runCurrent()
            assertEquals("Kids", app.data.profiles.profile(profile.id)?.name)
            store.save()
            runCurrent()
            assertEquals(1, host.closed)
            val saved = assertNotNull(app.data.profiles.profile(profile.id))
            assertEquals("Children", saved.name)
            assertEquals("sand", saved.avatar)
            assertFalse(saved.hasPin)

            val deleting = editor(app, profile.id, host)
            deleting.state.await { it.loaded }
            deleting.confirmDelete()
            runCurrent()
            assertNotNull(app.data.profiles.profile(profile.id))
            deleting.requestDelete()
            assertEquals(T.profiles.editor.deleteConfirm(isolate("Children")), deleting.state.value.deleteConfirmation)
            deleting.cancelDelete()
            assertNull(deleting.state.value.deleteConfirmation)
            deleting.requestDelete()
            deleting.confirmDelete()
            runCurrent()
            assertEquals(listOf(profile.id), host.deleted)
            assertNull(app.data.profiles.profile(profile.id))
        }
    }

    @Test
    fun aRenameKeepsAnAvatarThisBuildDoesntKnowAndNoneStaysNone() = runTest {
        ready { app ->
            // A newer build's avatar, and a profile that never picked one.
            val newer = app.data.profiles.create("Kids", "nebula")
            val plain = app.data.profiles.create("Guest", null)
            for ((profile, renamed) in listOf(newer to "Children", plain to "Visitor")) {
                val store = editor(app, profile.id, RecordingHost())
                assertEquals(ProfileAvatars.forProfile(profile.avatar, profile.id), store.state.await { it.loaded }.avatar, "a substitute shows")
                store.setName(renamed)
                store.save()
                runCurrent()
                val saved = assertNotNull(app.data.profiles.profile(profile.id))
                assertEquals(renamed, saved.name)
                assertEquals(profile.avatar, saved.avatar, "the stored avatar is left as it was")
            }
        }
    }

    @Test
    fun aProfileDeletedElsewhereClosesItsEditor() = runTest {
        ready { app ->
            val profile = app.data.profiles.create("Guest", null)
            val host = RecordingHost()
            val store = editor(app, profile.id, host)
            store.state.await { it.loaded }
            app.data.profiles.delete(profile.id)
            runCurrent()
            assertEquals(listOf(profile.id), host.deleted)
        }
    }

    @Test
    fun theAppGraphsEditorGivesANewProfileTheDefaultAddons() = runTest {
        val secrets = MemorySecretStore()
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val device = DeviceDescriptor("Desktop", DevicePlatform.WINDOWS)
        val graph = AppGraph(inMemoryDatabase(), secrets, SyncTrigger.None, BuildFlavor.SIDELOADED, device, "test", server.client(), scope)
        graph.use {
            val api = SyncApi(server.client(StandardTestDispatcher(testScheduler)))
            val account = SyncAccount(secrets, graph.data, device, api, { FakeSyncServer.NOW }, 2_000)
            account.restore()
            account.signIn(server.serverUrl) { TestDevice.credential() }
            val host = RecordingHost()
            val store = graph.profileEditorStore(null, host)
            store.setName("Wednesday")
            store.save()
            // The graph's database runs on real threads.
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { store.state.await { !it.saving && host.closed == 1 } }
            }
            val profile = graph.data.profiles.profiles().single()
            assertTrue(graph.data.addons.addons(profile.id).isNotEmpty())
        }
    }

    @Test
    fun turningTheKidsSwitchOnFetchesTheAgeDatabase() = runTest {
        ready { app ->
            val parent = app.data.profiles.create("Parent", null)
            app.data.profiles.setPin(parent.id, "1234")
            var fetches = 0
            val store = editor(app, null, RecordingHost(), fetchAgeRatings = { fetches++ })
            store.setName("Mia")
            store.state.await { it.kids.canSwitch }
            store.setKids(true)
            assertEquals(1, fetches)
            store.setKids(true)
            assertEquals(1, fetches, "once per turn-on")
            store.setKids(false)
            assertEquals(1, fetches, "turning it off fetches nothing")
            store.setKids(true)
            assertEquals(2, fetches)
        }
    }

    @Test
    fun aKidsProfileNeedsAnotherProfileThatIsntOneWithAPin() = runTest {
        ready { app ->
            val store = editor(app, null, RecordingHost())
            store.setName("Mia")
            val alone = store.state.await { it.loaded && it.kids.blockedReason != null }.kids
            assertEquals(T.profiles.editor.kids.needsParent, alone.blockedReason)
            assertFalse(alone.canSwitch)
            store.setKids(true)
            assertFalse(store.state.value.kids.enabled, "refused without an adult PIN")

            // A profile without a PIN, or a kids profile with one, can't let a child leave.
            val parent = app.data.profiles.create("Parent", null)
            val older = app.data.profiles.create("Older kid", null, kids = true, homeCatalogs = HomeCatalog.family)
            app.data.profiles.setPin(older.id, "1111")
            runCurrent()
            store.setKids(true)
            assertFalse(store.state.value.kids.enabled)

            app.data.profiles.setPin(parent.id, "1234")
            val ready = store.state.await { it.kids.blockedReason == null }.kids
            assertTrue(ready.canSwitch)
            store.setKids(true)
            val on = store.state.value
            assertTrue(on.kids.enabled)
            assertEquals(
                listOf(T.profiles.editor.kids.familyMovies to true, T.profiles.editor.kids.familySeries to true),
                on.kids.homeCatalogs.take(2).map { it.title to it.selected },
                "the family rows come picked",
            )
            assertTrue(on.kids.homeCatalogs.drop(2).isNotEmpty() && on.kids.homeCatalogs.drop(2).none { it.selected })
            assertEquals(listOf(AgeStep.KIDS), on.kids.caps.filter { it.selected }.map { it.step }, "Kids is the default cap")
            assertFalse(on.kids.allowUnrated)
            assertTrue(on.canSave)

            // The parent's PIN removed elsewhere: the new kids profile isn't saved, and the switch still turns off.
            app.data.profiles.setPin(parent.id, null)
            val blocked = store.state.await { it.kids.blockedReason != null }
            assertEquals(T.profiles.editor.kids.needsParent, blocked.kids.blockedReason)
            assertFalse(blocked.canSave)
            assertTrue(blocked.kids.canSwitch)
            store.setKids(false)
            assertTrue(store.state.value.canSave)

            // A profile that exists already can't become one.
            val existing = editor(app, parent.id, RecordingHost())
            val loaded = existing.state.await { it.loaded }.kids
            assertEquals(T.profiles.editor.kids.newOnly, loaded.blockedReason)
            assertFalse(loaded.canSwitch)
            existing.setKids(true)
            assertFalse(existing.state.value.kids.enabled)
        }
    }

    @Test
    fun aKidsProfileSavesTheHomeRowsPickedOrNone() = runTest {
        ready { app ->
            val parent = app.data.profiles.create("Parent", null)
            app.data.profiles.setPin(parent.id, "1234")
            val host = RecordingHost()
            val store = editor(app, null, host)
            store.setName("Mia")
            store.state.await { it.kids.canSwitch }
            store.setKids(true)

            store.setHomeCatalog(0, false)
            store.setHomeCatalog(2, true)
            assertEquals(listOf(false, true, true), store.state.value.kids.homeCatalogs.take(3).map { it.selected })
            store.save()
            runCurrent()
            assertEquals(1, host.closed)
            val mia = app.data.profiles.profiles().single { it.name == "Mia" }
            assertTrue(mia.kids)
            assertEquals(
                listOf(HomeCatalog.family[1], HomeCatalog(DefaultAddons.CINEMETA_URL, "movie", "top")),
                mia.homeCatalogs,
                "Family series, then Popular movies, in the order picked",
            )

            // Home rows are optional: a kids profile without any is saved.
            val noRowsHost = RecordingHost()
            val noRows = editor(app, null, noRowsHost)
            noRows.setName("Noa")
            noRows.state.await { it.kids.canSwitch }
            noRows.setKids(true)
            noRows.setHomeCatalog(0, false)
            noRows.setHomeCatalog(1, false)
            val none = noRows.state.value
            assertTrue(none.kids.homeCatalogs.none { it.selected })
            assertTrue(none.canSave)
            noRows.save()
            runCurrent()
            assertEquals(1, noRowsHost.closed)
            val noa = app.data.profiles.profiles().single { it.name == "Noa" }
            assertTrue(noa.kids)
            assertEquals(emptyList(), noa.homeCatalogs)
        }
    }

    /**
     * The cap, unrated titles, Home rows and the titles taken off the allowed and blocked lists are saved and reach the
     * account's other devices; a title a parent allowed elsewhere while the editor was open stays allowed.
     */
    @Test
    fun aKidsProfileAndItsRatingSettingsReachTheAccountsOtherDevices() = runTest {
        val phone = TestDevice("Phone", server, this, backgroundScope)
        val tablet = TestDevice("Tablet", server, this, backgroundScope)
        phone.use {
            tablet.use {
                phone.account.restore()
                phone.account.signIn(server.serverUrl) { TestDevice.credential() }
                tablet.account.restore()
                tablet.account.signIn(server.serverUrl) { TestDevice.credential() }
                val parent = phone.data.profiles.create("Parent", null)
                phone.data.profiles.setPin(parent.id, "1234")

                val host = RecordingHost()
                val store = editor(phone.data, null, host)
                store.setName("Mia")
                store.state.await { it.kids.canSwitch }
                store.setKids(true)
                store.setKidsCap(AgeStep.LITTLE)
                store.setAllowUnrated(true)
                store.setHomeCatalog(2, true)
                val picked = store.state.value.kids
                assertEquals(listOf(AgeStep.LITTLE), picked.caps.filter { it.selected }.map { it.step })
                assertTrue(picked.allowUnrated)
                store.save()
                store.state.await { host.closed == 1 }
                val miaId = phone.data.profiles.profiles().single { it.name == "Mia" }.id

                // A parent allowed and blocked titles from their pages.
                val frozen = KidsTitle("tt2294629", "movie", "Frozen")
                val bluey = KidsTitle("tt7678620", "series", "Bluey")
                val jaws = KidsTitle("tt0073195", "movie", "Jaws")
                phone.data.settings.set(miaId, ProfileSetting.KidsAllowed, listOf(frozen, bluey))
                phone.data.settings.set(miaId, ProfileSetting.KidsBlocked, listOf(jaws))
                val editHost = RecordingHost()
                val edit = editor(phone.data, miaId, editHost)
                val lists = edit.state.await { it.loaded }.kids
                assertEquals(
                    listOf(KidsTitleRow(frozen.key, "Frozen", T.browse.type.movie, T.profiles.editor.kids.remove(isolate("Frozen")))),
                    lists.allowed.take(1),
                )
                assertEquals(listOf(jaws.key), lists.blocked.map { it.key })
                edit.removeAllowed(frozen.key)
                edit.removeBlocked(jaws.key)
                assertEquals(listOf(bluey.key), edit.state.value.kids.allowed.map { it.key })
                assertTrue(edit.state.value.kids.blocked.isEmpty())
                val moana = KidsTitle("tt3521164", "movie", "Moana")
                phone.data.settings.update(miaId, ProfileSetting.KidsAllowed) { it + moana }
                runCurrent()
                assertEquals(listOf(frozen, bluey, moana), phone.data.settings.settings(miaId).kidsAllowed, "nothing changes before saving")
                edit.save()
                edit.state.await { editHost.closed == 1 }
                assertEquals(SyncStatus.Kind.Idle, phone.engine.syncNow().kind)
                assertEquals(SyncStatus.Kind.Idle, tablet.engine.syncNow().kind)

                val mia = assertNotNull(tablet.data.profiles.profile(miaId))
                assertTrue(mia.kids)
                assertEquals(HomeCatalog.family + HomeCatalog(DefaultAddons.CINEMETA_URL, "movie", "top"), mia.homeCatalogs)
                val settings = tablet.data.settings.settings(miaId)
                assertEquals(AgeStep.LITTLE, settings.kidsCap)
                assertTrue(settings.kidsAllowUnrated)
                assertEquals(listOf(bluey, moana), settings.kidsAllowed)
                assertEquals(emptyList(), settings.kidsBlocked)
                // The tablet's editor shows it as a kids profile, which can be turned back into an adult one.
                val kids = editor(tablet.data, miaId, RecordingHost()).state.await { it.loaded }.kids
                assertTrue(kids.enabled)
                assertTrue(kids.canSwitch)
                assertEquals(listOf(true, true, true), kids.homeCatalogs.map { it.selected })
                assertEquals(listOf(AgeStep.LITTLE), kids.caps.filter { it.selected }.map { it.step })
                assertTrue(kids.allowUnrated)
                assertEquals(listOf("Bluey", "Moana"), kids.allowed.map { it.title })
            }
        }
    }
}
