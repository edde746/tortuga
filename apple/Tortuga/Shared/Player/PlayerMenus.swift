import SwiftUI
import UniformTypeIdentifiers
import TortugaKit

#if os(iOS)
import UIKit
#endif

/// The audio, subtitle and speed menus and the options menu (`PlayerOptionsButton`), as one group of the controls. On
/// iOS and macOS the menus are the system's own (Liquid Glass menus on 26): the tracks with the current one checked
/// (the second subtitle's with its "2" badge, on the Mac its name), then, in the subtitle menu, Second subtitle and
/// More subtitles (`NativeMenu.secondSubtitle`, `moreSubtitles`), then the delay entry, which opens the delay in a
/// popover (`DelayPopover`). The store hears a menu open and close (`PlayerCommandOpenMenu`, `CloseMenu`), so the
/// controls stay up under it. On tvOS the buttons open their menu above them (`PlayerTVPanel`), in AVKit's order and
/// with its symbols: the app's menus (speed, options), then Subtitles and Audio at the trailing end, each focusable as
/// `PlayerFocus.menuButton` of its tab, where focus goes back as its menu closes. Each button there that can take focus
/// reports its frame under its tab (`PlayerMenuButtonFrames`): where its card opens, and whether focus can go back to
/// it. The speed is disabled while a watch-together room keeps it from this device.
struct PlayerMenuBar: View {
    let playback: PlayerModel
    let state: PlayerUiState
    #if os(tvOS)
    let focus: FocusState<PlayerFocus?>.Binding
    #endif

    var body: some View {
        HStack(spacing: PlayerMetrics.menuSpacing) {
            #if os(tvOS)
            speed.focused(focus, equals: .menuButton(.speed)).reportsFrame(.speed, when: state.canControl)
            options.focused(focus, equals: .menuButton(.options)).reportsFrame(.options)
            subtitles.focused(focus, equals: .menuButton(.subtitles)).reportsFrame(.subtitles)
            audio.focused(focus, equals: .menuButton(.audio)).reportsFrame(.audio)
            #else
            audio
            subtitles
            speed
            options
            #endif
        }
    }

    @ViewBuilder private var audio: some View {
        if state.showsAudioMenu {
            menuButton(
                .audio,
                systemImage: Self.audioSymbol,
                label: T.shared.player.menu.audio,
                value: state.audioTracks.first(where: \.selected)?.label,
                id: "player.audio"
            )
            #if os(macOS)
            .equatable()
            #endif
        }
    }

    @ViewBuilder private var subtitles: some View {
        if state.showsSubtitleMenu {
            menuButton(
                .subtitles,
                systemImage: "captions.bubble",
                label: T.shared.player.menu.subtitles,
                value: state.subtitleTracks.first(where: \.selected)?.label,
                id: "player.subtitles"
            )
            #if os(macOS)
            .equatable()
            #endif
        }
    }

    private var speed: some View {
        menuButton(
            .speed,
            systemImage: "gauge.with.dots.needle.67percent",
            label: T.shared.player.menu.speed,
            value: state.speedLabel,
            id: "player.speed"
        )
        #if os(macOS)
        .equatable()
        #endif
        .playerDisabled(!state.canControl)
    }

    private func menuButton(_ menu: PlayerMenu, systemImage: String, label: String, value: String?, id: String) -> PlayerMenuButton {
        PlayerMenuButton(menu: menu, systemImage: systemImage, label: label, value: value, id: id, state: state, store: playback.store)
    }

    private var options: some View {
        PlayerOptionsButton(state: state, store: playback.store)
            #if os(macOS)
            .equatable()
            #endif
    }

    /// AVKit's audio symbol on tvOS; the speaker elsewhere.
    #if os(tvOS)
    private static let audioSymbol = "waveform"
    #else
    private static let audioSymbol = "speaker.wave.2"
    #endif
}

/// A menu's entries, from the store's state, under its title (`PlayerUiState.menuTitle`): the tracks (subtitles start
/// with Off; a subtitle row's hold shows or removes the second subtitle, `Entry.hold`), with More subtitles' count in
/// the subtitle menu while there are subtitles to add, and the delay entry; the speeds; More subtitles' languages, each
/// with its count, opening its page, and a language's subtitles, a pick of which adds it and selects it (Apple TV's
/// pages; the system menus on iOS and the Mac nest them, `NativeMenu.moreSubtitles`); or an option's choices (its page,
/// `PlayerOption.choices`).
struct PlayerMenuContent {
    struct Entry {
        let label: String
        let detail: String?
        /// After the label, for an entry that opens more: a language's count of subtitles.
        var value: String?
        let selected: Bool
        /// The subtitle track showing as the second subtitle: it carries the "2" badge (`secondBadgeSymbol`).
        var second = false
        /// What a long press on the row does (a held Select on Apple TV; the system menus' Second subtitle submenu on
        /// iOS and the Mac, whose entries take none); nil for a row whose long press is a click (not a subtitle's).
        var hold: Hold?
        let perform: () -> Void
    }

    /// A subtitle row's long-press action, named for VoiceOver; no name where it does nothing.
    struct Hold {
        let name: String?
        let perform: () -> Void
    }

    /// The second subtitle's badge: a filled rounded square with a 2, in the row's foreground colour.
    static let secondBadgeSymbol = "2.square.fill"

    let title: String
    let entries: [Entry]
    /// How many subtitles the subtitle menu's More subtitles offers after its tracks; nil while there are none.
    var more: String?
    /// The delay this menu offers after its entries (audio, subtitles), with its current value.
    var delay: DelayState?

    init(_ menu: PlayerMenu, state: PlayerUiState, store: PlayerStore) {
        title = state.menuTitle(menu: menu)
        switch menu {
        case .audio:
            entries = Self.tracks(state.audioTracks, kind: .audio, store: store)
            delay = state.audioDelay
        case .subtitles:
            entries = Self.tracks(state.subtitleTracks, kind: .subtitle, store: store)
            more = state.moreSubtitles.isEmpty ? nil : state.moreSubtitlesCount
            delay = state.subtitleDelay
        case .moreSubtitles:
            entries = state.moreSubtitles.map { language in
                Entry(label: language.label, detail: nil, value: language.count, selected: false) {
                    store.dispatch(command: PlayerCommandOpenSubtitleLanguage(key: language.key))
                }
            }
        case .subtitleLanguage:
            entries = Self.choices(state.subtitleLanguage?.subtitles ?? [], store: store)
        case .speed:
            entries = state.speeds.map { option in
                Entry(label: option.label, detail: nil, selected: option.selected) {
                    store.dispatch(command: PlayerCommandSetSpeed(speed: option.speed))
                }
            }
        // An option's page; the options themselves, the delays and the room panel list apart (`PlayerOptionsButton`,
        // the delay popovers, the TV panel's Options tab).
        case .options, .videoFit, .zoom, .shaders, .sleepTimer, .audioChannels, .audioOutput, .subtitleDelay,
             .audioDelay, .watchTogether:
            entries = state.options.first { $0.page == menu }.map { Self.choices($0.choices, store: store) } ?? []
        }
    }

    /// Choices as entries: an option's (its page), a language's subtitles to add.
    static func choices(_ choices: [OptionChoice], store: PlayerStore) -> [Entry] {
        choices.map { choice in
            Entry(label: choice.label, detail: choice.detail, selected: choice.selected) {
                store.dispatch(command: choice.command)
            }
        }
    }

    /// Tracks as entries, a pick of which selects its track. A subtitle row's hold toggles the second subtitle
    /// (`PlayerCommandToggleSecondSubtitle`): a track's other than the subtitle's shows it as the second, the second's
    /// own or Off's removes it; the subtitle's row's, and Off's without a second subtitle, does nothing.
    private static func tracks(_ tracks: [TrackItem], kind: TrackKind, store: PlayerStore) -> [Entry] {
        let hasSecond = tracks.contains(where: \.second)
        return tracks.map { track in
            var hold: Hold?
            if kind == .subtitle {
                let name: String? = if track.second || (track.id == nil && hasSecond) {
                    T.shared.player.track.removeSecond
                } else if track.id != nil && !track.selected {
                    T.shared.player.track.showAsSecond
                } else {
                    nil
                }
                hold = Hold(name: name) { store.dispatch(command: PlayerCommandToggleSecondSubtitle(id: track.id)) }
            }
            return Entry(label: track.label, detail: track.detail, selected: track.selected, second: track.second, hold: hold) {
                store.dispatch(command: PlayerCommandSelectTrack(kind: kind, id: track.id))
            }
        }
    }
}

extension PlayerMenu {
    /// The delay popover this menu's delay entry opens.
    var delayMenu: PlayerMenu? {
        switch self {
        case .audio: .audioDelay
        case .subtitles: .subtitleDelay
        case .speed, .subtitleDelay, .audioDelay, .moreSubtitles, .subtitleLanguage, .options, .videoFit, .zoom, .shaders,
             .sleepTimer, .audioChannels, .audioOutput, .watchTogether:
            nil
        }
    }

    /// A menu drawn by the system (iOS, macOS; the options menu with its options' pages as submenus), as opposed to
    /// the delay popovers and the watch-together room sheet the player draws itself.
    var isNativeMenu: Bool {
        switch self {
        case .audioDelay, .subtitleDelay, .watchTogether: false
        case .audio, .subtitles, .speed, .moreSubtitles, .subtitleLanguage, .options, .videoFit, .zoom, .shaders,
             .sleepTimer, .audioChannels, .audioOutput:
            true
        }
    }
}

extension PlayerOptionId {
    /// The option's symbol in the options menu.
    var systemImage: String {
        switch self {
        case .watchTogether: "person.2"
        case .videoFit: "aspectratio"
        case .zoom: "plus.magnifyingglass"
        case .shaders: "wand.and.stars"
        case .sleepTimer: "moon.zzz"
        case .autoPlayNext: "forward.end"
        case .audioOutput: "hifispeaker"
        case .audioNormalization: "waveform"
        case .audioChannels: "speaker.wave.3"
        }
    }

    /// The option's accessibility identifier: `player.option.videoFit`.
    var identifier: String { "player.option.\((self as __PlayerOptionId).probeName)" }
}

#if os(tvOS)
/// The global frame of each tvOS menu button that shows and can take focus, by the tab it opens (`PlayerMenuBar`).
struct PlayerMenuButtonFrames: PreferenceKey {
    static var defaultValue: [PlayerPanelTab: CGRect] { [:] }

    static func reduce(value: inout [PlayerPanelTab: CGRect], nextValue: () -> [PlayerPanelTab: CGRect]) {
        value.merge(nextValue()) { $1 }
    }
}

private extension View {
    /// Reports this menu button's global frame under `tab` (`PlayerMenuButtonFrames`) while `focusable`.
    func reportsFrame(_ tab: PlayerPanelTab, when focusable: Bool = true) -> some View {
        background {
            if focusable {
                GeometryReader { proxy in
                    Color.clear.preference(key: PlayerMenuButtonFrames.self, value: [tab: proxy.frame(in: .global)])
                }
            }
        }
    }
}

/// A menu button on tvOS: opens this menu's card above it (`PlayerTVPanel`).
struct PlayerMenuButton: View {
    let menu: PlayerMenu
    let systemImage: String
    let label: String
    let value: String?
    let id: String
    let state: PlayerUiState
    let store: PlayerStore

    var body: some View {
        PlayerIconButton(systemImage: systemImage, label: label, value: value, id: id, selected: state.menu?.parent == menu) {
            store.dispatch(command: PlayerCommandOpenMenu(menu: menu))
        }
    }
}

/// The options button on tvOS: opens the options menu above it, which lists the playback info and the options.
struct PlayerOptionsButton: View {
    let state: PlayerUiState
    let store: PlayerStore

    var body: some View {
        PlayerIconButton(systemImage: "ellipsis", label: T.shared.player.options.title, id: "player.more", selected: PlayerPanelTab(state) == .options) {
            store.dispatch(command: PlayerCommandOpenMenu(menu: .options))
        }
    }
}
#else
/// A menu button on iOS and macOS: the system menu (see `PlayerMenuBar`), and the popover of its delay.
struct PlayerMenuButton: View {
    let menu: PlayerMenu
    let systemImage: String
    let label: String
    let value: String?
    let id: String
    let state: PlayerUiState
    let store: PlayerStore

    var body: some View {
        let delayShown = menu.delayMenu.map { store.menuPresentation($0, shown: state.menu == $0) } ?? .constant(false)
        menuButton(.track(menu, state: state, store: store))
            .statePresentation(delayShown) { base, shown in
                base.popover(isPresented: shown, arrowEdge: .top) {
                    if menu.delayMenu != nil {
                        DelayPopover(delay: menu == .subtitles ? state.subtitleDelay : state.audioDelay, store: store)
                            .presentationCompactAdaptation(.popover)
                    }
                }
            }
    }

    #if os(iOS)
    private func menuButton(_ content: NativeMenu) -> some View {
        NativeMenuButton(menu: menu, systemImage: systemImage, label: label, value: value, id: id, content: content, store: store)
            .frame(width: PlayerMetrics.button, height: PlayerMetrics.button)
    }
    #else
    private func menuButton(_ content: NativeMenu) -> some View {
        Menu {
            NativeMenuEntries(entries: content.children ?? [], title: content.title)
        } label: {
            PlayerMenuIcon(systemImage: systemImage)
        }
        .playerMenuStyle(menu: menu, label: label, value: value, id: id)
    }
    #endif
}

extension PlayerStore {
    /// A presentation of one of the store's menus (a delay's popover, the room sheet): it shows while `shown` holds, and
    /// its dismissal closes the menu if it is still the store's.
    @MainActor
    func menuPresentation(_ menu: PlayerMenu, shown: Bool) -> Binding<Bool> {
        Binding(
            get: { shown },
            set: { shown in
                if !shown, self.state.value.menu == menu { self.dispatch(command: PlayerCommandCloseMenu.shared) }
            }
        )
    }
}

/// The options button on iOS and macOS (`player.more`): a system menu (`NativeMenu.options`) with the playback info,
/// which shows or hides it (the sheet on iOS, the panel on the Mac), and the player's options (`PlayerUiState.options`):
/// an option with a page as a submenu with its value, its choices checked; a switch as a checked entry. Watch together,
/// outside a room, is a submenu of the ways to start one with what plays, and the room sheet opens once the pick is
/// made; in a room, its entry opens the sheet (`PlayerMenu.WATCH_TOGETHER`). The store hears it open and close as
/// `PlayerMenu.OPTIONS`, so the controls stay up under it; a pick closes it.
struct PlayerOptionsButton: View {
    let state: PlayerUiState
    let store: PlayerStore
    @Environment(\.shaderPrompts) private var shaderPrompts

    #if os(iOS)
    var body: some View {
        NativeMenuButton(
            menu: .options,
            systemImage: "ellipsis",
            label: T.shared.player.options.title,
            value: nil,
            id: "player.more",
            content: .options(state, store: store, shaders: shaderPrompts),
            store: store
        )
        .frame(width: PlayerMetrics.button, height: PlayerMetrics.button)
    }
    #else
    var body: some View {
        Menu {
            NativeMenuEntries(entries: NativeMenu.options(state, store: store, shaders: shaderPrompts).children ?? [])
        } label: {
            PlayerMenuIcon(systemImage: "ellipsis.circle")
        }
        .playerMenuStyle(menu: .options, label: T.shared.player.options.title, value: nil, id: "player.more")
    }
    #endif

    /// Reads the picked file off the main thread and sends its name and text (`PlayerCommandImportShader`); a file
    /// that can't be read, is over `maxBytes` or isn't UTF-8 fails the import (`PlayerCommandShaderImportFailed`).
    private func importShader(_ url: URL) async {
        let maxBytes = Self.maxBytes
        let text = await Task.detached(priority: .userInitiated) { () -> String? in
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let file = try? FileHandle(forReadingFrom: url) else { return nil }
            defer { try? file.close() }
            guard let data = try? file.read(upToCount: maxBytes + 1) ?? Data(), data.count <= maxBytes else { return nil }
            return String(data: data, encoding: .utf8)
        }.value
        if let text {
            store.dispatch(command: PlayerCommandImportShader(fileName: url.lastPathComponent, source: text))
        } else {
            store.dispatch(command: PlayerCommandShaderImportFailed.shared)
}

/// What the Shaders submenu's own entries open once the menu has gone: Import shader the open dialog, an imported
/// shader's Delete the question before it goes (a system menu's entries take no trailing button or swipe). The player
/// shows them (`ShaderPromptsPresenter`), not the options button: the controls, the button with them, hide on their
/// timer while the video plays, and a presentation goes with the view that shows it.
@Observable
final class ShaderPrompts {
    var importing = false
    var deleting: OptionChoice?
}

extension EnvironmentValues {
    /// The player's shader prompts, for its options button (`PlayerScreen`).
    @Entry var shaderPrompts: ShaderPrompts?
}

/// Shows `prompts` over the player and hands them to the options button in it: the open dialog, whose pick imports
/// the shader, and the question before an imported shader's deletion.
struct ShaderPromptsPresenter: ViewModifier {
    @Bindable var prompts: ShaderPrompts
    let store: PlayerStore

    /// The most a shader file may hold, as on Android (`AndroidFiles.MAX_IMPORT_BYTES`): a larger one fails the import
    /// rather than being read whole.
    private static let maxBytes = 32 * 1024 * 1024

    func body(content: Content) -> some View {
        content
            .environment(\.shaderPrompts, prompts)
            // A `.glsl` file; any file where the system knows no such type, its extension checked once picked.
            .fileImporter(isPresented: $prompts.importing, allowedContentTypes: [UTType(filenameExtension: "glsl") ?? .data]) { result in
                if case .success(let url) = result { Task { await importShader(url) } }
            }
            .confirmationDialog(
                prompts.deleting.map { T.shared.player.options.shader.deleteTitle(name: $0.label) } ?? "",
                isPresented: Binding(get: { prompts.deleting != nil }, set: { if !$0 { prompts.deleting = nil } }),
                titleVisibility: .visible,
                presenting: prompts.deleting
            ) { choice in
                Button(T.shared.player.options.shader.delete, role: .destructive) {
                    if let delete = choice.delete { store.dispatch(command: delete) }
                }
                Button(T.shared.player.options.shader.cancel, role: .cancel) {}
            }
    }
        }
    }
}

/// A system menu or one of its entries, built once from the store's state for iOS (`NativeMenuButton`, a `UIMenu`) and
/// the Mac (`NativeMenuEntries`, a SwiftUI `Menu`): an action, checked or not where it can be, or a submenu of more,
/// which `inline` makes a group of its menu's entries under its title. What an entry opens (a popover, a sheet, the
/// playback info) waits until the menu has gone (`afterClose`).
private struct NativeMenu {
    var title = ""
    var subtitle: String?
    var systemImage: String?
    /// The symbol's accessible name, for one that says something of its own (the second subtitle's badge); the Mac
    /// shows the name in the entry's second line instead of the symbol (`NativeMenuEntries`).
    var imageLabel: String?
    /// Whether the entry is checked, for one that can be; nil for one that can't.
    var checked: Bool?
    /// The entry's accessibility identifier on the Mac (UIKit's menu entries take none).
    var identifier = ""
    /// A submenu's entries; nil for an action.
    var children: [NativeMenu]?
    var inline = false
    /// One of the submenu's entries is checked at a time.
    var singleSelection = false
    var action: (() -> Void)?
    var afterClose: (() -> Void)?

    /// What the menu shows, to tell when it changed: iOS builds its menu again only then, and on the Mac each build
    /// closes an open submenu (`PlayerOptionsButton`'s `==`).
    var signature: [String] {
        let entry = "\(identifier)|\(title)|\(subtitle ?? "")|\(systemImage ?? "")|\(checked?.description ?? "-")"
        guard let children else { return [entry] }
        return [entry, "["] + children.flatMap(\.signature) + ["]"]
    }

    /// A track or speed menu (`PlayerMenuContent`): its entries, the current one checked, then the subtitle menu's
    /// Second subtitle (`secondSubtitle`), More subtitles (`moreSubtitles`) and the delay entry, which opens the delay's
    /// popover.
    static func track(_ menu: PlayerMenu, state: PlayerUiState, store: PlayerStore) -> NativeMenu {
        let content = PlayerMenuContent(menu, state: state, store: store)
        var more = [NativeMenu]()
        if menu == .subtitles, let second = secondSubtitle(content.entries) {
            more.append(second)
        }
        if let count = content.more {
            more.append(moreSubtitles(state.moreSubtitles, count: count, store: store))
        }
        if let delay = content.delay {
            more.append(NativeMenu(
                title: delay.title,
                subtitle: delay.label,
                systemImage: "timer",
                identifier: "player.menu.delay",
                afterClose: { store.dispatch(command: PlayerCommandOpenMenu(menu: delay.menu)) }
            ))
        }
        var groups = [NativeMenu(children: choices(content.entries), inline: true, singleSelection: true)]
        if !more.isEmpty { groups.append(NativeMenu(children: more, inline: true)) }
        return NativeMenu(title: content.title, children: groups)
    }

    /// Second subtitle, with its track (or Off) under its name: the subtitle rows' hold (`PlayerMenuContent.Entry.hold`)
    /// as a submenu, since a system menu's entries take no long press or right-click. It lists Off and every track but
    /// the subtitle's, the second's checked; a pick of another shows it as the second subtitle (Off removes it) and
    /// closes the menu. Nil while there is no track for it.
    private static func secondSubtitle(_ entries: [PlayerMenuContent.Entry]) -> NativeMenu? {
        guard let off = entries.first else { return nil }
        let tracks = entries.dropFirst().filter { $0.hold?.name != nil }
        guard !tracks.isEmpty else { return nil }
        let current = tracks.first(where: \.second)
        let items = ([off] + tracks).enumerated().map { index, entry in
            let checked = index == 0 ? current == nil : entry.second
            return NativeMenu(
                title: entry.label,
                subtitle: entry.detail,
                checked: checked,
                identifier: "player.menu.second.item.\(index)",
                action: checked ? nil : entry.hold?.perform
            )
        }
        return NativeMenu(
            title: T.shared.player.track.second,
            subtitle: (current ?? off).label,
            systemImage: "captions.bubble",
            identifier: "player.menu.second",
            children: [NativeMenu(children: items, inline: true, singleSelection: true)]
        )
    }

    /// More subtitles (`PlayerUiState.moreSubtitles`) with their `count`: a submenu of their languages, each with its
    /// count, each a submenu of its subtitles, a pick of which adds it, selects it and closes the menu.
    private static func moreSubtitles(_ languages: [SubtitleLanguage], count: String, store: PlayerStore) -> NativeMenu {
        let pages = languages.enumerated().map { index, language in
            NativeMenu(
                title: language.label,
                subtitle: language.count,
                identifier: "player.menu.more.\(index)",
                children: choices(PlayerMenuContent.choices(language.subtitles, store: store))
            )
        }
        return NativeMenu(
            title: T.shared.player.menu.moreSubtitles,
            subtitle: count,
            systemImage: "plus.bubble",
            identifier: "player.menu.more",
            children: pages
        )
    }

    /// The options menu, with no title over it (the button's ellipsis says what it is, and the menu keeps the room):
    /// the playback info, checked while it shows, which it shows or hides once the menu has gone, and on a touch screen
    /// Lock screen (`PlayerUiState.lockLabel`), which locks it once the menu has gone; then the options, an option with a
    /// page as a submenu with its value under its name, a switch as an entry checked while it is on.
    static func options(_ state: PlayerUiState, store: PlayerStore, shaders: ShaderPrompts? = nil) -> NativeMenu {
        let info = NativeMenu(
            title: T.shared.player.info.title,
            systemImage: "info.circle",
            checked: state.infoVisible,
            identifier: "player.more.info",
            afterClose: { store.dispatch(command: PlayerCommandToggleInfo.shared) }
        )
        let options = state.options.map { option -> NativeMenu in
            if option.id == .watchTogether, let watch = state.watch {
                return watchTogether(option, watch: watch, store: store)
            }
            if let on = option.on, let toggle = option.toggle {
                return NativeMenu(
                    title: option.label,
                    systemImage: option.id.systemImage,
                    checked: on.boolValue,
                    identifier: option.id.identifier,
                    action: { store.dispatch(command: toggle) }
                )
            }
            let picks = choices(PlayerMenuContent.choices(option.choices, store: store))
            let more = shaderEntries(option, prompts: shaders)
            return NativeMenu(
                title: option.label,
                subtitle: option.value,
                systemImage: option.id.systemImage,
                identifier: option.id.identifier,
                children: more.isEmpty
                    ? picks
                    : [NativeMenu(children: picks, inline: true, singleSelection: true), NativeMenu(children: more, inline: true)],
                singleSelection: more.isEmpty
            )
        }
        var first = [info]
        if let lockLabel = state.lockLabel {
            first.append(NativeMenu(
                title: lockLabel,
                systemImage: "lock.fill",
                identifier: "player.more.lock",
                afterClose: { store.dispatch(command: PlayerCommandLockScreen.shared) }
            ))
        }
        return NativeMenu(children: [NativeMenu(children: first, inline: true), NativeMenu(children: options, inline: true)])
    }

    /// The Shaders submenu's entries after its choices: Delete <name> for each imported shader (`OptionChoice.delete`),
    /// which asks to confirm, and Import shader (`PlayerOption.importLabel`), which opens the open dialog.
    private static func shaderEntries(_ option: PlayerOption, prompts: ShaderPrompts?) -> [NativeMenu] {
        var entries = option.choices.enumerated().compactMap { index, choice -> NativeMenu? in
            guard choice.delete != nil else { return nil }
            return NativeMenu(
                title: T.shared.player.options.shader.deleteLabel(name: choice.label),
                systemImage: "trash",
                identifier: "player.shaders.delete.\(index)",
                afterClose: { prompts?.deleting = choice }
            )
        }
        if let importLabel = option.importLabel {
            entries.append(NativeMenu(
                title: importLabel,
                systemImage: "square.and.arrow.down",
                identifier: "player.shaders.import",
                afterClose: { prompts?.importing = true }
            ))
        }
        return entries
    }

    /// Watch together's entry in the options menu: outside a room a submenu of the ways to start one with what plays,
    /// the room sheet opening once the pick is made and the menu has gone; in a room, or with no way to start one (a
    /// live stream, which the sheet says), an entry that opens the sheet.
    private static func watchTogether(_ option: PlayerOption, watch: PlayerWatchUi, store: PlayerStore) -> NativeMenu {
        let openPanel = { store.dispatch(command: PlayerCommandOpenMenu(menu: .watchTogether)) }
        guard watch.room == nil, !watch.startChoices.isEmpty else {
            return NativeMenu(
                title: option.label,
                subtitle: option.value,
                systemImage: option.id.systemImage,
                identifier: option.id.identifier,
                afterClose: openPanel
            )
        }
        let starts = watch.startChoices.map { choice in
            NativeMenu(
                title: choice.label,
                subtitle: choice.detail,
                systemImage: PlayerMetrics.menuStartSymbols ? choice.mode.systemImage : nil,
                identifier: "player.watch.start.\(choice.mode.identifier)",
                action: { store.dispatch(command: PlayerCommandWatch(command: WatchCommandStart(mode: choice.mode))) },
                afterClose: openPanel
            )
        }
        return NativeMenu(
            title: option.label,
            subtitle: option.value,
            systemImage: option.id.systemImage,
            identifier: option.id.identifier,
            children: [NativeMenu(title: watch.startTitle, children: starts, inline: true)]
        )
    }

    /// Entries as actions, the selected one checked, each with its detail line (`player.menu.item.<i>`), the second
    /// subtitle's with its badge.
    private static func choices(_ entries: [PlayerMenuContent.Entry]) -> [NativeMenu] {
        entries.enumerated().map { index, entry in
            NativeMenu(
                title: entry.label,
                subtitle: entry.detail,
                systemImage: entry.second ? PlayerMenuContent.secondBadgeSymbol : nil,
                imageLabel: entry.second ? T.shared.player.track.second : nil,
                checked: entry.selected,
                identifier: "player.menu.item.\(index)",
                action: entry.perform
            )
        }
    }
}

#if os(macOS)
/// The Mac's menus are built again only when what they show changes (`PlayerMenuBar` compares them): each build hands
/// AppKit new submenus, so while the video played, the position's updates closed an open submenu (an option's, More
/// subtitles' or a language's) under the pointer and dropped a pick in it. SwiftUI compares views on the main actor,
/// where the button's state lives.
extension PlayerOptionsButton: @preconcurrency Equatable {
    static func == (lhs: PlayerOptionsButton, rhs: PlayerOptionsButton) -> Bool {
        NativeMenu.options(lhs.state, store: lhs.store).signature == NativeMenu.options(rhs.state, store: rhs.store).signature
    }
}

/// A track or speed menu's button also shows its value and the popover of its delay (whose value the menu's delay
/// entry carries).
extension PlayerMenuButton: @preconcurrency Equatable {
    static func == (lhs: PlayerMenuButton, rhs: PlayerMenuButton) -> Bool {
        lhs.menu == rhs.menu && lhs.value == rhs.value
            && (lhs.state.menu == lhs.menu.delayMenu) == (rhs.state.menu == rhs.menu.delayMenu)
            && NativeMenu.track(lhs.menu, state: lhs.state, store: lhs.store).signature
            == NativeMenu.track(rhs.menu, state: rhs.state, store: rhs.store).signature
    }
}

/// A menu button's symbol on the Mac, as the player's other buttons draw theirs (`PlayerIconButton`).
private struct PlayerMenuIcon: View {
    let systemImage: String

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: PlayerMetrics.icon, weight: .semibold))
            .frame(width: PlayerMetrics.button, height: PlayerMetrics.button)
            .contentShape(Rectangle())
    }
}

private extension View {
    /// A menu button of the Mac's controls: a bare symbol without the menu's indicator, `label` its tooltip; the pointer
    /// over it tells `PlayerScreen` which menu AppKit then tracks (`PlayerMenuTracking`).
    func playerMenuStyle(menu: PlayerMenu, label: String, value: String?, id: String) -> some View {
        menuStyle(.button)
            .buttonStyle(PlayerPressStyle())
            .menuIndicator(.hidden)
            .fixedSize()
            .onHover { if $0 { PlayerMenuTracking.hovered = menu } }
            .tooltip(label)
            .accessibilityLabel(label)
            .accessibilityValue(value ?? "")
            .accessibilityIdentifier(id)
    }
}

/// A system menu's entries as SwiftUI's (`NativeMenu`): a group set apart from the entries before it, under its title,
/// the menu's own title over its first group (a SwiftUI menu shows none of its own). What an entry opens waits a
/// moment, until AppKit's menu has closed, whose end closes the store's menu first (`PlayerScreen`).
private struct NativeMenuEntries: View {
    let entries: [NativeMenu]
    /// The menu's title, over its first group.
    var title = ""

    var body: some View {
        ForEach(Array(entries.enumerated()), id: \.offset) { index, entry in
            if let children = entry.children, entry.inline {
                if index > 0 { Divider() }
                let heading = index == 0 && entry.title.isEmpty ? title : entry.title
                if heading.isEmpty {
                    NativeMenuEntries(entries: children)
                } else {
                    Section(heading) { NativeMenuEntries(entries: children) }
                }
            } else if let children = entry.children {
                Menu { NativeMenuEntries(entries: children) } label: { label(entry) }
                    .accessibilityIdentifier(entry.identifier)
            } else if let checked = entry.checked {
                Toggle(isOn: Binding(get: { checked }, set: { _ in run(entry) })) { label(entry) }
                    .accessibilityIdentifier(entry.identifier)
            } else {
                Button { run(entry) } label: { label(entry) }
                    .accessibilityIdentifier(entry.identifier)
            }
        }
    }

    /// The entry's title and second line, after its symbol where it has one. A badge (the second subtitle's) shows as its
    /// name leading the second line: these menus draw no symbol of an entry, beside it or in its text, and no second
    /// line beside a symbol.
    @ViewBuilder
    private func label(_ entry: NativeMenu) -> some View {
        if let badge = entry.imageLabel {
            Text(entry.title)
            Text(([badge] + [entry.subtitle].compactMap { $0 }).joined(separator: T.shared.detail.listSeparator))
        } else if let systemImage = entry.systemImage {
            Label { text(entry) } icon: { Image(systemName: systemImage) }
        } else {
            text(entry)
        }
    }

    @ViewBuilder
    private func text(_ entry: NativeMenu) -> some View {
        Text(entry.title)
        if let subtitle = entry.subtitle { Text(subtitle) }
    }

    private func run(_ entry: NativeMenu) {
        entry.action?()
        if let afterClose = entry.afterClose {
            let box = UncheckedBox(afterClose)
            DispatchQueue.main.async { box.value() }
        }
    }
}
#endif

/// A delay (subtitles or audio) as the system's stepper, a step of the store's each way, with Reset.
private struct DelayPopover: View {
    let delay: DelayState
    let store: PlayerStore

    var body: some View {
        VStack(spacing: 14) {
            Text(delay.title)
                .font(.headline)
            Stepper {
                Text(delay.label)
                    .font(.title3.monospacedDigit())
                    .frame(minWidth: 88, alignment: .leading)
            } onIncrement: {
                guard delay.canIncrease else { return }
                store.dispatch(command: delay.increase)
            } onDecrement: {
                guard delay.canDecrease else { return }
                store.dispatch(command: delay.decrease)
            }
            .accessibilityLabel(delay.title)
            .accessibilityValue(delay.label)
            .accessibilityIdentifier("player.delay")
            Button(T.shared.player.menu.reset) { store.dispatch(command: delay.reset) }
                .disabled(!delay.canReset)
                .accessibilityIdentifier("player.delay.reset")
        }
        .padding(20)
        .frame(minWidth: 240)
    }
}
#endif

#if os(macOS)
/// The Mac's menus report no opening of their own: `PlayerScreen` hears AppKit's menu tracking, and the menu button the
/// pointer was last over tells which menu it is.
@MainActor
enum PlayerMenuTracking {
    static var hovered: PlayerMenu?
}
#endif

#if os(iOS)
/// A UIKit button with the menu as its primary action (iOS). UIKit tells a button when its menu shows and goes, which a
/// SwiftUI `Menu` doesn't: the store hears the menu open and close, and an entry that opens a popover or a sheet (the
/// delay, the playback info) does so once the menu is gone. The menu (`content`) is built again only when what it shows
/// changes (`NativeMenu.signature`).
private struct NativeMenuButton: UIViewRepresentable {
    let menu: PlayerMenu
    let systemImage: String
    let label: String
    let value: String?
    let id: String
    let content: NativeMenu
    let store: PlayerStore

    func makeUIView(context: Context) -> MenuButton {
        let button = MenuButton(type: .system)
        button.showsMenuAsPrimaryAction = true
        var configuration = UIButton.Configuration.plain()
        configuration.baseForegroundColor = UIColor(Palette.onVideo)
        configuration.contentInsets = .zero
        button.configuration = configuration
        // At the accessibility text sizes a long press shows the symbol and label large, as on `PlayerIconButton`.
        button.showsLargeContentViewer = true
        button.addInteraction(UILargeContentViewerInteraction())
        return button
    }

    func updateUIView(_ button: MenuButton, context: Context) {
        // The image, its size, the enabled state and the alpha only when they change: the player's state updates
        // often, and each set redraws the button, its open menu's source too.
        if button.systemImage != systemImage {
            button.systemImage = systemImage
            button.configuration?.image = UIImage(systemName: systemImage)
            button.largeContentImage = UIImage(systemName: systemImage)
        }
        // The symbol as SwiftUI draws the player's other buttons beside it (`PlayerIconButton`): the same point size at
        // the text size (SwiftUI's, which the overlay caps), weight and scale.
        let pointSize = UIFontMetrics(forTextStyle: .body).scaledValue(
            for: PlayerMetrics.icon,
            compatibleWith: UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(context.environment.dynamicTypeSize))
        )
        if button.symbolPointSize != pointSize {
            button.symbolPointSize = pointSize
            button.configuration?.preferredSymbolConfigurationForImage = UIImage.SymbolConfiguration(
                pointSize: pointSize,
                weight: .semibold,
                scale: .medium
            )
        }
        button.accessibilityIdentifier = id
        button.accessibilityLabel = label
        button.accessibilityValue = value
        button.largeContentTitle = label
        // `disabled` around it (the speed, which a watch-together room may keep from this device).
        let enabled = context.environment.isEnabled
        if button.isEnabled != enabled {
            button.isEnabled = enabled
            button.alpha = enabled ? 1 : 0.45
        }
        let menu = self.menu
        let store = self.store
        button.onOpen = {
            if store.state.value.menu != menu { store.dispatch(command: PlayerCommandOpenMenu(menu: menu)) }
        }
        button.onClose = {
            if store.state.value.menu?.isNativeMenu == true { store.dispatch(command: PlayerCommandCloseMenu.shared) }
        }
        let signature = content.signature
        guard signature != button.signature else { return }
        button.signature = signature
        let built = UIMenu(title: content.title, children: Self.elements(content.children ?? [], button: button))
        button.menu = built
        if button.menuShown {
            button.contextMenuInteraction?.updateVisibleMenu { _ in built }
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: MenuButton, context: Context) -> CGSize? {
        CGSize(width: PlayerMetrics.button, height: PlayerMetrics.button)
    }

    /// A menu's entries as UIKit's; what an entry opens waits for `button`'s menu to close.
    private static func elements(_ entries: [NativeMenu], button: MenuButton) -> [UIMenuElement] {
        entries.map { entry -> UIMenuElement in
            let image = entry.systemImage.flatMap { systemImage in UIImage(systemName: systemImage) }
            if let label = entry.imageLabel { image?.accessibilityLabel = label }
            if let children = entry.children {
                var options: UIMenu.Options = []
                if entry.inline { options.insert(.displayInline) }
                if entry.singleSelection { options.insert(.singleSelection) }
                let menu = UIMenu(title: entry.title, image: image, options: options, children: elements(children, button: button))
                menu.subtitle = entry.subtitle
                return menu
            }
            let action = UIAction(title: entry.title, image: image, state: entry.checked == true ? .on : .off) { [weak button] _ in
                entry.action?()
                if let afterClose = entry.afterClose { button?.runAfterClose(afterClose) }
            }
            action.subtitle = entry.subtitle
            return action
        }
    }

    final class MenuButton: UIButton {
        var onOpen: (() -> Void)?
        var onClose: (() -> Void)?
        var signature: [String] = []
        /// The symbol the button's configuration shows.
        var systemImage: String?
        /// The point size of the symbol the configuration shows.
        var symbolPointSize: CGFloat?
        private(set) var menuShown = false
        /// The menu's dismissal is under way: its end runs what waits (`flushAfterClose`).
        private var closing = false
        /// What runs once the menu has gone (an entry that opens a popover).
        private var afterClose: (() -> Void)?

        /// Runs `action` once the menu has gone. UIKit doesn't promise whether an entry's handler runs before the menu
        /// starts to go, during its dismissal or after it: while the menu shows or goes, its dismissal's end runs
        /// `action`; once it has gone, it runs now.
        func runAfterClose(_ action: @escaping () -> Void) {
            afterClose = action
            if !menuShown, !closing { flushAfterClose() }
        }

        override func contextMenuInteraction(
            _ interaction: UIContextMenuInteraction,
            willDisplayMenuFor configuration: UIContextMenuConfiguration,
            animator: (any UIContextMenuInteractionAnimating)?
        ) {
            // No first responder while the menu shows: with one (the player's key view, `HardwareKeys`, or before it
            // SwiftUI's hosting view), UIKit brings the software keyboard up with the menu when no hardware keyboard
            // is attached. The key view takes first responder back once the menu has gone.
            _ = UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: self, for: nil)
            super.contextMenuInteraction(interaction, willDisplayMenuFor: configuration, animator: animator)
            menuShown = true
            onOpen?()
        }

        override func contextMenuInteraction(
            _ interaction: UIContextMenuInteraction,
            willEndFor configuration: UIContextMenuConfiguration,
            animator: (any UIContextMenuInteractionAnimating)?
        ) {
            super.contextMenuInteraction(interaction, willEndFor: configuration, animator: animator)
            menuShown = false
            closing = true
            onClose?()
            if let animator {
                animator.addCompletion { [weak self] in self?.closed() }
            } else {
                DispatchQueue.main.async { [weak self] in self?.closed() }
            }
        }

        private func closed() {
            closing = false
            flushAfterClose()
        }

        private func flushAfterClose() {
            guard !menuShown, let action = afterClose else { return }
            afterClose = nil
            action()
        }
    }
}
#endif
