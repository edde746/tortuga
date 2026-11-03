import XCTest

/// What the iPhone, Apple TV and Mac skip tests share, on the test addon's `tortuga-chapters` series
/// (tools/media/test_addon.py: episodes of tools/media's chapters.mkv, 60 s with the chapters Recap 0–4, Opening
/// 4–12, Episode 12–44, Ending 44–54 and Preview 54–60, the first with a next episode): the first episode played from
/// its Streams sheet over the series, the skip button (`player.skip`) by what it offers, a skip told apart from playing
/// on, the credits' up-next button (`player.upNext`), and Settings → Playback → Skipping (by touch on iOS, the remote
/// on tvOS, the pointer on macOS).
extension TortugaUITestCase {
    /// The series' details with its first episode's Streams sheet over them (`--dev-open`).
    static let chaptersFirstEpisode = ["board", "detail:series:tortuga-chapters", "streams:series:tortuga-chapters:tortuga-chapters:1:1"]
    /// The skip button over the intro.
    static let skipIntroLabel = "Skip intro"
    /// Where the intro ends, so where its skip lands.
    static let introEnd = 12.0
    /// Where the credits start: the up-next button from there.
    static let creditsStart = 44.0
    /// The choice of a Skipping row that skips by itself.
    static let skipAutomatically = (id: "AUTO", label: "Skip automatically")

    /// Plays the series' first episode from its Streams sheet (`chaptersFirstEpisode`): its one stream, by a tap, a
    /// click, or Select once it has focus.
    func playFirstChaptersEpisode() {
        #if os(tvOS)
        requireFocus("streams.row.0", timeout: 60, "the episode's stream has focus once its Streams sheet lists it")
        press(.select)
        #elseif os(macOS)
        ready(require("streams.row.0", timeout: 60, "the episode's Streams sheet lists its stream")).click()
        #else
        ready(require("streams.row.0", timeout: 60, "the episode's Streams sheet lists its stream")).tap()
        #endif
        require("player.video", timeout: 20, "the episode's stream opens the player")
    }

    /// What the prompt button `id` offers (the skip button `player.skip`: "Skip intro"; up next `player.upNext`: "Play
    /// the next episode: S1E2 · Episode 2", `playNextEpisode` and the episode), without its direction marks, read at
    /// once (it may go meanwhile); empty while it doesn't show.
    func promptLabel(_ id: String) -> String {
        guard let button = try? element(id).snapshot() else { return "" }
        return (button.label.isEmpty ? button.title : button.label).withoutDirectionMarks
    }

    /// What up next's label says it does, before the episode.
    static let playNextEpisode = "Play the next episode: "

    /// The prompt button `id`'s countdown as VoiceOver says it, its value ("Automatically in 8 seconds"), read at once;
    /// empty while it doesn't count down or doesn't show.
    func promptCountdown(_ id: String) -> String {
        guard let button = try? element(id).snapshot() else { return "" }
        return button.value as? String ?? ""
    }

    /// Waits for the skip button to offer `label`; fails with `what` when it doesn't within `timeout`.
    @discardableResult
    func requireSkipButton(_ label: String, timeout: TimeInterval, _ what: String) -> XCUIElement {
        if !waitUntil(timeout, { promptLabel("player.skip") == label }) {
            let shown = promptLabel("player.skip")
            fail("\(what): after \(Int(timeout)) s the skip button \(shown.isEmpty ? "doesn't show" : "offers '\(shown)'"), not '\(label)'")
        }
        return element("player.skip")
    }

    /// The skip button once it holds still: it moves with the controls as they come and go (above them while they
    /// show; reading the Mac's position brings them up), and a tap or click while it moves lands on the video beside
    /// it.
    func steadySkipButton() -> XCUIElement {
        let button = element("player.skip")
        var last = CGRect.null
        _ = waitUntil(3) {
            let frame = (try? button.snapshot().frame) ?? .null
            defer { last = frame }
            return !frame.isNull && frame == last
        }
        return button
    }

    /// Fails with `what` unless the position, `before` s as read at `start` in the intro, has jumped to the intro's
    /// end (`introEnd`), and the skip button gone with the intro. Playing (`paused` false), the jump must be further on
    /// than playing since `start` could have taken it (positions read as whole seconds). Paused with the controls up,
    /// the video can't play on, so reaching the end while play/pause still reads Play is the skip: a slow device may
    /// pause the intro within the 2.5 s the other rule needs before its end.
    func requireIntroSkipped(from before: Double, since start: Date, paused: Bool = false, _ what: String) {
        let after = requirePlayback(Self.introEnd, timeout: 6)
        let elapsed = Date().timeIntervalSince(start)
        if paused {
            let label = try? element("player.playPause").snapshot().label
            if label != "Play" { fail("\(what): play/pause reads \(label ?? "nothing") after the skip, not Play: the video played on") }
        } else if after - before < elapsed + 1.5 {
            fail("\(what): from \(before) s the position reached \(after) s in \(String(format: "%.1f", elapsed)) s, as playing on does, not by a skip")
        }
        requireGone("player.skip", timeout: 5, "\(what): the skip button goes with the intro")
    }

    /// With intros set to skip automatically: the intro's skip button counts down (shot as "skip-auto") and the intro
    /// skips by itself, no input meanwhile (any stops the countdown). iOS and tvOS read the position from the video's
    /// value; the Mac reads it from the controls, which the pointer brings up, so there the pointer leaves the window
    /// first (the controls coming and going under a pointer over the video count as its moves) and the position is
    /// read only once the button has gone: within 5 s, where an intro played through keeps it until 11.5 s, 7.5 s after
    /// it came up.
    func requireAutomaticIntroSkip() {
        #if os(macOS)
        pointerOutOfTheWindow()
        #endif
        requireSkipButton(Self.skipIntroLabel, timeout: 90, "the intro (4 s) brings the skip button up")
        let start = Date()
        #if !os(macOS)
        let before = playerPosition() ?? 0
        #endif
        // Shot as its fill grows, once the button has come up (its frame still).
        _ = steadySkipButton()
        shot("skip-auto")
        #if os(macOS)
        if !waitUntil(8, { !element("player.skip").exists }) { fail("the counting skip button still shows 8 s after it came up") }
        let shown = Date().timeIntervalSince(start)
        if shown > 5 {
            fail("the skip button went \(String(format: "%.1f", shown)) s after it came up: the intro played through rather than skip by itself")
        }
        requirePlayback(Self.introEnd, timeout: 5)
        #else
        requireIntroSkipped(from: before, since: start, "intros set to skip automatically")
        #endif
    }

    /// Played on into the credits (44 s), the prompt button comes up as up next, saying it plays the next episode
    /// (shot as "credits-upnext", before the Mac's reading of the position moves the pointer, which stops its
    /// countdown); fails when it doesn't or when it came up before them.
    func requireUpNextAtTheCredits() {
        require("player.upNext", timeout: 60, "the credits (\(Int(Self.creditsStart)) s) bring up next up")
        shot("credits-upnext")
        let label = promptLabel("player.upNext")
        if !label.hasPrefix(Self.playNextEpisode + "S1E2") { fail("up next at the credits reads '\(label)', not '\(Self.playNextEpisode)S1E2 · …'") }
        let position = playerPosition() ?? 0
        if position < Self.creditsStart {
            fail("up next came up at \(position) s, before the credits at \(Int(Self.creditsStart)) s")
        }
    }

    /// Settings → Playback's Skipping group with Intros set to skip automatically, shot as "skip-settings"; from a
    /// launch on the Board.
    func setIntrosToSkipAutomatically() {
        #if os(tvOS)
        // The way to Settings starts from the first card, which takes focus once the Board's shelves load.
        requireFocus("board.row.0.item.0", timeout: 90, "the first card has focus once the Board's shelves load")
        #endif
        openSection("playback")
        let intros = "settings.playback.skipIntros"
        #if os(tvOS)
        moveFocus(to: intros, with: .down, limit: 16, "Down reaches the Skipping group's Intros")
        #elseif os(iOS)
        swipeUntilShown("settings.playback.skipCredits", "the Playback page has the Skipping group")
        #else
        // The pointer scrolls over the page's form (the window's centre may lie outside it).
        let credits = require("settings.playback.skipCredits", "the Playback page has the Skipping group")
        let form = app.scrollViews.containing(.any, identifier: "settings.playback.audioLanguages").firstMatch
        scrollTo(credits, over: form, steps: 10, "the Playback page scrolled down to its Skipping group")
        #endif
        requireText(intros, contains: "Show button", "Intros start at Show button")
        pickChoice(intros, option: Self.skipAutomatically)
        shot("skip-settings")
    }

    #if os(tvOS)
    /// Menu puts the skip button away where it shows: the remux's first chapter, Opening (0–10 s), reads as an intro,
    /// so its button comes up with focus as the remux starts or resumes in the chapter, where Select would skip to 10 s
    /// instead of pausing. Away until the playback leaves the chapter, focus back on the video. A play resumed a moment
    /// before 10 s can leave the chapter before the button is read: gone then, past 10 s, there is nothing to put away.
    func putAwaySkipButton() {
        let skip = element("player.skip")
        guard skip.exists else { return }
        if !waitUntil(5, { holdsFocus("player.skip") || !skip.exists }) {
            fail("the skip button (\(promptLabel("player.skip"))) has focus while it shows: focus is on \(focusedId ?? "nothing"), not player.skip")
        }
        if !skip.exists {
            let position = playerPosition() ?? 0
            if position < 10 { fail("the skip button went away at \(position) s, inside the intro, before it had focus") }
            return
        }
        press(.menu)
        requireGone("player.skip", timeout: 5, "Menu puts the skip button away")
        if !element("player.video").exists { fail("Menu on the skip button left the player") }
    }
    #endif
}
