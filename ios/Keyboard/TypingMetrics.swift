import Foundation

enum TypingMode: String, CaseIterable { case free, test }

struct SpeedSample {
    let time: TimeInterval
    let wpm: Double
}

/// Counts text changes in Characters (including composed Unicode), not UTF-16 units.
/// IME replacements are normal typing. Only deletion-only edits count as free-mode corrections.
struct TypingSession {
    let mode: TypingMode
    let sentence: String
    let keyboard: String
    private(set) var text = ""
    private(set) var startedAt: TimeInterval?
    private(set) var finishedAt: TimeInterval?
    private(set) var inserted = 0
    private(set) var deleted = 0
    private(set) var mistakes = 0
    private(set) var mistakeTimes: [TimeInterval] = []
    private(set) var slippedPositions = Set<Int>()
    private(set) var samples: [SpeedSample] = []
    private struct ProgressPoint { let time: TimeInterval; let characters: Int }
    private var progressPoints: [ProgressPoint] = []
    private var typedCharacters = 0
    private var assistedEdit: (before: String, after: String)?
    private(set) var peakWpm = 0.0

    init(mode: TypingMode, sentence: String, keyboard: String = "tailorkeys") {
        self.mode = mode; self.sentence = sentence; self.keyboard = keyboard
    }
    func forKeyboard(_ id: String) -> TypingSession {
        keyboard == id ? self : TypingSession(mode: mode, sentence: sentence, keyboard: id)
    }

    var isComplete: Bool { finishedAt != nil }
    var characterCount: Int { text.count }
    var progress: Double { mode == .test && !sentence.isEmpty ? min(1, Double(characterCount) / Double(sentence.count)) : 0 }

    func elapsed(at now: TimeInterval) -> TimeInterval {
        guard let start = startedAt else { return 0 }
        return max(0, (finishedAt ?? now) - start)
    }

    static func wpm(characters: Int, seconds: TimeInterval) -> Double {
        guard seconds.isFinite, seconds > 0, characters > 0 else { return 0 }
        return Double(characters) * 12 / seconds
    }

    func wpm(at now: TimeInterval) -> Double { Self.wpm(characters: characterCount, seconds: elapsed(at: now)) }

    var accuracy: Double {
        if mode == .free { return inserted == 0 ? 1 : max(0, Double(inserted - deleted) / Double(inserted)) }
        let target = Array(sentence), typed = Array(text)
        guard !typed.isEmpty else { return 1 }
        let correct = typed.indices.filter { $0 < target.count && typed[$0] == target[$0] }.count
        return Double(correct) / Double(typed.count)
    }

    mutating func update(_ value: String, at now: TimeInterval) {
        guard !isComplete, value != text, now.isFinite else { return }
        let before = Array(text), after = Array(value), target = Array(sentence)
        var prefix = 0, suffix = 0
        while prefix < min(before.count, after.count) && before[prefix] == after[prefix] { prefix += 1 }
        while suffix < min(before.count, after.count) - prefix && before[before.count - suffix - 1] == after[after.count - suffix - 1] { suffix += 1 }
        let added = after.count - prefix - suffix
        let removed = before.count - prefix - suffix
        if startedAt == nil && added > 0 {
            startedAt = now; samples = [SpeedSample(time: 0, wpm: 0)]
            progressPoints = [ProgressPoint(time: now, characters: 0)]
        }
        let undo = assistedEdit.map { text == $0.after && value == $0.before } ?? false
        let corrections = mode == .free && (added > 0 || undo) ? 0 : removed
        inserted += added
        deleted += corrections
        var errors = mode == .free ? corrections : 0
        if added > 0 && (removed > 0 || prefix > 0 && suffix > 0) { assistedEdit = (text, value) }
        else if undo { assistedEdit = (value, text) }
        else { assistedEdit = nil }
        typedCharacters += max(0, added - removed)
        if startedAt != nil { progressPoints.append(ProgressPoint(time: now, characters: typedCharacters)) }
        if mode == .test && added > 0 {
            for index in prefix..<(prefix + added) where index >= target.count || after[index] != target[index] {
                errors += 1
                slippedPositions.insert(index)
            }
        }
        mistakes += errors
        if errors > 0 { mistakeTimes += Array(repeating: elapsed(at: now), count: errors) }
        text = value
        if mode == .test && !target.isEmpty && after.count >= target.count { finishedAt = now }
        sample(at: now, force: isComplete)
    }

    mutating func sample(at now: TimeInterval, force: Bool = false) {
        guard startedAt != nil else { return }
        let time = elapsed(at: now)
        let speed = rollingWpm(at: finishedAt ?? now)
        peakWpm = max(peakWpm, speed)
        guard force || time - (samples.last?.time ?? 0) >= 1 else { return }
        let point = SpeedSample(time: time, wpm: speed)
        if samples.last?.time == time { samples[samples.count - 1] = point }
        else { samples.append(point) }
        // Free typing may run indefinitely; bound the chart without changing totals.
        if samples.count > 1200 { samples.removeFirst(samples.count - 1200) }
    }

    /// Expand the trailing five seconds until at least ten characters of progress are included.
    private mutating func rollingWpm(at now: TimeInterval) -> Double {
        guard let start = startedAt, now - start >= 5, typedCharacters >= 10 else { return 0 }
        var baseline = 0
        while baseline + 1 < progressPoints.count {
            let next = progressPoints[baseline + 1]
            if next.time > now - 5 || next.characters > typedCharacters - 10 { break }
            baseline += 1
        }
        if baseline > 0 { progressPoints.removeFirst(baseline) }
        guard let first = progressPoints.first else { return 0 }
        let duration = progressPoints.count == 1 || progressPoints[1].time > now - 5 ? 5 : now - first.time
        return Self.wpm(characters: typedCharacters - first.characters, seconds: duration)
    }

    func result(at date: Date) -> TypingResult? {
        guard mode == .test, let end = finishedAt, elapsed(at: end) > 0 else { return nil }
        return TypingResult(wpm: wpm(at: end), accuracy: accuracy, at: date.timeIntervalSince1970 * 1000, keyboard: keyboard)
    }

    static func misspelledRanges(_ text: String, dictionary: Set<String>) -> [Range<String.Index>] {
        guard !dictionary.isEmpty, let regex = try? NSRegularExpression(pattern: #"[\p{L}\p{M}]+(?:['’][\p{L}\p{M}]+)*"#) else { return [] }
        return regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap { match in
            guard let range = Range(match.range, in: text),
                  !dictionary.contains(String(text[range]).lowercased().replacingOccurrences(of: "’", with: "'")) else { return nil }
            return range
        }
    }
}

enum TypingSentences {
    static let all = [
        "The morning light warmed the kitchen while the kettle boiled.",
        "Please leave the blue umbrella beside the front door today.",
        "We found a quiet table near the open window.",
        "My neighbor brought fresh peaches from the market this afternoon.",
        "A little rain made the garden smell sweet again.",
        "The dog waited patiently while I tied my walking shoes.",
        "I wrote a short note and tucked it into your bag.",
        "Our train passed a field of yellow flowers before sunset.",
        "You can keep the extra blanket on the bottom shelf.",
        "She hummed a tune while folding the clean towels.",
        "The small cafe serves warm bread with every bowl of soup.",
        "We walked home slowly beneath the bright evening sky.",
        "Put the green folder next to the lamp on my desk.",
        "His favorite mug was waiting beside the coffee pot.",
        "I will pick up some apples on my way home.",
        "The children built a tiny village from empty cardboard boxes.",
        "A cool breeze moved the curtains across the sunny room.",
        "Let us meet at the corner after your last class.",
        "Someone left a cheerful message on the kitchen calendar.",
        "The old bicycle still rides smoothly along the river path.",
        "Please bring a jacket because the evening might turn cold.",
        "We shared the last slice of cake after dinner.",
        "Her new plant has grown two small leaves this week.",
        "The library is a lovely place to spend a rainy afternoon.",
        "I found your missing glove underneath the passenger seat.",
        "Every Sunday we make pancakes and read the local paper.",
        "A friendly cat followed us halfway down the narrow street.",
        "The grocery list is pinned above the little wooden table.",
        "Our friends arrived early and helped us prepare the salad.",
        "Take a deep breath before you start the next sentence.",
        "The silver moon appeared above the rooftops just after dinner.",
        "He kept a spare notebook in the pocket of his coat.",
        "We watched the boats drift past the bridge this morning.",
        "Your fresh tea is on the tray beside the sofa.",
        "The bus stop is only a short walk from here.",
        "I opened the window to hear the birds outside.",
        "They planted a row of herbs along the garden fence.",
        "A warm scarf makes the chilly morning walk more pleasant.",
        "We packed a simple lunch and headed toward the lake.",
        "Please save me a seat near the front tomorrow morning."
    ]

    static func next(after previous: String) -> String {
        all.filter { $0 != previous }.randomElement() ?? all[0]
    }
}
