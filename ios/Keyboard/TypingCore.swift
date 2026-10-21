import Foundation

enum AutocorrectMode: Int { case off, confident, aggressive }
enum CorrectionRules {
    static func eligible(_ word: String) -> Bool {
        (3...32).contains(word.count) && word.allSatisfy { $0.isLetter || $0 == "'" } && !word.dropFirst().contains { $0.isUppercase }
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
