import Foundation

struct SavedLayout: Identifiable {
    let id: String
    var name: String
    var gridJSON: String
    var key: String { "saved:" + id }
}

struct LayoutChoice: Identifiable {
    let id: String
    let name: String
    let gridJSON: String
    let template: Template?
    var isCustom: Bool { template == nil }
    var isDraft: Bool { id == "draft" }
    // Every choice is constructed from a validated grid, never an unchecked stored string.
    var grid: KeyGrid { KeyGrid.fromJSON(gridJSON)! }
    var detail: String {
        switch template {
        case .standard?: return grid.rows == 5 ? "QWERTY · number row" : "QWERTY · 4 rows"
        case .split?: return "Split · slight fan"
        case .thumbs?: return "Large bottom keys"
        case nil: return "\(isDraft ? "Unsaved" : "Custom") · \(grid.rows) rows"
        }
    }
}

struct TypingResult: Codable {
    let wpm: Double
    let accuracy: Double
    let at: Double // Epoch milliseconds, shared with Android.
    var isValid: Bool { wpm.isFinite && wpm >= 0 && accuracy.isFinite && (0...1).contains(accuracy) && at.isFinite && at >= 0 }
}

/// The wire format deliberately contains grid OBJECTS, not escaped grid JSON strings.
/// `layout_selected` is the applied tile; browsing selection is only app state.
final class LayoutLibrary {
    private let defaults: UserDefaults
    private let settings: KeyboardSettings
    private(set) var saved: [SavedLayout] = []
    private(set) var draft: String?
    private(set) var appliedKey = "preset:STANDARD"
    private(set) var results: [String: [TypingResult]] = [:]

    init(settings: KeyboardSettings, defaults: UserDefaults, migrate: Bool = true, recoverTrial: Bool = true) {
        self.settings = settings
        self.defaults = defaults
        if recoverTrial { restoreTrial() } // The app recovers; the IME must preserve an active typing trial.
        reload(migrate: migrate)
    }

    static func presetKey(_ template: Template) -> String {
        switch template {
        case .standard: return "preset:STANDARD"
        case .split: return "preset:SPLIT"
        case .thumbs: return "preset:THUMBS"
        }
    }

    static func name(_ value: String, fallback: String = "Custom") -> String {
        let clean = value.components(separatedBy: .newlines).joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        return String((clean.isEmpty ? fallback : clean).prefix(24))
    }

    static func storedJSON(_ grid: KeyGrid) -> String? {
        let json = grid.toJSON()
        // Geometry can be sound while a large stack exceeds the bounded JSON format.
        return grid.isSound && KeyGrid.fromJSON(json) != nil ? json : nil
    }

    static func sameGrid(_ a: KeyGrid, _ b: KeyGrid, includingFaces: Bool = true) -> Bool {
        let left = a.items.sorted { $0.r == $1.r ? $0.c < $1.c : $0.r < $1.r }
        let right = b.items.sorted { $0.r == $1.r ? $0.c < $1.c : $0.r < $1.r }
        return a.rows == b.rows && left.count == right.count && zip(left, right).allSatisfy {
            $0.r == $1.r && $0.c == $1.c && $0.w == $1.w && $0.keys == $1.keys && (!includingFaces || $0.idx == $1.idx)
        }
    }

    static func matchingPreset(_ grid: KeyGrid) -> Template? {
        for template in Template.allCases {
            for language in Prefs.languageCodes {
                let candidate = KeyGrid.template(template, language)
                if grid.rows == 5 { candidate.setNumberRow(true) }
                if sameGrid(candidate, grid, includingFaces: false) { return template }
            }
        }
        return nil
    }

    var choices: [LayoutChoice] {
        var values = Template.allCases.map { template -> LayoutChoice in
            let grid = KeyGrid.template(template, settings.currentLanguage)
            // A trial can have a different row count without changing the library's presets.
            let rows = KeyGrid.fromJSON(defaults.string(forKey: Prefs.layoutTrialPrevious))?.rows
            if rows.map({ $0 == 5 }) ?? settings.numberRow { grid.setNumberRow(true) }
            return LayoutChoice(id: Self.presetKey(template), name: template.title, gridJSON: grid.toJSON(), template: template)
        }
        values += saved.map { LayoutChoice(id: $0.key, name: $0.name, gridJSON: $0.gridJSON, template: nil) }
        if let draft = draft { values.append(LayoutChoice(id: "draft", name: "Custom", gridJSON: draft, template: nil)) }
        return values
    }

    func choice(_ key: String) -> LayoutChoice? { choices.first { $0.id == key } }

    var quickTiles: [QuickLayoutTileState] { choices.map { QuickLayoutTileState(choice: $0, inUse: $0.id == appliedKey) } }
    var needsSaveFirst: Bool { draft != nil }

    func nextName(_ prefix: String) -> String {
        let names = Set(saved.map { $0.name.lowercased() })
        var number = 1
        while names.contains("\(prefix) \(number)".lowercased()) { number += 1 }
        return "\(prefix) \(number)"
    }

    /// Presets always fork. Callers show Save first when an existing draft prevents a copy.
    @discardableResult func quickEdit(_ key: String, newCopy: Bool) -> String? {
        guard let source = choice(key) else { return nil }
        if newCopy || !source.isCustom {
            guard startDraft(from: key) else { return nil }
            use("draft")
            return "draft"
        }
        use(key)
        return key
    }

    func importClipboard(_ text: String?) -> ClipboardLayoutResult {
        guard let text = text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .empty }
        guard let decoded = KeyGrid.decodeCode(text),
              let key = add(decoded.grid, name: decoded.name ?? nextName("Imported")) else { return .invalid }
        return .added(key)
    }

    func reload(migrate: Bool = true) {
        saved = []
        var ids = Set<String>()
        if let text = defaults.string(forKey: Prefs.layoutsSaved),
           let array = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [[String: Any]] {
            for item in array {
                guard let id = item["id"] as? String, UUID(uuidString: id) != nil, ids.insert(id).inserted,
                      let name = item["name"] as? String, let object = item["grid"] as? [String: Any],
                      let data = try? JSONSerialization.data(withJSONObject: object),
                      let grid = KeyGrid.fromJSON(String(decoding: data, as: UTF8.self)),
                      let json = Self.storedJSON(grid) else { continue }
                saved.append(SavedLayout(id: id, name: Self.name(name), gridJSON: json))
            }
        }
        draft = KeyGrid.fromJSON(defaults.string(forKey: Prefs.layoutDraft)).flatMap { Self.storedJSON($0) }
        results = [:]
        if let text = defaults.string(forKey: Prefs.typingResults),
           let decoded = try? JSONDecoder().decode([String: [TypingResult]].self, from: Data(text.utf8)) {
            results = decoded.mapValues { Array($0.filter(\.isValid).suffix(20)) }
        }
        appliedKey = defaults.string(forKey: Prefs.layoutSelected) ?? ""
        if migrate { reconcileApplied() }
        else if choice(appliedKey) == nil { appliedKey = Self.presetKey(Self.matchingPreset(settings.loadGrid()) ?? .standard) }
    }

    private func reconcileApplied() {
        if defaults.string(forKey: Prefs.layoutTrialPrevious) != nil, choice(appliedKey) != nil { return }
        let applied = settings.loadGrid()
        if let current = choice(appliedKey), Self.sameGrid(current.grid, applied, includingFaces: false) { return }
        if let preset = Self.matchingPreset(applied) {
            setAppliedKey(Self.presetKey(preset))
        } else if let entry = saved.first(where: { KeyGrid.fromJSON($0.gridJSON).map { Self.sameGrid($0, applied) } == true }) {
            setAppliedKey(entry.key)
        } else if let draft = draft, let grid = KeyGrid.fromJSON(draft), Self.sameGrid(grid, applied) {
            setAppliedKey("draft")
        } else if defaults.string(forKey: Prefs.layoutSelected) == nil {
            if let key = add(applied, name: "My layout") { setAppliedKey(key) }
        } else {
            // Edits made from the keyboard's own editor also become a visible tile.
            // Preserve an existing draft before adopting that externally edited layout.
            if draft != nil { _ = saveDraft(name: "Custom") }
            draft = applied.toJSON()
            defaults.set(draft, forKey: Prefs.layoutDraft)
            setAppliedKey("draft")
        }
    }

    private func setAppliedKey(_ key: String) {
        appliedKey = key
        defaults.set(key, forKey: Prefs.layoutSelected)
    }

    private func persistSaved() {
        let array: [[String: Any]] = saved.compactMap { item in
            guard let grid = try? JSONSerialization.jsonObject(with: Data(item.gridJSON.utf8)) else { return nil }
            return ["id": item.id, "name": item.name, "grid": grid]
        }
        if let data = try? JSONSerialization.data(withJSONObject: array, options: [.sortedKeys]) {
            defaults.set(String(decoding: data, as: UTF8.self), forKey: Prefs.layoutsSaved)
        }
    }

    @discardableResult func add(_ grid: KeyGrid, name: String) -> String? {
        guard let json = Self.storedJSON(grid) else { return nil }
        let entry = SavedLayout(id: UUID().uuidString.lowercased(), name: Self.name(name, fallback: "Imported layout"), gridJSON: json)
        saved.append(entry)
        persistSaved()
        return entry.key
    }

    @discardableResult func startDraft(from key: String) -> Bool {
        guard draft == nil, let source = choice(key) else { return false }
        draft = source.gridJSON
        defaults.set(draft, forKey: Prefs.layoutDraft)
        return true
    }

    @discardableResult func saveDraft(name: String) -> String? {
        guard let draft = draft, let grid = KeyGrid.fromJSON(draft) else { return nil }
        guard let key = add(grid, name: name) else { return nil }
        if appliedKey == "draft" { setAppliedKey(key) }
        let history = results.removeValue(forKey: "draft") ?? []
        if !history.isEmpty { results[key] = history; persistResults() }
        self.draft = nil
        defaults.removeObject(forKey: Prefs.layoutDraft)
        return key
    }

    func rename(_ key: String, name: String) {
        guard let index = saved.firstIndex(where: { $0.key == key }) else { return }
        saved[index].name = Self.name(name)
        persistSaved()
    }

    func update(_ key: String, grid: KeyGrid) {
        guard let json = Self.storedJSON(grid) else { return }
        if key == "draft" {
            draft = json
            defaults.set(draft, forKey: Prefs.layoutDraft)
        } else if let index = saved.firstIndex(where: { $0.key == key }) {
            saved[index].gridJSON = json
            persistSaved()
        } else { return }
        if appliedKey == key { settings.saveGrid(grid) }
    }

    func use(_ key: String) {
        guard let layout = choice(key) else { return }
        settings.saveGrid(layout.grid)
        setAppliedKey(key)
        defaults.removeObject(forKey: Prefs.layoutTrialPrevious)
        defaults.synchronize()
    }

    func delete(_ key: String) {
        guard let layout = choice(key), layout.isCustom else { return }
        if appliedKey == key { use(Self.presetKey(.standard)) }
        if key == "draft" { draft = nil; defaults.removeObject(forKey: Prefs.layoutDraft) }
        else { saved.removeAll { $0.key == key }; persistSaved() }
        results.removeValue(forKey: key)
        persistResults()
    }

    @discardableResult func beginTrial(_ key: String) -> Bool {
        guard let layout = choice(key) else { return false }
        restoreTrial()
        if key != appliedKey {
            defaults.set(settings.loadGrid().toJSON(), forKey: Prefs.layoutTrialPrevious)
            // Flush the recovery record before the extension can observe the trial grid.
            defaults.synchronize()
            settings.saveGrid(layout.grid)
        }
        return true
    }

    func restoreTrial() {
        guard let previous = defaults.string(forKey: Prefs.layoutTrialPrevious) else { return }
        if let grid = KeyGrid.fromJSON(previous) { settings.saveGrid(grid) }
        defaults.removeObject(forKey: Prefs.layoutTrialPrevious)
        defaults.synchronize()
    }

    func average(_ key: String) -> Double? {
        guard let history = results[key], !history.isEmpty else { return nil }
        return history.reduce(0) { $0 + $1.wpm } / Double(history.count)
    }

    func record(_ result: TypingResult, for key: String) {
        guard result.isValid, choice(key) != nil else { return }
        results[key] = Array(((results[key] ?? []) + [result]).suffix(20))
        persistResults()
    }

    private func persistResults() {
        if let data = try? JSONEncoder().encode(results) {
            defaults.set(String(decoding: data, as: UTF8.self), forKey: Prefs.typingResults)
        }
    }
}

enum LayoutCodeStatus {
    case empty, valid(KeyGrid), invalid(String)

    static func read(_ text: String) -> LayoutCodeStatus {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .empty }
        if let grid = KeyGrid.fromCode(text), LayoutLibrary.storedJSON(grid) != nil { return .valid(grid) }
        if let range = text.range(of: #"TK[2-9][0-9]*-"#, options: .regularExpression)
            ?? text.range(of: #"TK1[0-9]+-"#, options: .regularExpression), !range.isEmpty {
            return .invalid("This code is from a newer version of TailorKeys")
        }
        return .invalid("This code can’t be read. Copy the whole TK1- code and paste it again.")
    }
}

struct QuickLayoutTileState {
    let choice: LayoutChoice
    let inUse: Bool
    var label: String { choice.name + (inUse ? ", in use" : "") + (choice.isDraft ? ", unsaved" : "") }
    var bold: Bool { inUse }
    var outlineWidth: Double { inUse ? 2 : 1 }
}

enum ClipboardLayoutResult { case empty, invalid, added(String) }
