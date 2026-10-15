import UIKit

/// Settings shown inside the keyboard (tap the gear in the suggestion bar). Keeping them here means the
/// keyboard needs no App Group to share settings with the app, which free Apple IDs can't always use.
final class SettingsPanel: UIView, UIGestureRecognizerDelegate {

    var onChange: (() -> Void)?
    var onClose: (() -> Void)?
    var onRearrange: (() -> Void)?
    var onResetLayout: (() -> Void)?
    var onNumberRow: ((Bool) -> Bool)?
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
    /// Which tab is showing: 0 Layout, 1 Size, 2 Look, 3 Typing.
    private var tab = 0
    private static let tabNames = ["Layout", "Size", "Look", "Typing"]

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
        stack.addArrangedSubview(sliderRow("Space between keys", Prefs.keyGap, format: { "\($0) pt" }))
        stack.addArrangedSubview(sliderRow("Corner roundness", Prefs.cornerRadius, format: { "\($0) pt" }))
        stack.addArrangedSubview(sliderRow("Label size", Prefs.textScale, format: { "\($0)%" }))
        stack.addArrangedSubview(switchRow("Number row", settings.numberRow) { [weak self] on in
            guard let self = self else { return }
            if self.onNumberRow?(on) == false { self.build() }
            self.changed()
        })
        stack.addArrangedSubview(switchRow("Long-press hints", settings.showHints) { [weak self] on in self?.settings.set(Prefs.showHints, on); self?.changed() })
    }

    private func buildTypingTab() {
        stack.addArrangedSubview(switchRow("Capitalize sentences", settings.autoCap) { [weak self] on in self?.settings.set(Prefs.autoCap, on) })
        stack.addArrangedSubview(switchRow("Double space types a period", settings.doubleSpace) { [weak self] on in self?.settings.set(Prefs.doubleSpace, on) })
        stack.addArrangedSubview(switchRow("Key click sound", settings.clicks) { [weak self] on in self?.settings.set(Prefs.clicks, on) })
        stack.addArrangedSubview(sliderRow("Long-press delay", Prefs.longPressMs, format: { "\($0) ms" }))

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
        l ? [Preset(name: "Small", h: 44, w: 85, align: .center, split: false, gap: 0), Preset(name: "Default", h: 52, w: 100, align: .center, split: false, gap: 0),
             Preset(name: "Large", h: 62, w: 100, align: .center, split: false, gap: 0), Preset(name: "Left hand", h: 52, w: 55, align: .left, split: false, gap: 0),
             Preset(name: "Right hand", h: 52, w: 55, align: .right, split: false, gap: 0), Preset(name: "Split", h: 52, w: 100, align: .center, split: true, gap: 22)]
          : [Preset(name: "Small", h: 32, w: 90, align: .center, split: false, gap: 0), Preset(name: "Default", h: 38, w: 100, align: .center, split: false, gap: 0),
             Preset(name: "Large", h: 46, w: 100, align: .center, split: false, gap: 0), Preset(name: "Left hand", h: 40, w: 78, align: .left, split: false, gap: 0),
             Preset(name: "Right hand", h: 40, w: 78, align: .right, split: false, gap: 0), Preset(name: "Split", h: 38, w: 100, align: .center, split: true, gap: 16)]
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
