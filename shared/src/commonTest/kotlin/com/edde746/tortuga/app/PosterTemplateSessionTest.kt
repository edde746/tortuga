package com.edde746.tortuga.app

import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.ProfileSettings
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.settings.SettingsSection
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The profile's poster URL template as the running app hands it to the screens. */
class PosterTemplateSessionTest {
    private val template = "https://posters.example/k3y-of-the-service/{imdb_id}.jpg"

    private suspend fun TestScope.app(kids: Boolean, platform: FakeAppPlatform = FakeAppPlatform()): AppSession {
        val graph = testGraph()
        val profile = graph.data.profiles.create("Alice", null, kids = kids)
        return AppSession(graph, profile.id, platform, start = null, profileAccount = null).also { runCurrent() }
    }

    @Test
    fun aTemplateSetOnTheProfileReachesTheScreensAndFollowsItsChanges() = runTest {
        val app = app(kids = false)
        assertNull(app.state.value.posterTemplate)
        app.graph.data.settings.set(app.profileId, ProfileSetting.PosterTemplate, template)
        runCurrent()
        assertEquals("https://posters.example/k3y-of-the-service/tt0133093.jpg", assertNotNull(app.state.value.posterTemplate).url("movie", "tt0133093", null))
        app.graph.data.settings.set(app.profileId, ProfileSetting.PosterTemplate, "")
        runCurrent()
        assertNull(app.state.value.posterTemplate)
        app.close()
    }

    @Test
    fun theDefaultIsEmpty() {
        assertEquals("", ProfileSettings.DEFAULT.posterTemplate)
    }

    @Test
    fun aKidsProfileIgnoresIt() = runTest {
        val app = app(kids = true)
        app.graph.data.settings.set(app.profileId, ProfileSetting.PosterTemplate, template)
        runCurrent()
        assertNull(app.state.value.posterTemplate)
        app.close()
    }

    @Test
    fun theTvHomeScreenKeepsTheAddonsPoster() = runTest {
        val home = FakeHomeScreen()
        val app = app(kids = false, FakeAppPlatform(homeScreen = home))
        app.graph.data.settings.set(app.profileId, ProfileSetting.PosterTemplate, template)
        val library = app.graph.data.library
        library.upsertFromMeta(app.profileId, MetaSnapshot("tt0903747", "series", "Breaking Bad", "https://addon.example/bb.jpg", "poster"))
        library.recordProgress(app.profileId, "tt0903747", "tt0903747:1:1", 600_000, 1_800_000)
        runCurrent()
        assertNotNull(app.state.value.posterTemplate)
        assertEquals(listOf("https://addon.example/bb.jpg"), home.published.last().map { it.posterUrl })
        app.close()
    }

    @Test
    fun appearanceShowsTheTemplateWithoutItsKey() = runTest {
        val app = app(kids = false)
        app.navigator.navigate(Route.Settings)
        val settings = assertNotNull(app.settingsSession())
        runCurrent()
        val sections = settings.main.state.value.sections.map { it.section }
        assertEquals(SettingsSection.APPEARANCE, sections[sections.indexOf(SettingsSection.PLAYBACK) + 1])
        val store = assertNotNull(settings.appearance)
        store.setPosterTemplate("  $template  ")
        runCurrent()
        assertEquals(template, app.graph.data.settings.settings(app.profileId).posterTemplate)
        val row = assertNotNull(store.state.value.posterTemplate)
        assertEquals(template, row.value)
        assertFalse("k3y-of-the-service" in row.valueLabel, row.valueLabel)
        assertNull(row.error)
        assertNotNull(store.posterTemplateError("posters.example/{imdb_id}"))
        app.close()
    }

    @Test
    fun aKidsProfilesAppearanceHasNoPosterTemplate() = runTest {
        val app = app(kids = true)
        app.navigator.navigate(Route.Settings)
        val settings = assertNotNull(app.settingsSession())
        runCurrent()
        assertTrue(SettingsSection.APPEARANCE in settings.main.state.value.sections.map { it.section })
        val store = assertNotNull(settings.appearance)
        runCurrent()
        assertNull(store.state.value.posterTemplate)
        val blur = store.state.value.blurUnwatched
        assertTrue(blur.enabled && !blur.checked, "the switch, off, takes changes once read: $blur")
        store.toggle("blurUnwatched", true)
        runCurrent()
        assertTrue(store.state.value.blurUnwatched.checked)
        assertTrue(app.graph.data.settings.settings(app.profileId).blurUnwatched)
        app.close()
    }
}
