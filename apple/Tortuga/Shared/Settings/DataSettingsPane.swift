#if !os(tvOS)
import SwiftUI
import TortugaKit
import UniformTypeIdentifiers

/// The export and import section over its store (iPhone, iPad, the Mac; Apple TV has no file dialogs): the pane, the
/// system's save dialog while the store has a file to save (`saveRequest`), the system's open dialog from Import, and
/// the review of an opened file in a sheet. The platform's part is the dialogs and the bytes on disk; neither the file
/// nor where it is goes to the log.
struct DataSettingsRoute: View {
    let model: StoreModel<DataSettingsStore, DataSettingsUiState>

    /// The open dialog is up.
    @State private var importing = false
    /// The save dialog is up: set while the store has a file to save, cleared by the dialog when it closes.
    @State private var exporting = false

    var body: some View {
        let store = model.store
        let state = model.observer.state
        DataSettingsPane(state: state, store: store, onImport: { importing = true })
            .task { await model.observer.observe() }
            .onChange(of: state.saveRequest != nil, initial: true) { _, requested in exporting = requested }
            .fileExporter(
                isPresented: $exporting,
                item: state.saveRequest.map { ExportFile(text: $0.text) },
                contentTypes: [.json],
                defaultFilename: state.saveRequest?.fileName,
                onCompletion: { result in
                    switch result {
                    case .success: store.exportSaved()
                    case .failure: store.exportFailed()
                    }
                },
                onCancellation: store.exportCancelled
            )
            // Any file, so one saved under another type is still offered; the store tells what it holds.
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .data]) { result in
                switch result {
                case .success(let url):
                    Task { if let text = await importedText(url) { store.open(text: text) } else { store.openFailed() } }
                case .failure: store.openFailed()
                }
            }
            .settingsSheet(item: state.review, size: .fullHeightForm, onDismiss: store.cancelImport) { review in
                DataImportSheet(review: review, store: store)
            }
    }
}

/// The largest file Import reads (Data's, Home's collections'); a bigger one is refused as unreadable before it fills
/// memory, as a video picked by mistake and read whole would.
private let maxImportBytes = 32 << 20

/// The text of a file picked in the open dialog to import, read off the main thread; nil when it can't be read, is
/// over `maxImportBytes` (read no further than a byte past them) or isn't UTF-8.
func importedText(_ url: URL) async -> String? {
    await Task.detached(priority: .userInitiated) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let file = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? file.close() }
        guard let data = try? file.read(upToCount: maxImportBytes + 1) ?? Data(), data.count <= maxImportBytes else { return nil }
        return String(data: data, encoding: .utf8)
    }.value
}

/// An export as the save dialog writes it: the store's text as UTF-8 (Data's export, Home's collections).
struct ExportFile: Transferable {
    let text: String

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .json) { Data($0.text.utf8) }
    }
}

/// Export and import, as a native form: the categories to export, then the profiles (with two or more),
/// then the warning that the file holds API keys (while addons or stream history are ticked) above Export; the outcome
/// of the last export or import (an error in the error colour) until dismissed, between Export and Import so that it
/// shows beside the action just taken (a notice at the top of this page, two screens tall on iPhone, would show off
/// the screen); Import, which opens a file.
///
/// Accessibility identifiers: `settings.data.notice`, `settings.data.export.<addons|library|progress|settings|streams>`,
/// `settings.data.export.profile.<i>`, `settings.data.export.private`, `settings.data.export`, `settings.data.import`.
struct DataSettingsPane: View {
    let state: DataSettingsUiState
    let store: DataSettingsStore
    let onImport: () -> Void

    var body: some View {
        Form {
            Section(state.exportHeader) {
                ForEach(state.exportCategories, id: \.category) { row in
                    SettingsToggleRow(row: row.toggle, id: "settings.data.export.\((row.category as __DataCategory).probeName)") {
                        store.setExportCategory(category: row.category, enabled: $0)
                    }
                }
            }
            if !state.exportProfiles.isEmpty {
                Section(state.profilesHeader) {
                    ForEach(Array(state.exportProfiles.enumerated()), id: \.element.profileId) { index, row in
                        SettingsToggleRow(row: row.toggle, id: "settings.data.export.profile.\(index)") {
                            store.setExportProfile(profileId: row.profileId, enabled: $0)
                        }
                    }
                }
            }
            Section {
                if let privacyNote = state.privacyNote {
                    SettingsNoteLabel(text: privacyNote, systemImage: "exclamationmark.triangle")
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("settings.data.export.private")
                }
                Button(action: store.export) {
                    HStack {
                        Label(state.exportLabel, systemImage: "square.and.arrow.up")
                        if state.working {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(!state.canExport)
                .accessibilityIdentifier("settings.data.export")
            } footer: {
                Text(state.exportNote)
            }
            if let notice = state.notice {
                Section {
                    SettingsNotice(text: notice.text, error: notice.error, onDismiss: store.dismissNotice)
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("settings.data.notice")
                }
            }
            Section {
                Button(action: onImport) {
                    Label(state.importLabel, systemImage: "square.and.arrow.down")
                }
                .disabled(!state.canImport)
                .accessibilityIdentifier("settings.data.import")
            } header: {
                Text(state.importHeader)
            } footer: {
                Text(state.importNote)
            }
        }
        .settingsForm()
    }
}

/// An opened file before it is imported, in a form: where it comes from, its categories with what each holds, and its
/// profiles, each ticked or not, with where it goes (a profile here or a new one) unless it is a profile this account
/// has, which it goes to. Import and Cancel are the toolbar's.
///
/// Accessibility identifiers: `settings.data.review.source`, `settings.data.review.<category>`,
/// `settings.data.review.profile.<i>` with its `.target`, `settings.data.review.<confirm|cancel>`.
private struct DataImportSheet: View {
    let review: DataImportReview
    let store: DataSettingsStore

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label(review.source, systemImage: "doc")
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("settings.data.review.source")
                }
                Section(review.dataHeader) {
                    ForEach(review.categories, id: \.category) { row in
                        SettingsToggleRow(row: row.toggle, id: "settings.data.review.\((row.category as __DataCategory).probeName)") {
                            store.setImportCategory(category: row.category, enabled: $0)
                        }
                    }
                }
                Section {
                    ForEach(Array(review.profiles.enumerated()), id: \.element.fileProfileId) { index, row in
                        SettingsToggleRow(row: row.toggle, id: "settings.data.review.profile.\(index)") {
                            store.setImportProfile(fileProfileId: row.fileProfileId, enabled: $0)
                        }
                        if let target = row.target {
                            SettingsChoicePicker(row: target, id: "settings.data.review.profile.\(index).target") {
                                store.setImportTarget(fileProfileId: row.fileProfileId, target: $0)
                            }
                        }
                    }
                } header: {
                    Text(review.profilesHeader)
                } footer: {
                    Text(review.note)
                }
            }
            .settingsForm()
            .pageTitle(review.title)
            .sheetToolbar(
                cancel: SheetAction(title: review.cancelLabel, id: "settings.data.review.cancel", action: store.cancelImport),
                confirm: SheetAction(title: review.confirmLabel, id: "settings.data.review.confirm", enabled: review.canConfirm, action: store.confirmImport)
            )
        }
        .backAction(store.cancelImport)
    }
}
#endif
