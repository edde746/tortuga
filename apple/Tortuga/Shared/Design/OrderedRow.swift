import SwiftUI
import TortugaKit
import UniformTypeIdentifiers

#if !os(tvOS)
/// A row of a list the user orders (Home's rows, the installed addons, preferred languages) on the Mac, iPhone and
/// iPad, as Compose's ordered rows are: a drag handle (≡) at its leading edge, then `content`, then `control` (a control
/// of the row's own: an addon's switch), then ⋯. Nothing to switch on first. With `controlsBelow` (a row too narrow for
/// its text beside its controls) `content` takes the row's whole width and the controls are a line of their own under
/// it: the handle at its start, `control` and ⋯ at its end. `entries` are the row's actions: Move up and Move down where
/// the row can go, then the list's own (Configure, Remove, marked `destructive`), all in the row's one menu, which ⋯
/// opens. VoiceOver offers them as the handle's actions; `content` gives them to its own element too
/// (`accessibilityActions(_:)`). `title` names the row (the dragged image shows it): `handleLabel` is the handle's
/// spoken label, the list's "Reorder <title>", and `moreLabel` is ⋯'s, "More actions for <title>".
///
/// The Mac: a right click on the row opens the same menu. The handle shows on every row of a list longer than one, and
/// a drag starts from it. Dropped on another row, the row lands above or below it by the half under the pointer, a
/// line marking the place meanwhile; the list stores the move once, on the drop (`onMove`). `ids` is the list's ids in
/// order, `id` this row's; `dragged` (the list's) holds the id of the row being dragged; the dragged image shows
/// `title`. The drag carries the row as an item of Tortuga's own type (`UTType.orderedRow`), not as text: a text field
/// takes nothing from it, and text dragged in from elsewhere moves no row. The handle's tooltip is `handleLabel`.
///
/// iPhone and iPad: the list's own drag moves the row: a touch held on it (the handle, or anywhere on the row) lifts
/// it, the rows make room as it goes, and the list's `onMove` stores the move on the drop. The list stays out of Edit.
/// A long press opens no menu: the menu's own hold would take over the drag.
///
/// Accessibility identifiers: `<probe>.handle`, `<probe>.more` (⋯); the entries carry their own.
struct OrderedRow<Content: View, Control: View>: View {
    let probe: String
    let title: String
    let handleLabel: String
    let moreLabel: String
    let entries: [ItemMenuEntry]
    let controlsBelow: Bool
    let content: Content
    let control: Control
    #if os(macOS)
    let id: String
    let index: Int
    let ids: [String]
    @Binding var dragged: String?
    let onMove: (_ id: String, _ index: Int) -> Void

    @State private var height: CGFloat = 0
    /// Where the row dragged over this one lands: below it (true) or above it; nil while none would move.
    @State private var landsBelow: Bool?

    init(
        id: String,
        index: Int,
        ids: [String],
        title: String,
        probe: String,
        handleLabel: String,
        moreLabel: String,
        entries: [ItemMenuEntry],
        dragged: Binding<String?>,
        controlsBelow: Bool,
        onMove: @escaping (_ id: String, _ index: Int) -> Void,
        @ViewBuilder content: () -> Content,
        @ViewBuilder control: () -> Control
    ) {
        self.id = id
        self.index = index
        self.ids = ids
        self.title = title
        self.probe = probe
        self.handleLabel = handleLabel
        self.moreLabel = moreLabel
        self.entries = entries
        _dragged = dragged
        self.controlsBelow = controlsBelow
        self.onMove = onMove
        self.content = content()
        self.control = control()
    }
    #else
    init(
        title: String,
        probe: String,
        handleLabel: String,
        moreLabel: String,
        entries: [ItemMenuEntry],
        controlsBelow: Bool,
        @ViewBuilder content: () -> Content,
        @ViewBuilder control: () -> Control
    ) {
        self.title = title
        self.probe = probe
        self.handleLabel = handleLabel
        self.moreLabel = moreLabel
        self.entries = entries
        self.controlsBelow = controlsBelow
        self.content = content()
        self.control = control()
    }
    #endif

    var body: some View {
        Group {
            if controlsBelow {
                VStack(alignment: .leading, spacing: 8) {
                    content
                    HStack(spacing: 8) {
                        shownHandle
                        Spacer(minLength: 0)
                        control
                        moreButton
                    }
                }
            } else {
                HStack(spacing: 8) {
                    shownHandle
                    content
                    control
                    moreButton
                }
            }
        }
        #if os(macOS)
        .itemMenu(entries: entries, probe: "\(probe).menu")
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
        .onDrop(
            of: [.orderedRow],
            delegate: OrderedRowDrop(target: index, ids: ids, height: height, dragged: $dragged, landsBelow: $landsBelow, onMove: onMove)
        )
        .overlay(alignment: landsBelow == true ? .bottom : .top) {
            if let landsBelow {
                // On the line between the two rows: past this row's edge by half the list's gap between rows.
                Capsule()
                    .fill(Palette.primary)
                    .frame(height: 3)
                    .offset(y: landsBelow ? Self.dropLineOffset : -Self.dropLineOffset)
                    .allowsHitTesting(false)
            }
        }
        #endif
    }

    /// The handle where the list shows one: on the Mac only in a list longer than one.
    @ViewBuilder
    private var shownHandle: some View {
        #if os(macOS)
        if ids.count > 1 { handle }
        #else
        handle
        #endif
    }

    /// ⋯, opening the row's menu, where the row has entries.
    @ViewBuilder
    private var moreButton: some View {
        if !entries.isEmpty {
            ItemMenuButton(label: moreLabel, entries: entries, probe: "\(probe).menu")
                // Its 44 pt square reaches into the row's margins: the row stays as tall as its content.
                .padding(.vertical, -8)
                .accessibilityIdentifier("\(probe).more")
        }
    }

    /// The handle (≡), spoken as `handleLabel` with `entries` as its actions. On the Mac a drag starts from it: the
    /// row's id on the drag, the handle and the title as the dragged image.
    @ViewBuilder
    private var handle: some View {
        let image = Image(systemName: "line.3.horizontal")
            .foregroundStyle(Palette.onSurfaceVariant)
            .frame(width: 24, height: 28)
            .contentShape(Rectangle())
            #if os(macOS)
            .onDrag {
                dragged = id
                let provider = NSItemProvider()
                provider.registerDataRepresentation(for: .orderedRow, visibility: .ownProcess) { load in
                    load(Data(id.utf8), nil)
                    return nil
                }
                return provider
            } preview: {
                HStack(spacing: 8) {
                    Image(systemName: "line.3.horizontal").foregroundStyle(Palette.onSurfaceVariant)
                    Text(title).lineLimit(1)
                }
                // Offered the handle's width, the title would have no room.
                .fixedSize()
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Palette.surfaceContainerHighest, in: RoundedRectangle(cornerRadius: Radius.small))
            }
            .tooltip(handleLabel)
            #endif
            .accessibilityLabel(handleLabel)
            .accessibilityActions(entries)
            .accessibilityIdentifier("\(probe).handle")
        #if os(macOS)
        if #available(macOS 15, *) {
            image.pointerStyle(.grabIdle)
        } else {
            image
        }
        #else
        image
        #endif
    }

    #if os(macOS)
    /// How far past this row's edge the drop line sits: half the list's gap between rows.
    private static var dropLineOffset: CGFloat { 6 }
    #endif
}

extension OrderedRow where Control == EmptyView {
    #if os(macOS)
    /// A row without a control of its own, its controls beside its content.
    init(
        id: String,
        index: Int,
        ids: [String],
        title: String,
        probe: String,
        handleLabel: String,
        moreLabel: String,
        entries: [ItemMenuEntry],
        dragged: Binding<String?>,
        onMove: @escaping (_ id: String, _ index: Int) -> Void,
        @ViewBuilder content: () -> Content
    ) {
        self.init(
            id: id, index: index, ids: ids, title: title, probe: probe, handleLabel: handleLabel, moreLabel: moreLabel,
            entries: entries, dragged: dragged, controlsBelow: false, onMove: onMove, content: content
        ) { EmptyView() }
    }
    #else
    /// A row without a control of its own, its controls beside its content.
    init(title: String, probe: String, handleLabel: String, moreLabel: String, entries: [ItemMenuEntry], @ViewBuilder content: () -> Content) {
        self.init(title: title, probe: probe, handleLabel: handleLabel, moreLabel: moreLabel, entries: entries, controlsBelow: false, content: content) {
            EmptyView()
        }
    }
    #endif
}

#if os(macOS)
/// A drop on the row at `target` of the list `ids`, `height` tall: the row being dragged (`dragged`) lands above it or
/// below it, by the half the pointer is over (`landsBelow` meanwhile, nil where the dragged row would stay put). Only a
/// row's drag reaches it (`UTType.orderedRow`); one from another list is refused.
private struct OrderedRowDrop: DropDelegate {
    let target: Int
    let ids: [String]
    let height: CGFloat
    @Binding var dragged: String?
    @Binding var landsBelow: Bool?
    let onMove: (String, Int) -> Void

    func validateDrop(info: DropInfo) -> Bool {
        source != nil
    }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        landsBelow = destination(info) == nil ? nil : below(info)
        return DropProposal(operation: .move)
    }

    func dropExited(info: DropInfo) {
        landsBelow = nil
    }

    func performDrop(info: DropInfo) -> Bool {
        landsBelow = nil
        defer { dragged = nil }
        guard let source, let to = destination(info) else { return false }
        onMove(ids[source], to)
        return true
    }

    /// The dragged row's index in `ids`; nil when no row of this list is being dragged.
    private var source: Int? {
        dragged.flatMap { ids.firstIndex(of: $0) }
    }

    private func below(_ info: DropInfo) -> Bool {
        info.location.y > height / 2
    }

    /// The index the dragged row ends at, dropped here; nil where it would stay put.
    private func destination(_ info: DropInfo) -> Int? {
        guard let from = source else { return nil }
        // The place between rows, counted in the list with the dragged row still in it.
        let place = target + (below(info) ? 1 : 0)
        let to = place > from ? place - 1 : place
        return to == from ? nil : to
    }
}

extension UTType {
    /// A row of an `OrderedRow` list on the Mac, dragged by its handle: its id, for this process alone. Declared in the
    /// Info.plist's Mac keys (`UTExportedTypeDeclarations`).
    static let orderedRow = UTType(exportedAs: "com.edde746.tortuga.ordered-row")
}
#endif
#endif
