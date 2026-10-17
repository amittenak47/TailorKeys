import Foundation
import Accelerate

/// Standalone TKLM v1 CIFG model. One mutable State per text stream.
/// Feed <s> initially, then </s> and <s> between sentences. Caller tokenizes text.
final class NextWordModel {
    enum ModelError: Error { case invalidFormat(String) }

    let vocabulary: [String]
    let embeddingSize: Int
    let hiddenSize: Int
    var vocabularySize: Int { vocabulary.count }
    private let ids: [String: Int]
    private let embeddings: Matrix
    private let inputWeights: Matrix
    private let recurrentWeights: Matrix
    private let projection: Matrix
    private let bias: [Float]
    private let outputBias: [Float]

    final class State {
        fileprivate let owner: NextWordModel
        fileprivate var hidden: [Float]
        fileprivate var spare: [Float]
        fileprivate var cell: [Float]
        fileprivate var input: [Float]
        fileprivate var projected: [Float]
        fileprivate var dotScratch: [Float]

        fileprivate init(_ owner: NextWordModel) {
            self.owner = owner
            hidden = [Float](repeating: 0, count: owner.hiddenSize)
            spare = [Float](repeating: 0, count: owner.hiddenSize)
            cell = [Float](repeating: 0, count: owner.hiddenSize)
            input = [Float](repeating: 0, count: owner.embeddingSize)
            projected = [Float](repeating: 0, count: owner.embeddingSize)
            dotScratch = [Float](repeating: 0, count: max(owner.hiddenSize, owner.embeddingSize))
        }

        func reset() {
            for i in hidden.indices { hidden[i] = 0; spare[i] = 0; cell[i] = 0 }
            for i in input.indices { input[i] = 0; projected[i] = 0 }
        }
    }

    private init(words: [String], d: Int, h: Int, embeddings: Matrix,
                 wx: Matrix, wh: Matrix, projection: Matrix, bias: [Float], outputBias: [Float]) {
        vocabulary = words
        embeddingSize = d
        hiddenSize = h
        ids = Dictionary(uniqueKeysWithValues: words.enumerated().map { ($0.element, $0.offset) })
        self.embeddings = embeddings
        inputWeights = wx
        recurrentWeights = wh
        self.projection = projection
        self.bias = bias
        self.outputBias = outputBias
    }

    func idOf(_ word: String) -> Int {
        let normalized = word.lowercased().replacingOccurrences(of: "\u{2019}", with: "'")
            .replacingOccurrences(of: "\u{2018}", with: "'")
        return ids[normalized] ?? 2
    }

    func initialState() -> State { State(self) }

    /// Mutates and returns state; all buffers are allocated by initialState().
    @discardableResult
    func step(_ state: State, wordId: Int) -> State {
        precondition(state.owner === self && wordId >= 0 && wordId < vocabularySize)
        embeddings.row(wordId, into: &state.input)
        for j in 0..<hiddenSize {
            let f = Self.sigmoid(inputWeights.dot(j, state.input, scratch: &state.dotScratch)
                + recurrentWeights.dot(j, state.hidden, scratch: &state.dotScratch) + bias[j])
            let gRow = hiddenSize + j
            let g = Float(tanh(Double(inputWeights.dot(gRow, state.input, scratch: &state.dotScratch)
                + recurrentWeights.dot(gRow, state.hidden, scratch: &state.dotScratch) + bias[gRow])))
            let oRow = 2 * hiddenSize + j
            let o = Self.sigmoid(inputWeights.dot(oRow, state.input, scratch: &state.dotScratch)
                + recurrentWeights.dot(oRow, state.hidden, scratch: &state.dotScratch) + bias[oRow])
            let c = f * state.cell[j] + (1 - f) * g
            state.cell[j] = c
            state.spare[j] = o * Float(tanh(Double(c)))
        }
        // These assignments exchange the two uniquely owned Array buffers (no copy).
        let previous = state.hidden
        state.hidden = state.spare
        state.spare = previous
        for j in 0..<embeddingSize {
            state.projected[j] = projection.dot(j, state.hidden, scratch: &state.dotScratch)
        }
        return state
    }

    /// Natural-log probabilities for every vocabulary entry. Reuse caller's out buffer.
    func logProbs(_ state: State, out: inout [Float]) {
        precondition(state.owner === self && out.count >= vocabularySize)
        var maximum = -Float.infinity
        for i in 0..<vocabularySize {
            let score = embeddings.dot(i, state.projected, scratch: &state.dotScratch) + outputBias[i]
            out[i] = score
            maximum = max(maximum, score)
        }
        var sum = 0.0
        for i in 0..<vocabularySize { sum += exp(Double(out[i] - maximum)) }
        let normalizer = maximum + Float(log(sum))
        for i in 0..<vocabularySize { out[i] -= normalizer }
    }

    /// Unnormalized logits for only the supplied IDs; ordering matches logProbs().
    func scoreCandidates(_ state: State, candidateIds: [Int], out: inout [Float]) {
        precondition(state.owner === self && out.count >= candidateIds.count)
        for i in candidateIds.indices {
            let id = candidateIds[i]
            precondition(id >= 0 && id < vocabularySize)
            out[i] = embeddings.dot(id, state.projected, scratch: &state.dotScratch) + outputBias[id]
        }
    }

    private static func sigmoid(_ x: Float) -> Float {
        Float(1.0 / (1.0 + exp(-Double(x))))
    }

    private struct Matrix {
        let columns: Int
        let scales: [Float]
        let bytes: [Int8]

        func dot(_ row: Int, _ x: [Float], scratch: inout [Float]) -> Float {
            var result: Float = 0
            bytes.withUnsafeBufferPointer { q in
                scratch.withUnsafeMutableBufferPointer { temp in
                    // Widen signed int8 into the state's reusable buffer, then SIMD dot.
                    vDSP_vflt8(q.baseAddress!.advanced(by: row * columns), 1,
                               temp.baseAddress!, 1, vDSP_Length(columns))
                    x.withUnsafeBufferPointer { values in
                        vDSP_dotpr(temp.baseAddress!, 1, values.baseAddress!, 1,
                                   &result, vDSP_Length(columns))
                    }
                }
            }
            return result * scales[row]
        }

        func row(_ row: Int, into out: inout [Float]) {
            let scale = scales[row]
            let base = row * columns
            for j in 0..<columns { out[j] = Float(bytes[base + j]) * scale }
        }
    }

    /// Opens and closes the supplied stream. Validates all sizes before matrix allocation.
    static func load(from stream: InputStream) throws -> NextWordModel {
        stream.open()
        defer { stream.close() }
        var bytes = [UInt8]()
        var buffer = [UInt8](repeating: 0, count: 65536)
        while true {
            let n = buffer.withUnsafeMutableBufferPointer {
                stream.read($0.baseAddress!, maxLength: $0.count)
            }
            if n < 0 {
                if let error = stream.streamError { throw error }
                throw ModelError.invalidFormat("Read failed")
            }
            if n == 0 { break }
            if bytes.count + n > 5_000_000 { throw ModelError.invalidFormat("Oversized model") }
            bytes.append(contentsOf: buffer.prefix(n))
        }
        let r = Reader(bytes)
        guard try r.take(8) == [84, 75, 76, 77, 69, 78, 49, 0], try r.u32() == 1 else {
            throw ModelError.invalidFormat("Magic/version")
        }
        let v = Int(try r.u32()), d = Int(try r.u32()), h = Int(try r.u32())
        guard (5...32000).contains(v), (1...256).contains(d), (1...1024).contains(h),
              v * d + 3 * h * (d + h) + d * h <= 5_000_000 else {
            throw ModelError.invalidFormat("Dimensions")
        }
        var words = [String](), unique = Set<String>(), vocabularyBytes = 0
        for _ in 0..<v {
            let n = Int(try r.u16())
            vocabularyBytes += n + 2
            guard (1...256).contains(n), vocabularyBytes <= 1_000_000,
                  let word = String(bytes: try r.take(n), encoding: .utf8), unique.insert(word).inserted else {
                throw ModelError.invalidFormat("Vocabulary")
            }
            words.append(word)
        }
        guard Array(words.prefix(5)) == ["<s>", "</s>", "<unk>", "<num>", "<,>"] else {
            throw ModelError.invalidFormat("Special IDs")
        }
        let embeddings = try r.matrix(v, d), wx = try r.matrix(3 * h, d)
        let wh = try r.matrix(3 * h, h), projection = try r.matrix(d, h)
        let bias = try r.floats(3 * h), outputBias = try r.floats(v)
        guard r.position == bytes.count else { throw ModelError.invalidFormat("Trailing bytes") }
        return NextWordModel(words: words, d: d, h: h, embeddings: embeddings,
                             wx: wx, wh: wh, projection: projection, bias: bias, outputBias: outputBias)
    }

    private final class Reader {
        let bytes: [UInt8]
        var position = 0
        init(_ bytes: [UInt8]) { self.bytes = bytes }

        func take(_ n: Int) throws -> [UInt8] {
            guard n >= 0 && n <= bytes.count - position else { throw ModelError.invalidFormat("Truncated model") }
            defer { position += n }
            return Array(bytes[position..<(position + n)])
        }

        func u16() throws -> UInt16 {
            guard bytes.count - position >= 2 else { throw ModelError.invalidFormat("Truncated uint16") }
            defer { position += 2 }
            return UInt16(bytes[position]) | (UInt16(bytes[position + 1]) << 8)
        }

        func u32() throws -> UInt32 {
            guard bytes.count - position >= 4 else { throw ModelError.invalidFormat("Truncated uint32") }
            defer { position += 4 }
            return UInt32(bytes[position]) | (UInt32(bytes[position + 1]) << 8)
                | (UInt32(bytes[position + 2]) << 16) | (UInt32(bytes[position + 3]) << 24)
        }

        func floats(_ n: Int, positive: Bool = false) throws -> [Float] {
            var result = [Float]()
            result.reserveCapacity(n)
            for _ in 0..<n {
                let value = Float(bitPattern: try u32())
                guard value.isFinite && (!positive || value > 0) else { throw ModelError.invalidFormat("Invalid float") }
                result.append(value)
            }
            return result
        }

        func matrix(_ rows: Int, _ columns: Int) throws -> Matrix {
            let scales = try floats(rows, positive: true)
            let q = try take(rows * columns).map { Int8(bitPattern: $0) }
            return Matrix(columns: columns, scales: scales, bytes: q)
        }
    }
}
