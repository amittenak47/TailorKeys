import SwiftUI

struct CustomThemeSection: View {
    @ObservedObject var model: AppSettingsModel
    @Environment(\.colorScheme) private var scheme
    @State private var editing: CustomTheme?
    @State private var deleting = false
    private var prefs: KeyboardSettings { model.settings }
    private var selected: CustomTheme? { prefs.customThemes.first { $0.selection == prefs.themeID } }
    private let columns = [GridItem(.adaptive(minimum: 104), spacing: 8)]

    var body: some View {
        Section {
            HStack { Text("Theme"); Spacer(); Text(selected?.name ?? prefs.theme.title).foregroundColor(.secondary) }
            HStack(spacing: 4) {
                ForEach(ThemeName.allCases, id: \.self) { theme in
                    swatch(theme == .system ? "System" : theme.title, palette: Palette.of(theme, dark: scheme == .dark),
                           selected: !prefs.themeID.hasPrefix("custom:") && prefs.theme == theme) {
                        prefs.theme = theme; deleting = false; model.changed()
                    }
                }
            }.listRowSeparator(.hidden)
            HStack { Text("Your themes").foregroundColor(AppStyle.accent); Spacer(); Text("\(prefs.customThemes.count) saved").foregroundColor(.secondary) }.font(.caption)
            LazyVGrid(columns: columns, spacing: 8) {
                ForEach(prefs.customThemes) { theme in
                    swatch(theme.name, palette: theme.colors.palette, selected: prefs.themeID == theme.selection) {
                        prefs.themeID = theme.selection; deleting = false; model.changed()
                    }
                }
                Button { editing = CustomTheme(id: UUID().uuidString, name: "", colors: ThemeColors(Palette.of(prefs, dark: scheme == .dark))) } label: {
                    VStack { Image(systemName: "plus").frame(width: 44, height: 44).overlay(RoundedRectangle(cornerRadius: 14).stroke(style: StrokeStyle(lineWidth: 1, dash: [3]))); Text("Create theme").font(.caption).lineLimit(1).fixedSize(horizontal: true, vertical: false) }
                }.buttonStyle(.plain).foregroundColor(AppStyle.accent).frame(minHeight: 76)
            }.listRowSeparator(.hidden)
            if let selected = selected {
                HStack {
                    VStack(alignment: .leading) { Text(selected.name).bold(); Text("Your theme").font(.caption).foregroundColor(.secondary) }
                    Spacer()
                    Button("Edit") { editing = selected }.buttonStyle(.bordered)
                    Button("Delete", role: .destructive) { deleting = true }.buttonStyle(.bordered)
                }
                if deleting {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Delete “\(selected.name)”?").font(.headline)
                        Text("This theme is removed from this device. The keyboard switches back to Warm.").font(.subheadline)
                        HStack { Spacer(); Button("Cancel") { deleting = false }; Button("Delete", role: .destructive) { prefs.deleteTheme(selected.id); deleting = false; model.changed() }.buttonStyle(.borderedProminent) }
                    }.padding().background(RoundedRectangle(cornerRadius: 18).fill(Color.red.opacity(0.08)))
                }
            }
        }
        .fullScreenCover(item: $editing) { theme in ThemeEditor(model: model, theme: theme) }
    }

    private func swatch(_ name: String, palette: Palette, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 5) {
                HStack(spacing: 4) {
                    RoundedRectangle(cornerRadius: 3).fill(Color(palette.key)).frame(width: 14, height: 18).overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(palette.keyOutline), lineWidth: 1))
                    RoundedRectangle(cornerRadius: 3).fill(Color(palette.functionKey)).frame(width: 14, height: 18).overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(palette.keyOutline), lineWidth: 1))
                }.frame(width: 46, height: 46).background(RoundedRectangle(cornerRadius: 14).fill(Color(palette.background)))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(UIColor.separator), lineWidth: 1))
                    .overlay(RoundedRectangle(cornerRadius: 17).stroke(selected ? AppStyle.accent : Color.clear, lineWidth: 2).padding(-3))
                    .overlay(alignment: .topTrailing) { if selected { Image(systemName: "checkmark.circle.fill").font(.caption).foregroundColor(AppStyle.accent).background(Circle().fill(Color.white)) } }
                Text(name).font(.system(size: 11, weight: selected ? .bold : .regular)).multilineTextAlignment(.center)
            }.frame(maxWidth: .infinity, minHeight: 76)
        }.buttonStyle(.plain).accessibilityLabel(name).accessibilityAddTraits(selected ? [.isSelected] : [])
    }
}

private struct ThemeEditor: View {
    @ObservedObject var model: AppSettingsModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    let theme: CustomTheme
    @State private var name: String
    @State private var colors: ThemeColors
    @State private var channel = 3
    @State private var picker = false

    init(model: AppSettingsModel, theme: CustomTheme) {
        self.model = model; self.theme = theme
        _name = State(initialValue: theme.name); _colors = State(initialValue: theme.colors)
    }

    var body: some View {
        GeometryReader { geometry in
            let wide = geometry.size.width >= 600
            VStack(spacing: 8) {
                HStack {
                    Button { dismiss() } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }.accessibilityLabel("Close theme editor")
                    Text(theme.name.isEmpty ? "Create theme" : "Edit theme").font(.title3)
                    Spacer()
                    Button("✓ Save theme") { model.settings.saveTheme(CustomTheme(id: theme.id, name: name, colors: colors)); model.changed(); dismiss() }
                        .buttonStyle(.borderedProminent).disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }.padding(.horizontal, 12)
                KeyboardPreview(model: model, landscape: nil, permitsEditing: false, palette: colors.palette, maximumHeight: geometry.size.height * 0.4, showsPressed: channel == 5)
                    .frame(maxHeight: geometry.size.height * 0.4).clipped().padding(.horizontal, 4)
                Text("Live preview. It changes as you pick colours.").font(.caption2).foregroundColor(.secondary)
                ScrollView {
                    HStack(alignment: .top, spacing: 16) {
                        VStack(alignment: .leading, spacing: 12) {
                            TextField("Theme name", text: $name).textFieldStyle(.roundedBorder).onChange(of: name) { name = String($0.prefix(24)) }
                            Text("Start from").font(.caption.bold()).foregroundColor(AppStyle.accent)
                            ScrollView(.horizontal) {
                                HStack {
                                    ForEach(ThemeName.allCases, id: \.self) { preset in
                                        Button(preset.title) { colors = ThemeColors(Palette.of(preset, dark: scheme == .dark)) }.buttonStyle(.bordered)
                                    }
                                    ForEach(model.settings.customThemes) { preset in
                                        Button(preset.name) { colors = preset.colors }.buttonStyle(.bordered)
                                    }
                                }
                            }
                            Text("Colours").font(.caption.bold()).foregroundColor(AppStyle.accent)
                            ForEach(ThemeColors.labels.indices, id: \.self) { i in
                                Button { channel = i; picker = true } label: {
                                    HStack(spacing: 10) {
                                        Circle().fill(Color(ThemeColors.uiColor(colors.values[i]))).frame(width: 26, height: 26).overlay(Circle().stroke(Color.secondary, lineWidth: 1))
                                        Text(ThemeColors.labels[i]).fontWeight(channel == i ? .bold : .regular)
                                        Spacer(); Text(colors.values[i]).font(.caption.monospaced()); Image(systemName: "chevron.right").font(.caption)
                                    }.padding(12).background(RoundedRectangle(cornerRadius: 14).fill(channel == i ? AppStyle.accent.opacity(0.14) : Color.secondary.opacity(0.06)))
                                }.buttonStyle(.plain)
                            }
                            if !wide { Readability(colors: colors) }
                        }.frame(maxWidth: .infinity)
                        if wide {
                            VStack(spacing: 12) { colorPicker; Readability(colors: colors) }.frame(maxWidth: .infinity)
                        }
                    }.padding(16)
                }
            }.background(AppStyle.background.ignoresSafeArea())
                .sheet(isPresented: Binding(get: { picker && !wide }, set: { picker = $0 })) {
                    ScrollView {
                        VStack(spacing: 10) {
                            colorPicker
                            Readability(colors: colors)
                            Button("✓ Done") { picker = false }.buttonStyle(.borderedProminent).frame(maxWidth: .infinity, alignment: .trailing)
                        }.padding(20)
                    }.themePickerSheet()
                }
        }
    }
    private var colorPicker: some View {
        ThemeColorPicker(label: ThemeColors.labels[channel], hex: Binding(get: { colors.values[channel] }, set: { colors = colors.replacing(channel, with: $0) }), allowTransparent: channel == 6).id(channel)
    }
}

private struct Readability: View {
    let colors: ThemeColors
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Readability").font(.headline)
            if colors.keysBlend { Text("Keys blend into the background").font(.caption.bold()).foregroundColor(.red) }
            ForEach(colors.checks.indices, id: \.self) { i in
                let check = colors.checks[i]
                HStack(alignment: .top) {
                    Image(systemName: check.1 < 4.5 ? "exclamationmark.triangle" : "checkmark").foregroundColor(check.1 < 4.5 ? .red : .green)
                    VStack(alignment: .leading) { Text(check.0).font(.caption.bold()); if check.1 < 4.5 { Text("Hard to read. Aim for 4.5 : 1 or more.").font(.caption2).foregroundColor(.red) } }
                    Spacer(); Text(String(format: "%.1f : 1", check.1)).font(.caption.monospacedDigit())
                }
            }
            Text("You can save with warnings. They are advice, not rules.").font(.caption2).foregroundColor(.secondary)
        }.padding(14).background(RoundedRectangle(cornerRadius: 18).fill(Color.secondary.opacity(0.08)))
    }
}

private struct ThemeColorPicker: View {
    let label: String
    @Binding var hex: String
    var allowTransparent = false
    @State private var entry = ""
    @State private var hue = 0.0
    @State private var saturation = 0.0
    @State private var brightness = 0.0
    private var hsv: (Double, Double, Double) { (hue, saturation, brightness) }
    private var currentColor: UIColor { UIColor(hue: CGFloat(hue), saturation: CGFloat(saturation), brightness: CGFloat(brightness), alpha: 1) }
    private func syncHSV() {
        var h: CGFloat = 0, s: CGFloat = 0, v: CGFloat = 0, a: CGFloat = 0
        ThemeColors.uiColor(hex).getHue(&h, saturation: &s, brightness: &v, alpha: &a)
        hue = Double(h); saturation = Double(s); brightness = Double(v)
    }
    private func update(_ h: Double, _ s: Double, _ v: Double) {
        hue = h; saturation = s; brightness = v
        let color = currentColor
        hex = ThemeColors(Palette(color, color, color, color, color, color, color, color)).text
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label + " colour").font(.headline)
            if allowTransparent {
                Toggle("Show key outline", isOn: Binding(get: { ThemeColors.uiColor(hex).cgColor.alpha > 0 }, set: { enabled in
                    if enabled { update(hue, saturation, brightness) } else { hex = "#00000000" }
                }))
            }
            TextField("#RRGGBB", text: $entry).textFieldStyle(.roundedBorder).textInputAutocapitalization(.characters).disableAutocorrection(true)
                .accessibilityLabel("Hex colour").onChange(of: entry) { if let rgb = ThemeColors.parse($0, alpha: allowTransparent) { hex = String(format: $0.count == 9 ? "#%08X" : "#%06X", rgb) } }
                .onAppear { entry = hex; syncHSV() }.onChange(of: hex) {
                    if ThemeColors.parse(entry, alpha: allowTransparent) != ThemeColors.parse($0, alpha: allowTransparent) { entry = $0 }
                    let current = ThemeColors(Palette(currentColor, currentColor, currentColor, currentColor, currentColor, currentColor, currentColor, currentColor)).text
                    if ThemeColors.parse(current, alpha: allowTransparent) != ThemeColors.parse($0, alpha: allowTransparent) { syncHSV() }
                }
            if ThemeColors.parse(entry, alpha: allowTransparent) == nil { Text(allowTransparent ? "Enter six RGB or eight ARGB hexadecimal digits." : "Enter six hexadecimal digits.").font(.caption).foregroundColor(.red) }
            GeometryReader { geometry in
                ZStack {
                    LinearGradient(colors: [.white, Color(hue: hsv.0, saturation: 1, brightness: 1)], startPoint: .leading, endPoint: .trailing)
                    LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom)
                    Circle().stroke(Color.white, lineWidth: 2).frame(width: 12, height: 12).position(x: geometry.size.width * CGFloat(hsv.1), y: geometry.size.height * CGFloat(1 - hsv.2))
                }.clipShape(RoundedRectangle(cornerRadius: 14)).gesture(DragGesture(minimumDistance: 0).onChanged { drag in
                    update(hsv.0, Double(min(1, max(0, drag.location.x / geometry.size.width))), Double(min(1, max(0, 1 - drag.location.y / geometry.size.height))))
                })
            }.frame(height: 100)
            Text("Hue").font(.caption); Slider(value: Binding(get: { hsv.0 }, set: { update($0, hsv.1, hsv.2) }), in: 0...1).accessibilityLabel("Hue")
            Text("Saturation").font(.caption); Slider(value: Binding(get: { hsv.1 }, set: { update(hsv.0, $0, hsv.2) }), in: 0...1).accessibilityLabel("Saturation")
            Text("Value").font(.caption); Slider(value: Binding(get: { hsv.2 }, set: { update(hsv.0, hsv.1, $0) }), in: 0...1).accessibilityLabel("Value")
        }
    }
}

private extension View {
    @ViewBuilder func themePickerSheet() -> some View {
        if #available(iOS 16.0, *) { self.presentationDetents([.height(620), .large]).presentationDragIndicator(.visible) }
        else { self }
    }
}
