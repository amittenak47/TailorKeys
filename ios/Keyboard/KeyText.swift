import Foundation

enum Layer { case abc, num, sym }

/// Which layer is showing and how letters are cased. Shared by the controller and the view.
final class InputState {
    var language = "en"
    var layer: Layer = .abc
    var capsLock = false
    /// One-shot capital for the next letter, from auto-cap or a tap on ABC.
    var shift = false
    var upper: Bool { layer == .abc && (capsLock || shift) }
}

/// What each key code types and shows on each layer.
enum KeyText {
    private static let alt: [String: (String, String)] = {
        var m: [String: (String, String)] = [:]
        func map(_ src: String, _ num: String, _ sym: String) {
            let s = Array(src), n = Array(num), y = Array(sym)
            for i in s.indices { m[String(s[i])] = (String(n[i]), String(y[i])) }
        }
        map("qwertyuiop", "1234567890", "[]{}#%^*+=")
        map("asdfghjkl'", "@#$%&-+()\"", "_\\|~<>€£¥•")
        map("zxcvbnm", "!?',.:;", "?!:,.';")
        return m
    }()

    static func isLayerKey(_ k: String) -> Bool { k == Keys.abc || k == Keys.num || k == Keys.sym }
    static func layerOf(_ k: String) -> Layer { k == Keys.num ? .num : (k == Keys.sym ? .sym : .abc) }
    static func isDigit(_ k: String) -> Bool { k.count == 2 && k.hasPrefix("d") }
    static func isChar(_ k: String) -> Bool { k.count == 1 || isDigit(k) }

    /// The text a key types right now, or nil for keys that don't type.
    static func output(_ k: String, _ s: InputState) -> String? {
        if isDigit(k) { return String(k.dropFirst()) }
        if k.count != 1 { return nil }
        if s.layer != .abc, let a = alt[baseKey(k, s.language)] { return s.layer == .num ? a.0 : a.1 }
        return s.upper ? k.uppercased() : k
    }

    /// The character offered on long-press: the key's number-layer character.
    static func longPress(_ k: String, _ s: InputState) -> String? {
        k.count == 1 && s.layer == .abc ? alt[baseKey(k, s.language)]?.0 : nil
    }

    /// Small corner hint, shown only for long-press digits.
    static func hint(_ k: String, _ s: InputState) -> String? {
        guard let lp = longPress(k, s), let ch = lp.first, ch.isNumber else { return nil }
        return lp
    }

    private static func baseKey(_ k: String, _ lang: String) -> String {
        let variant: String
        switch lang {
        case "es": variant = "qwertyuiopasdfghjklñzxcvbnm"
        case "de": variant = "qwertzuiopasdfghjklßyxcvbnm"
        case "fr": variant = "azertyuiopqsdfghjklmwxcvbn'"
        default: return k
        }
        guard k.count == 1, let i = Array(variant).firstIndex(of: k.first!) else { return k }
        return String(Array("qwertyuiopasdfghjkl'zxcvbnm")[i])
    }

    static func alternatives(_ k: String, _ s: InputState, _ hints: Bool) -> [String] {
        guard s.layer == .abc, k.count == 1 else { return [] }
        let accents: [String: String]
        switch s.language {
        case "es": accents = ["a": "á", "e": "é", "i": "í", "o": "ó", "u": "úü", "n": "ñ", "ñ": "n", "?": "¿", "!": "¡"]
        case "de": accents = ["a": "ä", "o": "ö", "u": "ü", "s": "ß", "ß": "s"]
        case "fr": accents = ["a": "àâæ", "e": "éèêë", "i": "îï", "o": "ôœ", "u": "ùûü", "c": "ç", "y": "ÿ"]
        default: accents = [:]
        }
        var result: [String] = []
        if let old = longPress(k, s), let ch = old.first, (ch.isNumber && hints) || (!ch.isNumber && (s.language == "en" || accents[k] == nil)) { result.append(old) }
        for ch in accents[k] ?? "" {
            let value = s.upper ? String(ch).uppercased() : String(ch)
            if !result.contains(value) { result.append(value) }
        }
        return result
    }

    /// A spoken name for the face the key types right now.
    static func spoken(_ code: String, _ state: InputState, _ enterLabel: String?) -> String {
        switch code {
        case Keys.space: return "space"
        case Keys.backspace: return "backspace"
        case Keys.enter: return enterLabel.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 } ?? "enter"
        case Keys.abc: return "letters" + (state.capsLock ? ", caps lock on" : (state.shift ? ", shift on" : ""))
        case Keys.num: return "numbers"
        case Keys.sym: return "symbols"
        default:
            let face = output(code, state) ?? code
            switch face {
            case "'": return "apostrophe"
            case ",": return "comma"
            case ".": return "period"
            case "?": return "question mark"
            case "!": return "exclamation mark"
            case "\"": return "quotation mark"
            case ":": return "colon"
            case ";": return "semicolon"
            case "-": return "hyphen"
            case "_": return "underscore"
            case "/": return "slash"
            case "\\": return "backslash"
            case "@": return "at sign"
            case "#": return "number sign"
            case "$": return "dollar sign"
            case "%": return "percent sign"
            case "&": return "ampersand"
            case "*": return "asterisk"
            case "+": return "plus sign"
            case "=": return "equals sign"
            case "(": return "left parenthesis"
            case ")": return "right parenthesis"
            case "[": return "left bracket"
            case "]": return "right bracket"
            case "{": return "left brace"
            case "}": return "right brace"
            case "<": return "less than"
            case ">": return "greater than"
            case "|": return "vertical bar"
            case "~": return "tilde"
            case "^": return "caret"
            case "€": return "euro sign"
            case "£": return "pound sign"
            case "¥": return "yen sign"
            case "•": return "bullet"
            default: return state.upper && face.count == 1 && face.first!.isLetter ? "capital \(face)" : face
            }
        }
    }

    static func label(_ k: String, _ s: InputState) -> String {
        switch k {
        case Keys.abc: return s.capsLock ? "ABC" : (s.shift && s.layer == .abc ? "Abc" : "abc")
        case Keys.num: return "123"
        case Keys.sym: return "#+="
        case Keys.space, Keys.backspace, Keys.enter: return ""
        default: return output(k, s) ?? k
        }
    }
}
