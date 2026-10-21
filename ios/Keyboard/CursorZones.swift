import UIKit

/// Empty regions share the same row-edge geometry for hit testing, reach and accessibility.
struct CursorZone {
    let left: [CGPoint]
    let right: [CGPoint]
    let top: CGFloat
    let bottom: CGFloat
    let excluded: CGRect?
    let bounds: CGRect
    let path: UIBezierPath

    init(left: [CGPoint], right: [CGPoint], top: CGFloat, bottom: CGFloat, excluded: CGRect? = nil) {
        self.left = left; self.right = right; self.top = top; self.bottom = bottom; self.excluded = excluded
        let x0 = left.reduce(CGFloat.infinity) { min($0, $1.x) }
        let x1 = right.reduce(-CGFloat.infinity) { max($0, $1.x) }
        bounds = CGRect(x: x0, y: top, width: max(0, x1 - x0), height: bottom - top)
        let p = UIBezierPath()
        p.move(to: left[0])
        for point in left.dropFirst() { p.addLine(to: point) }
        for point in right.reversed() { p.addLine(to: point) }
        p.close()
        if let excluded = excluded { p.append(UIBezierPath(rect: excluded)); p.usesEvenOddFillRule = true }
        path = p
    }

    func contains(_ p: CGPoint) -> Bool {
        bounds.contains(p) && excluded?.contains(p) != true &&
            p.x >= Self.edge(left, at: p.y) && p.x <= Self.edge(right, at: p.y)
    }

    func hasRoom(_ width: CGFloat) -> Bool {
        for point in left where Self.edge(right, at: point.y) - Self.edge(left, at: point.y) < width { return false }
        for point in right where Self.edge(right, at: point.y) - Self.edge(left, at: point.y) < width { return false }
        return true
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

enum CursorZoneKind { case center, outer, empty, none }

/// Visual state only, independent of joystick speed. Mirrors Android's tested CursorPointer helper.
final class CursorPointer {
    private(set) var radius: CGFloat = 0
    private(set) var y: CGFloat = 0
    private(set) var minX: CGFloat = 0
    private(set) var maxX: CGFloat = 0
    private(set) var x: CGFloat = 0
    private var origin: CGFloat = 0
    private var velocity: CGFloat = 0
    private let pressedAt: TimeInterval
    private var releasedAt: TimeInterval?
    private var releaseAlpha: CGFloat = 0
    var released: Bool { releasedAt != nil }

    init(zone: CursorZone, press: CGPoint, radius: CGFloat, now: TimeInterval) {
        pressedAt = now
        self.radius = max(0, min(radius, (zone.bottom - zone.top) / 2))
        // Reserve a square around the disk, including all row vertices in its height, so tilted edges
        // and the one-hand switch cannot intersect the ring. Narrow zones scale the disk down.
        func fit(_ r: CGFloat) -> Bool {
            y = min(max(press.y, zone.top + r), zone.bottom - r)
            var left = max(CursorZone.edge(zone.left, at: y - r), CursorZone.edge(zone.left, at: y + r))
            var right = min(CursorZone.edge(zone.right, at: y - r), CursorZone.edge(zone.right, at: y + r))
            for p in zone.left where p.y > y - r && p.y < y + r { left = max(left, p.x) }
            for p in zone.right where p.y > y - r && p.y < y + r { right = min(right, p.x) }
            if let excluded = zone.excluded, y + r > excluded.minY, y - r < excluded.maxY {
                if press.x < excluded.midX { right = min(right, excluded.minX) }
                else { left = max(left, excluded.maxX) }
            }
            minX = left + r; maxX = right - r
            return minX <= maxX
        }
        if !fit(self.radius) {
            var low: CGFloat = 0, high = self.radius
            for _ in 0..<16 {
                let mid = (low + high) / 2
                if fit(mid) { low = mid } else { high = mid }
            }
            self.radius = low
            _ = fit(low)
        }
        origin = min(max(press.x, minX), maxX)
        x = origin
    }

    @discardableResult
    func step(_ push: CGFloat, seconds: CGFloat, reducedMotion: Bool) -> Bool {
        let p = min(1, max(-1, push))
        let target = origin + p * (p < 0 ? origin - minX : maxX - origin)
        if reducedMotion { x = target; velocity = 0; return false }
        let dt = min(0.04, max(0, seconds))
        let delta = x - target
        if velocity * delta > 0 { velocity = 0 }
        let c = velocity + 16 * delta
        let decay = CGFloat(exp(Double(-16 * dt)))
        let next = target + (delta + c * dt) * decay
        velocity = (velocity - 16 * c * dt) * decay
        let clamped = min(max(next, min(x, target)), max(x, target))
        x = min(maxX, max(minX, clamped))
        if abs(target - x) < 0.01 && abs(velocity) < 0.1 {
            x = target; velocity = 0
            return false
        }
        return true
    }

    func alpha(_ now: TimeInterval, reducedMotion: Bool) -> CGFloat {
        if reducedMotion { return released ? 0 : 1 }
        if let lift = releasedAt { return releaseAlpha * CGFloat(min(1, max(0, 1 - (now - lift) / 0.15))) }
        return CGFloat(min(1, max(0, (now - pressedAt) / 0.12)))
    }

    func release(_ now: TimeInterval, reducedMotion: Bool) {
        releaseAlpha = alpha(now, reducedMotion: reducedMotion)
        releasedAt = now
    }

    func fading(_ now: TimeInterval, reducedMotion: Bool) -> Bool {
        !reducedMotion && (released ? alpha(now, reducedMotion: false) > 0 : now - pressedAt < 0.12)
    }
}

/// Selects the center gap or outer padding for layouts that are not in one-hand mode.
func cursorZoneKind(_ control: CursorControl, outer: [CursorZone]) -> CursorZoneKind {
    switch control {
    case .off: return .none
    case .centerGap: return .center
    case .auto, .outerEdges:
        return outer.count == 2 && outer.allSatisfy { $0.hasRoom(32) } ? .outer : .center
    }
}

struct CursorZonePlan {
    let kind: CursorZoneKind
    let showSwitch: Bool
}

/// One-hand mode reserves only the big empty side; its switch remains available with cursor control off.
func cursorZonePlan(oneHand: Bool, split: Bool, control: CursorControl, outer: [CursorZone]) -> CursorZonePlan {
    if oneHand { return CursorZonePlan(kind: control == .off ? .none : .empty, showSwitch: true) }
    let kind = cursorZoneKind(control, outer: outer)
    return CursorZonePlan(kind: kind == .center && !split ? .none : kind, showSwitch: false)
}

/// Cubic ease-out, in points and monotonic seconds. Layout is computed only at the destination.
func slideOffset(_ distance: CGFloat, elapsed: TimeInterval, reducedMotion: Bool) -> CGFloat {
    if reducedMotion { return 0 }
    let remaining = 1 - CGFloat(min(1, max(0, elapsed / 0.25)))
    return distance * remaining * remaining * remaining
}
