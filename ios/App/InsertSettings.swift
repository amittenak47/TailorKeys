import SwiftUI

struct InsertSettingsOptions: View {
    @ObservedObject var model: AppSettingsModel
    let page: String
    let back: () -> Void
    @State private var managing = false
    private var repository: InsertRepository { InsertRepository(defaults: { model.settings.insertDefaults }) }
    private var prefs: UserDefaults { model.settings.insertDefaults }
    private func flag(_ key: String, _ value: Bool) -> Binding<Bool> { Binding(get: { prefs.boolDefault(key, value) }, set: { prefs.set($0, forKey: key); model.changed() }) }
    var body: some View {
        Group {
            Section { Button("‹  \(page)", action: back).frame(minHeight: 48) }
            if page == "Emoji" {
                Section(footer: Text("Applies to hands and people. Hold an emoji in the panel to pick another.")) {
                    HStack { Text("Default skin tone"); Spacer(); Text(InsertPrefs.toneNames[tone]).foregroundColor(.secondary) }
                    HStack(spacing: 0) {
                        ForEach(0..<6, id: \.self) { index in
                            Button { prefs.set(index, forKey: InsertPrefs.tone); model.changed() } label: {
                                VStack(spacing: 6) {
                                    ZStack {
                                        Circle().fill(toneColors[index]).frame(width: 32, height: 32)
                                        if tone == index { Image(systemName: "checkmark").font(.caption.bold()).foregroundColor(.white).frame(width: 22, height: 22).background(Circle().fill(Color(white: 0.14))) }
                                    }
                                    Text(InsertPrefs.toneNames[index]).font(.caption2).lineLimit(1).minimumScaleFactor(0.65)
                                }.frame(maxWidth: .infinity, minHeight: 76)
                            }.buttonStyle(.plain).accessibilityLabel(InsertPrefs.toneNames[index])
                                .accessibilityValue(prefs.integer(forKey: InsertPrefs.tone) == index ? "Selected" : "")
                        }
                    }
                    HStack {
                        Text("Preview").font(.caption)
                        ForEach(Array(["👋", "👍", "🙌", "🤝", "💪"].enumerated()), id: \.offset) { _, base in
                            Text(base + (tone == 0 ? "" : String(UnicodeScalar(0x1F3FA + tone)!))).font(.system(size: 28))
                        }
                    }.accessibilityLabel("Skin tone preview, \(InsertPrefs.toneNames[tone])")
                }
                Section {
                    Toggle(isOn: flag(InsertPrefs.recents, true)) { settingsLabel("Show recent emoji", "Recents come first in the panel") }
                    clearRow("Clear recent emoji", "Removes \(prefs.stringArray(forKey: "insert_recent_Emoji")?.count ?? 0) recents") { prefs.removeObject(forKey: "insert_recent_Emoji"); model.changed() }
                    Toggle(isOn: flag(InsertPrefs.kaomoji, true)) { settingsLabel("Show Kaomoji tab", "Faces such as (^_^) next to emoji") }
                }
            } else if page == "Clipboard and notes" {
                Section { Toggle(isOn: flag(InsertPrefs.history, false)) { settingsLabel("Clipboard history", "Keep what you copy so you can paste it again.") } }
                Section(header: Text("Keep items for").textCase(nil)) {
                    Picker("Keep items for", selection: Binding(get: { repository.keep.rawValue }, set: { prefs.set($0, forKey: InsertPrefs.keep); repository.change { _ in }; model.changed() })) {
                        ForEach(ClipKeep.allCases, id: \.rawValue) { Text($0.title).tag($0.rawValue) }
                    }.pickerStyle(.inline)
                }
                Section {
                    clearRow("Clear clipboard history now", "Removes \(repository.store().clips.filter { !$0.pinned }.count) items. Pinned items stay.") { repository.change { $0.clear() }; model.changed() }
                    Button("Manage notes · \(repository.store().notes.count)") { managing = true }
                }
                Section {
                    Text("Private by design").font(.subheadline.weight(.semibold))
                    Text("Items stay on this device and clear after \(repository.keep.title). Clipboard history in the keyboard needs Full Access. The keyboard reads the pasteboard only when you tap Read clipboard; iOS may ask permission. Notes still work without Full Access.").font(.footnote).foregroundColor(.secondary)
                }
            } else {
                Section(header: Text("Dictation").textCase(nil)) {
                    Label("Dictation is built into iPhone and iPad", systemImage: "mic")
                    Text("Apple does not let third-party keyboards use the microphone. In the Insert panel, Dictate points to the system mic below the keyboard. Tap it to talk; words appear in your field.")
                    Text("No mic? Turn on Dictation in Settings, General, Keyboard.").font(.footnote).foregroundColor(.secondary)
                }
            }
        }
        .sheet(isPresented: $managing) { InsertNotesManager(model: model) }
    }

    private var tone: Int { min(max(prefs.integer(forKey: InsertPrefs.tone), 0), 5) }
    private func settingsLabel(_ title: String, _ note: String) -> some View {
        VStack(alignment: .leading, spacing: 3) { Text(title); Text(note).font(.footnote).foregroundColor(.secondary) }
    }
    private func clearRow(_ title: String, _ note: String, clear: @escaping () -> Void) -> some View {
        HStack {
            settingsLabel(title, note)
            Spacer()
            Button("Clear", role: .destructive, action: clear).buttonStyle(.bordered).frame(minHeight: 44)
        }
    }

    private var toneColors: [Color] {
        [Color(red: 1, green: 0.80, blue: 0.22), Color(red: 0.97, green: 0.86, blue: 0.78),
         Color(red: 0.94, green: 0.82, blue: 0.61), Color(red: 0.75, green: 0.60, blue: 0.45),
         Color(red: 0.64, green: 0.46, blue: 0.30), Color(red: 0.44, green: 0.29, blue: 0.20)]
    }
}

private struct NoteDraft: Identifiable {
    let id = UUID()
    let item: ClipItem?
}

private struct InsertNotesManager: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var model: AppSettingsModel
    @State private var draft: NoteDraft?
    @State private var deleted: ClipItem?
    @State private var deletedAt = Date.distantPast
    private var repository: InsertRepository { InsertRepository(defaults: { model.settings.insertDefaults }) }
    var body: some View {
        NavigationView {
            List {
                Button("+ New note") { draft = NoteDraft(item: nil) }
                ForEach(repository.store().notes) { item in
                    HStack {
                        Button { draft = NoteDraft(item: item) } label: {
                            VStack(alignment: .leading) { Text(item.title.isEmpty ? "Note" : item.title).fontWeight(.semibold); Text(item.text).lineLimit(2).foregroundColor(.secondary) }
                        }
                        Spacer()
                        Button("Delete", role: .destructive) { repository.change { $0.delete(item.id) }; deleted = item; deletedAt = Date(); model.changed() }.buttonStyle(.borderless)
                    }.frame(minHeight: 48)
                }
                if let item = deleted {
                    Button("Note deleted · Undo") {
                        if Date().timeIntervalSince(deletedAt) <= 6 { repository.change { $0.restore(item) }; model.changed() }; deleted = nil
                    }
                }
            }
            .navigationTitle("Manage notes")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .sheet(item: $draft) { value in InsertNoteEditor(model: model, item: value.item) }
            .onChange(of: deletedAt) { stamp in DispatchQueue.main.asyncAfter(deadline: .now() + 6) { if deletedAt == stamp { deleted = nil } } }
        }.navigationViewStyle(.stack)
    }
}

private struct InsertNoteEditor: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var model: AppSettingsModel
    let item: ClipItem?
    @State private var title: String
    @State private var text: String
    init(model: AppSettingsModel, item: ClipItem?) {
        self.model = model; self.item = item; _title = State(initialValue: item?.title ?? ""); _text = State(initialValue: item?.text ?? "")
    }
    var body: some View {
        NavigationView {
            Form {
                TextField("Title", text: $title).accessibilityLabel("Note title").onChange(of: title) { title = String($0.prefix(40)) }
                TextEditor(text: $text).frame(minHeight: 180).accessibilityLabel("Note text").onChange(of: text) { text = String($0.prefix(ClipboardStore.noteMax)) }
                Text("\(title.count)/\(ClipboardStore.titleMax) · \(text.count)/\(ClipboardStore.noteMax)").font(.caption).foregroundColor(.secondary)
            }.navigationTitle(item == nil ? "New note" : "Edit note")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") {
                    InsertRepository(defaults: { model.settings.insertDefaults }).change { store in
                        if let item = item { store.edit(item.id, text: text, title: title) } else { store.note(title, text) }
                    }; model.changed(); dismiss()
                } }
            }
        }.navigationViewStyle(.stack)
    }
}
