import Foundation

/// Portable six-colour sRGB format; the Android app writes the same JSON keys and hex values.
struct ThemeColors: Codable, Equatable {
    var background: String
    var key: String
    var functionKey: String
    var text: String
    var accent: String
    var pressed: String
    static let labels = ["Background", "Keys", "Function keys", "Text", "Accent", "Pressed"]
    var values: [String] { [background, key, functionKey, text, accent, pressed] }
    var isValid: Bool { values.allSatisfy { Self.parse($0) != nil } }
    func replacing(_ index: Int, with value: String) -> ThemeColors {
        var v = values; v[index] = value
        return ThemeColors(background: v[0], key: v[1], functionKey: v[2], text: v[3], accent: v[4], pressed: v[5])
    }
    var checks: [(String, Double)] {
        [("Text on keys", Self.contrast(text, key)), ("Text on background", Self.contrast(text, background)),
         ("Text on function keys", Self.contrast(text, functionKey)), ("Accent on keys", Self.contrast(accent, key))]
    }
    static func parse(_ value: String) -> UInt32? {
        let hex = value.hasPrefix("#") ? String(value.dropFirst()) : value
        guard hex.count == 6, hex.unicodeScalars.allSatisfy({ CharacterSet(charactersIn: "0123456789abcdefABCDEF").contains($0) }) else { return nil }
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
