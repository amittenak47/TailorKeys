import Foundation

/// Suggestions for the two wheels, from the bundled word list plus words you type (kept on this device).
///
/// Closest (right wheel): completions and layout-aware corrections, ranked with the previous word.
/// Explore (left wheel): a weighted random draw from further down the same completions. The draw is seeded
/// by the prefix and context, so the wheel stays steady on redraw.
final class Predictor {
    private struct Word {
        let text: String
        let letters: [Character]
    }
    private struct Candidate {
        let word: String
        let score: Double
        let correction: Bool
    }
    private let base: [Word]
    private var rank: [String: Int] = [:]
    private var user: [String: Int] = [:]
    private var userLetters: [String: [Character]] = [:]
    private var seedPairs: [String: [String: Int]] = [:]
    private var userPairs: [String: [String: Int]] = [:]
    private var neighbours: [Character: Set<Character>] = [:]
    private let defaults = UserDefaults.standard
    private let userKey = "user_words"
    private let pairKey = "user_bigrams"
    private var cachedPrefix: String?
    private var cachedPrev: String?
    private var cachedCandidates: [Candidate]?

    init() {
        if let url = Bundle.main.url(forResource: "words", withExtension: "txt"), let text = try? String(contentsOf: url, encoding: .utf8) {
            base = text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
                .filter { !$0.isEmpty }.map { Word(text: $0, letters: Array($0)) }
        } else {
            base = []
        }
        for (i, w) in base.enumerated() where rank[w.text] == nil { rank[w.text] = i }
        if let url = Bundle.main.url(forResource: "bigrams", withExtension: "txt"), let text = try? String(contentsOf: url, encoding: .utf8) {
            for line in text.split(whereSeparator: \.isNewline) {
                let parts = line.split(separator: " ")
                if parts.count == 3, let weight = Int(parts[2]) {
                    let prev = String(parts[0]), word = String(parts[1])
                    if rank[prev] != nil && rank[word] != nil { seedPairs[prev, default: [:]][word] = weight }
                }
            }
        }
        user = defaults.dictionary(forKey: userKey) as? [String: Int] ?? [:]
        for w in user.keys { userLetters[w] = Array(w) }
        userPairs = defaults.dictionary(forKey: pairKey) as? [String: [String: Int]] ?? [:]
    }

    func setLayout(_ grid: KeyGrid) {
        cachedCandidates = nil
        neighbours.removeAll()
        let letters = grid.items.filter { key in
            guard let k = key.keys.first else { return false }
            return k.count == 1 && k.first!.isLetter
        }
        for a in letters { for b in letters {
            if a === b || abs(a.r - b.r) > 1 { continue }
            let nearColumns = a.c <= b.c + b.w && b.c <= a.c + a.w
            let acrossCenter = a.c + a.w - 1 == 4 && b.c == 6 || b.c + b.w - 1 == 4 && a.c == 6
            if nearColumns || acrossCenter {
                neighbours[a.keys[0].lowercased().first!, default: []].insert(b.keys[0].lowercased().first!)
            }
        } }
    }

    private func frequency(_ w: String) -> Double { 1.0 / Double((rank[w] ?? 5000) + 10) + Double(user[w] ?? 0) * 0.05 }
    private func pairCount(_ prev: String?, _ w: String) -> Double {
        guard let prev = prev else { return 0 }
        return Double(seedPairs[prev]?[w] ?? 0) + 3.0 * Double(userPairs[prev]?[w] ?? 0)
    }

    // Costs are tenths. Three bounded rows are reused for every dictionary entry in a lookup.
    private func cost(_ p: [Character], _ w: [Character], _ limit: Int,
                      _ older: inout [Int], _ previous: inout [Int], _ current: inout [Int]) -> Int {
        guard let first = w.first else { return 1000 }
        let transposed = w.count > 1 && p[0] == w[1] && p[1] == w[0]
        if p[0] != first && neighbours[p[0]]?.contains(first) != true && !transposed { return 1000 }
        let extra = p[0] == first ? 0 : 3
        let bound = limit - extra
        let band = bound / 8
        let prefixLength = min(p.count, w.count)
        if p.count - prefixLength > band { return 1000 }
        let whole = abs(w.count - p.count) <= 2
        let end = min(whole ? w.count : prefixLength, p.count + band)
        for j in previous.indices { older[j] = 1000; previous[j] = 1000; current[j] = 1000 }
        for j in 0...min(p.count, band) { previous[j] = j * 8 }
        var previousMin = 0
        var best = 1000
        for i in 1...end {
            let from = max(1, i - band)
            let to = min(p.count, i + band)
            current[0] = i <= band ? i * 8 : 1000
            if from > 1 { current[from - 1] = 1000 }
            if to < p.count { current[to + 1] = 1000 }
            var rowMin = current[0]
            for j in from...to {
                let substitution = p[j - 1] == w[i - 1] ? 0 : (neighbours[p[j - 1]]?.contains(w[i - 1]) == true ? 4 : 10)
                var value = min(previous[j] + 8, min(current[j - 1] + 8, previous[j - 1] + substitution))
                if i > 1 && j > 1 && p[j - 1] == w[i - 2] && p[j - 2] == w[i - 1] {
                    value = min(value, older[j - 2] + 6)
                }
                current[j] = value
                rowMin = min(rowMin, value)
            }
            if i == prefixLength || whole && i == w.count { best = min(best, current[p.count]) }
            // A transposition can jump over one over-budget row, but never two.
            if rowMin > bound && previousMin > bound { break }
            previousMin = rowMin
            swap(&older, &previous)
            swap(&previous, &current)
        }
        return best + extra
    }

    private func candidates(_ prefix: String, _ prev: String?) -> [Candidate] {
        let p = prefix.lowercased()
        if cachedPrefix == p && cachedPrev == prev, let cached = cachedCandidates { return cached }
        let letters = Array(p)
        let limit = letters.count <= 4 ? 9 : 13
        var older = [Int](repeating: 1000, count: letters.count + 1)
        var previous = older
        var current = older
        var all: [Candidate] = []
        func add(_ w: String, _ chars: [Character]) {
            if w == p { return }
            let completion = w.hasPrefix(p)
            let distance: Int
            if completion { distance = 0 }
            else if letters.count >= 2 { distance = cost(letters, chars, limit, &older, &previous, &current) }
            else { return }
            if distance > limit { return }
            let score = log(frequency(w)) + 1.5 * log(1 + pairCount(prev, w)) - 2.5 * Double(distance) / 10.0
            all.append(Candidate(word: w, score: score, correction: !completion))
        }
        for (w, chars) in userLetters { add(w, chars) }
        for (i, w) in base.enumerated() where user[w.text] == nil && rank[w.text] == i { add(w.text, w.letters) }
        let result = all.sorted {
            if p.isEmpty, prev != nil {
                let a = pairCount(prev, $0.word), b = pairCount(prev, $1.word)
                if a != b { return a > b }
            }
            return $0.score == $1.score ? $0.word < $1.word : $0.score > $1.score
        }
        cachedPrefix = p
        cachedPrev = prev
        cachedCandidates = result
        return result
    }

    func closest(_ prefix: String, _ prev: String? = nil, _ n: Int = 8) -> [String] {
        let c = candidates(prefix, prev)
        let maxCorrections = c.reduce(0) { $0 + ($1.correction ? 0 : 1) } < 5 ? n : 3
        var corrections = 0
        var result: [String] = []
        for candidate in c {
            if result.count >= n { break }
            if candidate.correction {
                if corrections >= maxCorrections { continue }
                corrections += 1
            }
            result.append(matchCase(candidate.word, prefix))
        }
        return result
    }

    func explore(_ prefix: String, _ prev: String? = nil, _ n: Int = 8) -> [String] {
        let c = candidates(prefix, prev)
        let seed = prefix.lowercased() + (prev.map { "\t" + $0 } ?? "")
        var rng = SplitMix(seed: seed.utf8.reduce(5381) { ($0 &* 33) &+ UInt64($1) })
        // Weighted sampling without replacement. Temperature 2.5 flattens the ranking.
        return c.dropFirst().prefix(300)
            .map { ($0.word, pow(Double.random(in: 0..<1, using: &rng), 1.0 / exp($0.score / 2.5))) }
            .sorted { $0.1 > $1.1 }
            .prefix(n)
            .map { matchCase($0.0, prefix) }
    }

    /// Symbol suggestions for the 123 and #+= layers, based on the character before the cursor.
    func symbols(before: Character?) -> (explore: [String], closest: [String]) {
        let closers: [Character: String] = ["(": ")", "[": "]", "{": "}", "<": ">", "\"": "\"", "'": "'"]
        let closest: [String]
        if let b = before, let close = closers[b] { closest = [close, ".", ","] }
        else if let b = before, b.isNumber { closest = [".", ",", "%", "$", ":", "-", "/"] }
        else { closest = [".", ",", "?", "!", "'", "\"", ":", ";"] }
        return (["…", "->", "=>", "—", "≠", "±", "°", "×", "÷", "≈", "✓", ":)", "<3", "©", "™", "§"], closest)
    }

    /// Remembers a word you typed.
    func learn(_ word: String, _ prev: String? = nil) {
        let w = word.lowercased()
        guard w.count >= 2, w.allSatisfy({ $0.isLetter || $0 == "'" }) else { return }
        user[w, default: 0] += 1
        if userLetters[w] == nil { userLetters[w] = Array(w) }
        if let prev = prev { userPairs[prev, default: [:]][w, default: 0] += 1 }
        cachedCandidates = nil
        save()
    }

    func save() {
        defaults.set(user, forKey: userKey)
        defaults.set(userPairs, forKey: pairKey)
    }

    private func matchCase(_ w: String, _ prefix: String) -> String {
        if prefix.count > 1 && prefix.allSatisfy({ !$0.isLetter || $0.isUppercase }) { return w.uppercased() }
        if let f = prefix.first, f.isUppercase { return w.prefix(1).uppercased() + w.dropFirst() }
        return w
    }
}

/// Small seeded random generator, so a prefix always produces the same explore list.
struct SplitMix: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }
}
