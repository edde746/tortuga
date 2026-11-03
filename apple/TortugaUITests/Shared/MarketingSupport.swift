import XCTest

/// What the marketing captures share (each platform's `MarketingShots`: one test per shot of the store screenshot list,
/// `test` and the shot's id, `testHome` for `home`): a dev session with the capture's addons alone
/// (`--dev-addons-only`: the marketing addon serving its catalog, invented titles over blurred images and clips of open
/// movies, and its stream-only addons; the website's captures keep the session's own addons too,
/// `TORTUGA_MARKETING_DEFAULTS`), the dev profile named as the capture says (`--dev-profile`), the app in the pass's
/// language (one pass of the tests per language, each launching the app in its language through `TORTUGA_LANGUAGE`),
/// and a screenshot of the whole screen per shot (`marketing-<shot>`) beside its metadata. The tests find what
/// they act on by identifiers, frames and the catalog's own names, never by the app's words, which change with the
/// language: the player's state comes from its Now Playing probe (`player.nowPlaying`, debug builds), the subtitle
/// entry by its language's name in the app's language, and the remote's moves from the elements' frames (Arabic lays
/// out right to left). What to show comes from the launch environment, which the run fills from the catalog's
/// `capture` for the device (the marketing addon's `--capture-env`):
/// - `TORTUGA_MARKETING_ADDONS`: the addons every launch installs, in order (`--dev-addon` each): their manifests' paths
///   on the marketing addon's server, the catalog addon first (`manifest.json,<path>/manifest.json`), comma-separated;
/// - `TORTUGA_MARKETING_DEFAULTS`: set, the dev session keeps its own addons (Cinemeta, OpenSubtitles) before those:
///   the website's captures, in real metadata, with the demo addon's streams;
/// - `TORTUGA_MARKETING_PROGRESS`: the watch progress the launches start with, `--dev-progress` specs
///   (`series:demo-tidewater:demo-tidewater:1:3:1319:3140`) comma-separated, Continue Watching's order;
/// - `TORTUGA_MARKETING_WATCHED`: the videos those launches start with marked watched, `--dev-watched` specs
///   (`series:tt0944947:tt0944947:1:1`) comma-separated;
/// - `TORTUGA_MARKETING_HOME`: text in the label of the Continue Watching card Home is shot with (tvOS: focused);
/// - `TORTUGA_MARKETING_OPEN_DETAIL`: the Detail screen shot, as `--dev-open` takes it (`detail:series:demo-tidewater`);
/// - `TORTUGA_MARKETING_DETAIL_PROGRESS`, `TORTUGA_MARKETING_DETAIL_WATCHED`: progress and watched videos the Detail
///   shot's launch starts with besides those above, for a Detail title outside Continue Watching, so Home's stays as
///   configured;
/// - `TORTUGA_MARKETING_DETAIL_FOCUS`: `episodes`, Apple TV's Detail shot is taken with focus on the episode row
///   (the page then shows the row, which a long title block leaves off the screen at Play); unset, on Play;
/// - `TORTUGA_MARKETING_ITEM`: the title Streams and the player are shot on, `<type>:<id>` (`series:demo-tidewater`);
/// - `TORTUGA_MARKETING_VIDEO`: its video, an episode's id (`demo-tidewater:1:3`) or the movie's;
/// - `TORTUGA_MARKETING_SUBTITLE`: the language of the subtitle track to show, by its English name (`English`), which
///   the subtitle menu names in the app's language (`Englisch`); unset keeps the player's choice;
/// - `TORTUGA_MARKETING_LINES`: the subtitle line the player is shot on, `<start>-<end>` in seconds (the first of a
///   comma-separated list);
/// - `TORTUGA_MARKETING_PLAY_PROGRESS`: the played video's own watch progress, a `--dev-progress` spec, which the
///   player's launch starts with: the stream picked resumes there (the website's captures: the full film, resumed at
///   the frame); unset, the stream plays from the start;
/// - `TORTUGA_MARKETING_FRAME`: where the player is shot instead of on a subtitle line, `<start>-<end>` in seconds:
///   the shot holding the frame, from the resume point on;
/// - `TORTUGA_MARKETING_SEARCH`: the query Search is shot with;
/// - `TORTUGA_MARKETING_DISCOVER`: the genre Discover's first catalog is shot with, picked from its Genre filter;
/// - `TORTUGA_MARKETING_LIBRARY`: the titles every launch starts with in the library, `--dev-library` specs
///   (`movie:demo-saltmarsh`) comma-separated, the first leading the Library;
/// - `TORTUGA_MARKETING_PROFILE`: the dev profile's name and avatar, as `--dev-profile` takes them (`Mara:sea`); unset
///   keeps the dev session's;
/// - `TORTUGA_MARKETING_WATCH`: who is in the room Watch together is shot in, its host first, comma-separated: the
///   scripted Open Watch Party participants, in a room on a relay of the run's own: the test reads the relay
///   as `TORTUGA_WATCH_RELAY` and the room's code as `TORTUGA_WATCH_CODE`.
class MarketingTestCase: TortugaUITestCase {
    /// A subtitle line: when it shows, in seconds of the video.
    struct Line {
        let start: Double
        let end: Double

        /// The position label's whole seconds (`14:08`, floored) throughout which the line shows; nil for a line too
        /// short to hold one.
        var shownSeconds: ClosedRange<Double>? {
            let first = start.rounded(.up)
            let last = (end - 1).rounded(.down)
            return first <= last ? first...last : nil
        }

        var label: String { "\(MarketingTestCase.clock(start))–\(MarketingTestCase.clock(end))" }
    }

    /// The title Streams and the player are shot on, `<type>:<id>`.
    var marketingItem: (type: String, id: String) {
        get throws {
            let value = try Self.required("TORTUGA_MARKETING_ITEM")
            guard let colon = value.firstIndex(of: ":"), colon != value.startIndex, value.index(after: colon) != value.endIndex else {
                throw HarnessError("TORTUGA_MARKETING_ITEM is <type>:<id>, got '\(value)'")
            }
            return (String(value[..<colon]), String(value[value.index(after: colon)...]))
        }
    }

    /// Text in the label of the Continue Watching card Home is shot with.
    var marketingHome: String? { Self.optional("TORTUGA_MARKETING_HOME") }

    /// The Detail screen shot, as `--dev-open` takes it.
    var marketingDetail: String {
        get throws { try Self.required("TORTUGA_MARKETING_OPEN_DETAIL") }
    }

    /// Whether Apple TV's Detail shot is taken with focus on the episode row rather than on Play.
    var marketingDetailOnEpisodes: Bool { Self.optional("TORTUGA_MARKETING_DETAIL_FOCUS") == "episodes" }

    /// The configured subtitle language's names an entry of the subtitle menu may carry: its English name as configured
    /// (`English`, the track's title) and its name in the app's language (`Englisch`, `الإنجليزية`), as the menu names a
    /// track's language; none when unset.
    var marketingSubtitleNames: [String] {
        guard let subtitle = Self.optional("TORTUGA_MARKETING_SUBTITLE") else { return [] }
        let english = Locale(identifier: "en")
        let code = Locale.LanguageCode.isoLanguageCodes.first { english.localizedString(forLanguageCode: $0.identifier) == subtitle }
        let local = code.flatMap { Locale(identifier: Self.languages[0]).localizedString(forLanguageCode: $0.identifier) }
        return [subtitle] + (local.map { [$0] } ?? [])
    }

    /// Whether `text` (a subtitle entry's label or detail) names the configured subtitle language.
    func namesMarketingSubtitle(_ text: String) -> Bool {
        marketingSubtitleNames.contains { text.localizedCaseInsensitiveContains($0) }
    }

    /// The capture's addons' manifest URLs, in install order: each path of `TORTUGA_MARKETING_ADDONS` on the server of
    /// the catalog addon's manifest (`TORTUGA_ADDON`).
    var marketingAddons: [String] {
        get throws {
            let paths = Self.list("TORTUGA_MARKETING_ADDONS")
            if paths.isEmpty { throw HarnessError("TORTUGA_MARKETING_ADDONS names no addon") }
            let manifest = try addonURL
            guard let root = URL(string: manifest)?.deletingLastPathComponent() else {
                throw HarnessError("TORTUGA_ADDON is no URL: \(manifest)")
            }
            return paths.map { root.appendingPathComponent($0.trimmingCharacters(in: .whitespaces)).absoluteString }
        }
    }

    /// Whether the player is paused, as its Now Playing probe reads (`player.nowPlaying`: `…; state=paused; …`): the
    /// same in every language, where play/pause's label is the app's word for Play.
    var playerPaused: Bool {
        let probe = element("player.nowPlaying")
        return probe.exists && (probe.value as? String)?.contains("state=paused") == true
    }

    /// The subtitle line the player is shot on.
    var marketingLine: Line {
        get throws {
            let spec = try Self.required("TORTUGA_MARKETING_LINES").split(separator: ",")[0]
            let bounds = spec.split(separator: "-").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
            guard bounds.count == 2, bounds[0] < bounds[1] else {
                throw HarnessError("TORTUGA_MARKETING_LINES holds <start>-<end> seconds, got '\(spec)'")
            }
            return Line(start: bounds[0], end: bounds[1])
        }
    }

    /// Where the player is shot, paused, when the capture resumes the video at a frame rather than seeking to a line:
    /// the seconds the shot holds; nil when unset.
    var marketingFrame: ClosedRange<Double>? {
        get throws {
            guard let spec = Self.optional("TORTUGA_MARKETING_FRAME") else { return nil }
            let bounds = spec.split(separator: "-").compactMap { Double($0.trimmingCharacters(in: .whitespaces)) }
            guard bounds.count == 2, bounds[0] < bounds[1] else {
                throw HarnessError("TORTUGA_MARKETING_FRAME holds <start>-<end> seconds, got '\(spec)'")
            }
            return bounds[0]...bounds[1]
        }
    }

    /// The query Search is shot with.
    var marketingSearch: String {
        get throws { try Self.required("TORTUGA_MARKETING_SEARCH") }
    }

    /// The genre Discover's first catalog is shot with.
    var marketingDiscover: String {
        get throws { try Self.required("TORTUGA_MARKETING_DISCOVER") }
    }

    /// The room Watch together is shot in: the run's relay, the room's code and who is in it, its host first.
    var marketingRoom: (relay: String, code: String, names: [String]) {
        get throws {
            let names = Self.list("TORTUGA_MARKETING_WATCH")
            if names.isEmpty { throw HarnessError("TORTUGA_MARKETING_WATCH names no one in the room") }
            return (try Self.required("TORTUGA_WATCH_RELAY"), try Self.required("TORTUGA_WATCH_CODE"), names)
        }
    }

    /// Launches the dev session with the capture's addons alone on `open` (`--dev-open` screens; none: Home), the dev
    /// profile named and its library filled as configured, with the configured watch progress where `progress` (and
    /// the Detail shot's own where `detail`, the played video's own where `played`), and `settings` (`--dev-setting`).
    func launchMarketing(open: [String] = [], progress: Bool, detail: Bool = false, played: Bool = false, settings: [String] = []) throws {
        executionTimeAllowance = 900
        var arguments = ["--dev"]
        if Self.optional("TORTUGA_MARKETING_DEFAULTS") == nil { arguments.append("--dev-addons-only") }
        for manifest in try marketingAddons { arguments += ["--dev-addon", manifest] }
        if let profile = Self.optional("TORTUGA_MARKETING_PROFILE") { arguments += ["--dev-profile", profile] }
        for spec in Self.list("TORTUGA_MARKETING_LIBRARY") { arguments += ["--dev-library", spec] }
        for setting in settings { arguments += ["--dev-setting", setting] }
        for screen in open { arguments += ["--dev-open", screen] }
        if progress {
            for spec in Self.list("TORTUGA_MARKETING_PROGRESS") { arguments += ["--dev-progress", spec] }
            for spec in Self.list("TORTUGA_MARKETING_WATCHED") { arguments += ["--dev-watched", spec] }
        }
        if detail {
            for spec in Self.list("TORTUGA_MARKETING_DETAIL_PROGRESS") { arguments += ["--dev-progress", spec] }
            for spec in Self.list("TORTUGA_MARKETING_DETAIL_WATCHED") { arguments += ["--dev-watched", spec] }
        }
        if played {
            for spec in Self.list("TORTUGA_MARKETING_PLAY_PROGRESS") { arguments += ["--dev-progress", spec] }
        }
        launch(arguments)
    }

    /// Launches the dev session on the configured Detail screen over Home, with the configured watch progress and the
    /// Detail shot's own.
    func launchMarketingDetail() throws {
        try launchMarketing(open: ["board", try marketingDetail], progress: true, detail: true)
    }

    /// Launches the dev session on the configured video's Streams, over its title's Detail screen over Home, so Back
    /// leads where a viewer would have come from; without watch progress but the played video's own, so the stream
    /// picked plays from the start, or resumes where the capture sets `TORTUGA_MARKETING_PLAY_PROGRESS`.
    func launchMarketingStreams() throws {
        let item = try marketingItem
        let video = try Self.required("TORTUGA_MARKETING_VIDEO")
        try launchMarketing(open: ["board", "detail:\(item.type):\(item.id)", "streams:\(item.type):\(item.id):\(video)"], progress: false, played: true)
    }

    /// Waits until the Streams list has a group for each of the capture's addons (`streams.group.<i>.name`) and none of
    /// them still loading, so the list shows every addon's streams; fails with `what` otherwise.
    func requireEveryAddonsStreams(_ what: String) throws {
        let addons = try marketingAddons.count
        let loading = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'streams.group.' AND identifier ENDSWITH '.loading'")).firstMatch
        let groups = { (0..<8).prefix { self.element("streams.group.\($0).name").exists }.count }
        if !waitUntil(90, { groups() >= addons && !loading.exists }) {
            fail("\(what): the list names \(groups()) addons' groups\(loading.exists ? ", one still loading" : ""), not the capture's \(addons)")
        }
    }

    /// Waits until the room lists `names` and no one else but the app: no `watch.room.participant.<i>` past the app's
    /// row. On the iPhone the rows run below the fold, where the list hasn't made them (the guest's too where the room's
    /// lines wrap: ru), so the rows are read with Leave room, the form's end, in view (the rows just above it), and the
    /// form then goes back to its top for the shot. Each language's pass leaves the room after its shot; one that failed
    /// before leaving stays listed (away) for the relay's resume window, 5 min, which this waits out (on Apple TV the
    /// room then stays scrolled where the longer list put it, the tab bar cut: retake the shot). Fails with `what`
    /// otherwise.
    func requireRoom(of names: [String], _ what: String) {
        #if os(iOS)
        swipeUntilShown("watch.room.leave", "\(what): the room ends in Leave room")
        #endif
        for name in names { requireParticipant(named: name, "\(what): the room lists \(name)") }
        let extra = element("watch.room.participant.\(names.count + 1)")
        if !waitUntil(360, { !extra.exists }) {
            fail("\(what): the room lists more than \(names.joined(separator: ", ")) and the app")
        }
        #if os(iOS)
        let list = frontmostList
        for _ in 0..<3 { list.swipeDown(velocity: .fast) }
        require("watch.room.qr", timeout: 5, "\(what): the room is back at its top, its QR code")
        #endif
    }

    /// The Continue Watching card whose label contains `text`.
    func continueWatchingCard(_ text: String) -> XCUIElement {
        app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH 'board.cw.' AND label CONTAINS %@", text)).firstMatch
    }

    /// The player's duration in seconds: the second timecode of the store's position label (`1:23 / 45:00`,
    /// `playhead`).
    func playerDuration() -> Double? {
        let value = playhead()
        let codes = value.split(separator: " ").filter { $0.contains(":") }
        return codes.count >= 2 ? Self.seconds(String(codes[1])) : nil
    }

    /// Keeps a screenshot of the whole screen, once it shows its content (nothing on it loading, for up to 40 s, past
    /// the addons' 30 s timeout) and has stopped moving (`screenSettles`), as `marketing-<shot>`, with its metadata as
    /// `marketing-<shot>-meta` (JSON): the `note`, the position shown where given, and whether the device lies in
    /// `landscape` (the simulator's screenshot then keeps the portrait framebuffer, which the run turns upright).
    func marketingShot(_ shot: String, _ note: String, at position: Double? = nil, landscape: Bool) {
        let loading = app.descendants(matching: .any).matching(NSPredicate(format: "identifier ENDSWITH '.loading'")).firstMatch
        _ = waitUntil(40) { !loading.exists }
        screenSettles(XCUIScreen.main)
        let png = XCUIScreen.main.screenshot().pngRepresentation
        attach(png, type: "public.png", name: "marketing-\(shot)")
        var meta: [String: Any] = ["shot": shot, "note": note, "landscape": landscape]
        if let position { meta["timestamp"] = Self.clock(position) }
        if let json = try? JSONSerialization.data(withJSONObject: meta, options: [.sortedKeys]) {
            attach(json, type: "public.plain-text", name: "marketing-\(shot)-meta")
        }
    }

    private func attach(_ data: Data, type: String, name: String) {
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: type)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// `14:08`, or `1:02:03` past an hour.
    nonisolated static func clock(_ seconds: Double) -> String {
        let total = max(0, Int(seconds))
        let (hours, minutes, rest) = (total / 3600, total % 3600 / 60, total % 60)
        return hours > 0 ? String(format: "%ld:%02ld:%02ld", hours, minutes, rest) : String(format: "%ld:%02ld", minutes, rest)
    }

    private static func optional(_ name: String) -> String? {
        let value = ProcessInfo.processInfo.environment[name]?.trimmingCharacters(in: .whitespaces) ?? ""
        return value.isEmpty ? nil : value
    }

    /// The comma-separated values of `name`; none when it is unset.
    private static func list(_ name: String) -> [String] {
        optional(name)?.split(separator: ",").map(String.init) ?? []
    }

    private static func required(_ name: String) throws -> String {
        guard let value = optional(name) else {
            throw HarnessError("\(name) is not set: the shot needs this marketing capture variable, passed with xcodebuild's TEST_RUNNER_ prefix")
        }
        return value
    }
}
