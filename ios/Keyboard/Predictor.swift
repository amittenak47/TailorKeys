import Foundation

/// Suggestions for the two wheels, from the bundled word list plus words you type (kept on this device).
///
/// Closest (right wheel): completions and layout-aware corrections, ranked with two previous words.
/// Explore (left wheel): a weighted random draw from further down the same completions. The draw is seeded
/// by the prefix and context, so the wheel stays steady on redraw.
final class Predictor {
    private struct Word {
        let text: String
        let letters: CompactLetters
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
    private var userLetters: [String: CompactLetters] = [:]
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
    private static let persistence = DispatchQueue(label: "app.tailorkeys.learning", qos: .utility)
    private static let wordLimit = 2048, contextLimit = 256, followerLimit = 32
    private var learningDirty = false
    private var saveWork: DispatchWorkItem?

    /// Loader-only barrier: a rapid return to a language must see its last queued snapshot.
    static func waitForLearning() { persistence.sync {} }

    private struct LearningSnapshot {
        let words: [String: Int]
        let pairs: [String: [String: Int]]
        let triples: [String: [String: Int]]
        let wordKey: String, pairKey: String, tripleKey: String
        func write() {
            let store = UserDefaults.standard
            store.set(words, forKey: wordKey)
            store.set(pairs, forKey: pairKey)
            store.set(triples, forKey: tripleKey)
        }
    }

    init(_ language: String = "en", onReady: @escaping () -> Void = {}) {
        self.language = language
        let suffix = language == "en" ? "" : "_" + language
        userKey = "user_words" + suffix
        pairKey = "user_bigrams" + suffix
        tripleKey = "user_trigrams" + suffix
        var words: [Word] = []
        words.reserveCapacity(60_000)
        if let url = Bundle.main.url(forResource: "words" + suffix, withExtension: "txt") {
            ResourceLines.read(url) { line in
                let word = line.trimmingCharacters(in: .whitespaces).lowercased()
                if !word.isEmpty { words.append(Word(text: word, letters: CompactLetters(word))) }
            }
        }
        base = words
        for (i, w) in base.enumerated() where rank[w.text] == nil { rank[w.text] = i }
        if language == "en" { english = EnglishContext(base.map { $0.text }, onReady) }
        user = defaults.dictionary(forKey: userKey) as? [String: Int] ?? [:]
        userPairs = defaults.dictionary(forKey: pairKey) as? [String: [String: Int]] ?? [:]
        userTriples = defaults.dictionary(forKey: tripleKey) as? [String: [String: Int]] ?? [:]
        boundLearning()
        // Only these base words and learned words can enter the frequency top 300.
        topWords = Array(Set(base.prefix(300).map { $0.text } + Array(user.keys))).sorted {
            let a = frequency($0), b = frequency($1)
            return a == b ? $0 < $1 : a > b
        }.prefix(300).map { $0 }
        // The non-UIKit factory calls this initializer on its serial loader. Parse here so
        // an obsolete request cannot leave a second language's n-gram worker alive.
        let pairs = Self.loadCounts("bigrams" + suffix, 2, rank)
        let triples = Self.loadCounts("trigrams" + suffix, 3, rank)
        var frequencies: [Double] = []
        if language != "en" {
            frequencies = [Double](repeating: 0, count: base.count)
            for (context, row) in pairs { for entry in row {
                let count = Double(entry & 0xFFFF_FFFF)
                frequencies[Int(context)] += count
                frequencies[Int(entry >> 32)] += count
            } }
            if let maximum = frequencies.max(), maximum > 0 {
                for i in frequencies.indices { frequencies[i] /= maximum }
            }
        }
        let common = frequencies.indices.sorted {
            frequencies[$0] == frequencies[$1] ? $0 < $1 : frequencies[$0] > frequencies[$1]
        }.prefix(300).map { base[$0].text }
        seedTables = SeedTables(pairs, triples, frequencies, common)
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

    func trimMemory() {
        cachedCandidates = nil; cachedTables = nil; cachedScores = nil; cachedTaps = nil
        english?.trimMemory()
    }

    func deactivate() {
        flushLearning()
        cachedCandidates = nil; cachedTables = nil; cachedScores = nil; cachedTaps = nil
        english?.cancel()
    }

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
        guard let url = Bundle.main.url(forResource: name, withExtension: "txt") else { return [:] }
        var rows: [UInt64: [UInt64]] = [:]
        ResourceLines.read(url) { line in
            let parts = line.split(separator: " ")
            guard parts.count == n + 1, let first = rank[String(parts[0])],
                  let second = rank[String(parts[1])], let word = rank[String(parts[n - 1])],
                  let count = UInt32(parts[n]), count > 0 else { return }
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
    private func cost(_ p: [Character], _ w: CompactLetters, _ limit: Int,
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
        func add(_ w: String, _ chars: CompactLetters) {
            if w.hasPrefix("<") || w == p { return }
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
            let empty = CompactLetters("")
            for w in pool { add(w, empty) }
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

    struct Suggestions {
        let weights: [Character: Double]
        let explore: [String]
        let closest: [String]
    }

    func suggestions(_ prefix: String, _ prev: String?, _ prev2: String?, taps: ComposingTaps?, weights: Bool) -> Suggestions {
        // Rank once against one English score version. A worker publication between calls
        // must not make the wheels and key weights scan the dictionary again or disagree.
        let ranked = candidates(prefix, prev, prev2, taps)
        return Suggestions(weights: weights ? nextLetters(ranked, prefix) : [:],
                           explore: explore(ranked, prefix, prev, prev2), closest: closest(ranked, prefix))
    }

    private func nextLetters(_ ranked: [Candidate], _ prefix: String) -> [Character: Double] {
        let length = prefix.count
        var weights: [Character: Double] = [:]
        for candidate in ranked where !candidate.correction {
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

    private func closest(_ c: [Candidate], _ prefix: String, _ n: Int = 8) -> [String] {
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

    private func explore(_ c: [Candidate], _ prefix: String, _ prev: String?, _ prev2: String?, _ n: Int = 8) -> [String] {
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
    private static let symbolClosers: [Character: [String]] = [
        "(": [")", " ", ".", ","], "[": ["]", " ", ".", ","], "{": ["}", " ", ".", ","],
        "<": [">", " ", ".", ","], "\"": ["\"", " ", ".", ","], "'": ["'", " ", ".", ","],
    ]
    private static let numberSymbols = [".", ",", "%", "$", ":", "-", "/"]
    private static let defaultSymbols = [".", ",", "?", "!", "'", "\"", ":", ";"]
    private static let exploreSymbols = ["…", "->", "=>", "—", "≠", "±", "°", "×", "÷", "≈", "✓", ":)", "<3", "©", "™", "§"]
    func symbols(before: Character?) -> (explore: [String], closest: [String]) {
        let closest: [String]
        if let b = before, let close = Self.symbolClosers[b] { closest = close }
        else if let b = before, b.isNumber { closest = Self.numberSymbols }
        else { closest = Self.defaultSymbols }
        return (Self.exploreSymbols, closest)
    }

    /// Remembers a word you typed.
    func learn(_ word: String, _ prev: String? = nil, _ prev2: String? = nil) {
        let w = word.lowercased()
        guard (2...64).contains(w.count), w.utf8.count <= 64, w.allSatisfy({ $0.isLetter || $0 == "'" }) else { return }
        user[w] = min(1_000_000, (user[w] ?? 0) + 1)
        if userLetters[w] == nil { userLetters[w] = CompactLetters(w) }
        if let prev = prev, prev.utf8.count <= 64 {
            userPairs[prev, default: [:]][w] = min(1_000_000, (userPairs[prev]?[w] ?? 0) + 1)
            if let prev2 = prev2, prev2.utf8.count <= 64 {
                let key = prev2 + "\t" + prev
                userTriples[key, default: [:]][w] = min(1_000_000, (userTriples[key]?[w] ?? 0) + 1)
                Self.trimFollowers(&userTriples, key)
            }
            Self.trimFollowers(&userPairs, prev)
        }
        if user.count > Self.wordLimit || userPairs.count > Self.contextLimit || userTriples.count > Self.contextLimit { boundLearning() }
        cachedCandidates = nil
        learningDirty = true
        saveWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.flushLearning() }
        saveWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1, execute: work)
    }

    /// Called on the owner thread. Only immutable COW snapshots enter the persistence queue.
    func flushLearning(wait: Bool = false) {
        saveWork?.cancel(); saveWork = nil
        if learningDirty {
            learningDirty = false
            let snapshot = LearningSnapshot(words: user, pairs: userPairs, triples: userTriples,
                                            wordKey: userKey, pairKey: pairKey, tripleKey: tripleKey)
            Self.persistence.async { snapshot.write() }
        }
        if wait { Self.persistence.sync {} }
    }

    private static func trimFollowers(_ contexts: inout [String: [String: Int]], _ key: String) {
        guard let row = contexts[key], row.count > followerLimit,
              let least = row.min(by: { $0.value == $1.value ? $0.key > $1.key : $0.value < $1.value }) else { return }
        contexts[key]?.removeValue(forKey: least.key)
    }

    private func boundLearning() {
        func bounded(_ words: [String: Int], _ limit: Int) -> [String: Int] {
            let valid = words.filter { (1...64).contains($0.key.utf8.count) && $0.value > 0 }
            return Dictionary(uniqueKeysWithValues: valid.sorted {
                $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value
            }.prefix(limit).map { ($0.key, min(1_000_000, $0.value)) })
        }
        func contexts(_ source: [String: [String: Int]]) -> [String: [String: Int]] {
            let rows = source.filter { $0.key.utf8.count <= 129 }.mapValues { bounded($0, Self.followerLimit) }
            return Dictionary(uniqueKeysWithValues: rows.sorted {
                let a = $0.value.values.reduce(0, +), b = $1.value.values.reduce(0, +)
                return a == b ? $0.key < $1.key : a > b
            }.prefix(Self.contextLimit).map { ($0.key, $0.value) })
        }
        user = bounded(user, Self.wordLimit)
        userPairs = contexts(userPairs); userTriples = contexts(userTriples)
        userLetters.removeAll(keepingCapacity: true)
        for word in user.keys { userLetters[word] = CompactLetters(word) }
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
