import SwiftUI
import TortugaKit

/// "Add a sign-in method" over the app of `app` (`AppOverlay.linkIdentity`); its store lives while it shows, and
/// linking or leaving closes the overlay (the shared `AppSession` is its host).
struct IdentityLinkRoute: View {
    let app: AppModel

    var body: some View {
        LiveStore(
            create: { app.session.identityLinkStore() },
            state: { $0.state },
            release: { $0.release() }
        ) { store, state in
            IdentityLinkScreen(state: state, store: store)
        }
    }
}

/// Linking another provider to the account: the providers not linked yet (or why there are none), a running flow's
/// progress or device code, and the last attempt's error. Its sheet's way out is Close while the providers are offered
/// and Cancel while a flow runs (`exit`: the bar's cancellation slot on iOS, the page's own button on the Mac; Escape
/// presses it). Every text comes from `state` or `T`. On tvOS the page is a `TrailingPanel` over the app, and the
/// screen under it keeps focus until it is moved: the first provider (or Retry) takes it once listed.
struct IdentityLinkScreen: View {
    let state: IdentityLinkUiState
    let store: IdentityLinkStore

    #if os(tvOS)
    /// The focused choice: a provider, or `retry`.
    @FocusState private var focused: String?
    #endif

    var body: some View {
        let identityLink = T.shared.account.identityLink
        AccountPage(
            title: identityLink.title,
            intro: identityLink.intro,
            onBack: working ? store.cancel : store.close,
            backId: "identityLink.back",
            exit: working
                ? PageExit(title: T.shared.account.signIn.cancel, id: "identityLink.cancel")
                : PageExit(title: T.shared.nav.close, id: "identityLink.close", closes: true),
            holdsFocus: firstChoice == nil
        ) {
            switch onEnum(of: state.step) {
            case .choose(let choose):
                switch onEnum(of: choose.options) {
                case .loading:
                    ProgressLine(text: nil)
                case .failed(let failed):
                    Text(failed.text).foregroundStyle(Palette.onSurfaceVariant)
                    ActionButton(title: T.shared.nav.retry, id: "identityLink.retry", systemImage: "arrow.clockwise", fullWidth: false, action: store.retry)
                        #if os(tvOS)
                        .focused($focused, equals: "retry")
                        #endif
                case .empty(let empty):
                    Text(empty.text).foregroundStyle(Palette.onSurfaceVariant)
                case .buttons(let buttons):
                    ForEach(Array(buttons.options.enumerated()), id: \.element.provider) { index, option in
                        ActionButton(
                            title: option.label,
                            id: "identityLink.option.\(option.provider)",
                            prominence: index == 0 ? .primary : .secondary,
                            icon: ProviderIcon(provider: option.provider, fallbackSymbol: "person.crop.circle.badge.plus")
                        ) {
                            store.link(provider: option.provider)
                        }
                        #if os(tvOS)
                        .focused($focused, equals: option.provider)
                        #endif
                    }
                }
                if let error = choose.error { ErrorLine(text: error) }
                PageDismissButton()
            case .working(let working):
                ProgressLine(text: working.progress)
                PageDismissButton(prominence: .secondary)
            case .deviceCode(let deviceCode):
                DeviceCodePanel(code: deviceCode.code, progress: deviceCode.progress)
            }
        }
        #if os(tvOS)
        .task(id: firstChoice) { await FocusRestore.restore($focused, to: firstChoice) }
        #endif
    }

    /// A provider flow runs (its progress or device code shows).
    private var working: Bool {
        switch onEnum(of: state.step) {
        case .choose: false
        case .working, .deviceCode: true
        }
    }

    /// What takes focus while the providers are offered: the first one, else Retry; nil while a flow runs or nothing
    /// is offered (the page holds focus then).
    private var firstChoice: String? {
        switch onEnum(of: state.step) {
        case .choose(let choose):
            switch onEnum(of: choose.options) {
            case .buttons(let buttons): buttons.options.first?.provider
            case .failed: "retry"
            case .loading, .empty: nil
            }
        case .working, .deviceCode: nil
        }
    }
}
