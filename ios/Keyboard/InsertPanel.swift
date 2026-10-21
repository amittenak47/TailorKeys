import UIKit
import CoreText
import SwiftUI

private enum InsertAssets {
    static let emoji = EmojiCatalog(read("emoji"))
    static let renderable = emoji.primary.filter { hasGlyph($0.text) }
    private static let glyphLock = NSRecursiveLock()
    private static var glyphCache: [String: Bool] = [:]
    static func hasGlyph(_ text: String) -> Bool {
        glyphLock.lock(); defer { glyphLock.unlock() }
        if let cached = glyphCache[text] { return cached }
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: [.font: UIFont.systemFont(ofSize: 28)]) as CFAttributedString)
        let runs = CTLineGetGlyphRuns(line) as! [CTRun]
        let supported = !runs.isEmpty && CTLineGetTypographicBounds(line, nil, nil, nil) <= 45 && runs.allSatisfy { run in
            var glyphs = [CGGlyph](repeating: 0, count: CTRunGetGlyphCount(run)); CTRunGetGlyphs(run, CFRange(location: 0, length: 0), &glyphs); return glyphs.allSatisfy { $0 != 0 }
        }
        glyphCache[text] = supported; return supported
    }
    static let kaomoji: [InsertSymbol] = read("kaomoji").components(separatedBy: .newlines).compactMap { line in
        let f = line.components(separatedBy: "\t"); return f.count >= 3 ? InsertSymbol(f[0], f[1], f[2]) : nil
    }
    private static func read(_ name: String) -> String {
        guard let url = Bundle.main.url(forResource: name, withExtension: "tsv") else { return "" }
        return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
    }
}

/// Reuses one collection view and stays attached for the lifetime of the input view.
final class InsertPanel: UIView, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    let settingsLinkController = UIHostingController(rootView: InsertSettingsLink())
    var onClose: (() -> Void)?
    var onInsert: ((String) -> Void)?
    var onBackspace: (() -> Void)?
    var onTyping: ((Bool, CGFloat) -> Void)?
    var fullAccess: () -> Bool = { false }
    var password: () -> Bool = { true }
    private let settings: KeyboardSettings
    private let inputState: InputState
    private var savedKeys: (Layer, Bool, Bool)?
    private let repository: InsertRepository
    private let main = UIStackView()
    private let flow = InsertFlowLayout()
    private lazy var collection = UICollectionView(frame: .zero, collectionViewLayout: flow)
    private var emojiReady = false
    private var laidOutWidth: CGFloat = 0
    private var bottom: UIStackView?
    private var bottomContext: UIView?
    private var dim: UIControl?
    private var heldOutline: UIView?
    private var preferenceObserver: NSObjectProtocol?
    private lazy var emojiByText = Dictionary(InsertAssets.emoji.entries.map { ($0.text, $0) }, uniquingKeysWith: { a, _ in a })
    private lazy var kaomojiByText = Dictionary(InsertAssets.kaomoji.map { ($0.text, $0) }, uniquingKeysWith: { a, _ in a })
    private var builtStyle = ""
    private var renderedEditor = false
    private var symbols: [InsertSymbol] = []
    private var section: String
    private var category = "Recent"
    private var notes = false
    private var mode: String?
    private var query = ""
    private var emojiScrollOffset = CGPoint.zero
    private var editItem: ClipItem?
    private var draftTitle = "", draftText = ""
    private var noteDrafts: [String: (String, String)] = [:]
    private var caret = 0
    private var titleFocused = false
    private var searchLabel: UIButton?
    private var titleLabel: UIButton?, textLabel: UIButton?
    private var resultScroll: UIScrollView?
    private var kaomojiHeader: UILabel?
    private var categoryButtons: [String: UIButton] = [:]
    private var tonePopover: UIStackView?
    private var toneRow: UIStackView?
    private var toneEntries: [InsertSymbol] = []
    private var toneChoice = -1
    private var toneEntered = false
    private var toneDefault: InsertSymbol?
    private var expiry: Timer?
    private var footer: UIStackView?
    private var palette: Palette { Palette.of(settings, dark: traitCollection.userInterfaceStyle == .dark) }
    private var tablet: Bool { UIDevice.current.userInterfaceIdiom == .pad && (bounds.width == 0 || bounds.width >= 600) }
    var acceptsText: Bool { mode != nil && !isHidden }
    var session = 0
    init(settings: KeyboardSettings, state: InputState) {
        self.settings = settings; self.inputState = state
        repository = InsertRepository(defaults: { settings.insertDefaults })
        let saved = settings.insertDefaults.string(forKey: InsertPrefs.section) ?? "Emoji"
        section = ["Emoji", "Kaomoji", "Clipboard", "Dictate"].contains(saved) ? saved : "Emoji"
        super.init(frame: .zero)
        main.axis = .vertical; main.spacing = 4; addSubview(main)
        flow.minimumInteritemSpacing = 0; flow.minimumLineSpacing = 0
        collection.backgroundColor = .clear; collection.dataSource = self; collection.delegate = self
        collection.register(SymbolCell.self, forCellWithReuseIdentifier: "symbol")
        collection.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(holdEmoji(_:))))
        isHidden = true; accessibilityElementsHidden = true
        DispatchQueue.global(qos: .utility).async { [weak self] in
            _ = InsertAssets.renderable
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }; self.emojiReady = true
                if !self.isHidden && self.section == "Emoji" { if self.mode == nil { self.render() } else { self.renderEditor() } }
            }
        }
        preferenceObserver = NotificationCenter.default.addObserver(forName: UserDefaults.didChangeNotification, object: nil, queue: .main) { [weak self] _ in self?.refresh() }
    }
    deinit { if let observer = preferenceObserver { NotificationCenter.default.removeObserver(observer) } }
    required init?(coder: NSCoder) { fatalError("init(coder:)") }
    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.userInterfaceStyle != traitCollection.userInterfaceStyle { refresh() }
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        let h: CGFloat = mode == "Note" || mode == "Search" ? 94 : bounds.height - (tablet ? 14 : 8)
        main.spacing = tablet ? 6 : 4
        main.frame = CGRect(x: tablet ? 12 : 4, y: mode == nil ? (tablet ? 10 : 6) : 0, width: max(0, bounds.width - (tablet ? 24 : 8)), height: h)
        if laidOutWidth != main.bounds.width {
            laidOutWidth = main.bounds.width
            if section == "Emoji" && mode == nil { symbols = emojiCells(); collection.reloadData() }
            flow.invalidateLayout()
        }
    }
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        if mode != nil && !main.frame.contains(point) { return nil }
        return super.hitTest(point, with: event)
    }
    func show() {
        retainDraft(); restoreKeys()
        if mode != nil { onTyping?(false, 0) }; mode = nil; session += 1
        isHidden = false; accessibilityElementsHidden = false
        if section == "Emoji" || section == "Kaomoji" { category = "Recent"; render(); collection.setContentOffset(.zero, animated: false) }
        else if renderedEditor || builtStyle != signature { render() }
        expiry?.invalidate(); expiry = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            guard let self = self else { return }; self.repository.change { _ in }; if self.section == "Clipboard" && self.mode == nil { self.render() }
        }
    }
    func hide() { retainDraft(); restoreKeys(); expiry?.invalidate(); expiry = nil; mode = nil; session += 1; onTyping?(false, 0); isHidden = true; accessibilityElementsHidden = true; dismissOverlay() }
    func refresh() { if !isHidden { if section == "Kaomoji" && !repository.prefs.boolDefault(InsertPrefs.kaomoji, true) { restoreKeys(); mode = nil; onTyping?(false, 0); render(); return }; if mode == nil { if builtStyle != signature { render() } } else { renderEditor() } } }
    private var signature: String {
        "\(settings.themeID):\(CustomTheme.encode(settings.customThemes)):\(traitCollection.userInterfaceStyle.rawValue):\(settings.int(Prefs.cornerRadius)):\(repository.prefs.integer(forKey: InsertPrefs.tone)):\(repository.prefs.bool(forKey: InsertPrefs.history)):\(repository.keep.rawValue):\(repository.prefs.boolDefault(InsertPrefs.recents, true)):\(repository.recent("Emoji")):\(repository.recent("Kaomoji")):\(repository.prefs.data(forKey: "insert_clips")?.hashValue ?? 0):\(fullAccess()):\(repository.prefs.boolDefault(InsertPrefs.kaomoji, true))"
    }
    private func reset() { kaomojiHeader = nil; bottom = nil; bottomContext = nil; main.arrangedSubviews.forEach { main.removeArrangedSubview($0); $0.removeFromSuperview() }; dismissOverlay(); tonePopover = nil; categoryButtons = [:]; setNeedsLayout() }
    private func stack(_ axis: NSLayoutConstraint.Axis = .horizontal) -> UIStackView { let s = UIStackView(); s.axis = axis; s.spacing = 8; return s }
    private func button(_ title: String, label: String? = nil, selected: Bool = false, _ action: @escaping () -> Void) -> UIButton {
        let b = UIButton(type: .system); b.setTitle(title, for: .normal); b.accessibilityLabel = label ?? title
        b.titleLabel?.font = .systemFont(ofSize: 14, weight: selected ? .semibold : .regular)
        b.setTitleColor(palette.text, for: .normal)
        b.tintColor = palette.text
        b.backgroundColor = selected ? palette.key : .clear
        b.layer.cornerRadius = 12; b.layer.borderWidth = selected ? 1 : 0
        b.layer.borderColor = palette.keyOutline.cgColor
        if selected { b.accessibilityTraits.insert(.selected) }
        b.contentEdgeInsets = UIEdgeInsets(top: 6, left: 10, bottom: 6, right: 10)
        b.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        b.widthAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        b.addAction(UIAction { _ in action() }, for: .touchUpInside); return b
    }
    private func label(_ text: String, size: CGFloat = 14) -> UILabel {
        let v = UILabel(); v.text = text; v.numberOfLines = 0; v.font = .systemFont(ofSize: size); v.textColor = palette.text; return v
    }
    private func scroll(_ content: UIView, horizontal: Bool = false) -> UIScrollView {
        let s: UIScrollView = horizontal && !tablet ? FadingInsertScrollView() : UIScrollView(); s.addSubview(content); content.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([content.leadingAnchor.constraint(equalTo: s.contentLayoutGuide.leadingAnchor), content.trailingAnchor.constraint(equalTo: s.contentLayoutGuide.trailingAnchor), content.topAnchor.constraint(equalTo: s.contentLayoutGuide.topAnchor), content.bottomAnchor.constraint(equalTo: s.contentLayoutGuide.bottomAnchor), horizontal ? content.heightAnchor.constraint(equalTo: s.frameLayoutGuide.heightAnchor) : content.widthAnchor.constraint(equalTo: s.frameLayoutGuide.widthAnchor)])
        s.showsHorizontalScrollIndicator = false; return s
    }
    private var fill: UIColor { mix(palette.background, palette.text, 0.07) }
    private var fillHi: UIColor { mix(palette.background, palette.text, 0.12) }
    private var hair: UIColor { mix(palette.key, palette.text, 0.12) }
    private var sub: UIColor { readable(palette.hint, on: fill) }
    private func mix(_ a: UIColor, _ b: UIColor, _ amount: CGFloat) -> UIColor {
        var ar: CGFloat = 0, ag: CGFloat = 0, ab: CGFloat = 0, aa: CGFloat = 0
        var br: CGFloat = 0, bg: CGFloat = 0, bb: CGFloat = 0, ba: CGFloat = 0
        a.getRed(&ar, green: &ag, blue: &ab, alpha: &aa); b.getRed(&br, green: &bg, blue: &bb, alpha: &ba)
        return UIColor(red: ar+(br-ar)*amount, green: ag+(bg-ag)*amount, blue: ab+(bb-ab)*amount, alpha: 1)
    }
    private func readable(_ color: UIColor, on surface: UIColor, toward: UIColor? = nil) -> UIColor {
        let target = toward ?? palette.text
        for step in 0...100 { let value = mix(color, target, CGFloat(step)/100); if ThemeColors.contrast(ThemeColors.hex(value), ThemeColors.hex(surface)) >= 4.5 { return value } }; return target
    }
    private func render() {
        if section == "Kaomoji" && !repository.prefs.boolDefault(InsertPrefs.kaomoji, true) { section = "Emoji"; category = "Recent"; mode = nil; onTyping?(false, 0) }
        renderedEditor = false; reset(); backgroundColor = palette.background; main.backgroundColor = .clear
        let top = stack(); top.heightAnchor.constraint(equalToConstant: 44).isActive = true
        let searchName = section == "Kaomoji" ? "Search kaomoji" : section == "Clipboard" ? "Search clips and notes" : "Search emoji"
        let searchButton = button("⌕  " + searchName, label: searchName) { [weak self] in if self?.section == "Dictate" { self?.section = "Emoji" }; self?.search() }
        searchButton.backgroundColor = fill; searchButton.layer.borderWidth = 1; searchButton.layer.borderColor = palette.keyOutline.cgColor; searchButton.setTitleColor(sub, for: .normal); searchButton.contentHorizontalAlignment = .leading; searchButton.titleLabel?.lineBreakMode = .byTruncatingTail; searchButton.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        top.addArrangedSubview(searchButton)
        let sections = stack(); sections.spacing = 0; sections.backgroundColor = fill; sections.layer.cornerRadius = 12; sections.layer.borderWidth = 1; sections.layer.borderColor = palette.keyOutline.cgColor
        for (i, name) in ["Emoji", "Kaomoji", "Clipboard", "Dictate"].enumerated() {
            if name == "Kaomoji" && !repository.prefs.boolDefault(InsertPrefs.kaomoji, true) { continue }
            let b = button(i == 1 ? "^_^" : "", label: name, selected: section == name) { [weak self] in
                guard let self = self else { return }; self.section = name; self.category = "Recent"; self.repository.prefs.set(name, forKey: InsertPrefs.section); self.render()
            }
            if i != 1 { b.setImage(UIImage(systemName: ["face.smiling", "", "doc.on.clipboard", "mic"][i]), for: .normal) }
            b.widthAnchor.constraint(equalToConstant: tablet ? 56 : 44).isActive = true
            b.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(showTooltip(_:))))
            styleTab(b, selected: section == name); sections.addArrangedSubview(b)
        }
        top.addArrangedSubview(sections); main.addArrangedSubview(top)
        if section == "Emoji" || section == "Kaomoji" { renderSymbols() }
        else if section == "Clipboard" { renderClipboard() }
        else { renderDictate() }
        if let bottom = bottom { main.removeArrangedSubview(bottom); bottom.removeFromSuperview(); main.addArrangedSubview(bottom) } else { makeBottom(UIView()) }
        builtStyle = signature
    }
    private func makeBottom(_ context: UIView) {
        bottom?.removeFromSuperview()
        let bar = stack(); bar.spacing = 0; bar.heightAnchor.constraint(equalToConstant: 44).isActive = true
        let abc = button("ABC", label: "Back to keys") { [weak self] in self?.onClose?() }; abc.widthAnchor.constraint(equalToConstant: tablet ? 64 : 52).isActive = true
        let back = button("", label: "Backspace") { [weak self] in self?.onBackspace?() }; back.setImage(UIImage(systemName: "delete.left"), for: .normal); back.widthAnchor.constraint(equalToConstant: tablet ? 64 : 52).isActive = true
        bar.addArrangedSubview(abc); bar.addArrangedSubview(context); bar.addArrangedSubview(back); bottomContext = context; bottom = bar; footer = bar; main.addArrangedSubview(bar)
    }
    @objc private func showTooltip(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began, let button = gesture.view as? UIButton else { return }
        let tooltip = label(button.accessibilityLabel ?? "", size: 12); tooltip.backgroundColor = palette.key; tooltip.layer.cornerRadius = 12; tooltip.clipsToBounds = true; tooltip.textAlignment = .center
        let frame = button.convert(button.bounds, to: self); tooltip.frame = CGRect(x: min(max(4, frame.midX-48), bounds.width-100), y: frame.maxY+4, width: 96, height: 44); addSubview(tooltip)
        DispatchQueue.main.asyncAfter(deadline: .now()+1.5) { tooltip.removeFromSuperview() }
    }
    private func styleTab(_ b: UIButton, selected: Bool, category: Bool = false) {
        b.backgroundColor = .clear; b.layer.borderWidth = 0; b.setTitleColor(selected ? palette.text : sub, for: .normal); b.tintColor = selected ? palette.text : sub
        b.titleLabel?.font = .systemFont(ofSize: 14, weight: selected ? .semibold : .regular)
        b.viewWithTag(500)?.removeFromSuperview()
        if selected {
            let thumb = UIView(); thumb.tag = 500; thumb.isUserInteractionEnabled = false; thumb.backgroundColor = category ? fillHi : palette.key; thumb.layer.cornerRadius = category ? 17 : 9
            thumb.layer.borderWidth = 1; thumb.layer.borderColor = palette.keyOutline.cgColor
            if !category { thumb.layer.shadowOpacity = 0.12; thumb.layer.shadowRadius = 1; thumb.layer.shadowOffset = CGSize(width: 0, height: 1) }
            thumb.translatesAutoresizingMaskIntoConstraints = false; b.insertSubview(thumb, at: 0)
            let inset: CGFloat = category && section == "Emoji" ? (tablet ? 7 : 5) : 2
            NSLayoutConstraint.activate([thumb.leadingAnchor.constraint(equalTo: b.leadingAnchor, constant: inset), thumb.trailingAnchor.constraint(equalTo: b.trailingAnchor, constant: -inset), thumb.centerYAnchor.constraint(equalTo: b.centerYAnchor), thumb.heightAnchor.constraint(equalToConstant: category ? 34 : 38)])
        }
        b.accessibilityTraits = selected ? [.button, .selected] : [.button]
    }
    private func dismissOverlay() { heldOutline?.removeFromSuperview(); heldOutline = nil; tonePopover?.removeFromSuperview(); tonePopover = nil; dim?.removeFromSuperview(); dim = nil }
    private func showDim() {
        dismissOverlay(); let overlay = UIControl(frame: bounds); overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]; overlay.backgroundColor = UIColor.black.withAlphaComponent(ThemeColors.contrast(ThemeColors.hex(palette.background), "#000000") < 4.5 ? 0.4 : 0.18)
        overlay.addAction(UIAction { [weak self] _ in self?.dismissOverlay() }, for: .touchUpInside); addSubview(overlay); dim = overlay
    }
    private func recents() -> [InsertSymbol] {
        let byText = section == "Emoji" ? (emojiReady ? emojiByText : [:]) : kaomojiByText
        return repository.recent(section).compactMap { byText[$0] ?? (section == "Emoji" && !emojiReady ? InsertSymbol($0, "Recent emoji", "Recent") : nil) }.filter { section != "Emoji" || !emojiReady || InsertAssets.hasGlyph($0.text) }
    }
    private func renderSymbols() {
        let all = section == "Emoji" ? (emojiReady ? InsertAssets.renderable : []) : InsertAssets.kaomoji
        let tabs = stack(); tabs.spacing = 0
        var cats = ["Recent"]; if section == "Emoji" { cats += ["Smileys & Emotion", "People & Body", "Animals & Nature", "Food & Drink", "Travel & Places", "Activities", "Objects", "Symbols", "Flags"] } else { for entry in all where !cats.contains(entry.category) { cats.append(entry.category) } }
        for (i, cat) in cats.enumerated() {
            let icons = ["◷", "☺", "♙", "♧", "♨", "▱", "⚽", "♧", "♡", "⚑"]
            let b = button(section == "Emoji" ? icons[min(i, icons.count - 1)] : cat, label: cat, selected: cat == category) { [weak self] in
                guard let self = self else { return }; self.category = cat
                if self.section == "Emoji" {
                    let index = cat == "Recent" ? 0 : self.symbols.firstIndex(where: { $0.category == cat }) ?? 0
                    if self.symbols.indices.contains(index) { self.collection.scrollToItem(at: IndexPath(item: index, section: 0), at: .top, animated: !UIAccessibility.isReduceMotionEnabled) }; self.markCategories()
                } else { self.symbols = cat == "Recent" ? self.kaomojiWithRecents() : all.filter { $0.category == cat }; self.collection.reloadData(); self.updateKaomojiEmpty(); self.kaomojiHeader?.text = cat.uppercased(); self.markCategories() }
            }
            if section == "Emoji" {
                let names = ["clock", "face.smiling", "person", "pawprint", "cup.and.saucer", "car", "sportscourt", "lightbulb", "heart", "flag"]
                b.setTitle("", for: .normal); b.setImage(UIImage(systemName: names[min(i, names.count - 1)]), for: .normal)
            }
            categoryButtons[cat] = b; styleTab(b, selected: cat == category, category: true); if section == "Emoji" { b.widthAnchor.constraint(equalToConstant: tablet ? 48 : 44).isActive = true }; tabs.addArrangedSubview(b)
        }
        let tabScroll = scroll(tabs, horizontal: true)
        symbols = section == "Emoji" ? emojiCells() : category == "Recent" ? kaomojiWithRecents() : all.filter { $0.category == category }
        flow.wrapping = section == "Kaomoji"; flow.minimumLineSpacing = section == "Kaomoji" ? 8 : 0; flow.minimumInteritemSpacing = section == "Kaomoji" ? 8 : 0
        if section == "Kaomoji" { let header = label(category.uppercased(), size: 12); header.font = .systemFont(ofSize: 12, weight: .semibold); header.textColor = sub; header.heightAnchor.constraint(equalToConstant: 26).isActive = true; kaomojiHeader = header; main.addArrangedSubview(header); main.setCustomSpacing(0, after: header) }
        collection.removeFromSuperview(); main.addArrangedSubview(collection); collection.reloadData(); updateKaomojiEmpty(); makeBottom(tabScroll)
    }
    private func emojiCells() -> [InsertSymbol] {
        let cols = emojiColumns(width: Double(max(44, main.bounds.width)), tablet: tablet); var result: [InsertSymbol] = []
        func group(_ category: String, _ entries: [InsertSymbol]) {
            guard !entries.isEmpty else { return }
            while result.count % cols != 0 { result.append(InsertSymbol("", "", result.last!.category)) }
            let title = ["Recent": "Frequently used", "Smileys & Emotion": "Smileys & people", "People & Body": "People", "Animals & Nature": "Animals", "Food & Drink": "Food", "Travel & Places": "Travel"][category] ?? category
            for i in 0..<cols { result.append(InsertSymbol("", i == 0 ? title : "", category)) }
            result += entries.map { InsertSymbol($0.text, $0.name, category, $0.keywords) }
        }
        group("Recent", recents()); var cats: [String] = []
        for entry in (emojiReady ? InsertAssets.renderable : []) where !cats.contains(entry.category) { cats.append(entry.category) }
        for cat in cats { group(cat, (emojiReady ? InsertAssets.renderable : []).filter { $0.category == cat }) }; return result
    }
    private func markCategories() {
        for (cat, b) in categoryButtons {
            styleTab(b, selected: cat == category, category: true)
            if cat == category, let scroll = b.superview?.superview as? UIScrollView { scroll.scrollRectToVisible(b.convert(b.bounds, to: scroll), animated: false) }
        }
    }
    private func shown(_ entry: InsertSymbol) -> InsertSymbol {
        guard emojiReady else { return entry }
        let toned = InsertAssets.emoji.toned(entry, repository.prefs.integer(forKey: InsertPrefs.tone))
        return InsertAssets.hasGlyph(toned.text) ? toned : entry
    }
    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { symbols.count }
    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "symbol", for: indexPath) as! SymbolCell
        let entry = section == "Emoji" ? shown(symbols[indexPath.item]) : symbols[indexPath.item]
        cell.clipsToBounds = false; cell.contentView.clipsToBounds = false; cell.text.text = entry.text.isEmpty ? entry.name.uppercased() : entry.text; cell.header = entry.text.isEmpty; cell.text.font = .systemFont(ofSize: entry.text.isEmpty ? 12 : section == "Emoji" ? (tablet ? 32 : 28) : (tablet ? 17 : 16)); cell.text.textColor = entry.text.isEmpty ? sub : palette.text
        if entry.text.isEmpty { cell.text.attributedText = NSAttributedString(string: entry.name.uppercased(), attributes: [.kern: 0.4, .font: UIFont.systemFont(ofSize: 12, weight: .semibold), .foregroundColor: sub]) }
        cell.backgroundColor = section == "Emoji" ? .clear : palette.key; cell.layer.cornerRadius = 12
        cell.layer.borderWidth = section == "Emoji" || entry.text.isEmpty ? 0 : 1; cell.layer.borderColor = palette.keyOutline.cgColor
        cell.isAccessibilityElement = !entry.text.isEmpty || !entry.name.isEmpty; cell.accessibilityLabel = entry.name; cell.accessibilityTraits = entry.text.isEmpty ? .header : .button
        cell.accessibilityCustomActions = entry.text.isEmpty || section != "Emoji" || !emojiReady ? nil : InsertAssets.emoji.variants(entry.text).filter { InsertAssets.hasGlyph($0.text) }.map { e in UIAccessibilityCustomAction(name: e.name) { [weak self] _ in self?.use(e); return true } }
        if section == "Kaomoji" {
            cell.accessibilityCustomActions = kaomojiActions(entry).map { title in UIAccessibilityCustomAction(name: title) { [weak self] _ in self?.performKaomoji(title, entry); return true } }
        }
        return cell
    }
    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        let entry = section == "Emoji" ? shown(symbols[indexPath.item]) : symbols[indexPath.item]; if !entry.text.isEmpty { use(entry) }
    }
    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        if section == "Emoji" { let cell: CGFloat = tablet ? 52 : 44; let cols = emojiColumns(width: Double(collectionView.bounds.width), tablet: tablet); let space = max(0, collectionView.bounds.width-CGFloat(cols)*cell)/CGFloat(cols); return CGSize(width: cell+space, height: symbols[indexPath.item].text.isEmpty ? 26 : tablet ? 52 : 46) }
        let w = (symbols[indexPath.item].text as NSString).size(withAttributes: [.font: UIFont.systemFont(ofSize: tablet ? 17 : 16)]).width + 28
        return CGSize(width: min(collectionView.bounds.width, max(64, ceil(w))), height: 44)
    }
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        if scrollView === collection, section == "Emoji", let index = collection.indexPathsForVisibleItems.min(by: { $0.item < $1.item }), symbols.indices.contains(index.item), category != symbols[index.item].category {
            category = symbols[index.item].category; markCategories()
        }
    }
    /// Pinned faces first, then recents. The Recent tab shows only these, like every other category.
    private func kaomojiWithRecents() -> [InsertSymbol] {
        var seen = Set<String>(); return (repository.pinned("Kaomoji").compactMap { kaomojiByText[$0] } + recents()).filter { seen.insert($0.text).inserted }
    }
    /// An empty Recent tab explains itself instead of showing a blank page.
    private func updateKaomojiEmpty() {
        guard section == "Kaomoji", symbols.isEmpty else { collection.backgroundView = nil; return }
        let hint = label("Faces you use or pin show up here.", size: 14); hint.textColor = sub; hint.textAlignment = .center; collection.backgroundView = hint
    }
    private func use(_ entry: InsertSymbol) {
        let which = section
        repository.insertSymbol(which, entry) { [weak self] in self?.onInsert?($0) }
        guard mode == nil, section == which else { return }
        // Refresh recents in place only while they are on screen; elsewhere the grid stays where the
        // user is picking, and recents are rebuilt the next time the panel opens.
        if which == "Emoji", let first = collection.indexPathsForVisibleItems.min(by: { $0.item < $1.item }), symbols.indices.contains(first.item), symbols[first.item].category == "Recent" {
            symbols = emojiCells(); collection.reloadData(); collection.setContentOffset(.zero, animated: false)
        } else if which == "Kaomoji", category == "Recent" {
            symbols = kaomojiWithRecents(); collection.reloadData(); updateKaomojiEmpty()
        }
    }
    @objc private func holdEmoji(_ gesture: UILongPressGestureRecognizer) {
        guard mode == nil else { return }
        if section == "Kaomoji" {
            if gesture.state == .began, let index = collection.indexPathForItem(at: gesture.location(in: collection)), let cell = collection.cellForItem(at: index) {
                let entry = symbols[index.item]; showMenu(cell, titles: kaomojiActions(entry)) { [weak self] in self?.performKaomoji($0, entry) }
            }; return
        }
        guard section == "Emoji", emojiReady else { return }
        if gesture.state == .began {
            guard let index = collection.indexPathForItem(at: gesture.location(in: collection)) else { return }
            toneEntries = InsertAssets.emoji.variants(symbols[index.item].text).filter { InsertAssets.hasGlyph($0.text) }
            guard toneEntries.count > 1 else { return }
            showDim(); let pop = stack(.vertical); pop.spacing = 0
            let row = stack(); row.spacing = 0
            let tone = min(max(0, repository.prefs.integer(forKey: InsertPrefs.tone)), 5)
            let defaultText = InsertAssets.emoji.toned(symbols[index.item], tone).text
            for entry in toneEntries {
                let index = InsertAssets.emoji.variants(symbols[index.item].text).firstIndex(of: entry) ?? 0
                let b = button(entry.text, label: entry.name + ", " + InsertPrefs.toneNames[index] + (entry.text == defaultText ? ", default" : "")) { [weak self] in self?.use(entry); self?.dismissOverlay() }
                b.widthAnchor.constraint(equalToConstant: 52).isActive = true; b.heightAnchor.constraint(equalToConstant: 52).isActive = true
                b.titleLabel?.font = .systemFont(ofSize: 32); b.contentEdgeInsets = .zero
                if entry.text == defaultText { b.backgroundColor = fillHi; b.layer.borderWidth = 2; b.layer.borderColor = palette.accent.cgColor }; row.addArrangedSubview(b)
            }
            pop.addArrangedSubview(row)
            let caption = label("✓  " + InsertPrefs.toneNames[tone] + " is your default · change it in Settings", size: 12); caption.textColor = sub; caption.textAlignment = .center; pop.addArrangedSubview(caption)
            pop.backgroundColor = palette.key; pop.layer.cornerRadius = 16; pop.layer.borderWidth = 1; pop.layer.borderColor = palette.keyOutline.cgColor; pop.layer.shadowOpacity = 0.2; pop.layer.shadowRadius = 8
            let point = gesture.location(in: self), width = CGFloat(toneEntries.count) * 52
            pop.frame = CGRect(x: min(max(8, point.x-width/2), max(8, bounds.width-width-8)), y: max(0, point.y-90), width: width, height: 84)
            addSubview(pop); tonePopover = pop; toneRow = row; toneChoice = -1; toneEntered = false
            toneDefault = toneEntries.first { $0.text == defaultText } ?? toneEntries.first
        } else if gesture.state == .changed, let row = toneRow {
            let point = gesture.location(in: row); toneChoice = row.bounds.contains(point) ? min(toneEntries.count-1, max(0, Int(point.x/52))) : -1
            if toneChoice >= 0 { toneEntered = true }
            for (i, view) in row.arrangedSubviews.enumerated() { view.backgroundColor = i == toneChoice || view.layer.borderWidth == 2 ? fillHi : .clear }
        } else if gesture.state == .ended {
            // Lifting before reaching the popover inserts the emoji as shown; lifting after leaving it cancels.
            if toneEntries.indices.contains(toneChoice) { use(toneEntries[toneChoice]) } else if !toneEntered, let entry = toneDefault { use(entry) }
            dismissOverlay()
        }
        else if gesture.state == .cancelled { dismissOverlay() }
    }
    private func kaomojiActions(_ entry: InsertSymbol) -> [String] { [repository.pinned("Kaomoji").contains(entry.text) ? "Unpin from Recent" : "Pin to Recent", "Copy"] }
    private func performKaomoji(_ action: String, _ entry: InsertSymbol) {
        dismissOverlay()
        if action == "Copy" { UIPasteboard.general.string = entry.text }
        else { repository.togglePin("Kaomoji", entry.text); category = "Recent"; render() }
    }
    private var danger: UIColor { readable(.systemRed, on: palette.key) }
    private func showMenu(_ anchor: UIView, titles: [String], perform: @escaping (String) -> Void) {
        showDim()
        let rect = anchor.convert(anchor.bounds, to: self)
        let outline = UIView(frame: rect.insetBy(dx: 1, dy: 1)); outline.isUserInteractionEnabled = false; outline.layer.cornerRadius = 12; outline.layer.borderWidth = 2; outline.layer.borderColor = palette.accent.cgColor; addSubview(outline); heldOutline = outline
        let menu = stack(.vertical); menu.spacing = 0; menu.backgroundColor = palette.key; menu.layer.cornerRadius = 14; menu.layer.borderWidth = 1; menu.layer.borderColor = palette.keyOutline.cgColor
        for (i, title) in titles.enumerated() {
            if i > 0 { let line = UIView(); line.backgroundColor = hair; line.heightAnchor.constraint(equalToConstant: 1/UIScreen.main.scale).isActive = true; menu.addArrangedSubview(line) }
            let b = button(title) { [weak self] in self?.dismissOverlay(); perform(title) }; b.contentHorizontalAlignment = .leading
            if title == "Delete" { b.setTitleColor(danger, for: .normal) }; menu.addArrangedSubview(b)
        }
        let width = min(220, bounds.width-8), height = CGFloat(titles.count)*44 + CGFloat(titles.count-1)
        menu.frame = CGRect(x: min(max(4, rect.minX), max(4, bounds.width-width-4)), y: max(0, rect.maxY+height+6 <= bounds.height ? rect.maxY+6 : rect.minY-height-6), width: width, height: height)
        addSubview(menu); tonePopover = menu
    }
    private func prepareKeys() {
        if savedKeys == nil { savedKeys = (inputState.layer, inputState.shift, inputState.capsLock) }
        inputState.layer = .abc; inputState.shift = false; inputState.capsLock = false
    }
    private func restoreKeys() {
        if let saved = savedKeys { inputState.layer = saved.0; inputState.shift = saved.1; inputState.capsLock = saved.2 }; savedKeys = nil; session += 1
    }
    private func search() { if section == "Emoji" { emojiScrollOffset = collection.contentOffset }; prepareKeys(); mode = "Search"; caret = query.count; session += 1; renderEditor() }
    private func edit(_ item: ClipItem? = nil) {
        prepareKeys()
        editItem = item; let draft = noteDrafts[item?.id ?? "new"]
        draftTitle = draft?.0 ?? item?.title ?? ""; draftText = draft?.1 ?? item?.text ?? ""
        titleFocused = false; caret = draftText.count; mode = "Note"; session += 1; renderEditor()
    }
    private func retainDraft() { if mode == "Note" { noteDrafts[editItem?.id ?? "new"] = (draftTitle, draftText) } }
    private var editorLimit: Int { editItem?.note == false ? ClipboardStore.clipMax : ClipboardStore.noteMax }
    private func renderEditor() {
        renderedEditor = true; reset(); backgroundColor = .clear; main.backgroundColor = palette.background
        onTyping?(true, 94)
        let row = stack(); row.heightAnchor.constraint(equalToConstant: 44).isActive = true
        let cancel = button("Cancel", label: "Back to " + section.lowercased()) { [weak self] in
            guard let self = self else { return }; let wasSearch = self.mode == "Search"
            self.retainDraft(); self.restoreKeys(); self.mode = nil; self.session += 1; self.onTyping?(false, 0); self.render()
            if wasSearch && self.section == "Emoji" { self.layoutIfNeeded(); self.collection.layoutIfNeeded(); self.collection.setContentOffset(self.emojiScrollOffset, animated: false) }
        }; cancel.setTitleColor(palette.smallAccent, for: .normal); cancel.widthAnchor.constraint(equalToConstant: 72).isActive = true; row.addArrangedSubview(cancel); main.addArrangedSubview(row)
        main.setCustomSpacing(6, after: row)
        if mode == "Search" {
            let pill = stack(); pill.spacing = 0; pill.backgroundColor = fill; pill.layer.cornerRadius = 12; pill.layer.borderWidth = 1; pill.layer.borderColor = palette.keyOutline.cgColor
            searchLabel = button("", label: "Search " + section.lowercased()) {}; searchLabel?.contentHorizontalAlignment = .leading; searchLabel?.titleLabel?.lineBreakMode = .byTruncatingHead
            pill.addArrangedSubview(searchLabel!)
            let clear = button("×", label: "Clear search") { [weak self] in self?.query = ""; self?.caret = 0; self?.updateEditor() }; clear.widthAnchor.constraint(equalToConstant: 44).isActive = true; pill.addArrangedSubview(clear); row.addArrangedSubview(pill)
            let results = UIScrollView(); resultScroll = results; main.addArrangedSubview(results); results.heightAnchor.constraint(equalToConstant: 44).isActive = true
        } else {
            let title = label(editItem == nil ? "New note" : editItem?.note == false ? "Edit clip" : "Edit note", size: 15); title.font = .systemFont(ofSize: 15, weight: .semibold); title.textAlignment = .center; row.addArrangedSubview(title)
            let save = button("Save") { [weak self] in
                guard let self = self else { return }; self.repository.change { store in if let item = self.editItem { store.edit(item.id, text: self.draftText, title: self.draftTitle) } else { store.note(self.draftTitle, self.draftText) } }
                self.noteDrafts.removeValue(forKey: self.editItem?.id ?? "new"); self.draftText = ""; self.draftTitle = ""; self.restoreKeys(); self.mode = nil; self.editItem = nil; self.session += 1; self.onTyping?(false, 0); self.render()
            }; save.widthAnchor.constraint(equalToConstant: 72).isActive = true; save.setTitleColor(palette.onAccent, for: .normal)
            let pill = UIView(); pill.backgroundColor = palette.accent; pill.layer.cornerRadius = 12; pill.isUserInteractionEnabled = false; pill.translatesAutoresizingMaskIntoConstraints = false; save.insertSubview(pill, at: 0)
            NSLayoutConstraint.activate([pill.leadingAnchor.constraint(equalTo: save.leadingAnchor), pill.trailingAnchor.constraint(equalTo: save.trailingAnchor), pill.centerYAnchor.constraint(equalTo: save.centerYAnchor), pill.heightAnchor.constraint(equalToConstant: 36)]); row.addArrangedSubview(save)
            let fields = stack(); fields.spacing = 6
            titleLabel = nil
            if tablet { titleLabel = noteField(true); fields.addArrangedSubview(titleLabel!) }
            textLabel = noteField(false); fields.addArrangedSubview(textLabel!)
            if let title = titleLabel { textLabel!.widthAnchor.constraint(equalTo: title.widthAnchor, multiplier: 2).isActive = true }
            fields.heightAnchor.constraint(equalToConstant: 44).isActive = true; main.addArrangedSubview(fields)
        }
        updateEditor(); setNeedsLayout()
    }
    private func noteField(_ title: Bool) -> UIButton {
        let b = button("", label: title ? "Note title" : "Note text") { [weak self] in guard let self = self else { return }; self.titleFocused = title; self.caret = title ? self.draftTitle.count : self.draftText.count; self.session += 1; self.updateEditor() }
        b.backgroundColor = fill; b.contentHorizontalAlignment = .leading; b.titleLabel?.lineBreakMode = .byTruncatingHead
        let caption = label(title ? "Title" : "Text", size: 12); caption.textColor = sub; caption.isUserInteractionEnabled = false; caption.translatesAutoresizingMaskIntoConstraints = false; b.addSubview(caption)
        let counter = label("", size: 12); counter.tag = 602; counter.textAlignment = .right; counter.isUserInteractionEnabled = false; counter.translatesAutoresizingMaskIntoConstraints = false; b.addSubview(counter)
        NSLayoutConstraint.activate([caption.leadingAnchor.constraint(equalTo: b.leadingAnchor, constant: 12), caption.centerYAnchor.constraint(equalTo: b.centerYAnchor), counter.trailingAnchor.constraint(equalTo: b.trailingAnchor, constant: -12), counter.centerYAnchor.constraint(equalTo: b.centerYAnchor)])
        return b
    }
    private func number(_ value: Int) -> String { let format = NumberFormatter(); format.locale = Locale(identifier: "en_US"); format.numberStyle = .decimal; return format.string(from: NSNumber(value: value)) ?? String(value) }
    private func caretTitle(_ text: String, focused: Bool) -> NSAttributedString {
        let display = focused ? withCaret(text) : text
        let result = NSMutableAttributedString(string: display, attributes: [.foregroundColor: palette.text, .font: UIFont.systemFont(ofSize: 15)])
        if focused { let offset = text.prefix(caret).utf16.count; result.addAttribute(.foregroundColor, value: palette.accent, range: NSRange(location: offset, length: 1)) }; return result
    }
    private func updateEditor() {
        if mode == "Note" {
            for (b, title) in [(titleLabel, true), (textLabel, false)] {
                guard let b = b else { continue }; let focused = title == titleFocused, text = title ? draftTitle : draftText
                b.setAttributedTitle(caretTitle(text, focused: focused), for: .normal); b.accessibilityValue = text
                b.layer.borderWidth = focused ? 2 : 1; b.layer.borderColor = (focused ? palette.accent : palette.keyOutline).cgColor
                let counter = b.viewWithTag(602) as? UILabel
                counter?.text = title ? (focused ? "\(text.count)/40" : "") : "\(number(text.count))/\(number(editorLimit))"
                counter?.textColor = !title && text.count >= editorLimit ? readable(danger, on: fill) : sub
                b.contentEdgeInsets = UIEdgeInsets(top: 0, left: title ? 48 : 44, bottom: 0, right: title ? (focused ? 56 : 12) : 90)
            }; return
        }
        let display = NSMutableAttributedString(string: "⌕  ", attributes: [.foregroundColor: sub, .font: UIFont.systemFont(ofSize: 15)]); display.append(caretTitle(query, focused: true)); searchLabel?.setAttributedTitle(display, for: .normal); searchLabel?.accessibilityValue = query
        guard let resultScroll = resultScroll else { return }; resultScroll.subviews.forEach { $0.removeFromSuperview() }
        let row = stack(); row.spacing = 0
        if section == "Clipboard" {
            for item in repository.searchableItems(fullAccess: fullAccess()).filter({ ($0.title + $0.text).localizedCaseInsensitiveContains(query) || query.isEmpty }).prefix(30) { row.addArrangedSubview(button(String(item.text.prefix(24)), label: item.text) { [weak self] in self?.onInsert?(item.text) }) }
        } else {
            let entries = section == "Emoji" ? (emojiReady ? InsertAssets.emoji.search(query).filter { InsertAssets.hasGlyph($0.text) } : []) : InsertAssets.kaomoji.filter { ($0.name + " " + $0.category).localizedCaseInsensitiveContains(query) || query.isEmpty }
            for entry in entries.prefix(40) { let e = section == "Emoji" ? shown(entry) : entry; let b = button(e.text, label: e.name) { [weak self] in self?.use(e) }; b.titleLabel?.font = .systemFont(ofSize: section == "Emoji" ? 28 : 16); if section == "Emoji" { b.widthAnchor.constraint(equalToConstant: 48).isActive = true; b.contentEdgeInsets = .zero }; row.addArrangedSubview(b) }
        }
        let empty = row.arrangedSubviews.isEmpty
        if empty { let message = label("No \(section.lowercased()) for “\(query)”", size: 14); message.textColor = sub; message.textAlignment = .center; row.addArrangedSubview(message) }
        resultScroll.addSubview(row); row.translatesAutoresizingMaskIntoConstraints = false
        if empty { row.widthAnchor.constraint(equalTo: resultScroll.frameLayoutGuide.widthAnchor).isActive = true }
        NSLayoutConstraint.activate([row.leadingAnchor.constraint(equalTo: resultScroll.contentLayoutGuide.leadingAnchor), row.trailingAnchor.constraint(equalTo: resultScroll.contentLayoutGuide.trailingAnchor), row.topAnchor.constraint(equalTo: resultScroll.contentLayoutGuide.topAnchor), row.bottomAnchor.constraint(equalTo: resultScroll.contentLayoutGuide.bottomAnchor), row.heightAnchor.constraint(equalTo: resultScroll.frameLayoutGuide.heightAnchor)])
    }
    @discardableResult func typeText(_ text: String) -> Bool {
        guard acceptsText else { return false }
        var value = mode == "Search" ? query : titleFocused ? draftTitle : draftText
        let limit = mode == "Search" ? Int.max : titleFocused ? ClipboardStore.titleMax : editorLimit
        let changed = insertEditorText(value, text, caret: caret, limit: limit); value = changed.0; caret = changed.1
        if mode == "Search" { query = value } else if titleFocused { draftTitle = value } else { draftText = value }
        caret = min(caret, (mode == "Search" ? query : titleFocused ? draftTitle : draftText).count); updateEditor(); return true
    }
    private func withCaret(_ text: String) -> String { var result = text; result.insert("│", at: result.index(result.startIndex, offsetBy: min(caret, result.count))); return result }
    @discardableResult func moveCursor(_ steps: Int) -> Bool { guard acceptsText else { return false }; let value = mode == "Search" ? query : titleFocused ? draftTitle : draftText; caret = max(0, min(value.count, caret + max(-20_000, min(20_000, steps)))); updateEditor(); return true }
    func key(_ code: String, state: InputState) -> Bool {
        guard acceptsText, !KeyText.isLayerKey(code) else { return false }
        switch code {
        case Keys.backspace:
            if caret > 0 {
                var value = mode == "Search" ? query : titleFocused ? draftTitle : draftText
                caret = min(caret, value.count) - 1; value.remove(at: value.index(value.startIndex, offsetBy: caret))
                if mode == "Search" { query = value } else if titleFocused { draftTitle = value } else { draftText = value }
            }; updateEditor()
        case Keys.space: typeText(" ")
        case Keys.enter: if mode == "Note" { typeText("\n") }
        default: if let text = KeyText.output(code, state) { typeText(text); if !state.capsLock { state.shift = false } }
        }; return true
    }
    private func sectionHeader(_ title: String, new: Bool = false) -> UIView {
        let row = stack(), caption = label(title.uppercased(), size: 12); caption.font = .systemFont(ofSize: 12, weight: .semibold); caption.textColor = sub
        let tracked = NSMutableAttributedString(string: title.uppercased(), attributes: [.kern: 0.4]); caption.attributedText = tracked; row.addArrangedSubview(caption)
        if new { let add = button("+ New", label: "Create note") { [weak self] in self?.edit() }; add.setTitleColor(palette.smallAccent, for: .normal); row.addArrangedSubview(add) }
        row.heightAnchor.constraint(equalToConstant: new ? 44 : 30).isActive = true; return row
    }
    private func stateView(_ icon: String, _ title: String, _ message: String, actions: [(String, () -> Void)] = []) -> UIView {
        let holder = UIScrollView(), area = UIView(), content = stack(.vertical); content.alignment = .center; content.spacing = 10
        let circle = UIView(); circle.backgroundColor = fill; circle.layer.cornerRadius = 22
        circle.widthAnchor.constraint(equalToConstant: 44).isActive = true; circle.heightAnchor.constraint(equalToConstant: 44).isActive = true
        let image = UIImageView(image: UIImage(systemName: icon)); image.tintColor = palette.text; image.contentMode = .scaleAspectFit; image.frame = CGRect(x: 12, y: 12, width: 20, height: 20); circle.addSubview(image); image.isAccessibilityElement = false
        content.addArrangedSubview(circle)
        let heading = label(title, size: 17); heading.font = .systemFont(ofSize: 17, weight: .semibold); heading.textAlignment = .center; content.addArrangedSubview(heading)
        let body = label(message, size: 14); body.textColor = sub; body.textAlignment = .center; content.addArrangedSubview(body)
        let buttons = stack(); buttons.tag = 620
        for (i, action) in actions.enumerated() { let b = button(action.0, action.1); b.backgroundColor = i == 0 ? palette.accent : fill; b.setTitleColor(i == 0 ? palette.onAccent : palette.text, for: .normal); buttons.addArrangedSubview(b) }; content.addArrangedSubview(buttons)
        holder.addSubview(area); area.translatesAutoresizingMaskIntoConstraints = false; area.addSubview(content); content.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([area.leadingAnchor.constraint(equalTo: holder.contentLayoutGuide.leadingAnchor), area.trailingAnchor.constraint(equalTo: holder.contentLayoutGuide.trailingAnchor), area.topAnchor.constraint(equalTo: holder.contentLayoutGuide.topAnchor), area.bottomAnchor.constraint(equalTo: holder.contentLayoutGuide.bottomAnchor), area.widthAnchor.constraint(equalTo: holder.frameLayoutGuide.widthAnchor), area.heightAnchor.constraint(greaterThanOrEqualTo: holder.frameLayoutGuide.heightAnchor), content.centerXAnchor.constraint(equalTo: area.centerXAnchor), content.centerYAnchor.constraint(equalTo: area.centerYAnchor), content.widthAnchor.constraint(lessThanOrEqualTo: area.widthAnchor, constant: -24), content.topAnchor.constraint(greaterThanOrEqualTo: area.topAnchor, constant: 8), content.bottomAnchor.constraint(lessThanOrEqualTo: area.bottomAnchor, constant: -8)])
        return holder
    }
    private func settingsButton(_ title: String, system: Bool = false) -> UIButton {
        let b = button(title) {}; b.isAccessibilityElement = false
        settingsLinkController.rootView = InsertSettingsLink(destination: system ? UIApplication.openSettingsURLString : "tailorkeys://settings?panel=clipboard")
        let link = settingsLinkController.view!; link.removeFromSuperview(); link.backgroundColor = .clear; link.translatesAutoresizingMaskIntoConstraints = false; b.addSubview(link)
        NSLayoutConstraint.activate([link.leadingAnchor.constraint(equalTo: b.leadingAnchor), link.trailingAnchor.constraint(equalTo: b.trailingAnchor), link.topAnchor.constraint(equalTo: b.topAnchor), link.bottomAnchor.constraint(equalTo: b.bottomAnchor)]); return b
    }
    private func renderClipboard() {
        let store = repository.store(); repository.save(store)
        let columns = stack(); columns.spacing = 14; columns.distribution = .fillEqually
        if tablet || !notes { columns.addArrangedSubview(clipboardColumn(store, notes: false)) }
        if tablet || notes { columns.addArrangedSubview(clipboardColumn(store, notes: true)) }; main.addArrangedSubview(columns)
        if tablet {
            let privacy = label(repository.keep == .forever ? "On this device · kept until you clear it" : "On this device · clears after " + repository.keep.title, size: 12); privacy.textColor = sub; privacy.textAlignment = .center
            let context = stack(); context.alignment = .center
            let icon = UIImageView(image: UIImage(systemName: "lock")); icon.tintColor = sub; icon.widthAnchor.constraint(equalToConstant: 14).isActive = true; icon.heightAnchor.constraint(equalToConstant: 14).isActive = true; icon.contentMode = .scaleAspectFit
            context.addArrangedSubview(UIView()); context.addArrangedSubview(icon); context.addArrangedSubview(privacy); context.addArrangedSubview(UIView()); makeBottom(context)
        } else {
            let context = UIView(), control = stack(); control.spacing = 0; control.backgroundColor = fill; control.layer.cornerRadius = 12
            for (i, name) in ["Clips", "Notes"].enumerated() { let b = button(name, selected: notes == (i == 1)) { [weak self] in self?.notes = i == 1; self?.render() }; b.widthAnchor.constraint(equalToConstant: 72).isActive = true; styleTab(b, selected: notes == (i == 1)); control.addArrangedSubview(b) }
            context.addSubview(control); control.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([control.centerXAnchor.constraint(equalTo: context.centerXAnchor), control.centerYAnchor.constraint(equalTo: context.centerYAnchor), control.heightAnchor.constraint(equalToConstant: 44)])
            makeBottom(context)
        }
    }
    private func clipboardColumn(_ store: ClipboardStore, notes: Bool) -> UIView {
        let column = stack(.vertical); column.spacing = 0
        let enabled = repository.prefs.boolDefault(InsertPrefs.history, false), items = notes ? store.notes : enabled && fullAccess() ? store.clips : []
        if !notes && enabled && fullAccess() {
            let paste = button("Paste from clipboard", label: "Paste from clipboard. iOS may ask permission to paste.") { [weak self] in
                guard let self = self, self.fullAccess(), !self.password(), self.repository.prefs.bool(forKey: InsertPrefs.history) else { return }
                if let text = UIPasteboard.general.string { self.repository.change { $0.capture(text, enabled: true, sensitive: false, password: self.password(), fullAccess: self.fullAccess()) } }; self.render()
            }; paste.heightAnchor.constraint(equalToConstant: 44).isActive = true; column.addArrangedSubview(paste)
        }
        if !notes {
            let state = clipboardPanelState(enabled: enabled, hasItems: !items.isEmpty, hadItems: repository.prefs.bool(forKey: InsertPrefs.hadClips), dismissed: repository.prefs.bool(forKey: "insert_first_use"), fullAccess: fullAccess())
            let view: UIView?
            switch state {
            case .historyOff:
                let content = stateView("doc.on.clipboard", "Clipboard history is off", "Notes still work: they are your own text, not history.", actions: [("Turn on", { [weak self] in self?.repository.prefs.set(true, forKey: InsertPrefs.history); self?.render() })])
                if let buttons = content.viewWithTag(620) as? UIStackView { buttons.addArrangedSubview(settingsButton("Settings")) }; view = content
            case .accessRequired:
                let content = stateView("lock", "Turn on Full Access", "iOS only shares the clipboard with Full Access. Notes still work.")
                if let buttons = content.viewWithTag(620) as? UIStackView { let b = settingsButton("Open Settings", system: true); b.backgroundColor = palette.accent; b.setTitleColor(palette.onAccent, for: .normal); buttons.addArrangedSubview(b) }; view = content
            case .firstUse:
                view = stateView("lock", "Your clipboard, kept on this device", "Copied text shows here and clears after " + repository.keep.title + ". Pins and notes stay until you remove them. Password fields are never kept.", actions: [("Got it", { [weak self] in self?.repository.prefs.set(true, forKey: "insert_first_use"); self?.render() })])
            case .empty: view = stateView("doc.on.clipboard", "Nothing copied yet", "Paste from clipboard to add copied text.")
            case .items: view = nil
            }
            if let view = view { column.addArrangedSubview(view); return column }
        }
        let list = stack(.vertical); list.spacing = 0
        func group(_ title: String, _ entries: [ClipItem], new: Bool = false) {
            list.addArrangedSubview(sectionHeader(title, new: new))
            let group = stack(.vertical); group.spacing = 0; group.backgroundColor = palette.key; group.layer.cornerRadius = 12; group.layer.borderWidth = 1; group.layer.borderColor = palette.keyOutline.cgColor; group.clipsToBounds = true
            for (index, item) in entries.enumerated() {
                if index > 0 { let inset = UIView(); let line = UIView(); line.backgroundColor = hair; line.translatesAutoresizingMaskIntoConstraints = false; inset.addSubview(line); NSLayoutConstraint.activate([inset.heightAnchor.constraint(equalToConstant: 1/UIScreen.main.scale), line.leadingAnchor.constraint(equalTo: inset.leadingAnchor, constant: 14), line.trailingAnchor.constraint(equalTo: inset.trailingAnchor), line.topAnchor.constraint(equalTo: inset.topAnchor), line.bottomAnchor.constraint(equalTo: inset.bottomAnchor)]); group.addArrangedSubview(inset) }
                let row = button("", label: (item.pinned ? "Pinned, " : "") + item.title + ", " + item.text) { [weak self] in self?.onInsert?(item.text) }; row.contentEdgeInsets = .zero
                let content = stack(); content.isUserInteractionEnabled = false; content.alignment = .center; content.translatesAutoresizingMaskIntoConstraints = false; row.addSubview(content)
                if item.pinned { let pin = UIImageView(image: UIImage(systemName: "pin.fill")); pin.tintColor = palette.text; pin.contentMode = .scaleAspectFit; pin.widthAnchor.constraint(equalToConstant: 16).isActive = true; pin.heightAnchor.constraint(equalToConstant: 16).isActive = true; content.addArrangedSubview(pin) }
                let text = label(item.text.replacingOccurrences(of: "\n", with: " "), size: 15); text.numberOfLines = 1; text.lineBreakMode = .byTruncatingTail; text.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
                if notes && !item.title.isEmpty { let title = label(item.title, size: 15); title.numberOfLines = 1; title.lineBreakMode = .byTruncatingTail; title.font = .systemFont(ofSize: 15, weight: .semibold); title.widthAnchor.constraint(lessThanOrEqualTo: row.widthAnchor, multiplier: 0.45).isActive = true; content.addArrangedSubview(title); text.textColor = readable(palette.hint, on: palette.key) }
                content.addArrangedSubview(text)
                if !notes { let age = label(RelativeDateTimeFormatter().localizedString(for: Date(timeIntervalSince1970: item.at), relativeTo: Date()), size: 13); age.textColor = readable(palette.hint, on: palette.key); age.numberOfLines = 1; age.setContentCompressionResistancePriority(.required, for: .horizontal); content.addArrangedSubview(age) }
                NSLayoutConstraint.activate([row.heightAnchor.constraint(equalToConstant: tablet ? 48 : 44), content.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 14), content.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -14), content.topAnchor.constraint(equalTo: row.topAnchor), content.bottomAnchor.constraint(equalTo: row.bottomAnchor)])
                let titles = clipActions(item)
                row.addGestureRecognizer(InsertHoldGesture { [weak self, weak row] in guard let self = self, let row = row else { return }; self.showMenu(row, titles: titles) { [weak self] in self?.perform($0, item: item) } })
                row.accessibilityCustomActions = titles.map { title in UIAccessibilityCustomAction(name: title) { [weak self] _ in self?.perform(title, item: item); return true } }; group.addArrangedSubview(row)
            }; list.addArrangedSubview(group); list.setCustomSpacing(8, after: group)
        }
        if notes { group("Notes", items, new: true); if items.isEmpty { list.addArrangedSubview(label("No notes yet. Write one with + New, or save a clip as a note.")) } }
        else { if items.contains(where: \.pinned) { group("Pinned", items.filter(\.pinned)) }; if items.contains(where: { !$0.pinned }) { group("Recent", items.filter { !$0.pinned }) } }
        column.addArrangedSubview(scroll(list)); return column
    }
    private func clipActions(_ item: ClipItem) -> [String] { [item.pinned ? "Unpin" : "Pin", "Edit"] + (item.note ? [] : ["Save as note"]) + ["Delete"] }
    private func perform(_ action: String, item: ClipItem) {
        dismissOverlay()
        switch action {
        case "Pin", "Unpin": repository.change { $0.pin(item.id) }; render()
        case "Edit": edit(item)
        case "Save as note": repository.change { $0.saveAsNote(item.id) }; render()
        case "Delete":
            let store = repository.store(); store.delete(item.id); repository.save(store); render(); showUndo(item, store)
        default: break
        }
    }
    private func showUndo(_ item: ClipItem, _ store: ClipboardStore) {
        viewWithTag(610)?.removeFromSuperview()
        let toast = stack(); toast.tag = 610; toast.alignment = .center; toast.backgroundColor = palette.text; toast.layer.cornerRadius = 14; toast.layer.shadowOpacity = 0.15; toast.layer.shadowRadius = 8
        let caption = label((item.note ? "Note" : "Clip") + " deleted", size: 15); caption.textColor = palette.key; toast.addArrangedSubview(caption)
        let undo = button("Undo") { [weak self, weak toast] in guard let self = self else { return }; if store.undo() { self.repository.change { $0.restore(item) } }; toast?.removeFromSuperview(); self.render() }; undo.setTitleColor(readable(palette.accent, on: palette.text, toward: palette.key), for: .normal); toast.addArrangedSubview(undo)
        toast.isLayoutMarginsRelativeArrangement = true; toast.layoutMargins = UIEdgeInsets(top: 0, left: 16, bottom: 0, right: 4)
        toast.frame = CGRect(x: max(8, bounds.midX-200), y: bounds.height-100, width: min(400,bounds.width-16), height: 48); addSubview(toast)
        DispatchQueue.main.asyncAfter(deadline: .now()+6) { [weak toast] in toast?.removeFromSuperview() }
    }
    private func renderDictate() {
        let content = UIView(), message = stack(.vertical); message.alignment = .center; message.spacing = 10
        let circle = UIView(); circle.backgroundColor = fill; circle.layer.cornerRadius = 22
        circle.widthAnchor.constraint(equalToConstant: 44).isActive = true; circle.heightAnchor.constraint(equalToConstant: 44).isActive = true
        let mic = UIImageView(image: UIImage(systemName: "mic")); mic.tintColor = palette.text; mic.contentMode = .scaleAspectFit; mic.frame = CGRect(x: 12, y: 12, width: 20, height: 20); circle.addSubview(mic); circle.accessibilityElementsHidden = true; message.addArrangedSubview(circle)
        let title = "Use the mic below", body = "iOS keeps the microphone for its own dictation. Tap it, speak, then come back with ABC."
        let heading = label(title, size: 17); heading.font = .systemFont(ofSize: 17, weight: .semibold); heading.textAlignment = .center
        let explanation = label(body, size: 14); explanation.textColor = sub; explanation.textAlignment = .center
        message.addArrangedSubview(heading); message.addArrangedSubview(explanation)
        message.isAccessibilityElement = true; message.accessibilityLabel = title + ". " + body; message.accessibilityTraits = .staticText
        content.addSubview(message); message.translatesAutoresizingMaskIntoConstraints = false
        let arrow = DictateArrow(); arrow.isOpaque = false; arrow.backgroundColor = .clear; arrow.ink = sub; arrow.isAccessibilityElement = false; arrow.isUserInteractionEnabled = false; arrow.translatesAutoresizingMaskIntoConstraints = false; content.addSubview(arrow)
        NSLayoutConstraint.activate([message.centerXAnchor.constraint(equalTo: content.centerXAnchor), message.centerYAnchor.constraint(equalTo: content.centerYAnchor), message.widthAnchor.constraint(lessThanOrEqualToConstant: 320), message.widthAnchor.constraint(lessThanOrEqualTo: content.widthAnchor, constant: -32), arrow.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -20), arrow.bottomAnchor.constraint(equalTo: content.bottomAnchor, constant: -6), arrow.widthAnchor.constraint(equalToConstant: 40), arrow.heightAnchor.constraint(equalToConstant: 40)])
        main.addArrangedSubview(content)
        if DictateHint.claimPulse(UserDefaults.standard, reduceMotion: UIAccessibility.isReduceMotionEnabled) {
            let pulse = CAKeyframeAnimation(keyPath: "transform.scale"); pulse.values = [1, 1.15, 1]; pulse.keyTimes = [0, 0.5, 1]; pulse.duration = 0.6; pulse.repeatCount = 2; pulse.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut); arrow.layer.add(pulse, forKey: "firstDictateHint")
        }
    }

}

private final class SymbolCell: UICollectionViewCell {
    let text = UILabel()
    var header = false
    override init(frame: CGRect) { super.init(frame: frame); text.textAlignment = .center; contentView.addSubview(text) }
    required init?(coder: NSCoder) { fatalError("init(coder:)") }
    override func layoutSubviews() { super.layoutSubviews(); text.frame = header ? CGRect(x: 0, y: 0, width: 500, height: bounds.height) : contentView.bounds; text.textAlignment = header ? .left : .center }
}

struct InsertSettingsLink: View {
    var destination = "tailorkeys://settings?panel=clipboard"
    var body: some View {
        Link(destination: URL(string: destination)!) {
            Rectangle().fill(Color.clear).contentShape(Rectangle()).frame(maxWidth: .infinity, maxHeight: .infinity)
        }.buttonStyle(.plain).accessibilityLabel("Clipboard and notes settings")
    }
}

private final class FadingInsertScrollView: UIScrollView {
    private let fade = CAGradientLayer()
    override func layoutSubviews() {
        super.layoutSubviews()
        guard contentSize.width > bounds.maxX+1 else { layer.mask = nil; return }
        fade.frame = bounds
        let edge = min(1, 24 / max(1, bounds.width))
        fade.colors = [UIColor.black.cgColor, UIColor.black.cgColor, UIColor.clear.cgColor]
        fade.locations = [0, NSNumber(value: Double(1-edge)), 1]
        fade.startPoint = CGPoint(x: 0, y: 0.5); fade.endPoint = CGPoint(x: 1, y: 0.5); layer.mask = fade
    }
}

private final class InsertFlowLayout: UICollectionViewFlowLayout {
    var wrapping = false
    private var wrapped: [IndexPath: UICollectionViewLayoutAttributes] = [:]
    override func prepare() {
        super.prepare(); wrapped.removeAll()
        guard wrapping, let collection = collectionView else { return }
        var x: CGFloat = 0, row: CGFloat = -1
        for index in 0..<collection.numberOfItems(inSection: 0) {
            let path = IndexPath(item: index, section: 0)
            guard let item = super.layoutAttributesForItem(at: path)?.copy() as? UICollectionViewLayoutAttributes else { continue }
            if item.frame.minY != row { row = item.frame.minY; x = 0 }
            item.frame.origin.x = x; x += item.frame.width+8; wrapped[path] = item
        }
    }
    override func layoutAttributesForElements(in rect: CGRect) -> [UICollectionViewLayoutAttributes]? {
        wrapping ? wrapped.values.filter { $0.frame.intersects(rect) } : super.layoutAttributesForElements(in: rect)
    }
    override func layoutAttributesForItem(at indexPath: IndexPath) -> UICollectionViewLayoutAttributes? { wrapping ? wrapped[indexPath] : super.layoutAttributesForItem(at: indexPath) }
}

private final class InsertHoldGesture: UILongPressGestureRecognizer {
    private let held: () -> Void
    init(_ held: @escaping () -> Void) { self.held = held; super.init(target: nil, action: nil); addTarget(self, action: #selector(invoke)) }
    required init?(coder: NSCoder) { fatalError("init(coder:)") }
    @objc private func invoke() { if state == .began { held() } }
}

private final class DictateArrow: UIView {
    var ink = UIColor.label
    override func draw(_ rect: CGRect) {
        ink.setStroke(); let arrow = UIBezierPath(); arrow.lineWidth = 1.75; arrow.lineCapStyle = .round; arrow.lineJoinStyle = .round
        arrow.move(to: CGPoint(x: 5, y: 7)); arrow.addQuadCurve(to: CGPoint(x: 31, y: 31), controlPoint: CGPoint(x: 29, y: 9))
        arrow.move(to: CGPoint(x: 24, y: 27)); arrow.addLine(to: CGPoint(x: 31, y: 31)); arrow.addLine(to: CGPoint(x: 34, y: 23)); arrow.stroke()
    }
}
