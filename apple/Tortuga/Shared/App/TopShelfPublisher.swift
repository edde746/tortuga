#if os(tvOS)
import OSLog
import TortugaKit
import TVServices

/// The Apple TV's home screen (`AppPlatform.homeScreen`): the Top Shelf above the app's icon. Each list the shared core
/// publishes (the running profile's Continue Watching on every change; none for a profile with a PIN and at sign-out)
/// goes to the App Group the extension reads, as the shared core's JSON (`TopShelfPayload`), and the system is told to
/// ask the extension again (`TopShelfProvider`).
@MainActor
final class TopShelfPublisher: @preconcurrency HomeScreen {
    private static let log = Logger(subsystem: "com.edde746.tortuga", category: "topshelf")

    func publish(items: [HomeScreenItem]) {
        guard let defaults = UserDefaults(suiteName: TopShelfContent.appGroup) else {
            return Self.log.error("no defaults for the App Group \(TopShelfContent.appGroup, privacy: .public): the Top Shelf keeps what it showed")
        }
        defaults.set(Data(TopShelfPayload.shared.json(items: items).utf8), forKey: TopShelfContent.defaultsKey)
        TVTopShelfContentProvider.topShelfContentDidChange()
        Self.log.notice("Top Shelf: \(items.count) item(s)")
    }
}
#endif
