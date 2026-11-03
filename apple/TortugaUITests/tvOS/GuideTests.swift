import XCTest

/// The TV guide with the remote, on the test addon's guide (`GuideTestSupport`):
/// - Home's shortcuts offer TV Guide after Library, Up from the first shelf and Right reaching it;
/// - Select on it opens the guide on today with focus on Tortuga One's programme on now, the details showing it;
/// - Right walks the channel's programmes to later ones (in the day's last half hour Left to earlier ones), the details
///   and the time following (six on, three hours away, the focused programme is in view), Left walks back, Down moves
///   to Tortuga Two and Up back to Tortuga One (to the programme nearest on screen, which depends on the time of day);
/// - Up from the channels reaches the day chips on Today; Tomorrow picked shows tomorrow (nothing on now), Down entering
///   it on Tortuga One; Today picked again, Down enters on Tortuga One's programme on now, not on whichever programme
///   lies nearest the chip;
/// - Select plays the channel: the player plays its live stream.
final class GuideTests: TortugaUITestCase {
    func testGuideOpensOnNowWalksTheTimeSwitchesDaysAndPlaysAChannel() throws {
        try launchDev(addon: true)
        requireFocus("board.row.0.item.0", timeout: 90, "the Board's first card has focus once its shelves load")
        moveFocus(to: "board.shortcut.watchTogether", with: .up, limit: 2, "Up from the first shelf reaches Home's first shortcut")
        moveFocus(to: "board.shortcut.guide", with: .right, limit: 3, "Right along Home's shortcuts reaches TV Guide")
        let label = element("board.shortcut.guide").label
        if label != "TV Guide" { fail("Home's guide shortcut reads '\(label)', not 'TV Guide'") }
        shot("home: tv guide shortcut")

        press(.select)
        guard let now = requireOnNow(channel: 0, "Select on TV Guide opens the guide on today") else { return }
        requireFocus(now.id, "the guide opens with focus on Tortuga One's programme on now")
        requireDetailsTitle(now.title, "the guide opened")
        requireDaySelected(1, "the guide opened")
        shot("guide on now")

        // Toward later programmes, or earlier ones in the day's last half hour, which has none later.
        let left = guideProgrammesLeftToday
        let (on, back): (XCUIRemote.Button, XCUIRemote.Button) = left > 0 ? (.right, .left) : (.left, .right)
        let (onName, backName, step) = left > 0 ? ("Right", "Left", 1) : ("Left", "Right", -1)
        press(on)
        let next = "guide.programme.0.\(now.index + step)"
        requireFocus(next, "\(onName) moves focus to the programme beside Tortuga One's on now")
        requireDetailsTitle(element(next).label, onName)
        // Six on (three hours away), or as many as the day has that way.
        let steps = min(6, left > 0 ? left : now.index)
        let far = "guide.programme.0.\(now.index + step * steps)"
        moveFocus(to: far, with: on, limit: 5, "\(onName) walks on along Tortuga One's programmes")
        let grid = element("guide.grid").frame, cell = element(far).frame
        if cell.minX >= grid.maxX || cell.maxX <= grid.minX {
            fail("the programme focused \(steps) away lies at \(cell), outside the grid at \(grid): the time didn't follow")
        }
        requireDetailsTitle(element(far).label, "\(onName) along the channel")
        shot("three hours on")
        moveFocus(to: now.id, with: back, limit: 6, "\(backName) walks back to the programme on now")
        press(.down)
        requireFocus(prefix: "guide.programme.1.", "Down moves focus to Tortuga Two")
        shot("tortuga two")
        press(.up)
        requireFocus(prefix: "guide.programme.0.", "Up moves focus back to Tortuga One")

        press(.up)
        requireFocus("guide.day.1", "Up from the channels reaches the day chips on Today")
        press(.right)
        requireFocus("guide.day.2", "Right moves focus to Tomorrow")
        press(.select)
        requireDaySelected(2, "Select on Tomorrow")
        requireNothingOnNow(channel: 0, "Tomorrow picked")
        press(.down)
        requireFocus(prefix: "guide.programme.0.", "Down from the chips enters tomorrow on Tortuga One")
        shot("tomorrow")
        press(.up)
        requireFocus("guide.day.2", "Up from the channels lands on the selected day, Tomorrow")
        press(.left)
        requireFocus("guide.day.1", "Left moves focus to Today")
        press(.select)
        requireDaySelected(1, "Select on Today")
        guard let today = requireOnNow(channel: 0, "Today picked again") else { return }
        press(.down)
        requireFocus(today.id, "Down from the chips enters today on Tortuga One's programme on now")
        shot("today again")

        press(.select)
        requireChannelPlaying("Select on Tortuga One's programme on now")
        shot("playing")
    }
}
