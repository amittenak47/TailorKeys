import Foundation

enum Align: String, CaseIterable { case left, center, right }

struct IntPref {
    let key: String
    let def: Int
    let min: Int
    let max: Int
}

/// Every setting with its default and range. Same keys and defaults as the Android app.
/// Stored in the keyboard's own UserDefaults, so no App Group (and no paid developer account) is needed.
enum Prefs {
    static func height(_ l: Bool) -> IntPref { l ? IntPref(key: "height_l", def: 58, min: 24, max: 70) : IntPref(key: "height_p", def: 44, min: 24, max: 70) }
    static func width(_ l: Bool) -> IntPref { IntPref(key: l ? "width_l" : "width_p", def: 100, min: 50, max: 100) }
    static func bottomPadding(_ l: Bool) -> IntPref { IntPref(key: l ? "bottom_pad_l" : "bottom_pad_p", def: 0, min: 0, max: 48) }
    static func splitGap(_ l: Bool) -> IntPref { l ? IntPref(key: "split_gap_l", def: 22, min: 8, max: 40) : IntPref(key: "split_gap_p", def: 16, min: 8, max: 40) }
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
    static let smartTargets = "smart_targets"
    static let lockSize = "lock_size"
    static let sameOrientations = "same_orientations"
    static let theme = "theme"
    static let layout = "layout"
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
    private let d = UserDefaults.standard
    private let tablet: Bool

    init(tablet: Bool) { self.tablet = tablet }

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

    func profile(_ l: Bool) -> SizeProfile {
        SizeProfile(heightPercent: int(Prefs.height(l)), widthPercent: int(Prefs.width(l)), align: align(l),
                    bottomPadding: int(Prefs.bottomPadding(l)), split: split(l), gap: int(Prefs.splitGap(l)), tilt: int(Prefs.tilt(l)), spaceTilt: int(Prefs.spaceTilt(l)))
    }

    var theme: ThemeName {
        get { ThemeName(rawValue: d.string(forKey: Prefs.theme) ?? "") ?? .system }
        set { d.set(newValue.rawValue, forKey: Prefs.theme) }
    }

    func loadGrid() -> KeyGrid {
        if let g = KeyGrid.fromJSON(d.string(forKey: Prefs.layout)) { return g }
        let g = KeyGrid.makeDefault()
        if numberRow { g.setNumberRow(true) }
        return g
    }

    /// Saves the layout. The number-row switch follows whether the layout has a number row.
    func saveGrid(_ g: KeyGrid) {
        d.set(g.toJSON(), forKey: Prefs.layout)
        d.set(g.rows == 5, forKey: Prefs.numberRow)
    }

    func resetAll() {
        for key in d.dictionaryRepresentation().keys where key != "user_words" { d.removeObject(forKey: key) }
    }
}
