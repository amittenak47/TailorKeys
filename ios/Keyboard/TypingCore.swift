import Foundation

enum AutocorrectMode: Int { case off, confident, aggressive }
/** What each Autocorrect choice does, shown under the setting. */
enum AutocorrectDetails {
    static let modes = [
        "Never changes what you type. Suggestions still appear.",
        "Fixes clear typos: one wrong, missing or swapped letter (teh → the) and missing spaces (ofthe → of the). Real words stay as typed.",
        "Also fixes several slips onto nearby keys (watwe → water), real words that don't fit the sentence (are you form → from) and stray spaces (key board → keyboard).",
    ]
    static func text(_ mode: Int) -> String {
        let detail = modes[min(max(mode, 0), modes.count - 1)]
        return mode == 0 ? detail : detail + " Fixes happen when you press space; backspace right after undoes one."
    }
}
enum CorrectionRules {
    static func eligible(_ word: String) -> Bool {
        (1...32).contains(word.count) && word.allSatisfy { $0.isLetter || $0 == "'" } && !word.dropFirst().contains { $0.isUppercase }
    }
    static func sentenceStart(_ before: String, _ wordLength: Int) -> Bool {
        let prefix = before.dropLast(wordLength).reversed().drop(while: { " \t\"'([{".contains($0) })
        return prefix.first.map { ".!?\n\r".contains($0) } ?? true
    }
    static func transposition(_ first: String, _ second: String) -> Bool {
        let a = Array(first), b = Array(second)
        guard a.count == b.count else { return false }
        var i = 0
        while i < a.count && a[i] == b[i] { i += 1 }
        guard i + 1 < a.count, a[i] == b[i + 1], a[i + 1] == b[i] else { return false }
        i += 2
        while i < a.count { if a[i] != b[i] { return false }; i += 1 }
        return true
    }
    static func context(_ before: String, _ span: Int) -> [String] {
        var remaining = before.dropLast(span)
        var words: [String] = []
        for _ in 0..<2 {
            while let ch = remaining.last, !ch.isLetter && ch != "'" {
                if ".!?\n\r".contains(ch) { return Array(words.reversed()) }
                remaining.removeLast()
            }
            let word = String(remaining.reversed().prefix { $0.isLetter || $0 == "'" }.reversed())
            if word.isEmpty { return Array(words.reversed()) }
            words.append(word.lowercased()); remaining = remaining.dropLast(word.count)
        }
        return Array(words.reversed())
    }
    static func smallEdit(_ first: String, _ second: String) -> Bool {
        let a = Array(first), b = Array(second)
        guard abs(a.count - b.count) <= 1 else { return false }
        var previous = Array(0...b.count), older = previous, current = previous
        for i in 1...a.count {
            current[0] = i
            for j in 1...b.count {
                current[j] = min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1))
                if i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] { current[j] = min(current[j], older[j - 2] + 1) }
            }
            swap(&older, &previous); swap(&previous, &current)
        }
        return previous[b.count] <= (a.count >= 7 ? 2 : 1)
    }
}
struct WordCorrection: Equatable { let original: String, replacement: String }
struct RejectedCorrection { let language: String, original: String, fix: String }

/// Explicit undo preferences do not decay with passive vocabulary counts.
final class CorrectionHistory {
    private struct Entry { let original: String, fix: String; var undos = 0, keeps = 0 }
    private var entries: [String: Entry] = [:]
    private var undoTotals: [String: Int] = [:], keepTotals: [String: Int] = [:]
    private func key(_ original: String, _ fix: String) -> String { original.lowercased() + "\t" + fix.lowercased() }
    func blocked(_ original: String, _ fix: String) -> Bool { (entries[key(original, fix)]?.undos ?? 0) >= 2 }
    func reject(_ original: String, _ fix: String) {
        let k = key(original, fix)
        var entry = entries[k] ?? Entry(original: original.lowercased(), fix: fix)
        entry.undos = min(1_000_000, entry.undos + 1); entries[k] = entry
        undoTotals[fix.lowercased(), default: 0] += 1
    }
    func kept(_ original: String, _ fix: String) {
        let k = key(original, fix)
        var entry = entries[k] ?? Entry(original: original.lowercased(), fix: fix)
        entry.keeps = min(1_000_000, entry.keeps + 1); entries[k] = entry
        keepTotals[fix.lowercased(), default: 0] += 1
    }
    @discardableResult func typed(_ original: String) -> Bool {
        let keys = entries.keys.filter { entries[$0]?.original == original.lowercased() && entries[$0]?.undos == 1 }
        for k in keys {
            entries[k]?.undos = 2
            if let fix = entries[k]?.fix.lowercased() { undoTotals[fix, default: 0] += 1 }
        }
        return !keys.isEmpty
    }
    func penalty(_ fix: String) -> Double {
        let undos = Double(undoTotals[fix.lowercased(), default: 0]), keeps = Double(keepTotals[fix.lowercased(), default: 0])
        return min(6, log(1 + undos) * undos / (undos + keeps + 1))
    }
    func banned(_ language: String) -> [RejectedCorrection] { entries.values.filter { $0.undos >= 2 }.map { RejectedCorrection(language: language, original: $0.original, fix: $0.fix) } }
    func remove(_ original: String, _ fix: String) { entries.removeValue(forKey: key(original, fix)); rebuildTotals() }
    func forgetWord(_ word: String) {
        let keys = entries.keys.filter { key in
            guard let e = entries[key] else { return false }
            return e.original.components(separatedBy: " ").contains(word.lowercased()) || e.fix.lowercased().components(separatedBy: " ").contains(word.lowercased())
        }
        for key in keys { entries.removeValue(forKey: key) }
        rebuildTotals()
    }
    func load(_ text: String) {
        entries.removeAll()
        for line in text.components(separatedBy: "\n") {
            let p = line.components(separatedBy: "\t")
            guard p.count == 4, let undos = Int(p[2]), let keeps = Int(p[3]), undos >= 0, keeps >= 0, !p[0].isEmpty, !p[1].isEmpty else { continue }
            entries[key(p[0], p[1])] = Entry(original: p[0].lowercased(), fix: p[1], undos: min(1_000_000, undos), keeps: min(1_000_000, keeps))
        }
        rebuildTotals()
    }
    private func rebuildTotals() {
        undoTotals.removeAll(); keepTotals.removeAll()
        for e in entries.values { undoTotals[e.fix.lowercased(), default: 0] += e.undos; keepTotals[e.fix.lowercased(), default: 0] += e.keeps }
    }
    func serialize() -> String { entries.values.map { "\($0.original)\t\($0.fix)\t\($0.undos)\t\($0.keeps)\n" }.joined() }
}

struct TidyFix {
    let start: Int, end: Int
    let original: String, replacement: String
}
enum TidyText {
    static func cursor(_ cursor: Int, fixes: [TidyFix]) -> Int {
        var result = cursor
        for fix in fixes {
            if fix.end <= cursor { result += fix.replacement.count - (fix.end - fix.start) }
            else if fix.start < cursor { result += min(fix.replacement.count, cursor - fix.start) - (cursor - fix.start) }
        }
        return result
    }
    static func afterWindow(_ after: String) -> String {
        let chars = Array(after.prefix(768))
        for i in chars.indices where ".!?\n".contains(chars[i]) && (i == chars.count - 1 || chars[i + 1].isWhitespace) { return String(chars[...i]) }
        if chars.count < 768 { return String(chars) }
        var end = chars.count
        while end > 0 && (chars[end - 1].isLetter || chars[end - 1].isNumber || chars[end - 1] == "'") { end -= 1 }
        return String(chars[..<end])
    }
    static func window(_ before: String) -> String {
        let chars = Array(before)
        var start = max(0, chars.count - 768), sentences = 0
        if chars.count > 1 {
            for i in stride(from: chars.count - 2, through: start, by: -1) {
                if ".!?\n".contains(chars[i]) && (i == 0 || !".!?\n".contains(chars[i - 1])) {
                    sentences += 1
                    if sentences == 2 { return String(chars[(i + 1)...]) }
                }
            }
        }
        if start > 0 { while start < chars.count && !chars[start].isWhitespace { start += 1 } }
        return String(chars[start...])
    }
    static func propose(_ text: String, startsSentence: Bool = true, correct: (String, String) -> String?) -> [TidyFix] {
        let chars = Array(text)
        var fixes: [TidyFix] = [], i = 0, sentenceStart = startsSentence
        while i < chars.count {
            if chars[i] == " " {
                let start = i
                while i < chars.count && chars[i] == " " { i += 1 }
                if i - start > 1 { fixes.append(TidyFix(start: start, end: i, original: String(chars[start..<i]), replacement: " ")) }
            } else if chars[i].isLetter || chars[i].isNumber {
                let start = i
                while i < chars.count && (chars[i].isLetter || chars[i].isNumber || chars[i] == "'" || chars[i] == "\u{2019}") { i += 1 }
                let word = String(chars[start..<i])
                let token = String(chars[..<start].reversed().prefix { !$0.isWhitespace }.reversed()) + word + String(chars[i...].prefix { !$0.isWhitespace })
                let address = token.contains("@") || token.contains("://") || token.contains("/") || token.trimmingCharacters(in: CharacterSet(charactersIn: ".,!?;:")).contains(".")
                var replacement = address ? word : (correct(word, String(chars[..<start])) ?? word)
                if !address && sentenceStart && replacement.first?.isLowercase == true { replacement = replacement.prefix(1).uppercased() + replacement.dropFirst() }
                if replacement != word { fixes.append(TidyFix(start: start, end: i, original: word, replacement: replacement)) }
                sentenceStart = false
            } else {
                if ".!?\n".contains(chars[i]) { sentenceStart = true }
                i += 1
            }
        }
        return fixes
    }
    static func apply(_ text: String, fixes: [TidyFix]) -> String {
        var chars = Array(text)
        for fix in fixes.sorted(by: { $0.start > $1.start }) { chars.replaceSubrange(fix.start..<fix.end, with: fix.replacement) }
        return String(chars)
    }
}
