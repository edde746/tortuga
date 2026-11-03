#if os(macOS)
import AppKit
#endif
import XCTest

/// The top of the details (the title, the metadata block, the actions) on the test addon's hero titles
/// (tools/media/test_addon.py: the near-white bright frame as their backdrop, amber logos): a series with a wide,
/// short logo whose canvas is a fifth transparent above and below its art, as fanart.tv's clear logos (Cinemeta's)
/// are, answered 3 s late; a movie with a tall, narrow logo; a movie without a logo; a movie without a logo whose name
/// takes four lines or more (120 characters).
extension TortugaUITestCase {
    /// Opens each hero title with `open` (its --dev-open screen) and checks it with `requireHero`, `layout` naming the
    /// page in the failures and the shots; with `logoLate`, the wide logo shows after the details and the metadata
    /// line doesn't move when it comes in (`open` takes less than the logo's 3 s). `narrow`: the page is the narrow one
    /// (phones, narrow windows), whose name sits in the title's box over the backdrop and whose backdrop reaches the
    /// metadata line. Each name shows whole, its first line measured over what is behind it (`requireName`).
    func requireHeroTitles(_ layout: String, narrow: Bool, logoLate: Bool = true, open: (String) throws -> Void) throws {
        let logoShows = { self.element("detail.title").exists && self.titleBottom(matching: Self.heroLogoAmber) != nil }

        try open("detail:series:tortuga-hero-wide")
        require("detail.play", timeout: 60, "\(layout): the wide-logo series' details show Play")
        let line = require("detail.infoLine", "\(layout): the series' details show the metadata line")
        let early = line.frame
        if logoLate, logoShows() {
            fail("\(layout): the wide logo shows with the details: answered 3 s late, the name should show first")
        }
        if !waitUntil(15, logoShows) { fail("\(layout): the wide logo doesn't come in") }
        if abs(line.frame.minY - early.minY) > 0.5 {
            fail("\(layout): the metadata line moves when the logo comes in: from y \(early.minY) to \(line.frame.minY)")
        }
        requireHero("\(layout): the wide, short logo", shot: "hero, \(layout), wide logo", art: true, overBrightBackdrop: narrow)

        try open("detail:movie:tortuga-hero-tall")
        require("detail.play", timeout: 60, "\(layout): the tall-logo movie's details show Play")
        if !waitUntil(15, logoShows) { fail("\(layout): the tall logo doesn't come in") }
        requireHero("\(layout): the tall, narrow logo", shot: "hero, \(layout), tall logo", art: true, overBrightBackdrop: narrow)

        try open("detail:movie:tortuga-hero-plain")
        require("detail.play", timeout: 60, "\(layout): the movie without a logo shows Play")
        requireName("\(layout): the name, without a logo", shot: "hero, \(layout), no logo, name")
        requireHero("\(layout): the name, without a logo", shot: "hero, \(layout), no logo", art: false, overBrightBackdrop: narrow)

        try open("detail:movie:tortuga-hero-long")
        require("detail.play", timeout: 60, "\(layout): the long-named movie shows Play")
        requireName("\(layout): the long name", shot: "hero, \(layout), long name, name")
        // A name taller than the narrow page's title box grows the box down, the metadata line with it, past the
        // backdrop's picture: no reading over the backdrop there.
        requireHero("\(layout): the long name", shot: "hero, \(layout), long name", art: false, overBrightBackdrop: false)
    }

    /// Shoots the details as `name` once the title's art has drawn (`requireArtDrawn`; where Play is under the fold, a
    /// landscape iPhone's page or a name on many lines, on iOS dragged up and on the Mac scrolled until it shows; on TV
    /// focus on it has brought it into view), prints what it measures (`hero, ` lines) and fails where nothing of the
    /// title shows in its box (no channel at 230 or more for a logo, 200 for a name: the Mac's label colour, white at
    /// 85 %, draws at 216 over black), where Play stays under the fold, or where the metadata line's top sits more than
    /// the headline's spacing under the title (`what`): with `art`, a logo, under what shows of it (the lowest row in its
    /// box with a channel at 230 or more), give or take 4 pt; else a name, under its line's bottom (the font's descent
    /// under its glyphs grows with the font: 2 pt on iPhone, 5 on TV, 8 on the Mac), give or take 1 pt. And where Play's
    /// top sits other than the spacing under the block's last line (the ratings row where there is one; on Apple TV the
    /// title block's last, the description or the "With" line, 40 pt over Play), give or take 1 pt: no row keeps room
    /// for one that isn't there (on tvOS Play has focus, drawn 1.05 times its size, its frame 1.65 pt higher); or,
    /// `overBrightBackdrop`, where nothing of the picture shows behind the metadata line's first line (its median
    /// luminance under 0.01) or it reads under 4.5:1.
    func requireHero(_ what: String, shot name: String, art: Bool, overBrightBackdrop: Bool) {
        requireArtDrawn(what)
        #if os(macOS)
        let fold = { self.app.windows.firstMatch.frame.maxY }
        #else
        let fold = { self.app.frame.maxY }
        #endif
        #if !os(tvOS)
        // Until Play has 24 pt under it, or stops moving: a drag shorter than the scroll view's touch slop moves nothing.
        for _ in 0..<6 {
            let playEnd = require("detail.play", timeout: 5, "\(what): the details show Play").frame.maxY
            let below = playEnd + 24 - fold()
            if below <= 0 { break }
            #if os(iOS)
            // Slowly and held, so the page doesn't coast on; at most 70 % of the screen at a time.
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.9))
            let step = min(below, app.frame.height * 0.7)
            start.press(forDuration: 0.1, thenDragTo: start.withOffset(CGVector(dx: 0, dy: -step)), withVelocity: .slow, thenHoldForDuration: 0.5)
            #else
            app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.6, dy: 0.6)).scroll(byDeltaX: 0, deltaY: -below)
            #endif
            // The page coasts on a little after the drag or the scroll: Play's place once it holds still.
            if settledFrame(of: element("detail.play")).maxY > playEnd - 1 { break }
        }
        #endif
        shot(name)
        #if os(tvOS)
        let spacing: CGFloat = 24
        let actionsSpacing: CGFloat = 40
        let blockEnds = ["detail.overview", "detail.castLine"]
        #else
        let spacing: CGFloat = 14
        let actionsSpacing = spacing
        let blockEnds: [String] = []
        #endif
        let line = require("detail.infoLine", "\(what): the metadata line shows").frame
        let ratings = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "detail.rating."))
        let ends = blockEnds.map { element($0) }.filter(\.exists)
        let blockBottom = (ratings.allElementsBoundByIndex + ends).map(\.frame.maxY).reduce(line.maxY, max)
        let playButton = require("detail.play", "\(what): the details show Play")
        let play = playButton.frame
        if play.maxY > fold() { fail("\(what): Play stays under the fold at \(play), the screen ending at y \(fold())") }
        guard let pixels = ScreenPixels() else { return fail("\(what): no screenshot to measure") }
        let title = require("detail.title", timeout: 5, "\(what): the details show the title").frame
        guard let bottom = pixels.lowestRow(in: title, matching: { max($0, $1, $2) >= (art ? 230 : 200) }) else {
            return fail("\(what): nothing of the title shows in its box \(title)")
        }
        // The backdrop alone shows in the screen's margin left of the title.
        let margin = CGRect(x: title.minX - 12, y: title.minY, width: 8, height: CGFloat(pixels.height) / pixels.scale - title.minY)
        let pictureEnd = pixels.topmostRow(in: margin, whereEvery: { max($0, $1, $2) <= 4 })
        let firstLine = CGRect(x: line.minX, y: line.minY, width: line.width, height: min(line.height, 18))
        let gap = line.minY - (art ? bottom : title.maxY)
        let actionsGap = play.minY - blockBottom
        let behind = pixels.medianLuminance(in: firstLine)
        let contrast = pixels.contrast(in: firstLine)
        print("hero, \(what): title box \(title), what shows ends at y \(bottom), metadata line \(line), gap \(gap) pt\(art ? "" : " (from the name's line)"), block ends at y \(blockBottom), Play \(play), actions gap \(actionsGap) pt, picture ends at y \(pictureEnd.map { "\($0)" } ?? "none"), luminance behind the line \(behind), contrast \(contrast):1, screen \(pixels.width)×\(pixels.height) px at \(pixels.scale) px/pt, app \(app.frame)")
        let mostGap = spacing + (art ? 4 : 1)
        if gap > mostGap { fail("\(what): the metadata line's top sits \(gap) pt under the title's \(art ? "art" : "line"), more than \(mostGap)") }
        #if os(tvOS)
        let grown = playButton.hasFocus ? play.height * (1 - 1 / 1.05) / 2 : 0
        #else
        let grown: CGFloat = 0
        #endif
        if abs(actionsGap + grown - actionsSpacing) > 1 {
            fail("\(what): Play's top sits \(actionsGap) pt under the block's last line, not the headline's \(actionsSpacing - grown)")
        }
        guard overBrightBackdrop else { return }
        if behind < 0.01 { fail("\(what): the backdrop's picture doesn't reach the metadata line (luminance \(behind) behind it)") }
        if contrast < 4.5 { fail("\(what): the metadata line reads at \(contrast):1 over the backdrop, under 4.5:1") }
    }

    /// Shoots the title as `name` once the title's art has drawn (`requireArtDrawn`) and fails unless it (`detail.title`:
    /// a name without a logo, its frame the text's) is as tall as its name laid out whole at the title's font
    /// (`DetailTitleArt`) in the title's width and a point, give or take half a line: a name cut short (an ellipsis) or
    /// shrunk to fit the title's box is shorter. Prints (a `hero, ` line) the lines the name takes laid out whole, those
    /// the title's height holds at that font, and how its first line stands out from what is behind it (as
    /// `requireHero` reads the metadata line): the top half of a line at the title's font, where the fade behind it is
    /// lightest; fails where that half line, on the screen, reads under 3:1 (WCAG's least for large text). Before
    /// `requireHero`, which may drag the first line away. `textSize`: iOS, the content size category the app was
    /// launched with (`launchDev`), nil for the system's.
    func requireName(_ what: String, shot name: String, textSize: String? = nil) {
        requireArtDrawn(what)
        shot(name)
        let title = require("detail.title", timeout: 5, "\(what): the details show the title")
        let label = text(of: title)
        let frame = title.frame
        if label.isEmpty { return fail("\(what): the title reads no name") }
        #if os(macOS)
        let font = NSFont.systemFont(ofSize: 40, weight: .bold)
        #else
        #if os(tvOS)
        let size = UIFont.preferredFont(forTextStyle: .title2).pointSize
        #else
        let category = textSize.map(UIContentSizeCategory.init(rawValue:)) ?? UITraitCollection.current.preferredContentSizeCategory
        let traits = UITraitCollection(preferredContentSizeCategory: category)
        // iPad's title: 40 pt at the default text size, growing as the large title does.
        let size = isPad
            ? UIFontMetrics(forTextStyle: .largeTitle).scaledValue(for: 40, compatibleWith: traits)
            : UIFont.preferredFont(forTextStyle: .largeTitle, compatibleWith: traits).pointSize
        #endif
        let font = UIFont.systemFont(ofSize: size, weight: .bold)
        #endif
        let line = font.ascender - font.descender + font.leading
        // The text is as wide as its longest line: at that width laid out alike, give or take rounding.
        let whole = (label as NSString).boundingRect(
            with: CGSize(width: frame.width + 1, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: font],
            context: nil
        ).height
        let lines = Int((whole / line).rounded())
        guard let pixels = ScreenPixels() else { return fail("\(what): no screenshot to measure") }
        let firstLine = CGRect(x: frame.minX, y: frame.minY, width: frame.width, height: min(line / 2, frame.height))
        let behind = pixels.medianLuminance(in: firstLine)
        let contrast = pixels.contrast(in: firstLine)
        print("hero, \(what): the name at \(font.pointSize) pt bold takes \(lines) lines, \(whole) pt, laid out whole in \(frame.width) pt; the title \(frame) holds \(frame.height / line) lines at that font; its first line \(firstLine): luminance behind it \(behind), contrast \(contrast):1")
        if frame.height < whole - line / 2 {
            fail("\(what): the name isn't whole: its \(lines) lines take \(whole) pt at the title's font, the title \(frame.height) pt")
        }
        #if os(macOS)
        let fold = app.windows.firstMatch.frame.maxY
        #else
        let fold = app.frame.maxY
        #endif
        if firstLine.maxY <= fold, contrast < 3 {
            fail("\(what): the name's first line reads at \(contrast):1 over what is behind it, under 3:1")
        }
    }

    /// Waits for the title's art to draw: its backdrop and its logo, where it has them (`detail.art`, debug builds:
    /// `backdrop=drawn logo=none`), so what is measured over the backdrop is measured over its picture, not the black
    /// page under it while the picture loads. Fails with `what` and the reading when either still loads after 30 s.
    func requireArtDrawn(_ what: String) {
        let art = element("detail.art")
        let reading = { art.exists ? self.text(of: art) : "no detail.art" }
        let drawn = {
            let value = reading()
            return value.hasPrefix("backdrop=") && !value.contains("loading")
        }
        if !waitUntil(30, drawn) { fail("\(what): the title's art hasn't drawn after 30 s: \(reading())") }
    }

    /// The bottom (in points) of the lowest pixel row in the title's box with a pixel `matching` (its red, green and
    /// blue) on the screen now.
    func titleBottom(matching: (UInt8, UInt8, UInt8) -> Bool) -> CGFloat? {
        ScreenPixels()?.lowestRow(in: element("detail.title").frame, matching: matching)
    }

    /// The test addon's logos are amber; its backdrop and the name are white or grey.
    static func heroLogoAmber(_ red: UInt8, _ green: UInt8, _ blue: UInt8) -> Bool {
        red >= 230 && green >= 120 && blue < 80
    }
}
