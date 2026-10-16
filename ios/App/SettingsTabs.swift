import SwiftUI

struct SettingsScreen: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var model: AppSettingsModel
    let tab: SettingsTab
    @State private var ratio = 1.0
    @State private var confirmReset = false
    @State private var languagesOpen = false
    @State private var chordsOpen = false
    @State private var numberRowBlocked = false

    private var prefs: KeyboardSettings { model.settings }
    private var l: Bool { model.landscape }

    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 0) {
                AppHeader(model: model)
                if pinsPreview(in: geometry.size.height) {
                    preview.padding(.top, 16)
                    optionsList
                } else {
                    // Scroll the real keyboard with its controls when the preview needs most of the screen.
                    List {
                        Section {
                            preview.listRowInsets(EdgeInsets()).listRowBackground(Color.clear)
                        }
                        options
                    }
                    .listStyle(.insetGrouped)
                }
                if tab == .layout { sharingButtons }
            }
            .environment(\.defaultMinListRowHeight, 48)
            .background(AppStyle.background.ignoresSafeArea())
        }
        .sheet(isPresented: $languagesOpen) { LanguagesSheet(model: model) }
        .sheet(isPresented: $chordsOpen) { ChordListSheet(model: model) }
        .alert("Reset settings?", isPresented: $confirmReset) {
            Button("Cancel", role: .cancel) {}
            Button("Reset settings", role: .destructive) { model.reset() }
        } message: {
            Text("Your layout and settings will return to their defaults.")
        }
        .alert("Number row is in use", isPresented: $numberRowBlocked) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Move other keys out of the top row before removing the number row.")
        }
        .transaction { if reduceMotion { $0.disablesAnimations = true; $0.animation = nil } }
    }

    private func pinsPreview(in height: CGFloat) -> Bool {
        let screen = UIScreen.main.bounds
        let landscape = tab == .size ? l : screen.width > screen.height
        let target = screen.height * CGFloat(prefs.profile(landscape).heightPercent) / 100
        let chrome: CGFloat = 52 + 16 + (tab == .layout ? 52 : 0)
        // Leave a usable list even at the largest height, including on smaller iPhones.
        return height - target - chrome >= 180
    }

    private var preview: some View {
        KeyboardPreview(model: model, landscape: tab == .size ? l : nil, permitsEditing: tab == .layout)
            .padding(.horizontal, 4)
    }

    private var optionsList: some View {
        List { options }
            .listStyle(.insetGrouped)
    }

    @ViewBuilder private var options: some View {
        if !model.fullAccessConfirmed {
            Section {
                Text("Change settings from the gear in the keyboard, or allow Full Access to change them here.")
                    .font(.system(size: 13)).foregroundColor(.secondary)
                    .padding(.vertical, 4)
                    .onTapGesture { model.sheet = .fullAccess }
                    .accessibilityAddTraits(.isButton)
                    .accessibilityHint("Learn about Full Access")
            }
        }
        switch tab {
        case .layout: layoutOptions
        case .size: sizeOptions
        case .look: lookOptions
        case .typing: typingOptions
        }
    }

    private var layoutOptions: some View {
        Group {
            Section {
                MenuRow(title: "Preset", value: model.layoutTitle) {
                    ForEach(Template.allCases, id: \.self) { template in
                        Button { model.applyTemplate(template) } label: {
                            if model.layoutTitle == template.title { Label(template.title, systemImage: "checkmark") }
                            else { Text(template.title) }
                        }
                    }
                    Button {} label: {
                        if model.layoutTitle == "Custom" { Label("Custom", systemImage: "checkmark") }
                        else { Text("Custom") }
                    }
                    .disabled(true)
                }
            }
            Section {
                Button { model.editing.toggle() } label: {
                    Label(model.editing ? "Done" : "Edit layout", systemImage: model.editing ? "checkmark" : "pencil")
                        .font(.system(size: 17, weight: .semibold))
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .foregroundColor(.white)
                        .background(RoundedRectangle(cornerRadius: 14).fill(AppStyle.accent))
                }
                .buttonStyle(.plain)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
        }
        .disabled(!model.fullAccessConfirmed)
    }

    private var sharingButtons: some View {
        HStack(spacing: 8) {
            Button {
                model.shareCode = model.grid.toCode()
                model.sheet = .share
            } label: { Label("Share", systemImage: "square.and.arrow.up") }
            Button(action: model.prepareImport) { Label("Import", systemImage: "square.and.arrow.down") }
        }
        .font(.system(size: 15, weight: .medium))
        .buttonStyle(.borderless)
        .controlSize(.regular)
        .frame(maxWidth: .infinity, minHeight: 44)
        .padding(.bottom, 8)
        .disabled(!model.fullAccessConfirmed)
    }

    private var sizeOptions: some View {
        Group {
            Section {
                Toggle("Same size when rotated", isOn: Binding(get: { prefs.sameOrientations }, set: { on in
                    if on { prefs.linkOrientations(from: l) } else { prefs.set(Prefs.sameOrientations, false) }
                    model.changed()
                }))
                if !prefs.sameOrientations {
                    Picker("Orientation", selection: $model.landscape) {
                        Text("Portrait").tag(false)
                        Text("Landscape").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .padding(.vertical, 4)
                }
            }
            Section {
                MenuRow(title: "Size", value: model.sizeTitle) {
                    ForEach(model.sizePresets, id: \.name) { preset in
                        Button { model.applySize(preset) } label: {
                            if model.sizeTitle == preset.name { Label(preset.name, systemImage: "checkmark") }
                            else { Text(preset.name) }
                        }
                    }
                    if model.sizeTitle == "Custom" { Button("Custom") {}.disabled(true) }
                }
                SettingSlider(title: "Height", pref: Prefs.height(l), value: model.integer(Prefs.height(l)) { v in
                    prefs.setFor(l, Prefs.height, v)
                    if prefs.lockSize { prefs.setFor(l, Prefs.width, Int((Double(v) / ratio).rounded())) }
                }, suffix: "%", onEditingChanged: captureRatio)
                widthSlider
                if prefs.int(Prefs.width(l)) < 100 {
                    Picker("Position", selection: Binding(get: { prefs.align(l) }, set: { value in
                        prefs.setAlignFor(l, value)
                        model.changed()
                    })) {
                        ForEach(Align.allCases, id: \.self) { align in
                            Text(align.rawValue.capitalized).tag(align)
                        }
                    }
                    .pickerStyle(.segmented)
                    .padding(.vertical, 4)
                }
                orientationSlider("Lift keyboard", Prefs.bottomPadding)
            }
            Section(header: Text("Split").textCase(nil).font(.system(size: 15, weight: .semibold))) {
                Toggle("Split keyboard", isOn: Binding(get: { prefs.split(l) }, set: { on in
                    prefs.setSplitFor(l, on)
                    model.changed()
                }))
                if prefs.split(l) {
                    orientationSlider("Center gap", Prefs.splitGap, suffix: "%")
                    orientationSlider("Tilt", Prefs.tilt, suffix: "\u{00B0}", signed: true)
                    orientationSlider("Space row tilt", Prefs.spaceTilt, suffix: "\u{00B0}", signed: true)
                    SettingSlider(title: "Curve", pref: Prefs.curve, value: model.integer(Prefs.curve), suffix: "%")
                }
            }
        }
        .disabled(!model.fullAccessConfirmed)
    }

    private var widthSlider: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Width")
                Spacer()
                Button {
                    prefs.set(Prefs.lockSize, !prefs.lockSize)
                    model.changed()
                } label: {
                    Image(systemName: prefs.lockSize ? "lock.fill" : "lock.open")
                        .frame(width: 44, height: 32)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Lock width")
                .accessibilityValue(prefs.lockSize ? "On" : "Off")
                Text("\(prefs.int(Prefs.width(l)))%")
                    .font(.system(size: 15)).foregroundColor(.secondary).monospacedDigit()
            }
            Slider(value: model.integer(Prefs.width(l)) { v in
                prefs.setFor(l, Prefs.width, v)
                if prefs.lockSize { prefs.setFor(l, Prefs.height, Int((Double(v) * ratio).rounded())) }
            }, in: Double(Prefs.width(l).min)...Double(Prefs.width(l).max), step: 1,
                   onEditingChanged: captureRatio)
                .accessibilityLabel("Width")
        }
        .padding(.top, 8)
    }

    private func captureRatio(_ editing: Bool) {
        if editing { ratio = Double(prefs.int(Prefs.height(l))) / Double(max(1, prefs.int(Prefs.width(l)))) }
    }

    private func orientationSlider(_ title: String, _ pref: @escaping (Bool) -> IntPref,
                                   suffix: String = "", signed: Bool = false) -> some View {
        SettingSlider(title: title, pref: pref(l), value: model.integer(pref(l)) { v in
            prefs.setFor(l, pref, v)
        }, suffix: suffix, signed: signed)
    }

    private var lookOptions: some View {
        Group {
            Section {
                MenuRow(title: "Theme", value: themeTitle(prefs.theme)) {
                    ForEach(ThemeName.allCases, id: \.self) { theme in
                        Button { setTheme(theme) } label: {
                            if prefs.theme == theme { Label(themeTitle(theme), systemImage: "checkmark") }
                            else { Text(themeTitle(theme)) }
                        }
                    }
                }
                .listRowSeparator(.hidden)
                HStack(spacing: 4) {
                    ForEach(ThemeName.allCases, id: \.self) { theme in
                        ThemeSwatch(theme: theme, selected: prefs.theme == theme) { setTheme(theme) }
                    }
                }
                .listRowInsets(EdgeInsets(top: 0, leading: 8, bottom: 8, trailing: 8))
            }
            Section {
                SettingSlider(title: "Key spacing", pref: Prefs.keyGap, value: model.integer(Prefs.keyGap))
                SettingSlider(title: "Corner radius", pref: Prefs.cornerRadius, value: model.integer(Prefs.cornerRadius))
                SettingSlider(title: "Text size", pref: Prefs.textScale, value: model.integer(Prefs.textScale), suffix: "%")
                Toggle("Use system text size", isOn: model.bool(Prefs.followSystemText, default: true))
                Toggle("Number row", isOn: Binding(get: { prefs.numberRow }, set: { on in
                    if !model.setNumberRow(on) { numberRowBlocked = true }
                }))
                Toggle("Key hints", isOn: model.bool(Prefs.showHints, default: true))
            }
        }
        .disabled(!model.fullAccessConfirmed)
    }

    private func setTheme(_ theme: ThemeName) {
        guard model.fullAccessConfirmed else { return }
        prefs.theme = theme
        model.changed()
    }

    private var typingOptions: some View {
        Group {
            Section {
                Button { languagesOpen = true } label: {
                    HStack {
                        Text("Languages").foregroundColor(.primary)
                        Spacer()
                        Text(prefs.languages.compactMap { Prefs.languageNames[$0] }.joined(separator: ", "))
                            .foregroundColor(.secondary).multilineTextAlignment(.trailing)
                    }
                }
                .disabled(!model.fullAccessConfirmed)
            }
            Section {
                Toggle("Auto-capitalize", isOn: model.bool(Prefs.autoCap, default: true))
                Toggle("Double-space period", isOn: model.bool(Prefs.doubleSpace, default: true))
                Toggle("Key sounds", isOn: model.bool(Prefs.clicks, default: true))
                SettingSlider(title: "Long-press time", pref: Prefs.longPressMs,
                              value: model.integer(Prefs.longPressMs), suffix: " ms")
            }
            .disabled(!model.fullAccessConfirmed)
            Section(header: Text("Accessibility").textCase(nil).font(.system(size: 15, weight: .semibold))) {
                Toggle("Type on press", isOn: model.bool(Prefs.typeOnPress, default: false))
                SettingSlider(title: "Ignore repeated taps", pref: Prefs.keyRepeatGuard,
                              value: model.integer(Prefs.keyRepeatGuard), suffix: " ms", offAtZero: true)
                SettingSlider(title: "Extra tap area", pref: Prefs.touchSlack,
                              value: model.integer(Prefs.touchSlack), suffix: " pt")
            }
            .disabled(!model.fullAccessConfirmed)
            Section(header: Text("Assist").textCase(nil).font(.system(size: 15, weight: .semibold))) {
                assistedToggle("Swipe typing", Prefs.swipeTyping, default: true)
                Toggle("Highlight likely keys", isOn: model.bool(Prefs.keyTint, default: true))
                Toggle("Smart tap areas", isOn: model.bool(Prefs.smartTargets, default: true))
                assistedToggle("Chords", Prefs.chording, default: false)
                Button("Chord list") { chordsOpen = true }
            }
            .disabled(!model.fullAccessConfirmed)
            Section {
                sheetRow("Guide", sheet: .guide)
                sheetRow("About", sheet: .about)
            }
            Section {
                Button("Reset settings", role: .destructive) { confirmReset = true }
                    .disabled(!model.fullAccessConfirmed)
            }
        }
    }

    private func assistedToggle(_ title: String, _ key: String, default value: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Toggle(title, isOn: Binding(get: { !prefs.typeOnPress && prefs.bool(key, value) }, set: { on in
                prefs.set(key, on); model.changed()
            }))
            .disabled(prefs.typeOnPress)
            if prefs.typeOnPress {
                Text("Off while Type on press is on").font(.footnote).foregroundColor(.secondary)
            }
        }
    }

    private func sheetRow(_ title: String, sheet: AppSettingsModel.Sheet) -> some View {
        Button { model.sheet = sheet } label: {
            HStack {
                Text(title).foregroundColor(.primary)
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundColor(.secondary)
            }
        }
    }
}

private func themeTitle(_ theme: ThemeName) -> String { theme == .system ? "System" : theme.title }

private struct ThemeSwatch: View {
    @Environment(\.colorScheme) private var colorScheme
    let theme: ThemeName
    let selected: Bool
    let action: () -> Void

    var body: some View {
        let palette = Palette.of(theme, dark: colorScheme == .dark)
        Button(action: action) {
            VStack(spacing: 5) {
                ZStack {
                    RoundedRectangle(cornerRadius: 10).fill(Color(palette.background))
                    RoundedRectangle(cornerRadius: 5).fill(Color(palette.key)).padding(8)
                    if theme == .highContrast {
                        RoundedRectangle(cornerRadius: 5).stroke(Color.white, lineWidth: 2).padding(8)
                    }
                    Text("A").font(.system(size: 15, weight: theme == .highContrast ? .bold : .regular)).foregroundColor(Color(palette.text))
                }
                .frame(width: 44, height: 44)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(selected ? AppStyle.accent : .clear, lineWidth: 2))
                Text(themeTitle(theme)).font(.system(size: 11)).multilineTextAlignment(.center).foregroundColor(selected ? AppStyle.accent : .secondary)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(themeTitle(theme))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
