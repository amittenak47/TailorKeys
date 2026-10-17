import Foundation

/// Accessed only on worker: one weight set (or remembered failure) per extension process.
enum EnglishModel {
    static let worker = DispatchQueue(label: "app.tailorkeys.english", qos: .userInitiated)
    private static var attempted = false
    private static var model: NextWordModel?
    static func load() -> NextWordModel? {
        if !attempted {
            attempted = true
            if let url = Bundle.main.url(forResource: "lm_en", withExtension: "bin"),
               let stream = InputStream(url: url) { model = try? NextWordModel.load(from: stream) }
        }
        return model
    }
}

/// Same tokens and sentence framing as tools/lm/prepare.py and Android EnglishTokens.
enum EnglishTokens {
    private static let token = try! NSRegularExpression(pattern: "[\\p{L}\\p{Nl}\\p{No}]+(?:'[\\p{L}\\p{Nl}\\p{No}]+)*|\\p{Nd}+(?:[.,]\\p{Nd}+)*|[,.!?]")
    // Python str.isdigit(): decimal digits plus Numeric_Type=Digit (not all numeric letters).
    private static let digits: [ClosedRange<UInt32>] = [0xb2...0xb3, 0xb9...0xb9, 0x1369...0x1371,
        0x19da...0x19da, 0x2070...0x2070, 0x2074...0x2079, 0x2080...0x2089, 0x2460...0x2468,
        0x2474...0x247c, 0x2488...0x2490, 0x24ea...0x24ea, 0x24f5...0x24fd, 0x24ff...0x24ff,
        0x2776...0x277e, 0x2780...0x2788, 0x278a...0x2792, 0x10a40...0x10a43, 0x10e60...0x10e68,
        0x11052...0x1105a, 0x1f100...0x1f10a]
    static func context(_ text: String) -> [String] {
        let text = text.lowercased().replacingOccurrences(of: "\u{2019}", with: "'")
            .replacingOccurrences(of: "\u{2018}", with: "'")
        var sentences: [[String]] = [], current: [String] = []
        token.enumerateMatches(in: text, range: NSRange(location: 0, length: text.utf16.count)) { match, _, _ in
            guard let match = match, let range = Range(match.range, in: text) else { return }
            let t = String(text[range])
            if t == "." || t == "!" || t == "?" {
                if !current.isEmpty { sentences.append(current); current = [] }
            } else {
                let cp = t.unicodeScalars.first!
                let digit = cp.properties.generalCategory == .decimalNumber || digits.contains { $0.contains(cp.value) }
                current.append(digit ? "<num>" : (t == "," ? "<,>" : t))
            }
        }
        var result: [String] = []
        for sentence in sentences.suffix(2) { result.append("<s>"); result += sentence; result.append("</s>") }
        result.append("<s>"); result += current
        return result
    }
}

/// Coalesced context requests. State is worker-owned; two reusable score buffers are swapped
/// under lock. Readers hold the lock for scoring, never for recurrence or the full softmax.
final class EnglishContext {
    final class Scores {
        let model: NextWordModel
        let ids: [Int]
        let oovMass: Double
        var probabilities: [Float]
        var top: [Int]
        var version: UInt64 = 0
        var previous2: String?
        init(_ model: NextWordModel, _ ids: [Int], _ mass: Double) {
            self.model = model; self.ids = ids; oovMass = mass
            probabilities = [Float](repeating: 0, count: model.vocabularySize)
            top = [Int](repeating: -1, count: min(300, model.vocabularySize - 5))
        }
        func probability(_ word: String, _ rank: Int?) -> Double {
            let id = rank.map { ids[$0] } ?? model.normalizedIdOf(word)
            if id != 2 { return Double(probabilities[id]) }
            // <unk> also covers words absent from the keyboard dictionary. Reserve their mass.
            return Double(probabilities[2]) + log(0.1 * (1.0 / Double((rank ?? 5000) + 10)) / oovMass)
        }
    }
    private let words: [String]
    private let onReady: () -> Void
    private let lock = NSLock()
    private var requested: String?
    private var generation: UInt64 = 0, epoch: UInt64 = 0
    private var running = false, failed = false
    private var published: Scores?
    // Worker-owned fields.
    private var model: NextWordModel?
    private var state: NextWordModel.State?
    private var tokens: [String] = []
    private var workerEpoch: UInt64?
    private var front: Scores?, back: Scores?
    private var computed: Scores?

    init(_ words: [String], _ onReady: @escaping () -> Void) { self.words = words; self.onReady = onReady }

    func reset() {
        lock.lock(); defer { lock.unlock() }
        requested = nil; generation &+= 1; epoch &+= 1; published = nil
    }

    func update(_ before: String, _ prefix: String) {
        let committed = before.dropLast(prefix.count)
        lock.lock()
        if failed || requested.map({ $0[...] == committed }) == true { lock.unlock(); return }
        requested = String(committed); generation &+= 1; published = nil
        let schedule = !running
        running = true
        lock.unlock()
        if schedule { EnglishModel.worker.async { [weak self] in self?.drain() } }
    }

    func withScores<T>(_ read: (Scores?) -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        return read(published)
    }

    private func drain() {
        if model == nil, let m = EnglishModel.load() {
            model = m; state = m.initialState()
            let ids = words.map { m.normalizedIdOf($0) }
            var mass = 0.0
            for i in ids.indices where ids[i] == 2 { mass += 1.0 / Double(i + 10) }
            front = Scores(m, ids, max(mass, 1e-12)); back = Scores(m, ids, max(mass, 1e-12))
        }
        guard let m = model, let state = state else {
            lock.lock(); failed = true; running = false; lock.unlock(); return
        }
        while true {
            lock.lock()
            let text = requested, version = generation, resetEpoch = epoch
            lock.unlock()
            let next = EnglishTokens.context(text ?? "")
            let unchanged = workerEpoch == resetEpoch && next == tokens && computed != nil
            var start = tokens.count
            if workerEpoch != resetEpoch || start > next.count || !zip(tokens, next).allSatisfy({ $0.0 == $0.1 }) {
                state.reset(); start = 0
            }
            workerEpoch = resetEpoch
            if start < next.count { for i in start..<next.count { m.step(state, wordId: m.idOf(next[i])) } }
            tokens = next
            let output = unchanged ? computed! : back!
            output.version = version
            let sentenceStart = (next.lastIndex(of: "<s>") ?? -1) + 1
            output.previous2 = next.dropFirst(sentenceStart).filter { !$0.hasPrefix("<") }.dropLast().last
            if !unchanged {
                m.logProbs(state, out: &output.probabilities)
                fillTop(output)
                computed = output
            }
            lock.lock()
            if version == generation {
                if front !== output { let spare = front; front = output; back = spare }
                published = text == nil ? nil : output
                running = false
                lock.unlock()
                if text != nil { onReady() }
                return
            }
            lock.unlock()
        }
    }

    private func fillTop(_ output: Scores) {
        let m = output.model
        for i in output.top.indices { output.top[i] = -1 }
        for id in 5..<m.vocabularySize {
            var at = output.top.count - 1
            let last = output.top[at]
            if last >= 0 && output.probabilities[id] <= output.probabilities[last] { continue }
            while at > 0 {
                let previous = output.top[at - 1]
                if previous >= 0 && output.probabilities[previous] >= output.probabilities[id] { break }
                output.top[at] = previous; at -= 1
            }
            output.top[at] = id
        }
    }
}
