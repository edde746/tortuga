package com.edde746.tortuga.app

import com.edde746.tortuga.data.library.MetaSnapshot
import com.edde746.tortuga.data.transfer.DataCategory
import com.edde746.tortuga.data.transfer.ImportTarget
import com.edde746.tortuga.data.transfer.TransferFile
import com.edde746.tortuga.playback.HardwareDecoder
import com.edde746.tortuga.playback.session.PlayRequest
import com.edde746.tortuga.playback.session.ScriptedEngine
import com.edde746.tortuga.playback.session.choice
import com.edde746.tortuga.playback.session.urlStream
import com.edde746.tortuga.presentation.detail.detailStore
import com.edde746.tortuga.presentation.menu.MediaAction
import com.edde746.tortuga.presentation.menu.mediaActions
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The watch activity the app's own screens and player send the addons that receive it: only to an addon the user
 * agreed to and left on, only for what the user did on this device. The agreement here is the one the user gave on
 * another of their devices, which the synced addon list brings.
 */
class WatchActivityTest {
    private val film = MetaSnapshot(FILM_ID, "movie", "Film", null, "poster")
    private val show = MetaSnapshot(SHOW_ID, "series", "Show", null, "poster")

    private fun library(type: String, id: String, query: String) = "/library/$type/$id/$query.json"

    /** `watched&videoId=` and the ids of [episodes] of [SHOW], as one event's query. */
    private fun watchedEpisodes(episodes: IntRange, action: String = "watched") =
        "action=$action&videoId=" + episodes.joinToString("%2C") { "$SHOW_ID%3A1%3A$it" }

    @Test
    fun aVideoPlayedHereSendsStartPauseAndStop() = runTest {
        val w = AddonWorld(this)
        val p = w.data.profiles.create("Ann", null).id
        w.pullAddons(p, SyncedAddon(TRACKER_URL, receiverManifest("tracker.example"), watchActivity = true))
        val engine = ScriptedEngine()
        val session = w.graph.playbackSession(p, engine, HardwareDecoder.APPLE)

        session.start(PlayRequest(FILM, FILM_ID, choice(urlStream("https://cdn.example/film.mkv"))))
        runCurrent()
        engine.open(duration = 100.0)
        runCurrent()
        engine.play(30.0)
        runCurrent()
        engine.setPaused(true)
        runCurrent()
        session.close()
        runCurrent()

        val path = "/player/movie/$FILM_ID"
        assertEquals(
            listOf(
                "$path/action=start&currentTime=0&duration=100000.json",
                "$path/action=pause&currentTime=30000&duration=100000.json",
                "$path/action=stop&currentTime=30000&duration=100000.json",
            ),
            w.events(),
        )
    }

    @Test
    fun markingASeasonSendsTheEpisodesWhoseMarkChangedAHundredToAnEvent() = runTest {
        val w = AddonWorld(this)
        val p = w.data.profiles.create("Ann", null).id
        w.pullAddons(p, SyncedAddon(META_URL, META_MANIFEST), SyncedAddon(TRACKER_URL, receiverManifest("tracker.example"), watchActivity = true))
        val detail = w.graph.detailStore(p, "series", SHOW_ID, null, NoHosts)
        runCurrent()

        detail.markSeasonWatched(1, watched = true)
        runCurrent()
        assertEquals(
            listOf(library("series", SHOW_ID, watchedEpisodes(1..100)), library("series", SHOW_ID, watchedEpisodes(101..102))),
            w.events(),
            "the 102 episodes out, a hundred to an event; not the one still to come",
        )

        w.requests.clear()
        detail.markSeasonWatched(1, watched = true)
        runCurrent()
        assertEquals(emptyList(), w.events(), "nothing changed, nothing sent")

        detail.episodeMenuAction("$SHOW_ID:1:5", MediaAction.MARK_UNWATCHED)
        runCurrent()
        detail.markSeasonWatched(1, watched = true)
        runCurrent()
        assertEquals(
            listOf(library("series", SHOW_ID, watchedEpisodes(5..5, "unwatched")), library("series", SHOW_ID, watchedEpisodes(5..5))),
            w.events(),
            "only the episode whose mark changed",
        )
        detail.release()
    }

    @Test
    fun theCardMenusLibraryEntriesSendAnEventOnlyWhenTheLibraryChanged() = runTest {
        val w = AddonWorld(this)
        val p = w.data.profiles.create("Ann", null).id
        w.pullAddons(p, SyncedAddon(META_URL, META_MANIFEST), SyncedAddon(TRACKER_URL, receiverManifest("tracker.example"), watchActivity = true))
        val actions = w.graph.mediaActions(p)

        actions.carryOut(film, null, MediaAction.ADD_TO_LIBRARY)
        actions.carryOut(film, null, MediaAction.ADD_TO_LIBRARY)
        actions.carryOut(film, null, MediaAction.REMOVE_FROM_LIBRARY)
        actions.carryOut(film, null, MediaAction.REMOVE_FROM_LIBRARY)
        actions.carryOut(film, null, MediaAction.MARK_WATCHED)
        actions.carryOut(show, "$SHOW_ID:1:3", MediaAction.MARK_WATCHED)
        actions.carryOut(show, "$SHOW_ID:1:3", MediaAction.MARK_WATCHED)
        runCurrent()

        assertEquals(
            listOf(
                library("movie", FILM_ID, "action=libraryAdd"),
                library("movie", FILM_ID, "action=libraryRemove"),
                library("movie", FILM_ID, "action=watched"),
                library("series", SHOW_ID, watchedEpisodes(3..3)),
            ),
            w.events(),
            "a second add, remove or episode mark changes nothing and sends nothing",
        )
    }

    @Test
    fun onlyAnAddonTheUserAgreedToAndLeftOnGetsTheirWatchActivity() = runTest {
        val w = AddonWorld(this)
        val p = w.data.profiles.create("Ann", null).id
        w.manifests["unasked.example"] = receiverManifest("unasked.example")
        w.manifests["off.example"] = receiverManifest("off.example")
        w.pullAddons(
            p,
            SyncedAddon(TRACKER_URL, receiverManifest("tracker.example"), watchActivity = true),
            SyncedAddon("https://unasked.example/manifest.json", receiverManifest("unasked.example")),
            SyncedAddon("https://off.example/manifest.json", receiverManifest("off.example"), enabled = false, watchActivity = true),
        )
        val engine = ScriptedEngine()
        val session = w.graph.playbackSession(p, engine, HardwareDecoder.APPLE)
        session.start(PlayRequest(FILM, FILM_ID, choice(urlStream("https://cdn.example/film.mkv"))))
        runCurrent()
        engine.open(duration = 100.0)
        runCurrent()
        session.close()
        w.graph.mediaActions(p).carryOut(film, null, MediaAction.ADD_TO_LIBRARY)
        runCurrent()

        assertEquals(
            listOf(
                "tracker.example /player/movie/$FILM_ID/action=start&currentTime=0&duration=100000.json",
                "tracker.example /player/movie/$FILM_ID/action=stop&currentTime=0&duration=100000.json",
                "tracker.example /library/movie/$FILM_ID/action=libraryAdd.json",
            ),
            w.requests,
            "the addon without the agreement and the one turned off get nothing",
        )
    }

    @Test
    fun libraryChangesASyncPullOrAFileBringSendNothingAndTheUsersOwnHereDo() = runTest {
        val w = AddonWorld(this)
        val p = w.data.profiles.create("Ann", null).id
        w.pullAddons(p, SyncedAddon(META_URL, META_MANIFEST), SyncedAddon(TRACKER_URL, receiverManifest("tracker.example"), watchActivity = true))
        // The screens that follow the library are open while the changes come in.
        val actions = w.graph.mediaActions(p)
        val detail = w.graph.detailStore(p, "series", SHOW_ID, null, NoHosts)
        runCurrent()
        val phone = w.newDevice(this)
        w.sync(w.data, phone)

        // The user's phone: a film added, watched and played; the season marked; an episode half played.
        phone.library.addToLibrary(p, film)
        phone.library.markWatched(p, film, true)
        phone.library.markSeasonWatched(p, SHOW, 1, true)
        phone.library.recordProgress(p, SHOW_ID, "$SHOW_ID:1:103", 60_000, 1_200_000)
        w.sync(phone, w.data)
        runCurrent()
        // A file exported from the phone, with a title the phone added since.
        val other = MetaSnapshot("tt0000003", "movie", "Other film", null, "poster")
        phone.library.addToLibrary(p, other)
        phone.library.markWatched(p, other, true)
        val file = TransferFile.decode(phone.transfer.export(setOf(p), setOf(DataCategory.LIBRARY, DataCategory.PROGRESS), "1.0.0", w.clock.now).encode())
        w.data.transfer.import(file, setOf(DataCategory.LIBRARY, DataCategory.PROGRESS), mapOf(p to ImportTarget.Existing(p)))
        runCurrent()
        assertEquals(SHOW_ID, w.data.library.item(p, SHOW_ID)?.id, "the phone's changes are here")
        assertEquals("tt0000003", w.data.library.item(p, "tt0000003")?.id, "the file's are too")
        assertEquals(emptyList(), w.events(), "none of them was this user's doing here")

        actions.carryOut(MetaSnapshot("tt0000004", "movie", "Third film", null, "poster"), null, MediaAction.ADD_TO_LIBRARY)
        runCurrent()
        assertEquals(listOf(library("movie", "tt0000004", "action=libraryAdd")), w.events())
        detail.release()
    }
}
