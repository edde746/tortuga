import XCTest

/// What the stream filter and auto-play tests share (Settings → Playback → Streams, the test movie's and the test
/// series' Streams lists and the player's info): the texts they read, reaching the title again through the Library, and
/// the Streams group's controls by touch (iOS), the remote (tvOS) and the pointer (macOS). The test addon
/// (tools/media/test_addon.py) lists for the movie, in order: "Tortuga Test 2160p" (remux.mkv; the list joins the
/// addon's two name lines), "Tortuga Test 1080p" (h264-sdr.mkv) and a peer-to-peer stream, which is never listed
/// (this app can't play it); for each of its series' three 20 s episodes: "Tortuga Test 720p" (h264-sdr.mkv, no binge
/// group) and "Tortuga Test 1080p" (the same file, in a binge group).
enum StreamFilterTexts {
    static let movie = "detail:movie:tt0111161"
    static let series = "detail:series:tortuga-series"
    static let off = AutoPlayChoice(id: "OFF", label: "Off")
    static let firstMatch = AutoPlayChoice(id: "FIRST_MATCH", label: "First stream that passes the filters")
    static let reuseLastLink = AutoPlayChoice(id: "REUSE_LAST_LINK", label: "Same stream as last time, else the first that passes")
    static let findingStream = "Finding a stream…"
    static let noMatch = "Auto-play found no stream that passes your filters."
    static let pickedFirstMatch = "Auto-play, first match"
    static let pickedLastLink = "Auto-play, same stream as last time"
    static let rowNote = "Hidden by your filters"
    static let invalidPattern = "Not a valid regular expression, so it's ignored"
    static func hidden(_ count: Int) -> String { "\(count) stream\(count == 1 ? "" : "s") hidden by your filters" }
    static func showing(_ count: Int) -> String { "Showing \(count) stream\(count == 1 ? "" : "s") your filters hide" }
}

/// The test addon's sorted movie (tools/media/test_addon.py's tortuga-sorted): seven releases, each named
/// "Tortuga Sorted <release>" with its file name and size under it, out of every order; and the sort's options on
/// Apple builds, which show no cache state (so no Instant first).
enum StreamSortTexts {
    /// Its Streams sheet over its details over the Board.
    static let open = ["board", "detail:movie:tortuga-sorted", "streams:movie:tortuga-sorted:tortuga-sorted"]
    /// The rows in the addon's order.
    static let addonOrder = ["1080p WEB-DL", "2160p WEB-DL DV", "720p HDTV", "WEBRip", "2160p REMUX", "1080p BluRay", "480p DVDRip"]
    /// By resolution, then largest size first: the remux (58.3 GB) before the WEB-DL (14.8 GB), the Blu-ray (4.6 GB)
    /// before the WEB-DL (2.1 GB), and the WEBRip, whose resolution its text doesn't say, last.
    static let byResolutionThenSize = ["2160p REMUX", "2160p WEB-DL DV", "1080p BluRay", "1080p WEB-DL", "720p HDTV", "480p DVDRip", "WEBRip"]
    static let addonOrderLabel = "Sort by: Addon order"
    static let resolutionLabel = "Sort by: Resolution"
    static let twoKeysLabel = "Sort by: Resolution, Largest size first"
    /// The options' places in the menu: Addon order, Resolution, Source quality, Largest size first, Smallest size first.
    static let resolution = 1
    static let sourceQuality = 2
    static let largest = 3
}

/// An auto-play mode: its option's id (what `PlaybackSettingsStore.choose` takes for `streams.autoPlay`) and its label
/// in the menu.
struct AutoPlayChoice {
    let id: String
    let label: String
}

extension TortugaUITestCase {
    /// Everything `element` says, without its direction marks: its label, its value and the texts inside it (a row's
    /// lines, which the Mac and tvOS may report apart).
    func allText(of element: XCUIElement) -> String {
        guard element.exists else { return "" }
        var parts = [element.label, element.value as? String ?? ""]
        parts += element.staticTexts.allElementsBoundByIndex.map { text(of: $0) }
        return parts.filter { !$0.isEmpty }.joined(separator: " ").withoutDirectionMarks
    }

    /// Waits for `id` to say `text` (`allText`); fails with `what` when it doesn't.
    func requireText(_ id: String, contains text: String, timeout: TimeInterval = 10, _ what: String) {
        let target = element(id)
        if !waitUntil(timeout, { allText(of: target).contains(text) }) {
            fail("\(what): \(id) says \"\(target.exists ? allText(of: target) : "nothing")\", not \"\(text)\"")
        }
    }

    /// The Streams list as the filters leave it: one row per entry of `names`, in order, each row's texts holding its
    /// name, and no row after them; the filter bar saying `filter`; the rows at `noted` carry the filters' note.
    func requireStreams(_ names: [String], filter: String, noted: [Int] = [], _ what: String) {
        require("streams.filter", timeout: 60, "\(what): the filter bar shows")
        requireText("streams.filter", contains: filter, "\(what): the filter bar")
        for (index, name) in names.enumerated() {
            require("streams.row.\(index)", timeout: 30, "\(what): row \(index) is listed")
            requireText("streams.row.\(index)", contains: name, "\(what): row \(index)")
            let note = allText(of: element("streams.row.\(index)")).contains(StreamFilterTexts.rowNote)
            if note != noted.contains(index) {
                fail("\(what): row \(index) \(note ? "carries" : "lacks") the note \"\(StreamFilterTexts.rowNote)\"")
            }
        }
        requireGone("streams.row.\(names.count)", timeout: 5, "\(what): no row after the \(names.count) listed")
    }

    /// No group's note under its rows (`streams.group.<i>.filtered`): with one group hiding streams, the filter bar above
    /// already says how many; fails with `what` when one shows.
    func requireNoFilteredNote(_ what: String) {
        let notes = app.descendants(matching: .any).matching(NSPredicate(format: "identifier MATCHES %@", #"streams\.group\.[0-9]+\.filtered"#))
        if let note = notes.allElementsBoundByIndex.first {
            fail("\(what): a group's note under its rows repeats the filter bar: \"\(allText(of: note))\"")
        }
    }

    /// The Streams list's rows from row `first` on hold `names` in order (each row's texts its name); fails with `what`.
    func requireRows(_ names: [String], from first: Int = 0, _ what: String) {
        for (offset, name) in names.enumerated() {
            requireText("streams.row.\(first + offset)", contains: name, timeout: 30, "\(what): row \(first + offset)")
        }
    }

    /// The sort button (`streams.sort`) reads `label` (`menuText`: on the Mac a menu's name is its title) and,
    /// `selected`, shows a sort in effect (filled: selected to accessibility); fails with `what` when it doesn't within
    /// 10 s.
    func requireSortButton(_ label: String, selected: Bool, _ what: String) {
        if !waitUntil(10, { menuText("streams.sort").contains(label) }) {
            fail("\(what): streams.sort says \"\(menuText("streams.sort"))\", not \"\(label)\"")
        }
        let read = { (try? self.element("streams.sort").snapshot())?.isSelected == true }
        if !waitUntil(10, { read() == selected }) {
            fail("\(what): the sort button is \(selected ? "not selected (not filled)" : "selected (filled)")")
        }
    }

    /// The sort button just before Refresh (`streams.refresh`): as tall, level with it, nearer to it than Refresh's
    /// width; fails with `what`.
    func requireSortBesideRefresh(_ what: String) {
        let sort = settledFrame(of: element("streams.sort"))
        let refresh = settledFrame(of: require("streams.refresh", "\(what): Refresh shows"))
        let gap = refresh.minX - sort.maxX
        if abs(sort.height - refresh.height) > 2 || abs(sort.midY - refresh.midY) > 2 || gap < 0 || gap > refresh.width {
            fail("\(what): the sort button at \(sort) isn't just before Refresh at \(refresh), level with it and as tall")
        }
    }

    /// Waits until the player plays: its position (resumed, or from the start) moves on by 2 s.
    func requirePlaying() {
        let start = requirePlayback(0.5, timeout: 90)
        requirePlayback(start + 2, timeout: 30)
    }

    /// The playback info's row `label` says `value`; fails with `what` when it doesn't.
    func requireInfoRow(_ label: String, contains value: String, _ what: String) {
        let row = infoRow(label)
        if !waitUntil(10, { row.exists && fullText(of: row).contains(value) }) {
            fail("\(what): the info's \(label) row reads \"\(row.exists ? fullText(of: row) : "nothing")\", not \"\(value)\"")
        }
    }

    #if !os(tvOS)
    /// Picks `choice` in the auto-play menu (`pickChoice`).
    func pickAutoPlay(_ choice: AutoPlayChoice) {
        pickChoice("settings.playback.streams.autoPlay", option: (choice.id, choice.label))
    }

    /// The movie's details from its start (a dev start on the Board, the details over it): the round + adds it to the
    /// library, the way back to it after Settings.
    func addMovieToLibrary() {
        let library = require("detail.library", timeout: 60, "the movie's details show the library button")
        let add = library.label
        activate(library)
        if !waitUntil(10, { element("detail.library").exists && element("detail.library").label != add }) { fail("Add to library doesn't turn into its undo") }
    }

    /// The movie's details again: the Library (iOS' tab, the Mac's sidebar row), then the movie, its only item. The
    /// Library comes back as it was left: on the movie's details after an earlier visit, which is where it ends then.
    func openMovieFromLibrary() {
        activate(navItem("nav.library", "the sidebar lists Library"))
        let item = element("library.item.0")
        let play = element("detail.play")
        if !waitUntil(20, { item.exists || play.exists }) { return fail("the Library shows neither its list nor the movie") }
        if play.exists { return }
        activate(item)
        require("detail.play", timeout: 60, "the movie opens from the Library")
    }

    /// Qualities' switches: switches the quality `id` (`4k`) to `shown`, with a screenshot `shotName` of the list. The
    /// Qualities row is scrolled into view first (`showOnScreen`).
    func setQuality(_ id: String, shown: Bool, shotName: String? = nil) {
        activate(showOnScreen("settings.playback.streams.qualities", "the Streams group has Qualities"))
        let toggle = require("settings.streams.quality.\(id)", "Qualities opens its switches")
        if switchValue(toggle) != (shown ? "1" : "0") { flipSwitch(toggle) }
        if !waitUntil(5, { switchValue(toggle) == (shown ? "1" : "0") }) { fail("\(Self.tapping) doesn't switch \(id) \(shown ? "on" : "off")") }
        if let shotName { shot(shotName) }
        closeSheet("settings.streams.qualities.close", showing: "settings.streams.quality.\(id)", "Close (an iPhone: a pull down) closes Qualities")
    }

    /// The pattern row `row` (`settings.playback.streams.include`), scrolled into view (`showOnScreen`), set to `pattern`
    /// in its editor, saved: the field's text, where it has one, replaced (selected whole by ⌘A on the Mac, deleted on
    /// iOS).
    func setPattern(_ row: String, to pattern: String) {
        activate(showOnScreen(row, "the Streams group has \(row)"))
        let field = require("settings.streams.pattern.field", "\(row) opens its editor")
        activate(field)
        #if os(macOS)
        field.typeKey("a", modifierFlags: .command)
        #else
        if let old = field.value as? String, !old.isEmpty, old != field.placeholderValue {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: old.count))
        }
        #endif
        field.typeText(pattern)
        activate(require("settings.streams.pattern.save", "the editor has Save"))
        requireGone("settings.streams.pattern.field", "Save closes the editor")
    }
    #endif

    #if os(iOS)
    /// `scrollTo` over the frontmost list (`frontmostList`: a sheet's form, not the screen's list under it), once `id`
    /// has had 5 s to come (a form's rows below the screen come as it scrolls in); a half-height sheet is first pulled
    /// up to full height (`pullSheetUp`) unless `id` shows already.
    @discardableResult
    func swipeUntilShown(_ id: String, _ what: String, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        let target = element(id)
        _ = target.exists(within: 5)
        let list = frontmostList
        if !liesInside(target) { pullSheetUp(list) }
        return scrollTo(target, over: list, what, file: file, line: line)
    }

    /// Pulls the sheet that holds `list` up to full height by its top, as a person does, where it stands at half height
    /// (its list's top below the window's upper third). In a half-height sheet a drag in the list lifts the sheet rather
    /// than scrolling it, and a drag as short as `scrollTo`'s lets it spring back (the Streams sheet's missing file row
    /// stayed cut by the sheet's edge for 8 drags, the install page's agreement below it for 15).
    func pullSheetUp(_ list: XCUIElement) {
        guard list.exists, list.frame.minY > windowFrame.height / 3 else { return }
        list.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: 0)).withOffset(CGVector(dx: 0, dy: 14)).press(
            forDuration: 0.05,
            thenDragTo: appWindow.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.02)),
            withVelocity: .slow,
            thenHoldForDuration: 0.2
        )
    }

    /// Settings → Playback, scrolled down to its Streams group's first row, auto-play (the rows under it come into view
    /// as a test needs them, `showOnScreen`).
    func openStreamsSettings() {
        openSection("playback")
        swipeUntilShown("settings.playback.streams.autoPlay", "the Playback page has its Streams group")
    }
    #endif

    #if os(tvOS)
    /// The movie's details from its start (a dev start on the Board, the details over it, Play focused): Right reaches
    /// the round +, which adds it to the library, the way back to it after Settings.
    func addMovieToLibrary() {
        requireFocus("detail.play", timeout: 60, "Play has focus once the movie's details show")
        moveFocus(to: "detail.library", with: .right, "Right from Play reaches the library button")
        let add = element("detail.library").label
        press(.select)
        if !waitUntil(10, { element("detail.library").exists && element("detail.library").label != add }) { fail("Add to library doesn't turn into its undo") }
    }

    /// Menu until the details have gone (back to the screen under them).
    func leaveDetail() {
        for _ in 0..<3 where element("detail.play").exists {
            press(.menu)
            _ = waitUntil(3) { !element("detail.play").exists }
        }
        requireGone("detail.play", "Menu leaves the details")
    }

    /// The movie's details again: the tab bar's Library, Down to the movie, its only item, and Select.
    func openMovieFromLibrary() {
        focusTabBar()
        let library = require("nav.library", "the tab bar has Library")
        let here = focused.frame
        moveFocus(to: "nav.library", with: library.frame.midX < here.midX ? .left : .right, "the tab bar reaches Library")
        moveFocus(to: "library.item.0", with: .down, "Down reaches the movie in the Library")
        press(.select)
        requireFocus("detail.play", timeout: 60, "the movie opens from the Library with Play focused")
    }

    /// Settings → Playback, then Down along the page to its Streams group's `row`.
    func openStreamsSettings(at row: String = "settings.playback.streams.autoPlay") {
        openSection("playback")
        moveFocus(to: row, with: .down, limit: 17, "Down reaches \(row) in the Streams group")
    }

    /// Moves focus along the Streams group to `row`: Up or Down, as the row lies from the focused one.
    func focusStreamsRow(_ row: String) {
        let rows = ["autoPlay", "suggestions", "qualities", "ranges", "codecs", "minSize", "maxSize", "include", "exclude"]
        let order = rows.map { "settings.playback.streams.\($0)" }
        let from = order.firstIndex { holdsFocus($0) } ?? 0
        let to = order.firstIndex(of: row) ?? 0
        moveFocus(to: row, with: to >= from ? .down : .up, limit: order.count + 2, "the remote reaches \(row)")
    }

    /// The open menu's focused entry by its label, or by the label inside it (the context menu's cell has none).
    var focusedEntry: String {
        let here = focused
        guard here.exists else { return "nothing" }
        if !here.label.isEmpty { return here.label }
        let inside = here.descendants(matching: .any).matching(NSPredicate(format: "label != ''")).firstMatch
        return inside.exists ? inside.label : "an unlabelled element"
    }

    /// Picks `choice` for auto-play: Select on its row opens its values as a list, where the remote reaches the value,
    /// and Select picks it (`pickChoice`).
    func pickAutoPlay(_ choice: AutoPlayChoice) {
        focusStreamsRow("settings.playback.streams.autoPlay")
        pickChoice("settings.playback.streams.autoPlay", option: (choice.id, choice.label))
    }

    /// Types `text` on the system keyboard the focused field opened, then leaves the keyboard by its Done (the return
    /// key only moves focus there).
    func typeOnKeyboard(_ text: String) {
        app.typeText(text + "\n")
        let keyboard = app.keyboards.firstMatch
        let done = app.buttons.matching(NSPredicate(format: "label ==[c] 'done'")).firstMatch
        for _ in 0..<4 where keyboard.exists {
            if done.exists, done.hasFocus {
                press(.select)
                _ = waitUntil(3) { !keyboard.exists }
            } else {
                press(.down)
            }
        }
        if keyboard.exists { fail("the keyboard stays up after typing \(text): focus is on \(focusedEntry)") }
    }

    /// Qualities' switches: switches the quality `id` (`4k`) to `shown` with Select, with a screenshot `shotName` of the
    /// list; Menu closes it.
    func setQuality(_ id: String, shown: Bool, shotName: String? = nil) {
        focusStreamsRow("settings.playback.streams.qualities")
        press(.select)
        let toggle = require("settings.streams.quality.\(id)", "Qualities opens its switches")
        walkFocus(to: "settings.streams.quality.\(id)", "the remote reaches \(id) in Qualities")
        if switchValue(toggle) != (shown ? "1" : "0") { press(.select) }
        if !waitUntil(5, { switchValue(toggle) == (shown ? "1" : "0") }) { fail("Select doesn't switch \(id) \(shown ? "on" : "off")") }
        if let shotName { shot(shotName) }
        press(.menu)
        requireGone("settings.streams.quality.\(id)", "Menu closes Qualities")
    }

    /// The pattern row `row` (`settings.playback.streams.include`) set to `pattern` in its editor with the system
    /// keyboard, saved (the editor's first button).
    func setPattern(_ row: String, to pattern: String) {
        focusStreamsRow(row)
        press(.select)
        requireFocus("settings.streams.pattern.field", "\(row) opens its editor with focus on the field")
        press(.select)
        typeOnKeyboard(pattern)
        requireText("settings.streams.pattern.field", contains: pattern, "the keyboard types the pattern")
        moveFocus(to: "settings.streams.pattern.save", with: .down, "Down reaches Save")
        press(.select)
        requireGone("settings.streams.pattern.field", "Save closes the editor")
    }

    /// Brings the player's controls up (Select pauses, which shows them; the remux's skip button put away first, see
    /// `putAwaySkipButton`) and opens the info on its tab, from the options button's tab.
    func openPlayerInfo() {
        putAwaySkipButton()
        press(.select)
        require("player.scrub", timeout: 5, "Select pauses and brings the controls up")
        requireFocus("player.scrub", "the timeline has focus once the controls are up")
        press(.up)
        let buttons = ["player.previous", "player.next", "player.speed", "player.more", "player.subtitles", "player.audio"]
        let from = buttons.firstIndex(of: focusedId ?? "") ?? 0
        let to = buttons.firstIndex(of: "player.more") ?? 0
        moveFocus(to: "player.more", with: to >= from ? .right : .left, "the remote reaches the options button")
        press(.select)
        requireFocus("player.more.info", "the options open with focus on Playback info")
        press(.select)
        require("player.info.panel", timeout: 5, "Select on Playback info opens the playback info")
    }
    #endif

    #if os(macOS)
    /// Settings → Playback, scrolled down to its Streams group, its last row inside the page's form: the pointer scrolls
    /// over the form (the window's centre may lie outside it).
    func openStreamsSettings() {
        openSection("playback")
        let target = require("settings.playback.streams.exclude", "the Playback page ends with the Streams group")
        let form = app.scrollViews.containing(.any, identifier: "settings.playback.audioLanguages").firstMatch
        scrollTo(target, over: form, steps: 10, "the Playback page scrolled down to its Streams group")
    }
    #endif
}
