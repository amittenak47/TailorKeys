import UIKit

enum ThemeName: String, CaseIterable {
    case system, light, dark, midnight, warm

    var title: String {
        switch self {
        case .system: return "Match system"
        case .light: return "Light"
        case .dark: return "Dark"
        case .midnight: return "Midnight"
        case .warm: return "Warm"
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

    static func of(_ name: ThemeName, dark: Bool) -> Palette {
        let resolved: ThemeName = name == .system ? (dark ? .dark : .light) : name
        switch resolved {
        case .dark: return Palette(hex(0x1B1C1F), hex(0x3A3B40), hex(0x2A2B30), hex(0x5A5C63), hex(0xF1F1F3), hex(0x9A9BA2), hex(0x5B8DEF), hex(0xFFFFFF))
        case .midnight: return Palette(hex(0x000000), hex(0x17181B), hex(0x0E0F11), hex(0x34363C), hex(0xEDEDEF), hex(0x7C7E86), hex(0x7AA2FF), hex(0x000000))
        case .warm: return Palette(hex(0xE9E2D6), hex(0xFBF8F2), hex(0xD3C9B8), hex(0xC7BBA6), hex(0x2B2620), hex(0x7A6F5F), hex(0xC2562B), hex(0xFFFFFF))
        default: return Palette(hex(0xE4E6EB), hex(0xFFFFFF), hex(0xC9CCD3), hex(0xB0B4BD), hex(0x1B1C1F), hex(0x6B6E76), hex(0x2F6BDB), hex(0xFFFFFF))
        }
    }

    private init(_ bg: UIColor, _ key: UIColor, _ fn: UIColor, _ pressed: UIColor, _ text: UIColor, _ hint: UIColor, _ accent: UIColor, _ on: UIColor) {
        background = bg; self.key = key; functionKey = fn; self.pressed = pressed; self.text = text; self.hint = hint; self.accent = accent; onAccent = on
    }

    private static func hex(_ v: UInt32) -> UIColor {
        UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }
}
