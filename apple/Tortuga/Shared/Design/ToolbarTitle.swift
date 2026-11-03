import SwiftUI

#if os(macOS)
extension View {
    /// While `hidden`, the window's toolbar leaves out the title (macOS 15 and later; before, it shows it), where the
    /// screen names itself (Detail, the Streams sheet's heading, a top-level destination's sidebar entry) and the window
    /// keeps the title for the Window menu. One modifier either way, so a screen keeps its state when `hidden` changes
    /// (a destination once a screen is pushed over it).
    @ViewBuilder
    func toolbarTitleHidden(_ hidden: Bool = true) -> some View {
        if #available(macOS 15, *) {
            toolbar(removing: hidden ? .title : nil)
        } else {
            self
        }
    }
}
#endif

extension View {
    /// The navigation title small in the bar's middle rather than large under it (iOS); elsewhere the view as it is.
    func inlineNavigationTitle() -> some View {
        #if os(iOS)
        navigationBarTitleDisplayMode(.inline)
        #else
        self
        #endif
    }

    /// `title` in the navigation bar of a page that shows the bar (a page pushed over another, a sheet's form), inline
    /// on iOS (`inlineNavigationTitle`). On tvOS the bar stays where it is while the page scrolls (tvOS 26.5), and where
    /// UIKit's own lists hide their rows above the bar's lower edge, fading them in below it, SwiftUI's draw them on up
    /// the screen, under the title: so the page shows nothing above that edge and fades in over the 24 points below
    /// it. The page's frame runs up under the bar; the mask keeps out of its top safe area, so it starts at the bar's
    /// edge, and runs on past the bottom and side safe areas, where rows still show.
    func pageTitle(_ title: String) -> some View {
        #if os(tvOS)
        navigationTitle(title)
            .mask {
                VStack(spacing: 0) {
                    LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom).frame(height: 24)
                    Color.black
                }
                .ignoresSafeArea(edges: [.horizontal, .bottom])
            }
        #else
        navigationTitle(title)
            .inlineNavigationTitle()
        #endif
    }
}
