import SwiftUI
import TortugaKit

/// How a window is used, which decides its navigation and sizes: a phone (compact width), a regular-width window
/// (iPad, Mac), or a TV (10-foot viewing, focus engine).
enum FormFactor: Sendable {
    case compact
    case regular
    case tv

    #if os(iOS)
    /// An iPhone, or an iPad window as narrow as one, for `horizontalSizeClass` compact; otherwise regular.
    static func current(_ horizontalSizeClass: UserInterfaceSizeClass?) -> FormFactor {
        horizontalSizeClass == .compact ? .compact : .regular
    }
    #endif
}

/// Size tokens for the window at hand, Apple's own metrics made denser: phones tight, iPad and Mac a
/// step roomier, TV large. Screens read them from the environment (`@Environment(\.tortugaDimens)`); the app shell sets
/// them for the window's form factor.
struct TortugaDimens: Sendable {
    /// The horizontal inset of screen content, inside the safe area. Screens take it at each side as `screenSides`,
    /// which leaves it out beside iOS 27.1's vertical bar. On tvOS the safe area already keeps content 80 pt from the
    /// screen's edges, so screens add nothing to it.
    var screenPadding: CGFloat
    /// The gap between items in a row or list.
    var gutter: CGFloat
    /// The gap between sections (shelves, groups).
    var sectionSpacing: CGFloat
    /// A shelf's heading to its cards.
    var headingGap: CGFloat
    /// Room around a row of cards for what grows out of them: the tvOS card lift, the keyboard focus ring.
    var focusRoom: CGFloat
    /// The width of a portrait (2:3) poster; landscape and square posters derive from it.
    var posterWidth: CGFloat
    /// Lists and text columns stay this narrow on wide windows.
    var contentMaxWidth: CGFloat
    /// The QR code's width: larger on TV (read across a room), smaller on phones.
    var qrSize: CGFloat
    /// A PIN pad key's diameter.
    var pinKey: CGFloat
    /// A profile avatar on the picker.
    var avatar: CGFloat

    /// The width of a card in a row for `shape`: landscape cards are wider so rows keep a similar height.
    func cardWidth(_ shape: PosterShape) -> CGFloat {
        switch shape {
        case .poster: posterWidth
        case .square: posterWidth * 1.2
        case .landscape: posterWidth * 1.9
        }
    }

    /// A poster grid across `width`: as many columns of about the card width for `shape` as fit inside the page's
    /// `sides`, and the cell width that fills that exactly, so the grid starts and ends at the content edges.
    func grid(width: CGFloat, sides: ScreenSides, shape: PosterShape) -> (columns: Int, cell: CGFloat) {
        let available = max(width - sides.horizontal, 0)
        let columns = max(Int((available + gutter) / (cardWidth(shape) + gutter)), 1)
        let cell = max((available - CGFloat(columns - 1) * gutter) / CGFloat(columns), 0)
        return (columns, cell)
    }

    static func of(_ formFactor: FormFactor) -> TortugaDimens {
        switch formFactor {
        case .tv:
            // tvOS lays out 1920×1080 points inside an 80 pt safe area at the sides.
            TortugaDimens(
                screenPadding: 0, gutter: 40, sectionSpacing: 24, headingGap: 12, focusRoom: 24, posterWidth: 240,
                contentMaxWidth: 1400, qrSize: 420, pinKey: 110, avatar: 200
            )
        case .compact:
            TortugaDimens(
                screenPadding: 16, gutter: 8, sectionSpacing: 16, headingGap: 8, focusRoom: 4, posterWidth: 104,
                contentMaxWidth: 600, qrSize: 200, pinKey: 64, avatar: 96
            )
        case .regular:
            TortugaDimens(
                screenPadding: 24, gutter: 12, sectionSpacing: 20, headingGap: 8, focusRoom: 6, posterWidth: 132,
                contentMaxWidth: 960, qrSize: 232, pinKey: 64, avatar: 96
            )
        }
    }
}

extension EnvironmentValues {
    @Entry var tortugaDimens: TortugaDimens = .of(.regular)
    @Entry var formFactor: FormFactor = .regular

    /// The page's sides (`ScreenSides`): the form factor's screen padding, none at the vertical bar's side.
    var screenSides: ScreenSides {
        ScreenSides(padding: tortugaDimens.screenPadding, bar: verticalBarEdge)
    }
}

/// Where a page's content (its cards, chips, rows and headings) starts and ends, inside its safe area at each side: the
/// screen padding, but none at the side of iOS 27.1's vertical bar (`verticalBarEdge`). The system ends the safe area
/// beside the bar's column with the gap it leaves after the column, and its forms start their cards right there: on the
/// iPhone Duo's cover, 14 pt from the tabs, where Home, Discover, Library, Search, Addons, Downloads and Detail kept
/// 30 pt from them with the padding added (25 Oct). A side's safe area without the bar (a phone's sensor housing,
/// turned) is the housing's alone, and the forms start past it by their margin, as the padding does. A readable column
/// narrower than the page (`readableColumn`) has no bar beside it, so its content has the padding at both sides.
/// Screens read it as `@Environment(\.screenSides)` (`ScreenSidesReader` where they put the view in a readable column)
/// and take it as padding (`insets`) or out of their width (`horizontal`); a page that lines something up with a
/// form's cards rather than its own content takes its own padding by the same rule.
struct ScreenSides: Equatable {
    let leading: CGFloat
    let trailing: CGFloat

    /// `padding` at each side, none at the side of the vertical bar (`bar`, `verticalBarEdge`).
    init(padding: CGFloat, bar: HorizontalEdge?) {
        leading = bar == .leading ? 0 : padding
        trailing = bar == .trailing ? 0 : padding
    }

    /// Both sides: what they take from the page's width.
    var horizontal: CGFloat { leading + trailing }

    /// The sides as padding.
    var insets: EdgeInsets { EdgeInsets(top: 0, leading: leading, bottom: 0, trailing: trailing) }
}

/// Hands `content` the page's sides where it is (`screenSides`): put in a readable column, it gets the column's.
struct ScreenSidesReader<Content: View>: View {
    @ViewBuilder var content: (ScreenSides) -> Content

    @Environment(\.screenSides) private var sides

    var body: some View {
        content(sides)
    }
}

extension View {
    /// Sets `formFactor` and its `tortugaDimens` for everything below, from the window's size class.
    func formFactorEnvironment() -> some View {
        modifier(FormFactorEnvironment())
    }
}

private struct FormFactorEnvironment: ViewModifier {
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    func body(content: Content) -> some View {
        #if os(tvOS)
        let formFactor = FormFactor.tv
        #elseif os(macOS)
        let formFactor = FormFactor.regular
        #else
        let formFactor = FormFactor.current(horizontalSizeClass)
        #endif
        return content
            .environment(\.formFactor, formFactor)
            .environment(\.tortugaDimens, .of(formFactor))
    }
}

extension PosterShape {
    /// Width ÷ height of an addon's poster shape (stremio-core: poster 1:1.464, landscape 16:9, square 1:1).
    var aspectRatio: CGFloat {
        switch self {
        case .poster: 1 / 1.464
        case .square: 1
        case .landscape: 16 / 9
        }
    }
}
