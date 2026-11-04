import SwiftUI
import TortugaKit
#if !os(tvOS)
import UniformTypeIdentifiers
#endif

/// Settings → Home → Import collections over `store`, in a form: the store's note, the link field (Return and the
/// keyboard's Go fetch it), Get and, on iPhone, iPad and the Mac, Open file (the system's open dialog, the file read
/// as UTF-8 text); a spinner while it reads, why it failed in the error colour. Then the summary: the file's
/// collections, a + for one it adds and a refresh mark for one replacing the profile's, each with what it brings and
/// its first folders, then each line of the summary muted; Import, off while nothing would import. Once imported, the
/// store's message and Done. Close (iOS, macOS: the toolbar's) and the remote's Menu close it (`onClose`), Import and
/// Done are the toolbar's confirm on iOS and macOS and the form's last button on tvOS, where every collection and line
/// of the summary is a stop of the remote (a row's focus), so the summary scrolls as focus walks it and Up from Import
/// reaches its last line.
///
/// Accessibility identifiers (the Compose UI's names): `collections.import.url`, `collections.import.fetch`,
/// `collections.import.open`, `collections.import.error`, `collections.import.collection.<i>`,
/// `collections.import.line.<i>`, `collections.import.confirm`, `collections.import.done`, `collections.import.close`.
struct CollectionImportSheet: View {
    let store: CollectionImportStore
    let onClose: () -> Void

    @State private var observer: StoreObserver<CollectionImportUiState>
    /// The link as typed: the store hears it on Get.
    @State private var link = ""
    #if !os(tvOS)
    /// The open dialog is up.
    @State private var opening = false
    #endif

    init(store: CollectionImportStore, onClose: @escaping () -> Void) {
        self.store = store
        self.onClose = onClose
        _observer = State(initialValue: StoreObserver(store.state))
    }

    var body: some View {
        let state = observer.state
        NavigationStack {
            Form {
                #if os(tvOS)
                if case .done = onEnum(of: state.step), let action = mainAction(state.step) { SheetActionsSection(actions: [action]) }
                #endif
                switch onEnum(of: state.step) {
                case .summary(let summary):
                    summarySections(summary)
                case .done(let done):
                    // In place of Import, which had VoiceOver's focus.
                    Section { Label(done.message, systemImage: "checkmark.circle").announcing(done.message) }
                case .idle, .loading, .failed:
                    linkSections(state)
                }
                #if os(tvOS)
                // Import last, under the summary: Up from it walks the summary from its end.
                if case .summary = onEnum(of: state.step), let action = mainAction(state.step) {
                    SheetActionsSection(actions: [action])
                }
                #endif
            }
            .settingsForm()
            .pageTitle(state.title)
            .sheetToolbar(cancel: SheetAction(title: T.shared.settings.close, id: "collections.import.close", action: onClose), confirm: mainAction(state.step))
        }
        .task { await observer.observe() }
        .backAction(onClose)
        #if !os(tvOS)
        // Any file, so one saved under another type is still offered; the store tells what it holds.
        .fileImporter(isPresented: $opening, allowedContentTypes: [.json, .data]) { result in
            switch result {
            case .success(let url):
                Task { if let text = await importedText(url) { store.open(text: text) } else { store.openFailed() } }
            case .failure: store.openFailed()
            }
        }
        #endif
    }

    /// The link step: the note, the field, Get and Open file, then the spinner or why it failed.
    @ViewBuilder
    private func linkSections(_ state: CollectionImportUiState) -> some View {
        let loading = state.step is CollectionImportStepLoading
        Section {
            TextField(state.urlLabel, text: $link, prompt: Text(state.urlHint))
                .autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never)
                .keyboardType(.URL)
                #endif
                .submitLabel(.go)
                .onSubmit(fetch)
                .disabled(loading)
                .accessibilityIdentifier("collections.import.url")
            Button(action: fetch) {
                Label(state.fetchLabel, systemImage: "arrow.down.circle")
            }
            .disabled(loading || link.trimmingCharacters(in: .whitespaces).isEmpty)
            .accessibilityIdentifier("collections.import.fetch")
            #if !os(tvOS)
            if let open = state.openFileLabel {
                Button { opening = true } label: {
                    Label(open, systemImage: "doc")
                }
                .disabled(loading)
                .accessibilityIdentifier("collections.import.open")
            }
            #endif
        } footer: {
            Text(state.note)
        }
        switch onEnum(of: state.step) {
        case .loading:
            Section { ProgressView().frame(maxWidth: .infinity) }
        case .failed(let failed):
            Section {
                ErrorLine(text: failed.message).accessibilityIdentifier("collections.import.error")
            }
        case .idle, .summary, .done:
            EmptyView()
        }
    }

    /// The summary: the file's collections as one group, then its lines.
    @ViewBuilder
    private func summarySections(_ summary: CollectionImportStepSummary) -> some View {
        if !summary.collections.isEmpty {
            Section {
                ForEach(Array(summary.collections.enumerated()), id: \.offset) { index, row in
                    summaryStop(id: "collections.import.collection.\(index)") {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.title)
                                Text(row.detail).font(.footnote).foregroundStyle(.secondary)
                                Text(row.folders).font(.footnote).foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: row.replaces ? "arrow.triangle.2.circlepath" : "plus")
                        }
                    }
                }
            }
        }
        if !summary.lines.isEmpty {
            Section {
                ForEach(Array(summary.lines.enumerated()), id: \.offset) { index, line in
                    summaryStop(id: "collections.import.line.\(index)") {
                        Text(line).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
    }

    /// A row of the summary: on tvOS a button that does nothing, so the remote stops on it and the form scrolls it into
    /// view; elsewhere the row as it is.
    @ViewBuilder
    private func summaryStop(id: String, @ViewBuilder content: () -> some View) -> some View {
        #if os(tvOS)
        // The button takes its texts as its label.
        Button {} label: { content().frame(maxWidth: .infinity, alignment: .leading) }
            .accessibilityIdentifier(id)
        #else
        content()
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier(id)
        #endif
    }

    /// The step's main action: Import over the summary, Done once imported; none on the link step, whose Get is the
    /// form's.
    private func mainAction(_ step: CollectionImportStep) -> SheetAction? {
        switch onEnum(of: step) {
        case .summary(let summary):
            SheetAction(title: summary.importLabel, id: "collections.import.confirm", enabled: summary.canImport, action: store.confirm)
        case .done(let done):
            SheetAction(title: done.doneLabel, id: "collections.import.done", action: onClose)
        case .idle, .loading, .failed:
            nil
        }
    }

    private func fetch() {
        let url = link.trimmingCharacters(in: .whitespaces)
        guard !url.isEmpty, !(observer.state.step is CollectionImportStepLoading) else { return }
        store.fetch(url: url)
    }
}
