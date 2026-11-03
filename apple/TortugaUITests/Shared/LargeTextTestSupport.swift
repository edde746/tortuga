import XCTest

/// Hit targets and large text: a small control's least hit target, and the system's audit of the text on the screen at
/// the accessibility text sizes.
extension TortugaUITestCase {
    #if os(macOS)
    /// The least hit target's side (HIG Accessibility, Mobility): 28 pt on the Mac.
    static let leastHitTarget: CGFloat = 28
    #else
    /// The least hit target's side (HIG Accessibility, Mobility): 44 pt on iOS and iPadOS.
    static let leastHitTarget: CGFloat = 44
    #endif

    /// Fails with `what` where `control`, a control drawn smaller than the least hit target (a bare symbol, one line of
    /// text), takes taps or clicks in less than `leastHitTarget` square: its frame, the square that takes them.
    func requireHitTarget(_ control: XCUIElement, _ what: String) {
        let frame = control.frame
        let side = Self.leastHitTarget
        // Half a point of slack for frames that land between pixels.
        if frame.width < side - 0.5 || frame.height < side - 0.5 {
            fail("\(what): \(control.identifier) takes taps in \(frame.width) × \(frame.height) pt at \(frame.origin), under \(side) pt square")
        }
    }

    #if !os(macOS)
    /// The largest text size, accessibility-extra-extra-extra-large (`launchDev`'s `textSize`).
    static let largestTextSize = "UICTContentSizeCategoryAccessibilityXXXL"

    /// The system's accessibility audit of what the screen shows for text cut short or clipped (`textClipped`) and text
    /// that doesn't follow the text size (`dynamicType`), each issue as `<what>: <type>: <element's identifier, label
    /// and frame>: <description>; <detailed description>`, also printed (an `audit, ` line). The audit takes the app
    /// through the text sizes from the smallest to the largest and back to its own (iOS 27.0, seen in a test's screen
    /// recording): text cut short at any of them is an issue ("may be clipped at larger Dynamic Type sizes"), whatever
    /// size the app runs at. Not issues, printed apart: those on text whose identifier starts with one of
    /// `keepingItsSize` reported as keeping its size (the player's controls, which stop growing at the first
    /// accessibility size; an avatar's initial, sized to its circle), those outside `within` where given (the elements
    /// whose text the test defends), and those the audit gives no element for, which no frame or label places.
    func textAuditIssues(_ what: String, keepingItsSize: [String] = [], within: [XCUIElement]? = nil) -> [String] {
        let regions = within?.compactMap { try? $0.snapshot().frame }
        var issues: [String] = []
        do {
            try app.performAccessibilityAudit(for: [.textClipped, .dynamicType]) { issue in
                let kind = issue.auditType == .textClipped ? "clipped" : issue.auditType == .dynamicType ? "fixed size" : "type \(issue.auditType.rawValue)"
                let description = "\(issue.compactDescription); \(issue.detailedDescription)"
                guard let element = issue.element.flatMap({ try? $0.snapshot() }) else {
                    print("audit, \(what), no element: \(kind): \(description)")
                    return true
                }
                let id = element.identifier
                let line = "\(kind): \(id.isEmpty ? "element type \(element.elementType.rawValue)" : id) \"\(element.label)\" at \(element.frame): \(description)"
                if issue.auditType == .dynamicType, keepingItsSize.contains(where: { id.hasPrefix($0) }) {
                    print("audit, \(what), keeping its size: \(line)")
                } else if let regions, !regions.contains(where: { $0.intersects(element.frame) }) {
                    print("audit, \(what), outside: \(line)")
                } else {
                    issues.append("\(what): \(line)")
                }
                return true
            }
        } catch {
            return ["\(what): the accessibility audit didn't run: \(error)"]
        }
        for issue in issues { print("audit, \(issue)") }
        return issues
    }
    #endif
}
