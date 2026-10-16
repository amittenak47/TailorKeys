import SwiftUI

struct FullAccessSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Image(systemName: "keyboard").font(.system(size: 44)).foregroundColor(AppStyle.accent)
                    Text("Full Access is optional").font(.title2).bold()
                    Text("Turn it on so the settings you change in this app reach the keyboard.")
                    Text("What you type never leaves this device. TailorKeys has no network code, with or without Full Access.")
                    Text("iOS shows the same warning for every keyboard that asks. It isn't about anything TailorKeys does.")
                        .foregroundColor(.secondary)
                    Text("In Settings, tap Keyboards and turn on Allow Full Access. Then open the keyboard once and come back.")
                    Button("Open Settings", action: AppStyle.openSettings)
                        .buttonStyle(.borderedProminent)
                    Text("Rather not? Change settings from the gear in the keyboard.")
                        .font(.footnote).foregroundColor(.secondary)
                }
                .padding(24)
            }
            .navigationTitle("Full Access")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
            }
        }
        .navigationViewStyle(.stack)
    }
}

struct ImportLayoutSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var model: AppSettingsModel
    @State private var code: String

    init(model: AppSettingsModel) {
        self.model = model
        _code = State(initialValue: model.importCode)
    }

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Layout code")) {
                    TextField("Paste a TK1- code", text: $code)
                        .textInputAutocapitalization(.never)
                        .disableAutocorrection(true)
                        .textFieldStyle(.roundedBorder)
                    if !code.isEmpty && KeyGrid.fromCode(code) == nil {
                        Text("No valid layout code found.").font(.footnote).foregroundColor(.secondary)
                    }
                }
                Section {
                    Button("Import") { importLayout() }
                        .disabled(KeyGrid.fromCode(code) == nil || !model.fullAccessConfirmed)
                }
            }
            .navigationTitle("Import layout")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
        .navigationViewStyle(.stack)
    }

    private func importLayout() {
        guard model.fullAccessConfirmed, let grid = KeyGrid.fromCode(code) else { return }
        model.editing = false
        model.saveGrid(grid)
        dismiss()
    }
}

/// The iOS 15 share sheet; ShareLink requires iOS 16.
struct LayoutShareSheet: UIViewControllerRepresentable {
    let code: String

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: [code], applicationActivities: nil)
        if let popover = controller.popoverPresentationController {
            popover.sourceView = controller.view
            popover.sourceRect = CGRect(x: controller.view.bounds.midX, y: controller.view.bounds.maxY, width: 0, height: 0)
            popover.permittedArrowDirections = []
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

struct AboutSheet: View {
    @Environment(\.dismiss) private var dismiss

    private var version: String {
        let info = Bundle.main.infoDictionary ?? [:]
        let version = info["CFBundleShortVersionString"] as? String ?? "\u{2014}"
        let build = info["CFBundleVersion"] as? String ?? "\u{2014}"
        return "Version \(version) (\(build))"
    }

    private var notice: String? {
        let url = Bundle.main.url(forResource: "NOTICE-dictionary", withExtension: "txt")
            ?? Bundle.main.url(forResource: "NOTICE", withExtension: nil)
        guard let url = url else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    HStack(spacing: 10) {
                        LogoMark()
                        Text("TailorKeys").font(.title).bold()
                    }
                    Text(version).foregroundColor(.secondary)
                    if let notice = notice {
                        Text(notice).font(.footnote).textSelection(.enabled)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(24)
            }
            .navigationTitle("About")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
            }
        }
        .navigationViewStyle(.stack)
    }
}

struct LanguagesSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var model: AppSettingsModel

    var body: some View {
        NavigationView {
            List {
                ForEach(Prefs.languageCodes, id: \.self) { code in
                    let selected = model.settings.languages.contains(code)
                    Button {
                        let enabled = model.settings.languages
                        model.setLanguages(selected ? enabled.filter { $0 != code } : enabled + [code])
                    } label: {
                        HStack {
                            Image(systemName: selected ? "checkmark.square.fill" : "square")
                            Text(Prefs.languageNames[code] ?? code).foregroundColor(.primary)
                        }
                    }
                    .disabled(selected && model.settings.languages.count == 1)
                    .accessibilityValue(selected ? "Enabled" : "Disabled")
                    .accessibilityAddTraits(selected ? .isSelected : [])
                }
            }
            .navigationTitle("Languages")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .navigationViewStyle(.stack)
    }
}
