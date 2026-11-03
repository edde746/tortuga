import SwiftUI
import TortugaKit

extension View {
    /// The tracker watchlists' sheet (`AppSessionState.watchlist`) over `app`'s screens while it is open, whichever
    /// screen opened it (a card menu's Watchlist entry, Detail's Watchlist with several trackers); never over the
    /// player. iPhone: a sheet at half height that pulls up to full; iPad: a form sheet with Close; the Mac: a window
    /// sheet with Close (Escape); Apple TV: a `TrailingPanel`, which the remote's Menu closes. Closing it calls
    /// `AppSession.dismissWatchlist`.
    func watchlistSheet(_ app: AppModel) -> some View {
        modifier(WatchlistSheetHost(app: app))
    }
}

private struct WatchlistSheetHost: ViewModifier {
    let app: AppModel

    #if !os(tvOS)
    @Environment(\.formFactor) private var formFactor
    #endif

    func body(content: Content) -> some View {
        let layout = BackStackLayout.companion.of(backStack: app.backStack)
        let covered = layout.sheet != nil || layout.screens.last is RoutePlayer
        let shown = Binding(
            get: { !covered && app.state.state.watchlist != nil },
            set: { if !$0 { app.session.dismissWatchlist() } }
        )
        let toggle: (TrackerService) -> Void = { app.session.toggleWatchlist(service: $0) }
        #if os(tvOS)
        content
            .trailingPanel(isPresented: shown, id: "app.watchlist.sheet") {
                if let sheet = app.state.state.watchlist {
                    WatchlistSheetView(sheet: sheet, onToggle: toggle)
                        .formFactorEnvironment()
                        .tortugaTheme()
                }
            }
        #else
        content
            .statePresentation(shown) { base, shown in
                base.sheet(isPresented: shown) {
                    if let sheet = app.state.state.watchlist {
                        WatchlistSheetView(sheet: sheet, onToggle: toggle, compact: formFactor == .compact) {
                            app.session.dismissWatchlist()
                        }
                        .formFactorEnvironment()
                        .tortugaTheme()
                    }
                }
            }
        #endif
    }
}

/// The tracker watchlists' sheet, titled "Tracker watchlists": a muted hint, then one row per tracker in the services'
/// order (`WatchlistRow`), laid out as the rating picker's tracker lines: its mark, its list's name ("Trakt
/// watchlist"), then at the row's end where a change is ("Saving…", "Not sent yet, will retry", "Not saved"; in the
/// error colour while a change isn't saved) and a check while the title is on the list.
/// A row puts the title on its list, or takes it off, on its own; the sheet stays open. Harness names:
/// `app.watchlist.sheet`, `app.watchlist.<service id>`, on iOS and the Mac `app.watchlist.close`.
struct WatchlistSheetView: View {
    let sheet: WatchlistSheet
    let onToggle: (TrackerService) -> Void
    #if !os(tvOS)
    let compact: Bool
    let onClose: () -> Void
    #endif

    var body: some View {
        #if os(iOS)
        NavigationStack {
            ScrollView {
                content
                    .padding(.horizontal, 20)
                    .padding(.top, 8)
                    .padding(.bottom, 20)
            }
            .scrollBounceBehavior(.basedOnSize)
            .pageTitle(sheet.title)
            .toolbar {
                if !compact {
                    ToolbarItem(placement: .cancellationAction) { SheetCloseButton(id: "app.watchlist.close", action: onClose) }
                }
            }
        }
        .presentationDetents(compact ? [.medium, .large] : [.large])
        .presentationDragIndicator(compact ? .visible : .hidden)
        .formSheetSizing()
        #elseif os(macOS)
        VStack(alignment: .leading, spacing: RatingMetrics.spacing) {
            Text(sheet.title)
                .font(.title3.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            content
            HStack {
                Spacer(minLength: 0)
                ActionButton(title: T.shared.nav.close, id: "app.watchlist.close", fullWidth: false, action: onClose)
                    .keyboardShortcut(.cancelAction)
            }
        }
        .padding(24)
        .frame(width: 480, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
        #else
        ScrollView {
            VStack(alignment: .leading, spacing: RatingMetrics.spacing) {
                Text(sheet.title)
                    .font(.title3.weight(.semibold))
                    .accessibilityAddTraits(.isHeader)
                content
            }
            .padding(.trailing, 48)
            .padding(.vertical, 48)
        }
        .scrollClipDisabled()
        #endif
    }

    private var content: some View {
        VStack(alignment: .leading, spacing: RatingMetrics.spacing) {
            Text(sheet.hint)
                .font(.subheadline)
                .foregroundStyle(Palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            rows
        }
        .accessibilityElement(children: .contain)
        #if !os(tvOS)
        .accessibilityIdentifier("app.watchlist.sheet")
        #endif
    }

    private var rows: some View {
        VStack(spacing: 0) {
            ForEach(Array(sheet.rows.enumerated()), id: \.element.service.id) { index, row in
                if index > 0 {
                    Divider().padding(.leading, RatingMetrics.lineInset + RatingMetrics.markSize + RatingMetrics.markGap)
                }
                Button { onToggle(row.service) } label: { label(row) }
                    .buttonStyle(WatchlistRowStyle())
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(row.name)
                    .accessibilityValue(row.status ?? "")
                    .accessibilityAddTraits(row.listed ? [.isButton, .isSelected] : .isButton)
                    .accessibilityIdentifier("app.watchlist.\(row.service.id)")
            }
        }
        .background(Palette.surfaceContainer, in: RoundedRectangle(cornerRadius: RatingMetrics.groupRadius, style: .continuous))
        #if os(tvOS)
        .focusSection()
        #endif
    }

    private func label(_ row: WatchlistRow) -> some View {
        HStack(spacing: RatingMetrics.markGap) {
            Image(decorative: row.service.markImageName)
                .resizable()
                .scaledToFit()
                .frame(width: RatingMetrics.markSize, height: RatingMetrics.markSize)
                .accessibilityIgnoresInvertColors()
            Text(row.name)
                .foregroundStyle(Palette.onBackground)
                .lineLimit(1)
            Spacer(minLength: 16)
            if let status = row.status {
                Text(status)
                    .font(.subheadline)
                    .foregroundStyle(row.attention ? Palette.error : Palette.onSurfaceVariant)
                    .multilineTextAlignment(.trailing)
                    .lineLimit(2)
                    .frame(maxWidth: RatingMetrics.statusWidth, alignment: .trailing)
            }
            if row.listed {
                Image(systemName: "checkmark")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(Palette.onBackground)
            }
        }
        .padding(.horizontal, RatingMetrics.lineInset)
        .frame(minHeight: RatingMetrics.lineHeight)
        .contentShape(Rectangle())
    }
}

/// A row of the sheet: dimmed while pressed; on Apple TV a solid fill behind it while it has focus, nothing grown or
/// lifted, shrinking a little while clicked (`tvPressed`).
private struct WatchlistRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        Row(configuration: configuration)
    }

    private struct Row: View {
        let configuration: Configuration

        #if os(tvOS)
        @Environment(\.isFocused) private var focused
        #endif

        var body: some View {
            #if os(tvOS)
            configuration.label
                .background(
                    focused ? Palette.surfaceContainerHigh : .clear,
                    in: RoundedRectangle(cornerRadius: RatingMetrics.groupRadius, style: .continuous)
                )
                .animation(.easeOut(duration: 0.15), value: focused)
                .tvPressed(configuration.isPressed)
            #else
            configuration.label
                .opacity(configuration.isPressed ? 0.6 : 1)
            #endif
        }
    }
}
