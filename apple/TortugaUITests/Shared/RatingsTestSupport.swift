import XCTest

/// What the Detail tests share about the ratings row under the metadata line: an item per source's score, its logo
/// and the score (`detail.rating.<key>`), labelled with the source's name and the score as VoiceOver reads it; on iOS
/// and macOS IMDb's (the addon's IMDb link) the only button, on Apple TV none.
extension TortugaUITestCase {
    /// The fake tracker server's titles (tools/media/test_trackers.py) with `--dev-trackers-connected`: Spirited
    /// Away has a score from every source (the anime ones once the anime-id datasets are in), Batman & Robin the
    /// non-anime sources with Rotten Tomatoes' scores under 60 % (rotten, spilled).
    static let fullRatingsTitle = "detail:movie:tt0245429"
    static let rottenRatingsTitle = "detail:movie:tt0118688"

    /// Every source's key in the row's order (`RatingSource`), with how its item's label reads.
    static let ratingSources: [(key: String, label: String)] = [
        ("rt_critics", #"Rotten Tomatoes critics \d{1,3} percent"#),
        ("rt_audience", #"Rotten Tomatoes audience \d{1,3} percent"#),
        ("imdb", #"IMDb \d{1,2}\.\d"#),
        ("tmdb", #"TMDB \d{1,3} percent"#),
        ("metacritic", #"Metacritic \d{1,3}"#),
        ("letterboxd", #"Letterboxd \d\.\d"#),
        ("trakt", #"Trakt \d{1,3} percent"#),
        ("simkl", #"Simkl \d{1,2}\.\d"#),
        ("mal", #"MyAnimeList \d{1,2}\.\d{2}"#),
        ("anilist", #"AniList \d{1,3} percent"#),
    ]

    /// The non-anime sources: what the fake has for a title that isn't anime.
    static let nonAnimeRatingKeys = ["rt_critics", "rt_audience", "imdb", "tmdb", "metacritic", "letterboxd", "trakt", "simkl"]

    /// Waits up to `timeout` for the ratings row to hold exactly `keys` (the row grows as each tracker answers), then
    /// fails with `what` unless: each item's label reads as its source's (`ratingSources`); IMDb's is a button and no
    /// other is (on tvOS none is); the items follow the row's order, line by line without overlapping, all as tall as
    /// each other and the same distance apart along a line, the user's own score (`detail.userRating`, read-only, not a
    /// button), where the title is rated, last at that distance; the row lies under the title and above Play, inside
    /// the screen (iOS: its 16 pt margins); and, `centred` (iPhone), each line is centred on the screen.
    func requireRatings(_ keys: [String], timeout: TimeInterval = 20, centred: Bool = false, _ what: String) {
        let shown = { Self.ratingSources.map { $0.key }.filter { self.element("detail.rating.\($0)").exists } }
        if !waitUntil(timeout, { shown() == keys }) {
            fail("\(what): the ratings row holds \(shown()) after \(Int(timeout)) s, not \(keys)")
        }
        // The row settles: the last item may still be moving into its line, and the user's own score comes as the
        // trackers answer for it, moving a centred line as it does. Read once its items stay put for 2 s.
        let userRating = element("detail.userRating")
        let frames = { keys.map { self.element("detail.rating.\($0)").frame } + (userRating.exists ? [userRating.frame] : []) }
        var last: [CGRect] = [], since = Date()
        _ = waitUntil(15) {
            let now = frames()
            if now != last { (last, since) = (now, Date()) }
            return Date().timeIntervalSince(since) >= 2
        }
        var shownFrames: [String: CGRect] = [:]
        for (key, pattern) in Self.ratingSources where keys.contains(key) {
            let item = element("detail.rating.\(key)")
            let label = text(of: item)
            if label.range(of: "^\(pattern)$", options: .regularExpression) == nil {
                fail("\(what): detail.rating.\(key) is labelled \"\(label)\", not as \(pattern)")
            }
            #if os(tvOS)
            let button = false
            #else
            let button = key == "imdb"
            #endif
            if (item.elementType == .button) != button {
                fail("\(what): detail.rating.\(key) is \(item.elementType == .button ? "" : "not ")a button")
            }
            shownFrames[key] = item.frame
        }
        let scores = keys.compactMap { key in shownFrames[key].map { (id: "detail.rating.\(key)", frame: $0) } }
        guard let first = scores.first?.frame else { return }
        let row = scores + (userRating.exists ? [(id: "detail.userRating", frame: userRating.frame)] : [])
        if userRating.exists, userRating.elementType == .button { fail("\(what): detail.userRating is a button") }
        let items = row.map(\.frame)
        for (id, frame) in scores where abs(frame.height - first.height) > 1 {
            fail("\(what): \(id) is \(Int(frame.height)) pt tall, detail.rating.\(keys[0]) \(Int(first.height)) pt")
        }
        var gaps: [(id: String, gap: CGFloat)] = []
        for ((leftId, left), (rightId, right)) in zip(row, row.dropFirst()) {
            let sameLine = abs(left.midY - right.midY) < 2
            if sameLine ? right.minX < left.maxX : right.minY < left.maxY {
                fail("\(what): \(rightId) (\(right)) doesn't follow \(leftId) (\(left))")
            }
            if sameLine { gaps.append((rightId, right.minX - left.maxX)) }
        }
        if let widest = gaps.max(by: { $0.gap < $1.gap }), let narrowest = gaps.min(by: { $0.gap < $1.gap }),
           widest.gap - narrowest.gap > 1 {
            fail("\(what): \(widest.id) is \(Int(widest.gap)) pt from the item before it, \(narrowest.id) \(Int(narrowest.gap)) pt")
        }
        let span = items.dropFirst().reduce(first) { $0.union($1) }
        let title = require("detail.title", timeout: 5, "\(what): the details show the title").frame
        let play = require("detail.play", timeout: 5, "\(what): the details show Play").frame
        if span.minY < title.maxY - 1 || span.maxY > play.minY + 1 {
            fail("\(what): the ratings row runs from y \(Int(span.minY)) to \(Int(span.maxY)), not between the title (to \(Int(title.maxY))) and Play (from \(Int(play.minY)))")
        }
        let screen = app.windows.firstMatch.frame
        #if os(iOS)
        let margin: CGFloat = 15
        #else
        let margin: CGFloat = 0
        #endif
        if span.minX < screen.minX + margin || span.maxX > screen.maxX - margin {
            fail("\(what): the ratings row runs from x \(Int(span.minX)) to \(Int(span.maxX)), past the screen's \(Int(screen.minX + margin))–\(Int(screen.maxX - margin))")
        }
        if centred {
            for line in Dictionary(grouping: items, by: { Int($0.midY.rounded()) }).values {
                let span = line.dropFirst().reduce(line[0]) { $0.union($1) }
                if abs(span.midX - screen.midX) > 2 {
                    fail("\(what): a line of the ratings row is centred at x \(Int(span.midX)), the screen at \(Int(screen.midX))")
                }
            }
        }
    }

    /// Fails with `what` unless both Rotten Tomatoes scores are under the 60 % line, where the row draws the rotten
    /// tomato and the spilled popcorn.
    func requireRotten(_ what: String) {
        for key in ["rt_critics", "rt_audience"] {
            let label = text(of: element("detail.rating.\(key)"))
            let value = label.split(separator: " ").compactMap { Double($0) }.last
            if (value ?? 100) >= 60 { fail("\(what): detail.rating.\(key) reads \"\(label)\", not under 60 percent") }
        }
    }
}
