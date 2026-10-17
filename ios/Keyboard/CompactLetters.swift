import Foundation

/// Two bytes per bundled letter instead of a 16-byte Character. Preserve arbitrary learned
/// graphemes with a fallback; dictionary matching still compares exactly the same Characters.
struct CompactLetters: RandomAccessCollection {
    typealias Index = Int
    private static let alphabet: [Character] = (0..<512).map { Character(Unicode.Scalar($0)!) }
    private let units: [UInt16]
    private let fallback: [Character]?

    init(_ word: String) {
        var packed: [UInt16] = []
        packed.reserveCapacity(word.utf8.count)
        for ch in word {
            guard ch.unicodeScalars.count == 1, let scalar = ch.unicodeScalars.first, scalar.value < 512 else {
                units = []; fallback = Array(word); return
            }
            packed.append(UInt16(scalar.value))
        }
        units = packed; fallback = nil
    }

    var startIndex: Int { 0 }
    var endIndex: Int { fallback?.count ?? units.count }
    subscript(index: Int) -> Character { fallback?[index] ?? Self.alphabet[Int(units[index])] }
}
