import UIKit

/// Empty regions share the same row-edge geometry for hit testing, reach and accessibility.
struct CursorZone {
    let left: [CGPoint]
    let right: [CGPoint]
    let top: CGFloat
    let bottom: CGFloat
    var excluded: CGRect? = nil

    var bounds: CGRect {
        let x0 = left.map(\.x).min()!, x1 = right.map(\.x).max()!
        return CGRect(x: x0, y: top, width: max(0, x1 - x0), height: bottom - top)
    }

    func contains(_ p: CGPoint) -> Bool {
        bounds.contains(p) && excluded?.contains(p) != true &&
            p.x >= Self.edge(left, at: p.y) && p.x <= Self.edge(right, at: p.y)
    }

    func hasRoom(_ width: CGFloat) -> Bool {
        (left + right).allSatisfy { Self.edge(right, at: $0.y) - Self.edge(left, at: $0.y) >= width }
    }

    var path: UIBezierPath {
        let p = UIBezierPath()
        p.move(to: left[0])
        for point in left.dropFirst() { p.addLine(to: point) }
        for point in right.reversed() { p.addLine(to: point) }
        p.close()
        if let excluded = excluded { p.append(UIBezierPath(rect: excluded)); p.usesEvenOddFillRule = true }
        return p
    }

    static func edge(_ side: [CGPoint], at y: CGFloat) -> CGFloat {
        if y <= side[0].y { return side[0].x }
        for i in 1..<side.count where y <= side[i].y {
            let a = side[i - 1], b = side[i]
            return b.y - a.y < 0.5 ? b.x : a.x + (b.x - a.x) * (y - a.y) / (b.y - a.y)
        }
        return side[side.count - 1].x
    }
}

enum CursorZoneKind { case center, outer, none }

func cursorZoneKind(_ control: CursorControl, outer: [CursorZone], hasEmptySide: Bool) -> CursorZoneKind {
    switch control {
    case .off: return .none
    case .centerGap: return .center
    case .auto, .outerEdges:
        return hasEmptySide || (outer.count == 2 && outer.allSatisfy { $0.hasRoom(32) }) ? .outer : .center
    }
}

/// Cubic ease-out, in points and monotonic seconds. Layout is computed only at the destination.
func slideOffset(_ distance: CGFloat, elapsed: TimeInterval, reducedMotion: Bool) -> CGFloat {
    if reducedMotion { return 0 }
    let remaining = 1 - CGFloat(min(1, max(0, elapsed / 0.25)))
    return distance * remaining * remaining * remaining
}
