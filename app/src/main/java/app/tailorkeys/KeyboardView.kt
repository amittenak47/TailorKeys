package app.tailorkeys

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import app.tailorkeys.layout.GridKey
import app.tailorkeys.layout.InputState
import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.KeyText
import app.tailorkeys.layout.Keys
import app.tailorkeys.layout.Layer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws the suggestion wheels and the key grid, and turns touches into key presses.
 *
 * In [editMode] (the gear in the suggestion bar, or the settings screen) the keys lay flat and wiggle, and
 * dragging a key moves it: onto an empty cell, onto another key's edge (insert and shift), onto its middle
 * (combine into a swipe key), or onto a spacebar (sit on its inner side while it shrinks). The suggestion bar
 * turns into a toolbar with undo, a hint for the current drag and, inside the keyboard, settings and Done.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val settings: KeyboardSettings) : View(context) {

    interface Listener {
        fun onKey(code: String)
        fun onLongPressText(text: String)
        fun onStackFace(code: String)
        fun onSuggestion(text: String)
        fun onGlide(points: List<GestureDecoder.Point>, keys: Map<Char, GestureDecoder.Point>, keyWidth: Float) {}
        fun onPickIme()
        fun onCursor(steps: Int, select: Boolean) {}
        fun onSelectMode(on: Boolean) {}
        fun onEdited() {}
        fun onOpenSettings() {}
    }

    var listener: Listener? = null
    var glideAllowed = true
        set(value) { if (field != value) cancelTouches(); field = value }
    var state = InputState()
        set(value) { field = value; refreshAccessibilityState(); invalidate() }
    var keyWeights: Map<Char, Double> = emptyMap()
        set(value) { field = value; invalidate() }
    /** Setting a new grid from outside clears the undo history; [undo] and [replaceGrid] keep it. */
    var grid: KeyGrid = KeyGrid.default()
        set(value) { field = value; if (!keepHistory) history.clear(); cancelTouches(); relayout() }
    var enterLabel: String? = null
        set(value) { field = value; refreshAccessibilityState(); invalidate() }
    var editMode = false
        set(value) { if (field != value) { field = value; editHint = null; cancelTouches(); relayout() } }
    /** True inside the keyboard itself: shows the gear, and settings and Done while rearranging. */
    var inKeyboard = false
        set(value) { field = value; relayout() }

    private val history = ArrayList<String>()
    private var keepHistory = false
    private var editHint: String? = null

    private var sugLeft = emptyList<String>()
    private var sugRight = emptyList<String>()
    private var prefix = ""

    fun setSuggestions(left: List<String>, right: List<String>, prefix: String) {
        sugLeft = left; sugRight = right; this.prefix = prefix
        for (w in wheels) { w.index = 0; w.dragDx = 0f }
        refreshAccessibilityState()
        if (!editMode) for (id in A_WHEEL_LEFT..A_WHEEL_RIGHT) accessibilityEvent(id, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT)
        invalidate()
    }

    private class Placed(val item: GridKey, val rect: RectF, val matrix: Matrix, val inverse: Matrix)
    private class Cell(val r: Int, val c: Int, val rect: RectF, val matrix: Matrix, val inverse: Matrix)
    private class Wheel(val side: Int) { val rect = RectF(); var index = 0; var dragDx = 0f }

    private val placed = ArrayList<Placed>()
    private val cells = ArrayList<Cell>()
    private val wheels = listOf(Wheel(0), Wheel(1))
    private val handleRect = RectF()
    private val cursorRect = RectF()
    private val gearRect = RectF()
    private val undoRect = RectF()
    private val hintRect = RectF()
    private val moreRect = RectF()
    private val doneRect = RectF()
    private var leftSpace: RectF? = null
    private var rightSpace: RectF? = null
    private var stripH = 0f
    private var unit = 0f
    private var letterCentres = emptyMap<Char, GestureDecoder.Point>()
    private var letterWidth = 1f
    private var builtWidth = -1
    private var restoreWidth: Int? = null

    private var palette = Palette.of(context, settings.theme())
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val wheelText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private val isLandscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun reloadSettings() {
        palette = Palette.of(context, settings.theme())
        relayout()
    }

    private fun relayout() {
        if (trackers.values.any { it.glideEligible }) cancelTouches()
        clearAccessibilityFocus()
        updateHoveredView(A_NONE)
        builtWidth = -1
        requestLayout()
        invalidate()
    }

    // ---- Layout ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, layoutFor(w))
    }

    private fun ensureLayout() { if (builtWidth != width && width > 0) layoutFor(width) }

    // Gesture navigation treats a strip along each screen edge as "back", which swallowed taps on the outer keys
    // (q, a, z, p…). Keep that gesture off the key area so edge keys always type.
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (Build.VERSION.SDK_INT >= 29 && inKeyboard) {
            val edge = (32 * density).toInt()
            val keysTop = stripH.toInt()
            systemGestureExclusionRects = listOf(Rect(0, keysTop, edge, height), Rect(width - edge, keysTop, width, height))
        }
    }

    /** The last worst-case tilt solution and the inputs it was computed for (see [layoutFor]). */
    private var reservedCache: Pair<List<Any>, FloatArray>? = null
    // Scratch space for layoutFor's rotated-bounds math, reused so the tilt search allocates nothing.
    private val boundsLetters = FloatArray(4)
    private val boundsSpace = FloatArray(4)

    /** Places every key for this width and returns the keyboard height. */
    private fun layoutFor(width: Int): Int {
        if (builtWidth != width && trackers.values.any { it.glideEligible }) cancelTouches()
        val d = density
        val landscape = isLandscape
        val p = settings.profile(landscape)
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        val split = p.split
        val curve = if (editMode || !split) 0f else settings.int(Prefs.CURVE) / 100f
        val tilt = if (editMode || !split) 0f else p.tilt.toFloat()
        val spaceTilt = if (editMode || !split) 0f else p.spaceTilt.toFloat()
        val th = Math.toRadians(tilt.toDouble())
        val n = grid.rows
        val gap = settings.int(Prefs.KEY_GAP) * d
        stripH = (if (screenH / d < 500) 34 else 40) * d
        val handleH = 14 * d
        val pad = 4 * d
        val bottomPad = p.bottomPaddingDp * d
        // "Same size when rotated": landscape keeps portrait's physical key size instead of applying the same
        // percentages to the shorter screen. Split halves keep their portrait width and sit at the edges.
        val dm = resources.displayMetrics
        val sameScale = landscape && settings.bool(Prefs.SAME_ORIENTATIONS)
        val portraitW = min(dm.widthPixels, dm.heightPixels).toFloat()
        val portraitH = max(dm.widthPixels, dm.heightPixels).toFloat()
        val target = min((if (sameScale) portraitH else screenH) * p.heightPercent / 100f, screenH * 0.72f)
        val areaW = if (sameScale && !split) min(width.toFloat(), portraitW * p.widthPercent / 100f) else width * p.widthPercent / 100f
        val areaLeft = when (p.align) { Align.LEFT -> 0f; Align.CENTER -> (width - areaW) / 2f; Align.RIGHT -> width - areaW }
        val innerW = areaW - 2 * pad
        val innerLeft = areaLeft + pad
        val half = when {
            !split -> innerW
            sameScale -> { val pInner = portraitW * p.widthPercent / 100f - 2 * pad; min((pInner - pInner * p.gap / 100f) / 2f, innerW / 2f) }
            else -> (innerW - innerW * p.gap / 100f) / 2f
        }
        val gapPx = if (split) innerW - 2 * half else 0f

        class Sol(val un: Float, val hc: Float, val cw: Float, val pv: Float, val disp: Float, val padTop: Float,
                  val letterY: Float = 0f, val spaceY: Float = hc, val span: Float = cw) {
            val total get() = stripH + padTop + hc + handleH + bottomPad
        }

        // Fit each tilted half inside its side: outer edge on the screen edge, never past the center line.
        fun solve(un: Float, angle: Double, bend: Float, fixedWidth: Float? = null): Sol {
            val hc = un * n
            val raise = if (split) 0.38f * bend * un else 0f
            val hf = hc + raise
            var cw = fixedWidth ?: half
            var pv = half
            if (split) {
                fun rot(w: Float): Pair<Float, Float> {
                    var mn = 0.0
                    var mx = 0.0
                    for ((px, py) in listOf(-w to 0f, -w to -hf, 0f to -hf, 0f to 0f)) {
                        val x = px * cos(angle) - py * sin(angle)
                        mn = min(mn, x); mx = max(mx, x)
                    }
                    return (-mn).toFloat() to mx.toFloat()
                }
                if (fixedWidth == null) for (i in 0 until 2000) {
                    val (a, b) = rot(cw)
                    if (a <= half + 0.5f && a + b <= innerW / 2 - 3 * d) break
                    cw -= 1f
                    if (cw < half * 0.2f) break
                }
                pv = rot(cw).first
            }
            val disp = if (split) ceil(cw * abs(sin(angle))).toFloat() else 0f
            return Sol(un, hc, cw, pv, disp, pad + disp + raise)
        }

        // Fit tilt, curve and all rows within the selected height, keeping a pixel of room when shrinking.
        fun fit(angle: Double, bend: Float): Sol {
            var s = solve(max(14 * d, (target - stripH - handleH - pad - bottomPad) / n), angle, bend)
            for (i in 0 until 24) {
                if (s.total <= target) break
                s = solve(max(14 * d, s.un - (s.total - target + 1) / (n + 0.5f)), angle, bend)
            }
            return s
        }
        // Each letter key's fan angle depends only on its column and the curve, so work it out once per layout
        // instead of in every trial below (this search runs hundreds of times while a slider moves).
        val fanCache = HashMap<Float, Pair<FloatArray, FloatArray>>()
        fun fans(bend: Float) = fanCache.getOrPut(bend) {
            val keys = grid.items.filter { it.r < n - 1 && it.c != KeyGrid.CENTER }
            val widths = FloatArray(keys.size)
            val sines = FloatArray(keys.size)
            keys.forEachIndexed { i, item ->
                val side = if (item.c < KeyGrid.CENTER) 0 else 1
                val lc = if (side == 0) item.c else item.c - 6
                widths[i] = item.w.toFloat()
                sines[i] = sin(Math.toRadians((abs(ANG[side][lc]) * bend).toDouble())).toFloat()
            }
            widths to sines
        }
        // Bounds include every key's fan rotation, even wide keys, at any smaller curve.
        fun blocks(un: Float, cw: Float, angle: Double, spaceAngle: Double, bend: Float): Sol {
            val letterH = un * (n - 1)
            var fanX = 0f
            var fanY = 0f
            val (widths, sines) = fans(bend)
            for (i in widths.indices) {
                fanX = max(fanX, max(0f, un - gap) * sines[i] / 2)
                fanY = max(fanY, max(0f, widths[i] * cw / 5 - gap) * sines[i] / 2)
            }
            // Axis-aligned bounds of a rotated rectangle, written into [out] as left, top, right, bottom.
            // Allocation-free: this runs hundreds of times per layout.
            fun bounds(x0: Float, y0: Float, x1: Float, y1: Float, a: Double, out: FloatArray) {
                val c = cos(a).toFloat()
                val s = sin(a).toFloat()
                val ax = x0 * c - y0 * s; val bx = x0 * c - y1 * s; val cx = x1 * c - y0 * s; val dx = x1 * c - y1 * s
                val ay = x0 * s + y0 * c; val by = x0 * s + y1 * c; val cy = x1 * s + y0 * c; val dy = x1 * s + y1 * c
                out[0] = min(min(ax, bx), min(cx, dx)); out[2] = max(max(ax, bx), max(cx, dx))
                out[1] = min(min(ay, by), min(cy, dy)); out[3] = max(max(ay, by), max(cy, dy))
            }
            val letters = boundsLetters
            val space = boundsSpace
            bounds(-cw + gap / 2 - fanX, -letterH + gap / 2 - 0.38f * bend * un - fanY, -gap / 2 + fanX, -gap / 2 + fanY, angle, letters)
            bounds(-cw + gap / 2, gap / 2, -gap / 2, un - gap / 2, spaceAngle, space)
            // Separate the complete rotated bounds by the normal gap, including opposing tilts.
            val lettersH = letters[3] - letters[1]
            val contentH = lettersH + gap + (space[3] - space[1])
            val hc = max(n * un, contentH)
            val extra = (hc - contentH) / 2
            val left = min(letters[0], space[0])
            val right = max(letters[2], space[2])
            return Sol(un, hc, cw, -left, 0f, pad, -letters[1] - letterH + extra,
                lettersH + gap - space[1] + extra, right - left)
        }
        val reserve = split && !editMode
        val s = if (reserve) {
            // Fit both ranges together at maximum curve; neither live angle changes key dimensions.
            val range = Prefs.tilt(landscape)
            val spaceRange = Prefs.spaceTilt(landscape)
            val angles = listOf(range.min, range.max).map { Math.toRadians(it.toDouble()) }
            val spaceAngles = listOf(spaceRange.min, spaceRange.max).map { Math.toRadians(it.toDouble()) }
            // 14 halvings resolve widths and heights far below a pixel.
            fun worst(un: Float): Sol {
                var lo = gap
                var hi = half
                repeat(14) {
                    val cw = (lo + hi) / 2
                    if (angles.all { a -> spaceAngles.all { b -> blocks(un, cw, a, b, 1f).span <= half } }) lo = cw else hi = cw
                }
                return angles.flatMap { a -> spaceAngles.map { b -> blocks(un, lo, a, b, 1f) } }.maxBy { it.total }
            }
            // The worst case doesn't depend on the live tilt or curve, so moving those sliders reuses it.
            val key = listOf(n, target, half, innerW, gap, stripH, bottomPad, range.min, range.max, spaceRange.min, spaceRange.max,
                grid.items.filter { it.r < n - 1 }.joinToString { "${it.c}:${it.w}" })
            // [row unit, half width, total height] of the worst case.
            val reserved = reservedCache?.takeIf { it.first == key }?.second ?: run {
                var lo = 14 * d
                var hi = max(lo, (target - stripH - handleH - pad - bottomPad) / n)
                repeat(14) {
                    val un = (lo + hi) / 2
                    if (worst(un).total <= target) lo = un else hi = un
                }
                val w = worst(lo)
                floatArrayOf(w.un, w.cw, w.total).also { reservedCache = key to it }
            }
            blocks(reserved[0], reserved[1], th, Math.toRadians(spaceTilt.toDouble()), curve)
        } else fit(th, curve)
        unit = s.un
        // Centre the active key area in the space reserved for the worst case.
        // Split: key size comes from the worst-case tilt (so keys never resize), but the frame fits the keys at the
        // current tilt, so there are no empty bands above and below them.
        val y0 = stripH + s.padTop + if (reserve) 0f else max(0f, target - s.total)
        val hc = s.hc

        class Cont(val c0: Int, val cols: Int, val u: Float, val side: Int, val base: Matrix, val spaceBase: Matrix? = null)
        val conts = ArrayList<Cont>()
        val showCenter = editMode || grid.items.any { it.c == KeyGrid.CENTER && it.r < n }
        if (!split) {
            val u = innerW / (if (showCenter) 11 else 10)
            conts += Cont(0, 5, u, -1, Matrix().apply { setTranslate(innerLeft, y0) })
            if (showCenter) conts += Cont(5, 1, u, -1, Matrix().apply { setTranslate(innerLeft + 5 * u, y0) })
            conts += Cont(6, 5, u, -1, Matrix().apply { setTranslate(innerLeft + (if (showCenter) 6 else 5) * u, y0) })
        } else if (reserve) {
            val letterH = unit * (n - 1)
            val lx = innerLeft + s.pv - s.cw
            val rx = innerLeft + half + gapPx + half - s.pv
            fun base(x: Float, side: Int, space: Boolean): Matrix {
                val top = y0 + if (space) s.spaceY - letterH else s.letterY
                val pivotY = if (space) y0 + s.spaceY else top + letterH
                val angle = (if (space) spaceTilt else tilt) * (if (side == 0) 1 else -1)
                return Matrix().apply {
                    setTranslate(x, top)
                    postRotate(angle, x + (if (side == 0) s.cw else 0f), pivotY)
                }
            }
            conts += Cont(0, 5, s.cw / 5, 0, base(lx, 0, false), base(lx, 0, true))
            val cwc = max(10 * d, min(s.cw / 5, gapPx - 4 * d))
            conts += Cont(5, 1, cwc, -1, Matrix().apply { setTranslate(innerLeft + half + (gapPx - cwc) / 2, y0 + (hc - n * unit) / 2) })
            conts += Cont(6, 5, s.cw / 5, 1, base(rx, 1, false), base(rx, 1, true))
        } else {
            val lx = innerLeft + (s.pv - s.cw)
            conts += Cont(0, 5, s.cw / 5, 0, Matrix().apply {
                setTranslate(lx, y0); postRotate(tilt, lx + s.cw, y0 + hc); if (tilt < 0) postTranslate(0f, -s.disp)
            })
            val cwc = max(10 * d, min(s.cw / 5, gapPx - 4 * d))
            conts += Cont(5, 1, cwc, -1, Matrix().apply { setTranslate(innerLeft + half + (gapPx - cwc) / 2, y0) })
            val rx = innerLeft + half + gapPx + (half - s.pv)
            conts += Cont(6, 5, s.cw / 5, 1, Matrix().apply {
                setTranslate(rx, y0); postRotate(-tilt, rx, y0 + hc); if (tilt < 0) postTranslate(0f, -s.disp)
            })
        }

        placed.clear()
        cells.clear()
        for (item in grid.items) {
            if (item.r >= n) continue
            for (ct in conts) {
                val a = max(item.c, ct.c0)
                val b = min(item.c + item.w, ct.c0 + ct.cols)
                if (b <= a) continue
                val lc = a - ct.c0
                val rect = RectF(lc * ct.u + gap / 2, item.r * unit + gap / 2, (lc + b - a) * ct.u - gap / 2, (item.r + 1) * unit - gap / 2)
                val m = Matrix()
                // Ergonomic curve: raise the middle fingers' columns and angle each key like a fan.
                if (ct.side >= 0 && curve > 0f && item.r < n - 1) {
                    m.setRotate(ANG[ct.side][lc] * curve, rect.centerX(), rect.centerY())
                    m.postTranslate(0f, -RAISE[ct.side][lc] * curve * unit)
                }
                m.postConcat(if (item.r == n - 1) ct.spaceBase ?: ct.base else ct.base)
                placed += Placed(item, rect, m, Matrix().also { inv -> m.invert(inv) })
            }
        }
        if (editMode) for (ct in conts) for (r in 0 until n) for (c in 0 until ct.cols) {
            val rect = RectF(c * ct.u + gap / 2, r * unit + gap / 2, (c + 1) * ct.u - gap / 2, (r + 1) * unit - gap / 2)
            cells += Cell(r, ct.c0 + c, rect, ct.base, Matrix().also { inv -> ct.base.invert(inv) })
        }

        // The gear sits between the wheels: in the split gap, or in a small gap of its own.
        if (split) {
            wheels[0].rect.set(innerLeft, 0f, innerLeft + half, stripH)
            wheels[1].rect.set(innerLeft + half + gapPx, 0f, innerLeft + innerW, stripH)
            gearRect.set(innerLeft + half, 0f, innerLeft + half + gapPx, stripH)
        } else {
            val mid = innerLeft + innerW / 2
            val g = if (inKeyboard) 18 * d else 0f
            wheels[0].rect.set(innerLeft, 0f, mid - g, stripH)
            wheels[1].rect.set(mid + g, 0f, innerLeft + innerW, stripH)
            gearRect.set(mid - g, 0f, mid + g, stripH)
        }
        if (!inKeyboard) gearRect.setEmpty()
        // Toolbar while rearranging: undo, hint, then settings and Done when inside the keyboard.
        val right = innerLeft + innerW
        undoRect.set(innerLeft, 0f, innerLeft + 48 * d, stripH)
        if (inKeyboard) {
            doneRect.set(right - 64 * d, 0f, right, stripH)
            moreRect.set(doneRect.left - 44 * d, 0f, doneRect.left, stripH)
            hintRect.set(undoRect.right, 0f, moreRect.left, stripH)
        } else {
            doneRect.setEmpty()
            moreRect.setEmpty()
            hintRect.set(undoRect.right, 0f, right - undoRect.width(), stripH)
        }
        handleRect.set(areaLeft, y0 + hc, areaLeft + areaW, y0 + hc + handleH)
        if (split && !editMode) cursorRect.set(innerLeft + half, stripH, innerLeft + half + gapPx, handleRect.top)
        else cursorRect.setEmpty()
        val minSpace = 40 * d
        leftSpace = if (areaLeft >= minSpace) RectF(0f, stripH, areaLeft, y0 + hc) else null
        rightSpace = if (width - (areaLeft + areaW) >= minSpace) RectF(areaLeft + areaW, stripH, width.toFloat(), y0 + hc) else null
        val letters = placed.filter { !it.item.isStack && it.item.keys[0].let { k -> k.length == 1 && k[0].isLetter() } }
        letterCentres = letters.associate { pk ->
            val pt = floatArrayOf(pk.rect.centerX(), pk.rect.centerY())
            pk.matrix.mapPoints(pt)
            pk.item.keys[0][0].lowercaseChar() to GestureDecoder.Point(pt[0], pt[1])
        }
        letterWidth = letters.map { it.rect.width() }.sorted().let { if (it.isEmpty()) 1f else it[it.size / 2] }
        builtWidth = width
        accessibilityEvent(View.NO_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
        return ceil(if (reserve) s.total else max(target, s.total)).toInt()
    }

    /** The face a swipe key shows: the one you swiped to, unless another key switched to a different layer. */
    private fun shownIdx(item: GridKey): Int {
        val n = item.keys.size
        val j = item.idx % n
        val k = item.keys[j]
        if (n > 1 && KeyText.isLayerKey(k) && KeyText.layerOf(k) != state.layer) {
            val match = item.keys.indexOfFirst { KeyText.isLayerKey(it) && KeyText.layerOf(it) == state.layer }
            if (match >= 0) return match
        }
        return j
    }

    // ---- Accessibility ----

    private val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
    private var accessibilityFocus = A_NONE
    private var hoveredView = A_NONE
    private var spokenLayer = state.layer
    private var spokenCapsLock = state.capsLock
    private var spokenShift = state.shift
    private var spokenEnterLabel: String? = null

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    private fun accessibilityIds(): List<Int> {
        val ids = placed.indices.toMutableList()
        if (editMode) {
            ids += A_UNDO
            if (!moreRect.isEmpty) ids += A_SETTINGS
            if (!doneRect.isEmpty) ids += A_DONE
        } else {
            ids += A_WHEEL_LEFT; ids += A_WHEEL_RIGHT
            if (gearRect.width() >= 20 * density) ids += A_GEAR
            if (!cursorRect.isEmpty) ids += A_CURSOR
        }
        return ids
    }

    private fun accessibilityRect(id: Int): RectF = when (id) {
        A_WHEEL_LEFT -> wheels[0].rect
        A_WHEEL_RIGHT -> wheels[1].rect
        A_GEAR -> gearRect
        A_CURSOR -> cursorRect
        A_UNDO -> undoRect
        A_SETTINGS -> moreRect
        A_DONE -> doneRect
        else -> RectF(placed[id].rect).also { placed[id].matrix.mapRect(it) }
    }

    private fun accessibilityValue(id: Int): String? {
        placed.getOrNull(id)?.item?.let { return KeyText.spoken(it.keys[shownIdx(it)], state, enterLabel) }
        val wheel = when (id) { A_WHEEL_LEFT -> wheels[0]; A_WHEEL_RIGHT -> wheels[1]; else -> return null }
        val list = words(wheel)
        return if (list.isEmpty()) null else wordAt(list, wheel.index)
    }

    private fun accessibilityLabel(id: Int): String = when (id) {
        A_WHEEL_LEFT -> "More suggestions"
        A_WHEEL_RIGHT -> "Suggestions"
        A_GEAR -> "Rearrange keys"
        A_CURSOR -> "Cursor"
        A_UNDO -> "Undo"
        A_SETTINGS -> "Settings"
        A_DONE -> "Done"
        else -> {
            val item = placed[id].item
            val face = accessibilityValue(id)!!
            if (item.isStack) "$face, swipe key, ${item.keys.size} options" else face
        }
    }

    // Older TalkBack versions identify keyboard keys by their class name.
    private fun accessibilityClassName(id: Int): String = when {
        id == View.NO_ID -> javaClass.name
        id in placed.indices -> "android.inputmethodservice.Keyboard\$Key"
        else -> "android.widget.Button"
    }

    private fun accessibilityEvent(id: Int, type: Int, change: Int = 0) {
        if (!accessibilityManager.isEnabled) return
        val event = AccessibilityEvent.obtain(type)
        event.packageName = context.packageName
        event.className = accessibilityClassName(id)
        event.isEnabled = isEnabled
        event.setSource(this, id)
        if (id != View.NO_ID && id in accessibilityIds()) {
            val value = accessibilityValue(id)
            event.contentDescription = if (type == AccessibilityEvent.TYPE_VIEW_SELECTED) value else accessibilityLabel(id)
            value?.let { event.text.add(it) }
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) event.contentChangeTypes = change
        parent?.requestSendAccessibilityEvent(this, event)
    }

    private fun refreshAccessibilityState() {
        val layerChanged = spokenLayer != state.layer
        val capsChanged = spokenCapsLock != state.capsLock
        val changed = layerChanged || capsChanged || spokenShift != state.shift || spokenEnterLabel != enterLabel
        spokenLayer = state.layer; spokenCapsLock = state.capsLock; spokenShift = state.shift; spokenEnterLabel = enterLabel
        if (layerChanged) announceForAccessibility(when (state.layer) { Layer.ABC -> "Letters"; Layer.NUM -> "Numbers"; Layer.SYM -> "Symbols" })
        if (capsChanged) announceForAccessibility(if (state.capsLock) "Caps lock on" else "Caps lock off")
        if (changed) accessibilityEvent(View.NO_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
    }

    private fun clearAccessibilityFocus() {
        if (accessibilityFocus == A_NONE) return
        val old = accessibilityFocus
        accessibilityFocus = A_NONE
        accessibilityEvent(old, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
    }

    private fun updateHoveredView(id: Int) {
        if (hoveredView == id) return
        val old = hoveredView
        hoveredView = id
        if (id != A_NONE) accessibilityEvent(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
        if (old != A_NONE) accessibilityEvent(old, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (accessibilityManager.isEnabled && accessibilityManager.isTouchExplorationEnabled) return onHoverEvent(event)
        return super.dispatchHoverEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!accessibilityManager.isEnabled || !accessibilityManager.isTouchExplorationEnabled) return super.onHoverEvent(event)
        ensureLayout()
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val x = event.x; val y = event.y
                val id = if (y < stripH) {
                    accessibilityIds().firstOrNull { it >= A_WHEEL_LEFT && accessibilityRect(it).contains(x, y) } ?: A_NONE
                } else {
                    findKey(x, y, strict = true)?.let { placed.indexOf(it) }
                        ?: if (!editMode && cursorRect.contains(x, y)) A_CURSOR else A_NONE
                }
                updateHoveredView(id)
            }
            MotionEvent.ACTION_HOVER_EXIT -> updateHoveredView(A_NONE)
            else -> return false
        }
        return true
    }

    private fun activateAccessibleView(id: Int): Boolean {
        if (id == A_CURSOR || id == A_UNDO && history.isEmpty()) return false
        if ((id == A_WHEEL_LEFT || id == A_WHEEL_RIGHT) && accessibilityValue(id) == null) return false
        accessibilityEvent(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
        when (id) {
            A_WHEEL_LEFT, A_WHEEL_RIGHT -> listener?.onSuggestion(accessibilityValue(id) ?: return false)
            A_GEAR -> editMode = true
            A_UNDO -> { if (history.isEmpty()) return false; undo() }
            A_SETTINGS -> listener?.onOpenSettings()
            A_DONE -> editMode = false
            A_CURSOR -> return false
            else -> {
                val item = placed[id].item
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                listener?.onKey(item.keys[shownIdx(item)])
            }
        }
        refreshAccessibilityState()
        invalidate()
        return true
    }

    private fun adjustAccessibleView(id: Int, step: Int): Boolean {
        when (id) {
            A_WHEEL_LEFT, A_WHEEL_RIGHT -> {
                val wheel = wheels[id - A_WHEEL_LEFT]
                val list = words(wheel)
                if (list.isEmpty()) return false
                wheel.index = (wheel.index + step + list.size) % list.size
                wheel.dragDx = 0f
            }
            A_CURSOR -> { listener?.onCursor(step, select = false); return true }
            else -> {
                val item = placed.getOrNull(id)?.item ?: return false
                if (!item.isStack) return false
                item.idx = (shownIdx(item) + step + item.keys.size) % item.keys.size
                listener?.onStackFace(item.keys[item.idx])
            }
        }
        refreshAccessibilityState()
        invalidate()
        accessibilityEvent(id, AccessibilityEvent.TYPE_VIEW_SELECTED)
        return true
    }

    private val accessibilityProvider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            ensureLayout()
            if (virtualViewId == View.NO_ID) return AccessibilityNodeInfo.obtain(this@KeyboardView).also {
                onInitializeAccessibilityNodeInfo(it)
                for (id in accessibilityIds()) it.addChild(this@KeyboardView, id)
            }
            val id = virtualViewId
            if (id !in accessibilityIds()) return null
            return AccessibilityNodeInfo.obtain().also {
                it.setSource(this@KeyboardView, id)
                it.setParent(this@KeyboardView)
                it.packageName = context.packageName
                it.className = accessibilityClassName(id)
                it.contentDescription = accessibilityLabel(id)
                it.isEnabled = isEnabled && (id != A_UNDO || history.isNotEmpty())
                it.isVisibleToUser = isShown && windowVisibility == VISIBLE
                it.isFocusable = true
                it.isAccessibilityFocused = accessibilityFocus == id
                val bounds = Rect()
                accessibilityRect(id).roundOut(bounds)
                it.setBoundsInParent(bounds)
                val location = IntArray(2)
                getLocationOnScreen(location)
                bounds.offset(location[0] - scrollX, location[1] - scrollY)
                it.setBoundsInScreen(bounds)
                val item = placed.getOrNull(id)?.item
                val wheel = id == A_WHEEL_LEFT || id == A_WHEEL_RIGHT
                val value = accessibilityValue(id)
                if (wheel) it.text = value
                if (Build.VERSION.SDK_INT >= 29) it.isTextEntryKey = item != null
                if (Build.VERSION.SDK_INT >= 30 && (item?.isStack == true || wheel)) it.stateDescription = value
                it.isClickable = id != A_CURSOR && (!wheel || value != null)
                if (it.isClickable) it.addAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (item?.isStack == true || wheel && value != null || id == A_CURSOR) {
                    it.isScrollable = true
                    it.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    it.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
                it.addAction(if (accessibilityFocus == id) AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS else AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            }
        }

        override fun findFocus(focus: Int): AccessibilityNodeInfo? =
            if (focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY && accessibilityFocus != A_NONE) createAccessibilityNodeInfo(accessibilityFocus) else null

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualViewId == View.NO_ID) return performAccessibilityAction(action, arguments)
            ensureLayout()
            if (virtualViewId !in accessibilityIds()) return false
            when (action) {
                AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (accessibilityFocus == virtualViewId || !accessibilityManager.isEnabled) return false
                    clearAccessibilityFocus()
                    accessibilityFocus = virtualViewId
                    accessibilityEvent(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    return true
                }
                AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (accessibilityFocus != virtualViewId) return false
                    clearAccessibilityFocus()
                    return true
                }
            }
            if (!isEnabled) return false
            return when (action) {
                AccessibilityNodeInfo.ACTION_CLICK -> activateAccessibleView(virtualViewId)
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> adjustAccessibleView(virtualViewId, 1)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> adjustAccessibleView(virtualViewId, -1)
                else -> false
            }
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibilityProvider

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        ensureLayout()
        refreshAccessibilityState()
        canvas.drawColor(palette.background)
        if (editMode) {
            drawToolbar(canvas)
            drawCells(canvas)
        } else {
            drawWheels(canvas)
            if (gearRect.width() >= 20 * density) drawGear(canvas, gearRect.centerX(), gearRect.centerY(), 20 * density, palette.hint)
        }
        val radius = settings.int(Prefs.CORNER_RADIUS) * density
        if (!editMode && !cursorRect.isEmpty) drawCursor(canvas, radius)
        val pressed = trackers.values.mapNotNull { it.pk?.item }.toSet()
        val now = SystemClock.uptimeMillis()
        for (pk in placed) {
            canvas.save()
            canvas.concat(pk.matrix)
            if (editMode) canvas.rotate(wiggle(pk.item, now), pk.rect.centerX(), pk.rect.centerY())
            drawKey(canvas, pk.item, pk.rect, radius, pk.item in pressed, if (drag?.item === pk.item) 0.3f else 1f)
            drawDropMark(canvas, pk)
            canvas.restore()
        }
        drawHandle(canvas)
        leftSpace?.let { drawMoveArrow(canvas, it, pointsLeft = true) }
        rightSpace?.let { drawMoveArrow(canvas, it, pointsLeft = false) }
        drag?.let { drawGhost(canvas, it, radius) }
        drawGlide(canvas, now)
        if (editMode) postInvalidateOnAnimation()
    }

    private fun drawGlide(canvas: Canvas, now: Long) {
        val points = trail
        if (points.size < 2) return
        stroke.color = palette.accent
        stroke.strokeWidth = 4 * density
        var visible = false
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val alpha = (1f - (now - b.time) / 300f).coerceIn(0f, 1f)
            if (alpha <= 0f) continue
            stroke.alpha = (alpha * 220).toInt()
            canvas.drawLine(a.x, a.y, b.x, b.y, stroke)
            visible = true
        }
        stroke.alpha = 255
        if (visible) postInvalidateOnAnimation() else if (glideId == null) trail = emptyList()
    }

    private fun wiggle(item: GridKey, now: Long) = (1.6 * sin(2 * PI * (now % 320) / 320.0 + item.id * 0.9)).toFloat()

    private fun drawKey(canvas: Canvas, item: GridKey, rect: RectF, radius: Float, pressed: Boolean, alpha: Float) {
        val face = shownIdx(item)
        val code = item.keys[face]
        val lock = code == Keys.ABC && state.layer == Layer.ABC && state.capsLock
        val oneShot = code == Keys.ABC && state.layer == Layer.ABC && state.shift && !state.capsLock
        val accentBg = code == Keys.ENTER || lock
        fill.color = when {
            pressed -> palette.pressed
            accentBg -> palette.accent
            KeyText.isLayerKey(code) || code == Keys.BACKSPACE -> palette.functionKey
            else -> palette.key
        }
        fill.alpha = (255 * alpha).toInt()
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (settings.bool(Prefs.KEY_TINT) && state.layer == Layer.ABC && !editMode && !pressed &&
            !item.isStack && code.length == 1 && code[0].isLetter() && keyWeights.isNotEmpty()) {
            fill.color = palette.accent
            fill.alpha = (56 * (keyWeights[code[0].lowercaseChar()] ?: 0.0)).roundToInt()
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        fill.alpha = 255
        val fg = when { accentBg && !pressed -> palette.onAccent; oneShot -> palette.accent; else -> palette.text }

        canvas.save()
        canvas.clipRect(rect)
        val sw = swipe?.takeIf { it.item === item }
        val dy = sw?.dy ?: 0f
        drawFace(canvas, code, rect, dy, fg, alpha)
        if (item.isStack && dy != 0f) {
            val n = item.keys.size
            drawFace(canvas, item.keys[(face + 1) % n], rect, rect.height() + dy, fg, alpha)
            drawFace(canvas, item.keys[(face + n - 1) % n], rect, -rect.height() + dy, fg, alpha)
        }
        canvas.restore()

        if (item.isStack) {
            val n = item.keys.size
            val r = 1.5f * density
            val step = 5 * density
            val top = rect.centerY() - (n - 1) * step / 2
            for (i in 0 until n) {
                fill.color = fg
                fill.alpha = ((if (i == face) 230 else 80) * alpha).toInt()
                canvas.drawCircle(rect.left + 5 * density, top + i * step, r, fill)
            }
            fill.alpha = 255
        }
        if (!item.isStack && grid.rows == 4 && settings.bool(Prefs.SHOW_HINTS)) KeyText.hint(code, state)?.let {
            text.color = palette.hint
            text.textSize = rect.height() * 0.2f
            canvas.drawText(it, rect.right - text.textSize * 0.7f, rect.top + text.textSize * 1.1f, text)
        }
        if (editMode && item.isStack) {
            fill.color = palette.accent
            val cx = rect.right - 8 * density
            val cy = rect.top + 8 * density
            canvas.drawCircle(cx, cy, 7 * density, fill)
            stroke.color = palette.onAccent
            stroke.strokeWidth = 1.5f * density
            val k = 2.5f * density
            canvas.drawLine(cx - k, cy - k, cx + k, cy + k, stroke)
            canvas.drawLine(cx - k, cy + k, cx + k, cy - k, stroke)
        }
    }

    private fun drawFace(canvas: Canvas, code: String, rect: RectF, offsetY: Float, color: Int, alpha: Float) {
        val r = RectF(rect).apply { offset(0f, offsetY) }
        val scale = settings.int(Prefs.TEXT_SCALE) / 100f
        val icon = min(r.width(), r.height()) * 0.42f * scale
        val a = (255 * alpha).toInt()
        when (code) {
            Keys.BACKSPACE -> drawDelete(canvas, r, icon, color, a)
            Keys.ENTER -> enterLabel?.let { drawLabel(canvas, r, it, r.height() * 0.3f * scale, color, a) } ?: drawReturn(canvas, r, icon, color, a)
            Keys.SPACE -> Unit
            else -> {
                val label = KeyText.label(code, state)
                val size = min(r.height() * 0.42f, 22 * density) * scale
                drawLabel(canvas, r, label, if (label.length > 1) size * 0.72f else size, color, a)
            }
        }
    }

    private fun drawLabel(canvas: Canvas, r: RectF, label: String, size: Float, color: Int, alpha: Int) {
        text.color = color
        text.alpha = alpha
        text.textSize = size
        val maxWidth = r.width() * 0.85f
        val measured = text.measureText(label)
        if (measured > maxWidth) text.textSize = size * maxWidth / measured
        canvas.drawText(label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2f, text)
    }

    private fun shape(cx: Float, cy: Float, s: Float, close: Boolean, vararg p: Float): Path {
        path.reset()
        path.moveTo(cx + p[0] * s, cy + p[1] * s)
        for (i in 2 until p.size step 2) path.lineTo(cx + p[i] * s, cy + p[i + 1] * s)
        if (close) path.close()
        return path
    }

    private fun strokeIn(color: Int, s: Float, alpha: Int) = stroke.apply { this.color = color; this.alpha = alpha; strokeWidth = s * 0.09f }

    private fun drawDelete(canvas: Canvas, r: RectF, s: Float, color: Int, alpha: Int) {
        val p = strokeIn(color, s, alpha)
        canvas.drawPath(shape(r.centerX(), r.centerY(), s, true, -0.55f, 0f, -0.22f, -0.38f, 0.55f, -0.38f, 0.55f, 0.38f, -0.22f, 0.38f), p)
        canvas.drawPath(shape(r.centerX(), r.centerY(), s, false, 0f, -0.15f, 0.3f, 0.15f), p)
        canvas.drawPath(shape(r.centerX(), r.centerY(), s, false, 0f, 0.15f, 0.3f, -0.15f), p)
    }

    private fun drawReturn(canvas: Canvas, r: RectF, s: Float, color: Int, alpha: Int) {
        val p = strokeIn(color, s, alpha)
        canvas.drawPath(shape(r.centerX(), r.centerY(), s, false, 0.45f, -0.4f, 0.45f, 0.1f, -0.45f, 0.1f), p)
        canvas.drawPath(shape(r.centerX(), r.centerY(), s, false, -0.2f, -0.15f, -0.45f, 0.1f, -0.2f, 0.35f), p)
    }

    private fun drawGear(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val p = strokeIn(color, s, 255)
        canvas.drawCircle(cx, cy, s * 0.2f, p)
        p.strokeWidth = s * 0.13f
        for (i in 0 until 8) {
            val a = i * PI / 4
            canvas.drawLine(cx + s * 0.3f * cos(a).toFloat(), cy + s * 0.3f * sin(a).toFloat(), cx + s * 0.42f * cos(a).toFloat(), cy + s * 0.42f * sin(a).toFloat(), p)
        }
    }

    /** An arrow curling back to the left. */
    private fun drawUndo(canvas: Canvas, cx: Float, cy: Float, s: Float, color: Int, alpha: Int) {
        val p = strokeIn(color, s, alpha)
        val r = s * 0.3f
        path.reset()
        path.addArc(cx - r, cy - r, cx + r, cy + r, 180f, 225f)
        canvas.drawPath(path, p)
        canvas.drawPath(shape(cx - r, cy + s * 0.06f, s, false, -0.17f, -0.17f, 0f, 0f, 0.17f, -0.17f), p)
    }

    private fun drawToolbar(canvas: Canvas) {
        val s = 20 * density
        drawUndo(canvas, undoRect.centerX(), undoRect.centerY(), s, palette.text, if (history.isEmpty()) 70 else 255)
        val sp = resources.displayMetrics.scaledDensity
        drawLabel(canvas, hintRect, editHint ?: "Drag keys to rearrange", 13 * sp, if (editHint != null) palette.accent else palette.hint, 255)
        if (!doneRect.isEmpty) {
            drawGear(canvas, moreRect.centerX(), moreRect.centerY(), s, palette.hint)
            text.isFakeBoldText = true
            drawLabel(canvas, doneRect, "Done", 15 * sp, palette.accent, 255)
            text.isFakeBoldText = false
        }
    }

    private fun drawMoveArrow(canvas: Canvas, area: RectF, pointsLeft: Boolean) {
        val s = min(area.width() * 0.35f, 18 * density)
        val dir = if (pointsLeft) 1f else -1f
        canvas.drawPath(shape(area.centerX(), area.centerY(), s, false, 0.25f * dir, -0.5f, -0.25f * dir, 0f, 0.25f * dir, 0.5f), strokeIn(palette.hint, s * 1.5f, 255))
    }

    /**
     * While a finger holds still in the gap, a soft glow grows outward from it over the long-press delay.
     * Full size means text selection is on; it then follows the finger. Moving resets it.
     */
    private fun drawCursor(canvas: Canvas, radius: Float) {
        val t = trackers.values.firstOrNull { it.kind == K_CURSOR } ?: return
        val held = (SystemClock.uptimeMillis() - t.holdAt).toFloat() / settings.int(Prefs.LONG_PRESS_MS)
        val p = if (t.select) 1f else held.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        if (!t.select) postInvalidateOnAnimation()
        if (p <= 0.02f) return
        val r = 64 * density * p
        val rgb = palette.accent and 0x00FFFFFF
        fill.shader = RadialGradient(t.fx, t.fy, r, intArrayOf(rgb or ((120 * p).toInt() shl 24), rgb), null, Shader.TileMode.CLAMP)
        canvas.save()
        path.reset()
        path.addRoundRect(cursorRect, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawCircle(t.fx, t.fy, r, fill)
        canvas.restore()
        fill.shader = null
    }

    private fun drawHandle(canvas: Canvas) {
        fill.color = palette.hint
        val w = 44 * density
        val h = 4 * density
        val cx = handleRect.centerX()
        val cy = handleRect.centerY()
        canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2, h / 2, fill)
    }

    private fun drawCells(canvas: Canvas) {
        val radius = settings.int(Prefs.CORNER_RADIUS) * density
        dash.strokeWidth = density
        for (cell in cells) {
            val target = drag?.target?.cell === cell
            dash.color = if (target) palette.accent else palette.hint
            dash.alpha = if (target) 255 else 120
            canvas.save()
            canvas.concat(cell.matrix)
            canvas.drawRoundRect(cell.rect, radius, radius, dash)
            canvas.restore()
        }
    }

    // ---- Suggestion wheels ----

    private fun words(w: Wheel) = if (w.side == 0) sugLeft else sugRight
    private fun wordAt(list: List<String>, i: Int) = list[((i % list.size) + list.size) % list.size]

    /** Horizontal offset of the word [k] places from the chosen one. */
    private fun offsetOf(list: List<String>, index: Int, k: Int): Float {
        val gap = 22 * density
        fun w(j: Int) = wheelText.measureText(wordAt(list, index + j))
        var x = 0f
        if (k > 0) for (j in 0 until k) x += w(j) / 2 + gap + w(j + 1) / 2
        else for (j in 0 downTo k + 1) x -= w(j) / 2 + gap + w(j - 1) / 2
        return x
    }

    /** Neighbours drawn on each side, never more than the list holds, so no word shows twice. */
    private fun wheelRange(n: Int): IntRange {
        val back = min(3, (n - 1) / 2)
        return -back..min(3, n - 1 - back)
    }

    private fun drawWheels(canvas: Canvas) {
        wheelText.textSize = 15 * resources.displayMetrics.scaledDensity
        if (!wheels[0].rect.isEmpty && wheels[1].rect.left == wheels[0].rect.right) {
            fill.color = palette.functionKey
            canvas.drawRect(wheels[0].rect.right - density / 2, stripH * 0.25f, wheels[0].rect.right + density / 2, stripH * 0.75f, fill)
        }
        for (wh in wheels) {
            val list = words(wh)
            if (list.isEmpty()) continue
            canvas.save()
            canvas.clipRect(wh.rect)
            val baseline = wh.rect.centerY() - (wheelText.descent() + wheelText.ascent()) / 2
            for (k in wheelRange(list.size)) {
                val word = wordAt(list, wh.index + k)
                val cx = wh.rect.centerX() + offsetOf(list, wh.index, k) + wh.dragDx
                val alpha = if (k == 0) 255 else 100
                var x = cx - wheelText.measureText(word) / 2
                // The part you've already typed is highlighted.
                if (prefix.isNotEmpty() && word.startsWith(prefix, ignoreCase = true)) {
                    val pre = word.substring(0, prefix.length)
                    wheelText.color = palette.accent; wheelText.alpha = alpha; wheelText.isFakeBoldText = true
                    canvas.drawText(pre, x, baseline, wheelText)
                    x += wheelText.measureText(pre)
                    wheelText.isFakeBoldText = false
                    wheelText.color = palette.text; wheelText.alpha = alpha
                    canvas.drawText(word.substring(prefix.length), x, baseline, wheelText)
                } else {
                    wheelText.color = palette.text; wheelText.alpha = alpha
                    canvas.drawText(word, x, baseline, wheelText)
                }
            }
            canvas.restore()
        }
    }

    // ---- Touch ----

    private inner class Tracker(val kind: Int, var pk: Placed?) {
        var consumed = false
        var moved = false
        var x0 = 0f
        var y0 = 0f
        var lastX = 0f
        var holdX = 0f
        var holdY = 0f
        var holdAt = 0L
        var fx = 0f
        var fy = 0f
        var select = false
        var glideEligible = false
        var glideWidth = 1f
        val points = ArrayList<GestureDecoder.Point>()
        var wheel: Wheel? = null
        val longPress = Runnable { onLongPress(this) }
        val repeat: Runnable = object : Runnable {
            override fun run() {
                listener?.onKey(Keys.BACKSPACE)
                timers.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
    }

    private class Swipe(val item: GridKey) { var dy = 0f; var stepped = false }

    private val timers = Handler(Looper.getMainLooper())
    private val trackers = LinkedHashMap<Int, Tracker>()
    private var swipe: Swipe? = null
    private var glideId: Int? = null
    private var trail: List<GestureDecoder.Point> = emptyList()

    fun cancelTouches() {
        trackers.values.forEach { stopTimers(it); if (it.select) listener?.onSelectMode(false) }
        trackers.clear()
        glideId = null
        trail = emptyList()
        swipe = null
        drag = null
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        ensureLayout()
        if (editMode) return editTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                pointerDown(e.getPointerId(e.actionIndex), e.getX(e.actionIndex), e.getY(e.actionIndex), e.eventTime)
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) for (i in 0 until e.pointerCount) {
                    trackers[e.getPointerId(i)]?.let { t -> recordGlide(e.getPointerId(i), t, e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalEventTime(h)) }
                }
                for (i in 0 until e.pointerCount) pointerMove(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                trackers[e.getPointerId(i)]?.let { recordGlide(e.getPointerId(i), it, e.getX(i), e.getY(i), e.eventTime) }
                pointerUp(e.getPointerId(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float, time: Long) {
        if (glideId != null) return
        // A new finger landing finishes keys other fingers are still holding, for fast rollover typing.
        for (t in trackers.values.toList()) {
            if (t.kind != K_KEY || t.consumed) continue
            stopTimers(t); t.consumed = true; commit(t)
        }
        if (gearRect.contains(x, y)) {
            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            trackers[id] = Tracker(K_GEAR, null)
            return
        }
        wheels.firstOrNull { it.rect.contains(x, y) && words(it).isNotEmpty() }?.let {
            trackers[id] = Tracker(K_WHEEL, null).also { t -> t.wheel = it; t.x0 = x }
            return
        }
        if (handleRect.contains(x, y)) {
            trackers[id] = Tracker(K_HANDLE, null).also { it.x0 = x; it.lastX = x }
            return
        }
        if (leftSpace?.contains(x, y) == true || rightSpace?.contains(x, y) == true) {
            moveKeyboardToward(x, y)
            return
        }
        val hit = findKey(x, y, strict = true)
        if (hit == null && cursorRect.contains(x, y)) {
            trackers[id] = Tracker(K_CURSOR, null).also {
                it.x0 = x; it.holdX = x; it.holdY = y; it.fx = x; it.fy = y
                it.holdAt = SystemClock.uptimeMillis()
                timers.postDelayed(it.longPress, settings.int(Prefs.LONG_PRESS_MS).toLong())
            }
            invalidate()
            return
        }
        val pk = findKey(x, y) ?: return
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        if (pk.item.isStack) {
            trackers[id] = Tracker(K_STACK, pk).also { it.y0 = y }
            swipe = Swipe(pk.item)
            invalidate()
            return
        }
        val t = Tracker(K_KEY, pk)
        t.x0 = x; t.y0 = y; t.glideWidth = pk.rect.width()
        t.glideEligible = glideAllowed && settings.bool(Prefs.SWIPE_TYPING) && state.layer == Layer.ABC &&
            pk.item.keys[0].let { it.length == 1 && it[0].isLetter() }
        if (t.glideEligible) t.points.add(GestureDecoder.Point(x, y, time))
        trackers[id] = t
        if (pk.item.keys[0] == Keys.BACKSPACE) {
            t.consumed = true
            listener?.onKey(Keys.BACKSPACE)
            timers.postDelayed(t.repeat, REPEAT_DELAY_MS)
        } else {
            timers.postDelayed(t.longPress, settings.int(Prefs.LONG_PRESS_MS).toLong())
        }
        invalidate()
    }

    private fun recordGlide(id: Int, t: Tracker, x: Float, y: Float, time: Long): Boolean {
        if (!t.glideEligible || t.consumed && glideId != id) return false
        if (glideId != null && glideId != id) return true
        t.points.add(GestureDecoder.Point(x, y, time))
        if (glideId == null && hypot(x - t.x0, y - t.y0) > 0.6f * t.glideWidth) {
            glideId = id
            stopTimers(t)
            t.consumed = true
            // Existing held controls must not fire on release during this word gesture.
            for ((otherId, other) in trackers.toMap()) if (otherId != id) {
                stopTimers(other)
                if (other.select) listener?.onSelectMode(false)
                trackers.remove(otherId)
            }
            swipe = null
        }
        if (glideId == id) { trail = t.points; invalidate(); return true }
        return false
    }

    private fun pointerMove(id: Int, x: Float, y: Float, time: Long) {
        val t = trackers[id] ?: return
        if (recordGlide(id, t, x, y, time)) return
        when (t.kind) {
            K_WHEEL -> {
                val dx = x - t.x0
                if (abs(dx) > 8 * density) t.moved = true
                if (t.moved) { t.wheel!!.dragDx = dx; invalidate() }
            }
            K_HANDLE -> { t.lastX = x; if (abs(x - t.x0) > 8 * density) t.moved = true }
            K_CURSOR -> {
                t.fx = x; t.fy = y
                invalidate()
                if (!t.select && hypot(x - t.holdX, y - t.holdY) > 6 * density) {
                    t.holdX = x; t.holdY = y
                    t.holdAt = SystemClock.uptimeMillis()
                    timers.removeCallbacks(t.longPress)
                    timers.postDelayed(t.longPress, settings.int(Prefs.LONG_PRESS_MS).toLong())
                }
                val step = 9 * density
                val steps = ((x - t.x0) / step).toInt()
                if (steps != 0) {
                    listener?.onCursor(steps, select = t.select)
                    t.x0 += steps * step
                    repeat(abs(steps)) { haptic(HapticFeedbackConstants.CLOCK_TICK) }
                }
            }
            K_STACK -> {
                // One swipe moves exactly one face; the rest of the gesture is ignored.
                val sw = swipe ?: return
                if (sw.stepped) return
                val dy = y - t.y0
                if (abs(dy) > 5 * density) t.moved = true
                if (!t.moved) return
                val h = t.pk!!.rect.height()
                sw.dy = dy.coerceIn(-h, h)
                if (abs(dy) > h * 0.5f) {
                    val item = sw.item
                    val n = item.keys.size
                    val j = (shownIdx(item) + (if (dy < 0) 1 else -1) + n) % n
                    item.idx = j
                    sw.stepped = true
                    sw.dy = 0f
                    listener?.onStackFace(item.keys[j])
                    accessibilityEvent(placed.indexOf(t.pk), AccessibilityEvent.TYPE_VIEW_SELECTED)
                    haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                invalidate()
            }
            K_KEY -> {
                // Sliding across letters changes which letter is typed when the finger lifts.
                if (t.consumed || !KeyText.isChar(t.pk!!.item.keys[0])) return
                val pk = findKey(x, y) ?: return
                if (pk === t.pk || pk.item.isStack || !KeyText.isChar(pk.item.keys[0])) return
                t.pk = pk
                timers.removeCallbacks(t.longPress)
                timers.postDelayed(t.longPress, settings.int(Prefs.LONG_PRESS_MS).toLong())
                invalidate()
            }
        }
    }

    private fun pointerUp(id: Int) {
        val t = trackers.remove(id) ?: return
        stopTimers(t)
        if (glideId == id) {
            glideId = null
            listener?.onGlide(t.points.toList(), letterCentres, letterWidth)
            invalidate()
            return
        }
        when (t.kind) {
            K_WHEEL -> {
                val wh = t.wheel!!
                val list = words(wh)
                if (t.moved) {
                    // Settle on whichever word is nearest the middle.
                    var best = 0
                    var bestDist = Float.MAX_VALUE
                    for (k in wheelRange(list.size)) {
                        val dist = abs(offsetOf(list, wh.index, k) + wh.dragDx)
                        if (dist < bestDist) { bestDist = dist; best = k }
                    }
                    wh.index = ((wh.index + best) % list.size + list.size) % list.size
                    accessibilityEvent(A_WHEEL_LEFT + wh.side, AccessibilityEvent.TYPE_VIEW_SELECTED)
                } else if (list.isNotEmpty()) {
                    listener?.onSuggestion(wordAt(list, wh.index))
                }
                wh.dragDx = 0f
            }
            K_HANDLE -> if (t.moved && abs(t.lastX - t.x0) > 36 * density) slideKeyboard(t.lastX < t.x0)
            K_STACK -> {
                swipe = null
                if (!t.moved) listener?.onKey(t.pk!!.item.keys[shownIdx(t.pk!!.item)])
            }
            K_KEY -> if (!t.consumed) commit(t)
            K_GEAR -> editMode = true
            K_CURSOR -> if (t.select) listener?.onSelectMode(false)
        }
        invalidate()
    }

    private fun commit(t: Tracker) {
        val item = t.pk?.item ?: return
        listener?.onKey(item.keys[0])
    }

    private fun onLongPress(t: Tracker) {
        if (t.kind == K_CURSOR) {
            t.select = true
            haptic(HapticFeedbackConstants.LONG_PRESS)
            listener?.onSelectMode(true)
            invalidate()
            return
        }
        val code = t.pk?.item?.keys?.get(0) ?: return
        if (code == Keys.SPACE) {
            listener?.onPickIme()
        } else {
            val alt = KeyText.longPress(code, state) ?: return
            listener?.onLongPressText(alt)
        }
        t.consumed = true
        haptic(HapticFeedbackConstants.LONG_PRESS)
    }

    /** The key under a point. Gaps and edges belong to the nearest key, so taps never fall through. */
    private fun findKey(x: Float, y: Float, strict: Boolean = false, skip: GridKey? = null): Placed? {
        val pt = FloatArray(2)
        val slack = settings.int(Prefs.KEY_GAP) * density / 2 + 1
        var hit: Placed? = null
        for (i in placed.indices.reversed()) {
            val pk = placed[i]
            if (pk.item === skip) continue
            pt[0] = x; pt[1] = y
            pk.inverse.mapPoints(pt)
            val r = pk.rect
            if (pt[0] >= r.left - slack && pt[0] <= r.right + slack && pt[1] >= r.top - slack && pt[1] <= r.bottom + slack) {
                hit = pk
                break
            }
        }
        fun isLetter(pk: Placed) = !pk.item.isStack && pk.item.keys[0].let { it.length == 1 && it[0].isLetter() }
        if (!strict && !editMode && state.layer == Layer.ABC && settings.bool(Prefs.SMART_TARGETS) &&
            keyWeights.isNotEmpty() && (hit == null || isLetter(hit))) {
            var best: Placed? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (pk in placed) {
                if (pk.item === skip || !isLetter(pk)) continue
                pt[0] = x; pt[1] = y
                pk.inverse.mapPoints(pt)
                val r = pk.rect
                val core = RectF(r).apply { inset(width() * 0.3f, height() * 0.3f) }
                if (core.contains(pt[0], pt[1])) return pk
                val target = RectF(r).apply { inset(-width() * 0.35f, -height() * 0.35f) }
                if (!target.contains(pt[0], pt[1])) continue
                val dx = ((pt[0] - r.centerX()) / r.width()).toDouble()
                val dy = ((pt[1] - r.centerY()) / r.height()).toDouble()
                val weight = keyWeights[pk.item.keys[0][0].lowercaseChar()] ?: 0.0
                val score = -(dx * dx + dy * dy) / (2 * 0.35 * 0.35) + ln(0.05 + weight)
                if (score > bestScore) { bestScore = score; best = pk }
            }
            if (best != null) return best
        }
        if (hit != null) return hit
        if (strict || y < stripH) return null
        var best: Placed? = null
        var bestDist = Float.MAX_VALUE
        for (pk in placed) {
            if (pk.item === skip) continue
            pt[0] = pk.rect.centerX(); pt[1] = pk.rect.centerY()
            pk.matrix.mapPoints(pt)
            val dist = hypot(pt[0] - x, pt[1] - y)
            if (dist < bestDist) { bestDist = dist; best = pk }
        }
        return if (bestDist < unit * 1.2f) best else null
    }

    private fun moveKeyboardToward(x: Float, y: Float) {
        val align = when {
            leftSpace?.contains(x, y) == true -> Align.LEFT
            rightSpace?.contains(x, y) == true -> Align.RIGHT
            else -> return
        }
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        settings.setAlign(isLandscape, align)
    }

    /** Dragging the bar under the keys: full width shrinks to one-hand size on that side; the other way restores it. */
    private fun slideKeyboard(left: Boolean) {
        val land = isLandscape
        val p = settings.profile(land)
        val dir = if (left) Align.LEFT else Align.RIGHT
        when {
            p.widthPercent >= 100 -> {
                restoreWidth = 100
                settings.set(Prefs.width(land), if (p.split) 70 else 78)
                settings.setAlign(land, dir)
            }
            p.align == Align.CENTER -> settings.setAlign(land, dir)
            p.align != dir -> {
                settings.setAlign(land, Align.CENTER)
                restoreWidth?.let { settings.set(Prefs.width(land), it) }
                restoreWidth = null
            }
        }
    }

    private fun stopTimers(t: Tracker) {
        timers.removeCallbacks(t.longPress)
        timers.removeCallbacks(t.repeat)
    }

    fun typoBuzz() {
        performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }

    private fun haptic(type: Int) {
        if (settings.bool(Prefs.HAPTICS)) performHapticFeedback(type)
    }

    override fun onDetachedFromWindow() {
        clearAccessibilityFocus()
        updateHoveredView(A_NONE)
        cancelTouches()
        super.onDetachedFromWindow()
    }

    // ---- Edit mode: drag and drop ----

    private class Target(val type: Int, val item: GridKey? = null, val before: Boolean = false, val r: Int = 0, val c: Int = 0, val pk: Placed? = null, val cell: Cell? = null)
    private class Drag(val item: GridKey, val w: Float, val h: Float, var x: Float, var y: Float, val offX: Float, val offY: Float) { var target: Target? = null }

    private var drag: Drag? = null

    private fun editTouch(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (y < stripH) {
                    when {
                        undoRect.contains(x, y) -> undo()
                        moreRect.contains(x, y) -> listener?.onOpenSettings()
                        doneRect.contains(x, y) -> editMode = false
                    }
                    return true
                }
                val pk = findKey(x, y, strict = true) ?: return true
                val pt = floatArrayOf(x, y)
                pk.inverse.mapPoints(pt)
                if (pk.item.isStack && hypot(pt[0] - (pk.rect.right - 8 * density), pt[1] - (pk.rect.top + 8 * density)) < 12 * density) {
                    val before = grid.toJson()
                    grid.separate(pk.item)
                    edited(before)
                    return true
                }
                drag = Drag(pk.item, pk.rect.width(), pk.rect.height(), x, y, pt[0] - pk.rect.centerX(), pt[1] - pk.rect.centerY())
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dg = drag ?: return true
                dg.x = x; dg.y = y
                dg.target = editTarget(x, y, dg)
                editHint = hintFor(dg.target)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val dg = drag ?: return true
                drag = null
                editHint = null
                val before = grid.toJson()
                applyDrop(dg)
                edited(before)
            }
            MotionEvent.ACTION_CANCEL -> { drag = null; editHint = null; invalidate() }
        }
        return true
    }

    /** Saves the layout as it was [before] an edit, so undo can return to it. Edits that changed nothing are skipped. */
    private fun edited(before: String) {
        grid.normalize()
        if (grid.toJson() != before) {
            history += before
            if (history.size > MAX_UNDO) history.removeAt(0)
            listener?.onEdited()
        }
        relayout()
    }

    /** Steps back one edit. */
    fun undo() {
        val prev = history.removeLastOrNull()?.let { KeyGrid.fromJson(it) } ?: return
        keepHistory = true
        grid = prev
        keepHistory = false
        listener?.onEdited()
    }

    /** Swaps in a whole new layout, such as a template or an imported code, as one step undo can reverse. */
    fun replaceGrid(g: KeyGrid) {
        val before = grid.toJson()
        keepHistory = true
        grid = g
        keepHistory = false
        if (g.toJson() != before) {
            history += before
            listener?.onEdited()
        }
    }

    private fun editTarget(x: Float, y: Float, dg: Drag): Target? {
        findKey(x, y, strict = true, skip = dg.item)?.let { pk ->
            val item = pk.item
            if (item.isSpace && item.w > 1 && KeyGrid.region(item.c) != 1 && !dg.item.isSpace) return Target(T_SPACE, item, pk = pk)
            val pt = floatArrayOf(x, y)
            pk.inverse.mapPoints(pt)
            val rel = (pt[0] - pk.rect.left) / pk.rect.width()
            return if (rel > 0.25f && rel < 0.75f) Target(T_COMBINE, item, pk = pk) else Target(T_INSERT, item, before = rel <= 0.25f, pk = pk)
        }
        val pt = FloatArray(2)
        for (cell in cells) {
            pt[0] = x; pt[1] = y
            cell.inverse.mapPoints(pt)
            if (cell.rect.contains(pt[0], pt[1])) return Target(T_MOVE, r = cell.r, c = cell.c, cell = cell)
        }
        return null
    }

    private fun hintFor(t: Target?) = when (t?.type) {
        T_COMBINE -> "Combine into a swipe key"
        T_INSERT -> "Insert, neighbours shift over"
        T_SPACE -> "Sit beside the spacebar"
        T_MOVE -> "Move here"
        else -> null
    }

    private fun applyDrop(dg: Drag) {
        val t = dg.target ?: return
        when (t.type) {
            T_MOVE -> grid.moveTo(dg.item, t.r, t.c)
            T_SPACE -> grid.insertBesideSpace(dg.item, t.item!!)
            T_COMBINE -> grid.combine(dg.item, t.item!!)
            T_INSERT -> if (!grid.insertShift(dg.item, t.item!!, t.before)) grid.swap(dg.item, t.item)
        }
    }

    private fun drawDropMark(canvas: Canvas, pk: Placed) {
        val t = drag?.target ?: return
        if (t.pk !== pk) return
        stroke.color = palette.accent
        stroke.alpha = 255
        val r = pk.rect
        when (t.type) {
            T_INSERT -> {
                fill.color = palette.accent
                val w = 4 * density
                if (t.before) canvas.drawRect(r.left, r.top, r.left + w, r.bottom, fill) else canvas.drawRect(r.right - w, r.top, r.right, r.bottom, fill)
            }
            else -> {
                stroke.strokeWidth = (if (t.type == T_COMBINE) 3 else 2) * density
                val radius = settings.int(Prefs.CORNER_RADIUS) * density
                canvas.drawRoundRect(r, radius, radius, stroke)
            }
        }
    }

    private fun drawGhost(canvas: Canvas, dg: Drag, radius: Float) {
        val rect = RectF(0f, 0f, dg.w, dg.h)
        canvas.save()
        canvas.translate(dg.x - dg.offX - dg.w / 2, dg.y - dg.offY - dg.h / 2)
        canvas.scale(1.1f, 1.1f, dg.w / 2, dg.h / 2)
        drawKey(canvas, dg.item, rect, radius, false, 0.92f)
        canvas.restore()
    }

    private companion object {
        const val A_NONE = Int.MIN_VALUE
        const val A_WHEEL_LEFT = 10000
        const val A_WHEEL_RIGHT = 10001
        const val A_GEAR = 10002
        const val A_CURSOR = 10003
        const val A_UNDO = 10004
        const val A_SETTINGS = 10005
        const val A_DONE = 10006
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
        const val K_KEY = 0
        const val K_STACK = 1
        const val K_WHEEL = 2
        const val K_HANDLE = 3
        const val K_GEAR = 4
        const val K_CURSOR = 5
        const val MAX_UNDO = 50
        const val T_MOVE = 0
        const val T_SPACE = 1
        const val T_COMBINE = 2
        const val T_INSERT = 3

        /** Column raise (fraction of a row) and fan angle (degrees) for each half, outer to inner on the left. */
        val RAISE = arrayOf(floatArrayOf(0f, .22f, .38f, .28f, .16f), floatArrayOf(.16f, .28f, .38f, .22f, 0f))
        val ANG = arrayOf(floatArrayOf(-9f, -4f, 0f, 3f, 6f), floatArrayOf(-6f, -3f, 0f, 4f, 9f))
    }
}
