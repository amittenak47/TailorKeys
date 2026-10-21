import Foundation
import Compression

enum Keys {
    static let space = "space"
    static let backspace = "bs"
    static let enter = "en"
    static let abc = "abc"
    static let num = "num"
    static let sym = "sym"
}

/// Starting layouts offered in the editor. `split` also turns on the split keyboard.
enum Template: CaseIterable {
    case standard, split, thumbs

    var title: String {
        switch self {
        case .standard: return "Standard"
        case .split: return "Ergonomic split"
        case .thumbs: return "Thumb keys"
        }
    }

    var split: Bool { self == .split }
}

/// One key on the grid. A key with more than one entry in `keys` is a swipe key that cycles through them.
final class GridKey {
    let id: Int
    var r: Int
    var c: Int
    var w: Int
    var keys: [String]
    var idx: Int

    init(id: Int, r: Int, c: Int, w: Int, keys: [String], idx: Int = 0) {
        self.id = id; self.r = r; self.c = c; self.w = w; self.keys = keys; self.idx = idx
    }

    var isStack: Bool { keys.count > 1 }
    var isSpace: Bool { keys.count == 1 && keys[0] == Keys.space }
}

/// Five columns on the left (0-4), a center column (5) and five on the right (6-10).
/// A key never crosses between those three regions. Spacebars stretch toward the center over empty cells.
/// Same rules as the Android `KeyGrid`.
final class KeyGrid {
    static let cols = 11
    static let center = 5

    var rows: Int
    var items: [GridKey]
    private var nextId: Int

    init(rows: Int, items: [GridKey], nextId: Int) {
        self.rows = rows; self.items = items; self.nextId = nextId
    }

    func copy() -> KeyGrid {
        KeyGrid(rows: rows, items: items.map {
            GridKey(id: $0.id, r: $0.r, c: $0.c, w: $0.w, keys: $0.keys, idx: $0.idx)
        }, nextId: nextId)
    }

    static func region(_ c: Int) -> Int { c < 5 ? 0 : (c == 5 ? 1 : 2) }

    static func bounds(_ c: Int) -> ClosedRange<Int> {
        switch region(c) {
        case 0: return 0...4
        case 1: return 5...5
        default: return 6...10
        }
    }

    func itemAt(_ r: Int, _ c: Int, skip: GridKey? = nil) -> GridKey? {
        items.first { $0 !== skip && $0.r == r && c >= $0.c && c < $0.c + $0.w }
    }

    private func occupied(_ r: Int, _ c: Int, _ skip: GridKey?) -> Bool { itemAt(r, c, skip: skip) != nil }

    func removalHint(_ key: GridKey) -> String? {
        let protected = [(Keys.abc, "abc switch"), (Keys.num, "123 switch"), (Keys.sym, "Symbol switch"),
                         (Keys.backspace, "Backspace"), (Keys.enter, "Enter"), (Keys.space, "Space")]
        return protected.first { code, _ in key.keys.contains(code) && !items.contains { $0 !== key && $0.keys.contains(code) } }
            .map { "\($0.1) can't be removed" }
    }

    @discardableResult func remove(_ key: GridKey) -> Bool {
        guard items.contains(where: { $0 === key }), removalHint(key) == nil else { return false }
        items.removeAll { $0 === key }
        compactRow(key.r, key.c)
        return true
    }

    func compactRow(_ row: Int, _ column: Int) {
        var c = Self.bounds(column).lowerBound
        for key in items.filter({ $0.r == row && Self.region($0.c) == Self.region(column) }).sorted(by: { $0.c < $1.c }) {
            key.c = c; c += key.w
        }
    }

    func rowColumns(_ row: Int, _ column: Int) -> Int {
        let start = Self.bounds(column).lowerBound
        let side = Self.region(column)
        var span = 0
        for key in items where key.r == row && Self.region(key.c) == side { span = max(span, key.c + key.w - start) }
        return span > 0 ? span : Self.bounds(column).count
    }

    func valid(_ k: GridKey) -> Bool {
        // Check each operand before adding: decoded Int values may be Int.max/Int.min.
        guard (0..<KeyGrid.cols).contains(k.c), (1...KeyGrid.cols).contains(k.w),
              k.w <= KeyGrid.cols - k.c, (0..<rows).contains(k.r) else { return false }
        if KeyGrid.region(k.c) != KeyGrid.region(k.c + k.w - 1) { return false }
        for i in 0..<k.w where occupied(k.r, k.c + i, k) { return false }
        return true
    }

    func normalize() {
        for k in items where k.isSpace {
            switch KeyGrid.region(k.c) {
            case 0: while k.c + k.w <= 4 && !occupied(k.r, k.c + k.w, k) { k.w += 1 }
            case 2: while k.c - 1 >= 6 && !occupied(k.r, k.c - 1, k) { k.c -= 1; k.w += 1 }
            default: break
            }
        }
    }

    @discardableResult
    func moveTo(_ k: GridKey, _ r: Int, _ c: Int) -> Bool {
        let r0 = k.r, c0 = k.c, w0 = k.w
        k.r = r; k.c = c; k.w = min(k.w, KeyGrid.bounds(c).upperBound - c + 1)
        while !valid(k) && k.w > 1 { k.w -= 1 }
        if valid(k) { return true }
        k.r = r0; k.c = c0; k.w = w0
        return false
    }

    /// Puts `k` on the inner side of a spacebar, which gives up one cell toward the outside.
    @discardableResult
    func insertBesideSpace(_ k: GridKey, _ space: GridKey) -> Bool {
        if space.w < 2 || KeyGrid.region(space.c) == 1 || k.isSpace { return false }
        k.r = space.r; k.w = 1
        if KeyGrid.region(space.c) == 0 { space.w -= 1; k.c = space.c + space.w } else { k.c = space.c; space.c += 1; space.w -= 1 }
        return true
    }

    /// Inserts `k` beside `target` and shifts the neighbours over by one, toward whichever side moves fewer
    /// keys. A walk stops at an empty cell or at a spacebar, which shrinks by one instead.
    @discardableResult
    func insertShift(_ k: GridKey, _ target: GridKey, before: Bool) -> Bool {
        if k.isSpace { return false }
        let r = target.r
        let range = KeyGrid.bounds(target.c)
        let b = before ? target.c : target.c + target.w

        func plan(_ dir: Int) -> (dir: Int, chain: [GridKey], absorb: GridKey?)? {
            var x = dir > 0 ? b : b - 1
            if !range.contains(x) { return nil }
            var chain: [GridKey] = []
            while range.contains(x) {
                guard let o = itemAt(r, x, skip: k) else { return (dir, chain, nil) }
                if o.isSpace && o.w > 1 { return (dir, chain, o) }
                chain.append(o)
                x = dir > 0 ? o.c + o.w : o.c - 1
            }
            return nil
        }

        let right = plan(1), left = plan(-1)
        let chosen: (dir: Int, chain: [GridKey], absorb: GridKey?)?
        if let rp = right, let lp = left { chosen = lp.chain.count < rp.chain.count ? lp : rp } else { chosen = right ?? left }
        guard let p = chosen else { return false }
        for o in p.chain { o.c += p.dir }
        if let s = p.absorb { if p.dir > 0 { s.c += 1 }; s.w -= 1 }
        k.r = r; k.w = 1; k.c = p.dir > 0 ? b : b - 1
        return true
    }

    @discardableResult
    func swap(_ a: GridKey, _ b: GridKey) -> Bool {
        let ar = a.r, ac = a.c, br = b.r, bc = b.c
        a.r = br; a.c = bc; b.r = ar; b.c = ac
        if valid(a) && valid(b) { return true }
        a.r = ar; a.c = ac; b.r = br; b.c = bc
        return false
    }

    func combine(_ k: GridKey, into target: GridKey) {
        if k === target { return }
        target.keys.append(contentsOf: k.keys)
        items.removeAll { $0 === k }
    }

    func separate(_ k: GridKey) {
        let rest = Array(k.keys.dropFirst())
        k.keys = [k.keys[0]]
        k.idx = 0
        for code in rest {
            if let spot = findEmpty(k.r, k.c) {
                items.append(GridKey(id: nextId, r: spot.0, c: spot.1, w: 1, keys: [code]))
                nextId += 1
            } else {
                k.keys.append(code)
            }
        }
    }

    private func findEmpty(_ r0: Int, _ c0: Int) -> (Int, Int)? {
        var best: (Int, Int)?
        var bestDist = Int.max
        for r in 0..<rows { for c in 0..<KeyGrid.cols where !occupied(r, c, nil) {
            let d = abs(r - r0) * 10 + abs(c - c0)
            if d < bestDist { bestDist = d; best = (r, c) }
        } }
        return best
    }

    /// Adds or removes a top row of digit keys. Removing fails while other keys sit in that row.
    @discardableResult
    func setNumberRow(_ on: Bool) -> Bool {
        if on && rows == 4 {
            for k in items { k.r += 1 }
            for (i, d) in "1234567890".enumerated() {
                items.append(GridKey(id: nextId, r: 0, c: i < 5 ? i : i + 1, w: 1, keys: ["d\(d)"]))
                nextId += 1
            }
            rows = 5
        } else if !on && rows == 5 {
            items.removeAll { $0.r == 0 && $0.keys.allSatisfy(KeyText.isDigit) }
            if items.contains(where: { $0.r == 0 }) { return false }
            for k in items { k.r -= 1 }
            rows = 4
        }
        return true
    }

    /// True when every key fits on the grid without overlapping. Imported layouts must pass this.
    var isSound: Bool {
        guard (4...5).contains(rows), (1...55).contains(items.count), items.allSatisfy({
            (0..<rows).contains($0.r) && (0..<KeyGrid.cols).contains($0.c) &&
            (1...KeyGrid.cols).contains($0.w) && $0.w <= KeyGrid.cols - $0.c &&
            (1...64).contains($0.keys.count) && $0.idx >= 0 && $0.idx < $0.keys.count &&
            $0.keys.allSatisfy { !$0.isEmpty && $0.utf8.count <= 64 }
        }) else { return false }
        return items.allSatisfy { valid($0) }
    }

    // MARK: - Sharing

    private static let codePrefix = "TK1-"
    private static let maxLayoutBytes = 65_536

    /// A short text code for sharing the layout: the JSON, raw-deflated and base64url-encoded.
    /// Android reads and writes the same codes.
    func toCode(settings: [String: Any]? = nil) -> String {
        guard var object = (try? JSONSerialization.jsonObject(with: Data(toJSON().utf8))) as? [String: Any] else { return "" }
        object["settings"] = settings
        guard let raw = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let packed = try? (raw as NSData).compressed(using: .zlib) as Data else { return "" }
        let b64 = packed.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        return KeyGrid.codePrefix + b64
    }

    /// Finds a layout code anywhere in `text`, so a whole pasted message works. Nil if there's none or it's broken.
    static func fromCode(_ text: String) -> KeyGrid? {
        decodeCode(text)?.grid
    }

    /// Optional names travel in the bounded layout object; decoding happens only once.
    static func decodeCode(_ text: String) -> (grid: KeyGrid, name: String?, settings: [String: Any]?)? {
        guard text.utf8.count <= maxLayoutBytes * 2 else { return nil }
        guard let start = text.range(of: codePrefix) else { return nil }
        let encoded = text[start.upperBound...].utf8.prefix(maxLayoutBytes * 2 + 1).prefix(while: {
            (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95
        })
        guard !encoded.isEmpty, encoded.count <= maxLayoutBytes * 2 else { return nil }
        var b64 = String(decoding: encoded, as: UTF8.self)
            .replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let packed = Data(base64Encoded: b64),
              packed.count <= maxLayoutBytes, let raw = inflate(packed),
              let json = String(data: raw, encoding: .utf8), let g = fromJSON(json) else { return nil }
        let object = (try? JSONSerialization.jsonObject(with: raw)) as? [String: Any]
        let name = (object?["name"] as? String).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        return (g, name.flatMap { $0.isEmpty ? nil : String($0.prefix(24)) }, object?["settings"] as? [String: Any])
    }

    /// Raw DEFLATE, like Android. END is required; exhausted input alone is not success.
    private static func inflate(_ packed: Data) -> Data? {
        var buffer = [UInt8](repeating: 0, count: 4096)
        return packed.withUnsafeBytes { source -> Data? in
            guard let base = source.bindMemory(to: UInt8.self).baseAddress else { return nil }
            var output = Data()
            return buffer.withUnsafeMutableBufferPointer { target -> Data? in
                var stream = compression_stream(dst_ptr: target.baseAddress!, dst_size: target.count,
                                                src_ptr: base, src_size: packed.count, state: nil)
                guard compression_stream_init(&stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) != COMPRESSION_STATUS_ERROR else { return nil }
                defer { compression_stream_destroy(&stream) }
                stream.src_ptr = base; stream.src_size = packed.count
                while true {
                    stream.dst_ptr = target.baseAddress!; stream.dst_size = target.count
                    let remaining = stream.src_size
                    let status = compression_stream_process(&stream, Int32(COMPRESSION_STREAM_FINALIZE.rawValue))
                    let written = target.count - stream.dst_size
                    guard written <= maxLayoutBytes - output.count else { return nil }
                    output.append(target.baseAddress!, count: written)
                    if status == COMPRESSION_STATUS_END { return stream.src_size == 0 ? output : nil }
                    if status == COMPRESSION_STATUS_ERROR || (written == 0 && stream.src_size == remaining) { return nil }
                }
            }
        }
    }

    private func mapLetters(_ lang: String) {
        let from = Array("qwertyuiopasdfghjkl'zxcvbnm")
        let to: [Character]
        switch lang {
        case "es": to = Array("qwertyuiopasdfghjklñzxcvbnm")
        case "de": to = Array("qwertzuiopasdfghjklßyxcvbnm")
        case "fr": to = Array("azertyuiopqsdfghjklmwxcvbn'")
        default: return
        }
        for key in items {
            key.keys = key.keys.map { code in
                guard code.count == 1, let i = from.firstIndex(of: code.first!) else { return code }
                return String(to[i])
            }
        }
    }

    /// Only recognized presets follow the active language in memory. Custom grids stay custom.
    func languageVariant(_ lang: String) -> KeyGrid {
        for t in Template.allCases { for source in ["en", "es", "de", "fr"] {
            let candidate = KeyGrid.template(t, source)
            if rows == 5 { candidate.setNumberRow(true) }
            if rows == candidate.rows && items.count == candidate.items.count && zip(items, candidate.items).allSatisfy({ a, b in
                a.r == b.r && a.c == b.c && a.w == b.w && a.keys == b.keys
            }) {
                let result = KeyGrid.template(t, lang)
                if rows == 5 { result.setNumberRow(true) }
                for (a, b) in zip(result.items, items) { a.idx = b.idx }
                return result
            }
        } }
        return self
    }

    // MARK: - Templates

    /// Starting layouts. Standard is the default; the others put B on the left hand like ergonomic keyboards.
    static func template(_ t: Template, _ lang: String = "en") -> KeyGrid {
        if t == .standard { return makeDefault(lang) }
        var items: [GridKey] = []
        func add(_ r: Int, _ c: Int, _ keys: [String], _ w: Int = 1) { items.append(GridKey(id: items.count, r: r, c: c, w: w, keys: keys)) }
        func row(_ r: Int, _ c0: Int, _ s: String) { for (i, ch) in s.enumerated() { add(r, c0 + i, [String(ch)]) } }
        row(0, 0, "qwert"); row(0, 6, "yuiop")
        row(1, 0, "asdfg"); row(1, 6, "hjkl'")
        row(2, 0, "zxcvb"); row(2, 6, "nm,.")
        add(3, 0, [Keys.abc, Keys.num, Keys.sym])
        if t == .split {
            add(2, 10, [Keys.backspace])
            add(3, 1, [Keys.space], 4)
            add(3, 6, [Keys.space], 4)
            add(3, 10, [Keys.enter])
        } else {
            // Backspace and enter sit at the inner ends of the spacebars, under the thumbs.
            add(2, 10, ["?"])
            add(3, 1, [Keys.space], 3)
            add(3, 4, [Keys.backspace])
            add(3, 6, [Keys.enter])
            add(3, 7, [Keys.space], 4)
        }
        let grid = KeyGrid(rows: 4, items: items, nextId: items.count)
        grid.mapLetters(lang)
        return grid
    }

    private struct ItemDTO: Codable { let r: Int; let c: Int; let w: Int; let k: [String]; let i: Int }
    private struct GridDTO: Codable { let rows: Int; let items: [ItemDTO] }

    func toJSON() -> String {
        let dto = GridDTO(rows: rows, items: items.map { ItemDTO(r: $0.r, c: $0.c, w: $0.w, k: $0.keys, i: $0.idx) })
        guard let data = try? JSONEncoder().encode(dto) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    static func fromJSON(_ s: String?) -> KeyGrid? {
        guard let s = s, s.utf8.count <= maxLayoutBytes, let data = s.data(using: .utf8),
              let dto = try? JSONDecoder().decode(GridDTO.self, from: data),
              (4...5).contains(dto.rows), (1...55).contains(dto.items.count) else { return nil }
        // Validate geometry before itemAt/valid can inspect another item's arithmetic.
        guard dto.items.allSatisfy({ item in
            (0..<dto.rows).contains(item.r) && (0..<cols).contains(item.c) &&
            (1...cols).contains(item.w) && item.w <= cols - item.c &&
            (1...64).contains(item.k.count) && item.i >= 0 && item.i < item.k.count &&
            item.k.allSatisfy { !$0.isEmpty && $0.utf8.count <= 64 }
        }) else { return nil }
        let items = dto.items.enumerated().map { GridKey(id: $0.offset, r: $0.element.r, c: $0.element.c, w: $0.element.w, keys: $0.element.k, idx: $0.element.i) }
        let grid = KeyGrid(rows: dto.rows, items: items, nextId: items.count)
        return grid.isSound ? grid : nil
    }

    static func makeDefault(_ lang: String = "en") -> KeyGrid {
        var items: [GridKey] = []
        func add(_ r: Int, _ c: Int, _ keys: [String], _ w: Int = 1) { items.append(GridKey(id: items.count, r: r, c: c, w: w, keys: keys)) }
        for (i, ch) in "qwertyuiop".enumerated() { add(0, i < 5 ? i : i + 1, [String(ch)]) }
        for (i, ch) in "asdfghjkl'".enumerated() { add(1, i < 5 ? i : i + 1, [String(ch)]) }
        // iPhone arrangement: shift (abc) starts the bottom letter row, a wide backspace ends it,
        // and 123 (swipe for #+=) sits bottom left.
        add(2, 0, [Keys.abc])
        for (i, ch) in "zxcv".enumerated() { add(2, 1 + i, [String(ch)]) }
        for (i, ch) in "bnm".enumerated() { add(2, 6 + i, [String(ch)]) }
        add(2, 9, [Keys.backspace], 2)
        add(3, 0, [Keys.num, Keys.sym])
        add(3, 1, [Keys.space], 4)
        add(3, 6, [Keys.space], 4)
        add(3, 10, [Keys.enter])
        let grid = KeyGrid(rows: 4, items: items, nextId: items.count)
        grid.mapLetters(lang)
        return grid
    }
}
