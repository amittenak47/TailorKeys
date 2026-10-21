import Foundation
import CoreGraphics

enum Align: String, CaseIterable { case left, center, right }
enum CursorControl: String, CaseIterable {
    case auto = "AUTO", centerGap = "CENTER_GAP", outerEdges = "OUTER_EDGES", off = "OFF"
    var title: String {
        switch self { case .auto: return "Auto"; case .centerGap: return "Center gap"; case .outerEdges: return "Outer edges"; case .off: return "Off" }
    }
}

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
    static let keyRepeatGuard = IntPref(key: "key_repeat_guard", def: 0, min: 0, max: 300)
    static let touchSlack = IntPref(key: "touch_slack", def: 0, min: 0, max: 12)
    static let textScale = IntPref(key: "text_scale", def: 100, min: 60, max: 160)
    static let longPressMs = IntPref(key: "long_press_ms", def: 350, min: 150, max: 800)

    static let followSystemText = "follow_system_text"
    static let typeOnPress = "type_on_press"
    static let numberRow = "number_row"
    static let showHints = "show_hints"
    static let cursorControl = "cursor_control"
    static let cursorGlow = "cursor_glow"
    static let spaceAccepts = "space_accepts"
    static let autoCap = "auto_cap"
    // Keep the old key under Automatic punctuation so existing choices migrate unchanged.
    static let doubleSpace = "double_space_period"
    static let clicks = "haptics"
    static let keyTint = "key_tint"
    static let swipeLetters = "swipe_letters"
    static let automaticSpace = "automatic_space"
    static let automaticPunctuation = "automatic_punctuation"
    static let swipeTyping = "swipe_typing"
    static let chording = "chording"
    static let smartTargets = "smart_targets"
    static let lockSize = "lock_size"
    static let sameOrientations = "same_orientations"
    static let theme = "theme"
    static let layout = "layout"
    static let layoutsSaved = "layouts_saved"
    static let layoutDraft = "layout_draft"
    static let layoutSelected = "layout_selected"
    static let layoutTrialPrevious = "layout_trial_previous"
    static let typingResults = "typing_results"
    static let languages = "languages"
    static let currentLanguage = "current_language"
    static let languageCodes = ["en", "es", "de", "fr"]
    static let languageNames = ["en": "English", "es": "Español", "de": "Deutsch", "fr": "Français"]

    static let booleanDefaults: [String: Bool] = [
        followSystemText: true, typeOnPress: false, numberRow: true, showHints: true,
        cursorGlow: false,
        spaceAccepts: true, autoCap: true, doubleSpace: true, clicks: true, keyTint: true,
        swipeLetters: false, automaticSpace: true, automaticPunctuation: true,
        swipeTyping: true, chording: false, smartTargets: true, lockSize: false, sameOrientations: false,
    ]
    /// Registry for reset; device-dependent defaults are still resolved by their getters.
    static var settingKeys: [String] {
        var keys = Array(booleanDefaults.keys) + [cursorControl, theme, layout, languages, currentLanguage,
                                                 layoutsSaved, layoutDraft, layoutSelected, layoutTrialPrevious, typingResults]
        keys += [curve, keyGap, cornerRadius, keyRepeatGuard, touchSlack, textScale, longPressMs].map { $0.key }
        for l in [false, true] {
            keys += [height(l), width(l), bottomPadding(l), splitGap(l), tilt(l), spaceTilt(l)].map { $0.key }
            keys += [alignKey(l), splitKey(l)]
        }
        return keys
    }
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
    var previewSplit: Bool?
    private var trialSplitCache: (json: String, split: Bool?)?

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

    func bool(_ key: String, _ def: Bool) -> Bool { d.object(forKey: key) as? Bool ?? Prefs.booleanDefaults[key] ?? def }
    func set(_ key: String, _ v: Bool) { d.set(v, forKey: key) }

    var followSystemText: Bool { bool(Prefs.followSystemText, true) }
    var typeOnPress: Bool { bool(Prefs.typeOnPress, false) }
    var numberRow: Bool { bool(Prefs.numberRow, true) }
    var showHints: Bool { bool(Prefs.showHints, true) }
    var cursorGlow: Bool { bool(Prefs.cursorGlow, false) }
    var cursorControl: CursorControl {
        get { CursorControl(rawValue: d.string(forKey: Prefs.cursorControl) ?? "") ?? .auto }
        set { d.set(newValue.rawValue, forKey: Prefs.cursorControl) }
    }
    var spaceAccepts: Bool { bool(Prefs.spaceAccepts, true) }
    var autoCap: Bool { bool(Prefs.autoCap, true) }
    var doubleSpace: Bool { bool(Prefs.doubleSpace, true) }
    var clicks: Bool { bool(Prefs.clicks, true) }
    var keyTint: Bool { bool(Prefs.keyTint, true) }
    var swipeLetters: Bool { bool(Prefs.swipeLetters, false) }
    var automaticSpace: Bool { bool(Prefs.automaticSpace, true) }
    var automaticPunctuation: Bool { bool(Prefs.automaticPunctuation, true) }
    var swipeTyping: Bool { !typeOnPress && bool(Prefs.swipeTyping, true) }
    var chording: Bool { !typeOnPress && bool(Prefs.chording, false) }
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

    func quickOneHand(_ l: Bool) -> Align { int(Prefs.width(l)) < 100 ? align(l) : .center }

    func cycleQuickOneHand(_ l: Bool) {
        let next: Align
        switch quickOneHand(l) { case .center: next = .left; case .left: next = .right; case .right: next = .center }
        setFor(l, Prefs.width, next == .center ? 100 : (split(l) ? 70 : 78))
        setAlignFor(l, next)
    }

    func adjustQuickHeight(_ l: Bool, _ delta: Int) { setFor(l, Prefs.height, int(Prefs.height(l)) + delta) }

    func cycleQuickTheme() {
        let themes = ThemeName.allCases // every theme, Warm and Midnight included
        theme = themes[((themes.firstIndex(of: theme) ?? -1) + 1) % themes.count]
    }

    func cycleLanguage(_ direction: Int) {
        let enabled = languages
        let index = enabled.firstIndex(of: currentLanguage) ?? 0
        currentLanguage = enabled[((index + direction) % enabled.count + enabled.count) % enabled.count]
    }

    func linkOrientations(from l: Bool) {
        for pref in [Prefs.height, Prefs.width, Prefs.bottomPadding, Prefs.splitGap, Prefs.tilt, Prefs.spaceTilt] { set(pref(!l), int(pref(l))) }
        d.set(align(l).rawValue, forKey: Prefs.alignKey(!l))
        d.set(split(l), forKey: Prefs.splitKey(!l))
        set(Prefs.sameOrientations, true)
    }

    func profile(_ landscape: Bool) -> SizeProfile {
        let l = previewOrientation ?? landscape
        var trialSplit: Bool?
        if d.string(forKey: Prefs.layoutTrialPrevious) != nil, let json = d.string(forKey: Prefs.layout) {
            if trialSplitCache?.json != json {
                trialSplitCache = (json, KeyGrid.fromJSON(json).flatMap { LayoutLibrary.matchingPreset($0)?.split })
            }
            trialSplit = trialSplitCache?.split
        }
        return SizeProfile(heightPercent: int(Prefs.height(l)), widthPercent: int(Prefs.width(l)), align: align(l),
                    bottomPadding: int(Prefs.bottomPadding(l)), split: previewSplit ?? trialSplit ?? split(l),
                    gap: int(Prefs.splitGap(l)), tilt: int(Prefs.tilt(l)), spaceTilt: int(Prefs.spaceTilt(l)))
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
        for key in Prefs.settingKeys { d.removeObject(forKey: key) }
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
        // A letter set belongs to its most frequent word, unless another common word (top 2,000) shares it:
        // "the" keeps t+h+e even though rare "thee" and "teeth" use the same letters.
        var common: [String: Int] = [:]
        for keys in sets.prefix(2_000) { common[keys, default: 0] += 1 }
        var first: [String: Int] = [:]
        for (i, keys) in sets.enumerated() where first[keys] == nil { first[keys] = i }
        candidates = Array(sets.indices).filter { sets[$0].count >= 3 }.prefix(400)
            .filter { (3...6).contains(sets[$0].count) && first[sets[$0]] == $0 && (common[sets[$0]] ?? 0) <= 1 }
            .map { Chord(keys: sets[$0], word: words[$0]) }
    }
    func forLetters(_ keys: Set<Character>) -> [Chord] {
        Array(candidates.filter { Set($0.keys).isSubset(of: keys) }.prefix(250))
    }
    static func letters(_ word: String) -> String { String(Set(word.lowercased().filter(\.isLetter)).sorted()) }
}

/// Admission closes on the first lift or 60 ms after the first press. Results retain press order.
final class ChordTouches<ID: Hashable> {
    struct Entry { let id: ID; let letter: Character; var point: CGPoint? = nil }
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
    func down(_ id: ID, _ letter: Character, _ time: TimeInterval, _ point: CGPoint? = nil) {
        let group: Group
        if let last = groups.last, !last.complete, !last.lifted, time - last.time <= 0.060 { group = last }
        else { group = Group(time); groups.append(group) }
        group.entries.append(Entry(id: id, letter: letter, point: point))
        group.held.insert(id)
    }
    func contains(_ id: ID) -> Bool { groups.contains { $0.held.contains(id) } }
    func isCandidate(_ id: ID) -> Bool { groups.contains { $0.held.contains(id) && !$0.complete && $0.entries.count >= 2 } }
    func update(_ id: ID, _ letter: Character?, _ consumed: Bool, _ point: CGPoint? = nil) {
        guard let group = groups.first(where: { $0.held.contains(id) }), group.entries.count == 1, !group.complete else { return }
        if let letter = letter { group.entries[0] = Entry(id: id, letter: letter, point: point) }
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
