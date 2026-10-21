import SwiftUI

// All settings writes pass through this model so the controls and UIKit preview update together.
final class AppSettingsModel: ObservableObject {
    enum Sheet: String, Identifiable {
        case guide, fullAccess, share, about
        var id: String { rawValue }
    }

    let group: UserDefaults
    let settings: KeyboardSettings
    let library: LayoutLibrary
    @Published var grid: KeyGrid
    @Published private(set) var selectedLayoutKey: String
    @Published private(set) var testing = false
    private(set) var trialChoice: LayoutChoice?
    @Published var importOpen = false
    @Published var importName = "Imported layout"
    @Published var editing = false
    @Published var landscape = false
    @Published var sheet: Sheet?
    @Published private(set) var fullAccessConfirmed = false
    @Published private(set) var keyboardAdded: Bool?
    @Published private(set) var fullAccessDismissed: Bool
    @Published private(set) var showReady = false
    @Published private(set) var revision = 0
    var shareCode = ""
    @Published var importCode = "" {
        didSet {
            guard importCode != oldValue else { return }
            importStatus = LayoutCodeStatus.read(importCode)
            if importOpen { grid = importedGrid?.copy() ?? settings.loadGrid() }
        }
    }
    @Published private(set) var importStatus: LayoutCodeStatus = .empty
    var importedGrid: KeyGrid? {
        if case .valid(let grid) = importStatus { return grid }
        return nil
    }
    var selectedLayout: LayoutChoice? { library.choice(selectedLayoutKey) }
    var choices: [LayoutChoice] { library.choices }

    func splitPreview(for choice: LayoutChoice) -> Bool? {
        return choice.split
    }

    var previewSplit: Bool? {
        guard !importOpen, let choice = testing ? trialChoice : selectedLayout else { return nil }
        return splitPreview(for: choice)
    }

    private var chordLibraries: [String: Chords] = [:]
    /// Dictionaries ship in the embedded keyboard extension, so the app needs no Predictor target dependency.
    func chordList() -> [Chord]? {
        let language = settings.currentLanguage
        if chordLibraries[language] == nil {
            let suffix = language == "en" ? "" : "_" + language
            let plugins = Bundle.main.builtInPlugInsURL.flatMap { try? FileManager.default.contentsOfDirectory(at: $0, includingPropertiesForKeys: nil) } ?? []
            let bundles = [Bundle.main] + plugins.compactMap { Bundle(url: $0) }
            guard let url = bundles.compactMap({ $0.url(forResource: "words" + suffix, withExtension: "txt") }).first,
                  let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
            let words = text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces).lowercased() }.filter { !$0.isEmpty }
            chordLibraries[language] = Chords(words)
        }
        let letters = Set(grid.items.filter { !$0.isStack }.compactMap { item -> Character? in
            guard item.keys[0].count == 1, let key = item.keys[0].lowercased().first, key.isLetter else { return nil }
            return key
        })
        return chordLibraries[language]?.forLetters(letters)
    }

    private var readyWork: DispatchWorkItem?

    init() {
        let store = UserDefaults(suiteName: KeyboardSettings.groupID) ?? .standard
        group = store
        let prefs = KeyboardSettings(tablet: UIDevice.current.userInterfaceIdiom == .pad, store: store)
        settings = prefs
        // Do not seed the shared domain before the extension has migrated its local settings.
        let ready = store.object(forKey: KeyboardSettings.fullAccessSeenKey) as? Date != nil
        let layouts = LayoutLibrary(settings: prefs, defaults: store, migrate: ready)
        library = layouts
        selectedLayoutKey = layouts.appliedKey
        grid = prefs.loadGrid()
        fullAccessDismissed = UserDefaults.standard.bool(forKey: "full_access_pill_dismissed")
    }

    func refresh() {
        // AppleKeyboards is not a documented API. Missing or unexpected data stays unknown.
        if let entries = UserDefaults.standard.array(forKey: "AppleKeyboards"),
           let keyboards = entries as? [String] {
            keyboardAdded = keyboards.contains { $0.contains("app.tailorkeys.keyboard") }
        } else {
            keyboardAdded = nil
        }
        fullAccessConfirmed = group.object(forKey: KeyboardSettings.fullAccessSeenKey) as? Date != nil
        if fullAccessConfirmed && sheet == .fullAccess { sheet = nil }
        if !fullAccessConfirmed {
            editing = false
            readyWork?.cancel()
            showReady = false
        }
        if keyboardAdded == true && fullAccessConfirmed && !UserDefaults.standard.bool(forKey: "setup_ready_shown") {
            UserDefaults.standard.set(true, forKey: "setup_ready_shown")
            showReady = true
            readyWork?.cancel()
            let work = DispatchWorkItem { [weak self] in
                withAnimation(UIAccessibility.isReduceMotionEnabled ? nil : .easeOut(duration: 0.2)) { self?.showReady = false }
            }
            readyWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: work)
        }
        if !testing {
            let previousApplied = library.appliedKey
            library.reload(migrate: fullAccessConfirmed)
            if selectedLayoutKey == previousApplied || selectedLayout == nil { selectedLayoutKey = library.appliedKey }
            if !editing { updatePreview() }
        }
        changed()
    }

    func dismissFullAccess() {
        UserDefaults.standard.set(true, forKey: "full_access_pill_dismissed")
        fullAccessDismissed = true
    }

    func changed() { revision += 1 }

    func bool(_ key: String, default value: Bool) -> Binding<Bool> {
        Binding(get: { self.settings.bool(key, value) }, set: { on in
            guard self.fullAccessConfirmed else { return }
            self.settings.set(key, on)
            self.changed()
        })
    }

    func integer(_ pref: IntPref, set: ((Int) -> Void)? = nil) -> Binding<Double> {
        Binding(get: { Double(self.settings.int(pref)) }, set: { value in
            guard self.fullAccessConfirmed else { return }
            let v = Int(value.rounded())
            if let set = set { set(v) } else { self.settings.set(pref, v) }
            self.changed()
        })
    }

    func saveGrid(_ grid: KeyGrid) {
        guard fullAccessConfirmed else { return }
        // Editor changes belong to their tile. Previewing/editing an unused tile never applies it.
        library.update(selectedLayoutKey, grid: grid)
        self.grid = grid
        changed()
    }

    private func updatePreview() {
        grid = importOpen ? (importedGrid?.copy() ?? settings.loadGrid()) : (selectedLayout?.grid ?? settings.loadGrid())
    }

    func previewSelectedLayout() { if !testing { updatePreview() } }
    func previewAppliedLayout() { editing = false; grid = settings.loadGrid() }

    func selectLayout(_ key: String) {
        guard library.choice(key) != nil, !testing else { return }
        editing = false
        selectedLayoutKey = key
        updatePreview()
    }

    func useSelectedLayout() { useLayout(selectedLayoutKey) }

    private func useLayout(_ key: String) {
        guard fullAccessConfirmed, library.choice(key) != nil else { return }
        library.use(key, landscape: UIScreen.main.bounds.width > UIScreen.main.bounds.height)
        changed()
    }

    func startCustom() {
        guard fullAccessConfirmed, library.startDraft(from: selectedLayoutKey) else { return }
        selectedLayoutKey = "draft"
        updatePreview()
        editing = true
        changed()
    }

    @discardableResult func saveDraft(name: String) -> String? {
        guard fullAccessConfirmed, let key = library.saveDraft(name: name) else { return nil }
        if selectedLayoutKey == "draft" { selectedLayoutKey = key; updatePreview() }
        changed()
        return key
    }

    func renameSelected(_ name: String) {
        guard fullAccessConfirmed else { return }
        if selectedLayoutKey == "draft" { _ = saveDraft(name: name) }
        else { library.rename(selectedLayoutKey, name: name); changed() }
    }

    func deleteSelected() {
        guard fullAccessConfirmed else { return }
        editing = false
        let wasApplied = selectedLayoutKey == library.appliedKey
        library.delete(selectedLayoutKey)
        if wasApplied { useLayout(library.appliedKey) }
        selectedLayoutKey = library.appliedKey
        updatePreview()
        changed()
    }

    func shareSelected() {
        guard let choice = selectedLayout else { return }
        shareCode = choice.grid.toCode()
        sheet = .share
    }

    func beginTest() {
        guard fullAccessConfirmed, let choice = selectedLayout, library.beginTrial(choice.id, landscape: UIScreen.main.bounds.width > UIScreen.main.bounds.height) else { return }
        editing = false
        trialChoice = choice
        grid = choice.grid
        testing = true
    }

    func endTest() {
        library.restoreTrial()
        testing = false
        updatePreview()
        changed()
    }

    func keepTrialLayout() {
        guard testing, let choice = trialChoice else { return }
        useLayout(choice.id)
    }

    func recordTest(_ result: TypingResult) {
        guard testing, let choice = trialChoice else { return }
        library.record(result, for: choice.id)
        changed()
    }

    func setLanguages(_ enabled: [String]) {
        guard fullAccessConfirmed else { return }
        settings.setLanguages(enabled)
        library.reload()
        updatePreview()
        changed()
    }

    func setNumberRow(_ on: Bool) -> Bool {
        guard fullAccessConfirmed else { return false }
        let g = settings.loadGrid()
        guard g.setNumberRow(on) else { return false }
        settings.saveGrid(g)
        if let choice = library.choice(library.appliedKey), choice.isCustom { library.update(choice.id, grid: g) }
        library.reload()
        updatePreview()
        changed()
        return true
    }

    func prepareImport() {
        editing = false
        importCode = ""
        importName = "Imported layout"
        importStatus = .empty
        importOpen = true
        updatePreview()
    }

    func cancelImport() {
        importOpen = false
        updatePreview()
    }

    func addImportedLayout() {
        guard fullAccessConfirmed, let grid = importedGrid else { return }
        guard let key = library.add(grid.copy(), name: importName, split: settings.split(UIScreen.main.bounds.width > UIScreen.main.bounds.height)) else { return }
        importOpen = false
        selectedLayoutKey = key
        updatePreview()
        changed()
    }

    func reset() {
        guard fullAccessConfirmed else { return }
        editing = false
        library.restoreTrial()
        settings.resetAll()
        library.reload()
        selectedLayoutKey = library.appliedKey
        updatePreview()
        changed()
    }

    struct SizePreset {
        let name: String
        let h: Int
        let w: Int
        let align: Align
        let split: Bool
        let gap: Int
    }

    // Keep these values and the detection criteria in step with SettingsPanel's presets.
    var sizePresets: [SizePreset] {
        landscape
        ? [SizePreset(name: "Small", h: 49, w: 85, align: .center, split: false, gap: 0),
           SizePreset(name: "Default", h: 58, w: 100, align: .center, split: false, gap: 0),
           SizePreset(name: "Large", h: 69, w: 100, align: .center, split: false, gap: 0),
           SizePreset(name: "Left hand", h: 58, w: 55, align: .left, split: false, gap: 0),
           SizePreset(name: "Right hand", h: 58, w: 55, align: .right, split: false, gap: 0),
           SizePreset(name: "Split", h: 58, w: 100, align: .center, split: true, gap: 22)]
        : [SizePreset(name: "Small", h: 37, w: 90, align: .center, split: false, gap: 0),
           SizePreset(name: "Default", h: 44, w: 100, align: .center, split: false, gap: 0),
           SizePreset(name: "Large", h: 53, w: 100, align: .center, split: false, gap: 0),
           SizePreset(name: "Left hand", h: 46, w: 78, align: .left, split: false, gap: 0),
           SizePreset(name: "Right hand", h: 46, w: 78, align: .right, split: false, gap: 0),
           SizePreset(name: "Split", h: 44, w: 100, align: .center, split: true, gap: 16)]
    }

    var sizeTitle: String {
        let p = settings.profile(landscape)
        return sizePresets.first {
            $0.h == p.heightPercent && $0.w == p.widthPercent && $0.align == p.align && $0.split == p.split
        }?.name ?? "Custom"
    }

    func applySize(_ preset: SizePreset) {
        guard fullAccessConfirmed else { return }
        settings.setFor(landscape, Prefs.height, preset.h)
        settings.setFor(landscape, Prefs.width, preset.w)
        settings.setAlignFor(landscape, preset.align)
        settings.setSplitFor(landscape, preset.split)
        if preset.split {
            settings.setFor(landscape, Prefs.splitGap, preset.gap)
            settings.setFor(landscape, Prefs.tilt, 0)
        }
        changed()
    }
}
