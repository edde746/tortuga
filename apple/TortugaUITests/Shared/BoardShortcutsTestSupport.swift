import XCTest

/// What the tests of Home's shortcut chips (`board.shortcut.<key>`) share on iPhone, iPad, the Mac and Apple TV: the
/// chips in the row's order, and what a chip draws before its label. The icon a chip leads with (its screen's symbol, a
/// tracker's list's service's mark) is decorative, out of the accessibility tree XCUITest reads, so the screen's pixels
/// tell: Vision finds where the label starts, and the part of the chip before it holds the icon or nothing.
extension TortugaUITestCase {
    /// The start of a tracker's list's chip's identifier: `board.shortcut.tracker.<service>.<list>`.
    static let shortcutListPrefix = "board.shortcut.tracker."

    /// The identifiers of Home's shortcut chips in the row's order, from its leading end.
    func shortcutChipIds() -> [String] {
        let chips = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "board.shortcut."))
        var seen = Set<String>()
        return chips.allElementsBoundByIndex.sorted { $0.frame.minX < $1.frame.minX }.map(\.identifier).filter { seen.insert($0).inserted }
    }

    /// The identifiers of Home's shortcut chips from the row's first to the first list of the last of the five trackers
    /// to show one, once each does (up to 60 s, every tracker connected); fails with `what` and returns none while one
    /// doesn't.
    func shortcutChipIdsThroughEachTracker(_ what: String) -> [String] {
        let firsts = ["trakt", "simkl", "mdblist", "mal", "anilist"].map { "\(Self.shortcutListPrefix)\($0)." }
        let listed = { () -> Bool in
            let ids = self.shortcutChipIds()
            return firsts.allSatisfy { prefix in ids.contains { $0.hasPrefix(prefix) } }
        }
        guard waitUntil(60, listed) else {
            fail("\(what): Home's shortcuts don't list a list of each tracker, only \(shortcutChipIds())")
            return []
        }
        let ids = shortcutChipIds()
        let end = firsts.compactMap { prefix in ids.firstIndex { $0.hasPrefix(prefix) } }.max() ?? 0
        return Array(ids[...end])
    }

    /// What is wrong with what `chip`, all of it on `screen`, draws before its label; nil where nothing is. A chip leads
    /// with an icon in its label's colour: the symbol of the screen it opens, or a tracker's list's service's mark.
    /// Vision finds the label's first word; the median of the word's box is the chip's fill behind it (white with focus
    /// on tvOS), and the mean of its 2 % farthest from the fill the label's colour, its strokes. Before the word, across
    /// the chip's middle three fifths and clear of the capsule's rounded end: an icon is at least 2 % of the pixels
    /// standing out of the fill by half the label's contrast, the mean of its own farthest 2 % within a quarter of that
    /// contrast of the label's colour.
    func shortcutLeadProblem(_ chip: XCUIElement, on screen: XCUIScreenshot) -> String? {
        let id = chip.identifier, frame = chip.frame
        // Without the isolates around a list's name (U+2068, U+2069), which draw nothing for Vision to read.
        let word = String(chip.label.unicodeScalars.filter { !$0.properties.isBidiControl }).split(separator: " ").first.map(String.init) ?? ""
        guard let label = screen.frame(ofWord: word, in: frame) else {
            return "\(id): Vision reads no \"\(word)\" on the chip (it reads \(screen.textLines(in: frame)))"
        }
        guard let pixels = ScreenPixels(screen) else { return "\(id): the screenshot has no pixels" }
        let strokes = pixels.colours(in: label)
        let fill = Self.median(strokes)
        let ink = Self.mean(Self.farthest(strokes, from: fill))
        let contrast = Self.distance(ink, fill)
        guard contrast >= 64 else { return "\(id): its label (\(Self.hex(ink))) hardly stands out of its fill (\(Self.hex(fill)))" }
        // To a little before the word's box, which Vision draws a point or two loose.
        let start = frame.minX + frame.height / 4
        let lead = CGRect(x: start, y: frame.midY - frame.height * 0.3, width: label.minX - 3 - start, height: frame.height * 0.6)
        let into = Int((label.minX - frame.minX).rounded())
        guard lead.width >= 4 else { return "\(id): no icon before its label, which starts \(into) pt into the chip" }
        let colours = pixels.colours(in: lead)
        let drawn = Double(colours.filter { Self.distance($0, fill) > contrast / 2 }.count) / Double(max(colours.count, 1))
        if drawn < 0.02 {
            return "\(id): no icon before its label (\(into) pt into the chip): \(Int((drawn * 100).rounded())) % of the \(Int(lead.width)) pt before it is drawn"
        }
        let icon = Self.mean(Self.farthest(colours, from: fill))
        if Self.distance(icon, ink) > contrast / 4 {
            return "\(id): its icon (\(Self.hex(icon))) isn't in its label's colour (\(Self.hex(ink))) on its fill (\(Self.hex(fill)))"
        }
        return nil
    }

    /// Each channel's median: the colour behind text, which covers less than half of its box.
    private static func median(_ colours: [SIMD3<Int>]) -> SIMD3<Int> {
        guard !colours.isEmpty else { return .zero }
        let middle = { (channel: Int) in colours.map { $0[channel] }.sorted()[colours.count / 2] }
        return SIMD3(middle(0), middle(1), middle(2))
    }

    /// The 2 % of `colours` farthest from `colour`, at least three: what is drawn on it at its fullest.
    private static func farthest(_ colours: [SIMD3<Int>], from colour: SIMD3<Int>) -> [SIMD3<Int>] {
        Array(colours.sorted { distance($0, colour) > distance($1, colour) }.prefix(max(3, colours.count / 50)))
    }

    private static func mean(_ colours: [SIMD3<Int>]) -> SIMD3<Int> {
        colours.isEmpty ? .zero : colours.reduce(.zero, &+) / colours.count
    }

    /// The largest difference of their red, green and blue (0–255).
    private static func distance(_ a: SIMD3<Int>, _ b: SIMD3<Int>) -> Int {
        let difference = a &- b
        return max(abs(difference.x), abs(difference.y), abs(difference.z))
    }

    private static func hex(_ colour: SIMD3<Int>) -> String {
        String(format: "#%02lX%02lX%02lX", colour.x, colour.y, colour.z)
    }
}
