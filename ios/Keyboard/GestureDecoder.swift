import Foundation
import CoreGraphics

/// SHARK2-style matcher, using the same pruning, resampling and constants as Android.
final class GestureDecoder {
    struct Candidate { let word: String; let score: Double }
    private struct Endpoints: Hashable { let first: Character; let last: Character }
    private final class Entry {
        let word: String
        let letters: CompactLetters
        let length: Int
        var template: [CGPoint]?
        var shape: [CGPoint]?
        init(_ word: String, _ letters: CompactLetters) { self.word = word; self.letters = letters; length = word.count }
    }
    private var buckets: [Endpoints: [Entry]] = [:]
    private var known = Set<String>()
    private var learned: [String: Entry] = [:]
    private var keys: [Character: CGPoint] = [:]
    private var keyWidth: CGFloat = 1
    private var cached: [Entry] = []
    private static let n = 50

    init(_ words: [String]) { addWords(words) }

    private func addWords(_ words: [String], learning: Bool = false) {
        for word in words {
            guard !word.isEmpty, word.allSatisfy({ $0.isLetter || $0 == "'" }), known.insert(word).inserted else { continue }
            var letters = ""
            for letter in word where letter.isLetter {
                if letters.last != letter { letters.append(letter) }
            }
            guard let first = letters.first, let last = letters.last else { continue }
            let entry = Entry(word, CompactLetters(letters))
            buckets[Endpoints(first: first, last: last), default: []].append(entry)
            if learning { learned[word] = entry }
        }
    }

    func setUserWords(_ words: [String]) {
        let wanted = Set(words)
        let removed = learned.keys.filter { !wanted.contains($0) }
        if !removed.isEmpty { clearTemplates() }
        for word in removed {
            guard let entry = learned.removeValue(forKey: word),
                  let first = entry.letters.first, let last = entry.letters.last else { continue }
            let endpoints = Endpoints(first: first, last: last)
            buckets[endpoints]?.removeAll { $0 === entry }
            if buckets[endpoints]?.isEmpty == true { buckets.removeValue(forKey: endpoints) }
            known.remove(word)
        }
        addWords(words, learning: true)
    }

    func setKeys(_ map: [Character: CGPoint], _ width: CGFloat) {
        let converted = map.filter { $0.key.isLetter }
        guard keys != converted || keyWidth != width else { return }
        keys = converted; keyWidth = max(1, width)
        clearTemplates()
    }

    func clearTemplates() {
        for entry in cached { entry.template = nil; entry.shape = nil }
        cached.removeAll(keepingCapacity: true)
    }

    /// N=50; score = -12 * (shape + 1.5 * location) + 0.6 * ln(freq) + context.
    /// Shape uses centroid/maximum-span normalization; location uses key width. k=12 was tuned in the JVM jitter harness.
    /// Endpoint radius=1.1 key widths; word length=1/8..2x visited keys (+2), allowing repeated letters.
    func decode(_ path: [GlidePoint], _ previous: String?, frequency: (String) -> Double,
                context: (String?, String) -> Double) -> [Candidate] {
        guard path.count >= 2, !keys.isEmpty else { return [] }
        // Bound retained templates to a few MB in the keyboard extension; never retain a full 60k x 50 cache.
        let input = resample(path.map { $0.position })
        let shape = normalize(input)
        let first = keys.filter { distance($0.value, path.first!.position) <= keyWidth * 1.1 }.keys.sorted()
        let last = keys.filter { distance($0.value, path.last!.position) <= keyWidth * 1.1 }.keys.sorted()
        var count = 0
        var prior: Character?
        for point in input {
            let nearest = keys.min { distance($0.value, point) < distance($1.value, point) }?.key
            if nearest != prior { count += 1; prior = nearest }
        }
        var top: [Candidate] = []
        for a in first { for b in last { for entry in buckets[Endpoints(first: a, last: b)] ?? [] {
            guard (max(1, count / 8)...max(4, count * 2 + 2)).contains(entry.length) else { continue }
            if entry.template == nil {
                if cached.count >= 2048 { clearTemplates() }
                let ideal = entry.letters.compactMap { keys[$0] }
                guard ideal.count == entry.letters.count else { continue }
                let template = resample(ideal)
                entry.template = template; entry.shape = normalize(template)
                cached.append(entry)
            }
            let location = meanDistance(input, entry.template!) / Double(keyWidth)
            let sh = meanDistance(shape, entry.shape!)
            let score = -12 * (sh + 1.5 * location) + 0.6 * log(max(1e-12, frequency(entry.word))) + context(previous, entry.word)
            let candidate = Candidate(word: entry.word, score: score)
            if let at = top.firstIndex(where: { $0.score < score || ($0.score == score && $0.word > entry.word) }) { top.insert(candidate, at: at) }
            else if top.count < 12 { top.append(candidate) }
            if top.count > 12 { top.removeLast() }
        } } }
        return top
    }

    private func distance(_ a: CGPoint, _ b: CGPoint) -> CGFloat { hypot(a.x - b.x, a.y - b.y) }
    private func meanDistance(_ a: [CGPoint], _ b: [CGPoint]) -> Double {
        var sum: CGFloat = 0
        for i in 0..<Self.n { sum += distance(a[i], b[i]) }
        return Double(sum) / Double(Self.n)
    }
    private func normalize(_ points: [CGPoint]) -> [CGPoint] {
        var cx: CGFloat = 0, cy: CGFloat = 0
        var minX = CGFloat.infinity, minY = CGFloat.infinity
        var maxX = -CGFloat.infinity, maxY = -CGFloat.infinity
        for p in points {
            cx += p.x; cy += p.y
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        cx /= CGFloat(Self.n); cy /= CGFloat(Self.n)
        let scale = max(keyWidth * 0.5, max(maxX - minX, maxY - minY))
        return points.map { CGPoint(x: ($0.x - cx) / scale, y: ($0.y - cy) / scale) }
    }
    private func resample(_ points: [CGPoint]) -> [CGPoint] {
        var cumulative = [CGFloat](repeating: 0, count: points.count)
        for i in 1..<points.count { cumulative[i] = cumulative[i - 1] + distance(points[i - 1], points[i]) }
        var result: [CGPoint] = []
        result.reserveCapacity(Self.n)
        var segment = 1
        for i in 0..<Self.n {
            let d = cumulative.last! * CGFloat(i) / CGFloat(Self.n - 1)
            while segment < points.count - 1 && cumulative[segment] < d { segment += 1 }
            let hi = min(segment, points.count - 1), lo = max(0, hi - 1)
            let span = cumulative[hi] - cumulative[lo]
            let f = span > 0 ? (d - cumulative[lo]) / span : 0
            result.append(CGPoint(x: points[lo].x + (points[hi].x - points[lo].x) * f,
                                  y: points[lo].y + (points[hi].y - points[lo].y) * f))
        }
        return result
    }
}
