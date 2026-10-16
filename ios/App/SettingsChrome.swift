import SwiftUI

enum AppStyle {
    static let accent = Color(red: 39 / 255, green: 83 / 255, blue: 224 / 255)
    static let background = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark ? .systemGroupedBackground
            : UIColor(red: 244 / 255, green: 242 / 255, blue: 238 / 255, alpha: 1)
    })

    static func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
}

enum SettingsTab: Int, CaseIterable, Identifiable {
    case layout, size, look, typing
    var id: Int { rawValue }
    var title: String {
        switch self {
        case .layout: return "Layout"
        case .size: return "Size"
        case .look: return "Look"
        case .typing: return "Typing"
        }
    }
    var symbol: String {
        switch self {
        case .layout: return "square.grid.2x2"
        case .size: return "arrow.up.right.and.arrow.down.left"
        case .look: return "paintpalette"
        case .typing: return "textformat"
        }
    }
}

/// Coordinates are taken from the approved 32-point mark.
struct KeycapMark: Shape {
    func path(in rect: CGRect) -> Path {
        Path(roundedRect: CGRect(x: rect.minX + rect.width / 32, y: rect.minY + rect.height / 32,
                                width: rect.width * 30 / 32, height: rect.height * 30 / 32),
             cornerRadius: rect.width * 9 / 32)
    }
}

struct KeycapSeam: Shape {
    func path(in rect: CGRect) -> Path {
        Path(roundedRect: CGRect(x: rect.minX + rect.width * 7.5 / 32, y: rect.minY + rect.height * 7 / 32,
                                width: rect.width * 17 / 32, height: rect.height * 14 / 32),
             cornerRadius: rect.width * 3.5 / 32)
    }
}

struct LogoMark: View {
    var body: some View {
        ZStack(alignment: .top) {
            KeycapMark().fill(AppStyle.accent)
            RoundedRectangle(cornerRadius: 6).fill(Color.white.opacity(0.16))
                .frame(width: 22 * 34 / 32, height: 19 * 34 / 32)
                .offset(y: 4.5 * 34 / 32)
            KeycapSeam().stroke(.white, style: StrokeStyle(lineWidth: 1.6, lineCap: .round, dash: [2.6, 2.4]))
        }
        .frame(width: 34, height: 34)
        .accessibilityHidden(true)
    }
}

struct AppHeader: View {
    @ObservedObject var model: AppSettingsModel

    var body: some View {
        HStack(spacing: 10) {
            LogoMark()
            Text("TailorKeys").font(.system(size: 30, weight: .bold)).tracking(0.3)
                .lineLimit(1).fixedSize(horizontal: true, vertical: false)
            Spacer(minLength: 0)
            setupPill
        }
        .padding(.leading, 16)
        .padding(.trailing, model.keyboardAdded == true && !model.fullAccessConfirmed && !model.fullAccessDismissed ? 8 : 16)
        .frame(height: 52)
    }

    @ViewBuilder private var setupPill: some View {
        if model.keyboardAdded != true {
            Button(action: AppStyle.openSettings) {
                Text("Add keyboard").font(.system(size: 14, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
                    .padding(.horizontal, 14).frame(height: 34)
                    .foregroundColor(.white).background(Capsule().fill(AppStyle.accent))
                    .overlay(alignment: .topTrailing) {
                        Circle().fill(Color(red: 1, green: 176 / 255, blue: 32 / 255))
                            .overlay(Circle().stroke(AppStyle.background, lineWidth: 2))
                            .frame(width: 12, height: 12).offset(x: 4, y: -4)
                    }
            }
            .frame(minHeight: 44)
            .accessibilityLabel("TailorKeys isn't added yet. Add keyboard")
        } else if model.showReady {
            Label("Ready", systemImage: "checkmark")
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(Color(red: 27 / 255, green: 94 / 255, blue: 48 / 255))
                .padding(.horizontal, 12).frame(height: 34)
                .background(Capsule().fill(Color(red: 221 / 255, green: 239 / 255, blue: 224 / 255)))
                .transition(.opacity)
        } else if !model.fullAccessConfirmed && !model.fullAccessDismissed {
            HStack(spacing: -6) {
                Button { model.sheet = .fullAccess } label: {
                    Text("Allow Full Access").font(.system(size: 14, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.75)
                        .padding(.horizontal, 12).frame(height: 34)
                        .overlay(Capsule().stroke(AppStyle.accent, lineWidth: 1.5))
                }
                .frame(minHeight: 44)
                Button(action: model.dismissFullAccess) {
                    Image(systemName: "xmark").font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.secondary).frame(width: 44, height: 44)
                }
                .accessibilityLabel("Dismiss")
            }
            .layoutPriority(-1)
        }
    }
}

struct SettingSlider: View {
    let title: String
    let pref: IntPref
    @Binding var value: Double
    var suffix = ""
    var signed = false
    var offAtZero = false
    var onEditingChanged: (Bool) -> Void = { _ in }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title)
                Spacer()
                Text(offAtZero && value == 0 ? "Off" : (signed && value > 0 ? "+" : "") + "\(Int(value))" + suffix)
                    .font(.system(size: 15)).foregroundColor(.secondary).monospacedDigit()
            }
            Slider(value: $value, in: Double(pref.min)...Double(pref.max), step: 1,
                   onEditingChanged: onEditingChanged)
                .accessibilityLabel(title)
                .accessibilityValue(offAtZero && value == 0 ? "Off" : "\(Int(value))" + suffix)
        }
        .padding(.top, 8)
    }
}

struct MenuRow<Content: View>: View {
    let title: String
    let value: String
    let content: () -> Content

    init(title: String, value: String, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.value = value
        self.content = content
    }

    var body: some View {
        Menu(content: content) {
            HStack(spacing: 8) {
                Text(title).foregroundColor(.primary)
                Spacer()
                Text(value)
                Image(systemName: "chevron.up.chevron.down").font(.system(size: 11, weight: .semibold))
            }
            .frame(minHeight: 32).contentShape(Rectangle())
        }
        .accessibilityLabel("\(title), \(value)")
    }
}
