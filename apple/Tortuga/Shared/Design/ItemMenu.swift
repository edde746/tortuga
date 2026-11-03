import SwiftUI
import TortugaKit
#if os(iOS)
import UIKit.UIGestureRecognizerSubclass
#endif

/// One entry of an item's menu: its `label` from the screen's store, the SF Symbol beside it if any (the watched
/// toggle's eye), its accessibility identifier where it needs a fixed one (else `<probe>.<index>`), whether it removes
/// something (`destructive`: the system's red), and the intent it sends; or, with a `submenu`, the entries it opens
/// instead (`playWith`).
struct ItemMenuEntry {
    let label: String
    var systemImage: String?
    var id: String?
    var destructive = false
    /// The entries of the submenu the entry opens (its `action` then goes unused); empty for an entry that acts itself.
    var submenu: [ItemMenuEntry] = []
    let action: () -> Void
}

extension ItemMenuEntry {
    /// "Play with…" (`PlayWithMenu`): a submenu titled as `menu` says, identified `id`, of Tortuga and the player apps
    /// on this device, each `<id>.<player id>` (`streams.menu.playWith.vlc`, as the Compose probes are); `onPick` takes
    /// the player's id.
    static func playWith(_ menu: PlayWithMenu, id: String, onPick: @escaping (String) -> Void) -> ItemMenuEntry {
        let players = menu.entries.map { entry in
            ItemMenuEntry(label: entry.label, id: "\(id).\(entry.playerId)") { onPick(entry.playerId) }
        }
        return ItemMenuEntry(label: menu.title, systemImage: "play.rectangle.on.rectangle", id: id, submenu: players) {}
    }

    /// An entry of a media item's menu (`MediaMenuEntry`: a poster, a Continue Watching card, a library item, an
    /// episode), identified `<probe>.<action id>` (`board.cw.0.menu.removeFromContinueWatching`,
    /// `detail.menu.item.chooseStream`), red where its action is destructive. `perform` takes its action.
    static func media(_ entry: MediaMenuEntry, probe: String, perform: @escaping (MediaAction) -> Void) -> ItemMenuEntry {
        let action = entry.action
        let symbol: String? = switch action {
        case .playFromStart: "arrow.counterclockwise"
        case .markWatched, .markSeasonWatched: watchedSymbol(false)
        case .markUnwatched, .markSeasonUnwatched: watchedSymbol(true)
        case .removeFromContinueWatching: "minus.circle"
        case .details: "info.circle"
        case .chooseStream: "list.bullet"
        case .download: downloadEntrySymbol("download")
        case .pauseDownload: downloadEntrySymbol("pause")
        case .resumeDownload: downloadEntrySymbol("resume")
        case .retryDownload: downloadEntrySymbol("retry")
        case .cancelDownload: downloadEntrySymbol("cancel")
        case .deleteDownload: downloadEntrySymbol("delete")
        case .trailer: "play.rectangle"
        case .addToLibrary: "plus"
        case .removeFromLibrary: "checkmark"
        case .addToWatchlist, .watchlists: "bookmark"
        case .removeFromWatchlist: "bookmark.fill"
        }
        return ItemMenuEntry(
            label: entry.label,
            systemImage: symbol,
            id: "\(probe).\(action.id)",
            destructive: action.destructive
        ) { perform(action) }
    }
}

/// The watched toggle's symbol wherever "Mark as watched" shows (Detail's action row, the item menus): an eye, filled
/// once watched.
func watchedSymbol(_ watched: Bool) -> String {
    watched ? "eye.fill" : "eye"
}

extension View {
    /// An item's menu (mark as watched, remove, …), as the Compose `ItemMenu` is, opened the way each platform asks
    /// for one: a long press on touch, a right click on the Mac and on an iPad with a pointer, a long press of Select
    /// on the Siri Remote. It is the system's context menu everywhere: over the lifted item on touch (a card's artwork
    /// alone: `PosterCard`) and at the pointer on the Mac, without a heading: a `Section` heading drops the entries'
    /// accessibility identifiers. The remote's Play/Pause never opens it: it plays a Continue Watching card or an
    /// episode (their own `onPlayPauseCommand`) and does nothing elsewhere. The context menu shows each entry's symbol
    /// where it has one. Picking an entry closes the menu. Entries are identified `<probe>.0`, `<probe>.1`, … as the
    /// Compose probes are (`library.menu.item.0`), unless they carry their own. A card or an episode also gives the
    /// entries to its button as VoiceOver's actions (`accessibilityActions(_:)`): a menu on a long press is found only
    /// by those who know to hold.
    func itemMenu(entries: [ItemMenuEntry], probe: String) -> some View {
        contextMenu { ItemMenuEntries(entries: entries, probe: probe) }
    }

    /// `entries` as VoiceOver's actions on this element (a card's or an episode's menu, an ordered row's Move up, Move
    /// down, Remove), on every platform. Entries that act themselves: a submenu's entries aren't reached from here.
    func accessibilityActions(_ entries: [ItemMenuEntry]) -> some View {
        accessibilityActions {
            ForEach(Array(entries.enumerated()), id: \.offset) { _, entry in
                Button(entry.label, action: entry.action)
            }
        }
    }
}

/// A ⋯ button that opens an item's menu (iOS, macOS): for items whose menu should be found without a long press or a
/// right click. `label` names it for VoiceOver (the store's "More actions"), on the Mac as its tooltip too.
struct ItemMenuButton: View {
    let label: String
    let entries: [ItemMenuEntry]
    let probe: String

    var body: some View {
        Menu {
            ItemMenuEntries(entries: entries, probe: probe)
        } label: {
            Image(systemName: "ellipsis")
                .font(.body.weight(.semibold))
                .foregroundStyle(Palette.onSurfaceVariant)
                // At least 44 pt square, larger where the symbol grows past it with the text size.
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
                .focusRing(radius: 22)
        }
        .menuStyle(.button)
        .menuIndicator(.hidden)
        .buttonStyle(PressDimButtonStyle())
        #if os(macOS)
        .tooltip(label)
        #endif
        .accessibilityLabel(label)
    }
}

/// An `ItemMenuButton` named `label` ("More actions for <title>") built again only when `key`, what its entries
/// say and do, or `label` changes (its parent compares it: `.equatable()`). A list redraws a row on every progress
/// report of its download (the Downloads rows, Detail's episodes), and a menu built again while open dropped the tap on
/// one of its entries, the menu staying open and the action never running.
struct SteadyMenuButton: View {
    let key: [AnyHashable]
    let label: String
    let entries: [ItemMenuEntry]
    let probe: String

    var body: some View {
        ItemMenuButton(label: label, entries: entries, probe: probe)
    }
}

/// SwiftUI compares views on the main actor, where the entries' store lives.
extension SteadyMenuButton: @preconcurrency Equatable {
    static func == (lhs: SteadyMenuButton, rhs: SteadyMenuButton) -> Bool {
        lhs.key == rhs.key && lhs.label == rhs.label && lhs.probe == rhs.probe
    }
}

/// An item menu's entries, each a button or, with a submenu, a menu of its own.
struct ItemMenuEntries: View {
    let entries: [ItemMenuEntry]
    let probe: String

    var body: some View {
        ForEach(Array(entries.enumerated()), id: \.offset) { index, entry in
            let id = entry.id ?? "\(probe).\(index)"
            if entry.submenu.isEmpty {
                Button(role: entry.destructive ? .destructive : nil, action: entry.action) {
                    EntryLabel(entry: entry)
                }
                .accessibilityIdentifier(id)
                .looksForAnOpenMenu()
            } else {
                Menu {
                    ItemMenuEntries(entries: entry.submenu, probe: id)
                } label: {
                    EntryLabel(entry: entry)
                }
                .accessibilityIdentifier(id)
                .looksForAnOpenMenu()
            }
        }
    }
}

#if os(iOS)
/// Whether a menu is open on touch, for the app's glass buttons, which stop taking touches while one is (`GlassShape`'s
/// turns non-interactive, `glassButtonStyle`'s are disabled; glass menus excepted): a tap on an item menu's entry (a
/// context menu's, an `ItemMenuButton`'s) drawn over interactive glass went to the glass under the menu, and the entry
/// did nothing (23 Oct, iPhone, iOS 27: Continue Watching's "Play from the beginning" over Home's shortcut chips, which
/// its menu covers since the card lifts its artwork alone). SwiftUI tells nothing of a menu opening or closing (its
/// entries' views appear as a menu opens, but stay after a ⋯ menu or a context menu closes without a pick: 23 Oct,
/// iOS 27), so this looks for UIKit's menu in the app's windows (`showsMenu`), any menu's, since nothing tells an item
/// menu's from another's: as an item menu's entry appears (`ItemMenuEntries`) and, from then on, at each touch in a
/// window (`TouchWatch`), every 100 ms over the next second, which a menu's opening and closing take, and while one is
/// open until it closes.
@MainActor
@Observable
final class OpenItemMenu {
    static let shared = OpenItemMenu()

    private(set) var isOpen = false
    @ObservationIgnored private var looking: Task<Void, Never>?

    fileprivate func look() {
        looking?.cancel()
        looking = Task {
            var waited = Duration.zero
            while !Task.isCancelled {
                update()
                if waited >= .seconds(1) && !isOpen { return }
                try? await Task.sleep(for: .milliseconds(100))
                waited += .milliseconds(100)
            }
        }
    }

    private func update() {
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        for window in windows where !(window.gestureRecognizers ?? []).contains(where: { $0 is TouchWatch }) {
            window.addGestureRecognizer(TouchWatch())
        }
        let open = windows.contains { window in
            window.subviews.contains { $0 !== window.rootViewController?.view && Self.showsMenu($0, depth: 3) }
        }
        if open != isOpen { isOpen = open }
    }

    /// Whether `view`, or a view up to `depth` levels into it, is UIKit's menu on screen: a context menu's or a menu
    /// button's container, which its class names (`_UIContextMenuContainerView`). The app's own views (its window's
    /// root view) are left out.
    private static func showsMenu(_ view: UIView, depth: Int) -> Bool {
        if view.isHidden || view.alpha < 0.01 { return false }
        if NSStringFromClass(type(of: view)).contains("ContextMenu") { return true }
        return depth > 0 && view.subviews.contains { showsMenu($0, depth: depth - 1) }
    }
}

/// Sees each touch in its window, to have `OpenItemMenu` look for a menu as one may open or close, without taking part:
/// it never recognizes, and nothing waits for it to fail.
private final class TouchWatch: UIGestureRecognizer, UIGestureRecognizerDelegate {
    init() {
        super.init(target: nil, action: nil)
        cancelsTouchesInView = false
        delaysTouchesEnded = false
        delegate = self
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        OpenItemMenu.shared.look()
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        ended(event)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        ended(event)
    }

    private func ended(_ event: UIEvent) {
        OpenItemMenu.shared.look()
        if event.allTouches?.allSatisfy({ $0.phase == .ended || $0.phase == .cancelled }) ?? true { state = .failed }
    }

    override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    override func shouldRequireFailure(of otherGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    override func shouldBeRequiredToFail(by otherGestureRecognizer: UIGestureRecognizer) -> Bool { false }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
        true
    }
}
#endif

private extension View {
    /// An entry of an item menu: on iOS, as it appears, `OpenItemMenu` looks for the menu.
    func looksForAnOpenMenu() -> some View {
        #if os(iOS)
        onAppear { OpenItemMenu.shared.look() }
        #else
        self
        #endif
    }
}

/// An entry's words, with its symbol where it has one.
private struct EntryLabel: View {
    let entry: ItemMenuEntry

    var body: some View {
        if let systemImage = entry.systemImage {
            Label(entry.label, systemImage: systemImage)
        } else {
            Text(entry.label)
        }
    }
}
