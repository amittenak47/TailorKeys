import UIKit

/// Input view that allows the standard keyboard click sound.
final class ClickInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

final class KeyboardViewController: UIInputViewController, KeyboardViewDelegate {

    private lazy var settings = KeyboardSettings(tablet: UIDevice.current.userInterfaceIdiom == .pad)
    private let predictor = Predictor()
    private let state = InputState()
    private var keyboard: KeyboardView!
    private var heightConstraint: NSLayoutConstraint?
    private var panel: SettingsPanel?
    private let hintLabel = UILabel()
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
        let hc = view.heightAnchor.constraint(equalToConstant: 260)
        hc.priority = UILayoutPriority(999)
        hc.isActive = true
        heightConstraint = hc
        kb.onHeightChange = { [weak self] h in
            if self?.heightConstraint?.constant != h { self?.heightConstraint?.constant = h }
        }
        keyboard = kb

        // Shown above the keys while rearranging, in place of the suggestions.
        hintLabel.font = .systemFont(ofSize: 13)
        hintLabel.textAlignment = .center
        hintLabel.isHidden = true
        hintLabel.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hintLabel)
        NSLayoutConstraint.activate([
            hintLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 8),
            hintLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -8),
            hintLabel.topAnchor.constraint(equalTo: view.topAnchor, constant: 2),
        ])
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        let numeric: [UIKeyboardType] = [.numberPad, .decimalPad, .phonePad, .numbersAndPunctuation, .asciiCapableNumberPad]
        state.layer = numeric.contains(textDocumentProxy.keyboardType ?? .default) ? .num : .abc
        state.shift = false
        state.capsLock = false
        keyboard.enterLabel = enterLabel()
        keyboard.reloadSettings()
        updateAutoCap()
        updateSuggestions()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let h = keyboard.height(forWidth: view.bounds.width)
        if heightConstraint?.constant != h { heightConstraint?.constant = h }
    }

    override func viewWillTransition(to size: CGSize, with coordinator: UIViewControllerTransitionCoordinator) {
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in
            self?.keyboard.relayout()
            self?.panel?.orientationChanged()
        }
    }

    override func textDidChange(_ textInput: UITextInput?) {
        updateAutoCap()
        updateSuggestions()
    }

    // MARK: - KeyboardViewDelegate

    func keyTapped(_ code: String) {
        switch code {
        case Keys.backspace: textDocumentProxy.deleteBackward()
        case Keys.space: typeSpace()
        case Keys.enter: textDocumentProxy.insertText("\n")
        default:
            if KeyText.isLayerKey(code) { tapLayerKey(code) } else if let s = KeyText.output(code, state) { insert(s) }
        }
        updateAutoCap()
        updateSuggestions()
        keyboard.setNeedsDisplay()
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
        if state.layer != .abc {
            textDocumentProxy.insertText(text)
        } else {
            let prefix = currentWord(textDocumentProxy.documentContextBeforeInput ?? "")
            for _ in 0..<prefix.count { textDocumentProxy.deleteBackward() }
            textDocumentProxy.insertText(text + " ")
            predictor.learn(text)
        }
        updateAutoCap()
        updateSuggestions()
    }

    func spaceLongPressed() { advanceToNextInputMode() }

    func openSettings() {
        if keyboard.editMode {
            keyboard.editMode = false
            hintLabel.isHidden = true
            return
        }
        guard panel == nil else { return }
        let p = SettingsPanel(settings: settings, landscape: UIScreen.main.bounds.width > UIScreen.main.bounds.height)
        p.onChange = { [weak self] in self?.keyboard.reloadSettings() }
        p.onClose = { [weak self] in self?.closePanel() }
        p.onRearrange = { [weak self] in
            guard let self = self else { return }
            self.closePanel()
            self.keyboard.editMode = true
            self.hintLabel.text = "Drag keys to rearrange. Tap ✓ when done."
            self.hintLabel.textColor = .secondaryLabel
            self.hintLabel.isHidden = false
        }
        p.onResetLayout = { [weak self] in
            guard let self = self else { return }
            let g = KeyGrid.makeDefault()
            if self.settings.numberRow { g.setNumberRow(true) }
            self.settings.saveGrid(g)
            self.keyboard.grid = g
        }
        p.onNumberRow = { [weak self] on in
            guard let self = self else { return false }
            let g = self.settings.loadGrid()
            if !g.setNumberRow(on) { return false }
            self.settings.saveGrid(g)
            self.settings.set(Prefs.numberRow, on)
            self.keyboard.grid = g
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

    func gridEdited() { settings.saveGrid(keyboard.grid) }

    func editHint(_ text: String?) {
        hintLabel.text = text ?? "Drag keys to rearrange. Tap ✓ when done."
        hintLabel.textColor = text == nil ? .secondaryLabel : .systemBlue
    }

    // MARK: - Typing

    private func insert(_ s: String) {
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
        if word.count > 1 { predictor.learn(word) }
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

    private func updateSuggestions() {
        let before = textDocumentProxy.documentContextBeforeInput ?? ""
        if state.layer != .abc {
            let s = predictor.symbols(before: before.last)
            keyboard.setSuggestions(left: s.explore, right: s.closest, prefix: "")
            return
        }
        let prefix = currentWord(before)
        keyboard.setSuggestions(left: predictor.explore(prefix), right: predictor.closest(prefix), prefix: prefix)
    }

    /// Turns on a one-shot capital where the field asks for one: sentence starts, word starts, or always.
    private func updateAutoCap() {
        guard settings.autoCap, state.layer == .abc, !state.capsLock else { return }
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
