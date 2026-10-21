// Run on a Mac with: bash ios/test-safety.sh
// This executable deliberately avoids UIKit, Xcode projects, signing and real preference domains.
import Foundation
import CoreGraphics

// KeyboardSettings only needs this value type; the production palette imports UIKit.
enum ThemeName: String, CaseIterable { case system, light, dark, midnight, warm, highContrast }

func check(_ condition: @autoclosure () -> Bool, _ message: String) {
    if !condition() { fatalError(message) }
}

func shareCode(_ bytes: Data) -> String {
    let compressed = try! (bytes as NSData).compressed(using: .zlib) as Data
    return encoded(compressed)
}

func encoded(_ bytes: Data) -> String {
    "TK1-" + bytes.base64EncodedString().replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
}

let validJSON = #"{"rows":4,"items":[{"r":0,"c":0,"w":1,"k":["a","b"],"i":0}]}"#
check(KeyGrid.fromJSON(validJSON)?.isSound == true, "valid stored layout rejected")
let cachedImport = KeyGrid.fromJSON(validJSON)!
let editedImport = cachedImport.copy()
editedImport.items[0].keys[0] = "z"
editedImport.items[0].idx = 1
check(cachedImport.items[0].keys[0] == "a" && cachedImport.items[0].idx == 0, "editing an import changed its cached decode")
// Independent zlib raw-DEFLATE fixture, using the Android wire format.
check(KeyGrid.fromCode("TK1-q1Yqyi8vVrIy0VHKLEnNLVayiq5WKlKyMtBRSgaT5UpWhjpK2UpW0UqJSjpKSUqxOkqZSlYGtbG1AA") != nil, "raw-DEFLATE interoperability failed")
let invalidJSON = [
    validJSON.replacingOccurrences(of: #""i":0"#, with: #""i":-1"#),
    validJSON.replacingOccurrences(of: #""i":0"#, with: #""i":2"#),
    validJSON.replacingOccurrences(of: #""c":0"#, with: "\"c\":\(Int.max)"),
    validJSON.replacingOccurrences(of: #""c":0"#, with: "\"c\":\(Int.min)"),
    validJSON.replacingOccurrences(of: #""w":1"#, with: "\"w\":\(Int.max)"),
    validJSON.replacingOccurrences(of: #""w":1"#, with: #""w":0"#),
    validJSON.replacingOccurrences(of: #""r":0"#, with: #""r":-1"#),
    validJSON.replacingOccurrences(of: #""r":0"#, with: #""r":4"#),
    validJSON.replacingOccurrences(of: #""rows":4"#, with: #""rows":6"#),
    validJSON.replacingOccurrences(of: #"["a","b"]"#, with: "[]"),
    validJSON.replacingOccurrences(of: #"["a","b"]"#, with: #"[""]"#),
    #"{"rows":4,"items":[]}"#,
    #"{"rows":4,"items":[{"r":0,"c":4,"w":2,"k":["a"],"i":0}]}"#,
    #"{"rows":4,"items":[{"r":0,"c":10,"w":2,"k":["a"],"i":0}]}"#,
    #"{"rows":4,"items":[{"r":0,"c":0,"w":2,"k":["a"],"i":0},{"r":0,"c":1,"w":1,"k":["b"],"i":0}]}"#,
]
for json in invalidJSON {
    check(KeyGrid.fromJSON(json) == nil, "invalid persisted JSON accepted: \(json)")
    check(KeyGrid.fromCode(shareCode(Data(json.utf8))) == nil, "invalid import accepted")
}
for template in Template.allCases { for language in Prefs.languageCodes {
    let grid = KeyGrid.template(template, language)
    for rows in [false, true] {
        grid.setNumberRow(rows)
        check(KeyGrid.fromJSON(grid.toJSON())?.isSound == true, "template JSON round trip failed")
        check(KeyGrid.fromCode("Here is my layout: " + grid.toCode())?.isSound == true, "template code round trip failed")
    }
} }

let exactLimit = validJSON + String(repeating: " ", count: 65_536 - validJSON.utf8.count)
check(KeyGrid.fromCode(shareCode(Data(exactLimit.utf8))) != nil, "64 KiB boundary rejected")
check(KeyGrid.fromCode(shareCode(Data((exactLimit + " ").utf8))) == nil, "over-limit expansion accepted")
check(KeyGrid.fromCode(shareCode(Data(repeating: 32, count: 2_000_000))) == nil, "compression bomb accepted")
let packed = try! (Data(validJSON.utf8) as NSData).compressed(using: .zlib) as Data
for length in 0..<packed.count {
    check(KeyGrid.fromCode(encoded(Data(packed.prefix(length)))) == nil, "truncated stream accepted at \(length)")
}
check(KeyGrid.fromCode(encoded(packed + Data([1, 2, 3]))) == nil, "trailing compressed data accepted")
check(KeyGrid.fromCode("TK1-" + String(repeating: "A", count: 140_000)) == nil, "oversized encoded input accepted")

let suite = "app.tailorkeys.safety." + UUID().uuidString
let store = UserDefaults(suiteName: suite)!
defer { store.removePersistentDomain(forName: suite) }
let settings = KeyboardSettings(tablet: false, store: store)
let defaultGrid = settings.loadGrid().toJSON()
for key in Prefs.settingKeys { store.set("changed", forKey: key) }
store.set(false, forKey: Prefs.followSystemText)
store.set(true, forKey: Prefs.typeOnPress)
store.set(true, forKey: Prefs.chording)
store.set(280, forKey: Prefs.keyRepeatGuard.key)
store.set(12, forKey: Prefs.touchSlack.key)
store.set(["hello": 2], forKey: "user_words")
store.set(true, forKey: "setup_ready_shown")
settings.resetAll()
for key in Prefs.settingKeys { check(store.object(forKey: key) == nil, "reset omitted \(key)") }
check(settings.followSystemText && !settings.typeOnPress && !settings.chording && settings.swipeTyping, "reset defaults differ")
check(settings.int(Prefs.keyRepeatGuard) == 0 && settings.int(Prefs.touchSlack) == 0, "reset left tap timing/slack enabled")
check(store.dictionary(forKey: "user_words") != nil && store.bool(forKey: "setup_ready_shown"), "reset erased learning/setup")
for json in invalidJSON {
    store.set(json, forKey: Prefs.layout)
    check(settings.loadGrid().isSound, "invalid persisted layout did not fall back")
    check(settings.loadGrid().toJSON().count == defaultGrid.count, "fallback layout differs")
}

for word in ["hello", "français", "straße", "œuvre", "a\u{301}", "\u{1F469}\u{200D}\u{1F4BB}", "漢字"] {
    check(Array(CompactLetters(word)) == Array(word), "compact letters changed graphemes")
}
let temporary = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
defer { try? FileManager.default.removeItem(at: temporary) }
try Data(("first\r\n" + String(repeating: "x", count: 4097) + "\n" + String(repeating: "a\n", count: 9000) + "français").utf8).write(to: temporary)
var lines: [String] = []
ResourceLines.read(temporary) { lines.append($0) }
check(lines.count == 9002 && lines.first == "first" && lines.last == "français", "streaming line boundaries/EOF/limit differ")

// Reuse the existing Android/PyTorch reference vectors without changing Android sources.
let asset = URL(fileURLWithPath: "app/src/main/assets/lm_en.bin")
let model = try NextWordModel.load(from: InputStream(url: asset)!)
let references = try String(contentsOfFile: "app/src/test/resources/lm_reference.txt", encoding: .utf8)
var referenceCount = 0
for line in references.split(whereSeparator: \.isNewline) where !line.hasPrefix("#") {
    let fields = line.split(separator: "\t")
    check(fields.count == 3, "invalid reference record")
    let sequence = fields[0].split(separator: " ").map { Int($0)! }
    let expected = fields[1].split(separator: " ").map { Int($0)! }
    let probabilities = fields[2].split(separator: " ").map { Float($0)! }
    let state = model.initialState()
    for id in sequence { model.step(state, wordId: id) }
    var output = [Float](repeating: 0, count: model.vocabularySize)
    model.logProbs(state, out: &output)
    let top = output.indices.sorted { output[$0] == output[$1] ? $0 < $1 : output[$0] > output[$1] }
    check(Array(top.prefix(expected.count)) == expected, "model reference ranking changed")
    var logits = [Float](repeating: 0, count: expected.count)
    model.scoreCandidates(state, candidateIds: expected, out: &logits)
    let normalizer = logits[0] - output[expected[0]]
    for i in expected.indices {
        check(abs(output[expected[i]] - probabilities[i]) <= 0.15, "model reference probability changed")
        check(abs(logits[i] - normalizer - output[expected[i]]) < 0.0001, "batched and single-row Accelerate scores differ")
    }
    state.reset()
    for id in sequence { model.step(state, wordId: id) }
    var replay = [Float](repeating: 0, count: model.vocabularySize)
    model.logProbs(state, out: &replay)
    check(zip(output, replay).allSatisfy { abs($0.0 - $0.1) < 0.00001 }, "model reset/replay changed")
    referenceCount += 1
}
check(referenceCount >= 3, "model reference vectors missing")
print("Safety checks and \(referenceCount) model reference vectors passed.")


// Swipe extraction, hint placement and text-edit rules mirror the Android JVM checks.
let swipeKeys: [Character: CGPoint] = ["a": .zero, "b": CGPoint(x: 100, y: 0), "c": CGPoint(x: 100, y: 100)]
let cornerTrace = SwipeTrace(swipeKeys, 100)
for x in stride(from: 0, through: 100, by: 10) { cornerTrace.sample(CGPoint(x: CGFloat(x), y: 0), Double(x) / 1000, x / 10) }
for y in stride(from: 10, through: 100, by: 10) { cornerTrace.sample(CGPoint(x: 100, y: CGFloat(y)), Double(y + 100) / 1000, y / 10 + 10) }
check(cornerTrace.text() == "abc", "sharp swipe corner lost")
let dwellTrace = SwipeTrace(swipeKeys, 100)
dwellTrace.sample(.zero, 0, 0); dwellTrace.sample(.zero, 0.211, 1); dwellTrace.sample(.zero, 0.8, 2)
check(dwellTrace.text() == "aa" && dwellTrace.hints.count == 1, "dwell did not double exactly once")
let slowTrace = SwipeTrace(swipeKeys, 100)
for x in stride(from: 0, through: 100, by: 10) { slowTrace.sample(CGPoint(x: CGFloat(x), y: 0), Double(x) / 100, x / 10) }
check(slowTrace.hints.isEmpty, "slow pass triggered dwell")
var wordKeys: [Character: CGPoint] = [:]
for (i, ch) in "corlay".enumerated() { wordKeys[ch] = CGPoint(x: CGFloat(i * 100), y: i % 2 == 0 ? 0 : 100) }
func swipePath(_ word: String) -> [GlidePoint] { word.enumerated().map { GlidePoint(position: wordKeys[$0.element]!, time: Double($0.offset) * 0.06) } }
let swipeDecoder = GestureDecoder(["corolary", "corollary", "ollary"])
swipeDecoder.setKeys(wordKeys, 100)
let doubled = swipeDecoder.decode(swipePath("corolary"), nil, frequency: { _ in 1 }, context: { _, _ in 0 }, hints: [DoubleLetterHint(letter: "l", pathIndex: 4)])
check(doubled.first?.word == "corollary", "double-letter hint did not improve ranking")
let continued = swipeDecoder.decode(swipePath("olary"), nil, frequency: { _ in 1 }, context: { _, _ in 0 }, hints: [DoubleLetterHint(letter: "l", pathIndex: 1)], prefix: "cor")
check(continued.first?.word == "corollary" && continued.allSatisfy { $0.word.hasPrefix("cor") }, "typed prefix was not enforced")
check(SwipeTrace.appendToPrefix("cor", "rollary") == "corollary", "prefix boundary overlap repeated")
for ch in ".,?!:;" {
    let edit = TextInputRules.punctuation(String(ch), "dog ", "", automaticSpace: true, enabled: true)
    check(edit?.deleteBefore == 1 && edit?.insert == String(ch) + " ", "punctuation spacing failed")
    check(TextInputRules.punctuation(String(ch), "dog ", "", automaticSpace: true, enabled: false) == nil, "punctuation switch ignored")
}
for (open, close) in [("(", ")"), ("[", "]"), ("{", "}"), ("\"", "\"")] {
    let pair = TextInputRules.punctuation(open, "", "", automaticSpace: false, enabled: true)
    check(pair?.insert == open + close && pair?.move == -1, "pair insertion failed")
    check(TextInputRules.punctuation(close, "", close, automaticSpace: false, enabled: true)?.move == 1, "closer step failed")
    check(TextInputRules.punctuation(open, "", "", automaticSpace: false, enabled: true, password: true) == nil, "password inserted pair")
}
check(TextInputRules.punctuation("'", "dog ", "", automaticSpace: true, enabled: true)?.insert == "'", "apostrophe spacing failed")
check(TextInputRules.doubleSpace(true, "dog ", 0.3) && !TextInputRules.doubleSpace(false, "dog ", 0.3), "double-space switch ignored")
check(!TextInputRules.doubleSpace(true, "dog ", 0.3, password: true), "password double-space enabled")
check(TextInputRules.addSpace(true, "") && !TextInputRules.addSpace(false, ""), "end spacing switch ignored")
check(!TextInputRules.addSpace(true, " next") && !TextInputRules.addSpace(true, "."), "middle spacing duplicated")
let replacement = TextInputRules.replacement(2, "llo next", automaticSpace: true)
check(replacement.before == 2 && replacement.after == 3 && !replacement.space, "middle-word replacement lost boundaries")
check(TextInputRules.wordForms("dog", exists: { $0 == "dogs" }) == ["dogs", "dog's"], "word forms missing")

// Layout library wire format, migration and trial recovery never touch real preference domains.
func withLayoutStore(_ body: (KeyboardSettings, UserDefaults) -> Void) {
    let name = "app.tailorkeys.layouts.test." + UUID().uuidString
    let defaults = UserDefaults(suiteName: name)!
    defer { defaults.removePersistentDomain(forName: name) }
    let prefs = KeyboardSettings(tablet: false, store: defaults)
    prefs.setLanguages(["en"])
    body(prefs, defaults)
}

for template in Template.allCases {
    for numberRow in [false, true] {
        withLayoutStore { prefs, defaults in
            let grid = KeyGrid.template(template)
            grid.setNumberRow(numberRow)
            prefs.saveGrid(grid)
            let library = LayoutLibrary(settings: prefs, defaults: defaults)
            check(library.appliedKey == LayoutLibrary.presetKey(template), "preset migration selected the wrong tile")
            check(library.saved.isEmpty, "preset migration made a custom tile")
            check(defaults.string(forKey: "layout_selected") == library.appliedKey, "applied selection key differs from Android")
        }
    }
}

withLayoutStore { prefs, defaults in
    let library = LayoutLibrary(settings: prefs, defaults: defaults, migrate: false)
    library.reload(migrate: false)
    check(defaults.string(forKey: Prefs.layoutSelected) == nil, "app seeded shared settings before keyboard migration")
    check(library.appliedKey == "preset:STANDARD", "read-only library has no selection")
}

withLayoutStore { prefs, defaults in
    let custom = KeyGrid.fromJSON(validJSON)!
    prefs.saveGrid(custom)
    let library = LayoutLibrary(settings: prefs, defaults: defaults)
    check(library.saved.count == 1 && library.saved[0].name == "My layout", "custom migration did not preserve My layout")
    check(library.appliedKey == library.saved[0].key, "custom migration did not apply the saved identity")
    library.reload()
    check(library.saved.count == 1, "migration ran twice")
    let second = library.add(KeyGrid.template(.thumbs), name: "Couch typing")!
    let json = try! JSONSerialization.jsonObject(with: Data(defaults.string(forKey: "layouts_saved")!.utf8)) as! [[String: Any]]
    check(json.count == 2 && json[0]["grid"] is [String: Any], "saved grids must be JSON objects in user order")
    check(json[0]["id"] as? String == library.saved[0].id && UUID(uuidString: library.saved[1].id) != nil, "saved IDs are not UUIDs")
    library.rename(second, name: "  A new name\nfor my layout that is very long  ")
    check(library.saved[1].name.count == 24 && !library.saved[1].name.contains("\n"), "name normalization or 24-character limit failed")
    check(LayoutLibrary.name("   ") == "Custom", "blank names need a usable default")
    let original = prefs.loadGrid()
    check(library.startDraft(from: second), "cannot start a draft")
    check(!library.startDraft(from: "preset:STANDARD"), "more than one draft was allowed")
    let edited = library.choice("draft")!.grid
    edited.items[0].keys = ["é"]
    library.update("draft", grid: edited)
    check(LayoutLibrary.sameGrid(original, prefs.loadGrid()), "editing an unused draft applied it")
    check(!LayoutLibrary.sameGrid(library.choice(second)!.grid, edited), "draft edits mutated their source")
    library.record(TypingResult(wpm: 42, accuracy: 0.9, at: 1_000), for: "draft")
    library.use("draft")
    let savedDraft = library.saveDraft(name: "Desk")!
    check(library.draft == nil && defaults.string(forKey: "layout_draft") == nil, "saving a draft left its storage key")
    check(library.appliedKey == savedDraft && library.average(savedDraft) == 42, "saving draft lost applied identity or results")
    check(library.results["draft"] == nil, "draft results leaked into the next draft")
    check(library.add(KeyGrid(rows: 0, items: [], nextId: 0), name: "Invalid") == nil, "invalid grid entered the library")
    let oversized = KeyGrid.template(.standard)
    for key in oversized.items { key.keys = Array(repeating: String(repeating: "x", count: 64), count: 64) }
    check(oversized.isSound && library.add(oversized, name: "Too large") == nil, "unreadable oversized grid entered the library")
    library.delete(savedDraft)
    check(library.appliedKey == "preset:STANDARD", "deleting in-use custom did not switch to Standard")
    check(LayoutLibrary.matchingPreset(prefs.loadGrid()) == .standard, "deleting in-use custom left its applied grid")
    check(library.results[savedDraft] == nil, "deleting a layout left its results")
    library.delete("preset:STANDARD")
    check(library.choice("preset:STANDARD") != nil, "a built-in preset was deleted")
    let reloaded = LayoutLibrary(settings: prefs, defaults: defaults)
    check(reloaded.saved.map(\.id) == library.saved.map(\.id), "saved layout order did not survive reload")
}

withLayoutStore { prefs, defaults in
    let library = LayoutLibrary(settings: prefs, defaults: defaults)
    let previous = prefs.loadGrid()
    check(library.beginTrial("preset:SPLIT"), "trial failed to start")
    check(defaults.string(forKey: "layout_trial_previous") != nil, "trial has no recovery record")
    check(library.appliedKey == "preset:STANDARD" && defaults.string(forKey: "layout_selected") == "preset:STANDARD", "trial changed permanent selection")
    check(LayoutLibrary.matchingPreset(prefs.loadGrid()) == .split && prefs.profile(false).split, "extension cannot see trial layout/split")
    check(!prefs.split(false), "trial permanently changed the Size tab")
    library.restoreTrial()
    check(LayoutLibrary.sameGrid(previous, prefs.loadGrid()) && !prefs.profile(false).split, "leaving trial did not restore grid/profile")
    check(defaults.string(forKey: "layout_trial_previous") == nil, "trial recovery record was not cleared")
    let fourRows = library.add(KeyGrid.fromJSON(validJSON)!, name: "Four rows")!
    _ = library.beginTrial(fourRows)
    check(!prefs.numberRow && library.choice("preset:STANDARD")!.grid.rows == previous.rows, "trial row count changed built-in tiles")
    library.restoreTrial()
    check(prefs.numberRow == (previous.rows == 5), "trial did not restore the number-row switch")
    _ = library.beginTrial("preset:THUMBS")
    let relaunched = LayoutLibrary(settings: prefs, defaults: defaults)
    check(LayoutLibrary.sameGrid(previous, prefs.loadGrid()), "launch recovery did not restore the original layout")
    check(relaunched.appliedKey == "preset:STANDARD", "launch recovery selected trial layout")
    _ = relaunched.beginTrial("preset:THUMBS")
    relaunched.use("preset:THUMBS")
    relaunched.restoreTrial()
    check(LayoutLibrary.matchingPreset(prefs.loadGrid()) == .thumbs && relaunched.appliedKey == "preset:THUMBS", "Use layout was restored away")
    check(!relaunched.beginTrial("saved:missing"), "trial accepted an unknown layout")
    _ = relaunched.beginTrial("preset:THUMBS")
    check(defaults.string(forKey: "layout_trial_previous") == nil, "trying an applied tile created a needless recovery record")
}

withLayoutStore { prefs, defaults in
    let library = LayoutLibrary(settings: prefs, defaults: defaults)
    for index in 1...25 {
        library.record(TypingResult(wpm: Double(index), accuracy: 0.95, at: Double(index) * 1_000), for: "preset:STANDARD")
    }
    library.record(TypingResult(wpm: 80, accuracy: 1, at: 99_000), for: "preset:SPLIT")
    library.record(TypingResult(wpm: .infinity, accuracy: 1, at: 1), for: "preset:STANDARD")
    library.record(TypingResult(wpm: 10, accuracy: -1, at: 1), for: "preset:STANDARD")
    library.record(TypingResult(wpm: 10, accuracy: 1, at: 1), for: "saved:missing")
    check(library.results["preset:STANDARD"]?.count == 20 && library.results["preset:STANDARD"]?.first?.wpm == 6, "history is not the last 20 results")
    check(library.average("preset:STANDARD") == 15.5 && library.average("preset:SPLIT") == 80, "layout averages mixed histories")
    let object = try! JSONSerialization.jsonObject(with: Data(defaults.string(forKey: "typing_results")!.utf8)) as! [String: [[String: Any]]]
    check(object["preset:STANDARD"]?.last?["at"] as? Double == 25_000, "results do not store epoch milliseconds")
    library.reload()
    check(library.average("preset:STANDARD") == 15.5, "result history did not round trip")
    defaults.set("broken", forKey: Prefs.layoutsSaved)
    defaults.set("broken", forKey: Prefs.layoutDraft)
    defaults.set("broken", forKey: Prefs.typingResults)
    defaults.set("broken", forKey: Prefs.layoutTrialPrevious)
    let recovered = LayoutLibrary(settings: prefs, defaults: defaults)
    check(recovered.saved.isEmpty && recovered.draft == nil && recovered.results.isEmpty, "corrupt library storage did not recover")
    check(recovered.appliedKey == "preset:STANDARD", "corrupt storage lost valid applied layout")
    prefs.resetAll()
    for key in ["layouts_saved", "layout_draft", "layout_selected", "layout_trial_previous", "typing_results"] {
        check(defaults.object(forKey: key) == nil, "reset left layout library key \(key)")
    }
}

switch LayoutCodeStatus.read("Someone shared TK2-abc") {
case .invalid(let message): check(message == "This code is from a newer version of TailorKeys", "future code message differs")
default: fatalError("future layout code accepted")
}
switch LayoutCodeStatus.read("TK1-cutoff") {
case .invalid: break
default: fatalError("bad code accepted")
}
switch LayoutCodeStatus.read(KeyGrid.template(.split).toCode()) {
case .valid(let grid): check(LayoutLibrary.matchingPreset(grid) == .split, "live import decoder changed grid")
default: fatalError("valid live import rejected")
}

check(TypingSession.wpm(characters: 250, seconds: 60) == 50, "WPM must use five-character words")
check(TypingSession.wpm(characters: 10, seconds: 0) == 0 && TypingSession.wpm(characters: 10, seconds: -.infinity) == 0, "WPM has invalid zero-time behavior")
var free = TypingSession(mode: .free, sentence: "")
check(free.accuracy == 1 && free.elapsed(at: 100) == 0, "empty test metrics differ")
free.update("hello", at: 100)
free.update("hell", at: 101)
free.update("hello", at: 102)
check(free.inserted == 6 && free.deleted == 1 && free.mistakes == 1, "free typing did not count corrections")
check(abs(free.accuracy - 5.0 / 6.0) < 0.0001, "free accuracy is not undeleted keystroke share")
check(free.wpm(at: 160) == 1 && free.result(at: Date()) == nil, "free session saved a result or computed wrong speed")
free.update("hallo", at: 103)
check(free.inserted == 7 && free.deleted == 2 && free.mistakes == 2, "replacement counted unchanged suffix")
free.update("ha🌻llo", at: 104)
check(free.characterCount == 6 && free.inserted == 8, "metrics counted UTF-16 units instead of characters")
free.update("", at: 105)
check(free.accuracy == 0 && free.wpm(at: 105) == 0, "cleared text has negative accuracy or speed")

var test = TypingSession(mode: .test, sentence: "hello world")
test.update("heXlo", at: 100)
check(test.mistakes == 1 && test.slippedPositions == [2] && test.accuracy == 0.8, "test mismatch metrics differ")
test.update("hello", at: 101)
check(test.accuracy == 1 && test.mistakes == 1, "correcting a test error lost its historical mistake")
test.update("hello world", at: 160)
check(test.isComplete && test.wpm(at: 200) == 2.2 && test.elapsed(at: 200) == 60, "completed test clock did not freeze")
check(test.samples.last?.wpm == 2.2 && test.mistakeTimes == [0], "final speed sample or mistake timestamp missing")
let testResult = test.result(at: Date(timeIntervalSince1970: 42))!
check(testResult.at == 42_000 && testResult.accuracy == 1, "result timestamp/accuracy differs")
test.update("changed", at: 201)
check(test.text == "hello world", "finished session accepted more input")
var wrongFinish = TypingSession(mode: .test, sentence: "abc")
wrongFinish.update("a", at: 0)
wrongFinish.update("axz", at: 2)
check(wrongFinish.isComplete && wrongFinish.mistakes == 2 && abs(wrongFinish.accuracy - 1.0 / 3) < 0.0001, "mistyped sentence cannot finish with honest accuracy")
var paste = TypingSession(mode: .test, sentence: "abc")
paste.update("abc", at: 0)
check(paste.wpm(at: 0) == 0 && paste.result(at: Date()) == nil, "instant paste saved infinite WPM")
let spelling = "hello cafe\u{301} wrng"
let misspelled = TypingSession.misspelledRanges(spelling, dictionary: ["hello", "café"])
check(misspelled.map { String(spelling[$0]) } == ["wrng"], "dictionary changed Unicode ranges or missed the final misspelt word")
check(TypingSentences.all.count == 40 && Set(TypingSentences.all).count == 40, "sentence bank is not 40 original entries")
check(TypingSentences.all.allSatisfy { (8...14).contains($0.split(separator: " ").count) }, "sentence length outside 8–14 words")
check(TypingSentences.next(after: TypingSentences.all[0]) != TypingSentences.all[0], "New sentence repeated the same sentence")
print("Layout storage, recovery, import and typing metrics checks passed.")


// Quick options shares ordering, identities and draft rules with the app's layout library.
withLayoutStore { prefs, defaults in
    let library = LayoutLibrary(settings: prefs, defaults: defaults)
    let saved = library.add(KeyGrid.makeDefault(), name: "Desk")!
    check(library.startDraft(from: "preset:THUMBS"), "quick draft failed")
    library.use(saved)
    let tiles = library.quickTiles
    check(tiles.map { $0.choice.id } == ["preset:STANDARD", "preset:SPLIT", "preset:THUMBS", saved, "draft"], "quick strip order differs from app")
    check(tiles.filter { $0.inUse }.count == 1, "quick panel marked multiple applied tiles")
    let applied = tiles.first { $0.inUse }!
    check(applied.choice.id == saved && applied.bold && applied.outlineWidth == 2, "quick applied marking missing outline/bold")
    check(applied.label == "Desk, in use", "VoiceOver applied label missing")
    library.use("draft")
    check(library.quickTiles.last?.label == "Custom, in use, unsaved", "draft VoiceOver label missing")
    let original = defaults.dictionaryRepresentation() as NSDictionary
    check(library.needsSaveFirst, "existing draft did not require Save first")
    check(library.quickEdit("preset:STANDARD", newCopy: false) == nil, "preset edit overwrote draft")
    check(library.quickEdit("draft", newCopy: true) == nil, "New overwrote draft")
    check(original.isEqual(defaults.dictionaryRepresentation() as NSDictionary), "cancel/save-first changed preferences")
    check(library.quickEdit("draft", newCopy: false) == "draft", "editing draft forked it")
    _ = library.saveDraft(name: library.nextName("Custom"))
    check(library.saved.last?.name == "Custom 1", "draft auto-name wrong")
    let preset = library.choice("preset:STANDARD")!.gridJSON
    check(library.quickEdit("preset:STANDARD", newCopy: false) == "draft", "preset edit did not fork")
    check(library.choice("draft")?.gridJSON == preset, "preset fork changed the source grid")
    check(library.choice("preset:STANDARD")?.gridJSON == preset, "preset was mutated")
    let changed = library.choice("draft")!.grid
    changed.items[0].keys[0] = "!"
    library.update("draft", grid: changed)
    check(prefs.loadGrid().items[0].keys[0] == "!", "draft edit did not update applied grid")
    let savedDraft = library.saveDraft(name: library.nextName("Custom"))!
    check(library.quickEdit(savedDraft, newCopy: false) == savedDraft, "saved edit changed identity")
    let savedChange = library.choice(savedDraft)!.grid
    savedChange.items[0].keys[0] = "?"
    library.update(savedDraft, grid: savedChange)
    check(library.choice(savedDraft)!.grid.items[0].keys[0] == "?" && library.draft == nil, "saved edit did not stay in place")
}

withLayoutStore { prefs, defaults in
    let library = LayoutLibrary(settings: prefs, defaults: defaults)
    for name in ["Custom 1", "Custom 3", "Imported 1", "Imported 3"] { _ = library.add(KeyGrid.makeDefault(), name: name) }
    check(library.nextName("Custom") == "Custom 2" && library.nextName("Imported") == "Imported 2", "auto-names did not fill the next free number")
    let emptyTexts: [String?] = [nil, "  "]
    for text in emptyTexts {
        if case .empty = library.importClipboard(text) {} else { fatalError("empty clipboard was not reported") }
    }
    for text in ["not a layout", "TK1-cutoff", "TK2-abc", "TK1-" + String(repeating: "A", count: 140_000)] {
        if case .invalid = library.importClipboard(text) {} else { fatalError("invalid clipboard accepted") }
    }
    let source = KeyGrid.template(.thumbs)
    if case .added(let key) = library.importClipboard("Shared layout: " + source.toCode()) {
        check(library.choice(key)?.name == "Imported 2", "clipboard auto-name wrong")
        check(LayoutLibrary.sameGrid(library.choice(key)!.grid, source), "clipboard layout changed")
    } else { fatalError("valid clipboard rejected") }
    var object = try! JSONSerialization.jsonObject(with: Data(source.toJSON().utf8)) as! [String: Any]
    object["name"] = "Travel"
    let named = shareCode(try! JSONSerialization.data(withJSONObject: object))
    if case .added(let key) = library.importClipboard(named) {
        check(library.choice(key)?.name == "Travel", "clipboard code name lost")
    } else { fatalError("named clipboard rejected") }
    check(library.appliedKey == "preset:STANDARD" && library.draft == nil, "clipboard import applied or created draft")
    _ = library.beginTrial("preset:SPLIT")
    let duringTrial = prefs.loadGrid().toJSON()
    let keyboardLibrary = prefs.makeLayoutLibrary()
    check(prefs.loadGrid().toJSON() == duringTrial && defaults.string(forKey: Prefs.layoutTrialPrevious) != nil, "keyboard panel cancelled an active typing trial")
    check(keyboardLibrary.draft == nil, "keyboard panel adopted the trial as a draft")
}
print("Quick options checks passed")

// Tablet findings: field privacy, sentence progress, and layout-owned split flags.
check(TextInputRules.canLearn(secure: false, autocorrectionDisabled: false), "ordinary fields should learn")
check(!TextInputRules.canLearn(secure: false, autocorrectionDisabled: true), "typing-test fields learned words")
check(!TextInputRules.canLearn(secure: true, autocorrectionDisabled: false), "secure fields learned words")
var progressSession = TypingSession(mode: .test, sentence: "hello")
check(progressSession.progress == 0, "new sentence progress is not empty")
progressSession.update("he", at: 0)
check(progressSession.progress == 0.4, "partial sentence progress differs")
check(progressSession.result(at: Date()) == nil, "abandoned sentence saved a result")
progressSession.update("hello extra", at: 2)
check(progressSession.progress == 1, "sentence progress exceeded its track")

withLayoutStore { prefs, defaults in
    let library = LayoutLibrary(settings: prefs, defaults: defaults)
    prefs.setSplitFor(false, true)
    prefs.setSplitFor(true, false)
    check(!library.choice("preset:STANDARD")!.split, "Standard inherited user's split")
    check(library.beginTrial("preset:STANDARD"), "applied Standard trial failed")
    check(!prefs.profile(false).split && !prefs.profile(true).split, "Standard trial kept user's split")
    check(prefs.split(false) && !prefs.split(true), "trial mutated orientation preferences")
    library.restoreTrial()
    check(prefs.profile(false).split && !prefs.profile(true).split, "trial did not restore both orientations")
    library.use("preset:STANDARD")
    check(!prefs.split(false), "using Standard kept split enabled")
    library.use("preset:SPLIT")
    check(prefs.split(false), "using Split failed to apply split")
    check(library.startDraft(from: "preset:SPLIT"), "cannot fork split preset")
    let saved = library.saveDraft(name: "Split custom")!
    library.use("preset:STANDARD")
    let reloaded = LayoutLibrary(settings: prefs, defaults: defaults)
    check(reloaded.choice(saved)!.split, "saved layout lost its stored split")
    check(reloaded.beginTrial(saved), "custom trial failed")
    check(prefs.profile(false).split && prefs.profile(true).split, "custom split not visible to extension")
    _ = LayoutLibrary(settings: prefs, defaults: defaults) // process-death recovery
    check(!prefs.profile(false).split && !prefs.profile(true).split, "recovery retained trial split")
    check(reloaded.beginTrial(saved), "second custom trial failed")
    reloaded.use(saved)
    reloaded.restoreTrial()
    check(prefs.split(false), "accepting trial restored away the chosen split")
    check(defaults.string(forKey: Prefs.layoutTrialSplit) == nil, "trial split marker leaked")
    check(defaults.object(forKey: Prefs.layoutDraftSplit) == nil, "draft split marker leaked")
}

for template in Template.allCases {
    let grid = KeyGrid.template(template)
    for code in [Keys.abc, Keys.num, Keys.sym, Keys.backspace, Keys.enter] {
        let key = grid.items.first { $0.keys.contains(code) }!
        check(!grid.remove(key) && grid.removalHint(key) != nil, "last required face removed")
    }
    check(grid.remove(grid.items.first { $0.isSpace }!), "duplicate Space was protected")
    check(!grid.remove(grid.items.first { $0.isSpace }!), "last Space was removed")
}
let compacted = KeyGrid.template(.split)
check(compacted.remove(compacted.items.first { $0.keys == ["e"] }!), "letter removal failed")
check(compacted.items.filter { $0.r == 0 && $0.c < 5 }.map { $0.c } == [0, 1, 2, 3], "row did not compact")
check(compacted.rowColumns(0, 0) == 4 && compacted.rowColumns(0, 6) == 5, "reflow crossed the split")
for code in [",", "."] { check(compacted.remove(compacted.items.first { $0.keys == [code] }!), "punctuation protected") }
check(KeyGrid.fromCode(compacted.toCode()).map { LayoutLibrary.sameGrid(compacted, $0) } == true, "removed layout code failed")

withLayoutStore { prefs, defaults in
    let library = LayoutLibrary(settings: prefs, defaults: defaults)
    prefs.setFor(false, Prefs.height, 51); prefs.setFor(true, Prefs.height, 65)
    prefs.setFor(true, Prefs.width, 79); prefs.setAlignFor(true, .right)
    prefs.set(Prefs.curve, 92); prefs.set(Prefs.cornerRadius, 15); prefs.theme = .warm
    let snapshot = prefs.layoutSettings()
    let a = library.add(compacted, name: "Desk")!
    prefs.theme = .dark; prefs.setFor(false, Prefs.height, 30)
    library.use(a)
    check(NSDictionary(dictionary: snapshot).isEqual(to: prefs.layoutSettings()), "layout settings did not apply")
    prefs.theme = .midnight; prefs.setFor(true, Prefs.tilt, -8)
    library.reload()
    let updated = library.choice(a)!
    check(updated.settings?["theme"] as? String == "MIDNIGHT", "Look did not sync")
    check(updated.settings?["tilt_l"] as? Int == -8, "landscape Size did not sync")
    let decoded = KeyGrid.decodeCode(updated.grid.toCode(settings: updated.settings))!
    check(NSDictionary(dictionary: decoded.settings!).isEqual(to: updated.settings!), "settings code round trip failed")
    let previous = prefs.layoutSettings()
    var other = previous; other["theme"] = "LIGHT"; other["height_l"] = 34
    let b = library.add(compacted, name: "Couch", snapshot: other)!
    check(library.beginTrial(b), "settings trial failed")
    check(prefs.theme == .light && prefs.int(Prefs.height(true)) == 34, "trial settings did not apply")
    prefs.theme = .dark
    _ = LayoutLibrary(settings: prefs, defaults: defaults)
    check(NSDictionary(dictionary: previous).isEqual(to: prefs.layoutSettings()), "crash recovery lost Size/Look")
    check(defaults.string(forKey: Prefs.layoutTrialSettings) == nil, "trial settings recovery record leaked")
    library.reload()
    check(library.choice(a)?.settings?["theme"] as? String == "MIDNIGHT", "trial overwrote previous layout settings")
    check(library.startDraft(from: a), "settings draft failed")
    library.use("draft"); prefs.set(Prefs.textScale, 135)
    let saved = library.saveDraft(name: "Draft")!
    check(library.choice(saved)?.settings?["text_scale"] as? Int == 135, "draft settings did not sync or save")
    library.use("preset:STANDARD")
    check(prefs.int(Prefs.textScale) == 135, "preset reset Look")
    var array = (try! JSONSerialization.jsonObject(with: Data(defaults.string(forKey: Prefs.layoutsSaved)!.utf8))) as! [[String: Any]]
    array[0].removeValue(forKey: "settings")
    defaults.set(String(decoding: try! JSONSerialization.data(withJSONObject: array), as: UTF8.self), forKey: Prefs.layoutsSaved)
    library.reload(); library.use(a)
    check(prefs.int(Prefs.textScale) == 135, "legacy entry reset current settings")
}

print("Tablet layout and typing checks passed")


check(CorrectionRules.eligible("teh"), "plain typo excluded")
check(!CorrectionRules.eligible("teH") && !CorrectionRules.eligible("teh2"), "protected token accepted")
check(CorrectionRules.smallEdit("hte", "the"), "initial transposition missed")
check(CorrectionRules.smallEdit("recieve", "receive"), "internal transposition missed")
let tidySource = "teh cat.  hello there"
let tidyFixes = TidyText.propose(tidySource) { word, _ in word == "teh" ? "the" : nil }
check(TidyText.apply(tidySource, fixes: tidyFixes) == "The cat. Hello there", "tidy proposal apply")
check(TidyText.apply(tidySource, fixes: []) == tidySource, "tidy unchecked fixes changed text")
let learningSuite = "app.tailorkeys.tests.learning." + UUID().uuidString
let learningStore = UserDefaults(suiteName: learningSuite)!
learningStore.set(["zorbles": 2, "hello": 3], forKey: "user_words")
learningStore.set(["say": ["zorbles": 2], "zorbles": ["hello": 1]], forKey: "user_bigrams")
learningStore.set(["please\tsay": ["zorbles": 2]], forKey: "user_trigrams")
LearningStore.forget(learningStore, language: "en", word: "zorbles")
check(LearningStore.words(learningStore).map { $0.word } == ["hello"], "individual forgetting")
check((LearningStore.data(learningStore, "en")["trigrams"] as? [String: [String: Int]])?["please\tsay"]?["zorbles"] == nil, "triple not forgotten")
LearningStore.forget(learningStore)
check(LearningStore.words(learningStore).isEmpty, "forget all languages")
learningStore.removePersistentDomain(forName: learningSuite)

check(TextInputRules.punctuation(".", "swipe ", "", automaticSpace: true, enabled: true, trailingSpace: false)?.insert == ".", "disabled automatic space leaked through punctuation")
check(TidyText.cursor(8, fixes: [TidyFix(start: 5, end: 7, original: "  ", replacement: " ")]) == 7, "tidy cursor mapping")

for width in [CGFloat(50), CGFloat(100)] {
    var board: [Character: CGPoint] = [:]
    for (row, letters) in ["qwertyuiop", "asdfghjkl", "zxcvbnm"].enumerated() {
        for (column, letter) in letters.enumerated() { board[letter] = CGPoint(x: (CGFloat(column) + CGFloat(row) * 0.35) * width, y: CGFloat(row) * width) }
    }
    for word in ["hello", "quick", "type", "keyboard"] {
        let trace = SwipeTrace(board, width), letters = Array(word)
        var time: TimeInterval = 0, index = 0
        var previous = board[letters[0]]!
        trace.sample(previous, time, index); index += 1
        for i in 1..<letters.count {
            let point = board[letters[i]]!
            if letters[i] == letters[i - 1] {
                time += 0.220; trace.sample(point, time, index); index += 1
            } else {
                for step in 1...20 {
                    time += 0.002
                    let fraction = CGFloat(step) / 20
                    trace.sample(CGPoint(x: previous.x + (point.x - previous.x) * fraction, y: previous.y + (point.y - previous.y) * fraction), time, index); index += 1
                }
                if i < letters.count - 1 { time += 0.120; trace.sample(point, time, index); index += 1 }
            }
            previous = point
        }
        check(trace.text() == word, "intended letters lost: " + word)
    }
}


// Rejection preferences are explicit, permanent, per language and removable.
let rejectionHistory = CorrectionHistory()
rejectionHistory.reject("teh", "the")
check(!rejectionHistory.blocked("teh", "the"), "one undo immediately banned pair")
rejectionHistory.reject("teh", "the")
check(rejectionHistory.blocked("teh", "the"), "two undos did not ban pair")
let restoredHistory = CorrectionHistory(); restoredHistory.load(rejectionHistory.serialize())
check(restoredHistory.blocked("teh", "the"), "pair did not survive serialization")
restoredHistory.remove("teh", "the")
check(!restoredHistory.blocked("teh", "the"), "pair removal failed")
restoredHistory.reject("recieve", "receive"); restoredHistory.typed("recieve")
check(restoredHistory.blocked("recieve", "receive"), "undo then retype did not ban pair")
check(restoredHistory.penalty("receive") > 0, "undo penalty missing")
let rejectionSuite = "app.tailorkeys.tests.rejections." + UUID().uuidString
let rejectionStore = UserDefaults(suiteName: rejectionSuite)!
rejectionStore.set(["corrections": restoredHistory.serialize(), "revision": ""], forKey: "user_bundle")
check(LearningStore.rejected(rejectionStore).count == 1, "never corrected list missing pair")
LearningStore.allowCorrection(rejectionStore, LearningStore.rejected(rejectionStore)[0])
check(LearningStore.rejected(rejectionStore).isEmpty, "allowing a correction failed")
rejectionStore.set(["corrections": restoredHistory.serialize(), "revision": rejectionStore.string(forKey: LearningStore.revisionKey) ?? ""], forKey: "user_bundle")
LearningStore.forget(rejectionStore)
check(LearningStore.rejected(rejectionStore).isEmpty, "forget all retained rejection history")
rejectionStore.removePersistentDomain(forName: rejectionSuite)
check(CorrectionRules.sentenceStart("Hello. i", 1), "sentence-start I context")
check(!CorrectionRules.sentenceStart("you and i", 1), "mid-sentence I context")
check(CorrectionRules.context("press the key board", 9) == ["press", "the"], "join context included removed word")
// Branching preserves the live recurrent state and matches an independently replayed state.
let liveBranchState = model.initialState(), copiedBranchState = model.initialState(), replayBranchState = model.initialState()
for word in ["<s>", "press", "the"] { model.step(liveBranchState, wordId: model.idOf(word)); model.step(replayBranchState, wordId: model.idOf(word)) }
model.copyState(liveBranchState, to: copiedBranchState)
var liveBeforeBranch = [Float](repeating: 0, count: model.vocabularySize), liveAfterBranch = liveBeforeBranch
var branchOutput = liveBeforeBranch, independentOutput = liveBeforeBranch
model.logProbs(liveBranchState, out: &liveBeforeBranch)
model.step(copiedBranchState, wordId: model.idOf("keyboard")); model.step(replayBranchState, wordId: model.idOf("keyboard"))
model.logProbs(copiedBranchState, out: &branchOutput); model.logProbs(replayBranchState, out: &independentOutput)
model.logProbs(liveBranchState, out: &liveAfterBranch)
check(zip(branchOutput, independentOutput).allSatisfy { abs($0.0 - $0.1) < 0.00001 }, "copied state changed phrase probabilities")
check(liveBeforeBranch == liveAfterBranch, "phrase scoring advanced live state")
