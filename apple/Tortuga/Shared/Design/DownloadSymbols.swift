import SwiftUI
import TortugaKit

/// How far a download is, as a ring that fills clockwise from the top: Detail's download button, an episode's download
/// line, a row of Library's Downloads. `value` is 0 to 1.
struct DownloadRing: View {
    let value: Double
    var lineWidth: CGFloat = 2.5

    var body: some View {
        ZStack {
            Circle().stroke(Palette.onSurfaceVariant.opacity(0.35), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: min(max(value, 0), 1))
                .stroke(Palette.primary, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .padding(lineWidth / 2)
        .accessibilityHidden(true)
    }
}

/// A download's state as one short line, muted (red once it failed): its symbol, or a small ring while it fills
/// (`progress`), then its words ("Downloading 45%", "Downloaded"). Detail's line under the action row, an episode's
/// line in its row.
struct DownloadStateLine: View {
    let text: String
    let systemImage: String
    var progress: Double?
    var failed = false
    var font: Font = .footnote

    var body: some View {
        Label {
            Text(text)
        } icon: {
            if let progress {
                DownloadRing(value: progress, lineWidth: 2).frame(width: 13, height: 13)
            } else {
                Image(systemName: systemImage)
            }
        }
        .font(font)
        .foregroundStyle(failed ? Palette.error : Palette.onSurfaceVariant)
        .lineLimit(1)
        .accessibilityElement(children: .combine)
    }
}

extension DetailDownloadState {
    /// The SF Symbol of Detail's download button and of an episode's download line in this state.
    var systemImage: String {
        switch self {
        case .available: "arrow.down"
        case .queued: "clock"
        case .downloading: "arrow.down.circle.dotted"
        case .paused: "pause.fill"
        case .failed: "exclamationmark.triangle"
        case .partial: "circle.lefthalf.filled"
        case .downloaded: "arrow.down.circle.fill"
        }
    }
}

extension DownloadStatus {
    /// The SF Symbol beside a download's status in Library's Downloads.
    var systemImage: String {
        switch self {
        case .queued: "clock"
        case .resolving: "hourglass"
        case .downloading: "arrow.down.circle.dotted"
        case .paused: "pause.circle"
        case .failed: "exclamationmark.triangle"
        case .completed: "arrow.down.circle.fill"
        case .cancelled: "xmark.circle"
        }
    }
}

/// Keep synced's symbol: Library's Downloads' row to its screen, and the screen's empty state.
let keepSyncedSymbol = "arrow.triangle.2.circlepath"

/// The SF Symbol beside a download menu's entry, by the store's entry id (`DetailDownloads`' ids: "pause", "delete");
/// nil for the season, series and Keep synced choices, which are words alone.
func downloadEntrySymbol(_ id: String) -> String? {
    switch id {
    case "download": "arrow.down.circle"
    case "pause", "pauseAll": "pause"
    case "resume", "resumeAll": "play"
    case "retry": "arrow.clockwise"
    case "pickStream": "list.bullet"
    case "cancel": "xmark"
    case "delete", "deleteSeason", "deleteAll": "trash"
    default: nil
    }
}
