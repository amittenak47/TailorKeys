import Foundation

enum Layer { case abc, num, sym }

/// Which layer is showing and how letters are cased. Shared by the controller and the view.
final class InputState {
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
        if s.layer != .abc, let a = alt[k] { return s.layer == .num ? a.0 : a.1 }
        return s.upper ? k.uppercased() : k
    }

    /// The character offered on long-press: the key's number-layer character.
    static func longPress(_ k: String, _ s: InputState) -> String? {
        k.count == 1 && s.layer == .abc ? alt[k]?.0 : nil
    }

    /// Small corner hint, shown only for long-press digits.
    static func hint(_ k: String, _ s: InputState) -> String? {
        guard let lp = longPress(k, s), let ch = lp.first, ch.isNumber else { return nil }
        return lp
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
