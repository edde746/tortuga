import Nuke
import NukeUI
import SwiftUI
import TortugaKit

#if !os(tvOS)
/// The download (`detail.download`, where the device has downloads): a round button with the state's symbol, a ring
/// filling around a down arrow while it downloads with a known size, and its label for VoiceOver and the harness (on
/// the Mac a tooltip too). With `opensMenu` a press opens the download menu (a series' "Download season…" choices and
/// Keep synced, or what can be done with the download); otherwise a press acts at once (`pressDownload`) and a long
/// press or a right click opens the menu. A progress report moves the ring, laid over the control, and the label
/// alone: the control with its menu is built again only when what it shows changes (`DetailDownloadControl`), since a
/// menu built again while open dropped the tap on its entry.
struct DetailDownloadButton: View {
    let download: DetailDownload
    let intents: DetailIntents

    var body: some View {
        let ring = download.state == .downloading ? download.progress?.doubleValue : nil
        DetailDownloadControl(menu: download.menu, opensMenu: download.opensMenu, state: download.state, ringed: ring != nil, intents: intents)
            .equatable()
            .overlay {
                if let ring {
                    DownloadRing(value: ring)
                        .frame(width: detailRingSide, height: detailRingSide)
                        .allowsHitTesting(false)
                }
            }
            .detailGlassCircle(menu: download.opensMenu)
            .accessibilityLabel(download.label)
            .accessibilityIdentifier("detail.download")
            #if os(macOS)
            .tooltip(download.label)
            #endif
    }
}

/// The download button's control without its ring: the round menu or button with its symbol, and the download menu.
private struct DetailDownloadControl: View {
    let menu: [DetailDownloadSection]
    let opensMenu: Bool
    let state: DetailDownloadState
    /// The ring is laid over it (`DetailDownloadButton`): its symbol is the down arrow inside the ring.
    let ringed: Bool
    let intents: DetailIntents

    var body: some View {
        if opensMenu {
            Menu {
                DownloadMenuSections(sections: menu, onPick: intents.downloadAction)
            } label: {
                symbol
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
        } else {
            Button(action: intents.pressDownload) {
                symbol
            }
            .contextMenu {
                DownloadMenuSections(sections: menu, onPick: intents.downloadAction)
            }
        }
    }

    /// The symbol in the round buttons' symbol square, padded into the glass circle where that pads its label
    /// (`roundGlassLabel`: the Mac's, a menu's on iOS).
    private var symbol: some View {
        Group {
            if ringed {
                Image(systemName: "arrow.down")
                    .font(.caption2.weight(.bold))
                    .frame(width: detailRingSide, height: detailRingSide)
            } else {
                Image(systemName: state.systemImage)
                    .font(.body.weight(.semibold))
                    .frame(width: detailSymbolBox, height: detailSymbolBox)
            }
        }
        .roundGlassLabel(menu: opensMenu)
    }
}

/// SwiftUI compares views on the main actor, where the menu's store lives.
extension DetailDownloadControl: @preconcurrency Equatable {
    static func == (lhs: DetailDownloadControl, rhs: DetailDownloadControl) -> Bool {
        lhs.menu == rhs.menu && lhs.opensMenu == rhs.opensMenu && lhs.state == rhs.state && lhs.ringed == rhs.ringed
    }
}

/// The download ring's side, the symbol square's where the platform has one.
private var detailRingSide: CGFloat {
    detailSymbolBox ?? 20
}

/// A download menu's sections in order, each under its title when it has one ("Season 2", "Keep synced"); an entry in
/// effect (Keep synced's count) is checked, one that deletes is red. An entry is `detail.download.<entry id>`.
struct DownloadMenuSections: View {
    let sections: [DetailDownloadSection]
    let onPick: (String) -> Void

    var body: some View {
        ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
            Section {
                ForEach(section.entries, id: \.id) { entry in
                    Button(role: entry.destructive ? .destructive : nil) { onPick(entry.id) } label: {
                        if entry.selected {
                            Label(entry.label, systemImage: "checkmark")
                        } else if let symbol = downloadEntrySymbol(entry.id) {
                            Label(entry.label, systemImage: symbol)
                        } else {
                            Text(entry.label)
                        }
                    }
                    .accessibilityIdentifier("detail.download.\(entry.id)")
                }
            } header: {
                if let title = section.title { Text(title) }
            }
        }
    }
}
#endif

/// Under the action row, the download's state in words with its symbol ("Downloading 45%", "3 of 10 downloaded"),
/// once there is a download (`detail.download.label`).
struct DetailDownloadLine: View {
    let download: DetailDownload

    @Environment(\.formFactor) private var formFactor

    var body: some View {
        DownloadStateLine(
            text: download.label,
            systemImage: download.state.systemImage,
            progress: download.state == .downloading ? download.progress?.doubleValue : nil,
            failed: download.state == .failed,
            font: formFactor == .tv ? .callout : .footnote
        )
        .accessibilityIdentifier("detail.download.label")
    }
}
