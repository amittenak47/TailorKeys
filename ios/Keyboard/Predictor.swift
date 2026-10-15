import Foundation

/// Suggestions for the two wheels, from the bundled word list plus words you type (kept on this device).
///
/// Closest (right wheel): completions of what you're typing, most likely first. Words you use count most.
/// Explore (left wheel): a weighted random draw from further down the same completions. The draw is seeded
/// by the prefix, so the wheel doesn't reshuffle on every redraw.
final class Predictor {
    private let base: [String]
    private var rank: [String: Int] = [:]
    private var user: [String: Int]
    private let defaults = UserDefaults.standard
    private let userKey = "user_words"

    init() {
        if let url = Bundle.main.url(forResource: "words", withExtension: "txt"), let text = try? String(contentsOf: url, encoding: .utf8) {
            base = text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces).lowercased() }.filter { !$0.isEmpty }
        } else {
            base = []
        }
        for (i, w) in base.enumerated() where rank[w] == nil { rank[w] = i }
        user = defaults.dictionary(forKey: userKey) as? [String: Int] ?? [:]
    }

    private func score(_ w: String) -> Double { 1.0 / Double((rank[w] ?? 5000) + 10) + Double(user[w] ?? 0) * 0.05 }

    private func candidates(_ prefix: String) -> [String] {
        let p = prefix.lowercased()
        var seen = Set<String>()
        var all: [String] = []
        for w in user.keys where w.hasPrefix(p) && w != p && seen.insert(w).inserted { all.append(w) }
        for w in base where w.hasPrefix(p) && w != p && seen.insert(w).inserted { all.append(w) }
        return all.sorted { score($0) > score($1) }
    }

    func closest(_ prefix: String, _ n: Int = 8) -> [String] {
        candidates(prefix).prefix(n).map { matchCase($0, prefix) }
    }

    func explore(_ prefix: String, _ n: Int = 8) -> [String] {
        let c = candidates(prefix)
        if c.count <= 2 { return c.map { matchCase($0, prefix) } }
        var rng = SplitMix(seed: prefix.lowercased().utf8.reduce(5381) { ($0 &* 33) &+ UInt64($1) })
        // Weighted sampling without replacement. Temperature 2.5 flattens the ranking.
        return c.dropFirst().prefix(300)
            .map { ($0, pow(Double.random(in: 0..<1, using: &rng), 1.0 / pow(score($0), 1 / 2.5))) }
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
    func learn(_ word: String) {
        let w = word.lowercased()
        guard w.count >= 2, w.allSatisfy({ $0.isLetter || $0 == "'" }) else { return }
        user[w, default: 0] += 1
        defaults.set(user, forKey: userKey)
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
