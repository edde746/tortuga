import SwiftUI
import TortugaKit

extension InfoSectionId {
    /// The section's accessibility identifier: `player.info.video`.
    var identifier: String { "player.info.\((self as __InfoSectionId).probeName)" }
}

/// A row of the playback info (`player.info.row`), its label and value read as one.
private let infoRowIdentifier = "player.info.row"

#if os(iOS)
/// The playback info on iOS: a sheet (half height on iPhone, closed by its grabber; Close on iPad) with the store's
/// sections as a native list, each under its heading (`player.info.<section>`), the values in tabular digits so the live
/// ones (bitrates, frame counts, the cache) change without jittering.
struct PlayerInfoSheet: View {
    let sections: [InfoSection]
    let close: () -> Void

    var body: some View {
        NavigationStack {
            List {
                ForEach(sections, id: \.id) { section in
                    Section {
                        ForEach(Array(section.rows.enumerated()), id: \.offset) { _, row in
                            LabeledContent(row.label) {
                                Text(row.value).monospacedDigit()
                            }
                            .accessibilityElement(children: .combine)
                            .accessibilityIdentifier(infoRowIdentifier)
                        }
                    } header: {
                        Text(section.title).accessibilityIdentifier(section.id.identifier)
                    }
                }
            }
            .accessibilityIdentifier("player.info.panel")
            .navigationTitle(T.shared.player.info.title)
            .navigationBarTitleDisplayMode(.inline)
            .sheetToolbar(close: SheetAction(title: T.shared.nav.close, id: "player.info.close", action: close))
        }
        .sheetFrame(.player)
        .playerSheetBackground()
    }
}

extension View {
    /// A player sheet's background (the playback info, Watch together): from iOS 26 the system's Liquid Glass sheet, as
    /// every other sheet has; before, the regular material, the video showing through it.
    @ViewBuilder
    func playerSheetBackground() -> some View {
        if #available(iOS 26, *) {
            self
        } else {
            presentationBackground(.regularMaterial)
        }
    }
}
#endif

#if os(macOS)
/// The playback info on the Mac: a glass panel over the video's top right, as QuickTime Player's inspector floats, with
/// the store's sections in two columns where the window has the room for them, else in one; of a fixed width, so live
/// values move nothing, and scrolling where it is taller than the room it is given.
struct PlayerInfoPanel: View {
    let sections: [InfoSection]

    var body: some View {
        ViewThatFits(in: .horizontal) {
            panel(columns: 2)
            panel(columns: 1)
        }
    }

    private func panel(columns: Int) -> some View {
        ViewThatFits(in: .vertical) {
            PlayerInfoColumns(sections: sections, columns: columns).padding(PlayerMetrics.infoPadding)
            ScrollView {
                PlayerInfoColumns(sections: sections, columns: columns).padding(PlayerMetrics.infoPadding)
            }
        }
        // A scroll view would take all the width it is offered.
        .frame(width: PlayerMetrics.infoPanelWidth(columns: columns))
        .playerGlass(in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("player.info.panel")
    }
}
#endif

#if !os(iOS)
/// The playback info's sections in `columns` columns of a fixed width, side by side (`InfoColumns` deals the sections
/// out in order, so the columns end about level and a live value moves no section to another column). A column
/// `InfoColumns` leaves empty keeps its room, so the whole keeps its width.
struct PlayerInfoColumns: View {
    let sections: [InfoSection]
    let columns: Int

    var body: some View {
        let split = InfoColumns.shared.split(sections: sections, columns: Int32(columns))
        HStack(alignment: .top, spacing: PlayerMetrics.infoColumnSpacing) {
            ForEach(0..<columns, id: \.self) { index in
                PlayerInfoColumn(sections: index < split.count ? split[index] : [])
                    .frame(width: PlayerMetrics.infoColumnWidth, alignment: .topLeading)
            }
        }
    }
}

/// One column's sections, one under another, their labels in a column as wide as the column's longest label
/// (`PlayerMetrics.infoLabelWidth` at most, past which a label wraps): the values line up down the column, and a column
/// without the Video section's long labels ("MaxCLL / MaxFALL") leaves its values more of its width.
private struct PlayerInfoColumn: View {
    let sections: [InfoSection]
    @State private var labelWidth = PlayerMetrics.infoLabelWidth

    var body: some View {
        VStack(alignment: .leading, spacing: PlayerMetrics.infoSectionSpacing) {
            ForEach(sections, id: \.id) { section in
                PlayerInfoSection(section: section, labelWidth: labelWidth)
            }
        }
        // The longest label at its own width, out of sight.
        .background(alignment: .topLeading) {
            VStack(alignment: .leading) {
                ForEach(Array(sections.flatMap { $0.rows }.enumerated()), id: \.offset) { _, row in
                    Text(row.label).font(PlayerMetrics.infoLabelFont)
                }
            }
            .fixedSize()
            .hidden()
            .accessibilityHidden(true)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { labelWidth = min($0.rounded(.up), PlayerMetrics.infoLabelWidth) }
        }
    }
}

/// One section of the playback info (`player.info.<section>`): its heading, then its rows, each label in grey in a
/// column `labelWidth` wide and the value beside it in tabular digits, so a live value changes without moving anything;
/// a long value (a file's name) wraps rather than lose its end.
private struct PlayerInfoSection: View {
    let section: InfoSection
    let labelWidth: CGFloat

    #if os(tvOS)
    @ScaledMetric(relativeTo: .body) private var valueSize: CGFloat = PlayerMetrics.infoValueSize
    #endif

    var body: some View {
        VStack(alignment: .leading, spacing: PlayerMetrics.infoRowSpacing) {
            Text(section.title)
                .font(PlayerMetrics.infoHeadingFont)
                .accessibilityAddTraits(.isHeader)
                .padding(.bottom, PlayerMetrics.infoHeadingGap)
            ForEach(Array(section.rows.enumerated()), id: \.offset) { _, row in
                HStack(alignment: .firstTextBaseline, spacing: PlayerMetrics.infoLabelGap) {
                    Text(row.label)
                        .font(PlayerMetrics.infoLabelFont)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(width: labelWidth, alignment: .leading)
                    Text(row.value)
                        #if os(tvOS)
                        .font(.system(size: valueSize, weight: .medium))
                        #else
                        .font(PlayerMetrics.infoValueFont)
                        #endif
                        .monospacedDigit()
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier(infoRowIdentifier)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(section.id.identifier)
    }
}
#endif
