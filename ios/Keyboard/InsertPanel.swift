import UIKit
import CoreText
import SwiftUI

private enum InsertAssets {
    static let emoji = EmojiCatalog(read("emoji"))
    static let renderable = emoji.primary.filter { hasGlyph($0.text) }
    private static var glyphCache: [String: Bool] = [:]
    static func hasGlyph(_ text: String) -> Bool {
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
    private let flow = UICollectionViewFlowLayout()
    private lazy var collection = UICollectionView(frame: .zero, collectionViewLayout: flow)
    private lazy var gridViewport = scroll(collection, horizontal: true)
    private var gridWidth: NSLayoutConstraint?
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
    private var editItem: ClipItem?
    private var draftTitle = "", draftText = ""
    private var noteDrafts: [String: (String, String)] = [:]
    private var caret = 0
    private var titleFocused = false
    private var searchLabel: UIButton?
    private var titleLabel: UIButton?, textLabel: UIButton?
    private var resultScroll: UIScrollView?
    private var categoryButtons: [String: UIButton] = [:]
    private var tonePopover: UIStackView?
    private var toneRow: UIStackView?
    private var toneEntries: [InsertSymbol] = []
    private var toneChoice = -1
    private var expiry: Timer?
    private var footer: UIStackView?
    private var palette: Palette { Palette.of(settings.theme, dark: traitCollection.userInterfaceStyle == .dark) }
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
        flow.minimumInteritemSpacing = 0; flow.minimumLineSpacing = 4
        collection.backgroundColor = .clear; collection.dataSource = self; collection.delegate = self
        collection.register(SymbolCell.self, forCellWithReuseIdentifier: "symbol")
        collection.addGestureRecognizer(UILongPressGestureRecognizer(target: self, action: #selector(holdEmoji(_:))))
        isHidden = true; accessibilityElementsHidden = true
    }
    required init?(coder: NSCoder) { fatalError("init(coder:)") }
    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.userInterfaceStyle != traitCollection.userInterfaceStyle { refresh() }
    }
    override func layoutSubviews() {
        super.layoutSubviews()
        let h: CGFloat = mode == "Note" ? (tablet ? 104 : 152) : mode == "Search" ? (tablet ? 56 : 104) : bounds.height - 8
        main.frame = CGRect(x: 8, y: 4, width: max(0, bounds.width - 16), height: h)
        gridWidth?.constant = section == "Emoji" ? (tablet ? 784 : 384) : max(48, main.bounds.width)
        flow.invalidateLayout()
    }
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        if mode != nil && !main.frame.contains(point) { return nil }
        return super.hitTest(point, with: event)
    }
    func show() {
        retainDraft(); restoreKeys()
        if mode != nil { onTyping?(false, 0) }; mode = nil; session += 1
        isHidden = false; accessibilityElementsHidden = false
        if renderedEditor || builtStyle != signature { render() }
        expiry?.invalidate(); expiry = Timer.scheduledTimer(withTimeInterval: 60, repeats: true) { [weak self] _ in
            guard let self = self else { return }; self.repository.change { _ in }; if self.section == "Clipboard" && self.mode == nil { self.render() }
        }
    }
    func hide() { retainDraft(); restoreKeys(); expiry?.invalidate(); expiry = nil; mode = nil; session += 1; onTyping?(false, 0); isHidden = true; accessibilityElementsHidden = true; tonePopover?.removeFromSuperview() }
    func refresh() { if !isHidden { if mode == nil { if builtStyle != signature { render() } } else { renderEditor() } } }
    private var signature: String {
        "\(settings.theme.rawValue):\(traitCollection.userInterfaceStyle.rawValue):\(settings.int(Prefs.cornerRadius)):\(repository.prefs.integer(forKey: InsertPrefs.tone)):\(repository.prefs.bool(forKey: InsertPrefs.history)):\(repository.keep.rawValue):\(repository.recent("Emoji")):\(repository.recent("Kaomoji")):\(repository.prefs.data(forKey: "insert_clips")?.hashValue ?? 0):\(fullAccess())"
    }
    private func reset() { main.arrangedSubviews.forEach { main.removeArrangedSubview($0); $0.removeFromSuperview() }; tonePopover?.removeFromSuperview(); tonePopover = nil; categoryButtons = [:]; setNeedsLayout() }
    private func stack(_ axis: NSLayoutConstraint.Axis = .horizontal) -> UIStackView { let s = UIStackView(); s.axis = axis; s.spacing = 4; return s }
    private func button(_ title: String, label: String? = nil, selected: Bool = false, _ action: @escaping () -> Void) -> UIButton {
        let b = UIButton(type: .system); b.setTitle(title, for: .normal); b.accessibilityLabel = label ?? title
        b.titleLabel?.font = .systemFont(ofSize: 14, weight: selected ? .semibold : .regular)
        b.setTitleColor(selected ? palette.smallAccent : palette.text, for: .normal)
        b.tintColor = selected ? palette.smallAccent : palette.text
        b.backgroundColor = selected ? palette.key : palette.functionKey
        b.layer.cornerRadius = CGFloat(settings.int(Prefs.cornerRadius)); b.layer.borderWidth = selected ? 2 : 0; b.layer.borderColor = palette.accent.cgColor
        if selected { b.accessibilityTraits.insert(.selected) }
        b.contentEdgeInsets = UIEdgeInsets(top: 6, left: 10, bottom: 6, right: 10)
        b.heightAnchor.constraint(greaterThanOrEqualToConstant: 48).isActive = true
        b.widthAnchor.constraint(greaterThanOrEqualToConstant: 48).isActive = true
        b.addAction(UIAction { _ in action() }, for: .touchUpInside); return b
    }
    private func label(_ text: String, size: CGFloat = 14) -> UILabel {
        let v = UILabel(); v.text = text; v.numberOfLines = 0; v.font = .systemFont(ofSize: size); v.textColor = palette.text; return v
    }
    private func scroll(_ content: UIView, horizontal: Bool = false) -> UIScrollView {
        let s = UIScrollView(); s.addSubview(content); content.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([content.leadingAnchor.constraint(equalTo: s.contentLayoutGuide.leadingAnchor), content.trailingAnchor.constraint(equalTo: s.contentLayoutGuide.trailingAnchor), content.topAnchor.constraint(equalTo: s.contentLayoutGuide.topAnchor), content.bottomAnchor.constraint(equalTo: s.contentLayoutGuide.bottomAnchor), horizontal ? content.heightAnchor.constraint(equalTo: s.frameLayoutGuide.heightAnchor) : content.widthAnchor.constraint(equalTo: s.frameLayoutGuide.widthAnchor)])
        s.showsHorizontalScrollIndicator = false; return s
    }
    private func render() {
        renderedEditor = false; reset(); backgroundColor = palette.background; main.backgroundColor = .clear
        let top = stack(); top.heightAnchor.constraint(equalToConstant: 56).isActive = true
        top.addArrangedSubview(button("ABC", label: "Back to keys") { [weak self] in self?.onClose?() })
        if tablet && section != "Dictate" { top.addArrangedSubview(button("⌕ Search", label: "Search \(section.lowercased())") { [weak self] in self?.search() }) }
        let sections = stack(); sections.distribution = .fillEqually
        for (i, name) in ["Emoji", "Kaomoji", "Clipboard", "Dictate"].enumerated() {
            let title = tablet ? name : ["☺", "^_^", "▤", "♩"][i]
            let b = button(title, label: name, selected: section == name) { [weak self] in
                guard let self = self else { return }; self.section = name; self.category = "Recent"; self.repository.prefs.set(name, forKey: InsertPrefs.section); self.render()
            }
            if i != 1 { b.setImage(UIImage(systemName: ["face.smiling", "", "doc.on.clipboard", "mic"][i]), for: .normal); b.setTitle(tablet ? " " + name : "", for: .normal) }
            styleTab(b, selected: section == name); sections.addArrangedSubview(b)
        }
        top.addArrangedSubview(sections); top.addArrangedSubview(button("⌫", label: "Backspace") { [weak self] in self?.onBackspace?() }); main.addArrangedSubview(top)
        if section == "Emoji" || section == "Kaomoji" { renderSymbols() }
        else if section == "Clipboard" { renderClipboard() }
        else { renderDictate() }
        builtStyle = signature
    }
    private func styleTab(_ b: UIButton, selected: Bool) {
        b.layer.borderWidth = 0; b.backgroundColor = selected ? palette.key : .clear
        b.setTitleColor(selected ? palette.smallAccent : palette.text, for: .normal); b.tintColor = selected ? palette.smallAccent : palette.text
        let bar: UIView
        if let existing = b.viewWithTag(500) { bar = existing } else {
            bar = UIView(); bar.tag = 500; bar.translatesAutoresizingMaskIntoConstraints = false; b.addSubview(bar)
            NSLayoutConstraint.activate([bar.leadingAnchor.constraint(equalTo: b.leadingAnchor, constant: 8), bar.trailingAnchor.constraint(equalTo: b.trailingAnchor, constant: -8), bar.bottomAnchor.constraint(equalTo: b.bottomAnchor), bar.heightAnchor.constraint(equalToConstant: 3)])
        }
        bar.backgroundColor = palette.accent; bar.isHidden = !selected
        b.accessibilityTraits = selected ? [.button, .selected] : [.button]
    }
    private func recents() -> [InsertSymbol] {
        let byText = section == "Emoji" ? emojiByText : kaomojiByText
        return repository.recent(section).compactMap { byText[$0] }.filter { section != "Emoji" || InsertAssets.hasGlyph($0.text) }
    }
    private func renderSymbols() {
        let all = section == "Emoji" ? InsertAssets.renderable : InsertAssets.kaomoji
        if section == "Kaomoji" && category == "Recent" && recents().isEmpty { category = "Happy" }
        let tabs = stack()
        if !tablet { tabs.addArrangedSubview(button("⌕", label: "Search \(section.lowercased())") { [weak self] in self?.search() }) }
        var cats = ["Recent"]; for entry in all where !cats.contains(entry.category) { cats.append(entry.category) }
        for (i, cat) in cats.enumerated() {
            let icons = ["◷", "☺", "♙", "♧", "♨", "▱", "⚽", "♧", "♡", "⚑"]
            let b = button(section == "Emoji" ? icons[min(i, icons.count - 1)] : cat, label: cat, selected: cat == category) { [weak self] in
                guard let self = self else { return }; self.category = cat
                if self.section == "Emoji" {
                    let index = cat == "Recent" ? 0 : self.symbols.firstIndex(where: { $0.category == cat }) ?? 0
                    if self.symbols.indices.contains(index) { self.collection.scrollToItem(at: IndexPath(item: index, section: 0), at: .top, animated: !UIAccessibility.isReduceMotionEnabled) }; self.markCategories()
                } else { self.symbols = cat == "Recent" ? self.recents() : all.filter { $0.category == cat }; self.collection.reloadData(); self.markCategories() }
            }
            if section == "Emoji" {
                let names = ["clock", "face.smiling", "person", "pawprint", "cup.and.saucer", "car", "sportscourt", "lightbulb", "heart", "flag"]
                b.setTitle("", for: .normal); b.setImage(UIImage(systemName: names[min(i, names.count - 1)]), for: .normal)
            }
            categoryButtons[cat] = b; styleTab(b, selected: cat == category); tabs.addArrangedSubview(b)
        }
        let tabScroll = scroll(tabs, horizontal: true); tabScroll.heightAnchor.constraint(equalToConstant: 48).isActive = true; main.addArrangedSubview(tabScroll)
        symbols = section == "Emoji" ? emojiCells() : category == "Recent" ? recents() : all.filter { $0.category == category }
        if gridWidth == nil { gridWidth = collection.widthAnchor.constraint(equalToConstant: tablet ? 784 : 384); gridWidth?.isActive = true }
        main.addArrangedSubview(gridViewport); collection.reloadData()
    }
    private func emojiCells() -> [InsertSymbol] {
        let cols = tablet ? 14 : 8; var result: [InsertSymbol] = []
        func group(_ category: String, _ entries: [InsertSymbol]) {
            guard !entries.isEmpty else { return }
            while result.count % cols != 0 { result.append(InsertSymbol("", "", result.last!.category)) }
            let title = ["Smileys & Emotion": "Smileys", "People & Body": "People", "Animals & Nature": "Animals", "Food & Drink": "Food", "Travel & Places": "Travel"][category] ?? category
            for i in 0..<cols { result.append(InsertSymbol("", i == 0 ? title : "", category)) }
            result += entries.map { InsertSymbol($0.text, $0.name, category, $0.keywords) }
        }
        group("Recent", recents()); var cats: [String] = []
        for entry in InsertAssets.renderable where !cats.contains(entry.category) { cats.append(entry.category) }
        for cat in cats { group(cat, InsertAssets.renderable.filter { $0.category == cat }) }; return result
    }
    private func markCategories() {
        for (cat, b) in categoryButtons { styleTab(b, selected: cat == category) }
    }
    private func shown(_ entry: InsertSymbol) -> InsertSymbol {
        let toned = InsertAssets.emoji.toned(entry, repository.prefs.integer(forKey: InsertPrefs.tone))
        return InsertAssets.hasGlyph(toned.text) ? toned : entry
    }
    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { symbols.count }
    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: "symbol", for: indexPath) as! SymbolCell
        let entry = section == "Emoji" ? shown(symbols[indexPath.item]) : symbols[indexPath.item]
        cell.text.text = entry.text.isEmpty ? entry.name : entry.text; cell.text.font = .systemFont(ofSize: entry.text.isEmpty ? 12 : section == "Emoji" ? 28 : 16); cell.text.textColor = palette.text
        cell.backgroundColor = section == "Emoji" ? .clear : palette.key; cell.layer.cornerRadius = CGFloat(settings.int(Prefs.cornerRadius))
        cell.isAccessibilityElement = !entry.text.isEmpty || !entry.name.isEmpty; cell.accessibilityLabel = entry.name; cell.accessibilityTraits = entry.text.isEmpty ? .header : .button
        cell.accessibilityCustomActions = entry.text.isEmpty || section != "Emoji" ? nil : InsertAssets.emoji.variants(entry.text).filter { InsertAssets.hasGlyph($0.text) }.map { e in UIAccessibilityCustomAction(name: e.name) { [weak self] _ in self?.use(e); return true } }
        return cell
    }
    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        let entry = section == "Emoji" ? shown(symbols[indexPath.item]) : symbols[indexPath.item]; if !entry.text.isEmpty { use(entry) }
    }
    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize {
        if section == "Emoji" { return CGSize(width: tablet ? 56 : 48, height: symbols[indexPath.item].text.isEmpty ? 32 : tablet ? 56 : 48) }
        let w = (symbols[indexPath.item].text as NSString).size(withAttributes: [.font: UIFont.systemFont(ofSize: 16)]).width + 24
        return CGSize(width: min(collectionView.bounds.width, max(64, ceil(w))), height: 48)
    }
    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        if scrollView === collection, section == "Emoji", let index = collection.indexPathsForVisibleItems.min(by: { $0.item < $1.item }), symbols.indices.contains(index.item) {
            category = symbols[index.item].category; markCategories()
        }
    }
    private func use(_ entry: InsertSymbol) { onInsert?(entry.text); repository.used(section, entry.text) }
    @objc private func holdEmoji(_ gesture: UILongPressGestureRecognizer) {
        guard section == "Emoji", mode == nil else { return }
        if gesture.state == .began {
            guard let index = collection.indexPathForItem(at: gesture.location(in: collection)) else { return }
            toneEntries = InsertAssets.emoji.variants(symbols[index.item].text).filter { InsertAssets.hasGlyph($0.text) }; guard toneEntries.count > 1 else { return }
            tonePopover?.removeFromSuperview(); let pop = stack(.vertical); let row = stack(); row.spacing = 0; row.distribution = .fillEqually
            let tone = repository.prefs.integer(forKey: InsertPrefs.tone)
            pop.addArrangedSubview(label("Skin tone: \(InsertPrefs.toneNames[min(max(0, tone), 5)]) (your default)", size: 12))
            let defaultText = InsertAssets.emoji.toned(symbols[index.item], tone).text
            for entry in toneEntries { row.addArrangedSubview(button(entry.text + (entry.text == defaultText ? " ✓" : ""), label: entry.name + (entry.text == defaultText ? ", your default, checked" : ""), selected: entry.text == defaultText) { [weak self] in self?.use(entry); self?.tonePopover?.removeFromSuperview() }) }
            pop.addArrangedSubview(row); pop.backgroundColor = palette.key; pop.layer.cornerRadius = CGFloat(settings.int(Prefs.cornerRadius)); pop.layer.shadowOpacity = 0.2; pop.layer.shadowRadius = 8
            let point = gesture.location(in: self); pop.frame = CGRect(x: min(max(8, point.x - 144), max(8, bounds.width - 296)), y: max(56, point.y - 100), width: 288, height: 96)
            addSubview(pop); tonePopover = pop; toneRow = row; toneChoice = -1
        } else if gesture.state == .changed, let row = toneRow {
            let point = gesture.location(in: row); toneChoice = row.bounds.contains(point) ? min(toneEntries.count - 1, max(0, Int(point.x / 48))) : -1
            for (i, view) in row.arrangedSubviews.enumerated() { view.backgroundColor = i == toneChoice ? palette.pressed : palette.key }
        } else if gesture.state == .ended { if toneEntries.indices.contains(toneChoice) { use(toneEntries[toneChoice]) }; tonePopover?.removeFromSuperview() }
        else if gesture.state == .cancelled { tonePopover?.removeFromSuperview() }
    }
    private func prepareKeys() {
        if savedKeys == nil { savedKeys = (inputState.layer, inputState.shift, inputState.capsLock) }
        inputState.layer = .abc; inputState.shift = false; inputState.capsLock = false
    }
    private func restoreKeys() {
        if let saved = savedKeys { inputState.layer = saved.0; inputState.shift = saved.1; inputState.capsLock = saved.2 }; savedKeys = nil; session += 1
    }
    private func search() { prepareKeys(); mode = "Search"; caret = query.count; session += 1; renderEditor() }
    private func edit(_ item: ClipItem? = nil) {
        prepareKeys()
        editItem = item; let draft = noteDrafts[item?.id ?? "new"]
        draftTitle = draft?.0 ?? item?.title ?? ""; draftText = draft?.1 ?? item?.text ?? ""
        titleFocused = false; caret = draftText.count; mode = "Note"; session += 1; renderEditor()
    }
    private func retainDraft() { if mode == "Note" { noteDrafts[editItem?.id ?? "new"] = (draftTitle, draftText) } }
    private func renderEditor() {
        renderedEditor = true
        reset(); backgroundColor = .clear; main.backgroundColor = palette.background
        let height: CGFloat = mode == "Note" ? (tablet ? 104 : 152) : tablet ? 56 : 104; onTyping?(true, height)
        let row = stack(); row.heightAnchor.constraint(equalToConstant: 48).isActive = true
        row.addArrangedSubview(button(mode == "Search" ? "‹" : "Cancel", label: "Back to \(section.lowercased())") { [weak self] in guard let self = self else { return }; self.retainDraft(); self.restoreKeys(); self.mode = nil; self.session += 1; self.onTyping?(false, 0); self.main.backgroundColor = .clear; self.render() })
        main.addArrangedSubview(row)
        if mode == "Search" {
            searchLabel = button("⌕  \(query)│", label: "Search \(section.lowercased()), \(query)", selected: true) {}
            row.addArrangedSubview(searchLabel!); row.addArrangedSubview(button("×", label: "Clear search") { [weak self] in self?.query = ""; self?.caret = 0; self?.updateEditor() })
            let results = UIScrollView(); resultScroll = results
            if tablet { row.addArrangedSubview(results) } else { main.addArrangedSubview(results); results.heightAnchor.constraint(equalToConstant: 48).isActive = true }
            updateEditor()
        } else {
            row.addArrangedSubview(label(editItem == nil ? "New note" : "Edit note"))
            row.addArrangedSubview(button("✓ Save", selected: true) { [weak self] in
                guard let self = self else { return }; self.repository.change { store in
                    if let item = self.editItem { store.edit(item.id, text: self.draftText, title: self.draftTitle) } else { store.note(self.draftTitle, self.draftText) }
                }; self.noteDrafts.removeValue(forKey: self.editItem?.id ?? "new"); self.draftText = ""; self.draftTitle = ""; self.restoreKeys(); self.mode = nil; self.editItem = nil; self.session += 1; self.onTyping?(false, 0); self.render()
            })
            titleLabel = button("", label: "Note title") { [weak self] in guard let self = self else { return }; self.titleFocused = true; self.caret = self.draftTitle.count; self.session += 1; self.updateEditor() }
            textLabel = button("", label: "Note text") { [weak self] in guard let self = self else { return }; self.titleFocused = false; self.caret = self.draftText.count; self.session += 1; self.updateEditor() }
            if tablet { let fields = stack(); fields.distribution = .fillEqually; fields.addArrangedSubview(titleLabel!); fields.addArrangedSubview(textLabel!); main.addArrangedSubview(fields) }
            else { main.addArrangedSubview(titleLabel!); main.addArrangedSubview(textLabel!) }; updateEditor()
        }
        setNeedsLayout()
    }
    private func updateEditor() {
        if mode == "Note" {
            titleLabel?.setTitle("Title: \(titleFocused ? withCaret(draftTitle) : draftTitle)  \(draftTitle.count)/40", for: .normal)
            textLabel?.setTitle("Text: \(!titleFocused ? withCaret(draftText) : draftText)  \(draftText.count)/500", for: .normal)
            titleLabel?.layer.borderWidth = titleFocused ? 2 : 0; titleLabel?.layer.borderColor = palette.accent.cgColor
            textLabel?.layer.borderWidth = titleFocused ? 0 : 2; textLabel?.layer.borderColor = palette.accent.cgColor
            titleLabel?.accessibilityValue = draftTitle; textLabel?.accessibilityValue = draftText; return
        }
        searchLabel?.setTitle("⌕  \(withCaret(query))", for: .normal); searchLabel?.accessibilityValue = query
        guard let scroll = resultScroll else { return }; scroll.subviews.forEach { $0.removeFromSuperview() }
        let row = stack(); let entries = section == "Emoji" ? InsertAssets.emoji.search(query).filter { InsertAssets.hasGlyph($0.text) } : InsertAssets.kaomoji.filter { ($0.name + " " + $0.category).localizedCaseInsensitiveContains(query) || query.isEmpty }
        if section == "Clipboard" {
            for item in repository.searchableItems(fullAccess: fullAccess()).filter({ ($0.title + $0.text).localizedCaseInsensitiveContains(query) || query.isEmpty }).prefix(30) {
                row.addArrangedSubview(button(String(item.text.prefix(24)), label: item.text) { [weak self] in self?.onInsert?(item.text) })
            }
        } else { for entry in entries.prefix(40) {
            let e = section == "Emoji" ? shown(entry) : entry
            let b = button(e.text, label: e.name) { [weak self] in self?.use(e) }; b.titleLabel?.font = .systemFont(ofSize: section == "Emoji" ? 28 : 16); row.addArrangedSubview(b)
        } }
        if row.arrangedSubviews.isEmpty { row.addArrangedSubview(label("No \(section.lowercased()) for “\(query)”", size: 12)) }
        scroll.addSubview(row); row.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([row.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor), row.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor), row.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor), row.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor), row.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor)])
    }
    @discardableResult func typeText(_ text: String) -> Bool {
        guard acceptsText else { return false }
        var value = mode == "Search" ? query : titleFocused ? draftTitle : draftText
        let index = value.index(value.startIndex, offsetBy: min(caret, value.count)); value.insert(contentsOf: text, at: index); caret += text.count
        if mode == "Search" { query = value } else if titleFocused { draftTitle = String(value.prefix(40)) } else { draftText = String(value.prefix(500)) }
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
    private func renderClipboard() {
        let store = repository.store(); repository.save(store)
        if !tablet {
            let tabs = stack(); tabs.distribution = .fillEqually
            tabs.addArrangedSubview(button("⌕", label: "Search clips") { [weak self] in self?.search() })
            tabs.addArrangedSubview(button("Clipboard \(store.clips.count)", selected: !notes) { [weak self] in self?.notes = false; self?.render() })
            tabs.addArrangedSubview(button("Notes \(store.notes.count)", selected: notes) { [weak self] in self?.notes = true; self?.render() }); main.addArrangedSubview(tabs)
        }
        let columns = stack(); columns.distribution = .fillEqually
        if tablet || !notes { columns.addArrangedSubview(clipboardColumn(store, notes: false)) }
        if tablet || notes { columns.addArrangedSubview(clipboardColumn(store, notes: true)) }
        main.addArrangedSubview(columns)
        let foot = stack(); foot.addArrangedSubview(label("Items stay on this device and clear after \(repository.keep.title). Pins and notes stay until deleted.", size: 11))
        let settings = button("Settings") {}; settings.isAccessibilityElement = false
        let link = settingsLinkController.view!; link.backgroundColor = .clear; link.translatesAutoresizingMaskIntoConstraints = false; settings.addSubview(link)
        NSLayoutConstraint.activate([link.leadingAnchor.constraint(equalTo: settings.leadingAnchor), link.trailingAnchor.constraint(equalTo: settings.trailingAnchor), link.topAnchor.constraint(equalTo: settings.topAnchor), link.bottomAnchor.constraint(equalTo: settings.bottomAnchor)])
        foot.addArrangedSubview(settings); main.addArrangedSubview(foot); footer = foot
    }
    private func clipboardColumn(_ store: ClipboardStore, notes: Bool) -> UIView {
        let column = stack(.vertical); let header = stack()
        header.addArrangedSubview(label(notes ? "Notes" : "Clipboard"))
        header.addArrangedSubview(button(notes ? "+ New" : "Clear") { [weak self] in
            guard let self = self else { return }; if notes { self.edit() } else { self.repository.change { $0.clear() }; self.render() }
        }); column.addArrangedSubview(header)
        let list = stack(.vertical)
        let enabled = repository.prefs.boolDefault(InsertPrefs.history, false)
        if !notes && !fullAccess() { list.addArrangedSubview(label("Turn on Full Access to keep clipboard history. Notes still work.")) }
        else if !notes && !enabled {
            list.addArrangedSubview(label("Clipboard history is off. Turn it on to keep what you copy for a while. Notes still work."))
            list.addArrangedSubview(button("Turn on") { [weak self] in self?.repository.prefs.set(true, forKey: InsertPrefs.history); self?.render() })
        } else if !notes {
            if !repository.prefs.bool(forKey: "insert_first_use") {
                list.addArrangedSubview(button("History stays on this device · Got it") { [weak self] in self?.repository.prefs.set(true, forKey: "insert_first_use"); self?.render() })
            }
            list.addArrangedSubview(button("Read clipboard", label: "Read clipboard. iOS may ask permission to paste.") { [weak self] in
                guard let self = self, self.fullAccess(), !self.password(), self.repository.prefs.bool(forKey: InsertPrefs.history) else { return }
                if let text = UIPasteboard.general.string { self.repository.change { $0.capture(text, enabled: true, sensitive: false, password: self.password(), fullAccess: self.fullAccess()) } }; self.render()
            })
        }
        let items = notes ? store.notes : enabled && fullAccess() ? store.clips : []
        if items.isEmpty && (notes || enabled && fullAccess()) { list.addArrangedSubview(label(notes ? "No notes yet. Write one with + New, or hold a clip and Save as note." : "Nothing copied yet. Read clipboard to add copied text.")) }
        var pinSection: Bool?
        for item in items {
            if !notes && pinSection != item.pinned { pinSection = item.pinned; list.addArrangedSubview(label(item.pinned ? "Pinned" : "Recent", size: 12)) }
            let age = RelativeDateTimeFormatter().localizedString(for: Date(timeIntervalSince1970: item.at), relativeTo: Date())
            let text = (item.pinned ? "⌖ " : "") + (notes ? item.title + "\n" + item.text : item.text + "\n" + age)
            let b = button(text, label: item.title + ", " + item.text + ", " + age) { [weak self] in self?.onInsert?(item.text) }
            b.titleLabel?.numberOfLines = 3; b.titleLabel?.lineBreakMode = .byTruncatingTail; b.contentHorizontalAlignment = .leading; b.backgroundColor = palette.key
            b.menu = UIMenu(children: clipActions(item)); b.showsMenuAsPrimaryAction = false
            b.accessibilityCustomActions = clipActions(item).map { action in UIAccessibilityCustomAction(name: action.title) { [weak self] _ in self?.perform(action.title, item: item); return true } }
            list.addArrangedSubview(b)
        }
        column.addArrangedSubview(scroll(list)); return column
    }
    private func clipActions(_ item: ClipItem) -> [UIAction] {
        ([item.pinned ? "Unpin" : "Pin", "Edit"] + (item.note ? [] : ["Save as note"]) + ["Delete"]).map { title in
            UIAction(title: title, attributes: title == "Delete" ? .destructive : []) { [weak self] _ in self?.perform(title, item: item) }
        }
    }
    private func perform(_ action: String, item: ClipItem) {
        switch action {
        case "Pin", "Unpin": repository.change { $0.pin(item.id) }; render()
        case "Edit": edit(item)
        case "Save as note": repository.change { $0.saveAsNote(item.id) }; render()
        case "Delete":
            let store = repository.store(); store.delete(item.id); repository.save(store); render()
            let undo = button("\(item.note ? "Note" : "Clip") deleted · Undo") { [weak self] in guard let self = self else { return }; if store.undo() { self.repository.change { $0.restore(item) } }; self.render() }
            footer?.addArrangedSubview(undo); DispatchQueue.main.asyncAfter(deadline: .now() + 6) { [weak undo] in undo?.removeFromSuperview() }
        default: break
        }
    }
    private func renderDictate() {
        let col = stack(.vertical); col.backgroundColor = palette.key; col.layer.cornerRadius = CGFloat(settings.int(Prefs.cornerRadius)); col.layoutMargins = UIEdgeInsets(top: 20, left: 20, bottom: 12, right: 20); col.isLayoutMarginsRelativeArrangement = true
        let icon = UIImageView(image: UIImage(systemName: "mic")); icon.tintColor = palette.hint; icon.contentMode = .scaleAspectFit; icon.heightAnchor.constraint(equalToConstant: 48).isActive = true; col.addArrangedSubview(icon)
        col.addArrangedSubview(label("Dictation is built into \(tablet ? "iPad" : "iPhone")", size: 18))
        col.addArrangedSubview(label("Apple does not let keyboards use the microphone. Tap the mic below the keyboard to talk. Your words appear in the field like any typing."))
        col.addArrangedSubview(label("No mic? Turn on Dictation in Settings, General, Keyboard.", size: 12))
        let arrow = label("↓", size: 32); arrow.textColor = palette.accent; arrow.textAlignment = .right; arrow.accessibilityLabel = "Use the system microphone below this keyboard"; col.addArrangedSubview(arrow); main.addArrangedSubview(col)
    }
}

private final class SymbolCell: UICollectionViewCell {
    let text = UILabel()
    override init(frame: CGRect) { super.init(frame: frame); text.textAlignment = .center; contentView.addSubview(text) }
    required init?(coder: NSCoder) { fatalError("init(coder:)") }
    override func layoutSubviews() { super.layoutSubviews(); text.frame = contentView.bounds }
}

struct InsertSettingsLink: View {
    var body: some View {
        Link(destination: URL(string: "tailorkeys://settings?panel=clipboard")!) {
            Rectangle().fill(Color.clear).contentShape(Rectangle()).frame(maxWidth: .infinity, maxHeight: .infinity)
        }.buttonStyle(.plain).accessibilityLabel("Clipboard and notes settings")
    }
}
