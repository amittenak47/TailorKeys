import Foundation
import CoreGraphics

/// Shared by the view and decoder, and usable without UIKit in the logic harness.
struct GlidePoint {
    let position: CGPoint
    let time: TimeInterval
}

struct DoubleLetterHint { let letter: Character; let pathIndex: Int }

/// Incremental geometry only. No dictionary work or temporary paths in the drawing loop.
final class SwipeTrace {
    private let keys: [Character: CGPoint]
    private let width: CGFloat
    private var letters = ""
    private var first = true
    private var anchor = CGPoint.zero, pivot = CGPoint.zero, dwell = CGPoint.zero, last = CGPoint.zero
    private var dwellAt: TimeInterval = 0
    private var dwellLetter: Character = " "
    private var fired = false
    private var travel: CGFloat = 0
    private var end: Character = " "
    private(set) var hints: [DoubleLetterHint] = []
    private(set) var dotLetter: Character = " "
    private(set) var dotAt: TimeInterval = -.infinity
    init(_ keys: [Character: CGPoint], _ width: CGFloat) {
        self.keys = keys; self.width = width
        letters.reserveCapacity(32); hints.reserveCapacity(4)
    }
    private func distance(_ a: CGPoint, _ b: CGPoint) -> CGFloat { hypot(a.x - b.x, a.y - b.y) }
    private func nearest(_ point: CGPoint) -> Character {
        var best = CGFloat.infinity
        var letter: Character = " "
        for (ch, centre) in keys {
            let dx = point.x - centre.x, dy = point.y - centre.y, d = dx * dx + dy * dy
            if d < best { best = d; letter = ch }
        }
        return letter
    }
    private func append(_ ch: Character) { if ch != " " && letters.last != ch { letters.append(ch) } }
    func sample(_ point: CGPoint, _ time: TimeInterval, _ index: Int) {
        let ch = nearest(point)
        if first {
            first = false; append(ch)
            anchor = point; pivot = point; dwell = point; last = point
            dwellAt = time; dwellLetter = ch
        }
        end = ch
        travel += distance(point, last); last = point
        if ch != dwellLetter || distance(point, dwell) > width * 0.35 || (!fired && (travel > width * 0.22 || distance(point, dwell) > width * 0.12)) {
            dwell = point; dwellAt = time; dwellLetter = ch; fired = false; travel = 0
        }
        if !fired, time - dwellAt >= 0.210, let centre = keys[ch], distance(point, centre) <= width * 0.55 {
            append(ch); letters.append(ch)
            hints.append(DoubleLetterHint(letter: ch, pathIndex: index))
            fired = true; dotLetter = ch; dotAt = time
        }
        let ax = pivot.x - anchor.x, ay = pivot.y - anchor.y
        let bx = point.x - pivot.x, by = point.y - pivot.y
        let a = hypot(ax, ay), b = hypot(bx, by)
        if b >= width * 0.28 {
            if a >= width * 0.28 && (ax * bx + ay * by) / (a * b) < 0.65 { append(nearest(pivot)) }
            anchor = pivot; pivot = point
        }
    }
    func text() -> String { end == " " || letters.last == end ? letters : letters + String(end) }
    static func appendToPrefix(_ prefix: String, _ raw: String) -> String {
        prefix + (prefix.last.map { String($0).lowercased() } == raw.first.map { String($0).lowercased() } && !prefix.isEmpty ? String(raw.dropFirst()) : raw)
    }
}
