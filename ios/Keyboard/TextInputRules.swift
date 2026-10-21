import Foundation

struct TextInputRules {
    static func canLearn(secure: Bool, autocorrectionDisabled: Bool) -> Bool {
        !secure && !autocorrectionDisabled
    }

    static func wordChar(_ ch: Character) -> Bool { ch.isLetter || ch.isNumber || ch == "'" || ch == "\u{2019}" }
    static func suffixLength(_ after: String) -> Int { after.prefix(while: wordChar).count }
    static func addSpace(_ enabled: Bool, _ after: String?, password: Bool = false) -> Bool {
        guard enabled, !password else { return false }
        let after = after ?? ""
        return after.isEmpty || after.first.map { $0.isLetter || $0.isNumber } == true
    }
    struct Replacement { let before: Int; let after: Int; let space: Bool }
    static func replacement(_ prefixLength: Int, _ after: String, automaticSpace: Bool, selected: Bool = false,
                            spacedWordLength: Int = 0) -> Replacement {
        let suffix = selected || spacedWordLength > 0 ? 0 : suffixLength(after)
        return Replacement(before: selected ? 0 : prefixLength + (spacedWordLength > 0 ? 1 : 0), after: suffix,
                           space: addSpace(automaticSpace, String(after.dropFirst(suffix))))
    }
    struct Edit { var deleteBefore = 0; var insert = ""; var move = 0 }
    static func punctuation(_ text: String, _ before: String, _ after: String, automaticSpace: Bool,
                            enabled: Bool, password: Bool = false, trailingSpace: Bool = true) -> Edit? {
        guard enabled, !password, text.count == 1, let ch = text.first else { return nil }
        if ")]}\"".contains(ch), after.first == ch { return Edit(move: 1) }
        if automaticSpace && before.last == " " {
            if ".,?!:;".contains(ch) { return Edit(deleteBefore: 1, insert: text + (trailingSpace ? " " : "")) }
            if ch == "'" { return Edit(deleteBefore: 1, insert: "'") }
        }
        let closer: Character?
        // A quote only opens at the start of a word, so 5" (inches) and mid-word quotes stay single.
        let opensQuote = before.last.map { $0.isWhitespace || "([{".contains($0) } ?? true
        switch ch { case "(": closer = ")"; case "[": closer = "]"; case "{": closer = "}"; case "\"": closer = opensQuote ? "\"" : nil; default: closer = nil }
        return closer.map { Edit(insert: text + String($0), move: -1) }
    }
    static func doubleSpace(_ enabled: Bool, _ before: String, _ elapsed: TimeInterval, password: Bool = false) -> Bool {
        enabled && !password && elapsed >= 0 && elapsed < 0.6 && before.last == " " && before.dropLast().last.map { $0.isLetter || $0.isNumber } == true
    }
    static func wordForms(_ word: String, exists: (String) -> Bool) -> [String] {
        guard !word.isEmpty, word.allSatisfy(\.isLetter) else { return [] }
        let lower = word.lowercased()
        let plural: String
        if lower.hasSuffix("y"), let prev = lower.dropLast().last, !"aeiou".contains(prev) { plural = String(lower.dropLast()) + "ies" }
        else if ["s", "x", "z", "ch", "sh"].contains(where: { lower.hasSuffix($0) }) { plural = lower + "es" }
        else { plural = lower + "s" }
        var result: [String] = []
        if exists(plural) { result.append(word.first?.isUppercase == true ? plural.prefix(1).uppercased() + plural.dropFirst() : plural) }
        result.append(word + "'s")
        return result
    }
}
