import Observation
import TortugaKit

/// The product's account shell for SwiftUI: the shared `RootSession` (`root`: the account gate, and the app of the
/// running profile, created, kept and closed as the gate moves), with its screen and the gate's state mirrored for the
/// views, and an `AppModel` for the running app. `RootView` runs `screen.observe()` and `gateState.observe()`.
///
/// Built once per process from the product `AppBootstrap`; the gate restores the account and starts the sync engine.
@MainActor
@Observable
final class RootModel {
    let root: RootSession
    let signIn: AppleSignInPlatform
    /// The server the sign-in screen starts on: the hosted one, or a UI test's fake of it (`--dev-sign-in-server`).
    let signInServer: String
    /// What the shell shows.
    let screen: StoreObserver<any RootScreen>
    /// The gate's state: the sync status line and the sign-in notice.
    let gateState: StoreObserver<AccountGateUiState>
    /// The start the splash names the steps of (`RootSession.startup`).
    let startup: StoreObserver<StartupUiState>

    @ObservationIgnored private var app: AppModel?

    /// - Parameters:
    ///   - boot: a product bootstrap (`AppBootstrap.product`), which has an account and a sync engine.
    ///   - startup: the start the launch began before the bootstrap, which it was given.
    init(boot: AppBootstrap, account: SyncAccount, sync: SyncEngine, platform: AppleAppPlatform, signInServer: String?, startup: Startup) {
        let signIn = AppleSignInPlatform()
        let root = RootSession(graph: boot.graph, account: account, sync: sync, signIn: signIn, platform: platform, startup: startup)
        self.signIn = signIn
        self.signInServer = signInServer ?? SyncServers.shared.HOSTED
        self.root = root
        screen = StoreObserver(root.screen)
        gateState = StoreObserver(root.gateState)
        self.startup = StoreObserver(startup.state)
    }

    var graph: AppGraph { root.graph }
    var gate: AccountGate { root.gate }

    /// The model of the running app `session`, the same one for as long as the session runs.
    func model(for session: AppSession) -> AppModel {
        if let app, app.session === session { return app }
        let model = AppModel(session)
        app = model
        return model
    }
}
