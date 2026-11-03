import SwiftUI
import TortugaKit

/// Settings' Addons section over its store (`SettingsSession.addons`), which the session hands the install links
/// followed into the app (`stremio://…/manifest.json`, `AppSession.openAddons`). The Settings session keeps it, so an
/// open install or details page outlasts a trip to the browser (Configure) and to the other tabs.
struct AddonsSettingsRoute: View {
    let model: StoreModel<AddonsStore, AddonsUiState>

    var body: some View {
        AddonsScreen(state: model.observer.state, store: model.store)
            .task { await model.observer.observe() }
    }
}

/// Settings' Addons section: the profile's addons in the user's order, filterable by type, each with its
/// logo, name, a line of its version, types and what it provides, its note (watch activity not allowed yet) and its
/// switch, which turns it off (it keeps
/// its place, dimmed, and is asked for nothing; the built-in addon's stays on); opening one shows its details. Every row
/// moves up or down, opens its configuration
/// (the browser; on Apple TV a QR code to open on a phone) and is removed: iOS and the Mac, an `OrderedRow` dragged by
/// its handle (on iOS a touch held on it), its actions in the menu of its ⋯ (on the Mac a right click too), the Mac's
/// rows in a grouped form as Settings → Home's; on both the type filter and Install are the section's header row
/// (`screenHeader`, in the list's column, staying at the top while the list scrolls; Install in the navigation bar on
/// iOS, at the row's trailing end on the Mac); tvOS, its item menu (a long press of Select), Install and the type
/// filter above the rows. Removing asks first. Installing takes an addon link
/// (`https://…/manifest.json` or `stremio://…`), shows the fetched manifest and installs on confirmation, or first
/// opens its configuration (Configure, for an addon with settings); an addon that asks for the user's watch activity
/// installs only once they tick that they agree; a `stremio://…/manifest.json` link followed into
/// the app opens that addon's install here (on Apple TV over the screen it is on instead: `installLinkCover`). An
/// addon's details show its background art behind their header, its switch and, for an addon that asks for it,
/// whether it receives the user's watch activity, and hold its link, shown only on request. There is no
/// Browse: the App Store build has none (`AddonsUiState.browse`). The page's title is the section's
/// (`SettingsSectionPage`).
///
/// Accessibility identifiers: `addons.filter` (with `.option.<i>`), `addons.install`, `addons.row.<i>` (opens the
/// details) with `.enabled` (its switch; on tvOS a toggle button, selected while on), `.up`, `.down`, `.configure`,
/// `.remove` (its actions), `.handle` and `.more` (⋯; iOS and macOS),
/// `addons.install.<link|submit|watchActivity|confirm|configure|cancel|done>`,
/// `addons.details.<enabled|watchActivity|link|reveal|hide|copy|configure|remove|close>`,
/// `addons.remove.<confirm|cancel>`.
struct AddonsScreen: View {
    let state: AddonsUiState
    let store: AddonsStore

    /// "Link copied." shows under the link of this addon until its details close.
    @State private var copiedFor: String?

    #if os(macOS)
    /// The id of the addon being dragged by its handle.
    @State private var dragged: String?
    #endif
    #if !os(tvOS)
    /// Whether the rows are too narrow for their text beside their controls (`stackedBelow`), which then go under it.
    @State private var stacked = false
    #endif
    #if os(iOS)
    /// The list's side safe areas (a phone's sensor housing in landscape, the iPhone Duo's vertical bar), which its
    /// cards keep clear of.
    @State private var listInsets = EdgeInsets()
    #endif

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            #if os(tvOS)
            HStack(spacing: 24) {
                ActionButton(title: state.installLabel, id: "addons.install", systemImage: "plus", fullWidth: false, action: store.startInstall)
                typeFilter
            }
            // Install's and the filter's glass, one row of it.
            .glassGroup(spacing: 24)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 16)
            .focusSectionIfAvailable()
            #endif
            if let notice = state.notice { noticeCard(notice) }
            content
        }
        #if os(tvOS)
        .readableColumn()
        #endif
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        #if !os(tvOS)
        .screenHeader(column: true) {
            typeFilter
        } actions: {
            Button(action: store.startInstall) {
                Label(state.installLabel, systemImage: "plus")
            }
            .accessibilityIdentifier("addons.install")
        }
        #endif
        // Black under the list, which draws no background of its own, as under the settings' forms (`settingsForm`).
        .background(Palette.background)
        // An install link opened while an addon's details show comes up over them (`AddonDetailsSheet`): one view
        // presents one sheet at a time.
        .settingsSheet(item: state.details == nil ? openInstall : nil, onDismiss: store.closeInstall) { install in
            InstallSheet(install: install, store: store)
        }
        .settingsSheet(item: state.details, size: .fullHeightForm, onDismiss: closeDetails) { details in
            AddonDetailsSheet(
                details: details,
                install: openInstall,
                removeDialog: state.removeDialog,
                linkCopied: copiedFor == details.id,
                store: store,
                onCopy: {
                    copiedFor = details.id
                    store.doCopyLink()
                    // The Copy button's new label isn't spoken by itself.
                    announce(T.shared.addons.details.linkCopied)
                },
                onClose: closeDetails
            )
        }
        .removeConfirmation(state.details == nil ? state.removeDialog : nil, store: store)
    }

    /// The install page's state while it is open.
    private var openInstall: AddonInstallUiState? {
        if case .closed = onEnum(of: state.install.step) { nil } else { state.install }
    }

    private func closeDetails() {
        copiedFor = nil
        store.closeDetails()
    }

    /// The type filter (`AddonsUiState.typeChip`), when the installed addons serve more than one type.
    @ViewBuilder
    private var typeFilter: some View {
        if let chip = state.typeChip {
            FilterMenu(
                chip: chip,
                options: state.typeFilters.map { FilterOption(label: $0.label, selected: $0.selected) },
                id: "addons.filter"
            ) { store.filterType(filterId: state.typeFilters[$0].id) }
        }
    }

    /// A notice (why an install or a removal failed), until dismissed, in the list's column.
    private func noticeCard(_ notice: String) -> some View {
        ScreenSidesReader { sides in
            NoticeCard(text: notice, onDismiss: store.dismissNotice)
                .padding(sides.insets)
                .padding(.vertical, 8)
        }
        .readableColumn()
    }

    @ViewBuilder
    private var content: some View {
        switch onEnum(of: state.body) {
        case .loading:
            LoadingStateView()
        case .empty(let empty):
            EmptyStateView(message: empty.text, systemImage: "puzzlepiece.extension")
        case .rows(let rows):
            #if os(tvOS)
            TVAddonList(addons: rows.addons, store: store, menuEntries: menuEntries)
            #elseif os(macOS)
            // A form, as Settings → Home is: a List draws the system's blue ring around a right-clicked row (inset or
            // plain, with or without selection; probed 12 Oct), a grouped form draws none.
            Form { addonRows(rows.addons) }
                .settingsForm()
            #else
            ScreenSidesReader { sides in
                List {
                    addonRows(rows.addons)
                }
                .listStyle(.insetGrouped)
                .listSectionSpacing(.compact)
                // The header row's gap above the rows is the list's top.
                .contentMargins(.top, 0, for: .scrollContent)
                // The cards at the header row's inset, lined up with its chip (`screenHeader`: the page's side inside
                // the safe area, `screenSides`), not at the system's section margin (20 pt on an iPhone). Margins set
                // here replace the system's, safe area included (the list runs under it), so each side's safe area is
                // added back.
                .contentMargins(.leading, listInsets.leading + sides.leading, for: .scrollContent)
                .contentMargins(.trailing, listInsets.trailing + sides.trailing, for: .scrollContent)
                .onGeometryChange(for: EdgeInsets.self) { $0.safeAreaInsets } action: { listInsets = $0 }
                .scrollContentBackground(.hidden)
            }
            .readableColumn()
            #endif
        }
    }

    #if !os(tvOS)
    /// A row narrower than this (a 480 pt card less its 16 pt insets) puts its handle, switch and ⋯ on a line under its
    /// text: beside them its text would get a few characters a line (an iPhone's row).
    private static let stackedBelow: CGFloat = 448

    /// Measures a row's width for `stacked`: every row is as wide as the list, so each sets the same value.
    private func measured(_ row: some View) -> some View {
        row.onGeometryChange(for: Bool.self) { $0.size.width < Self.stackedBelow } action: { stacked = $0 }
    }
    #endif

    #if os(macOS)
    /// The installed addons' rows, each an `OrderedRow`: dragged by its handle, moved, configured and removed from its
    /// ⋯ or a right click.
    private func addonRows(_ addons: [AddonRow]) -> some View {
        let ids = addons.map(\.id)
        return ForEach(Array(addons.enumerated()), id: \.element.id) { index, row in
            let id = "addons.row.\(index)"
            let entries = menuEntries(row, id: id)
            measured(
                OrderedRow(
                    id: row.id,
                    index: index,
                    ids: ids,
                    title: row.name,
                    probe: id,
                    handleLabel: row.dragLabel,
                    moreLabel: row.moreLabel,
                    entries: entries,
                    dragged: $dragged,
                    controlsBelow: stacked
                ) { rowId, to in
                    store.moveTo(id: rowId, index: Int32(to))
                } content: {
                    AddonListRow(row: row, index: index, stacked: stacked, store: store)
                        .accessibilityActions(entries)
                } control: {
                    AddonSwitch(row: row, index: index, store: store)
                }
            )
        }
    }
    #elseif os(iOS)
    /// The installed addons' rows, each an `OrderedRow`: dragged by a touch held on it (the list's `onMove`), moved,
    /// configured and removed (asking first) from its ⋯.
    private func addonRows(_ addons: [AddonRow]) -> some View {
        ForEach(Array(addons.enumerated()), id: \.element.id) { index, row in
            let id = "addons.row.\(index)"
            let entries = menuEntries(row, id: id)
            measured(
                OrderedRow(title: row.name, probe: id, handleLabel: row.dragLabel, moreLabel: row.moreLabel, entries: entries, controlsBelow: stacked) {
                    AddonListRow(row: row, index: index, stacked: stacked, store: store)
                        .accessibilityActions(entries)
                } control: {
                    AddonSwitch(row: row, index: index, store: store)
                }
            )
        }
        .onMove { from, to in
            guard let source = from.first else { return }
            // `to` counts the list with the row still in place; the store takes the index it ends at.
            store.moveTo(id: addons[source].id, index: Int32(to > source ? to - 1 : to))
        }
    }
    #endif

    /// Move up and Move down where `row` can go that way, Configure where it can be configured, Remove unless built in.
    private func menuEntries(_ row: AddonRow, id: String) -> [ItemMenuEntry] {
        var entries: [ItemMenuEntry] = []
        if row.canMoveUp {
            entries.append(ItemMenuEntry(label: T.shared.addons.moveUp, systemImage: "arrow.up", id: "\(id).up") { store.moveUp(id: row.id) })
        }
        if row.canMoveDown {
            entries.append(ItemMenuEntry(label: T.shared.addons.moveDown, systemImage: "arrow.down", id: "\(id).down") { store.moveDown(id: row.id) })
        }
        if row.configurable {
            entries.append(
                ItemMenuEntry(label: T.shared.addons.details.configure, systemImage: "slider.horizontal.3", id: "\(id).configure") {
                    store.configure(id: row.id)
                }
            )
        }
        if row.removable {
            entries.append(
                ItemMenuEntry(label: T.shared.addons.details.remove, systemImage: "trash", id: "\(id).remove", destructive: true) {
                    store.remove(id: row.id)
                }
            )
        }
        return entries
    }
}

#if !os(tvOS)
/// One installed addon's text in the list (iOS, macOS), which opens its details, dimmed while the addon is off: its
/// logo beside its summary, or, `stacked` (a narrow row), leading the name's line with the facts and description under
/// them in the row's whole width. Its `OrderedRow` holds its switch (`AddonSwitch`) and its actions.
private struct AddonListRow: View {
    let row: AddonRow
    let index: Int
    let stacked: Bool
    let store: AddonsStore

    var body: some View {
        Button { store.openDetails(id: row.id) } label: {
            Group {
                if stacked {
                    AddonSummary(row: row, logoSize: 44)
                } else {
                    HStack(spacing: 12) {
                        AddonLogo(url: row.logo, size: 44)
                        AddonSummary(row: row)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .opacity(row.enabled ? 1 : 0.5)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("addons.row.\(index)")
    }
}

/// An addon's switch in its row (iOS, macOS): none for the built-in addon, which stays on.
private struct AddonSwitch: View {
    let row: AddonRow
    let index: Int
    let store: AddonsStore

    var body: some View {
        if row.canDisable {
            Toggle(row.enabledLabel, isOn: Binding(get: { row.enabled }, set: { store.setEnabled(id: row.id, enabled: $0) }))
                .labelsHidden()
                .toggleStyle(.switch)
                .tint(Palette.switchOn)
                .accessibilityIdentifier("addons.row.\(index).enabled")
        }
    }
}
#endif

#if os(tvOS)
/// The installed addons on tvOS: a column of rows, each its summary, a button that opens the details and turns white
/// with focus (no lift), dimmed while the addon is off, then its switch: a round button beside it, Right from the row,
/// showing a check while the addon is on and a crossed circle while it is off (none for the built-in addon). A system
/// switch would be a row of its own, out of the summary's line. Its item menu (`menuEntries`, iOS's and the Mac's)
/// moves it up or down, opens its configuration (as a QR code to open on a phone) and removes it (built-in addons
/// stay); a row with none of these has no menu. Nothing in a row grows past the column's padding, so the list clips its
/// rows at its edges: scrolled, none draws over Install and the type filter above it.
private struct TVAddonList: View {
    let addons: [AddonRow]
    let store: AddonsStore
    let menuEntries: (AddonRow, String) -> [ItemMenuEntry]

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                ForEach(Array(addons.enumerated()), id: \.element.id) { index, row in
                    let id = "addons.row.\(index)"
                    let entries = menuEntries(row, id)
                    let button = Button { store.openDetails(id: row.id) } label: {
                        HStack(spacing: 20) {
                            AddonLogo(url: row.logo, size: 72)
                            AddonSummary(row: row)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .opacity(row.enabled ? 1 : 0.5)
                        .padding(16)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(SurfaceButtonStyle(radius: Radius.medium))
                    .accessibilityIdentifier(id)
                    HStack(spacing: 24) {
                        if entries.isEmpty {
                            button
                        } else {
                            button.itemMenu(entries: entries, probe: "\(id).menu")
                        }
                        let toggle = IconButton(
                            systemImage: row.enabled ? "checkmark" : "circle.slash",
                            label: row.enabledLabel,
                            id: "\(id).enabled",
                            enabled: row.canDisable,
                            symbolBox: 40
                        ) {
                            store.setEnabled(id: row.id, enabled: !row.enabled)
                        }
                        if row.canDisable {
                            toggle.accessibilityAddTraits(row.enabled ? [.isToggle, .isSelected] : .isToggle)
                        } else {
                            // The built-in addon's row keeps the switch's room: every row ends on one line.
                            toggle.hidden().accessibilityHidden(true)
                        }
                    }
                }
            }
            .padding(.vertical, 16)
            // Room for a focused switch, which grows past the row's end.
            .padding(.trailing, 12)
        }
    }
}
#endif

/// An addon's name (with its "Built in" badge), the line of its version, types and what it provides (which wraps
/// between words, never before a separator), its note (watch activity not allowed yet), and its description. The
/// built-in addon's note is the badge beside its name; any other addon's note is a line of its own, shown whole (the
/// description is cut at two lines). With `logoSize` (a narrow row) its logo leads the name's line.
private struct AddonSummary: View {
    let row: AddonRow
    var logoSize: CGFloat?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let logoSize {
                HStack(spacing: 12) {
                    AddonLogo(url: row.logo, size: logoSize)
                    title
                }
                .padding(.bottom, 6)
            } else {
                title
            }
            Text(row.facts)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if row.removable, let note = row.note {
                Text(note).font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            if let description = row.descriptionText {
                Text(description).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
            }
        }
        .multilineTextAlignment(.leading)
    }

    private var title: some View {
        HStack(spacing: 8) {
            Text(row.name).font(.headline).lineLimit(2)
            if !row.removable, let note = row.note { BadgeView(text: note) }
        }
    }
}

/// An addon's logo, or the addon symbol while there is none or it loads.
private struct AddonLogo: View {
    let url: String?
    let size: CGFloat

    var body: some View {
        RemoteImage(url: url, contentMode: .fit) {
            Image(systemName: "puzzlepiece.extension")
                .font(.system(size: size * 0.4))
                .foregroundStyle(Palette.onSurfaceVariant)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(width: size, height: size)
        .background(Palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
        .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
        .accessibilityHidden(true)
    }
}

/// An addon's logo, name and version at the top of a form, spaced as the system's own headers are.
struct AddonHeader: View {
    let name: String
    let version: String
    let logo: String?

    var body: some View {
        HStack(spacing: 16) {
            AddonLogo(url: logo, size: 64)
            VStack(alignment: .leading, spacing: 4) {
                Text(name).font(.title2.weight(.semibold)).lineLimit(2).accessibilityAddTraits(.isHeader)
                Text(version).font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 8)
    }
}

/// An addon's background art behind its details' header: Detail's backdrop, kept faint, the image darkened so the
/// name and description read over it.
private struct AddonBackdrop: View {
    let url: String

    var body: some View {
        let black = Palette.background
        RemoteImage(url: url)
            .overlay {
                LinearGradient(
                    stops: [.init(color: black.opacity(0.55), location: 0), .init(color: black.opacity(0.85), location: 1)],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
            .background(Palette.surfaceContainer)
            .accessibilityHidden(true)
    }
}

extension View {
    /// "Remove Example Streams?" while `dialog` is set: Remove or Cancel. Confirming closes the details of the removed
    /// addon, if they are open.
    fileprivate func removeConfirmation(_ dialog: AddonRemoveDialog?, store: AddonsStore) -> some View {
        confirmation(
            dialog.map { dialog in
                Confirmation(
                    title: dialog.title,
                    message: dialog.message,
                    confirmLabel: dialog.confirmLabel,
                    cancelLabel: dialog.cancelLabel,
                    confirmId: "addons.remove.confirm",
                    cancelId: "addons.remove.cancel"
                )
            },
            onConfirm: {
                store.confirmRemove()
                store.closeDetails()
            },
            onCancel: store.dismissRemove
        )
    }
}

/// An addon's details in a form: its header (over its background art, where it has one) and description, with the
/// built-in addon's note under them as the section's footer (a sentence, never a badge beside the name), its switches
/// (`switches`), what it serves and provides and its contact, its link behind Show link (a configured addon's link
/// carries its accounts' keys), Configure (its page in the browser; on Apple TV a QR code to open on a phone) and
/// Remove, then its catalogs. Close (the toolbar's cancellation slot on iOS and macOS, where Escape presses it; the
/// actions' last row on tvOS, focused first there) closes it; on tvOS the switches follow the actions, so Close shows
/// as the page opens. An install opened meanwhile (`install`: a configured addon's link back from the browser) shows
/// over it.
private struct AddonDetailsSheet: View {
    let details: AddonDetailsUiState
    let install: AddonInstallUiState?
    let removeDialog: AddonRemoveDialog?
    let linkCopied: Bool
    let store: AddonsStore
    let onCopy: () -> Void
    let onClose: () -> Void

    @FocusState private var closeFocused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        AddonHeader(name: details.name, version: details.version, logo: details.logo)
                        if let description = details.descriptionText {
                            Text(description).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .listRowBackground(details.background.map { AddonBackdrop(url: $0) })
                } footer: {
                    if let note = details.note {
                        Text(note).accessibilityIdentifier("addons.details.note")
                    }
                }
                #if !os(tvOS)
                switches
                #endif
                Section {
                    LabeledContent(details.typesLabel, value: details.types)
                    LabeledContent(details.resourcesLabel, value: details.resources)
                    if let contact = details.contact { LabeledContent(details.contactLabel, value: contact) }
                }
                ShareLinkSection(share: details.share, linkCopied: linkCopied, store: store, onCopy: onCopy)
                Section {
                    if details.configurable {
                        Button { store.configure(id: details.id) } label: {
                            Label(details.configureLabel, systemImage: "slider.horizontal.3")
                        }
                        .accessibilityIdentifier("addons.details.configure")
                    }
                    if details.removable {
                        Button(role: .destructive) { store.remove(id: details.id) } label: {
                            Label(details.removeLabel, systemImage: "trash")
                        }
                        .accessibilityIdentifier("addons.details.remove")
                    }
                    #if os(tvOS)
                    Button(T.shared.addons.close, action: onClose)
                        .focused($closeFocused)
                        .accessibilityIdentifier("addons.details.close")
                    #endif
                }
                #if os(tvOS)
                switches
                #endif
                if !details.catalogs.isEmpty {
                    Section(details.catalogsLabel) {
                        ForEach(Array(details.catalogs.enumerated()), id: \.offset) { _, catalog in
                            Text(catalog)
                        }
                    }
                }
            }
            .settingsForm()
            .pageTitle(details.name)
            .sheetToolbar(close: SheetAction(title: T.shared.addons.close, id: "addons.details.close", action: onClose))
        }
        .removeConfirmation(removeDialog, store: store)
        .settingsSheet(item: install, onDismiss: store.closeInstall) { install in
            InstallSheet(install: install, store: store)
        }
        #if os(tvOS)
        .defaultFocus($closeFocused, true)
        .onAppear { closeFocused = true }
        #endif
    }

    /// The addon's switch (none for the built-in addon, which stays on; while off, why it shows nothing) and, for an
    /// addon that asks for it, whether it receives the user's watch activity, in a section of their own.
    @ViewBuilder
    private var switches: some View {
        if details.canDisable || details.watchActivity != nil {
            Section {
                if details.canDisable {
                    SettingsToggleRow(title: details.enabledLabel, subtitle: details.enabledNote, checked: details.enabled, id: "addons.details.enabled") {
                        store.setEnabled(id: details.id, enabled: $0)
                    }
                }
                if let activity = details.watchActivity {
                    SettingsToggleRow(title: activity.label, subtitle: activity.explanation, checked: activity.checked, id: "addons.details.watchActivity") {
                        store.setWatchActivity(id: details.id, allowed: $0)
                    }
                }
            }
        }
    }
}

/// The addon's link: why it is private, the link itself only after Show link, Hide, and Copy (without showing it;
/// iOS and macOS, which have a pasteboard).
private struct ShareLinkSection: View {
    let share: ShareLinkUiState
    let linkCopied: Bool
    let store: AddonsStore
    let onCopy: () -> Void

    var body: some View {
        Section {
            if let link = share.link {
                Text(link)
                    .font(.system(.callout, design: .monospaced))
                    .fixedSize(horizontal: false, vertical: true)
                    #if !os(tvOS)
                    .textSelection(.enabled)
                    #endif
                    .accessibilityIdentifier("addons.details.link")
            }
            if share.revealed {
                Button(action: store.hideLink) { Label(share.hideLabel, systemImage: "eye.slash") }
                    .accessibilityIdentifier("addons.details.hide")
            } else {
                Button(action: store.revealLink) { Label(share.revealLabel, systemImage: "eye") }
                    .accessibilityIdentifier("addons.details.reveal")
            }
            #if !os(tvOS)
            Button(action: onCopy) {
                Label(linkCopied ? T.shared.addons.details.linkCopied : share.copyLabel, systemImage: linkCopied ? "checkmark" : "doc.on.doc")
            }
            .accessibilityIdentifier("addons.details.copy")
            #endif
        } header: {
            Text(share.title)
        } footer: {
            Text(share.warning)
        }
    }
}
