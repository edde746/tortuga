import AuthenticationServices
import os
import OSLog
import TortugaKit

#if os(macOS)
import AppKit
#else
import UIKit
#endif

#if !os(tvOS)
import GoogleSignIn
#endif

/// Sign-in on iOS, iPadOS, macOS and tvOS: Sign in with Apple through AuthenticationServices on iPhone,
/// iPad and Apple TV, and Google through Google's SDK (GoogleSignIn-iOS) on iPhone, iPad and the Mac, as Tortuga's
/// iOS-type Google client (`GIDClientID` in the Info.plist). Apple TV doesn't link Google's SDK: Google reaches it by
/// its device flow, with Tortuga's TV client when the build has it (`BuildSignInConfig`, Tortuga's OAuth client IDs).
///
/// GitHub's web flow (the shared `GitHubWebSignIn`), and Sign in with Apple's web flow (`AppleWebSignIn`, which the
/// shared `ProviderFlows` offers on iPhone and iPad against a server other than the hosted one, and on the Mac always),
/// open the provider's page in the system's web authentication session (`openBrowser`): a sheet over the app's window
/// on iPhone and iPad, the default browser on the Mac. The server's return page ends the session by redirecting it to
/// `tortuga://signin` with the state and a one-time proof, which the session hands to the flow's redirect receiver
/// (`openRedirectReceiver`); the app then collects the result from the server once, and the flow's end closes the
/// session (`closeBrowser`). Apple TV has no browser: GitHub reaches it by its device flow. A provider's own browser
/// sign-in can't return to the app (`canReceiveRedirect`), so a server's OpenID Connect provider reaches every Apple
/// device through its device flow, where the server offers it (the shared `ProviderFlows` decides).
///
/// Sign in with Apple's sheet needs the `com.apple.developer.applesignin` entitlement: the iOS and tvOS apps carry it
/// (`Tortuga/Shared/Tortuga.entitlements`, signed with team G88U5B5783). The Mac app's signing doesn't (its Developer
/// ID profile has no Sign in with Apple capability), so on the Mac Apple signs in through the browser: Apple's web
/// flow with Tortuga's Services ID through the hosted server's return page, as on Android and desktop.
///
/// Google's SDK stores its session in the data protection keychain (GTMAppAuth's keychain store, on the Mac too) and
/// fails a sign-in whose session it can't store. On an ad-hoc signed Mac build, which that keychain refuses
/// (`errSecMissingEntitlement`, -34018), Google's page and its token exchange go through but the SDK then
/// ends with `GIDSignInError.keychain` and no ID token, so the sign-in screen shows Google as unavailable; a build
/// signed with the team's keychain access group (development-signed builds, releases) signs in. The simulators and
/// devices keep the keychain for ad-hoc builds.
@MainActor
final class AppleSignInPlatform: @preconcurrency SignInPlatform {
    #if os(tvOS)
    let isTv = true
    let canOpenBrowser = false
    let nativeGoogle = false
    #else
    let isTv = false
    let canOpenBrowser = true
    let nativeGoogle = true
    #endif
    #if os(macOS)
    let nativeApple = false
    let appleServicesId: String? = BuildSignInConfig.shared.config.appleServicesId
    #else
    let nativeApple = true
    let appleServicesId: String? = nil
    #endif
    /// False: a provider's own browser sign-in can't return here. OpenID Connect providers register only the loopback
    /// redirect, which the Apple apps don't listen on; the server's return page reaches the app through
    /// `openRedirectReceiver` all the same.
    let canReceiveRedirect = false
    let googleBrowserClient: OAuthClientConfig? = nil
    #if os(tvOS)
    let googleTvClient: OAuthClientConfig? = BuildSignInConfig.shared.config.googleTvClient
    #else
    let googleTvClient: OAuthClientConfig? = nil
    #endif

    #if !os(macOS)
    /// The running Sign in with Apple request; AuthenticationServices holds its delegate weakly.
    private var running: AppleSignIn?
    #endif

    #if !os(tvOS)
    /// The web authentication session `openBrowser` started, until `closeBrowser`.
    private var browser: BrowserSession?
    /// The receiver `openRedirectReceiver` opened, until its `close`: the next session's end goes to it.
    private var receiver: SessionRedirectReceiver?
    #endif

    /// Runs Sign in with Apple or Google's sign-in (SKIE exposes the Kotlin `suspend fun nativeSignIn` as this async
    /// requirement).
    nonisolated func __nativeSignIn(provider: String, nonce: String) async throws -> ProviderCredential {
        let credential: UncheckedBox<ProviderCredential> = try await withCheckedThrowingContinuation { continuation in
            Task { @MainActor in
                self.signIn(provider: provider, nonce: nonce) { credential, error in
                    if let credential {
                        continuation.resume(returning: UncheckedBox(credential))
                    } else {
                        continuation.resume(throwing: error ?? Self.failure(AccountErrorProviderUnavailable.shared))
                    }
                }
            }
        }
        return credential.value
    }

    private func signIn(
        provider: String,
        nonce: String,
        completion: @escaping (ProviderCredential?, (any Error)?) -> Void
    ) {
        switch provider {
        #if !os(macOS)
        case IdentityProvider.shared.APPLE:
            running?.cancel()
            let signIn = AppleSignIn(nonce: nonce) { [weak self] credential, error in
                self?.running = nil
                completion(credential, error)
            }
            running = signIn
            signIn.start()
        #endif
        #if !os(tvOS)
        case IdentityProvider.shared.GOOGLE:
            GoogleSignInRequest(nonce: nonce, completion: completion).start()
        #endif
        default:
            completion(nil, Self.failure(AccountErrorProviderNotEnabled.shared))
        }
    }

    #if os(tvOS)
    /// Never called: `canOpenBrowser` is false, so the web flows go by their device flows here.
    func openBrowser(url: String) {
        ApplePlatform.openURL(url)
    }

    /// Nothing to close: `openBrowser` is never called here.
    func closeBrowser() {}

    /// Never called: `canOpenBrowser` is false, so no web flow runs here. The receiver it returns fails at once, as the
    /// provider not being available on this device.
    func openRedirectReceiver() -> any RedirectReceiver {
        UnavailableRedirectReceiver()
    }
    #else
    /// Shows `url` (GitHub's or Apple's authorize page) in a web authentication session: a sheet over the app's window
    /// on iPhone and iPad, a tab of the default browser on the Mac (macOS 27). It shares the browser's cookies
    /// (not ephemeral), so a person signed in to the provider there isn't asked again; the system first asks whether
    /// Tortuga may use the site to sign in. The session waits for the scheme `tortuga`: the server's return page
    /// redirects it to `tortuga://signin` with the state and a one-time proof (or the provider's error), and the
    /// session's end goes to the receiver `openRedirectReceiver` opened, whose wait returns that query. A sheet or tab
    /// the person closes ends the wait as a cancelled sign-in; a URL that doesn't parse or a session that doesn't
    /// start, as the provider being unavailable.
    func openBrowser(url: String) {
        browser?.cancel()
        browser = nil
        let receiver = self.receiver
        guard let address = URL(string: url) else {
            Logger(subsystem: "com.edde746.tortuga", category: "account").error("openBrowser: not a URL")
            receiver?.browserEnded(callback: nil, error: nil)
            return
        }
        let session = BrowserSession(url: address) { callback, error in
            receiver?.browserEnded(callback: callback, error: error)
        }
        browser = session
        session.start()
    }

    /// Takes down the sheet or tab `openBrowser` opened, if it is still open; a wait for its redirect ends as a
    /// cancelled sign-in.
    func closeBrowser() {
        browser?.cancel()
        browser = nil
    }

    /// A receiver for the redirect to `tortuga://signin` (`SyncPaths.HANDOFF_APP_RETURN`) that ends the next web
    /// authentication session (`openBrowser`). It replaces a receiver still open, whose wait ends as a cancelled
    /// sign-in; its `close` drops it.
    func openRedirectReceiver() -> any RedirectReceiver {
        receiver?.close()
        let opened = SessionRedirectReceiver { closed in
            Task { @MainActor in
                if self.receiver === closed { self.receiver = nil }
            }
        }
        receiver = opened
        return opened
    }
    #endif

    /// A Kotlin `AccountException` for `error`, as an `NSError` the Kotlin side turns back into the exception.
    nonisolated fileprivate static func failure(_ error: any AccountError) -> any Error {
        AccountException(error: error, cause: nil).asError()
    }

    /// The app's key window, which the system's sign-in sheets are presented over.
    fileprivate static func anchor() -> ASPresentationAnchor {
        #if os(macOS)
        NSApp.keyWindow ?? NSApp.windows.first ?? NSWindow()
        #else
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first ?? UIWindow()
        #endif
    }
}

#if !os(tvOS)
/// One web authentication session (`AppleSignInPlatform.openBrowser`): `ASWebAuthenticationSession`, not ephemeral,
/// waiting for the callback scheme `tortuga`. It ends at the return page's redirect to `tortuga://signin`, when the
/// person closes its sheet or tab, or when `cancel` takes it down; `ended` gets the callback URL or the error (a
/// cancelled sign-in for `cancel`, neither for a session that didn't start). Its end is logged by the error's domain
/// and code, never with the URL.
@MainActor
private final class BrowserSession: NSObject, ASWebAuthenticationPresentationContextProviding {
    private let ended: @Sendable (URL?, (any Error)?) -> Void
    private var session: ASWebAuthenticationSession?

    init(url: URL, ended: @escaping @Sendable (URL?, (any Error)?) -> Void) {
        self.ended = ended
        super.init()
        // Sendable, so not the main actor's: the system may call it on another queue.
        session = ASWebAuthenticationSession(url: url, callbackURLScheme: "tortuga") { @Sendable callback, error in
            let reason = error.map { "\(($0 as NSError).domain) \(($0 as NSError).code)" } ?? "returned to the app"
            Logger(subsystem: "com.edde746.tortuga", category: "account")
                .notice("web authentication session ended: \(reason, privacy: .public)")
            ended(callback, error)
        }
        session?.prefersEphemeralWebBrowserSession = false
        session?.presentationContextProvider = self
    }

    func start() {
        if session?.start() != true {
            Logger(subsystem: "com.edde746.tortuga", category: "account").error("the web authentication session didn't start")
            ended(nil, nil)
        }
    }

    func cancel() {
        session?.cancel()
        session = nil
        ended(nil, ASWebAuthenticationSessionError(.canceledLogin))
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated { AppleSignInPlatform.anchor() }
    }
}

/// The redirect receiver of iPhone, iPad and the Mac (`AppleSignInPlatform.openRedirectReceiver`): the end of the web
/// authentication session `openBrowser` starts while it is open. The callback to `tortuga://signin` gives the wait its
/// query, percent-decoded, when the query's `state` is the awaited one; another state is `AccountError.SignInRejected`
/// (the session has ended, so no other redirect follows). A closed sheet or tab, `closeBrowser` and `close` are
/// `AccountError.SignInCancelled`; any other end `AccountError.ProviderUnavailable`. The end may come before the wait
/// or after it; the wait gets the first one, once. Any thread: the Kotlin flow waits off the main actor.
private final class SessionRedirectReceiver: RedirectReceiver, Sendable {
    private enum Phase: Sendable {
        /// Neither the session's end nor the wait yet.
        case open
        /// `awaitRedirect` waits for `state`.
        case waiting(state: String, continuation: CheckedContinuation<[String: String], any Error>)
        /// The session ended before the wait.
        case ended(callback: URL?, error: (any Error)?)
        /// The wait got its end, or the receiver closed.
        case done
    }

    let redirectUri = SyncPaths.shared.HANDOFF_APP_RETURN
    private let phase = OSAllocatedUnfairLock(initialState: Phase.open)
    private let onClose: @Sendable (SessionRedirectReceiver) -> Void

    init(onClose: @escaping @Sendable (SessionRedirectReceiver) -> Void) {
        self.onClose = onClose
    }

    /// Waits for the session's end (SKIE exposes the Kotlin `suspend fun awaitRedirect` as this async requirement). A
    /// second wait, or one after `close`, fails as a cancelled sign-in.
    func __awaitRedirect(state: String) async throws -> [String: String] {
        try await withCheckedThrowingContinuation { continuation in
            let now: Result<[String: String], any Error>? = phase.withLock { phase in
                switch phase {
                case .open:
                    phase = .waiting(state: state, continuation: continuation)
                    return nil
                case .ended(let callback, let error):
                    phase = .done
                    return Self.redirect(callback, error, awaiting: state)
                case .waiting, .done:
                    return .failure(AppleSignInPlatform.failure(AccountErrorSignInCancelled.shared))
                }
            }
            if let now { continuation.resume(with: now) }
        }
    }

    /// The session ended: with the callback to `tortuga://signin`, or with `error` (neither: it didn't start). Only the
    /// first end counts.
    func browserEnded(callback: URL?, error: (any Error)?) {
        let resume: (continuation: CheckedContinuation<[String: String], any Error>, result: Result<[String: String], any Error>)? =
            phase.withLock { phase in
                switch phase {
                case .open:
                    phase = .ended(callback: callback, error: error)
                    return nil
                case .waiting(let state, let continuation):
                    phase = .done
                    return (continuation, Self.redirect(callback, error, awaiting: state))
                case .ended, .done:
                    return nil
                }
            }
        if let resume { resume.continuation.resume(with: resume.result) }
    }

    /// Ends a running wait as a cancelled sign-in, drops a later end, and drops the receiver from the platform.
    func close() {
        let waiting: CheckedContinuation<[String: String], any Error>? = phase.withLock { phase in
            defer { phase = .done }
            if case .waiting(_, let continuation) = phase { return continuation }
            return nil
        }
        waiting?.resume(throwing: AppleSignInPlatform.failure(AccountErrorSignInCancelled.shared))
        onClose(self)
    }

    /// What a wait for `state` gets from the session's end: the callback's query when its `SyncPaths.RETURN_STATE` is
    /// `state`.
    private static func redirect(_ callback: URL?, _ error: (any Error)?, awaiting state: String) -> Result<[String: String], any Error> {
        guard let callback else {
            let cancelled = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
            let reason: any AccountError = cancelled ? AccountErrorSignInCancelled.shared : AccountErrorProviderUnavailable.shared
            return .failure(AppleSignInPlatform.failure(reason))
        }
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let query = Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { first, _ in first })
        guard query[SyncPaths.shared.RETURN_STATE] == state else {
            return .failure(AppleSignInPlatform.failure(AccountErrorSignInRejected.shared))
        }
        return .success(query)
    }
}
#endif

#if !os(macOS)
/// One Sign in with Apple request: the system sheet, then the identity token and authorization code the server
/// verifies, with the nonce passed unchanged (`SignInPlatform.nativeSignIn`). A closed sheet is
/// `AccountError.SignInCancelled`; any other failure `AccountError.ProviderUnavailable`.
@MainActor
private final class AppleSignIn: NSObject, ASAuthorizationControllerDelegate,
    ASAuthorizationControllerPresentationContextProviding {
    private let nonce: String
    private var completion: ((ProviderCredential?, (any Error)?) -> Void)?
    private var controller: ASAuthorizationController?

    init(nonce: String, completion: @escaping (ProviderCredential?, (any Error)?) -> Void) {
        self.nonce = nonce
        self.completion = completion
    }

    func start() {
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.nonce = nonce
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        self.controller = controller
        controller.performRequests()
    }

    func cancel() {
        if #available(tvOS 18.0, *) { controller?.cancel() }
        finish(nil, AppleSignInPlatform.failure(AccountErrorSignInCancelled.shared))
    }

    nonisolated func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        let credential = authorization.credential as? ASAuthorizationAppleIDCredential
        let token = credential?.identityToken.flatMap { String(data: $0, encoding: .utf8) }
        let code = credential?.authorizationCode.flatMap { String(data: $0, encoding: .utf8) }
        MainActor.assumeIsolated {
            guard let token else {
                return finish(nil, AppleSignInPlatform.failure(AccountErrorProviderUnavailable.shared))
            }
            let result = ProviderCredential(
                provider: IdentityProvider.shared.APPLE,
                idToken: token,
                nonce: nonce,
                authorizationCode: code
            )
            finish(result, nil)
        }
    }

    nonisolated func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: any Error) {
        let cancelled = (error as? ASAuthorizationError)?.code == .canceled
        let description = error.localizedDescription
        MainActor.assumeIsolated {
            Logger(subsystem: "com.edde746.tortuga", category: "account")
                .notice("Sign in with Apple ended: \(description, privacy: .public)")
            let reason: any AccountError = cancelled ? AccountErrorSignInCancelled.shared : AccountErrorProviderUnavailable.shared
            finish(nil, AppleSignInPlatform.failure(reason))
        }
    }

    nonisolated func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        MainActor.assumeIsolated { AppleSignInPlatform.anchor() }
    }

    private func finish(_ credential: ProviderCredential?, _ error: (any Error)?) {
        guard let completion else { return }
        self.completion = nil
        controller = nil
        completion(credential, error)
    }
}
#endif

#if !os(tvOS)
extension AppleSignInPlatform {
    /// Gives `url`, which the app was asked to open, to Google's SDK: true when it was the return of a Google sign-in
    /// (the reversed client ID's scheme in the Info.plist), which the SDK then finishes.
    static func handleOpenURL(_ url: URL) -> Bool {
        GIDSignIn.sharedInstance.handle(url)
    }
}

/// One Google sign-in through Google's SDK: Google's sign-in page in the system's web authentication sheet over the
/// app's window, then the ID token the server verifies, with the nonce passed unchanged (`SignInPlatform.nativeSignIn`).
/// Only the token is kept: the SDK's own session is signed out right away, so no Google session stays in the app. A
/// closed sheet is `AccountError.SignInCancelled`; any other failure `AccountError.ProviderUnavailable`, logged by its
/// error domain and code.
@MainActor
private final class GoogleSignInRequest {
    private let nonce: String
    private let completion: (ProviderCredential?, (any Error)?) -> Void

    init(nonce: String, completion: @escaping (ProviderCredential?, (any Error)?) -> Void) {
        self.nonce = nonce
        self.completion = completion
    }

    func start() {
        guard let presenter = Self.presenter() else {
            return finish(token: nil, cancelled: false, reason: "no window to present on")
        }
        GIDSignIn.sharedInstance.signIn(withPresenting: presenter, hint: nil, additionalScopes: nil, nonce: nonce) { result, error in
            let token = result?.user.idToken?.tokenString
            let cancelled = (error as? GIDSignInError)?.code == .canceled
            let reason = error.map { "\(($0 as NSError).domain) \(($0 as NSError).code)" } ?? "no ID token"
            MainActor.assumeIsolated {
                self.finish(token: token, cancelled: cancelled, reason: reason)
            }
        }
    }

    private func finish(token: String?, cancelled: Bool, reason: String) {
        GIDSignIn.sharedInstance.signOut()
        guard let token else {
            Logger(subsystem: "com.edde746.tortuga", category: "account")
                .notice("Google sign-in ended: \(reason, privacy: .public)")
            let error: any AccountError = cancelled ? AccountErrorSignInCancelled.shared : AccountErrorProviderUnavailable.shared
            return completion(nil, AppleSignInPlatform.failure(error))
        }
        let credential = ProviderCredential(
            provider: IdentityProvider.shared.GOOGLE,
            idToken: token,
            nonce: nonce,
            authorizationCode: nil
        )
        completion(credential, nil)
    }

    #if os(macOS)
    /// The app's window, which the sheet is presented over.
    private static func presenter() -> NSWindow? {
        NSApp.keyWindow ?? NSApp.mainWindow ?? NSApp.windows.first(where: \.isVisible)
    }
    #else
    /// The view controller on top in the app's key window: the sign-in screen's, or the one it presents.
    private static func presenter() -> UIViewController? {
        let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        return top
    }
    #endif
}
#endif

#if os(tvOS)
/// The redirect receiver of Apple TV, which has no browser (see `AppleSignInPlatform.openRedirectReceiver`).
private final class UnavailableRedirectReceiver: RedirectReceiver {
    let redirectUri = ""

    func __awaitRedirect(state: String) async throws -> [String: String] {
        throw AppleSignInPlatform.failure(AccountErrorProviderNotEnabled.shared)
    }

    func close() {}
}
#endif
