import Accelerate
import Nuke
import NukeUI
import SwiftUI

/// An addon's artwork (poster, background, logo) loaded and cached by Nuke (image loading is native, not shared).
/// Shows `placeholder` while loading, on failure and for a nil or malformed `url`; the image fills the frame the caller
/// gives it, cropped (`.fill`, keeping its `alignment` part in view) or letterboxed (`.fit`). A cropped image stays
/// inside that frame for touches as well as drawing: clipping alone leaves the overflow hit-testable, so a wide poster
/// in a row would take its neighbour's taps. Smart Invert leaves the image as it is, as it does photos.
///
/// The image is made at the frame's size in pixels ([ArtworkResize]), so the screen draws it pixel for pixel: drawn
/// from a larger bitmap, the screen shrank it bilinearly from one pixel in several and an episode's thumbnail looked
/// jagged. A frame that changes size loads it again at the new size (from Nuke's caches), the image last made for the
/// URL showing until it comes ([LastMade]). `blurred` blurs `url`'s image past recognition (an unwatched episode's
/// still, so it gives nothing away): a Gaussian a tenth of the frame's width, its edges kept opaque; the placeholder
/// stays sharp.
///
/// `fallbackUrl`, when set, is loaded in `url`'s place once `url` fails (or isn't an address), sharp (a series' art in
/// place of an episode's still gives nothing away); without one nothing more is requested.
struct RemoteImage<Placeholder: View>: View {
    let url: String?
    var fallbackUrl: String?
    var contentMode: ContentMode = .fill
    var alignment: Alignment = .center
    var blurred = false
    @ViewBuilder var placeholder: () -> Placeholder

    @Environment(\.displayScale) private var displayScale
    @Environment(\.layoutDirection) private var layoutDirection
    /// The frame, in points; zero until it is laid out, and nothing loads till then.
    @State private var frame = CGSize.zero

    var body: some View {
        let request = request()
        Color.clear
            .onGeometryChange(for: CGSize.self) { $0.size } action: { frame = $0 }
            .overlay(alignment: alignment) {
                LazyImage(request: request) { state in
                    if let image = state.image ?? LastMade.image(for: url.flatMap(URL.init(string:))) {
                        image.resizable().aspectRatio(contentMode: contentMode)
                            .blur(radius: blurred ? frame.width / 10 : 0, opaque: true)
                            .accessibilityIgnoresInvertColors()
                    } else if let fallbackUrl, frame.width > 0, frame.height > 0,
                              request == nil || state.error.map(Self.failed) == true {
                        RemoteImage(url: fallbackUrl, contentMode: contentMode, alignment: alignment, placeholder: placeholder)
                    } else {
                        placeholder()
                    }
                }
                .onCompletion { result in
                    if case .success(let response) = result { LastMade.keep(response) }
                }
            }
            .clipped()
            .contentShape(Rectangle())
    }

    /// The load failed, rather than having no request yet (no frame laid out).
    private static func failed(_ error: Error) -> Bool {
        if case .imageRequestMissing? = error as? ImagePipeline.Error { return false }
        return true
    }

    private func request() -> ImageRequest? {
        guard let url = url.flatMap(URL.init(string:)), frame.width > 0, frame.height > 0 else { return nil }
        let pixels = CGSize(width: (frame.width * displayScale).rounded(), height: (frame.height * displayScale).rounded())
        var anchor = UnitPoint(x: alignment.horizontal == .leading ? 0 : alignment.horizontal == .trailing ? 1 : 0.5,
                               y: alignment.vertical == .top ? 0 : alignment.vertical == .bottom ? 1 : 0.5)
        if layoutDirection == .rightToLeft { anchor.x = 1 - anchor.x }
        return ArtworkResize(pixels: pixels, fill: contentMode == .fill, anchor: anchor).request(url)
    }
}

extension RemoteImage where Placeholder == EmptyView {
    init(url: String?, fallbackUrl: String? = nil, contentMode: ContentMode = .fill, alignment: Alignment = .center) {
        self.init(url: url, fallbackUrl: fallbackUrl, contentMode: contentMode, alignment: alignment) { EmptyView() }
    }
}

/// The image last made for each URL, at whichever size: shown instead of the placeholder until the image for the
/// frame's size comes, by the view that showed it (its frame changed) and by any that shows the URL anew. A screen
/// opened again may lay a frame out a pass after it appears (a phone's Detail backdrop, whose height takes the top
/// inset then): its first request, at the size before, misses Nuke's memory cache, which keys each size apart, and
/// the backdrop would be missing for a frame. At most 64 MB of bitmaps, most of them Nuke's own as well.
@MainActor
private enum LastMade {
    private static let images: NSCache<NSURL, PlatformImage> = {
        let cache = NSCache<NSURL, PlatformImage>()
        cache.totalCostLimit = 64 << 20
        return cache
    }()

    static func image(for url: URL?) -> Image? {
        guard let url, let image = images.object(forKey: url as NSURL) else { return nil }
        return Image(platformImage: image)
    }

    static func keep(_ response: ImageResponse) {
        guard let url = response.request.url else { return }
        let bytes = response.image.uprightCGImage.map { $0.bytesPerRow * $0.height } ?? 0
        images.setObject(response.image, forKey: url as NSURL, cost: bytes)
    }
}

/// Artwork made at the size it is drawn: `pixels` filled (`fill`: cropped to its shape, keeping the `anchor` part:
/// (0, 0) the top leading corner) or fitted, by vImage's high-quality resampling (Lanczos), which comes within 0.5 %
/// RMSE of ImageMagick's Lanczos on the app's thumbnails, posters and backdrops. An image smaller than that is cropped
/// to the shape only and drawn larger by the screen, never made larger here; one whose orientation isn't up is left
/// as it is.
struct ArtworkResize: ImageProcessing, Hashable {
    let pixels: CGSize
    let fill: Bool
    var anchor = UnitPoint.center

    var identifier: String {
        "tortuga/artwork?\(Int(pixels.width))x\(Int(pixels.height)),\(fill ? "fill" : "fit"),\(anchor.x),\(anchor.y)"
    }

    /// `url`'s image decoded by ImageIO no larger than twice `pixels` (filling them or fitted in them, as this makes
    /// it), then made by this. An addon's image may be any size, and decoded whole one 30000 pixels square takes
    /// 3.6 GB, which ends the app; decoded near the size it is drawn at it costs that little whatever its own. Twice
    /// that, so what shows is resampled by the Lanczos here, not by ImageIO's thumbnailing, which never enlarges.
    func request(_ url: URL) -> ImageRequest {
        var request = ImageRequest(url: url, processors: [self])
        request.thumbnail = ImageRequest.ThumbnailOptions(
            size: CGSize(width: pixels.width * 2, height: pixels.height * 2), unit: .pixels, contentMode: fill ? .aspectFill : .aspectFit
        )
        return request
    }

    func process(_ image: PlatformImage) -> PlatformImage? {
        guard let source = image.uprightCGImage, source.width > 0, source.height > 0 else { return image }
        let width = Double(source.width), height = Double(source.height)
        let scale = fill ? max(pixels.width / width, pixels.height / height) : min(pixels.width / width, pixels.height / height)
        // The part of the source that shows: all of it when fitted, the target's shape when filled.
        let shown = fill ? CGSize(width: min(width, pixels.width / scale), height: min(height, pixels.height / scale)) : CGSize(width: width, height: height)
        let crop = CGRect(x: ((width - shown.width) * anchor.x).rounded(), y: ((height - shown.height) * anchor.y).rounded(),
                          width: shown.width.rounded(), height: shown.height.rounded())
        // Smaller than the frame: cropped to its shape only, when it needs that.
        guard scale < 1 || fill && (crop.width < width || crop.height < height) else { return image }
        let size = scale >= 1 ? crop.size : fill ? pixels : CGSize(width: (width * scale).rounded(), height: (height * scale).rounded())
        guard let cropped = source.cropping(to: crop), let made = cropped.bitmap(width: Int(size.width), height: Int(size.height)) else {
            return image
        }
        return PlatformImage(upright: made)
    }
}

private extension CGImage {
    /// This image decoded into a bitmap of `width` × `height`, by vImage's Lanczos when that isn't its own size, in
    /// premultiplied BGRA in its own RGB colour space (else sRGB); nil when vImage can't take it. Decoded here, on
    /// Nuke's processing queue: Nuke decompresses only the images it decodes, so a cropped JPEG left as it is would
    /// decode on the main thread when first drawn.
    func bitmap(width: Int, height: Int) -> CGImage? {
        let space = colorSpace.flatMap { $0.model == .rgb ? $0 : nil } ?? CGColorSpace(name: CGColorSpace.sRGB)!
        let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        guard let format = vImage_CGImageFormat(bitsPerComponent: 8, bitsPerPixel: 32, colorSpace: space, bitmapInfo: info),
              var input = try? vImage_Buffer(cgImage: self, format: format) else { return nil }
        defer { input.free() }
        guard width != self.width || height != self.height else { return try? input.createCGImage(format: format) }
        guard var output = try? vImage_Buffer(width: width, height: height, bitsPerPixel: 32) else { return nil }
        defer { output.free() }
        guard vImageScale_ARGB8888(&input, &output, nil, vImage_Flags(kvImageHighQualityResampling)) == kvImageNoError else { return nil }
        return try? output.createCGImage(format: format)
    }
}

#if os(macOS)
extension NSImage {
    var uprightCGImage: CGImage? { cgImage(forProposedRect: nil, context: nil, hints: nil) }
    convenience init(upright image: CGImage) { self.init(cgImage: image, size: NSSize(width: image.width, height: image.height)) }
}

private extension Image {
    init(platformImage: NSImage) { self.init(nsImage: platformImage) }
}
#else
extension UIImage {
    var uprightCGImage: CGImage? { imageOrientation == .up ? cgImage : nil }
    convenience init(upright image: CGImage) { self.init(cgImage: image, scale: 1, orientation: .up) }
}

private extension Image {
    init(platformImage: UIImage) { self.init(uiImage: platformImage) }
}
#endif
