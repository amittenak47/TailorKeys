import Foundation

struct SavedLayout: Identifiable {
    let id: String
    var name: String
    var gridJSON: String
    var split = false
    var settings: [String: Any]? = nil
    var key: String { "saved:" + id }
}

struct LayoutChoice: Identifiable {
    let id: String
    let name: String
    let gridJSON: String
    let template: Template?
    var storedSplit = false
    var settings: [String: Any]? = nil
    var split: Bool { template?.split ?? storedSplit }
    func splitFor(_ landscape: Bool) -> Bool { template?.split ?? (settings?[Prefs.splitKey(landscape)] as? Bool) ?? storedSplit }
    var isCustom: Bool { template == nil }
    var isDraft: Bool { id == "draft" }
    // Every choice is constructed from a validated grid, never an unchecked stored string.
    var grid: KeyGrid { KeyGrid.fromJSON(gridJSON)! }
    var hasSplitSettings: Bool { settings?["split_p"] is Bool && settings?["split_l"] is Bool }
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
    let keyboard: String
    init(wpm: Double, accuracy: Double, at: Double, keyboard: String = "tailorkeys") {
        self.wpm = wpm; self.accuracy = accuracy; self.at = at; self.keyboard = keyboard
    }
    private enum CodingKeys: String, CodingKey { case wpm, accuracy, at, keyboard }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        wpm = try values.decode(Double.self, forKey: .wpm)
        accuracy = try values.decode(Double.self, forKey: .accuracy)
        at = try values.decode(Double.self, forKey: .at)
        keyboard = try values.decodeIfPresent(String.self, forKey: .keyboard) ?? "tailorkeys"
    }
    var isValid: Bool { wpm.isFinite && wpm >= 0 && accuracy.isFinite && (0...1).contains(accuracy) && at.isFinite && at >= 0 }
}

struct KeyboardAverage {
    let keyboard: String
    let wpm: Double
    let accuracy: Double
    let tests: Int
    static func aggregate(_ results: [TypingResult]) -> [KeyboardAverage] {
        Dictionary(grouping: results, by: { $0.keyboard }).map { id, results in
            KeyboardAverage(keyboard: id, wpm: results.reduce(0) { $0 + $1.wpm } / Double(results.count),
                            accuracy: results.reduce(0) { $0 + $1.accuracy } / Double(results.count), tests: results.count)
        }.sorted { a, b in a.keyboard == "tailorkeys" ? b.keyboard != "tailorkeys" : (b.keyboard == "tailorkeys" ? false : a.keyboard < b.keyboard) }
    }
    static func comparisons(_ rows: [KeyboardAverage]) -> [KeyboardComparisonSummary] {
        guard let tailor = rows.first(where: { $0.keyboard == "tailorkeys" }) else { return [] }
        return rows.filter { $0.keyboard != "tailorkeys" }.map {
            KeyboardComparisonSummary(other: $0.keyboard, wpmDifference: tailor.wpm - $0.wpm, accuracyPoints: (tailor.accuracy - $0.accuracy) * 100)
        }
    }
}

struct KeyboardComparisonSummary {
    let other: String
    let wpmDifference: Double
    let accuracyPoints: Double
    var description: String {
        "TailorKeys is \(Int(abs(wpmDifference).rounded())) wpm \(wpmDifference >= 0 ? "faster" : "slower") on average, \(Int(abs(accuracyPoints).rounded())) points \(accuracyPoints >= 0 ? "more" : "less") accurate"
    }
}

/// The wire format deliberately contains grid OBJECTS, not escaped grid JSON strings.
/// `layout_selected` is the applied tile; browsing selection is only app state.
final class LayoutLibrary {
    private let defaults: UserDefaults
    private let settings: KeyboardSettings
    private(set) var saved: [SavedLayout] = []
    private(set) var draft: String?
    private(set) var draftSplit = false
    private(set) var draftSettings: [String: Any]?
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
        values += saved.map { LayoutChoice(id: $0.key, name: $0.name, gridJSON: $0.gridJSON, template: nil, storedSplit: $0.split, settings: $0.settings) }
        if let draft = draft { values.append(LayoutChoice(id: "draft", name: "Custom", gridJSON: draft, template: nil, storedSplit: draftSplit, settings: draftSettings)) }
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
    @discardableResult func quickEdit(_ key: String, newCopy: Bool, landscape: Bool = false) -> String? {
        guard let source = choice(key) else { return nil }
        if newCopy || !source.isCustom {
            guard startDraft(from: key) else { return nil }
            use("draft", landscape: landscape)
            return "draft"
        }
        use(key, landscape: landscape)
        return key
    }

    func importClipboard(_ text: String?) -> ClipboardLayoutResult {
        guard let text = text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .empty }
        guard let decoded = KeyGrid.decodeCode(text),
              let key = add(decoded.grid, name: decoded.name ?? nextName("Imported"), snapshot: decoded.settings) else { return .invalid }
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
                saved.append(SavedLayout(id: id, name: Self.name(name), gridJSON: json, split: item["split"] as? Bool ?? settings.split(false), settings: item["settings"] as? [String: Any]))
            }
        }
        // Older saved layouts acquire a stable split value before another layout is applied.
        if migrate, let text = defaults.string(forKey: Prefs.layoutsSaved),
           let array = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [[String: Any]],
           array.contains(where: { $0["split"] == nil }) { persistSaved() }
        draftSplit = defaults.string(forKey: Prefs.layoutDraftSplit).flatMap { Bool($0) } ?? settings.split(false)
        draft = KeyGrid.fromJSON(defaults.string(forKey: Prefs.layoutDraft)).flatMap { Self.storedJSON($0) }
        draftSettings = defaults.string(forKey: Prefs.layoutDraft).flatMap { Self.object($0)?["settings"] as? [String: Any] }
        if migrate, draft != nil, defaults.object(forKey: Prefs.layoutDraftSplit) == nil {
            defaults.set(String(draftSplit), forKey: Prefs.layoutDraftSplit)
        }
        results = [:]
        if let text = defaults.string(forKey: Prefs.typingResults),
           let decoded = try? JSONDecoder().decode([String: [TypingResult]].self, from: Data(text.utf8)) {
            results = decoded.mapValues { Self.recentResults($0.filter(\.isValid)) }
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
            draftSettings = settings.layoutSettings()
            draftSplit = settings.split(false)
            defaults.set(String(draftSplit), forKey: Prefs.layoutDraftSplit)
            persistDraft()
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
            var object: [String: Any] = ["id": item.id, "name": item.name, "grid": grid, "split": item.split]
            object["settings"] = item.settings
            return object
        }
        if let data = try? JSONSerialization.data(withJSONObject: array, options: [.sortedKeys]) {
            defaults.set(String(decoding: data, as: UTF8.self), forKey: Prefs.layoutsSaved)
        }
    }

    @discardableResult func add(_ grid: KeyGrid, name: String, split: Bool? = nil, snapshot: [String: Any]? = nil) -> String? {
        reload(migrate: false)
        guard let json = Self.storedJSON(grid) else { return nil }
        let entry = SavedLayout(id: UUID().uuidString.lowercased(), name: Self.name(name, fallback: "Imported layout"), gridJSON: json, split: split ?? settings.split(false), settings: snapshot ?? settings.layoutSettings())
        saved.append(entry)
        persistSaved()
        return entry.key
    }

    @discardableResult func startDraft(from key: String) -> Bool {
        reload(migrate: false)
        guard draft == nil, let source = choice(key) else { return false }
        draft = source.gridJSON
        draftSplit = source.split
        draftSettings = source.settings ?? settings.layoutSettings()
        if !source.isCustom { draftSettings?["split_p"] = source.split; draftSettings?["split_l"] = source.split }
        defaults.set(String(draftSplit), forKey: Prefs.layoutDraftSplit)
        persistDraft()
        return true
    }

    @discardableResult func saveDraft(name: String) -> String? {
        reload(migrate: false)
        guard let draft = draft, let grid = KeyGrid.fromJSON(draft) else { return nil }
        guard let key = add(grid, name: name, split: draftSplit, snapshot: draftSettings) else { return nil }
        if appliedKey == "draft" { setAppliedKey(key) }
        let history = results.removeValue(forKey: "draft") ?? []
        if !history.isEmpty { results[key] = history; persistResults() }
        self.draft = nil
        draftSettings = nil
        defaults.removeObject(forKey: Prefs.layoutDraft)
        defaults.removeObject(forKey: Prefs.layoutDraftSplit)
        return key
    }

    func rename(_ key: String, name: String) {
        reload(migrate: false)
        guard let index = saved.firstIndex(where: { $0.key == key }) else { return }
        saved[index].name = Self.name(name)
        persistSaved()
    }

    func update(_ key: String, grid: KeyGrid) {
        reload(migrate: false)
        guard let json = Self.storedJSON(grid) else { return }
        if key == "draft" {
            draft = json
            persistDraft()
        } else if let index = saved.firstIndex(where: { $0.key == key }) {
            saved[index].gridJSON = json
            persistSaved()
        } else { return }
        if appliedKey == key { settings.saveGrid(grid) }
    }

    func use(_ key: String, landscape: Bool = false) {
        reload(migrate: false)
        guard let layout = choice(key) else { return }
        if layout.isCustom, let snapshot = layout.settings { settings.applyLayoutSettings(snapshot) }
        if !layout.hasSplitSettings { settings.applyLayoutSplit(landscape, layout.split) }
        settings.saveGrid(layout.grid)
        setAppliedKey(key)
        defaults.removeObject(forKey: Prefs.layoutTrialPrevious)
        defaults.removeObject(forKey: Prefs.layoutTrialSplit)
        defaults.removeObject(forKey: Prefs.layoutTrialSettings)
        defaults.synchronize()
    }

    func delete(_ key: String) {
        reload(migrate: false)
        guard let layout = choice(key), layout.isCustom else { return }
        if appliedKey == key { use(Self.presetKey(.standard)) }
        if key == "draft" { draft = nil; defaults.removeObject(forKey: Prefs.layoutDraft); defaults.removeObject(forKey: Prefs.layoutDraftSplit) }
        else { saved.removeAll { $0.key == key }; persistSaved() }
        results.removeValue(forKey: key)
        persistResults()
    }

    @discardableResult func beginTrial(_ key: String, landscape: Bool = false) -> Bool {
        reload(migrate: false)
        guard let layout = choice(key) else { return false }
        restoreTrial()
        if key != appliedKey || layout.split != settings.split(landscape) ||
            (layout.settings.map { !NSDictionary(dictionary: $0).isEqual(to: settings.layoutSettings()) } ?? false) {
            defaults.set(settings.loadGrid().toJSON(), forKey: Prefs.layoutTrialPrevious)
            // Legacy entries and presets use the split override; custom snapshots include both orientations.
            if !layout.hasSplitSettings { defaults.set(String(layout.split), forKey: Prefs.layoutTrialSplit) }
            if let data = try? JSONSerialization.data(withJSONObject: settings.layoutSettings(), options: [.sortedKeys]) {
                defaults.set(String(decoding: data, as: UTF8.self), forKey: Prefs.layoutTrialSettings)
            }
            // Flush the recovery record before the extension can observe the trial grid.
            defaults.synchronize()
            if layout.isCustom, let snapshot = layout.settings { settings.applyLayoutSettings(snapshot) }
            settings.saveGrid(layout.grid)
        }
        return true
    }

    func restoreTrial() {
        guard let previous = defaults.string(forKey: Prefs.layoutTrialPrevious) else { return }
        if let text = defaults.string(forKey: Prefs.layoutTrialSettings), let snapshot = Self.object(text) { settings.applyLayoutSettings(snapshot) }
        if let grid = KeyGrid.fromJSON(previous) { settings.saveGrid(grid) }
        defaults.removeObject(forKey: Prefs.layoutTrialPrevious)
        defaults.removeObject(forKey: Prefs.layoutTrialSplit)
        defaults.removeObject(forKey: Prefs.layoutTrialSettings)
        defaults.synchronize()
    }

    private static func object(_ text: String) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any]
    }

    private func persistDraft() {
        guard let text = draft, var object = Self.object(text) else { return }
        object["settings"] = draftSettings
        if let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) {
            defaults.set(String(decoding: data, as: UTF8.self), forKey: Prefs.layoutDraft)
        }
    }

    private static func recentResults(_ results: [TypingResult]) -> [TypingResult] {
        Dictionary(grouping: results, by: { $0.keyboard }).values.flatMap { Array($0.suffix(20)) }.sorted { $0.at < $1.at }
    }
    func keyboardAverages() -> [KeyboardAverage] { KeyboardAverage.aggregate(results.values.flatMap { $0 }) }
    func average(_ key: String, keyboard: String = "tailorkeys") -> Double? {
        let history = (results[key] ?? []).filter { $0.keyboard == keyboard }
        guard !history.isEmpty else { return nil }
        return history.reduce(0) { $0 + $1.wpm } / Double(history.count)
    }

    func record(_ result: TypingResult, for key: String) {
        guard result.isValid, choice(key) != nil else { return }
        results[key] = Self.recentResults((results[key] ?? []) + [result])
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
