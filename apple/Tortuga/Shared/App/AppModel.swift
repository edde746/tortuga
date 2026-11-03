import Foundation
import Observation
import TortugaKit

/// The running profile's app for SwiftUI: the shared `AppSession` (`session`: its navigator, playbacks, overlay and
/// screens' stores), with its back stacks, state and account gate's state mirrored for the views and a `PlayerModel` for
/// the current playback. The account shell makes one per session (`RootModel.model(for:)`); a `--dev` launch makes one
/// for its session. The shell's view runs `stacks.observe()`, `state.observe()` and `gateState?.observe()`.
@MainActor
@Observable
final class AppModel {
    let session: AppSession
    /// The navigator's back stacks, one per destination, and the one shown.
    let stacks: StoreObserver<NavigationStacks>
    /// The playback and the overlay.
    let state: StoreObserver<AppSessionState>
    /// The account gate's state, for the sync status; nil in a development session.
    let gateState: StoreObserver<AccountGateUiState>?

    @ObservationIgnored private var player: PlayerModel?

    init(_ session: AppSession) {
        self.session = session
        stacks = StoreObserver(session.navigator.stacks)
        state = StoreObserver(session.state)
        gateState = session.gateState.map { StoreObserver($0) }
    }

    var graph: AppGraph { session.graph }
    var profileId: String { session.profileId }
    var navigator: TortugaNavigator { session.navigator }

    /// The shown destination's back stack, bottom to top.
    var backStack: [any Route] { stacks.state.current }

    /// `route` is the top of the stack it is on (`NavigationStacks.stackOf`): nothing over it, whether or not its
    /// destination shows, so a destination shown again isn't a screen come back to.
    func isTop(_ route: any Route) -> Bool {
        stacks.state.stackOf(route: route)?.last.map { ($0 as AnyObject).isEqual(route) } ?? false
    }

    /// What covers the app's screens: the profile editor, adding a sign-in method, a link to open on a phone; nil for
    /// none.
    var overlay: (any AppOverlay)? { state.state.overlay }

    /// The player's model of the current playback, the same one for as long as that playback lasts; nil for none, the
    /// ended playback's model let go of (its video surface, engine and Now Playing with it).
    var playback: PlayerModel? {
        guard let active = state.state.playback else {
            player = nil
            return nil
        }
        if let player, player.playback === active { return player }
        let model = PlayerModel(active)
        player = model
        return model
    }
}

/// The player screen's view of the shared `ActivePlayback`: its store's state and timeline mirrored for SwiftUI, and the
/// Apple side of the playback (`ApplePlayback`: the video surface, Now Playing).
@MainActor
final class PlayerModel {
    let playback: ActivePlayback
    let state: StoreObserver<PlayerUiState>
    /// The store's timeline (`PlayerStore.timeline`), which moves several times a second as the video plays: only the
    /// views drawing it read it, and the controls observe it while they show it.
    let timeline: StoreObserver<TimelineState>
    /// The timeline again, for the video's position value (`player.video`), which an assistive technology reads: observed
    /// apart from the controls' (`PlayerScreen`), so two observations never mirror into one.
    let videoTimeline: StoreObserver<TimelineState>
    let apple: ApplePlayback

    init(_ playback: ActivePlayback) {
        self.playback = playback
        // AppleAppPlatform makes every playback's platform side.
        apple = playback.platform as! ApplePlayback
        state = StoreObserver(playback.store.state)
        timeline = StoreObserver(playback.store.timeline)
        videoTimeline = StoreObserver(playback.store.timeline)
    }

    var store: PlayerStore { playback.store }
    var config: PlayerConfig { playback.config }
    var surface: VideoSurface { apple.surface }
    var nowPlaying: NowPlayingCenter { apple.nowPlaying }
}

/// An overlay as a sheet's item.
struct OverlayItem: Identifiable {
    let overlay: any AppOverlay

    var id: String {
        switch onEnum(of: overlay) {
        case .editProfile(let editor): "editProfile:\(editor.profileId ?? "new")"
        case .linkIdentity: "linkIdentity"
        case .openOnPhone(let link): "openOnPhone:\(link.url)"
        case .parentPin: "parentPin"
        }
    }
}
