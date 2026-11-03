import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// Colour roles (OLED dark, neutral, no brand hue in the UI): black screens, Apple's dark system greys
/// for grouped rows, cards and sheets, white text and controls. The app icon keeps the brand colours; the UI has none.
/// The same roles as the Compose colour scheme, in Apple's own greys. A role named after a system colour takes that
/// colour's increased-contrast value while Increase Contrast is on (`Color(_:increased:)`), as the system's own do; the
/// rest are white, black or the app's own and stay as they are.
enum Palette {
    /// Screens, bars and the areas behind grouped rows.
    static let background = Color.black
    /// Text and icons on `background` (the label colour).
    static let onBackground = Color.white
    /// Secondary text: hints, status lines, captions (the secondary label colour: 60 %, 70 % with Increase Contrast).
    static let onSurfaceVariant = Color(Color(rgb: 0xEBEBF5).opacity(0.6), increased: Color(rgb: 0xEBEBF5).opacity(0.7))
    /// The emphasised fill: selection, progress, a focused TV control.
    static let primary = Color.white
    /// Text and icons on `primary`.
    static let onPrimary = Color.black
    /// Text, symbols and fills over the video (the player's controls, menus and panels): pure white, the player's
    /// foreground style (`PlayerScreen`), so a control there draws in it without naming a colour.
    static let onVideo = Color.white
    /// The player's secondary level (`.secondary` over the video: the episode line, why the controls are off): `onVideo`
    /// at 60 %, at 70 % with Increase Contrast (the secondary label's step).
    static let onVideoSecondary = Color(onVideo.opacity(0.6), increased: onVideo.opacity(0.7))
    /// Surfaces from the lowest to the highest emphasis: sheets and panels, grouped rows and cards, solid badges and
    /// pressed or selected rows (the system's dark greys: secondary and tertiary grouped backgrounds, gray 4, each
    /// lighter with Increase Contrast; the lowest is the app's own near black).
    static let surfaceContainerLow = Color(rgb: 0x0F0F0F)
    static let surfaceContainer = Color(rgb: 0x1C1C1E, increased: 0x242426)
    static let surfaceContainerHigh = Color(rgb: 0x2C2C2E, increased: 0x363638)
    static let surfaceContainerHighest = Color(rgb: 0x3A3A3C, increased: 0x444446)
    /// A tonal badge on a focused (white) tvOS surface: a mid grey under black text (the system's grey 2 lightened).
    static let focusedTonalBadge = Color(white: 0.62)
    /// Errors: the system's dark red.
    static let error = Color(rgb: 0xFF453A, increased: 0xFF6961)
    /// The player's live dot, beside LIVE over the video: the one red of a live playback (Compose's `TortugaColors.Live`).
    static let live = Color(rgb: 0xE5251B)
    /// The focus ring on keyboard focus (iOS, macOS).
    static let focusRing = Color.white
    static let scrim = Color.black
    /// A switch that is on: the system's own green (iOS) or the Mac's accent colour, since the white tint would
    /// hide the knob.
    #if os(macOS)
    static let switchOn = Color.accentColor
    #else
    static let switchOn = Color.green
    #endif
    #if os(iOS)
    /// iPad's sidebar tint on iPadOS 18 and 27, the system's grey 2: those sidebars fill the selected row with the tint
    /// under white content, which the white tint would hide (`AppShell.tabTint`).
    static let sidebarTint = Color(rgb: 0x636366, increased: 0x7C7C80)
    #endif
}

/// Corner radii.
enum Radius {
    static let extraSmall: CGFloat = 6
    static let small: CGFloat = 10
    static let medium: CGFloat = 14
    static let large: CGFloat = 20
}

extension Color {
    /// An opaque sRGB colour from `0xRRGGBB`.
    init(rgb: UInt32) {
        self.init(
            .sRGB,
            red: Double((rgb >> 16) & 0xFF) / 255,
            green: Double((rgb >> 8) & 0xFF) / 255,
            blue: Double(rgb & 0xFF) / 255
        )
    }

    /// An opaque sRGB colour from `0xRRGGBB`, `increased` while Increase Contrast is on.
    init(rgb: UInt32, increased: UInt32) {
        self.init(Color(rgb: rgb), increased: Color(rgb: increased))
    }

    /// `normal`, or `increased` while Increase Contrast is on (`colorSchemeContrast == .increased`), resolved where it
    /// is drawn as the system's colours are: from the trait collection (iOS, tvOS) or the appearance (macOS).
    init(_ normal: Color, increased: Color) {
        #if os(macOS)
        let high: [NSAppearance.Name] = [.accessibilityHighContrastDarkAqua, .accessibilityHighContrastAqua]
        self.init(nsColor: NSColor(name: nil) { appearance in
            let match = appearance.bestMatch(from: [.darkAqua, .aqua] + high)
            return NSColor(match.map(high.contains) == true ? increased : normal)
        })
        #else
        self.init(uiColor: UIColor { $0.accessibilityContrast == .high ? UIColor(increased) : UIColor(normal) })
        #endif
    }

    /// A colour from a shared `0xAARRGGBB` value (`ProfileAvatar.background`, `.foreground`).
    init(argb: Int64) {
        let value = UInt32(truncatingIfNeeded: argb)
        self.init(
            .sRGB,
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255,
            opacity: Double((value >> 24) & 0xFF) / 255
        )
    }
}

extension View {
    /// The app's look for everything below: dark, with white controls on iOS and macOS. tvOS keeps the system's tint:
    /// its alerts and dialogs draw their labels in the tint, which would vanish into the white of a focused entry.
    /// Every window's root applies it once.
    func tortugaTheme() -> some View {
        #if os(tvOS)
        preferredColorScheme(.dark)
        #else
        preferredColorScheme(.dark)
            .tint(Palette.primary)
        #endif
    }
}
