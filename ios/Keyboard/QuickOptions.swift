import UIKit

/// Native tiles over the keyboard, with scrolling at small heights and standard VoiceOver controls.
final class QuickOptions: UIView {
    var onClose: (() -> Void)?
    var onRearrange: (() -> Void)?
    var onSettings: (() -> Void)?
    var onLanguage: (() -> Void)?
    var onChange: (() -> Void)?
    private let settings: KeyboardSettings
    private let stripHeight: () -> CGFloat
    private let titleLabel = UILabel()
    private let closeButton = UIButton(type: .system)
    private let sizeLabel = UILabel()
    private var headerHeight: NSLayoutConstraint!
    private var buttons: [UIButton] = []
    private var split: UIButton!
    private var oneHand: UIButton!
    private var smaller: UIButton!
    private var larger: UIButton!
    private var theme: UIButton!
    private var language: UIButton!
    private var swipe: UIButton!
    private var landscape: Bool { UIScreen.main.bounds.width > UIScreen.main.bounds.height }

    init(settings: KeyboardSettings, stripHeight: @escaping () -> CGFloat) {
        self.settings = settings
        self.stripHeight = stripHeight
        super.init(frame: .zero)
        accessibilityViewIsModal = true
        titleLabel.text = "Quick options"
        titleLabel.font = .systemFont(ofSize: 16, weight: .semibold)
        titleLabel.accessibilityTraits = .header
        closeButton.setTitle("✕", for: .normal)
        closeButton.accessibilityLabel = "Close quick options"
        closeButton.addAction(UIAction { [weak self] _ in self?.onClose?() }, for: .touchUpInside)
        let header = UIStackView(arrangedSubviews: [titleLabel, closeButton])
        header.alignment = .center
        closeButton.widthAnchor.constraint(equalToConstant: 48).isActive = true
        let scroll = UIScrollView()
        scroll.alwaysBounceVertical = false
        let rows = UIStackView()
        rows.axis = .vertical
        rows.spacing = 6
        for v in [header, scroll] as [UIView] { v.translatesAutoresizingMaskIntoConstraints = false; addSubview(v) }
        rows.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(rows)
        headerHeight = header.heightAnchor.constraint(equalToConstant: stripHeight())
        NSLayoutConstraint.activate([
            header.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            header.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -6),
            header.topAnchor.constraint(equalTo: topAnchor), headerHeight,
            scroll.leadingAnchor.constraint(equalTo: leadingAnchor), scroll.trailingAnchor.constraint(equalTo: trailingAnchor),
            scroll.topAnchor.constraint(equalTo: header.bottomAnchor), scroll.bottomAnchor.constraint(equalTo: bottomAnchor),
            rows.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 8),
            rows.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -8),
            rows.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 4),
            rows.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -8),
            rows.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -16),
        ])
        func row() -> UIStackView {
            let r = UIStackView(); r.spacing = 6; r.distribution = .fillEqually; r.alignment = .fill
            rows.addArrangedSubview(r)
            return r
        }
        let first = row()
        first.addArrangedSubview(tile("Rearrange", icon: "arrow.left.arrow.right", changes: false) { $0.onRearrange?() })
        split = tile("Split", icon: "rectangle.split.2x1") { s in s.settings.setSplitFor(s.landscape, !s.settings.split(s.landscape)) }
        first.addArrangedSubview(split)
        let second = row()
        oneHand = tile("One hand", icon: "hand.point.up.left") { $0.settings.cycleQuickOneHand($0.landscape) }
        second.addArrangedSubview(oneHand)
        let size = UIStackView(); size.axis = .vertical; size.spacing = 2
        sizeLabel.textAlignment = .center
        sizeLabel.font = .systemFont(ofSize: 13)
        size.addArrangedSubview(sizeLabel)
        let sizeButtons = UIStackView(); sizeButtons.distribution = .fillEqually; sizeButtons.spacing = 6
        smaller = tile("−", icon: nil) { $0.settings.adjustQuickHeight($0.landscape, -2) }
        larger = tile("+", icon: nil) { $0.settings.adjustQuickHeight($0.landscape, 2) }
        for b in [smaller!, larger!] { sizeButtons.addArrangedSubview(b) }
        size.addArrangedSubview(sizeButtons)
        second.addArrangedSubview(size)
        let third = row()
        theme = tile("Theme", icon: "circle.lefthalf.filled") { $0.settings.cycleQuickTheme() }
        language = tile("Language", icon: "globe", changes: false) { $0.onLanguage?() }
        third.addArrangedSubview(theme); third.addArrangedSubview(language)
        let fourth = row()
        swipe = tile("Swipe typing", icon: "scribble") { s in s.settings.set(Prefs.swipeTyping, !s.settings.bool(Prefs.swipeTyping, true)) }
        fourth.addArrangedSubview(swipe)
        fourth.addArrangedSubview(tile("All settings", icon: "gearshape", changes: false) { $0.onSettings?() })
        refresh()
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    private func tile(_ label: String, icon: String?, changes: Bool = true, action: @escaping (QuickOptions) -> Void) -> UIButton {
        let b = UIButton(type: .system)
        b.setTitle(label, for: .normal)
        if let icon = icon { b.setImage(UIImage(systemName: icon), for: .normal) }
        b.titleLabel?.font = UIFontMetrics(forTextStyle: .body).scaledFont(for: .systemFont(ofSize: 14))
        b.titleLabel?.adjustsFontForContentSizeCategory = true
        b.titleLabel?.numberOfLines = 0
        b.titleLabel?.textAlignment = .center
        b.contentEdgeInsets = UIEdgeInsets(top: 8, left: 6, bottom: 8, right: 6)
        b.heightAnchor.constraint(greaterThanOrEqualToConstant: 56).isActive = true
        b.widthAnchor.constraint(greaterThanOrEqualToConstant: 48).isActive = true
        b.accessibilityLabel = label
        b.layer.cornerRadius = 10
        b.layer.borderWidth = 1
        b.addAction(UIAction { [weak self] _ in
            guard let self = self else { return }
            action(self)
            if changes { self.onChange?() }
            self.refresh()
        }, for: .touchUpInside)
        buttons.append(b)
        return b
    }

    func refresh() {
        let p = Palette.of(settings.theme, dark: traitCollection.userInterfaceStyle == .dark)
        backgroundColor = p.background
        titleLabel.textColor = p.text
        closeButton.tintColor = p.accent
        headerHeight.constant = stripHeight()
        func state(_ b: UIButton, _ label: String, _ value: String, on: Bool = false) {
            b.setTitle(" \(label)\n\(value)", for: .normal)
            b.accessibilityLabel = label
            b.accessibilityValue = value
            b.isSelected = on
        }
        state(split, "Split", settings.split(landscape) ? "On" : "Off", on: settings.split(landscape))
        let hand: String
        switch settings.quickOneHand(landscape) { case .center: hand = "Off"; case .left: hand = "Left"; case .right: hand = "Right" }
        state(oneHand, "One hand", hand, on: hand != "Off")
        let height = settings.int(Prefs.height(landscape))
        sizeLabel.text = "↕ Size \(height)%"
        sizeLabel.textColor = p.text
        smaller.accessibilityLabel = "Size, decrease"
        larger.accessibilityLabel = "Size, increase"
        for b in [smaller!, larger!] { b.accessibilityValue = "\(height) percent" }
        smaller.isEnabled = height > Prefs.height(landscape).min
        larger.isEnabled = height < Prefs.height(landscape).max
        state(theme, "Theme", settings.theme == .system ? "System" : settings.theme.title)
        state(language, "Language", settings.currentLanguage.uppercased())
        language.isEnabled = settings.languages.count > 1
        let swipeOn = settings.bool(Prefs.swipeTyping, true)
        state(swipe, "Swipe typing", swipeOn ? "On" : "Off", on: swipeOn)
        for b in buttons {
            b.alpha = b.isEnabled ? 1 : 0.4
            b.backgroundColor = b.isSelected ? p.accent : p.key
            b.tintColor = b.isSelected ? p.onAccent : p.text
            b.setTitleColor(b.isSelected ? p.onAccent : p.text, for: .normal)
            b.layer.borderColor = (b.isSelected ? p.accent : p.hint).cgColor
        }
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        if headerHeight != nil { refresh() }
    }

    override func accessibilityPerformEscape() -> Bool { onClose?(); return true }
}
