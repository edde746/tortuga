import SwiftUI

extension LayoutDirection {
    /// What starts a line the app puts together itself from text it didn't write (isolated: `isolate(text:)`) and
    /// marks of its own without letters (a checkmark, a comma, " · "): a right-to-left mark in a right-to-left
    /// interface, else nothing. A line takes the direction of its first letter outside isolates, left to right without
    /// one, so in Arabic such a line would run left to right.
    var lineStart: String {
        self == .rightToLeft ? "\u{200F}" : ""
    }
}
