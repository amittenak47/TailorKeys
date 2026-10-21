import SwiftUI

/// Colours and dimensions from LayoutLight / LayoutTablet, adapted to system text sizing.
enum LayoutStyle {
    static func color(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((value >> 16) & 255) / 255, green: CGFloat((value >> 8) & 255) / 255,
                           blue: CGFloat(value & 255) / 255, alpha: 1)
        })
    }
    static let background = color(0xFDF9F2, 0x161410)
    static let surface = color(0xEBE4D6, 0x2D281F)
    static let raised = color(0xE1D8C7, 0x3A342A)
    static let text = color(0x1D1B16, 0xEAE2D5)
    static let secondary = color(0x4D4639, 0xCFC5B6)
    static let muted = color(0x6B6354, 0xA39A8A)
    static let outline = color(0x8F8472, 0x7A7160)
    static let accent = color(0x2443A1, 0xB6C4FF)
    static let onAccent = color(0xFFFFFF, 0x06257D)
    static let button = color(0xE3D9C3, 0x4A4233)
    static let warning = color(0x5C4000, 0xF3CB74)
    static let warningBackground = color(0xFFE6B5, 0x4A3A17)
    static let error = color(0xB3261E, 0xFFB4AB)
    static let success = color(0x1E6B52, 0x8ED1B8)
    static let navigation = color(0xF0EADF, 0x231F19)
    static let navigationSelection = color(0xDCE1FF, 0x2443A1)

    static func motion(reduce: Bool, entering: Bool) -> Animation {
        reduce ? .linear(duration: 0.12) : .timingCurve(0.2, 0, 0, 1, duration: entering ? 0.36 : 0.30)
    }
}

struct LayoutButtonStyle: ButtonStyle {
    var primary = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(.subheadline, design: .default).weight(.semibold))
            .lineLimit(1).minimumScaleFactor(0.8)
            .padding(.horizontal, 16).frame(minHeight: 48)
            .foregroundColor(primary ? LayoutStyle.onAccent : LayoutStyle.accent)
            .background(Capsule().fill(primary ? LayoutStyle.accent : LayoutStyle.button))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

extension View {
    @ViewBuilder func layoutMorph(_ id: String, in namespace: Namespace.ID, enabled: Bool) -> some View {
        if enabled { matchedGeometryEffect(id: id, in: namespace) }
        else { self }
    }
}

struct SettingsNavigation: View {
    @Binding var selection: SettingsTab

    var body: some View {
        HStack(spacing: 8) {
            ForEach(SettingsTab.allCases) { tab in
                Button { selection = tab } label: {
                    VStack(spacing: 4) {
                        Image(systemName: tab.symbol).font(.system(size: 22, weight: .medium))
                            .frame(width: 64, height: 32)
                            .background(Capsule().fill(selection == tab ? LayoutStyle.navigationSelection : .clear))
                        Text(tab.title).font(.caption.weight(selection == tab ? .bold : .medium))
                    }
                    .foregroundColor(selection == tab ? LayoutStyle.text : LayoutStyle.secondary)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.title)
                .accessibilityAddTraits(selection == tab ? .isSelected : [])
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 8)
        .background(LayoutStyle.navigation.ignoresSafeArea(edges: .bottom))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Settings tabs")
    }
}

struct LayoutThumbnail: View {
    @Environment(\.colorScheme) private var scheme
    @ObservedObject var model: AppSettingsModel
    let choice: LayoutChoice

    var body: some View {
        Image(uiImage: LayoutThumbnailCache.shared.image(for: choice, settings: model.settings, dark: scheme == .dark, split: model.splitPreview(for: choice)))
            .resizable().scaledToFit().clipShape(RoundedRectangle(cornerRadius: 12))
            .accessibilityHidden(true)
    }
}
