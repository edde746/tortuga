import Nuke
import NukeUI
import SwiftUI
import TortugaKit

/// The one row of actions, never wrapping: Play/Resume (the watched part filled inside it; a spinner for its symbol
/// while its stream is being found, `DetailPrimaryAction.busy`: auto-play's pick, or with auto-play off the stream it
/// last played from asked of that stream's addon again) at its label's width, then round buttons for choosing
/// the stream (with auto-play on: the stream list for Play's video, `detail.chooseStream`), the library (+ / ✓), the
/// watched mark, the download (`DetailDownloadButton`, where the device has downloads), the trailer (a menu of them
/// when there are several) and Share, every one at its full size. Where the row has no room for them all (a phone, a
/// narrow window, the details' column beside a series' episodes), the most important stay (`fitActions`, the rule
/// every UI shares) and a round ⋯ at the row's end (`DetailOverflowButton`) opens the rest; Play's label is cut short
/// only when Play and ⋯ alone don't fit. Rate (`DetailAction.rate`, where a tracker rates the title) and Block on this
/// profile (`DetailAction.kidsBlock`, on a kids profile) are always behind ⋯, which then shows however much room the
/// row has. On phones (`compact`) the row is one tight group, centred by its column. Rate opens the rating picker
/// (`detailRatingPicker`).
/// The round buttons carry the actions' words for VoiceOver and the harness (`detail.library`'s label). On Apple TV the
/// row has every action this title has in the TVs' order (`tvActions`; Rate, `detail.rate`, and Block on this profile,
/// `detail.kidsBlockAction`, last as round buttons; nothing behind ⋯, as the row has the page's width), Play reads "No
/// streams found" once every stream addon has answered none for its video (`DetailPrimaryAction.noStreams`; pressing it
/// still opens the stream list), and each round button tells the page where it is (`DetailActionAnchors`), for the line
/// under the row.
struct DetailActionRow: View {
    let state: DetailUiState
    let intents: DetailIntents
    let compact: Bool
    /// The row's room, from its leading edge, where it is wider than the column's (a movie's on wide windows, where
    /// nothing is beside the details: the page's width, past the details' reading width); nil: the column's.
    let width: CGFloat?
    let focus: FocusState<DetailFocus?>.Binding

    @Environment(\.formFactor) private var formFactor
    /// The rating picker shows (`DetailAction.rate`).
    @State private var rating = false

    var body: some View {
        Group {
            #if os(tvOS)
            row(DetailActionFit(shown: state.tvActions, more: []))
            #else
            // The widest row first: every action, then a round button fewer each time, ⋯ in the last one's place. With
            // Rate or Block on this profile, always behind ⋯, the widest is every other action beside ⋯.
            ViewThatFits(in: .horizontal) {
                ForEach(Array((1...max(state.actions.count, 1)).reversed()), id: \.self) { slots in
                    row(state.fitActions(slots: Int32(slots)))
                }
            }
            .frame(width: width, alignment: .leading)
            #endif
        }
        .detailRatingPicker(isPresented: $rating, rating: state.userRating, onRate: intents.rate, focus: focus)
    }

    private func row(_ fit: DetailActionFit) -> some View {
        let spacing: CGFloat = formFactor == .tv ? 24 : compact ? 8 : 12
        return HStack(spacing: spacing) {
            if let action = state.primaryAction {
                Button(action: intents.play) {
                    PlayLabel(label: Self.label(of: action), busy: action.busy)
                        .frame(maxHeight: .infinity)
                        // The label's least width; the capsule adds its sides (`ProgressButtonStyle`): on phones Play
                        // is at least 128 pt wide.
                        .frame(minWidth: formFactor == .tv ? 320 : compact ? 96 : 180)
                }
                // An episode's label is its code alone ("S1E5"), a resume's only where it resumes from ("S1E5 · 32:44");
                // VoiceOver can't see the symbol or the fill, so it hears "Play S1E5", "Resume S1E5 · 32:44". Voice
                // Control takes the words shown as well as those.
                .accessibilityLabel(Self.spokenLabel(of: action))
                .accessibilityInputLabels([Self.label(of: action), Self.spokenLabel(of: action)])
                .buttonStyle(ProgressButtonStyle(progress: action.progress?.doubleValue))
                .focused(focus, equals: .play)
                .accessibilityIdentifier("detail.play")
            }
            ForEach(fit.shown, id: \.self) { action in
                roundButton(action)
                    .focused(focus, equals: .action(action))
                    #if os(tvOS)
                    .anchorPreference(key: DetailActionAnchors.self, value: .bounds) { [action: $0] }
                    #endif
            }
            if !fit.more.isEmpty {
                DetailOverflowButton(actions: fit.more, state: state, intents: intents) { rating = true }
                    .equatable()
                    .detailCircleSlot()
            }
        }
        // The round buttons' glass, one row of it, on Apple TV: not on iOS, where the row's menus (⋯, Download,
        // Trailers) open over its buttons, nor on the Mac (`glassGroup`).
        #if os(tvOS)
        .glassGroup(spacing: spacing)
        #endif
        // The primary action as tall as the round buttons.
        .fixedSize(horizontal: false, vertical: true)
    }

    /// Play's words: on Apple TV "No streams found" once every stream addon has answered none (`tvLabel`).
    private static func label(of action: DetailPrimaryAction) -> String {
        #if os(tvOS)
        action.tvLabel
        #else
        action.label
        #endif
    }

    private static func spokenLabel(of action: DetailPrimaryAction) -> String {
        #if os(tvOS)
        action.tvSpokenLabel
        #else
        action.spokenLabel
        #endif
    }

    @ViewBuilder
    private func roundButton(_ action: DetailAction) -> some View {
        switch action {
        case .chooseStream:
            IconButton(systemImage: "list.bullet", label: state.chooseStreamText ?? "", id: "detail.chooseStream", symbolBox: detailSymbolBox, action: intents.chooseStream)
                .detailRoundButton()
        case .library:
            IconButton(
                systemImage: state.inLibrary ? "checkmark" : "plus",
                label: state.libraryActionText,
                id: "detail.library",
                symbolBox: detailSymbolBox,
                action: state.inLibrary ? intents.removeFromLibrary : intents.addToLibrary
            )
            .detailRoundButton()
        case .watched:
            IconButton(
                systemImage: watchedSymbol(state.watched),
                label: state.watchedActionText,
                id: "detail.watched",
                symbolBox: detailSymbolBox,
                action: { intents.markWatched(!state.watched) }
            )
            .detailRoundButton()
        case .download:
            downloadButton
        case .trailer:
            DetailTrailerButton(state: state, onOpen: intents.openTrailer)
                .detailCircleSlot()
        case .share:
            shareButton
        case .rate:
            // Apple TV's row (`tvActions`); elsewhere it is behind ⋯ (`fitActions`).
            IconButton(
                systemImage: state.userRating?.text != nil ? "star.fill" : "star",
                label: state.userRating?.label ?? "",
                id: "detail.rate",
                symbolBox: detailSymbolBox
            ) { rating = true }
            .detailRoundButton()
        case .kidsBlock:
            // Apple TV's row (`tvActions`); elsewhere it is behind ⋯ (`fitActions`).
            IconButton(
                systemImage: "nosign",
                label: state.kidsBlockAction ?? "",
                id: "detail.kidsBlockAction",
                symbolBox: detailSymbolBox,
                action: intents.blockOnProfile
            )
            .detailRoundButton()
        case .watchlist:
            // Apple TV's row (`tvActions`), before Rate; elsewhere it is behind ⋯ (`fitActions`).
            IconButton(
                systemImage: state.watchlist?.listed == true ? "bookmark.fill" : "bookmark",
                label: state.watchlist?.label ?? "",
                id: "detail.watchlist",
                symbolBox: detailSymbolBox,
                action: intents.watchlist
            )
            .detailRoundButton()
        }
    }

    /// Share: the system's share sheet with the title's page (`detail.share`), drawn as `IconButton` is. Apple TV has
    /// none (`state.share` is nil there).
    @ViewBuilder
    private var shareButton: some View {
        #if !os(tvOS)
        if let share = state.share, let url = URL(string: share.url) {
            ShareLink(item: url) {
                Image(systemName: "square.and.arrow.up")
                    .font(.body.weight(.semibold))
                    .frame(width: detailSymbolBox, height: detailSymbolBox)
                    .roundGlassLabel()
            }
            .roundGlassControl()
            #if os(macOS)
            .tooltip(share.label)
            #endif
            .accessibilityLabel(share.label)
            .accessibilityIdentifier("detail.share")
            .detailRoundButton()
        }
        #endif
    }

    /// The download's button, where the device has downloads (Apple TV has none: `state.download` is nil there).
    @ViewBuilder
    private var downloadButton: some View {
        #if !os(tvOS)
        if let download = state.download {
            DetailDownloadButton(download: download, intents: intents)
                .detailCircleSlot()
        }
        #endif
    }
}

/// Play's symbol and label on one line in the button style's font, cut short only where Play and ⋯ alone have no
/// room (`DetailActionRow`); on Apple TV shrunk first, to 11/14 of its size, in the room the round buttons leave.
private struct PlayLabel: View {
    let label: String
    let busy: Bool

    var body: some View {
        Label {
            Text(label)
        } icon: {
            PlaySymbol(busy: busy)
        }
        .lineLimit(1)
        #if os(tvOS)
        .minimumScaleFactor(11.0 / 14.0)
        #endif
    }
}

/// Play's symbol, or a small spinner while the stream to play is being found.
private struct PlaySymbol: View {
    let busy: Bool

    var body: some View {
        if busy {
            ProgressView().smallSpinner()
        } else {
            Image(systemName: "play.fill")
        }
    }
}

/// The action row's height: the Play capsule's, and on phones the round buttons' square.
private func detailActionHeight(_ formFactor: FormFactor) -> CGFloat {
    #if os(macOS)
    34
    #else
    switch formFactor {
    case .tv: 66
    case .compact: 48
    case .regular: 44
    }
    #endif
}

extension View {
    /// iPhone and iPad: a round button's place in the action row, a square of its circle's size (the symbol square in
    /// the glass button style's room around it, `IconButton.circleInset`: 48 pt, Play's height on phones). On iOS 26
    /// the system's glass circle keeps room around itself for its press effect, which widened the gaps between the
    /// circles past the row's spacing (unevenly beside a round menu's, which keeps none: `roundGlassControl`) and left
    /// the phone's centred row off centre by that room; in its square the circle sits where the row lays it out. The
    /// circle keeps the default text size's: a larger one grew it past its square, out of the row's margins. At the
    /// accessibility text sizes a long press shows its symbol and label large (the Large Content Viewer).
    func detailCircleSlot() -> some View {
        #if os(iOS)
        let side = (detailSymbolBox ?? 0) + 2 * IconButton.circleInset
        return dynamicTypeSize(...DynamicTypeSize.large)
            .frame(width: side, height: side)
            .accessibilityShowsLargeContentViewer()
        #else
        return self
        #endif
    }

    /// A round button of the action row at the large control size (`largeControl`) in its place (`detailCircleSlot`).
    func detailRoundButton() -> some View {
        largeControl().detailCircleSlot()
    }

    /// A round control of the action row that isn't an `IconButton` (the download, the trailers' menu, ⋯), a `menu` or
    /// a button: a round glass control (`roundGlassControl`) at the round buttons' size (`largeControl`); the Mac's and
    /// an iOS menu's take their size from their label (`roundGlassLabel`).
    func detailGlassCircle(menu: Bool = false) -> some View {
        roundGlassControl(menu: menu)
            #if !os(macOS)
            .largeControl()
            #endif
    }
}

/// The round buttons' symbol square, which sizes their circles alike whatever each symbol's shape. iOS: 18 pt, a 48 pt
/// circle, Play's height on phones; at their own size the glass circles followed their symbols' heights (⋯ 36 pt, the
/// list 45, + 47, the trailer's 48). The Mac's circles are small at their own size, next to the Play capsule; in their
/// glass circle (`roundGlassLabel`) as tall as Play.
var detailSymbolBox: CGFloat? {
    #if os(macOS)
    22
    #elseif os(iOS)
    18
    #else
    nil
    #endif
}

/// The trailer: one opens at once; several open a menu of them (`detail.trailer.item.<n>`).
private struct DetailTrailerButton: View {
    let state: DetailUiState
    let onOpen: (String) -> Void

    var body: some View {
        let trailers = state.trailers
        if let trailer = state.singleTrailer {
            IconButton(systemImage: "play.rectangle", label: state.trailerActionText, id: "detail.trailer", symbolBox: detailSymbolBox) {
                onOpen(trailer.id)
            }
            .largeControl()
        } else {
            Menu {
                ForEach(Array(trailers.enumerated()), id: \.element.id) { index, trailer in
                    Button(trailer.label) { onOpen(trailer.id) }
                        .accessibilityIdentifier("detail.trailer.item.\(index)")
                }
            } label: {
                Image(systemName: "play.rectangle")
                    .font(.body.weight(.semibold))
                    .frame(width: detailSymbolBox, height: detailSymbolBox)
                    .roundGlassLabel(menu: true)
            }
            .menuStyle(.button)
            .menuIndicator(.hidden)
            .detailGlassCircle(menu: true)
            #if os(macOS)
            .tooltip(state.trailerActionText)
            #endif
            .accessibilityLabel(state.trailerActionText)
            .accessibilityIdentifier("detail.trailer")
        }
    }
}

/// The actions the row has no room for (`DetailActionFit.more`), behind a round ⋯ at its end (`detail.overflow`, spoken
/// "More actions"): a menu of their words and symbols in the row's order, each doing what its button does: Choose a
/// stream, the library, the watched mark, the download (its press, or its menu where the button opens one: a series'
/// choices, what can be done with a download; "Downloading…" while it downloads, without the percentage, which would
/// build the menu again on every progress report), the trailer (several: a menu of them), Share, Rate (opening the
/// rating picker: `onRate`) and Block on this profile. Entries are `detail.overflow.<action>`
/// (`detail.overflow.kidsBlock`, `detail.overflow.rate`); inside them the download menu's `detail.download.<entry id>`
/// and the trailers' `detail.trailer.item.<n>`, as in the buttons' own menus.
private struct DetailOverflowButton: View {
    let actions: [DetailAction]
    let state: DetailUiState
    let intents: DetailIntents
    /// Opens the rating picker (`DetailActionRow`'s).
    let onRate: () -> Void

    var body: some View {
        Menu {
            ForEach(actions, id: \.self) { action in
                entry(action)
            }
        } label: {
            Image(systemName: "ellipsis")
                .font(.body.weight(.semibold))
                .frame(width: detailSymbolBox, height: detailSymbolBox)
                .roundGlassLabel(menu: true)
        }
        .menuStyle(.button)
        .menuIndicator(.hidden)
        .detailGlassCircle(menu: true)
        #if os(macOS)
        .tooltip(T.shared.detail.moreActions)
        #endif
        .accessibilityLabel(T.shared.detail.moreActions)
        .accessibilityIdentifier("detail.overflow")
    }

    @ViewBuilder
    private func entry(_ action: DetailAction) -> some View {
        switch action {
        case .chooseStream:
            Button(action: intents.chooseStream) {
                Label(state.chooseStreamText ?? "", systemImage: "list.bullet")
            }
            .accessibilityIdentifier("detail.overflow.chooseStream")
        case .library:
            Button(action: state.inLibrary ? intents.removeFromLibrary : intents.addToLibrary) {
                Label(state.libraryActionText, systemImage: state.inLibrary ? "checkmark" : "plus")
            }
            .accessibilityIdentifier("detail.overflow.library")
        case .watched:
            Button { intents.markWatched(!state.watched) } label: {
                Label(state.watchedActionText, systemImage: watchedSymbol(state.watched))
            }
            .accessibilityIdentifier("detail.overflow.watched")
        case .download:
            downloadEntry
        case .trailer:
            if let trailer = state.singleTrailer {
                Button { intents.openTrailer(trailer.id) } label: {
                    Label(state.trailerActionText, systemImage: "play.rectangle")
                }
                .accessibilityIdentifier("detail.overflow.trailer")
            } else {
                Menu {
                    ForEach(Array(state.trailers.enumerated()), id: \.element.id) { index, trailer in
                        Button(trailer.label) { intents.openTrailer(trailer.id) }
                            .accessibilityIdentifier("detail.trailer.item.\(index)")
                    }
                } label: {
                    Label(state.trailerActionText, systemImage: "play.rectangle")
                }
                .accessibilityIdentifier("detail.overflow.trailer")
            }
        case .share:
            shareEntry
        case .rate:
            Button(action: onRate) {
                Label(state.userRating?.label ?? "", systemImage: state.userRating?.text != nil ? "star.fill" : "star")
            }
            .accessibilityIdentifier("detail.overflow.rate")
        case .kidsBlock:
            Button(action: intents.blockOnProfile) {
                Label(state.kidsBlockAction ?? "", systemImage: "nosign")
            }
            .accessibilityIdentifier("detail.overflow.kidsBlock")
        case .watchlist:
            Button(action: intents.watchlist) {
                Label(state.watchlist?.label ?? "", systemImage: state.watchlist?.listed == true ? "bookmark.fill" : "bookmark")
            }
            .accessibilityIdentifier("detail.overflow.watchlist")
        }
    }

    /// Share's entry, where the device shares.
    @ViewBuilder
    private var shareEntry: some View {
        #if !os(tvOS)
        if let share = state.share, let url = URL(string: share.url) {
            ShareLink(item: url) {
                Label(share.label, systemImage: "square.and.arrow.up")
            }
            .accessibilityIdentifier("detail.overflow.share")
        }
        #endif
    }

    /// The download's entry, where the device has downloads: without its percentage ("Downloading…"), so a progress
    /// report doesn't build the menu again (`DetailOverflowButton`'s `==`).
    @ViewBuilder
    private var downloadEntry: some View {
        #if !os(tvOS)
        if let download = state.download {
            if download.opensMenu {
                Menu {
                    DownloadMenuSections(sections: download.menu, onPick: intents.downloadAction)
                } label: {
                    Label(Self.entryLabel(download), systemImage: download.state.systemImage)
                }
                .accessibilityIdentifier("detail.overflow.download")
            } else {
                Button(action: intents.pressDownload) {
                    Label(Self.entryLabel(download), systemImage: download.state.systemImage)
                }
                .accessibilityIdentifier("detail.overflow.download")
            }
        }
        #endif
    }

    /// The download entry's words: "Downloading…" while it downloads, whatever its percentage.
    private static func entryLabel(_ download: DetailDownload) -> String {
        download.state == .downloading ? T.shared.detail.download.downloadingStarted : download.label
    }
}

/// SwiftUI compares views on the main actor, where the state's store lives. Equal while every value its entries read is:
/// the page's state changes on every progress report of a download, and a menu built again while open dropped the tap
/// on its entry, the menu staying open and the action never running (`SteadyMenuButton`). An entry that reads more of
/// the state adds it here.
extension DetailOverflowButton: @preconcurrency Equatable {
    static func == (lhs: DetailOverflowButton, rhs: DetailOverflowButton) -> Bool {
        let a = lhs.state, b = rhs.state
        return lhs.actions == rhs.actions
            && a.chooseStreamText == b.chooseStreamText
            && a.inLibrary == b.inLibrary && a.libraryActionText == b.libraryActionText
            && a.watched == b.watched && a.watchedActionText == b.watchedActionText
            && a.download.map(entryLabel) == b.download.map(entryLabel)
            && a.download?.state == b.download?.state
            && a.download?.opensMenu == b.download?.opensMenu
            && a.download?.menu == b.download?.menu
            && a.trailers == b.trailers && a.trailerActionText == b.trailerActionText
            && a.share == b.share
            && a.userRating?.label == b.userRating?.label && (a.userRating?.text != nil) == (b.userRating?.text != nil)
            && a.kidsBlockAction == b.kidsBlockAction
            && a.watchlist == b.watchlist
    }
}

/// A capsule in the primary fill (white, black text) with `progress` (0–1, the watched part) filled inside it in a
/// darker tone. tvOS: translucent until focused, then white, grown a little, shrinking a little while clicked
/// (`tvPressed`); iOS and macOS: dimmed while pressed, the focus ring on keyboard focus.
private struct ProgressButtonStyle: ButtonStyle {
    let progress: Double?

    func makeBody(configuration: Configuration) -> some View {
        ProgressCapsule(configuration: configuration, progress: progress)
    }

    private struct ProgressCapsule: View {
        let configuration: Configuration
        let progress: Double?

        @Environment(\.isFocused) private var focused
        @Environment(\.formFactor) private var formFactor

        /// In the primary fill: always on iOS and macOS; on tvOS while focused (translucent until then).
        private var lit: Bool {
            #if os(tvOS)
            focused
            #else
            true
            #endif
        }

        var body: some View {
            let tv = formFactor == .tv
            // At least as tall as the round buttons beside it (the row then stretches it to theirs).
            let height = detailActionHeight(formFactor)
            configuration.label
                .font((tv ? Font.callout : .body).weight(.semibold))
                .foregroundStyle(lit ? Palette.onPrimary : Palette.onBackground)
                // Phones a little narrower, for the label's room beside four or five round buttons.
                .padding(.horizontal, tv ? 36 : formFactor == .compact ? 16 : 22)
                .frame(minHeight: height)
                .background {
                    Capsule()
                        .fill(lit ? Palette.primary : Palette.primary.opacity(0.16))
                        .overlay(alignment: .leading) {
                            if let progress {
                                GeometryReader { geometry in
                                    Rectangle()
                                        .fill(lit ? Color(white: 0.76) : Palette.primary.opacity(0.18))
                                        .frame(width: geometry.size.width * min(max(progress, 0), 1))
                                }
                            }
                        }
                        .clipShape(Capsule())
                }
                .contentShape(Capsule())
                #if os(tvOS)
                .scaleEffect(focused ? 1.05 : 1)
                .shadow(color: .black.opacity(focused ? 0.45 : 0), radius: 16, y: 8)
                .animation(.easeOut(duration: 0.15), value: focused)
                .tvPressed(configuration.isPressed)
                #else
                .opacity(configuration.isPressed ? 0.75 : 1)
                .focusRing(radius: height / 2)
                #endif
        }
    }
}
