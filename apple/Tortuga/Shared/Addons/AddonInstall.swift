import SwiftUI
import TortugaKit

// `installLinksOverScreen` is set under the same gate as the cover that shows the preview, so a platform that
// previews install links over the screen always has the cover.
#if os(tvOS)
extension View {
    /// Apple TV: the install page of an addon whose install link the app followed (`AppSessionState.installPreview`),
    /// over whatever shows (`topmostCover`), in place of Settings' Addons section opened on it
    /// (`AppPlatform.installLinksOverScreen`). Cancel and the remote's Menu leave the user where they were; once the
    /// addon is installed and the page has gone, Settings opens on Addons, which lists it: a plain switch of tab, with
    /// no page coming up as it happens.
    func installLinkCover(_ app: AppModel) -> some View {
        modifier(InstallLinkCover(app: app))
    }
}

extension AppleAppPlatform {
    /// Apple TV previews an addon's install link over the screen it is on (`AppSessionState.installPreview`,
    /// `installLinkCover`).
    var installLinksOverScreen: Bool { true }
}

private struct InstallLinkCover: ViewModifier {
    let app: AppModel

    /// The addon installed: Settings opens on Addons once the page has gone.
    @State private var installed = false

    func body(content: Content) -> some View {
        let session = app.session
        content.topmostCover(item: app.state.state.installPreview, onGone: {
            guard installed else { return }
            installed = false
            session.openAddons(installLink: nil)
        }) { link in
            InstallLinkPage(session: session, link: link) {
                installed = true
                session.closeInstallPreview()
            }
        }
    }
}

/// The install page of `link` with an Addons store of its own; `onInstalled` runs once the addon is installed. Cancel,
/// Menu, or the system dismissing the page close it (`AppSession.closeInstallPreview`).
private struct InstallLinkPage: View {
    let session: AppSession
    let link: TransportUrl
    let onInstalled: () -> Void

    var body: some View {
        LiveStore(
            create: { session.addonsStore() },
            state: { $0.state },
            release: { $0.release() }
        ) { store, state in
            InstallSheet(install: state.install, store: store)
                .task(id: link) { store.previewInstall(link: link) }
                .onChange(of: Self.ended(state.install.step)) { _, ended in
                    switch ended {
                    case true?: onInstalled()
                    case false?: session.closeInstallPreview()
                    case nil: break
                    }
                }
        }
        .background(Palette.background)
        .backAction(session.closeInstallPreview)
        .onDisappear(perform: session.closeInstallPreview)
    }

    /// Whether the install has ended: true once installed, false once closed; nil while it goes on.
    private static func ended(_ step: AddonInstallStep) -> Bool? {
        switch onEnum(of: step) {
        case .installed: true
        case .closed: false
        case .enterLink, .loading, .preview, .installing: nil
        }
    }
}
#else
extension AppleAppPlatform {
    /// Elsewhere an install link opens Settings' Addons section on it.
    var installLinksOverScreen: Bool { false }
}
#endif

/// Installing from a link, in a form: the link, then the fetched manifest to confirm (or to configure first, for an
/// addon with settings: Configure), then the outcome. A manifest that asks for the user's watch activity brings the
/// switch of their agreement, which a new install waits for (`AddonPreviewUiState.canConfirm`: the action disabled
/// until then). Cancel and the step's action are the toolbar's on iOS and macOS, Configure and the agreement rows under
/// the addon's header there; on tvOS all of them are the form's first rows (the agreement first, else the action,
/// focused first there).
struct InstallSheet: View {
    let install: AddonInstallUiState
    let store: AddonsStore

    /// The field keeps its own text: the store's state arrives a moment later, and typing must not wait for it. It
    /// starts as the store's (seeded here, not on appear, where it went back to the store as if typed and cleared the
    /// install's error), and starts from it again as a failure brings the page back to the link (`reseed`).
    @State private var link: String
    /// The store's link the field last started again from (`reseed`): the field's change to it isn't sent back as if
    /// typed, which clears the error.
    @State private var given: String?
    @FocusState private var linkFocused: Bool

    init(install: AddonInstallUiState, store: AddonsStore) {
        self.install = install
        self.store = store
        let link: String = switch onEnum(of: install.step) {
        case .enterLink(let step): step.link
        case .closed, .loading, .preview, .installing, .installed: ""
        }
        _link = State(initialValue: link)
    }

    var body: some View {
        NavigationStack {
            Form {
                #if os(tvOS)
                if !entersLink { tvActions }
                #endif
                switch onEnum(of: install.step) {
                case .enterLink(let step):
                    Section {
                        TextField(install.linkHint, text: $link, prompt: Text(install.linkHint))
                            .labelsHidden()
                            .autocorrectionDisabled()
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            .keyboardType(.URL)
                            #endif
                            .submitLabel(.go)
                            .onChange(of: link) { _, text in
                                defer { given = nil }
                                if text != given { store.updateInstallLink(text: text) }
                            }
                            .onSubmit(submit)
                            .focused($linkFocused)
                            .accessibilityIdentifier("addons.install.link")
                    } footer: {
                        if let error = step.error { ErrorLine(text: error) }
                    }
                    #if os(tvOS)
                    tvActions
                    #endif
                case .loading:
                    progress
                case .installing(let step):
                    PreviewSections(preview: step.preview)
                    progress
                case .preview(let step):
                    let preview = step.preview
                    PreviewSections(preview: preview, onConfigure: store.configurePreview, onConsent: { store.setInstallConsent(agreed: $0) })
                    if preview.status != nil || preview.warning != nil || step.error != nil {
                        Section {
                            if let status = preview.status { Text(status).fixedSize(horizontal: false, vertical: true) }
                            if let warning = preview.warning { SettingsNoteLabel(text: warning, systemImage: "exclamationmark.triangle") }
                            if let error = step.error { ErrorLine(text: error) }
                        }
                    }
                case .installed(let step):
                    // In place of Install, which had VoiceOver's focus.
                    Section { Label(step.result, systemImage: "checkmark.circle").announcing(step.result) }
                case .closed:
                    EmptyView()
                }
            }
            .settingsForm()
            .pageTitle(install.title)
            #if !os(tvOS)
            .toolbar { toolbar }
            #endif
        }
        .onAppear { linkFocused = entersLink }
        .onChange(of: linkError) { _, error in if error != nil { reseed() } }
    }

    /// The spinner of a step that waits: the manifest's fetch, an install.
    private var progress: some View {
        Section {
            ProgressView().frame(maxWidth: .infinity)
        }
    }

    private var entersLink: Bool {
        if case .enterLink = onEnum(of: install.step) { true } else { false }
    }

    /// Why the last link failed, while the page asks for one.
    private var linkError: String? {
        switch onEnum(of: install.step) {
        case .enterLink(let step): step.error
        case .closed, .loading, .preview, .installing, .installed: nil
        }
    }

    /// A link that fails comes back to the link with the error under it and the store's link: the one typed, or none
    /// for a link followed into the app while the page was open (`AddonsStore.previewInstall`). The field starts again
    /// from the store's, so Continue's state matches what it shows, and the error stays. The loading step between may
    /// never have been drawn (a refused connection fails at once), so the error's arrival is the sign.
    private func reseed() {
        switch onEnum(of: install.step) {
        case .enterLink(let step):
            guard link != step.link else { return }
            given = step.link
            link = step.link
        case .closed, .loading, .preview, .installing, .installed:
            break
        }
    }

    #if !os(tvOS)
    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        switch onEnum(of: install.step) {
        case .enterLink(let step):
            ToolbarItem(placement: .cancellationAction) {
                Button(T.shared.addons.cancel, action: store.closeInstall)
                    .keyboardShortcut(.cancelAction)
                    .accessibilityIdentifier("addons.install.cancel")
            }
            ToolbarItem(placement: .confirmationAction) {
                Button(install.submitLabel, action: submit)
                    .disabled(!step.canSubmit)
                    .accessibilityIdentifier("addons.install.submit")
            }
        case .preview(let step):
            let preview = step.preview
            ToolbarItem(placement: .cancellationAction) {
                Button(preview.cancelLabel, action: store.closeInstall)
                    .keyboardShortcut(.cancelAction)
                    .accessibilityIdentifier("addons.install.cancel")
            }
            ToolbarItem(placement: .confirmationAction) {
                switch onEnum(of: preview.action) {
                case .none:
                    EmptyView()
                case .confirm(let confirm):
                    Button(confirm.label, action: store.confirmInstall)
                        .disabled(!preview.canConfirm)
                        .accessibilityIdentifier("addons.install.confirm")
                }
            }
        case .installed:
            ToolbarItem(placement: .confirmationAction) {
                Button(T.shared.addons.install.done, action: store.closeInstall).accessibilityIdentifier("addons.install.done")
            }
        case .loading, .installing, .closed:
            ToolbarItem(placement: .cancellationAction) {
                Button(T.shared.addons.cancel, action: store.closeInstall)
                    .keyboardShortcut(.cancelAction)
                    .accessibilityIdentifier("addons.install.cancel")
            }
        }
    }
    #endif

    #if os(tvOS)
    /// The agreement to send the addon watch activity where the manifest asks for it, the step's action, then Cancel:
    /// the tvOS form's buttons (its navigation bar has no buttons). A disabled button fades: tvOS draws it as an enabled
    /// one without focus.
    @ViewBuilder
    private var tvActions: some View {
        switch onEnum(of: install.step) {
        case .enterLink(let step):
            Section {
                Button(install.submitLabel, action: submit)
                    .disabled(!step.canSubmit)
                    .accessibilityIdentifier("addons.install.submit")
                Button(T.shared.addons.cancel, action: store.closeInstall).accessibilityIdentifier("addons.install.cancel")
            }
        case .preview(let step):
            let preview = step.preview
            Section {
                if let activity = preview.watchActivity {
                    SettingsToggleRow(title: activity.label, subtitle: activity.explanation, checked: activity.checked, id: "addons.install.watchActivity") {
                        store.setInstallConsent(agreed: $0)
                    }
                }
                switch onEnum(of: preview.action) {
                case .none:
                    EmptyView()
                case .confirm(let confirm):
                    Button(confirm.label, action: store.confirmInstall)
                        .disabled(!preview.canConfirm)
                        .opacity(preview.canConfirm ? 1 : 0.45)
                        .accessibilityIdentifier("addons.install.confirm")
                }
                if let configure = preview.configureLabel {
                    Button(configure, action: store.configurePreview).accessibilityIdentifier("addons.install.configure")
                }
                Button(preview.cancelLabel, action: store.closeInstall).accessibilityIdentifier("addons.install.cancel")
            }
        case .installed:
            Section {
                Button(T.shared.addons.install.done, action: store.closeInstall).accessibilityIdentifier("addons.install.done")
            }
        case .loading, .installing:
            Section {
                Button(T.shared.addons.cancel, action: store.closeInstall).accessibilityIdentifier("addons.install.cancel")
            }
        case .closed:
            EmptyView()
        }
    }
    #endif

    /// The field's text goes to the store first: Return can arrive before the store saw the last change.
    private func submit() {
        store.updateInstallLink(text: link)
        store.submitInstallLink()
    }
}

/// The fetched manifest: its header and description, Configure where `onConfigure` is given and the addon has settings
/// and the switch of the user's agreement to send it their watch activity where `onConsent` is given and the manifest
/// asks for it (iOS and macOS; tvOS has both among the page's actions), then what it serves and provides, and its
/// catalogs.
private struct PreviewSections: View {
    let preview: AddonPreviewUiState
    var onConfigure: (() -> Void)?
    var onConsent: ((Bool) -> Void)?

    var body: some View {
        let details = T.shared.addons.details
        Section {
            AddonHeader(name: preview.name, version: preview.version, logo: preview.logo)
            if let description = preview.descriptionText {
                Text(description).fixedSize(horizontal: false, vertical: true)
            }
        }
        #if !os(tvOS)
        if let label = preview.configureLabel, let onConfigure {
            Section {
                Button(action: onConfigure) { Label(label, systemImage: "slider.horizontal.3") }
                    .accessibilityIdentifier("addons.install.configure")
            }
        }
        if let activity = preview.watchActivity, let onConsent {
            Section {
                SettingsToggleRow(title: activity.label, subtitle: activity.explanation, checked: activity.checked, id: "addons.install.watchActivity", onChange: onConsent)
            }
        }
        #endif
        Section {
            LabeledContent(details.types, value: preview.types)
            LabeledContent(details.resources, value: preview.resources)
        }
        if !preview.catalogs.isEmpty {
            Section(details.catalogs) {
                ForEach(Array(preview.catalogs.enumerated()), id: \.offset) { _, catalog in
                    Text(catalog)
                }
            }
        }
    }
}
