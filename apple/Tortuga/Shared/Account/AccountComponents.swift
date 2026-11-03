import SwiftUI
import TortugaKit

#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
#endif

/// An account screen: one centred column, no wider than a phone's, of the `title` (a large title), the `intro`,
/// `content` and `actions`, scrolling when tall. In a Mac sheet `actions` stay pinned under the scrolling column, so a
/// page taller than its sheet (the profile editor with its PIN and Kids profile sections) still shows its buttons.
/// `onBack` gives the page its one way back: the system's back button (the navigation
/// bar's on iOS, the window toolbar's on the Mac; its accessibility identifier is `backId`), the remote's Back (tvOS
/// Menu) and Escape, which presses the button without needing focus. A sheet (`AppShell`'s overlay) has no Back:
/// there `onBack` is the page's `exit`, Cancel or Close, in the bar's cancellation slot on iOS and as the page's own
/// button on the Mac (`PageDismissButton`), Escape pressing it either way. In a tvOS `TrailingPanel` the page lies on
/// the panel's surface from its top. Content that shows nothing to focus on tvOS (`holdsFocus`: a code to read, a
/// spinner) lets the page take focus, so the remote's Back reaches it rather than leaving the app.
struct AccountPage<Content: View, Actions: View>: View {
    let title: String
    var intro: String?
    var onBack: (() -> Void)?
    var backId = "account.back"
    /// How `onBack` reads where the page closes a sheet.
    var exit: PageExit?
    var holdsFocus = false
    @ViewBuilder var content: () -> Content
    /// The page's buttons, under `content`.
    @ViewBuilder var actions: () -> Actions

    @Environment(\.formFactor) private var formFactor
    @Environment(\.screenSides) private var sides
    /// Shown in a sheet (`AppShell`'s overlay: an iOS or Mac sheet, a tvOS `TrailingPanel`), not as the window's screen.
    @Environment(\.isInSheet) private var inSheet
    #if os(tvOS)
    @FocusState private var pageFocus: String?
    #endif

    var body: some View {
        #if os(tvOS)
        if holdsFocus, onBack != nil {
            page
                .focusable()
                .focusEffectDisabled()
                .focused($pageFocus, equals: "page")
                .task { await FocusRestore.restore($pageFocus, to: "page", hold: 1.5) }
                .backAction(onBack)
        } else {
            page.backAction(onBack)
        }
        #else
        if let onBack, holdsToolbar {
            NavigationStack {
                page.toolbar {
                    #if os(iOS)
                    if inSheet, let exit {
                        ToolbarItem(placement: .cancellationAction) { exitButton(exit, action: onBack) }
                    } else {
                        ToolbarItem(placement: .topBarLeading) { backButton(onBack) }
                    }
                    #else
                    ToolbarItem(placement: .navigation) { backButton(onBack) }
                    #endif
                }
            }
            .backAction(onBack)
        } else {
            page.backAction(onBack)
        }
        #endif
    }

    #if !os(tvOS)
    private func backButton(_ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(T.shared.nav.back, systemImage: "chevron.backward")
        }
        .keyboardShortcut(.cancelAction)
        #if os(macOS)
        // The toolbar shows the symbol alone.
        .help(T.shared.nav.back)
        #endif
        .accessibilityIdentifier(backId)
    }
    #endif

    #if os(iOS)
    /// `exit` in a sheet's bar: Close as the system's close button (`SheetCloseButton`), Cancel as a word.
    @ViewBuilder
    private func exitButton(_ exit: PageExit, action: @escaping () -> Void) -> some View {
        if exit.closes {
            SheetCloseButton(title: exit.title, id: exit.id, action: action)
        } else {
            Button(exit.title, action: action)
                .keyboardShortcut(.cancelAction)
                .accessibilityIdentifier(exit.id)
        }
    }
    #endif

    /// The page's toolbar can show its way out: not in a Mac sheet.
    private var holdsToolbar: Bool {
        #if os(macOS)
        !inSheet
        #else
        true
        #endif
    }

    /// The page has its back control (the toolbar's Back, or in an iOS sheet its `exit`; the remote's Back), which a
    /// button of its own would repeat.
    private var hasBack: Bool {
        #if os(tvOS)
        onBack != nil
        #else
        onBack != nil && holdsToolbar
        #endif
    }

    /// The page's `exit` for a button of its own (`PageDismissButton`), where it has no back control to carry it.
    private var ownExit: (exit: PageExit, action: () -> Void)? {
        guard !hasBack, let exit, let onBack else { return nil }
        return (exit, onBack)
    }

    private var page: some View {
        VStack(spacing: 0) {
            GeometryReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(title)
                                .font(.largeTitle.bold())
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityAddTraits(.isHeader)
                            if let intro {
                                Text(intro)
                                    .foregroundStyle(Palette.onSurfaceVariant)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        .padding(.bottom, 12)
                        content()
                            .environment(\.accountPageExit, ownExit)
                        if holdsToolbar {
                            actions()
                                .environment(\.accountPageExit, ownExit)
                        }
                    }
                    .frame(maxWidth: columnWidth, alignment: .leading)
                    .padding(sides.insets)
                    .padding(.vertical, 56)
                    // Short pages sit in the middle of the window (at the top of a tvOS panel); tall ones scroll.
                    .frame(maxWidth: .infinity, minHeight: proxy.size.height, alignment: inPanel ? .top : .center)
                }
            }
            // In a Mac sheet the buttons stay under the scrolling column.
            if !holdsToolbar, Actions.self != EmptyView.self {
                VStack(alignment: .leading, spacing: 12) {
                    actions()
                        .environment(\.accountPageExit, ownExit)
                }
                .frame(maxWidth: columnWidth, alignment: .leading)
                .padding(sides.insets)
                .padding(.vertical, 20)
                .frame(maxWidth: .infinity)
            }
        }
        .background(inPanel ? Color.clear : Palette.background)
    }

    /// In a tvOS `TrailingPanel`, on its surface.
    private var inPanel: Bool {
        #if os(tvOS)
        inSheet
        #else
        false
        #endif
    }

    /// A phone's column: narrow enough to read at a glance on any window, a room's width on TV.
    private var columnWidth: CGFloat {
        switch formFactor {
        case .compact: 480
        case .regular: 440
        case .tv: 880
        }
    }
}

extension AccountPage where Actions == EmptyView {
    /// A page whose buttons, if any, are part of `content`.
    init(
        title: String,
        intro: String? = nil,
        onBack: (() -> Void)? = nil,
        backId: String = "account.back",
        exit: PageExit? = nil,
        holdsFocus: Bool = false,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.init(title: title, intro: intro, onBack: onBack, backId: backId, exit: exit, holdsFocus: holdsFocus, content: content) {
            EmptyView()
        }
    }
}

extension EnvironmentValues {
    /// The account page's `exit` and what it runs, where the page has no back control to carry it (a Mac sheet):
    /// `PageDismissButton` shows it.
    @Entry var accountPageExit: (exit: PageExit, action: () -> Void)? = nil
}

/// How an account page's way out reads where it closes a sheet (`AccountPage.exit`): Cancel, which drops what was
/// entered or stops what runs, or Close (`closes`), which loses nothing. `id` is its accessibility identifier.
struct PageExit {
    let title: String
    let id: String
    var closes = false
}

/// The account page's `exit` as a button of its own, shown only where the page has no back control to carry it (a Mac
/// sheet), so a page never has two; Escape presses it.
struct PageDismissButton: View {
    var prominence: ButtonProminence = .plain

    @Environment(\.accountPageExit) private var exit

    var body: some View {
        if let exit {
            ActionButton(title: exit.exit.title, id: exit.exit.id, prominence: prominence, action: exit.action)
                #if !os(tvOS)
                .keyboardShortcut(.cancelAction)
                #endif
        }
    }
}

extension View {
    /// The remote's Back (tvOS Menu) and the Mac's Escape run `action` while this view is on screen; nil leaves them
    /// alone. iOS has neither: there Escape presses the page's or sheet's visible Back, Cancel or Close
    /// (`keyboardShortcut(.cancelAction)`), which works without focus, where a key press (`onKeyPress`) needs it.
    @ViewBuilder
    func backAction(_ action: (() -> Void)?) -> some View {
        #if os(iOS)
        self
        #else
        if let action {
            onExitCommand(perform: action)
        } else {
            self
        }
        #endif
    }

    /// A focus section where the platform has them (tvOS, macOS).
    func focusSectionIfAvailable() -> some View {
        shelfFocusSection()
    }
}

/// A notice at the top of a screen (why this device was signed out), with a dismiss button: a `SettingsNotice`, its
/// icon at full strength, on a card.
struct NoticeCard: View {
    let text: String
    let onDismiss: () -> Void

    var body: some View {
        SettingsNotice(text: text, iconStyle: .primary, onDismiss: onDismiss)
            .padding(16)
            .background(Palette.surfaceContainer, in: RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
    }
}

/// A short code to read out or type elsewhere (`ABCD-EFGH`), large and monospaced, on one line (smaller where the
/// width beside a QR code is short, as on the Mac). `id` is its accessibility identifier, the Compose UI's probe
/// name for the same code (`link.code`).
struct CodeText: View {
    let code: String
    let id: String

    var body: some View {
        Text(code)
            .font(.system(.largeTitle, design: .monospaced).weight(.semibold))
            .tracking(4)
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            #if !os(tvOS)
            .textSelection(.enabled)
            #endif
            .accessibilityIdentifier(id)
    }
}

/// A code beside its QR code on wide layouts, above it on phones; `codeId` is the code's accessibility identifier and
/// `qrId` the QR code's, where it has one.
struct CodeAndQR: View {
    let code: String?
    let codeId: String
    let qrPayload: String?
    let qrDescription: String
    var qrId = ""

    @Environment(\.formFactor) private var formFactor
    @Environment(\.tortugaDimens) private var dimens

    var body: some View {
        let layout = formFactor == .compact
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 16))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 32))
        layout {
            if let qrPayload {
                QRCodeImage(payload: qrPayload, description: qrDescription, size: dimens.qrSize)
                    .accessibilityIdentifier(qrId)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
            }
            if let code { CodeText(code: code, id: codeId) }
        }
    }
}

/// The new device's side of a device link: the code and its QR code while waiting, the countdown and status line,
/// and "Get a new code" after a decline, an expiry or a failure. The page's back control leaves it.
struct DeviceLinkPanel: View {
    let link: DeviceLinkUiState
    let onRestart: () -> Void

    var body: some View {
        Text(T.shared.account.link.instructions)
            .fixedSize(horizontal: false, vertical: true)
        switch onEnum(of: link) {
        case .starting(let starting):
            ProgressLine(text: starting.progress)
        case .waiting(let waiting):
            CodeAndQR(code: waiting.code, codeId: "link.code", qrPayload: waiting.qrPayload, qrDescription: T.shared.account.link.qrDescription)
            Text(waiting.expiresText).foregroundStyle(Palette.onSurfaceVariant)
            ProgressLine(text: waiting.progress)
        case .failed(let failed):
            ErrorLine(text: failed.error)
            ActionButton(title: T.shared.account.link.newCode, id: "link.restart", prominence: .primary, systemImage: "arrow.clockwise", action: onRestart)
        }
    }
}

/// A TV's device-flow screen: where to go and the code to enter there, its QR code, the countdown, and the page's
/// `exit` where the page has no back control (`PageDismissButton`).
struct DeviceCodePanel: View {
    let code: DeviceCodeUiState
    let progress: String

    var body: some View {
        Text(code.instructions)
        CodeAndQR(code: code.userCode, codeId: "deviceCode.code", qrPayload: code.qrPayload, qrDescription: T.shared.account.signIn.deviceCode.qrDescription)
        Text(code.scanHint).foregroundStyle(Palette.onSurfaceVariant)
        Text(code.expiresText).foregroundStyle(Palette.onSurfaceVariant)
        ProgressLine(text: progress)
        PageDismissButton(prominence: .secondary)
    }
}

/// The system pasteboard (iOS, macOS; tvOS has none).
enum Pasteboard {
    /// How long a sensitive copy stays on an iOS pasteboard.
    private static let sensitiveLifetime: TimeInterval = 120

    /// Copies `text`; `sensitive` (a secret, such as an addon link's key): iOS keeps it on this device (no Universal
    /// Clipboard) for two minutes, macOS marks it concealed so clipboard managers skip it (nspasteboard.org).
    @MainActor
    static func copy(_ text: String, sensitive: Bool = false) {
        #if os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
        if sensitive {
            NSPasteboard.general.setString("", forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
        }
        #elseif os(iOS)
        if sensitive {
            UIPasteboard.general.setItems(
                [["public.utf8-plain-text": text]],
                options: [.localOnly: true, .expirationDate: Date().addingTimeInterval(sensitiveLifetime)]
            )
        } else {
            UIPasteboard.general.string = text
        }
        #endif
    }
}
