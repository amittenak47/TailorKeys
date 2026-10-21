import UIKit

/// Reusable tap costs, mirrored in Android's ComposingTaps. Kept here for the app preview.
final class ComposingTaps {
    static let letters = 256
    static let maxLength = 64
    private var costs = [UInt8](repeating: 10, count: ComposingTaps.maxLength * ComposingTaps.letters)
    private var known = [Bool](repeating: false, count: ComposingTaps.maxLength)
    private var count = 0
    private(set) var word = ""
    private(set) var revision: UInt64 = 0

    func clear() { sync(""); revision &+= 1 }
    func sync(_ prefix: String) {
        if word == prefix { return }
        for i in known.indices { known[i] = false }
        count = 0
        word = prefix
        revision &+= 1
    }
    func append(_ prefix: String, _ letter: String, _ distances: [Float]?) {
        sync(prefix)
        let index = word.count
        word += letter
        if index < Self.maxLength, letter.count == 1, let distances = distances {
            // -log Gaussian, sigma 0.5 widths, two tenths per nat. Boundary credit
            // is capped so a point far from every letter never makes a letter free.
            let origin = min(0.25, distances.min() ?? 0)
            let offset = index * Self.letters
            for c in 0..<Self.letters {
                costs[offset + c] = UInt8(min(10, max(0, Int((4 * (distances[c] - origin)).rounded()))))
            }
            known[index] = true
            count += 1
        }
        revision &+= 1
    }
    func backspace() {
        guard !word.isEmpty else { return }
        let index = word.count - 1
        if index < Self.maxLength && known[index] { known[index] = false; count -= 1 }
        word.removeLast()
        revision &+= 1
    }
    func hasTaps(_ prefix: String) -> Bool { word == prefix && count > 0 }
    func hasTap(_ index: Int) -> Bool { index < Self.maxLength && known[index] }
    func cost(_ index: Int, _ letter: Character) -> Int {
        guard let code = letter.unicodeScalars.first?.value, code < UInt32(Self.letters) else { return 10 }
        return Int(costs[index * Self.letters + Int(code)])
    }
    func prefix(_ before: String) -> String {
        if word.contains(where: { $0.isNumber }) && before.hasSuffix(word) { return word }
        let suffix = before.reversed().prefix { $0.isLetter || $0 == "'" }
        return String(suffix.reversed())
    }
}

protocol KeyboardViewDelegate: AnyObject {
    func chordTyped(_ word: String)
    func keyTapped(_ code: String)
    func keyTapped(_ code: String, distances: [Float])
    func replacePressText(_ original: String, following: String, replacement: String)
    func longPressText(_ text: String)
    func stackFace(_ code: String)
    func wheelChosen()
    func choiceInterrupted()
    func suggestionPicked(_ text: String)
    func switchLanguage(_ direction: Int)
    /// The globe in the suggestion bar (only on iPhones that need one).
    func switchKeyboard()
    func openQuickOptions()
    func openSettings()
    func moveCursor(_ steps: Int)
    func gridEdited()
    func glideStarted()
    func glideProgress(_ points: [GlidePoint], _ keys: [Character: CGPoint], _ keyWidth: CGFloat, _ raw: String, _ hints: [DoubleLetterHint])
    func glideCancelled()
    func glideFinished(_ points: [GlidePoint], _ keys: [Character: CGPoint], _ keyWidth: CGFloat, _ raw: String, _ hints: [DoubleLetterHint])
}

extension KeyboardViewDelegate {
    func keyTapped(_ code: String, distances: [Float]) { keyTapped(code) }
    func openQuickOptions() {}
    func wheelChosen() {}
    func choiceInterrupted() {}
    func chordTyped(_ word: String) {}
    func replacePressText(_ original: String, following: String, replacement: String) {}
    func switchLanguage(_ direction: Int) {}
    func glideStarted() {}
    func glideProgress(_ points: [GlidePoint], _ keys: [Character: CGPoint], _ keyWidth: CGFloat, _ raw: String, _ hints: [DoubleLetterHint]) {}
    func glideCancelled() {}
    func glideFinished(_ points: [GlidePoint], _ keys: [Character: CGPoint], _ keyWidth: CGFloat, _ raw: String, _ hints: [DoubleLetterHint]) {}
}

/// Draws the suggestion wheels and the key grid, and turns touches into key presses.
/// A port of the Android `KeyboardView`: same layout math, same swipe keys, same drag-and-drop editor.
/// While rearranging, the suggestion bar becomes a toolbar: undo, a hint for the current drag, settings and Done.
final class KeyboardView: UIView {

    weak var delegate: KeyboardViewDelegate?
    var chordLibrary: Chords?
    var glideAllowed = true { didSet { if glideAllowed != oldValue { cancelTouches() } } }
    var state = InputState() { didSet { refreshInputState() } }
    var keyWeights: [Character: Double] = [:] { didSet { if keyWeights != oldValue { setNeedsDisplay() } } }
    /// Setting a new grid from outside clears the undo history; `undo()` and `replaceGrid(_:)` keep it.
    var grid: KeyGrid { didSet { if !keepHistory { history.removeAll() }; cancelTouches(); relayout() } }
    var enterLabel: String? { didSet { rebuildAccessibilityElements(); setNeedsDisplay() } }
    var editMode = false { didSet { if editMode != oldValue { clearArmedWord(); delegate?.choiceInterrupted(); editHint = nil; cancelTouches(); updateWiggle(); relayout() } } }

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

    private var armedWheel: Wheel?
    var armedWord: String? {
        guard let wheel = armedWheel, !words(wheel).isEmpty else { return nil }
        return wordAt(words(wheel), wheel.index)
    }

    func clearArmedWord() {
        guard armedWheel != nil else { return }
        armedWheel = nil
        rebuildAccessibilityElements()
        setNeedsDisplay()
    }

    private func armWheel(_ wheel: Wheel) {
        guard viewPrefs.spaceAccepts, state.layer == .abc, !words(wheel).isEmpty else { return }
        armedWheel = wheel
        delegate?.wheelChosen()
        rebuildAccessibilityElements()
        UIAccessibility.post(notification: .announcement, argument: "\(armedWord!) chosen, space to insert")
        setNeedsDisplay()
    }

    func setSuggestions(left: [String], right: [String], prefix: String) {
        if sugLeft == left && sugRight == right && self.prefix == prefix { return }
        clearArmedWord()
        sugLeft = left; sugRight = right; self.prefix = prefix
        for w in wheels { w.index = 0; w.dragDx = 0 }
        prepareWheelWords()
        rebuildAccessibilityElements()
        setNeedsDisplay()
    }

    /// Built at layout time, reused for every frame (including pressed keys and edit wiggle).
    private final class KeyShape {
        let rect: CGRect
        let body: CGPath
        let outline: CGPath
        let inset: CGPath
        init(_ rect: CGRect, _ radius: CGFloat) {
            self.rect = rect
            body = UIBezierPath(roundedRect: rect, cornerRadius: radius).cgPath
            outline = UIBezierPath(roundedRect: rect.insetBy(dx: 1, dy: 1), cornerRadius: radius).cgPath
            inset = UIBezierPath(roundedRect: rect.insetBy(dx: 4, dy: 4), cornerRadius: radius).cgPath
        }
    }
    private final class Placed {
        let item: GridKey; let rect: CGRect; let t: CGAffineTransform; let inv: CGAffineTransform
        let shape: KeyShape
        let centre: CGPoint
        let letter: Character?
        let tapLetters: [Int]
        init(item: GridKey, rect: CGRect, t: CGAffineTransform, radius: CGFloat) {
            self.item = item; self.rect = rect; self.t = t; inv = t.inverted()
            shape = KeyShape(rect, radius)
            centre = CGPoint(x: rect.midX, y: rect.midY).applying(t)
            let first = item.keys[0].lowercased().first
            letter = !item.isStack && item.keys[0].count == 1 && first?.isLetter == true ? first : nil
            tapLetters = item.keys.compactMap { text in
                guard text.count == 1, let letter = text.lowercased().first, letter.isLetter,
                      let code = letter.unicodeScalars.first?.value, code < UInt32(ComposingTaps.letters) else { return nil }
                return Int(code)
            }
        }
    }
    private struct Cell {
        let r: Int; let c: Int; let rect: CGRect; let t: CGAffineTransform; let inv: CGAffineTransform
        let path: CGPath
    }
    private final class Wheel {
        let side: Int; var rect = CGRect.zero; var index = 0; var dragDx: CGFloat = 0
        var rendered: [WheelWord] = []
        init(side: Int) { self.side = side }
    }

    private var placed: [Placed] = []
    private var tapDistances = [Float](repeating: 100, count: ComposingTaps.letters)
    private var cells: [Cell] = []
    private let wheels = [Wheel(side: 0), Wheel(side: 1)]
    private var handleRect = CGRect.zero
    private var centerZone: CursorZone?
    private var cursorZones: [CursorZone] = []
    private var switchRect: CGRect?
    private var switchToLeft = false
    private var layoutHeight: CGFloat = 0
    private var slideProfileWidth: Int?
    private var slideAlign: Align = .center
    private var slideViewWidth: CGFloat = -1
    private var slideCenter: CGFloat = 0
    private var slideDistance: CGFloat = 0
    private var slideStarted: TimeInterval = 0
    private var slideDisplayLink: CADisplayLink?
    private var optionsCovered = false

    /// Keep Quick options changes at their starting position until the keys are visible again.
    func setOptionsCovered(_ covered: Bool) {
        guard optionsCovered != covered else { return }
        let offset = currentSlideOffset
        optionsCovered = covered
        startSlide(offset)
        setNeedsDisplay()
    }

    private var currentSlideOffset: CGFloat {
        slideOffset(slideDistance, elapsed: optionsCovered ? 0 : CACurrentMediaTime() - slideStarted,
                    reducedMotion: reducedMotion || UIAccessibility.isReduceMotionEnabled)
    }
    private var isSliding: Bool { currentSlideOffset != 0 }

    private func startSlide(_ distance: CGFloat) {
        cancelTouches()
        slideDisplayLink?.invalidate(); slideDisplayLink = nil
        slideDistance = reducedMotion || UIAccessibility.isReduceMotionEnabled ? 0 : distance
        slideStarted = CACurrentMediaTime()
        if slideDistance != 0 && !optionsCovered {
            let link = CADisplayLink(target: self, selector: #selector(slideTick))
            link.add(to: .main, forMode: .common)
            slideDisplayLink = link
        }
    }

    @objc private func slideTick() {
        setNeedsDisplay()
        if !isSliding {
            slideDisplayLink?.invalidate(); slideDisplayLink = nil
            slideDistance = 0
            rebuildAccessibilityElements(layoutChanged: true)
        }
    }
    private var gearRect = CGRect.zero
    private var globeRect = CGRect.zero
    /// Set by the controller from `needsInputModeSwitchKey` (iPhones with a home button).
    var showGlobe = false { didSet { if showGlobe != oldValue { relayout() } } }
    private var undoRect = CGRect.zero
    private var hintRect = CGRect.zero
    private var moreRect = CGRect.zero
    private var doneRect = CGRect.zero
    private var leftSpace: CGRect?
    private var rightSpace: CGRect?
    var suggestionStripHeight: CGFloat { stripH }
    private var stripH: CGFloat = 0
    private var unit: CGFloat = 0
    private var letterCentres: [Character: CGPoint] = [:]
    private var letterWidth: CGFloat = 1
    private var builtWidth: CGFloat = -1
    private var restoreWidth: Int?
    private var palette: Palette
    private var viewPrefs = ViewPreferences()
    private var faceLabels: [String: String] = [:]
    private var faceHints: [String: String] = [:]
    private var faceLetters: [String: Character] = [:]
    private var languageLabel = "EN"
    private var drawnLayer: Layer?
    private var drawnShift = false, drawnCaps = false
    private var drawnLanguage = ""
    private var handlePath: CGPath?

    private struct ViewPreferences {
        var highContrast = false, keyTint = true, showHints = true, smartTargets = true
        var swipeTyping = true, chording = false, typeOnPress = false, clicks = true, spaceAccepts = true
        var languages = 1
        var labelScale: CGFloat = 1, textScale: CGFloat = 1, radius: CGFloat = 8, baseSlack: CGFloat = 3.5, tapSlack: CGFloat = 3.5
        var repeatGuard: TimeInterval = 0, longPress: TimeInterval = 0.35
        var emptySide: EmptySide = .slideArrow
    }
    private func refreshPreferences() {
        viewPrefs.highContrast = settings.theme == .highContrast
        viewPrefs.keyTint = settings.keyTint; viewPrefs.showHints = settings.showHints
        viewPrefs.smartTargets = settings.smartTargets; viewPrefs.swipeTyping = settings.swipeTyping
        viewPrefs.chording = settings.chording; viewPrefs.typeOnPress = settings.typeOnPress
        viewPrefs.clicks = settings.clicks; viewPrefs.spaceAccepts = settings.spaceAccepts
        viewPrefs.languages = settings.languages.count; viewPrefs.emptySide = settings.emptySide
        viewPrefs.textScale = CGFloat(settings.int(Prefs.textScale)) / 100
        let system = settings.followSystemText ? min(1.6, max(0.85, UIFontMetrics(forTextStyle: .body)
            .scaledValue(for: 15, compatibleWith: traitCollection) / 15)) : 1
        viewPrefs.labelScale = viewPrefs.textScale * system
        viewPrefs.radius = CGFloat(settings.int(Prefs.cornerRadius))
        viewPrefs.baseSlack = CGFloat(settings.int(Prefs.keyGap)) / 2 + 1
        viewPrefs.tapSlack = viewPrefs.baseSlack + CGFloat(settings.int(Prefs.touchSlack))
        viewPrefs.repeatGuard = Double(settings.int(Prefs.keyRepeatGuard)) / 1000
        viewPrefs.longPress = Double(settings.int(Prefs.longPressMs)) / 1000
    }

    func refreshInputState() {
        guard drawnLayer != state.layer || drawnShift != state.shift || drawnCaps != state.capsLock || drawnLanguage != state.language else { return }
        drawnLayer = state.layer; drawnShift = state.shift; drawnCaps = state.capsLock; drawnLanguage = state.language
        prepareFaces()
        rebuildAccessibilityElements()
        setNeedsDisplay()
    }

    private func prepareFaces() {
        faceLabels.removeAll(keepingCapacity: true); faceHints.removeAll(keepingCapacity: true)
        faceLetters.removeAll(keepingCapacity: true)
        languageLabel = state.language.uppercased()
        for item in grid.items { for code in item.keys {
            faceLabels[code] = KeyText.label(code, state)
            faceHints[code] = KeyText.hint(code, state)
            faceLetters[code] = code.lowercased().first
        } }
    }

    private var displayLink: CADisplayLink?
    private var cursorDisplayLink: CADisplayLink?

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
        refreshPreferences()
        prepareDrawingResources()
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    private var isLandscape: Bool { let b = UIScreen.main.bounds; return b.width > b.height }

    private var reducedMotion = UIAccessibility.isReduceMotionEnabled
    private var highContrast: Bool { viewPrefs.highContrast }
    private var labelScale: CGFloat { viewPrefs.labelScale }

    private func refreshMotion() {
        reducedMotion = UIAccessibility.isReduceMotionEnabled
        if reducedMotion && glideID == nil { trail = [] }
        if reducedMotion { slideDisplayLink?.invalidate(); slideDisplayLink = nil; slideDistance = 0 }
        layer.removeAllAnimations()
        updateWiggle()
    }

    func reloadSettings() {
        clearArmedWord()
        cancelTouches()
        refreshMotion()
        state.language = settings.currentLanguage
        dismissPopup()
        palette = Palette.of(settings.theme, dark: traitCollection.userInterfaceStyle == .dark)
        refreshPreferences()
        trimDrawingCaches()
        prepareDrawingResources()
        relayout()
    }

    func relayout() {
        if trackers.values.contains(where: { $0.glideEligible }) { cancelTouches() }
        builtWidth = -1
        let w = bounds.width > 0 ? bounds.width : UIScreen.main.bounds.width
        onHeightChange?(layoutFor(w))
        setNeedsDisplay()
    }

    func height(forWidth w: CGFloat) -> CGFloat { builtWidth == w ? layoutHeight : layoutFor(w) }

    override func layoutSubviews() {
        super.layoutSubviews()
        let changed = builtWidth != bounds.width
        if changed { layoutFor(bounds.width) }
        // Paths use screen coordinates; refresh them after a height change moves the keyboard's frame.
        for (index, zone) in cursorZones.enumerated() {
            accessibleItems["cursor\(index)"]?.accessibilityPath = UIAccessibility.convertToScreenCoordinates(zone.path, in: self)
        }
        if changed { setNeedsDisplay() }
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        reloadSettings()
    }

    // MARK: - Layout

    /// The last worst-case tilt solution and the inputs it was computed for (see `layoutFor`).
    private var reservedCache: (key: String, un: CGFloat, cw: CGFloat)?

    private func rotateAbout(_ a: CGFloat, _ px: CGFloat, _ py: CGFloat) -> CGAffineTransform {
        CGAffineTransform(translationX: -px, y: -py).concatenating(CGAffineTransform(rotationAngle: a)).concatenating(CGAffineTransform(translationX: px, y: py))
    }

    /// Places every key for this width and returns the keyboard height.
    @discardableResult
    private func layoutFor(_ width: CGFloat) -> CGFloat {
        if builtWidth != width && trackers.values.contains(where: { $0.glideEligible }) { cancelTouches() }
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
        stripH = max(screenH < 500 ? 34 : 40, 22 * labelScale + 8)
        let handleH: CGFloat = 14
        let pad: CGFloat = 4
        let bottomPad = CGFloat(p.bottomPadding)
        // "Same size when rotated": landscape keeps portrait's physical key size instead of applying the same
        // percentages to the shorter screen. Split halves keep their portrait width and sit at the edges.
        let screen = UIScreen.main.bounds.size
        let sameScale = isLandscape && settings.sameOrientations
        let portraitW = Swift.min(screen.width, screen.height)
        let portraitH = Swift.max(screen.width, screen.height)
        let target = Swift.min((sameScale ? portraitH : screenH) * CGFloat(p.heightPercent) / 100, screenH * 0.72)
        let areaW = sameScale && !split ? Swift.min(width, portraitW * CGFloat(p.widthPercent) / 100) : width * CGFloat(p.widthPercent) / 100
        let areaLeft: CGFloat
        switch p.align {
        case .left: areaLeft = 0
        case .center: areaLeft = (width - areaW) / 2
        case .right: areaLeft = width - areaW
        }
        let innerW = areaW - 2 * pad
        let innerLeft = areaLeft + pad
        let half: CGFloat
        if !split {
            half = innerW
        } else if sameScale {
            let pInner = portraitW * CGFloat(p.widthPercent) / 100 - 2 * pad
            half = Swift.min((pInner - pInner * CGFloat(p.gap) / 100) / 2, innerW / 2)
        } else {
            half = (innerW - innerW * CGFloat(p.gap) / 100) / 2
        }
        let gapPx = split ? innerW - 2 * half : 0
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
                let cosine = cos(angle), sine = sin(angle)
                func rot(_ w: CGFloat) -> (CGFloat, CGFloat) {
                    let a = -Double(w) * cosine, b = hf * sine
                    let mn = Swift.min(0, Swift.min(a, Swift.min(a + b, b)))
                    let mx = Swift.max(0, Swift.max(a, Swift.max(a + b, b)))
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
        // Each letter key's fan angle depends only on its column and the curve, so work it out once per layout
        // instead of in every trial below (this search runs hundreds of times while a slider moves).
        var fanCache: [CGFloat: (widths: [CGFloat], sines: [CGFloat])] = [:]
        func fans(_ bend: CGFloat) -> (widths: [CGFloat], sines: [CGFloat]) {
            if let cached = fanCache[bend] { return cached }
            var widths: [CGFloat] = []
            var sines: [CGFloat] = []
            for item in grid.items where item.r < n - 1 && item.c != KeyGrid.center {
                let side = item.c < KeyGrid.center ? 0 : 1
                let lc = side == 0 ? item.c : item.c - 6
                widths.append(CGFloat(item.w))
                sines.append(CGFloat(sin(Double(abs(KeyboardView.ang[side][lc]) * bend) * Double.pi / 180)))
            }
            fanCache[bend] = (widths, sines)
            return (widths, sines)
        }
        // Bounds include every key's fan rotation, even wide keys, at any smaller curve.
        func blocks(_ un: CGFloat, _ cw: CGFloat, _ angle: Double, _ spaceAngle: Double, _ bend: CGFloat) -> Sol {
            let letterH = un * CGFloat(n - 1)
            var fanX: CGFloat = 0
            var fanY: CGFloat = 0
            let f = fans(bend)
            for i in f.widths.indices {
                fanX = Swift.max(fanX, Swift.max(0, un - gap) * f.sines[i] / 2)
                fanY = Swift.max(fanY, Swift.max(0, f.widths[i] * cw / 5 - gap) * f.sines[i] / 2)
            }
            // Axis-aligned bounds of a rotated rectangle, without temporary arrays: this runs hundreds of times.
            func bounds(_ x0: CGFloat, _ y0: CGFloat, _ x1: CGFloat, _ y1: CGFloat, _ a: Double) -> CGRect {
                let c = CGFloat(cos(a)), s = CGFloat(sin(a))
                let ax = x0 * c - y0 * s, bx = x0 * c - y1 * s, cx = x1 * c - y0 * s, dx = x1 * c - y1 * s
                let ay = x0 * s + y0 * c, by = x0 * s + y1 * c, cy = x1 * s + y0 * c, dy = x1 * s + y1 * c
                let minX = Swift.min(Swift.min(ax, bx), Swift.min(cx, dx)), maxX = Swift.max(Swift.max(ax, bx), Swift.max(cx, dx))
                let minY = Swift.min(Swift.min(ay, by), Swift.min(cy, dy)), maxY = Swift.max(Swift.max(ay, by), Swift.max(cy, dy))
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
            // 14 halvings resolve widths and heights far below a point.
            func worst(_ un: CGFloat) -> Sol {
                var lo = gap
                var hi = half
                for _ in 0..<14 {
                    let cw = (lo + hi) / 2
                    if angles.allSatisfy({ a in spaceAngles.allSatisfy { b in blocks(un, cw, a, b, 1).span <= half } }) { lo = cw } else { hi = cw }
                }
                return angles.flatMap { a in spaceAngles.map { b in blocks(un, lo, a, b, 1) } }.max { total($0) < total($1) }!
            }
            // The worst case doesn't depend on the live tilt or curve, so moving those sliders reuses it.
            let key = "\(n)|\(target)|\(half)|\(innerW)|\(gap)|\(strip)|\(bottomPad)|\(range.min)|\(range.max)|\(spaceRange.min)|\(spaceRange.max)|"
                + grid.items.filter { $0.r < n - 1 }.map { "\($0.c):\($0.w)" }.joined(separator: ",")
            let reserved: (un: CGFloat, cw: CGFloat)
            if let cached = reservedCache, cached.key == key {
                reserved = (cached.un, cached.cw)
            } else {
                var lo: CGFloat = 14
                var hi = Swift.max(lo, (target - strip - handleH - pad - bottomPad) / CGFloat(n))
                for _ in 0..<14 {
                    let un = (lo + hi) / 2
                    if total(worst(un)) <= target { lo = un } else { hi = un }
                }
                let w = worst(lo)
                reserved = (w.un, w.cw)
                reservedCache = (key, w.un, w.cw)
            }
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
                placed.append(Placed(item: item, rect: rect, t: t.concatenating(base), radius: viewPrefs.radius))
            }
        }
        if editMode {
            for ct in conts { for r in 0..<n { for c in 0..<ct.cols {
                let rect = CGRect(x: CGFloat(c) * ct.u + gap / 2, y: CGFloat(r) * unit + gap / 2, width: ct.u - gap, height: unit - gap)
                cells.append(Cell(r: r, c: ct.c0 + c, rect: rect, t: ct.base, inv: ct.base.inverted(), path: UIBezierPath(roundedRect: rect, cornerRadius: viewPrefs.radius).cgPath))
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
        // iPhones with a home button need the keyboard's own switch key: a globe at the left end of the bar.
        if showGlobe && !editMode {
            globeRect = CGRect(x: innerLeft, y: 0, width: 36, height: strip)
            wheels[0].rect = CGRect(x: globeRect.maxX, y: 0, width: Swift.max(0, wheels[0].rect.maxX - globeRect.maxX), height: strip)
        } else {
            globeRect = .zero
        }
        // Toolbar while rearranging: undo, hint, settings, Done.
        let right = innerLeft + innerW
        undoRect = CGRect(x: innerLeft, y: 0, width: 48, height: strip)
        doneRect = CGRect(x: right - 64, y: 0, width: 64, height: strip)
        moreRect = CGRect(x: doneRect.minX - 44, y: 0, width: 44, height: strip)
        hintRect = CGRect(x: undoRect.maxX, y: 0, width: Swift.max(0, moreRect.minX - undoRect.maxX), height: strip)
        handleRect = CGRect(x: areaLeft, y: y0 + hc, width: areaW, height: handleH)
        // Select the inner or outer key in each row, then follow its transformed edge just as the split does.
        func keyEdge(_ leftHalf: Bool, inner: Bool) -> [CGPoint] {
            let edgeRight = leftHalf == inner
            func mapX(_ pk: Placed) -> CGFloat {
                CGPoint(x: edgeRight ? pk.rect.maxX : pk.rect.minX, y: pk.rect.midY).applying(pk.t).x
            }
            let keys = placed.filter { !split || (leftHalf ? $0.item.c < KeyGrid.center : $0.item.c > KeyGrid.center) }
            var points: [CGPoint] = []
            for row in Dictionary(grouping: keys, by: { $0.item.r }).values {
                let pk = edgeRight ? row.max(by: { mapX($0) < mapX($1) }) : row.min(by: { mapX($0) < mapX($1) })
                guard let key = pk else { continue }
                let x = edgeRight ? key.rect.maxX : key.rect.minX
                points += [CGPoint(x: x, y: key.rect.minY).applying(key.t), CGPoint(x: x, y: key.rect.maxY).applying(key.t)]
            }
            points.sort { $0.y < $1.y }
            guard let first = points.first, let last = points.last else { return [] }
            return [CGPoint(x: first.x, y: stripH)] + points + [CGPoint(x: last.x, y: handleRect.minY)]
        }
        func vertical(_ x: CGFloat) -> [CGPoint] { [CGPoint(x: x, y: stripH), CGPoint(x: x, y: handleRect.minY)] }
        func zone(_ left: [CGPoint], _ right: [CGPoint], excluded: CGRect? = nil) -> CursorZone? {
            guard !left.isEmpty, !right.isEmpty else { return nil }
            return CursorZone(left: left, right: right, top: stripH, bottom: handleRect.minY, excluded: excluded)
        }
        centerZone = split && !editMode ? zone(keyEdge(true, inner: true), keyEdge(false, inner: true)) : nil
        let outer: [CursorZone] = editMode ? [] : [zone(vertical(areaLeft), keyEdge(true, inner: false)),
            zone(keyEdge(false, inner: false), vertical(areaLeft + areaW))].compactMap { $0 }
        let emptyCursor = viewPrefs.emptySide == .cursor
        let minSpace: CGFloat = emptyCursor ? 0 : 40
        leftSpace = areaLeft > 0 && areaLeft >= minSpace ? CGRect(x: 0, y: strip, width: areaLeft, height: y0 + hc - strip) : nil
        rightSpace = width - (areaLeft + areaW) > 0 && width - (areaLeft + areaW) >= minSpace ? CGRect(x: areaLeft + areaW, y: strip, width: width - areaLeft - areaW, height: y0 + hc - strip) : nil
        var emptyZones: [CursorZone] = []
        switchRect = nil
        if emptyCursor && !editMode {
            // The switch hugs the keys, leaving the greatest possible distance from the screen's back-gesture edge.
            if let space = [leftSpace, rightSpace].compactMap({ $0 }).max(by: { $0.width < $1.width }) {
                switchToLeft = leftSpace == space
                let inset = Swift.min(8, space.width / 4)
                let size = Swift.max(1, Swift.min(Swift.min(unit - gap, 48), space.width - 2 * inset))
                let x = switchToLeft ? space.maxX - inset - size : space.minX + inset
                switchRect = CGRect(x: x, y: space.maxY - inset - size, width: size, height: size)
            }
            for space in [leftSpace, rightSpace].compactMap({ $0 }) {
                let excluded = switchRect.flatMap { space.contains($0) ? $0 : nil }
                if let z = zone(vertical(space.minX), vertical(space.maxX), excluded: excluded) { emptyZones.append(z) }
            }
        }
        let kind = cursorZoneKind(settings.cursorControl, outer: outer, hasEmptySide: !emptyZones.isEmpty)
        if editMode { cursorZones = [] }
        else {
            switch kind {
            case .outer: cursorZones = outer + emptyZones
            case .center: cursorZones = [centerZone].compactMap { $0 }
            case .none: cursorZones = []
            }
        }
        let letters = placed.filter { !$0.item.isStack && $0.item.keys[0].count == 1 && $0.item.keys[0].first!.isLetter }
        letterCentres = [:]
        for pk in letters { letterCentres[pk.item.keys[0].lowercased().first!] = CGPoint(x: pk.rect.midX, y: pk.rect.midY).applying(pk.t) }
        let widths = letters.map { $0.rect.width }.sorted()
        letterWidth = widths.isEmpty ? 1 : widths[widths.count / 2]
        let nextCenter = areaLeft + areaW / 2
        if !editMode, slideViewWidth == width, let previousWidth = slideProfileWidth,
           previousWidth != p.widthPercent || slideAlign != p.align {
            startSlide(slideCenter + currentSlideOffset - nextCenter)
        } else if slideViewWidth != width || editMode {
            slideDisplayLink?.invalidate(); slideDisplayLink = nil; slideDistance = 0
        }
        slideCenter = nextCenter
        slideProfileWidth = p.widthPercent
        slideAlign = p.align
        slideViewWidth = width
        layoutHeight = ceil(reserve ? total(s) : Swift.max(target, total(s)))
        builtWidth = width
        handlePath = UIBezierPath(roundedRect: CGRect(x: handleRect.midX - 22, y: handleRect.midY - 2, width: 44, height: 4), cornerRadius: 2).cgPath
        prepareFaces()
        prepareWheelWords()
        rebuildAccessibilityElements(layoutChanged: accessibilityLayoutChanged)
        return layoutHeight
    }

    /// The face a swipe key shows: the one you swiped to, unless another key switched to a different layer.
    private func shownIdx(_ item: GridKey) -> Int {
        let n = item.keys.count
        let j = item.idx % n
        let k = item.keys[j]
        if n > 1 && KeyText.isLayerKey(k) && KeyText.layerOf(k) != state.layer {
            for i in item.keys.indices where KeyText.isLayerKey(item.keys[i]) && KeyText.layerOf(item.keys[i]) == state.layer { return i }
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
        var signature = [state.language, "\(viewPrefs.languages)", "\(state.layer)", "\(state.capsLock)", "\(state.shift)", enterLabel ?? "", "\(editMode)", "\(history.isEmpty)"]
        signature.append(contentsOf: placed.map { "\(KeyText.spoken($0.item.keys[shownIdx($0.item)], state, enterLabel)):\($0.item.keys.count)" })
        signature.append("\(armedWheel?.side ?? -1):" + (armedWord ?? ""))
        signature.append(contentsOf: wheels.map { words($0).isEmpty ? "" : wordAt(words($0), $0.index) })
        signature.append(contentsOf: popup?.choices ?? [])
        guard layoutChanged || signature != accessibilitySignature else { return }
        accessibilitySignature = signature
        var items: [String: AccessibleElement] = [:]
        var ordered: [AccessibleElement] = []
        func element(_ id: String, _ frame: CGRect, _ label: String, _ traits: UIAccessibilityTraits) -> AccessibleElement {
            let e = accessibleItems[id] ?? AccessibleElement(accessibilityContainer: self)
            e.isAccessibilityElement = true
            e.accessibilityPath = nil
            e.accessibilityFrameInContainerSpace = frame
            e.accessibilityLabel = label
            e.accessibilityTraits = traits
            e.accessibilityValue = nil
            e.accessibilityCustomActions = nil
            e.activate = nil; e.increment = nil; e.decrement = nil
            items[id] = e
            ordered.append(e)
            return e
        }
        for (index, pk) in placed.enumerated() {
            let face = KeyText.spoken(pk.item.keys[shownIdx(pk.item)], state, enterLabel)
            let label = pk.item.isStack ? "\(face), swipe key, \(pk.item.keys.count) options" : (pk.item.isSpace && viewPrefs.languages > 1 ? "Space, " + state.language.uppercased() + ", swipe to change language" : face)
            let traits: UIAccessibilityTraits = pk.item.isStack ? [.keyboardKey, .adjustable] : .keyboardKey
            let e = element("key\(index)", pk.rect.applying(pk.t), label, traits)
            e.activate = { [weak self] in self?.activateAccessibleKey(index) ?? false }
            if !KeyText.alternatives(pk.item.keys[shownIdx(pk.item)], state, viewPrefs.showHints).isEmpty {
                e.accessibilityCustomActions = [UIAccessibilityCustomAction(name: "Show alternatives") { [weak self] _ in
                    self?.openPopup(pk, nil) ?? false
                }]
            }
            if pk.item.isSpace {
                var actions = [UIAccessibilityCustomAction(name: "Insert suggestion") { [weak self] _ in
                    self?.pickSideSuggestion(pk.item) ?? false
                }]
                if showGlobe {
                    actions.append(UIAccessibilityCustomAction(name: "Switch keyboards") { [weak self] _ in
                        self?.delegate?.switchKeyboard()
                        return true
                    })
                }
                e.accessibilityCustomActions = actions
                if viewPrefs.languages > 1 {
                    e.accessibilityTraits.insert(.adjustable)
                    e.increment = { [weak self] in self?.delegate?.switchLanguage(1) }
                    e.decrement = { [weak self] in self?.delegate?.switchLanguage(-1) }
                }
            }
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
                e.accessibilityValue = list.isEmpty ? nil : wordAt(list, wheel.index) + (armedWheel === wheel ? " chosen, space to insert" : "")
                e.activate = { [weak self] in self?.activateAccessibleWheel(index) ?? false }
                e.increment = { [weak self] in self?.adjustAccessibleWheel(index, 1) }
                e.decrement = { [weak self] in self?.adjustAccessibleWheel(index, -1) }
            }
            for (index, zone) in cursorZones.enumerated() {
                let e = element("cursor\(index)", zone.bounds, "Cursor", .adjustable)
                e.accessibilityPath = UIAccessibility.convertToScreenCoordinates(zone.path, in: self)
                e.increment = { [weak self] in
                    guard let self = self, !self.isSliding else { return }
                    self.delegate?.moveCursor(1)
                }
                e.decrement = { [weak self] in
                    guard let self = self, !self.isSliding else { return }
                    self.delegate?.moveCursor(-1)
                }
            }
            if let button = switchRect {
                let e = element("switch", button, "Switch sides", .button)
                e.activate = { [weak self] in
                    guard let self = self, !self.isSliding else { return false }
                    self.moveToward(CGPoint(x: button.midX, y: button.midY)); return true
                }
            }
            let gear = element("gear", gearRect, "Quick options", .button)
            gear.activate = { [weak self] in
                guard let self = self else { return false }
                self.delegate?.openQuickOptions()
                return true
            }
        }
        if let p = popup {
            for i in p.choices.indices {
                let e = element("accent\(i)", p.rects[i], p.choices[i], .keyboardKey)
                e.activate = { [weak self] in
                    guard let self = self, let popup = self.popup, popup.choices.indices.contains(i) else { return false }
                    let choice = popup.choices[i]
                    let owner = popup.owner
                    self.dismissPopup()
                    self.commitPopup(owner, choice)
                    return true
                }
            }
        }
        accessibleItems = items
        accessibilityElements = ordered
        if layoutChanged { UIAccessibility.post(notification: .layoutChanged, argument: nil) }
    }

    private func activateAccessibleKey(_ index: Int) -> Bool {
        guard !isSliding, placed.indices.contains(index), delegate != nil else { return false }
        let item = placed[index].item
        if !acceptKeyTap(item, CACurrentMediaTime()) { return true }
        click()
        emitKey(item.keys[shownIdx(item)])
        rebuildAccessibilityElements()
        setNeedsDisplay()
        return true
    }

    private func adjustAccessibleKey(_ index: Int, _ step: Int) {
        guard !isSliding, placed.indices.contains(index) else { return }
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
        armWheel(wheel)
        rebuildAccessibilityElements()
        setNeedsDisplay()
    }

    // MARK: - Drawing

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        if builtWidth != bounds.width { layoutFor(bounds.width) }
        palette.background.setFill()
        ctx.fill(bounds)
        ctx.saveGState()
        ctx.translateBy(x: currentSlideOffset, y: 0)
        if editMode {
            drawToolbar()
            drawCells(ctx)
        } else {
            drawWheels(ctx)
            drawGear()
            drawSymbol("globe", in: globeRect, color: palette.hint)
            // Under the keys, so it shows through the split and the spaces between keys.
            drawWave(ctx)
        }
        let now = CACurrentMediaTime()
        for pk in placed {
            ctx.saveGState()
            ctx.concatenate(pk.t)
            if editMode && !reducedMotion {
                ctx.translateBy(x: pk.rect.midX, y: pk.rect.midY)
                ctx.rotate(by: wiggle(pk.item, now))
                ctx.translateBy(x: -pk.rect.midX, y: -pk.rect.midY)
            }
            drawKey(ctx, pk.item, pk.shape, isPressed(pk.item), drag?.item === pk.item ? 0.3 : 1)
            drawDropMark(ctx, pk)
            ctx.restoreGState()
        }
        drawHandle()
        if let d = drag { drawGhost(ctx, d) }
        drawGlide(ctx, now)
        drawPopup()
        ctx.restoreGState()
        if !editMode && !isSliding {
            if viewPrefs.emptySide == .slideArrow {
                if let l = leftSpace { drawArrow(l, pointsLeft: true) }
                if let r = rightSpace { drawArrow(r, pointsLeft: false) }
            } else if let button = switchRect {
                palette.key.setFill()
                ctx.fillEllipse(in: button)
                drawArrow(button, pointsLeft: switchToLeft)
            }
        }
    }

    private func drawGlide(_ ctx: CGContext, _ now: TimeInterval) {
        let age = now - dwellDotAt
        if age >= 0 && age < 0.25, let centre = letterCentres[dwellDotLetter] {
            let opacity = reducedMotion ? 0.6 : 0.6 * (1 - abs(age / 0.125 - 1))
            ctx.saveGState(); ctx.setAlpha(CGFloat(opacity)); palette.accent.setFill()
            ctx.fillEllipse(in: CGRect(x: centre.x - 4, y: centre.y - 4, width: 8, height: 8))
            ctx.restoreGState()
        }
        let points: [GlidePoint]
        if let id = glideID, let tracker = trackers[id] { points = tracker.points } else { points = trail }
        guard points.count > 1 else { return }
        ctx.saveGState()
        ctx.setLineWidth(4)
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)
        for i in 1..<points.count {
            let a = points[i - 1], b = points[i]
            let alpha = reducedMotion ? 1 : max(0, min(1, 1 - (now - b.time) / 0.3))
            if alpha <= 0 { continue }
            ctx.setAlpha(CGFloat(alpha * 0.86))
            ctx.setStrokeColor(palette.accent.cgColor)
            ctx.move(to: a.position); ctx.addLine(to: b.position); ctx.strokePath()
        }
        ctx.restoreGState()
    }

    private func wiggle(_ item: GridKey, _ now: CFTimeInterval) -> CGFloat {
        CGFloat(1.6 * Double.pi / 180 * sin(2 * Double.pi * now.truncatingRemainder(dividingBy: 0.32) / 0.32 + Double(item.id) * 0.9))
    }

    private func isPressed(_ item: GridKey) -> Bool {
        for tracker in trackers.values where tracker.pk?.item === item { return true }
        return false
    }

    private func drawKey(_ ctx: CGContext, _ item: GridKey, _ shape: KeyShape, _ pressed: Bool, _ alpha: CGFloat) {
        let rect = shape.rect
        let keyAlpha: CGFloat = highContrast ? 1 : alpha
        let face = shownIdx(item)
        let code = item.keys[face]
        let lock = code == Keys.abc && state.layer == .abc && state.capsLock
        let oneShot = code == Keys.abc && state.layer == .abc && state.shift && !state.capsLock
        let accentBg = code == Keys.enter || lock
        let bg: UIColor
        if highContrast { bg = palette.key }
        else if pressed { bg = palette.pressed }
        else if accentBg { bg = palette.accent }
        else if KeyText.isLayerKey(code) || code == Keys.backspace { bg = palette.functionKey }
        else { bg = palette.key }
        ctx.saveGState()
        ctx.setAlpha(keyAlpha)
        bg.setFill(); ctx.addPath(shape.body); ctx.fillPath()
        let key = faceLetters[code]
        let weight: Double
        let chord: Bool
        if let key = key { weight = keyWeights[key] ?? 0; chord = chordHighlight.contains(key) }
        else { weight = 0; chord = false }
        if !highContrast, !editMode, let key = key, code.count == 1, chordHighlight.contains(key) {
            ctx.setAlpha(0.43); palette.accent.setFill(); ctx.addPath(shape.body); ctx.fillPath()
        }
        if !highContrast && viewPrefs.keyTint && state.layer == .abc && !editMode && !pressed && !item.isStack &&
           code.count == 1 && code.first!.isLetter && !keyWeights.isEmpty {
            ctx.setAlpha(CGFloat(0.22 * weight))
            palette.accent.setFill(); ctx.addPath(shape.body); ctx.fillPath()
        }
        ctx.setAlpha(keyAlpha)
        if highContrast {
            palette.text.setStroke(); ctx.addPath(shape.outline); ctx.setLineWidth(2); ctx.strokePath()
        }
        let highlighted = accentBg || oneShot || pressed || chord || (viewPrefs.keyTint && !editMode && weight > 0.1)
        if (editMode && reducedMotion) || (highContrast && highlighted) {
            palette.accent.setStroke(); ctx.addPath(highContrast ? shape.inset : shape.outline)
            ctx.setLineWidth(2); ctx.strokePath()
        }
        let fg = highContrast ? palette.text : (accentBg && !pressed ? palette.onAccent : (oneShot ? palette.accent : palette.text))
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
            fg.setFill()
            for i in 0..<n {
                ctx.setAlpha(keyAlpha * (i == face ? 0.9 : 0.3))
                ctx.fillEllipse(in: CGRect(x: rect.minX + 3.5, y: top + CGFloat(i) * step - 1.5, width: 3, height: 3))
            }
        }
        ctx.setAlpha(keyAlpha)
        if !item.isStack && grid.rows == 4 && viewPrefs.showHints, let h = faceHints[code] {
            drawText(h, center: CGPoint(x: rect.maxX - rect.height * 0.14, y: rect.minY + rect.height * 0.16), size: min(rect.height * 0.2 * labelScale, min(rect.width * 0.35, rect.height * 0.32)), color: palette.hint)
        }
        if editMode && item.isStack {
            ctx.setAlpha(1)
            palette.accent.setFill()
            let c = CGPoint(x: rect.maxX - 8, y: rect.minY + 8)
            ctx.fillEllipse(in: CGRect(x: c.x - 7, y: c.y - 7, width: 14, height: 14))
            palette.onAccent.setStroke()
            ctx.move(to: CGPoint(x: c.x - 2.5, y: c.y - 2.5)); ctx.addLine(to: CGPoint(x: c.x + 2.5, y: c.y + 2.5))
            ctx.move(to: CGPoint(x: c.x - 2.5, y: c.y + 2.5)); ctx.addLine(to: CGPoint(x: c.x + 2.5, y: c.y - 2.5))
            ctx.setLineWidth(1.5); ctx.strokePath()
        }
        ctx.restoreGState()
    }

    private func drawFace(_ code: String, _ rect: CGRect, _ offset: CGFloat, _ color: UIColor) {
        let r = rect.offsetBy(dx: 0, dy: offset)
        let scale = labelScale
        let icon = Swift.min(r.width, r.height) * 0.42 * viewPrefs.textScale
        let center = CGPoint(x: r.midX, y: r.midY)
        switch code {
        case Keys.backspace: drawIcon(Self.deletePaths, center, icon, color)
        case Keys.enter:
            if let l = enterLabel { drawText(l, center: center, size: r.height * 0.3 * scale, color: color, maxWidth: r.width * 0.85, maxHeight: r.height * 0.85) }
            else { drawIcon(Self.returnPaths, center, icon, color) }
        case Keys.space:
            if viewPrefs.languages > 1 && !editMode {
                drawText(languageLabel, center: center, size: Swift.min(12, r.height * 0.25) * scale, color: palette.hint, maxWidth: r.width * 0.85, maxHeight: r.height * 0.85)
            }
        default:
            let label = faceLabels[code] ?? code
            let size = Swift.min(r.height * 0.42, 22) * scale
            drawText(label, center: center, size: label.count > 1 ? size * 0.72 : size, color: color, maxWidth: r.width * 0.85, maxHeight: r.height * 0.85)
        }
    }

    private struct TextKey: Hashable {
        let text: String
        let size: CGFloat
        let width: CGFloat?
        let height: CGFloat?
        let bold: Bool
        let color: ObjectIdentifier
    }
    private final class TextRun {
        let text: NSAttributedString
        let size: CGSize
        init(_ text: NSAttributedString, _ size: CGSize) { self.text = text; self.size = size }
        func draw(at point: CGPoint) { text.draw(at: point) }
    }
    private var textRuns: [TextKey: TextRun] = [:]
    private var symbols: [String: UIImage] = [:]
    private var acrossGradient: CGGradient?
    private var downGradient: CGGradient?
    private var cachedWheelFont = UIFont.systemFont(ofSize: 15)

    func trimDrawingCaches() { textRuns.removeAll() }

    private func prepareDrawingResources() {
        _ = Self.deletePaths; _ = Self.returnPaths; _ = Self.arrowPaths
        cachedWheelFont = .systemFont(ofSize: 15 * labelScale, weight: highContrast ? .bold : .regular)
        let config = UIImage.SymbolConfiguration(pointSize: 15, weight: .regular)
        for name in ["gearshape", "globe", "arrow.uturn.backward"] {
            symbols[name] = UIImage(systemName: name, withConfiguration: config)?
                .withTintColor(name == "arrow.uturn.backward" ? palette.text : palette.hint, renderingMode: .alwaysOriginal)
        }
        let space = CGColorSpaceCreateDeviceRGB()
        let color = waveColor
        acrossGradient = CGGradient(colorsSpace: space,
            colors: [CGFloat(0), 0.12, 0.5, 1, 0.5, 0.12, 0].map { color.withAlphaComponent($0).cgColor } as CFArray,
            locations: [0, 0.15, 0.3, 0.5, 0.7, 0.85, 1])
        downGradient = CGGradient(colorsSpace: space,
            colors: [UIColor.clear.cgColor, UIColor.black.cgColor, UIColor.black.cgColor, UIColor.clear.cgColor] as CFArray,
            locations: [0, 0.2, 0.8, 1])
    }

    private func textRun(_ s: String, size: CGFloat, color: UIColor, maxWidth: CGFloat? = nil,
                         maxHeight: CGFloat? = nil, bold: Bool? = nil) -> TextRun {
        let weight = bold ?? highContrast
        let key = TextKey(text: s, size: size, width: maxWidth, height: maxHeight, bold: weight, color: ObjectIdentifier(color))
        if let run = textRuns[key] { return run }
        // Misses occur only when text, theme, size or key state changes; steady frames reuse the run.
        if textRuns.count >= 512 { textRuns.removeAll(keepingCapacity: true) }
        var font = UIFont.systemFont(ofSize: size, weight: weight ? .bold : .regular)
        var attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
        var measured = (s as NSString).size(withAttributes: attrs)
        if let width = maxWidth, measured.width > width, measured.width > 0 {
            font = font.withSize(size * width / measured.width); attrs[.font] = font
            measured = (s as NSString).size(withAttributes: attrs)
        }
        if let height = maxHeight, measured.height > height, measured.height > 0 {
            font = font.withSize(font.pointSize * height / measured.height); attrs[.font] = font
            measured = (s as NSString).size(withAttributes: attrs)
        }
        let run = TextRun(NSAttributedString(string: s, attributes: attrs), measured)
        textRuns[key] = run
        return run
    }

    private func drawText(_ s: String, center: CGPoint, size: CGFloat, color: UIColor, maxWidth: CGFloat? = nil, maxHeight: CGFloat? = nil, bold: Bool? = nil) {
        guard size > 0 else { return }
        let run = textRun(s, size: size, color: color, maxWidth: maxWidth, maxHeight: maxHeight, bold: bold)
        run.draw(at: CGPoint(x: center.x - run.size.width / 2, y: center.y - run.size.height / 2))
    }

    private static func iconPath(_ points: [CGFloat], closed: Bool = false) -> CGPath {
        let p = CGMutablePath()
        p.move(to: CGPoint(x: points[0], y: points[1]))
        var i = 2
        while i < points.count { p.addLine(to: CGPoint(x: points[i], y: points[i + 1])); i += 2 }
        if closed { p.closeSubpath() }
        return p
    }
    private static let deletePaths = [
        iconPath([-0.55, 0, -0.22, -0.38, 0.55, -0.38, 0.55, 0.38, -0.22, 0.38], closed: true),
        iconPath([0, -0.15, 0.3, 0.15]), iconPath([0, 0.15, 0.3, -0.15]),
    ]
    private static let returnPaths = [iconPath([0.45, -0.4, 0.45, 0.1, -0.45, 0.1]), iconPath([-0.2, -0.15, -0.45, 0.1, -0.2, 0.35])]
    private static let arrowPaths = [iconPath([0.25, -0.5, -0.25, 0, 0.25, 0.5])]

    private func drawIcon(_ paths: [CGPath], _ center: CGPoint, _ size: CGFloat, _ color: UIColor, mirror: Bool = false, lineWidth: CGFloat = 0.09) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.saveGState()
        ctx.translateBy(x: center.x, y: center.y); ctx.scaleBy(x: mirror ? -size : size, y: size)
        color.setStroke(); ctx.setLineWidth(lineWidth); ctx.setLineCap(.round); ctx.setLineJoin(.round)
        for path in paths { ctx.addPath(path); ctx.strokePath() }
        ctx.restoreGState()
    }

    private func drawArrow(_ area: CGRect, pointsLeft: Bool) {
        drawIcon(Self.arrowPaths, CGPoint(x: area.midX, y: area.midY), Swift.min(area.width * 0.35, 18), palette.hint,
                 mirror: !pointsLeft, lineWidth: 0.135)
    }

    private func drawHandle() {
        guard let ctx = UIGraphicsGetCurrentContext(), let path = handlePath else { return }
        palette.hint.setFill(); ctx.addPath(path); ctx.fillPath()
    }

    private func drawSymbol(_ name: String, in rect: CGRect, color: UIColor) {
        guard rect.width >= 20 else { return }
        guard let img = symbols[name] else { return }
        img.draw(at: CGPoint(x: rect.midX - img.size.width / 2, y: rect.midY - img.size.height / 2))
    }

    private func cursorZone(at p: CGPoint) -> CursorZone? { cursorZones.first { $0.contains(p) } }

    /// Springs the keyboard-wide wave toward where the push points: from the press point out to the keyboard's edge as
    /// the push reaches the gap's edge, in proportion, gliding smoothly without overshoot so it stays easy on the eyes.
    /// Returns whether it is still moving.
    private func stepWave(_ t: Tracker, _ now: TimeInterval) -> Bool {
        let dt = CGFloat(Swift.min(0.04, Swift.max(0, now - t.waveTime)))
        t.waveTime = now
        let push = t.joystick.push
        let zoneBounds = t.cursorZone?.bounds ?? handleRect
        let reach = push < 0 ? t.x0 - Swift.min(handleRect.minX, zoneBounds.minX)
            : Swift.max(handleRect.maxX, zoneBounds.maxX) - t.x0
        let target = t.x0 + CGFloat(push) * reach
        if UIAccessibility.isReduceMotionEnabled { t.waveX = target; t.waveV = 0; return false }
        t.waveV += (60 * (target - t.waveX) - 16 * t.waveV) * dt
        t.waveX += t.waveV * dt
        return abs(target - t.waveX) > 0.5 || abs(t.waveV) > 4
    }

    /// While a cursor zone is held, a soft vertical glow in a pale complement of the theme's accent sits under the keys and moves out
    /// the way the cursor is pushed: a small push carries it a little past the split, a push to the split's edge carries
    /// it to the keyboard's edge. It fades out on every side and gets stronger the harder the push.
    /// The accent's complementary hue, softened to a light pastel so the glow reads as a tint, never a dark smear.
    private var waveColor: UIColor {
        var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        palette.accent.getHue(&h, saturation: &s, brightness: &b, alpha: &a)
        var r: CGFloat = 0, g: CGFloat = 0, bl: CGFloat = 0
        palette.background.getRed(&r, green: &g, blue: &bl, alpha: &a)
        let light = 0.2126 * r + 0.7152 * g + 0.0722 * bl > 0.5
        return UIColor(hue: (h + 0.5).truncatingRemainder(dividingBy: 1), saturation: Swift.min(s, 0.4), brightness: light ? 0.92 : 0.85, alpha: 1)
    }

    private func drawWave(_ ctx: CGContext) {
        var cursor: Tracker?
        for tracker in trackers.values where tracker.kind == .cursor { cursor = tracker; break }
        guard let t = cursor else { return }
        let half: CGFloat = 44
        let peak = 0.22 + 0.14 * CGFloat(Swift.min(1, abs(t.joystick.push)))
        guard let across = acrossGradient, let down = downGradient else { return }
        let box = CGRect(x: t.waveX - half, y: stripH, width: half * 2, height: handleRect.minY - stripH)
        ctx.saveGState()
        ctx.clip(to: box)
        ctx.setAlpha(peak)
        ctx.beginTransparencyLayer(auxiliaryInfo: nil)
        ctx.setAlpha(1)
        ctx.drawLinearGradient(across, start: CGPoint(x: box.minX, y: 0), end: CGPoint(x: box.maxX, y: 0), options: [])
        ctx.setBlendMode(.destinationIn)
        ctx.drawLinearGradient(down, start: CGPoint(x: 0, y: box.minY), end: CGPoint(x: 0, y: box.maxY), options: [])
        ctx.endTransparencyLayer()
        ctx.restoreGState()
    }

    private func drawGear() { drawSymbol("gearshape", in: gearRect, color: palette.hint) }

    private func drawToolbar() {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.saveGState(); ctx.setAlpha(history.isEmpty ? 0.3 : 1)
        drawSymbol("arrow.uturn.backward", in: undoRect, color: palette.text)
        ctx.restoreGState()
        drawText(editHint ?? "Drag keys to rearrange", center: CGPoint(x: hintRect.midX, y: hintRect.midY), size: 13,
                 color: editHint != nil ? palette.accent : palette.hint, maxWidth: hintRect.width * 0.95)
        drawSymbol("gearshape", in: moreRect, color: palette.hint)
        drawText("Done", center: CGPoint(x: doneRect.midX, y: doneRect.midY), size: 15, color: palette.accent, bold: true)
    }

    private static let cellDash: [CGFloat] = [4, 4]
    private func drawCells(_ ctx: CGContext) {
        for (i, cell) in cells.enumerated() {
            let isTarget = drag?.target?.cellIndex == i
            ctx.saveGState(); ctx.concatenate(cell.t)
            ctx.setAlpha(isTarget ? 1 : 0.5)
            (isTarget ? palette.accent : palette.hint).setStroke()
            ctx.addPath(cell.path); ctx.setLineWidth(isTarget ? 2 : 1)
            ctx.setLineDash(phase: 0, lengths: Self.cellDash); ctx.strokePath()
            ctx.restoreGState()
        }
    }

    // MARK: - Suggestion wheels

    private var wheelFont: UIFont { cachedWheelFont }
    private func words(_ w: Wheel) -> [String] { w.side == 0 ? sugLeft : sugRight }
    private func wordAt(_ list: [String], _ i: Int) -> String { list[((i % list.count) + list.count) % list.count] }

    private final class WheelWord {
        let whole: TextRun
        let pre: TextRun?
        let rest: TextRun?
        let bold: TextRun
        let pill: CGPath
        init(whole: TextRun, pre: TextRun?, rest: TextRun?, bold: TextRun) {
            self.whole = whole; self.pre = pre; self.rest = rest; self.bold = bold
            let box = CGRect(x: -bold.size.width / 2 - 8, y: -bold.size.height / 2 - 4,
                             width: bold.size.width + 16, height: bold.size.height + 8)
            pill = UIBezierPath(roundedRect: box, cornerRadius: box.height / 2).cgPath
        }
    }

    private func prepareWheelWords() {
        let lowerPrefix = prefix.lowercased()
        for wh in wheels {
            wh.rendered = words(wh).map { word in
                let whole = textRun(word, size: 15 * labelScale, color: palette.text)
                let bold = textRun(word, size: 15 * labelScale, color: palette.accent, bold: true)
                var pre: TextRun?, rest: TextRun?
                if !prefix.isEmpty && word.count >= prefix.count && word.lowercased().hasPrefix(lowerPrefix) {
                    pre = textRun(String(word.prefix(prefix.count)), size: 15 * labelScale, color: palette.accent)
                    rest = textRun(String(word.dropFirst(prefix.count)), size: 15 * labelScale, color: palette.text)
                }
                return WheelWord(whole: whole, pre: pre, rest: rest, bold: bold)
            }
        }
    }

    private func renderedWord(_ wh: Wheel, _ index: Int) -> WheelWord {
        let count = wh.rendered.count
        return wh.rendered[((index % count) + count) % count]
    }

    /// Neighbours drawn on each side, never more than the list holds, so no word shows twice.
    private func wheelRange(_ n: Int) -> ClosedRange<Int> {
        let back = Swift.min(3, (n - 1) / 2)
        return -back...Swift.min(3, n - 1 - back)
    }

    private func offsetOf(_ wh: Wheel, _ k: Int) -> CGFloat {
        let gap: CGFloat = 22
        var x: CGFloat = 0
        if k > 0 {
            for j in 0..<k { x += renderedWord(wh, wh.index + j).whole.size.width / 2 + gap + renderedWord(wh, wh.index + j + 1).whole.size.width / 2 }
        } else if k < 0 {
            for j in stride(from: 0, to: k, by: -1) { x -= renderedWord(wh, wh.index + j).whole.size.width / 2 + gap + renderedWord(wh, wh.index + j - 1).whole.size.width / 2 }
        }
        return x
    }

    private struct WheelTapTarget { let index: Int; let center: CGFloat; let width: CGFloat }
    private func wheelTarget(_ wh: Wheel, _ k: Int) -> WheelTapTarget {
        WheelTapTarget(index: wh.index + k, center: wh.rect.midX + offsetOf(wh, k) + wh.dragDx,
                       width: renderedWord(wh, wh.index + k).whole.size.width)
    }

    private func wheelTapIndex(_ wh: Wheel, _ x: CGFloat) -> Int {
        var nearest = wh.index, distance = CGFloat.infinity
        for k in wheelRange(wh.rendered.count) {
            let target = wheelTarget(wh, k)
            guard target.center + target.width / 2 > wh.rect.minX && target.center - target.width / 2 < wh.rect.maxX else { continue }
            let delta = abs(x - target.center)
            if delta <= target.width / 2 { return target.index }
            if delta < distance { distance = delta; nearest = target.index }
        }
        return nearest
    }

    private func drawWheels(_ ctx: CGContext) {
        for wh in wheels {
            if wh.rendered.isEmpty || wh.rect.width < 10 { continue }
            ctx.saveGState(); ctx.clip(to: wh.rect)
            let y = wh.rect.midY - wheelFont.lineHeight / 2
            for k in wheelRange(wh.rendered.count) {
                let target = wheelTarget(wh, k)
                let word = renderedWord(wh, target.index)
                let cx = target.center
                let x = cx - word.whole.size.width / 2
                if armedWheel === wh && k == 0 {
                    ctx.saveGState(); ctx.translateBy(x: cx, y: wh.rect.midY)
                    ctx.setAlpha(0.2); palette.accent.setFill(); ctx.addPath(word.pill); ctx.fillPath()
                    ctx.setAlpha(1)
                    word.bold.draw(at: CGPoint(x: -word.bold.size.width / 2, y: -word.bold.size.height / 2))
                    ctx.restoreGState()
                } else {
                    ctx.setAlpha(highContrast || k == 0 ? 1 : 0.4)
                    if let pre = word.pre, let rest = word.rest {
                        pre.draw(at: CGPoint(x: x, y: y)); rest.draw(at: CGPoint(x: x + pre.size.width, y: y))
                    } else { word.whole.draw(at: CGPoint(x: x, y: y)) }
                }
            }
            ctx.restoreGState()
        }
    }

    // MARK: - Touch

    private enum Kind { case key, stack, wheel, handle, gear, cursor }

    private final class AccentPopup {
        let choices: [String]
        let rects: [CGRect]
        let paths: [CGPath]
        let owner: Tracker?
        var selected: Int?
        init(_ choices: [String], _ rects: [CGRect], _ owner: Tracker?) {
            self.choices = choices; self.rects = rects; self.owner = owner
            paths = rects.map { UIBezierPath(roundedRect: $0, cornerRadius: 6).cgPath }
        }
    }
    private var popup: AccentPopup?

    @discardableResult private func openPopup(_ pk: Placed, _ owner: Tracker?) -> Bool {
        let choices = owner?.pressChoices ?? KeyText.alternatives(pk.item.keys[shownIdx(pk.item)], state, viewPrefs.showHints)
        guard !choices.isEmpty else { return false }
        dismissPopup()
        let anchor = pk.rect.applying(pk.t)
        let cell = Swift.min(Swift.max(36, pk.rect.width), (bounds.width - 8) / CGFloat(choices.count))
        let h = Swift.min(Swift.min(unit, 48), Swift.max(24, anchor.minY - 4))
        let left = Swift.max(4, Swift.min(anchor.midX - cell * CGFloat(choices.count) / 2, bounds.width - 4 - cell * CGFloat(choices.count)))
        let top = Swift.max(0, anchor.minY - h - 4)
        let rects = choices.indices.map { CGRect(x: left + CGFloat($0) * cell, y: top, width: cell, height: h) }
        popup = AccentPopup(choices, rects, owner)
        owner?.consumed = true
        owner?.glideEligible = false
        owner?.stop()
        rebuildAccessibilityElements(layoutChanged: true)
        UIAccessibility.post(notification: .layoutChanged, argument: accessibleItems["accent0"])
        setNeedsDisplay()
        return true
    }

    override func accessibilityPerformEscape() -> Bool {
        guard popup != nil else { return super.accessibilityPerformEscape() }
        dismissPopup()
        return true
    }

    private func dismissPopup() {
        guard popup != nil else { return }
        if popup?.owner?.kind == .stack { swipe = nil }
        popup = nil
        rebuildAccessibilityElements(layoutChanged: true)
        setNeedsDisplay()
    }

    private func drawPopup() {
        guard let p = popup, let ctx = UIGraphicsGetCurrentContext() else { return }
        for i in p.choices.indices {
            let r = p.rects[i]
            (p.selected == i ? palette.accent : palette.functionKey).setFill()
            ctx.addPath(p.paths[i]); ctx.fillPath()
            palette.text.setStroke(); ctx.setLineWidth(highContrast ? 2 : 1)
            ctx.addPath(p.paths[i]); ctx.strokePath()
            drawText(p.choices[i], center: CGPoint(x: r.midX, y: r.midY), size: Swift.min(22 * labelScale, r.height * 0.5),
                     color: p.selected == i ? palette.onAccent : palette.text, maxWidth: r.width * 0.85, maxHeight: r.height * 0.85)
        }
    }

    private final class Tracker {
        let kind: Kind
        var pk: Placed?
        var pressedText: String?
        var followingText = ""
        var pressChoices: [String]?
        var languageDirection = 0
        var consumed = false
        var moved = false
        var x0: CGFloat = 0
        var y0: CGFloat = 0
        var tapPoint = CGPoint.zero
        var lastX: CGFloat = 0
        var lastY: CGFloat = 0
        lazy var joystick = CursorJoystick()
        var cursorZone: CursorZone?
        /// The keyboard-wide wave's position and velocity (pt, pt/s), sprung toward where the push points.
        var waveX: CGFloat = 0
        var waveV: CGFloat = 0
        var waveTime: TimeInterval = 0
        var waveMoving = false
        var lastCursorClick: TimeInterval = -.infinity
        var deferredClick = false
        var glideEligible = false
        var glideWidth: CGFloat = 1
        var points: [GlidePoint] = []
        var trace: SwipeTrace?
        var glidePoint = CGPoint.zero
        var liveAt: TimeInterval = -.infinity
        var publishedPoints = -1, publishedHints = -1
        var glideTimer: Timer?
        var wheel: Wheel?
        var longTimer: Timer?
        var repeatTimer: Timer?
        init(kind: Kind, pk: Placed?) { self.kind = kind; self.pk = pk }
        func stop() { longTimer?.invalidate(); repeatTimer?.invalidate(); longTimer = nil; repeatTimer = nil }
    }

    private final class Swipe { let item: GridKey; var dy: CGFloat = 0; var stepped = false; init(item: GridKey) { self.item = item } }

    private var trackers: [ObjectIdentifier: Tracker] = [:]
    private var swipe: Swipe?
    private var lastKeyTaps: [Int: TimeInterval] = [:]
    private let chordTouches = ChordTouches<ObjectIdentifier>()
    private var chordHighlight = Set<Character>()
    private var chordHighlightWork: DispatchWorkItem?
    private var glideID: ObjectIdentifier?
    private var trail: [GlidePoint] = []
    private var dwellDotLetter: Character = " "
    private var dwellDotAt: TimeInterval = -.infinity

    private func moveCursor(_ t: Tracker, _ steps: Int, _ now: TimeInterval) {
        guard steps != 0 else { return }
        invalidatePressAccents()
        delegate?.moveCursor(steps)
        if now - t.lastCursorClick >= 0.08 { click(); t.lastCursorClick = now }
    }

    private func updateCursorLink() {
        var moving = false
        for tracker in trackers.values where tracker.kind == .cursor {
            if tracker.joystick.speed != 0 || tracker.waveMoving { moving = true; break }
        }
        guard moving else {
            cursorDisplayLink?.invalidate(); cursorDisplayLink = nil
            return
        }
        if cursorDisplayLink == nil {
            let link = CADisplayLink(target: self, selector: #selector(cursorTick))
            link.add(to: .main, forMode: .common)
            cursorDisplayLink = link
        }
    }

    @objc private func cursorTick() {
        let now = CACurrentMediaTime()
        for t in trackers.values where t.kind == .cursor {
            moveCursor(t, t.joystick.advance(now), now)
            t.waveMoving = stepWave(t, now)
        }
        setNeedsDisplay()
        updateCursorLink()
    }

    func cancelTouches() {
        cursorDisplayLink?.invalidate(); cursorDisplayLink = nil
        chordTouches.cancel()
        chordHighlightWork?.cancel()
        chordHighlight.removeAll()
        dismissPopup()
        trackers.values.forEach { $0.stop(); $0.glideTimer?.invalidate() }
        trackers = [:]
        if glideID != nil { delegate?.glideCancelled() }
        glideID = nil
        trail = []; dwellDotAt = -.infinity
        updateWiggle()
        swipe = nil
        drag = nil
        editTouch = nil
        setNeedsDisplay()
    }

    func endTypingSession() {
        cancelTouches()
        lastKeyTaps.removeAll(keepingCapacity: true)
        editMode = false
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        if builtWidth != bounds.width { layoutFor(bounds.width) }
        guard !isSliding else { return }
        if touches.count == 1, let t = touches.first {
            let p = t.location(in: self)
            if editMode { editBegan(t, p) } else { down(ObjectIdentifier(t), p, t.timestamp) }
        } else {
            // Multitouch ordering is rare, and matters for chord rollover.
            for t in touches.sorted(by: { $0.timestamp < $1.timestamp }) {
                let p = t.location(in: self)
                if editMode { editBegan(t, p) } else { down(ObjectIdentifier(t), p, t.timestamp) }
            }
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard !isSliding else { return }
        for t in touches {
            let p = t.location(in: self)
            if editMode { editMoved(t, p) } else {
                let id = ObjectIdentifier(t)
                if let tracker = trackers[id], tracker.glideEligible, let samples = event?.coalescedTouches(for: t) {
                    for sample in samples { _ = recordGlide(id, tracker, sample.location(in: self), sample.timestamp) }
                }
                move(id, p, t.timestamp)
            }
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard !isSliding else { return }
        for t in touches {
            if editMode { editEnded(t) } else {
                let id = ObjectIdentifier(t)
                let point = t.location(in: self)
                trackers[id]?.tapPoint = point
                if let tracker = trackers[id] { _ = recordGlide(id, tracker, point, t.timestamp) }
                if let tracker = trackers[id], popup?.owner === tracker || tracker.kind == .wheel || tracker.kind == .cursor || (tracker.pk?.item.isSpace == true && viewPrefs.languages > 1) {
                    move(id, point, t.timestamp)
                }
                up(id)
            }
        }
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        if glideID != nil && !touches.contains(where: { trackers[ObjectIdentifier($0)] != nil }) { return }
        cancelTouches()
    }

    private func startLongPress(_ t: Tracker) {
        t.longTimer?.invalidate()
        t.longTimer = Timer.scheduledTimer(withTimeInterval: viewPrefs.longPress, repeats: false) { [weak self, weak t] _ in
            guard let self = self, let t = t else { return }
            self.longPress(t)
        }
    }

    private func down(_ id: ObjectIdentifier, _ p: CGPoint, _ time: TimeInterval) {
        if glideID != nil || isSliding { return }
        let zone = cursorZone(at: p)
        let onControl = zone != nil || gearRect.contains(p) || handleRect.contains(p) || wheels.contains { $0.rect.contains(p) } ||
            leftSpace?.contains(p) == true || rightSpace?.contains(p) == true
        var initialHit = onControl ? nil : findKey(p)
        if !onControl, let pk = initialHit, !acceptKeyTap(pk.item, time) { return }
        let chordKey: Placed?
        if viewPrefs.chording, state.layer == .abc, !editMode, popup == nil,
           zone == nil, !gearRect.contains(p), !handleRect.contains(p), !wheels.contains(where: { $0.rect.contains(p) }),
           leftSpace?.contains(p) != true, rightSpace?.contains(p) != true,
           let pk = initialHit, pk.letter != nil {
            chordKey = pk
        } else { chordKey = nil }
        for (otherID, other) in trackers {
            let code = other.pk?.item.keys[0]
            chordTouches.update(otherID, code?.count == 1 ? code?.first : nil, other.consumed, other.tapPoint)
        }
        let prepared = chordTouches.prepareDown(time, chordKey != nil)
        for otherID in prepared.rollover { trackers[otherID]?.stop(); trackers[otherID]?.consumed = true }
        finishChords(prepared.completions)
        if !prepared.completions.isEmpty { initialHit = nil }
        // Chord members defer rollover; every other touch keeps the existing path.
        for (otherID, t) in trackers where t.kind == .key && !t.consumed && !chordTouches.contains(otherID) {
            t.stop(); t.consumed = true; commit(t)
            initialHit = nil
        }
        if gearRect.contains(p) { trackers[id] = Tracker(kind: .gear, pk: nil); return }
        if globeRect.contains(p) { click(); delegate?.switchKeyboard(); return }
        if let wh = wheels.first(where: { $0.rect.contains(p) && !words($0).isEmpty }) {
            let t = Tracker(kind: .wheel, pk: nil); t.wheel = wh; t.x0 = p.x; t.lastX = p.x; trackers[id] = t
            return
        }
        if handleRect.contains(p) {
            let t = Tracker(kind: .handle, pk: nil); t.x0 = p.x; t.lastX = p.x; trackers[id] = t
            return
        }
        if switchRect?.contains(p) == true || (viewPrefs.emptySide == .slideArrow &&
            (leftSpace?.contains(p) == true || rightSpace?.contains(p) == true)) { moveToward(p); return }
        let hit = findKey(p, strict: true)
        if hit == nil, let zone = zone {
            guard !trackers.values.contains(where: { $0.kind == .cursor }) else { return }
            let t = Tracker(kind: .cursor, pk: nil); t.x0 = p.x; t.y0 = p.y; t.lastX = p.x; t.lastY = p.y; trackers[id] = t
            t.cursorZone = zone
            t.waveX = p.x; t.waveTime = CACurrentMediaTime()
            t.joystick.reach(left: p.x - CursorZone.edge(zone.left, at: p.y), right: CursorZone.edge(zone.right, at: p.y) - p.x)
            setNeedsDisplay()
            return
        }
        if hit == nil && (centerZone?.contains(p) == true || leftSpace?.contains(p) == true || rightSpace?.contains(p) == true) { return }
        guard let pk = chordKey ?? initialHit ?? findKey(p) else { return }
        if chordKey == nil { click() }
        if pk.item.isStack {
            let t = Tracker(kind: .stack, pk: pk); t.x0 = p.x; t.y0 = p.y; t.tapPoint = p; trackers[id] = t
            pressLetter(t)
            startLongPress(t)
            swipe = Swipe(item: pk.item)
            setNeedsDisplay()
            return
        }
        let t = Tracker(kind: .key, pk: pk)
        t.tapPoint = p
        t.x0 = p.x; t.y0 = p.y; t.glideWidth = pk.rect.width
        t.glideEligible = glideAllowed && viewPrefs.swipeTyping && state.layer == .abc &&
            pk.item.keys[0].count == 1 && pk.item.keys[0].first!.isLetter
        if t.glideEligible {
            t.glidePoint = p
            t.points.append(GlidePoint(position: p, time: time))
        }
        trackers[id] = t
        if chordKey != nil {
            t.deferredClick = true
            chordTouches.down(id, pk.item.keys[0].lowercased().first!, time, p)
            if chordTouches.isCandidate(id) {
                for (otherID, other) in trackers where chordTouches.isCandidate(otherID) { other.stop() }
            }
        }
        if pk.item.keys[0] == Keys.backspace {
            t.consumed = true
            emitKey(Keys.backspace)
            t.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self, weak t] _ in
                guard let t = t else { return }
                t.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in self?.emitKey(Keys.backspace) }
                _ = self
            }
        } else if !chordTouches.isCandidate(id) {
            pressLetter(t)
            startLongPress(t)
        }
        setNeedsDisplay()
    }

    private func recordGlide(_ id: ObjectIdentifier, _ t: Tracker, _ p: CGPoint, _ time: TimeInterval) -> Bool {
        guard t.glideEligible, !t.consumed || glideID == id else { return false }
        if let active = glideID, active != id { return true }
        t.points.append(GlidePoint(position: p, time: time))
        t.glidePoint = p; t.trace?.sample(p, time, t.points.count - 1)
        if glideID == nil && hypot(p.x - t.x0, p.y - t.y0) > 1.0 * t.glideWidth {
            chordTouches.cancel()
            glideID = id
            let trace = SwipeTrace(letterCentres, letterWidth)
            t.trace = trace
            for (i, point) in t.points.enumerated() { trace.sample(point.position, point.time, i) }
            t.stop(); t.consumed = true
            delegate?.glideStarted()
            let timer = Timer(timeInterval: 0.02, repeats: true) { [weak self, weak t] _ in
                guard let self = self, let t = t, self.glideID == id else { return }
                let now = CACurrentMediaTime()
                let oldDot = t.trace?.dotAt
                t.trace?.sample(t.glidePoint, now, t.points.count - 1)
                self.publishGlide(t, now)
                if oldDot != t.trace?.dotAt { self.setNeedsDisplay() }
            }
            t.glideTimer = timer; RunLoop.main.add(timer, forMode: .common)
            for (otherID, other) in Array(trackers) where otherID != id { other.stop(); trackers.removeValue(forKey: otherID) }
            swipe = nil
            updateWiggle()
        }
        if glideID == id {
            publishGlide(t, time)
            updateWiggleIfNeeded()
            setNeedsDisplay()
            return true
        }
        return false
    }

    private func publishGlide(_ t: Tracker, _ now: TimeInterval) {
        if let trace = t.trace, trace.dotAt != dwellDotAt {
            dwellDotLetter = trace.dotLetter; dwellDotAt = trace.dotAt
            if reducedMotion && dwellDotAt.isFinite {
                DispatchQueue.main.asyncAfter(deadline: .now() + max(0.001, 0.25 - (CACurrentMediaTime() - dwellDotAt))) { [weak self] in self?.setNeedsDisplay() }
            }
        }
        guard now - t.liveAt >= 0.05 else { return }
        let hintCount = t.trace?.hints.count ?? 0
        guard t.publishedPoints != t.points.count || t.publishedHints != hintCount else { return }
        t.liveAt = now; t.publishedPoints = t.points.count; t.publishedHints = hintCount
        delegate?.glideProgress(t.points, letterCentres, letterWidth, t.trace?.text() ?? "", t.trace?.hints ?? [])
    }

    private func move(_ id: ObjectIdentifier, _ p: CGPoint, _ time: TimeInterval) {
        guard let t = trackers[id] else { return }
        t.tapPoint = p
        if let popup = popup, popup.owner === t {
            let selection = popup.rects.firstIndex(where: { $0.contains(p) })
            if selection != popup.selected { popup.selected = selection; setNeedsDisplay() }
            return
        }
        if let pk = t.pk, pk.item.isSpace, !t.consumed, viewPrefs.languages > 1 {
            let dx = p.x - t.x0
            if abs(dx) > 8 { t.longTimer?.invalidate() }
            if abs(dx) > pk.rect.width / CGFloat(pk.item.w) && abs(p.y - t.y0) < unit {
                t.languageDirection = dx > 0 ? 1 : -1
                t.consumed = true
            }
            return
        }
        if recordGlide(id, t, p, time) { return }
        if chordTouches.isCandidate(id) { return }
        switch t.kind {
        case .wheel:
            t.lastX = p.x
            let dx = p.x - t.x0
            if abs(dx) > 8 { t.moved = true }
            if t.moved { clearArmedWord(); t.wheel?.dragDx = dx; setNeedsDisplay() }
        case .handle:
            t.lastX = p.x
            if abs(p.x - t.x0) > 8 { t.moved = true }
        case .cursor:
            let now = CACurrentMediaTime()
            t.lastX = p.x; t.lastY = p.y
            moveCursor(t, t.joystick.displace(p.x - t.x0, now), now)
            t.waveMoving = true
            updateCursorLink()
            setNeedsDisplay()
        case .stack:
            // One swipe moves exactly one face; the rest of the gesture is ignored.
            guard let sw = swipe, !t.consumed, !sw.stepped, let pk = t.pk else { return }
            let dy = p.y - t.y0
            if abs(dy) > 5 { t.moved = true }
            if !t.moved { return }
            t.longTimer?.invalidate()
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
                rebuildAccessibilityElements()
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
        if let popup = popup, popup.owner === t {
            chordTouches.update(id, nil, true)
            finishChords(chordTouches.release(id).completions)
            let choice = popup.selected.map { popup.choices[$0] }
            dismissPopup()
            if let choice = choice { commitPopup(t, choice) }
            return
        }
        if t.languageDirection != 0 {
            delegate?.switchLanguage(t.languageDirection)
            click()
            return
        }
        if glideID == id {
            trail = t.points
            t.glideTimer?.invalidate(); t.glideTimer = nil
            glideID = nil
            if reducedMotion { trail = []; updateWiggle() }
            delegate?.glideFinished(t.points, letterCentres, letterWidth, t.trace?.text() ?? "", t.trace?.hints ?? [])
            setNeedsDisplay()
            return
        }
        let code = t.pk?.item.keys[0]
        chordTouches.update(id, code?.count == 1 ? code?.first : nil, t.consumed, t.tapPoint)
        let chordRelease = chordTouches.release(id)
        if chordRelease.handled {
            finishChords(chordRelease.completions)
            setNeedsDisplay()
            return
        }
        switch t.kind {
        case .wheel:
            guard let wh = t.wheel else { break }
            let list = words(wh)
            if t.moved && !list.isEmpty {
                // Settle on whichever word is nearest the middle.
                var best = 0
                var bestDist = CGFloat.greatestFiniteMagnitude
                for k in wheelRange(list.count) {
                    let d = abs(offsetOf(wh, k) + wh.dragDx)
                    if d < bestDist { bestDist = d; best = k }
                }
                wh.index = ((wh.index + best) % list.count + list.count) % list.count
                armWheel(wh)
            } else if !list.isEmpty {
                invalidatePressAccents()
                delegate?.suggestionPicked(wordAt(list, wheelTapIndex(wh, t.lastX)))
            }
            wh.dragDx = 0
        case .handle:
            if t.moved && abs(t.lastX - t.x0) > 36 { slide(left: t.lastX < t.x0) }
        case .stack:
            swipe = nil
            if !t.moved, !t.consumed, let pk = t.pk { emitKey(pk.item.keys[shownIdx(pk.item)], t.tapPoint) }
        case .key:
            if !t.consumed { commit(t) }
        case .gear:
            delegate?.openQuickOptions()
        case .cursor:
            updateCursorLink()
        }
        setNeedsDisplay()
    }

    private func finishChords(_ completions: [ChordTouches<ObjectIdentifier>.Completion]) {
        for completion in completions {
            if completion.candidate { finishChord(completion.entries) }
            else { for entry in completion.entries { click(); emitKey(String(entry.letter), entry.point) } }
        }
    }

    private func finishChord(_ entries: [ChordTouches<ObjectIdentifier>.Entry]) {
        let letters = String(entries.map { $0.letter })
        let available = Set(grid.items.filter { !$0.isStack }.compactMap { item -> Character? in
            guard item.keys[0].count == 1, let key = item.keys[0].lowercased().first, key.isLetter else { return nil }
            return key
        })
        if let chord = chordLibrary?.forLetters(available).first(where: { $0.keys == Chords.letters(letters) }) {
            delegate?.chordTyped(chord.word)
            click()
            chordHighlight = Set(chord.keys)
            chordHighlightWork?.cancel()
            let work = DispatchWorkItem { [weak self] in self?.chordHighlight.removeAll(); self?.setNeedsDisplay() }
            chordHighlightWork = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.15, execute: work)
            UIAccessibility.post(notification: .announcement, argument: chord.word)
        } else {
            for entry in entries { click(); emitKey(String(entry.letter)) }
        }
    }

    private func acceptKeyTap(_ item: GridKey, _ time: TimeInterval) -> Bool {
        if let previous = lastKeyTaps[item.id], time >= previous,
           time - previous < viewPrefs.repeatGuard { return false }
        lastKeyTaps[item.id] = time
        return true
    }

    private func invalidatePressAccents() {
        for t in trackers.values where t.pressedText != nil { t.pressedText = nil; t.stop() }
    }

    private func emitKey(_ code: String, _ tap: CGPoint? = nil) {
        let output = KeyText.output(code, state)
        if let output = output {
            for t in trackers.values where t.pressedText != nil { t.followingText += output }
        } else { invalidatePressAccents() }
        if let tap = tap, let output = output, output.count == 1,
           let ch = output.first, ch.isLetter || ch.isNumber {
            for i in tapDistances.indices { tapDistances[i] = 100 }
            for pk in placed {
                let point = tap.applying(pk.inv)
                let width = max(1, pk.rect.width)
                let dx = (point.x - pk.rect.midX) / width
                let dy = (point.y - pk.rect.midY) / width
                for code in pk.tapLetters {
                    tapDistances[code] = min(tapDistances[code], Float(dx * dx + dy * dy))
                }
            }
            delegate?.keyTapped(code, distances: tapDistances)
        } else { delegate?.keyTapped(code) }
    }

    private func pressLetter(_ t: Tracker) {
        guard let item = t.pk?.item else { return }
        let code = item.keys[shownIdx(item)]
        guard viewPrefs.typeOnPress, state.layer == .abc, code.count == 1, code.first!.isLetter,
              let output = KeyText.output(code, state) else { return }
        t.pressChoices = KeyText.alternatives(code, state, viewPrefs.showHints)
        emitKey(code, t.tapPoint)
        t.pressedText = output
        t.consumed = true
    }

    private func commitPopup(_ t: Tracker?, _ choice: String) {
        if let t = t, let original = t.pressedText {
            delegate?.replacePressText(original, following: t.followingText, replacement: choice)
        } else { delegate?.longPressText(choice) }
    }

    private func commit(_ t: Tracker) {
        if t.deferredClick { t.deferredClick = false; click() }
        guard let item = t.pk?.item else { return }
        emitKey(item.keys[0], t.tapPoint)
    }

    /// Holding a spacebar inserts the word centred in the suggestion wheel on its side (left spacebar → left wheel),
    /// including a word chosen by spinning.
    @discardableResult
    private func pickSideSuggestion(_ item: GridKey) -> Bool {
        let wheel = wheels[item.c < KeyGrid.center ? 0 : 1]
        let list = words(wheel)
        guard !list.isEmpty else { return false }
        delegate?.suggestionPicked(wordAt(list, wheel.index))
        return true
    }

    private func longPress(_ t: Tracker) {
        guard let item = t.pk?.item else { return }
        let code = item.keys[shownIdx(item)]
        if code == Keys.space {
            // Nothing to pick: let the lift type a normal space.
            guard pickSideSuggestion(item) else { return }
        } else {
            guard let pk = t.pk, openPopup(pk, t) else { return }
        }
        t.consumed = true
    }

    /// The key under a point. Gaps and edges belong to the nearest key, so taps never fall through.
    private func findKey(_ p: CGPoint, strict: Bool = false, skip: GridKey? = nil) -> Placed? {
        let baseSlack = viewPrefs.baseSlack
        let slack = viewPrefs.tapSlack
        // Existing targets win before extra slack, including on narrow, rotated split layouts.
        let hit = placed.reversed().first { $0.item !== skip && $0.rect.insetBy(dx: -baseSlack, dy: -baseSlack).contains(p.applying($0.inv)) }
            ?? placed.reversed().first { $0.item !== skip && $0.rect.insetBy(dx: -slack, dy: -slack).contains(p.applying($0.inv)) }
        if !strict && !editMode && state.layer == .abc && viewPrefs.smartTargets && !keyWeights.isEmpty &&
           (hit == nil || hit?.letter != nil) {
            var best: Placed?
            var bestScore = -Double.infinity
            for pk in placed where pk.item !== skip && pk.letter != nil {
                let local = p.applying(pk.inv)
                let r = pk.rect
                if r.insetBy(dx: r.width * 0.15, dy: r.height * 0.15).contains(local) { return pk }
                if !r.insetBy(dx: -r.width * 0.35, dy: -r.height * 0.35).contains(local) { continue }
                let dx = Double((local.x - r.midX) / r.width)
                let dy = Double((local.y - r.midY) / r.height)
                let weight = keyWeights[pk.letter!] ?? 0
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
            let d = hypot(pk.centre.x - p.x, pk.centre.y - p.y)
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
        if viewPrefs.clicks { UIDevice.current.playInputClick() }
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
        let shape: KeyShape
        var p: CGPoint
        let off: CGPoint
        var target: Target?
        init(item: GridKey, size: CGSize, p: CGPoint, off: CGPoint, radius: CGFloat) {
            self.item = item; self.size = size; self.p = p; self.off = off
            shape = KeyShape(CGRect(x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height), radius)
        }
    }

    private var drag: Drag?
    private var editTouch: UITouch?

    private func updateWiggle() {
        displayLink?.invalidate()
        displayLink = nil
        guard !reducedMotion && (editMode || glideID != nil || !trail.isEmpty) else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    private func updateWiggleIfNeeded() { if displayLink == nil { updateWiggle() } }

    @objc private func tick() {
        if glideID == nil, let last = trail.last, CACurrentMediaTime() - last.time > 0.3 {
            trail = []
            updateWiggle()
        }
        setNeedsDisplay()
    }

    override func willMove(toWindow newWindow: UIWindow?) {
        super.willMove(toWindow: newWindow)
        if newWindow == nil {
            cancelTouches(); displayLink?.invalidate(); displayLink = nil
            slideDisplayLink?.invalidate(); slideDisplayLink = nil; slideDistance = 0
        } else { refreshMotion(); setNeedsDisplay() }
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
        drag = Drag(item: pk.item, size: pk.rect.size, p: p, off: CGPoint(x: q.x - pk.rect.midX, y: q.y - pk.rect.midY), radius: viewPrefs.radius)
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

    private func drawDropMark(_ ctx: CGContext, _ pk: Placed) {
        guard let t = drag?.target, t.pk === pk else { return }
        let r = pk.rect
        palette.accent.setFill()
        palette.accent.setStroke()
        if t.type == .insert {
            UIRectFill(t.before ? CGRect(x: r.minX, y: r.minY, width: 4, height: r.height) : CGRect(x: r.maxX - 4, y: r.minY, width: 4, height: r.height))
        } else {
            ctx.addPath(pk.shape.body); ctx.setLineWidth(t.type == .combine ? 3 : 2); ctx.strokePath()
        }
    }

    private func drawGhost(_ ctx: CGContext, _ d: Drag) {
        ctx.saveGState()
        ctx.translateBy(x: d.p.x - d.off.x, y: d.p.y - d.off.y)
        ctx.scaleBy(x: 1.1, y: 1.1)
        drawKey(ctx, d.item, d.shape, false, 0.92)
        ctx.restoreGState()
    }
}

/// Rate control independent of event frequency and reduced-motion preferences.
private final class CursorJoystick {
    static let maxSpeed = 30.0
    private static let minSpeed = 3.0
    private static let deadZone = 4.0
    /// A press right beside the gap's edge still gets this much room before top speed.
    private static let minReach = 8.0
    /// Current signed speed in characters per second (also drives the gap's direction gradient).
    private(set) var speed = 0.0
    /// How far the push reaches toward the gap's edge, signed, from -1 to 1; drives the keyboard-wide wave.
    private(set) var push = 0.0
    private var fraction = 0.0
    private var lastTime: TimeInterval?
    private var reachLeft = CursorJoystick.minReach
    private var reachRight = CursorJoystick.minReach

    /// How far the gap extends from the press point on each side, in points. Pushing to that edge gives top speed.
    func reach(left: CGFloat, right: CGFloat) {
        reachLeft = Swift.max(Self.minReach, Double(left))
        reachRight = Swift.max(Self.minReach, Double(right))
    }

    /// Like a thumb stick: `dx` is how far the finger has pushed from where it pressed, in points, and the cursor keeps
    /// moving that way until it comes back. Speed runs from 3 chars/s just past the dead zone to `maxSpeed` at the
    /// gap's edge, easing in so small pushes stay precise; the finger never has to leave the gap.
    func displace(_ dx: CGFloat, _ now: TimeInterval) -> Int {
        var steps = advance(now)
        let magnitude = abs(Double(dx))
        let reach = dx < 0 ? reachLeft : reachRight
        push = Swift.min(1, magnitude / reach) * (dx < 0 ? -1 : 1)
        let u = Swift.min(1, Swift.max(0, (magnitude - Self.deadZone) / (reach - Self.deadZone)))
        let next = magnitude <= Self.deadZone ? 0 : (Self.minSpeed + (Self.maxSpeed - Self.minSpeed) * u * u.squareRoot()) * (dx < 0 ? -1.0 : 1.0)
        if next == 0 || next * speed < 0 { fraction = 0 }
        if next != 0 && speed == 0 { steps += next < 0 ? -1 : 1 }
        speed = next
        return steps
    }

    func advance(_ now: TimeInterval) -> Int {
        let previous = lastTime
        lastTime = now
        guard let previous = previous else { return 0 }
        fraction += speed * max(0, now - previous)
        // Avoid losing a whole character to floating-point error at an exact boundary.
        let steps = Int(fraction + (fraction > 0 ? 1e-9 : -1e-9))
        fraction -= Double(steps)
        return steps
    }
}

