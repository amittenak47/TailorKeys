import UIKit

/// Compact quick options over the keys: a 4 × 2 grid of key-styled tiles (icon, label, state) that fits the keyboard
/// without scrolling; a scroll view remains only as a fallback for very small keyboards.
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
    private var headerHeight: NSLayoutConstraint!
    private var landscape: Bool { UIScreen.main.bounds.width > UIScreen.main.bounds.height }

    /// One grid cell: icon over label over a muted state line.
    private final class Tile: UIControl {
        let iconView = UIImageView()
        let glyph = UILabel()
        let label = UILabel()
        let stateLabel = UILabel()
        var on = false
        var action: (() -> Void)?
        init(icon: String?, title: String) {
            super.init(frame: .zero)
            iconView.contentMode = .scaleAspectFit
            iconView.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 16, weight: .medium)
            if let icon = icon { iconView.image = UIImage(systemName: icon) }
            glyph.font = .systemFont(ofSize: 15, weight: .bold)
            label.text = title
            label.font = .systemFont(ofSize: 12, weight: .medium)
            stateLabel.font = .systemFont(ofSize: 11)
            for l in [glyph, label, stateLabel] { l.textAlignment = .center; l.adjustsFontSizeToFitWidth = true; l.minimumScaleFactor = 0.8 }
            let top: UIView = icon == nil ? glyph : iconView
            let stack = UIStackView(arrangedSubviews: [top, label, stateLabel])
            stack.axis = .vertical
            stack.alignment = .center
            stack.spacing = 1
            stack.isUserInteractionEnabled = false
            stack.translatesAutoresizingMaskIntoConstraints = false
            addSubview(stack)
            NSLayoutConstraint.activate([
                top.heightAnchor.constraint(equalToConstant: 20),
                stack.centerXAnchor.constraint(equalTo: centerXAnchor), stack.centerYAnchor.constraint(equalTo: centerYAnchor),
                stack.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 2),
                heightAnchor.constraint(equalToConstant: 60),
            ])
            isAccessibilityElement = true
            accessibilityTraits = .button
            addAction(UIAction { [weak self] _ in self?.action?() }, for: .touchUpInside)
        }
        required init?(coder: NSCoder) { fatalError("not used") }
    }

    private var tiles: [Tile] = []
    private var split: Tile!
    private var oneHand: Tile!
    private var size: Tile!
    private let smaller = UIButton(type: .system)
    private let larger = UIButton(type: .system)
    private var theme: Tile!
    private var language: Tile!
    private var swipe: Tile!
    private var all: Tile!

    init(settings: KeyboardSettings, stripHeight: @escaping () -> CGFloat) {
        self.settings = settings
        self.stripHeight = stripHeight
        super.init(frame: .zero)
        accessibilityViewIsModal = true
        titleLabel.text = "Quick options"
        titleLabel.font = .systemFont(ofSize: 15, weight: .semibold)
        titleLabel.accessibilityTraits = .header
        closeButton.setTitle("Done", for: .normal)
        closeButton.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        closeButton.accessibilityLabel = "Close quick options"
        closeButton.addAction(UIAction { [weak self] _ in self?.onClose?() }, for: .touchUpInside)
        let header = UIStackView(arrangedSubviews: [titleLabel, closeButton])
        header.alignment = .center
        closeButton.widthAnchor.constraint(equalToConstant: 64).isActive = true
        let scroll = UIScrollView()
        scroll.alwaysBounceVertical = false
        scroll.showsVerticalScrollIndicator = false
        let rows = UIStackView()
        rows.axis = .vertical
        rows.spacing = 6
        for v in [header, scroll] as [UIView] { v.translatesAutoresizingMaskIntoConstraints = false; addSubview(v) }
        rows.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(rows)
        headerHeight = header.heightAnchor.constraint(equalToConstant: stripHeight())
        NSLayoutConstraint.activate([
            header.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            header.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -6),
            header.topAnchor.constraint(equalTo: topAnchor), headerHeight,
            scroll.leadingAnchor.constraint(equalTo: leadingAnchor), scroll.trailingAnchor.constraint(equalTo: trailingAnchor),
            scroll.topAnchor.constraint(equalTo: header.bottomAnchor), scroll.bottomAnchor.constraint(equalTo: bottomAnchor),
            rows.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 8),
            rows.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor, constant: -8),
            rows.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 2),
            rows.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -8),
            rows.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -16),
        ])
        func row() -> UIStackView {
            let r = UIStackView(); r.spacing = 6; r.distribution = .fillEqually
            rows.addArrangedSubview(r)
            return r
        }
        func tile(_ title: String, icon: String?, in r: UIStackView, changes: Bool = true, action: ((QuickOptions) -> Void)?) -> Tile {
            let t = Tile(icon: icon, title: title)
            if let action = action {
                t.action = { [weak self] in
                    guard let self = self else { return }
                    action(self)
                    if changes { self.onChange?() }
                    self.refresh()
                }
            }
            r.addArrangedSubview(t)
            tiles.append(t)
            return t
        }
        let first = row()
        _ = tile("Rearrange", icon: "square.grid.3x2", in: first, changes: false) { $0.onRearrange?() }
        split = tile("Split", icon: "rectangle.split.2x1", in: first) { s in s.settings.setSplitFor(s.landscape, !s.settings.split(s.landscape)) }
        oneHand = tile("One hand", icon: "hand.point.up.left", in: first) { $0.settings.cycleQuickOneHand($0.landscape) }
        size = tile("Size", icon: "arrow.up.and.down", in: first, action: nil)
        // Size keeps its − / + as two halves of its state line.
        size.stateLabel.isHidden = true
        size.isAccessibilityElement = false
        for (b, title, delta) in [(smaller, "−", -2), (larger, "+", 2)] {
            b.setTitle(title, for: .normal)
            b.titleLabel?.font = .systemFont(ofSize: 18, weight: .medium)
            b.addAction(UIAction { [weak self] _ in
                guard let self = self else { return }
                self.settings.adjustQuickHeight(self.landscape, delta)
                self.onChange?()
                self.refresh()
            }, for: .touchUpInside)
        }
        let steps = UIStackView(arrangedSubviews: [smaller, larger])
        steps.distribution = .fillEqually
        steps.translatesAutoresizingMaskIntoConstraints = false
        size.addSubview(steps)
        NSLayoutConstraint.activate([
            steps.leadingAnchor.constraint(equalTo: size.leadingAnchor), steps.trailingAnchor.constraint(equalTo: size.trailingAnchor),
            steps.bottomAnchor.constraint(equalTo: size.bottomAnchor), steps.heightAnchor.constraint(equalToConstant: 26),
        ])
        let second = row()
        theme = tile("Theme", icon: "circle.lefthalf.filled", in: second) { $0.settings.cycleQuickTheme() }
        language = tile("Language", icon: nil, in: second, changes: false) { $0.onLanguage?() }
        swipe = tile("Swipe", icon: "scribble", in: second) { s in s.settings.set(Prefs.swipeTyping, !s.settings.bool(Prefs.swipeTyping, true)) }
        all = tile("Settings", icon: "gearshape", in: second, changes: false) { $0.onSettings?() }
        refresh()
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    func refresh() {
        let p = Palette.of(settings.theme, dark: traitCollection.userInterfaceStyle == .dark)
        backgroundColor = p.background
        titleLabel.textColor = p.text
        closeButton.tintColor = p.accent
        headerHeight.constant = stripHeight()
        func state(_ t: Tile, _ value: String, on: Bool = false, enabled: Bool = true) {
            t.stateLabel.text = value
            t.on = on
            t.isEnabled = enabled
            t.accessibilityLabel = t.label.text
            t.accessibilityValue = value
        }
        state(split, settings.split(landscape) ? "On" : "Off", on: settings.split(landscape))
        let hand: String
        switch settings.quickOneHand(landscape) { case .center: hand = "Off"; case .left: hand = "Left"; case .right: hand = "Right" }
        state(oneHand, hand, on: hand != "Off")
        let height = settings.int(Prefs.height(landscape))
        size.label.text = "Size \(height)%"
        smaller.accessibilityLabel = "Size, decrease"
        larger.accessibilityLabel = "Size, increase"
        for b in [smaller, larger] { b.accessibilityValue = "\(height) percent"; b.tintColor = p.text }
        smaller.isEnabled = height > Prefs.height(landscape).min
        larger.isEnabled = height < Prefs.height(landscape).max
        state(theme, settings.theme == .system ? "System" : settings.theme.title)
        language.glyph.text = settings.currentLanguage.uppercased()
        let multi = settings.languages.count > 1
        state(language, multi ? "Tap to switch" : "Only one", enabled: multi)
        let swipeOn = settings.bool(Prefs.swipeTyping, true)
        state(swipe, swipeOn ? "On" : "Off", on: swipeOn)
        state(all, "All")
        let radius = CGFloat(settings.int(Prefs.cornerRadius))
        for t in tiles {
            t.alpha = t.isEnabled ? 1 : 0.45
            t.backgroundColor = p.key
            t.layer.cornerRadius = radius
            let ink = t.on ? p.accent : p.text
            t.iconView.tintColor = ink
            t.glyph.textColor = ink
            t.label.textColor = ink
            t.stateLabel.textColor = t.on ? p.accent : p.hint
        }
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        if headerHeight != nil { refresh() }
    }

    override func accessibilityPerformEscape() -> Bool { onClose?(); return true }
}
