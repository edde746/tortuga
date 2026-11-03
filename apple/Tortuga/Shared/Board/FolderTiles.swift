import Nuke
import NukeUI
import SwiftUI
import TortugaKit

/// A collection's folder on Home (`FolderTile`): its cover in the row's `shape` (the collection's: poster, landscape or
/// square, as wide as Home's cards of that shape, so every tile of a row has one size), or without a cover its title in
/// the middle of a tile tinted from the title (`folderTint`). Under it the caption line, kept on every tile so the
/// captions of a row line up: the title when the folder shows it (`showTitle`) and the tile has a cover, else an empty
/// line of the same height; then the note of a folder whose sources can't show (`note`: "Needs the addon AIOMetadata")
/// on one muted line. One button, as a poster card is: lifted on tvOS focus, the focus ring on keyboard focus; to
/// accessibility named by the title, its note its value, the caption hidden.
struct FolderTileCard: View {
    let tile: FolderTile
    let shape: PosterShape
    /// The button's accessibility identifier: `board.row.<r>.folder.<i>`.
    let accessibilityId: String
    let focus: CardFocus
    let action: () -> Void

    @Environment(\.tortugaDimens) private var dimens

    var body: some View {
        let width = dimens.cardWidth(shape)
        VStack(alignment: .leading, spacing: Self.captionGap) {
            Button(action: action) {
                artwork
                    .frame(width: width, height: width / shape.aspectRatio)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
                    .contentShape(RoundedRectangle(cornerRadius: Radius.medium, style: .continuous))
                    .focusRing(radius: Radius.medium)
            }
            .cardButtonStyle()
            .accessibilityLabel(tile.title)
            .accessibilityValue(tile.note ?? "")
            .accessibilityIdentifier(accessibilityId)
            .focused(focus.binding, equals: focus.key)
            VStack(alignment: .leading, spacing: 2) {
                // A space when there's no caption: an empty text lays out with no height.
                Text(tile.showTitle && tile.coverUrl != nil ? tile.title : " ")
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                if let note = tile.note {
                    Text(note)
                        .font(.caption)
                        .foregroundStyle(Palette.onSurfaceVariant)
                        .lineLimit(1)
                }
            }
            .accessibilityHidden(true)
        }
        .frame(width: width, alignment: .leading)
    }

    @ViewBuilder
    private var artwork: some View {
        if let cover = tile.coverUrl {
            ZStack {
                Palette.surfaceContainerHighest
                FolderCover(url: cover, shape: shape)
            }
        } else {
            ZStack {
                folderTint(tile.title)
                Text(tile.title)
                    .font(.headline)
                    .foregroundStyle(Palette.onBackground)
                    .multilineTextAlignment(.center)
                    .lineLimit(3)
                    .padding(12)
                    .accessibilityHidden(true)
            }
        }
    }

    /// Between the tile and its caption: on tvOS the focused tile lifts and grows, and the caption stays clear.
    private static var captionGap: CGFloat {
        #if os(tvOS)
        16
        #else
        6
        #endif
    }
}

/// A dark tile colour of its own for `title`: its hue from the title's hash as Kotlin's `String.hashCode` makes it (the
/// Compose tiles' `folderTint`), 35 % saturation, 30 % value, so the same title gets the same colour on every device.
func folderTint(_ title: String) -> Color {
    var hash: Int32 = 0
    for unit in title.utf16 { hash = hash &* 31 &+ Int32(unit) }
    let hue = (Int(hash) % 360 + 360) % 360
    return Color(hue: Double(hue) / 360, saturation: 0.35, brightness: 0.3)
}

/// A folder's cover, a still of the image (a GIF shows its first frame), against the tile's `shape`: within
/// `tolerance` of the tile's aspect it is cropped from its centre to fill the tile; further off it is drawn whole in the
/// middle, over its own `CoverFill`, so the bars are the cover's colours. Until it loads, and when it fails, nothing:
/// the tile's grey shows. The image is made at the tile's size in pixels, fitted whole (`ArtworkResize`), which keeps
/// its aspect for the choice.
private struct FolderCover: View {
    let url: String
    let shape: PosterShape

    @Environment(\.displayScale) private var displayScale
    /// The tile, in points; zero until it is laid out, and nothing loads till then.
    @State private var frame = CGSize.zero

    var body: some View {
        Color.clear
            .onGeometryChange(for: CGSize.self) { $0.size } action: { frame = $0 }
            .overlay {
                if let request {
                    LazyImage(request: request) { state in
                        if let image = state.image, let size = state.imageContainer?.image.size, size.height > 0 {
                            let whole = abs(size.width / size.height / shape.aspectRatio - 1) > Self.tolerance
                            ZStack {
                                if whole { CoverFill(url: url) }
                                image.resizable().aspectRatio(contentMode: whole ? .fit : .fill)
                            }
                            .accessibilityIgnoresInvertColors()
                        }
                    }
                }
            }
            .clipped()
    }

    private var request: ImageRequest? {
        guard let url = URL(string: url), frame.width > 0, frame.height > 0 else { return nil }
        let pixels = CGSize(width: (frame.width * displayScale).rounded(), height: (frame.height * displayScale).rounded())
        return ImageRequest(url: url, processors: [ArtworkResize(pixels: pixels, fill: false)])
    }

    /// How far (as a fraction) a cover's aspect may be from its tile's and still be cropped to fill it.
    private static let tolerance: CGFloat = 0.05
}

/// The bars behind a cover drawn whole: the cover itself made `CoverFillImage` (16 pixels on its long side, dimmed),
/// scaled up to fill the tile with smooth interpolation, which blurs it. Pixels, not a blur effect, so it looks as the
/// Compose tiles' does; a cover with transparency leaves it empty, its blurred shape would ring the cover's edges.
private struct CoverFill: View {
    let url: String

    var body: some View {
        Color.clear
            .overlay {
                LazyImage(request: ImageRequest(url: URL(string: url), processors: [CoverFillImage()])) { state in
                    if let image = state.image {
                        image.resizable().interpolation(.medium).aspectRatio(contentMode: .fill)
                    }
                }
            }
            .clipped()
    }
}

/// A cover made into its own blurred fill (its own memory cache entry, a few hundred bytes): decoded to `pixels` on its
/// long side and its colours at `light`; a cover with any pixel less than opaque (a logo on alpha, an edge's
/// antialiasing included) gives one clear pixel instead, so nothing is drawn behind it.
private struct CoverFillImage: ImageProcessing, Hashable {
    /// The long side, in pixels: small enough that scaling it up blurs it.
    static let pixels = 16
    /// How much of the cover's light is left: dimmed, so the cover in front of it stands out.
    static let light: CGFloat = 0.7
    /// The least alpha a pixel counts as opaque at (of 255).
    static let opaque: UInt8 = 250

    var identifier: String { "tortuga/coverFill?\(Self.pixels),\(Self.light)" }

    func process(_ image: PlatformImage) -> PlatformImage? {
        guard let source = image.uprightCGImage, source.width > 0, source.height > 0,
              let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
        let info = CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        if Self.hasTransparency(source, space: space) {
            let clear = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 0, space: space, bitmapInfo: info)
            return clear?.makeImage().map(PlatformImage.init(upright:))
        }
        let scale = Double(Self.pixels) / Double(max(source.width, source.height))
        let width = max(Int((Double(source.width) * scale).rounded()), 1)
        let height = max(Int((Double(source.height) * scale).rounded()), 1)
        guard let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: space, bitmapInfo: info) else {
            return nil
        }
        let all = CGRect(x: 0, y: 0, width: width, height: height)
        context.interpolationQuality = .high
        context.draw(source, in: all)
        context.setFillColor(CGColor(gray: 0, alpha: 1 - Self.light))
        context.fill(all)
        return context.makeImage().map(PlatformImage.init(upright:))
    }

    /// Whether `image` has a pixel less than `opaque`: none without an alpha channel, else read whole.
    private static func hasTransparency(_ image: CGImage, space: CGColorSpace) -> Bool {
        switch image.alphaInfo {
        case .none, .noneSkipFirst, .noneSkipLast: return false
        default: break
        }
        let width = image.width, height = image.height
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: space,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return false }
        return stride(from: 3, to: pixels.count, by: 4).contains { pixels[$0] < opaque }
    }
}
