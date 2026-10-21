import SwiftUI
import Combine

struct TypingTestScreen: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var model: AppSettingsModel
    let choice: LayoutChoice
    let namespace: Namespace.ID
    let onBack: () -> Void
    @FocusState private var focused: Bool
    @State private var comparing = false
    private var tailorkeys: Bool { model.testKeyboard == "tailorkeys" }
    private var keyboardLabel: String { tailorkeys ? "TailorKeys" : "Other" }
    @State private var mode = TypingMode.free
    @State private var input = ""
    @State private var sentence: String
    @State private var session: TypingSession
    @State private var now = ProcessInfo.processInfo.systemUptime
    @State private var average: Double?
    @State private var recorded = false
    @State private var contentVisible = false
    @State private var departing = false
    @State private var keyboardVisible = false
    @State private var dictionary = Set<String>()
    @State private var dictionaryLoaded = false
    private let clock = Timer.publish(every: 0.25, on: .main, in: .common).autoconnect()

    init(model: AppSettingsModel, choice: LayoutChoice, namespace: Namespace.ID, onBack: @escaping () -> Void) {
        self.model = model
        self.choice = choice
        self.namespace = namespace
        self.onBack = onBack
        let sentence = TypingSentences.next(after: "")
        _sentence = State(initialValue: sentence)
        _session = State(initialValue: TypingSession(mode: .free, sentence: sentence, keyboard: model.testKeyboard))
        _average = State(initialValue: model.library.average(choice.id, keyboard: model.testKeyboard))
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            GeometryReader { geometry in
                ScrollView {
                    card(wide: geometry.size.width >= 600)
                        .padding(.horizontal, 16).padding(.vertical, 16)
                        .frame(maxWidth: .infinity, minHeight: geometry.size.height)
                }
            }
            if !keyboardVisible && tailorkeys {
                KeyboardPreview(model: model, landscape: nil, permitsEditing: false, previewsSelection: true)
                    .padding(.horizontal, 4).opacity(session.isComplete ? 0.35 : 1)
                    .layoutMorph("keyboard", in: namespace, enabled: tailorkeys && !reduceMotion)
            }
            if !keyboardVisible && !tailorkeys {
                VStack(spacing: 8) {
                    Image(systemName: "keyboard"); Text("Other keyboard area").font(.headline)
                    Text("Drawn by the system").font(.caption)
                }.frame(maxWidth: .infinity).frame(height: 160).background(Color.secondary.opacity(0.08))
            }
        }
        .foregroundColor(LayoutStyle.text)
        .background(LayoutStyle.background.ignoresSafeArea())
        .onReceive(clock) { _ in
            guard !session.isComplete else { return }
            now = ProcessInfo.processInfo.systemUptime
            session.sample(at: now)
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { notification in
            guard let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
            keyboardVisible = frame.intersects(UIScreen.main.bounds) && frame.minY < UIScreen.main.bounds.maxY - 40
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboardVisible = false }
        .onChange(of: model.testKeyboard) { id in
            session = session.forKeyboard(id)
            input = ""; recorded = false
            average = model.library.average(choice.id, keyboard: id)
            now = ProcessInfo.processInfo.systemUptime
            focused = true
        }
        .onChange(of: mode) { _ in reset(newSentence: false) }
        .onChange(of: input, perform: updateInput)
        .task {
            // Finish the shared-element morph before asking the system to present its keyboard.
            if !reduceMotion {
                try? await Task.sleep(nanoseconds: 150_000_000)
                guard !Task.isCancelled else { return }
                withAnimation(.timingCurve(0.05, 0.7, 0.1, 1, duration: 0.19)) { contentVisible = true }
                try? await Task.sleep(nanoseconds: 210_000_000)
            } else {
                withAnimation(.linear(duration: 0.12)) { contentVisible = true }
                try? await Task.sleep(nanoseconds: 120_000_000)
            }
            guard !Task.isCancelled, model.testing else { return }
            focused = true
            UIAccessibility.post(notification: .screenChanged, argument: "Typing test, " + choice.name)
        }
        .task(id: model.settings.currentLanguage) {
            let language = model.settings.currentLanguage
            dictionary = await Task.detached(priority: .utility) { TypingDictionary.load(language) }.value
            dictionaryLoaded = true
        }
        .onDisappear { focused = false }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Button(action: leave) { Image(systemName: "arrow.left").font(.title3).frame(width: 44, height: 44) }
                .buttonStyle(.plain).accessibilityLabel("Back to layouts")
            VStack(alignment: .leading, spacing: 2) {
                Text("Typing test").font(.title3.weight(.medium)).foregroundColor(LayoutStyle.text)
                Text(tailorkeys ? "TailorKeys · " + choice.name : "Typing with Other").font(.caption).foregroundColor(LayoutStyle.secondary)
            }
            Spacer()
            Menu {
                Button("TailorKeys") { model.setTestKeyboard("tailorkeys") }
                Button("Other") { model.setTestKeyboard("other") }
            } label: { Label("Which keyboard?", systemImage: "keyboard").font(.caption) }
                .accessibilityValue(keyboardLabel)
        }.padding(.horizontal, 12).frame(height: 64)
        .gesture(DragGesture().onEnded { drag in
            if drag.startLocation.x < 44 && drag.translation.width > 60 { leave() }
        })
    }

    private func card(wide: Bool) -> some View {
        VStack(alignment: .leading, spacing: wide ? 20 : 12) {
            Text("Hold the globe key to switch keyboards.").font(.caption).foregroundColor(LayoutStyle.secondary)
            HStack { Spacer(); Button(comparing ? "Back to test" : "Compare") { comparing.toggle(); if comparing { focused = false } } }
            if comparing { comparison }
            else if session.isComplete { results(wide: wide) }
            else { typing(wide: wide) }
        }
        .opacity(contentVisible ? 1 : 0)
        .offset(y: reduceMotion || contentVisible || departing ? 0 : 12)
        .animation(reduceMotion ? .linear(duration: 0.12) : .timingCurve(0.05, 0.7, 0.1, 1, duration: departing ? 0.09 : 0.19), value: contentVisible)
        .padding(wide ? 28 : 16).frame(maxWidth: 600)
        .background {
            RoundedRectangle(cornerRadius: 28).fill(LayoutStyle.surface)
                .layoutMorph("tile:" + choice.id, in: namespace, enabled: tailorkeys && !reduceMotion)
        }
    }

    private func typing(wide: Bool) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Picker("Typing mode", selection: $mode) {
                Text("Free typing").tag(TypingMode.free)
                Text("Test sentence").tag(TypingMode.test)
            }.pickerStyle(.segmented).frame(minHeight: 44)
            VStack(alignment: .leading, spacing: 8) {
                if mode == .test {
                    annotatedSentence(historical: false).font(wide ? .title2 : .body).lineSpacing(5)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityLabel("Sentence: " + sentence)
                    GeometryReader { geometry in
                        ZStack(alignment: .leading) {
                            Capsule().fill(LayoutStyle.text.opacity(0.12))
                            Capsule().fill(LayoutStyle.accent).frame(width: geometry.size.width * CGFloat(session.progress))
                        }
                    }.frame(height: 4)
                        .accessibilityValue("\(Int(session.progress * 100)) percent")
                        .accessibilityLabel("Sentence progress")
                } else if !input.isEmpty {
                    freeTranscript.font(wide ? .title2 : .body).lineSpacing(5)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityLabel("Your text: " + input)
                }
                TextField(mode == .free ? "Type anything" : "Type the sentence here", text: $input)
                    .focused($focused).font(.body).frame(minHeight: 44)
                    // The extension treats autocorrectionType .no as an opt-out from learning.
                    .textContentType(.none)
                    .textInputAutocapitalization(.sentences).disableAutocorrection(true)
                    .submitLabel(.done).onSubmit { focused = false }
                    .accessibilityLabel(mode == .free ? "Free typing area" : "Type the sample sentence")
            }
            .padding(12).background(RoundedRectangle(cornerRadius: 12).fill(LayoutStyle.background))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(LayoutStyle.outline.opacity(0.6), lineWidth: 1))
            Text(tailorkeys ? "Type on the keys below" : "Results save under Other")
                .font(.caption2).foregroundColor(LayoutStyle.secondary).lineLimit(1).minimumScaleFactor(0.6)
            if wide {
                HStack(alignment: .top, spacing: 16) {
                    speed(wide: true).frame(maxWidth: .infinity, alignment: .leading)
                    Divider()
                    accuracy
                    Divider()
                    mistakes
                    Divider()
                    time
                }.fixedSize(horizontal: false, vertical: true)
            } else {
                speed(wide: false)
                Divider().background(LayoutStyle.outline.opacity(0.4))
                smallMetrics
            }
            HStack {
                Button { reset(newSentence: mode == .test) } label: {
                    Label(mode == .free ? "Clear" : "New sentence", systemImage: "arrow.clockwise").frame(minHeight: 44)
                }.buttonStyle(.plain).foregroundColor(LayoutStyle.accent).font(.subheadline.weight(.medium))
                Spacer(minLength: 8)
                Text(mode == .free ? "Nothing is saved" : (tailorkeys ? "Type on the keys below" : "Results save under Other"))
                    .font(.caption).foregroundColor(LayoutStyle.muted)
            }
            if mode == .free && dictionaryLoaded && dictionary.isEmpty {
                Text("Dictionary unavailable. Spelling marks are off.").font(.caption).foregroundColor(LayoutStyle.muted)
            }
        }
    }

    private func speed(wide: Bool) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            metricTitle(mode == .free ? "Speed now" : "Words per minute")
            HStack(alignment: .lastTextBaseline, spacing: 6) {
                Text("\(Int(session.wpm(at: now).rounded()))").font(.system(size: wide ? 60 : 44, weight: .semibold)).monospacedDigit()
                Text("wpm").font(.caption).foregroundColor(LayoutStyle.secondary)
                if !wide {
                    Spacer()
                    SpeedChart(samples: session.samples, mistakeTimes: [], average: average).frame(width: 145, height: 40)
                }
            }
            if wide { SpeedChart(samples: session.samples, mistakeTimes: [], average: average).frame(height: 40) }
            Text(average.map { "avg \(Int($0.rounded())) · peak \(Int((session.samples.map(\.wpm).max() ?? 0).rounded()))" }
                 ?? (session.startedAt == nil ? "starts when you type" : "live average"))
                .font(.caption).foregroundColor(LayoutStyle.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Words per minute, \(Int(session.wpm(at: now).rounded()))" + (average.map { ", average \(Int($0.rounded()))" } ?? ""))
    }

    private var smallMetrics: some View {
        HStack(alignment: .top, spacing: 12) {
            accuracy.frame(maxWidth: .infinity, alignment: .leading)
            Divider()
            mistakes.frame(maxWidth: .infinity, alignment: .leading)
            Divider()
            time.frame(maxWidth: .infinity, alignment: .leading)
        }.fixedSize(horizontal: false, vertical: true)
    }

    private var accuracy: some View {
        VStack(alignment: .leading, spacing: 8) {
            metricTitle("Accuracy")
            ZStack {
                Circle().stroke(LayoutStyle.outline.opacity(0.4), lineWidth: 5)
                Circle().trim(from: 0, to: CGFloat(session.accuracy))
                    .stroke(LayoutStyle.success, style: StrokeStyle(lineWidth: 5, lineCap: .round)).rotationEffect(.degrees(-90))
                Text("\(Int((session.accuracy * 100).rounded()))%")
                    .font(.system(size: 12, weight: .bold)).monospacedDigit()
            }.frame(width: 46, height: 46).padding(3)
        }
        .accessibilityElement(children: .ignore).accessibilityLabel("Accuracy, \(Int((session.accuracy * 100).rounded())) percent")
    }

    private var mistakes: some View {
        VStack(alignment: .leading, spacing: 8) {
            metricTitle("Mistakes")
            Text("\(session.mistakes)").font(.system(size: 34, weight: .semibold)).monospacedDigit()
            HStack(spacing: 3) {
                ForEach(0..<min(8, session.mistakes), id: \.self) { _ in Capsule().fill(LayoutStyle.error).frame(width: 9, height: 3) }
            }.accessibilityHidden(true)
        }
        .accessibilityElement(children: .ignore).accessibilityLabel("Mistakes, \(session.mistakes)" + (mode == .free ? ", corrections" : ""))
    }

    private var time: some View {
        VStack(alignment: .leading, spacing: 8) {
            metricTitle("Time")
            Text(timeLabel).font(.system(size: 32, weight: .semibold)).monospacedDigit().minimumScaleFactor(0.7).lineLimit(1)
            Text(mode == .free ? "\(session.characterCount) characters" : "\(session.characterCount) / \(sentence.count) characters")
                .font(.caption).foregroundColor(LayoutStyle.secondary)
        }
        .accessibilityElement(children: .ignore).accessibilityLabel("Time, \(Int(session.elapsed(at: now))) seconds, \(session.characterCount) characters")
    }

    private func metricTitle(_ title: String) -> some View {
        Text(title.uppercased()).font(.system(size: 12, weight: .medium)).tracking(0.8)
            .foregroundColor(LayoutStyle.secondary).fixedSize(horizontal: false, vertical: true)
    }

    private var timeLabel: String {
        let seconds = Int(session.elapsed(at: now))
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    private func results(wide: Bool) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Label("Test complete", systemImage: "checkmark").font(.caption.weight(.semibold))
                    .padding(.horizontal, 12).frame(height: 28).foregroundColor(LayoutStyle.success)
                    .background(Capsule().fill(LayoutStyle.button))
                Spacer()
                Text(tailorkeys ? "TailorKeys · " + choice.name : "Typing with Other").font(.caption).foregroundColor(LayoutStyle.secondary)
            }
            HStack(alignment: .lastTextBaseline, spacing: 12) {
                Text("\(Int(session.wpm(at: now).rounded()))")
                    .font(.system(size: wide ? 104 : 72, weight: .semibold)).tracking(-2).monospacedDigit()
                VStack(alignment: .leading, spacing: 4) {
                    Text("words per minute").font(.body)
                    if let average = average {
                        let difference = Int((session.wpm(at: now) - average).rounded())
                        Text("\(abs(difference)) \(difference >= 0 ? "above" : "below") your average here (\(Int(average.rounded())))")
                            .font(.caption).foregroundColor(LayoutStyle.success)
                    }
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Test complete, \(Int(session.wpm(at: now).rounded())) words per minute")
            SpeedChart(samples: session.samples, mistakeTimes: session.mistakeTimes, average: average).frame(height: wide ? 80 : 60)
            HStack {
                Text("0:00")
                Spacer()
                Text("Dots mark mistakes")
                Spacer()
                Text(timeLabel)
            }.font(.caption2).foregroundColor(LayoutStyle.muted)
            smallMetrics
            VStack(alignment: .leading, spacing: 8) {
                metricTitle("Where it slipped")
                annotatedSentence(historical: true).font(.subheadline).lineSpacing(4)
            }.padding(12).frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 14).fill(LayoutStyle.background))
            comparison
            HStack {
                Button { reset(newSentence: true) } label: { Label("New sentence", systemImage: "arrow.clockwise") }
                    .buttonStyle(LayoutButtonStyle(primary: true))
                Button(action: model.keepTrialLayout) {
                    Label(choice.id == model.library.appliedKey ? "In use" : "Use layout", systemImage: "checkmark")
                }.buttonStyle(LayoutButtonStyle()).disabled(!tailorkeys || choice.id == model.library.appliedKey)
            }.frame(maxWidth: .infinity)
            Button(action: leave) { Label("Back to layouts", systemImage: "arrow.left").frame(maxWidth: .infinity, minHeight: 44) }
                .buttonStyle(.plain).foregroundColor(LayoutStyle.accent).font(.subheadline.weight(.medium))
        }
    }

    private var comparison: some View {
        let rows = model.library.keyboardAverages()
        return VStack(alignment: .leading, spacing: 10) {
            Text("Compare keyboards").font(.headline)
            Text("Saved Test sentence results").font(.caption2).foregroundColor(LayoutStyle.secondary)
            if rows.isEmpty { Text("Finish a test sentence to compare keyboards.").font(.subheadline) }
            HStack {
                Text("Keyboard").frame(maxWidth: .infinity, alignment: .leading)
                Text("Avg wpm").frame(width: 70); Text("Accuracy").frame(width: 65); Text("Tests").frame(width: 35)
            }.font(.caption2)
            ForEach(rows, id: \.keyboard) { row in
                HStack {
                    Text(row.keyboard == "tailorkeys" ? "TailorKeys" : "Other").fontWeight(row.keyboard == "tailorkeys" ? .bold : .regular).frame(maxWidth: .infinity, alignment: .leading)
                    VStack(spacing: 4) {
                        Text("\(Int(row.wpm.rounded()))")
                        ProgressView(value: row.wpm, total: max(1, rows.map(\.wpm).max() ?? 1)).tint(LayoutStyle.accent)
                    }.frame(width: 70)
                    Text("\(Int((row.accuracy * 100).rounded()))%").frame(width: 65)
                    Text("\(row.tests)").frame(width: 35)
                }.font(.caption).padding(10).background(RoundedRectangle(cornerRadius: 12).fill(row.keyboard == "tailorkeys" ? LayoutStyle.accent.opacity(0.14) : Color.clear))
            }
        }
    }

    private func annotatedSentence(historical: Bool) -> Text {
        let target = Array(sentence), typed = Array(session.text)
        return target.indices.reduce(Text("")) { text, index in
            let entered = index < typed.count
            let wrong = historical ? session.slippedPositions.contains(index) : (entered && typed[index] != target[index])
            let character = !historical && wrong ? typed[index] : target[index]
            return text + Text(String(character))
                .foregroundColor(wrong ? LayoutStyle.error : (entered || historical ? LayoutStyle.text : LayoutStyle.muted))
                .underline(wrong, color: LayoutStyle.error)
        }
    }

    private var freeTranscript: Text {
        let ranges = TypingSession.misspelledRanges(input, dictionary: dictionary)
        var text = Text("")
        var start = input.startIndex
        for range in ranges {
            text = text + Text(String(input[start..<range.lowerBound]))
            text = text + Text(String(input[range])).foregroundColor(LayoutStyle.error).underline(true, color: LayoutStyle.error)
            start = range.upperBound
        }
        return text + Text(String(input[start...]))
    }

    private func updateInput(_ value: String) {
        guard session.keyboard == model.testKeyboard else {
            session = session.forKeyboard(model.testKeyboard); input = ""; recorded = false
            return
        }
        now = ProcessInfo.processInfo.systemUptime
        session.update(value, at: now)
        if session.isComplete && !recorded {
            recorded = true
            if let result = session.result(at: Date()) { model.recordTest(result) }
            focused = false
            UIAccessibility.post(notification: .announcement, argument: "Test complete, \(Int(session.wpm(at: now).rounded())) words per minute")
        }
    }

    private func reset(newSentence: Bool) {
        if newSentence { sentence = TypingSentences.next(after: sentence) }
        input = ""
        now = ProcessInfo.processInfo.systemUptime
        session = TypingSession(mode: mode, sentence: sentence, keyboard: model.testKeyboard)
        average = model.library.average(choice.id, keyboard: model.testKeyboard)
        recorded = false
        focused = true
    }

    private func leave() {
        guard !departing else { return }
        departing = true
        contentVisible = false
        focused = false
        onBack()
    }
}

private struct SpeedChart: View {
    let samples: [SpeedSample]
    let mistakeTimes: [TimeInterval]
    let average: Double?

    var body: some View {
        Canvas { context, size in
            let duration = max(1, samples.last?.time ?? 1)
            let ceiling = max(1, max(samples.map(\.wpm).max() ?? 0, average ?? 0) * 1.15)
            func point(_ sample: SpeedSample) -> CGPoint {
                CGPoint(x: CGFloat(min(1, sample.time / duration)) * max(0, size.width - 8) + 4,
                        y: size.height - 4 - CGFloat(sample.wpm / ceiling) * max(0, size.height - 8))
            }
            if let average = average {
                let y = point(SpeedSample(time: 0, wpm: average)).y
                var guide = Path(); guide.move(to: CGPoint(x: 0, y: y)); guide.addLine(to: CGPoint(x: size.width, y: y))
                context.stroke(guide, with: .color(LayoutStyle.outline), style: StrokeStyle(lineWidth: 1, dash: [3, 4]))
            }
            let points = samples.map(point)
            guard let first = points.first, let last = points.last else { return }
            var line = Path(); line.move(to: first)
            for point in points.dropFirst() { line.addLine(to: point) }
            var area = line
            area.addLine(to: CGPoint(x: last.x, y: size.height)); area.addLine(to: CGPoint(x: first.x, y: size.height)); area.closeSubpath()
            context.fill(area, with: .color(LayoutStyle.accent.opacity(0.16)))
            context.stroke(line, with: .color(LayoutStyle.accent), style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            for time in mistakeTimes {
                let before = samples.last(where: { $0.time <= time }) ?? samples[0]
                let after = samples.first(where: { $0.time >= time }) ?? before
                let fraction = after.time > before.time ? (time - before.time) / (after.time - before.time) : 0
                let dot = point(SpeedSample(time: time, wpm: before.wpm + fraction * (after.wpm - before.wpm)))
                let circle = Path(ellipseIn: CGRect(x: dot.x - 4, y: dot.y - 4, width: 8, height: 8))
                context.fill(circle, with: .color(LayoutStyle.surface))
                context.stroke(circle, with: .color(LayoutStyle.error), lineWidth: 2)
            }
            if mistakeTimes.isEmpty {
                context.fill(Path(ellipseIn: CGRect(x: last.x - 4, y: last.y - 4, width: 8, height: 8)), with: .color(LayoutStyle.accent))
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Speed chart, \(samples.count) readings, \(mistakeTimes.count) mistake markers" + (average.map { ", average \(Int($0.rounded())) words per minute" } ?? ""))
    }
}

private enum TypingDictionary {
    private final class Words: NSObject {
        let values: Set<String>
        init(_ values: Set<String>) { self.values = values }
    }
    private static let cache = NSCache<NSString, Words>()

    static func load(_ language: String) -> Set<String> {
        if let words = cache.object(forKey: language as NSString) { return words.values }
        let suffix = language == "en" ? "" : "_" + language
        let plugins = Bundle.main.builtInPlugInsURL.flatMap { try? FileManager.default.contentsOfDirectory(at: $0, includingPropertiesForKeys: nil) } ?? []
        let bundles = [Bundle.main] + plugins.compactMap { Bundle(url: $0) }
        guard let url = bundles.compactMap({ $0.url(forResource: "words" + suffix, withExtension: "txt") }).first,
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        let values = Set(text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces).lowercased() })
        cache.setObject(Words(values), forKey: language as NSString)
        return values
    }
}
