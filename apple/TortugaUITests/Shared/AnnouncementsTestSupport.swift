import XCTest

/// What the announcement tests share: what the app told VoiceOver, read off the Debug build's record of it
/// (`app.announcements`, one text per line, `none` before the first), since XCUITest can't hear VoiceOver.
extension TortugaUITestCase {
    /// Waits up to 10 s for `text` to be one of the texts announced; fails with `what` and what was announced when it
    /// isn't.
    func requireAnnounced(_ text: String, _ what: String) {
        let record = require("app.announcements", "\(what): the app keeps what it announced")
        if !waitUntil(10, { record.label.components(separatedBy: "\n").contains(text) }) {
            fail("\(what): \"\(text)\" wasn't announced; the app announced \(record.label.debugDescription)")
        }
    }
}
