import SwiftUI
import TortugaKit

/// Home's shortcuts (`BoardUiState.shortcuts`, in the store's order: Watch together, reading "2 watching together" in a
/// room; Downloads where the device has them, reading "Downloading 2, 45%" while some are on their way; Library;
/// Calendar; each connected tracker's lists) as one line of the app's chips (`ShortcutChip`): a chip that opens one of
/// the app's screens leads with that screen's symbol, a tracker's list with its service's mark; none selected (each
/// opens a screen, none is a choice), the first lined up with the shelves' cards, scrolling sideways, nothing behind the
/// row. A chip runs `onOpen` with its key; VoiceOver reads its `BoardShortcut.accessibilityLabel`.
///
/// tvOS: each chip is a focus stop, white with black text while it has focus as Detail's season chips are
/// (`SurfaceButtonStyle`: nothing grows, so the row keeps one height). The row is a focus section that lands on its
/// first chip: Down from Continue Watching and Up from the shelf under the row reach it there, and Down from a chip goes
/// on to that shelf. A chip's focus is the Board's (`focusKey`), so coming back to Home finds it on the chip that had it.
///
/// Accessibility identifiers: `board.shortcuts` (the row) and `board.shortcut.<key>`.
struct BoardShortcuts: View {
    let shortcuts: [BoardShortcut]
    let focus: FocusState<String?>.Binding
    let onOpen: (String) -> Void

    @Environment(\.formFactor) private var formFactor
    @Environment(\.tortugaDimens) private var dimens
    @Environment(\.screenSides) private var sides

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: formFactor == .tv ? 16 : 8) {
                ForEach(shortcuts, id: \.key) { shortcut in
                    ShortcutChip(shortcut: shortcut) { onOpen(shortcut.key) }
                        .focused(focus, equals: Self.focusKey(shortcut))
                }
            }
            #if os(iOS)
            // The chips' glass (iOS 26 and later), one row of it, 8 pt apart.
            .glassGroup(spacing: 8)
            #endif
            .padding(sides.insets)
            // Room for the focus ring (keyboard focus), which the row doesn't clip.
            .padding(.vertical, dimens.focusRoom)
        }
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
        .shelfFocusSection()
        #if os(tvOS)
        .defaultFocus(focus, shortcuts.first.map(Self.focusKey), priority: .userInitiated)
        #endif
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("board.shortcuts")
    }

    /// A shortcut's key in the Board's focus.
    static func focusKey(_ shortcut: BoardShortcut) -> String { "shortcut:\(shortcut.key)" }
}

/// One of Home's shortcuts: the symbol of the screen it opens (`BoardShortcutKind.systemImage`), or for a tracker's list
/// its service's mark (`BoardShortcut.tracker`, the one Settings → Trackers shows, in one colour as the symbols are, a
/// square a fifth larger than the label's font), and its label, in the text colour and the label's font, the icon 6 pt
/// before the label (10 on tvOS), in a capsule of the app's chip size (`ChipMetrics`), regular weight, one line. The
/// icon is decorative: VoiceOver reads the label alone. On iOS 26 and later (iPhone and iPad) the capsule is Liquid
/// Glass, the glass of the header row's filters (`glassCapsule`), which lights up and swells under a finger.
/// Elsewhere, and before iOS 26, it is the surface fill (`Palette.surfaceContainer`): pressed it dims, with keyboard
/// focus it shows the focus ring, and on tvOS focus turns it white with black text and icon (`SurfaceButtonStyle`).
private struct ShortcutChip: View {
    let shortcut: BoardShortcut
    let action: () -> Void

    @Environment(\.formFactor) private var formFactor
    /// The label's font's size at the default text size, a fifth larger, growing with the text as the font does.
    #if os(tvOS)
    @ScaledMetric(relativeTo: .body) private var markSize: CGFloat = 35
    #elseif os(macOS)
    @ScaledMetric(relativeTo: .subheadline) private var markSize: CGFloat = 13
    #else
    @ScaledMetric(relativeTo: .subheadline) private var markSize: CGFloat = 18
    #endif

    var body: some View {
        let height = ChipMetrics.height(formFactor)
        let button = Button(action: action) {
            HStack(spacing: formFactor == .tv ? 10 : 6) {
                if let tracker = shortcut.tracker {
                    Image(decorative: tracker.markImageName)
                        .renderingMode(.template)
                        .resizable()
                        .scaledToFit()
                        .frame(width: markSize, height: markSize)
                } else if let symbol = shortcut.kind.systemImage {
                    Image(systemName: symbol).accessibilityHidden(true)
                }
                Text(shortcut.label).chipName(maxWidth: ChipMetrics.maxNameWidth(formFactor))
            }
            .font(ChipMetrics.font(formFactor))
            .padding(.horizontal, ChipMetrics.inset(formFactor))
            .frame(minHeight: height)
            .contentShape(Capsule())
        }
        Group {
            #if os(iOS)
            if #available(iOS 26, *) {
                button.glassCapsule()
            } else {
                button.buttonStyle(SurfaceButtonStyle(fill: Palette.surfaceContainer, radius: height / 2))
            }
            #else
            button.buttonStyle(SurfaceButtonStyle(fill: Palette.surfaceContainer, radius: height / 2))
            #endif
        }
        .accessibilityLabel(shortcut.accessibilityLabel)
        .accessibilityIdentifier("board.shortcut.\(shortcut.key)")
    }
}

extension BoardShortcutKind {
    /// The symbol of the screen a shortcut opens, the one that screen has elsewhere in the app: Watch together the
    /// room's indicator's, Downloads its empty state's, Library its tab's, the Calendar its empty state's, the TV guide
    /// the clock the other apps' guide has. A tracker's list has none: no one symbol fits a watchlist, a trending list
    /// and a list of one's own, so it shows its service's mark (`BoardShortcut.tracker`).
    var systemImage: String? {
        switch self {
        case .watchTogether: "person.2.fill"
        case .downloads: "arrow.down.circle"
        case .library: "books.vertical"
        case .guide: "clock"
        case .calendar: "calendar"
        case .trackerList: nil
        }
    }
}
