import SwiftUI

// All settings writes pass through this model so the controls and UIKit preview update together.
final class AppSettingsModel: ObservableObject {
    enum Sheet: String, Identifiable {
        case guide, fullAccess, importLayout, share, about
        var id: String { rawValue }
    }

    let group: UserDefaults
    let settings: KeyboardSettings
    @Published var grid: KeyGrid
    @Published var editing = false
    @Published var landscape = false
    @Published var sheet: Sheet?
    @Published private(set) var fullAccessConfirmed = false
    @Published private(set) var keyboardAdded: Bool?
    @Published private(set) var fullAccessDismissed: Bool
    @Published private(set) var showReady = false
    @Published private(set) var revision = 0
    var shareCode = ""
    var importCode = ""

    private var readyWork: DispatchWorkItem?

    init() {
        guard let store = UserDefaults(suiteName: KeyboardSettings.groupID) else {
            fatalError("The TailorKeys App Group store is unavailable.")
        }
        group = store
        let prefs = KeyboardSettings(tablet: UIDevice.current.userInterfaceIdiom == .pad, store: store)
        settings = prefs
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
                withAnimation(.easeOut(duration: 0.2)) { self?.showReady = false }
            }
            readyWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: work)
        }
        grid = settings.loadGrid()
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
        settings.saveGrid(grid)
        self.grid = grid
        changed()
    }

    func setLanguages(_ enabled: [String]) {
        guard fullAccessConfirmed else { return }
        settings.setLanguages(enabled)
        grid = settings.loadGrid()
        changed()
    }

    func applyTemplate(_ template: Template) {
        guard fullAccessConfirmed else { return }
        let g = KeyGrid.template(template, settings.currentLanguage)
        if settings.numberRow { g.setNumberRow(true) }
        saveGrid(g)
        if template.split { settings.setSplitFor(landscape, true) }
        changed()
    }

    var layoutTitle: String {
        for template in Template.allCases {
            let g = KeyGrid.template(template, settings.currentLanguage)
            if settings.numberRow { g.setNumberRow(true) }
            if Self.sameJSON(g.toJSON(), grid.toJSON()) { return template.title }
        }
        return "Custom"
    }

    private static func sameJSON(_ a: String, _ b: String) -> Bool {
        // JSONEncoder's dictionary key order is unspecified; compare the decoded JSON.
        guard let left = try? JSONSerialization.jsonObject(with: Data(a.utf8)),
              let right = try? JSONSerialization.jsonObject(with: Data(b.utf8)),
              let lhs = left as? NSDictionary, let rhs = right as? NSDictionary else { return false }
        return lhs.isEqual(rhs)
    }

    func setNumberRow(_ on: Bool) -> Bool {
        guard fullAccessConfirmed else { return false }
        let g = settings.loadGrid()
        guard g.setNumberRow(on) else { return false }
        saveGrid(g)
        return true
    }

    func prepareImport() {
        let text = UIPasteboard.general.string ?? ""
        importCode = KeyGrid.fromCode(text) != nil ? text : ""
        sheet = .importLayout
    }

    func reset() {
        guard fullAccessConfirmed else { return }
        editing = false
        settings.resetAll()
        grid = settings.loadGrid()
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
