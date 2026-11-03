import SwiftUI

extension Path {
    /// A path from SVG path data in absolute commands (M, L, H, V, C, Z), as the brands' files write their marks
    /// (`GoogleLogo`, `GitHubLogo`); numbers may carry an exponent (`6.69539e-07`).
    init(svg data: String) {
        var tokens = data.matches(of: #/[MLHVCZ]|-?[0-9]*\.?[0-9]+(?:e-?[0-9]+)?/#).map { String($0.output) }[...]
        self.init()
        var command = "M"
        var point = CGPoint.zero
        func number() -> CGFloat { CGFloat(Double(tokens.removeFirst()) ?? 0) }
        func next() -> CGPoint { CGPoint(x: number(), y: number()) }
        while let token = tokens.first {
            if Double(token) == nil {
                command = token
                tokens.removeFirst()
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
            default: closeSubpath()
            }
        }
    }
}
