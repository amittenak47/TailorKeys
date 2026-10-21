import Foundation

struct InsertSymbol: Equatable {
    let text: String
    let name: String
    let category: String
    let keywords: String
    init(_ text: String, _ name: String, _ category: String, _ keywords: String = "") {
        self.text = text; self.name = name; self.category = category; self.keywords = keywords
    }
}

final class EmojiCatalog {
    let entries: [InsertSymbol]
    let primary: [InsertSymbol]
    private let families: [String: [InsertSymbol]]
    init(_ tsv: String) {
        entries = tsv.components(separatedBy: .newlines).compactMap { line in
            let f = line.components(separatedBy: "\t")
            return f.count >= 3 ? InsertSymbol(f[0], f[1], f[2], f.count > 3 ? f[3] : "") : nil
        }
        primary = entries.filter { !Self.hasTone($0.text) && $0.category != "Component" }
        families = Dictionary(grouping: entries, by: { Self.base($0.text) })
    }
    // Tone modifiers replace VS16 for older emoji. Normalize only the lookup key.
    private static func base(_ text: String) -> String { String(String.UnicodeScalarView(text.unicodeScalars.filter { !(0x1F3FB...0x1F3FF).contains($0.value) && $0.value != 0xFE0F })) }
    private static func hasTone(_ text: String) -> Bool { text.unicodeScalars.contains { (0x1F3FB...0x1F3FF).contains($0.value) } }
    private static func fold(_ text: String) -> String { text.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US_POSIX")) }
    func search(_ query: String) -> [InsertSymbol] {
        let words = Self.fold(query).split(whereSeparator: \.isWhitespace)
        return primary.filter { entry in let haystack = Self.fold(entry.name + " " + entry.keywords); return words.allSatisfy { haystack.contains($0) } }
    }
    func variants(_ text: String) -> [InsertSymbol] {
        let family = families[Self.base(text)] ?? []
        guard let plain = family.first(where: { !Self.hasTone($0.text) }) else { return [] }
        return [plain] + (0x1F3FB...0x1F3FF).compactMap { tone in family.first { entry in
            let tones = entry.text.unicodeScalars.filter { (0x1F3FB...0x1F3FF).contains($0.value) }
            return !tones.isEmpty && tones.allSatisfy { $0.value == UInt32(tone) }
        } }
    }
    func toned(_ entry: InsertSymbol, _ tone: Int) -> InsertSymbol {
        if entry.text.unicodeScalars.contains(where: { (0x1F3FB...0x1F3FF).contains($0.value) }) { return entry }
        let family = variants(entry.text); return family.indices.contains(tone) ? family[tone] : entry
    }
}

struct InsertRecents {
    private(set) var values: [String] = []
    init(_ values: [String] = []) { for item in values.reversed() { use(item) } }
    mutating func use(_ text: String) { values.removeAll { $0 == text }; values.insert(text, at: 0); values = Array(values.prefix(32)) }
}

enum ClipKeep: Int, CaseIterable {
    case hour, day, week, forever
    var title: String { ["1 hour", "1 day", "1 week", "until cleared"][rawValue] }
    var seconds: TimeInterval { [3600, 86400, 604800, .infinity][rawValue] }
}

struct ClipItem: Codable, Equatable, Identifiable {
    var id = UUID().uuidString
    var text: String
    var at: TimeInterval
    var pinned = false
    var note = false
    var title = ""
}

final class ClipboardStore {
    /// One limit for notes everywhere they are written: the panel editor, the settings sheet and the store.
    static let noteMax = 2_000
    static let titleMax = 40
    static let clipMax = 20_000
    private var items: [ClipItem]
    private var deleted: (ClipItem, TimeInterval)?
    private let now: () -> TimeInterval
    init(_ items: [ClipItem] = [], now: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 }) { self.items = items; self.now = now }
    var clips: [ClipItem] { sorted(items.filter { !$0.note }) }
    var notes: [ClipItem] { sorted(items.filter(\.note)) }
    private func sorted(_ items: [ClipItem]) -> [ClipItem] { items.sorted { a, b in a.pinned != b.pinned ? a.pinned : a.at > b.at } }
    func expire(_ keep: ClipKeep) { items.removeAll { !$0.note && !$0.pinned && now() - $0.at >= keep.seconds } }
    @discardableResult func capture(_ text: String, enabled: Bool, sensitive: Bool, password: Bool, fullAccess: Bool = true) -> Bool {
        guard enabled, !sensitive, !password, fullAccess, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        let safe = String(text.prefix(Self.clipMax))
        if items.contains(where: { !$0.note && $0.pinned && $0.text == safe }) { return false }
        items.removeAll { !$0.note && !$0.pinned && $0.text == safe }
        items.append(ClipItem(text: safe, at: now()))
        let excess = Set(clips.filter { !$0.pinned }.dropFirst(100).map(\.id)); items.removeAll { excess.contains($0.id) }; return true
    }
    func pin(_ id: String) { if let i = items.firstIndex(where: { $0.id == id }) { items[i].pinned.toggle() } }
    func edit(_ id: String, text: String, title: String) { if let i = items.firstIndex(where: { $0.id == id }) { items[i].text = String(text.prefix(items[i].note ? Self.noteMax : Self.clipMax)); items[i].title = String(title.prefix(Self.titleMax)) } }
    @discardableResult func note(_ title: String, _ text: String) -> ClipItem { let item = ClipItem(text: String(text.prefix(Self.noteMax)), at: now(), note: true, title: String(title.prefix(Self.titleMax))); items.append(item); return item }
    func saveAsNote(_ id: String) { if let item = items.first(where: { $0.id == id }) { note(String(item.text.prefix(Self.titleMax)), item.text) } }
    func delete(_ id: String) { if let item = items.first(where: { $0.id == id }) { deleted = (item, now()); items.removeAll { $0.id == id } } }
    @discardableResult func undo() -> Bool { guard let saved = deleted else { return false }; deleted = nil; guard now() - saved.1 <= 6 else { return false }; restore(saved.0); return true }
    func restore(_ item: ClipItem) { if !items.contains(where: { $0.id == item.id }) { items.append(item) } }
    func clear() { items.removeAll { !$0.note && !$0.pinned }; deleted = nil }
    func encode() -> Data { let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]; return (try? encoder.encode(items)) ?? Data() }
    static func decode(_ data: Data, now: @escaping () -> TimeInterval = { Date().timeIntervalSince1970 }) -> ClipboardStore { ClipboardStore((try? JSONDecoder().decode([ClipItem].self, from: data)) ?? [], now: now) }
}

enum InsertPrefs {
    static let history = "insert_history", keep = "insert_keep", tone = "insert_tone", recents = "insert_recents", section = "insert_section"
    static let toneNames = ["Default", "Light", "Medium-light", "Medium", "Medium-dark", "Dark"]
    static let promptSeen = "insert_key_prompt_v1"
    static func needsPrompt(seen: Bool, custom: Bool, grid: KeyGrid) -> Bool { !seen && custom && !grid.items.contains { $0.keys.contains(Keys.insert) } }
}

final class InsertRepository {
    private let defaults: () -> UserDefaults
    var prefs: UserDefaults { defaults() }
    init(defaults: @escaping () -> UserDefaults) { self.defaults = defaults }
    var keep: ClipKeep { prefs.object(forKey: InsertPrefs.keep) == nil ? .day : ClipKeep(rawValue: prefs.integer(forKey: InsertPrefs.keep)) ?? .day }
    func store() -> ClipboardStore {
        let result = ClipboardStore.decode(prefs.data(forKey: "insert_clips") ?? Data()); result.expire(prefs.object(forKey: InsertPrefs.keep) == nil ? .day : keep); return result
    }
    func save(_ store: ClipboardStore) { let data = store.encode(); if prefs.data(forKey: "insert_clips") != data { prefs.set(data, forKey: "insert_clips") } }
    func searchableItems(fullAccess: Bool) -> [ClipItem] {
        let s = store(); return (fullAccess && prefs.boolDefault(InsertPrefs.history, false) ? s.clips : []) + s.notes
    }
    func change(_ action: (ClipboardStore) -> Void) { let s = store(); action(s); save(s) }
    func recent(_ section: String) -> [String] { prefs.boolDefault(InsertPrefs.recents, true) ? prefs.stringArray(forKey: "insert_recent_" + section) ?? [] : [] }
    func used(_ section: String, _ text: String) { guard prefs.boolDefault(InsertPrefs.recents, true) else { return }; var r = InsertRecents(recent(section)); r.use(text); prefs.set(r.values, forKey: "insert_recent_" + section) }
    func clearRecents() { prefs.removeObject(forKey: "insert_recent_Emoji"); prefs.removeObject(forKey: "insert_recent_Kaomoji") }
}

extension UserDefaults {
    func boolDefault(_ key: String, _ value: Bool) -> Bool { object(forKey: key) == nil ? value : bool(forKey: key) }
}
