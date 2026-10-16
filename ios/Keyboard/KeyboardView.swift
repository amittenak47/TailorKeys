import UIKit

protocol KeyboardViewDelegate: AnyObject {
    func keyTapped(_ code: String)
    func longPressText(_ text: String)
    func stackFace(_ code: String)
    func suggestionPicked(_ text: String)
    func spaceLongPressed()
    func openSettings()
    func moveCursor(_ steps: Int)
    func gridEdited()
}

/// Draws the suggestion wheels and the key grid, and turns touches into key presses.
/// A port of the Android `KeyboardView`: same layout math, same swipe keys, same drag-and-drop editor.
/// While rearranging, the suggestion bar becomes a toolbar: undo, a hint for the current drag, settings and Done.
final class KeyboardView: UIView {

    weak var delegate: KeyboardViewDelegate?
    var state = InputState() { didSet { rebuildAccessibilityElements(); setNeedsDisplay() } }
    var keyWeights: [Character: Double] = [:] { didSet { setNeedsDisplay() } }
    /// Setting a new grid from outside clears the undo history; `undo()` and `replaceGrid(_:)` keep it.
    var grid: KeyGrid { didSet { if !keepHistory { history.removeAll() }; cancelTouches(); relayout() } }
    var enterLabel: String? { didSet { rebuildAccessibilityElements(); setNeedsDisplay() } }
    var editMode = false { didSet { if editMode != oldValue { editHint = nil; cancelTouches(); updateWiggle(); relayout() } } }

    private var history: [String] = []
    private var keepHistory = false
    private var editHint: String?
    private static let maxUndo = 50
    /// Called with the keyboard's height whenever the layout changes.
    var onHeightChange: ((CGFloat) -> Void)?

    private let settings: KeyboardSettings
    private var sugLeft: [String] = []
    private var sugRight: [String] = []
    private var prefix = ""

    func setSuggestions(left: [String], right: [String], prefix: String) {
        sugLeft = left; sugRight = right; self.prefix = prefix
        for w in wheels { w.index = 0; w.dragDx = 0 }
        rebuildAccessibilityElements()
        setNeedsDisplay()
    }

    private final class Placed {
        let item: GridKey; let rect: CGRect; let t: CGAffineTransform; let inv: CGAffineTransform
        init(item: GridKey, rect: CGRect, t: CGAffineTransform) { self.item = item; self.rect = rect; self.t = t; inv = t.inverted() }
    }
    private struct Cell { let r: Int; let c: Int; let rect: CGRect; let t: CGAffineTransform; let inv: CGAffineTransform }
    private final class Wheel { let side: Int; var rect = CGRect.zero; var index = 0; var dragDx: CGFloat = 0; init(side: Int) { self.side = side } }

    private var placed: [Placed] = []
    private var cells: [Cell] = []
    private let wheels = [Wheel(side: 0), Wheel(side: 1)]
    private var handleRect = CGRect.zero
    private var cursorRect = CGRect.zero
    private var gearRect = CGRect.zero
    private var undoRect = CGRect.zero
    private var hintRect = CGRect.zero
    private var moreRect = CGRect.zero
    private var doneRect = CGRect.zero
    private var leftSpace: CGRect?
    private var rightSpace: CGRect?
    private var stripH: CGFloat = 0
    private var unit: CGFloat = 0
    private var builtWidth: CGFloat = -1
    private var restoreWidth: Int?
    private var palette: Palette
    private var displayLink: CADisplayLink?

    private static let raise: [[CGFloat]] = [[0, 0.22, 0.38, 0.28, 0.16], [0.16, 0.28, 0.38, 0.22, 0]]
    private static let ang: [[CGFloat]] = [[-9, -4, 0, 3, 6], [-6, -3, 0, 4, 9]]

    init(settings: KeyboardSettings, grid: KeyGrid) {
        self.settings = settings
        self.grid = grid
        palette = Palette.of(settings.theme, dark: false)
        super.init(frame: .zero)
        isMultipleTouchEnabled = true
        isAccessibilityElement = false
        isOpaque = true
        contentMode = .redraw
        palette = Palette.of(settings.theme, dark: traitCollection.userInterfaceStyle == .dark)
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    private var isLandscape: Bool { let b = UIScreen.main.bounds; return b.width > b.height }

    func reloadSettings() {
        palette = Palette.of(settings.theme, dark: traitCollection.userInterfaceStyle == .dark)
        relayout()
    }

    func relayout() {
        builtWidth = -1
        let w = bounds.width > 0 ? bounds.width : UIScreen.main.bounds.width
        onHeightChange?(layoutFor(w))
        setNeedsDisplay()
    }

    func height(forWidth w: CGFloat) -> CGFloat { layoutFor(w) }

    override func layoutSubviews() {
        super.layoutSubviews()
        if builtWidth != bounds.width { layoutFor(bounds.width) }
        setNeedsDisplay()
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        reloadSettings()
    }

    // MARK: - Layout

    private func rotateAbout(_ a: CGFloat, _ px: CGFloat, _ py: CGFloat) -> CGAffineTransform {
        CGAffineTransform(translationX: -px, y: -py).concatenating(CGAffineTransform(rotationAngle: a)).concatenating(CGAffineTransform(translationX: px, y: py))
    }

    /// Places every key for this width and returns the keyboard height.
    @discardableResult
    private func layoutFor(_ width: CGFloat) -> CGFloat {
        let accessibilityLayoutChanged = builtWidth != width
        let p = settings.profile(isLandscape)
        let screenH = UIScreen.main.bounds.height
        let split = p.split
        let curve: CGFloat = (editMode || !split) ? 0 : CGFloat(settings.int(Prefs.curve)) / 100
        let tilt: CGFloat = (editMode || !split) ? 0 : CGFloat(p.tilt)
        let spaceTilt: CGFloat = (editMode || !split) ? 0 : CGFloat(p.spaceTilt)
        let th = Double(tilt) * Double.pi / 180
        let n = grid.rows
        let gap = CGFloat(settings.int(Prefs.keyGap))
        stripH = screenH < 500 ? 34 : 40
        let handleH: CGFloat = 14
        let pad: CGFloat = 4
        let bottomPad = CGFloat(p.bottomPadding)
        let target = Swift.min(screenH * CGFloat(p.heightPercent) / 100, screenH * 0.72)
        let areaW = width * CGFloat(p.widthPercent) / 100
        let areaLeft: CGFloat
        switch p.align {
        case .left: areaLeft = 0
        case .center: areaLeft = (width - areaW) / 2
        case .right: areaLeft = width - areaW
        }
        let innerW = areaW - 2 * pad
        let innerLeft = areaLeft + pad
        let gapPx = split ? innerW * CGFloat(p.gap) / 100 : 0
        let half = split ? (innerW - gapPx) / 2 : innerW
        let strip = stripH

        struct Sol {
            let un: CGFloat; let hc: CGFloat; let cw: CGFloat; let pv: CGFloat; let disp: CGFloat; let padTop: CGFloat
            var letterY: CGFloat = 0; var spaceY: CGFloat = 0; var span: CGFloat = 0
        }
        func total(_ s: Sol) -> CGFloat { strip + s.padTop + s.hc + handleH + bottomPad }

        // Fit each tilted half inside its side: outer edge on the screen edge, never past the center line.
        func solve(_ un: CGFloat, angle: Double, bend: CGFloat, fixedWidth: CGFloat? = nil) -> Sol {
            let hc = un * CGFloat(n)
            let raise = split ? 0.38 * bend * un : 0
            let hf = Double(hc + raise)
            var cw = fixedWidth ?? half
            var pv = half
            if split {
                func rot(_ w: CGFloat) -> (CGFloat, CGFloat) {
                    var mn = 0.0
                    var mx = 0.0
                    for (px, py) in [(-Double(w), 0.0), (-Double(w), -hf), (0.0, -hf), (0.0, 0.0)] {
                        let x = px * cos(angle) - py * sin(angle)
                        mn = Swift.min(mn, x); mx = Swift.max(mx, x)
                    }
                    return (CGFloat(-mn), CGFloat(mx))
                }
                var i = 0
                while fixedWidth == nil && i < 2000 {
                    let (a, b) = rot(cw)
                    if a <= half + 0.5 && a + b <= innerW / 2 - 3 { break }
                    cw -= 1
                    if cw < half * 0.2 { break }
                    i += 1
                }
                pv = rot(cw).0
            }
            let disp = split ? ceil(cw * CGFloat(abs(sin(angle)))) : 0
            return Sol(un: un, hc: hc, cw: cw, pv: pv, disp: disp, padTop: pad + disp + raise)
        }

        // Fit tilt, curve and all rows within the selected height, keeping a point of room when shrinking.
        func fit(_ angle: Double, _ bend: CGFloat) -> Sol {
            var s = solve(Swift.max(14, (target - strip - handleH - pad - bottomPad) / CGFloat(n)), angle: angle, bend: bend)
            for _ in 0..<24 {
                if total(s) <= target { break }
                s = solve(Swift.max(14, s.un - (total(s) - target + 1) / (CGFloat(n) + 0.5)), angle: angle, bend: bend)
            }
            return s
        }
        // Bounds include every key's fan rotation, even wide keys, at any smaller curve.
        func blocks(_ un: CGFloat, _ cw: CGFloat, _ angle: Double, _ spaceAngle: Double, _ bend: CGFloat) -> Sol {
            let letterH = un * CGFloat(n - 1)
            var fanX: CGFloat = 0
            var fanY: CGFloat = 0
            for item in grid.items where item.r < n - 1 && item.c != KeyGrid.center {
                let side = item.c < KeyGrid.center ? 0 : 1
                let lc = side == 0 ? item.c : item.c - 6
                let a = Double(abs(KeyboardView.ang[side][lc]) * bend) * Double.pi / 180
                fanX = Swift.max(fanX, Swift.max(0, un - gap) * CGFloat(sin(a)) / 2)
                fanY = Swift.max(fanY, Swift.max(0, CGFloat(item.w) * cw / 5 - gap) * CGFloat(sin(a)) / 2)
            }
            func bounds(_ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat, _ a: Double) -> CGRect {
                var minX = CGFloat.infinity, minY = CGFloat.infinity
                var maxX = -CGFloat.infinity, maxY = -CGFloat.infinity
                for (x, y) in [(x0, y0), (x0, y1), (x1, y0), (x1, y1)] {
                    let rx = x * CGFloat(cos(a)) - y * CGFloat(sin(a))
                    let ry = x * CGFloat(sin(a)) + y * CGFloat(cos(a))
                    minX = Swift.min(minX, rx); maxX = Swift.max(maxX, rx)
                    minY = Swift.min(minY, ry); maxY = Swift.max(maxY, ry)
                }
                return CGRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
            }
            let letters = bounds(-cw + gap / 2 - fanX, -letterH + gap / 2 - 0.38 * bend * un - fanY,
                                 -gap / 2 + fanX, -gap / 2 + fanY, angle)
            let space = bounds(-cw + gap / 2, gap / 2, -gap / 2, un - gap / 2, spaceAngle)
            // Separate the complete rotated bounds by the normal gap, including opposing tilts.
            let contentH = letters.height + gap + space.height
            let hc = Swift.max(CGFloat(n) * un, contentH)
            let extra = (hc - contentH) / 2
            let left = Swift.min(letters.minX, space.minX)
            let right = Swift.max(letters.maxX, space.maxX)
            return Sol(un: un, hc: hc, cw: cw, pv: -left, disp: 0, padTop: pad,
                       letterY: -letters.minY - letterH + extra,
                       spaceY: letters.height + gap - space.minY + extra, span: right - left)
        }
        let reserve = split && !editMode
        let s: Sol
        if reserve {
            // Fit both ranges together at maximum curve; neither live angle changes key dimensions.
            let range = Prefs.tilt(isLandscape)
            let spaceRange = Prefs.spaceTilt(isLandscape)
            let angles = [range.min, range.max].map { Double($0) * Double.pi / 180 }
            let spaceAngles = [spaceRange.min, spaceRange.max].map { Double($0) * Double.pi / 180 }
            func worst(_ un: CGFloat) -> Sol {
                var lo = gap
                var hi = half
                for _ in 0..<20 {
                    let cw = (lo + hi) / 2
                    if angles.allSatisfy({ a in spaceAngles.allSatisfy { b in blocks(un, cw, a, b, 1).span <= half } }) { lo = cw } else { hi = cw }
                }
                return angles.flatMap { a in spaceAngles.map { b in blocks(un, lo, a, b, 1) } }.max { total($0) < total($1) }!
            }
            var lo: CGFloat = 14
            var hi = Swift.max(lo, (target - strip - handleH - pad - bottomPad) / CGFloat(n))
            for _ in 0..<20 {
                let un = (lo + hi) / 2
                if total(worst(un)) <= target { lo = un } else { hi = un }
            }
            let reserved = worst(lo)
            s = blocks(reserved.un, reserved.cw, th, Double(spaceTilt) * Double.pi / 180, curve)
        } else {
            s = fit(th, curve)
        }
        unit = s.un
        // Split: key size comes from the worst-case tilt (so keys never resize), but the frame fits the keys at the
        // current tilt, so there are no empty bands above and below them.
        let y0 = strip + s.padTop + (reserve ? 0 : Swift.max(0, target - total(s)))
        let hc = s.hc

        struct Cont { let c0: Int; let cols: Int; let u: CGFloat; let side: Int; let base: CGAffineTransform; var spaceBase: CGAffineTransform? = nil }
        var conts: [Cont] = []
        let showCenter = editMode || grid.items.contains { $0.c == KeyGrid.center && $0.r < n }
        if !split {
            let u = innerW / (showCenter ? 11 : 10)
            conts.append(Cont(c0: 0, cols: 5, u: u, side: -1, base: CGAffineTransform(translationX: innerLeft, y: y0)))
            if showCenter { conts.append(Cont(c0: 5, cols: 1, u: u, side: -1, base: CGAffineTransform(translationX: innerLeft + 5 * u, y: y0))) }
            conts.append(Cont(c0: 6, cols: 5, u: u, side: -1, base: CGAffineTransform(translationX: innerLeft + (showCenter ? 6 : 5) * u, y: y0)))
        } else if reserve {
            let letterH = unit * CGFloat(n - 1)
            let lx = innerLeft + s.pv - s.cw
            let rx = innerLeft + half + gapPx + half - s.pv
            func base(_ x: CGFloat, _ side: Int, _ space: Bool) -> CGAffineTransform {
                let top = y0 + (space ? s.spaceY - letterH : s.letterY)
                let pivotY = space ? y0 + s.spaceY : top + letterH
                let angle = (space ? spaceTilt : tilt) * (side == 0 ? 1 : -1) * CGFloat.pi / 180
                return CGAffineTransform(translationX: x, y: top).concatenating(rotateAbout(angle, x + (side == 0 ? s.cw : 0), pivotY))
            }
            conts.append(Cont(c0: 0, cols: 5, u: s.cw / 5, side: 0, base: base(lx, 0, false), spaceBase: base(lx, 0, true)))
            let cwc = Swift.max(10, Swift.min(s.cw / 5, gapPx - 4))
            conts.append(Cont(c0: 5, cols: 1, u: cwc, side: -1, base: CGAffineTransform(translationX: innerLeft + half + (gapPx - cwc) / 2, y: y0 + (hc - CGFloat(n) * unit) / 2)))
            conts.append(Cont(c0: 6, cols: 5, u: s.cw / 5, side: 1, base: base(rx, 1, false), spaceBase: base(rx, 1, true)))
        } else {
            let a = tilt * CGFloat.pi / 180
            let lx = innerLeft + (s.pv - s.cw)
            var lb = CGAffineTransform(translationX: lx, y: y0).concatenating(rotateAbout(a, lx + s.cw, y0 + hc))
            if tilt < 0 { lb = lb.concatenating(CGAffineTransform(translationX: 0, y: -s.disp)) }
            conts.append(Cont(c0: 0, cols: 5, u: s.cw / 5, side: 0, base: lb))
            let cwc = Swift.max(10, Swift.min(s.cw / 5, gapPx - 4))
            conts.append(Cont(c0: 5, cols: 1, u: cwc, side: -1, base: CGAffineTransform(translationX: innerLeft + half + (gapPx - cwc) / 2, y: y0)))
            let rx = innerLeft + half + gapPx + (half - s.pv)
            var rb = CGAffineTransform(translationX: rx, y: y0).concatenating(rotateAbout(-a, rx, y0 + hc))
            if tilt < 0 { rb = rb.concatenating(CGAffineTransform(translationX: 0, y: -s.disp)) }
            conts.append(Cont(c0: 6, cols: 5, u: s.cw / 5, side: 1, base: rb))
        }

        placed = []
        cells = []
        for item in grid.items where item.r < n {
            for ct in conts {
                let a = Swift.max(item.c, ct.c0)
                let b = Swift.min(item.c + item.w, ct.c0 + ct.cols)
                if b <= a { continue }
                let lc = a - ct.c0
                let rect = CGRect(x: CGFloat(lc) * ct.u + gap / 2, y: CGFloat(item.r) * unit + gap / 2, width: CGFloat(b - a) * ct.u - gap, height: unit - gap)
                var t = CGAffineTransform.identity
                // Ergonomic curve: raise the middle fingers' columns and angle each key like a fan.
                if ct.side >= 0 && curve > 0 && item.r < n - 1 {
                    let angle = KeyboardView.ang[ct.side][lc] * curve * CGFloat.pi / 180
                    t = rotateAbout(angle, rect.midX, rect.midY).concatenating(CGAffineTransform(translationX: 0, y: -KeyboardView.raise[ct.side][lc] * curve * unit))
                }
                let base = item.r == n - 1 ? (ct.spaceBase ?? ct.base) : ct.base
                placed.append(Placed(item: item, rect: rect, t: t.concatenating(base)))
            }
        }
        if editMode {
            for ct in conts { for r in 0..<n { for c in 0..<ct.cols {
                let rect = CGRect(x: CGFloat(c) * ct.u + gap / 2, y: CGFloat(r) * unit + gap / 2, width: ct.u - gap, height: unit - gap)
                cells.append(Cell(r: r, c: ct.c0 + c, rect: rect, t: ct.base, inv: ct.base.inverted()))
            } } }
        }

        if split {
            wheels[0].rect = CGRect(x: innerLeft, y: 0, width: half, height: strip)
            wheels[1].rect = CGRect(x: innerLeft + half + gapPx, y: 0, width: half, height: strip)
            gearRect = CGRect(x: innerLeft + half, y: 0, width: gapPx, height: strip)
        } else {
            wheels[0].rect = CGRect(x: innerLeft, y: 0, width: innerW / 2 - 18, height: strip)
            wheels[1].rect = CGRect(x: innerLeft + innerW / 2 + 18, y: 0, width: innerW / 2 - 18, height: strip)
            gearRect = CGRect(x: innerLeft + innerW / 2 - 18, y: 0, width: 36, height: strip)
        }
        // Toolbar while rearranging: undo, hint, settings, Done.
        let right = innerLeft + innerW
        undoRect = CGRect(x: innerLeft, y: 0, width: 48, height: strip)
        doneRect = CGRect(x: right - 64, y: 0, width: 64, height: strip)
        moreRect = CGRect(x: doneRect.minX - 44, y: 0, width: 44, height: strip)
        hintRect = CGRect(x: undoRect.maxX, y: 0, width: Swift.max(0, moreRect.minX - undoRect.maxX), height: strip)
        handleRect = CGRect(x: areaLeft, y: y0 + hc, width: areaW, height: handleH)
        cursorRect = split && !editMode ? CGRect(x: innerLeft + half, y: stripH, width: gapPx, height: handleRect.minY - stripH) : .zero
        leftSpace = areaLeft >= 40 ? CGRect(x: 0, y: strip, width: areaLeft, height: y0 + hc - strip) : nil
        rightSpace = width - (areaLeft + areaW) >= 40 ? CGRect(x: areaLeft + areaW, y: strip, width: width - areaLeft - areaW, height: y0 + hc - strip) : nil
        builtWidth = width
        rebuildAccessibilityElements(layoutChanged: accessibilityLayoutChanged)
        return ceil(reserve ? total(s) : Swift.max(target, total(s)))
    }

    /// The face a swipe key shows: the one you swiped to, unless another key switched to a different layer.
    private func shownIdx(_ item: GridKey) -> Int {
        let n = item.keys.count
        let j = item.idx % n
        let k = item.keys[j]
        if n > 1 && KeyText.isLayerKey(k) && KeyText.layerOf(k) != state.layer,
           let match = item.keys.firstIndex(where: { KeyText.isLayerKey($0) && KeyText.layerOf($0) == state.layer }) {
            return match
        }
        return j
    }

    // MARK: - Accessibility

    private final class AccessibleElement: UIAccessibilityElement {
        var activate: (() -> Bool)?
        var increment: (() -> Void)?
        var decrement: (() -> Void)?
        override func accessibilityActivate() -> Bool { activate?() ?? false }
        override func accessibilityIncrement() { increment?() }
        override func accessibilityDecrement() { decrement?() }
    }

    private var accessibleItems: [String: AccessibleElement] = [:]
    private var accessibilitySignature: [String] = []
    private var spokenLayer: Layer = .abc
    private var spokenCapsLock = false

    private func rebuildAccessibilityElements(layoutChanged: Bool = false) {
        let layerChanged = spokenLayer != state.layer
        let capsChanged = spokenCapsLock != state.capsLock
        spokenLayer = state.layer; spokenCapsLock = state.capsLock
        if layerChanged {
            let name: String
            switch state.layer { case .abc: name = "Letters"; case .num: name = "Numbers"; case .sym: name = "Symbols" }
            UIAccessibility.post(notification: .announcement, argument: name)
        }
        if capsChanged { UIAccessibility.post(notification: .announcement, argument: state.capsLock ? "Caps lock on" : "Caps lock off") }
        var signature = ["\(state.layer)", "\(state.capsLock)", "\(state.shift)", enterLabel ?? "", "\(editMode)", "\(history.isEmpty)"]
        signature.append(contentsOf: placed.map { "\(KeyText.spoken($0.item.keys[shownIdx($0.item)], state, enterLabel)):\($0.item.keys.count)" })
        signature.append(contentsOf: wheels.map { words($0).isEmpty ? "" : wordAt(words($0), $0.index) })
        guard layoutChanged || signature != accessibilitySignature else { return }
        accessibilitySignature = signature
        var items: [String: AccessibleElement] = [:]
        var ordered: [AccessibleElement] = []
        func element(_ id: String, _ frame: CGRect, _ label: String, _ traits: UIAccessibilityTraits) -> AccessibleElement {
            let e = accessibleItems[id] ?? AccessibleElement(accessibilityContainer: self)
            e.isAccessibilityElement = true
            e.accessibilityFrameInContainerSpace = frame
            e.accessibilityLabel = label
            e.accessibilityTraits = traits
            e.accessibilityValue = nil
            e.activate = nil; e.increment = nil; e.decrement = nil
            items[id] = e
            ordered.append(e)
            return e
        }
        for (index, pk) in placed.enumerated() {
            let face = KeyText.spoken(pk.item.keys[shownIdx(pk.item)], state, enterLabel)
            let label = pk.item.isStack ? "\(face), swipe key, \(pk.item.keys.count) options" : face
            let traits: UIAccessibilityTraits = pk.item.isStack ? [.keyboardKey, .adjustable] : .keyboardKey
            let e = element("key\(index)", pk.rect.applying(pk.t), label, traits)
            e.activate = { [weak self] in self?.activateAccessibleKey(index) ?? false }
            if pk.item.isStack {
                e.accessibilityValue = face
                e.increment = { [weak self] in self?.adjustAccessibleKey(index, 1) }
                e.decrement = { [weak self] in self?.adjustAccessibleKey(index, -1) }
            }
        }
        if editMode {
            let undo = element("undo", undoRect, "Undo", history.isEmpty ? [.button, .notEnabled] : .button)
            undo.activate = { [weak self] in
                guard let self = self, !self.history.isEmpty else { return false }
                self.undo()
                return true
            }
            let settings = element("settings", moreRect, "Settings", .button)
            settings.activate = { [weak self] in
                guard let self = self, let delegate = self.delegate else { return false }
                delegate.openSettings()
                return true
            }
            let done = element("done", doneRect, "Done", .button)
            done.activate = { [weak self] in
                guard let self = self else { return false }
                self.editMode = false
                return true
            }
        } else {
            for (index, wheel) in wheels.enumerated() {
                let list = words(wheel)
                let e = element("wheel\(index)", wheel.rect, index == 0 ? "More suggestions" : "Suggestions", list.isEmpty ? [.adjustable, .notEnabled] : .adjustable)
                e.accessibilityValue = list.isEmpty ? nil : wordAt(list, wheel.index)
                e.activate = { [weak self] in self?.activateAccessibleWheel(index) ?? false }
                e.increment = { [weak self] in self?.adjustAccessibleWheel(index, 1) }
                e.decrement = { [weak self] in self?.adjustAccessibleWheel(index, -1) }
            }
            let gear = element("gear", gearRect, "Rearrange keys", .button)
            gear.activate = { [weak self] in
                guard let self = self else { return false }
                self.editMode = true
                return true
            }
        }
        accessibleItems = items
        accessibilityElements = ordered
        if layoutChanged { UIAccessibility.post(notification: .layoutChanged, argument: nil) }
    }

    private func activateAccessibleKey(_ index: Int) -> Bool {
        guard placed.indices.contains(index), let delegate = delegate else { return false }
        let item = placed[index].item
        click()
        delegate.keyTapped(item.keys[shownIdx(item)])
        rebuildAccessibilityElements()
        setNeedsDisplay()
        return true
    }

    private func adjustAccessibleKey(_ index: Int, _ step: Int) {
        guard placed.indices.contains(index) else { return }
        let item = placed[index].item
        guard item.isStack else { return }
        item.idx = (shownIdx(item) + step + item.keys.count) % item.keys.count
        delegate?.stackFace(item.keys[item.idx])
        click()
        rebuildAccessibilityElements()
        setNeedsDisplay()
    }

    private func activateAccessibleWheel(_ index: Int) -> Bool {
        let wheel = wheels[index]
        let list = words(wheel)
        guard !list.isEmpty, let delegate = delegate else { return false }
        delegate.suggestionPicked(wordAt(list, wheel.index))
        rebuildAccessibilityElements()
        setNeedsDisplay()
        return true
    }

    private func adjustAccessibleWheel(_ index: Int, _ step: Int) {
        let wheel = wheels[index]
        let list = words(wheel)
        guard !list.isEmpty else { return }
        wheel.index = (wheel.index + step + list.count) % list.count
        wheel.dragDx = 0
        rebuildAccessibilityElements()
        setNeedsDisplay()
    }

    // MARK: - Drawing

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        if builtWidth != bounds.width { layoutFor(bounds.width) }
        rebuildAccessibilityElements()
        palette.background.setFill()
        ctx.fill(bounds)
        if editMode {
            drawToolbar()
            drawCells(ctx)
        } else {
            drawWheels(ctx)
            drawGear()
        }
        let radius = CGFloat(settings.int(Prefs.cornerRadius))
        let pressed = Set(trackers.values.compactMap { $0.pk?.item.id })
        let now = CACurrentMediaTime()
        for pk in placed {
            ctx.saveGState()
            ctx.concatenate(pk.t)
            if editMode {
                ctx.translateBy(x: pk.rect.midX, y: pk.rect.midY)
                ctx.rotate(by: wiggle(pk.item, now))
                ctx.translateBy(x: -pk.rect.midX, y: -pk.rect.midY)
            }
            drawKey(ctx, pk.item, pk.rect, radius, pressed.contains(pk.item.id), drag?.item === pk.item ? 0.3 : 1)
            drawDropMark(pk, radius)
            ctx.restoreGState()
        }
        drawHandle()
        if let l = leftSpace { drawArrow(l, pointsLeft: true) }
        if let r = rightSpace { drawArrow(r, pointsLeft: false) }
        if let d = drag { drawGhost(ctx, d, radius) }
    }

    private func wiggle(_ item: GridKey, _ now: CFTimeInterval) -> CGFloat {
        CGFloat(1.6 * Double.pi / 180 * sin(2 * Double.pi * now.truncatingRemainder(dividingBy: 0.32) / 0.32 + Double(item.id) * 0.9))
    }

    private func drawKey(_ ctx: CGContext, _ item: GridKey, _ rect: CGRect, _ radius: CGFloat, _ pressed: Bool, _ alpha: CGFloat) {
        let face = shownIdx(item)
        let code = item.keys[face]
        let lock = code == Keys.abc && state.layer == .abc && state.capsLock
        let oneShot = code == Keys.abc && state.layer == .abc && state.shift && !state.capsLock
        let accentBg = code == Keys.enter || lock
        let bg: UIColor
        if pressed { bg = palette.pressed }
        else if accentBg { bg = palette.accent }
        else if KeyText.isLayerKey(code) || code == Keys.backspace { bg = palette.functionKey }
        else { bg = palette.key }
        bg.withAlphaComponent(alpha).setFill()
        UIBezierPath(roundedRect: rect, cornerRadius: radius).fill()
        if settings.keyTint && state.layer == .abc && !editMode && !pressed && !item.isStack &&
           code.count == 1 && code.first!.isLetter && !keyWeights.isEmpty {
            let weight = keyWeights[code.lowercased().first!] ?? 0
            palette.accent.withAlphaComponent(CGFloat(0.22 * weight)).setFill()
            UIBezierPath(roundedRect: rect, cornerRadius: radius).fill()
        }
        let fg = (accentBg && !pressed ? palette.onAccent : (oneShot ? palette.accent : palette.text)).withAlphaComponent(alpha)

        ctx.saveGState()
        ctx.clip(to: rect)
        let dy: CGFloat = swipe?.item === item ? (swipe?.dy ?? 0) : 0
        drawFace(code, rect, dy, fg)
        if item.isStack && dy != 0 {
            let n = item.keys.count
            drawFace(item.keys[(face + 1) % n], rect, rect.height + dy, fg)
            drawFace(item.keys[(face + n - 1) % n], rect, -rect.height + dy, fg)
        }
        ctx.restoreGState()

        if item.isStack {
            let n = item.keys.count
            let step: CGFloat = 5
            let top = rect.midY - CGFloat(n - 1) * step / 2
            for i in 0..<n {
                fg.withAlphaComponent(alpha * (i == face ? 0.9 : 0.3)).setFill()
                UIBezierPath(ovalIn: CGRect(x: rect.minX + 3.5, y: top + CGFloat(i) * step - 1.5, width: 3, height: 3)).fill()
            }
        }
        if !item.isStack && grid.rows == 4 && settings.showHints, let h = KeyText.hint(code, state) {
            drawText(h, center: CGPoint(x: rect.maxX - rect.height * 0.14, y: rect.minY + rect.height * 0.16), size: rect.height * 0.2, color: palette.hint.withAlphaComponent(alpha))
        }
        if editMode && item.isStack {
            palette.accent.setFill()
            let c = CGPoint(x: rect.maxX - 8, y: rect.minY + 8)
            UIBezierPath(ovalIn: CGRect(x: c.x - 7, y: c.y - 7, width: 14, height: 14)).fill()
            palette.onAccent.setStroke()
            let x = UIBezierPath()
            x.move(to: CGPoint(x: c.x - 2.5, y: c.y - 2.5)); x.addLine(to: CGPoint(x: c.x + 2.5, y: c.y + 2.5))
            x.move(to: CGPoint(x: c.x - 2.5, y: c.y + 2.5)); x.addLine(to: CGPoint(x: c.x + 2.5, y: c.y - 2.5))
            x.lineWidth = 1.5
            x.stroke()
        }
    }

    private func drawFace(_ code: String, _ rect: CGRect, _ offset: CGFloat, _ color: UIColor) {
        let r = rect.offsetBy(dx: 0, dy: offset)
        let scale = CGFloat(settings.int(Prefs.textScale)) / 100
        let icon = Swift.min(r.width, r.height) * 0.42 * scale
        let center = CGPoint(x: r.midX, y: r.midY)
        switch code {
        case Keys.backspace: drawDelete(center, icon, color)
        case Keys.enter:
            if let l = enterLabel { drawText(l, center: center, size: r.height * 0.3 * scale, color: color, maxWidth: r.width * 0.85) }
            else { drawReturn(center, icon, color) }
        case Keys.space: break
        default:
            let label = KeyText.label(code, state)
            let size = Swift.min(r.height * 0.42, 22) * scale
            drawText(label, center: center, size: label.count > 1 ? size * 0.72 : size, color: color, maxWidth: r.width * 0.85)
        }
    }

    private func drawText(_ s: String, center: CGPoint, size: CGFloat, color: UIColor, maxWidth: CGFloat? = nil) {
        guard size > 0 else { return }
        var font = UIFont.systemFont(ofSize: size)
        var attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
        var sz = (s as NSString).size(withAttributes: attrs)
        if let mw = maxWidth, sz.width > mw, sz.width > 0 {
            font = font.withSize(size * mw / sz.width)
            attrs[.font] = font
            sz = (s as NSString).size(withAttributes: attrs)
        }
        (s as NSString).draw(at: CGPoint(x: center.x - sz.width / 2, y: center.y - sz.height / 2), withAttributes: attrs)
    }

    private func poly(_ c: CGPoint, _ s: CGFloat, _ close: Bool, _ p: [CGFloat]) -> UIBezierPath {
        let path = UIBezierPath()
        path.move(to: CGPoint(x: c.x + p[0] * s, y: c.y + p[1] * s))
        var i = 2
        while i < p.count { path.addLine(to: CGPoint(x: c.x + p[i] * s, y: c.y + p[i + 1] * s)); i += 2 }
        if close { path.close() }
        path.lineWidth = s * 0.09
        path.lineCapStyle = .round
        path.lineJoinStyle = .round
        return path
    }

    private func drawDelete(_ c: CGPoint, _ s: CGFloat, _ color: UIColor) {
        color.setStroke()
        poly(c, s, true, [-0.55, 0, -0.22, -0.38, 0.55, -0.38, 0.55, 0.38, -0.22, 0.38]).stroke()
        poly(c, s, false, [0, -0.15, 0.3, 0.15]).stroke()
        poly(c, s, false, [0, 0.15, 0.3, -0.15]).stroke()
    }

    private func drawReturn(_ c: CGPoint, _ s: CGFloat, _ color: UIColor) {
        color.setStroke()
        poly(c, s, false, [0.45, -0.4, 0.45, 0.1, -0.45, 0.1]).stroke()
        poly(c, s, false, [-0.2, -0.15, -0.45, 0.1, -0.2, 0.35]).stroke()
    }

    private func drawArrow(_ area: CGRect, pointsLeft: Bool) {
        let s = Swift.min(area.width * 0.35, 18)
        let d: CGFloat = pointsLeft ? 1 : -1
        palette.hint.setStroke()
        let path = poly(CGPoint(x: area.midX, y: area.midY), s, false, [0.25 * d, -0.5, -0.25 * d, 0, 0.25 * d, 0.5])
        path.lineWidth = s * 0.135
        path.stroke()
    }

    private func drawHandle() {
        palette.hint.setFill()
        UIBezierPath(roundedRect: CGRect(x: handleRect.midX - 22, y: handleRect.midY - 2, width: 44, height: 4), cornerRadius: 2).fill()
    }

    private func drawSymbol(_ name: String, in rect: CGRect, color: UIColor) {
        let config = UIImage.SymbolConfiguration(pointSize: 15, weight: .regular)
        guard rect.width >= 20, let img = UIImage(systemName: name, withConfiguration: config)?
            .withTintColor(color, renderingMode: .alwaysOriginal) else { return }
        img.draw(at: CGPoint(x: rect.midX - img.size.width / 2, y: rect.midY - img.size.height / 2))
    }

    private func drawGear() { drawSymbol("gearshape", in: gearRect, color: palette.hint) }

    private func drawToolbar() {
        drawSymbol("arrow.uturn.backward", in: undoRect, color: palette.text.withAlphaComponent(history.isEmpty ? 0.3 : 1))
        drawText(editHint ?? "Drag keys to rearrange", center: CGPoint(x: hintRect.midX, y: hintRect.midY), size: 13,
                 color: editHint != nil ? palette.accent : palette.hint, maxWidth: hintRect.width * 0.95)
        drawSymbol("gearshape", in: moreRect, color: palette.hint)
        let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.boldSystemFont(ofSize: 15), .foregroundColor: palette.accent]
        let size = ("Done" as NSString).size(withAttributes: attrs)
        ("Done" as NSString).draw(at: CGPoint(x: doneRect.midX - size.width / 2, y: doneRect.midY - size.height / 2), withAttributes: attrs)
    }

    private func drawCells(_ ctx: CGContext) {
        let radius = CGFloat(settings.int(Prefs.cornerRadius))
        for (i, cell) in cells.enumerated() {
            let isTarget = drag?.target?.cellIndex == i
            ctx.saveGState()
            ctx.concatenate(cell.t)
            (isTarget ? palette.accent : palette.hint.withAlphaComponent(0.5)).setStroke()
            let path = UIBezierPath(roundedRect: cell.rect, cornerRadius: radius)
            path.lineWidth = isTarget ? 2 : 1
            path.setLineDash([4, 4], count: 2, phase: 0)
            path.stroke()
            ctx.restoreGState()
        }
    }

    // MARK: - Suggestion wheels

    private let wheelFont = UIFont.systemFont(ofSize: 15)
    private func words(_ w: Wheel) -> [String] { w.side == 0 ? sugLeft : sugRight }
    private func wordAt(_ list: [String], _ i: Int) -> String { list[((i % list.count) + list.count) % list.count] }
    private func textWidth(_ s: String) -> CGFloat { (s as NSString).size(withAttributes: [.font: wheelFont]).width }

    /// Neighbours drawn on each side, never more than the list holds, so no word shows twice.
    private func wheelRange(_ n: Int) -> ClosedRange<Int> {
        let back = Swift.min(3, (n - 1) / 2)
        return -back...Swift.min(3, n - 1 - back)
    }

    /// Horizontal offset of the word `k` places from the chosen one.
    private func offsetOf(_ list: [String], _ index: Int, _ k: Int) -> CGFloat {
        let gap: CGFloat = 22
        func w(_ j: Int) -> CGFloat { textWidth(wordAt(list, index + j)) }
        var x: CGFloat = 0
        if k > 0 { for j in 0..<k { x += w(j) / 2 + gap + w(j + 1) / 2 } }
        else if k < 0 { for j in stride(from: 0, to: k, by: -1) { x -= w(j) / 2 + gap + w(j - 1) / 2 } }
        return x
    }

    private func drawWheels(_ ctx: CGContext) {
        for wh in wheels {
            let list = words(wh)
            if list.isEmpty || wh.rect.width < 10 { continue }
            ctx.saveGState()
            ctx.clip(to: wh.rect)
            let y = wh.rect.midY - wheelFont.lineHeight / 2
            for k in wheelRange(list.count) {
                let word = wordAt(list, wh.index + k)
                let cx = wh.rect.midX + offsetOf(list, wh.index, k) + wh.dragDx
                let alpha: CGFloat = k == 0 ? 1 : 0.4
                let x = cx - textWidth(word) / 2
                // The part you've already typed is highlighted.
                if !prefix.isEmpty && word.count >= prefix.count && word.lowercased().hasPrefix(prefix.lowercased()) {
                    let pre = String(word.prefix(prefix.count))
                    let rest = String(word.dropFirst(prefix.count))
                    (pre as NSString).draw(at: CGPoint(x: x, y: y), withAttributes: [.font: wheelFont, .foregroundColor: palette.accent.withAlphaComponent(alpha)])
                    (rest as NSString).draw(at: CGPoint(x: x + textWidth(pre), y: y), withAttributes: [.font: wheelFont, .foregroundColor: palette.text.withAlphaComponent(alpha)])
                } else {
                    (word as NSString).draw(at: CGPoint(x: x, y: y), withAttributes: [.font: wheelFont, .foregroundColor: palette.text.withAlphaComponent(alpha)])
                }
            }
            ctx.restoreGState()
        }
    }

    // MARK: - Touch

    private enum Kind { case key, stack, wheel, handle, gear, cursor }

    private final class Tracker {
        let kind: Kind
        var pk: Placed?
        var consumed = false
        var moved = false
        var x0: CGFloat = 0
        var y0: CGFloat = 0
        var lastX: CGFloat = 0
        var wheel: Wheel?
        var longTimer: Timer?
        var repeatTimer: Timer?
        init(kind: Kind, pk: Placed?) { self.kind = kind; self.pk = pk }
        func stop() { longTimer?.invalidate(); repeatTimer?.invalidate(); longTimer = nil; repeatTimer = nil }
    }

    private final class Swipe { let item: GridKey; var dy: CGFloat = 0; var stepped = false; init(item: GridKey) { self.item = item } }

    private var trackers: [ObjectIdentifier: Tracker] = [:]
    private var swipe: Swipe?

    func cancelTouches() {
        trackers.values.forEach { $0.stop() }
        trackers = [:]
        swipe = nil
        drag = nil
        editTouch = nil
        setNeedsDisplay()
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            let p = t.location(in: self)
            if editMode { editBegan(t, p) } else { down(ObjectIdentifier(t), p) }
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            let p = t.location(in: self)
            if editMode { editMoved(t, p) } else { move(ObjectIdentifier(t), p) }
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            if editMode { editEnded(t) } else { up(ObjectIdentifier(t)) }
        }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { cancelTouches() }

    private func startLongPress(_ t: Tracker) {
        t.longTimer?.invalidate()
        t.longTimer = Timer.scheduledTimer(withTimeInterval: Double(settings.int(Prefs.longPressMs)) / 1000, repeats: false) { [weak self, weak t] _ in
            guard let self = self, let t = t else { return }
            self.longPress(t)
        }
    }

    private func down(_ id: ObjectIdentifier, _ p: CGPoint) {
        // A new finger landing finishes keys other fingers are still holding, for fast rollover typing.
        for t in trackers.values where t.kind == .key && !t.consumed {
            t.stop(); t.consumed = true; commit(t)
        }
        if gearRect.contains(p) { trackers[id] = Tracker(kind: .gear, pk: nil); return }
        if let wh = wheels.first(where: { $0.rect.contains(p) && !words($0).isEmpty }) {
            let t = Tracker(kind: .wheel, pk: nil); t.wheel = wh; t.x0 = p.x; trackers[id] = t
            return
        }
        if handleRect.contains(p) {
            let t = Tracker(kind: .handle, pk: nil); t.x0 = p.x; t.lastX = p.x; trackers[id] = t
            return
        }
        if leftSpace?.contains(p) == true || rightSpace?.contains(p) == true { moveToward(p); return }
        let hit = findKey(p, strict: true)
        if hit == nil && cursorRect.contains(p) {
            let t = Tracker(kind: .cursor, pk: nil); t.x0 = p.x; trackers[id] = t
            setNeedsDisplay()
            return
        }
        guard let pk = findKey(p) else { return }
        click()
        if pk.item.isStack {
            let t = Tracker(kind: .stack, pk: pk); t.y0 = p.y; trackers[id] = t
            swipe = Swipe(item: pk.item)
            setNeedsDisplay()
            return
        }
        let t = Tracker(kind: .key, pk: pk)
        trackers[id] = t
        if pk.item.keys[0] == Keys.backspace {
            t.consumed = true
            delegate?.keyTapped(Keys.backspace)
            t.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self, weak t] _ in
                guard let t = t else { return }
                t.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in self?.delegate?.keyTapped(Keys.backspace) }
                _ = self
            }
        } else {
            startLongPress(t)
        }
        setNeedsDisplay()
    }

    private func move(_ id: ObjectIdentifier, _ p: CGPoint) {
        guard let t = trackers[id] else { return }
        switch t.kind {
        case .wheel:
            let dx = p.x - t.x0
            if abs(dx) > 8 { t.moved = true }
            if t.moved { t.wheel?.dragDx = dx; setNeedsDisplay() }
        case .handle:
            t.lastX = p.x
            if abs(p.x - t.x0) > 8 { t.moved = true }
        case .cursor:
            // UITextDocumentProxy has no API to select text.
            let steps = Int((p.x - t.x0) / 9)
            if steps != 0 {
                delegate?.moveCursor(steps)
                t.x0 += CGFloat(steps) * 9
                for _ in 0..<abs(steps) { click() }
            }
        case .stack:
            // One swipe moves exactly one face; the rest of the gesture is ignored.
            guard let sw = swipe, !sw.stepped, let pk = t.pk else { return }
            let dy = p.y - t.y0
            if abs(dy) > 5 { t.moved = true }
            if !t.moved { return }
            let h = pk.rect.height
            sw.dy = Swift.min(Swift.max(dy, -h), h)
            if abs(dy) > h * 0.5 {
                let item = sw.item
                let n = item.keys.count
                let j = (shownIdx(item) + (dy < 0 ? 1 : -1) + n) % n
                item.idx = j
                sw.stepped = true
                sw.dy = 0
                delegate?.stackFace(item.keys[j])
                click()
            }
            setNeedsDisplay()
        case .key:
            // Sliding across letters changes which letter is typed when the finger lifts.
            guard !t.consumed, let cur = t.pk, KeyText.isChar(cur.item.keys[0]), let pk = findKey(p), pk !== cur,
                  !pk.item.isStack, KeyText.isChar(pk.item.keys[0]) else { return }
            t.pk = pk
            startLongPress(t)
            setNeedsDisplay()
        case .gear:
            break
        }
    }

    private func up(_ id: ObjectIdentifier) {
        guard let t = trackers.removeValue(forKey: id) else { return }
        t.stop()
        switch t.kind {
        case .wheel:
            guard let wh = t.wheel else { break }
            let list = words(wh)
            if t.moved && !list.isEmpty {
                // Settle on whichever word is nearest the middle.
                var best = 0
                var bestDist = CGFloat.greatestFiniteMagnitude
                for k in wheelRange(list.count) {
                    let d = abs(offsetOf(list, wh.index, k) + wh.dragDx)
                    if d < bestDist { bestDist = d; best = k }
                }
                wh.index = ((wh.index + best) % list.count + list.count) % list.count
            } else if !list.isEmpty {
                delegate?.suggestionPicked(wordAt(list, wh.index))
            }
            wh.dragDx = 0
        case .handle:
            if t.moved && abs(t.lastX - t.x0) > 36 { slide(left: t.lastX < t.x0) }
        case .stack:
            swipe = nil
            if !t.moved, let pk = t.pk { delegate?.keyTapped(pk.item.keys[shownIdx(pk.item)]) }
        case .key:
            if !t.consumed { commit(t) }
        case .gear:
            editMode = true
        case .cursor:
            break
        }
        setNeedsDisplay()
    }

    private func commit(_ t: Tracker) {
        guard let item = t.pk?.item else { return }
        delegate?.keyTapped(item.keys[0])
    }

    private func longPress(_ t: Tracker) {
        guard let code = t.pk?.item.keys.first else { return }
        if code == Keys.space {
            delegate?.spaceLongPressed()
        } else {
            guard let alt = KeyText.longPress(code, state) else { return }
            delegate?.longPressText(alt)
        }
        t.consumed = true
    }

    /// The key under a point. Gaps and edges belong to the nearest key, so taps never fall through.
    private func findKey(_ p: CGPoint, strict: Bool = false, skip: GridKey? = nil) -> Placed? {
        let slack = CGFloat(settings.int(Prefs.keyGap)) / 2 + 1
        let hit = placed.reversed().first { $0.item !== skip && $0.rect.insetBy(dx: -slack, dy: -slack).contains(p.applying($0.inv)) }
        func isLetter(_ pk: Placed) -> Bool {
            let code = pk.item.keys[0]
            return !pk.item.isStack && code.count == 1 && code.first!.isLetter
        }
        if !strict && !editMode && state.layer == .abc && settings.smartTargets && !keyWeights.isEmpty &&
           (hit == nil || isLetter(hit!)) {
            var best: Placed?
            var bestScore = -Double.infinity
            for pk in placed where pk.item !== skip && isLetter(pk) {
                let local = p.applying(pk.inv)
                let r = pk.rect
                if r.insetBy(dx: r.width * 0.3, dy: r.height * 0.3).contains(local) { return pk }
                if !r.insetBy(dx: -r.width * 0.35, dy: -r.height * 0.35).contains(local) { continue }
                let dx = Double((local.x - r.midX) / r.width)
                let dy = Double((local.y - r.midY) / r.height)
                let weight = keyWeights[pk.item.keys[0].lowercased().first!] ?? 0
                let score = -(dx * dx + dy * dy) / (2 * 0.35 * 0.35) + log(0.05 + weight)
                if score > bestScore { bestScore = score; best = pk }
            }
            if let best = best { return best }
        }
        if let hit = hit { return hit }
        if strict || p.y < stripH { return nil }
        var best: Placed?
        var bestDist = CGFloat.greatestFiniteMagnitude
        for pk in placed where pk.item !== skip {
            let c = CGPoint(x: pk.rect.midX, y: pk.rect.midY).applying(pk.t)
            let d = hypot(c.x - p.x, c.y - p.y)
            if d < bestDist { bestDist = d; best = pk }
        }
        return bestDist < unit * 1.2 ? best : nil
    }

    private func moveToward(_ p: CGPoint) {
        let align: Align
        if leftSpace?.contains(p) == true { align = .left } else if rightSpace?.contains(p) == true { align = .right } else { return }
        click()
        settings.setAlignFor(isLandscape, align)
        relayout()
    }

    /// Dragging the bar under the keys: full width shrinks to one-hand size on that side; the other way restores it.
    private func slide(left: Bool) {
        let land = isLandscape
        let p = settings.profile(land)
        let dir: Align = left ? .left : .right
        if p.widthPercent >= 100 {
            restoreWidth = 100
            settings.setFor(land, Prefs.width, p.split ? 70 : 78)
            settings.setAlignFor(land, dir)
        } else if p.align == .center {
            settings.setAlignFor(land, dir)
        } else if p.align != dir {
            settings.setAlignFor(land, .center)
            if let w = restoreWidth { settings.setFor(land, Prefs.width, w) }
            restoreWidth = nil
        }
        relayout()
    }

    private func click() {
        if settings.clicks { UIDevice.current.playInputClick() }
    }

    // MARK: - Edit mode: drag and drop

    private enum TargetType { case move, space, combine, insert }

    private struct Target {
        let type: TargetType
        var item: GridKey? = nil
        var before = false
        var r = 0
        var c = 0
        var pk: Placed? = nil
        var cellIndex: Int? = nil
    }

    private final class Drag {
        let item: GridKey
        let size: CGSize
        var p: CGPoint
        let off: CGPoint
        var target: Target?
        init(item: GridKey, size: CGSize, p: CGPoint, off: CGPoint) { self.item = item; self.size = size; self.p = p; self.off = off }
    }

    private var drag: Drag?
    private var editTouch: UITouch?

    private func updateWiggle() {
        displayLink?.invalidate()
        displayLink = nil
        guard editMode else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    @objc private func tick() { setNeedsDisplay() }

    override func willMove(toWindow newWindow: UIWindow?) {
        super.willMove(toWindow: newWindow)
        if newWindow == nil { displayLink?.invalidate(); displayLink = nil } else { updateWiggle() }
    }

    private func editBegan(_ t: UITouch, _ p: CGPoint) {
        guard editTouch == nil else { return }
        if p.y < stripH {
            if undoRect.contains(p) { undo() }
            else if moreRect.contains(p) { delegate?.openSettings() }
            else if doneRect.contains(p) { editMode = false }
            return
        }
        guard let pk = findKey(p, strict: true) else { return }
        let q = p.applying(pk.inv)
        if pk.item.isStack && hypot(q.x - (pk.rect.maxX - 8), q.y - (pk.rect.minY + 8)) < 12 {
            let before = grid.toJSON()
            grid.separate(pk.item)
            edited(before)
            return
        }
        editTouch = t
        drag = Drag(item: pk.item, size: pk.rect.size, p: p, off: CGPoint(x: q.x - pk.rect.midX, y: q.y - pk.rect.midY))
        setNeedsDisplay()
    }

    private func editMoved(_ t: UITouch, _ p: CGPoint) {
        guard t === editTouch, let d = drag else { return }
        d.p = p
        d.target = target(p, d)
        editHint = hint(d.target)
        setNeedsDisplay()
    }

    private func editEnded(_ t: UITouch) {
        guard t === editTouch else { return }
        editTouch = nil
        guard let d = drag else { return }
        drag = nil
        editHint = nil
        let before = grid.toJSON()
        apply(d)
        edited(before)
    }

    /// Saves the layout as it was `before` an edit, so undo can return to it. Edits that changed nothing are skipped.
    private func edited(_ before: String) {
        grid.normalize()
        if grid.toJSON() != before {
            history.append(before)
            if history.count > KeyboardView.maxUndo { history.removeFirst() }
            delegate?.gridEdited()
        }
        relayout()
    }

    /// Steps back one edit.
    func undo() {
        guard let last = history.popLast(), let prev = KeyGrid.fromJSON(last) else { return }
        keepHistory = true
        grid = prev
        keepHistory = false
        delegate?.gridEdited()
    }

    /// Swaps in a whole new layout, such as a template or an imported code, as one step undo can reverse.
    func replaceGrid(_ g: KeyGrid) {
        let before = grid.toJSON()
        keepHistory = true
        grid = g
        keepHistory = false
        if g.toJSON() != before {
            history.append(before)
            delegate?.gridEdited()
        }
    }

    private func target(_ p: CGPoint, _ d: Drag) -> Target? {
        if let pk = findKey(p, strict: true, skip: d.item) {
            let item = pk.item
            if item.isSpace && item.w > 1 && KeyGrid.region(item.c) != 1 && !d.item.isSpace { return Target(type: .space, item: item, pk: pk) }
            let q = p.applying(pk.inv)
            let rel = (q.x - pk.rect.minX) / pk.rect.width
            return rel > 0.25 && rel < 0.75 ? Target(type: .combine, item: item, pk: pk) : Target(type: .insert, item: item, before: rel <= 0.25, pk: pk)
        }
        for (i, cell) in cells.enumerated() where cell.rect.contains(p.applying(cell.inv)) {
            return Target(type: .move, r: cell.r, c: cell.c, cellIndex: i)
        }
        return nil
    }

    private func hint(_ t: Target?) -> String? {
        guard let t = t else { return nil }
        switch t.type {
        case .combine: return "Combine into a swipe key"
        case .insert: return "Insert, neighbours shift over"
        case .space: return "Sit beside the spacebar"
        case .move: return "Move here"
        }
    }

    private func apply(_ d: Drag) {
        guard let t = d.target else { return }
        switch t.type {
        case .move: grid.moveTo(d.item, t.r, t.c)
        case .space: if let other = t.item { grid.insertBesideSpace(d.item, other) }
        case .combine: if let other = t.item { grid.combine(d.item, into: other) }
        case .insert: if let other = t.item, !grid.insertShift(d.item, other, before: t.before) { grid.swap(d.item, other) }
        }
    }

    private func drawDropMark(_ pk: Placed, _ radius: CGFloat) {
        guard let t = drag?.target, t.pk === pk else { return }
        let r = pk.rect
        palette.accent.setFill()
        palette.accent.setStroke()
        if t.type == .insert {
            UIRectFill(t.before ? CGRect(x: r.minX, y: r.minY, width: 4, height: r.height) : CGRect(x: r.maxX - 4, y: r.minY, width: 4, height: r.height))
        } else {
            let path = UIBezierPath(roundedRect: r, cornerRadius: radius)
            path.lineWidth = t.type == .combine ? 3 : 2
            path.stroke()
        }
    }

    private func drawGhost(_ ctx: CGContext, _ d: Drag, _ radius: CGFloat) {
        ctx.saveGState()
        ctx.translateBy(x: d.p.x - d.off.x, y: d.p.y - d.off.y)
        ctx.scaleBy(x: 1.1, y: 1.1)
        drawKey(ctx, d.item, CGRect(x: -d.size.width / 2, y: -d.size.height / 2, width: d.size.width, height: d.size.height), radius, false, 0.92)
        ctx.restoreGState()
    }
}
