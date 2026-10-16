import Foundation

enum Align: String, CaseIterable { case left, center, right }

struct IntPref {
    let key: String
    let def: Int
    let min: Int
    let max: Int
}

/// Every setting with its default and range. Same keys and defaults as the Android app.
/// Stored in the App Group with Full Access, or in the keyboard's own defaults without it.
enum Prefs {
    static func height(_ l: Bool) -> IntPref { l ? IntPref(key: "height_l", def: 58, min: 24, max: 70) : IntPref(key: "height_p", def: 44, min: 24, max: 70) }
    static func width(_ l: Bool) -> IntPref { IntPref(key: l ? "width_l" : "width_p", def: 100, min: 50, max: 100) }
    static func bottomPadding(_ l: Bool) -> IntPref { IntPref(key: l ? "bottom_pad_l" : "bottom_pad_p", def: 0, min: 0, max: 48) }
    static func splitGap(_ l: Bool) -> IntPref { l ? IntPref(key: "split_gap_l", def: 22, min: 2, max: 40) : IntPref(key: "split_gap_p", def: 16, min: 2, max: 40) }
    static func tilt(_ l: Bool) -> IntPref { IntPref(key: l ? "tilt_l" : "tilt_p", def: 0, min: -10, max: 10) }
    static func spaceTilt(_ l: Bool) -> IntPref { IntPref(key: l ? "space_tilt_l" : "space_tilt_p", def: 0, min: -10, max: 10) }
    static func alignKey(_ l: Bool) -> String { l ? "align_l" : "align_p" }
    static func splitKey(_ l: Bool) -> String { l ? "split_l" : "split_p" }

    static let curve = IntPref(key: "curve", def: 70, min: 0, max: 100)
    static let keyGap = IntPref(key: "key_gap", def: 5, min: 0, max: 14)
    static let cornerRadius = IntPref(key: "corner_radius", def: 8, min: 0, max: 20)
    static let textScale = IntPref(key: "text_scale", def: 100, min: 60, max: 160)
    static let longPressMs = IntPref(key: "long_press_ms", def: 350, min: 150, max: 800)

    static let numberRow = "number_row"
    static let showHints = "show_hints"
    static let autoCap = "auto_cap"
    static let doubleSpace = "double_space_period"
    static let clicks = "haptics"
    static let keyTint = "key_tint"
    static let swipeTyping = "swipe_typing"
    static let chording = "chording"
    static let smartTargets = "smart_targets"
    static let lockSize = "lock_size"
    static let sameOrientations = "same_orientations"
    static let theme = "theme"
    static let layout = "layout"
    static let languages = "languages"
    static let currentLanguage = "current_language"
    static let languageCodes = ["en", "es", "de", "fr"]
    static let languageNames = ["en": "English", "es": "Español", "de": "Deutsch", "fr": "Français"]
}

struct SizeProfile {
    let heightPercent: Int
    let widthPercent: Int
    let align: Align
    let bottomPadding: Int
    let split: Bool
    let gap: Int
    let tilt: Int
    let spaceTilt: Int
}

final class KeyboardSettings {
    static let groupID = "group.app.tailorkeys"
    static let fullAccessSeenKey = "keyboard_seen_full_access"
    private static let migrationKey = "keyboard_settings_migrated"

    private var d: UserDefaults
    private let tablet: Bool
    /// Only the app preview overrides the orientation being displayed.
    var previewOrientation: Bool?

    init(tablet: Bool, store: UserDefaults = .standard) {
        self.tablet = tablet
        d = store
    }

    convenience init(tablet: Bool, hasFullAccess: Bool) {
        self.init(tablet: tablet, store: KeyboardSettings.keyboardStore(hasFullAccess: hasFullAccess))
    }

    func reloadStore(hasFullAccess: Bool) {
        d = KeyboardSettings.keyboardStore(hasFullAccess: hasFullAccess)
    }

    private static func keyboardStore(hasFullAccess: Bool) -> UserDefaults {
        guard hasFullAccess, let group = UserDefaults(suiteName: groupID) else { return .standard }
        if !group.bool(forKey: migrationKey) {
            // Inspect only the persistent domain: dictionaryRepresentation also includes system defaults.
            if (group.persistentDomain(forName: groupID) ?? [:]).isEmpty,
               let bundleID = Bundle.main.bundleIdentifier {
                for (key, value) in UserDefaults.standard.persistentDomain(forName: bundleID) ?? [:] {
                    group.set(value, forKey: key)
                }
            }
            group.set(true, forKey: migrationKey)
        }
        return group
    }

    private var deviceLanguage: String {
        let lang = Locale.preferredLanguages.first.flatMap { Locale(identifier: $0).languageCode } ?? "en"
        return Prefs.languageCodes.contains(lang) ? lang : "en"
    }
    var languages: [String] {
        let stored = d.string(forKey: Prefs.languages)?.components(separatedBy: ",") ?? [deviceLanguage]
        let enabled = Prefs.languageCodes.filter { stored.contains($0) }
        return enabled.isEmpty ? [deviceLanguage] : enabled
    }
    var currentLanguage: String {
        get { let value = d.string(forKey: Prefs.currentLanguage) ?? ""; return languages.contains(value) ? value : languages[0] }
        set { if languages.contains(newValue) { d.set(newValue, forKey: Prefs.currentLanguage) } }
    }
    func setLanguages(_ enabled: [String]) {
        let valid = Prefs.languageCodes.filter { enabled.contains($0) }
        guard !valid.isEmpty else { return }
        let current = currentLanguage
        d.set(valid.joined(separator: ","), forKey: Prefs.languages)
        currentLanguage = valid.contains(current) ? current : valid[0]
    }

    func int(_ p: IntPref) -> Int { min(max(d.object(forKey: p.key) as? Int ?? p.def, p.min), p.max) }
    func set(_ p: IntPref, _ v: Int) { d.set(min(max(v, p.min), p.max), forKey: p.key) }

    func bool(_ key: String, _ def: Bool) -> Bool { d.object(forKey: key) as? Bool ?? def }
    func set(_ key: String, _ v: Bool) { d.set(v, forKey: key) }

    var numberRow: Bool { bool(Prefs.numberRow, true) }
    var showHints: Bool { bool(Prefs.showHints, true) }
    var autoCap: Bool { bool(Prefs.autoCap, true) }
    var doubleSpace: Bool { bool(Prefs.doubleSpace, true) }
    var clicks: Bool { bool(Prefs.clicks, true) }
    var keyTint: Bool { bool(Prefs.keyTint, true) }
    var swipeTyping: Bool { bool(Prefs.swipeTyping, true) }
    var chording: Bool { bool(Prefs.chording, false) }
    var smartTargets: Bool { bool(Prefs.smartTargets, true) }
    var lockSize: Bool { bool(Prefs.lockSize, false) }
    var sameOrientations: Bool { bool(Prefs.sameOrientations, false) }

    func align(_ l: Bool) -> Align { Align(rawValue: d.string(forKey: Prefs.alignKey(l)) ?? "") ?? .center }
    /// Split defaults to on for iPads in landscape.
    func split(_ l: Bool) -> Bool { bool(Prefs.splitKey(l), l && tablet) }

    // Per-orientation setters write both orientations while "same for both" is on.
    private func orientations(_ l: Bool) -> [Bool] { sameOrientations ? [false, true] : [l] }
    func setFor(_ l: Bool, _ pref: (Bool) -> IntPref, _ v: Int) { for o in orientations(l) { set(pref(o), v) } }
    func setAlignFor(_ l: Bool, _ a: Align) { for o in orientations(l) { d.set(a.rawValue, forKey: Prefs.alignKey(o)) } }
    func setSplitFor(_ l: Bool, _ on: Bool) { for o in orientations(l) { d.set(on, forKey: Prefs.splitKey(o)) } }

    func linkOrientations(from l: Bool) {
        for pref in [Prefs.height, Prefs.width, Prefs.bottomPadding, Prefs.splitGap, Prefs.tilt, Prefs.spaceTilt] { set(pref(!l), int(pref(l))) }
        d.set(align(l).rawValue, forKey: Prefs.alignKey(!l))
        d.set(split(l), forKey: Prefs.splitKey(!l))
        set(Prefs.sameOrientations, true)
    }

    func profile(_ landscape: Bool) -> SizeProfile {
        let l = previewOrientation ?? landscape
        return SizeProfile(heightPercent: int(Prefs.height(l)), widthPercent: int(Prefs.width(l)), align: align(l),
                    bottomPadding: int(Prefs.bottomPadding(l)), split: split(l), gap: int(Prefs.splitGap(l)), tilt: int(Prefs.tilt(l)), spaceTilt: int(Prefs.spaceTilt(l)))
    }

    var theme: ThemeName {
        get { ThemeName(rawValue: d.string(forKey: Prefs.theme) ?? "") ?? .system }
        set { d.set(newValue.rawValue, forKey: Prefs.theme) }
    }

    func loadGrid() -> KeyGrid {
        if let g = KeyGrid.fromJSON(d.string(forKey: Prefs.layout)) { return g.languageVariant(currentLanguage) }
        let g = KeyGrid.makeDefault(currentLanguage)
        if numberRow { g.setNumberRow(true) }
        return g
    }

    /// Saves the layout. The number-row switch follows whether the layout has a number row.
    func saveGrid(_ g: KeyGrid) {
        d.set(g.toJSON(), forKey: Prefs.layout)
        d.set(g.rows == 5, forKey: Prefs.numberRow)
    }

    func resetAll() {
        // Leave learned words, migration and setup metadata intact in either store.
        var keys = [Prefs.curve.key, Prefs.keyGap.key, Prefs.cornerRadius.key, Prefs.textScale.key,
                    Prefs.longPressMs.key, Prefs.numberRow, Prefs.showHints, Prefs.autoCap, Prefs.doubleSpace,
                    Prefs.clicks, Prefs.keyTint, Prefs.smartTargets, Prefs.swipeTyping, Prefs.lockSize, Prefs.sameOrientations,
                    Prefs.theme, Prefs.layout, Prefs.languages, Prefs.currentLanguage]
        for l in [false, true] {
            keys += [Prefs.height(l).key, Prefs.width(l).key, Prefs.bottomPadding(l).key,
                     Prefs.splitGap(l).key, Prefs.tilt(l).key, Prefs.spaceTilt(l).key,
                     Prefs.alignKey(l), Prefs.splitKey(l)]
        }
        for key in keys { d.removeObject(forKey: key) }
    }
}


/// Shared by the keyboard and its settings app, which reads the embedded extension's dictionary.
struct Chord {
    let keys: String
    let word: String
    var label: String { keys.map(String.init).joined(separator: " ") + " → " + word }
}

struct Chords {
    private let candidates: [Chord]
    init(_ rankedWords: [String]) {
        var seen = Set<String>()
        let words = Array(rankedWords.prefix(20_000)).filter { seen.insert($0).inserted }
        let sets = words.map(Self.letters)
        var counts: [String: Int] = [:]
        for keys in sets { counts[keys, default: 0] += 1 }
        candidates = zip(words, sets).filter { $0.1.count >= 3 }.prefix(400)
            .filter { (3...6).contains($0.1.count) && counts[$0.1] == 1 }
            .map { Chord(keys: $0.1, word: $0.0) }
    }
    func forLetters(_ keys: Set<Character>) -> [Chord] {
        Array(candidates.filter { Set($0.keys).isSubset(of: keys) }.prefix(250))
    }
    static func letters(_ word: String) -> String { String(Set(word.lowercased().filter(\.isLetter)).sorted()) }
}

/// Admission closes on the first lift or 60 ms after the first press. Results retain press order.
final class ChordTouches<ID: Hashable> {
    struct Entry { let id: ID; let letter: Character }
    struct Completion { let entries: [Entry]; let candidate: Bool }
    struct Prepared { let rollover: [ID]; let completions: [Completion] }
    struct Release { let handled: Bool; let completions: [Completion] }
    private final class Group {
        let time: TimeInterval
        var entries: [Entry] = []
        var held = Set<ID>()
        var lifted = false
        var complete = false
        var suppressed = false
        init(_ time: TimeInterval) { self.time = time }
    }
    private var groups: [Group] = []
    func prepareDown(_ time: TimeInterval, _ eligible: Bool) -> Prepared {
        var rollover: [ID] = []
        for group in groups where !group.complete && group.entries.count == 1 && (!eligible || time - group.time > 0.060) {
            group.complete = true
            rollover.append(contentsOf: group.held)
        }
        return Prepared(rollover: rollover, completions: drain())
    }
    func down(_ id: ID, _ letter: Character, _ time: TimeInterval) {
        let group: Group
        if let last = groups.last, !last.complete, !last.lifted, time - last.time <= 0.060 { group = last }
        else { group = Group(time); groups.append(group) }
        group.entries.append(Entry(id: id, letter: letter))
        group.held.insert(id)
    }
    func contains(_ id: ID) -> Bool { groups.contains { $0.held.contains(id) } }
    func isCandidate(_ id: ID) -> Bool { groups.contains { $0.held.contains(id) && !$0.complete && $0.entries.count >= 2 } }
    func update(_ id: ID, _ letter: Character?, _ consumed: Bool) {
        guard let group = groups.first(where: { $0.held.contains(id) }), group.entries.count == 1, !group.complete else { return }
        if let letter = letter { group.entries[0] = Entry(id: id, letter: letter) }
        group.suppressed = consumed
    }
    func release(_ id: ID) -> Release {
        guard let group = groups.first(where: { $0.held.contains(id) }) else { return Release(handled: false, completions: []) }
        group.held.remove(id)
        group.lifted = true
        if group.held.isEmpty { group.complete = true }
        return Release(handled: true, completions: drain())
    }
    private func drain() -> [Completion] {
        var result: [Completion] = []
        while groups.first?.complete == true {
            let group = groups.removeFirst()
            if !group.suppressed { result.append(Completion(entries: group.entries, candidate: group.entries.count >= 2)) }
        }
        return result
    }
    func cancel() { groups.removeAll() }
}
