import SwiftUI
import TortugaKit

/// The sign-in screen of `root` (`AccountDestination.SignIn`): its `SignInStore` lives while the screen shows.
/// Coming back to the foreground (from the browser) asks the flows for their result at once. A sign-in to another
/// account than the one whose unsynced changes this device holds asks first (`SignInUiState.confirm`).
struct SignInRoute: View {
    let root: RootModel

    var body: some View {
        LiveStore(
            create: { root.graph.signInStore(gate: root.gate, platform: root.signIn, hostedServer: root.signInServer) },
            state: { $0.state },
            release: { $0.release() }
        ) { store, state in
            SignInScreen(state: state, notice: root.gateState.state.notice, store: store, dismissNotice: root.gate.dismissNotice)
                .onForeground { store.onForeground() }
                .confirmation(
                    state.confirm.map { confirm in
                        Confirmation(
                            title: confirm.title,
                            message: confirm.message,
                            confirmLabel: confirm.confirmLabel,
                            cancelLabel: confirm.cancelLabel,
                            destructive: true,
                            confirmId: "signIn.unsynced.confirm",
                            cancelId: "signIn.unsynced.cancel"
                        )
                    },
                    onConfirm: store.discardUnsynced,
                    onCancel: store.keepUnsynced
                )
        }
    }
}

/// Sign-in to a Tortuga account, one step at a time: the provider buttons with the server line, a running flow's
/// progress with cancel, a TV's device code, and linking from a signed-in device (the code and its QR code). A new
/// account goes on to the profiles. "Use your own server" opens the server sheet (a `TrailingPanel` on tvOS). Every
/// text comes from `state` or `T`; `notice` is why this device was signed out.
struct SignInScreen: View {
    let state: SignInUiState
    let notice: String?
    let store: SignInStore
    let dismissNotice: () -> Void

    var body: some View {
        let signIn = T.shared.account.signIn
        switch onEnum(of: state.step) {
        case .choose(let choose):
            AccountPage(title: signIn.title, intro: signIn.intro) {
                if let notice { NoticeCard(text: notice, onDismiss: dismissNotice) }
                choices(error: choose.error)
            }
            #if os(tvOS)
            .trailingPanel(isPresented: serverSheet, id: "signIn.server.panel") {
                ServerSheet(server: state.server, store: store)
            }
            #else
            .statePresentation(serverSheet) { base, shown in
                base.sheet(isPresented: shown) {
                    ServerSheet(server: state.server, store: store)
                }
            }
            #endif
        case .working(let working):
            AccountPage(title: signIn.title, onBack: store.cancel, backId: "signIn.back", holdsFocus: true) {
                ProgressLine(text: working.progress)
            }
        case .deviceCode(let deviceCode):
            AccountPage(title: signIn.title, onBack: store.cancel, backId: "signIn.back", holdsFocus: true) {
                DeviceCodePanel(code: deviceCode.code, progress: deviceCode.progress)
            }
        case .link(let link):
            AccountPage(title: T.shared.account.link.title, onBack: store.cancel, backId: "signIn.back", holdsFocus: !(link.link is DeviceLinkUiStateFailed)) {
                DeviceLinkPanel(link: link.link, onRestart: store.restartLink)
            }
        }
    }

    /// The provider buttons (or why there are none), the link choice under them, and the server line. The buttons stand
    /// in the room of `optionsRoom` buttons laid out unseen and out of reach (no focus, no accessibility element), the
    /// spinner over the first while they load: whatever the server answers (fewer buttons than room, none, why it
    /// couldn't be reached), what lies under them stays where a tap, click or the focus aims as it comes. Each room
    /// button wears the mark of the hosted server's button it keeps room for (Apple's, Google's, GitHub's: ProviderFlows'
    /// order), as a mark sets its button's height: another symbol stood the link a pixel lower while the options loaded.
    /// `error` (why the last attempt failed) shows under them.
    @ViewBuilder
    private func choices(error: String?) -> some View {
        let signIn = T.shared.account.signIn
        ZStack(alignment: .topLeading) {
            // Spaced as the page's rows (AccountPage).
            VStack(alignment: .leading, spacing: 12) {
                let hosted = [IdentityProvider.shared.APPLE, IdentityProvider.shared.GOOGLE, IdentityProvider.shared.GITHUB]
                ForEach(0..<Int(state.optionsRoom), id: \.self) { index in
                    ActionButton(
                        title: " ",
                        id: "",
                        prominence: index == 0 ? .primary : .secondary,
                        enabled: false,
                        icon: ProviderIcon(provider: hosted[min(index, hosted.count - 1)], fallbackSymbol: "person.crop.circle.badge.checkmark")
                    ) {}
                    .opacity(0)
                    .accessibilityHidden(true)
                    .overlay(alignment: .leading) {
                        if index == 0 && state.optionsLoading { ProgressLine(text: signIn.loadingOptions) }
                    }
                }
            }
            VStack(alignment: .leading, spacing: 12) {
                if state.optionsLoading {
                    if state.optionsRoom == 0 { ProgressLine(text: signIn.loadingOptions) }
                } else {
                    ForEach(Array(state.options.enumerated()), id: \.element.provider) { index, option in
                        ActionButton(
                            title: option.label,
                            id: "signIn.option.\(option.provider)",
                            prominence: index == 0 ? .primary : .secondary,
                            icon: ProviderIcon(provider: option.provider, fallbackSymbol: "person.crop.circle.badge.checkmark")
                        ) {
                            store.signIn(provider: option.provider)
                        }
                    }
                    if let note = state.optionsNote {
                        Text(note).font(.callout).foregroundStyle(Palette.onSurfaceVariant)
                    }
                    if state.canRetry {
                        ActionButton(title: signIn.retry, id: "signIn.retry", systemImage: "arrow.clockwise", action: store.retry)
                    }
                }
            }
            // The buttons' glass, one column of it; the unseen room behind them stays out of the group.
            .glassGroup(spacing: 12)
        }
        if let error { ErrorLine(text: error) }
        ActionButton(title: signIn.linkDevice, id: "signIn.link", systemImage: "laptopcomputer.and.iphone", action: store.linkDevice)
        Text(signIn.linkDeviceHint)
            .font(.footnote)
            .foregroundStyle(Palette.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
        Divider().padding(.vertical, 8)
        Label(state.server.text, systemImage: "server.rack")
            .font(.footnote)
            .foregroundStyle(Palette.onSurfaceVariant)
        ServerLinks(custom: state.server.custom, onEdit: store.editServer, onHosted: store.useHostedServer)
    }

    /// The server sheet is up while the store edits the server; closing it cancels the edit.
    private var serverSheet: Binding<Bool> {
        Binding(get: { state.server.editing }, set: { if !$0 { store.cancelServerEdit() } })
    }
}

/// "Use your own server" and, on a server of one's own, "Use the Tortuga server": text buttons side by side (glass
/// ones on tvOS, one above the other so Down reaches each).
private struct ServerLinks: View {
    let custom: Bool
    let onEdit: () -> Void
    let onHosted: () -> Void

    var body: some View {
        let signIn = T.shared.account.signIn
        #if os(tvOS)
        let layout = AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
        #else
        let layout = AnyLayout(HStackLayout(spacing: 20))
        #endif
        layout {
            ActionButton(title: signIn.useOwnServer, id: "signIn.server.edit", prominence: .plain, fullWidth: false, action: onEdit)
            if custom {
                ActionButton(title: signIn.useHostedServer, id: "signIn.server.hosted", prominence: .plain, fullWidth: false, action: onHosted)
            }
        }
        // On Apple TV glass buttons, one column of them.
        .glassGroup(spacing: 12)
    }
}

/// "Use your own server": the address field, its error or the check running, and Check. The field is local while
/// typing and goes to the store on every change, so the store checks what was typed.
///
/// - iOS, macOS: a form in a sheet, Cancel and Check in its toolbar.
/// - tvOS: a column in a `TrailingPanel` (`signIn.server.panel`; the system's sheet clipped the form and its title),
///   headed by the title (`signIn.server.title`), Check its last row, white with dark text while focused; the remote's
///   Menu cancels. Focus goes to the field when the panel opens and when a check ends with nothing in the panel
///   focused; while the check runs (the field and Check disabled) the column holds it.
private struct ServerSheet: View {
    let server: SignInServerUiState
    let store: SignInStore

    /// The field's text, the store's as the sheet opens (seeded here, not on appear, where it went back to the store as
    /// if typed).
    @State private var address: String
    @FocusState private var focus: ServerFocus?

    init(server: SignInServerUiState, store: SignInStore) {
        self.server = server
        self.store = store
        _address = State(initialValue: server.field)
    }

    var body: some View {
        form
            #if !os(tvOS)
            .onAppear { focus = .field }
            #endif
    }

    #if os(tvOS)
    private var form: some View {
        let signIn = T.shared.account.signIn
        return VStack(alignment: .leading, spacing: 24) {
            Text(signIn.useOwnServer)
                .font(.title3.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("signIn.server.title")
            field
            status
            ActionButton(title: signIn.checkServer, id: "signIn.server.submit", prominence: .primary, enabled: server.canSubmit, action: store.submitServer)
                .focused($focus, equals: .submit)
        }
        .padding(.top, 48)
        .holdsBackFocus(server.checking)
        .task(id: server.checking) {
            if !server.checking { await FocusRestore.restore($focus, to: .field) }
        }
    }
    #else
    private var form: some View {
        let signIn = T.shared.account.signIn
        return NavigationStack {
            Form {
                Section {
                    field
                } footer: {
                    status
                }
            }
            .formStyle(.grouped)
            .pageTitle(signIn.useOwnServer)
            .sheetToolbar(
                cancel: SheetAction(title: signIn.cancel, id: "signIn.server.cancel", action: store.cancelServerEdit),
                confirm: SheetAction(title: signIn.checkServer, id: "signIn.server.submit", enabled: server.canSubmit, action: store.submitServer)
            )
        }
        .sheetFrame(.server)
    }
    #endif

    private var field: some View {
        let signIn = T.shared.account.signIn
        return TextField(signIn.serverField, text: $address, prompt: Text(signIn.serverFieldHint))
            .autocorrectionDisabled()
            #if os(iOS)
            .textContentType(.URL)
            .keyboardType(.URL)
            .textInputAutocapitalization(.never)
            #endif
            .disabled(server.checking)
            .focused($focus, equals: .field)
            .onSubmit(store.submitServer)
            .onChange(of: address) { _, text in store.setServerField(text: text) }
            .accessibilityIdentifier("signIn.server.field")
    }

    @ViewBuilder
    private var status: some View {
        if let error = server.error {
            ErrorLine(text: error)
        } else if server.checking {
            ProgressLine(text: T.shared.account.signIn.checkingServer)
        }
    }
}

/// What has focus in the server sheet.
private enum ServerFocus: Hashable {
    case field
    case submit
}
