import UIKit

/// Settings shown inside the keyboard (tap the gear in the suggestion bar). Keeping them here means the
/// keyboard needs no App Group to share settings with the app, which free Apple IDs can't always use.
final class SettingsPanel: UIView, UIGestureRecognizerDelegate {

    var onChange: (() -> Void)?
    var onClose: (() -> Void)?
    var onRearrange: (() -> Void)?
    var onResetLayout: (() -> Void)?
    var onNumberRow: ((Bool) -> Bool)?
    var onLanguageChange: (() -> Void)?
    var onTemplate: ((Template) -> Void)?
    /// Types the layout's share code into the text field.
    var onTypeCode: (() -> Void)?
    /// Reads a share code from the text field; false if there wasn't one.
    var onImportCode: (() -> Bool)?

    private let settings: KeyboardSettings
    private let scroll = UIScrollView()
    private let stack = UIStackView()
    /// The orientation being edited.
    private var page: Bool
    private var ratio: CGFloat = 1
    /// Which tab is showing: 0 Layout, 1 Size, 2 Look, 3 Settings.
    private var forgetConfirm = false
    private var tab = 0
    private static let tabNames = ["Layout", "Size", "Look", "Settings"]

    init(settings: KeyboardSettings, landscape: Bool) {
        self.settings = settings
        page = landscape
        super.init(frame: .zero)
        backgroundColor = .secondarySystemBackground

        let title = UILabel()
        title.text = "TailorKeys"
        title.font = .boldSystemFont(ofSize: 15)
        title.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let rearrange = UIButton(type: .system)
        rearrange.setTitle("Rearrange keys", for: .normal)
        rearrange.addAction(UIAction { [weak self] _ in self?.onRearrange?() }, for: .touchUpInside)
        let done = UIButton(type: .system)
        done.setTitle("Done", for: .normal)
        done.titleLabel?.font = .boldSystemFont(ofSize: 15)
        done.addAction(UIAction { [weak self] _ in self?.onClose?() }, for: .touchUpInside)
        let bar = UIStackView(arrangedSubviews: [title, rearrange, done])
        bar.spacing = 16
        bar.alignment = .center

        // Tabs under the bar: each one shows only its own controls.
        let tabs = UISegmentedControl(items: SettingsPanel.tabNames)
        tabs.selectedSegmentIndex = 0
        tabs.addAction(UIAction { [weak self, weak tabs] _ in
            guard let self = self, let i = tabs?.selectedSegmentIndex else { return }
            self.tab = i
            self.scroll.setContentOffset(.zero, animated: false)
            self.build()
        }, for: .valueChanged)

        stack.axis = .vertical
        stack.spacing = 10
        scroll.addSubview(stack)
        for v in [bar, tabs, scroll] as [UIView] { v.translatesAutoresizingMaskIntoConstraints = false; addSubview(v) }
        stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            bar.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            bar.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
            bar.topAnchor.constraint(equalTo: topAnchor, constant: 6),
            bar.heightAnchor.constraint(equalToConstant: 32),
            tabs.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            tabs.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -14),
            tabs.topAnchor.constraint(equalTo: bar.bottomAnchor, constant: 4),
            tabs.heightAnchor.constraint(equalToConstant: 30),
            scroll.leadingAnchor.constraint(equalTo: leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: trailingAnchor),
            scroll.topAnchor.constraint(equalTo: tabs.bottomAnchor, constant: 6),
            scroll.bottomAnchor.constraint(equalTo: bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 14),
            stack.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -14),
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 4),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -12),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -28),
        ])

        // Swipe sideways to switch between portrait and landscape settings.
        for dir in [UISwipeGestureRecognizer.Direction.left, .right] {
            let g = UISwipeGestureRecognizer(target: self, action: #selector(swiped(_:)))
            g.direction = dir
            g.delegate = self
            scroll.addGestureRecognizer(g)
        }
        build()
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    func orientationChanged() {
        page = UIScreen.main.bounds.width > UIScreen.main.bounds.height
        build()
    }

    @objc private func swiped(_ g: UISwipeGestureRecognizer) {
        guard tab == 1, !settings.sameOrientations else { return }
        let landscape = g.direction == .left
        if landscape != page { page = landscape; build() }
    }

    func gestureRecognizer(_ g: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool { !(touch.view is UISlider) }
    func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    private func changed(rebuild: Bool = false) {
        onChange?()
        if rebuild { build() }
    }

    // MARK: - Content

    private func build() {
        stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        switch tab {
        case 0: buildLayoutTab()
        case 1: buildSizeTab()
        case 2: buildLookTab()
        default: buildTypingTab()
        }
    }

    private func buildLayoutTab() {
        // Templates and sharing. Codes go through the text field because the keyboard has no clipboard access.
        stack.addArrangedSubview(menuRow("Start from", current: "Choose", options: Template.allCases.map { $0.title }) { [weak self] title in
            guard let t = Template.allCases.first(where: { $0.title == title }) else { return }
            self?.onTemplate?(t)
        })
        stack.addArrangedSubview(linkButton("Share: type my layout code here") { [weak self] in self?.onTypeCode?() })
        let importButton = UIButton(type: .system)
        importButton.setTitle("Import: read a layout code from the text field", for: .normal)
        importButton.contentHorizontalAlignment = .leading
        importButton.titleLabel?.numberOfLines = 0
        importButton.addAction(UIAction { [weak self, weak importButton] _ in
            if self?.onImportCode?() == false {
                importButton?.setTitle("No layout code found. Paste one into the text field first.", for: .normal)
            }
        }, for: .touchUpInside)
        stack.addArrangedSubview(importButton)
    }

    private func buildSizeTab() {
        let l = page
        stack.addArrangedSubview(switchRow("Same for portrait and landscape", settings.sameOrientations) { [weak self] on in
            guard let self = self else { return }
            if on { self.settings.linkOrientations(from: self.page) } else { self.settings.set(Prefs.sameOrientations, false) }
            self.changed(rebuild: true)
        })
        if !settings.sameOrientations {
            let seg = UISegmentedControl(items: ["Portrait", "Landscape"])
            seg.selectedSegmentIndex = l ? 1 : 0
            seg.addAction(UIAction { [weak self, weak seg] _ in
                self?.page = seg?.selectedSegmentIndex == 1
                self?.build()
            }, for: .valueChanged)
            stack.addArrangedSubview(seg)
        }

        stack.addArrangedSubview(menuRow("Preset", current: currentPreset(l), options: presets(l).map { $0.name }) { [weak self] name in
            self?.applyPreset(name, l)
        })
        stack.addArrangedSubview(sliderRow("Height", Prefs.height(l), format: { "\($0)% of screen" }, onStart: { [weak self] in self?.captureRatio(l) }) { [weak self] v in
            guard let self = self else { return }
            self.settings.setFor(l, Prefs.height, v)
            if self.settings.lockSize { self.settings.setFor(l, Prefs.width, Int((CGFloat(v) / self.ratio).rounded())) }
        })
        let lock = UISwitch()
        lock.isOn = settings.lockSize
        lock.addAction(UIAction { [weak self, weak lock] _ in self?.settings.set(Prefs.lockSize, lock?.isOn ?? false) }, for: .valueChanged)
        let lockLabel = UILabel()
        lockLabel.text = "Lock"
        lockLabel.font = .systemFont(ofSize: 13)
        let lockRow = UIStackView(arrangedSubviews: [lockLabel, lock])
        lockRow.spacing = 4
        lockRow.alignment = .center
        let wasNarrow = settings.int(Prefs.width(l)) < 100
        stack.addArrangedSubview(sliderRow("Width", Prefs.width(l), format: { "\($0)%" }, extra: lockRow, onStart: { [weak self] in self?.captureRatio(l) }) { [weak self] v in
            guard let self = self else { return }
            self.settings.setFor(l, Prefs.width, v)
            if self.settings.lockSize { self.settings.setFor(l, Prefs.height, Int((CGFloat(v) * self.ratio).rounded())) }
            if (v < 100) != wasNarrow { self.build() }
        })
        if wasNarrow {
            let seg = UISegmentedControl(items: Align.allCases.map { $0.rawValue.capitalized })
            seg.selectedSegmentIndex = Align.allCases.firstIndex(of: settings.align(l)) ?? 1
            seg.addAction(UIAction { [weak self, weak seg] _ in
                guard let self = self, let i = seg?.selectedSegmentIndex else { return }
                self.settings.setAlignFor(l, Align.allCases[i])
                self.changed()
            }, for: .valueChanged)
            stack.addArrangedSubview(seg)
        }
        stack.addArrangedSubview(sliderRow("Raise from bottom edge", Prefs.bottomPadding(l), format: { "\($0) pt" }) { [weak self] v in
            self?.settings.setFor(l, Prefs.bottomPadding, v)
        })

        // Split: its settings only show while it's on.
        stack.addArrangedSubview(switchRow("Split", settings.split(l)) { [weak self] on in
            self?.settings.setSplitFor(l, on)
            self?.changed(rebuild: true)
        })
        if settings.split(l) {
            stack.addArrangedSubview(sliderRow("Gap", Prefs.splitGap(l), format: { "\($0)%" }) { [weak self] v in self?.settings.setFor(l, Prefs.splitGap, v) })
            stack.addArrangedSubview(sliderRow("Tilt", Prefs.tilt(l), format: { ($0 > 0 ? "+" : "") + "\($0)°" }) { [weak self] v in self?.settings.setFor(l, Prefs.tilt, v) })
            stack.addArrangedSubview(sliderRow("Ergonomic curve", Prefs.curve, format: { "\($0)%" }))
        }

    }

    private func buildLookTab() {
        stack.addArrangedSubview(menuRow("Theme", current: settings.theme.title, options: ThemeName.allCases.map { $0.title }) { [weak self] title in
            guard let self = self, let t = ThemeName.allCases.first(where: { $0.title == title }) else { return }
            self.settings.theme = t
            self.changed(rebuild: true)
        })
        let swatches = UIStackView()
        swatches.distribution = .fillEqually
        swatches.spacing = 6
        for theme in ThemeName.allCases {
            let palette = Palette.of(theme, dark: traitCollection.userInterfaceStyle == .dark)
            let button = UIButton(type: .system)
            button.setTitle("A", for: .normal)
            button.titleLabel?.font = .systemFont(ofSize: 17, weight: theme == .highContrast ? .bold : .regular)
            button.backgroundColor = palette.background
            button.setTitleColor(palette.text, for: .normal)
            button.layer.cornerRadius = 6
            button.layer.borderWidth = 2
            button.layer.borderColor = (theme == .highContrast ? UIColor.white : palette.accent).cgColor
            button.accessibilityLabel = theme.title
            button.accessibilityTraits = settings.theme == theme ? [.button, .selected] : [.button]
            button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            button.addAction(UIAction { [weak self] _ in
                self?.settings.theme = theme; self?.changed(rebuild: true)
            }, for: .touchUpInside)
            swatches.addArrangedSubview(button)
        }
        stack.addArrangedSubview(swatches)
        stack.addArrangedSubview(sliderRow("Space between keys", Prefs.keyGap, format: { "\($0) pt" }))
        stack.addArrangedSubview(sliderRow("Corner roundness", Prefs.cornerRadius, format: { "\($0) pt" }))
        stack.addArrangedSubview(sliderRow("Text size", Prefs.textScale, format: { "\($0)%" }))
        stack.addArrangedSubview(switchRow("Use system text size", settings.followSystemText) { [weak self] on in self?.settings.set(Prefs.followSystemText, on); self?.changed() })
        stack.addArrangedSubview(switchRow("Number row", settings.numberRow) { [weak self] on in
            guard let self = self else { return }
            if self.onNumberRow?(on) == false { self.build() }
            self.changed()
        })
        stack.addArrangedSubview(switchRow("Long-press hints", settings.showHints) { [weak self] on in self?.settings.set(Prefs.showHints, on); self?.changed() })
    }

    private func populateLearnedWords(_ rows: UIStackView, _ search: UISearchTextField, _ title: UILabel) {
        rows.arrangedSubviews.forEach { rows.removeArrangedSubview($0); $0.removeFromSuperview() }
        let all = LearningStore.words(settings.learningStore)
        let words = all.filter { search.text?.isEmpty != false || $0.word.localizedCaseInsensitiveContains(search.text ?? "") }
        title.text = "Learned words (\(all.count))"
        for entry in words.prefix(100) {
            rows.addArrangedSubview(linkButton("\(entry.word) (\(entry.language)) - Remove") { [weak self, weak rows, weak search, weak title] in
                guard let self = self, let rows = rows, let search = search, let title = title else { return }
                LearningStore.forget(self.settings.learningStore, language: entry.language, word: entry.word)
                self.onChange?(); self.populateLearnedWords(rows, search, title)
            })
        }
        if words.isEmpty { rows.addArrangedSubview(label("No learned words")) }
        if words.count > 100 { rows.addArrangedSubview(label("Search to narrow \(words.count) words.")) }
        rows.addArrangedSubview(label("Never corrected"))
        let blocked = LearningStore.rejected(settings.learningStore).filter { search.text?.isEmpty != false || $0.original.localizedCaseInsensitiveContains(search.text ?? "") || $0.fix.localizedCaseInsensitiveContains(search.text ?? "") }
        for entry in blocked.prefix(100) {
            rows.addArrangedSubview(linkButton("\(entry.original) \u{2192} \(entry.fix) (\(entry.language)) - Remove") { [weak self, weak rows, weak search, weak title] in
                guard let self = self, let rows = rows, let search = search, let title = title else { return }
                LearningStore.allowCorrection(self.settings.learningStore, entry)
                self.onChange?(); self.populateLearnedWords(rows, search, title)
            })
        }
        if blocked.isEmpty { rows.addArrangedSubview(label("No blocked corrections")) }

    }

    private func learnedWordList() -> UIView {
        let container = UIStackView(); container.axis = .vertical; container.spacing = 6
        let title = label("Learned words"); title.font = .boldSystemFont(ofSize: 15)
        let search = UISearchTextField(); search.placeholder = "Search learned words"; search.autocorrectionType = .no; search.autocapitalizationType = .none
        let rows = UIStackView(); rows.axis = .vertical
        search.addAction(UIAction { [weak self, weak rows, weak search, weak title] _ in
            guard let rows = rows, let search = search, let title = title else { return }
            self?.populateLearnedWords(rows, search, title)
        }, for: .editingChanged)
        [title, search, rows].forEach { container.addArrangedSubview($0) }
        search.heightAnchor.constraint(equalToConstant: 44).isActive = true
        populateLearnedWords(rows, search, title)
        return container
    }

    private func buildTypingTab() {
        let languages = label("Languages")
        languages.font = .boldSystemFont(ofSize: 15)
        stack.addArrangedSubview(languages)
        for code in Prefs.languageCodes {
            stack.addArrangedSubview(switchRow(Prefs.languageNames[code] ?? code, settings.languages.contains(code)) { [weak self] on in
                guard let self = self else { return }
                var enabled = self.settings.languages
                if on { enabled.append(code) } else { enabled.removeAll { $0 == code } }
                self.settings.setLanguages(enabled)
                self.onLanguageChange?()
                self.build()
            })
        }
        stack.addArrangedSubview(menuRow("Active language", current: Prefs.languageNames[settings.currentLanguage] ?? "",
            options: settings.languages.map { Prefs.languageNames[$0] ?? $0 }) { [weak self] title in
            guard let self = self, let code = Prefs.languageCodes.first(where: { Prefs.languageNames[$0] == title }) else { return }
            self.settings.currentLanguage = code
            self.onLanguageChange?()
            self.build()
        })
        stack.addArrangedSubview(menuRow("Autocorrect", current: ["Off", "Confident", "Aggressive"][settings.int(Prefs.autocorrect)], options: ["Off", "Confident", "Aggressive"]) { [weak self] value in
            self?.settings.set(Prefs.autocorrect, ["Off", "Confident", "Aggressive"].firstIndex(of: value) ?? 1); self?.changed(rebuild: true)
        })
        addNote("Fix typos when you press space. Backspace right after undoes it.")
        stack.addArrangedSubview(switchRow("Learn from my typing", settings.learning) { [weak self] value in self?.settings.set(Prefs.learning, value); self?.changed() })
        stack.addArrangedSubview(linkButton("Forget learned words") { [weak self] in self?.forgetConfirm = true; self?.build() })
        if forgetConfirm {
            addNote("Forget learned words, word sequences and blocked corrections in every language?")
            stack.addArrangedSubview(linkButton("Cancel") { [weak self] in self?.forgetConfirm = false; self?.build() })
            stack.addArrangedSubview(linkButton("Forget all") { [weak self] in
                guard let self = self else { return }
                LearningStore.forget(self.settings.learningStore); self.forgetConfirm = false; self.changed(rebuild: true)
            })
        }
        stack.addArrangedSubview(learnedWordList())
        stack.addArrangedSubview(switchRow("Capitalize sentences", settings.autoCap) { [weak self] on in self?.settings.set(Prefs.autoCap, on) })
        stack.addArrangedSubview(switchRow("Automatic space", settings.automaticSpace) { [weak self] on in self?.settings.set(Prefs.automaticSpace, on); self?.changed() })
        addNote("Add a space after swiped and picked words.")
        stack.addArrangedSubview(switchRow("Automatic punctuation", settings.automaticPunctuation) { [weak self] on in self?.settings.set(Prefs.automaticPunctuation, on); self?.changed(rebuild: true) })
        addNote("Attach punctuation and close brackets and quotes.")
        if settings.automaticPunctuation {
            stack.addArrangedSubview(switchRow("Double space types a period", settings.doubleSpace) { [weak self] on in self?.settings.set(Prefs.doubleSpace, on) })
        }
        stack.addArrangedSubview(switchRow("Key click sound", settings.clicks) { [weak self] on in self?.settings.set(Prefs.clicks, on) })
        stack.addArrangedSubview(sliderRow("Long-press delay", Prefs.longPressMs, format: { "\($0) ms" }))

        stack.addArrangedSubview(menuRow("Cursor control", current: settings.cursorControl.title, options: CursorControl.allCases.map { $0.title }) { [weak self] title in
            guard let self = self, let value = CursorControl.allCases.first(where: { $0.title == title }) else { return }
            self.settings.cursorControl = value; self.changed(rebuild: true)
        })
        addNote("Hold and push in the empty space to move the cursor.")
        stack.addArrangedSubview(switchRow("Cursor glow", settings.cursorGlow) { [weak self] on in
            self?.settings.set(Prefs.cursorGlow, on); self?.changed()
        })
        addNote("Show a soft glow across the keys when you move the cursor.")

        let accessibility = label("Accessibility")
        accessibility.font = .boldSystemFont(ofSize: 15)
        stack.addArrangedSubview(accessibility)
        stack.addArrangedSubview(switchRow("Type on press", settings.typeOnPress) { [weak self] on in
            self?.settings.set(Prefs.typeOnPress, on); self?.changed(rebuild: true)
        })
        stack.addArrangedSubview(sliderRow("Ignore repeated taps", Prefs.keyRepeatGuard, format: { $0 == 0 ? "Off" : "\($0) ms" }))
        stack.addArrangedSubview(sliderRow("Extra tap area", Prefs.touchSlack, format: { "\($0) pt" }))
        let assist = label("Assist")
        assist.font = .boldSystemFont(ofSize: 15)
        stack.addArrangedSubview(assist)
        stack.addArrangedSubview(switchRow("Highlight likely keys", settings.keyTint) { [weak self] on in self?.settings.set(Prefs.keyTint, on); self?.changed() })
        stack.addArrangedSubview(switchRow("Smart tap areas", settings.smartTargets) { [weak self] on in self?.settings.set(Prefs.smartTargets, on); self?.changed() })
        stack.addArrangedSubview(switchRow("Space accepts chosen word", settings.spaceAccepts) { [weak self] on in self?.settings.set(Prefs.spaceAccepts, on); self?.changed() })
        for (title, key, def) in [("Swipe typing", Prefs.swipeTyping, true), ("Chords", Prefs.chording, false)] {
            let row = switchRow(title, !settings.typeOnPress && settings.bool(key, def)) { [weak self] on in
                self?.settings.set(key, on); self?.changed(rebuild: true)
            }
            row.isUserInteractionEnabled = !settings.typeOnPress
            row.alpha = settings.typeOnPress ? 0.38 : 1
            (row as? UIStackView)?.arrangedSubviews.compactMap { $0 as? UISwitch }.forEach { $0.isEnabled = !settings.typeOnPress }
            stack.addArrangedSubview(row)
            if settings.typeOnPress {
                let note = label("Off while Type on press is on")
                note.font = .systemFont(ofSize: 12); note.textColor = .secondaryLabel
                note.numberOfLines = 0
                stack.addArrangedSubview(note)
            }
        }

        if settings.swipeTyping {
            stack.addArrangedSubview(menuRow("Swipe style", current: settings.swipeLetters ? "Type the letters" : "Guess the word",
                options: ["Guess the word", "Type the letters"]) { [weak self] value in
                self?.settings.set(Prefs.swipeLetters, value == "Type the letters"); self?.changed(rebuild: true)
            })
            addNote("Choose word guessing or the letters at turns and pauses.")
        }

        addNote("The chord list is in the TailorKeys app, under Typing → Chord list.")

        stack.addArrangedSubview(linkButton("Reset all settings") { [weak self] in
            guard let self = self else { return }
            self.settings.resetAll()
            self.onResetLayout?()
            self.changed(rebuild: true)
        })
    }

    private func captureRatio(_ l: Bool) {
        ratio = CGFloat(settings.int(Prefs.height(l))) / CGFloat(Swift.max(1, settings.int(Prefs.width(l))))
    }

    // MARK: - Presets

    private struct Preset { let name: String; let h: Int; let w: Int; let align: Align; let split: Bool; let gap: Int }

    private func presets(_ l: Bool) -> [Preset] {
        l ? [Preset(name: "Small", h: 49, w: 85, align: .center, split: false, gap: 0), Preset(name: "Default", h: 58, w: 100, align: .center, split: false, gap: 0),
             Preset(name: "Large", h: 69, w: 100, align: .center, split: false, gap: 0), Preset(name: "Left hand", h: 58, w: 55, align: .left, split: false, gap: 0),
             Preset(name: "Right hand", h: 58, w: 55, align: .right, split: false, gap: 0), Preset(name: "Split", h: 58, w: 100, align: .center, split: true, gap: 22)]
          : [Preset(name: "Small", h: 37, w: 90, align: .center, split: false, gap: 0), Preset(name: "Default", h: 44, w: 100, align: .center, split: false, gap: 0),
             Preset(name: "Large", h: 53, w: 100, align: .center, split: false, gap: 0), Preset(name: "Left hand", h: 46, w: 78, align: .left, split: false, gap: 0),
             Preset(name: "Right hand", h: 46, w: 78, align: .right, split: false, gap: 0), Preset(name: "Split", h: 44, w: 100, align: .center, split: true, gap: 16)]
    }

    private func currentPreset(_ l: Bool) -> String {
        let p = settings.profile(l)
        return presets(l).first { $0.h == p.heightPercent && $0.w == p.widthPercent && $0.align == p.align && $0.split == p.split }?.name ?? "Custom"
    }

    private func applyPreset(_ name: String, _ l: Bool) {
        guard let p = presets(l).first(where: { $0.name == name }) else { return }
        settings.setFor(l, Prefs.height, p.h)
        settings.setFor(l, Prefs.width, p.w)
        settings.setAlignFor(l, p.align)
        settings.setSplitFor(l, p.split)
        if p.split { settings.setFor(l, Prefs.splitGap, p.gap); settings.setFor(l, Prefs.tilt, 0) }
        changed(rebuild: true)
    }

    // MARK: - Row builders

    private func addNote(_ text: String) {
        let note = label(text)
        note.font = .systemFont(ofSize: 12); note.textColor = .secondaryLabel; note.numberOfLines = 0
        stack.addArrangedSubview(note)
    }

    private func label(_ text: String) -> UILabel {
        let l = UILabel()
        l.text = text
        l.font = .systemFont(ofSize: 15)
        l.setContentHuggingPriority(.defaultLow, for: .horizontal)
        return l
    }

    private func sliderRow(_ title: String, _ pref: IntPref, format: @escaping (Int) -> String, extra: UIView? = nil,
                           onStart: (() -> Void)? = nil, set: ((Int) -> Void)? = nil) -> UIView {
        let value = UILabel()
        value.font = .systemFont(ofSize: 13)
        value.textColor = .secondaryLabel
        value.text = format(settings.int(pref))
        var parts: [UIView] = [label(title)]
        if let e = extra { parts.append(e) }
        parts.append(value)
        let header = UIStackView(arrangedSubviews: parts)
        header.spacing = 10
        header.alignment = .center
        let slider = UISlider()
        slider.minimumValue = Float(pref.min)
        slider.maximumValue = Float(pref.max)
        slider.value = Float(settings.int(pref))
        slider.addAction(UIAction { _ in onStart?() }, for: .touchDown)
        slider.addAction(UIAction { [weak self, weak slider, weak value] _ in
            guard let self = self, let slider = slider else { return }
            let v = Int(slider.value.rounded())
            value?.text = format(v)
            if let set = set { set(v) } else { self.settings.set(pref, v) }
            self.onChange?()
        }, for: .valueChanged)
        let row = UIStackView(arrangedSubviews: [header, slider])
        row.axis = .vertical
        row.spacing = 2
        return row
    }

    private func switchRow(_ title: String, _ on: Bool, action: @escaping (Bool) -> Void) -> UIView {
        let sw = UISwitch()
        sw.isOn = on
        sw.addAction(UIAction { [weak sw] _ in action(sw?.isOn ?? false) }, for: .valueChanged)
        let row = UIStackView(arrangedSubviews: [label(title), sw])
        row.alignment = .center
        return row
    }

    private func menuRow(_ title: String, current: String, options: [String], action: @escaping (String) -> Void) -> UIView {
        let button = UIButton(type: .system)
        button.setTitle(current + " ▾", for: .normal)
        button.menu = UIMenu(children: options.map { o in UIAction(title: o, state: o == current ? .on : .off) { _ in action(o) } })
        button.showsMenuAsPrimaryAction = true
        let row = UIStackView(arrangedSubviews: [label(title), button])
        row.alignment = .center
        return row
    }

    private func linkButton(_ title: String, action: @escaping () -> Void) -> UIView {
        let b = UIButton(type: .system)
        b.setTitle(title, for: .normal)
        b.contentHorizontalAlignment = .leading
        b.addAction(UIAction { _ in action() }, for: .touchUpInside)
        return b
    }
}
