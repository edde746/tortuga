import XCTest

/// The TV guide by touch on iPhone and iPad and with the pointer on the Mac, on the test addon's guide
/// (`GuideTestSupport`):
/// - Home's shortcuts offer TV Guide, which opens the guide on today with Tortuga One's programme on now picked (iPad
///   and the Mac: in the details over the channels, with Play);
/// - a tap or click on Tortuga One's next programme (in the day's last half hour the one before) picks it: iPad and the
///   Mac show it in the details, iPhone in a sheet with its details and Play, Play whole at the sheet's half height,
///   which Close puts away;
/// - the time scrolls sideways (a swipe on iOS, the wheel sideways on the Mac) with the channel column fixed, and the
///   Now chip brings it back;
/// - Tomorrow's chip shows tomorrow (nothing on now), the Now chip today again with the programme on now picked;
/// - Play (iPhone: in the programme's sheet) plays the channel: the player plays its live stream.
final class GuideTests: TortugaUITestCase {
    func testGuideOpensOnNowPicksScrollsSwitchesDaysAndPlaysAChannel() throws {
        try launchDev(addon: true)
        let shortcut = require("board.shortcut.guide", timeout: 90, "Home shows the TV Guide shortcut")
        // An iPhone's chips run past the screen's edge.
        scrollAlong(element("board.shortcuts"), toShow: shortcut)
        if shortcut.label != "TV Guide" { fail("Home's guide shortcut reads '\(shortcut.label)', not 'TV Guide'") }
        shot("home: tv guide shortcut")

        activate(shortcut)
        guard let now = requireOnNow(channel: 0, "\(Self.tapping) on TV Guide opens the guide on today") else { return }
        requireDaySelected(1, "the guide opened")
        requirePicked(now.id, "the guide opened on Tortuga One's programme on now")
        if hasDetails {
            requireDetailsTitle(now.title, "the guide opened")
            require("guide.play", "the details offer Play")
        }
        shot("guide on now")

        // The day's last half hour has no next programme: the one before stands in.
        let next = element("guide.programme.0.\(now.index + (guideProgrammesLeftToday > 0 ? 1 : -1))")
        activate(next)
        if hasDetails {
            requirePicked(next.identifier, "\(Self.tapping) on Tortuga One's next programme")
            requireDetailsTitle(next.label, "\(Self.tapping) on Tortuga One's next programme")
            shot("next programme picked")
        } else {
            let play = require("guide.play", "\(Self.tapping) on Tortuga One's next programme opens its sheet with Play")
            requireDetailsTitle(next.label, "the next programme's sheet")
            if !waitUntil(5, { windowFrame.contains(play.frame) }) {
                fail("the sheet at half height cuts Play: it lies at \(play.frame), the window at \(windowFrame)")
            }
            shot("next programme's sheet")
            activate(require("guide.sheet.close", "the sheet offers Close"))
            requireGone("guide.play", "Close puts the sheet away")
            requirePicked(next.identifier, "the sheet put away")
        }

        let channel = require("guide.channel.0", "the guide shows Tortuga One's logo")
        let (x, channelX) = (next.frame.minX, channel.frame.minX)
        // Toward later hours in the morning, earlier ones from noon: an evening's time shown already reaches midnight.
        let later = Calendar.current.component(.hour, from: Date()) < 12
        #if os(macOS)
        element("guide.grid").scroll(byDeltaX: later ? -600 : 600, deltaY: 0)
        #else
        if later { ready("guide.grid").swipeLeft() } else { ready("guide.grid").swipeRight() }
        #endif
        if !waitUntil(5, { later ? next.frame.minX < x - 50 : next.frame.minX > x + 50 }) {
            fail("scrolling sideways doesn't move the programmes along: the next one stays at x \(Int(next.frame.minX))")
        }
        if abs(channel.frame.minX - channelX) > 2 { fail("the time scrolled, the channel column moved from x \(Int(channelX)) to \(Int(channel.frame.minX))") }
        shot("time scrolled")
        activate(require("guide.now", "the guide offers the Now chip"))
        if !waitUntil(5, { abs(next.frame.minX - x) < 30 }) { fail("the Now chip leaves the next programme at x \(Int(next.frame.minX)), not back at \(Int(x))") }

        activate(require("guide.day.2", "the guide offers Tomorrow"))
        requireDaySelected(2, "\(Self.tapping) on Tomorrow")
        requireNothingOnNow(channel: 0, "Tomorrow picked")
        shot("tomorrow")
        activate(element("guide.now"))
        requireDaySelected(1, "the Now chip")
        guard let today = requireOnNow(channel: 0, "the Now chip shows today") else { return }
        requirePicked(today.id, "today again, the programme on now")
        shot("today again")

        if !hasDetails { activate(element(today.id)) }
        activate(require("guide.play", hasDetails ? "the details offer Play" : "\(Self.tapping) on the programme on now opens its sheet with Play"))
        requireChannelPlaying("Play on Tortuga One's programme on now")
        shot("playing")
    }

    /// iPad and the Mac show the picked programme in the details over the channels; iPhone in a sheet.
    private var hasDetails: Bool {
        #if os(iOS)
        isPad
        #else
        true
        #endif
    }

    /// Waits up to 5 s for the programme `id` to read as picked (selected); fails with `what`.
    private func requirePicked(_ id: String, _ what: String) {
        let cell = element(id)
        if !waitUntil(5, { cell.exists && cell.isSelected }) { fail("\(what): \(id) isn't picked") }
    }
}
