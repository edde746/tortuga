import SwiftUI
import TortugaKit

/// The product app: the account shell of `root`, its screen chosen by the account gate. Screens switch without a
/// cross-fade: an app closed by a sign-out or a profile switch must not stay on screen over released stores.
struct RootView: View {
    let root: RootModel

    var body: some View {
        let screen = onEnum(of: root.screen.state)
        let shell = if case .main = screen { true } else { false }
        Group {
            switch screen {
            case .starting:
                StartingView(startup: root.startup)
            case .signIn:
                SignInRoute(root: root)
            case .profiles:
                ProfilesRoute(root: root)
            case .main(let main):
                let app = root.model(for: main.app)
                AppShell(app: app)
                    .id(ObjectIdentifier(app))
            }
        }
        // Before the shell the vertical bar would hold the status bar alone.
        .verticalBarDisabled(!shell)
        .task { await root.screen.observe() }
        .task { await root.gateState.observe() }
    }
}

/// The splash, while the app starts: the loader in the middle of the screen, whatever its safe areas (the status bar at
/// the top of the iPhone Duo, whose vertical bar is off before the shell, put it 53 pt low) and, under it, the step the
/// start is on (`StartupUiState.status`: none until the start has taken half a second). The loader stays where it is
/// when the line comes. `startup` is nil before the launch has read its options, and for a `--dev` session.
struct StartingView: View {
    let startup: StoreObserver<StartupUiState>?

    var body: some View {
        ZStack {
            ProgressView()
            if let status = startup?.state.status {
                // The lower half of the screen, the line at its top just under the loader.
                VStack(spacing: 0) {
                    Color.clear
                    Text(status)
                        .foregroundStyle(Palette.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                        .accessibilityIdentifier("startup.status")
                        .padding(.horizontal, 32)
                        .padding(.top, 48)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
        .background(Palette.background)
        .task { await startup?.observe() }
    }
}

extension View {
    /// Runs `action` whenever the app comes back to the foreground (from another app, the Home screen), and once on
    /// appear: the sign-in screen's link from another device then looks for its approval at once.
    func onForeground(_ action: @escaping () -> Void) -> some View {
        modifier(OnForeground(action: action))
    }
}

private struct OnForeground: ViewModifier {
    let action: () -> Void
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content.onChange(of: scenePhase, initial: true) { _, phase in
            if phase == .active { action() }
        }
    }
}
