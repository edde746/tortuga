package com.edde746.tortuga.presentation.detail

import com.edde746.tortuga.addons.AddonAggregator
import com.edde746.tortuga.addons.AddonTransport
import com.edde746.tortuga.addons.DefaultAddons
import com.edde746.tortuga.addons.json
import com.edde746.tortuga.data.LocalData
import com.edde746.tortuga.data.inMemoryDatabase
import com.edde746.tortuga.data.library.LibraryRepository
import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.library.WatchedBitField
import com.edde746.tortuga.data.settings.AutoPlayMode
import com.edde746.tortuga.data.settings.ProfileSetting
import com.edde746.tortuga.data.settings.StreamQuality
import com.edde746.tortuga.data.sync.HybridClock
import com.edde746.tortuga.fixtures.FixtureCorpus
import com.edde746.tortuga.model.AddonResponse
import com.edde746.tortuga.model.DeepLink
import com.edde746.tortuga.model.Descriptor
import com.edde746.tortuga.model.ExtraValue
import com.edde746.tortuga.model.Manifest
import com.edde746.tortuga.model.MetaItem
import com.edde746.tortuga.model.MetaItemPreview
import com.edde746.tortuga.model.ResourceContent
import com.edde746.tortuga.model.StreamSource
import com.edde746.tortuga.model.transportUrl
import com.edde746.tortuga.net.NetworkState
import com.edde746.tortuga.playback.resolve.FakeHttp
import com.edde746.tortuga.playback.session.AutoPlayPick
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.StreamAutoPicker
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.navigation.DetailVideoAction
import com.edde746.tortuga.presentation.profiles.ParentCheck
import com.edde746.tortuga.trackers.WatchedMark
import com.edde746.tortuga.trackers.WatchedMarkListener
import com.edde746.tortuga.trackers.ratings.RatingSource
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** The Detail store over Cinemeta's recorded answers (`fixtures/live/cinemeta`): Chernobyl and The Shawshank Redemption. */
class DetailStoreTest {
    private val cinemeta = DefaultAddons.descriptors.first()
    private val openSubtitles = DefaultAddons.descriptors.last()
    private val backup = metaAddon("backup")
    private val broken = metaAddon("broken")

    private fun metaAddon(name: String) = Descriptor(
        Manifest.decode("""{"id":"org.example.$name","version":"1.0.0","name":"$name","types":["movie","series"],"resources":["meta"],"idPrefixes":["tt"]}"""),
        "https://$name.example.com/manifest.json".transportUrl(),
    )

    private class Host : DetailHost {
        val streams = mutableListOf<String>()
        val notices = mutableListOf<String?>()

        /** Whether each list opened ([streams]) plays its pick from the beginning. */
        val fromStart = mutableListOf<Boolean>()
        val played = mutableListOf<PlayRequest>()
        val urls = mutableListOf<String>()
        val links = mutableListOf<DeepLink>()

        override fun openStreams(meta: MetaItem, videoId: String, notice: String?, fromStart: Boolean) {
            streams += "${meta.id}/$videoId"
            notices += notice
            this.fromStart += fromStart
        }

        override fun openDownloadStreams(meta: MetaItem, videoId: String) {
            streams += "download ${meta.id}/$videoId"
        }

        override fun openKeepSynced() = Unit

        override fun play(request: PlayRequest) {
            played += request
        }

        override fun openUrl(url: String) {
            urls += url
        }

        override fun openDeepLink(link: DeepLink) {
            links += link
        }

        override fun showTitle(entryId: String, type: String, id: String, shownVideoId: String?, preview: MetaItemPreview?) = Unit

        override fun openMeta(type: String, id: String, preview: MetaItemPreview?) = Unit

        override fun openDetail(type: String, id: String, videoId: String?, action: DetailVideoAction) = Unit

        override fun askParent(check: ParentCheck, onUnlocked: suspend () -> Unit) = Unit
    }

    private class Fixture(private val scope: TestScope, private val addons: List<Descriptor>) {
        /** The [linksAddon] answers so far: each one's links carry its number. */
        var linkAnswers = 0

        val data = LocalData(
            inMemoryDatabase(), { NOW },
            StandardTestDispatcher(scope.testScheduler), HybridClock.DEFAULT_MAX_DRIFT_MILLIS,
        )
        val http = FakeHttp(scope) { request ->
            when (request.url.host) {
                "v3-cinemeta.strem.io" -> when (request.url.encodedPath) {
                    "/meta/series/tt7366338.json" -> json(FixtureCorpus.text("live/cinemeta/meta-series-tt7366338.json"))
                    "/meta/movie/tt0111161.json" -> json(FixtureCorpus.text("live/cinemeta/meta-movie-tt0111161.json"))
                    else -> respond("", HttpStatusCode.NotFound)
                }
                "extras.example.com" -> json(EXTRAS)
                "backup.example.com" -> {
                    delay(5.seconds)
                    json("""{"meta":{"id":"tt7366338","type":"series","name":"Chernobyl (backup)","videos":[{"id":"tt7366338:1:1","title":"Episode one","season":1,"episode":1}]}}""")
                }
                // Chernobyl as an addon may list it: an episode with no title of its own (a blank one counts as none).
                "untitled.example.com" -> json("""{"meta":{"id":"tt7366338","type":"series","name":"Chernobyl","videos":[{"id":"tt7366338:1:1","title":" ","season":1,"episode":1}]}}""")
                "streams.example.com" -> {
                    delay(2.seconds)
                    val episode = Regex("""(\d+)\.json$""").find(request.url.encodedPath)?.groupValues?.get(1)
                    if (request.url.encodedPath.startsWith("/stream/series/") && episode != null) {
                        // Each episode's own links, in one binge group per quality.
                        json(
                            """{"streams":[
                                {"name":"Streams\n2160p","url":"https://streams.example.com/play/e$episode/2160","behaviorHints":{"bingeGroup":"streams-2160"}},
                                {"name":"Streams\n1080p","url":"https://streams.example.com/play/e$episode/1080","behaviorHints":{"bingeGroup":"streams-1080"}},
                                {"name":"Streams\n720p","url":"https://streams.example.com/play/e$episode/720"}
                            ]}""",
                        )
                    } else {
                        json(
                            """{"streams":[
                                {"name":"Streams\n2160p","url":"https://streams.example.com/play/2160"},
                                {"name":"Streams\n1080p","url":"https://streams.example.com/play/1080"},
                                {"name":"Streams\n720p","url":"https://streams.example.com/play/720"}
                            ]}""",
                        )
                    }
                }
                "links.example.com" -> {
                    delay(2.seconds)
                    // A new link for every file in every answer (an addon's /playback/… links), good only for this answer.
                    val answer = ++linkAnswers
                    val episode = Regex("""(\d+)\.json$""").find(request.url.encodedPath)?.groupValues?.get(1)
                    json(
                        """{"streams":[
                            {"name":"Links\n1080p","url":"https://links.example.com/playback/$answer/e$episode-web","behaviorHints":{"filename":"Example.Show.S01E0$episode.1080p.WEB.mkv","videoSize":2000}},
                            {"name":"Links\n2160p","url":"https://links.example.com/playback/$answer/e$episode-remux","behaviorHints":{"filename":"Example.Show.S01E0$episode.2160p.REMUX.mkv","videoSize":9000}}
                        ]}""",
                    )
                }
                "empty.example.com" -> {
                    delay(1.seconds)
                    json("""{"streams":[]}""")
                }
                else -> respond("", HttpStatusCode.InternalServerError)
            }
        }
        val host = Host()

        val aggregator = AddonAggregator(AddonTransport(http.client, parseDispatcher = StandardTestDispatcher(scope.testScheduler)))

        fun store(
            type: String,
            id: String,
            videoId: String? = null,
            preview: MetaItemPreview? = null,
            covered: Flow<Boolean> = flowOf(false),
            videoAction: DetailVideoAction = DetailVideoAction.STREAMS,
            trackerMarks: WatchedMarkListener? = null,
        ) = DetailStore(
            profileId = "p",
            type = type,
            id = id,
            videoId = videoId,
            shownVideoId = null,
            aggregator = aggregator,
            installedAddons = { addons },
            library = data.library,
            streamHistory = data.streamHistory,
            settings = data.settings.observe("p"),
            autoPicker = StreamAutoPicker(aggregator, data.streamHistory),
            host = host,
            clock = object : Clock {
                override fun now() = Instant.fromEpochMilliseconds(NOW)
            },
            parentScope = scope.backgroundScope,
            network = MutableStateFlow(NetworkState.ONLINE),
            preview = preview,
            covered = covered,
            videoAction = videoAction,
            trackerMarks = trackerMarks,
        )
    }

    private fun TestScope.chernobyl(addons: List<Descriptor> = listOf(cinemeta, openSubtitles)): Pair<Fixture, DetailStore> {
        val f = Fixture(this, addons)
        return f to f.store("series", "tt7366338")
    }

    private val series = MetaSnapshot("tt7366338", "series", "Chernobyl", null, "poster")

    @Test
    fun aSeriesFromCinemetaIsPreformatted() = runTest {
        val (_, store) = chernobyl()
        runCurrent()
        val state = store.state.value
        assertIs<DetailBody.Loaded>(state.body)
        assertEquals(listOf(DetailSource("Cinemeta", DetailSourceStatus.READY, error = null, shown = true)), state.sources, "subtitle addons aren't asked")
        val header = assertNotNull((state.body as? DetailBody.Page)?.header)
        assertEquals("Chernobyl", header.title)
        // The addon's release info and the genres isolated, this app's runtime not.
        assertEquals("\u20682019\u2069 · 1 h 5 min · \u2068Drama\u2069, \u2068History\u2069", header.infoLine)
        assertEquals(
            listOf(DetailRating(RatingSource.IMDB, RatingLogo.IMDB, "9.3", "IMDb 9.3", linkId = "link:0")),
            header.ratings,
            "without trackers the row is the addon's IMDb rating",
        )
        assertEquals("https://images.metahub.space/background/medium/tt7366338/img", header.background)
        assertEquals("https://images.metahub.space/logo/medium/tt7366338/img", header.logo)
        assertEquals("Information", header.factsTitle)
        assertEquals(
            listOf(
                Triple("Released", "May 6, 2019", false),
                Triple("Runtime", "1 h 5 min", false),
                Triple("Country", "United States, United Kingdom", false),
                Triple("Awards", "Won 10 Primetime Emmys. 87 wins & 60 nominations total", false),
                Triple("Cast", "\u2068Jared Harris\u2069, \u2068Jessie Buckley\u2069, \u2068Stellan Skarsgård\u2069", true),
                Triple("Writers", "\u2068Craig Mazin\u2069", true),
                Triple("Genres", "\u2068Drama\u2069, \u2068History\u2069, \u2068Thriller\u2069", true),
                Triple("Details from", "Cinemeta", false),
            ),
            header.facts.map { Triple(it.label, it.value, it.linked) },
            "no language or directors in Cinemeta's answer, so no line for them; the names are the link groups' pills too; the addon the details come from last",
        )
        assertTrue(header.overview!!.startsWith("In April 1986"))
        assertEquals(
            listOf("Genres" to listOf("Drama", "History", "Thriller"), "Cast" to listOf("Jared Harris", "Jessie Buckley", "Stellan Skarsgård"), "Writers" to listOf("Craig Mazin")),
            header.linkGroups.map { group -> group.title to group.links.map { it.label } },
        )
        assertEquals(listOf("Trailer 1", "Trailer 2"), state.trailers.map { it.label })
        assertEquals("Trailers", state.trailerActionText)

        // Specials last; the first regular season is selected.
        assertEquals(listOf("Season 1" to true, "Specials" to false), state.seasons.map { it.label to it.selected })
        val first = state.episodes.first()
        assertEquals(listOf("S1E1", "S1E2", "S1E3", "S1E4", "S1E5"), state.episodes.map { it.code })
        assertEquals("1:23:45", first.title)
        assertEquals("1", first.number)
        assertEquals("1. \u20681:23:45\u2069", first.numberedTitle)
        assertEquals("May 7, 2019 · 1 h 5 min", first.infoLine)
        assertEquals("https://episodes.metahub.space/tt7366338/1/1/w780.jpg", first.thumbnail)
        assertTrue(first.overview!!.startsWith("Plant workers and firefighters"))
        assertEquals(listOf(false, false, false, false, false), state.episodes.map { it.watched })

        // No stream addon is installed: nothing can be found. The button reads the episode's code alone; screen readers
        // hear what it does.
        assertEquals(
            DetailPrimaryAction("S1E1", "tt7366338:1:1", resume = false, episodeTitle = "1:23:45", progress = null, spokenLabel = "Play S1E1", noStreams = "No streams found"),
            state.primaryAction,
        )
        assertTrue(first.nextUp)
        assertFalse(state.inLibrary)
        assertEquals("Add to library", state.libraryActionText)
        assertEquals("Mark all episodes as watched", state.watchedActionText)

        store.selectSeason(0)
        assertEquals(12, store.state.value.episodes.size)
        assertEquals("S0E28", store.state.value.episodes.single { it.title == "What is Chernobyl" }.code)
    }

    @Test
    fun aMovieFromCinemetaIsPreformatted() = runTest {
        val f = Fixture(this, listOf(cinemeta))
        val store = f.store("movie", "tt0111161")
        runCurrent()
        val state = store.state.value
        val header = assertNotNull((state.body as? DetailBody.Page)?.header)
        assertEquals("The Shawshank Redemption", header.title)
        assertEquals("\u20681994\u2069 · 2 h 22 min · \u2068Drama\u2069", header.infoLine)
        assertEquals(listOf("Genres", "Cast", "Writers", "Directors"), header.linkGroups.map { it.title })
        assertEquals(
            listOf("Released", "Runtime", "Country", "Awards", "Cast", "Directors", "Writers", "Genres", "Details from"),
            header.facts.map { it.label },
        )
        assertEquals("Oct 14, 1994", header.facts.first().value)
        assertEquals("With \u2068Tim Robbins\u2069, \u2068Morgan Freeman\u2069, \u2068Bob Gunton\u2069", header.castLine)
        // The cast, the directors, the writers; Frank Darabont, who directed and wrote it, once.
        assertEquals(listOf("Tim Robbins", "Morgan Freeman", "Bob Gunton", "Frank Darabont", "Stephen King"), header.castAndCrew.map { it.label })
        assertEquals(emptyList(), state.seasons)
        assertEquals(emptyList(), state.episodes)
        assertEquals(DetailPrimaryAction("Play", "tt0111161", resume = false, episodeTitle = null, progress = null, spokenLabel = "Play", noStreams = "No streams found"), state.primaryAction)
        assertEquals("Mark as watched", state.watchedActionText)

        f.data.library.upsertFromMeta("p", MetaSnapshot("tt0111161", "movie", "The Shawshank Redemption", null, "poster"))
        f.data.library.recordProgress("p", "tt0111161", "tt0111161", 3_723_000, 8_520_000)
        runCurrent()
        val resume = assertNotNull(store.state.value.primaryAction)
        assertEquals("1:02:03", resume.label)
        assertEquals("Resume 1:02:03", resume.spokenLabel)
        assertTrue(resume.resume)
        assertEquals(3_723_000.0 / 8_520_000, resume.progress)

        store.markWatched(true)
        runCurrent()
        assertTrue(store.state.value.watched)
        assertEquals("Mark as unwatched", store.state.value.watchedActionText)
        assertEquals("Play", store.state.value.primaryAction?.label, "marking watched clears the position")
    }

    @Test
    fun theLineUnderPlayIsTheEpisodesTitleAlone() = runTest {
        // Play has the episode's code ("S1E1"), so the line under it names the episode by its own title alone.
        val (_, store) = chernobyl()
        runCurrent()
        assertEquals("S1E1" to "1:23:45", store.state.value.primaryAction?.let { it.label to it.episodeTitle })
        // An episode without a title of its own has no line: the code on the button is all there is to say.
        val untitled = Fixture(this, listOf(metaAddon("untitled"))).store("series", "tt7366338")
        runCurrent()
        assertEquals("S1E1" to null, untitled.state.value.primaryAction?.let { it.label to it.episodeTitle })
        // A movie has none.
        val movie = Fixture(this, listOf(cinemeta)).store("movie", "tt0111161")
        runCurrent()
        assertEquals("Play" to null, movie.state.value.primaryAction?.let { it.label to it.episodeTitle })
        assertFalse(movie.state.value.hasEpisodes, "a movie's page has no episodes' column")
    }

    @Test
    fun aCatalogItemsHeaderShowsWhileTheTitleLoadsWhereNothingMovesWhenItComes() = runTest {
        val f = Fixture(this, listOf(cinemeta, openSubtitles))
        // Cinemeta's catalog items are its metas without the videos.
        val item = chernobylMeta().preview
        val store = f.store("series", "tt7366338", preview = item)
        val preview = store.state.value
        assertEquals(true, (preview.body as? DetailBody.Preview)?.episodesPending, "a series keeps its episodes' column")
        assertTrue(preview.hasEpisodes)
        assertFalse(preview.listsEpisodes, "TV's page lists no episodes before the title comes")
        assertEquals(emptyList(), preview.episodes)
        assertNull(preview.primaryAction)
        runCurrent()
        val loaded = store.state.value
        assertIs<DetailBody.Loaded>(loaded.body)
        assertTrue(loaded.hasEpisodes)
        assertTrue(loaded.listsEpisodes)
        val header = assertNotNull((loaded.body as? DetailBody.Page)?.header)
        assertEquals(
            header.copy(ratings = header.ratings.map { it.copy(linkId = null) }, facts = header.facts.dropLast(1)),
            (preview.body as? DetailBody.Page)?.header,
            "the same header, its links waiting for the title, and the addon the details come from added last",
        )
        assertEquals(DetailFact("Details from", "Cinemeta", linked = false), header.facts.last())

        // A catalog item without the logo or the backdrop the page is laid out by shows nothing until the title comes.
        assertNull((f.store("series", "tt7366338", preview = item.copy(logo = null)).state.value.body as? DetailBody.Page)?.header)
        assertNull((f.store("series", "tt7366338", preview = item.copy(background = null)).state.value.body as? DetailBody.Page)?.header)
    }

    @Test
    fun aCoveredDetailWaitsForTheScreenOverItToGo() = runTest {
        val f = Fixture(this, listOf(cinemeta))
        val covered = MutableStateFlow(false)
        val store = f.store("movie", "tt0111161", covered = covered)
        runCurrent()
        // The player over it saves the position every 15 s.
        covered.value = true
        f.data.library.upsertFromMeta("p", MetaSnapshot("tt0111161", "movie", "The Shawshank Redemption", null, "poster"))
        f.data.library.recordProgress("p", "tt0111161", "tt0111161", 3_723_000, 8_520_000)
        runCurrent()
        assertEquals("Play", store.state.value.primaryAction?.label)
        covered.value = false
        runCurrent()
        assertEquals("1:02:03", store.state.value.primaryAction?.label)
    }

    @Test
    fun whatTheAddonLeavesOutIsLeftOut() = runTest {
        val f = Fixture(this, listOf(metaAddon("extras")))
        val store = f.store("series", "tt9")
        runCurrent()
        val state = store.state.value
        val header = assertNotNull((state.body as? DetailBody.Page)?.header)
        // No release info: the year of the release date; no genres to add.
        assertEquals("2020 · 24 min", header.infoLine)
        assertEquals(listOf(DetailRating(RatingSource.IMDB, RatingLogo.IMDB, "8.1", "IMDb 8.1", linkId = "link:0")), header.ratings)
        assertEquals(
            listOf("Released" to "Jan 1, 2020", "Runtime" to "24 min", "Language" to "Japanese", "Details from" to "extras"),
            header.facts.map { it.label to it.value },
        )
        assertEquals("Trailer", state.trailerActionText)
        val (untitled, upcoming) = state.episodes
        assertEquals(listOf("Episode 1", "Episode 1", "1"), listOf(untitled.title, untitled.numberedTitle, untitled.number))
        assertEquals("Jan 1, 2020 · 24 min", untitled.infoLine)
        assertEquals("2. \u2068Later\u2069", upcoming.numberedTitle)
        assertEquals("Airs Jan 1, 2099 · 24 min", upcoming.infoLine)
        assertTrue(upcoming.upcoming)
    }

    @Test
    fun theFirstAddonInTheInstalledOrderThatHasTheTitleIsShown() = runTest {
        val (_, store) = chernobyl(listOf(backup, broken, cinemeta))
        runCurrent()
        var state = store.state.value
        assertEquals(listOf(DetailSourceStatus.LOADING, DetailSourceStatus.FAILED, DetailSourceStatus.READY), state.sources.map { it.status })
        assertEquals("The addon returned an error (HTTP 500).", state.sources[1].error)
        assertEquals("Chernobyl", (state.body as? DetailBody.Page)?.header?.title, "shown while an earlier addon is still loading")
        assertTrue(state.sources[2].shown)

        advanceTimeBy(6.seconds)
        runCurrent()
        state = store.state.value
        assertEquals("Chernobyl (backup)", (state.body as? DetailBody.Page)?.header?.title, "the earlier addon wins once it answers, as in stremio-core")
        assertEquals(listOf(true, false, false), state.sources.map { it.shown })
    }

    @Test
    fun noAddonOrNoAnswerIsWorded() = runTest {
        val f = Fixture(this, listOf(openSubtitles, broken))
        val nothing = f.store("series", "tt7366338")
        runCurrent()
        assertEquals("Couldn't load the details.", (nothing.state.value.body as? DetailBody.Failed)?.text)
        assertNull((nothing.state.value.body as? DetailBody.Page)?.header)

        val none = Fixture(this, listOf(openSubtitles)).store("series", "tt7366338")
        runCurrent()
        assertEquals("None of the installed addons has details for this title.", (none.state.value.body as? DetailBody.Failed)?.text)

        val missing = Fixture(this, listOf(cinemeta)).store("movie", "tt0000001")
        runCurrent()
        assertEquals("No addon has details for this title.", (missing.state.value.body as? DetailBody.Failed)?.text)
    }

    @Test
    fun anEpisodeInProgressResumesAndWithNoStreamToResumeOpensItsList() = runTest {
        val (f, store) = chernobyl()
        runCurrent()
        f.data.library.upsertFromMeta("p", series)
        f.data.library.recordProgress("p", "tt7366338", "tt7366338:1:3", 754_000, 3_900_000)
        runCurrent()
        var state = store.state.value
        val action = assertNotNull(state.primaryAction)
        assertEquals("S1E3 · 12:34", action.label)
        assertEquals("Resume S1E3 · 12:34", action.spokenLabel)
        assertEquals("Open Wide, O Earth", action.episodeTitle, "the line under the button: the title alone, the code is on it")
        assertEquals(754_000.0 / 3_900_000, state.episodes[2].progress)
        assertTrue(state.episodes[2].nextUp)
        // How much is left where it resumes, after its date and runtime on TV's line; the others resume nothing.
        assertEquals(DetailText.join(listOfNotNull(state.episodes[2].infoLine, "53 min left")), state.episodes[2].detailLine)
        assertEquals(state.episodes[1].infoLine, state.episodes[1].detailLine)

        // Without a remembered stream, the stream list opens.
        store.play()
        runCurrent()
        assertEquals(listOf("tt7366338/tt7366338:1:3"), f.host.streams)

        // So it does when the addon it last played from is no longer installed.
        f.data.streamHistory.record("p", "tt7366338", "tt7366338:1:3", """{"url":"https://gone.example.com/e3.mkv","name":"Gone 1080p"}""", "https://gone.example.com/manifest.json")
        store.play()
        runCurrent()
        assertEquals(listOf("tt7366338/tt7366338:1:3", "tt7366338/tt7366338:1:3"), f.host.streams)
        assertEquals(emptyList(), f.host.played)

        // A season elsewhere stays selected once chosen.
        store.selectSeason(0)
        state = store.state.value
        assertEquals(listOf(false, true), state.seasons.map { it.selected })
    }

    private val linksAddon = Descriptor(
        Manifest.decode("""{"id":"org.example.links","version":"1.0.0","name":"Links","types":["series"],"resources":["stream"],"idPrefixes":["tt"]}"""),
        "https://links.example.com/manifest.json".transportUrl(),
    )

    @Test
    fun aResumedEpisodePlaysTheLinkItsAddonGivesNowForTheSavedFileNeverTheSavedLink() = runTest {
        // Seen on 17 Oct with Chainsmoker Cat: auto-play off, Continue Watching resumed an episode another device had
        // played at home; the saved link from there answered with an error video ("Wrong IP").
        val f = Fixture(this, listOf(cinemeta, linksAddon))
        val store = f.store("series", "tt7366338")
        val e3 = "tt7366338:1:3"
        f.data.library.upsertFromMeta("p", series)
        f.data.library.recordProgress("p", "tt7366338", e3, 754_000, 3_900_000)
        val saved = """{"name":"Links\n2160p","url":"https://links.example.com/playback/home/e3-remux","behaviorHints":{"filename":"Example.Show.S01E03.2160p.REMUX.mkv","videoSize":9000}}"""
        f.data.streamHistory.record("p", "tt7366338", e3, saved, linksAddon.transportUrl)
        runCurrent()

        store.play()
        runCurrent()
        assertTrue(store.state.value.primaryAction!!.busy, "busy while its addon answers")
        assertEquals(emptyList(), f.host.played)
        advanceTimeBy(3.seconds)
        runCurrent()
        val played = f.host.played.single()
        assertEquals(e3, played.videoId)
        assertEquals("https://links.example.com/playback/1/e3-remux", played.url, "the saved file, linked by this answer")
        assertEquals(listOf("https://links.example.com/playback/1/e3-web"), played.alternatives.map { (it.stream.source as StreamSource.Url).url })
        assertNull(played.autoPlay, "auto-play is off")
        assertNull(played.startPositionSeconds, "it resumes")
        assertFalse(store.state.value.primaryAction!!.busy)

        // Every play asks again: an answer's links are never kept for the next.
        store.play()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals("https://links.example.com/playback/2/e3-remux", f.host.played.last().url)

        // The addon no longer offers the saved file: the stream list, as with nothing saved.
        f.data.streamHistory.record("p", "tt7366338", e3, saved.replace("2160p.REMUX", "720p.HDTV"), linksAddon.transportUrl)
        store.play()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(2, f.host.played.size)
        assertEquals(listOf("tt7366338/$e3"), f.host.streams)
        assertEquals(listOf<String?>(null), f.host.notices)
    }

    @Test
    fun aFinishedEpisodeLeadsToTheNextAndAWatchedSeriesStartsOver() = runTest {
        val (f, store) = chernobyl()
        runCurrent()
        val meta = chernobylMeta()
        f.data.library.upsertFromMeta("p", series)
        f.data.library.recordProgress("p", "tt7366338", "tt7366338:1:3", 0, 3_900_000, meta)
        f.data.library.recordProgress("p", "tt7366338", "tt7366338:1:3", 3_800_000, 3_900_000, meta, watched = true)
        runCurrent()
        var state = store.state.value
        assertEquals("S1E4", state.primaryAction?.label)
        assertEquals(listOf(false, false, true, false, false), state.episodes.map { it.watched }, "counting as watched marks the episode")
        assertEquals(listOf(false, false, false, true, false), state.episodes.map { it.nextUp })

        // S1E4 is marked too: S1E5 is next.
        store.episodeMenuAction("tt7366338:1:4", MediaAction.MARK_WATCHED)
        runCurrent()
        assertEquals("S1E5", store.state.value.primaryAction?.label)

        // The last episode is followed by specials only, which the next episode never moves into: the first
        // unwatched episode comes up, then, with everything watched, the first.
        f.data.library.recordProgress("p", "tt7366338", "tt7366338:1:5", 0, 3_900_000, meta)
        f.data.library.recordProgress("p", "tt7366338", "tt7366338:1:5", 3_800_000, 3_900_000, meta, watched = true)
        runCurrent()
        assertEquals("S1E1", store.state.value.primaryAction?.label)
        store.markSeasonWatched(1, true)
        runCurrent()
        state = store.state.value
        assertEquals("S1E1", state.primaryAction?.label)
        assertTrue(state.seasons.first().watched)
        assertEquals(
            listOf(MediaAction.MARK_SEASON_UNWATCHED to "Mark season as unwatched", MediaAction.MARK_WATCHED to "Mark all episodes as watched"),
            state.seasons.first().menu.map { it.action to it.label },
            "the season's mark, then the whole title's",
        )
        assertEquals("Watched", state.seasons.first().stateText, "its chip's check, spoken")
        assertFalse(state.watched, "specials aren't watched")
        assertNull(state.seasons.last().stateText, "specials aren't watched")

        store.markWatched(true)
        runCurrent()
        assertTrue(store.state.value.watched)
        assertEquals("Mark all episodes as unwatched", store.state.value.watchedActionText)
        store.markSeasonWatched(1, false)
        runCurrent()
        assertEquals(List(5) { false }, store.state.value.episodes.map { it.watched })
    }

    @Test
    fun aMarkMadeAsThePageClosesIsWrittenAndTheTrackersTold() = runTest {
        val f = Fixture(this, listOf(cinemeta))
        val told = mutableListOf<WatchedMark>()
        val store = f.store("series", "tt7366338", trackerMarks = { told += it })
        runCurrent()
        // Back right after the mark: the page's store is released before the write has run.
        store.episodeMenuAction("tt7366338:1:4", MediaAction.MARK_WATCHED)
        store.release()
        runCurrent()
        val marks = WatchedBitField.of(f.data.library.item("p", "tt7366338")?.state?.watchedVideos, chernobylMeta())
        assertTrue(marks.isWatched("tt7366338:1:4"), "the library holds the mark")
        assertEquals(listOf("tt7366338:1:4"), told.map { (it as WatchedMark.Video).videoId }, "the trackers were told")
    }

    @Test
    fun anEpisodeLeftWatchedWithAPositionShowsNoProgressAndTheNextComesUp() = runTest {
        val (f, store) = chernobyl()
        runCurrent()
        val meta = chernobylMeta()
        // The state an older build left: S1E1–S1E2 marked, the pointer still on S1E2 at 92 %, written without the meta.
        f.data.library.upsertFromMeta("p", series)
        val marks = WatchedBitField.of(null, meta).apply { set("tt7366338:1:1", true); set("tt7366338:1:2", true) }
        f.data.library.setWatchedVideos("p", "tt7366338", marks.serialize())
        f.data.library.recordProgress("p", "tt7366338", "tt7366338:1:2", 3_600_000, 3_900_000, watched = true)
        runCurrent()
        val state = store.state.value
        assertEquals(listOf(true, true, false, false, false), state.episodes.map { it.watched })
        assertEquals(List(5) { null }, state.episodes.map { it.progress }, "a watched episode shows no progress")
        assertEquals(listOf(false, false, true, false, false), state.episodes.map { it.nextUp })
        assertEquals("S1E3", state.primaryAction?.label)
    }

    @Test
    fun libraryAndLinks() = runTest {
        val (f, store) = chernobyl()
        runCurrent()
        store.addToLibrary()
        runCurrent()
        assertTrue(store.state.value.inLibrary)
        assertEquals("Remove from library", store.state.value.libraryActionText)
        assertEquals(listOf("tt7366338"), f.data.library.observeLibraryIds())
        store.removeFromLibrary()
        runCurrent()
        assertFalse(store.state.value.inLibrary)

        val header = (store.state.value.body as DetailBody.Page).header
        store.openLink(header.linkGroups[0].links[0].id)
        store.openLink(header.linkGroups[1].links[2].id)
        store.openLink(header.ratings.single().linkId!!)
        store.openTrailer(store.state.value.trailers[0].id)
        assertEquals(
            listOf(
                DeepLink.Discover(DefaultAddons.CINEMETA_URL, "series", "top", listOf(ExtraValue("genre", "Drama"))),
                DeepLink.Search("Stellan Skarsgård"),
            ),
            f.host.links,
        )
        assertEquals(listOf("https://imdb.com/title/tt7366338", "https://www.youtube.com/watch?v=s9APLXM9Ei8"), f.host.urls)
    }

    @Test
    fun aLinkToAVideoSelectsItsSeasonAndOpensItsStreamsOnce() = runTest {
        val f = Fixture(this, listOf(cinemeta))
        val store = f.store("series", "tt7366338", videoId = "tt7366338:0:28")
        runCurrent()
        assertEquals(listOf("tt7366338/tt7366338:0:28"), f.host.streams)
        assertEquals("Specials", store.state.value.seasons.single { it.selected }.label)
        store.reload()
        runCurrent()
        assertEquals(1, f.host.streams.size)
    }

    private val streamAddon = Descriptor(
        Manifest.decode("""{"id":"org.example.streams","version":"1.0.0","name":"Streams","types":["movie","series"],"resources":["stream"],"idPrefixes":["tt"]}"""),
        "https://streams.example.com/manifest.json".transportUrl(),
    )

    /** The Shawshank Redemption's Detail with [streamAddon] (2160p, 1080p, 720p after two seconds) and [mode]. */
    private suspend fun TestScope.shawshank(mode: AutoPlayMode): Pair<Fixture, DetailStore> {
        val f = Fixture(this, listOf(cinemeta, streamAddon))
        f.data.settings.set("p", ProfileSetting.StreamAutoPlay, mode)
        val store = f.store("movie", "tt0111161")
        runCurrent()
        return f to store
    }

    private val PlayRequest.url get() = (stream.stream.source as StreamSource.Url).url

    @Test
    fun autoPlayPlaysTheFirstStreamThatPassesTheFiltersWithoutTheList() = runTest {
        val (f, store) = shawshank(AutoPlayMode.FIRST_MATCH)
        f.data.settings.set("p", ProfileSetting.HiddenStreamQualities, listOf(StreamQuality.UHD_4K))
        runCurrent()
        assertEquals("Choose a stream", store.state.value.chooseStreamText)

        store.play()
        runCurrent()
        val busy = assertNotNull(store.state.value.primaryAction)
        assertTrue(busy.busy)
        assertEquals("Finding a stream…", busy.label)
        assertEquals("Finding a stream…", busy.spokenLabel)
        store.play()
        advanceTimeBy(3.seconds)
        runCurrent()
        val played = f.host.played.single()
        assertEquals("https://streams.example.com/play/1080", played.url)
        assertEquals(AutoPlayPick.FIRST_MATCH, played.autoPlay)
        assertEquals(listOf("https://streams.example.com/play/720"), played.alternatives.map { (it.stream.source as StreamSource.Url).url })
        assertEquals(emptyList(), f.host.streams, "the stream list never opened")
        assertEquals(DetailPrimaryAction("Play", "tt0111161", resume = false, episodeTitle = null, progress = null), store.state.value.primaryAction)
    }

    @Test
    fun withNothingThatPassesAutoPlayOpensTheListWithANotice() = runTest {
        val (f, store) = shawshank(AutoPlayMode.FIRST_MATCH)
        f.data.settings.set("p", ProfileSetting.StreamIncludePattern, "remux")
        runCurrent()
        store.play()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(emptyList(), f.host.played)
        assertEquals(listOf("tt0111161/tt0111161"), f.host.streams)
        assertEquals(listOf<String?>("Auto-play found no stream that passes your filters."), f.host.notices)
        assertFalse(store.state.value.primaryAction!!.busy)
    }

    private fun streamsOnly(name: String) = Descriptor(
        Manifest.decode("""{"id":"org.example.$name","version":"1.0.0","name":"$name","types":["movie","series"],"resources":["stream"],"idPrefixes":["tt"]}"""),
        "https://$name.example.com/manifest.json".transportUrl(),
    )

    @Test
    fun playSaysNoStreamsFoundOnceEveryStreamAddonHasAnsweredNone() = runTest {
        // The stream addons are asked as the page opens, for the video Play plays. An
        // empty answer and a failed addon both count as none, as in the stream list, which says "No streams found" over
        // the failed addon's error.
        val f = Fixture(this, listOf(cinemeta, streamsOnly("empty"), streamsOnly("broken")))
        val store = f.store("movie", "tt0111161")
        runCurrent()
        assertNull(store.state.value.primaryAction!!.noStreams, "the broken addon failed; the empty one is still answering")
        advanceTimeBy(2.seconds)
        runCurrent()
        val action = store.state.value.primaryAction!!
        assertEquals("No streams found", action.noStreams)
        // Pressing it still opens the stream list.
        store.play()
        runCurrent()
        assertEquals(listOf("tt0111161/tt0111161"), f.host.streams)
    }

    @Test
    fun aLateAnswerWithAStreamMeansTheLabelNeverShows() = runTest {
        // The empty addon answers after a second, the streams addon after two: the label waits for every answer, so a
        // late one never has to take it back.
        val f = Fixture(this, listOf(cinemeta, streamsOnly("empty"), streamAddon))
        val store = f.store("movie", "tt0111161")
        runCurrent()
        advanceTimeBy(1.5.seconds)
        runCurrent()
        assertNull(store.state.value.primaryAction!!.noStreams, "one empty answer says nothing while another addon is answering")
        advanceTimeBy(1.seconds)
        runCurrent()
        assertNull(store.state.value.primaryAction!!.noStreams)
    }

    @Test
    fun reuseLastLinkPlaysTheRememberedStreamWhileItIsStillOffered() = runTest {
        val (f, store) = shawshank(AutoPlayMode.REUSE_LAST_LINK)
        f.data.settings.set("p", ProfileSetting.HiddenStreamQualities, listOf(StreamQuality.UHD_4K))
        f.data.streamHistory.record("p", "tt0111161", "tt0111161", """{"name":"Old name","url":"https://streams.example.com/play/720"}""", streamAddon.transportUrl)
        runCurrent()
        store.play()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals("https://streams.example.com/play/720", f.host.played.last().url)
        assertEquals(AutoPlayPick.LAST_LINK, f.host.played.last().autoPlay)
        assertEquals("Streams\n720p", f.host.played.last().stream.stream.name, "the stream as the addon offers it now")

        // A remembered link the addon no longer offers: the first stream that passes instead.
        f.data.streamHistory.record("p", "tt0111161", "tt0111161", """{"url":"https://streams.example.com/play/expired"}""", streamAddon.transportUrl)
        store.play()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals("https://streams.example.com/play/1080", f.host.played.last().url)
        assertEquals(AutoPlayPick.FIRST_MATCH, f.host.played.last().autoPlay)
        assertEquals(emptyList(), f.host.streams)
    }

    @Test
    fun choosingAStreamOpensTheListInsteadAndStopsAutoPlay() = runTest {
        val (f, store) = shawshank(AutoPlayMode.OFF)
        assertNull(store.state.value.chooseStreamText, "with auto-play off, Play opens the list")
        f.data.settings.set("p", ProfileSetting.StreamAutoPlay, AutoPlayMode.FIRST_MATCH)
        runCurrent()
        assertEquals("Choose a stream", store.state.value.chooseStreamText)

        store.play()
        runCurrent()
        store.chooseStream()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(emptyList(), f.host.played)
        assertEquals(listOf("tt0111161/tt0111161"), f.host.streams)
        assertEquals(listOf<String?>(null), f.host.notices)
        assertFalse(store.state.value.primaryAction!!.busy)
    }

    /** Chernobyl's Detail with [streamAddon] (each episode's 2160p, 1080p and 720p after two seconds), 4K hidden, and [mode]. */
    private suspend fun TestScope.chernobylStreams(mode: AutoPlayMode): Pair<Fixture, DetailStore> {
        val f = Fixture(this, listOf(cinemeta, streamAddon))
        f.data.settings.set("p", ProfileSetting.StreamAutoPlay, mode)
        f.data.settings.set("p", ProfileSetting.HiddenStreamQualities, listOf(StreamQuality.UHD_4K))
        val store = f.store("series", "tt7366338")
        runCurrent()
        return f to store
    }

    @Test
    fun anAutoPlayLinkPlaysItsVideoAsItLastPlayedEvenWithAutoPlayOff() = runTest {
        val f = Fixture(this, listOf(cinemeta, streamAddon))
        f.data.settings.set("p", ProfileSetting.StreamAutoPlay, AutoPlayMode.OFF)
        f.data.streamHistory.record("p", "tt7366338", "tt7366338:1:2", """{"url":"https://streams.example.com/play/e2/720"}""", streamAddon.transportUrl)
        f.store("series", "tt7366338", videoId = "tt7366338:1:2", videoAction = DetailVideoAction.AUTO_PLAY_LINK)
        advanceTimeBy(3.seconds)
        runCurrent()
        val played = f.host.played.single()
        assertEquals("tt7366338:1:2", played.videoId)
        assertEquals("https://streams.example.com/play/e2/720", played.url)
        assertEquals(emptyList(), f.host.streams, "no list opened")
    }

    @Test
    fun anEpisodeOpensItsListWithAutoPlayOffAndPlaysTheFirstMatchWithItOn() = runTest {
        val (f, store) = chernobylStreams(AutoPlayMode.OFF)
        store.openEpisode("tt7366338:1:2")
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(listOf("tt7366338/tt7366338:1:2"), f.host.streams)
        assertEquals(emptyList(), f.host.played)

        f.data.settings.set("p", ProfileSetting.StreamAutoPlay, AutoPlayMode.FIRST_MATCH)
        runCurrent()
        store.openEpisode("tt7366338:1:2")
        runCurrent()
        val state = store.state.value
        assertEquals("Finding a stream…", state.episodes.single { it.videoId == "tt7366338:1:2" }.infoLine)
        assertFalse(state.primaryAction!!.busy, "the primary action plays another episode")
        advanceTimeBy(3.seconds)
        runCurrent()
        val played = f.host.played.single()
        assertEquals("tt7366338:1:2", played.videoId)
        assertEquals("https://streams.example.com/play/e2/1080", played.url)
        assertEquals(AutoPlayPick.FIRST_MATCH, played.autoPlay)
        assertEquals(listOf("tt7366338/tt7366338:1:2"), f.host.streams, "the list didn't open again")
        assertNotEquals("Finding a stream…", store.state.value.episodes.single { it.videoId == "tt7366338:1:2" }.infoLine)

        // The episode's menu opens its list instead, cancelling a pick under way.
        store.openEpisode("tt7366338:1:3")
        runCurrent()
        store.episodeMenuAction("tt7366338:1:3", MediaAction.CHOOSE_STREAM)
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(1, f.host.played.size)
        assertEquals(listOf("tt7366338/tt7366338:1:2", "tt7366338/tt7366338:1:3"), f.host.streams)
    }

    @Test
    fun withAutoPlayOffAnEpisodeThatResumesPlaysItsLastStreamAsResumeDoes() = runTest {
        val (f, store) = chernobylStreams(AutoPlayMode.OFF)
        val e3 = "tt7366338:1:3"
        f.data.library.upsertFromMeta("p", series)
        f.data.library.recordProgress("p", "tt7366338", e3, 754_000, 3_900_000)
        f.data.streamHistory.record("p", "tt7366338", e3, """{"url":"https://streams.example.com/play/e3/720"}""", streamAddon.transportUrl)
        runCurrent()
        assertEquals(e3, store.state.value.primaryAction?.videoId)
        // Its row and Resume, the same video: the same stream, as its addon offers it now.
        store.openEpisode(e3)
        advanceTimeBy(3.seconds)
        runCurrent()
        store.play()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(listOf("https://streams.example.com/play/e3/720", "https://streams.example.com/play/e3/720"), f.host.played.map { it.url })
        assertEquals(emptyList(), f.host.streams)

        // An episode it doesn't resume opens its list.
        store.openEpisode("tt7366338:1:4")
        runCurrent()
        assertEquals(listOf("tt7366338/tt7366338:1:4"), f.host.streams)
    }

    @Test
    fun reuseLastLinkPlaysAnEpisodesOwnStreamElseTheTitlesLastBingeGroupElseTheFirstMatch() = runTest {
        val (f, store) = chernobylStreams(AutoPlayMode.REUSE_LAST_LINK)
        suspend fun played(videoId: String): PlayRequest {
            store.openEpisode(videoId)
            advanceTimeBy(3.seconds)
            runCurrent()
            return f.host.played.last()
        }
        val e1 = "tt7366338:1:1"
        val e3 = "tt7366338:1:3"
        val stream = streamAddon.transportUrl

        // Episode 1 last played from a group the addon no longer offers: episode 3 plays the first match.
        f.data.streamHistory.record("p", "tt7366338", e1, """{"url":"https://streams.example.com/play/e1/old","behaviorHints":{"bingeGroup":"streams-retired"}}""", stream)
        assertEquals("https://streams.example.com/play/e3/1080", played(e3).url)
        assertEquals(AutoPlayPick.FIRST_MATCH, f.host.played.last().autoPlay)

        // Episode 1 last played from the 2160p group: episode 3, never played, plays its stream in that group, though
        // the filters hide 4K.
        f.data.streamHistory.record("p", "tt7366338", e1, """{"url":"https://streams.example.com/play/e1/2160","behaviorHints":{"bingeGroup":"streams-2160"}}""", stream)
        assertEquals("https://streams.example.com/play/e3/2160", played(e3).url)
        assertEquals(AutoPlayPick.LAST_LINK, f.host.played.last().autoPlay)

        // Episode 3's own last stream comes first.
        f.data.streamHistory.record("p", "tt7366338", e3, """{"url":"https://streams.example.com/play/e3/720"}""", stream)
        assertEquals("https://streams.example.com/play/e3/720", played(e3).url)
        assertEquals(AutoPlayPick.LAST_LINK, f.host.played.last().autoPlay)
        assertEquals(emptyList(), f.host.streams, "no list opened")
    }

    @Test
    fun aCardsPlayFromTheBeginningPlaysWhatPlayWouldFromZeroOnce() = runTest {
        val shawshank = MetaSnapshot("tt0111161", "movie", "The Shawshank Redemption", null, "poster")
        // Auto-play off: the stream it last played from, from 0 instead of the library's 1:02:03.
        val remembered = Fixture(this, listOf(cinemeta, streamAddon))
        remembered.data.library.upsertFromMeta("p", shawshank)
        remembered.data.library.recordProgress("p", "tt0111161", "tt0111161", 3_723_000, 8_520_000)
        remembered.data.streamHistory.record("p", "tt0111161", "tt0111161", """{"url":"https://streams.example.com/play/1080"}""", streamAddon.transportUrl)
        val store = remembered.store("movie", "tt0111161", videoAction = DetailVideoAction.PLAY_FROM_START)
        advanceTimeBy(3.seconds)
        runCurrent()
        val played = remembered.host.played.single()
        assertEquals("tt0111161", played.videoId)
        assertEquals("https://streams.example.com/play/1080", played.url)
        assertEquals(0.0, played.startPositionSeconds)
        assertEquals(emptyList(), remembered.host.streams)

        // Carried out once: not again on a reload, and Play resumes as before.
        store.reload()
        runCurrent()
        assertEquals(1, remembered.host.played.size)
        store.play()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertNull(remembered.host.played.last().startPositionSeconds)

        // Without a stream to replay, the list opens, its pick to play from 0.
        val listed = Fixture(this, listOf(cinemeta))
        listed.store("movie", "tt0111161", videoAction = DetailVideoAction.PLAY_FROM_START)
        runCurrent()
        assertEquals(listOf("tt0111161/tt0111161"), listed.host.streams)
        assertEquals(listOf(true), listed.host.fromStart)
        assertEquals(emptyList(), listed.host.played)

        // Auto-play on: its pick, from 0.
        val picked = Fixture(this, listOf(cinemeta, streamAddon))
        picked.data.settings.set("p", ProfileSetting.StreamAutoPlay, AutoPlayMode.FIRST_MATCH)
        picked.store("movie", "tt0111161", videoAction = DetailVideoAction.PLAY_FROM_START)
        advanceTimeBy(3.seconds)
        runCurrent()
        val pick = picked.host.played.single()
        assertEquals("https://streams.example.com/play/2160", pick.url)
        assertEquals(AutoPlayPick.FIRST_MATCH, pick.autoPlay)
        assertEquals(0.0, pick.startPositionSeconds)
    }

    @Test
    fun aCardsChooseAStreamOpensTheListOfItsVideoElseOfTheOnePlayPlays() = runTest {
        val f = Fixture(this, listOf(cinemeta))
        f.data.library.upsertFromMeta("p", series)
        f.data.library.recordProgress("p", "tt7366338", "tt7366338:1:3", 754_000, 3_900_000)
        f.store("series", "tt7366338", videoAction = DetailVideoAction.CHOOSE_STREAM)
        runCurrent()
        assertEquals(listOf("tt7366338/tt7366338:1:3"), f.host.streams, "the episode Play resumes")

        // A Continue Watching card names its video: its list opens, once.
        val card = Fixture(this, listOf(cinemeta))
        val store = card.store("series", "tt7366338", videoId = "tt7366338:1:2", videoAction = DetailVideoAction.CHOOSE_STREAM)
        runCurrent()
        store.reload()
        runCurrent()
        assertEquals(listOf("tt7366338/tt7366338:1:2"), card.host.streams)
        assertEquals(listOf(false), card.host.fromStart)
    }

    @Test
    fun aCardsTrailerOpensTheTitlesFirstTrailerOnce() = runTest {
        val f = Fixture(this, listOf(cinemeta))
        val store = f.store("series", "tt7366338", videoAction = DetailVideoAction.TRAILER)
        runCurrent()
        store.reload()
        runCurrent()
        assertEquals(listOf("https://www.youtube.com/watch?v=s9APLXM9Ei8"), f.host.urls)
        assertEquals(emptyList(), f.host.streams)
        assertEquals(emptyList(), f.host.played)
    }

    @Test
    fun anEpisodesMenuPlaysItFromTheBeginningMarksItsSeasonAndChoosesAStreamWithAutoPlayOn() = runTest {
        val (f, store) = chernobylStreams(AutoPlayMode.OFF)
        val e2 = "tt7366338:1:2"
        val e3 = "tt7366338:1:3"
        f.data.library.upsertFromMeta("p", series)
        f.data.library.recordProgress("p", "tt7366338", e3, 754_000, 3_900_000)
        f.data.streamHistory.record("p", "tt7366338", e3, """{"url":"https://streams.example.com/play/e3/720"}""", streamAddon.transportUrl)
        runCurrent()
        fun menu(videoId: String) = store.state.value.episodes.single { it.videoId == videoId }.menu.map { it.action }
        assertEquals(listOf(MediaAction.PLAY_FROM_START, MediaAction.MARK_WATCHED, MediaAction.MARK_SEASON_WATCHED), menu(e3))
        assertEquals(listOf(MediaAction.MARK_WATCHED, MediaAction.MARK_SEASON_WATCHED), menu(e2), "with auto-play off, the episode's press opens its list")

        // Auto-play off: the stream it last played from, from 0.
        store.episodeMenuAction(e3, MediaAction.PLAY_FROM_START)
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(listOf(e3 to 0.0), f.host.played.map { it.videoId to it.startPositionSeconds })
        assertEquals("https://streams.example.com/play/e3/720", f.host.played.last().url)

        // Auto-play on: Choose a stream joins the menu, and Play from the beginning plays auto-play's pick from 0.
        f.data.settings.set("p", ProfileSetting.StreamAutoPlay, AutoPlayMode.FIRST_MATCH)
        runCurrent()
        assertEquals(listOf(MediaAction.MARK_WATCHED, MediaAction.MARK_SEASON_WATCHED, MediaAction.CHOOSE_STREAM), menu(e2))
        store.episodeMenuAction(e3, MediaAction.PLAY_FROM_START)
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals("https://streams.example.com/play/e3/1080", f.host.played.last().url)
        assertEquals(0.0, f.host.played.last().startPositionSeconds)

        // The season mark is the episode's season's.
        store.episodeMenuAction(e2, MediaAction.MARK_SEASON_WATCHED)
        runCurrent()
        val state = store.state.value
        assertEquals(List(5) { true }, state.episodes.map { it.watched })
        assertTrue(state.seasons.single { it.season == 1 }.watched)
        assertFalse(state.seasons.single { it.season == 0 }.watched, "specials aren't marked")
        assertEquals(listOf(MediaAction.MARK_UNWATCHED, MediaAction.MARK_SEASON_UNWATCHED, MediaAction.CHOOSE_STREAM), menu(e2))
        assertEquals(emptyList(), f.host.streams)
    }

    @Test
    fun anEpisodesMenuOffersPlayFromTheBeginningExactlyWherePlayResumesIt() = runTest {
        val (f, store) = chernobyl()
        runCurrent()
        val e3 = "tt7366338:1:3"
        fun episode() = store.state.value.episodes.single { it.videoId == e3 }
        fun offered() = MediaAction.PLAY_FROM_START in episode().menu.map { it.action }
        f.data.library.upsertFromMeta("p", series)

        // Past the credits: Play starts it at 0 (PlaybackSession.resumePoint), so the menu has nothing to start over.
        f.data.library.recordProgress("p", "tt7366338", e3, 3_600_000, 3_900_000)
        runCurrent()
        assertEquals(3_600_000.0 / 3_900_000, episode().progress)
        assertEquals(episode().infoLine, episode().detailLine, "nothing left past the credits")
        assertFalse(offered(), "past the credits")

        // A position with no duration known: Play resumes it, so the menu offers to start over.
        f.data.library.recordSeek("p", "tt7366338", e3, 754_000, 0)
        runCurrent()
        assertNull(episode().progress)
        assertTrue(offered(), "a position without a duration")
    }

    private suspend fun LibraryRepository.observeLibraryIds() = observeLibrary("p").first().map { it.id }

    /** Chernobyl as the store gets it, for recording progress the way the player does (with the videos). */
    private fun chernobylMeta(): MetaItem =
        (AddonResponse.decode(FixtureCorpus.text("live/cinemeta/meta-series-tt7366338.json")).content as ResourceContent.Meta).item

    private companion object {
        /** 2026-09-21. */
        const val NOW = 1_790_000_000_000L

        /** A series with what Cinemeta's recorded ones don't have (a language) and without what they have (genres). */
        const val EXTRAS = """{"meta":{"id":"tt9","type":"series","name":"Extras","language":" Japanese ","runtime":"24 min",
            "released":"2020-01-01T00:00:00.000Z","country":[],"links":[{"name":"8.1","category":"imdb","url":"https://imdb.com/title/tt9"}],
            "trailerStreams":[{"ytId":"s9APLXM9Ei8"}],"videos":[{"id":"tt9:1:1","season":1,"episode":1,"released":"2020-01-01T00:00:00.000Z"},
            {"id":"tt9:1:2","title":"Later","season":1,"episode":2,"released":"2099-01-01T00:00:00.000Z"}]}}"""
    }
}
