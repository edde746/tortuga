import CoreText
import SwiftUI
import TortugaKit

/// A stream's description in the footnote style, from its runs (`StreamRow.descriptionRuns`): small capitals in the
/// system font's own, super- and subscripts smaller and raised or lowered, and each text symbol the system font lacks
/// (one another font draws) scaled to the cap height and centred on it, all within the line's height, so every line is
/// as tall as a plain one. A description without these is the plain Text it was.
struct StreamDescriptionText: View {
    let text: String
    let runs: [StreamTextRun]

    #if !os(macOS)
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    #endif

    var body: some View {
        if runs.allSatisfy({ $0.style == .plain }) {
            Text(text).font(.footnote)
        } else {
            let font = footnote
            Text(Self.attributed(runs, font: font)).font(Font(font))
        }
    }

    /// The footnote style's font at the environment's text size (the Mac has one size).
    private var footnote: CTFont {
        #if os(macOS)
        NSFont.preferredFont(forTextStyle: .footnote)
        #else
        UIFont.preferredFont(
            forTextStyle: .footnote, compatibleWith: UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(dynamicTypeSize))
        )
        #endif
    }

    /// The fraction of the font's size a super- or subscript is drawn at.
    private static let scriptScale: CGFloat = 0.7

    /// `runs` in `font`: each style's font and baseline offset on its run; plain runs take the Text's own font.
    @MainActor
    static func attributed(_ runs: [StreamTextRun], font: CTFont) -> AttributedString {
        let size = CTFontGetSize(font)
        let script = Font(CTFontCreateCopyWithAttributes(font, size * scriptScale, nil, nil))
        var text = AttributedString()
        for run in runs {
            var part = AttributedString(run.text)
            switch run.style {
            case .plain:
                break
            case .smallCaps:
                part.font = Font(font).lowercaseSmallCaps()
            case .superscript:
                // Its capitals' tops at the text's cap height.
                part.font = script
                part.baselineOffset = CTFontGetCapHeight(font) * (1 - scriptScale)
            case .subscript:
                // As low as its smaller descent goes without reaching past the text's.
                part.font = script
                part.baselineOffset = -CTFontGetDescent(font) * (1 - scriptScale)
            case .symbol:
                for symbol in run.text {
                    var one = AttributedString(String(symbol))
                    if let fit = fitted(symbol, font: font) {
                        one.font = Font(fit.font)
                        one.baselineOffset = fit.offset
                    }
                    text += one
                }
                continue
            }
            text += part
        }
        return text
    }

    /// The font another font draws `symbol` in at its fitted size, with its baseline offset; nil where `font` has the
    /// symbol itself. Cached by symbol and size: a list's rows repeat a handful.
    @MainActor
    private static func fitted(_ symbol: Character, font: CTFont) -> Fit? {
        let key = FitKey(symbol: symbol, size: CTFontGetSize(font))
        if let cached = fits[key] { return cached }
        let fit = fit(symbol, font: font)
        fits[key] = .some(fit)
        return fit
    }

    private struct FitKey: Hashable {
        let symbol: Character
        let size: CGFloat
    }

    private struct Fit {
        let font: CTFont
        let offset: CGFloat
    }

    @MainActor private static var fits: [FitKey: Fit?] = [:]

    /// `symbol` (with any variation selector after it) in the font that draws it, scaled so its ink is the cap height
    /// tall and at most an em wide, centred on the cap height; smaller where that font's ascent or descent at that size,
    /// so placed, would reach past `font`'s and make the line taller.
    private static func fit(_ symbol: Character, font: CTFont) -> Fit? {
        let string = String(symbol)
        let fallback = CTFontCreateForString(font, string as CFString, CFRange(location: 0, length: string.utf16.count))
        if CTFontCopyPostScriptName(fallback) == CTFontCopyPostScriptName(font) { return nil }
        var characters = Array(string.utf16)
        var glyphs = [CGGlyph](repeating: 0, count: characters.count)
        CTFontGetGlyphsForCharacters(fallback, &characters, &glyphs, characters.count)
        // The symbol's own glyph: a variation selector after it draws nothing.
        let ink = CTFontGetBoundingRectsForGlyphs(fallback, .default, glyphs, nil, 1)
        guard ink.height > 0, ink.width > 0 else { return nil }
        let size = CTFontGetSize(font)
        let middle = CTFontGetCapHeight(font) / 2
        // At scale s the ink's middle sits at `middle` with the baseline offset middle - ink.midY * s, so the other
        // font's ascent reaches middle + (ascent - midY) * s and its descent middle - (descent + midY) * s below.
        var scale = min(2 * middle / ink.height, size / ink.width)
        let rise = CTFontGetAscent(fallback) - ink.midY
        let fall = CTFontGetDescent(fallback) + ink.midY
        if rise > 0 { scale = min(scale, (CTFontGetAscent(font) - middle) / rise) }
        if fall > 0 { scale = min(scale, (CTFontGetDescent(font) + middle) / fall) }
        return Fit(font: CTFontCreateCopyWithAttributes(fallback, size * scale, nil, nil), offset: middle - ink.midY * scale)
    }
}
