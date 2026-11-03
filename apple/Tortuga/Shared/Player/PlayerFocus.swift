import SwiftUI
import TortugaKit

#if os(macOS) || os(tvOS)
import Combine
#endif

#if os(macOS)
import Carbon.HIToolbox
#endif

/// Where focus goes in the player. tvOS moves it as the Compose TV player does (`PlayerFocus.target`); iOS and macOS
/// leave focus to the user (a Mac's keyboard keeps its focus on the video, whose keys go to the store; iOS takes a
/// hardware keyboard's keys without focus, `HardwareKeys`).
enum PlayerFocus: Hashable {
    case video
    case scrub
    /// Apple TV's play/pause, which takes the timeline's place while live without a rewind window (`TVControls`).
    case playPause
    /// Apple TV's live label with a rewind window: the go-to-live button, first among the transport bar's buttons.
    case goToLive
    /// The transport bar's previous and next episode buttons.
    case previousEpisode
    case nextEpisode
    /// The transport bar's menu buttons, by the panel tab each opens (`PlayerMenuBar`), and the watch-together session
    /// indicator, which opens the room panel: a panel closing gives focus back to the one that opened it.
    case menuButton(PlayerPanelTab)
    case watchIndicator
    /// The info panel's Info tab.
    case infoPanelTab
    case menuItem(Int)
    case delayDecrease
    /// The subtitle tab's More subtitles row.
    case moreSubtitles
    /// The Options tab's Playback info row, and its row of an option.
    case optionInfo
    case option(PlayerOptionId)
    case stillWatching
    /// The prompt button (skip, up next).
    case prompt
    /// The error's buttons (`ErrorState.buttons`), by what each does: the primary one takes focus.
    case error(ErrorAction)
    /// A watch-together guest's prompt's first action.
    case watchPrompt

    /// The focus a change of the player's layers calls for, or nil to leave it where it is: the error's primary
    /// action; when the panel opens, the open menu's selected entry (its tabs for the playback info, the first row for
    /// the options), the delay's decrease button when a delay opens in it, a page's selected entry when it opens (an
    /// option's choices, More subtitles' languages, a language's subtitles) and the row that opened it when Back leaves
    /// it, the watch-together panel's first button when it opens or what it shows changes (the room once started); a
    /// guest's prompt's first action when it comes up or becomes another (Don't play's declined file); still watching's
    /// Continue and the prompt button when they come up (the prompt button by itself, not as it comes back with the
    /// controls past its own seconds, and not in the middle of a scrub, whose left and right the timeline takes); the
    /// timeline when a scrub starts; when the panel closes (Menu, a pick, its own close) what opened it (`opener`, from
    /// `panelOpener`; a menu button only while it is among `menuButtons`), as AVKit's menus give focus back to their
    /// button; and the timeline (live: play/pause in its place) when the controls come up, the prompt button with them
    /// or not, when the playback info opened from it (Down) closes, when the guest's prompt or the error goes, when the
    /// playback turns live or back under the controls and when the episode button that has focus (`focused`) turns off,
    /// Previous on a series' first episode, where tvOS would leave focus on the dimmed button until the controls go; the
    /// card or the prompt button while the controls are down, else the video.
    static func target(
        from old: PlayerFocusContext,
        to new: PlayerFocusContext,
        focused: PlayerFocus?,
        opener: PlayerFocus?,
        menuButtons: Set<PlayerPanelTab>,
        state: PlayerUiState
    ) -> PlayerFocus? {
        if let error = new.error { return error == old.error ? nil : error }
        if new.panel != nil {
            if old.panel == nil { return entry(state: state) }
            if new.watch != old.watch { return entry(state: state) }
            if new.page != old.page {
                if let page = old.page, new.page == nil || new.page == page.back,
                   let row = Self.opener(of: page, language: old.language, state: state) {
                    return row
                }
                if new.page != nil { return entry(state: state) }
            }
            return new.delay && !old.delay ? .delayDecrease : nil
        }
        // The guest's prompt holds the remote while it shows (the controls and the video step aside).
        if new.watchPrompt != nil { return old.watchPrompt == new.watchPrompt && old.panel == nil ? nil : .watchPrompt }
        // Still watching's Continue and the prompt button, as they come up, over the controls too.
        if new.stillWatching, !old.stillWatching { return .stillWatching }
        if new.prompt, !old.prompt, !new.stillWatching, !new.scrubbing { return .prompt }
        if new.scrubbing, !old.scrubbing { return .scrub }
        if new.controls {
            let home: PlayerFocus = new.live ? .playPause : .scrub
            if old.panel != nil {
                if let opener, shows(opener, menuButtons: menuButtons, state: state) { return opener }
                return home
            }
            if !old.controls || old.error != nil || old.watchPrompt != nil || old.live != new.live { return home }
            if let focused, old.episodeButtons.contains(focused), !new.episodeButtons.contains(focused) { return home }
            return nil
        }
        return new.stillWatching ? .stillWatching : new.prompt ? .prompt : .video
    }

    /// What a panel opening on `state` gives focus back to as it closes, as AVKit's transport bar does: its menu's
    /// button, the session indicator for the room panel; nil for the playback info (Down from the timeline, a swipe),
    /// which gives it back to the timeline.
    static func panelOpener(_ state: PlayerUiState) -> PlayerFocus? {
        guard let tab = PlayerPanelTab(state), tab != .info else { return nil }
        return state.menu == .watchTogether ? .watchIndicator : .menuButton(tab)
    }

    /// Whether `opener` is still on the transport bar to take focus back: the session indicator leaves with the room;
    /// a menu button shows and can take focus while it reports its frame (`menuButtons`), which the track menus'
    /// buttons stop with their tracks and the speed while a room keeps it from this device.
    private static func shows(_ opener: PlayerFocus, menuButtons: Set<PlayerPanelTab>, state: PlayerUiState) -> Bool {
        switch opener {
        case .watchIndicator: state.watch?.indicator != nil
        case .menuButton(let tab): menuButtons.contains(tab)
        case .video, .scrub, .playPause, .goToLive, .previousEpisode, .nextEpisode, .infoPanelTab, .menuItem,
             .delayDecrease, .moreSubtitles, .optionInfo, .option, .stillWatching, .prompt, .error, .watchPrompt:
            true
        }
    }

    /// The entry the panel opens on: the selected track, speed or choice, the delay's decrease button, the options'
    /// first row, or the playback info's tab.
    private static func entry(state: PlayerUiState) -> PlayerFocus {
        guard let menu = state.menu else { return .infoPanelTab }
        return switch menu {
        case .subtitleDelay, .audioDelay: .delayDecrease
        case .options: .optionInfo
        // The selected track, speed or choice (`PlayerUiState.selectedEntry`); the first of More subtitles' languages
        // and of the watch-together room panel.
        case .audio, .subtitles, .speed, .moreSubtitles, .subtitleLanguage, .videoFit, .zoom, .shaders, .sleepTimer,
             .audioChannels, .audioOutput, .watchTogether:
            .menuItem(Int(state.selectedEntry))
        }
    }

    /// The row that opened `page`, which takes focus when Back leaves it: the option's, More subtitles', or the row of
    /// `language` (the language page's key) among More subtitles' languages.
    private static func opener(of page: PlayerMenu, language: String?, state: PlayerUiState) -> PlayerFocus? {
        if page == .moreSubtitles { return .moreSubtitles }
        if page == .subtitleLanguage { return state.moreSubtitles.firstIndex { $0.key == language }.map(PlayerFocus.menuItem) }
        return state.options.first { $0.page == page }.map { PlayerFocus.option($0.id) }
    }
}

/// The parts of the player's state its focus follows.
struct PlayerFocusContext: Equatable {
    var controls: Bool
    /// The panel's tab (tvOS), nil while it is closed.
    var panel: PlayerPanelTab?
    /// A delay shows in the panel.
    var delay: Bool
    /// The page that shows in the panel: an option's, More subtitles or one of its languages.
    var page: PlayerMenu?
    /// The language whose page shows (`PlayerUiState.subtitleLanguage`).
    var language: String?
    var scrubbing: Bool
    /// The playback is live without a rewind window (`PlayerUiState.showsTimeline` false): play/pause in the timeline's
    /// place.
    var live: Bool
    var stillWatching: Bool
    /// The prompt button (skip, up next) shows by itself: counting down or in its first seconds, not only with the
    /// controls (`PlayerPrompt.withControls`).
    var prompt: Bool
    /// The error's primary button, which takes focus; nil without an error.
    var error: PlayerFocus?
    /// What the watch-together room panel shows while it is open in the panel; nil while it isn't.
    var watch: WatchPanelFocus?
    /// A watch-together guest's prompt, by its message, while it shows: another takes focus to its first action.
    var watchPrompt: String?
    /// The episode buttons that are on (`.previousEpisode`, `.nextEpisode`): the ones focus may rest on.
    var episodeButtons: Set<PlayerFocus>

    init(_ state: PlayerUiState) {
        controls = state.controlsVisible
        panel = PlayerPanelTab(state)
        delay = state.menu == .subtitleDelay || state.menu == .audioDelay
        page = state.menu?.back != nil ? state.menu : nil
        language = state.subtitleLanguage?.key
        scrubbing = state.scrubbing
        live = !state.showsTimeline
        stillWatching = state.stillWatching != nil
        prompt = state.prompt.map { !$0.withControls } ?? false
        error = state.error.map { PlayerFocus.error($0.primary.action) }
        watch = state.menu == .watchTogether ? state.watch.map(WatchPanelFocus.init) : nil
        watchPrompt = (state.centre as? PlayerCentrePrompt)?.prompt.message
        episodeButtons = []
        if state.episodes?.previous == true { episodeButtons.insert(.previousEpisode) }
        if state.episodes?.next == true { episodeButtons.insert(.nextEpisode) }
    }

    /// Nothing up: what the player's first state changes from.
    static let opening = PlayerFocusContext()

    private init() {
        controls = false
        panel = nil
        delay = false
        page = nil
        language = nil
        scrubbing = false
        live = false
        stillWatching = false
        prompt = false
        error = nil
        watch = nil
        watchPrompt = nil
        episodeButtons = []
    }
}

/// What decides the watch-together panel's buttons (`PlayerTVWatchPanel.entries`): its first takes focus again when it
/// changes. The host's buttons come and go with the role, the offset's with this player's playing the room's media.
struct WatchPanelFocus: Equatable {
    var room: String?
    var host: Bool
    var offset: Bool
    var busy: Bool
    var failure: Bool

    init(_ watch: PlayerWatchUi) {
        room = watch.room?.code
        host = watch.room?.isHost == true
        offset = watch.offset != nil
        busy = watch.busy != nil
        failure = watch.failure != nil
    }
}
