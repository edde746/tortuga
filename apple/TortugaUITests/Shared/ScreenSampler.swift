#if os(macOS)
import AppKit
#else
import UIKit
#endif
import Vision
import XCTest

/// Screenshots of a display (the main one unless told: on a foldable the app's, `appScreen`) taken one after another on
/// a thread of its own, about ten a second, while the test clicks, taps or presses: XCUITest returns from those only
/// once the app is idle again, so what the app draws for a few frames in between (a title shown for the moment a pop
/// takes to reach the navigator, rows before the content above them loads) is seen only from another thread. XCTest
/// marks `XCUIScreen` main-actor only; its `screenshot` is called here through the Objective-C runtime.
final class ScreenSampler: @unchecked Sendable {
    private let screen: NSObject
    private let lock = NSLock()
    private var frames: [(taken: Date, shot: XCUIScreenshot)] = []
    private var running = true
    private var thread: Thread?
    /// Signalled as the sampling thread ends.
    private let finished = DispatchSemaphore(value: 0)

    @MainActor init(of screen: XCUIScreen = .main) {
        self.screen = screen
    }

    /// Starts sampling, for at most `limit` seconds.
    func start(limit: TimeInterval = 10) {
        let thread = Thread { [self] in
            let started = Date()
            while lock.withLock({ running }), Date().timeIntervalSince(started) < limit {
                guard let frame = screen.perform(NSSelectorFromString("screenshot"))?.takeUnretainedValue() as? XCUIScreenshot else { continue }
                lock.withLock { frames.append((Date(), frame)) }
            }
            finished.signal()
        }
        self.thread = thread
        thread.start()
    }

    /// How many frames it has taken so far: a test waits for some before and after the step whose frames it judges.
    var count: Int { lock.withLock { frames.count } }

    /// Stops sampling, once the thread has taken its last frame; the frames taken, in order.
    func stop() -> [XCUIScreenshot] {
        stopTimed().map(\.shot)
    }

    /// Stops sampling, once the thread has taken its last frame; the frames taken, in order, each with when it was taken.
    func stopTimed() -> [(taken: Date, shot: XCUIScreenshot)] {
        lock.withLock { running = false }
        if thread != nil {
            finished.wait()
            thread = nil
        }
        return lock.withLock { frames }
    }

    /// Stops sampling; the last frame taken before `time`, nil when none was.
    func stop(before time: Date) -> XCUIScreenshot? {
        _ = stop()
        return lock.withLock { frames.last { $0.taken < time }?.shot }
    }

    /// Waits for sampling to end at its limit; the frames taken, in order, each with when it was taken.
    func framesAtLimit() -> [(taken: Date, shot: XCUIScreenshot)] {
        if thread != nil {
            finished.wait()
            thread = nil
        }
        return lock.withLock { frames }
    }
}

extension XCUIScreenshot {
    /// The mean brightness (0–255) of `rect` (screen points, the main display's top-left origin).
    func brightness(in rect: CGRect) -> Double {
        let gray = grays(in: rect)
        guard !gray.isEmpty else { return 0 }
        return Double(gray.reduce(0) { $0 + Int($1) }) / Double(gray.count)
    }

    /// How much the brightness of `rect` varies (screen points, the main display's top-left origin): the standard
    /// deviation (0–255) of its pixels' brightness, which a blur lowers.
    func contrast(in rect: CGRect) -> Double {
        let gray = grays(in: rect)
        guard !gray.isEmpty else { return 0 }
        let mean = Double(gray.reduce(0) { $0 + Int($1) }) / Double(gray.count)
        return (gray.reduce(0) { $0 + (Double($1) - mean) * (Double($1) - mean) } / Double(gray.count)).squareRoot()
    }

    /// How far down `rect`'s pixels show in `other` (screen points, 0 to `range`): the offset at which they match best,
    /// in whole pixels.
    func offset(of rect: CGRect, in other: XCUIScreenshot, upTo range: CGFloat) -> CGFloat {
        guard let mine = grayImage(in: rect),
              let theirs = other.grayImage(in: CGRect(x: rect.minX, y: rect.minY, width: rect.width, height: rect.height + range)),
              mine.width == theirs.width else { return 0 }
        let width = mine.width
        var best = (offset: 0, difference: Int.max)
        mine.bytes.withUnsafeBufferPointer { a in
            theirs.bytes.withUnsafeBufferPointer { b in
                for offset in 0...max(theirs.height - mine.height, 0) {
                    var difference = 0
                    for y in stride(from: 0, to: mine.height, by: 2) {
                        for x in stride(from: 0, to: width, by: 3) {
                            difference += abs(Int(a[y * width + x]) - Int(b[(y + offset) * width + x]))
                        }
                    }
                    if difference < best.difference { best = (offset, difference) }
                }
            }
        }
        return CGFloat(best.offset) / mine.scale
    }

    /// Where `rect`'s pixels show in `other` moved sideways by `range` (screen points, left negative), as a page going
    /// back or coming moves the screen under it: the offset at which they match best, to a point, and how far
    /// they differ there (0–255 a pixel), `other` scaled to `rect`'s brightness first, so a page dimmed as it slides
    /// still matches. Nil when `rect` widened by `range` runs off the screen.
    func sideways(of rect: CGRect, in other: XCUIScreenshot, by range: ClosedRange<CGFloat>) -> (offset: CGFloat, difference: Double)? {
        let wide = CGRect(x: rect.minX + range.lowerBound, y: rect.minY, width: rect.width + range.upperBound - range.lowerBound, height: rect.height)
        guard let mine = grayImage(in: rect), let theirs = other.grayImage(in: wide), mine.height == theirs.height,
              abs(CGFloat(theirs.width) - wide.width * mine.scale) < 2 else { return nil }
        // A point at a time, every sixth pixel each way: a test's frames are judged in a second or two each.
        let step = max(Int(mine.scale.rounded()), 1), sample = 6
        var best: (offset: Int, difference: Double)?
        mine.bytes.withUnsafeBufferPointer { a in
            theirs.bytes.withUnsafeBufferPointer { b in
                for start in stride(from: 0, through: theirs.width - mine.width, by: step) {
                    var sumA = 0, sumB = 0
                    for y in stride(from: 0, to: mine.height, by: sample) {
                        for x in stride(from: 0, to: mine.width, by: sample) {
                            sumA += Int(a[y * mine.width + x])
                            sumB += Int(b[y * theirs.width + start + x])
                        }
                    }
                    let gain = Double(sumA) / Double(max(sumB, 1))
                    var difference = 0.0, count = 0
                    for y in stride(from: 0, to: mine.height, by: sample) {
                        for x in stride(from: 0, to: mine.width, by: sample) {
                            difference += abs(Double(a[y * mine.width + x]) - gain * Double(b[y * theirs.width + start + x]))
                            count += 1
                        }
                    }
                    let mean = difference / Double(max(count, 1))
                    if best == nil || mean < best!.difference { best = (start, mean) }
                }
            }
        }
        guard let best else { return nil }
        return (CGFloat(best.offset) / mine.scale + range.lowerBound, best.difference)
    }

    /// The brightness (0–255) of each pixel within `rect`.
    private func grays(in rect: CGRect) -> [UInt8] {
        grayImage(in: rect)?.bytes ?? []
    }

    /// The brightness (0–255) of each pixel within `rect`, row by row, with the rows' width and count and the pixels in
    /// a point.
    private func grayImage(in rect: CGRect) -> (bytes: [UInt8], width: Int, height: Int, scale: CGFloat)? {
        guard let (crop, place) = pixels(in: rect) else { return nil }
        let width = crop.width, height = crop.height
        var bytes = [UInt8](repeating: 0, count: width * height)
        let drawn = bytes.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width,
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
            ) else { return false }
            context.draw(crop, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        return drawn ? (bytes, width, height, CGFloat(width) / place.width) : nil
    }

    /// The lines of text Vision reads in `rect` (screen points, the main display's top-left origin).
    func textLines(in rect: CGRect) -> [String] {
        texts(in: rect).map(\.text)
    }

    /// The lines of text Vision reads in `rect`, each with its place (screen points, the main display's top-left
    /// origin).
    func texts(in rect: CGRect) -> [(text: String, frame: CGRect)] {
        guard let (crop, place) = pixels(in: rect) else { return [] }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        request.recognitionLanguages = ["en-US"]
        try? VNImageRequestHandler(cgImage: crop).perform([request])
        return (request.results ?? []).compactMap { line -> (text: String, frame: CGRect)? in
            guard let text = line.topCandidates(1).first?.string else { return nil }
            // Vision's box is a share of the crop, from its bottom-left corner.
            let box = line.boundingBox
            let frame = CGRect(
                x: place.minX + box.minX * place.width, y: place.minY + (1 - box.maxY) * place.height,
                width: box.width * place.width, height: box.height * place.height
            )
            return (text, frame)
        }
    }

    /// Where Vision reads `word` in `rect` (screen points, the main display's top-left origin): its own box in the first
    /// line that holds it (case aside), whatever else that line holds (a symbol read as a letter before it); nil where
    /// no line does. Vision reads the pixels three times as large, so a Mac's 11 pt type at a pixel a point is read too.
    func frame(ofWord word: String, in rect: CGRect) -> CGRect? {
        guard let (crop, place) = pixels(in: rect), let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(
                  data: nil, width: crop.width * 3, height: crop.height * 3, bitsPerComponent: 8, bytesPerRow: 0,
                  space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
              ) else { return nil }
        context.interpolationQuality = .high
        context.draw(crop, in: CGRect(x: 0, y: 0, width: context.width, height: context.height))
        guard let large = context.makeImage() else { return nil }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        request.recognitionLanguages = ["en-US"]
        try? VNImageRequestHandler(cgImage: large).perform([request])
        for line in request.results ?? [] {
            guard let candidate = line.topCandidates(1).first,
                  let range = candidate.string.range(of: word, options: .caseInsensitive),
                  let box = (try? candidate.boundingBox(for: range))?.boundingBox else { continue }
            // Vision's box is a share of the crop, from its bottom-left corner.
            return CGRect(
                x: place.minX + box.minX * place.width, y: place.minY + (1 - box.maxY) * place.height,
                width: box.width * place.width, height: box.height * place.height
            )
        }
        return nil
    }

    /// The screenshot's pixels within `rect` (screen points, the main display's top-left origin), each edge on its
    /// nearest pixel's, and the part of `rect` they show (all of it but what lies off the screen). Rounded outwards, an
    /// edge a hair above a pixel's (y 62 + 75.333 pt is 411.999 pixels at 3×) took in the row above.
    private func pixels(in rect: CGRect) -> (image: CGImage, place: CGRect)? {
        let picture = image
        #if os(macOS)
        let whole = picture.cgImage(forProposedRect: nil, context: nil, hints: nil)
        #else
        // Drawn upright where the picture has an orientation: the iPhone Duo's inner screen in landscape comes as
        // portrait pixels turned by it, its size the screen's.
        let whole: CGImage?
        if picture.imageOrientation == .up {
            whole = picture.cgImage
        } else {
            let format = UIGraphicsImageRendererFormat()
            format.scale = picture.scale
            whole = UIGraphicsImageRenderer(size: picture.size, format: format).image { _ in picture.draw(at: .zero) }.cgImage
        }
        #endif
        guard let whole, picture.size.width > 0 else { return nil }
        let scale = CGFloat(whole.width) / picture.size.width
        let minX = (rect.minX * scale).rounded(), minY = (rect.minY * scale).rounded()
        let pixels = CGRect(x: minX, y: minY, width: (rect.maxX * scale).rounded() - minX, height: (rect.maxY * scale).rounded() - minY)
            .intersection(CGRect(x: 0, y: 0, width: whole.width, height: whole.height))
        guard !pixels.isEmpty, let crop = whole.cropping(to: pixels) else { return nil }
        return (crop, CGRect(x: pixels.minX / scale, y: pixels.minY / scale, width: pixels.width / scale, height: pixels.height / scale))
    }
}
