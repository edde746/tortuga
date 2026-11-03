import SwiftUI

/// How much a button stands out. Every button is a native one: Liquid Glass on iOS, macOS and tvOS 26 and later
/// (`.glassProminent`, `.glass`), the bordered styles before.
enum ButtonProminence {
    /// The one main action of a screen or sheet: a white fill with black text (iOS, macOS). On tvOS focus is what
    /// stands out, so the main action is a glass (bordered) button in a heavier weight.
    case primary
    /// Other actions: a glass (bordered, a solid grey) fill.
    case secondary
    /// A text button (iOS, macOS): Cancel, "Use your own server". tvOS has no text buttons: a glass one.
    case plain
    /// Sign out, delete, reset: `secondary` in the system's destructive red.
    case destructive
}

extension View {
    /// The native button style for `prominence`.
    func buttonProminence(_ prominence: ButtonProminence) -> some View {
        modifier(ProminenceStyle(prominence: prominence))
    }

    /// `.glass` on 26 and later, `.bordered` before: a secondary action, a link pill, an icon button. The Mac fills a
    /// glass button with the tint, and the app's tint is white (`tortugaTheme`): there these drop it, so they stay
    /// neutral glass and don't look like the primary action. On iOS a glass button (not a `menu`) is disabled while a
    /// menu is open (`OpenItemMenu`), its label keeping its white: enabled, it took the taps of an item menu's entries
    /// drawn over it, whatever its hit testing (`allowsHitTesting`) or its glass (`.glass(.regular)`) said. A glass
    /// menu stays enabled: disabled while its own menu was open, it closed that menu (Library's view menu), and
    /// nothing tells which menu is open.
    @ViewBuilder
    func glassButtonStyle(menu: Bool = false) -> some View {
        if #available(iOS 26, macOS 26, tvOS 26, *) {
            #if os(macOS)
            buttonStyle(.glass).tint(Color?.none)
            #elseif os(iOS)
            buttonStyle(.glass).modifier(DisabledUnderOpenItemMenu(menu: menu))
            #else
            buttonStyle(.glass)
            #endif
        } else {
            buttonStyle(.bordered)
        }
    }

    /// A link pill (a genre, a cast member, Clear over Search's recent searches on tvOS): a glass (bordered) capsule,
    /// solid, never outlined.
    func pillButtonStyle() -> some View {
        glassButtonStyle().buttonBorderShape(.capsule)
    }

    /// A round glass control, a button or a menu (`IconButton`, Detail's download, trailers and ⋯, `FilterMenu`): the
    /// glass button style in `shape` (`.circle` or `.capsule`). The Mac draws the glass button style on a menu as a flat
    /// grey shape and gives a menu no border shape, so there menus and buttons alike are plain in Liquid Glass of that
    /// shape (the system's regular material before macOS 26; `glassCapsule` for a capsule), their symbol white in an
    /// active and an inactive window alike. iOS 26 sizes a button-styled menu as a capsule whatever its border shape
    /// (Detail's ⋯ was 58×48 pt around its 48 pt circle; iOS 27 sizes it as a circle), so on iOS a circle that is a
    /// `menu` is drawn as the Mac's are. Its label takes `roundGlassLabel(shape, menu:)`. A `menu` keeps taking touches
    /// while a menu is open (`glassButtonStyle`).
    @ViewBuilder
    func roundGlassControl(_ shape: ButtonBorderShape = .circle, menu: Bool = false) -> some View {
        #if os(macOS)
        if shape == .capsule {
            glassCapsule()
        } else {
            glassCircle()
        }
        #elseif os(iOS)
        if menu && shape == .circle {
            modifier(GlassShape(shape: Circle(), menu: true))
        } else {
            glassButtonStyle(menu: menu).buttonBorderShape(shape)
        }
        #else
        glassButtonStyle().buttonBorderShape(shape)
        #endif
    }

    /// The label of a `roundGlassControl` in `shape`. The Mac's pads itself into the glass, so the whole shape takes the
    /// click: a circle's symbol by `IconButton.circleInset`, a capsule's text as the toolbar's is
    /// (`glassCapsuleLabel`); on iOS a round `menu`'s symbol pads itself into its circle alike.
    @ViewBuilder
    func roundGlassLabel(_ shape: ButtonBorderShape = .circle, menu: Bool = false) -> some View {
        #if os(macOS)
        if shape == .capsule {
            glassCapsuleLabel()
        } else {
            padding(IconButton.circleInset).contentShape(Circle())
        }
        #elseif os(iOS)
        if menu && shape == .circle {
            padding(IconButton.circleInset).contentShape(Circle())
        } else {
            self
        }
        #else
        self
        #endif
    }

    /// Neighbouring glass (a row or column of glass buttons, capsules or cards: the header row's filters, Home's
    /// shortcuts, Streams' chips, Detail's actions on Apple TV, the player's buttons, a page's buttons) rendered
    /// together, on iOS and tvOS 26 and later, in a `GlassEffectContainer`: each shape samples the glass beside it rather
    /// than drawing over it alone. `spacing` is the closest two of its shapes sit at rest, so they blend only where a
    /// press swells one towards its neighbour, never at rest. On tvOS, where focus lifts a button towards its
    /// neighbours, they never blend. Glass drawn on glass (a card's buttons) takes a group of its own inside the card,
    /// never the card's: overlapping shapes in one group merge. On iOS a group never holds a glass menu beside glass
    /// that stops taking touches under an open menu (`OpenItemMenu`): there a tap on the menu's entry drawn over that
    /// glass did nothing, the menu staying open (iOS 27: ⋯'s Mark as watched over Detail's round buttons,
    /// `DetailTests.testActionRowFitsTheWidth` failed 4 of 4 with the row grouped), so Detail's row isn't grouped
    /// there and the header row's actions stay out of its filters' group. On the Mac each shape draws alone: macOS 27.2
    /// reports one control of a group with the whole group's accessibility frame (Library's view menu spanned the type
    /// and sort beside it, the rating sheet's Close spanned Remove rating), so what aims at that control's
    /// centre reaches its neighbour. Before 26 the view as it is.
    @ViewBuilder
    func glassGroup(spacing: CGFloat) -> some View {
        #if os(macOS)
        self
        #else
        if #available(iOS 26, tvOS 26, *) {
            #if os(tvOS)
            GlassEffectContainer(spacing: 0) { self }
            #else
            GlassEffectContainer(spacing: spacing) { self }
            #endif
        } else {
            self
        }
        #endif
    }

    /// The large control size where the platform has one (iOS, macOS): account and sheet buttons.
    func largeControl() -> some View {
        #if os(tvOS)
        self
        #else
        controlSize(.large)
        #endif
    }

    /// A button that is a whole form row (a link row, a language or a player to add, a recent room): the form's own row
    /// on iOS and tvOS; on the Mac, whose form draws a button as a push button, a plain one.
    func formRowButtonStyle() -> some View {
        #if os(macOS)
        buttonStyle(.plain)
        #else
        self
        #endif
    }
}

#if !os(tvOS)
extension View {
    /// A plain button or menu in the round glass control's glass (`roundGlassControl`: regular, interactive; the regular
    /// material before iOS and macOS 26) in a capsule. The Mac's header row's filter menus, field and groups of actions
    /// (`screenHeader`), as the toolbar's buttons are, their labels padding themselves from the capsule's ends
    /// (`glassCapsuleLabel`); Home's shortcuts on iOS 26 and later and the Streams list's chips on iOS and macOS 26 and
    /// later (`BoardShortcuts`, `StreamsBar`), at the app's chip size.
    func glassCapsule() -> some View {
        modifier(GlassShape(shape: Capsule()))
    }

    /// `glassCapsule`'s glass in a circle: a round glass control on the Mac (`roundGlassControl`), an icon alone among
    /// iPhone's header row actions (`screenHeader`), which pads itself as tall as the filters' capsules.
    func glassCircle() -> some View {
        modifier(GlassShape(shape: Circle()))
    }

    /// A round glass menu that shows that what it sets is on (the Streams list's sort while it sorts): the glass circle
    /// of `roundGlassControl(menu: true)`, in the primary fill (white glass, its label black) while `filled`. One view
    /// either way, so its menu, open while it fills, stays open. Its label pads itself into the circle.
    func roundGlassMenu(filled: Bool) -> some View {
        modifier(GlassShape(shape: Circle(), menu: true, filled: filled))
    }
}
#endif

#if os(macOS)
extension View {
    /// The label of a `glassCapsule` control: its text and symbol in the toolbar's font, inset from the capsule's
    /// rounded `ends`, the capsule's height. A lone control takes both ends; actions sharing one capsule (Downloads'
    /// Pause all and Resume all) take half the inset each side, the capsule the other half at its ends.
    func glassCapsuleLabel(ends: Edge.Set = .horizontal) -> some View {
        let inset = HeaderMetrics.capsuleInset
        return font(.body)
            .padding(.leading, ends.contains(.leading) ? inset : inset / 2)
            .padding(.trailing, ends.contains(.trailing) ? inset : inset / 2)
            .frame(minHeight: HeaderMetrics.controlHeight)
            .contentShape(Rectangle())
    }
}
#endif

#if !os(tvOS)
private struct GlassShape<S: Shape>: ViewModifier {
    let shape: S
    var menu = false
    /// The primary fill: white (glass tinted white), the label black.
    var filled = false

    @Environment(\.isEnabled) private var enabled

    func body(content: Content) -> some View {
        let styled = content
            .buttonStyle(.plain)
            .foregroundStyle(filled ? Palette.onPrimary : Palette.onBackground)
            // Disabled, the label fades; the glass stays.
            .opacity(enabled ? 1 : 0.45)
        if #available(iOS 26, macOS 26, *) {
            styled.glassEffect(glass, in: shape)
        } else {
            styled.background(filled ? AnyShapeStyle(Palette.primary) : AnyShapeStyle(.regularMaterial), in: shape)
        }
    }

    /// Regular and interactive; on iOS, while a menu is open (`OpenItemMenu`), regular alone, which looks the same at
    /// rest: interactive, it took the taps of an item menu's entries drawn over it. A `menu` stays interactive, as a
    /// glass menu stays enabled (`glassButtonStyle`). Tinted white while `filled`.
    @available(iOS 26, macOS 26, *)
    private var glass: Glass {
        #if os(iOS)
        let glass: Glass = OpenItemMenu.shared.isOpen && !menu ? .regular : .regular.interactive()
        #else
        let glass: Glass = .regular.interactive()
        #endif
        return filled ? glass.tint(Palette.primary) : glass
    }
}
#endif

#if os(iOS)
/// `glassButtonStyle`'s glass button, disabled while a menu is open (`OpenItemMenu`), but a `menu`. Its label stays in
/// the enabled label's white (`Palette.onBackground`) rather than the disabled grey, so nothing under the menu changes;
/// a button disabled on its own stays grey.
private struct DisabledUnderOpenItemMenu: ViewModifier {
    let menu: Bool

    @Environment(\.isEnabled) private var enabled

    func body(content: Content) -> some View {
        if !menu && enabled && OpenItemMenu.shared.isOpen {
            content.foregroundStyle(Palette.onBackground).disabled(true)
        } else {
            content
        }
    }
}
#endif

private struct ProminenceStyle: ViewModifier {
    let prominence: ButtonProminence

    @Environment(\.isEnabled) private var enabled

    func body(content: Content) -> some View {
        switch prominence {
        case .primary:
            #if os(tvOS)
            content.glassButtonStyle().fontWeight(.semibold)
            #else
            // Disabled, the system greys the fill: black text would vanish into it.
            prominent(content).foregroundStyle(enabled ? Palette.onPrimary : Palette.onSurfaceVariant)
            #endif
        case .secondary, .destructive:
            content.glassButtonStyle()
        case .plain:
            #if os(tvOS)
            content.glassButtonStyle()
            #else
            content.buttonStyle(.borderless)
            #endif
        }
    }

    #if !os(tvOS)
    @ViewBuilder
    private func prominent(_ content: Content) -> some View {
        if #available(iOS 26, macOS 26, *) {
            content.buttonStyle(.glassProminent)
        } else {
            content.buttonStyle(.borderedProminent)
        }
    }
    #endif
}

/// A native button with a title (and an icon: an SF Symbol, or a mark such as a sign-in provider's logo), full width or
/// content-wide (`fullWidth`), in `prominence`; its title on one line, at the accessibility text sizes on as many as it
/// takes. `id` is its accessibility identifier, the Compose UI's probe name for the same button
/// (`signIn.server.edit`). tvOS draws a disabled button just like an enabled one without focus, so there it fades.
struct ActionButton<Icon: View>: View {
    let title: String
    let id: String
    var prominence: ButtonProminence = .secondary
    var fullWidth = true
    var enabled = true
    let icon: Icon?
    let action: () -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Button(role: prominence == .destructive ? .destructive : nil, action: action) {
            Group {
                if let icon {
                    Label { Text(title) } icon: { icon }
                } else {
                    Text(title)
                }
            }
            .lineLimit(dynamicTypeSize.isAccessibilitySize ? nil : 1)
            .frame(maxWidth: fullWidth ? .infinity : nil)
        }
        .buttonProminence(prominence)
        .largeControl()
        .disabled(!enabled)
        #if os(tvOS)
        .opacity(enabled ? 1 : 0.45)
        #endif
        .accessibilityIdentifier(id)
    }
}

extension ActionButton where Icon == Image {
    init(
        title: String,
        id: String,
        prominence: ButtonProminence = .secondary,
        systemImage: String? = nil,
        fullWidth: Bool = true,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.init(
            title: title,
            id: id,
            prominence: prominence,
            fullWidth: fullWidth,
            enabled: enabled,
            icon: systemImage.map(Image.init(systemName:)),
            action: action
        )
    }
}

/// A round button with an SF Symbol and a spoken `label` (move up, remove, copy), on the Mac its tooltip too: a glass
/// (bordered) circle, as round menus are (`roundGlassControl`), so a row of round buttons and menus (Detail's actions)
/// shares one look. `symbolBox` sets the symbol's square, and with it the circle's size, where a row needs the circle as
/// tall as its other controls (the Mac's circles are small otherwise).
struct IconButton: View {
    let systemImage: String
    let label: String
    let id: String
    var enabled = true
    var symbolBox: CGFloat?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.body.weight(.semibold))
                .frame(width: symbolBox, height: symbolBox)
                .roundGlassLabel()
        }
        .roundGlassControl()
        .disabled(!enabled)
        .accessibilityLabel(label)
        #if os(macOS)
        .tooltip(label)
        #endif
        .accessibilityIdentifier(id)
    }

    #if os(macOS)
    /// A glass circle's room around its symbol.
    static let circleInset: CGFloat = 6
    #elseif os(iOS)
    /// A round menu's room around its symbol (`roundGlassLabel`): the glass button style's around an `IconButton`'s
    /// symbol at the large control size (an 18 pt symbol in a 48 pt circle), so Detail's round menus match the round
    /// buttons beside them.
    static let circleInset: CGFloat = 15
    #endif
}

/// A shelf heading's text button ("See all"), tinted text: touch and pointer UIs. Home's rows on tvOS end in a See all
/// card instead.
struct HeadingButton: View {
    let title: String
    let id: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Text(title)
                Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).accessibilityHidden(true)
            }
            .hitTargetLabel()
        }
        .buttonStyle(.borderless)
        .accessibilityIdentifier(id)
        .hitTarget()
    }
}
