import Nuke
import NukeUI
import SwiftUI
import TortugaKit

/// The title's logo when the addon has one (the name while it loads, and if it fails), else its name, as a heading.
/// The logo is its art alone: clear logos from fanart.tv (Cinemeta's) are 800×310 canvases with a fifth to a third of
/// them transparent above and below the art, which would otherwise sit between the art and the metadata line. The art
/// fits in `maxWidth` and the box's height (`DetailMetrics.titleHeight`), at the bottom of a box that tall, and is never
/// drawn past twice its own pixels. On a narrow page's backdrop the name sits at the bottom of the same box, so a
/// title's metadata line is where the backdrop's fade has it. The name is never cut short or shrunk: it wraps onto as
/// many lines as it takes, and a name taller than the box (a long one, large text) grows the box downwards, moving the
/// lines under it down; the page scrolls to them. Where the name's first line ends tells the narrow page where the
/// backdrop's fade goes (`DetailNarrowLayout`).
struct DetailTitleArt: View {
    let header: DetailHeader
    let maxWidth: CGFloat
    /// The tallest the logo's art may be: the box's height, or less where the lifted box has less room above it.
    let artHeight: CGFloat
    let centred: Bool
    /// Called with how far under the box's top the name's first line ends while the name shows, nil once the logo
    /// replaces it.
    let onFirstLine: (CGFloat?) -> Void

    @Environment(\.formFactor) private var formFactor
    @Environment(\.displayScale) private var displayScale
    /// The name's size on iPad and the Mac: 40 pt at the default text size, following Dynamic Type as the large title
    /// does.
    @ScaledMetric(relativeTo: .largeTitle) private var regularTitleSize: CGFloat = 40
    /// The box's coordinate space, where the name's first line is measured.
    private nonisolated static let box = "DetailTitleArt"

    var body: some View {
        let height = DetailMetrics.titleHeight(formFactor, centred: centred)
        Group {
            if let logo = header.logo.flatMap(URL.init(string:)) {
                LazyImage(request: ImageRequest(url: logo, processors: [trimmedToArt])) { state in
                    if let image = state.image, let container = state.imageContainer {
                        let size = logoSize(pixels: pixelSize(of: container.image), maxHeight: artHeight)
                        image.resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: size.width, height: size.height)
                            .accessibilityIgnoresInvertColors()
                    } else {
                        name
                            .detailArtPending("logo")
                    }
                }
                // As tall as the logo may be from the start, so nothing under it moves when it comes in (but where the
                // name before it took more room than that).
                .frame(minHeight: height, alignment: centred ? .bottom : .bottomLeading)
            } else if centred, header.background != nil {
                name
                    .frame(minHeight: height, alignment: .bottom)
            } else {
                name
            }
        }
        .coordinateSpace(.named(Self.box))
        .frame(maxWidth: .infinity, alignment: centred ? .bottom : .bottomLeading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(header.title)
        .accessibilityAddTraits(.isHeader)
        .accessibilityIdentifier("detail.title")
    }

    /// The name, whole on as many lines as it takes.
    private var name: some View {
        Text(header.title)
            .font(titleFont)
            .multilineTextAlignment(centred ? .center : .leading)
            .fixedSize(horizontal: false, vertical: true)
            // One line of the name's font at its top, unseen: where its first line ends.
            .background(alignment: .top) {
                Text(verbatim: "X")
                    .font(titleFont)
                    .hidden()
                    .onGeometryChange(for: CGFloat.self) { $0.frame(in: .named(Self.box)).maxY } action: { onFirstLine($0) }
            }
            .onDisappear { onFirstLine(nil) }
            .frame(maxWidth: .infinity, alignment: centred ? .bottom : .bottomLeading)
    }

    private var titleFont: Font {
        switch formFactor {
        case .compact: .largeTitle.weight(.bold)
        case .regular: .system(size: regularTitleSize, weight: .bold)
        case .tv: .title2.weight(.bold)
        }
    }

    /// The logo's size: its own shape fitted in `maxWidth` × `maxHeight`, at most twice its pixels on this screen.
    private func logoSize(pixels: CGSize, maxHeight: CGFloat) -> CGSize {
        guard pixels.width > 0, pixels.height > 0 else { return .zero }
        let aspect = pixels.width / pixels.height
        let width = min(maxWidth, maxHeight * aspect, 2 * pixels.width / displayScale)
        return CGSize(width: width, height: width / aspect)
    }
}

#if os(macOS)
private func pixelSize(of image: NSImage) -> CGSize {
    guard let representation = image.representations.first, representation.pixelsWide > 0, representation.pixelsHigh > 0 else {
        return image.size
    }
    return CGSize(width: representation.pixelsWide, height: representation.pixelsHigh)
}
#else
private func pixelSize(of image: UIImage) -> CGSize {
    CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
}
#endif

/// Cuts a logo to its art: the rows and columns at its edges with nothing at least 3 % opaque on them.
private let trimmedToArt = ImageProcessors.Anonymous(id: "app.tortuga.trimmedToArt") { image in
    #if os(macOS)
    guard let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { return image }
    #else
    guard let cgImage = image.cgImage else { return image }
    #endif
    guard let art = artRect(of: cgImage), let cropped = cgImage.cropping(to: art) else { return image }
    #if os(macOS)
    return NSImage(cgImage: cropped, size: art.size)
    #else
    return UIImage(cgImage: cropped, scale: image.scale, orientation: image.imageOrientation)
    #endif
}

/// The smallest rectangle of `image` (in pixels, from its top-left corner) holding every pixel at least 3 % opaque; nil
/// when that is the whole image, or nothing.
private func artRect(of image: CGImage) -> CGRect? {
    let width = image.width, height = image.height
    var pixels = [UInt8](repeating: 0, count: width * height * 4)
    let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
        guard let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                  data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                  space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
              ) else { return false }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return true
    }
    guard drawn else { return nil }
    var top = height, bottom = -1, left = width, right = -1
    for y in 0..<height {
        for x in 0..<width where pixels[(y * width + x) * 4 + 3] > 7 {
            top = min(top, y)
            bottom = max(bottom, y)
            left = min(left, x)
            right = max(right, x)
        }
    }
    guard bottom >= 0, bottom - top + 1 < height || right - left + 1 < width else { return nil }
    return CGRect(x: left, y: top, width: right - left + 1, height: bottom - top + 1)
}
