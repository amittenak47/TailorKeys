import UIKit

enum ThemeName: String, CaseIterable {
    case system, light, dark, midnight, warm, highContrast

    var title: String {
        switch self {
        case .system: return "Match system"
        case .light: return "Light"
        case .dark: return "Dark"
        case .midnight: return "Midnight"
        case .warm: return "Warm"
        case .highContrast: return "High contrast"
        }
    }
}

struct Palette {
    let background: UIColor
    let key: UIColor
    let functionKey: UIColor
    let pressed: UIColor
    let text: UIColor
    let hint: UIColor
    let accent: UIColor
    let onAccent: UIColor

    var smallAccent: UIColor {
        if accent.isEqual(Self.hex(0xC2562B)) { return Self.hex(0x9C4A28) }
        if accent.isEqual(Self.hex(0x5B8DEF)) { return Self.hex(0x8DB1FF) }
        return accent
    }

    static func of(_ settings: KeyboardSettings, dark: Bool) -> Palette {
        if settings.themeID.hasPrefix("custom:"), let custom = settings.customThemes.first(where: { $0.selection == settings.themeID }) {
            return custom.colors.palette
        }
        return of(settings.theme, dark: dark)
    }

    static func of(_ name: ThemeName, dark: Bool) -> Palette {
        let resolved: ThemeName = name == .system ? (dark ? .dark : .light) : name
        switch resolved {
        case .highContrast: return Palette(hex(0x000000), hex(0x000000), hex(0x000000), hex(0x000000), hex(0xFFFFFF), hex(0xFFFFFF), hex(0xFFD400), hex(0x000000))
        case .dark: return Palette(hex(0x1B1C1F), hex(0x3A3B40), hex(0x2A2B30), hex(0x5A5C63), hex(0xF1F1F3), hex(0x9A9BA2), hex(0x5B8DEF), hex(0xFFFFFF))
        case .midnight: return Palette(hex(0x000000), hex(0x17181B), hex(0x0E0F11), hex(0x34363C), hex(0xEDEDEF), hex(0x7C7E86), hex(0x7AA2FF), hex(0x000000))
        case .warm: return Palette(hex(0xE9E2D6), hex(0xFBF8F2), hex(0xD3C9B8), hex(0xC7BBA6), hex(0x2B2620), hex(0x7A6F5F), hex(0xC2562B), hex(0xFFFFFF))
        default: return Palette(hex(0xE4E6EB), hex(0xFFFFFF), hex(0xC9CCD3), hex(0xB0B4BD), hex(0x1B1C1F), hex(0x6B6E76), hex(0x2F6BDB), hex(0xFFFFFF))
        }
    }

    init(_ bg: UIColor, _ key: UIColor, _ fn: UIColor, _ pressed: UIColor, _ text: UIColor, _ hint: UIColor, _ accent: UIColor, _ on: UIColor) {
        background = bg; self.key = key; functionKey = fn; self.pressed = pressed; self.text = text; self.hint = hint; self.accent = accent; onAccent = on
    }

    static func hex(_ v: UInt32) -> UIColor {
        UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }
}


extension ThemeColors {
    init(_ palette: Palette) {
        func hex(_ color: UIColor) -> String {
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            color.getRed(&r, green: &g, blue: &b, alpha: &a)
            return String(format: "#%02X%02X%02X", Int((r * 255).rounded()), Int((g * 255).rounded()), Int((b * 255).rounded()))
        }
        background = hex(palette.background); key = hex(palette.key); functionKey = hex(palette.functionKey)
        text = hex(palette.text); accent = hex(palette.accent); pressed = hex(palette.pressed)
    }
    static func uiColor(_ value: String) -> UIColor { Palette.hex(parse(value) ?? 0) }
    var palette: Palette {
        let ink = Self.uiColor(text), accentColor = Self.uiColor(accent)
        let onAccent = Self.contrast("#000000", accent) >= Self.contrast("#FFFFFF", accent) ? UIColor.black : UIColor.white
        return Palette(Self.uiColor(background), Self.uiColor(key), Self.uiColor(functionKey), Self.uiColor(pressed), ink, ink, accentColor, onAccent)
    }
}
