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
                    VStack(spacing: 5) { Image(systemName: "plus").frame(width: 48, height: 48).overlay(RoundedRectangle(cornerRadius: 14).stroke(style: StrokeStyle(lineWidth: 1, dash: [4, 3]))); Text("Create").font(.caption) }.frame(maxWidth: .infinity, minHeight: 76)
                }.buttonStyle(.plain).foregroundColor(AppStyle.accent).accessibilityLabel("Create theme")
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
                }.frame(width: 48, height: 48).background(RoundedRectangle(cornerRadius: 14).fill(Color(palette.background)))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(LayoutStyle.outline, lineWidth: 1))
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
    @State private var preset: String?
    @State private var pickerOriginal: String

    init(model: AppSettingsModel, theme: CustomTheme) {
        self.model = model; self.theme = theme
        _name = State(initialValue: theme.name); _colors = State(initialValue: theme.colors)
        _preset = State(initialValue: model.settings.customThemes.contains { $0.id == theme.id } ? theme.selection : nil)
        _pickerOriginal = State(initialValue: theme.colors.text)
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
                            HStack { Text("Theme name").font(.caption); Spacer(); Text("\(name.count) / 24").font(.caption).monospacedDigit() }
                            TextField("Theme name", text: $name).textFieldStyle(.roundedBorder).onChange(of: name) { name = String($0.prefix(24)) }
                            Text("Start from").font(.caption.bold()).foregroundColor(AppStyle.accent)
                            ScrollView(.horizontal) {
                                HStack {
                                    ForEach(ThemeName.allCases, id: \.self) { preset in
                                        startButton(preset.title, id: preset == .highContrast ? "HIGH_CONTRAST" : preset.rawValue.uppercased(), colors: ThemeColors(Palette.of(preset, dark: scheme == .dark)))
                                    }
                                    ForEach(model.settings.customThemes) { preset in
                                        startButton(preset.name, id: preset.selection, colors: preset.colors)
                                    }
                                }
                            }
                            Text("Colours").font(.caption.bold()).foregroundColor(AppStyle.accent)
                            ForEach(ThemeColors.labels.indices, id: \.self) { i in
                                let open = channel == i && (wide || picker)
                                let low = colors.checks.contains { !$0.passes && $0.channels.contains(i) }
                                Button { if !open { pickerOriginal = colors.values[i] }; channel = i; picker = true } label: {
                                    HStack(spacing: 10) {
                                        Circle().fill(Color(ThemeColors.uiColor(colors.values[i]))).frame(width: 26, height: 26).overlay(Circle().stroke(Color.secondary, lineWidth: 1))
                                        VStack(alignment: .leading) {
                                            Text(ThemeColors.labels[i]).fontWeight(open ? .bold : .regular)
                                            if low { Label("Low contrast", systemImage: "exclamationmark.triangle").font(.caption2).foregroundColor(.red) }
                                        }
                                        Spacer(); Text(colors.values[i]).font(.caption.monospaced()); Image(systemName: open ? "chevron.down" : "chevron.right").font(.caption)
                                    }.padding(12).background(RoundedRectangle(cornerRadius: 14).fill(open ? AppStyle.accent.opacity(0.14) : Color.secondary.opacity(0.06)))
                                }.buttonStyle(.plain)
                            }
                            if !wide { Readability(colors: $colors) }
                        }.frame(maxWidth: .infinity)
                        if wide {
                            VStack(spacing: 12) { colorPicker; Readability(colors: $colors) }.frame(maxWidth: .infinity)
                        }
                    }.padding(16)
                }
            }.background(AppStyle.background.ignoresSafeArea())
                .sheet(isPresented: Binding(get: { picker && !wide }, set: { picker = $0 })) {
                    ScrollView {
                        VStack(spacing: 10) {
                            colorPicker
                            Readability(colors: $colors)
                            Button("✓ Done") { picker = false }.buttonStyle(.borderedProminent).frame(maxWidth: .infinity, alignment: .trailing)
                        }.padding(20)
                    }.themePickerSheet()
                }
        }
    }
    private func startButton(_ title: String, id: String, colors seeded: ThemeColors) -> some View {
        Button((preset == id ? "✓ " : "") + title) { preset = id; colors = seeded; pickerOriginal = seeded.values[channel] }
            .buttonStyle(.bordered).font(.body.weight(preset == id ? .bold : .regular))
            .background(RoundedRectangle(cornerRadius: 8).fill(preset == id ? AppStyle.accent.opacity(0.18) : Color.clear))
            .accessibilityAddTraits(preset == id ? [.isSelected] : [])
    }
    private var colorPicker: some View {
        ThemeColorPicker(label: ThemeColors.labels[channel], hex: Binding(get: { colors.values[channel] }, set: { colors = colors.replacing(channel, with: $0) }), resetColor: pickerOriginal, suggestion: pickerSuggestion, allowTransparent: channel == 6).id(channel)
    }
    private var pickerSuggestion: (String, Double)? {
        guard let check = colors.checks.first(where: { !$0.passes && $0.channels.contains(channel) }) else { return nil }
        let against = channel == check.fixChannel || channel == 6 ? check.background : colors.values[check.fixChannel]
        let suggestion = ThemeColors.closestReadable(colors.values[channel], against, channel == 6 ? 3 : check.target)
        return (suggestion, ThemeColors.contrast(suggestion, against))
    }
}

private struct Readability: View {
    @Binding var colors: ThemeColors
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Readability").font(.headline)
            ForEach(colors.checks.indices, id: \.self) { i in
                let check = colors.checks[i]
                HStack(alignment: .top) {
                    Image(systemName: check.passes ? "checkmark" : "exclamationmark.triangle").foregroundColor(check.passes ? .green : .red)
                    VStack(alignment: .leading) {
                        Text(check.label).font(.caption.bold())
                        if !check.passes {
                            Text(check.warning).font(.caption2).foregroundColor(.red)
                            let suggestion = ThemeColors.closestReadable(colors.values[check.fixChannel], check.background, check.target)
                            Button("Use \(suggestion)") { colors = colors.replacing(check.fixChannel, with: suggestion) }.frame(minHeight: 44)
                        }
                    }
                    Spacer(); Text(String(format: "%.1f : 1", check.ratio)).font(.caption.monospacedDigit())
                }
            }
            Text("You can save with warnings. They are advice, not rules.").font(.caption2).foregroundColor(.secondary)
        }.padding(14).background(RoundedRectangle(cornerRadius: 18).fill(Color.secondary.opacity(0.08)))
    }
}

private struct ThemeColorPicker: View {
    let label: String
    @Binding var hex: String
    let resetColor: String
    let suggestion: (String, Double)?
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
                .accessibilityLabel("Hex colour").onChange(of: entry) { value in
                    if let rgb = ThemeColors.parse(value, alpha: allowTransparent) {
                        let digits = value.hasPrefix("#") ? value.count - 1 : value.count
                        hex = String(format: digits == 8 ? "#%08X" : "#%06X", rgb)
                    }
                }
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
            if let suggestion = suggestion {
                HStack {
                    Text("Closest readable colour \(suggestion.0) reads at \(String(format: "%.1f", suggestion.1)) : 1").font(.caption)
                    Spacer(); Button("Use") { hex = suggestion.0 }.frame(minHeight: 44)
                }
            }
            Button("↻ Reset") { hex = resetColor; entry = resetColor; syncHSV() }.frame(maxWidth: .infinity, minHeight: 44, alignment: .trailing)
        }
    }
}

private extension View {
    @ViewBuilder func themePickerSheet() -> some View {
        if #available(iOS 16.0, *) { self.presentationDetents([.height(620), .large]).presentationDragIndicator(.visible) }
        else { self }
    }
}
