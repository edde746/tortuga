import CoreGraphics
import XCTest

/// A screenshot's pixels as 8-bit sRGB, top row first and upright as the screen shows it (iOS in landscape too), for
/// how much what shows on it stands out. Rects are in points of the screenshot (the screen's on iOS and tvOS, the
/// display's on macOS: elements' frames), `scale` pixels each.
struct ScreenPixels {
    private(set) var width: Int
    private(set) var height: Int
    /// Pixels per point.
    let scale: CGFloat
    private var bytes: [UInt8]

    init?(_ screenshot: XCUIScreenshot) {
        #if os(macOS)
        let size = screenshot.image.size
        guard let image = screenshot.image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return nil }
        #else
        // Drawn with its orientation, so a landscape screen's pixels lie as its frames do.
        let picture = screenshot.image
        let size = picture.size
        let format = UIGraphicsImageRendererFormat()
        format.scale = picture.scale
        guard let image = UIGraphicsImageRenderer(size: size, format: format).image(actions: { _ in picture.draw(at: .zero) }).cgImage
        else { return nil }
        #endif
        guard size.width > 0 else { return nil }
        width = image.width
        height = image.height
        scale = CGFloat(image.width) / size.width
        bytes = [UInt8](repeating: 0, count: width * height * 4)
        let (width, height) = (width, height)
        let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
            guard let space = CGColorSpace(name: CGColorSpace.sRGB),
                  let context = CGContext(
                      data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                      space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
                  ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        if !drawn { return nil }
        #if os(iOS)
        // A landscape screen's frames are landscape; a screenshot still upright in portrait turns to match them.
        let device = XCUIDevice.shared.orientation
        if device.isLandscape, height > width { turn(to: device) }
        #endif
    }

    /// The screen now.
    init?() {
        self.init(XCUIScreen.main.screenshot())
    }

    /// The colour of the pixel `x` pixels from the left and `y` from the top: its red, green and blue, 0–255.
    func colour(x: Int, y: Int) -> (red: UInt8, green: UInt8, blue: UInt8) {
        let i = (y * width + x) * 4
        return (bytes[i], bytes[i + 1], bytes[i + 2])
    }

    /// WCAG's contrast ratio of the brightest 2 % of the pixels in `rect` (a white symbol or text) against their median
    /// (what is behind it).
    func contrast(in rect: CGRect) -> Double {
        var luminances = luminances(in: rect)
        guard !luminances.isEmpty else { return 0 }
        luminances.sort()
        let symbol = luminances[(luminances.count - 1) * 98 / 100]
        let behind = luminances[luminances.count / 2]
        return (symbol + 0.05) / (behind + 0.05)
    }

    /// The median relative luminance of the pixels in `rect`: what is behind text, which covers less than half of it.
    func medianLuminance(in rect: CGRect) -> Double {
        let luminances = luminances(in: rect).sorted()
        return luminances.isEmpty ? 0 : luminances[luminances.count / 2]
    }

    /// The bottom edge (in points) of the lowest pixel row in `rect` with a pixel `matching` (its red, green and blue,
    /// 0–255): where art of that colour ends.
    func lowestRow(in rect: CGRect, matching: (UInt8, UInt8, UInt8) -> Bool) -> CGFloat? {
        let (columns, rows) = pixelRanges(of: rect)
        for y in rows.reversed() {
            for x in columns {
                let i = (y * width + x) * 4
                if matching(bytes[i], bytes[i + 1], bytes[i + 2]) { return CGFloat(y + 1) / scale }
            }
        }
        return nil
    }

    /// The top edge (in points) of the highest pixel row in `rect` whose every pixel is `matching` (its red, green and
    /// blue, 0–255): where a picture ends in black.
    func topmostRow(in rect: CGRect, whereEvery matching: (UInt8, UInt8, UInt8) -> Bool) -> CGFloat? {
        let (columns, rows) = pixelRanges(of: rect)
        guard !columns.isEmpty else { return nil }
        for y in rows where columns.allSatisfy({ x in
            let i = (y * width + x) * 4
            return matching(bytes[i], bytes[i + 1], bytes[i + 2])
        }) {
            return CGFloat(y) / scale
        }
        return nil
    }

    /// The share (0–1) of the pixels in `rect` that are `matching` (their red, green and blue, 0–255): how much of it
    /// a colour fills.
    func share(in rect: CGRect, matching: (UInt8, UInt8, UInt8) -> Bool) -> Double {
        let (columns, rows) = pixelRanges(of: rect)
        guard !columns.isEmpty, !rows.isEmpty else { return 0 }
        var count = 0
        for y in rows {
            for x in columns {
                let i = (y * width + x) * 4
                if matching(bytes[i], bytes[i + 1], bytes[i + 2]) { count += 1 }
            }
        }
        return Double(count) / Double(columns.count * rows.count)
    }

    /// The colours of the pixels in `rect` (their red, green and blue, 0–255), row by row.
    func colours(in rect: CGRect) -> [SIMD3<Int>] {
        let (columns, rows) = pixelRanges(of: rect)
        var colours = [SIMD3<Int>]()
        colours.reserveCapacity(columns.count * rows.count)
        for y in rows {
            for x in columns {
                let i = (y * width + x) * 4
                colours.append(SIMD3(Int(bytes[i]), Int(bytes[i + 1]), Int(bytes[i + 2])))
            }
        }
        return colours
    }

    /// The share (0–1) of the pixels in `rect` whose red, green or blue differs by more than `tolerance` (0–255) from
    /// `other`'s, a screenshot of the same screen at another moment: how much of it changed in between. 1 where the two
    /// differ in size or `rect` holds no pixel.
    func changedShare(in rect: CGRect, from other: ScreenPixels, tolerance: Int = 24) -> Double {
        let (columns, rows) = pixelRanges(of: rect)
        guard !columns.isEmpty, !rows.isEmpty, other.width == width, other.height == height else { return 1 }
        var count = 0
        for y in rows {
            for x in columns {
                let i = (y * width + x) * 4
                if (0..<3).contains(where: { abs(Int(bytes[i + $0]) - Int(other.bytes[i + $0])) > tolerance }) { count += 1 }
            }
        }
        return Double(count) / Double(columns.count * rows.count)
    }

    private func pixelRanges(of rect: CGRect) -> (columns: Range<Int>, rows: Range<Int>) {
        let left = min(max(Int(rect.minX * scale), 0), width)
        let top = min(max(Int(rect.minY * scale), 0), height)
        let columns = left..<min(max(Int(rect.maxX * scale), left), width)
        let rows = top..<min(max(Int(rect.maxY * scale), top), height)
        return (columns, rows)
    }

    #if os(iOS)
    /// Turns a portrait screenshot a quarter, to the screen of a device turned to `orientation`: landscape left (the
    /// interface's landscape right) puts the screen's top along the portrait picture's right edge.
    private mutating func turn(to orientation: UIDeviceOrientation) {
        let (portraitWidth, portraitHeight) = (width, height)
        var turned = [UInt8](repeating: 0, count: bytes.count)
        for y in 0..<portraitWidth {
            for x in 0..<portraitHeight {
                let (px, py) = orientation == .landscapeLeft ? (portraitWidth - 1 - y, x) : (y, portraitHeight - 1 - x)
                let from = (py * portraitWidth + px) * 4
                let to = (y * portraitHeight + x) * 4
                turned[to] = bytes[from]
                turned[to + 1] = bytes[from + 1]
                turned[to + 2] = bytes[from + 2]
            }
        }
        bytes = turned
        (width, height) = (portraitHeight, portraitWidth)
    }
    #endif

    private func luminances(in rect: CGRect) -> [Double] {
        let (columns, rows) = pixelRanges(of: rect)
        var luminances = [Double]()
        luminances.reserveCapacity(columns.count * rows.count)
        for y in rows {
            for x in columns {
                let i = (y * width + x) * 4
                luminances.append(Self.luminance(bytes[i], bytes[i + 1], bytes[i + 2]))
            }
        }
        return luminances
    }

    /// The relative luminance of an sRGB colour.
    private static func luminance(_ red: UInt8, _ green: UInt8, _ blue: UInt8) -> Double {
        func linear(_ value: UInt8) -> Double {
            let c = Double(value) / 255
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
    }
}
