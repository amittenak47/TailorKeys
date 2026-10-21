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
    var onAccent: String { Self.contrast("#000000", accent) >= Self.contrast("#FFFFFF", accent) ? "#000000" : "#FFFFFF" }
    var checks: [ThemeCheck] {
        let outline = Self.contrast(Self.composite(keyOutline, on: background), background)
        let outlinePasses = Self.contrast(key, background) < 1.2 && outline >= 3
        return [
            ThemeCheck(label: "Text on keys", ratio: Self.contrast(text, key), target: 4.5, channels: [3, 1], fixChannel: 3, background: key),
            ThemeCheck(label: "Text on function keys", ratio: Self.contrast(text, functionKey), target: 4.5, channels: [3, 2], fixChannel: 3, background: functionKey),
            ThemeCheck(label: "Enter key label", ratio: Self.contrast(onAccent, accent), target: 4.5, channels: [4], fixChannel: 4, background: onAccent),
            ThemeCheck(label: outlinePasses ? "Keys against background (outline)" : "Keys against background", ratio: outlinePasses ? outline : Self.contrast(key, background), target: outlinePasses ? 3 : 1.2, channels: [1, 0, 6], fixChannel: 1, background: background),
            ThemeCheck(label: "Pressed against keys", ratio: Self.contrast(pressed, key), target: 1.2, channels: [5, 1], fixChannel: 5, background: key)]
    }
    var keysBlend: Bool { !checks[3].passes }
    /// HSL hue in degrees, saturation and lightness; shared with Android.
    static func hsl(_ color: String) -> (h: Double, s: Double, l: Double) {
        let rgb = parse(color, alpha: true) ?? 0
        let r = Double((rgb >> 16) & 255) / 255, g = Double((rgb >> 8) & 255) / 255, b = Double(rgb & 255) / 255
        let high = max(r, max(g, b)), low = min(r, min(g, b)), delta = high - low, light = (high + low) / 2
        let hue = delta == 0 ? 0 : (high == r ? ((g - b) / delta + 6).truncatingRemainder(dividingBy: 6) : high == g ? (b - r) / delta + 2 : (r - g) / delta + 4) * 60
        return (hue, delta == 0 ? 0 : delta / (1 - abs(2 * light - 1)), light)
    }
    private static func rgb(_ hue: Double, _ saturation: Double, _ light: Double) -> String {
        let chroma = (1 - abs(2 * light - 1)) * saturation
        let x = chroma * (1 - abs((hue / 60).truncatingRemainder(dividingBy: 2) - 1)), m = light - chroma / 2
        let rgb: [Double]
        switch Int(hue / 60) {
        case 0: rgb = [chroma, x, 0]; case 1: rgb = [x, chroma, 0]; case 2: rgb = [0, chroma, x]
        case 3: rgb = [0, x, chroma]; case 4: rgb = [x, 0, chroma]; default: rgb = [chroma, 0, x]
        }
        func channel(_ i: Int) -> UInt32 { UInt32(min(255, max(0, ((rgb[i] + m) * 255).rounded()))) }
        return String(format: "#%06X", channel(0) << 16 | channel(1) << 8 | channel(2))
    }
    static func closestReadable(_ fg: String, _ bg: String, _ target: Double) -> String {
        if contrast(fg, bg) >= target { return fg }
        let source = hsl(fg)
        for step in 1...10_000 {
            for direction in [-1, 1] {
                let light = source.l + Double(direction * step) / 10_000
                if !(0...1).contains(light) { continue }
                let candidate = rgb(source.h, source.s, light), diff = abs(hsl(candidate).h - source.h)
                if contrast(candidate, bg) >= target && min(diff, 360 - diff) <= 1 { return candidate }
            }
        }
        return contrast("#000000", bg) >= contrast("#FFFFFF", bg) ? "#000000" : "#FFFFFF"
    }
    static func composite(_ outline: String, on background: String) -> String {
        let value = parse(outline, alpha: true) ?? 0, bg = parse(background) ?? 0
        let alpha = opacity(outline)
        func channel(_ shift: UInt32) -> UInt32 { UInt32(Double((value >> shift) & 255) * alpha + Double((bg >> shift) & 255) * (1 - alpha)) << shift }
        return String(format: "#%06X", channel(16) | channel(8) | channel(0))
    }
    static func opacity(_ value: String) -> Double {
        let hex = value.hasPrefix("#") ? String(value.dropFirst()) : value
        return hex.count == 8 ? Double((parse(value, alpha: true) ?? 0) >> 24) / 255 : 1
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

struct ThemeCheck {
    let label: String
    let ratio: Double
    let target: Double
    let channels: [Int]
    let fixChannel: Int
    let background: String
    var passes: Bool { ratio >= target }
    var warning: String {
        if target == 4.5 { return "Hard to read. Aim for 4.5 : 1 or more." }
        if fixChannel == 5 { return "Pressed keys look the same. Aim for 1.2 : 1 or more." }
        return "Keys blend into the background. Aim for 1.2 : 1, or a 3 : 1 outline."
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
