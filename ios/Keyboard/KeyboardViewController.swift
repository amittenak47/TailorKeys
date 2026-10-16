import UIKit

/// Input view that allows the standard keyboard click sound.
final class ClickInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

final class KeyboardViewController: UIInputViewController, KeyboardViewDelegate {

    private var settings: KeyboardSettings!
    private var predictor: Predictor!
    private var gestureDecoder: GestureDecoder!
    private struct LanguageModel { let predictor: Predictor; let decoder: GestureDecoder; let chords: Chords }
    private var models: [String: LanguageModel] = [:]
    private var loading = Set<String>()
    private let loader = DispatchQueue(label: "app.tailorkeys.languages", qos: .userInitiated)
    private var modelReady = false

    private func makeModel(_ lang: String) -> LanguageModel {
        let p = Predictor(lang) { [weak self] in
            DispatchQueue.main.async { [weak self] in
                guard let self = self, self.state.language == lang, self.modelReady else { return }
                self.updateSuggestions()
            }
        }
        return LanguageModel(predictor: p, decoder: GestureDecoder(p.gestureWords()), chords: Chords(predictor: p))
    }

    private func activateLanguage() {
        let lang = settings.currentLanguage
        if state.language != lang {
            clearGlide()
            lastSpace = 0
            keyboard?.cancelTouches()
        }
        state.language = lang
        if let kb = keyboard { kb.grid = settings.loadGrid() }
        modelReady = models[lang] != nil
        keyboard?.chordLibrary = models[lang]?.chords
        if let model = models[lang] {
            predictor = model.predictor
            gestureDecoder = model.decoder
            if let kb = keyboard { predictor.setLayout(kb.grid) }
        } else if loading.insert(lang).inserted {
            loader.async { [weak self] in
                guard let self = self else { return }
                let model = self.makeModel(lang)
                DispatchQueue.main.async { [weak self] in
                    guard let self = self else { return }
                    self.models[lang] = model
                    self.loading.remove(lang)
                    if self.state.language == lang { self.activateLanguage(); self.updateSuggestions() }
                }
            }
        }
    }
    private var glideWord: String?
    private var glideBefore: String?
    private var glideDocument: UUID?
    private var glideCandidates: [String] = []
    private var glideExplore: [String] = []
    private var changingGlide = false
    private var suggestAllowed: Bool { textDocumentProxy.isSecureTextEntry != true }
    private let state = InputState()
    private var keyboard: KeyboardView!
    private var heightConstraint: NSLayoutConstraint?
    private var panel: SettingsPanel?
    private var lastSpace: CFTimeInterval = 0
    private var lastAbcTap: CFTimeInterval = 0

    override func loadView() {
        let v = ClickInputView(frame: .zero, inputViewStyle: .keyboard)
        v.allowsSelfSizing = true
        view = v
        inputView = v
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        let hc = view.heightAnchor.constraint(equalToConstant: 260)
        hc.priority = UILayoutPriority(999)
        hc.isActive = true
        heightConstraint = hc
    }

    /// Full Access is reliable here, after the input view has been loaded.
    private func loadKeyboardIfNeeded() {
        if settings != nil {
            settings.reloadStore(hasFullAccess: hasFullAccess)
            keyboard.grid = settings.loadGrid()
            activateLanguage()
            return
        }
        settings = KeyboardSettings(tablet: UIDevice.current.userInterfaceIdiom == .pad, hasFullAccess: hasFullAccess)
        state.language = settings.currentLanguage
        let initial = makeModel(state.language)
        models[state.language] = initial
        predictor = initial.predictor
        gestureDecoder = initial.decoder
        modelReady = true
        let kb = KeyboardView(settings: settings, grid: settings.loadGrid())
        kb.state = state
        kb.chordLibrary = initial.chords
        kb.delegate = self
        kb.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(kb)
        NSLayoutConstraint.activate([
            kb.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            kb.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            kb.topAnchor.constraint(equalTo: view.topAnchor),
            kb.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        kb.onHeightChange = { [weak self] h in
            if self?.heightConstraint?.constant != h { self?.heightConstraint?.constant = h }
        }
        keyboard = kb
        _ = gestureDecoder // Build the dictionary index before the first finger gesture.
        predictor.setLayout(kb.grid)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        loadKeyboardIfNeeded()
        if hasFullAccess, let group = UserDefaults(suiteName: KeyboardSettings.groupID) {
            group.set(Date(), forKey: KeyboardSettings.fullAccessSeenKey)
        }
        // A panel left open across appearances must reflect the freshly loaded store too.
        if panel != nil { panel?.removeFromSuperview(); panel = nil; openSettings() }
        let numeric: [UIKeyboardType] = [.numberPad, .decimalPad, .phonePad, .numbersAndPunctuation, .asciiCapableNumberPad]
        state.layer = numeric.contains(textDocumentProxy.keyboardType ?? .default) ? .num : .abc
        clearGlide()
        state.shift = false
        state.capsLock = false
        keyboard.enterLabel = enterLabel()
        keyboard.reloadSettings()
        updateAutoCap()
        updateSuggestions()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard let keyboard = keyboard else { return }
        let h = keyboard.height(forWidth: view.bounds.width)
        if heightConstraint?.constant != h { heightConstraint?.constant = h }
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in
            self?.keyboard?.relayout()
            self?.panel?.orientationChanged()
        }
    }

    override func textDidChange(_ textInput: UITextInput?) {
        if changingGlide { return }
        if settings != nil && (state.language != settings.currentLanguage || predictor?.language != settings.currentLanguage) { activateLanguage() }
        updateAutoCap()
        updateSuggestions()
    }

    // MARK: - KeyboardViewDelegate

    func chordTyped(_ word: String) {
        changingGlide = true
        defer { changingGlide = false }
        let shifted = state.shift
        if let last = textDocumentProxy.documentContextBeforeInput?.last, last.isLetter || last.isNumber { typeSpace() }
        updateAutoCap()
        state.shift = state.shift || shifted
        let upper = state.capsLock || (settings.autoCap && textDocumentProxy.autocapitalizationType == .allCharacters)
        let text = upper ? word.uppercased() : (state.shift ? word.prefix(1).uppercased() + word.dropFirst() : word)
        clearGlide()
        insert(text)
        typeSpace()
        updateAutoCap()
        updateSuggestions()
        keyboard.setNeedsDisplay()
    }

    func keyTapped(_ code: String) {
        switch code {
        case Keys.backspace: clearGlide(); textDocumentProxy.deleteBackward()
        case Keys.space: typeSpace()
        case Keys.enter: finishGlideLearning(); clearGlide(); textDocumentProxy.insertText("\n")
        default:
            if KeyText.isLayerKey(code) { tapLayerKey(code) } else {
                if state.layer == .abc && code.count == 1 && code.first!.isLetter && currentGlide() {
                    let shifted = state.shift
                    typeSpace()
                    updateAutoCap()
                    state.shift = state.shift || shifted
                }
                if let s = KeyText.output(code, state) { insert(s) }
            }
        }
        // No typo buzz on iOS: keyboards can't vibrate without Full Access.
        updateAutoCap()
        updateSuggestions()
        keyboard.setNeedsDisplay()
    }

    func moveCursor(_ steps: Int) {
        clearGlide()
        textDocumentProxy.adjustTextPosition(byCharacterOffset: steps)
        updateAutoCap()
        updateSuggestions()
    }

    func longPressText(_ text: String) {
        insert(text)
        updateSuggestions()
    }

    /// A swipe key landed on a new face. Layer faces switch the layout straight away.
    func stackFace(_ code: String) {
        if KeyText.isLayerKey(code) {
            state.layer = KeyText.layerOf(code)
            state.shift = false
            updateSuggestions()
        }
        keyboard.setNeedsDisplay()
    }

    /// A word from a wheel replaces the word being typed. A symbol is just inserted.
    func suggestionPicked(_ text: String) {
        if state.layer == .abc && currentGlide() {
            changingGlide = true
            defer { changingGlide = false }
            for _ in 0..<glideWord!.count { textDocumentProxy.deleteBackward() }
            textDocumentProxy.insertText(text)
            glideWord = text
            glideBefore = textDocumentProxy.documentContextBeforeInput
            updateAutoCap()
            updateSuggestions()
            return
        }
        if state.layer != .abc {
            insert(text)
        } else {
            clearGlide()
            let before = textDocumentProxy.documentContextBeforeInput ?? ""
            let prefix = currentWord(before)
            let prev = previousWord(before)
            for _ in 0..<prefix.count { textDocumentProxy.deleteBackward() }
            textDocumentProxy.insertText(text + " ")
            if suggestAllowed && modelReady { predictor.learn(text, prev, previousWord2(before)) }
        }
        updateAutoCap()
        updateSuggestions()
    }

    func glideFinished(_ points: [GlidePoint], _ keys: [Character: CGPoint], _ keyWidth: CGFloat) {
        guard modelReady, suggestAllowed, settings.swipeTyping, state.layer == .abc else { return }
        gestureDecoder.addWords(predictor.gestureUserWords())
        gestureDecoder.setKeys(keys, keyWidth)
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        let needsSpace = before.last.map { $0.isLetter || $0.isNumber } ?? false
        let prev = previousWord(needsSpace ? before + " " : before)
        let matches = gestureDecoder.decode(points, prev, frequency: predictor.gestureFrequency, context: predictor.contextScore)
        guard !matches.isEmpty else { return }
        changingGlide = true
        defer { changingGlide = false }
        let shifted = state.shift
        if needsSpace { typeSpace() }
        updateAutoCap()
        state.shift = state.shift || shifted
        let upper = state.capsLock || (settings.autoCap && textDocumentProxy.autocapitalizationType == .allCharacters)
        let capital = state.shift
        let candidates = matches.map { match -> String in
            if upper { return match.word.uppercased() }
            if capital { return match.word.prefix(1).uppercased() + match.word.dropFirst() }
            return match.word
        }
        clearGlide()
        insert(candidates[0])
        glideWord = candidates[0]
        glideCandidates = candidates
        var rng = SplitMix(seed: candidates[0].utf8.reduce(5381) { ($0 &* 33) &+ UInt64($1) })
        // Weighted sampling at temperature 2.5, restricted to alternatives beyond the right wheel.
        glideExplore = matches.indices.dropFirst(8).map { i -> (String, Double) in
            let u = max(1e-12, Double.random(in: 0..<1, using: &rng))
            return (candidates[i], matches[i].score / 2.5 - log(-log(u)))
        }.sorted { $0.1 > $1.1 }.map { $0.0 }
        glideBefore = textDocumentProxy.documentContextBeforeInput
        glideDocument = textDocumentProxy.documentIdentifier
        lastSpace = 0
        updateSuggestions()
        keyboard.setNeedsDisplay()
    }

    private func clearGlide() {
        glideWord = nil; glideBefore = nil; glideDocument = nil; glideCandidates = []; glideExplore = []
    }

    private func currentGlide() -> Bool {
        guard glideWord != nil else { return false }
        if textDocumentProxy.documentIdentifier == glideDocument,
           (textDocumentProxy.selectedText ?? "").isEmpty,
           textDocumentProxy.documentContextBeforeInput == glideBefore { return true }
        clearGlide()
        return false
    }

    private func finishGlideLearning() {
        if currentGlide() && suggestAllowed && modelReady { predictor.learn(glideWord!, previousWord(textDocumentProxy.documentContextBeforeInput ?? "")) }
    }

    func spaceLongPressed() { advanceToNextInputMode() }

    func switchLanguage(_ direction: Int) {
        let enabled = settings.languages
        guard enabled.count > 1, let index = enabled.firstIndex(of: settings.currentLanguage) else { return }
        settings.currentLanguage = enabled[(index + direction + enabled.count) % enabled.count]
        activateLanguage()
        keyboard.reloadSettings()
        updateSuggestions()
        UIAccessibility.post(notification: .announcement, argument: Prefs.languageNames[state.language])
    }

    /// The gear in the rearranging toolbar: leaves rearranging and shows the settings panel.
    func openSettings() {
        keyboard.editMode = false
        guard panel == nil else { return }
        let landscape = UIScreen.main.bounds.width > UIScreen.main.bounds.height
        let p = SettingsPanel(settings: settings, landscape: landscape)
        p.onChange = { [weak self] in
            self?.keyboard.reloadSettings()
            self?.updateSuggestions()
        }
        p.onClose = { [weak self] in self?.closePanel() }
        p.onRearrange = { [weak self] in self?.rearrange() }
        p.onTemplate = { [weak self] t in
            guard let self = self else { return }
            let g = KeyGrid.template(t, self.settings.currentLanguage)
            if self.settings.numberRow { g.setNumberRow(true) }
            self.keyboard.replaceGrid(g)
            if t.split { self.settings.setSplitFor(landscape, true) }
            self.rearrange()
        }
        // Without Full Access a keyboard can't use the clipboard, so codes go through the text field instead.
        p.onTypeCode = { [weak self] in
            guard let self = self else { return }
            self.textDocumentProxy.insertText(self.keyboard.grid.toCode())
            self.closePanel()
        }
        p.onImportCode = { [weak self] in
            guard let self = self else { return false }
            let proxy = self.textDocumentProxy
            let text = (proxy.documentContextBeforeInput ?? "") + (proxy.documentContextAfterInput ?? "")
            guard let g = KeyGrid.fromCode(text) else { return false }
            self.keyboard.replaceGrid(g)
            self.rearrange()
            return true
        }
        p.onResetLayout = { [weak self] in
            guard let self = self else { return }
            let g = KeyGrid.makeDefault(self.settings.currentLanguage)
            if self.settings.numberRow { g.setNumberRow(true) }
            self.settings.saveGrid(g)
            self.keyboard.grid = g
            self.predictor.setLayout(g)
        }
        p.onNumberRow = { [weak self] on in
            guard let self = self else { return false }
            let g = self.settings.loadGrid()
            if !g.setNumberRow(on) { return false }
            self.settings.saveGrid(g)
            self.settings.set(Prefs.numberRow, on)
            self.keyboard.grid = g
            self.predictor.setLayout(g)
            return true
        }
        p.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(p)
        NSLayoutConstraint.activate([
            p.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            p.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            p.topAnchor.constraint(equalTo: view.topAnchor),
            p.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        panel = p
    }

    private func closePanel() {
        panel?.removeFromSuperview()
        panel = nil
        keyboard.reloadSettings()
    }

    /// Closes the panel and shows the keys wiggling, with undo in the toolbar.
    private func rearrange() {
        closePanel()
        keyboard.editMode = true
    }

    func gridEdited() {
        predictor.setLayout(keyboard.grid)
        settings.saveGrid(keyboard.grid)
    }

    // MARK: - Typing

    private func insert(_ s: String) {
        if currentGlide() {
            if s.first?.isLetter == true { typeSpace() }
            else if s.contains(where: { !$0.isLetter && !$0.isNumber && $0 != "'" }) { finishGlideLearning() }
        }
        clearGlide()
        textDocumentProxy.insertText(s)
        if state.shift && !state.capsLock { state.shift = false }
    }

    /// On ABC: tap for a one-shot capital, double-tap for caps lock, tap again to clear. Elsewhere: switch layer.
    private func tapLayerKey(_ code: String) {
        if code == Keys.abc && state.layer == .abc {
            let now = CACurrentMediaTime()
            if state.capsLock { state.capsLock = false; state.shift = false }
            else if state.shift && now - lastAbcTap < 0.35 { state.capsLock = true; state.shift = false }
            else { state.shift.toggle() }
            lastAbcTap = now
        } else {
            state.layer = KeyText.layerOf(code)
            state.shift = false
        }
    }

    private func typeSpace() {
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        let word = currentWord(before)
        if suggestAllowed && modelReady && word.count > 1 { predictor.learn(word, previousWord(before), previousWord2(before)) }
        clearGlide()
        let now = CACurrentMediaTime()
        if settings.doubleSpace && state.layer == .abc && now - lastSpace < 0.6,
           before.hasSuffix(" "), let prev = before.dropLast().last, prev.isLetter || prev.isNumber {
            textDocumentProxy.deleteBackward()
            textDocumentProxy.insertText(". ")
            lastSpace = 0
            return
        }
        textDocumentProxy.insertText(" ")
        lastSpace = now
    }

    private func enterLabel() -> String? {
        switch textDocumentProxy.returnKeyType ?? .default {
        case .go: return "Go"
        case .search, .google, .yahoo: return "Search"
        case .send: return "Send"
        case .next: return "Next"
        case .done: return "Done"
        case .join: return "Join"
        default: return nil
        }
    }

    private func currentWord(_ before: String) -> String {
        var chars: [Character] = []
        for ch in before.reversed() {
            if ch.isLetter || ch == "'" { chars.append(ch) } else { break }
        }
        return String(chars.reversed())
    }

    private func previousWord(_ before: String) -> String? {
        var remaining = before.dropLast(currentWord(before).count)
        while let ch = remaining.last {
            if ch == "." || ch == "!" || ch == "?" || ch.isNewline { return nil }
            if ch.isLetter || ch == "'" { break }
            remaining.removeLast()
        }
        let word = currentWord(String(remaining)).lowercased()
        return word.isEmpty ? nil : word
    }

    private func previousWord2(_ before: String) -> String? {
        var remaining = before.dropLast(currentWord(before).count)
        while let ch = remaining.last {
            if ch == "." || ch == "!" || ch == "?" || ch.isNewline { return nil }
            if ch.isLetter || ch == "'" { break }
            remaining.removeLast()
        }
        // previousWord skips this completed previous word and applies the same reset rules.
        return previousWord(String(remaining))
    }

    private func updateSuggestions() {
        guard keyboard != nil else { return }
        if !modelReady {
            keyboard.keyWeights = [:]
            keyboard.setSuggestions(left: [], right: [], prefix: "")
            return
        }
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        keyboard.glideAllowed = suggestAllowed
        if !settings.swipeTyping { clearGlide() }
        if !suggestAllowed {
            clearGlide()
            keyboard.keyWeights = [:]
            keyboard.setSuggestions(left: [], right: [], prefix: "")
            return
        }
        if state.layer != .abc {
            keyboard.keyWeights = [:]
            let s = predictor.symbols(before: before.last)
            keyboard.setSuggestions(left: s.explore, right: s.closest, prefix: "")
            return
        }
        if currentGlide() {
            keyboard.keyWeights = [:]
            keyboard.setSuggestions(left: glideExplore, right: Array(glideCandidates.prefix(8)), prefix: "")
            return
        }
        let prefix = currentWord(before)
        let prev = previousWord(before)
        let prev2 = previousWord2(before)
        keyboard.keyWeights = (settings.keyTint || settings.smartTargets) ? predictor.nextLetters(prefix, prev, prev2) : [:]
        keyboard.setSuggestions(left: predictor.explore(prefix, prev, prev2), right: predictor.closest(prefix, prev, prev2), prefix: prefix)
    }

    /// Turns on a one-shot capital where the field asks for one: sentence starts, word starts, or always.
    private func updateAutoCap() {
        guard keyboard != nil, settings.autoCap, state.layer == .abc, !state.capsLock else { return }
        let type = textDocumentProxy.autocapitalizationType ?? .sentences
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        switch type {
        case .none: state.shift = false
        case .allCharacters: state.shift = true
        case .words: state.shift = before.isEmpty || before.last == " " || before.last == "\n"
        default:
            let trimmed = before.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty || before.last == "\n" { state.shift = true }
            else if before.last == " ", let end = trimmed.last { state.shift = ".!?".contains(end) }
            else { state.shift = false }
        }
        keyboard.setNeedsDisplay()
    }
}
