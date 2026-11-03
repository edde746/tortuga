import XCTest

/// Downloads by touch on iPhone and iPad and with the pointer on the Mac, in one dev session that keeps its database
/// (`--dev-data`), against the test addon's download
/// items (test_addon.py --downloads) on the media server's download routes (tools/media/serve.py), the tests in order,
/// each finding what the one before left. The throttled movie's and the lazy link's transfers are held too
/// (--download-hold, --download-hold-lazy): each gets only the bytes a test grants (`downloadHold`), so it is partway
/// at every check that needs it so, however long the steps before took:
/// 1. Settings → Downloads: English picked as the subtitles saved with each download.
/// 2. Four movies queued from their Details' Download (the Streams sheet in download mode, its stream picked): the
///    throttled one downloads, the others wait their turn (one at a time), and its progress moves on. The app is killed
///    mid-download and launched again: the system's transfer went on meanwhile (the row is further along) and is
///    reattached (the transport's `reattach:` line in the run's app.log). Paused from its row's ⋯, the next one (the
///    lazy hosted link) starts; resumed, it waits for that one. The lazy link's download is paused in turn, so
///    the first completes from where it was, then the third, then the fourth, whose CDN links drop their transfer
///    partway (the cut link: it completes in chunks from fresh links). Once the lazy link's CDN link has expired, it is
///    resumed and completes from a fresh link, continuing from where it was paused (its row never goes back).
/// 3. The season series: S1E1's 1080p stream by hand from its episode menu, then Download season… (All episodes; the
///    episode whose 1080p stream is marked not ready gets another), Keep synced at five unwatched episodes (Season 2's
///    first joins; the menu's Manage Keep synced opens Keep synced, listing the rule), and S1E1 marked watched tops it
///    up with Season 2's second; Delete all downloads asks first, and Cancel keeps them; the Downloads view lists the
///    series by season, and its Delete downloads asks first too. The Download menu opens from the action row's
///    Download, or on iOS from its ⋯ where the row has no room for Download (iPad's details column beside the episodes).
/// 4. Offline (`--dev-setting offline=true`): Home's offline state, Go to Downloads (the Downloads view, its offline
///    note, its view menu staying put while the list scrolls: on iPad in landscape, the list taller than the screen
///    only there), the redirect movie played from its row and paused, its saved English subtitles picked (on iOS drawn
///    on a seek; the info panel's source: Downloaded), then its Detail from the row's menu, shown from the details
///    saved with it, which a note under the title says.
/// 5. iOS (`DownloadsTests+iOS`): offline with no addon to answer (the test addon removed in Settings → Addons; the
///    next test's launch installs it again), Mark as watched from Home's Continue Watching cards: the season series'
///    S1E3 is marked from the details saved with its downloads and the card moves on to S1E4; the test addon's other
///    series, nothing of it saved, is left unmarked and a toast (`app.menuNotice`) says its details didn't load.
/// 6. Settings → Downloads with the downloads' storage, and Delete all, confirmed, empties the Downloads view (the
///    Mac's downloads folder is the product's, in Application Support: nothing stays there).
/// 7. Home's Downloads shortcut (`board.shortcut.downloads`, a chip of the row under Continue Watching, on the Mac above
///    the shelves): "Downloads" with nothing downloading, opening Library's Downloads; with the throttled movie queued,
///    its label the download's progress ("Downloading 1, 4%"), which moves on, the chip on the row's line (on the Mac
///    nothing of Home's in the toolbar); on iOS again at a large accessibility text size, relaunched mid-download, where
///    the system's audit finds no label of the row's chips cut short; it opens Library's Downloads, where on iOS,
///    relaunched at the largest text size, the audit finds no text clipped or cut short and none that keeps its size,
///    and the download is cancelled; back on Home it reads "Downloads" again.
/// 8. Keep synced's screen, on a database of its own (`DownloadsTests+KeepSynced`): rules set from Detail and listed,
///    one paused, the notice of a rule topping itself up, the iPhone at the largest text size, and Stop deleting what
///    a rule downloaded.
/// 9. Paused from Detail's menus while the download reports its progress, 8 times each: the slow movie, its hold
///    lifted, from its Download menu (on iOS ⋯'s Download where the row has no room), Paused within 5 s and resumed by
///    Download, then the slow series' S1E1 from its episode's ⋯, Paused within 5 s and resumed from the ⋯ (a menu built
///    again while open dropped the pick).
/// Each step keeps its screenshots. iPhone's tabs each keep their own stack only while shown: Detail is opened again
/// by a launch (`--dev-open`) rather than found where it was left.
final class DownloadsTests: TortugaUITestCase {
    /// The dev database the tests share, fresh for each run of the test process.
    static let data = "downloads-" + UUID().uuidString.prefix(8)

    #if os(macOS)
    /// The screens the Mac's launches open (`--dev-open`): Home. iOS's open on Home without one.
    static let homeOpen = ["board"]
    #else
    static let homeOpen: [String] = []
    #endif

    func test1SettingsSaveEnglishSubtitles() throws {
        executionTimeAllowance = 300
        try launchDownloads(Self.data, open: Self.homeOpen)
        openSection("downloads")
        require("settings.downloads.deleteAll", timeout: 20, "Settings → Downloads shows")
        require("settings.downloads.atOnce", "Settings → Downloads has downloads at once")
        shot("settings downloads")
        activate(require("settings.downloads.subtitleLanguages", "Settings → Downloads has the subtitle languages"))
        let search = require("settings.languages.search", "the subtitle languages' editor opens")
        activate(search)
        search.typeText("English")
        activate(require("settings.languages.available.0", timeout: 10, "searching English lists it"))
        require("settings.languages.chosen.0", timeout: 10, "English is chosen")
        shot("subtitle languages: English")
        closeSheet("settings.languages.close", showing: "settings.languages.search", "Close (an iPhone: a pull down) closes the editor")
        let languages = element("settings.downloads.subtitleLanguages")
        if !waitUntil(5, { languages.exists && fullText(of: languages).contains("English") }) {
            fail("the subtitle languages row reads \"\(fullText(of: languages))\", not English")
        }
        require("settings.downloads.useProfileLanguages", "a list chosen here offers the profile's languages back")
        shot("settings downloads: English subtitles")
    }

    func test2QueueProgressKillPauseResumeAndExpiringLinks() throws {
        // Four throttled transfers, one of them twice, a link left to expire and links that die: longer than the run's
        // 5 minutes.
        executionTimeAllowance = 1800
        let (slowId, slow) = Self.slowMovie
        let (lazyId, lazy) = Self.lazyMovie
        let (redirectId, redirect) = Self.redirectMovie
        let (cutId, cut) = Self.cutMovie
        // The four Details on top of each other over Home, the throttled movie's on top.
        try launchDownloads(Self.data, open: ["board"] + [cutId, redirectId, lazyId, slowId].map { "detail:movie:\($0)" })
        // The throttled movie and the lazy link held from their next byte (an earlier test or pass may have lifted
        // their holds or left bytes granted), then the throttled one granted a step, so its row shows a percentage.
        // Each resolve of a held stream (its first start, a start in a process that hasn't resolved it, a link expired)
        // reads 64 KiB at the file's head and at its tail first, so a step is granted before each. The throttled one
        // gets four steps (8 MiB, 55 %) before its hold is lifted, so it is still downloading at every check before.
        try downloadHold("slow", "none")
        try downloadHold("lazy", "none")
        try downloadHold("slow", "\(Self.holdStep)")
        queueFromDetail(slow, shots: true)
        goBack()
        queueFromDetail(lazy, shots: false)
        goBack()
        queueFromDetail(redirect, shots: false)
        goBack()
        queueFromDetail(cut, shots: false)
        #if os(iOS)
        goBack()
        #endif

        openDownloads()
        requireDownload(slow, "Downloading", timeout: 30, "the first movie queued downloads")
        requireDownload(lazy, "Queued", timeout: 5, "one download at a time: the second waits")
        requireDownload(redirect, "Queued", timeout: 5, "one download at a time: the third waits")
        requireDownload(cut, "Queued", timeout: 5, "one download at a time: the fourth waits")
        shot("queue: one downloading, three queued")
        guard let first = downloadPercent(slow) else { return fail("the downloading row shows no percentage: \(downloadText(slow))") }
        // A step more: it moves on from wherever the first step's bytes left it.
        try downloadHold("slow", "\(Self.holdStep)")
        if !waitUntil(15, { (downloadPercent(slow) ?? 0) > first }) {
            fail("the download's progress stays at \(first)%: \(downloadText(slow))")
        }
        shot("progress")

        // Killed mid-download: the system's transfer goes on without the app for 8 s, and the relaunch reattaches it.
        // Time without the app is what this step needs; nothing the test can read shows the transfer meanwhile.
        let killedAt = downloadPercent(slow) ?? first
        app.terminate()
        // A step granted once the app is gone, before the relaunch: 13.7 points, more than the 5 the relaunched row
        // must show, which only the system's transfer can take (at 128 KiB/s it takes 6.9 points in the 8 s without the
        // app).
        try downloadHold("slow", "\(Self.holdStep)")
        if !holds(for: 8, { app.state == .notRunning }) { fail("the app killed mid-download runs again within 8 s") }
        try launchDownloads(Self.data, open: ["library"])
        openDownloads(fromTab: false)
        requireDownload(slow, "Downloading", timeout: 30, "relaunched, the killed download is downloading again")
        guard let recovered = downloadPercent(slow) else { return fail("the reattached row shows no percentage: \(downloadText(slow))") }
        if recovered <= killedAt + 5 {
            fail("relaunched 8 s after the kill at \(killedAt)%, the download reads \(recovered)%: the transfer didn't go on")
        }
        shot("relaunched: further than the \(killedAt)% it was killed at")

        // Paused from its ⋯: the next download (the lazy link, its CDN link handed out now) starts; resumed, the first
        // waits for it. The lazy link's step, granted before it starts, covers its probes and the 10 % it must reach
        // (1.66 MB of 2.1), and keeps it at 12.9 % at most until it is paused.
        try downloadHold("lazy", "\(Self.holdStep)")
        downloadAction(slow, "pause", "Pause")
        requireDownload(slow, "Paused", timeout: 15, "Pause pauses the download")
        requireDownload(lazy, "Downloading", timeout: 30, "with the first paused, the next one downloads")
        let lazyStarted = Date()
        shot("paused, the next one downloading")
        downloadAction(slow, "resume", "Resume")
        requireDownload(slow, "Queued", timeout: 15, "Resume queues the paused download behind the running one")
        shot("resumed: queued behind the running one")

        // The lazy link's download paused partway: the first goes on from where it was paused, then the third, then
        // the cut link's.
        if !waitUntil(20, { (downloadPercent(lazy) ?? 0) >= 10 }) { fail("the lazy link's download doesn't get going: \(downloadText(lazy))") }
        // The first starts again in a process that hasn't resolved its stream (the relaunched one): a step for that.
        try downloadHold("slow", "\(Self.holdStep)")
        downloadAction(lazy, "pause", "Pause")
        requireDownload(lazy, "Paused", timeout: 15, "Pause pauses the lazy link's download")
        guard let pausedAt = downloadShownPercent(lazy) else { return fail("the paused row shows no percentage: \(downloadText(lazy))") }
        requireDownload(slow, "Downloading", timeout: 30, "with the lazy link paused, the first one goes on")
        shot("lazy link paused, the first going on")
        // Its hold lifted: the rest, at most the whole file, comes in 117 s at 128 KiB/s.
        try downloadHold("slow", "all")
        requireDownload(slow, "Downloaded", timeout: 180, "the paused and resumed download completes")
        requireDownload(redirect, "Downloaded", timeout: 60, "the third download completes")
        requireDownload(cut, "Downloading", timeout: 30, "then the cut link's download starts")

        // Resumed once its CDN link has expired (--link-expire, a minute): it continues from a fresh link where it was
        // paused, once the cut link's download, its CDN links dropping their transfers partway, has completed. The
        // expiry is the test addon's clock, which nothing the app shows reads: the download stays paused until then.
        let expiry = Self.linkLifetime + 5 - Date().timeIntervalSince(lazyStarted)
        if expiry > 0, !holds(for: expiry, { downloadText(lazy).contains("Paused") }) {
            fail("the lazy link's download doesn't stay paused until its CDN link has expired: \(downloadText(lazy))")
        }
        // The lazy link's hold lifted before it resolves again: the rest comes at 128 KiB/s.
        try downloadHold("lazy", "all")
        downloadAction(lazy, "resume", "Resume")
        requireResumed(lazy, from: pausedAt, timeout: 600, "the lazy link's download, resumed past its link's expiry")
        requireDownload(cut, "Downloaded", timeout: 300, "the cut link's download, its links dying partway, completes")
        shot("all four downloaded")
    }

    func test3SeasonDownloadAndKeepSynced() throws {
        executionTimeAllowance = 900
        let series = Self.seasonSeries
        try launchDownloads(Self.data, open: ["board", "detail:series:\(series)"])
        #if os(macOS)
        require("detail.download", timeout: 60, "the series' Detail offers Download")
        #else
        // The download's state comes just after the title: Download in the action row, or ⋯ where the row has no room
        // for it (whose Download `openDownloadMenu` picks).
        if !waitUntil(60, { element("detail.download").exists || element("detail.overflow").exists }) {
            fail("the series' Detail offers no Download and no ⋯ after 60 s")
        }
        #endif
        shot("series detail")

        // S1E1's 1080p stream by hand, from the episode's menu: the pick the season's episodes follow.
        let episode = ready(scrollTo("detail.episode.0", "the series lists its first episode"))
        #if os(macOS)
        episode.rightClick()
        #else
        episode.press(forDuration: 1.2)
        #endif
        let byHand = require("detail.menu.item.download", timeout: 5, "the episode's menu offers Download")
        shot("episode menu: Download")
        pickMenuEntry(byHand)
        require("streams.notice", timeout: 30, "Download episode opens the Streams sheet in download mode")
        let stream = require("streams.row.0", timeout: 30, "the download mode lists the episode's streams")
        shot("episode streams in download mode")
        activate(stream)
        requireGone("streams.row.0", timeout: 10, "picking the stream closes the sheet")
        #if os(macOS)
        // The Mac reads an episode's download state as part of its row (`detail.episode.<n>`).
        let first = element("detail.episode.0")
        if !waitUntil(30, { first.exists && ["Queued", "Downloading", "Downloaded"].contains { fullText(of: first).contains($0) } }) {
            fail("S1E1 shows no download state: \"\(fullText(of: first))\"")
        }
        #else
        require("detail.episode.0.download", timeout: 30, "S1E1 shows its download's state")
        #endif

        // Download season…: Season 1's episodes, each stream picked as S1E1's was.
        openDownloadMenu()
        let all = downloadMenuEntry("season:ALL", "All episodes", "the series' Download menu offers all of Season 1's episodes")
        shot("series download menu")
        pickMenuEntry(all)
        require("detail.downloadNotice", timeout: 10, "Download season… says what it added")
        shot("season 1 added")
        requireEpisodesDownloaded(Self.seasonOneTitles.count, season: 1)
        shot("season 1 downloaded")

        // Keep synced at five unwatched episodes: Season 2's first joins the four of Season 1. The menu's Manage Keep
        // synced then opens Keep synced, listing the rule; Back returns to Detail.
        openDownloadMenu()
        pickMenuEntry(downloadMenuEntry("sync:5", "Keep 5 unwatched episodes", "the series' Download menu offers Keep 5 unwatched episodes"))
        openDownloadMenu()
        let manage = downloadMenuEntry("syncRules", "Manage Keep synced", "with a rule, the menu offers Manage Keep synced")
        shot("keep synced: 5 unwatched episodes")
        pickMenuEntry(manage)
        require("keepSynced.rule.\(series)", timeout: 10, "Manage Keep synced opens Keep synced, listing the series' rule")
        shot("keep synced: the series' rule")
        goBack()
        require("detail.season.1", timeout: 10, "Back from Keep synced returns to the series' Detail")
        selectSeason(1)
        requireEpisodesDownloaded(1, season: 2)
        shot("keep synced: season 2's first episode")

        // S1E1 watched: one unwatched episode fewer, so the rule tops up with S2E2.
        selectSeason(0)
        let watched = ready(scrollTo("detail.episode.0", "Season 1 lists S1E1"))
        #if os(macOS)
        watched.rightClick()
        #else
        watched.press(forDuration: 1.2)
        #endif
        pickMenuEntry(require("detail.menu.item.markWatched", timeout: 5, "the episode's menu offers Mark as watched"))
        if !waitUntil(10, { element("detail.episode.0").exists && isWatched(element("detail.episode.0")) }) { fail("Mark as watched doesn't mark S1E1 watched") }
        selectSeason(1)
        requireEpisodesDownloaded(2, season: 2)
        shot("S1E1 watched: keep synced topped up")

        // Delete all downloads (the menu's last entry, scrolled to where the menu is taller than the screen) asks
        // first; Cancel keeps every one.
        openDownloadMenu()
        pickMenuEntry(menuEntry("Delete all downloads", "the series' Download menu offers Delete all downloads"))
        let keepAll = require("detail.downloadDelete.cancel", timeout: 5, "Delete all downloads asks first")
        require("detail.downloadDelete.confirm", "the question offers Delete")
        shot("delete all downloads: the question")
        activate(keepAll)
        requireGone("detail.downloadDelete.cancel", "Cancel closes the question")
        requireEpisodesDownloaded(2, season: 2)

        openDownloads()
        require("downloads.season.\(series).1", "the Downloads view lists Season 1 under the series")
        require("downloads.season.\(series).2", "the Downloads view lists Season 2 under the series")
        requireDownload(Self.seasonTwoTitles[1], "Downloaded", timeout: 30, "the Downloads view lists S2E2")
        shot("downloads: the series by season")

        // The series' Delete downloads asks first; Cancel keeps the series.
        deleteSeriesDownloads(series)
        let keepSeries = require("downloads.deleteGroup.cancel", timeout: 5, "the series' Delete downloads asks first")
        require("downloads.deleteGroup.confirm", "the question offers Delete")
        shot("downloads: the series' delete question")
        activate(keepSeries)
        requireGone("downloads.deleteGroup.cancel", "Cancel closes the question")
        requireDownload(Self.seasonTwoTitles[1], "Downloaded", timeout: 5, "Cancel keeps the series' downloads")
    }

    func test4OfflinePlaysADownloadWithItsSavedSubtitles() throws {
        executionTimeAllowance = 600
        let redirect = Self.redirectMovie.1
        try launchDownloads(Self.data, open: Self.homeOpen, settings: ["offline=true"])
        require("offline.state", timeout: 30, "offline, Home shows the offline state")
        shot("home offline")
        activate(require("offline.openDownloads", "the offline state offers Go to Downloads"))
        require("downloads.offline", timeout: 10, "Go to Downloads opens the Downloads view with its offline note")
        shot("offline: downloads")
        // The view menu stays at the top (on the Mac on the toolbar's band) while the downloads scroll under it. An iPad
        // in portrait holds the whole list: in landscape, where it launches again (`quitAndTurn`), it has room to move;
        // then in portrait again for the rest.
        #if os(iOS)
        if isPad {
            addTeardownBlock { TortugaUITestCase.quitAndTurn(to: .portrait) }
            Self.quitAndTurn(to: .landscapeLeft)
            try openOfflineDownloads()
        }
        #endif
        requireHeaderStays("library.view", scrolling: "downloads.offline", "Downloads' view menu")
        shot("offline: downloads scrolled")
        #if os(iOS)
        if isPad {
            Self.quitAndTurn(to: .portrait)
            try openOfflineDownloads()
        }
        #endif

        // A completed download plays from its file, with the English subtitles saved with it (its row below the
        // season series' and the other movies').
        let row = scrollToDownload(redirect)
        activate(row)
        let video = require("player.video", timeout: 30, "a completed download plays when picked")
        requirePlayback(1, timeout: 30)
        #if os(macOS)
        showControls(video)
        #else
        // Paused first: the file runs 20 s, which the menus below outlast on a slow simulator, and at its end the video
        // stops with the controls pinned up.
        pauseWithTheControls(video, "play/pause pauses")
        showPlayerControls(video, "paused, the controls stay up")
        #endif
        activate(element("player.subtitles"))
        let english = menuEntry("English", "the subtitle menu lists the saved English subtitles")
        shot("offline player: subtitle menu")
        pickMenuEntry(english)
        let subtitles = element("player.subtitles")
        #if os(macOS)
        // The pick shows on the subtitle button: the controls stay up on their timer wherever the menu left the
        // pointer (this short menu hangs below the window).
        if !waitUntil(5, { subtitles.exists && subtitles.value as? String == "English" }) {
            fail(subtitles.exists
                ? "after picking English, the subtitle menu shows '\(subtitles.value as? String ?? "nothing")'"
                : "picking English hides the controls at once")
        }
        app.typeKey(" ", modifierFlags: [])
        showControls(video)
        if !waitUntil(5, { element("player.playPause").exists && element("player.playPause").label == "Play" }) { fail("Space doesn't pause the video") }
        #else
        if !waitUntil(5, { subtitles.exists && subtitles.value as? String == "English" }) {
            fail("after picking English, the subtitle menu shows '\(subtitles.value as? String ?? "nothing")'")
        }
        // A track picked while paused draws its line once the player seeks: a tap on the timeline at 7.25 s of the
        // 20 s, in line 2 of the SRT (5–9.5 s).
        ready("player.scrub").coordinate(withNormalizedOffset: CGVector(dx: 7.25 / 20, dy: 0.5)).tap()
        if !waitUntil(10, { playerPosition().map { (5...9).contains($0) } ?? false }) {
            fail("a tap on the timeline at 7.25 s left the download at \(playerPosition() ?? 0) s")
        }
        #endif
        // The paused frame redrawn with its subtitle line before the shot.
        _ = screenSettles()
        shot("offline player: saved English subtitles")
        togglePlaybackInfo()
        let source = infoRow("Source")
        if !source.exists(within: 5) || !fullText(of: source).contains("Downloaded") {
            fail("the playback info's source reads \"\(source.exists ? fullText(of: source) : "nothing")\", not Downloaded")
        }
        shot("offline player: source Downloaded")
        #if os(macOS)
        showControls(video)
        ready("player.back").click()
        requireGone("player.video", "the back button closes the player")
        #else
        // The info's sheet covers the controls (on an iPhone in landscape the whole player): closed first, as a person
        // does, before the back button.
        closeSheet("player.info.close", showing: "player.info.panel", "Close (an iPhone: a pull down) closes the playback info")
        closePlayer(video, "the player closes")
        #endif

        // Its Detail, from the details saved with the download.
        scrollToDownload(redirect)
        downloadAction(redirect, "openDetail", "Go to details")
        let note = require("detail.offlineNote", timeout: 30, "offline, Detail shows the details saved with the download")
        let title = require("detail.title", timeout: 5, "offline, Detail shows the saved title").frame
        if note.frame.intersects(title) || note.frame.minY < title.maxY {
            fail("the offline note at \(note.frame) isn't under the title at \(title)")
        }
        shot("offline detail from the saved details")
    }

    func test6SettingsShowTheStorageAndDeleteAll() throws {
        executionTimeAllowance = 300
        try launchDownloads(Self.data, open: Self.homeOpen)
        openSection("downloads")
        let storage = require("settings.downloads.storage", timeout: 20, "Settings → Downloads shows the storage")
        if !waitUntil(10, { fullText(of: storage).contains("MB used") }) {
            fail("the storage reads \"\(fullText(of: storage))\", not the downloads' megabytes")
        }
        shot("settings downloads: storage")
        #if os(macOS)
        activate(require("settings.downloads.deleteAll", "Settings → Downloads has Delete all"))
        #else
        activate(swipeUntilShown("settings.downloads.deleteAll", "Settings → Downloads has Delete all"))
        #endif
        let confirm = require("settings.downloads.deleteAll.confirm", timeout: 5, "Delete all asks first")
        shot("delete all: confirmation")
        activate(confirm)
        openDownloads()
        require("downloads.empty", timeout: 20, "Delete all leaves the Downloads view empty")
        shot("downloads deleted")
    }

    func test7HomesDownloadsShortcutFollowsADownload() throws {
        executionTimeAllowance = 600
        let (slowId, slow) = Self.slowMovie
        try launchDownloads(Self.data, open: Self.homeOpen)
        #if os(macOS)
        require("board.row.0.item.0", timeout: 90, "the Board shows its first shelf")
        #else
        requireTabScreen("nav.board", "Home shows at launch")
        #endif
        let shortcut = require("board.shortcut.downloads", timeout: 30, "Home's shortcuts offer Downloads")
        if shortcut.label != "Downloads" { fail("with nothing downloading, Home's Downloads reads \"\(shortcut.label)\"") }
        shot("home at rest")
        activate(shortcut)
        require("downloads.empty", timeout: 20, "Home's Downloads opens Library's Downloads, empty")
        shot("downloads from home at rest")

        try launchDownloads(Self.data, open: ["board", "detail:movie:\(slowId)"])
        // The throttled movie held from its next byte (test 2 lifted its hold), then a step granted, so the chip shows
        // a percentage past 0. Two steps more, and on iOS one before the relaunch (which may resolve its stream again)
        // and one after the audit there, keep it under 70 %, so it is still downloading when it is cancelled below,
        // whatever the steps took.
        try downloadHold("slow", "none")
        try downloadHold("slow", "\(Self.holdStep)")
        queueFromDetail(slow, shots: false)
        goBack()
        let chip = requireHomeDownloads("Home's Downloads follows the movie downloading")
        // It follows the download as the bytes come.
        let first = Self.lastPercent(in: chip.label) ?? 0
        // Two steps more (27 points) cover the 15 it must move on from wherever the first left it.
        try downloadHold("slow", "\(2 * Self.holdStep)")
        if !waitUntil(40, { chip.exists && (Self.lastPercent(in: chip.label) ?? 0) >= first + 15 }) {
            fail("Home's Downloads doesn't follow the download: from \(first)%, it reads \"\(chip.label)\" after 40 s")
        }
        shot("home: downloading")

        #if os(iOS)
        // Relaunched at a large text size: the system's transfer goes on, and the chip follows it, the row's labels
        // whole (a name's cap grows with the text). Held from its next byte, the label stops moving, so the audit, which
        // takes it from the smallest size to the largest, reads one text at every size; a step more then lets it go on.
        try downloadHold("slow", "\(Self.holdStep)")
        try launchDownloads(Self.data, textSize: "UICTContentSizeCategoryAccessibilityL")
        let downloading = requireHomeDownloads("at a large text size, Home's Downloads follows the movie downloading")
        try downloadHold("slow", "none")
        let settled = waitUntil(30) {
            let label = downloading.label
            return holds(for: 3) { downloading.label == label }
        }
        if !settled { fail("held, Home's Downloads still moves on after 30 s: it reads \"\(downloading.label)\"") }
        shot("home: downloading, large text")
        var issues = textAuditIssues("Home's shortcuts at a large text size", within: [element("board.shortcuts")])
        try downloadHold("slow", "\(Self.holdStep)")
        #else
        let downloading = chip
        let issues: [String] = []
        #endif
        activate(downloading)
        requireDownload(slow, "Downloading", timeout: 30, "Home's Downloads opens Library's Downloads with the movie")
        shot("downloads from home")
        #if os(iOS)
        // At the largest text size the downloading row puts its text under its artwork, whole, and the header row its
        // Pause all under the view menu, whose name stays whole; the audit takes them from the smallest size to it.
        try launchDownloads(Self.data, textSize: Self.largestTextSize)
        openDownloads()
        requireDownload(slow, "Downloading", timeout: 30, "at the largest text size, Library's Downloads lists the movie downloading")
        screenSettles()
        shot("downloads, largest text")
        let header = [element("library.view"), element("downloads.pauseAll")]
        issues += textAuditIssues("Library's Downloads at the largest text size", within: [downloadRow(slow)] + header)
        #endif
        downloadAction(slow, "cancel", "Cancel download")
        activate(navItem("nav.board", "the sidebar lists Home"))
        let rest = require("board.shortcut.downloads", timeout: 15, "Home's shortcuts still offer Downloads")
        if !waitUntil(15, { rest.label == "Downloads" }) {
            fail("with the download cancelled, Home's Downloads reads \"\(rest.label)\", not Downloads")
        }
        shot("home: download cancelled")
        if !issues.isEmpty { fail("the audit finds \(issues.count) issues: \(issues.joined(separator: "; "))") }
    }

    func test9PausesFromDetailsMenusWhileTheDownloadMoves() throws {
        executionTimeAllowance = 900
        let (slowId, slow) = Self.slowMovie
        try launchDownloads(Self.data, open: ["board", "detail:movie:\(slowId)"])
        // Its hold lifted (an earlier test may have left it held): the download moves at its rate through every round.
        try downloadHold("slow", "all")
        queueFromDetail(slow, shots: false)
        let movie = element("detail.download.label")
        if !requireState(of: movie, "Downloading", timeout: 60, "\(slow) downloads") { return }
        // Paused within 5 s of each pick, while the download reports its progress (at most every 500 ms).
        for round in 1...Self.pauseRounds {
            openDownloadMenu()
            pickMenuEntry(downloadMenuEntry("pause", "Pause download", "round \(round): \(slow)'s Download menu offers Pause download"))
            if !requireState(of: movie, "Paused", timeout: 5, "round \(round): Pause download in \(slow)'s Download menu") { return }
            if round == 1 { shot("movie paused from its Download menu") }
            // Paused, Download resumes at once.
            openDownloadMenu()
            if !requireState(of: movie, "Downloading", timeout: 30, "round \(round): \(slow) resumed by Download") { return }
        }
        openDownloadMenu()
        pickMenuEntry(downloadMenuEntry("cancel", "Cancel download", "\(slow)'s Download menu offers Cancel download"))
        requireGone("detail.download.label", "Cancel download in \(slow)'s Download menu")

        try launchDownloads(Self.data, open: ["board", "detail:series:\(Self.slowSeries)"])
        let more = "detail.episode.0.menu"
        activate(scrollTo(more, "the slow series lists its first episode with its ⋯ button"))
        pickMenuEntry(require("detail.menu.item.download", timeout: 5, "the episode's ⋯ offers Download"))
        require("streams.notice", timeout: 30, "Download episode opens the Streams sheet in download mode")
        activate(require("streams.row.0", timeout: 30, "the download mode lists the episode's stream"))
        requireGone("streams.row.0", timeout: 10, "picking the stream closes the sheet")
        #if os(macOS)
        // The Mac reads an episode's download state as part of its row.
        let episode = element("detail.episode.0")
        #else
        let episode = element("detail.episode.0.download")
        #endif
        // An episode's line reads its download's percentage while it downloads ("51%"), Paused once paused.
        let downloading = "%"
        if !requireState(of: episode, downloading, timeout: 60, "S1E1 downloads") { return }
        for round in 1...Self.pauseRounds {
            activate(scrollTo(more, "round \(round): S1E1's ⋯ button"))
            pickMenuEntry(require("detail.menu.item.pauseDownload", timeout: 5, "round \(round): S1E1's ⋯ offers Pause download"))
            if !requireState(of: episode, "Paused", timeout: 5, "round \(round): Pause download in S1E1's ⋯") { return }
            if round == 1 { shot("episode paused from its ⋯") }
            activate(scrollTo(more, "round \(round): S1E1's ⋯ button, paused"))
            pickMenuEntry(require("detail.menu.item.resumeDownload", timeout: 5, "round \(round): S1E1's ⋯ offers Resume download"))
            if !requireState(of: episode, downloading, timeout: 30, "round \(round): Resume download in S1E1's ⋯") { return }
        }
        activate(scrollTo(more, "S1E1's ⋯ button"))
        pickMenuEntry(require("detail.menu.item.cancelDownload", timeout: 5, "S1E1's ⋯ offers Cancel download"))
    }

    /// How many times `test9PausesFromDetailsMenusWhileTheDownloadMoves` pauses and resumes each download.
    private static let pauseRounds = 8

    /// Waits up to `timeout` for `label` (a download's state on Detail) to read `state`; fails with `what` and what it
    /// reads when it doesn't. Whether it did.
    private func requireState(of label: XCUIElement, _ state: String, timeout: TimeInterval, _ what: String) -> Bool {
        if waitUntil(timeout, { label.exists && fullText(of: label).contains(state) }) { return true }
        fail("\(what): it reads \"\(fullText(of: label))\" after \(Int(timeout)) s, not \(state)")
        return false
    }

    /// Home's Downloads shortcut once its label reads the download's progress past 0% ("Downloading 1, 4%"), on the
    /// line of the row's other chips (Watch together, Library), and on the Mac Home's toolbar holding the sidebar's
    /// toggle alone: no Downloads there under any name.
    @discardableResult
    private func requireHomeDownloads(_ what: String) -> XCUIElement {
        let chip = require("board.shortcut.downloads", timeout: 30, what)
        if !waitUntil(30, { chip.exists && (Self.lastPercent(in: chip.label) ?? 0) > 0 }) {
            fail("\(what): it reads \"\(chip.label)\", no progress past 0% after 30 s")
        }
        let library = require("board.shortcut.library", "Home's shortcuts offer Library")
        if abs(chip.frame.midY - library.frame.midY) > 2 || chip.frame.maxX > library.frame.minX {
            fail("\(what): Downloads (\(chip.frame)) isn't on the row's line before Library (\(library.frame))")
        }
        #if os(macOS)
        let toolbar = app.toolbars
        let controls = [toolbar.buttons, toolbar.menuButtons, toolbar.popUpButtons].flatMap { $0.allElementsBoundByIndex.map(\.label) }
        if controls.count != 1 || !["Hide Sidebar", "Show Sidebar"].contains(controls[0]) {
            fail("\(what): Home's toolbar holds \(controls), not the sidebar's toggle alone")
        }
        #endif
        return chip
    }

    /// From a movie's Detail: Download opens the Streams sheet in download mode, and its stream queues the download,
    /// closing the sheet; Detail then shows the download's state.
    private func queueFromDetail(_ title: String, shots: Bool) {
        let download = require("detail.download", timeout: 60, "\(title)'s Detail offers Download")
        if shots { shot("movie detail: Download") }
        activate(download)
        require("streams.notice", timeout: 30, "Download opens the Streams sheet in download mode with its notice")
        let row = require("streams.row.0", timeout: 30, "the download mode lists the movie's stream")
        if shots { shot("streams sheet in download mode") }
        activate(row)
        requireGone("streams.row.0", timeout: 10, "picking the stream closes the sheet")
        require("detail.download.label", timeout: 20, "\(title)'s Detail shows the download's state")
        if shots { shot("movie detail: downloading") }
    }

    /// Library (unless it shows: iPhone's tab, the iPad's tab bar's, the Mac's sidebar's), then Downloads in its view
    /// menu; on the Mac its rows start at the view menu's leading edge.
    func openDownloads(fromTab: Bool = true) {
        if fromTab { activate(navItem("nav.library", "the sidebar lists Library")) }
        activate(require("library.view", timeout: 30, "the Library shows its view menu"))
        pickMenuEntry(require("library.view.option.2", timeout: 5, "the view menu lists Downloads"))
        if !waitUntil(10, { anyDownloadRow.exists || element("downloads.empty").exists }) {
            fail("picking Downloads in the view menu shows no downloads")
        }
        #if os(macOS)
        // A row that is a button (not a progress indicator) spans the row from its artwork.
        let row = app.buttons.matching(NSPredicate(
            format: "identifier BEGINSWITH 'downloads.row.' AND NOT (identifier ENDSWITH '.menu')"
        )).firstMatch
        let menu = element("library.view").frame
        if row.exists, abs(row.frame.minX - menu.minX) > 2 {
            fail("the Downloads rows start at x \(Int(row.frame.minX)), not at the view menu's \(Int(menu.minX))")
        }
        #endif
    }

    /// The row's ⋯, then its action `action` (`downloads.action.<action>`; on the Mac, else the menu item titled
    /// `title`).
    private func downloadAction(_ row: String, _ action: String, _ title: String) {
        let more = downloadMenuButton(row)
        #if os(macOS)
        ready(more).hover()
        ready(more).click()
        let entry = element("downloads.action.\(action)")
        if entry.exists(within: 5) {
            pickMenuEntry(entry)
        } else {
            pickMenuEntry(menuEntry(title, "\(row)'s ⋯ offers \(title)"))
        }
        #else
        ready(more).tap()
        pickMenuEntry(require("downloads.action.\(action)", timeout: 5, "\(row)'s ⋯ offers \(action)"))
        #endif
    }

    /// The series' heading's ⋯, then its Delete downloads (`downloads.group.<key>.delete`; on the Mac, else the menu
    /// item titled so).
    private func deleteSeriesDownloads(_ key: String) {
        let more = scrollTo(element("downloads.group.\(key).menu"), up: true, "the series' heading has its ⋯")
        #if os(macOS)
        ready(more).hover()
        ready(more).click()
        let entry = element("downloads.group.\(key).delete")
        if entry.exists(within: 5) {
            pickMenuEntry(entry)
        } else {
            pickMenuEntry(menuEntry("Delete downloads", "the series' ⋯ offers Delete downloads"))
        }
        #else
        ready(more).tap()
        pickMenuEntry(require("downloads.group.\(key).delete", timeout: 5, "the series' ⋯ offers Delete downloads"))
        #endif
    }

    /// The open Download menu's entry `detail.download.<id>`, or its first entry labelled `label`: on iOS 27 the
    /// sectioned Download menu lists its entries without their identifiers; the Mac's menu items carry their action
    /// (`menuAction:`) as their identifier, Season 1's entries before All seasons'.
    func downloadMenuEntry(_ id: String, _ label: String, _ what: String) -> XCUIElement {
        #if os(macOS)
        let entry = app.menuItems.matching(NSPredicate(format: "identifier == %@ OR title == %@", "detail.download.\(id)", label)).firstMatch
        #else
        let entry = app.buttons.matching(NSPredicate(format: "identifier == %@ OR label == %@", "detail.download.\(id)", label)).firstMatch
        #endif
        if !entry.exists(within: 5) { fail("\(what): no detail.download.\(id) or '\(label)' after 5 s") }
        return entry
    }

    /// Presses Download in the action row, scrolled back up to, or on iOS ⋯'s Download where the row has no room for it
    /// (iPad's details column beside the episodes): it opens the Download menu (a series', a movie's while it downloads)
    /// or acts at once (a paused movie's resumes).
    func openDownloadMenu() {
        #if os(macOS)
        ready(scrollTo(element("detail.download"), up: true, "the action row's Download")).click()
        #else
        scrollTo(element("detail.play"), up: true, "the action row's Play")
        if element("detail.download").exists {
            ready("detail.download").tap()
            return
        }
        ready(require("detail.overflow", "the action row has Download or ⋯")).tap()
        ready(require("detail.overflow.download", timeout: 5, "the action row's ⋯ lists Download")).tap()
        #endif
    }

    /// Picks the season at `index` (0: Season 1) by its chip on Detail.
    private func selectSeason(_ index: Int) {
        let chip = scrollTo("detail.season.\(index)", "the series has season \(index + 1)'s chip")
        activate(chip)
        if !waitUntil(5, { chip.exists && chip.isSelected }) { fail("picking season \(index + 1)'s chip doesn't select it") }
    }

    /// Scrolls the Downloads view (a swipe up; the Mac's scroll wheel down) until the row of `title` is inside the
    /// window (the list makes its rows as they scroll in).
    @discardableResult
    private func scrollToDownload(_ title: String) -> XCUIElement {
        let row = downloadRow(title)
        _ = row.exists(within: 5)
        return scrollTo(row, steps: 10, "the Downloads view shows \(title)")
    }

    /// Waits for the first `count` episodes of the season shown to read Downloaded (each one scrolled to: its download
    /// state's label, on the Mac its row); fails naming the first that doesn't.
    private func requireEpisodesDownloaded(_ count: Int, season: Int) {
        for index in 0..<count {
            #if os(macOS)
            let row = scrollTo("detail.episode.\(index)", "S\(season)E\(index + 1) is listed")
            if !waitUntil(120, { row.exists && fullText(of: row).contains("Downloaded") }) {
                fail("S\(season)E\(index + 1) reads \"\(fullText(of: row))\" after 120 s, not Downloaded")
            }
            #else
            let label = scrollTo("detail.episode.\(index).download", "S\(season)E\(index + 1) shows its download's state")
            if !waitUntil(120, { label.exists && fullText(of: label).contains("Downloaded") }) {
                fail("S\(season)E\(index + 1)'s download reads \"\(fullText(of: label))\" after 120 s")
            }
            #endif
        }
    }

    #if os(iOS)
    /// Launches offline and opens the Downloads view from Home's offline state.
    private func openOfflineDownloads() throws {
        try launchDownloads(Self.data, settings: ["offline=true"])
        require("offline.state", timeout: 30, "offline, Home shows the offline state")
        ready(require("offline.openDownloads", "the offline state offers Go to Downloads")).tap()
        require("downloads.offline", timeout: 10, "Go to Downloads opens the Downloads view with its offline note")
    }
    #else
    /// Brings the player's controls up by moving the pointer over the video.
    private func showControls(_ video: XCUIElement) {
        pointerOn(video, at: CGVector(dx: 0.3, dy: 0.4))
        pointerOn(video, at: CGVector(dx: 0.5, dy: 0.5))
        require("player.playPause", timeout: 5, "the pointer over the video shows the controls")
    }
    #endif
}
