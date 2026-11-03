#if !os(tvOS)
import Foundation
import OSLog
import TortugaKit

private let log = Logger(subsystem: "com.edde746.tortuga", category: "downloads")

/// The shared core's `DownloadPlatform` on iPhone, iPad and the Mac (Apple TV has none: its storage is a cache the
/// system purges). One location, `internal`: `Application Support/Downloads` (on the Mac, whose Application Support
/// every app shares, in the app's own folder there), excluded from backups. The background `URLSession` moves the bytes
/// (`BackgroundTransport`). One per process, given to `AppGraph.installDownloads`.
final class AppleDownloadPlatform: DownloadPlatform {
    let transport: any DownloadTransport = BackgroundTransport.shared
    private let root: String

    init() {
        let support = NSSearchPathForDirectoriesInDomains(.applicationSupportDirectory, .userDomainMask, true).first
            ?? NSHomeDirectory() + "/Library/Application Support"
        #if os(macOS)
        root = support + "/" + (Bundle.main.bundleIdentifier ?? "com.edde746.tortuga") + "/Downloads"
        #else
        root = support + "/Downloads"
        #endif
        do {
            try FileManager.default.createDirectory(atPath: root, withIntermediateDirectories: true)
        } catch {
            log.error("downloads folder not made: \(String(describing: error), privacy: .public)")
        }
        excludeFromBackup(path: root)
    }

    func locations() -> [DownloadLocation] {
        [
            DownloadLocation(
                id: DownloadLocation.companion.INTERNAL, path: root,
                label: T.shared.settings.downloads.location.deviceStorage, removable: false
            ),
        ]
    }

    var choosesFolder: Bool { false }

    func freeBytes(path: String) -> KotlinLong? {
        BackgroundTransport.availableCapacity(atPath: path).map { KotlinLong(value: $0) }
    }

    /// Wi-Fi only is offered on iPhone and iPad, on by default; not on the Mac.
    var wifiOnlyDefault: KotlinBoolean? {
        #if os(macOS)
        nil
        #else
        KotlinBoolean(bool: true)
        #endif
    }

    /// The background session runs the transfers whether the app runs or not: nothing to start.
    func onQueueActive(active: Bool, wifiOnly: Bool) {}

    /// Keeps `path` out of iCloud and computer backups; a failure is logged, as the downloads would go into them.
    func excludeFromBackup(path: String) {
        var url = URL(filePath: path, directoryHint: .isDirectory)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        do {
            try url.setResourceValues(values)
        } catch {
            log.error("\((path as NSString).lastPathComponent, privacy: .public) not excluded from backups: \(String(describing: error), privacy: .public)")
        }
    }
}
#endif
