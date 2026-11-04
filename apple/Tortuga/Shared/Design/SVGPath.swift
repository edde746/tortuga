import SwiftUI

extension Path {
    /// A path from SVG path data in absolute commands (M, L, H, V, C, Z), as the brands' files write their marks
    /// (`GoogleLogo`, `GitHubLogo`); numbers may carry an exponent (`6.69539e-07`), and a command's numbers may repeat
    /// for another segment of it. Anything else (another command, a number short, numbers after Z or before any command,
    /// a character that is neither) stops the app, naming it: the data is the app's own, so a mark refreshed with data
    /// this doesn't read fails the first time it is drawn rather than hanging or drawing another shape.
    init(svg data: String) {
        let tokens = data.matches(of: #/-?[0-9]*\.?[0-9]+(?:e-?[0-9]+)?|[^\s,]/#).map { String($0.output) }
        self.init()
        var index = 0
        var command = ""
        var point = CGPoint.zero
        func number() -> CGFloat {
            guard index < tokens.count, let value = Double(tokens[index]) else {
                fatalError("SVG path data: \(command) needs a number at token \(index) of \(tokens.count)")
            }
            index += 1
            return CGFloat(value)
        }
        func next() -> CGPoint { CGPoint(x: number(), y: number()) }
        while index < tokens.count {
            if Double(tokens[index]) == nil {
                command = tokens[index]
                index += 1
            }
            switch command {
            case "M": point = next(); move(to: point)
            case "L": point = next(); addLine(to: point)
            case "H": point.x = number(); addLine(to: point)
            case "V": point.y = number(); addLine(to: point)
            case "C":
                let control1 = next(), control2 = next()
                point = next()
                addCurve(to: point, control1: control1, control2: control2)
            case "Z":
                closeSubpath()
                // Takes no numbers: one after it, before another command, is an error.
                command = ""
            default:
                fatalError("SVG path data: \(command.isEmpty ? "a number with no command" : "'\(command)'") at token \(index) of \(tokens.count)")
            }
        }
    }
}
