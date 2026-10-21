import SwiftUI

struct LayoutScreen: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var model: AppSettingsModel
    @Namespace private var morph
    @State private var modal: LayoutModal?
    @State private var name = "Custom"
    @State private var deleting = false
    @State private var notice: String?
    @State private var dockHeight: CGFloat = 72

    private enum LayoutModal: Equatable { case add, rename, saveFirst }

    var body: some View {
        ZStack {
            if model.testing, let choice = model.trialChoice {
                TypingTestScreen(model: model, choice: choice, namespace: morph, onBack: closeTest)
                    .transition(.opacity)
            } else {
                GeometryReader { geometry in layoutPage(size: geometry.size) }
                    .transition(.opacity)
                    .accessibilityHidden(modal != nil)
            }
            if let modal = modal { modalView(modal).zIndex(2) }
        }
        .foregroundColor(LayoutStyle.text)
        .background(LayoutStyle.background.ignoresSafeArea())
        .onAppear { model.previewSelectedLayout() }
        .onDisappear { if model.testing { model.endTest() } }
        .onChange(of: model.selectedLayoutKey) { _ in deleting = false }
    }

    private func layoutPage(size: CGSize) -> some View {
        let target = UIScreen.main.bounds.height * CGFloat(model.settings.profile(size.width > size.height).heightPercent) / 100
        return VStack(spacing: 0) {
            AppHeader(model: model, layoutStyle: true).padding(.bottom, 8)
            if !model.importOpen && size.height - target > 260 {
                preview.padding(.horizontal, 4)
                ScrollView { pageContent(wide: size.width >= 600).padding(16) }
            } else {
                ScrollView {
                    VStack(spacing: 12) {
                        if !model.importOpen { preview.padding(.horizontal, 4) }
                        pageContent(wide: size.width >= 600).padding(.horizontal, 16).padding(.bottom, 8)
                    }
                }
            }
            if !model.importOpen {
                actionDock(wide: size.width >= 600).padding(.horizontal, 16).padding(.bottom, 8)
                    .background(GeometryReader { geometry in
                        Color.clear.preference(key: LayoutDockHeight.self, value: geometry.size.height)
                    })
            }
        }
        // SwiftUI shrinks this container to the keyboard safe area. The import panel
        // stays in the scrolling branch, with no full keyboard preview consuming its space.
        .onPreferenceChange(LayoutDockHeight.self) { dockHeight = $0 }
    }

    private var preview: some View {
        KeyboardPreview(model: model, landscape: nil, permitsEditing: !model.importOpen, previewsSelection: true)
            .opacity(importError == nil ? 1 : 0.28)
            .overlay {
                if importError != nil {
                    Label("No preview for this code", systemImage: "exclamationmark.circle")
                        .font(.subheadline.weight(.medium)).padding(12)
                        .background(Capsule().fill(LayoutStyle.raised))
                }
            }
            .layoutMorph("keyboard", in: morph, enabled: !reduceMotion)
    }

    @ViewBuilder private func pageContent(wide: Bool) -> some View {
        if model.importOpen { importPanel }
        else {
            VStack(spacing: wide ? 12 : 8) {
                HStack {
                    Text("Layouts").font(.headline)
                    Spacer()
                    Text("Tap a layout to preview it").font(.caption).foregroundColor(LayoutStyle.muted)
                }.padding(.vertical, 4)
                if !model.fullAccessConfirmed {
                    Button { model.sheet = .fullAccess } label: {
                        Text("Allow Full Access to send layouts to your keyboard.")
                            .font(.footnote).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    }.buttonStyle(.plain).foregroundColor(LayoutStyle.accent)
                }
                if model.editing {
                    HStack {
                        Text("Drag keys in the preview to rearrange them.").font(.footnote)
                        Spacer()
                        Button("Done") { model.editing = false }.frame(minWidth: 44, minHeight: 44)
                    }
                    if model.selectedLayoutKey == "draft" {
                        Button("Save layout") { openName(.rename) }.buttonStyle(LayoutButtonStyle(primary: true))
                    }
                }
                LazyVGrid(columns: [GridItem(.flexible(), spacing: wide ? 12 : 8), GridItem(.flexible())], spacing: wide ? 12 : 8) {
                    ForEach(model.choices) { choice in tile(choice, wide: wide) }
                    if model.choices.count % 2 == 1 { addTile(wide: wide, fullRow: false) }
                }
                if model.choices.count % 2 == 0 { addTile(wide: wide, fullRow: true) }
                if let notice = notice {
                    Label(notice, systemImage: "checkmark.circle.fill")
                        .font(.footnote).foregroundColor(LayoutStyle.success)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .accessibilityAddTraits(.updatesFrequently)
                }
            }.padding(.bottom, dockHeight + 16)
        }
    }

    private func tile(_ choice: LayoutChoice, wide: Bool) -> some View {
        let selected = choice.id == model.selectedLayoutKey
        let inUse = choice.id == model.library.appliedKey
        return Button { model.selectLayout(choice.id) } label: {
            VStack(alignment: .leading, spacing: 8) {
                LayoutThumbnail(model: model, choice: choice)
                    .frame(maxWidth: .infinity).frame(height: wide ? 148 : 88)
                    .overlay(alignment: .topLeading) {
                        VStack(alignment: .leading, spacing: 4) {
                            if inUse { badge("In use", symbol: "checkmark", warning: false) }
                            if choice.isDraft { badge("Unsaved", symbol: "circle.fill", warning: true) }
                        }.padding(6)
                    }
                    .overlay(alignment: .topTrailing) {
                        if selected && !inUse {
                            Image(systemName: "checkmark.circle.fill").foregroundColor(LayoutStyle.accent)
                                .padding(6).accessibilityHidden(true)
                        }
                    }
                VStack(alignment: .leading, spacing: 2) {
                    Text(choice.name).font(.system(.body).weight(selected ? .semibold : .medium)).lineLimit(1)
                    Text(choice.detail).font(.caption).foregroundColor(LayoutStyle.secondary).lineLimit(1)
                }
            }
            .padding(8).frame(maxWidth: .infinity, minHeight: wide ? 216 : 156, alignment: .topLeading)
            .background {
                RoundedRectangle(cornerRadius: 16).fill(selected ? LayoutStyle.raised : LayoutStyle.surface)
                    .layoutMorph("tile:" + choice.id, in: morph, enabled: !reduceMotion)
            }
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(selected ? LayoutStyle.accent : .clear, lineWidth: 2))
            .contentShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .simultaneousGesture(LongPressGesture().onEnded { _ in
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            model.selectLayout(choice.id)
        })
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(choice.name + (inUse ? ", in use" : "") + (choice.isDraft ? ", unsaved" : "") + ", " + choice.detail)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityHint("Select to preview this layout and show its actions")
    }

    private func badge(_ title: String, symbol: String, warning: Bool) -> some View {
        Label(title, systemImage: symbol).font(.system(size: 12, weight: .semibold))
            .padding(.horizontal, 9).frame(height: 24)
            .foregroundColor(warning ? LayoutStyle.warning : LayoutStyle.onAccent)
            .background(Capsule().fill(warning ? LayoutStyle.warningBackground : LayoutStyle.accent))
    }

    private func addTile(wide: Bool, fullRow: Bool) -> some View {
        Button { modal = .add } label: {
            Group {
                if fullRow {
                    HStack(spacing: 14) { addIcon; addCopy; Spacer(minLength: 0) }
                } else {
                    VStack(alignment: .leading, spacing: 10) { addIcon; addCopy }.frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(16).frame(maxWidth: .infinity, minHeight: fullRow ? (wide ? 72 : 64) : (wide ? 216 : 156))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(LayoutStyle.outline, style: StrokeStyle(lineWidth: 2, dash: [5, 4])))
            .contentShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain).disabled(!model.fullAccessConfirmed)
        .accessibilityLabel("Add a layout: new custom layout or import a code")
    }

    private var addIcon: some View {
        Image(systemName: "plus").font(.system(size: 22, weight: .medium))
            .frame(width: 40, height: 40).background(Circle().fill(LayoutStyle.button))
    }

    private var addCopy: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Add a layout").font(.body.weight(.medium))
            Text("New custom layout, or import a TK1- code").font(.caption).foregroundColor(LayoutStyle.secondary)
        }
    }

    private func actionDock(wide: Bool) -> some View {
        VStack(spacing: 8) {
            if deleting, let choice = model.selectedLayout {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Delete “\(choice.name)”?").font(.headline)
                    Text(choice.id == model.library.appliedKey ? "Standard will be used instead." : "This layout and its typing results will be removed.")
                        .font(.footnote).foregroundColor(LayoutStyle.secondary)
                    HStack {
                        Button("Cancel") { deleting = false }.buttonStyle(LayoutButtonStyle())
                        Spacer()
                        Button("Delete") { model.deleteSelected(); deleting = false }
                            .buttonStyle(LayoutButtonStyle()).foregroundColor(LayoutStyle.error)
                    }
                }.padding(16).background(RoundedRectangle(cornerRadius: 16).fill(LayoutStyle.surface))
            }
            if let choice = model.selectedLayout {
                HStack(spacing: 2) {
                    if wide && !choice.isCustom {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(choice.name).font(.body.weight(.semibold))
                            Text(choice.id == model.library.appliedKey ? "✓ In use" : "Previewing, not in use")
                                .font(.caption).foregroundColor(LayoutStyle.secondary)
                        }.padding(.leading, 16)
                        Spacer()
                    }
                    dockAction(choice.id == model.library.appliedKey ? "In use" : "Use", "checkmark", choice: choice,
                               disabled: choice.id == model.library.appliedKey || !model.fullAccessConfirmed, action: model.useSelectedLayout)
                    dockAction("Try", "keyboard", choice: choice, disabled: !model.fullAccessConfirmed) {
                        withAnimation(LayoutStyle.motion(reduce: reduceMotion, entering: true)) { model.beginTest() }
                    }
                    dockAction("Share", "square.and.arrow.up", choice: choice, action: model.shareSelected)
                    if choice.isCustom {
                        dockAction(model.editing ? "Done" : "Edit", "pencil", choice: choice, disabled: !model.fullAccessConfirmed) { model.editing.toggle() }
                        dockAction("Rename", "character.cursor.ibeam", choice: choice, disabled: !model.fullAccessConfirmed) { openName(.rename) }
                        dockAction("Delete", "trash", choice: choice, disabled: !model.fullAccessConfirmed) { deleting.toggle() }
                    }
                }
                .padding(4).frame(minHeight: wide ? 72 : 64)
                .background(RoundedRectangle(cornerRadius: wide ? 28 : 20).fill(LayoutStyle.raised))
                .accessibilityElement(children: .contain).accessibilityLabel("Actions for " + choice.name)
            }
        }
    }

    private func dockAction(_ title: String, _ symbol: String, choice: LayoutChoice, disabled: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: symbol).font(.system(size: 20))
                Text(title).font(.system(size: 12, weight: .medium)).lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, minHeight: 56).frame(minWidth: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain).foregroundColor(LayoutStyle.accent).disabled(disabled).opacity(disabled ? 0.6 : 1)
        .accessibilityLabel(title + ", " + choice.name)
    }

    private var importError: String? {
        guard model.importOpen else { return nil }
        if case .invalid(let error) = model.importStatus { return error }
        return nil
    }

    private var importPanel: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Button(action: model.cancelImport) { Image(systemName: "arrow.left").frame(width: 44, height: 44) }
                    .accessibilityLabel("Back to layouts")
                Text("Import layout").font(.title3.weight(.medium))
                Spacer()
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("Layout code").font(.caption).foregroundColor(LayoutStyle.accent)
                TextEditor(text: $model.importCode).font(.system(.subheadline, design: .monospaced))
                    .textInputAutocapitalization(.never).disableAutocorrection(true)
                    .frame(height: 90).padding(8)
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(importError == nil ? LayoutStyle.outline : LayoutStyle.error, lineWidth: 1.5))
                    .accessibilityLabel("Layout code. Paste a TK1- code")
                if let error = importError {
                    Label(error, systemImage: "exclamationmark.circle").font(.caption).foregroundColor(LayoutStyle.error)
                } else if let grid = model.importedGrid {
                    Label("Valid TK1 code · \(grid.rows) rows · \(grid.items.count) keys", systemImage: "checkmark")
                        .font(.caption).foregroundColor(LayoutStyle.success)
                } else {
                    Text("Codes start with TK1-. Paste one you received.").font(.caption).foregroundColor(LayoutStyle.secondary)
                }
            }
            Button { model.importCode = UIPasteboard.general.string ?? "" } label: {
                Label(importError == nil ? "Paste from clipboard" : "Paste again", systemImage: "doc.on.clipboard")
            }.buttonStyle(LayoutButtonStyle())
            if let grid = model.importedGrid {
                HStack(spacing: 12) {
                    LayoutThumbnail(model: model, choice: LayoutChoice(id: "import", name: model.importName, gridJSON: grid.toJSON(), template: nil))
                        .frame(width: 120, height: 72)
                    LayoutNameField(name: $model.importName, title: "Name")
                }.padding(12).background(RoundedRectangle(cornerRadius: 16).fill(LayoutStyle.surface))
            } else {
                Label("Need a code? Pick a layout, tap Share, and send the TK1- code it gives you.", systemImage: "square.and.arrow.up")
                    .font(.footnote).padding(16).background(RoundedRectangle(cornerRadius: 16).fill(LayoutStyle.surface))
            }
            HStack {
                Button("Cancel", action: model.cancelImport).buttonStyle(LayoutButtonStyle())
                Button(action: model.addImportedLayout) { Label("Add layout", systemImage: "plus").frame(maxWidth: .infinity) }
                    .buttonStyle(LayoutButtonStyle(primary: true))
                    .disabled(model.importedGrid == nil || !model.fullAccessConfirmed)
                    .opacity(model.importedGrid == nil ? 0.45 : 1)
            }.padding(.top, 12)
        }
    }

    @ViewBuilder private func modalView(_ kind: LayoutModal) -> some View {
        ZStack(alignment: kind == .add ? .bottom : .center) {
            Color.black.opacity(0.6).ignoresSafeArea().onTapGesture { modal = nil }
                .accessibilityHidden(true)
            if kind == .add {
                VStack(alignment: .leading, spacing: 12) {
                    Capsule().fill(LayoutStyle.outline).frame(width: 32, height: 4).frame(maxWidth: .infinity)
                    HStack {
                        Text("Add a layout").font(.title3.weight(.medium))
                        Spacer()
                        Button { modal = nil } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }.accessibilityLabel("Close")
                    }
                    addOption(model.library.draft == nil ? "New custom layout" : "Save first",
                              subtitle: model.library.draft == nil ? "Start from \(model.selectedLayout?.name ?? "Standard") and move the keys yourself." : "Save “Custom” first. You can keep one draft at a time.",
                              symbol: model.library.draft == nil ? "pencil" : "exclamationmark.triangle") {
                        if model.library.draft == nil { modal = nil; model.startCustom() }
                        else { openName(.saveFirst) }
                    }
                    addOption("Import from code", subtitle: "Paste a TK1- code someone shared with you.", symbol: "square.and.arrow.down") {
                        modal = nil
                        model.prepareImport()
                    }
                }
                .padding(24).frame(maxWidth: 640)
                .background(RoundedRectangle(cornerRadius: 28).fill(LayoutStyle.raised))
                .padding(.horizontal, 8).transition(.opacity)
            } else {
                LayoutNameDialog(name: $name, saveFirst: kind == .saveFirst, onCancel: { modal = nil }) {
                    if kind == .saveFirst {
                        _ = model.saveDraft(name: name)
                        model.startCustom()
                    } else { model.renameSelected(name) }
                    notice = "Saved as “\(LayoutLibrary.name(name))”"
                    modal = nil
                }
                .padding(16).frame(maxWidth: 388)
            }
        }
        .accessibilityElement(children: .contain)
    }

    private func addOption(_ title: String, subtitle: String, symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: symbol).font(.title3).frame(width: 48, height: 48).background(Circle().fill(LayoutStyle.button))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.medium))
                    Text(subtitle).font(.subheadline).foregroundColor(LayoutStyle.secondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
            }.frame(minHeight: 72).contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func openName(_ kind: LayoutModal) {
        name = kind == .saveFirst ? "Custom" : (model.selectedLayout?.name ?? "Custom")
        modal = kind
    }

    private func closeTest() {
        withAnimation(LayoutStyle.motion(reduce: reduceMotion, entering: false)) { model.endTest() }
    }
}

struct LayoutNameField: View {
    @Binding var name: String
    var title = "Layout name"
    var autoFocus = false
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption).foregroundColor(LayoutStyle.accent)
            TextField("Custom", text: $name).focused($focused).frame(minHeight: 44)
                .padding(.horizontal, 12)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(LayoutStyle.accent, lineWidth: 1.5))
                .onChange(of: name) { if $0.count > 24 { name = String($0.prefix(24)) } }
                .accessibilityLabel(title)
            Text("\(name.count) / 24").font(.caption).foregroundColor(LayoutStyle.secondary).frame(maxWidth: .infinity, alignment: .trailing)
        }
        .onAppear { if autoFocus { focused = true } }
    }
}

private struct LayoutNameDialog: View {
    @Binding var name: String
    let saveFirst: Bool
    let onCancel: () -> Void
    let onSave: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(saveFirst ? "Save this layout first" : "Rename layout").font(.title2)
            if saveFirst {
                Text("You can have one Custom layout at a time. Save this one as a tile, then start a new one.")
                    .font(.subheadline).foregroundColor(LayoutStyle.secondary)
            }
            LayoutNameField(name: $name, autoFocus: true)
            if saveFirst { Text("Keep it or give it a name.").font(.caption).foregroundColor(LayoutStyle.secondary) }
            HStack {
                Spacer(minLength: 0)
                Button("Cancel", action: onCancel).buttonStyle(LayoutButtonStyle())
                Button(saveFirst ? "Save and continue" : "Save", action: onSave).buttonStyle(LayoutButtonStyle(primary: true))
            }
        }
        .padding(24).background(RoundedRectangle(cornerRadius: 28).fill(LayoutStyle.raised))
    }
}

private struct LayoutDockHeight: PreferenceKey {
    static var defaultValue: CGFloat = 72
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}
