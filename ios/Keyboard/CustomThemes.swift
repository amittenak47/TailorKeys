import Foundation

/// Portable sRGB fills and optional ARGB outline, shared with Android.
struct ThemeColors: Codable, Equatable {
    var background: String
    var key: String
    var functionKey: String
    var text: String
    var accent: String
    var pressed: String
    var keyOutline: String = "#00000000"
    static let labels = ["Background", "Keys", "Function keys", "Text", "Accent", "Pressed", "Key outline"]
    var values: [String] { [background, key, functionKey, text, accent, pressed, keyOutline] }
    var isValid: Bool { values.prefix(6).allSatisfy { Self.parse($0) != nil } && Self.parse(keyOutline, alpha: true) != nil }
    enum CodingKeys: String, CodingKey { case background, key, functionKey, text, accent, pressed, keyOutline }
    init(background: String, key: String, functionKey: String, text: String, accent: String, pressed: String, keyOutline: String = "#00000000") {
        self.background = background; self.key = key; self.functionKey = functionKey
        self.text = text; self.accent = accent; self.pressed = pressed; self.keyOutline = keyOutline
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        background = try c.decode(String.self, forKey: .background); key = try c.decode(String.self, forKey: .key)
        functionKey = try c.decode(String.self, forKey: .functionKey); text = try c.decode(String.self, forKey: .text)
        accent = try c.decode(String.self, forKey: .accent); pressed = try c.decode(String.self, forKey: .pressed)
        keyOutline = try c.decodeIfPresent(String.self, forKey: .keyOutline) ?? "#00000000"
    }
    func replacing(_ index: Int, with value: String) -> ThemeColors {
        var v = values; v[index] = value
        return ThemeColors(background: v[0], key: v[1], functionKey: v[2], text: v[3], accent: v[4], pressed: v[5], keyOutline: v[6])
    }
    var checks: [(String, Double)] {
        [("Text on keys", Self.contrast(text, key)), ("Text on background", Self.contrast(text, background)),
         ("Text on function keys", Self.contrast(text, functionKey)), ("Accent on keys", Self.contrast(accent, key))]
    }
    var keysBlend: Bool {
        Self.contrast(key, background) < 1.5 && Self.contrast(Self.composite(keyOutline, on: background), background) < 1.5
    }
    static func composite(_ outline: String, on background: String) -> String {
        let hex = outline.hasPrefix("#") ? String(outline.dropFirst()) : outline
        let value = parse(outline, alpha: true) ?? 0, bg = parse(background) ?? 0
        let alpha = hex.count == 8 ? Double(value >> 24) / 255 : 1
        func channel(_ shift: UInt32) -> UInt32 { UInt32(Double((value >> shift) & 255) * alpha + Double((bg >> shift) & 255) * (1 - alpha)) << shift }
        return String(format: "#%06X", channel(16) | channel(8) | channel(0))
    }
    static func parse(_ value: String, alpha: Bool = false) -> UInt32? {
        let hex = value.hasPrefix("#") ? String(value.dropFirst()) : value
        guard (hex.count == 6 || alpha && hex.count == 8), hex.unicodeScalars.allSatisfy({ CharacterSet(charactersIn: "0123456789abcdefABCDEF").contains($0) }) else { return nil }
        return UInt32(hex, radix: 16)
    }
    static func contrast(_ first: String, _ second: String) -> Double {
        func luminance(_ value: String) -> Double {
            let rgb = parse(value) ?? 0
            func channel(_ shift: UInt32) -> Double {
                let c = Double((rgb >> shift) & 255) / 255
                return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        let a = luminance(first), b = luminance(second)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }
}

struct CustomTheme: Codable, Identifiable, Equatable {
    let id: String
    var name: String
    var colors: ThemeColors
    var selection: String { "custom:" + id }
    static func decode(_ json: String?) -> [CustomTheme] {
        guard let json = json, let array = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [[String: Any]] else { return [] }
        var seen = Set<String>()
        return array.compactMap { item in
            guard let data = try? JSONSerialization.data(withJSONObject: item), var theme = try? JSONDecoder().decode(CustomTheme.self, from: data),
                  !theme.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, theme.colors.isValid, seen.insert(theme.id).inserted else { return nil }
            theme.name = String(theme.name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(24))
            if theme.name.isEmpty { theme.name = "Custom" }
            return theme
        }
    }
    static func encode(_ themes: [CustomTheme]) -> String {
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(themes) else { return "[]" }
        return String(decoding: data, as: UTF8.self)
    }
}
