import Nuke
import NukeUI
import SwiftUI
import TortugaKit

#if os(macOS)
import AppKit
#else
import UIKit
#endif

#if !os(tvOS)
/// The top of the details: the title (its logo when the addon has one), the metadata line with the ratings row under
/// it, the row of actions with the episode the primary action plays, the download's state and the title's playback in
/// a player app under it, then, while the details shown are the ones saved with the downloads, a banner saying so
/// (`detail.offlineNote`), and the description. On phones (`centred`) the title, the metadata line, the ratings and the
/// actions sit centred under the backdrop, as in the Apple TV app; the description stays at the leading edge. A
/// preview (the catalog item's header while the title loads) is the title, the metadata line and the ratings alone,
/// where the whole headline has them. Apple TV's page has its own title block (`DetailTvPage`).
struct DetailHeadline: View {
    let state: DetailUiState
    let header: DetailHeader
    let intents: DetailIntents
    /// Phones and narrow windows: centred, the actions one compact group.
    let centred: Bool
    /// The widest the logo may be.
    let logoWidth: CGFloat
    /// The action row's room where it isn't the column's (`DetailActionRow.width`): a movie's on wide windows.
    let actionsWidth: CGFloat?
    /// How far above the headline its title's box may rise: the room over it in its column.
    let roomAbove: CGFloat
    let focus: FocusState<DetailFocus?>.Binding
    /// Called with how far under the title's box's top the name's first line ends while the name shows, nil while the
    /// logo shows (`DetailTitleArt.onFirstLine`).
    var onFirstLine: (CGFloat?) -> Void = { _ in }

    @Environment(\.formFactor) private var formFactor
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        // Where the title has a box (a logo; a narrow page's name over the backdrop), the box rises by the room a
        // Cinemeta logo's transparent bottom took in it. The actions follow the metadata block at the headline's
        // spacing, as the block follows the title: no row keeps room for one that isn't there.
        let boxed = header.logo != nil || centred && header.background != nil
        let box = DetailMetrics.titleHeight(formFactor, centred: centred)
        let lift = boxed ? DetailMetrics.titleLift(logoWidth: logoWidth, boxHeight: box) : 0
        let alignment: HorizontalAlignment = centred ? .center : .leading
        VStack(alignment: alignment, spacing: 14) {
            // Art as tall as the box would rise past the room above: that much shorter.
            DetailTitleArt(
                header: header, maxWidth: logoWidth, artHeight: box - max(lift - roomAbove, 0), centred: centred, onFirstLine: onFirstLine
            )
            .padding(.top, -lift)
            if state.showsMetaLine {
                VStack(alignment: alignment, spacing: 8) {
                    DetailMetaLine(header: header, centred: centred)
                    DetailRatingsRow(
                        ratings: header.ratings,
                        userRating: state.userRating,
                        centred: centred,
                        onOpenLink: intents.openLink
                    )
                }
            }
            // What a long wait waits for, in the actions' place.
            if state.preview, let note = state.loadingNote {
                Text(note)
                    .font(.subheadline)
                    .foregroundStyle(Palette.onSurfaceVariant)
                    .multilineTextAlignment(centred ? .center : .leading)
                    .accessibilityIdentifier("detail.loadingNote")
            }
            if !state.preview {
                VStack(alignment: alignment, spacing: 8) {
                    DetailActionRow(state: state, intents: intents, compact: centred, width: actionsWidth, focus: focus)
                    if let line = state.primaryAction?.episodeTitle {
                        // Whole at the accessibility text sizes, on as many lines as it takes.
                        Text(line)
                            .font(.footnote)
                            .foregroundStyle(Palette.onSurfaceVariant)
                            .multilineTextAlignment(centred ? .center : .leading)
                            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
                    }
                    if let download = state.download, download.state != .available {
                        DetailDownloadLine(download: download)
                    }
                    if let playerApp = state.playerApp {
                        DetailPlayerAppLine(playerApp: playerApp, centred: centred, focus: focus, onAnswer: intents.answerPlayerApp)
                    }
                }
                if let note = state.offlineNote {
                    DetailOfflineNote(text: note)
                }
                if let overview = header.overview {
                    DetailOverview(text: overview)
                }
            }
        }
    }
}
#endif

/// Release, runtime and genres on one line (cut short on Apple TV's page, which has no room for a second).
struct DetailMetaLine: View {
    let header: DetailHeader
    let centred: Bool

    @Environment(\.formFactor) private var formFactor

    var body: some View {
        if let info = header.infoLine {
            Text(info)
                .font(formFactor == .tv ? .callout : .subheadline)
                .foregroundStyle(Palette.onSurfaceVariant)
                .multilineTextAlignment(centred ? .center : .leading)
                .lineLimit(formFactor == .tv ? 1 : nil)
                .accessibilityIdentifier("detail.infoLine")
        }
    }
}

/// Under the action row, the title's playback in a player app (`DetailPlayerApp`): while a player that reports back
/// plays it, the line following it ("Playing in mpv · 12:34 of 1:45:00", "Paused in mpv · …" while paused); after one
/// that reports nothing, the question ("Opened in VLC. Finished watching …?") with Mark as watched and Not yet, which
/// answer it. Inline, not a dialog: it stays until answered or a later playback of the title replaces it.
///
/// Accessibility identifiers: `detail.playerApp` (the line), `detail.playerApp.markWatched`, `detail.playerApp.notYet`;
/// the answers are `.playerApp(finished:)` in the page's `focus`.
struct DetailPlayerAppLine: View {
    let playerApp: DetailPlayerApp
    let centred: Bool
    let focus: FocusState<DetailFocus?>.Binding
    let onAnswer: (_ finished: Bool) -> Void

    @Environment(\.formFactor) private var formFactor

    var body: some View {
        let tv = formFactor == .tv
        VStack(alignment: centred ? .center : .leading, spacing: tv ? 14 : 8) {
            Label(playerApp.text, systemImage: "arrow.up.forward.app")
                .font(tv ? .callout : .footnote)
                .foregroundStyle(Palette.onSurfaceVariant)
                .multilineTextAlignment(centred ? .center : .leading)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("detail.playerApp")
            if playerApp.markWatchedText != nil || playerApp.notYetText != nil {
                HStack(spacing: tv ? 24 : 10) {
                    if let markWatched = playerApp.markWatchedText {
                        Button { onAnswer(true) } label: {
                            Label(markWatched, systemImage: watchedSymbol(false))
                        }
                        .buttonProminence(.secondary)
                        .focused(focus, equals: .playerApp(finished: true))
                        .accessibilityIdentifier("detail.playerApp.markWatched")
                    }
                    if let notYet = playerApp.notYetText {
                        Button { onAnswer(false) } label: { Text(notYet).hitTargetLabel() }
                            .buttonProminence(.plain)
                            .focused(focus, equals: .playerApp(finished: false))
                            .accessibilityIdentifier("detail.playerApp.notYet")
                            .hitTarget()
                    }
                }
                // The answers' glass (both on Apple TV), one row of it.
                .glassGroup(spacing: tv ? 24 : 10)
                .font(tv ? .callout : .subheadline.weight(.semibold))
            }
        }
    }
}

/// The banner over the details while they are the ones saved with the downloads ("Showing the details saved with your
/// downloads."): offline, or no addon could load the title.
struct DetailOfflineNote: View {
    let text: String

    var body: some View {
        Label(text, systemImage: "wifi.slash")
            .font(.footnote.weight(.medium))
            .foregroundStyle(Palette.onSurfaceVariant)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(Palette.surfaceContainerHigh, in: Capsule())
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("detail.offlineNote")
    }
}

#if !os(tvOS)
/// The description, three lines until More opens the rest (Less closes it again). More shows only when the text runs
/// past three lines.
private struct DetailOverview: View {
    let text: String

    @State private var expanded = false
    @State private var fullHeight: CGFloat = 0
    @State private var shownHeight: CGFloat = 0

    var body: some View {
        let font: Font = .subheadline
        VStack(alignment: .leading, spacing: 2) {
            Text(text)
                .font(font)
                .foregroundStyle(Palette.onSurfaceVariant)
                .lineLimit(expanded ? nil : 3)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { shownHeight = $0 }
                .background {
                    Text(text)
                        .font(font)
                        .fixedSize(horizontal: false, vertical: true)
                        .hidden()
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { fullHeight = $0 }
                }
            if expanded || fullHeight > shownHeight + 1 {
                Button { expanded.toggle() } label: {
                    Text(expanded ? T.shared.detail.showLess : T.shared.detail.showMore)
                        .font(font.weight(.semibold))
                        .hitTargetLabel()
                }
                .buttonStyle(.borderless)
                .accessibilityIdentifier("detail.more")
                .hitTarget()
            }
        }
    }
}
#endif
