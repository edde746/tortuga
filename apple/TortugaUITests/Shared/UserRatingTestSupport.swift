import XCTest

/// What the Detail tests share about the user's own rating against the fake trackers (tools/media/test_trackers.py)
/// with every tracker connected (`--dev-trackers-connected`): the fake's
/// routes (`/_rating` gives or clears a title's rating on a service as if given there, `/_fail?ratings=` makes services'
/// rating writes fail, `/_log`'s `userRatings` what each service holds), the action that opens the picker (iOS and the
/// Mac: ⋯'s `detail.overflow.rate`; Apple TV: the row's round `detail.rate`; labelled "Rate this title", "Your rating:
/// 8", "Your rating: 7–8"), the read-only score at the ratings row's end once rated (`detail.userRating`, labelled as
/// the action), each tracker's line in the picker (`detail.userRating.tracker.<service>`, its status as its value),
/// and the stars (`detail.userRating.stars`, valued "8" or "Not rated"), whose lit half stars are read off the screen.
extension TortugaUITestCase {
    /// The Shawshank Redemption, rated in the tests, and Batman & Robin, which the fake holds rated 7 on Trakt and 8 on
    /// Simkl (`seedFakeRatings`): their scores differ.
    static let rateTitle = "detail:movie:tt0111161"
    static let rateRangeTitle = "detail:movie:tt0118688"
    static let rateImdb = "tt0111161"
    static let rateRangeImdb = "tt0118688"
    /// The services that rate a title that isn't anime, in the picker's order.
    static let raters = ["trakt", "simkl", "mdblist"]

    #if os(tvOS)
    /// The stars' row (`DetailRatingStars`): its padding at each end and the gap between two stars, in points.
    static let starsPadding: CGFloat = 8
    static let starsGap: CGFloat = 16
    #else
    static let starsPadding: CGFloat = 4
    static let starsGap: CGFloat = 8
    #endif

    /// The fake's answer to `path`, POSTed when `post` (its routes) else read, as JSON.
    @discardableResult
    func fakeTrackers(_ path: String, post: Bool = false) throws -> [String: Any] {
        guard let url = URL(string: try trackersURL + path) else { throw HarnessError("\(path) makes no URL of TORTUGA_TRACKERS") }
        let method = post ? "POST" : "GET"
        var request = URLRequest(url: url, timeoutInterval: 10)
        request.httpMethod = method
        let answer = FakeAnswer()
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { data, response, error in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if let data, status == 200 {
                answer.data = data
            } else {
                answer.failure = "\(method) \(path): \(error.map { "\($0)" } ?? "HTTP \(status)")"
            }
            done.signal()
        }.resume()
        done.wait()
        guard let data = answer.data else { throw HarnessError("the fake trackers didn't answer: \(answer.failure ?? "")") }
        return try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
    }

    /// The fake as the rating tests start from: no service's rating writes failing, The Shawshank Redemption rated on
    /// none, Batman & Robin 7 on Trakt and 8 on Simkl, none on MDBList (the fake keeps them for the whole run).
    func seedFakeRatings() throws {
        try fakeTrackers("/_fail?ratings=", post: true)
        for service in Self.raters {
            try fakeTrackers("/_rating?service=\(service)&imdb=\(Self.rateImdb)", post: true)
        }
        try fakeTrackers("/_rating?service=trakt&imdb=\(Self.rateRangeImdb)&rating=7", post: true)
        try fakeTrackers("/_rating?service=simkl&imdb=\(Self.rateRangeImdb)&rating=8", post: true)
        try fakeTrackers("/_rating?service=mdblist&imdb=\(Self.rateRangeImdb)", post: true)
    }

    /// Each service's ratings of `imdb` in the fake: none, or the one it holds.
    func fakeRatings(_ imdb: String) throws -> [String: [Int]] {
        let held = try fakeTrackers("/_log")["userRatings"] as? [String: [[String: Any]]] ?? [:]
        return held.mapValues { entries in
            entries.filter { ($0["ids"] as? [String: Any])?["imdb"] as? String == imdb }.compactMap { $0["rating"] as? Int }
        }
    }

    /// Waits up to `timeout` for the fake to hold `expected` of `imdb` (`fakeRatings`); fails with `what` when it doesn't.
    func requireFakeRatings(_ imdb: String, _ expected: [String: [Int]], timeout: TimeInterval = 30, _ what: String) {
        var held: [String: [Int]] = [:]
        if !waitUntil(timeout, {
            held = (try? fakeRatings(imdb)) ?? [:]
            return held == expected
        }) {
            fail("\(what): the fake holds \(held), not \(expected)")
        }
    }

    /// Waits up to `timeout` for the read-only score at the ratings row's end to be labelled `label` ("Your rating:
    /// 8"); fails with `what` when it isn't.
    @discardableResult
    func requireRatingItem(_ label: String, timeout: TimeInterval = 20, _ what: String) -> XCUIElement {
        let item = require("detail.userRating", timeout: timeout, what)
        if !waitUntil(timeout, { item.label == label }) {
            fail("\(what): detail.userRating is labelled \"\(item.label)\", not \"\(label)\"")
        }
        return item
    }

    /// Fails with `what` when the ratings row shows a score of the user's own (`detail.userRating`) within 5 s: an
    /// unrated title, or none without trackers.
    func requireNoRatingItem(_ what: String) {
        if waitUntil(5, { element("detail.userRating").exists }) { fail("\(what): the ratings row has detail.userRating") }
    }

    #if os(tvOS)
    /// Waits up to `timeout` for the action row's Rate (`detail.rate`) to be labelled `label`; fails with `what` when
    /// it isn't.
    @discardableResult
    func requireRateAction(_ label: String, timeout: TimeInterval = 20, _ what: String) -> XCUIElement {
        let rate = require("detail.rate", timeout: timeout, what)
        if !waitUntil(timeout, { rate.label == label }) {
            fail("\(what): detail.rate is labelled \"\(rate.label)\", not \"\(label)\"")
        }
        return rate
    }
    #endif

    /// Waits up to `timeout` for the picker's lines to read `expected` (each service's status), no other service's
    /// line there; fails with `what` when they don't. On the Mac, whose XCUITest reads no value of a line
    /// (`requireStars`), only which services' lines show.
    func requireRatingLines(_ expected: [String: String], timeout: TimeInterval = 60, _ what: String) {
        #if os(macOS)
        let expected = expected.mapValues { _ in "" }
        #endif
        var lines: [String: String] = [:]
        if !waitUntil(timeout, {
            lines = [:]
            for service in Self.raters + ["mal", "anilist"] {
                let line = element("detail.userRating.tracker.\(service)")
                if line.exists { lines[service] = line.value as? String ?? "" }
            }
            return lines == expected
        }) {
            fail("\(what): the trackers' lines read \(lines), not \(expected)")
        }
    }

    /// Every service's line reading `status`.
    static func lines(_ status: String) -> [String: String] {
        Dictionary(uniqueKeysWithValues: raters.map { ($0, status) })
    }

    /// Fails with `what` unless the stars give `score` (nil: none) as their value, and light exactly its half stars,
    /// read off the screen: a half star is lit where over a tenth of its pixels are near white (each channel at least
    /// 230, the text colour); the unlit fill, white at 20 %, stays far under that. The Mac's XCUITest reads no value of
    /// the stars (nor of the trackers' lines: macOS 27), so there the lit stars alone are checked, once the
    /// caller has seen the score arrive elsewhere.
    func requireStars(_ score: Int?, _ what: String) {
        let stars = require("detail.userRating.stars", what)
        #if !os(macOS)
        let value = score.map(String.init) ?? "Not rated"
        if !waitUntil(10, { stars.value as? String == value }) {
            fail("\(what): the stars' value is \"\(stars.value as? String ?? "")\", not \"\(value)\"")
        }
        #endif
        // The stars redraw with the value: read until they light its half stars, for up to 5 s.
        let expected = Array((0..<(score ?? 0)).map { $0 + 1 })
        var read: [Int]?
        _ = waitUntil(5) {
            guard let pixels = ScreenPixels() else { return false }
            let frame = stars.frame
            let star = (frame.width - 2 * Self.starsPadding - 4 * Self.starsGap) / 5
            read = (1...10).filter { point in
                let half = halfStar(point, in: frame, star: star).insetBy(dx: 1, dy: 0)
                return pixels.share(in: half) { min($0, $1, $2) >= 230 } > 0.1
            }
            return read == expected
        }
        guard let lit = read else { return fail("\(what): no screenshot to read the stars from") }
        if lit != expected {
            fail("\(what): the stars light \(lit), not \(expected.isEmpty ? "none" : "1–\(score ?? 0)")")
        }
    }

    /// Point `point`'s half star in the stars' `frame`, each star `star` wide: a star's left half its odd point, its
    /// right half the even one.
    private func halfStar(_ point: Int, in frame: CGRect, star: CGFloat) -> CGRect {
        let index = CGFloat((point - 1) / 2)
        let x = frame.minX + Self.starsPadding + index * (star + Self.starsGap) + (point.isMultiple(of: 2) ? star / 2 : 0)
        return CGRect(x: x, y: frame.minY, width: star / 2, height: frame.height)
    }

    #if !os(tvOS)
    /// Taps (the Mac: clicks) point `point`'s half star, at its middle.
    func giveScore(_ point: Int) {
        let stars = ready(require("detail.userRating.stars", "the picker shows the stars"))
        let frame = stars.frame
        let star = (frame.width - 2 * Self.starsPadding - 4 * Self.starsGap) / 5
        let half = halfStar(point, in: frame, star: star)
        let target = stars.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: half.midX - frame.minX, dy: frame.height / 2))
        #if os(macOS)
        target.click()
        #else
        target.tap()
        #endif
    }

    /// Opens ⋯ and waits for its Rate (`detail.overflow.rate`), there once a tracker that rates the title has answered:
    /// until `timeout`, ⋯ is closed and opened again while it doesn't list Rate. The menu is left open on the entry.
    func rateEntry(timeout: TimeInterval = 20, _ what: String) -> XCUIElement {
        let entry = element("detail.overflow.rate")
        let entries = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'detail.overflow.'"))
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            let more = ready(require("detail.overflow", timeout: max(deadline.timeIntervalSinceNow, 1), "\(what): the action row has ⋯"))
            #if os(macOS)
            more.click()
            #else
            more.tap()
            #endif
            if entry.exists(within: 5) { return entry }
            closeRatingMenu()
            _ = waitUntil(5) { !entries.firstMatch.exists }
        } while Date() < deadline
        fail("\(what): ⋯ doesn't list detail.overflow.rate after \(Int(timeout)) s")
        return entry
    }

    /// ⋯'s Rate's words: a Mac menu item carries them as its title, its label empty.
    private func words(_ entry: XCUIElement) -> String { entry.label.isEmpty ? entry.title : entry.label }

    /// Waits up to `timeout` for ⋯'s Rate to read `label` ("Rate this title", "Your rating: 8"), then closes ⋯;
    /// fails with `what` when it doesn't.
    func requireRateAction(_ label: String, timeout: TimeInterval = 20, _ what: String) {
        let entry = rateEntry(timeout: timeout, what)
        if !waitUntil(5, { words(entry) == label }) {
            fail("\(what): detail.overflow.rate reads \"\(words(entry))\", not \"\(label)\"")
        }
        closeRatingMenu()
        _ = waitUntil(5) { menuClosed(entry) }
    }

    /// Fails with `what` when ⋯, where the row has it, lists Rate: no tracker rates the title.
    func requireNoRateAction(_ what: String) {
        let more = element("detail.overflow")
        guard more.exists(within: 5) else { return }
        #if os(macOS)
        ready(more).click()
        #else
        ready(more).tap()
        #endif
        let entries = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'detail.overflow.'"))
        _ = entries.firstMatch.exists(within: 5)
        let listed = element("detail.overflow.rate").exists
        closeRatingMenu()
        _ = waitUntil(5) { !entries.firstMatch.exists }
        if listed { fail("\(what): ⋯ lists detail.overflow.rate") }
    }

    /// Closes the open ⋯ menu: Escape on the Mac; on iOS a tap at the screen's trailing edge, away from its entries.
    private func closeRatingMenu() {
        #if os(macOS)
        app.typeKey(.escape, modifierFlags: [])
        #else
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.75)).tap()
        #endif
    }

    /// Opens the picker from ⋯'s Rate, checking first that it reads `label` where given.
    func openRatingPicker(_ what: String, label: String? = nil, timeout: TimeInterval = 20) {
        let entry = rateEntry(timeout: timeout, what)
        if let label, !waitUntil(5, { words(entry) == label }) {
            fail("\(what): detail.overflow.rate reads \"\(words(entry))\", not \"\(label)\"")
        }
        pickMenuEntry(entry)
        require("detail.userRating.sheet", timeout: 5, "\(what): ⋯'s Rate opens the picker")
        require("detail.userRating.stars", timeout: 5, "\(what): the picker shows the stars")
    }

    /// Closes the picker: its Close (iPad, the Mac), else a tap on the dimmed page above the iPhone's sheet.
    func closeRatingPicker(_ what: String) {
        let close = element("detail.userRating.close")
        if close.exists {
            #if os(macOS)
            ready(close).click()
            #else
            ready(close).tap()
            #endif
        } else {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
        }
        requireGone("detail.userRating.sheet", "\(what): the picker closes")
    }
    #endif
}

/// What the fake answered, written on URLSession's queue while the test waits for it.
private final class FakeAnswer: @unchecked Sendable {
    var data: Data?
    var failure: String?
}
