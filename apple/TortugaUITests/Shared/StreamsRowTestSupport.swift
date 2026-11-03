import XCTest

/// What the Streams list's row of addons tests share (iOS, macOS and tvOS StreamsTests): a launch with four stream
/// addons, the row's chips (`streams.bar.addon.<i>`, each labelled with its addon's name; the selected one, All or an
/// addon's, with the selected trait), and the lists the chips leave. The four are test_addon.py's own and the three it
/// serves under /sources/, each listing h264-sdr.mkv and h264-4x3.mkv ("Tortuga Second 1080p", "Tortuga Second 4:3")
/// with the description "Answer <n>", n counting that addon's answers on the server: for the remux movie the list holds
/// the test addon's two streams (rows 0 and 1), Tortuga Second's (2, 3), Tortuga Slow's (4, 5, 6 s late) and Tortuga
/// Broken's error (its streams answer 500).
extension TortugaUITestCase {
    /// The four addons' names, in the order they are installed: the row's chips once each has answered.
    static let streamAddonNames = ["Tortuga Test", "Tortuga Second", "Tortuga Slow", "Tortuga Broken"]

    /// Launches a `--dev` session with the stream addons `keys` installed in that order (`--dev-addon` each: "test" the
    /// test addon, any other test_addon.py's `<base>/sources/<key>/manifest.json`; by default the test addon, then
    /// Second, Slow and Broken), opened on `open` (`--dev-open` screens), with the run's device settings.
    func launchWithStreamAddons(_ keys: [String] = ["test", "second", "slow", "broken"], open: [String]) throws {
        let manifest = try addonURL
        guard let base = URL(string: manifest)?.deletingLastPathComponent() else {
            throw HarnessError("TORTUGA_ADDON is not a URL: \(manifest)")
        }
        var arguments = ["--dev"]
        for key in keys {
            let url = key == "test" ? manifest : base.appendingPathComponent("sources/\(key)/manifest.json").absoluteString
            arguments += ["--dev-addon", url]
        }
        for screen in open { arguments += ["--dev-open", screen] }
        for setting in Self.harnessSettings { arguments += ["--dev-setting", setting] }
        launch(arguments)
    }

    /// A chip's name, read at once: its label, else its title (empty while it isn't there).
    func chipName(_ chip: XCUIElement) -> String {
        guard let snapshot = try? chip.snapshot() else { return "" }
        return snapshot.label.isEmpty ? snapshot.title : snapshot.label
    }

    /// The row's addon chips' names, in order.
    func addonChipNames() -> [String] {
        var names: [String] = []
        for index in 0..<8 {
            let chip = element("streams.bar.addon.\(index)")
            guard chip.exists else { break }
            names.append(chipName(chip))
        }
        return names
    }

    /// Waits for the row to hold a chip for each of the four addons, in their order: Tortuga Slow's comes once it has
    /// answered. Fails with `what` and the chips' names.
    func requireEveryAddonsChip(_ what: String) {
        if !waitUntil(30, { addonChipNames() == Self.streamAddonNames }) {
            fail("\(what): the row's chips read \(addonChipNames()), not \(Self.streamAddonNames)")
        }
    }

    /// Waits for the row's chip `index` (`streams.bar.addon.<index>`) to be `name`'s; fails with `what`.
    func requireChip(_ index: Int, named name: String, _ what: String) {
        let chip = element("streams.bar.addon.\(index)")
        if !waitUntil(30, { chipName(chip) == name }) {
            fail("\(what): the row's chip \(index) reads \"\(chipName(chip))\", not \(name)")
        }
    }

    /// Waits for the row to have `id` selected (`streams.bar.all`, `streams.bar.addon.<i>`) and none of its other chips;
    /// fails with `what` and the chips selected.
    func requireSelectedChip(_ id: String, _ what: String) {
        let chips = ["streams.bar.all"] + Self.streamAddonNames.indices.map { "streams.bar.addon.\($0)" }
        func selected() -> [String] {
            chips.filter { (try? element($0).snapshot())?.isSelected == true }
        }
        if !waitUntil(10, { selected() == [id] }) { fail("\(what): the row has \(selected()) selected, not \(id) alone") }
    }

    /// The chip `id`, picked at `frame`, is where it was and as wide: a chip is as wide selected as not, so a pick moves
    /// no chip (a chip before it that changed width would move it). Fails with `what` and the chip's frames.
    func requireChipStill(_ id: String, at frame: CGRect, _ what: String) {
        let now = element(id).frame
        if abs(now.minX - frame.minX) > 1 || abs(now.width - frame.width) > 1 {
            fail("\(what): the chip \(id) moved from x \(Int(frame.minX)), \(Int(frame.width)) wide, to x \(Int(now.minX)), \(Int(now.width)) wide")
        }
    }

    /// The list under Tortuga Second's chip: its two streams alone (`streams.row.0` and `.1`, each naming it, no
    /// `streams.row.2`) and no addon's name over them (the chip names the list). Fails with `what`.
    func requireSecondAlone(_ what: String) {
        requireText("streams.row.0", contains: "Tortuga Second", "\(what): the first stream is Tortuga Second's")
        requireText("streams.row.1", contains: "Tortuga Second", "\(what): the second stream is Tortuga Second's")
        requireGone("streams.row.2", timeout: 5, "\(what): Tortuga Second's two streams alone")
        requireGone("streams.group.0.name", timeout: 5, "\(what): no addon's name over the streams under the chip")
    }

    /// Tortuga Broken's chip selected and its group alone: the addon's error (`streams.group.0.error`) with Retry
    /// (`.retry`) and no stream. Fails with `what`.
    func requireBrokenAlone(_ what: String) {
        requireSelectedChip("streams.bar.addon.3", "\(what): Tortuga Broken's chip")
        require("streams.group.0.error", timeout: 10, "\(what): Tortuga Broken's group shows its error")
        require("streams.group.0.retry", timeout: 10, "\(what): Tortuga Broken's group offers Retry")
        requireGone("streams.row.0", timeout: 5, "\(what): Tortuga Broken lists no stream")
    }

    /// The answer number in the stream row `id`'s description ("Answer 3"), nil where it shows none.
    func answerNumber(_ id: String) -> Int? {
        let text = allText(of: element(id))
        guard let range = text.range(of: #"Answer \d+"#, options: .regularExpression) else { return nil }
        return Int(text[range].dropFirst("Answer ".count))
    }

    /// The first stream's (`streams.row.0`) answer number. Fails with `what` where it shows none (0 then).
    func requireFirstAnswer(_ what: String) -> Int {
        guard let answer = answerNumber("streams.row.0") else {
            fail("\(what): the first stream reads \"\(allText(of: element("streams.row.0")))\", no answer number")
            return 0
        }
        return answer
    }

    /// Waits for the first stream (`streams.row.0`), at answer `before`, to read a later answer: its addon asked again
    /// past the app's cache. Fails with `what` and the answer it reads.
    func requireFirstAnsweredAgain(after before: Int, _ what: String) {
        var answer: Int?
        if !waitUntil(30, { answer = answerNumber("streams.row.0"); return (answer ?? 0) > before }) {
            fail("\(what): the first stream reads answer \(answer.map { "\($0)" } ?? "none"), not one after \(before)")
        }
    }

    /// The list under Tortuga Slow's chip: its two streams alone, the first's answer number. Fails with `what`.
    func requireSlowAnswer(_ what: String) -> Int {
        requireText("streams.row.0", contains: "Tortuga Slow", timeout: 15, "\(what): the first stream is Tortuga Slow's")
        requireGone("streams.row.2", timeout: 5, "\(what): Tortuga Slow's two streams alone")
        return requireFirstAnswer("\(what): Tortuga Slow's")
    }

    /// After Refresh under Tortuga Slow's chip, its first stream at answer `before`: its group loads while it is asked
    /// again (`streams.group.0.loading`, its 6 s), the chip still selected, and then its first stream reads a later
    /// answer, the app's cache passed. Fails with `what`.
    func requireSlowAskedAgain(after before: Int, _ what: String) {
        require("streams.group.0.loading", timeout: 5, "\(what): Tortuga Slow's group loads while it is asked again")
        requireSelectedChip("streams.bar.addon.2", "\(what): while Tortuga Slow is asked again")
        requireFirstAnsweredAgain(after: before, "\(what): Tortuga Slow's")
        requireSelectedChip("streams.bar.addon.2", "\(what): once Tortuga Slow answered again")
    }

    /// Waits until every addon has answered: Tortuga Slow, 6 s late, lists its first stream (`streams.row.4`, past the
    /// test addon's two and Tortuga Second's two) in the list as it opens (on All; a build without the row lists every
    /// group). A list makes its rows as they scroll in: on iOS, where the row hasn't come within 10 s (below the iPhone's
    /// half-height sheet), the list is swiped up to it; on tvOS focus goes down to it from the first stream and back.
    /// Fails with `what`.
    func requireTortugaSlowListed(_ what: String) {
        let slow = "streams.row.4"
        #if os(iOS)
        if !element(slow).exists(within: 10) { swipeUntilShown(slow, "\(what): Tortuga Slow's first stream") }
        #elseif os(tvOS)
        requireFocus("streams.row.0", timeout: 60, "\(what): the first stream has focus")
        press(.down, until: "streams.row.3", limit: 4, "\(what): Down reaches Tortuga Second's last stream")
        _ = element(slow).exists(within: 30)
        press(.down, until: slow, limit: 1, "\(what): Down from Tortuga Second's last stream reaches Tortuga Slow's first")
        #else
        require(slow, timeout: 30, "\(what): Tortuga Slow's first stream")
        #endif
        requireText(slow, contains: "Tortuga Slow", "\(what): Tortuga Slow's first stream")
        #if os(tvOS)
        press(.up, until: "streams.row.0", limit: 5, "\(what): Up returns to the first stream")
        #endif
    }

    #if os(tvOS)
    /// Presses `button` until `id` has focus, at most `limit` times, without first waiting for `id` to be there as
    /// `moveFocus` does: the Streams panel's column makes its rows as focus scrolls them in. Fails with `what`.
    func press(_ button: XCUIRemote.Button, until id: String, limit: Int = 8, _ what: String) {
        for _ in 0..<limit where !holdsFocus(id) { press(button) }
        if !waitUntil(2, { holdsFocus(id) }) { fail("\(what): \(limit) presses left focus on \(focusedId ?? "nothing"), not \(id)") }
    }
    #else
    /// Taps (on the Mac clicks) the row's `id` (`streams.bar.all`, `.addon.<i>`), the row first scrolled sideways
    /// toward it until all of it lies inside the row where it runs past an end (`scrollAlong`). Returns the chip's frame
    /// as it is pressed. Fails with `what`.
    @discardableResult
    func pressInAddonRow(_ id: String, _ what: String, file: StaticString = #filePath, line: UInt = #line) -> CGRect {
        let target = require(id, timeout: 30, what)
        scrollAlong(element("streams.bar"), toShow: target)
        ready(target, file: file, line: line)
        let pressed = target.frame
        #if os(macOS)
        target.click()
        #else
        target.tap()
        #endif
        return pressed
    }
    #endif
}
