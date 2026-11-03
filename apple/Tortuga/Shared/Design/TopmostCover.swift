import SwiftUI
import TortugaKit

#if os(tvOS)
import UIKit

extension View {
    /// Apple TV: `cover` of `item` over whatever shows while `item` is set: the screens, a page presented over them
    /// (Addons' install or details page) and the Streams panel included. A SwiftUI presentation can't come from a view
    /// that is already presenting (it would wait for that page to close), so this one is presented by the topmost view
    /// controller of the key window, with what the window's root gives the screens (`LaunchView`): a controller of its
    /// own inherits none of it. Another `item` redraws it in place; nil dismisses it, and `onGone` runs once it has gone.
    /// Asked for while a presentation comes or goes (the menu whose entry asked for it closing), it waits for that to end
    /// and for focus to be back, so focus goes back there when the cover goes.
    func topmostCover<Item: Equatable, Cover: View>(
        item: Item?,
        onGone: @escaping () -> Void = {},
        @ViewBuilder cover: @escaping (Item) -> Cover
    ) -> some View {
        modifier(TopmostCover(item: item, onGone: onGone, cover: cover))
    }
}

private struct TopmostCover<Item: Equatable, Cover: View>: ViewModifier {
    let item: Item?
    let onGone: () -> Void
    let cover: (Item) -> Cover

    @State private var presenter = TopmostPresenter<Cover>()

    func body(content: Content) -> some View {
        content.onChange(of: item, initial: true) {
            if let item {
                presenter.show(cover(item))
            } else {
                presenter.dismiss(then: onGone)
            }
        }
    }
}

@MainActor
private final class TopmostPresenter<Cover: View> {
    private weak var controller: UIHostingController<ThemedCover<Cover>>?
    /// Waiting for focus to come back before presenting.
    private var focusWait: (any NSObjectProtocol)?

    func show(_ cover: Cover) {
        let page = ThemedCover(content: cover)
        if let controller {
            controller.rootView = page
            return
        }
        let hosting = UIHostingController(rootView: page)
        hosting.modalPresentationStyle = .overFullScreen
        hosting.view.backgroundColor = .clear
        controller = hosting
        present(hosting)
    }

    /// Presents `hosting` over the topmost view controller once no presentation is coming or going and something has
    /// focus: the dismissal UIKit runs once the cover goes puts focus back on what had it as the cover came, and with
    /// nothing (focus still on its way back from a closed menu) it falls to the screen's first control.
    private func present(_ hosting: UIViewController) {
        guard controller === hosting else { return }
        let window = UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController { top = presented }
        if let transition = top?.transitionCoordinator {
            transition.animate(alongsideTransition: nil) { [weak self] _ in self?.present(hosting) }
            return
        }
        if window?.windowScene?.focusSystem?.focusedItem == nil {
            focusWait = NotificationCenter.default.addObserver(forName: UIFocusSystem.didUpdateNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    self.stopWaiting()
                    self.present(hosting)
                }
            }
            return
        }
        top?.present(hosting, animated: true)
    }

    private func stopWaiting() {
        guard let focusWait else { return }
        NotificationCenter.default.removeObserver(focusWait)
        self.focusWait = nil
    }

    /// Dismisses the cover, then runs `gone`; at once when it isn't presented (the system dismissed it, or it still
    /// waits to come).
    func dismiss(then gone: @escaping () -> Void) {
        stopWaiting()
        guard let controller, controller.presentingViewController != nil else {
            controller = nil
            return gone()
        }
        controller.dismiss(animated: true, completion: gone)
    }
}

private struct ThemedCover<Content: View>: View {
    let content: Content

    var body: some View {
        let tag = InterfaceLocale.shared.tag.value as String
        content
            .environment(\.locale, Locale(identifier: tag))
            .environment(\.layoutDirection, InterfaceLocale.shared.isRightToLeft(tag: tag) ? .rightToLeft : .leftToRight)
            .formFactorEnvironment()
            .tortugaTheme()
    }
}
#endif
