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
