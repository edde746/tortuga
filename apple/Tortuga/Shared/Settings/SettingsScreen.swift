import SwiftUI
import TortugaKit

/// The Settings route of `app` over the shared `SettingsSession` (kept by the navigator for the app session, Settings
/// being a tab, so a section left open, and its store, are still there after a screen it opened or another tab).
struct SettingsRoute: View {
    let app: AppModel

    var body: some View {
        if let session = SettingsModel.retained(by: app) {
            SettingsScreen(app: app, session: session)
        }
    }
}

/// The shared `SettingsSession` for SwiftUI: its first page's state mirrored in `main` for as long as the navigator
/// keeps the session (the open section is a page of the stack's path, `RouteStack`, so its state must follow while the
/// list is covered), and each section's store with its state mirrored, made when the section first shows. Kept on the
/// navigator beside the session, under `key`.
@MainActor
final class SettingsModel {
    static let key = "settings.apple"

    let session: SettingsSession
    let main: StoreObserver<SettingsUiState>

    private var playbackModel: StoreModel<PlaybackSettingsStore, PlaybackSettingsUiState>?
    private var deviceModel: StoreModel<DeviceSettingsStore, DeviceSettingsUiState>?
    private var subtitlesModel: StoreModel<SubtitleSettingsStore, SubtitleSettingsUiState>?
    private var accountModel: StoreModel<AccountSettingsStore, AccountSettingsUiState>?
    private var trackersModel: StoreModel<TrackersSettingsStore, TrackersSettingsUiState>?
    private var homeModel: StoreModel<HomeSettingsStore, HomeSettingsUiState>?
    private var appearanceModel: StoreModel<AppearanceSettingsStore, AppearanceSettingsUiState>?
    private var downloadsModel: StoreModel<DownloadSettingsStore, DownloadSettingsUiState>?
    private var dataModel: StoreModel<DataSettingsStore, DataSettingsUiState>?
    private var playerModel: StoreModel<PlayerSettingsStore, PlayerSettingsUiState>?
    private var addonsModel: StoreModel<AddonsStore, AddonsUiState>?
    /// `main`'s mirroring, cancelled when the navigator lets the session go (the app session closes).
    private var observation: Task<Void, Never>?

    private init(session: SettingsSession) {
        self.session = session
        let main = StoreObserver(session.main.state)
        self.main = main
        observation = Task { await main.observe() }
    }

    /// The first page's store: its intents.
    var store: SettingsStore { session.main }

    /// The section whose page is pushed over the list (`SettingsUiState.section`).
    var pushedSection: SettingsSection? { main.state.section }

    /// The notice whose page is pushed over About (`AboutUiState.notice`).
    var pushedNotice: NoticePage? { main.state.section == .about ? main.state.about.notice : nil }

    /// The model kept for Settings on `app`'s navigator; nil while the route isn't on the stack (a popped screen still
    /// draws while it animates out, and a model made then would leak).
    static func retained(by app: AppModel) -> SettingsModel? {
        guard let session = app.session.settingsSession() else { return nil }
        // The navigator calls both on the main thread: `create` within this call, `release` when the app session
        // closes; the session releases the stores.
        let model = app.navigator.retain(
            route: RouteSettings.shared,
            key: key,
            create: {
                let model: SettingsModel = MainActor.assumeIsolated { SettingsModel(session: session) }
                return model
            },
            release: { value in
                guard let model = value as? SettingsModel else { return }
                MainActor.assumeIsolated { model.observation?.cancel() }
            }
        )
        return model as? SettingsModel
    }

    /// The playback section's store, made on first use; nil once the session is released.
    var playback: StoreModel<PlaybackSettingsStore, PlaybackSettingsUiState>? {
        if let playbackModel { return playbackModel }
        guard let store = session.playback else { return nil }
        let model = StoreModel(store) { $0.state }
        playbackModel = model
        return model
    }

    /// Playback's Player row and page (this device's player), made on first use; nil where the device has no player
    /// apps (`AppPlatform.playerApps`), or once the session is released.
    var player: StoreModel<PlayerSettingsStore, PlayerSettingsUiState>? {
        if let playerModel { return playerModel }
        guard let store = session.player else { return nil }
        let model = StoreModel(store) { $0.state }
        playerModel = model
        return model
    }

    /// The device section's store, made on first use with what this device supports.
    var device: StoreModel<DeviceSettingsStore, DeviceSettingsUiState>? {
        if let deviceModel { return deviceModel }
        guard let store = session.device else { return nil }
        let model = StoreModel(store) { $0.state }
        deviceModel = model
        return model
    }

    /// The subtitles section's store, made on first use.
    var subtitles: StoreModel<SubtitleSettingsStore, SubtitleSettingsUiState>? {
        if let subtitlesModel { return subtitlesModel }
        guard let store = session.subtitles else { return nil }
        let model = StoreModel(store) { $0.state }
        subtitlesModel = model
        return model
    }

    /// The account section's store, made on first use; nil without an account (a `--dev` session).
    var account: StoreModel<AccountSettingsStore, AccountSettingsUiState>? {
        if let accountModel { return accountModel }
        guard let store = session.account else { return nil }
        let model = StoreModel(store) { $0.state }
        accountModel = model
        return model
    }

    /// The trackers section's store, made on first use; nil without trackers.
    var trackers: StoreModel<TrackersSettingsStore, TrackersSettingsUiState>? {
        if let trackersModel { return trackersModel }
        guard let store = session.trackers else { return nil }
        let model = StoreModel(store) { $0.state }
        trackersModel = model
        return model
    }

    /// The home section's store, made on first use.
    var home: StoreModel<HomeSettingsStore, HomeSettingsUiState>? {
        if let homeModel { return homeModel }
        guard let store = session.home else { return nil }
        let model = StoreModel(store) { $0.state }
        homeModel = model
        return model
    }

    /// The appearance section's store, made on first use.
    var appearance: StoreModel<AppearanceSettingsStore, AppearanceSettingsUiState>? {
        if let appearanceModel { return appearanceModel }
        guard let store = session.appearance else { return nil }
        let model = StoreModel(store) { $0.state }
        appearanceModel = model
        return model
    }

    /// The downloads section's store, made on first use; nil where the device has no downloads (Apple TV).
    var downloads: StoreModel<DownloadSettingsStore, DownloadSettingsUiState>? {
        if let downloadsModel { return downloadsModel }
        guard let store = session.downloads else { return nil }
        let model = StoreModel(store) { $0.state }
        downloadsModel = model
        return model
    }

    /// The export and import section's store, made on first use.
    var data: StoreModel<DataSettingsStore, DataSettingsUiState>? {
        if let dataModel { return dataModel }
        guard let store = session.data else { return nil }
        let model = StoreModel(store) { $0.state }
        dataModel = model
        return model
    }

    /// The Addons section's store, made on first use; the session hands it the install links followed into the app
    /// (`AppSession.openAddons`).
    var addons: StoreModel<AddonsStore, AddonsUiState>? {
        if let addonsModel { return addonsModel }
        guard let store = session.addons else { return nil }
        let model = StoreModel(store) { $0.state }
        addonsModel = model
        return model
    }
}

/// A section's store with its state mirrored for the view.
@MainActor
final class StoreModel<Store: AnyObject, State> {
    let store: Store
    let observer: StoreObserver<State>

    init(_ store: Store, state: (Store) -> SkieSwiftStateFlow<State>) {
        self.store = store
        observer = StoreObserver(state(store))
    }
}

/// Settings: the current profile, then the sections, as a native grouped form. The open section's
/// page (`SettingsSectionPage`) is pushed over it on every device as a page of the stack's path (`RouteStack`), so it
/// goes with the Settings route (Back returns to the list). Every text comes from the stores.
///
/// Accessibility identifiers: `settings.profile.edit`, `settings.profile.manage`, `settings.section.<section>`.
struct SettingsScreen: View {
    let app: AppModel
    let session: SettingsModel

    var body: some View {
        SectionList(app: app, session: session)
            .navigationTitle(session.main.state.title)
    }
}

/// The page of `section`, pushed over Settings while the section is open (`SettingsModel.pushedSection`); nothing once
/// the Settings route has left the stack and the page only animates out.
struct SettingsSectionPage: View {
    let app: AppModel
    let section: SettingsSection

    var body: some View {
        if let session = SettingsModel.retained(by: app) {
            SettingsSectionContent(session: session, section: section)
                .environment(\.isPushedScreen, true)
                .pageTitle(session.main.state.sectionTitle(section: section))
        }
    }
}

/// The page of About's notice, pushed over About while it shows (`SettingsModel.pushedNotice`); nothing once it has
/// closed and the page only animates out.
struct SettingsNoticePage: View {
    let app: AppModel

    var body: some View {
        if let session = SettingsModel.retained(by: app), let notice = session.pushedNotice {
            NoticePane(page: notice, store: session.store)
                .environment(\.isPushedScreen, true)
                .pageTitle(notice.name)
        }
    }
}

/// The first page: the profile and one row per section. On iOS 18 and later it keeps its place through a change of its
/// size (`KeptPlace`).
private struct SectionList: View {
    let app: AppModel
    let session: SettingsModel

    #if os(iOS)
    @State private var shownRows = ShownRows()
    #endif

    var body: some View {
        #if os(iOS)
        if #available(iOS 18, *) {
            ScrollViewReader { proxy in
                form.modifier(KeptPlace(sections: session.main.state.sections.map(\.section), shownRows: shownRows, proxy: proxy))
            }
        } else {
            form
        }
        #else
        form
        #endif
    }

    private var form: some View {
        let state = session.main.state
        return Form {
            // From the first frame, at its loaded size: a profile read after the sections would push them down from
            // under a tap, a click or the focus.
            ProfileSection(
                profile: state.profile,
                canManage: app.session.canSwitchProfile,
                onEdit: session.store.editProfile,
                onManage: session.store.manageProfiles
            )
            Section {
                ForEach(state.sections, id: \.section) { row in
                    SettingsLinkRow(
                        title: row.title,
                        subtitle: row.summary,
                        systemImage: row.section.systemImage,
                        id: "settings.section.\((row.section as __SettingsSection).probeName)"
                    ) {
                        session.store.open(section: row.section)
                    }
                    #if os(iOS)
                    .modifier(ShownRow(section: row.section, rows: shownRows))
                    #endif
                }
            }
        }
        .settingsForm()
    }
}

#if os(iOS)
/// The sections whose rows Settings shows whole, as the rows report it (`ShownRow`), for `KeptPlace`: read when the
/// list scrolls, so a change redraws nothing.
private final class ShownRows {
    var sections: Set<SettingsSection> = []
}

/// Keeps `rows` holding `section` while its row shows whole (iOS 18 and later).
private struct ShownRow: ViewModifier {
    let section: SettingsSection
    let rows: ShownRows

    func body(content: Content) -> some View {
        if #available(iOS 18, *) {
            content.onScrollVisibilityChange(threshold: 0.99) { shown in
                if shown { rows.sections.insert(section) } else { rows.sections.remove(section) }
            }
        } else {
            content
        }
    }
}

/// Settings keeps its place through a change of its size (the iPhone Duo folded or unfolded, an iPad window resized).
/// On the new size the navigation bar comes back with its large title expanded and scrolls the list back to where that
/// title fits, near its top: scrolled to its end, Settings came back 57 pt from its top (iOS 27.1, 25 Oct). The place is
/// where the last scroll came to rest (the bar's own scroll is none): the list's top, its end, or else the first row it
/// showed whole. Once a new size has held for half a second, past the bar's scroll (0.3 s), the list scrolls back to
/// its end or that row (its top it keeps by itself), at once with Reduce Motion; a touch in the meantime leaves it
/// where the touch takes it. Pushed pages, their titles inline, don't move.
@available(iOS 18, *)
private struct KeptPlace: ViewModifier {
    let sections: [SettingsSection]
    let shownRows: ShownRows
    let proxy: ScrollViewProxy

    private enum Place {
        case top, end
        case row(SettingsSection)
    }

    @State private var kept = Place.top
    /// The new sizes so far: each starts the wait before going back to `kept` again.
    @State private var resizes = 0
    @State private var restoring = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .onScrollGeometryChange(for: CGSize.self, of: { $0.containerSize }) { _, _ in
                restoring = true
                resizes += 1
            }
            .onScrollPhaseChange { _, phase, context in
                if phase == .interacting { restoring = false }
                if phase == .idle { kept = place(context.geometry) }
            }
            .task(id: resizes) {
                guard restoring else { return }
                do { try await Task.sleep(for: .milliseconds(500)) } catch { return }
                guard restoring else { return }
                restoring = false
                withAnimation(reduceMotion ? nil : .default) {
                    switch kept {
                    case .top: break
                    case .end: if let last = sections.last { proxy.scrollTo(last, anchor: .bottom) }
                    case .row(let section): proxy.scrollTo(section, anchor: .top)
                    }
                }
            }
    }

    private func place(_ geometry: ScrollGeometry) -> Place {
        let offset = geometry.contentOffset.y
        if offset <= 1 - geometry.contentInsets.top { return .top }
        if offset >= geometry.contentSize.height - geometry.containerSize.height + geometry.contentInsets.bottom - 1 { return .end }
        return sections.first(where: shownRows.sections.contains).map(Place.row) ?? .top
    }
}
#endif

/// The current profile in one row: its avatar (the store's, as the profile picker draws it), name and lock, then two
/// buttons with a symbol each, named by their labels: a pen that edits it (none where the profile isn't edited from
/// here: a kids profile's `editLabel` is nil), and two arrows that switch or manage profiles (Apple TV's only way to the
/// profile picker: its tab bar has no profile button). On iOS and macOS the name and lock show whole and the buttons go
/// beside them or under them (`SettingsRowLayout`); on tvOS each is one line. While it is read (`profile` nil), the
/// card empty at the height of a one-line name and lock: its content unseen and out of reach (no focus, no
/// accessibility element, so no identifier).
private struct ProfileSection: View {
    let profile: SettingsProfileCard?
    let canManage: Bool
    let onEdit: () -> Void
    let onManage: () -> Void

    @Environment(\.formFactor) private var formFactor
    #if !os(tvOS)
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    #endif

    var body: some View {
        let avatarSize: CGFloat = formFactor == .tv ? 72 : 44
        Section {
            HStack(spacing: 16) {
                if let profile {
                    ProfileAvatarTile(initial: profile.initial, avatar: profile.avatar, size: avatarSize)
                } else {
                    Color.clear.frame(width: avatarSize, height: avatarSize)
                }
                #if os(tvOS)
                VStack(alignment: .leading, spacing: 2) {
                    Text(profile?.name ?? " ").font(.headline).lineLimit(1)
                    // One line, as the placeholder's: the row's height doesn't depend on the lock.
                    Text(profile?.detail ?? " ").font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                buttons
                #else
                SettingsRowLayout(
                    leastTextWidth: SettingsRowLayout.leastTextWidth(
                        title: profile?.name ?? " ", subtitle: profile?.detail, titleStyle: .headline, dynamicTypeSize: dynamicTypeSize
                    ),
                    spacing: 16
                ) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(profile?.name ?? " ").font(.headline)
                        Text(profile?.detail ?? " ").font(.footnote).foregroundStyle(.secondary)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    buttons
                }
                #endif
            }
            .padding(.vertical, 4)
            .opacity(profile == nil ? 0 : 1)
            .disabled(profile == nil)
            .accessibilityHidden(profile == nil)
        }
    }

    private var buttons: some View {
        let text = T.shared.settings.profile
        return HStack(spacing: Self.buttonSpacing) {
            if profile == nil || profile?.editLabel != nil {
                button("pencil", label: profile?.editLabel ?? text.edit, id: "settings.profile.edit", action: onEdit)
            }
            if canManage {
                button("arrow.left.arrow.right", label: profile?.manageLabel ?? text.manage, id: "settings.profile.manage", action: onManage)
            }
        }
        #if os(tvOS)
        // The circles' glass, one row of it.
        .glassGroup(spacing: Self.buttonSpacing)
        #endif
    }

    /// A plain symbol, its whole 44 pt square taking the tap or click; on tvOS, which has no plain buttons, a glass
    /// circle (`IconButton`).
    @ViewBuilder
    private func button(_ systemImage: String, label: String, id: String, action: @escaping () -> Void) -> some View {
        #if os(tvOS)
        IconButton(systemImage: systemImage, label: label, id: id, action: action)
        #else
        Button(action: action) {
            Image(systemName: systemImage)
                .imageScale(.large)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        // In a list row beside another button: each answers its own taps only.
        .buttonStyle(.borderless)
        #if os(macOS)
        .tooltip(label)
        #else
        .help(label)
        #endif
        .accessibilityLabel(label)
        .accessibilityIdentifier(id)
        #endif
    }

    #if os(tvOS)
    private static let buttonSpacing: CGFloat = 12
    #else
    private static let buttonSpacing: CGFloat = 0
    #endif
}

/// A section of `session` over its store.
private struct SettingsSectionContent: View {
    let session: SettingsModel
    let section: SettingsSection

    var body: some View {
        switch section {
        case .playback:
            if let model = session.playback { PlaybackSettingsRoute(model: model, player: session.player) }
        case .device:
            if let model = session.device { DeviceSettingsRoute(model: model) }
        case .subtitles:
            if let model = session.subtitles { SubtitleSettingsRoute(model: model) }
        case .account:
            if let model = session.account { AccountSettingsRoute(model: model) }
        case .trackers:
            if let model = session.trackers { TrackersSettingsRoute(model: model) }
        case .home:
            if let model = session.home { HomeSettingsRoute(model: model) }
        case .appearance:
            if let model = session.appearance { AppearanceSettingsRoute(model: model) }
        case .about:
            AboutPane(about: session.main.state.about, store: session.store)
        case .downloads:
            // Apple TV has no downloads, nor this section.
            #if os(tvOS)
            EmptyView()
            #else
            if let model = session.downloads { DownloadSettingsRoute(model: model) }
            #endif
        case .data:
            // Apple TV has no file dialogs, nor this section.
            #if os(tvOS)
            EmptyView()
            #else
            if let model = session.data { DataSettingsRoute(model: model) }
            #endif
        case .addons:
            if let model = session.addons { AddonsSettingsRoute(model: model) }
        }
    }
}

extension SettingsSection {
    var systemImage: String {
        switch self {
        case .playback: "play.circle"
        #if os(tvOS)
        case .device: "appletv"
        #elseif os(macOS)
        case .device: "desktopcomputer"
        #else
        case .device: "iphone"
        #endif
        case .subtitles: "captions.bubble"
        case .downloads: "arrow.down.circle"
        case .addons: "puzzlepiece.extension"
        case .home: "house"
        case .appearance: "paintpalette"
        case .trackers: "arrow.triangle.2.circlepath"
        case .account: "person.crop.circle"
        case .data: "arrow.up.arrow.down.circle"
        case .about: "info.circle"
        }
    }
}
