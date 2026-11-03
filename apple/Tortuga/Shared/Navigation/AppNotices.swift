import SwiftUI
import TortugaKit

extension View {
    /// The app's notices as toasts over the bottom of `app`'s screens (`RouteStack`: its stack, or on the Mac each of
    /// its pages), whichever shows: what came of a card menu's entry that couldn't be carried out, on whichever screen
    /// it was picked (`AppSessionState.menuNotice`: "Couldn't mark Breaking Bad: …", `app.menuNotice`, then
    /// `AppSession.dismissMenuNotice`), and what the profile's Keep synced rules downloaded on their own
    /// (`AppSessionState.keepSyncedNotice`: "Keep synced added 2 episodes of Breaking Bad", `app.keepSyncedNotice`, then
    /// `AppSession.dismissKeepSyncedNotice`; Apple TV, which has no downloads, never has one). Each goes once it has
    /// shown; while both show, the card menu's sits above Keep synced's. Never over the player: while the player or the
    /// Streams sheet covers the screens, the notices wait, and show once the screens are seen again.
    func appNotices(_ app: AppModel) -> some View {
        overlay(alignment: .bottom) { AppNoticesHost(app: app) }
    }
}

/// The notices while `app`'s state has them and nothing covers the screens; reading them here keeps their changes from
/// drawing the screens again.
private struct AppNoticesHost: View {
    let app: AppModel

    var body: some View {
        let layout = BackStackLayout.companion.of(backStack: app.backStack)
        let covered = layout.sheet != nil || layout.screens.last is RoutePlayer
        let state = app.state.state
        let keepSynced = covered ? nil : state.keepSyncedNotice
        // The screens take every touch around the toasts; the toasts themselves stay reachable, as other toasts are
        // (while VoiceOver runs each waits for its Dismiss action).
        VStack(spacing: 0) {
            Color.clear
                .allowsHitTesting(false)
                .toast(covered ? nil : state.menuNotice, id: "app.menuNotice", onShown: app.session.dismissMenuNotice)
            // Room for the Keep synced toast under the card menu's: its height, with the card menu toast's own bottom
            // margin standing for the Keep synced toast's, and 8 points between the two.
            if let keepSynced {
                Toast(text: keepSynced, id: "app.keepSyncedNotice.room")
                    .padding(.bottom, 8)
                    .hidden()
                    .accessibilityHidden(true)
                    .allowsHitTesting(false)
            }
        }
        .toast(keepSynced, id: "app.keepSyncedNotice", onShown: app.session.dismissKeepSyncedNotice)
    }
}
