import SwiftUI
import TortugaKit

/// Content is on its way: a spinner, with `text` under it when the store has one. Fills the space it is given, and on
/// a pushed tvOS screen holds focus (`holdsBackFocus`).
struct LoadingStateView: View {
    var text: String?

    @Environment(\.tortugaDimens) private var dimens

    var body: some View {
        VStack(spacing: 16) {
            ProgressView().largeSpinner()
            if let text {
                Text(text)
                    .font(.body)
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(dimens.screenPadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .holdsBackFocus()
    }
}

extension ProgressView {
    /// A screen's or the player's spinner: large (tvOS has the one size).
    func largeSpinner() -> some View {
        #if os(tvOS)
        self
        #else
        controlSize(.large)
        #endif
    }

    /// A spinner beside a line or a symbol: small (tvOS has no small control size: the spinner scaled to the line).
    func smallSpinner() -> some View {
        #if os(tvOS)
        scaleEffect(0.6).frame(width: 28, height: 28)
        #else
        controlSize(.small)
        #endif
    }
}

/// Something failed: `message` (worded by the store) and, with `onRetry`, a retry button that takes focus so a remote
/// or keyboard user can act at once; without one, on a pushed tvOS screen the view holds focus (`holdsBackFocus`).
struct ErrorStateView: View {
    let message: String
    var title: String?
    var retryLabel: String = T.shared.nav.retry
    var onRetry: (() -> Void)?

    @FocusState private var retryFocused: Bool

    var body: some View {
        MessageStateView(systemImage: "exclamationmark.circle", title: title, message: message, holdsFocus: onRetry == nil) {
            if let onRetry {
                Button(action: onRetry) {
                    Label(retryLabel, systemImage: "arrow.clockwise")
                }
                .glassButtonStyle()
                .focused($retryFocused)
                .onAppear { retryFocused = true }
            }
        }
    }
}

/// Nothing to show: `message` (from the store) under `systemImage`; `action` for what the user can do about it. Without
/// an action, on a pushed tvOS screen the view holds focus (`holdsBackFocus`).
struct EmptyStateView<Action: View>: View {
    let message: String
    var systemImage: String = "film"
    @ViewBuilder var action: () -> Action

    var body: some View {
        MessageStateView(systemImage: systemImage, title: nil, message: message, holdsFocus: Action.self == EmptyView.self, actions: action)
    }
}

extension EmptyStateView where Action == EmptyView {
    init(message: String, systemImage: String = "film") {
        self.init(message: message, systemImage: systemImage) { EmptyView() }
    }
}

/// The device is offline (`OfflineState`, `offline.state`): its title and text in place of what needs the network
/// (Home's catalogs, Discover's catalog, Search's results), and "Go to Downloads" where the device has downloads
/// (`offline.openDownloads`, `onOpenDownloads`); on Apple TV, which has none, the text alone.
struct OfflineStateView: View {
    let state: OfflineState
    let onOpenDownloads: () -> Void

    var body: some View {
        MessageStateView(systemImage: "wifi.slash", title: state.title, message: state.text, holdsFocus: state.actionText == nil) {
            if let action = state.actionText {
                Button(action: onOpenDownloads) {
                    Label(action, systemImage: "arrow.down.circle")
                }
                .glassButtonStyle()
                .accessibilityIdentifier("offline.openDownloads")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("offline.state")
    }
}

/// What this profile may not see, in place of it (a title blocked on a kids profile): a lock, `title`, `reason` and the
/// primary `actionLabel` button (`onAction`: asking a parent), which takes focus so a remote or keyboard user can act at
/// once. Accessibility identifiers: `id` (the region), `<id>.action`.
struct BlockedStateView: View {
    let title: String
    let reason: String
    let actionLabel: String
    let id: String
    let onAction: () -> Void

    /// Optional for `FocusRestore`.
    @FocusState private var actionFocused: Bool?

    var body: some View {
        MessageStateView(systemImage: "lock", title: title, message: reason, holdsFocus: false) {
            ActionButton(title: actionLabel, id: "\(id).action", prominence: .primary, systemImage: "lock", fullWidth: false, action: onAction)
                .focused($actionFocused, equals: true)
                #if os(tvOS)
                // The spinner before it held focus, which went with it.
                .task { await FocusRestore.restore($actionFocused, to: true) }
                #else
                .onAppear { actionFocused = true }
                #endif
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(id)
    }
}

private struct MessageStateView<Actions: View>: View {
    let systemImage: String
    let title: String?
    let message: String
    /// There are no actions to focus: the view itself holds focus on a pushed tvOS screen.
    let holdsFocus: Bool
    @ViewBuilder var actions: () -> Actions

    @Environment(\.tortugaDimens) private var dimens

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 44))
                .foregroundStyle(Palette.onSurfaceVariant)
                .accessibilityHidden(true)
            if let title {
                Text(title)
                    .font(.title2.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
            }
            Text(message)
                .font(.body)
                .foregroundStyle(Palette.onSurfaceVariant)
                .multilineTextAlignment(.center)
            actions()
        }
        .frame(maxWidth: 480)
        .padding(dimens.screenPadding)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .holdsBackFocus(holdsFocus)
    }
}

extension EnvironmentValues {
    /// The screen is pushed over another: a route above the root of its tab's stack, a Settings section's page over
    /// the section list, a `TrailingPanel`'s content (`holdsBackFocus`).
    @Entry var isPushedScreen = false
}

extension View {
    /// tvOS: on a pushed screen (`isPushedScreen`), this view takes focus while `holds`; it is for what a screen shows
    /// while it has nothing else to focus (loading, a failure without a retry, nothing to list). The remote's Menu goes
    /// back from the screen that has focus, but a pushed screen with nothing focusable leaves focus on the tab bar (the
    /// system gives it focus at launch, and the focus engine moves it there when the screen that had it goes), and
    /// Menu on the tab bar leaves the app. Elsewhere, and on a tab's root (whose Menu goes to the tab bar anyway),
    /// nothing changes: there such a view leaves focus on the tab bar.
    func holdsBackFocus(_ holds: Bool = true) -> some View {
        modifier(BackFocusHolder(holds: holds))
    }
}

private struct BackFocusHolder: ViewModifier {
    let holds: Bool

    @Environment(\.isPushedScreen) private var pushed
    @FocusState private var focus: String?

    func body(content: Content) -> some View {
        #if os(tvOS)
        if holds, pushed {
            content
                .focusable()
                .focusEffectDisabled()
                .focused($focus, equals: "back")
                // Taken from the tab bar too, which the system focuses a moment after launch.
                .task { await FocusRestore.restore($focus, to: "back", hold: 1.5) }
        } else {
            content
        }
        #else
        content
        #endif
    }
}

/// Something running: a small spinner and what it waits for.
struct ProgressLine: View {
    let text: String?

    var body: some View {
        HStack(spacing: 12) {
            ProgressView()
            if let text {
                Text(text).foregroundStyle(Palette.onSurfaceVariant)
            }
        }
    }
}

/// A line saying what went wrong: the error icon, then the store's wording (which accessibility reads alone). It shows
/// after something failed, often away from where VoiceOver is, so its text is announced as it shows and as it changes.
struct ErrorLine: View {
    let text: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "exclamationmark.circle")
                .accessibilityHidden(true)
            Text(text)
        }
        .font(.callout)
        .foregroundStyle(Palette.error)
        .announcing(text)
    }
}
