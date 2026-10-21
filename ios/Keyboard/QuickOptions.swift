import UIKit
import SwiftUI

/// Built once alongside the keyboard. Opening only refreshes changed values and cached artwork.
final class QuickOptions: UIView, UIScrollViewDelegate {
    var onClose: (() -> Void)?
    var onEdit: ((String, String) -> Void)?
    var onLanguage: (() -> Void)?
    var onTidy: (() -> Void)?
    private var review: UIView?
    var onChange: (() -> Void)?
    private let settings: KeyboardSettings
    private var library: LayoutLibrary
    private let stripHeight: () -> CGFloat
    private var landscape: Bool { UIScreen.main.bounds.width > UIScreen.main.bounds.height }
    private let back = UIButton(type: .system)
    private let heading = UILabel()
    private let trailing = UIButton(type: .system)
    private let message = UILabel()
    private let pages = UIView()
    private let main = UIView()
    private let all = UIScrollView()
    private let strip = UIScrollView()
    private let fade = CAGradientLayer()
    private let editButton = UIButton(type: .system)
    private let newButton = UIButton(type: .system)
    private let tileScroll = UIScrollView()
    private let tileGrid = UIView()
    private let newLayout = UIButton(type: .system)
    private let paste = UIButton(type: .system)
    private let smaller = UIButton(type: .system)
    private let larger = UIButton(type: .system)
    private let confirm = UIView()
    private let confirmBox = UIView()
    private let confirmTitle = UILabel()
    private let confirmText = UILabel()
    private let cancel = UIButton(type: .system)
    private let saveContinue = UIButton(type: .system)
    private var pendingCopy: (String, Bool)?
    private var showingAll = false
    private var pageTransition = 0
    private let newLayoutOutline = CAShapeLayer()
    private var stripViews: [String: Card] = [:]
    private var gridViews: [String: Card] = [:]
    private var orderedKeys: [String] = []
    private var renderedSignature = ""
    private var renderedTablet: Bool?
    private var scrollToApplied = false
    private var tiles: [Tile] = []
    private(set) var settingsLinkController: UIHostingController<QuickSettingsLink>?

    private final class Tile: UIControl {
        let icon = UIImageView()
        let glyph = UILabel()
        let title = UILabel()
        let state = UILabel()
        var action: (() -> Void)?
        init(_ text: String, icon symbol: String?) {
            super.init(frame: .zero)
            if let symbol = symbol { icon.image = UIImage(systemName: symbol) }
            icon.contentMode = .scaleAspectFit
            icon.preferredSymbolConfiguration = UIImage.SymbolConfiguration(pointSize: 16, weight: .medium)
            title.text = text
            title.font = .systemFont(ofSize: 12, weight: .medium)
            glyph.font = .systemFont(ofSize: 15, weight: .bold)
            state.font = .systemFont(ofSize: 11)
            for label in [glyph, title, state] { label.textAlignment = .center; label.adjustsFontSizeToFitWidth = true; label.minimumScaleFactor = 0.8; label.isAccessibilityElement = false; addSubview(label) }
            addSubview(icon)
            isAccessibilityElement = true
            accessibilityTraits = .button
            addAction(UIAction { [weak self] _ in self?.action?() }, for: .touchUpInside)
        }
        required init?(coder: NSCoder) { fatalError("not used") }
        override func layoutSubviews() {
            super.layoutSubviews()
            let y = (bounds.height - 50) / 2
            icon.frame = CGRect(x: (bounds.width - 20) / 2, y: y, width: 20, height: 20)
            glyph.frame = icon.frame
            title.frame = CGRect(x: 2, y: y + 22, width: bounds.width - 4, height: 15)
            state.frame = CGRect(x: 2, y: y + 37, width: bounds.width - 4, height: 13)
        }
    }

    private final class Card: UIControl {
        let thumbnail = UIImageView()
        let name = UILabel()
        let check = UILabel()
        let unsaved = UILabel()
        let fullName: Bool
        var action: (() -> Void)?
        init(fullName: Bool) {
            self.fullName = fullName
            super.init(frame: .zero)
            thumbnail.contentMode = .scaleAspectFit
            thumbnail.clipsToBounds = true
            thumbnail.layer.cornerRadius = 6
            name.font = .systemFont(ofSize: 12)
            name.numberOfLines = fullName ? 2 : 1
            check.text = "\u{2713}"
            check.textAlignment = .center
            check.font = .systemFont(ofSize: 12, weight: .bold)
            check.layer.cornerRadius = 12; check.clipsToBounds = true
            unsaved.text = "Unsaved"
            unsaved.textAlignment = .center
            unsaved.font = .systemFont(ofSize: 10, weight: .semibold)
            unsaved.layer.cornerRadius = 10; unsaved.clipsToBounds = true
            for view in [thumbnail, name, check, unsaved] as [UIView] { view.isUserInteractionEnabled = false; addSubview(view) }
            isAccessibilityElement = true
            accessibilityTraits = .button
            addAction(UIAction { [weak self] _ in self?.action?() }, for: .touchUpInside)
        }
        required init?(coder: NSCoder) { fatalError("not used") }
        override func layoutSubviews() {
            super.layoutSubviews()
            let nameHeight: CGFloat = fullName ? 34 : 22
            thumbnail.frame = CGRect(x: 6, y: 6, width: bounds.width - 12, height: bounds.height - nameHeight - 10)
            name.frame = CGRect(x: 6, y: thumbnail.frame.maxY + 2, width: bounds.width - 12, height: nameHeight)
            check.frame = CGRect(x: 8, y: 8, width: 24, height: 24)
            unsaved.frame = CGRect(x: bounds.width - 65, y: 8, width: 57, height: 20)
        }
    }

    init(settings: KeyboardSettings, stripHeight: @escaping () -> CGFloat) {
        self.settings = settings
        self.library = settings.makeLayoutLibrary()
        self.stripHeight = stripHeight
        super.init(frame: .zero)
        accessibilityViewIsModal = true
        heading.font = .systemFont(ofSize: 15, weight: .bold)
        message.font = .systemFont(ofSize: 12)
        message.numberOfLines = 2
        message.isHidden = true
        for view in [back, heading, trailing, message, pages] as [UIView] { addSubview(view) }
        for view in [main, all] { pages.addSubview(view) }
        addSubview(confirm)
        for view in [editButton, newButton, strip, tileScroll] as [UIView] { main.addSubview(view) }
        tileScroll.addSubview(tileGrid)
        strip.showsHorizontalScrollIndicator = false
        strip.delegate = self
        strip.layer.mask = fade
        tileScroll.showsVerticalScrollIndicator = false
        all.showsVerticalScrollIndicator = true
        all.isHidden = true
        all.addSubview(newLayout); all.addSubview(paste)
        newLayoutOutline.fillColor = UIColor.clear.cgColor
        newLayoutOutline.lineWidth = 2; newLayoutOutline.lineDashPattern = [5, 3]
        newLayout.layer.insertSublayer(newLayoutOutline, at: 0)
        configure(back, title: "\u{2039}  Back") { [weak self] in
            guard let self = self else { return }
            if !self.confirm.isHidden { self.hideConfirm() }
            else if self.showingAll { self.showAll(false) } else { self.onClose?() }
        }
        back.accessibilityLabel = "Back to keyboard"
        configure(trailing, title: "All layouts \u{203a}") { [weak self] in
            guard let self = self else { return }
            if self.showingAll { self.edit(newCopy: false) } else { self.showAll(true) }
        }
        configure(editButton, title: "Edit", symbol: "pencil", vertical: true) { [weak self] in self?.edit(newCopy: false) }
        configure(newButton, title: "New", symbol: "plus", vertical: true) { [weak self] in
            guard let self = self else { return }
            if self.library.appliedKey == "draft" { _ = self.library.saveDraft(name: self.library.nextName("Custom")); self.refresh() }
            else { self.edit(newCopy: true) }
        }
        configure(newLayout, title: "New layout", symbol: "plus", vertical: true) { [weak self] in self?.edit(newCopy: true) }
        configure(paste, title: "Paste code", symbol: "doc.on.clipboard") { [weak self] in self?.pasteCode() }
        func tile(_ title: String, _ symbol: String?, action: ((QuickOptions) -> Void)? = nil) -> Tile {
            let tile = Tile(title, icon: symbol)
            if let action = action {
                tile.action = { [weak self] in
                    guard let self = self else { return }
                    action(self); self.onChange?(); self.refresh()
                }
            }
            tiles.append(tile); tileGrid.addSubview(tile)
            return tile
        }
        _ = tile("Split", "rectangle.split.2x1") { s in s.settings.setSplitFor(s.landscape, !s.settings.split(s.landscape)) }
        _ = tile("One hand", "hand.point.up.left") { $0.settings.cycleQuickOneHand($0.landscape) }
        let size = tile("Size", "arrow.up.and.down")
        size.isAccessibilityElement = false
        size.addSubview(smaller); size.addSubview(larger)
        size.accessibilityElements = [smaller, larger]
        for (button, title, delta) in [(smaller, "\u{2212}", -2), (larger, "+", 2)] {
            configure(button, title: title) { [weak self] in
                guard let self = self else { return }
                self.settings.adjustQuickHeight(self.landscape, delta); self.onChange?(); self.refresh()
            }
        }
        _ = tile("Learning", "brain") { s in s.settings.set(Prefs.learning, !s.settings.learning) }
        let language = tile("Language", nil)
        language.action = { [weak self] in self?.onLanguage?(); self?.refresh() }
        _ = tile("Tidy", "text.badge.checkmark") { $0.onTidy?() }
        let settingsTile = tile("Settings", "slider.horizontal.3")
        // SwiftUI Link handles app URLs in a keyboard extension without UIApplication.shared.
        let link = UIHostingController(rootView: QuickSettingsLink())
        link.view.backgroundColor = .clear
        settingsTile.addSubview(link.view)
        settingsTile.isAccessibilityElement = false
        settingsLinkController = link
        confirm.isHidden = true
        confirm.backgroundColor = UIColor.black.withAlphaComponent(0.4)
        confirm.accessibilityViewIsModal = true
        confirm.addSubview(confirmBox)
        confirmTitle.text = "Save your unsaved Custom first?"
        confirmTitle.font = .systemFont(ofSize: 16, weight: .bold); confirmTitle.numberOfLines = 2
        confirmText.text = "There is room for one draft. Save this one before starting another."
        confirmText.font = .systemFont(ofSize: 13); confirmText.numberOfLines = 0
        for v in [confirmTitle, confirmText, cancel, saveContinue] as [UIView] { confirmBox.addSubview(v) }
        configure(cancel, title: "Keep Custom") { [weak self] in self?.hideConfirm() }
        configure(saveContinue, title: "Save and continue") { [weak self] in self?.continueEdit() }
        refresh()
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    private func configure(_ button: UIButton, title: String, symbol: String? = nil, vertical: Bool = false, action: @escaping () -> Void) {
        var config = UIButton.Configuration.plain()
        config.title = title
        config.image = symbol.flatMap { UIImage(systemName: $0) }
        config.imagePlacement = vertical ? .top : .leading
        config.imagePadding = vertical ? 2 : 6
        config.contentInsets = NSDirectionalEdgeInsets(top: 0, leading: 4, bottom: 0, trailing: 4)
        config.titleTextAttributesTransformer = UIConfigurationTextAttributesTransformer { original in
            var result = original
            result.font = UIFont.systemFont(ofSize: vertical ? 12 : 14, weight: .medium)
            return result
        }
        button.configuration = config
        button.addAction(UIAction { _ in action() }, for: .touchUpInside)
    }

    func showTidy(_ fixes: [TidyFix], apply: @escaping ([TidyFix]) -> Void) {
        review?.removeFromSuperview()
        let scroll = UIScrollView(frame: pages.bounds); scroll.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        scroll.backgroundColor = Palette.of(settings.theme, dark: traitCollection.userInterfaceStyle == .dark).background
        let stack = UIStackView(); stack.axis = .vertical; stack.spacing = 6
        var checked = [Bool](repeating: true, count: fixes.count)
        for (index, fix) in fixes.enumerated() {
            let button = UIButton(type: .system)
            button.setTitle("\u{2611} \(fix.original) \u{2192} \(fix.replacement)", for: .normal)
            button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
            button.addAction(UIAction { [weak button] _ in
                checked[index].toggle()
                button?.setTitle("\(checked[index] ? "\u{2611}" : "\u{2610}") \(fix.original) \u{2192} \(fix.replacement)", for: .normal)
            }, for: .touchUpInside)
            stack.addArrangedSubview(button)
        }
        let button = UIButton(type: .system)
        button.setTitle(fixes.isEmpty ? "No fixes found. Back" : "Apply selected fixes", for: .normal)
        button.heightAnchor.constraint(equalToConstant: 48).isActive = true
        button.addAction(UIAction { [weak self] _ in
            apply(fixes.enumerated().filter { checked[$0.offset] }.map { $0.element })
            self?.review?.removeFromSuperview(); self?.review = nil
        }, for: .touchUpInside)
        stack.addArrangedSubview(button)
        scroll.addSubview(stack); stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor, constant: 8),
            stack.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor, constant: -8),
            stack.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor, constant: 12),
            stack.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor, constant: -24)
        ])
        review = scroll; pages.addSubview(scroll)
    }

    func reloadStore() {
        library = settings.makeLayoutLibrary()
        renderedSignature = ""
        refresh()
    }

    func refresh(resetScroll: Bool = false) {
        library.reload()
        if resetScroll { review?.removeFromSuperview(); review = nil; showAll(false, animated: false); hideConfirm(); message.isHidden = true; tileScroll.contentOffset = .zero; scrollToApplied = true }
        let p = Palette.of(settings.theme, dark: traitCollection.userInterfaceStyle == .dark)
        let choices = library.quickTiles
        let signature = "\(choices.map { $0.choice.id + $0.choice.name + $0.choice.gridJSON })|\(library.appliedKey)|\(settings.theme)|\(traitCollection.userInterfaceStyle.rawValue)|\(settings.profile(landscape))|\(settings.currentLanguage)|\(settings.languages)|\(settings.learning)|\(settings.int(Prefs.curve))|\(settings.int(Prefs.keyGap))|\(settings.int(Prefs.cornerRadius))|\(settings.int(Prefs.textScale))|\(settings.followSystemText)|\(UITraitCollection.current.preferredContentSizeCategory.rawValue)|\(UIScreen.main.bounds.size)"
        guard signature != renderedSignature else { if resetScroll { setNeedsLayout() }; return }
        renderedSignature = signature
        backgroundColor = p.background
        for b in [back, trailing, paste] { b.tintColor = p.smallAccent }
        editButton.tintColor = p.text
        heading.textColor = p.text; message.textColor = p.text
        let radius = CGFloat(settings.int(Prefs.cornerRadius))
        for b in [editButton, newButton, cancel] { b.backgroundColor = p.key; b.layer.cornerRadius = radius }
        confirmBox.backgroundColor = p.key; confirmBox.layer.cornerRadius = radius
        confirmTitle.textColor = p.text; confirmText.textColor = p.hint; cancel.tintColor = p.text
        saveContinue.backgroundColor = p.accent; saveContinue.tintColor = p.onAccent; saveContinue.layer.cornerRadius = radius
        newLayout.tintColor = p.text
        let keys = choices.map { $0.choice.id }
        for key in orderedKeys where !keys.contains(key) { stripViews.removeValue(forKey: key)?.removeFromSuperview(); gridViews.removeValue(forKey: key)?.removeFromSuperview() }
        orderedKeys = keys
        for state in choices {
            let choice = state.choice
            for full in [false, true] {
                let card: Card
                if let existing = full ? gridViews[choice.id] : stripViews[choice.id] { card = existing }
                else {
                    card = Card(fullName: full)
                    if full { gridViews[choice.id] = card; all.addSubview(card) } else { stripViews[choice.id] = card; strip.addSubview(card) }
                    card.action = { [weak self] in
                        guard let self = self else { return }
                        self.library.use(choice.id, landscape: self.landscape)
                        self.onChange?(); self.refresh()
                    }
                }
                card.name.text = choice.name
                card.name.font = .systemFont(ofSize: bounds.width >= 600 ? 14 : 12, weight: state.bold ? .bold : .medium)
                card.name.textColor = p.text
                card.backgroundColor = p.key
                card.layer.cornerRadius = radius
                card.layer.borderWidth = CGFloat(state.outlineWidth)
                card.layer.borderColor = (state.inUse ? p.accent : settings.theme == .highContrast ? p.text : .clear).cgColor
                card.check.isHidden = !state.inUse
                card.check.backgroundColor = p.accent; card.check.textColor = p.onAccent
                card.unsaved.isHidden = !choice.isDraft
                card.unsaved.backgroundColor = p.functionKey; card.unsaved.textColor = p.text
                card.accessibilityLabel = state.label
                card.accessibilityTraits = state.inUse ? [.button, .selected] : .button
                card.thumbnail.image = LayoutThumbnailCache.shared.image(for: choice, settings: settings,
                    dark: traitCollection.userInterfaceStyle == .dark, split: choice.split)
            }
        }
        strip.accessibilityElements = orderedKeys.compactMap { stripViews[$0] }
        var gridOrder: [Any] = orderedKeys.compactMap { gridViews[$0] }
        gridOrder.append(contentsOf: [newLayout, paste])
        all.accessibilityElements = gridOrder
        main.accessibilityElements = [editButton, newButton, strip, tileScroll]
        let draft = library.appliedKey == "draft"
        newButton.tintColor = draft ? p.smallAccent : p.text
        var config = newButton.configuration
        config?.title = draft ? "Save" : "New"
        config?.image = UIImage(systemName: draft ? "square.and.arrow.down" : "plus")
        newButton.configuration = config
        editButton.accessibilityLabel = "Edit \(library.choice(library.appliedKey)?.name ?? "layout")"
        newButton.accessibilityLabel = draft ? "Save Custom as \(library.nextName("Custom"))" : "New custom layout"
        let hand: String
        switch settings.quickOneHand(landscape) { case .center: hand = "Off"; case .left: hand = "Left"; case .right: hand = "Right" }
        let height = settings.int(Prefs.height(landscape))
        let split = settings.split(landscape), learning = settings.learning
        let values = [split ? "On" : "Off", hand, "\(height)%", learning ? "On" : "Paused",
                      settings.languages.count > 1 ? "Tap to switch" : "Only one", "Review text", "All"]
        for (index, tile) in tiles.enumerated() {
            tile.state.text = values[index]
            let on = index == 0 ? split : index == 1 ? hand != "Off" : index == 3 ? learning : false
            let ink = on ? p.smallAccent : p.text
            tile.icon.tintColor = ink; tile.title.textColor = ink; tile.glyph.textColor = ink
            tile.state.textColor = on ? p.smallAccent : p.hint
            tile.backgroundColor = p.key; tile.layer.cornerRadius = radius
            tile.layer.borderWidth = settings.theme == .highContrast ? 1 : 0; tile.layer.borderColor = p.text.cgColor
            tile.isEnabled = index != 4 || settings.languages.count > 1
            tile.alpha = tile.isEnabled ? 1 : 0.45
            tile.accessibilityLabel = tile.title.text
            tile.accessibilityValue = values[index]
        }
        tiles[4].glyph.text = settings.currentLanguage.uppercased()
        smaller.accessibilityLabel = "Size, decrease"; larger.accessibilityLabel = "Size, increase"
        for b in [smaller, larger] { b.accessibilityValue = "\(height) percent"; b.tintColor = p.text }
        smaller.isEnabled = height > Prefs.height(landscape).min; larger.isEnabled = height < Prefs.height(landscape).max
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width, h = bounds.height
        guard w > 0, h > 0 else { return }
        let tablet = w >= 600, short = h < 250
        if renderedTablet != tablet {
            renderedTablet = tablet
            for card in Array(stripViews.values) + Array(gridViews.values) {
                card.name.font = .systemFont(ofSize: tablet ? 14 : 12, weight: card.check.isHidden ? .medium : .bold)
            }
        }
        let headerH = max(tablet ? 56 : 48, stripHeight())
        back.frame = CGRect(x: 4, y: 0, width: 82, height: headerH)
        heading.frame = CGRect(x: 86, y: 0, width: max(0, w - 230), height: headerH)
        trailing.frame = CGRect(x: w - 134, y: 0, width: 130, height: headerH)
        let messageH: CGFloat = message.isHidden ? 0 : 34
        message.frame = CGRect(x: 12, y: headerH, width: w - 24, height: messageH)
        pages.frame = CGRect(x: 0, y: headerH + messageH, width: w, height: max(0, h - headerH - messageH))
        main.frame = pages.bounds; all.frame = pages.bounds; confirm.frame = bounds
        let rowH: CGFloat = tablet && !short ? 138 : 100
        let gap: CGFloat = tablet && !short ? 24 : 8
        let tileH: CGFloat = tablet ? 72 : 60
        let top = max(0, (pages.bounds.height - rowH - gap - tileH * 2 - 14) / 2)
        let actionW: CGFloat = tablet ? 96 : 58
        let actionH = (rowH - 4) / 2
        editButton.frame = CGRect(x: 8, y: top, width: actionW, height: actionH)
        newButton.frame = CGRect(x: 8, y: top + actionH + 4, width: actionW, height: actionH)
        strip.frame = CGRect(x: actionW + 16, y: top, width: max(0, w - actionW - 24), height: rowH)
        let cardW: CGFloat = tablet ? 188 : w < 340 ? 108 : 124
        let cardH: CGFloat = short ? 80 : rowH
        for (index, key) in orderedKeys.enumerated() { stripViews[key]?.frame = CGRect(x: CGFloat(index) * (cardW + 8), y: (rowH - cardH) / 2, width: cardW, height: cardH) }
        strip.contentSize = CGSize(width: CGFloat(orderedKeys.count) * (cardW + 8), height: rowH)
        let tileTop = top + rowH + gap
        tileScroll.frame = CGRect(x: 8, y: tileTop, width: w - 16, height: max(0, pages.bounds.height - tileTop))
        let narrow = w < 260
        let gridH = (tileH + 6) * (narrow ? 4 : 2) + 6
        tileGrid.frame = CGRect(x: 0, y: 0, width: w - 16, height: gridH)
        tileScroll.contentSize = CGSize(width: w - 16, height: max(gridH, tileScroll.bounds.height))
        let unit = (tileGrid.bounds.width - (narrow ? 6 : 18)) / (narrow ? 2 : 4)
        let positions: [(CGFloat, CGFloat, CGFloat)] = narrow
            ? [(0, 0, unit), (unit + 6, 0, unit), (0, tileH + 6, tileGrid.bounds.width),
               (0, (tileH + 6) * 2, unit), (unit + 6, (tileH + 6) * 2, unit), (0, (tileH + 6) * 3, unit), (unit + 6, (tileH + 6) * 3, unit)]
            : [(0, 0, unit), (unit + 6, 0, unit), ((unit + 6) * 2, 0, unit * 2 + 6),
               (0, tileH + 6, unit), (unit + 6, tileH + 6, unit), ((unit + 6) * 2, tileH + 6, unit), ((unit + 6) * 3, tileH + 6, unit)]
        for (index, tile) in tiles.enumerated() { let v = positions[index]; tile.frame = CGRect(x: v.0, y: v.1, width: v.2, height: tileH) }
        smaller.frame = CGRect(x: 0, y: 0, width: 48, height: tileH)
        larger.frame = CGRect(x: tiles[2].bounds.width - 48, y: 0, width: 48, height: tileH)
        settingsLinkController?.view.frame = tiles[6].bounds
        let columns = tablet ? 4 : w < 340 ? 2 : 3
        let cellW = (w - 16 - CGFloat(columns - 1) * 8) / CGFloat(columns)
        let cellH: CGFloat = tablet ? 162 : 112
        for (index, key) in (orderedKeys + ["new"]).enumerated() {
            let frame = CGRect(x: 8 + CGFloat(index % columns) * (cellW + 8), y: CGFloat(index / columns) * (cellH + 8), width: cellW, height: cellH)
            if key == "new" { newLayout.frame = frame } else { gridViews[key]?.frame = frame }
        }
        let rows = (orderedKeys.count + 1 + columns - 1) / columns
        paste.frame = CGRect(x: 8, y: CGFloat(rows) * (cellH + 8), width: w - 16, height: 48)
        all.contentSize = CGSize(width: w, height: paste.frame.maxY + 8)
        newLayoutOutline.frame = newLayout.bounds
        newLayoutOutline.path = UIBezierPath(roundedRect: newLayout.bounds.insetBy(dx: 1, dy: 1), cornerRadius: CGFloat(settings.int(Prefs.cornerRadius))).cgPath
        newLayoutOutline.strokeColor = Palette.of(settings.theme, dark: traitCollection.userInterfaceStyle == .dark).hint.cgColor
        let boxW = min(380, w - 32)
        let boxH: CGFloat = 180
        confirmBox.frame = CGRect(x: (w - boxW) / 2, y: max(0, (h - boxH) / 2), width: boxW, height: boxH)
        confirmTitle.frame = CGRect(x: 16, y: 12, width: boxW - 32, height: 40)
        confirmText.frame = CGRect(x: 16, y: 54, width: boxW - 32, height: 52)
        cancel.frame = CGRect(x: 16, y: 116, width: (boxW - 40) / 2, height: 48)
        saveContinue.frame = CGRect(x: boxW / 2 + 4, y: 116, width: (boxW - 40) / 2, height: 48)
        updateFade()
        if scrollToApplied, let card = stripViews[library.appliedKey] { strip.scrollRectToVisible(card.frame.insetBy(dx: -6, dy: 0), animated: false); scrollToApplied = false }
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) { if scrollView === strip { updateFade() } }
    private func updateFade() {
        fade.frame = strip.bounds
        fade.startPoint = CGPoint(x: 0, y: 0.5); fade.endPoint = CGPoint(x: 1, y: 0.5)
        let more = strip.contentOffset.x + strip.bounds.width < strip.contentSize.width - 1
        fade.colors = [UIColor.black.cgColor, UIColor.black.cgColor, (more ? UIColor.clear : UIColor.black).cgColor]
        fade.locations = [0, NSNumber(value: Double(max(0, 1 - 36 / max(1, strip.bounds.width)))), 1]
    }

    private func showAll(_ value: Bool, animated: Bool = true) {
        guard showingAll != value else { return }
        showingAll = value
        pageTransition += 1
        let transition = pageTransition
        heading.text = value ? "Layouts" : nil
        var config = trailing.configuration; config?.title = value ? "Edit" : "All layouts \u{203a}"; trailing.configuration = config
        back.accessibilityLabel = value ? "Back to Quick options" : "Back to keyboard"
        let incoming: UIView = value ? all : main, outgoing: UIView = value ? main : all
        incoming.layer.removeAllAnimations(); outgoing.layer.removeAllAnimations()
        incoming.isHidden = false
        incoming.alpha = animated ? 0 : 1
        incoming.transform = animated && !UIAccessibility.isReduceMotionEnabled ? CGAffineTransform(translationX: value ? 16 : -16, y: 0) : .identity
        let changes = {
            incoming.alpha = 1; incoming.transform = .identity; outgoing.alpha = 0
            outgoing.transform = animated && !UIAccessibility.isReduceMotionEnabled ? CGAffineTransform(translationX: value ? -16 : 16, y: 0) : .identity
        }
        if animated {
            UIView.animate(withDuration: UIAccessibility.isReduceMotionEnabled ? 0.1 : value ? 0.2 : 0.16, delay: 0, options: [.curveEaseOut, .beginFromCurrentState], animations: changes) { [weak self] _ in
                guard self?.pageTransition == transition else { return }
                outgoing.isHidden = true; outgoing.alpha = 1; outgoing.transform = .identity
            }
            UIAccessibility.post(notification: .screenChanged, argument: back)
        } else { changes(); outgoing.isHidden = true; outgoing.alpha = 1; outgoing.transform = .identity }
        main.accessibilityElementsHidden = value; all.accessibilityElementsHidden = !value
    }

    func animateOpen() {
        layer.removeAllAnimations(); alpha = 0
        transform = UIAccessibility.isReduceMotionEnabled ? .identity : CGAffineTransform(translationX: 0, y: 8)
        UIView.animate(withDuration: UIAccessibility.isReduceMotionEnabled ? 0.1 : 0.16, delay: UIAccessibility.isReduceMotionEnabled ? 0 : 0.04, options: [.curveEaseOut, .beginFromCurrentState]) { self.alpha = 1; self.transform = .identity }
    }

    private func edit(newCopy: Bool) {
        guard let source = library.choice(library.appliedKey) else { return }
        if (newCopy || !source.isCustom) && library.needsSaveFirst {
            pendingCopy = (source.id, newCopy)
            confirm.isHidden = false; main.accessibilityElementsHidden = true; all.accessibilityElementsHidden = true
            back.accessibilityElementsHidden = true; trailing.accessibilityElementsHidden = true
            UIAccessibility.post(notification: .screenChanged, argument: confirmTitle)
        } else { openEditor(source.id, newCopy: newCopy) }
    }
    private func hideConfirm() {
        confirm.isHidden = true; pendingCopy = nil
        back.accessibilityElementsHidden = false; trailing.accessibilityElementsHidden = false
        main.accessibilityElementsHidden = showingAll; all.accessibilityElementsHidden = !showingAll
    }
    private func continueEdit() {
        guard let (source, copy) = pendingCopy else { return }
        // If New was requested from the draft, its saved identity is now the source of the next copy.
        guard let saved = library.saveDraft(name: library.nextName("Custom")) else { return }
        hideConfirm()
        openEditor(source == "draft" ? saved : source, newCopy: copy)
    }
    private func openEditor(_ key: String, newCopy: Bool) {
        guard let source = library.choice(key), let editKey = library.quickEdit(key, newCopy: newCopy, landscape: landscape) else { return }
        let title = !source.isCustom ? "\(source.name) is a preset; editing a copy named Custom"
            : newCopy ? "New layout, copied from \(source.name)" : "Editing \(source.name)"
        onEdit?(editKey, title)
    }
    private func pasteCode() {
        // Read only after the explicit Paste code tap; iOS may present its system paste prompt.
        let result = library.importClipboard(UIPasteboard.general.string)
        switch result {
        case .empty: message.text = "No layout code on the clipboard"
        case .invalid: message.text = "This code isn't a TailorKeys layout"
        case .added(let key): message.text = "Added \(library.choice(key)?.name ?? "layout")"
        }
        message.isHidden = false
        UIAccessibility.post(notification: .announcement, argument: message.text)
        refresh(); setNeedsLayout()
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        refresh()
    }
    override func accessibilityPerformEscape() -> Bool {
        if !confirm.isHidden { hideConfirm() } else if showingAll { showAll(false) } else { onClose?() }
        return true
    }
}

/// The native tile supplies its artwork; Link supplies the system URL-opening action and accessibility.
struct QuickSettingsLink: View {
    var body: some View {
        Link(destination: URL(string: "tailorkeys://settings")!) {
            Rectangle().fill(Color.clear).contentShape(Rectangle())
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Settings, All")
    }
}
