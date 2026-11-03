@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.edde746.tortuga.app

import com.edde746.tortuga.addons.DefaultAddons
import com.edde746.tortuga.addons.json
import com.edde746.tortuga.addons.mockClient
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.downloads.LocalVideo
import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.input.PlayerCommand
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.MetaItemBehaviorHints
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.PosterShape
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.playback.Track
import com.edde746.tortuga.playback.TrackKind
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.session.AutoPlayPick
import com.edde746.tortuga.playback.session.EPISODE_1_TRACKS
import com.edde746.tortuga.playback.session.EPISODE_2_TRACKS
import com.edde746.tortuga.presentation.addons.AddonInstallAction
import com.edde746.tortuga.presentation.addons.AddonInstallStep
import com.edde746.tortuga.presentation.board.MetaCard
import com.edde746.tortuga.presentation.discover.DiscoverStore
import com.edde746.tortuga.presentation.navigation.BackStackLayout
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.navigation.LibrarySection
import com.edde746.tortuga.presentation.navigation.Route
import com.edde746.tortuga.presentation.navigation.TopLevelDestination
import com.edde746.tortuga.presentation.player.EpisodeButtons
import com.edde746.tortuga.presentation.settings.SettingsSection
import com.edde746.tortuga.strings.T
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** One profile's running app: the playbacks it starts and ends, where its screens' links lead, and its end. */
class AppSessionTest {
    @Test
    fun playingAgainEndsTheCurrentPlaybackFirstInReleaseOrder() = runTest {
        val (app, platform) = app()
        app.playback.play(MOVIE_REQUEST)
        runCurrent()
        val first = assertNotNull(app.state.value.playback)
        assertSame(first.store, platform.playbacks.single().attached)
        assertEquals(listOf(Route.Board, Route.Player), app.navigator.backStack.value)
        first.store.dispatch(PlayerCommand.ToggleFullscreen)
        runCurrent()

        app.playback.play(EPISODE_1)
        runCurrent()
        assertEquals(
            listOf(
                "1: attached",
                "fullscreen true",
                // The first playback ends before the second one's engine exists.
                "fullscreen false",
                "1: controls hidden",
                "1: display restored",
                "1: platform released",
                "1: engine released",
                "2: attached",
            ),
            platform.log,
        )
        val second = assertNotNull(app.state.value.playback)
        assertSame(platform.playbacks[1].engine, second.engine)
        // Still one player on the stack.
        assertEquals(listOf(Route.Board, Route.Player), app.navigator.backStack.value)
    }

    @Test
    fun theDisplayStaysAwakeAsThePlayerSaysAndIsLetGoWhenThePlaybackEnds() = runTest {
        val (app, platform) = app()
        app.playback.play(MOVIE_REQUEST)
        runCurrent()
        val first = platform.playbacks.single()
        val store = assertNotNull(app.state.value.playback).store
        assertTrue(first.awake.first(), "awake as the player opens")
        assertEquals(store.state.value.keepAwake, first.awake.last(), "then as the player says")
        store.onVisibleChanged(false)
        runCurrent()
        assertFalse(first.awake.last(), "out of sight")
        store.onVisibleChanged(true)
        runCurrent()
        assertEquals(store.state.value.keepAwake, first.awake.last())

        // Replaced: whatever its player said last, the display may sleep, and nothing more comes from it.
        val before = first.awake.toList()
        app.playback.play(EPISODE_1)
        runCurrent()
        assertEquals(before + false, first.awake)
        val second = platform.playbacks[1]
        assertTrue(second.awake.first())

        // The app closing mid-play lets it sleep too.
        val playing = second.awake.toList()
        app.close()
        runCurrent()
        assertEquals(playing + false, second.awake)
    }

    @Test
    fun leavingThePlayerEndsThePlaybackAndClosingItAsTheOnlyScreenLeavesTheApp() = runTest {
        val (app, platform) = app()
        app.playback.play(MOVIE_REQUEST)
        runCurrent()
        assertNotNull(app.state.value.playback).store.dispatch(PlayerCommand.Close)
        runCurrent()
        assertEquals(listOf(Route.Board), app.navigator.backStack.value)
        assertNull(app.state.value.playback)
        assertTrue(platform.playbacks.single().scripted.released)
        assertEquals(0, platform.exits)

        // A development session's direct play: the player is the app's only screen.
        val (direct, directPlatform) = app(start = Route.Player)
        direct.playback.play(MOVIE_REQUEST)
        runCurrent()
        assertNotNull(direct.state.value.playback).store.dispatch(PlayerCommand.Close)
        assertEquals(1, directPlatform.exits)
    }

    @Test
    fun aStreamPlayedFromTheStreamsSheetTakesItsPlace() = runTest {
        val (app, _) = app()
        val detail = Route.Detail("movie", "tt2")
        app.navigator.navigate(detail)
        app.navigator.openStreams(MOVIE_REQUEST.meta, "tt2")
        app.playback.play(MOVIE_REQUEST)
        runCurrent()
        assertEquals(listOf(Route.Board, detail, Route.Player), app.navigator.backStack.value)
        // Back from the player returns to the screen the sheet was over, the sheet closed.
        app.navigator.back()
        runCurrent()
        assertEquals(listOf(Route.Board, detail), app.navigator.backStack.value)
        assertNull(app.state.value.playback)
    }

    @Test
    fun theNextEpisodeWithoutAStreamOpensItsStreamsSheetOverThePlayer() = runTest {
        val (app, platform) = app()
        app.playback.play(EPISODE_1)
        runCurrent()
        val first = assertNotNull(app.state.value.playback)
        // No installed addon and no stream history to pick episode 2's stream from.
        first.session.playNextEpisode()
        runCurrent()
        val streams = Route.Streams("series", "tt1", "tt1:1:2")
        assertEquals(BackStackLayout(listOf(Route.Board, Route.Player), sheet = streams), app.navigator.layout)
        assertSame(first, app.state.value.playback, "the player stays under the sheet")
        // The stream list's item came along with its route.
        app.streamsStore(streams).release()

        // A stream picked there plays in the same player.
        app.playback.play(EPISODE_1.copy(videoId = "tt1:1:2"))
        runCurrent()
        assertEquals(listOf(Route.Board, Route.Player), app.navigator.backStack.value)
        assertNotSame(first, assertNotNull(app.state.value.playback))
        assertTrue(platform.playbacks[0].scripted.released)
        assertEquals(2, platform.playbacks.size)
    }

    @Test
    fun theTrackPicksGoOnToTheTitlesLaterPlayersNotToAnotherTitle() = runTest {
        val (app, platform) = app()
        app.playback.play(downloaded("tt1:1:1"))
        runCurrent()
        openWith(platform.playbacks[0], EPISODE_1_TRACKS)
        assertNotNull(app.state.value.playback).store.dispatch(PlayerCommand.SelectTrack(TrackKind.SUBTITLE, 5))
        runCurrent()
        assertNotNull(app.state.value.playback).session.playNextEpisode()
        runCurrent()

        // Episode 2's stream picked in the Streams sheet over the player: a new session, the same subtitles.
        app.playback.play(downloaded("tt1:1:2"))
        runCurrent()
        assertEquals(2L, openWith(platform.playbacks[1], EPISODE_2_TRACKS), "French")

        // Another title played after leaving the player: its own languages choose.
        assertNotNull(app.state.value.playback).store.dispatch(PlayerCommand.Close)
        runCurrent()
        app.playback.play(MOVIE_REQUEST.copy(local = LocalVideo(2, "/videos/movie.mkv", 1000, null, null, emptyList(), MOVIE_REQUEST.stream)))
        runCurrent()
        assertEquals(4L, openWith(platform.playbacks[2], EPISODE_2_TRACKS), "English audio: its forced subtitles")

        // The series played again, as from Detail or Continue Watching: the binge goes on with its picks.
        assertNotNull(app.state.value.playback).store.dispatch(PlayerCommand.Close)
        runCurrent()
        app.playback.play(downloaded("tt1:1:1"))
        runCurrent()
        assertEquals(5L, openWith(platform.playbacks[3], EPISODE_1_TRACKS), "French")
    }

    /** [videoId] of the tests' series from its download, which plays without a stream to resolve. */
    private fun downloaded(videoId: String) =
        EPISODE_1.copy(videoId = videoId, local = LocalVideo(1, "/videos/$videoId.mkv", 1000, null, null, emptyList(), EPISODE_1.stream))

    /** Opens [playback]'s file with [tracks]; the subtitle track selected then. */
    private fun TestScope.openWith(playback: FakePlatformPlayback, tracks: List<Track>): Long? {
        playback.scripted.fileTracks = tracks
        playback.scripted.open(duration = 3000.0)
        runCurrent()
        return playback.scripted.state.tracks.firstOrNull { it.kind == TrackKind.SUBTITLE && it.selected }?.id
    }

    @Test
    fun closingTheNextEpisodesStreamsSheetUnpickedClosesThePlayer() = runTest {
        val (app, platform) = app()
        app.navigator.navigate(Route.Detail("series", "tt1"))
        app.playback.play(EPISODE_1)
        runCurrent()
        assertNotNull(app.state.value.playback).session.playNextEpisode()
        runCurrent()
        assertTrue(app.navigator.back())
        runCurrent()
        assertEquals(listOf(Route.Board, Route.Detail("series", "tt1")), app.navigator.backStack.value)
        assertNull(app.state.value.playback)
        assertTrue(platform.playbacks.single().scripted.released)
    }

    @Test
    fun thePlayersPreviousEpisodeWithoutAStreamOpensItsStreamsSheetOverThePlayer() = runTest {
        val (app, _) = app()
        app.playback.play(EPISODE_1.copy(videoId = "tt1:1:2"))
        runCurrent()
        val playback = assertNotNull(app.state.value.playback)
        assertEquals(EpisodeButtons(previous = true, next = false), playback.store.state.value.episodes, "the last episode before the specials")
        // No installed addon and no stream history to pick episode 1's stream from.
        playback.store.dispatch(PlayerCommand.PreviousEpisode)
        runCurrent()
        val streams = Route.Streams("series", "tt1", "tt1:1:1")
        assertEquals(BackStackLayout(listOf(Route.Board, Route.Player), sheet = streams), app.navigator.layout)
        assertSame(playback, app.state.value.playback, "the player stays under the sheet")
        app.streamsStore(streams).release()
    }

    @Test
    fun closingEndsThePlaybackAndTheScreensStoresOnceAndForAll() = runTest {
        val (app, platform) = app()
        var released = 0
        app.navigator.navigate(Route.Library)
        app.navigator.retain(Route.Library, "test", create = { Any() }, release = { released++ })
        app.editProfile("profile-1")
        app.playback.play(MOVIE_REQUEST)
        runCurrent()

        app.close()
        assertNull(app.state.value.playback)
        assertNull(app.state.value.overlay)
        assertTrue(platform.playbacks.single().scripted.released)
        assertEquals(1, released)

        app.close()
        app.playback.play(MOVIE_REQUEST)
        assertEquals(1, platform.playbacks.size, "a closed app starts no playback")
        assertEquals(1, platform.log.count { it.endsWith("engine released") })
    }

    @Test
    fun aMovieCardWhoseDefaultVideoIsItselfOpensPlainDetail() = runTest {
        val (app, _) = app()
        val board = app.boardStore()
        // Cinemeta names every movie's own id as its default video.
        board.openItem(card(MetaItemPreview(id = "tt0133093", type = "movie", name = "The Matrix", behaviorHints = MetaItemBehaviorHints(defaultVideoId = "tt0133093"))))
        assertEquals(Route.Detail("movie", "tt0133093"), app.navigator.current)
        board.openItem(card(MetaItemPreview(id = "tt1", type = "series", name = "Chernobyl")))
        assertEquals(Route.Detail("series", "tt1"), app.navigator.current)
        board.release()
    }

    @Test
    fun aChannelCardOpensTheStreamsOfTheDefaultVideoItNames() = runTest {
        val (app, _) = app()
        val board = app.boardStore()
        board.openItem(card(MetaItemPreview(id = "ch1", type = "channel", name = "A channel", behaviorHints = MetaItemBehaviorHints(defaultVideoId = "ch1:v2"))))
        assertEquals(Route.Detail("channel", "ch1", "ch1:v2"), app.navigator.current)
        board.release()
    }

    @Test
    fun aLiveCardOpensItsOwnStreams() = runTest {
        val (app, _) = app()
        val board = app.boardStore()
        board.openItem(card(MetaItemPreview(id = "tv1", type = "tv", name = "A station")))
        assertEquals(Route.Detail("tv", "tv1", "tv1"), app.navigator.current)
        board.openItem(card(MetaItemPreview(id = "ev1", type = "events", name = "A stream", behaviorHints = MetaItemBehaviorHints(isLive = true, defaultVideoId = "ev1:old"))))
        assertEquals(Route.Detail("events", "ev1", "ev1"), app.navigator.current)
        board.release()
    }

    /**
     * A Continue Watching item resumes its video as Detail's Resume does, from Home and from Library's Continue Watching
     * alike: with auto-play on, auto-play's pick plays, and the stream list opens only when nothing passes the filters
     * or the pick doesn't start, with the notice saying so; with auto-play off, the stream it last played from plays,
     * else the stream list opens.
     */
    @Test
    fun aContinueWatchingItemResumesAsDetailsResumeDoes() = runTest {
        val movie = "tt0111161"
        var playable = true
        val http = FakeHttp(this) { request ->
            val path = request.url.encodedPath
            when {
                path == "/meta/movie/$movie.json" -> json(FixtureCorpus.text("live/cinemeta/meta-movie-tt0111161.json"))
                path == "/stream/movie/$movie.json" -> json(
                    """{"streams":[
                        {"name":"Streams\n1080p","url":"https://streams.example.com/play/1080"},
                        {"name":"Streams\n720p","url":"https://streams.example.com/play/720"}
                    ]}""",
                )
                path.startsWith("/play/") && playable -> respond("")
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val graph = testGraph(http = http.client)
        val profile = "profile-1"
        val streams = "https://streams.example.com/manifest.json"
        graph.data.addons.install(profile, DefaultAddons.CINEMETA_URL, FixtureCorpus.text("live/cinemeta/manifest.json"))
        graph.data.addons.install(profile, streams, """{"id":"org.example.streams","version":"1.0.0","name":"Streams","types":["movie"],"resources":["stream"],"idPrefixes":["tt"]}""")
        graph.data.library.upsertFromMeta(profile, MetaSnapshot(movie, "movie", "The Shawshank Redemption", null, "poster"))
        graph.data.library.recordProgress(profile, movie, movie, 3_723_000, 8_520_000)
        val (app, _) = app(graph = graph)
        val board = app.boardStore()
        val library = app.continueWatchingStore()
        runCurrent()
        val card = board.state.value.continueWatching.single()
        val detail = Route.Detail("movie", movie, movie, videoAction = DetailVideoAction.PLAY)
        val list = Route.Streams("movie", movie, movie)

        /** What comes up once [open] has led to the Detail screen (built as its UI builds it) and that has settled. */
        suspend fun resume(mode: AutoPlayMode, open: () -> Unit): BackStackLayout {
            graph.data.settings.set(profile, ProfileSetting.StreamAutoPlay, mode)
            app.navigator.select(TopLevelDestination.BOARD)
            runCurrent()
            open()
            val store = app.detailStore(assertIs<Route.Detail>(app.navigator.current))
            runCurrent()
            store.release()
            return app.navigator.layout
        }
        fun playing() = assertNotNull(app.state.value.playback).session.info.value.stream

        // Auto-play off, nothing played before: the stream list, as Resume opens it.
        assertEquals(BackStackLayout(listOf(Route.Board, detail), sheet = list), resume(AutoPlayMode.OFF) { board.openContinueWatching(card) })
        assertNull(app.navigator.streamsNotice(list))

        // Auto-play on: its pick plays straight away, from Home and from Library alike.
        assertEquals(BackStackLayout(listOf(Route.Board, detail, Route.Player), sheet = null), resume(AutoPlayMode.FIRST_MATCH) { board.openContinueWatching(card) })
        assertEquals(AutoPlayPick.FIRST_MATCH, playing()?.autoPlay)
        assertEquals(BackStackLayout(listOf(Route.Board, detail, Route.Player), sheet = null), resume(AutoPlayMode.FIRST_MATCH) { library.open(movie) })
        assertEquals(AutoPlayPick.FIRST_MATCH, playing()?.autoPlay)

        // Nothing passes the filters: the list, saying so.
        graph.data.settings.set(profile, ProfileSetting.StreamIncludePattern, "remux")
        assertEquals(BackStackLayout(listOf(Route.Board, detail), sheet = list), resume(AutoPlayMode.FIRST_MATCH) { board.openContinueWatching(card) })
        assertEquals(T.streams.autoPlay.noMatch, app.navigator.streamsNotice(list))
        graph.data.settings.set(profile, ProfileSetting.StreamIncludePattern, "")

        // The pick doesn't start: the list over the player, saying so.
        playable = false
        assertEquals(BackStackLayout(listOf(Route.Board, detail, Route.Player), sheet = list), resume(AutoPlayMode.FIRST_MATCH) { board.openContinueWatching(card) })
        assertEquals(T.streams.autoPlay.failed, app.navigator.streamsNotice(list))
        playable = true

        // Auto-play off with a stream it last played from: that stream plays, as Resume plays it.
        graph.data.streamHistory.record(profile, movie, movie, """{"name":"Streams\n720p","url":"https://streams.example.com/play/720"}""", streams)
        assertEquals(BackStackLayout(listOf(Route.Board, detail, Route.Player), sheet = null), resume(AutoPlayMode.OFF) { library.open(movie) })
        assertNull(playing()?.autoPlay)
        board.release()
        library.release()
        app.close()
    }

    @Test
    fun linksOpenTheirScreens() = runTest {
        val (app, platform) = app()
        app.openLink(DeepLink.Detail("series", "tt1", "tt1:1:2"))
        assertEquals(Route.Detail("series", "tt1", "tt1:1:2"), app.navigator.current)
        app.openLink(DeepLink.Search("Emily Watson"))
        assertEquals(Route.Search, app.navigator.current)
        assertEquals("Emily Watson", app.navigator.takeSearchQuery())
        app.openLink(DeepLink.InstallAddon("https://addon.example/manifest.json".transportUrl()))
        // Settings opens on its Addons section, where the Settings tab and its Addons row lead.
        assertEquals(listOf<Route>(Route.Settings), app.navigator.backStack.value)
        assertEquals(SettingsSection.ADDONS, app.settingsSession()?.main?.state?.value?.section)
        app.openUrl("https://www.imdb.com/title/tt1/")
        assertEquals(listOf("https://www.imdb.com/title/tt1/"), platform.opened)
        // A development session has no account: no picker, no sign-in method to add.
        app.linkIdentity()
        assertNull(app.state.value.overlay)
        assertEquals(false, app.canSwitchProfile)
    }

    @Test
    fun pageLinksOpenTheirTabsAndAnAutoPlayLinkItsVideo() = runTest {
        val (app, _) = app()
        app.openLink(DeepLink.Detail("series", "tt1", "tt1:1:2", autoPlay = true))
        assertEquals(Route.Detail("series", "tt1", "tt1:1:2", videoAction = DetailVideoAction.AUTO_PLAY_LINK), app.navigator.current)
        // Without a video there is nothing to play: plain Detail.
        app.openLink(DeepLink.Detail("series", "tt1", autoPlay = true))
        assertEquals(Route.Detail("series", "tt1"), app.navigator.current)
        app.openLink(DeepLink.DiscoverDefault)
        assertEquals(listOf<Route>(Route.Discover), app.navigator.backStack.value)
        assertNull(app.navigator.discoverLink.value, "no catalog asked for: Discover opens on its own")
        app.openLink(DeepLink.Library)
        assertEquals(listOf<Route>(Route.Library), app.navigator.backStack.value)
        assertEquals(LibrarySection.LIBRARY, app.librarySection())
        app.openLink(DeepLink.Board)
        assertEquals(listOf<Route>(Route.Board), app.navigator.backStack.value)
        app.close()
    }

    @Test
    fun anAddonLinkFromOutsideTheAppOpensItsInstallPreviewNowOrWhileAddonsShows() = runTest {
        val http = mockClient { request ->
            val name = if ("configured" in request.url.encodedPath) "Example (configured)" else "Example"
            when (request.url.host) {
                "addon.example" -> json(
                    """{"id":"org.example","version":"1.0.0","name":"$name","types":["movie"],"resources":["stream"],"behaviorHints":{"configurable":true}}""",
                )
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val platform = FakeAppPlatform(opensUrls = false)
        val graph = testGraph(http = http)
        val app = AppSession(graph, "profile-1", platform, null, profileAccount = null, startProfile = graph.data.addProfile("profile-1"))
        assertTrue(app.openIncomingLink("stremio://addon.example/manifest.json"))
        assertEquals(listOf<Route>(Route.Settings), app.navigator.backStack.value)
        assertNull(app.state.value.installPreview)
        val session = assertNotNull(app.settingsSession())
        assertEquals(SettingsSection.ADDONS, session.main.state.value.section)
        val addons = assertNotNull(session.addonsStore)
        runCurrent()
        val preview = assertIs<AddonInstallStep.Preview>(addons.state.value.install.step).preview
        assertEquals("Example", preview.name)
        assertEquals(AddonInstallAction.Install(T.addons.install.confirm), preview.action)

        // Configuring before installing: on a device without a browser the page goes to a phone.
        assertNotNull(preview.configureLabel)
        addons.configurePreview()
        val handoff = assertIs<AppOverlay.OpenOnPhone>(app.state.value.overlay)
        assertEquals("https://addon.example/configure", handoff.url)
        assertEquals("addon.example", handoff.site)
        app.closeOverlay()

        // The page's Install link comes back while Addons shows.
        assertTrue(app.openIncomingLink("stremio://addon.example/configured/manifest.json"))
        runCurrent()
        assertSame(addons, session.addons)
        assertEquals("Example (configured)", assertIs<AddonInstallStep.Preview>(addons.state.value.install.step).preview.name)
        app.close()
    }

    @Test
    fun anAddonLinkShowsItsInstallPageOverTheScreenWhereThePlatformSaysSo() = runTest {
        val (app, _) = app(platform = FakeAppPlatform(installLinksOverScreen = true))
        app.navigator.openLibrary(LibrarySection.LIBRARY)
        val stack = app.navigator.backStack.value
        assertTrue(app.openIncomingLink("stremio://addon.example/manifest.json"))
        assertEquals("https://addon.example/manifest.json".transportUrl(), app.state.value.installPreview)
        assertEquals(stack, app.navigator.backStack.value)
        app.closeInstallPreview()
        assertNull(app.state.value.installPreview)
        assertEquals(stack, app.navigator.backStack.value)
        app.close()
    }

    @Test
    fun aLinkNothingHereOpensIsOfferedToAPhone() = runTest {
        val (app, platform) = app(platform = FakeAppPlatform(opensUrls = false))
        app.openUrl("https://www.youtube.com/watch?v=abc")
        val overlay = assertIs<AppOverlay.OpenOnPhone>(app.state.value.overlay)
        assertEquals("https://www.youtube.com/watch?v=abc", overlay.url)
        assertEquals("www.youtube.com", overlay.site)
        app.closeOverlay()
        platform.opensUrls = true
        app.openUrl("https://www.imdb.com/title/tt1/")
        assertNull(app.state.value.overlay)
        assertEquals(listOf("https://www.youtube.com/watch?v=abc", "https://www.imdb.com/title/tt1/"), platform.opened)
        app.close()
    }

    @Test
    fun onlyWebLinksReachTheSystemsOpener() = runTest {
        val (app, platform) = app(platform = FakeAppPlatform(opensUrls = false))
        for (link in listOf(
            "file:///C:/Windows/System32/calc.exe",
            "\\\\host\\share\\setup.exe",
            "smb://host/share/setup.exe",
            "search-ms:query=setup&crumb=location:\\\\host\\share",
            "stremio:///",
            "javascript:alert(1)",
        )) {
            app.openUrl(link)
            assertNull(app.state.value.overlay, "$link is offered to no phone either")
        }
        assertEquals(emptyList<String>(), platform.opened)
        app.openUrl("HTTPS://www.imdb.com/title/tt1/")
        assertEquals(listOf("HTTPS://www.imdb.com/title/tt1/"), platform.opened)
        app.close()
    }

    @Test
    fun settingsKeepsItsStoresUntilTheSessionClosesAndLeadsToTheApp() = runTest {
        val (app, platform) = app()
        assertNull(app.settingsSession(), "no session before Settings first shows")
        app.navigator.navigate(Route.Settings)
        val session = assertNotNull(app.settingsSession())
        assertSame(session, app.settingsSession())
        val device = assertNotNull(session.device)
        assertSame(device, session.deviceStore)
        assertNull(session.account, "a development session has no account")
        assertNull(session.trackers, "no trackers on this graph")

        session.main.open(SettingsSection.ADDONS)
        assertEquals(SettingsSection.ADDONS, session.main.state.value.section)
        assertEquals(Route.Settings, app.navigator.current, "Addons is a section of Settings, not a screen")
        val addons = assertNotNull(session.addons)
        assertSame(addons, session.addonsStore)
        session.main.openSourceCode()
        assertEquals(1, platform.opened.size)

        // Back leaves Settings for Home; Settings' stack stays, and shown again it finds its stores as they were.
        assertTrue(app.navigator.back())
        assertEquals(listOf<Route>(Route.Board), app.navigator.backStack.value)
        app.navigator.pick(TopLevelDestination.SETTINGS)
        assertSame(session, app.settingsSession())
        assertSame(addons, session.addons, "Addons' store too, with a sheet it had open")
        app.close()
        assertNull(session.playback, "a released session makes no more stores")
    }

    @Test
    fun aTabShownAgainFindsItsScreenAsItWasUntilTheProfileChanges() = runTest {
        val graph = testGraph()
        for (profile in listOf("profile-1", "profile-2")) {
            graph.data.addProfile(profile)
            graph.data.addons.install(profile, DefaultAddons.CINEMETA_URL, FixtureCorpus.text("live/cinemeta/manifest.json"))
        }

        // What Discover's screen does each time it is built: it takes the store its route keeps, and the catalog a link
        // asked for.
        fun AppSession.discover(): DiscoverStore {
            val store = assertNotNull(navigator.retain(Route.Discover, DiscoverStore.KEY, create = { discoverStore() }, release = DiscoverStore::release))
            navigator.takeDiscoverLink()?.let(store::open)
            runCurrent()
            return store
        }

        val app = AppSession(graph, "profile-1", FakeAppPlatform(), start = null, profileAccount = null)
        app.navigator.select(TopLevelDestination.DISCOVER)
        app.discover().selectCatalog(1)
        runCurrent()
        assertEquals("\u2068New\u2069 · Movies", app.discover().state.value.title)

        // Home, then Discover again: the screen, built again, shows the catalog picked.
        app.navigator.select(TopLevelDestination.BOARD)
        app.navigator.select(TopLevelDestination.DISCOVER)
        assertEquals("\u2068New\u2069 · Movies", app.discover().state.value.title)

        // A link from Home (a row's See all) moves it to the linked catalog.
        app.navigator.select(TopLevelDestination.BOARD)
        app.openLink(DeepLink.Discover(DefaultAddons.CINEMETA_URL, "series", "top", emptyList()))
        assertEquals("\u2068Popular\u2069 · Series", app.discover().state.value.title)

        // A profile switch closes the session (RootSessionTest); the next profile's Discover opens on the first catalog.
        app.close()
        val next = AppSession(graph, "profile-2", FakeAppPlatform(), start = null, profileAccount = null)
        next.navigator.select(TopLevelDestination.DISCOVER)
        assertEquals("\u2068Popular\u2069 · Movies", next.discover().state.value.title)
        next.close()
    }

    @Test
    fun openAddonsOpensSettingsOnItsAddonsSection() = runTest {
        val (app, _) = app()
        app.openAddons()
        assertEquals(listOf<Route>(Route.Settings), app.navigator.backStack.value)
        assertEquals(SettingsSection.ADDONS, app.settingsSession()?.main?.state?.value?.section)
        app.close()
    }

    @Test
    fun watchTogetherOpensOverHomeFromAnyScreen() = runTest {
        val (app, _) = app()
        app.navigator.select(TopLevelDestination.SETTINGS)
        app.navigator.navigate(Route.Detail("movie", "tt1"))
        app.openWatchTogether()
        assertEquals(listOf(Route.Board, Route.WatchTogether), app.navigator.backStack.value)
        assertEquals(TopLevelDestination.BOARD, TopLevelDestination.selectedIn(app.navigator.backStack.value))
        assertTrue(app.navigator.back())
        assertEquals(listOf<Route>(Route.Board), app.navigator.backStack.value)
        app.close()
    }

    @Test
    fun calendarOpensOverHomeOrLibraryAndBackReturnsThere() = runTest {
        val (app, _) = app()
        app.openCalendar()
        assertEquals(listOf(Route.Board, Route.Calendar), app.navigator.backStack.value)
        assertTrue(app.navigator.back())
        app.navigator.select(TopLevelDestination.LIBRARY)
        app.openCalendar()
        assertEquals(listOf(Route.Library, Route.Calendar), app.navigator.backStack.value)
        assertEquals(TopLevelDestination.LIBRARY, TopLevelDestination.selectedIn(app.navigator.backStack.value))
        assertTrue(app.navigator.back())
        assertEquals(listOf<Route>(Route.Library), app.navigator.backStack.value)
        app.close()
    }

    @Test
    fun watchTogetherSetsThisDevicesRelay() = runTest {
        val (app, _) = app()
        app.openWatchTogether()
        val lobby = app.watchStore()
        runCurrent()
        fun relay() = lobby.state.value.relaySetting
        assertEquals(listOf("", T.watch.relay.hosted, "false"), listOf(relay().value, relay().valueLabel, relay().custom.toString()))
        assertFalse(lobby.setRelay("relay.example"), "not a server's address")
        assertTrue(lobby.setRelay("HTTPS://Relay.Example:443/"))
        runCurrent()
        assertEquals(listOf("https://relay.example", "relay.example", "true"), listOf(relay().value, relay().valueLabel, relay().custom.toString()))
        // Empty goes back to the public relays.
        assertTrue(lobby.setRelay(" "))
        runCurrent()
        assertEquals(listOf("", T.watch.relay.hosted, "false"), listOf(relay().value, relay().valueLabel, relay().custom.toString()))
        lobby.release()
        app.close()
    }

    /** The running app of the stored profile `profile-1`. */
    private suspend fun TestScope.app(
        start: Route? = null,
        platform: FakeAppPlatform = FakeAppPlatform(),
        graph: AppGraph = testGraph(),
    ): Pair<AppSession, FakeAppPlatform> =
        AppSession(graph, "profile-1", platform, start, profileAccount = null, startProfile = graph.data.addProfile("profile-1")) to platform

    private fun card(preview: MetaItemPreview) =
        MetaCard(preview.id, preview.type, preview.name, year = null, rating = null, posterUrl = null, PosterShape.POSTER, backgroundUrl = null, preview)
}
