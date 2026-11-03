import ObjectiveC
import XCTest

/// XCTest waits before and after each action until the app's animations have ended, which it counts inside the app:
/// one up as UIKit tells a view animation's delegate the animation started (`UIViewAnimationState`'s
/// `animationDidStart:`), one down as it tells it the animation stopped. UIKit never tells the stop of an animation
/// whose layer it discards in flight (seen on the iPhone Duo's cover screen, iOS 27.1): a tap on a sheet's Check server
/// or Continue with the keyboard up ends the editing (the app's keyboard dismissal), the keyboard going away resizes the
/// sheet, which moves its scroll edge effect's gradient layer in a 0.38 s animation, and the sheet closing or changing
/// its page meanwhile discards that layer. Nothing animates after that, but the count stays at one for the app's life:
/// every later action waits XCTest's 60 s ("App animations complete notification not received") before it goes on.
///
/// Once one of those waits has run out with the animations included, the app's later waits leave them out and wait for
/// its main run loop alone, as XCTest itself does after each 60 s. XCTest's waits are `XCUIApplicationProcess`'s, which
/// it doesn't publish: `install` names what an Xcode update took away.
enum AnimationCountLeak {
    /// XCTest's wait for the app to quiesce: its animations idle (or not), whether it comes before an action.
    private static let selector = NSSelectorFromString("waitForQuiescenceIncludingAnimationsIdle:usingActivity:isPreEvent:")
    /// Long enough that only XCTest's own timeout (60 s) ends a wait.
    private static let timeout: TimeInterval = 55
    nonisolated(unsafe) private static var installed = false
    /// The processes of the app whose count of animations never returned to zero.
    nonisolated(unsafe) private static var leaked: Set<Int32> = []

    /// Wraps XCTest's wait once per test run; returns why it can't, or nil.
    static func install() -> String? {
        if installed { return nil }
        guard let process = NSClassFromString("XCUIApplicationProcess"), let method = class_getInstanceMethod(process, selector) else {
            return "XCTest has no -[XCUIApplicationProcess \(NSStringFromSelector(selector))] to wrap (an Xcode update): AnimationCountLeak needs its new name"
        }
        typealias Wait = @convention(c) (NSObject, Selector, Bool, Bool, Bool) -> Void
        let wait = unsafeBitCast(method_getImplementation(method), to: Wait.self)
        let wrapped: @convention(block) (NSObject, Bool, Bool, Bool) -> Void = { process, animations, activity, preEvent in
            let pid = (process.value(forKey: "processID") as? NSNumber)?.int32Value ?? 0
            guard animations, !leaked.contains(pid) else {
                wait(process, selector, false, activity, preEvent)
                return
            }
            let start = ProcessInfo.processInfo.systemUptime
            wait(process, selector, true, activity, preEvent)
            if ProcessInfo.processInfo.systemUptime - start >= timeout {
                leaked.insert(pid)
                print("the app's count of running animations stayed above zero for \(Int(timeout)) s: its later actions wait for its run loop alone")
            }
        }
        method_setImplementation(method, imp_implementationWithBlock(wrapped))
        installed = true
        return nil
    }
}
