import UIKit

/// Input view that allows the standard keyboard click sound.
final class ClickInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

final class KeyboardViewController: UIInputViewController, KeyboardViewDelegate {

    private var settings: KeyboardSettings!
    private var predictor: Predictor!
    private var gestureDecoder: GestureDecoder!
    private var loadingLanguage: String?
    private var loadGeneration: UInt64 = 0
    private var modelReady = false
    private var modelDocument: UUID?
    private var keyboardVisible = false
    private var settingsFullAccess: Bool?

    private func evictModel() {
        predictor?.deactivate()
        predictor = nil; gestureDecoder = nil
        keyboard?.chordLibrary = nil
        modelReady = false
    }

    private func activateLanguage() {
        let lang = settings.currentLanguage
        let languageChanged = state.language != lang
        if languageChanged {
            clearTaps(); choiceInterrupted(); clearGlide()
            lastSpace = 0; lastAbcTap = 0
            keyboard?.cancelTouches()
        }
        state.language = lang
        if predictor?.language == lang {
            modelReady = true
            return
        }
        // At most one installed language. A serial in-flight load is discarded before the
        // newest requested language is built, so rapid switching cannot queue whole models.
        evictModel()
        if languageChanged, let kb = keyboard { kb.grid = settings.loadGrid() }
        guard keyboardVisible, loadingLanguage == nil else { return }
        loadingLanguage = lang
        let generation = loadGeneration
        LanguageModelFactory.load(lang, store: settings.learningStore, onReady: { [weak self] in
            guard let self = self, self.state.language == lang, self.modelReady else { return }
            self.updateSuggestions()
        }, completion: { [weak self] model in
            guard let self = self else { model.predictor.deactivate(); return }
            self.loadingLanguage = nil
            guard generation == self.loadGeneration, self.settings.currentLanguage == lang, self.keyboardVisible else {
                model.predictor.deactivate()
                // Leave this callback's model behind before starting the next allocation.
                if self.keyboardVisible { DispatchQueue.main.async { [weak self] in self?.activateLanguage() } }
                return
            }
            self.predictor = model.predictor; self.gestureDecoder = model.decoder
            self.keyboard?.chordLibrary = model.chords
            self.modelReady = true
            if let kb = self.keyboard { model.predictor.setLayout(kb.grid, kb.letterSpots) }
            self.beginDocumentOperation()
            defer { self.endDocumentOperation() }
            self.updateAutoCap(); self.updateSuggestions()
        })
    }
    private struct DocumentSnapshot: Equatable {
        let before: String?
        let after: String?
        let selected: String?
        let identifier: UUID
    }
    private var documentDepth = 0
    private var documentCache: DocumentSnapshot?
    private var refreshedDocument: DocumentSnapshot?
    private var document: DocumentSnapshot {
        if let cached = documentCache, documentDepth > 0 { return cached }
        let proxy = textDocumentProxy
        let value = DocumentSnapshot(before: proxy.documentContextBeforeInput, after: proxy.documentContextAfterInput,
                                     selected: proxy.selectedText, identifier: proxy.documentIdentifier)
        if documentDepth > 0 { documentCache = value }
        return value
    }
    private func beginDocumentOperation() {
        if documentDepth == 0 { documentCache = nil }
        documentDepth += 1
        if documentDepth == 1 && settings != nil { synchronizeDocument() }
    }
    private func endDocumentOperation() {
        documentDepth -= 1
        if documentDepth == 0 { documentCache = nil }
    }
    private func insertRaw(_ text: String) { textDocumentProxy.insertText(text); documentCache = nil }
    private func deleteRaw() { textDocumentProxy.deleteBackward(); documentCache = nil }
    private func adjustRaw(_ offset: Int) { textDocumentProxy.adjustTextPosition(byCharacterOffset: offset); documentCache = nil }
    private var manualShift: Bool?

    private func synchronizeDocument() {
        guard modelDocument != document.identifier else { return }
        lastSpace = 0; lastAbcTap = 0; manualShift = nil
        protectedBefore = nil; protectedAfter = nil; suppressedBefore = nil; suppressedAfter = nil
        liveGlide = nil; clearAutomaticSpace()
        clearTaps(); choiceInterrupted(); clearGlide()
        predictor?.flushLearning()
        predictor?.resetContext()
        modelDocument = document.identifier
    }

    private let glideWorker = DispatchQueue(label: "app.tailorkeys.glide", qos: .userInitiated)
    private var liveBusy = false
    private final class GlideSession {
        let prefix: String, before: String, after: String?
        let identifier: UUID
        let letters: Bool, upper: Bool, capital: Bool
        var shown: String?
        var expected: String
        var lifted = false
        var spaced = false
        var matches: [GestureDecoder.Candidate] = []
        var scoring: Predictor.GestureScoring?
        init(_ prefix: String, _ before: String, _ after: String?, _ identifier: UUID,
             _ letters: Bool, _ upper: Bool, _ capital: Bool) {
            self.prefix = prefix; self.before = before; self.after = after; self.identifier = identifier
            self.letters = letters; self.upper = upper; self.capital = capital; expected = before
        }
    }
    private var liveGlide: GlideSession?
    private var autoWord: String?, autoBefore: String?, autoAfter: String?
    private var autoDocument: UUID?
    private func clearAutomaticSpace() { autoWord = nil; autoBefore = nil; autoAfter = nil; autoDocument = nil }
    private func hasAutomaticSpace() -> Bool {
        guard autoBefore != nil else { return false }
        if document.identifier == autoDocument && document.before == autoBefore && document.after == autoAfter && (document.selected ?? "").isEmpty { return true }
        clearAutomaticSpace(); return false
    }
    private func replacesAutomaticWord(_ text: String) -> Bool {
        hasAutomaticSpace() && autoWord != nil && (text == autoWord || glideCandidates.contains(text) ||
            (settings.automaticPunctuation && TextInputRules.wordForms(autoWord ?? "", exists: predictor.hasWord).contains(text)))
    }
    @discardableResult private func automaticSpace(_ word: String) -> Bool {
        guard TextInputRules.addSpace(settings.automaticSpace && !settings.swipeLetters, document.after, password: !suggestAllowed) else { return false }
        let attached = document.before?.hasSuffix(word) == true
        insertRaw(" ")
        autoWord = attached ? word : nil; autoBefore = document.before; autoAfter = document.after; autoDocument = document.identifier
        lastSpace = CACurrentMediaTime()
        return true
    }
    private var glideWord: String?
    private var glideBefore: String?
    private var glideDocument: UUID?
    private var glideCandidates: [String] = []
    private var glideExplore: [String] = []
    private var changingGlide = false
    private let taps = ComposingTaps()
    private var tapBefore: String?
    private var tapAfter: String?
    private var tapDocument: UUID?

    private func clearTaps() { taps.clear(); tapBefore = nil; tapAfter = nil; tapDocument = nil }
    private func rememberTapContext() {
        tapBefore = document.before
        tapAfter = document.after
        tapDocument = document.identifier
    }
    private func validateTaps() {
        if tapDocument != nil && (tapDocument != document.identifier ||
            tapBefore != document.before || tapAfter != document.after ||
            !(document.selected ?? "").isEmpty) { clearTaps() }
    }
    private var suggestAllowed: Bool { textDocumentProxy.isSecureTextEntry != true }
    // iOS exposes no no-personalized-learning flag to extensions. Hosts such as our
    // typing test opt out via autocorrectionType = .no; never learn from those fields.
    private var learningAllowed: Bool {
        settings?.learning == true && TextInputRules.canLearn(secure: textDocumentProxy.isSecureTextEntry == true,
                               autocorrectionDisabled: textDocumentProxy.autocorrectionType == .no)
    }
    private let state = InputState()
    private var keyboard: KeyboardView!
    private var heightConstraint: NSLayoutConstraint?
    private var panel: SettingsPanel?
    private var quickOptions: QuickOptions?
    private var insertPanel: InsertPanel?
    private var insertTypingGrid: KeyGrid?
    private var editingLayoutKey: String?
    private var lastSpace: CFTimeInterval = 0
    private var lastAbcTap: CFTimeInterval = 0
    private var chosenBefore: String?
    private var chosenAfter: String?
    private var chosenDocument: UUID?
    private struct ArmedUndo {
        let original: String
        let inserted: String
        let separator: String
        let before: String?
        let after: String?
        let document: UUID
        var correction: Bool = false
        var insertedAfter: Int = 0
        var originalTail: Int = 0
        var onKept: () -> Void = {}
        let learn: () -> Void
    }
    private var armedUndo: ArmedUndo?
    private var protectedBefore: String?, protectedAfter: String?
    private var suppressedBefore: String?, suppressedAfter: String?
    private struct PendingLearning {
        let word: String, prev: String?, prev2: String?, picked: Bool
        let snapshot: DocumentSnapshot
        let model: Predictor
    }
    private var pendingLearning: PendingLearning?
    private var correctAllowed: Bool {
        let proxy = textDocumentProxy
        return suggestAllowed && proxy.autocorrectionType != .no && proxy.keyboardType != .URL &&
            proxy.keyboardType != .emailAddress && proxy.keyboardType != .webSearch
    }
    private func deferLearning(_ word: String, _ prev: String?, _ prev2: String?, picked: Bool = false) {
        guard learningAllowed, modelReady, !word.isEmpty else { return }
        pendingLearning = PendingLearning(word: word, prev: prev, prev2: prev2, picked: picked, snapshot: document, model: predictor)
    }
    private func keepPendingLearning() {
        guard let pending = pendingLearning else { return }
        pendingLearning = nil
        if learningAllowed && document == pending.snapshot { pending.model.observeFinal(pending.word, pending.prev, pending.prev2, picked: pending.picked) }
    }


    func wheelChosen() {
        beginDocumentOperation()
        defer { endDocumentOperation() }
        chosenBefore = document.before
        chosenAfter = document.after
        chosenDocument = document.identifier
    }

    private func clearChoice() {
        keyboard?.clearArmedWord()
        chosenBefore = nil; chosenAfter = nil; chosenDocument = nil
    }

    private func finishArmedLearning() {
        let undo = armedUndo
        armedUndo = nil
        if let undo = undo, document.identifier == undo.document,
            document.before == undo.before, document.after == undo.after, (document.selected ?? "").isEmpty {
            undo.onKept(); if learningAllowed { undo.learn() }
        }
    }

    func choiceInterrupted() { clearChoice(); armedUndo = nil; pendingLearning = nil }

    private func validateChoice() {
        guard settings != nil else { return }
        if !settings.spaceAccepts || document.identifier != chosenDocument ||
            document.before != chosenBefore ||
            document.after != chosenAfter || !(document.selected ?? "").isEmpty {
            clearChoice()
        }
    }

    private func undoArmedCommit() -> Bool {
        guard let undo = armedUndo,
              document.identifier == undo.document,
              document.before == undo.before,
              document.after == undo.after,
              (document.selected ?? "").isEmpty else { return false }
        armedUndo = nil // Reverting discards the deferred learning increment.
        clearChoice(); clearGlide()
        clearTaps()
        if undo.insertedAfter > 0 { adjustRaw(undo.insertedAfter) }
        for _ in undo.inserted { deleteRaw() }
        insertRaw(undo.original + undo.separator)
        if undo.originalTail > 0 { adjustRaw(-undo.originalTail) }
        if undo.correction {
            if modelReady { predictor.recordRejection(undo.original, String(undo.inserted.dropLast(undo.separator.count))) }
            suppressedBefore = String((document.before ?? "").dropLast(undo.separator.count)); suppressedAfter = document.after
            let context = suppressedBefore ?? ""
            let previous = CorrectionRules.context(context, undo.original.count)
            deferLearning(undo.original, previous.last, previous.count > 1 ? previous[previous.count - 2] : nil)
        }
        return true
    }

    private func correctWord(_ separator: String) -> Bool {
        guard correctAllowed, modelReady, state.layer == .abc, (document.selected ?? "").isEmpty, !currentGlide() else { return false }
        let before = document.before ?? ""
        let word = String(before.reversed().prefix(while: TextInputRules.wordChar).reversed())
        let typed = taps.hasInput(word)
        if typed { predictor.noteTyped(word) }
        if (before == protectedBefore && document.after == protectedAfter) || (before == suppressedBefore && document.after == suppressedAfter) { return false }
        if let ch = document.after?.first, ch.isLetter || ch.isNumber { return false }
        let priorPrefix = String(before.dropLast(word.count)).trimmingCharacters(in: .whitespaces)
        let pickedPrevious = protectedBefore?.trimmingCharacters(in: .whitespaces) == priorPrefix && document.after == protectedAfter
        guard let change = predictor.correction(before, AutocorrectMode(rawValue: settings.int(Prefs.autocorrect)) ?? .confident,
            taps: taps, userTyped: typed, allowJoin: !pickedPrevious) else { return false }
        let context = CorrectionRules.context(before, change.original.count)
        let prev = context.last, prev2 = context.count > 1 ? context[context.count - 2] : nil
        let model = predictor, allowed = learningAllowed
        pendingLearning = nil
        clearTaps(); clearChoice(); clearGlide(); clearAutomaticSpace()
        for _ in change.original { deleteRaw() }
        insertRaw(change.replacement + separator)
        armedUndo = ArmedUndo(original: change.original, inserted: change.replacement + separator, separator: separator,
            before: document.before, after: document.after, document: document.identifier, correction: true,
            onKept: { model?.recordKept(change.original, change.replacement) }, learn: { [weak self] in
                if allowed && self?.learningAllowed == true { model?.observeFinal(change.replacement, prev, prev2) }
            })
        return true
    }

    private func commitArmed(_ separator: String) -> Bool {
        validateChoice()
        guard settings.spaceAccepts, state.layer == .abc, let word = keyboard?.armedWord else { return false }
        let read = document
        let before = read.before ?? ""
        let spaced = replacesAutomaticWord(word)
        let original = spaced ? (autoWord ?? "") : (currentGlide() && !hasAutomaticSpace() ? glideWord! : currentWord(before))
        let suffix = spaced || !(read.selected ?? "").isEmpty ? 0 : TextInputRules.suffixLength(read.after ?? "")
        let originalSuffix = String((read.after ?? "").prefix(suffix))
        let model = predictor
        let shouldLearn = learningAllowed && modelReady
        let prev = previousWord(spaced ? String(before.dropLast()) : before)
        let prev2 = previousWord2(spaced ? String(before.dropLast()) : before)
        clearChoice(); clearGlide(); clearAutomaticSpace(); clearTaps()
        if (read.selected ?? "").isEmpty {
            if suffix > 0 { adjustRaw(suffix) }
            for _ in 0..<(original.count + (spaced ? 1 : 0) + suffix) { deleteRaw() }
        }
        insert(word)
        var inserted = word
        if separator == " " {
            if document.after?.first?.isWhitespace != true { typeSpace(learnWord: false); inserted += " " }
        } else {
            insert(separator); inserted += separator
            if settings.automaticPunctuation && automaticSpace(word) { inserted += " " }
        }
        armedUndo = ArmedUndo(original: original + originalSuffix, inserted: inserted, separator: separator,
            before: document.before, after: document.after,
            document: document.identifier, learn: {
                if shouldLearn { model?.observe(word, prev, prev2, picked: true) }
            })
        return true
    }

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
        if settingsFullAccess != hasFullAccess {
            predictor?.flushLearning(wait: true)
            loadGeneration &+= 1
            evictModel()
            settingsFullAccess = hasFullAccess
        }
        if settings != nil {
            settings.reloadStore(hasFullAccess: hasFullAccess)
            quickOptions?.reloadStore()
            if state.language == settings.currentLanguage { keyboard.grid = settings.loadGrid() }
            activateLanguage()
            return
        }
        settings = KeyboardSettings(tablet: UIDevice.current.userInterfaceIdiom == .pad, hasFullAccess: hasFullAccess)
        state.language = settings.currentLanguage
        let kb = KeyboardView(settings: settings, grid: settings.loadGrid())
        kb.state = state
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
        activateLanguage()
        attachQuickOptions()
        attachInsertPanel()
    }

    override func viewWillAppear(_ animated: Bool) {
        beginDocumentOperation()
        defer { endDocumentOperation() }
        super.viewWillAppear(animated)
        keyboardVisible = true
        lastSpace = 0; lastAbcTap = 0
        loadKeyboardIfNeeded()
        // Holding a spacebar picks a suggestion, so iPhones that need a keyboard switch key get a globe instead.
        keyboard.showGlobe = needsInputModeSwitchKey
        choiceInterrupted()
        clearTaps()
        if hasFullAccess, let group = UserDefaults(suiteName: KeyboardSettings.groupID) {
            group.set(Date(), forKey: KeyboardSettings.fullAccessSeenKey)
        }
        // A panel left open across appearances must reflect the freshly loaded store too.
        if panel != nil { panel?.removeFromSuperview(); panel = nil; openSettings() }
        let numeric: [UIKeyboardType] = [.numberPad, .decimalPad, .phonePad, .numbersAndPunctuation, .asciiCapableNumberPad]
        state.layer = numeric.contains(textDocumentProxy.keyboardType ?? .default) ? .num : .abc
        clearGlide()
        manualShift = nil
        state.shift = false
        state.capsLock = false
        keyboard.enterLabel = enterLabel()
        predictor?.refreshLearning()
        keyboard.reloadSettings()
        updateAutoCap()
        updateSuggestions()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard let keyboard = keyboard else { return }
        let h = keyboard.height(forWidth: view.bounds.width)
        if heightConstraint?.constant != h { heightConstraint?.constant = h }
        let area = keyboard.quickOptionsBounds
        quickOptions?.frame = CGRect(x: area.minX, y: 0, width: area.width, height: view.bounds.height)
        insertPanel?.frame = CGRect(x: area.minX, y: 0, width: area.width, height: view.bounds.height)
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in
            self?.keyboard?.relayout()
            self?.panel?.orientationChanged()
            self?.quickOptions?.refresh()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        keyboardVisible = false
        liveGlide = nil
        lastSpace = 0; lastAbcTap = 0
        clearTaps()
        choiceInterrupted()
        closeQuickOptions()
        closeInsert()
        keyboard?.endTypingSession()
        editingLayoutKey = nil; keyboard?.editTitle = nil
        predictor?.flushLearning(wait: true)
        super.viewWillDisappear(animated)
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        predictor?.flushLearning()
        if let decoder = gestureDecoder { glideWorker.async { decoder.clearTemplates() } }
        predictor?.trimMemory()
        keyboard?.trimDrawingCaches()
        if !keyboardVisible { loadGeneration &+= 1; evictModel() }
    }

    override func selectionDidChange(_ textInput: UITextInput?) {
        beginDocumentOperation()
        defer { endDocumentOperation() }
        if !changingGlide && documentDepth == 1 && keyboard != nil && document != refreshedDocument {
            manualShift = nil
            validateTaps()
            validateChoice()
            if let undo = armedUndo,
               document.identifier != undo.document ||
               document.before != undo.before ||
               document.after != undo.after { armedUndo = nil }
            _ = currentGlide()
            updateAutoCap()
            updateSuggestions()
        }
        super.selectionDidChange(textInput)
    }

    override func textDidChange(_ textInput: UITextInput?) {
        beginDocumentOperation()
        defer { endDocumentOperation() }
        if changingGlide || documentDepth > 1 { return }
        if settings != nil && (state.language != settings.currentLanguage || predictor?.language != settings.currentLanguage) { activateLanguage() }
        updateAutoCap()
        updateSuggestions()
    }

    // MARK: - KeyboardViewDelegate

    func chordTyped(_ word: String) {
        if insertPanel?.typeText(word) == true { return }
        beginDocumentOperation()
        defer { endDocumentOperation() }
        keepPendingLearning(); finishArmedLearning()
        clearTaps()
        choiceInterrupted()
        changingGlide = true
        defer { changingGlide = false }
        let shifted = state.shift
        if let last = document.before?.last, last.isLetter || last.isNumber { automaticSpace(currentWord(document.before ?? "")) }
        updateAutoCap()
        state.shift = state.shift || shifted
        let upper = state.capsLock || (settings.autoCap && textDocumentProxy.autocapitalizationType == .allCharacters)
        let text = upper ? word.uppercased() : (state.shift ? word.prefix(1).uppercased() + word.dropFirst() : word)
        clearGlide()
        insert(text)
        automaticSpace(text)
        updateAutoCap()
        updateSuggestions()
    }

    func forwardDelete() {
        beginDocumentOperation()
        defer { endDocumentOperation() }
        armedUndo = nil; pendingLearning = nil
        clearTaps(); clearChoice(); clearGlide(); clearAutomaticSpace()
        if !(document.selected ?? "").isEmpty { deleteRaw() }
        else if document.after?.isEmpty == false {
            let before = document.before, after = document.after
            adjustRaw(1)
            guard document.before != before || document.after != after else { return }
            deleteRaw()
        }
        updateSuggestions()
    }

    func keyTapped(_ code: String) { handleKey(code, nil) }
    func keyTapped(_ code: String, distances: [Float]) { handleKey(code, distances) }
    func letterLayout(_ spots: [KeySpot]) { if let grid = keyboard?.grid { predictor?.setLayout(grid, spots) } }

    private func handleKey(_ code: String, _ distances: [Float]?) {
        if code == Keys.insert { openInsert(); return }
        if insertPanel?.acceptsText == true, KeyText.isLayerKey(code) { tapLayerKey(code); keyboard.setNeedsDisplay(); return }
        if insertPanel?.key(code, state: state) == true { keyboard.setNeedsDisplay(); return }
        beginDocumentOperation()
        defer { endDocumentOperation() }
        changingGlide = true
        defer { changingGlide = false }
        liveGlide = nil
        validateTaps()
        if code == Keys.backspace && undoArmedCommit() {
            updateAutoCap(); updateSuggestions(); return
        }
        if code == Keys.backspace { armedUndo = nil; pendingLearning = nil }
        else if code == Keys.space || code == Keys.enter || code.count == 1 { keepPendingLearning(); finishArmedLearning() }
        let separator: String? = code == Keys.space ? " " : (code.count == 1 && ".,?!;:".contains(code) ? code : nil)
        if let separator = separator, commitArmed(separator) {
            updateAutoCap(); updateSuggestions(); return
        }
        if let separator = separator, correctWord(separator) { updateAutoCap(); updateSuggestions(); return }
        clearChoice()
        let endingGlide = separator != nil && separator != " " && currentGlide() ? glideWord : nil
        let endedBefore = separator != nil && separator != " " ? (endingGlide != nil && hasAutomaticSpace() ? String((document.before ?? "").dropLast()) : document.before) : nil
        switch code {
        case Keys.backspace:
            clearGlide()
            if (document.selected ?? "").isEmpty {
                taps.sync(currentWord(document.before ?? ""))
                taps.backspace()
            } else { clearTaps() }
            deleteRaw()
            rememberTapContext()
        case Keys.space: typeSpace()
        case Keys.enter: pendingLearning = nil; clearGlide(); clearTaps(); insertRaw("\n")
        default:
            if KeyText.isLayerKey(code) { tapLayerKey(code) } else {
                if state.layer == .abc && code.count == 1 && code.first!.isLetter && currentGlide() {
                    let shifted = state.shift
                    finishGlideLearning()
                    if !hasAutomaticSpace() { automaticSpace(glideWord!) }
                    clearGlide()
                    updateAutoCap()
                    state.shift = state.shift || shifted
                }
                if let s = KeyText.output(code, state) { insert(s, typing: true, distances: distances) }
            }
        }
        if let before = endedBefore { deferLearning(endingGlide ?? currentWord(before), previousWord(before), previousWord2(before)) }
        // No typo buzz on iOS: keyboards can't vibrate without Full Access.
        updateAutoCap()
        updateSuggestions()
    }

    func moveCursor(_ steps: Int) {
        if insertPanel?.moveCursor(steps) == true { return }
        liveGlide = nil; clearAutomaticSpace()
        beginDocumentOperation()
        defer { endDocumentOperation() }
        clearTaps()
        choiceInterrupted()
        clearGlide()
        manualShift = nil
        adjustRaw(steps)
        updateAutoCap()
        updateSuggestions()
    }

    func replacePressText(_ original: String, following: String, replacement: String) {
        beginDocumentOperation()
        defer { endDocumentOperation() }
        clearTaps()
        choiceInterrupted()
        guard document.before?.hasSuffix(original + following) == true else { return }
        if !following.isEmpty { adjustRaw(-following.count) }
        for _ in original { deleteRaw() }
        insertRaw(replacement)
        if !following.isEmpty { adjustRaw(following.count) }
        updateAutoCap()
        updateSuggestions()
    }

    func longPressText(_ text: String) {
        if insertPanel?.typeText(text) == true { return }
        beginDocumentOperation()
        defer { endDocumentOperation() }
        choiceInterrupted()
        insert(text, typing: true)
        updateSuggestions()
    }

    /// A swipe key landed on a new face. Layer faces switch the layout straight away.
    func stackFace(_ code: String) {
        beginDocumentOperation()
        defer { endDocumentOperation() }
        if KeyText.isLayerKey(code) {
            choiceInterrupted()
            state.layer = KeyText.layerOf(code)
            manualShift = nil
            state.shift = false
            updateAutoCap()
            updateSuggestions()
        }
    }

    /// A word from a wheel replaces the word being typed. A symbol is just inserted.
    private func showTidy() {
        beginDocumentOperation()
        defer { endDocumentOperation() }
        guard correctAllowed, modelReady, (document.selected ?? "").isEmpty else { quickOptions?.showTidy([]) { _ in }; return }
        let snapshot = document
        let before = document.before ?? ""
        let left = TidyText.window(before), right = TidyText.afterWindow(document.after ?? "")
        let preceding = String(before.dropLast(left.count)).trimmingCharacters(in: .whitespacesAndNewlines)
        let startsSentence = left == before || preceding.last.map { ".!?".contains($0) } == true
        let text = left + right
        let fixes = TidyText.propose(text, startsSentence: startsSentence) { word, context in
            self.predictor.updateContext(context + word, word)
            return self.predictor.autocorrect(word, .aggressive, self.previousWord(context + word), self.previousWord2(context + word))
        }
        quickOptions?.showTidy(fixes) { [weak self] chosen in
            guard let self = self, !chosen.isEmpty else { return }
            self.beginDocumentOperation(); defer { self.endDocumentOperation() }
            guard self.document == snapshot else { return }
            let replacement = TidyText.apply(text, fixes: chosen)
            self.pendingLearning = nil; self.armedUndo = nil
            self.clearTaps(); self.clearChoice(); self.clearGlide(); self.clearAutomaticSpace()
            // UITextDocumentProxy has no batch-edit API; insert the reviewed result once.
            if !right.isEmpty { self.adjustRaw(right.count) }
            for _ in text { self.deleteRaw() }
            self.insertRaw(replacement)
            let tail = replacement.count - TidyText.cursor(left.count, fixes: chosen)
            if tail > 0 { self.adjustRaw(-tail) }
            self.armedUndo = ArmedUndo(original: text, inserted: replacement, separator: "", before: self.document.before,
                after: self.document.after, document: self.document.identifier, insertedAfter: tail, originalTail: right.count, learn: {})
            self.updateSuggestions()
        }
    }

    func forgetWord(_ text: String) {
        armedUndo = nil; pendingLearning = nil; liveGlide = nil; clearGlide()
        predictor?.flushLearning(wait: true)
        LearningStore.forget(settings.learningStore, language: state.language, word: text)
        predictor?.refreshLearning()
        updateSuggestions()
    }

    func suggestionPicked(_ text: String) {
        beginDocumentOperation()
        defer { endDocumentOperation() }
        changingGlide = true
        defer { changingGlide = false }
        if armedUndo?.correction == true && armedUndo?.original == text && undoArmedCommit() { updateSuggestions(); return }
        if currentWord(document.before ?? "").isEmpty { keepPendingLearning(); finishArmedLearning() }
        armedUndo = nil; pendingLearning = nil; clearChoice(); liveGlide = nil
        if state.layer != .abc { insert(text); updateSuggestions(); return }
        let read = document
        let spaced = replacesAutomaticWord(text)
        let before = read.before ?? ""
        let original = spaced ? (autoWord ?? "") : (currentGlide() && !hasAutomaticSpace() ? glideWord! : currentWord(before))
        let prev = previousWord(spaced ? String(before.dropLast()) : before)
        let prev2 = previousWord2(spaced ? String(before.dropLast()) : before)
        let plan = TextInputRules.replacement(original.count, read.after ?? "", automaticSpace: settings.automaticSpace && !settings.swipeLetters && suggestAllowed,
            selected: !(read.selected ?? "").isEmpty, spacedWordLength: spaced ? original.count : 0)
        clearTaps(); clearGlide(); clearAutomaticSpace()
        if (read.selected ?? "").isEmpty {
            if plan.after > 0 { adjustRaw(plan.after) }
            for _ in 0..<(plan.before + plan.after) { deleteRaw() }
        }
        insertRaw(text)
        if plan.space { automaticSpace(text) }
        protectedBefore = document.before; protectedAfter = document.after
        deferLearning(text, prev, prev2, picked: true)
        updateAutoCap(); updateSuggestions()
    }

    func glideStarted() {
        if insertPanel?.acceptsText == true { return }
        beginDocumentOperation()
        defer { endDocumentOperation() }
        keepPendingLearning(); finishArmedLearning()
        choiceInterrupted()
        let read = document
        let prefix = currentGlide() || !(read.selected ?? "").isEmpty ? "" : currentWord(read.before ?? "")
        clearGlide(); clearTaps()
        liveGlide = GlideSession(prefix, read.before ?? "", read.after, read.identifier, settings.swipeLetters,
            state.capsLock || (settings.autoCap && textDocumentProxy.autocapitalizationType == .allCharacters), state.shift)
    }
    func glideCancelled() { liveGlide = nil }
    private func glideCase(_ session: GlideSession, _ word: String) -> String {
        if session.upper { return word.uppercased() }
        if !session.prefix.isEmpty { return session.prefix + word.dropFirst(session.prefix.count) }
        return session.capital ? word.prefix(1).uppercased() + word.dropFirst() : word
    }
    private func validGlide(_ session: GlideSession) -> Bool {
        document.identifier == session.identifier && (document.before ?? "") == session.expected &&
            document.after == session.after && (document.selected ?? "").isEmpty
    }
    private func showRaw(_ session: GlideSession, _ raw: String) {
        guard validGlide(session) else { liveGlide = nil; return }
        let text = glideCase(session, SwipeTrace.appendToPrefix(session.prefix, raw))
        if text == session.shown { return }
        // UITextDocumentProxy has no composing region. Replace only our own validated insertion.
        for _ in 0..<(session.shown?.count ?? session.prefix.count) { deleteRaw() }
        insertRaw(text)
        session.shown = text; session.expected = document.before ?? ""
    }
    func glideProgress(_ points: [GlidePoint], _ keys: [Character: CGPoint], _ keyWidth: CGFloat, _ raw: String, _ hints: [DoubleLetterHint]) {
        if insertPanel?.acceptsText == true { return }
        beginDocumentOperation()
        defer { endDocumentOperation() }
        changingGlide = true
        defer { changingGlide = false }
        guard let session = liveGlide else { return }
        if session.letters { showRaw(session, raw) }
        if modelReady && suggestAllowed && !liveBusy { decodeGlide(session, points, keys, keyWidth, raw, hints, false) }
    }
    func glideFinished(_ points: [GlidePoint], _ keys: [Character: CGPoint], _ keyWidth: CGFloat, _ raw: String, _ hints: [DoubleLetterHint]) {
        if let panel = insertPanel, panel.acceptsText {
            let session = panel.session
            guard modelReady, !settings.swipeLetters, let scoring = predictor?.gestureScoring() else { panel.typeText(raw); return }
            glideWorker.async {
                let decoder = GestureDecoder(scoring.words); decoder.setKeys(keys, keyWidth)
                let word = decoder.decode(points, nil, frequency: scoring.frequency, context: { _, _ in 1.0 }, hints: hints).first?.word ?? raw
                DispatchQueue.main.async { [weak panel] in if panel?.session == session { panel?.typeText(word) } }
            }; return
        }
        beginDocumentOperation()
        defer { endDocumentOperation() }
        changingGlide = true
        defer { changingGlide = false }
        guard let session = liveGlide else { return }
        guard suggestAllowed, settings.swipeTyping, state.layer == .abc else { liveGlide = nil; return }
        guard validGlide(session) else { liveGlide = nil; return }
        session.lifted = true
        if session.letters {
            showRaw(session, raw)
            guard liveGlide === session else { return }
            glideWord = session.shown; glideBefore = document.before; glideDocument = document.identifier
        }
        if !session.letters {
            let provisional = session.matches.filter { $0.word.last == raw.last }
            finishGuess(session, provisional, raw, pending: modelReady)
        }
        if modelReady { decodeGlide(session, points, keys, keyWidth, raw, hints, true) }
        updateSuggestions()
    }
    private func decodeGlide(_ session: GlideSession, _ points: [GlidePoint], _ keys: [Character: CGPoint],
                             _ width: CGFloat, _ raw: String, _ hints: [DoubleLetterHint], _ final: Bool) {
        guard let decoder = gestureDecoder, let model = predictor else { return }
        if session.scoring == nil {
            model.updateContext(session.before, session.prefix)
            session.scoring = model.gestureScoring()
        }
        guard let scoring = session.scoring else { return }
        let prev = previousWord(session.before)
        if !final { liveBusy = true }
        glideWorker.async { [weak self] in
            decoder.setUserWords(scoring.words); decoder.setKeys(keys, width)
            let matches = decoder.decode(points, prev, frequency: scoring.frequency, context: scoring.context,
                                         hints: hints, prefix: session.prefix, partial: !final)
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                if !final { self.liveBusy = false }
                guard self.liveGlide === session, self.predictor === model, final || !session.lifted else { return }
                self.beginDocumentOperation()
                defer { self.endDocumentOperation() }
                self.changingGlide = true
                defer { self.changingGlide = false }
                guard self.suggestAllowed, self.validGlide(session) else { self.liveGlide = nil; return }
                let ranked = matches
                session.matches = ranked
                let prefix = session.letters ? SwipeTrace.appendToPrefix(session.prefix, raw) : (ranked.first?.prefix ?? SwipeTrace.appendToPrefix(session.prefix, raw))
                let predictions = model.suggestions(prefix, prev, nil, taps: nil, weights: self.settings.keyTint || self.settings.smartTargets)
                self.keyboard?.keyWeights = predictions.weights
                let words = ranked.map { self.glideCase(session, $0.word) }
                var unique = Set<String>()
                self.glideCandidates = (words + (session.letters ? predictions.closest : [])).filter { unique.insert($0).inserted }
                self.glideExplore = session.letters ? predictions.explore : Array(words.dropFirst(8))
                if final {
                    if session.letters { self.liveGlide = nil; self.updateSuggestions() }
                    else { self.finishGuess(session, ranked, raw) }
                } else { self.keyboard?.setSuggestions(left: self.glideExplore, right: Array(self.glideCandidates.prefix(8)), prefix: session.letters ? prefix : "") }
            }
        }
    }
    private func finishGuess(_ session: GlideSession, _ matches: [GestureDecoder.Candidate], _ raw: String, pending: Bool = false) {
        let word = glideCase(session, matches.first?.word ?? SwipeTrace.appendToPrefix(session.prefix, raw))
        let remove = session.shown.map { $0.count + (session.spaced ? 1 : 0) } ?? session.prefix.count
        for _ in 0..<remove { deleteRaw() }
        insertRaw(word); clearAutomaticSpace(); session.spaced = automaticSpace(word)
        glideWord = word; glideBefore = document.before; glideDocument = document.identifier
        session.shown = word; session.expected = document.before ?? ""
        if !pending { liveGlide = nil }
        manualShift = nil
        if state.shift && !state.capsLock { state.shift = false }
        updateAutoCap(); updateSuggestions()
    }

    private func clearGlide() {
        glideWord = nil; glideBefore = nil; glideDocument = nil; glideCandidates = []; glideExplore = []
    }

    private func currentGlide() -> Bool {
        guard glideWord != nil else { return false }
        if document.identifier == glideDocument,
           (document.selected ?? "").isEmpty,
           document.before == glideBefore { return true }
        clearGlide()
        return false
    }

    private func finishGlideLearning() {
        guard currentGlide(), learningAllowed, modelReady else { return }
        let before = document.before ?? ""
        let context = hasAutomaticSpace() ? String(before.dropLast()) : before
        predictor.observe(glideWord!, previousWord(context), previousWord2(context))
    }

    func switchKeyboard() { choiceInterrupted(); advanceToNextInputMode() }

    func switchLanguage(_ direction: Int) {
        choiceInterrupted()
        guard settings.languages.count > 1 else { return }
        settings.cycleLanguage(direction)
        activateLanguage()
        keyboard.reloadSettings()
        updateSuggestions()
        UIAccessibility.post(notification: .announcement, argument: Prefs.languageNames[state.language])
    }

    func openQuickOptions() {
        closeInsert()
        attachQuickOptions()
        guard let p = quickOptions, p.isHidden else { return }
        choiceInterrupted()
        keyboard.cancelTouches()
        keyboard.setOptionsCovered(true)
        keyboard.layer.removeAllAnimations(); keyboard.alpha = 1
        UIView.animate(withDuration: UIAccessibility.isReduceMotionEnabled ? 0.1 : 0.08) { self.keyboard.alpha = 0 }
        keyboard.accessibilityElementsHidden = true
        p.refresh(resetScroll: true)
        let area = keyboard.quickOptionsBounds
        p.frame = CGRect(x: area.minX, y: 0, width: area.width, height: view.bounds.height)
        p.isHidden = false; p.accessibilityElementsHidden = false
        p.layoutIfNeeded(); p.animateOpen()
        UIAccessibility.post(notification: .screenChanged, argument: p)
    }

    private func attachQuickOptions() {
        guard quickOptions == nil, settings != nil, keyboard != nil else { return }
        let p = QuickOptions(settings: settings, stripHeight: { [weak self] in self?.keyboard.suggestionStripHeight ?? 40 })
        p.onClose = { [weak self] in self?.closeQuickOptions() }
        p.onEdit = { [weak self] key, title in
            guard let self = self else { return }
            self.closeQuickOptions()
            self.editingLayoutKey = key
            self.keyboard.editTitle = title
            self.keyboard.grid = self.settings.loadGrid()
            self.predictor?.setLayout(self.keyboard.grid, self.keyboard.letterSpots)
            self.keyboard.editMode = true
            UIAccessibility.post(notification: .announcement, argument: title)
        }
        p.onLanguage = { [weak self] in self?.switchLanguage(1) }
        p.onTidy = { [weak self] in self?.showTidy() }
        p.onChange = { [weak self] in
            guard let self = self else { return }
            if self.state.language != self.settings.currentLanguage { self.activateLanguage() }
            let grid = self.settings.loadGrid()
            if !LayoutLibrary.sameGrid(grid, self.keyboard.grid) {
                self.clearTaps(); self.keyboard.grid = grid; self.predictor?.setLayout(grid, self.keyboard.letterSpots)
            }
            if self.predictor?.refreshLearning() == true { self.armedUndo = nil; self.pendingLearning = nil; self.liveGlide = nil; self.clearGlide() }
            if !self.settings.learning { self.pendingLearning = nil; self.armedUndo = nil }
            self.keyboard.reloadSettings()
            let area = self.keyboard.quickOptionsBounds
            self.quickOptions?.frame = CGRect(x: area.minX, y: 0, width: area.width, height: self.view.bounds.height)
            self.updateSuggestions()
        }
        view.addSubview(p)
        if let link = p.settingsLinkController { addChild(link); link.didMove(toParent: self) }
        p.isHidden = true; p.accessibilityElementsHidden = true
        quickOptions = p
    }

    private func closeQuickOptions() {
        quickOptions?.layer.removeAllAnimations()
        quickOptions?.alpha = 1; quickOptions?.transform = .identity
        quickOptions?.isHidden = true; quickOptions?.accessibilityElementsHidden = true
        keyboard?.setOptionsCovered(false)
        keyboard?.layer.removeAllAnimations(); keyboard?.alpha = 1
        keyboard?.accessibilityElementsHidden = false
        UIAccessibility.post(notification: .layoutChanged, argument: keyboard)
    }

    private func attachInsertPanel() {
        guard insertPanel == nil else { return }
        let p = InsertPanel(settings: settings, state: state)
        p.fullAccess = { [weak self] in self?.hasFullAccess == true }
        p.password = { [weak self] in self?.textDocumentProxy.isSecureTextEntry ?? true }
        p.onClose = { [weak self] in self?.closeInsert() }
        p.onBackspace = { [weak self] in self?.keyTapped(Keys.backspace) }
        p.onInsert = { [weak self] text in
            guard let self = self else { return }; self.beginDocumentOperation(); defer { self.endDocumentOperation() }
            self.choiceInterrupted(); self.insert(text); self.updateSuggestions()
        }
        p.onTyping = { [weak self] active, height in
            guard let self = self else { return }
            if active && self.insertTypingGrid == nil { self.insertTypingGrid = self.keyboard.grid.copy() }
            if active, let saved = self.insertTypingGrid {
                let grid = saved.copy(); if UIDevice.current.userInterfaceIdiom != .pad || self.keyboard.bounds.width < 600 { grid.setNumberRow(false) }; self.keyboard.grid = grid
            } else if let saved = self.insertTypingGrid { self.keyboard.grid = saved; self.insertTypingGrid = nil }
            self.keyboard.insertStripHeight = height; self.keyboard.setOptionsCovered(!active); self.keyboard.alpha = active ? 1 : 0; self.keyboard.accessibilityElementsHidden = !active
        }
        addChild(p.settingsLinkController); view.addSubview(p); p.settingsLinkController.didMove(toParent: self); insertPanel = p
    }
    private func openInsert() {
        closeQuickOptions(); attachInsertPanel(); choiceInterrupted(); keyboard.cancelTouches()
        keyboard.setOptionsCovered(true); keyboard.alpha = 0; keyboard.accessibilityElementsHidden = true
        let area = keyboard.quickOptionsBounds
        insertPanel?.frame = CGRect(x: area.minX, y: 0, width: area.width, height: view.bounds.height)
        insertPanel?.show(); UIAccessibility.post(notification: .screenChanged, argument: insertPanel)
    }
    private func closeInsert() {
        insertPanel?.hide(); keyboard?.setOptionsCovered(false); keyboard?.alpha = 1; keyboard?.accessibilityElementsHidden = false
    }

    /// The gear in the rearranging toolbar: leaves rearranging and shows the settings panel.
    func openSettings() {
        choiceInterrupted()
        keyboard.editMode = false
        guard panel == nil else { return }
        let landscape = UIScreen.main.bounds.width > UIScreen.main.bounds.height
        let p = SettingsPanel(settings: settings, landscape: landscape)
        p.onChange = { [weak self] in
            guard let self = self else { return }
            if self.state.language != self.settings.currentLanguage { self.activateLanguage() }
            if self.predictor?.refreshLearning() == true { self.armedUndo = nil; self.pendingLearning = nil; self.liveGlide = nil; self.clearGlide() }
            if !self.settings.learning { self.pendingLearning = nil; self.armedUndo = nil }
            self.keyboard.reloadSettings()
            self.updateSuggestions()
        }
        p.onLanguageChange = { [weak self] in
            self?.activateLanguage()
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
            self.insertRaw(self.keyboard.grid.toCode())
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
            self.clearTaps()
            let g = KeyGrid.makeDefault(self.settings.currentLanguage)
            if self.settings.numberRow { g.setNumberRow(true) }
            self.settings.saveGrid(g)
            self.keyboard.grid = g
            self.predictor?.setLayout(g, self.keyboard.letterSpots)
        }
        p.onNumberRow = { [weak self] on in
            guard let self = self else { return false }
            self.clearTaps()
            let g = self.settings.loadGrid()
            if !g.setNumberRow(on) { return false }
            self.settings.saveGrid(g)
            self.settings.set(Prefs.numberRow, on)
            self.keyboard.grid = g
            self.predictor?.setLayout(g, self.keyboard.letterSpots)
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
        clearTaps()
        predictor?.setLayout(keyboard.grid, keyboard.letterSpots)
        let library = settings.makeLayoutLibrary()
        if let key = editingLayoutKey, key == library.appliedKey { library.update(key, grid: keyboard.grid) }
        else { settings.saveGrid(keyboard.grid) }
    }

    func editDone() {
        guard editingLayoutKey != nil else { return }
        gridEdited()
        editingLayoutKey = nil; keyboard.editTitle = nil
        openQuickOptions()
    }

    // MARK: - Typing

    private func insert(_ s: String, typing: Bool = false, distances: [Float]? = nil) {
        if currentGlide() {
            if s.first?.isLetter == true && !hasAutomaticSpace() { automaticSpace(glideWord!) }
        }
        clearGlide()
        let auto = hasAutomaticSpace()
        let edit = TextInputRules.punctuation(s, document.before ?? "", document.after ?? "", automaticSpace: auto,
                                             enabled: settings.automaticPunctuation, password: !suggestAllowed, trailingSpace: settings.automaticSpace && !settings.swipeLetters)
        clearAutomaticSpace()
        if let edit = edit, (document.selected ?? "").isEmpty {
            clearTaps()
            for _ in 0..<edit.deleteBefore { deleteRaw() }
            if !edit.insert.isEmpty { insertRaw(edit.insert) }
            if edit.move != 0 { adjustRaw(edit.move) }
            if edit.insert.hasSuffix(" ") {
                autoBefore = document.before; autoAfter = document.after; autoDocument = document.identifier
            }
            return
        }
        let prefix = currentWord(document.before ?? "")
        let ch = s.first
        let track = typing && s.count == 1 && (ch?.isLetter == true || (ch?.isNumber == true && !prefix.isEmpty)) &&
            (document.selected ?? "").isEmpty
        if track { taps.append(prefix, s, distances) } else { clearTaps() }
        insertRaw(s)
        if track { rememberTapContext() }
        manualShift = nil
        if state.shift && !state.capsLock { state.shift = false }
    }

    /// On ABC: tap for a one-shot capital, double-tap for caps lock, tap again to clear. Elsewhere: switch layer.
    private func tapLayerKey(_ code: String) {
        if code == Keys.abc && state.layer == .abc {
            let now = CACurrentMediaTime()
            if state.capsLock { state.capsLock = false; state.shift = false }
            else if state.shift && now - lastAbcTap < 0.35 { state.capsLock = true; state.shift = false }
            else { state.shift.toggle() }
            manualShift = state.capsLock ? nil : state.shift
            lastAbcTap = now
        } else {
            state.layer = KeyText.layerOf(code)
            manualShift = nil
            state.shift = false
        }
    }

    private func typeSpace(learnWord: Bool = true) {
        let before = document.before ?? ""
        let word = currentWord(before)
        let picked = before == protectedBefore && document.after == protectedAfter
        let endedWord = currentGlide() ? (glideWord ?? "") : word
        let context = hasAutomaticSpace() ? String(before.dropLast()) : before
        clearTaps()
        clearGlide()
        let now = CACurrentMediaTime()
        clearAutomaticSpace()
        if state.layer == .abc && TextInputRules.doubleSpace(settings.automaticPunctuation && settings.doubleSpace, before, now - lastSpace, password: !suggestAllowed) {
            deleteRaw()
            insertRaw(". ")
            lastSpace = 0
            return
        }
        insertRaw(" ")
        lastSpace = now
        if learnWord { deferLearning(endedWord, previousWord(context), previousWord2(context), picked: picked) }
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

    private func currentWord(_ before: String) -> String { taps.prefix(before) }

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
        beginDocumentOperation()
        defer { endDocumentOperation() }
        guard keyboard != nil else { return }
        synchronizeDocument()
        refreshedDocument = document
        keyboard.refreshInputState()
        validateTaps()
        validateChoice()
        if !modelReady {
            keyboard.keyWeights = [:]
            keyboard.setSuggestions(left: [], right: [], prefix: "")
            return
        }
        let before = document.before ?? ""
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
        if let undo = armedUndo, undo.correction, document.before == undo.before, document.after == undo.after {
            keyboard.keyWeights = [:]
            keyboard.setSuggestions(left: [], right: [undo.original], prefix: "")
            return
        }
        if hasAutomaticSpace() && autoWord != nil && settings.automaticPunctuation {
            let word = autoWord ?? ""
            predictor.updateContext(before, "")
            let next = predictor.suggestions("", word.lowercased(), nil, taps: nil, weights: settings.keyTint || settings.smartTargets)
            keyboard.keyWeights = next.weights
            var unique = Set<String>()
            let forms = TextInputRules.wordForms(word, exists: predictor.hasWord)
            let closest = (forms + glideCandidates + next.closest).filter { unique.insert($0).inserted }
            keyboard.setSuggestions(left: next.explore, right: Array(closest.prefix(8)), prefix: "")
            return
        }
        if currentGlide() {
            let weights = predictor.suggestions(glideWord ?? "", previousWord(before), nil, taps: nil, weights: settings.keyTint || settings.smartTargets)
            keyboard.keyWeights = weights.weights
            predictor.updateContext(before, "")
            keyboard.setSuggestions(left: glideExplore, right: Array(glideCandidates.prefix(8)), prefix: "")
            return
        }
        let prefix = currentWord(before)
        taps.sync(prefix)
        let prev = previousWord(before)
        let prev2 = previousWord2(before)
        predictor.updateContext(before, prefix)
        let suggestions = predictor.suggestions(prefix, prev, prev2, taps: taps, weights: settings.keyTint || settings.smartTargets)
        keyboard.keyWeights = suggestions.weights
        keyboard.setSuggestions(left: suggestions.explore, right: suggestions.closest, prefix: prefix)
    }

    /// Turns on a one-shot capital where the field asks for one: sentence starts, word starts, or always.
    private func updateAutoCap() {
        guard keyboard != nil, suggestAllowed, settings.autoCap, state.layer == .abc, !state.capsLock, manualShift == nil else { return }
        let type = textDocumentProxy.autocapitalizationType ?? .sentences
        let before = document.before ?? ""
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
        keyboard.refreshInputState()
    }
}
