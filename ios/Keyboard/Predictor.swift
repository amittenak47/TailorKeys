import Foundation

/// Suggestions for the two wheels, from the bundled word list plus words you type (kept on this device).
///
/// Closest (right wheel): completions and layout-aware corrections, ranked with two previous words.
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
        let pairCount: Int
        let tripleCount: Int
    }
    private let base: [Word]
    private var rank: [String: Int] = [:]
    private var user: [String: Int] = [:]
    private var userLetters: [String: [Character]] = [:]
    // Context IDs -> sorted packed (word ID, count) rows. No retained n-gram strings.
    private final class SeedTables {
        let pairs: [UInt64: [UInt64]]
        let triples: [UInt64: [UInt64]]
        let frequencies: [Double]
        let common: [String]
        init(_ pairs: [UInt64: [UInt64]], _ triples: [UInt64: [UInt64]], _ frequencies: [Double] = [], _ common: [String] = []) {
            self.pairs = pairs
            self.triples = triples
            self.frequencies = frequencies
            self.common = common
        }
    }
    private let tableLock = NSLock()
    private var seedTables: SeedTables?
    private var cachedTables: SeedTables?
    private var topWords: [String] = []
    private var userPairs: [String: [String: Int]] = [:]
    private var userTriples: [String: [String: Int]] = [:]
    private var neighbours: [Character: Set<Character>] = [:]
    private var gridCosts = [UInt8](repeating: 10, count: ComposingTaps.letters * ComposingTaps.letters)
    private let defaults = UserDefaults.standard
    let language: String
    private let userKey: String
    private let pairKey: String
    private let tripleKey: String
    private var cachedPrefix: String?
    private var cachedPrev: String?
    private var cachedPrev2: String?
    private var cachedCandidates: [Candidate]?
    private var cachedTaps: ComposingTaps?
    private var cachedTapRevision: UInt64?
    private var english: EnglishContext?
    private var cachedScores: EnglishContext.Scores?
    private var cachedScoreVersion: UInt64?

    init(_ language: String = "en", onReady: @escaping () -> Void = {}) {
        self.language = language
        let suffix = language == "en" ? "" : "_" + language
        userKey = "user_words" + suffix
        pairKey = "user_bigrams" + suffix
        tripleKey = "user_trigrams" + suffix
        if let url = Bundle.main.url(forResource: "words" + suffix, withExtension: "txt"), let text = try? String(contentsOf: url, encoding: .utf8) {
            base = text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
                .filter { !$0.isEmpty }.map { Word(text: $0, letters: Array($0)) }
        } else {
            base = []
        }
        for (i, w) in base.enumerated() where rank[w.text] == nil { rank[w.text] = i }
        if language == "en" { english = EnglishContext(base.map { $0.text }, onReady) }
        user = defaults.dictionary(forKey: userKey) as? [String: Int] ?? [:]
        for w in user.keys { userLetters[w] = Array(w) }
        userPairs = defaults.dictionary(forKey: pairKey) as? [String: [String: Int]] ?? [:]
        userTriples = defaults.dictionary(forKey: tripleKey) as? [String: [String: Int]] ?? [:]
        // Only these base words and learned words can enter the frequency top 300.
        topWords = Array(Set(base.prefix(300).map { $0.text } + Array(user.keys))).sorted {
            let a = frequency($0), b = frequency($1)
            return a == b ? $0 < $1 : a > b
        }.prefix(300).map { $0 }
        let wordRanks = rank
        let wordTexts = base.map { $0.text }
        DispatchQueue(label: "app.tailorkeys.ngrams", qos: .utility).async { [weak self] in
            let pairs = Self.loadCounts("bigrams" + suffix, 2, wordRanks)
            let triples = Self.loadCounts("trigrams" + suffix, 3, wordRanks)
            // Conversation counts complement AOSP ranks only in the new languages.
            var frequencies: [Double] = []
            if language != "en" {
                frequencies = [Double](repeating: 0, count: wordTexts.count)
                for (context, row) in pairs { for entry in row {
                    let count = Double(entry & 0xFFFF_FFFF)
                    frequencies[Int(context)] += count
                    frequencies[Int(entry >> 32)] += count
                } }
                if let max = frequencies.max(), max > 0 { frequencies = frequencies.map { $0 / max } }
            }
            let common = frequencies.indices.sorted { frequencies[$0] == frequencies[$1] ? $0 < $1 : frequencies[$0] > frequencies[$1] }.prefix(300).map { wordTexts[$0] }
            let tables = SeedTables(pairs, triples, frequencies, common)
            guard let self = self else { return }
            // Publish both immutable tables together; all cache mutation stays on the caller thread.
            self.tableLock.lock()
            self.seedTables = tables
            self.tableLock.unlock()
            onReady()
        }
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
        for i in gridCosts.indices { gridCosts[i] = 10 }
        for c in 0..<ComposingTaps.letters { gridCosts[c * ComposingTaps.letters + c] = 0 }
        for (a, adjacent) in neighbours { for b in adjacent {
            if let first = a.unicodeScalars.first?.value, let second = b.unicodeScalars.first?.value,
               first < UInt32(ComposingTaps.letters), second < UInt32(ComposingTaps.letters) {
                gridCosts[Int(first) * ComposingTaps.letters + Int(second)] = 4
            }
        } }
    }

    func updateContext(_ before: String, _ prefix: String) { english?.update(before, prefix) }
    func resetContext() { english?.reset() }

    private func gridCost(_ a: Character, _ b: Character) -> Int {
        if a == b { return 0 }
        if let first = a.unicodeScalars.first?.value, let second = b.unicodeScalars.first?.value,
           first < UInt32(ComposingTaps.letters), second < UInt32(ComposingTaps.letters) {
            return Int(gridCosts[Int(first) * ComposingTaps.letters + Int(second)])
        }
        return a == b ? 0 : (neighbours[a]?.contains(b) == true ? 4 : 10)
    }

    private func frequency(_ w: String, _ tables: SeedTables? = nil) -> Double {
        let corpus: Double
        if let id = rank[w], let values = tables?.frequencies, values.indices.contains(id) { corpus = values[id] }
        else { corpus = 0 }
        return 1.0 / Double((rank[w] ?? 5000) + 10) + 0.2 * corpus + Double(user[w] ?? 0) * 0.05
    }
    private static func contextId(_ first: Int, _ second: Int) -> UInt64 {
        (UInt64(first) << 32) | UInt64(second)
    }

    private static func loadCounts(_ name: String, _ n: Int, _ rank: [String: Int]) -> [UInt64: [UInt64]] {
        guard let url = Bundle.main.url(forResource: name, withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [:] }
        var rows: [UInt64: [UInt64]] = [:]
        for line in text.split(whereSeparator: \.isNewline) {
            let parts = line.split(separator: " ")
            guard parts.count == n + 1, let first = rank[String(parts[0])],
                  let second = rank[String(parts[1])], let word = rank[String(parts[n - 1])],
                  let count = UInt32(parts[n]), count > 0 else { continue }
            let key = n == 2 ? UInt64(first) : contextId(first, second)
            rows[key, default: []].append((UInt64(word) << 32) | UInt64(count))
        }
        for key in Array(rows.keys) { rows[key]!.sort() }
        return rows
    }

    private func count(_ row: [UInt64], _ word: Int?) -> Int {
        guard let word = word else { return 0 }
        var low = 0, high = row.count
        while low < high {
            let mid = (low + high) / 2
            let id = Int(row[mid] >> 32)
            if id == word { return Int(row[mid] & 0xFFFF_FFFF) }
            if id < word { low = mid + 1 } else { high = mid }
        }
        return 0
    }

    // Costs are tenths. Three bounded rows are reused for every dictionary entry in a lookup.
    private func cost(_ p: [Character], _ w: [Character], _ limit: Int,
                      _ older: inout [Int], _ previous: inout [Int], _ current: inout [Int], _ taps: ComposingTaps?) -> Int {
        guard let first = w.first else { return 1000 }
        let transposed = w.count > 1 && p[0] == w[1] && p[1] == w[0]
        let firstSpatial = taps?.hasTap(0) == true
        let firstCost = firstSpatial ? taps!.cost(0, first) : 0
        if !transposed && (firstSpatial ? firstCost > 6 : (p[0] != first && neighbours[p[0]]?.contains(first) != true)) { return 1000 }
        let extra = firstSpatial ? (transposed ? min(3, firstCost) : firstCost) : (p[0] == first ? 0 : 3)
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
                let substitution: Int
                if let taps = taps, taps.hasTap(j - 1) { substitution = taps.cost(j - 1, w[i - 1]) }
                else { substitution = gridCost(p[j - 1], w[i - 1]) }
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

    private func candidates(_ prefix: String, _ prev: String?, _ prev2: String?, _ input: ComposingTaps?) -> [Candidate] {
        if let english = english { return english.withScores { candidates(prefix, prev, prev2, input, $0) } }
        return candidates(prefix, prev, prev2, input, nil)
    }

    private func candidates(_ prefix: String, _ prev: String?, _ prev2: String?, _ input: ComposingTaps?, _ scores: EnglishContext.Scores?) -> [Candidate] {
        let p = prefix.lowercased()
        let taps = input?.hasTaps(prefix) == true ? input : nil
        let tapRevision = taps?.revision
        tableLock.lock()
        let tables = seedTables
        tableLock.unlock()
        if cachedPrefix == p && cachedPrev == prev && cachedPrev2 == prev2 && cachedTables === tables &&
           cachedTaps === taps && cachedTapRevision == tapRevision && cachedScores === scores && cachedScoreVersion == scores?.version,
           let cached = cachedCandidates { return cached }
        let pairs = prev.flatMap { rank[$0] }.flatMap { tables?.pairs[UInt64($0)] } ?? []
        var triples: [UInt64] = []
        if let prev = prev, let prev2 = prev2, let first = rank[prev2], let second = rank[prev] {
            triples = tables?.triples[Self.contextId(first, second)] ?? []
        }
        let learnedPairs = prev.flatMap { userPairs[$0] } ?? [:]
        let learnedTriples = prev2.flatMap { first in prev.flatMap { userTriples[first + "\t" + $0] } } ?? [:]
        let letters = Array(p)
        let legacyLimit = letters.count <= 4 ? 9 : 13
        let limit = taps != nil ? min(22, max(legacyLimit, 3 * letters.count + 3)) : legacyLimit
        var older = [Int](repeating: 1000, count: letters.count + 1)
        var previous = older
        var current = older
        var all: [Candidate] = []
        func add(_ w: String, _ chars: [Character]) {
            if w.hasPrefix("<") || (w == p && scores == nil) { return }
            let completion = w.hasPrefix(p)
            let distance: Int
            if completion { distance = 0 }
            else if letters.count >= 2 { distance = cost(letters, chars, limit, &older, &previous, &current, taps) }
            else { return }
            if distance > limit { return }
            let id = rank[w]
            let seedPair = scores == nil ? count(pairs, id) : 0, userPair = learnedPairs[w] ?? 0
            let seedTriple = scores == nil ? count(triples, id) : 0, userTriple = learnedTriples[w] ?? 0
            let score: Double
            if let scores = scores {
                score = scores.probability(w, id) + personalScore(w, userPair, userTriple) - 32.0 * Double(distance) / 10.0
            } else {
                let context = log(1 + Double(seedPair)) + 1.5 * log(1 + Double(userPair)) +
                    1.2 * (log(1 + Double(seedTriple)) + 1.5 * log(1 + Double(userTriple)))
                score = log(frequency(w, tables)) + 1.5 * context - 2.5 * Double(distance) / 10.0
            }
            all.append(Candidate(word: w, score: score, correction: !completion,
                                 pairCount: seedPair + userPair, tripleCount: seedTriple + userTriple))
        }
        if p.isEmpty {
            // No dictionary scan: only this context's followers plus the load-time top 300.
            var pool = Set<String>()
            if let scores = scores {
                for id in scores.top { pool.insert(scores.model.vocabulary[id]) }
            } else {
                for row in triples { pool.insert(base[Int(row >> 32)].text) }
                for row in pairs { pool.insert(base[Int(row >> 32)].text) }
                pool.formUnion(topWords)
                pool.formUnion(tables?.common ?? [])
            }
            pool.formUnion(learnedTriples.keys)
            pool.formUnion(learnedPairs.keys)
            for w in pool { add(w, []) }
        } else {
            for (w, chars) in userLetters { add(w, chars) }
            for (i, w) in base.enumerated() where user[w.text] == nil && rank[w.text] == i { add(w.text, w.letters) }
        }
        let result = all.sorted {
            if scores == nil, p.isEmpty, prev != nil {
                // Trigram followers, then remaining bigram followers, then frequency fallback.
                let aTier = $0.tripleCount > 0 ? 2 : ($0.pairCount > 0 ? 1 : 0)
                let bTier = $1.tripleCount > 0 ? 2 : ($1.pairCount > 0 ? 1 : 0)
                if aTier != bTier { return aTier > bTier }
                let a = $0.tripleCount > 0 ? $0.tripleCount : $0.pairCount
                let b = $1.tripleCount > 0 ? $1.tripleCount : $1.pairCount
                if a != b { return a > b }
            }
            return $0.score == $1.score ? $0.word < $1.word : $0.score > $1.score
        }
        cachedPrefix = p
        cachedPrev = prev
        cachedPrev2 = prev2
        cachedTables = tables
        cachedTaps = taps
        cachedTapRevision = tapRevision
        cachedScores = scores
        cachedScoreVersion = scores?.version
        cachedCandidates = result
        return result
    }

    func nextLetters(_ prefix: String, _ prev: String?, _ prev2: String? = nil, taps: ComposingTaps? = nil) -> [Character: Double] {
        let length = prefix.count
        var weights: [Character: Double] = [:]
        for candidate in candidates(prefix, prev, prev2, taps) where !candidate.correction {
            guard let letter = candidate.word.dropFirst(length).first else { continue }
            weights[letter, default: 0] += exp(candidate.score)
        }
        guard let max = weights.values.max() else { return [:] }
        return weights.mapValues { $0 / max }
    }

    func isDeadEnd(_ prefix: String) -> Bool {
        let p = prefix.lowercased()
        return prefix.count >= 2 && !user.keys.contains { $0.hasPrefix(p) } && !base.contains { $0.text.hasPrefix(p) }
    }

    func closest(_ prefix: String, _ prev: String? = nil, _ prev2: String? = nil, _ n: Int = 8, taps: ComposingTaps? = nil) -> [String] {
        let c = candidates(prefix, prev, prev2, taps)
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

    func explore(_ prefix: String, _ prev: String? = nil, _ prev2: String? = nil, _ n: Int = 8, taps: ComposingTaps? = nil) -> [String] {
        let c = candidates(prefix, prev, prev2, taps)
        let seed = prefix.lowercased() + "\t" + (prev ?? "") + "\t" + (prev2 ?? "")
        var rng = SplitMix(seed: seed.utf8.reduce(5381) { ($0 &* 33) &+ UInt64($1) })
        // Weighted sampling without replacement. Temperature 2.5 flattens the ranking.
        let pool = prefix.isEmpty ? c.dropFirst() : c.dropFirst().prefix(300)
        return pool.map { ($0.word, pow(Double.random(in: 0..<1, using: &rng), 1.0 / exp($0.score / 2.5))) }
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
    func learn(_ word: String, _ prev: String? = nil, _ prev2: String? = nil) {
        let w = word.lowercased()
        guard w.count >= 2, w.allSatisfy({ $0.isLetter || $0 == "'" }) else { return }
        user[w, default: 0] += 1
        if userLetters[w] == nil { userLetters[w] = Array(w) }
        if let prev = prev {
            userPairs[prev, default: [:]][w, default: 0] += 1
            if let prev2 = prev2 { userTriples[prev2 + "\t" + prev, default: [:]][w, default: 0] += 1 }
        }
        cachedCandidates = nil
        save()
    }

    func save() {
        defaults.set(user, forKey: userKey)
        defaults.set(userPairs, forKey: pairKey)
        defaults.set(userTriples, forKey: tripleKey)
    }

    private func matchCase(_ w: String, _ prefix: String) -> String {
        if prefix.count > 1 && prefix.allSatisfy({ !$0.isLetter || $0.isUppercase }) { return w.uppercased() }
        if let f = prefix.first, f.isUppercase { return w.prefix(1).uppercased() + w.dropFirst() }
        return w
    }

    /// Bundled dictionary in frequency-rank order, independent of learned words.
    func chordWords() -> [String] { base.prefix(20_000).map { $0.text } }

    // Read-only gesture inputs; tap ranking and its caches remain unchanged.
    func gestureWords() -> [String] { base.map { $0.text } }
    func gestureUserWords() -> [String] { Array(user.keys) }
    func gestureFrequency(_ word: String) -> Double {
        tableLock.lock()
        let tables = seedTables
        tableLock.unlock()
        return frequency(word, tables)
    }
    /// The previous-word part of the suggestion score, for ranking swiped words the same way.
    func contextScore(_ prev: String?, _ word: String) -> Double {
        if let score = english?.withScores({ scores -> Double? in
            guard let scores = scores else { return nil }
            return scores.probability(word, rank[word]) + personalScore(word, prev, scores.previous2)
        }) { return score }
        tableLock.lock()
        let tables = seedTables
        tableLock.unlock()
        let pairs = prev.flatMap { rank[$0] }.flatMap { tables?.pairs[UInt64($0)] } ?? []
        let seedPair = count(pairs, rank[word])
        let userPair = prev.flatMap { userPairs[$0]?[word] } ?? 0
        return 1.5 * (log(1 + Double(seedPair)) + 1.5 * log(1 + Double(userPair)))
    }

    private func personalScore(_ word: String, _ prev: String?, _ prev2: String?) -> Double {
        let pair = prev.flatMap { userPairs[$0]?[word] } ?? 0
        let triple = prev2.flatMap { first in prev.flatMap { userTriples[first + "\t" + $0]?[word] } } ?? 0
        return personalScore(word, pair, triple)
    }

    private func personalScore(_ word: String, _ pair: Int, _ triple: Int) -> Double {
        let wordBoost = log(1 + Double(user[word] ?? 0) * 0.05 * Double((rank[word] ?? 5000) + 10))
        return wordBoost + 2.25 * log(1 + Double(pair)) + 2.7 * log(1 + Double(triple))
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
