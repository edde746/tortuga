import SwiftUI

/// Google's G: the gradient mark of Google's sign-in branding kit (`signin-assets.zip`, 2025), drawn from the kit's SVG.
/// A conic gradient and six ellipses of colour lie inside the G's outline. The kit blurs them by one unit; here each
/// ellipse's edge fades along that blur's profile instead and the gradient stays sharp, which draws the same G within a
/// level or two of the kit's PNGs. The Compose `GoogleLogoPainter` draws it from the same numbers. It fills the
/// largest square that fits, in its own colours under Smart Invert too.
struct GoogleLogo: View {
    var body: some View {
        Canvas { context, size in
            let scale = min(size.width, size.height) / Self.side
            context.translateBy(x: (size.width - Self.side * scale) / 2, y: (size.height - Self.side * scale) / 2)
            context.scaleBy(x: scale, y: scale)
            // The kit's coordinates: a 44-unit button with the G at (12, 12).
            context.translateBy(x: -12, y: -12)
            context.clip(to: Self.outline)
            context.fill(
                Path(CGRect(x: 0, y: 0, width: 44, height: 44)),
                with: .conicGradient(Self.sweep, center: CGPoint(x: 21.6819, y: 21.7927), angle: .degrees(-45))
            )
            for glow in Self.glows {
                var layer = context
                layer.translateBy(x: glow.center.x, y: glow.center.y)
                layer.rotate(by: .degrees(glow.rotation))
                layer.scaleBy(x: glow.radii.width, y: glow.radii.height)
                let reach = glow.reach
                layer.fill(
                    Path(ellipseIn: CGRect(x: -reach, y: -reach, width: 2 * reach, height: 2 * reach)),
                    with: .radialGradient(glow.gradient, center: .zero, startRadius: 0, endRadius: reach)
                )
            }
        }
        .accessibilityIgnoresInvertColors()
        .accessibilityHidden(true)
    }

    /// The G's square, in the kit's units.
    private static let side: CGFloat = 20

    private static let outline = Path(svg:
        "M31.3987 20.1814H21.9849V24.0445H27.3598C27.1286 25.294 26.4294 26.3596 25.3676 27.0712C24.4746 27.6716 " +
            "23.3266 28.0211 21.9849 28.0211C19.3864 28.0211 17.1823 26.2666 16.3947 23.9004C16.1952 23.2989 16.0853 " +
            "22.6599 16.0853 21.9983C16.0853 21.3367 16.1952 20.6966 16.3947 20.0962C17.1823 17.7311 19.3864 15.9755 " +
            "21.9849 15.9755C23.4524 15.9755 24.767 16.4816 25.8039 17.4713L28.6653 14.6057C26.936 12.9908 24.6786 12 " +
            "21.9849 12C18.0832 12 14.705 14.2414 13.0618 17.5076C12.383 18.8592 12 20.3834 12 21.9994C12 23.6155 " +
            "12.383 25.1396 13.0618 26.4913C14.705 29.7597 18.0832 32 21.9849 32C24.6797 32 26.9485 31.1137 28.6018 " +
            "29.5861C30.4887 27.8452 31.5732 25.2702 31.5732 22.2275C31.5732 21.5182 31.5131 20.835 31.3987 " +
            "20.1825V20.1814Z"
    )

    /// The conic gradient: (degrees clockwise from the start, up and to the right of the centre; colour).
    private static let sweep: Gradient = {
        let stops: [(degrees: Double, rgb: UInt32)] = [
            (0, 0xFF4641), (4.14555, 0xFF4641), (39.154, 0x3186FF), (72.0044, 0x3186FF), (96.7463, 0x00A5B7),
            (120.897, 0x0EBC5F), (154.722, 0x0EBC5F), (179.136, 0x6CC400), (203.588, 0xFFCC00), (226.915, 0xFFD314),
            (251.688, 0xFFCC00), (273.129, 0xFF6A2B), (289.305, 0xFD4641), (359.593, 0xFF4641), (360, 0xFF4641),
        ]
        return Gradient(stops: stops.map { Gradient.Stop(color: Color(rgb: $0.rgb), location: $0.degrees / 360) })
    }()

    private static let glows = [
        Glow(22.0496, 22.2413, 5.39634, 2.83537, rotation: 24.4473, 0x3186FF),
        Glow(35.3538, 20.2155, 7.43918, 3.09357, 0x3186FF),
        Glow(27.2744, 18.2195, 7.40854, 2.37805, 0xFF4641),
        Glow(31.5427, 14.9268, 7.40854, 2.37805, 0xFF5B8B),
        Glow(26.4817, 21.878, 8.5061, 3.10976, 0x3186FF),
        Glow(27.1842, 16.0197, 4.53882, 2.37805, rotation: -28.6599, 0xFF4641),
    ]

    /// An ellipse of colour, drawn as a unit circle stretched to its radii, and turned (`rotation`, degrees clockwise)
    /// about its centre. Its edge fades as the kit's one-unit blur fades it: over (−3, 3) blur widths, the width taken
    /// over the ellipse's mean radius.
    private struct Glow {
        let center: CGPoint
        let radii: CGSize
        let rotation: Double
        /// How far past the unit circle the fade reaches.
        let reach: CGFloat
        let gradient: Gradient

        init(_ x: CGFloat, _ y: CGFloat, _ rx: CGFloat, _ ry: CGFloat, rotation: Double = 0, _ rgb: UInt32) {
            center = CGPoint(x: x, y: y)
            radii = CGSize(width: rx, height: ry)
            self.rotation = rotation
            let width = 1 / (rx * ry).squareRoot()
            let reach = 1 + 3 * width
            self.reach = reach
            let color = Color(rgb: rgb)
            gradient = Gradient(stops: [Gradient.Stop(color: color, location: 0)] + GoogleLogo.edge.map { t, cover in
                Gradient.Stop(color: color.opacity(cover), location: (1 + t * width) / reach)
            })
        }
    }

    /// A one-unit Gaussian blur's edge: how much of a shape still covers a point `t` blur widths outside it.
    private static let edge: [(t: CGFloat, cover: Double)] = [
        (-3, 0.99865), (-2.25, 0.98778), (-1.5, 0.93319), (-0.75, 0.77337), (0, 0.5),
        (0.75, 0.22663), (1.5, 0.06681), (2.25, 0.01222), (3, 0.00135),
    ]
}
