import SwiftUI
import TortugaKit

/// A hand-off to a player app under way or failed (`PlayerAppBanner`), as one line in a panel: a spinner while it is
/// under way, the line ("Opening in VLC…", "VLC isn't installed on this device."), Play in Tortuga after a failure and
/// Cancel or Dismiss.
///
/// Accessibility identifiers: `playerApp.banner`, `playerApp.banner.playInTortuga`, `playerApp.banner.dismiss`.
struct PlayerAppBannerView: View {
    let banner: PlayerAppBanner
    let onPlayInTortuga: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            if banner.busy {
                ProgressView()
                    #if !os(tvOS)
                    .controlSize(.small)
                    #endif
            }
            Text(banner.text)
                .font(.subheadline.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let playInTortuga = banner.playInTortugaText {
                ActionButton(title: playInTortuga, id: "playerApp.banner.playInTortuga", prominence: .primary, fullWidth: false, action: onPlayInTortuga)
            }
            ActionButton(title: banner.dismissText, id: "playerApp.banner.dismiss", fullWidth: false, action: onDismiss)
        }
        // The buttons' glass, one row of it.
        .glassGroup(spacing: 12)
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: Radius.large, style: .continuous))
        .frame(maxWidth: 720)
        .padding(.horizontal, 16)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("playerApp.banner")
    }
}

extension View {
    /// `app`'s hand-off to a player app (`AppSessionState.playerApp`) as a `PlayerAppBannerView` over the bottom of the
    /// view while there is one, sliding in and out (fading with Reduce Motion on): the screens' stack shows it
    /// (`RouteStack`), and so does the Streams sheet, which covers the screens while it is up. Its buttons are the
    /// session's `playInTortuga` and `dismissPlayerApp`; its line is announced once, by `AppShell`.
    func playerAppBanner(_ app: AppModel) -> some View {
        overlay(alignment: .bottom) { PlayerAppBannerHost(app: app) }
    }
}

/// The banner while `app`'s state has one; reading it here keeps its changes from drawing the view under it again.
private struct PlayerAppBannerHost: View {
    let app: AppModel

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let banner = app.state.state.playerApp
        ZStack {
            if let banner {
                PlayerAppBannerView(banner: banner, onPlayInTortuga: app.session.playInTortuga, onDismiss: app.session.dismissPlayerApp)
                    .padding(.bottom, 24)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeOut(duration: 0.2), value: banner)
    }
}
