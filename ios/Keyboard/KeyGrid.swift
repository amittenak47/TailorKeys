import Foundation

enum Keys {
    static let space = "space"
    static let backspace = "bs"
    static let enter = "en"
    static let abc = "abc"
    static let num = "num"
    static let sym = "sym"
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

    func valid(_ k: GridKey) -> Bool {
        if k.c < 0 || k.c + k.w > KeyGrid.cols || k.r < 0 || k.r >= rows { return false }
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

    private struct ItemDTO: Codable { let r: Int; let c: Int; let w: Int; let k: [String]; let i: Int }
    private struct GridDTO: Codable { let rows: Int; let items: [ItemDTO] }

    func toJSON() -> String {
        let dto = GridDTO(rows: rows, items: items.map { ItemDTO(r: $0.r, c: $0.c, w: $0.w, k: $0.keys, i: $0.idx) })
        guard let data = try? JSONEncoder().encode(dto) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }

    static func fromJSON(_ s: String?) -> KeyGrid? {
        guard let s = s, let data = s.data(using: .utf8), let dto = try? JSONDecoder().decode(GridDTO.self, from: data) else { return nil }
        let items = dto.items.enumerated().map { GridKey(id: $0.offset, r: $0.element.r, c: $0.element.c, w: $0.element.w, keys: $0.element.k, idx: $0.element.i) }
        return KeyGrid(rows: dto.rows, items: items, nextId: items.count)
    }

    static func makeDefault() -> KeyGrid {
        var items: [GridKey] = []
        func add(_ r: Int, _ c: Int, _ keys: [String], _ w: Int = 1) { items.append(GridKey(id: items.count, r: r, c: c, w: w, keys: keys)) }
        for (i, ch) in "qwertyuiop".enumerated() { add(0, i < 5 ? i : i + 1, [String(ch)]) }
        for (i, ch) in "asdfghjkl'".enumerated() { add(1, i < 5 ? i : i + 1, [String(ch)]) }
        for (i, ch) in "zxcv".enumerated() { add(2, i, [String(ch)]) }
        for (i, ch) in "bnm".enumerated() { add(2, 7 + i, [String(ch)]) }
        add(2, 10, [Keys.backspace])
        add(3, 0, [Keys.abc, Keys.num, Keys.sym])
        add(3, 1, [Keys.space], 4)
        add(3, 6, [Keys.space], 4)
        add(3, 10, [Keys.enter])
        return KeyGrid(rows: 4, items: items, nextId: items.count)
    }
}
