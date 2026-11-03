import SwiftUI
import TortugaKit

/// The account section over its store, with its confirmations and the device-link approval.
struct AccountSettingsRoute: View {
    let model: StoreModel<AccountSettingsStore, AccountSettingsUiState>

    var body: some View {
        let store = model.store
        let state = model.observer.state
        AccountSettingsPane(state: state, store: store)
            .task { await model.observer.observe() }
            .confirmation(
                state.dialog.map { dialog in
                    Confirmation(
                        title: dialog.title,
                        message: dialog.message,
                        confirmLabel: dialog.confirmLabel,
                        cancelLabel: dialog.cancelLabel,
                        destructive: dialog.destructive,
                        confirmId: "settings.account.dialog.confirm",
                        cancelId: "settings.account.dialog.cancel"
                    )
                },
                onConfirm: store.confirmDialog,
                onCancel: store.dismissDialog
            )
            .settingsSheet(item: state.linkApproval, onDismiss: store.closeLinkApproval) { link in
                LinkApprovalSheet(link: link, store: store)
            }
    }
}

/// The account, as a native form: sync status and server, sign-ins, devices (revoking one asks first),
/// approving a new device's link by its code, sign-out and account deletion. Destructive actions ask first
/// (`Confirmation`, worded by the store); what an action did shows as a notice.
///
/// Accessibility identifiers: `settings.account.<syncNow|linkIdentity|approveLink|signOut|delete>`,
/// `settings.account.identity.<i>.unlink`, `settings.account.device.<i>.revoke`,
/// `settings.account.dialog.<confirm|cancel>`, `settings.account.link.<code|submit|approve|reject|done|cancel>`.
struct AccountSettingsPane: View {
    let state: AccountSettingsUiState
    let store: AccountSettingsStore

    var body: some View {
        let enabled = !state.working
        Form {
            if let notice = state.notice {
                Section { SettingsNotice(text: notice, onDismiss: store.dismissNotice) }
            }
            Section {
                LabeledContent(state.statusLabel) {
                    VStack(alignment: .trailing, spacing: 2) {
                        Text(state.status)
                        if let lastSynced = state.lastSynced {
                            Text(lastSynced).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                }
                if let server = state.server {
                    LabeledContent(state.serverLabel, value: server)
                }
                if state.signedIn {
                    rowButton(state.syncNowLabel, systemImage: "arrow.triangle.2.circlepath", id: "settings.account.syncNow", enabled: state.canSyncNow, action: store.syncNow)
                }
                if state.loading {
                    ProgressView().frame(maxWidth: .infinity)
                }
                if let error = state.loadError {
                    ErrorLine(text: error)
                    rowButton(T.shared.nav.retry, systemImage: "arrow.clockwise", id: "settings.account.retry", action: store.refresh)
                }
            }
            if state.signedIn {
                Section(state.identitiesHeader) {
                    ForEach(Array(state.identities.enumerated()), id: \.element.id) { index, identity in
                        ItemRow(title: identity.provider, detail: identity.detail, badge: nil, provider: identity.providerId) {
                            if identity.canUnlink {
                                Button(role: .destructive) { store.unlinkIdentity(identityId: identity.id) } label: {
                                    Image(systemName: "trash").hitTargetLabel()
                                }
                                .buttonProminence(.plain)
                                .disabled(!enabled)
                                #if os(macOS)
                                .tooltip(identity.unlinkLabel)
                                #endif
                                .accessibilityLabel(identity.unlinkLabel)
                                .accessibilityIdentifier("settings.account.identity.\(index).unlink")
                                .hitTarget()
                            }
                        }
                    }
                    if state.canLinkIdentity {
                        rowButton(state.linkIdentityLabel, systemImage: "link", id: "settings.account.linkIdentity", enabled: enabled, action: store.linkIdentity)
                    }
                }
                Section(state.devicesHeader) {
                    ForEach(Array(state.devices.enumerated()), id: \.element.id) { index, device in
                        ItemRow(title: device.name, detail: device.detail, badge: device.currentLabel) {
                            Button(role: .destructive) { store.revokeDevice(deviceId: device.id) } label: {
                                Text(T.shared.settings.account.revoke.confirm).hitTargetLabel()
                            }
                                .buttonProminence(.plain)
                                .disabled(!enabled)
                                .accessibilityLabel(device.revokeLabel)
                                .accessibilityInputLabels([T.shared.settings.account.revoke.confirm, device.revokeLabel])
                                .accessibilityIdentifier("settings.account.device.\(index).revoke")
                                .hitTarget()
                        }
                    }
                    rowButton(state.approveLinkLabel, systemImage: "laptopcomputer.and.iphone", id: "settings.account.approveLink", enabled: enabled, action: store.startLinkApproval)
                }
                Section {
                    rowButton(state.signOutLabel, systemImage: "rectangle.portrait.and.arrow.right", id: "settings.account.signOut", role: .destructive, enabled: enabled, action: store.signOut)
                }
                Section {
                    rowButton(state.deleteAccount.title, systemImage: "trash", id: "settings.account.delete", role: .destructive, enabled: enabled, action: store.deleteAccount)
                } footer: {
                    Text(state.deleteAccount.subtitle)
                }
            }
        }
        .settingsForm(idle: !hasControl(enabled: enabled))
    }

    /// A form row that is one button: its label and icon, in red for `role` destructive.
    private func rowButton(
        _ title: String,
        systemImage: String,
        id: String,
        role: ButtonRole? = nil,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        Button(role: role, action: action) {
            Label(title, systemImage: systemImage)
        }
        .disabled(!enabled)
        .accessibilityIdentifier(id)
    }

    /// Whether the page shows a control that can take focus: the notice's dismiss button, the retry, or a signed-in
    /// account's actions (disabled while one runs).
    private func hasControl(enabled: Bool) -> Bool {
        state.notice != nil || state.loadError != nil || (state.signedIn && enabled)
    }
}

/// A sign-in or device: a sign-in's provider mark (`ProviderIcon`), its name, a detail line, an optional badge ("This
/// device") and its action, a plain button (`buttonProminence(.plain)`) so that only the button reacts, not the whole
/// row; on iOS and macOS the action beside the text or under it (`SettingsRowLayout`).
private struct ItemRow<Action: View>: View {
    let title: String
    let detail: String?
    let badge: String?
    var provider: String?
    @ViewBuilder let action: () -> Action

    #if !os(tvOS)
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    #endif

    var body: some View {
        HStack(spacing: 12) {
            if let provider { ProviderIcon(provider: provider, fallbackSymbol: "person.crop.circle").accessibilityHidden(true) }
            #if os(tvOS)
            text
            action()
            #else
            SettingsRowLayout(
                leastTextWidth: SettingsRowLayout.leastTextWidth(title: title, subtitle: detail, dynamicTypeSize: dynamicTypeSize),
                spacing: 12
            ) {
                text
                action()
            }
            #endif
        }
    }

    private var text: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 8) {
                Text(title)
                if let badge { BadgeView(text: badge) }
            }
            if let detail { Text(detail).font(.footnote).foregroundStyle(.secondary) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Approving a new device, in a form: its code typed (shown in capitals, with or without its dash as typed; the store
/// groups it for submit; Return continues; the hint inside the field while it is empty), checked with the server, then
/// the device shown for a yes or no, then the outcome. The step's actions are the toolbar's (iOS, macOS) or the form's
/// buttons (tvOS).
private struct LinkApprovalSheet: View {
    let link: LinkApprovalUiState
    let store: AccountSettingsStore

    /// The field's text, the store's as the sheet opens (seeded here, not on appear, where it went back to the store as
    /// if typed), and again as a failure brings the sheet back to the code (`reseed`).
    @State private var code: String
    /// The store's code the field last started again from (`reseed`): the field's change to it isn't sent back as if
    /// typed, which clears the error.
    @State private var given: String?
    @FocusState private var codeFocused: Bool

    init(link: LinkApprovalUiState, store: AccountSettingsStore) {
        self.link = link
        self.store = store
        _code = State(initialValue: Self.storeCode(link.step) ?? "")
    }

    var body: some View {
        let actions = actions
        let cancel = actions.cancel
        let confirm = actions.confirm
        NavigationStack {
            Form {
                switch onEnum(of: link.step) {
                case .enterCode(let entry):
                    Section {
                        TextField(link.codeHint, text: capitals, prompt: Text(link.codeHint))
                            .labelsHidden()
                            .font(.system(.title2, design: .monospaced).weight(.semibold))
                            .autocorrectionDisabled()
                            #if os(iOS)
                            .textInputAutocapitalization(.characters)
                            .keyboardType(.asciiCapable)
                            #endif
                            .submitLabel(.go)
                            .onSubmit { if entry.canSubmit { store.submitLinkCode() } }
                            // The field keeps the code as typed, in capitals (the store groups it for submit): replacing
                            // its text with the store's, which comes a moment later, can lose a key typed meanwhile.
                            .onChange(of: code) { _, text in
                                defer { given = nil }
                                if text != given { store.updateLinkCode(text: text) }
                            }
                            .focused($codeFocused)
                            .accessibilityIdentifier("settings.account.link.code")
                    } header: {
                        Text(link.instructions).textCase(nil)
                    } footer: {
                        if let error = entry.error { ErrorLine(text: error) }
                    }
                case .checking, .approving, .rejecting:
                    Section { ProgressView().frame(maxWidth: .infinity) }
                case .confirm(let confirm):
                    Section {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(confirm.deviceName).font(.headline)
                                Text(confirm.devicePlatform).font(.footnote).foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "laptopcomputer.and.iphone")
                        }
                    } header: {
                        Text(confirm.question).textCase(nil)
                    } footer: {
                        Text(confirm.consequence)
                    }
                case .approved(let approved):
                    outcome(approved.result)
                case .rejected(let rejected):
                    outcome(rejected.result)
                }
                SheetActionsSection(actions: [confirm, cancel].compactMap { $0 })
            }
            .settingsForm()
            .pageTitle(link.title)
            .sheetToolbar(cancel: cancel, confirm: confirm)
        }
        .backAction(store.closeLinkApproval)
        .onAppear { codeFocused = Self.storeCode(link.step) != nil }
        .onChange(of: link) { _, link in
            if case .enterCode(let entry) = onEnum(of: link.step), entry.error != nil { reseed(entry.code) }
        }
    }

    /// The outcome, in place of Approve or Reject, which had VoiceOver's focus.
    private func outcome(_ result: String) -> some View {
        Section { Text(result).announcing(result) }
    }

    /// The store's code while the step is the code field's; nil in the other steps.
    private static func storeCode(_ step: LinkApprovalStep) -> String? {
        switch onEnum(of: step) {
        case .enterCode(let entry): entry.code
        case .checking, .confirm, .approving, .rejecting, .approved, .rejected: nil
        }
    }

    /// A refused code, a failed answer or an expired link comes back to the code with the store's code cleared and the
    /// error under it: the field starts again from the store's code, so Continue's state matches what it shows, and the
    /// error stays. The step it passed through (checking, the device's question) may never have been drawn, so the
    /// error's arrival is the sign.
    private func reseed(_ stored: String) {
        guard code != stored else { return }
        given = stored
        code = stored
    }

    /// The code field's text, upper-cased as it is typed: the Mac's keyboard and a shifted-off iPhone keyboard type
    /// lower case, and the store keeps the code in capitals.
    private var capitals: Binding<String> {
        Binding(get: { code }, set: { code = $0.uppercased() })
    }

    /// The step's cancel and main actions.
    private var actions: (cancel: SheetAction?, confirm: SheetAction?) {
        switch onEnum(of: link.step) {
        case .enterCode(let entry):
            (
                SheetAction(title: T.shared.settings.account.cancel, id: "settings.account.link.cancel", action: store.closeLinkApproval),
                SheetAction(title: link.submitLabel, id: "settings.account.link.submit", enabled: entry.canSubmit, action: store.submitLinkCode)
            )
        case .confirm:
            (
                SheetAction(title: link.rejectLabel, id: "settings.account.link.reject", role: .destructive, action: store.rejectLink),
                SheetAction(title: link.approveLabel, id: "settings.account.link.approve", action: store.approveLink)
            )
        case .approved, .rejected:
            (nil, SheetAction(title: T.shared.settings.account.link.done, id: "settings.account.link.done", action: store.closeLinkApproval))
        case .checking, .approving, .rejecting:
            (SheetAction(title: T.shared.settings.account.cancel, id: "settings.account.link.cancel", action: store.closeLinkApproval), nil)
        }
    }
}
