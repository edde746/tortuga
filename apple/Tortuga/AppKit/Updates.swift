import Combine
import Sparkle
import SwiftUI
import TortugaKit

/// Sparkle updates for the notarized DMG: the feed and the key that signs every update are in Info.plist
/// (SUFeedURL, SUPublicEDKey). Release builds only start the updater: Debug builds (local runs, the UI tests) never
/// read the feed.
@MainActor
final class AppUpdater {
    let controller: SPUStandardUpdaterController

    init() {
        #if DEBUG
        let start = false
        #else
        let start = true
        #endif
        controller = SPUStandardUpdaterController(startingUpdater: start, updaterDelegate: nil, userDriverDelegate: nil)
    }
}

/// The app menu's Check for Updates item, enabled while Sparkle can check.
struct CheckForUpdatesButton: View {
    @ObservedObject private var model: CheckForUpdatesModel
    private let updater: SPUUpdater

    init(updater: SPUUpdater) {
        self.updater = updater
        model = CheckForUpdatesModel(updater: updater)
    }

    var body: some View {
        Button(T.shared.app.checkForUpdates) { updater.checkForUpdates() }
            .disabled(!model.canCheckForUpdates)
    }
}

@MainActor
private final class CheckForUpdatesModel: ObservableObject {
    @Published var canCheckForUpdates = false

    init(updater: SPUUpdater) {
        updater.publisher(for: \.canCheckForUpdates).assign(to: &$canCheckForUpdates)
    }
}
