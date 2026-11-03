import SwiftUI
import TortugaKit

/// The sidebar (the Mac; iPad before iOS 18): the top-level destinations, and at its bottom the running profile
/// (choosing it opens the profile picker, where the account has one) and the sync status, starting under the
/// destinations' icons.
struct Sidebar: View {
    let app: AppModel
    let selected: TopLevelDestination

    var body: some View {
        List(selection: Binding(get: { selected }, set: { if let destination = $0 { app.navigator.pick(destination: destination) } })) {
            Section {
                ForEach(TopLevelDestination.allCases, id: \.self) { destination in
                    Label(destination.label, systemImage: destination.systemImage)
                        #if os(macOS)
                        // The Mac draws sidebar icons in the system accent colour; the UI has no accent hue.
                        .listItemTint(Palette.onBackground)
                        #else
                        // The selection is the white tint: black on it, as on a focused tvOS tab.
                        .foregroundStyle(destination == selected ? Palette.onPrimary : Palette.onBackground)
                        #endif
                        .tag(destination)
                        .accessibilityIdentifier(destination.accessibilityId)
                        // A click on the selected destination picks it again (`TortugaNavigator.pick`): the list
                        // reports only a change of selection.
                        .simultaneousGesture(TapGesture().onEnded {
                            if destination == selected { app.navigator.pick(destination: destination) }
                        })
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            ProfileHeader(app: app)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, SidebarMetrics.iconInset)
                .padding(.trailing, 16)
                .padding(.top, 12)
                .padding(.bottom, SidebarMetrics.bottomMargin)
        }
        #if os(macOS)
        .navigationSplitViewColumnWidth(min: 180, ideal: 200)
        #endif
    }
}

/// Where the profile sits in a sidebar.
private enum SidebarMetrics {
    /// The destinations' icons' leading edge, from the sidebar's.
    #if os(macOS)
    static let iconInset: CGFloat = 19
    #else
    static let iconInset: CGFloat = 33
    /// Under the profile in iPad's sidebar bottom bar, which reaches down to the screen's edge: the home indicator's
    /// height and the sidebar's margin above it.
    static let barBottom: CGFloat = 32
    #endif
    /// The sidebar's margin under the profile, above the bottom safe area.
    static let bottomMargin: CGFloat = 16
}

#if os(iOS)
/// The profile in iPad's sidebar bottom bar, which centres what it holds: only its drawing moves, to the destinations'
/// icons (an offset from where the bar placed it, in screen points, the sidebar lying along the screen's leading edge),
/// so the bar lays out exactly as it does around the bare profile. Filling the bar to align it (a flexible frame) and a
/// layout placing it at the bar's edge both kept the sidebar laying out without end: the app never went idle. Room on
/// its trailing side for that move and the sidebar's margin keeps a name as wide as the bar inside the sidebar,
/// truncated: centred at the bar's whole width, the move to the icons ran its end past the sidebar's edge.
struct SidebarBarProfile: View {
    let app: AppModel

    /// Where the bar placed the profile's leading edge, on screen.
    @State private var placed: CGFloat?

    var body: some View {
        ProfileHeader(app: app)
            .padding(.trailing, SidebarMetrics.iconInset + 16)
            .offset(x: placed.map { SidebarMetrics.iconInset - $0 } ?? 0)
            // Read outside the offset: the place the bar gave it, which the offset doesn't move.
            .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minX } action: { placed = $0 }
            .padding(.bottom, SidebarMetrics.barBottom)
    }
}
#endif

/// The running profile's avatar and name, a button to the profile picker where the account has one (the sidebar's
/// foot on iPad and the Mac, iPad's navigation bar). While changes sync or can't, the avatar carries the sync status as
/// Compose's rail and TV column do: a cloud on its lower trailing corner, muted while changes sync and in the error
/// colour while they can't, in a notch cut out of the avatar (whatever is behind it, the sidebar or the bar's glass,
/// rings the cloud), half the avatar's side and reaching a little past its edge; nothing while up to date. The status's
/// text is the mark's accessibility label (`nav.sync`) and, on the Mac, the button's tooltip. The mark is drawn over
/// the button and takes no clicks: the button keeps its layout and hit area, and a click on the mark opens the picker.
/// Phones show no mark: their sync trouble is the line above the screen (`PhoneSyncLine`).
struct ProfileHeader: View {
    let app: AppModel

    @Environment(\.formFactor) private var formFactor

    /// The avatar's side: 32 pt at the default text size, following Dynamic Type as the name beside it does; the mark
    /// (its notch and glyph) keeps its share of it.
    @ScaledMetric(relativeTo: .headline) private var avatarSide: CGFloat = 32
    private var markSide: CGFloat { avatarSide / 2 }
    /// How far the mark reaches past the avatar's edge.
    private var markOverhang: CGFloat { avatarSide * 3 / 32 }

    var body: some View {
        Group {
            if let profile = app.state.state.profile {
                let marked = markedStatus
                Button(action: app.session.switchProfile) {
                    HStack(spacing: 10) {
                        ProfileAvatarTile(initial: profile.initial, avatar: profile.avatar, size: avatarSide)
                        .mask {
                            Rectangle()
                                .overlay(alignment: .bottomTrailing) {
                                    if marked != nil {
                                        Circle()
                                            .frame(width: markSide, height: markSide)
                                            .offset(x: markOverhang, y: markOverhang)
                                            .blendMode(.destinationOut)
                                    }
                                }
                                .compositingGroup()
                        }
                        // The whole avatar takes clicks, the notch too.
                        .contentShape(Circle())
                        Text(profile.name).font(.headline).lineLimit(1)
                    }
                }
                .buttonStyle(.plain)
                .disabled(!app.session.canSwitchProfile)
                #if os(macOS)
                .tooltip(marked?.syncStatus ?? "")
                #endif
                .accessibilityHint(app.session.canSwitchProfile ? T.shared.profiles.switchProfile : "")
                .accessibilityIdentifier("nav.profile")
                // The avatar leads the button, centred on its height.
                .overlay(alignment: .leading) {
                    if let marked {
                        Image(systemName: marked.syncKind.systemImage)
                            .font(.system(size: avatarSide / 4, weight: .semibold))
                            .foregroundStyle(marked.syncKind.needsAttention ? Palette.error : Palette.onSurfaceVariant)
                            .frame(width: markSide, height: markSide)
                            .offset(
                                x: avatarSide - markSide + markOverhang,
                                y: (avatarSide - markSide) / 2 + markOverhang
                            )
                            .allowsHitTesting(false)
                            .accessibilityLabel(marked.syncStatus)
                            .accessibilityIdentifier("nav.sync")
                    }
                }
            }
        }
    }

    /// The account's sync status while the avatar carries it: changes syncing or unable to, not on a phone.
    private var markedStatus: AccountGateUiState? {
        guard formFactor != .compact, let status = app.gateState?.state else { return nil }
        switch onEnum(of: status.syncKind) {
        case .idle: return nil
        case .syncing, .error, .offline, .signedOut: return status
        }
    }
}

#if os(iOS)
/// iPhone's sync trouble: while changes can't sync (`SyncKind.needsAttention`), a line across the top of the screens
/// under the status bar, its symbol in the error colour and the status in the text colour on the raised surface, as
/// Android phones show it; nothing while changes sync or are up to date. The player covers it. Accessibility
/// identifier `nav.sync`.
struct PhoneSyncLine: View {
    let gateState: StoreObserver<AccountGateUiState>

    var body: some View {
        let status = gateState.state
        if status.syncKind.needsAttention {
            Label {
                Text(status.syncStatus).foregroundStyle(Palette.onBackground)
            } icon: {
                Image(systemName: status.syncKind.systemImage).foregroundStyle(Palette.error)
            }
            .font(.footnote)
            .lineLimit(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Palette.surfaceContainerHigh.ignoresSafeArea(edges: .top))
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("nav.sync")
        }
    }
}
#endif
