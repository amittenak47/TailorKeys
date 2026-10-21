package app.tailorkeys

import android.animation.ValueAnimator
import android.provider.Settings
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RadialGradient
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
 * In [editMode] (Rearrange in quick options, or the settings screen) the keys lay flat and wiggle, and
 * dragging a key moves it: onto an empty cell, onto another key's edge (insert and shift), onto its middle
 * (combine into a swipe key), or onto a spacebar (sit on its inner side while it shrinks). The suggestion bar
 * turns into a toolbar with undo, a hint for the current drag and, inside the keyboard, settings and Done.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val settings: KeyboardSettings) : View(context) {

    interface Listener {
        fun onChord(word: String) {}
        fun onForwardDelete() {}
        fun onKey(code: String)
        fun onTapKey(code: String, distances: FloatArray) { onKey(code) }
        fun onReplacePressText(original: String, following: String, replacement: String) {}
        fun onLongPressText(text: String)
        fun onStackFace(code: String)
        fun onWheelChosen() {}
        fun onChoiceInterrupted() {}
        fun onForgetWord(word: String) {}
        fun onSuggestion(text: String)
        fun onGlideStart() {}
        fun onGlideProgress(points: List<GestureDecoder.Point>, keys: Map<Char, GestureDecoder.Point>, keyWidth: Float, raw: String, hints: List<GestureDecoder.DoubleHint>) {}
        fun onGlideCancel() {}
        fun onGlide(points: List<GestureDecoder.Point>, keys: Map<Char, GestureDecoder.Point>, keyWidth: Float, raw: String, hints: List<GestureDecoder.DoubleHint>) {}
        fun onSwitchLanguage(direction: Int) {}
        fun onPickIme()
        fun onCursor(steps: Int, select: Boolean) {}
        fun onSelectMode(on: Boolean) {}
        fun onEdited() {}
        fun onEditDone() {}
        fun onOpenSettings() {}
        fun onQuickOptions() {}
    }

    var listener: Listener? = null
    var chordLibrary: Chords? = null
    var glideAllowed = true
        set(value) { if (field != value) cancelTouches(); field = value }
    var state = InputState()
        set(value) { field = value; refreshAccessibilityState(); invalidate() }
    var keyWeights: Map<Char, Double> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            letterWeights.fill(0.0)
            for ((c, weight) in value) if (c.code < letterWeights.size) letterWeights[c.code] = weight
            invalidate()
        }
    /** Setting a new grid from outside clears the undo history; [undo] and [replaceGrid] keep it. */
    var grid: KeyGrid = KeyGrid.default()
        set(value) { field = value; if (!keepHistory) history.clear(); cancelTouches(); relayout() }
    var enterLabel: String? = null
        set(value) { field = value; refreshAccessibilityState(); invalidate() }
    var editMode = false
        set(value) { if (field != value) { field = value; clearArmedWord(); listener?.onChoiceInterrupted(); editingKey = null; editHint = null; cancelTouches(); relayout() } }
    /** True inside the keyboard itself: shows the gear, and settings and Done while rearranging. */
    var inKeyboard = false
        set(value) { field = value; relayout() }

    private val history = ArrayList<String>()
    private var keepHistory = false
    private var editHint: String? = null
    var editTitle: String? = null
    /** Bounds already resolved by layoutFor, including narrow/one-hand profiles. */
    internal var quickOptionsBounds = RectF()
        private set

    private fun finishEditing() { editMode = false; listener?.onEditDone() }

    private var sugLeft = emptyList<String>()
    private var sugRight = emptyList<String>()
    private var prefix = ""

    private var armedWheel: Wheel? = null
    val armedWord: String? get() = armedWheel?.let { w -> words(w).takeIf { it.isNotEmpty() }?.let { wordAt(it, w.index) } }

    fun clearArmedWord() {
        if (armedWheel == null) return
        armedWheel = null
        refreshAccessibilityState()
        invalidateWheels()
    }

    private fun armWheel(wheel: Wheel) {
        if (!settings.bool(Prefs.SPACE_ACCEPTS) || state.layer != Layer.ABC || words(wheel).isEmpty()) return
        armedWheel = wheel
        listener?.onWheelChosen()
        refreshAccessibilityState()
        announceForAccessibility("$armedWord chosen, space to insert")
        invalidate()
    }

    fun setSuggestions(left: List<String>, right: List<String>, prefix: String) {
        if (sugLeft == left && sugRight == right && this.prefix == prefix) return
        clearArmedWord()
        sugLeft = left; sugRight = right; this.prefix = prefix
        for (w in wheels) { w.index = 0; w.dragDx = 0f }
        rebuildWheelMetrics()
        refreshAccessibilityState()
        if (!editMode) for (id in A_WHEEL_LEFT..A_WHEEL_RIGHT) accessibilityEvent(id, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT)
        invalidateWheels()
    }

    private class Placed(val item: GridKey, val rect: RectF, val matrix: Matrix, val inverse: Matrix) { var presses = 0 }
    private class Cell(val r: Int, val c: Int, val rect: RectF, val matrix: Matrix, val inverse: Matrix)
    private class Wheel(val side: Int) {
        val rect = RectF(); var index = 0; var dragDx = 0f
        var widths = FloatArray(0)
        var boldWidths = FloatArray(0)
        var prefixWidths = FloatArray(0)
    }

    private val placed = ArrayList<Placed>()
    private val letterWeights = DoubleArray(ComposingTaps.LETTERS)
    private val chordLit = BooleanArray(ComposingTaps.LETTERS)
    private val tapDistances = FloatArray(ComposingTaps.LETTERS)
    private val tapPoint = FloatArray(2)
    private val cells = ArrayList<Cell>()
    private val wheels = listOf(Wheel(0), Wheel(1))
    private val handleRect = RectF()
    private var centerZone: CursorZone? = null
    private var cursorZones = emptyList<CursorZone>()
    private var outerCursorActive = false
    private var switchRect: RectF? = null
    private var switchToLeft = false
    private var layoutHeight = 0
    private var slideProfile: Pair<Int, Align>? = null
    private var slideViewWidth = -1
    private var slideCenter = 0f
    private var slideDistance = 0f
    private var slideStarted = 0L
    private var optionsCovered = false

    /** Let a Quick options change slide visibly when the panel closes, instead of finishing underneath it. */
    fun setOptionsCovered(covered: Boolean) {
        if (optionsCovered == covered) return
        slideDistance = currentSlideOffset()
        optionsCovered = covered
        slideStarted = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun currentSlideOffset() = slideOffset(slideDistance, if (optionsCovered) 0L else SystemClock.uptimeMillis() - slideStarted, reducedMotion)
    private fun isSliding() = currentSlideOffset() != 0f
    private val gearRect = RectF()
    private val undoRect = RectF()
    private val hintRect = RectF()
    private val moreRect = RectF()
    private val doneRect = RectF()
    private var leftSpace: RectF? = null
    private var rightSpace: RectF? = null
    val suggestionStripHeight get() = stripH.toInt()
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
    private val faceRect = RectF()
    private val outlineRect = RectF()
    private val ghostRect = RectF()
    private val hitPoint = FloatArray(2)
    private val faceLabels = HashMap<String, Array<String>>()
    private val faceHints = HashMap<String, String?>()
    private var languageLabel = "EN"
    private var multipleLanguages = false
    private var waveShaders = emptyArray<Shader>()
    private data class WaveAppearance(val color: Int, val top: Float, val bottom: Float)
    private var waveAppearance: WaveAppearance? = null
    private val waveMatrix = Matrix()
    private var cursorGlow = settings.bool(Prefs.CURSOR_GLOW)
    private var cursorPointer: CursorPointer? = null
    private var pointerSelecting = false
    private var pointerTime = 0L
    private val pointerFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = density; color = Color.WHITE
    }
    private val pointerShadowShader = RadialGradient(0f, 0f, 1f,
        intArrayOf(0x18000000, 0x10000000, 0x00000000), floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP)
    private val pointerShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = pointerShadowShader }
    private val pointerMatrix = Matrix()

    private val isLandscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private var reducedMotion = false
    private var highContrast = settings.theme() == ThemeName.HIGH_CONTRAST
    private val systemTextScale get() = if (settings.bool(Prefs.FOLLOW_SYSTEM_TEXT)) resources.configuration.fontScale.coerceIn(0.85f, 1.6f) else 1f
    private var labelScale = settings.int(Prefs.TEXT_SCALE) / 100f * systemTextScale
    private var iconScale = settings.int(Prefs.TEXT_SCALE) / 100f
    private var cornerRadius = settings.int(Prefs.CORNER_RADIUS) * density
    private var tintKeys = settings.bool(Prefs.KEY_TINT)
    private var showHints = settings.bool(Prefs.SHOW_HINTS)
    private val chordsAllowed get() = settings.bool(Prefs.CHORDING) && !settings.bool(Prefs.TYPE_ON_PRESS)

    /** A selected layout previews its own split without changing the Size preferences. */
    var previewSplit: Boolean? = null
        set(value) { if (field != value) { field = value; relayout() } }
    private fun displayProfile() = settings.profile(isLandscape).let { p -> previewSplit?.let { p.copy(split = it) } ?: p }

    private data class GeometrySettings(
        val profile: SizeProfile, val curve: Int, val gap: Int, val labelScale: Float,
        val sameOrientations: Boolean, val cursor: CursorControl,
        val landscape: Boolean, val screenWidth: Int, val screenHeight: Int,
    )
    private fun geometrySettings() = GeometrySettings(displayProfile(),
        settings.int(Prefs.CURVE), settings.int(Prefs.KEY_GAP), labelScale,
        settings.bool(Prefs.SAME_ORIENTATIONS), settings.cursorControl(),
        isLandscape, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    private var builtGeometry: GeometrySettings? = null

    private data class DrawingSettings(
        val theme: ThemeName, val night: Int, val language: String, val multipleLanguages: Boolean,
        val textScale: Int, val labelScale: Float, val radius: Int, val tint: Boolean, val hints: Boolean,
    )
    private fun drawingSettings() = DrawingSettings(settings.theme(), resources.configuration.uiMode,
        state.language, settings.languages().size > 1, settings.int(Prefs.TEXT_SCALE), labelScale,
        settings.int(Prefs.CORNER_RADIUS), settings.bool(Prefs.KEY_TINT), settings.bool(Prefs.SHOW_HINTS))
    private var builtDrawing: DrawingSettings? = null

    private fun refreshMotion() {
        reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
            !ValueAnimator.areAnimatorsEnabled()
        if (reducedMotion && glideId == null) trail = emptyList()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshMotion()
        invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) { refreshMotion(); invalidate() }
    }

    fun reloadSettings() {
        clearArmedWord()
        cancelTouches()
        refreshMotion()
        state.language = settings.currentLanguage()
        dismissPopup()
        palette = Palette.of(context, settings.theme())
        highContrast = settings.theme() == ThemeName.HIGH_CONTRAST
        labelScale = settings.int(Prefs.TEXT_SCALE) / 100f * systemTextScale
        multipleLanguages = settings.languages().size > 1
        languageLabel = state.language.uppercase()
        cursorGlow = settings.bool(Prefs.CURSOR_GLOW)
        if (builtGeometry != geometrySettings()) relayout()
        else {
            clearAccessibilityFocus()
            updateHoveredView(A_NONE)
            if (builtDrawing != drawingSettings()) rebuildDrawingCache()
            refreshAccessibilityState()
            accessibilityEvent(View.NO_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
            invalidate()
        }
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
        setMeasuredDimension(w, if (builtWidth == w) layoutHeight else layoutFor(w))
    }

    private fun ensureLayout() { if (builtWidth != width && width > 0) layoutFor(width) }

    // Android caps exclusion at 200 dp per edge. Centre the band on the keys so the middle rows win.
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (Build.VERSION.SDK_INT >= 29 && inKeyboard) {
            val keyBounds = placed.map { RectF(it.rect).also(it.matrix::mapRect) }
            val topY = keyBounds.minOfOrNull { it.top } ?: stripH
            val bottomY = keyBounds.maxOfOrNull { it.bottom } ?: handleRect.top
            val bandH = min(200 * density, bottomY - topY)
            val bandTop = ((topY + bottomY - bandH) / 2).roundToInt()
            val edge = 32 * density
            val leftWidth = if (outerCursorActive) max(edge, cursorZones.filter { it.bounds.left <= edge }
                .maxOfOrNull { it.bounds.right } ?: edge) else edge
            val rightStart = if (outerCursorActive) min(width - edge, cursorZones.filter { it.bounds.right >= width - edge }
                .minOfOrNull { it.bounds.left } ?: (width - edge)) else width - edge
            systemGestureExclusionRects = listOf(Rect(0, bandTop, ceil(leftWidth).toInt(), bandTop + bandH.toInt()),
                Rect(rightStart.toInt(), bandTop, width, bandTop + bandH.toInt()))
        }
    }

    private val layoutSolver = LayoutSolver()

    /** Places every key for this width and returns the keyboard height. */
    private fun layoutFor(width: Int, profile: SizeProfile? = null): Int {
        if (builtWidth != width && trackers.values.any { it.glideEligible }) cancelTouches()
        val d = density
        val landscape = isLandscape
        val p = profile ?: displayProfile()
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        val split = p.split
        val curve = if (editMode || !split) 0f else settings.int(Prefs.CURVE) / 100f
        val tilt = if (editMode || !split) 0f else p.tilt.toFloat()
        val n = grid.rows
        val gap = settings.int(Prefs.KEY_GAP) * d
        stripH = max((if (screenH / d < 500) 34 else 40) * d, 22 * d * labelScale + 8 * d)
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

        val reserve = split && !editMode
        val s = layoutSolver.compute(d, n, gap, stripH, handleH, pad, bottomPad,
            target, half, innerW, split, editMode, tilt, curve, grid)
        unit = s.un
        // Centre the active key area in the space reserved for the worst case.
        // Split: key size comes from the worst-case tilt (so keys never resize), but the frame fits the keys at the
        // current tilt, so there are no empty bands above and below them.
        val y0 = stripH + s.padTop + if (reserve) 0f else max(0f, target - s.total)
        val hc = s.hc

        class Cont(val c0: Int, val cols: Int, val u: Float, val side: Int, val base: Matrix)
        val conts = ArrayList<Cont>()
        val showCenter = editMode || grid.items.any { it.c == KeyGrid.CENTER && it.r < n }
        if (!split) {
            val u = innerW / (if (showCenter) 11 else 10)
            conts += Cont(0, 5, u, -1, Matrix().apply { setTranslate(innerLeft, y0) })
            if (showCenter) conts += Cont(5, 1, u, -1, Matrix().apply { setTranslate(innerLeft + 5 * u, y0) })
            conts += Cont(6, 5, u, -1, Matrix().apply { setTranslate(innerLeft + (if (showCenter) 6 else 5) * u, y0) })
        } else if (reserve) {
            val lx = innerLeft + s.pv - s.cw
            val rx = innerLeft + half + gapPx + half - s.pv
            fun base(x: Float, side: Int): Matrix {
                val top = y0 + s.letterY
                val pivotY = top + n * unit
                val angle = tilt * (if (side == 0) 1 else -1)
                return Matrix().apply {
                    setTranslate(x, top)
                    postRotate(angle, x + (if (side == 0) s.cw else 0f), pivotY)
                }
            }
            conts += Cont(0, 5, s.cw / 5, 0, base(lx, 0))
            val cwc = max(10 * d, min(s.cw / 5, gapPx - 4 * d))
            conts += Cont(5, 1, cwc, -1, Matrix().apply { setTranslate(innerLeft + half + (gapPx - cwc) / 2, y0 + (hc - n * unit) / 2) })
            conts += Cont(6, 5, s.cw / 5, 1, base(rx, 1))
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
                val rowUnit = layoutSolver.rowUnit(grid, item.r, ct.c0, ct.u * ct.cols)
                val rect = RectF(lc * rowUnit + gap / 2, item.r * unit + gap / 2, (lc + b - a) * rowUnit - gap / 2, (item.r + 1) * unit - gap / 2)
                val m = Matrix()
                // Ergonomic curve: raise the middle fingers' columns and angle each key like a fan.
                if (ct.side >= 0 && curve > 0f) {
                    m.setRotate(ANG[ct.side][lc] * curve, rect.centerX(), rect.centerY())
                    m.postTranslate(0f, -RAISE[ct.side][lc] * curve * unit)
                }
                m.postConcat(ct.base)
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
        // All cursor regions use the same row-edge geometry, including the shared tilt and fan.
        fun keyEdge(leftHalf: Boolean, inner: Boolean): List<ZonePoint> {
            val edgeRight = leftHalf == inner
            val pt = FloatArray(2)
            fun mapX(pk: Placed): Float {
                pt[0] = if (edgeRight) pk.rect.right else pk.rect.left; pt[1] = pk.rect.centerY()
                pk.matrix.mapPoints(pt); return pt[0]
            }
            val points = ArrayList<ZonePoint>()
            placed.filter { !split || if (leftHalf) it.item.c < KeyGrid.CENTER else it.item.c > KeyGrid.CENTER }
                .groupBy { it.item.r }.values.forEach { row ->
                    val pk = if (edgeRight) row.maxBy(::mapX) else row.minBy(::mapX)
                    for (y in floatArrayOf(pk.rect.top, pk.rect.bottom)) {
                        pt[0] = if (edgeRight) pk.rect.right else pk.rect.left; pt[1] = y
                        pk.matrix.mapPoints(pt); points += ZonePoint(pt[0], pt[1])
                    }
                }
            points.sortBy { it.y }
            if (points.isNotEmpty()) {
                points.add(0, ZonePoint(points.first().x, stripH))
                points += ZonePoint(points.last().x, handleRect.top)
            }
            return points
        }
        fun vertical(x: Float) = listOf(ZonePoint(x, stripH), ZonePoint(x, handleRect.top))
        fun zone(left: List<ZonePoint>, right: List<ZonePoint>, excluded: ZoneRect? = null) =
            if (left.isNotEmpty() && right.isNotEmpty()) CursorZone(left, right, stripH, handleRect.top, excluded) else null
        centerZone = if (split && !editMode) zone(keyEdge(true, true), keyEdge(false, true)) else null
        val outer = if (!editMode) listOfNotNull(zone(vertical(areaLeft), keyEdge(true, false)),
            zone(keyEdge(false, false), vertical(areaLeft + areaW))) else emptyList()
        val oneHand = p.widthPercent < 100 && p.align != Align.CENTER
        val plan = cursorZonePlan(oneHand, split, settings.cursorControl(), outer, 32 * d)
        leftSpace = if (areaLeft > 0f) RectF(0f, stripH, areaLeft, y0 + hc) else null
        rightSpace = if (width - (areaLeft + areaW) > 0f) RectF(areaLeft + areaW, stripH, width.toFloat(), y0 + hc) else null
        val emptyZones = ArrayList<CursorZone>()
        switchRect = null
        if (plan.showSwitch && !editMode) {
            // Keep the switch next to the keys, as far from the system back-gesture edge as the space permits.
            val space = if (p.align == Align.RIGHT) leftSpace else rightSpace
            if (space != null) {
                switchToLeft = space === leftSpace
                val inset = min(8 * d, space.width() / 4)
                val size = min(min(unit - gap, 48 * d), space.width() - 2 * inset).coerceAtLeast(1f)
                val x = if (switchToLeft) space.right - inset - size else space.left + inset
                switchRect = RectF(x, space.bottom - inset - size, x + size, space.bottom - inset)
                val excluded = switchRect?.takeIf { space.contains(it) }?.let { ZoneRect(it.left, it.top, it.right, it.bottom) }
                zone(vertical(space.left), vertical(space.right), excluded)?.let(emptyZones::add)
            }
        }
        outerCursorActive = !editMode && (plan.kind == CursorZoneKind.OUTER || plan.kind == CursorZoneKind.EMPTY)
        cursorZones = if (editMode) emptyList() else when (plan.kind) {
            CursorZoneKind.EMPTY -> emptyZones
            CursorZoneKind.OUTER -> outer
            CursorZoneKind.CENTER -> listOfNotNull(centerZone)
            CursorZoneKind.NONE -> emptyList()
        }
        val letters = placed.filter { !it.item.isStack && it.item.keys[0].let { k -> k.length == 1 && k[0].isLetter() } }
        letterCentres = letters.associate { pk ->
            val pt = floatArrayOf(pk.rect.centerX(), pk.rect.centerY())
            pk.matrix.mapPoints(pt)
            pk.item.keys[0][0].lowercaseChar() to GestureDecoder.Point(pt[0], pt[1])
        }
        letterWidth = letters.map { it.rect.width() }.sorted().let { if (it.isEmpty()) 1f else it[it.size / 2] }
        val nextProfile = p.widthPercent to p.align
        val nextCenter = areaLeft + areaW / 2
        if (!editMode && slideViewWidth == width && slideProfile != null && slideProfile != nextProfile) {
            val distance = slideCenter + currentSlideOffset() - nextCenter
            cancelTouches()
            slideDistance = if (reducedMotion) 0f else distance
            slideStarted = SystemClock.uptimeMillis()
        } else if (slideViewWidth != width || editMode) slideDistance = 0f
        slideCenter = nextCenter
        slideProfile = nextProfile
        slideViewWidth = width
        layoutHeight = ceil(if (reserve) s.total else max(target, s.total)).toInt()
        quickOptionsBounds.set(areaLeft, 0f, areaLeft + areaW, layoutHeight.toFloat())
        builtWidth = width
        builtGeometry = geometrySettings()
        rebuildDrawingCache()
        accessibilityEvent(View.NO_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
        return layoutHeight
    }

    private fun rebuildDrawingCache() {
        labelScale = settings.int(Prefs.TEXT_SCALE) / 100f * systemTextScale
        iconScale = settings.int(Prefs.TEXT_SCALE) / 100f
        cornerRadius = settings.int(Prefs.CORNER_RADIUS) * density
        tintKeys = settings.bool(Prefs.KEY_TINT)
        showHints = settings.bool(Prefs.SHOW_HINTS)
        highContrast = settings.theme() == ThemeName.HIGH_CONTRAST
        multipleLanguages = settings.languages().size > 1
        languageLabel = state.language.uppercase()
        faceLabels.clear(); faceHints.clear()
        val sample = InputState().apply { language = state.language }
        for (item in grid.items) for (code in item.keys) {
            val labels = Array(4) { mode ->
                sample.layer = when (mode) { 2 -> Layer.NUM; 3 -> Layer.SYM; else -> Layer.ABC }
                sample.shift = mode == 1
                KeyText.label(code, sample)
            }
            faceLabels[code] = labels
            sample.layer = Layer.ABC; sample.shift = false
            faceHints[code] = KeyText.hint(code, sample)
        }
        rebuildWheelMetrics()
        builtDrawing = drawingSettings()
        val rgb = waveColor()
        val appearance = WaveAppearance(rgb, stripH, max(stripH + 1f, handleRect.top))
        if (waveAppearance == appearance) return
        waveAppearance = appearance
        val down = LinearGradient(0f, stripH, 0f, max(stripH + 1f, handleRect.top),
            intArrayOf(0, -0x1000000, -0x1000000, 0), floatArrayOf(0f, .2f, .8f, 1f), Shader.TileMode.CLAMP)
        waveShaders = Array(66) { index ->
            val peak = index + 55
            val colors = IntArray(WAVE_FALLOFF.size) { rgb or ((peak * WAVE_FALLOFF[it]).toInt() shl 24) }
            ComposeShader(LinearGradient(-1f, 0f, 1f, 0f, colors, WAVE_STOPS, Shader.TileMode.CLAMP), down, PorterDuff.Mode.DST_IN)
        }
    }

    /** The face a swipe key shows: the one you swiped to, unless another key switched to a different layer. */
    private fun shownIdx(item: GridKey): Int {
        val n = item.keys.size
        val j = Math.floorMod(item.idx, n)
        val k = item.keys[j]
        if (n > 1 && KeyText.isLayerKey(k) && KeyText.layerOf(k) != state.layer) {
            for (i in item.keys.indices) {
                val code = item.keys[i]
                if (KeyText.isLayerKey(code) && KeyText.layerOf(code) == state.layer) return i
            }
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
            cursorZones.indices.forEach { ids += A_CURSOR + it }
            if (switchRect != null) ids += A_SWITCH
        }
        popup?.choices?.indices?.forEach { ids += A_POPUP + it }
        return ids
    }

    private fun accessibilityRect(id: Int): RectF = when {
        id >= A_POPUP -> popup!!.rects[id - A_POPUP]
        isCursorAccessibility(id) -> zoneRect(cursorZones[id - A_CURSOR])
        else -> when (id) {
        A_WHEEL_LEFT -> wheels[0].rect
        A_WHEEL_RIGHT -> wheels[1].rect
        A_GEAR -> gearRect
        A_SWITCH -> switchRect ?: RectF()
        A_UNDO -> undoRect
        A_SETTINGS -> moreRect
        A_DONE -> doneRect
        else -> RectF(placed[id].rect).also { placed[id].matrix.mapRect(it) }
        }
    }

    private fun accessibilityValue(id: Int): String? {
        if (id >= A_POPUP) return popup?.choices?.getOrNull(id - A_POPUP)
        placed.getOrNull(id)?.item?.let { return KeyText.spoken(it.keys[shownIdx(it)], state, enterLabel) }
        val wheel = when (id) { A_WHEEL_LEFT -> wheels[0]; A_WHEEL_RIGHT -> wheels[1]; else -> return null }
        val list = words(wheel)
        return if (list.isEmpty()) null else wordAt(list, wheel.index) + if (armedWheel === wheel) " chosen, space to insert" else ""
    }

    private fun accessibilityLabel(id: Int): String = when {
        isCursorAccessibility(id) -> "Cursor"
        id >= A_POPUP -> accessibilityValue(id).orEmpty()
        else -> when (id) {
        A_WHEEL_LEFT -> "More suggestions"
        A_WHEEL_RIGHT -> "Suggestions"
        A_GEAR -> "Quick options"
        A_SWITCH -> "Switch sides"
        A_UNDO -> "Undo"
        A_SETTINGS -> "Settings"
        A_DONE -> "Done"
        else -> {
            val item = placed[id].item
            val face = accessibilityValue(id)!!
            if (editMode) "$face, row ${item.r + 1}, column ${item.c + 1}. Click to select; editing actions available" else if (item.isStack) "$face, swipe key, ${item.keys.size} options" else if (item.isSpace && multipleLanguages) "$face, ${state.language.uppercase()}, swipe to change language" else face
        }
        }
    }

    // Older TalkBack versions identify keyboard keys by their class name.
    private fun accessibilityClassName(id: Int): String = when {
        id == View.NO_ID -> javaClass.name
        id in placed.indices || id >= A_POPUP -> "android.inputmethodservice.Keyboard\$Key"
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
        if (isSliding()) { updateHoveredView(A_NONE); return true }
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val x = event.x; val y = event.y
                val id = popup?.rects?.indexOfFirst { it.contains(x, y) }?.takeIf { it >= 0 }?.let { A_POPUP + it } ?: if (y < stripH) {
                    accessibilityIds().firstOrNull { it >= A_WHEEL_LEFT && accessibilityRect(it).contains(x, y) } ?: A_NONE
                } else {
                    findKey(x, y, strict = true)?.let { placed.indexOf(it) }
                        ?: if (!editMode && switchRect?.contains(x, y) == true) A_SWITCH
                        else if (!editMode) cursorZones.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let { A_CURSOR + it } ?: A_NONE
                        else A_NONE
                }
                updateHoveredView(id)
            }
            MotionEvent.ACTION_HOVER_EXIT -> updateHoveredView(A_NONE)
            else -> return false
        }
        return true
    }

    private fun activateAccessibleView(id: Int): Boolean {
        if (isSliding()) return false
        if (!EditorRules.typesFromAccessibility(editMode) && (id in placed.indices || id >= A_POPUP)) {
            val item = placed.getOrNull(id)?.item ?: return false
            editingKey = if (editingKey === item) null else item
            announceForAccessibility(if (editingKey == null) "Key deselected" else "Key selected. Choose a destination key and use Move or Combine.")
            return true
        }
        if (id >= A_POPUP) {
            val choice = accessibilityValue(id) ?: return false
            val owner = popup?.owner
            dismissPopup()
            commitPopup(owner, choice)
            return true
        }
        if (isCursorAccessibility(id) || id == A_UNDO && history.isEmpty()) return false
        if ((id == A_WHEEL_LEFT || id == A_WHEEL_RIGHT) && accessibilityValue(id) == null) return false
        accessibilityEvent(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
        when (id) {
            A_WHEEL_LEFT, A_WHEEL_RIGHT -> { val w = wheels[id - A_WHEEL_LEFT]; listener?.onSuggestion(wordAt(words(w), w.index)); clearArmedWord() }
            A_GEAR -> listener?.onQuickOptions()
            A_UNDO -> { if (history.isEmpty()) return false; undo() }
            A_SETTINGS -> listener?.onOpenSettings()
            A_DONE -> finishEditing()
            A_SWITCH -> switchRect?.let { moveKeyboardToward(it.centerX(), it.centerY()) }
            else -> {
                val item = placed[id].item
                if (!acceptKeyTap(item, SystemClock.uptimeMillis())) return true
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                emitKey(item.keys[shownIdx(item)])
            }
        }
        refreshAccessibilityState()
        invalidate()
        return true
    }

    private fun adjustAccessibleView(id: Int, step: Int): Boolean {
        if (isSliding() || editMode) return false
        if (isCursorAccessibility(id)) { listener?.onCursor(step, select = false); return true }
        when (id) {
            A_WHEEL_LEFT, A_WHEEL_RIGHT -> {
                val wheel = wheels[id - A_WHEEL_LEFT]
                val list = words(wheel)
                if (list.isEmpty()) return false
                wheel.index = (wheel.index + step + list.size) % list.size
                wheel.dragDx = 0f
                armWheel(wheel)
            }
            else -> {
                val item = placed.getOrNull(id)?.item ?: return false
                if (item.isSpace && multipleLanguages) {
                    listener?.onSwitchLanguage(step)
                    haptic(HapticFeedbackConstants.CLOCK_TICK)
                    return true
                }
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
                if (Build.VERSION.SDK_INT >= 29) it.isTextEntryKey = !editMode && (item != null || id >= A_POPUP)
                if (Build.VERSION.SDK_INT >= 30 && (item?.isStack == true || wheel)) it.stateDescription = value
                it.isClickable = !isCursorAccessibility(id) && (!wheel || value != null)
                if (it.isClickable) it.addAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (!editMode && (item?.isStack == true || item?.isSpace == true && multipleLanguages || wheel && value != null || isCursorAccessibility(id))) {
                    it.isScrollable = true
                    it.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    it.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
                if (id >= A_POPUP) it.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS)
                if (editMode && item != null) {
                    for (action in LayoutEditing.Action.entries) {
                        it.addAction(AccessibilityNodeInfo.AccessibilityAction(A_EDIT + action.ordinal, action.label))
                    }
                }
                if (!editMode && item != null && (item.isSpace || KeyText.alternatives(item.keys[shownIdx(item)], state, settings.bool(Prefs.SHOW_HINTS)).isNotEmpty())) {
                    it.isLongClickable = true
                    it.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
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
            if (!isEnabled || isSliding()) return false
            if (editMode && action in A_EDIT until A_EDIT + LayoutEditing.Action.entries.size) {
                val item = placed.getOrNull(virtualViewId)?.item ?: return false
                val before = grid.toJson()
                val changed = LayoutEditing.apply(grid, item, LayoutEditing.Action.entries[action - A_EDIT], editingKey)
                if (!changed && LayoutEditing.Action.entries[action - A_EDIT] == LayoutEditing.Action.REMOVE)
                    grid.removalHint(item)?.let { announceForAccessibility(it) }
                if (changed) { editingKey = null; edited(before, normalize = LayoutEditing.Action.entries[action - A_EDIT] != LayoutEditing.Action.REMOVE) }
                return changed
            }
            return when (action) {
                AccessibilityNodeInfo.ACTION_DISMISS -> { dismissPopup(); true }
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                    if (editMode) return false
                    val pk = placed.getOrNull(virtualViewId) ?: return false
                    if (pk.item.isSpace) pickSideSuggestion(pk.item)
                    else openPopup(pk, null)
                }
                AccessibilityNodeInfo.ACTION_CLICK -> activateAccessibleView(virtualViewId)
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> adjustAccessibleView(virtualViewId, 1)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> adjustAccessibleView(virtualViewId, -1)
                else -> false
            }
        }
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = accessibilityProvider

    fun refreshInputState() { refreshAccessibilityState(); invalidate() }

    // ---- Drawing ----

    override fun onDraw(canvas: Canvas) {
        // The panel is opaque. Do not record the keyboard or continue wiggle/trail animation loops.
        if (optionsCovered && alpha == 0f) return
        ensureLayout()
        canvas.drawColor(palette.background)
        canvas.save()
        val offset = currentSlideOffset()
        canvas.translate(offset, 0f)
        if (editMode) {
            drawToolbar(canvas)
            drawCells(canvas)
        } else {
            drawWheels(canvas)
            if (gearRect.width() >= 20 * density) drawGear(canvas, gearRect.centerX(), gearRect.centerY(), 20 * density, palette.hint)
        }
        val radius = cornerRadius
        // Under the keys, so it shows through the split and the spaces between keys.
        if (!editMode && cursorGlow) drawWave(canvas)
        val now = SystemClock.uptimeMillis()
        for (i in placed.indices) {
            val pk = placed[i]
            canvas.save()
            canvas.concat(pk.matrix)
            if (editMode && !reducedMotion) canvas.rotate(wiggle(pk.item, now), pk.rect.centerX(), pk.rect.centerY())
            drawKey(canvas, pk.item, pk.rect, radius, pk.presses > 0, if (drag?.item === pk.item) 0.3f else 1f)
            drawDropMark(canvas, pk)
            canvas.restore()
        }
        drawHandle(canvas)
        drag?.let { drawGhost(canvas, it, radius) }
        drawGlide(canvas, now)
        drawPopup(canvas)
        canvas.restore()
        if (offset != 0f && !optionsCovered) postInvalidateOnAnimation()
        else if (!editMode) {
            switchRect?.let {
                fill.color = palette.key
                canvas.drawOval(it, fill)
                drawMoveArrow(canvas, it, pointsLeft = switchToLeft)
            }
        }
        if (!editMode) drawCursorPointer(canvas, now)
        if (editMode && !reducedMotion) postInvalidateOnAnimation()
    }

    private fun drawGlide(canvas: Canvas, now: Long) {
        val age = now - dwellDotAt
        if (age in 0..249) {
            val centre = letterCentres[dwellDotLetter]
            if (centre != null) {
                fill.color = palette.accent
                val opacity = if (reducedMotion) .6f else .6f * (1f - kotlin.math.abs(age / 125f - 1f))
                fill.alpha = (255 * opacity).toInt()
                canvas.drawCircle(centre.x, centre.y, 4 * density, fill)
                fill.alpha = 255
                if (!reducedMotion) postInvalidateOnAnimation()
            }
        }
        val points = trail
        if (points.size < 2) return
        stroke.color = palette.accent
        stroke.strokeWidth = 4 * density
        var visible = false
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            val alpha = if (reducedMotion) 1f else (1f - (now - b.time) / 300f).coerceIn(0f, 1f)
            if (alpha <= 0f) continue
            stroke.alpha = (alpha * 220).toInt()
            canvas.drawLine(a.x, a.y, b.x, b.y, stroke)
            visible = true
        }
        stroke.alpha = 255
        if (visible && !reducedMotion) postInvalidateOnAnimation() else if (!visible && glideId == null) trail = emptyList()
    }

    private fun wiggle(item: GridKey, now: Long) = (1.6 * sin(2 * PI * (now % 320) / 320.0 + item.id * 0.9)).toFloat()

    private fun drawKey(canvas: Canvas, item: GridKey, rect: RectF, radius: Float, pressed: Boolean, alpha: Float) {
        val keyAlpha = if (highContrast) 1f else alpha
        val face = shownIdx(item)
        val code = item.keys[face]
        val letter = if (code.length == 1) code[0].lowercaseChar().code else -1
        val chord = letter in chordLit.indices && chordLit[letter]
        val weight = if (letter in letterWeights.indices) letterWeights[letter] else 0.0
        val lock = code == Keys.ABC && state.layer == Layer.ABC && state.capsLock
        val oneShot = code == Keys.ABC && state.layer == Layer.ABC && state.shift && !state.capsLock
        val accentBg = code == Keys.ENTER || lock
        fill.color = when {
            highContrast -> palette.key
            pressed -> palette.pressed
            accentBg -> palette.accent
            KeyText.isLayerKey(code) || code == Keys.BACKSPACE -> palette.functionKey
            else -> palette.key
        }
        fill.alpha = (255 * keyAlpha).toInt()
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (!highContrast && !editMode && chord) {
            fill.color = palette.accent
            fill.alpha = 110
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        if (!highContrast && tintKeys && state.layer == Layer.ABC && !editMode && !pressed &&
            !item.isStack && code.length == 1 && code[0].isLetter() && keyWeights.isNotEmpty()) {
            fill.color = palette.accent
            fill.alpha = (56 * weight).roundToInt()
            canvas.drawRoundRect(rect, radius, radius, fill)
        }
        fill.alpha = 255
        if (highContrast) {
            stroke.color = palette.text; stroke.alpha = 255; stroke.strokeWidth = 2 * density
            outlineRect.set(rect); outlineRect.inset(density, density)
            canvas.drawRoundRect(outlineRect, radius, radius, stroke)
        }
        val highlighted = accentBg || oneShot || pressed || chord ||
            (tintKeys && !editMode && weight > 0.1)
        if (editMode && reducedMotion || highContrast && highlighted) {
            stroke.color = palette.accent; stroke.alpha = 255; stroke.strokeWidth = 2 * density
            outlineRect.set(rect)
            val pad = if (highContrast) 4 * density else density
            outlineRect.inset(pad, pad)
            canvas.drawRoundRect(outlineRect, radius, radius, stroke)
        }
        val fg = when { highContrast -> palette.text; accentBg && !pressed -> palette.onAccent; oneShot -> palette.accent; else -> palette.text }

        canvas.save()
        canvas.clipRect(rect)
        val sw = swipe?.takeIf { it.item === item }
        val dy = sw?.dy ?: 0f
        drawFace(canvas, code, rect, dy, fg, keyAlpha)
        if (item.isStack && dy != 0f) {
            val n = item.keys.size
            drawFace(canvas, item.keys[(face + 1) % n], rect, rect.height() + dy, fg, keyAlpha)
            drawFace(canvas, item.keys[(face + n - 1) % n], rect, -rect.height() + dy, fg, keyAlpha)
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
        if (!item.isStack && grid.rows == 4 && showHints && state.layer == Layer.ABC) faceHints[code]?.let {
            text.color = palette.hint
            text.isFakeBoldText = highContrast
            text.textSize = min(rect.height() * 0.2f * labelScale, min(rect.width() * 0.35f, rect.height() * 0.32f))
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
        val r = faceRect
        r.set(rect); r.offset(0f, offsetY)
        val scale = labelScale
        val icon = min(r.width(), r.height()) * 0.42f * iconScale
        val a = (255 * alpha).toInt()
        when (code) {
            Keys.BACKSPACE -> drawDelete(canvas, r, icon, color, a)
            Keys.ENTER -> enterLabel?.let { drawLabel(canvas, r, it, r.height() * 0.3f * scale, color, a) } ?: drawReturn(canvas, r, icon, color, a)
            Keys.SPACE -> if (multipleLanguages && !editMode) {
                drawLabel(canvas, r, languageLabel, min(12 * density, r.height() * 0.25f) * scale, palette.hint, a)
            }
            else -> {
                val label = if (code == Keys.ABC) KeyText.label(code, state) else
                    faceLabels[code]?.get(if (state.layer == Layer.NUM) 2 else if (state.layer == Layer.SYM) 3 else if (state.upper) 1 else 0) ?: code
                val size = min(r.height() * 0.42f, 22 * density) * scale
                drawLabel(canvas, r, label, if (label.length > 1) size * 0.72f else size, color, a)
            }
        }
    }

    private fun drawLabel(canvas: Canvas, r: RectF, label: String, size: Float, color: Int, alpha: Int) {
        text.color = color
        text.alpha = alpha
        text.isFakeBoldText = highContrast
        text.textSize = size
        val maxWidth = r.width() * 0.85f
        val measured = text.measureText(label)
        if (measured > maxWidth) text.textSize = size * maxWidth / measured
        val height = text.descent() - text.ascent()
        if (height > r.height() * 0.85f) text.textSize *= r.height() * 0.85f / height
        canvas.drawText(label, r.centerX(), r.centerY() - (text.descent() + text.ascent()) / 2f, text)
    }

    private fun shape(cx: Float, cy: Float, s: Float, close: Boolean, p: FloatArray): Path {
        path.reset()
        path.moveTo(cx + p[0] * s, cy + p[1] * s)
        var i = 2
        while (i < p.size) { path.lineTo(cx + p[i] * s, cy + p[i + 1] * s); i += 2 }
        if (close) path.close()
        return path
    }

    private fun strokeIn(color: Int, s: Float, alpha: Int) = stroke.apply { this.color = color; this.alpha = alpha; strokeWidth = s * 0.09f }

    private fun drawDelete(canvas: Canvas, r: RectF, s: Float, color: Int, alpha: Int) {
        val p = strokeIn(color, s, alpha)
        canvas.drawPath(shape(r.centerX(), r.centerY(), s, true, SHAPE_DELETE), p)
        canvas.drawPath(shape(r.centerX(), r.centerY(), s, false, SHAPE_DELETE_X1), p)
        canvas.drawPath(shape(r.centerX(), r.centerY(), s, false, SHAPE_DELETE_X2), p)
    }

    private fun drawReturn(canvas: Canvas, r: RectF, s: Float, color: Int, alpha: Int) {
        val p = strokeIn(color, s, alpha)
        canvas.drawPath(shape(r.centerX(), r.centerY(), s, false, SHAPE_RETURN), p)
        canvas.drawPath(shape(r.centerX(), r.centerY(), s, false, SHAPE_RETURN_HEAD), p)
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
        canvas.drawPath(shape(cx - r, cy + s * 0.06f, s, false, SHAPE_UNDO_HEAD), p)
    }

    private fun drawToolbar(canvas: Canvas) {
        if (drag != null) {
            val active = drag?.target?.type == T_REMOVE
            fill.color = if (active) palette.accent else palette.key
            canvas.drawRoundRect(hintRect, 8 * density, 8 * density, fill)
            val cx = hintRect.centerX(); val cy = hintRect.centerY(); val s = 10 * density
            val p = strokeIn(if (active) palette.background else palette.text, s * 2, 255)
            canvas.drawRect(cx - s * .6f, cy - s * .4f, cx + s * .6f, cy + s * .8f, p)
            canvas.drawLine(cx - s, cy - s * .7f, cx + s, cy - s * .7f, p)
            canvas.drawLine(cx - s * .3f, cy - s, cx + s * .3f, cy - s, p)
            return
        }
        val s = 20 * density
        drawUndo(canvas, undoRect.centerX(), undoRect.centerY(), s, palette.text, if (history.isEmpty()) 70 else 255)
        val sp = resources.displayMetrics.scaledDensity
        val title = editHint ?: editTitle ?: "Drag keys to rearrange"
        val lines = if (editHint == null) title.split("; ", limit = 2) else listOf(title)
        lines.forEachIndexed { index, line ->
            val rect = if (lines.size == 1) hintRect else RectF(hintRect).apply {
                if (index == 0) bottom = centerY() else top = centerY()
            }
            drawLabel(canvas, rect, line, 13 * sp, if (editHint != null || editTitle != null) palette.smallAccent else palette.hint, 255)
        }
        if (!doneRect.isEmpty) {
            drawGear(canvas, moreRect.centerX(), moreRect.centerY(), s, palette.hint)
            text.isFakeBoldText = true
            drawLabel(canvas, doneRect, "Done", 15 * sp, palette.smallAccent, 255)
            text.isFakeBoldText = false
        }
    }

    private fun drawMoveArrow(canvas: Canvas, area: RectF, pointsLeft: Boolean) {
        val s = min(area.width() * 0.35f, 18 * density)
        val dir = if (pointsLeft) 1f else -1f
        path.reset()
        path.moveTo(area.centerX() + .25f * dir * s, area.centerY() - .5f * s)
        path.lineTo(area.centerX() - .25f * dir * s, area.centerY())
        path.lineTo(area.centerX() + .25f * dir * s, area.centerY() + .5f * s)
        canvas.drawPath(path, strokeIn(palette.hint, s * 1.5f, 255))
    }

    private fun cursorZoneAt(x: Float, y: Float) = cursorZones.firstOrNull { it.contains(x, y) }
    private fun zoneRect(zone: CursorZone) = zone.bounds.let { RectF(it.left, it.top, it.right, it.bottom) }
    private fun isCursorAccessibility(id: Int) = id in A_CURSOR until A_CURSOR + cursorZones.size

    /**
     * Springs the keyboard-wide wave toward where the push points: from the press point out to the keyboard's edge as
     * the push reaches the gap's edge, in proportion, gliding smoothly without overshoot so it stays easy on the eyes.
     * Returns whether it is still moving.
     */
    private fun stepWave(t: Tracker, now: Long): Boolean {
        val dt = (now - t.waveTime).coerceIn(0L, 40L) / 1000f
        t.waveTime = now
        val push = t.joystick.push
        val bounds = t.cursorZone?.bounds
        val reach = if (push < 0) t.x0 - min(handleRect.left, bounds?.left ?: handleRect.left)
            else max(handleRect.right, bounds?.right ?: handleRect.right) - t.x0
        val target = t.x0 + push.toFloat() * reach
        if (reducedMotion) { t.waveX = target; t.waveV = 0f; return false }
        t.waveV += (60f * (target - t.waveX) - 16f * t.waveV) * dt
        t.waveX += t.waveV * dt
        return abs(target - t.waveX) > 0.5f * density || abs(t.waveV) > 4 * density
    }

    /**
     * While a cursor zone is held, a soft vertical glow in a pale complement of the theme's accent sits under the keys and moves out
     * the way the cursor is pushed: a small push carries it a little past the split, a push to the split's edge carries
     * it to the keyboard's edge. It fades out on every side, gets stronger the harder the push, and is brighter and
     * wider while selecting.
     */
    /** The accent's complementary hue, softened to a light pastel so the glow reads as a tint, never a dark smear. */
    private fun waveColor(): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(palette.accent, hsv)
        hsv[0] = (hsv[0] + 180f) % 360f
        hsv[1] = min(hsv[1], 0.4f)
        hsv[2] = if (Color.luminance(palette.background) > 0.5f) 0.92f else 0.85f
        return Color.HSVToColor(hsv) and 0x00FFFFFF
    }

    private fun drawWave(canvas: Canvas) {
        val t = cursorTracker ?: return
        if (waveShaders.isEmpty()) return
        val strength = min(1.0, abs(t.joystick.push)).toFloat()
        val peak = ((if (t.select) 85 else 55) + 35 * strength).toInt()
        val half = (if (t.select) 52 else 44) * density
        waveMatrix.setScale(half, 1f)
        waveMatrix.postTranslate(t.waveX, 0f)
        val shader = waveShaders[peak - 55]
        shader.setLocalMatrix(waveMatrix)
        fill.shader = shader
        canvas.drawRect(t.waveX - half, stripH, t.waveX + half, handleRect.top, fill)
        fill.shader = null
    }

    private fun invalidateWave(oldX: Float, newX: Float, selecting: Boolean) {
        if (!cursorGlow) return
        val half = (if (selecting) 52 else 44) * density + 1f
        invalidate(kotlin.math.floor(min(oldX, newX) - half).toInt(), stripH.toInt(),
            ceil(max(oldX, newX) + half).toInt(), ceil(handleRect.top).toInt())
    }

    /** Draw last so keys cannot cover the disk. Paints, gradient and matrix survive every frame. */
    private fun drawCursorPointer(canvas: Canvas, now: Long) {
        val p = cursorPointer ?: return
        if (cursorTracker == null && !p.released) return
        val alpha = p.alpha(now, reducedMotion)
        if (alpha <= 0f) return
        val shadowRadius = p.radius + 4 * density
        pointerMatrix.setScale(shadowRadius, shadowRadius)
        pointerMatrix.postTranslate(p.x, p.y + density)
        pointerShadowShader.setLocalMatrix(pointerMatrix)
        pointerShadow.alpha = (255 * alpha).toInt()
        canvas.drawCircle(p.x, p.y + density, shadowRadius, pointerShadow)
        pointerFill.color = if (pointerSelecting) palette.accent else Color.rgb(173, 203, 227)
        pointerFill.alpha = (255 * alpha).toInt()
        pointerRing.alpha = (255 * alpha).toInt()
        val radius = (p.radius - density / 2).coerceAtLeast(0f)
        canvas.drawCircle(p.x, p.y, radius, pointerFill)
        canvas.drawCircle(p.x, p.y, radius, pointerRing)
    }

    private fun invalidatePointer(p: CursorPointer) {
        val inset = p.radius + 6 * density
        invalidate(kotlin.math.floor(p.minX - inset).toInt(), kotlin.math.floor(p.y - inset).toInt(),
            ceil(p.maxX + inset).toInt(), ceil(p.y + inset).toInt())
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
        val radius = cornerRadius
        dash.strokeWidth = density
        for (i in cells.indices) {
            val cell = cells[i]
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

    private fun invalidateWheels() {
        invalidate(0, 0, width, ceil(stripH).toInt())
    }

    private fun rebuildWheelMetrics() {
        wheelText.textSize = 15 * density * labelScale
        for (i in wheels.indices) {
            val wheel = wheels[i]
            val list = words(wheel)
            if (wheel.widths.size != list.size) {
                wheel.widths = FloatArray(list.size)
                wheel.boldWidths = FloatArray(list.size)
                wheel.prefixWidths = FloatArray(list.size)
            }
            for (j in list.indices) {
                wheelText.isFakeBoldText = highContrast
                wheel.widths[j] = wheelText.measureText(list[j])
                wheelText.isFakeBoldText = true
                wheel.boldWidths[j] = wheelText.measureText(list[j])
                wheel.prefixWidths[j] = if (prefix.isNotEmpty() && list[j].startsWith(prefix, true))
                    wheelText.measureText(list[j], 0, prefix.length) else 0f
            }
        }
        wheelText.isFakeBoldText = highContrast
    }

    /** Horizontal offset of word k, using metrics prepared when suggestions or text size change. */
    private fun offsetOf(wheel: Wheel, k: Int): Float {
        val widths = wheel.widths
        val n = widths.size
        val gap = 22 * density
        fun w(j: Int) = widths[((wheel.index + j) % n + n) % n]
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

    private fun wheelTargets(wh: Wheel): List<WheelTapTarget> {
        wheelText.textSize = 15 * density * labelScale
        wheelText.isFakeBoldText = highContrast
        val list = words(wh)
        return wheelRange(list.size).map { k ->
            WheelTapTarget(wh.index + k, wh.rect.centerX() + offsetOf(wh, k) + wh.dragDx,
                wheelText.measureText(wordAt(list, wh.index + k)))
        }
    }

    private fun drawWheels(canvas: Canvas) {
        wheelText.textSize = 15 * density * labelScale
        wheelText.isFakeBoldText = highContrast
        if (!wheels[0].rect.isEmpty && wheels[1].rect.left == wheels[0].rect.right) {
            fill.color = palette.functionKey
            canvas.drawRect(wheels[0].rect.right - density / 2, stripH * 0.25f, wheels[0].rect.right + density / 2, stripH * 0.75f, fill)
        }
        for (i in wheels.indices) {
            val wh = wheels[i]
            val list = words(wh)
            if (list.isEmpty()) continue
            canvas.save()
            canvas.clipRect(wh.rect)
            val baseline = wh.rect.centerY() - (wheelText.descent() + wheelText.ascent()) / 2
            val back = min(3, (list.size - 1) / 2)
            val last = min(3, list.size - 1 - back)
            for (k in -back..last) {
                val index = ((wh.index + k) % list.size + list.size) % list.size
                val word = list[index]
                val cx = wh.rect.centerX() + offsetOf(wh, k) + wh.dragDx
                val alpha = if (highContrast || k == 0) 255 else 100
                var x = cx - wh.widths[index] / 2
                if (armedWheel === wh && k == 0) {
                    wheelText.isFakeBoldText = true
                    val half = wh.boldWidths[index] / 2
                    val h = wheelText.descent() - wheelText.ascent() + 8 * density
                    fill.color = palette.accent; fill.alpha = 51
                    canvas.drawRoundRect(cx - half - 8 * density, wh.rect.centerY() - h / 2,
                        cx + half + 8 * density, wh.rect.centerY() + h / 2, h / 2, h / 2, fill)
                    fill.alpha = 255
                    wheelText.color = palette.accent; wheelText.alpha = 255
                    canvas.drawText(word, cx - half, baseline, wheelText)
                    wheelText.isFakeBoldText = highContrast
                    continue
                }
                // The part you've already typed is highlighted.
                if (prefix.isNotEmpty() && word.startsWith(prefix, ignoreCase = true)) {
                    wheelText.color = palette.accent; wheelText.alpha = alpha; wheelText.isFakeBoldText = true
                    canvas.drawText(word, 0, prefix.length, x, baseline, wheelText)
                    x += wh.prefixWidths[index]
                    wheelText.isFakeBoldText = highContrast
                    wheelText.color = palette.text; wheelText.alpha = alpha
                    canvas.drawText(word, prefix.length, word.length, x, baseline, wheelText)
                } else {
                    wheelText.color = palette.text; wheelText.alpha = alpha
                    canvas.drawText(word, x, baseline, wheelText)
                }
            }
            canvas.restore()
        }
    }

    // ---- Touch ----

    private class AccentPopup(val choices: List<String>, val rects: List<RectF>, val owner: Tracker?) {
        var selected = -1
    }
    private var popup: AccentPopup? = null

    private fun openPopup(pk: Placed, owner: Tracker?): Boolean {
        val choices = owner?.pressChoices ?: KeyText.alternatives(pk.item.keys[shownIdx(pk.item)], state, settings.bool(Prefs.SHOW_HINTS))
        if (choices.isEmpty()) return false
        dismissPopup()
        val anchor = RectF(pk.rect).also { pk.matrix.mapRect(it) }
        val cell = min(max(36 * density, pk.rect.width()), (width - 8 * density) / choices.size)
        val h = min(min(unit, 48 * density), max(24 * density, anchor.top - 4 * density))
        val left = (anchor.centerX() - cell * choices.size / 2).coerceIn(4 * density, max(4 * density, width - 4 * density - cell * choices.size))
        val top = max(0f, anchor.top - h - 4 * density)
        clearAccessibilityFocus()
        popup = AccentPopup(choices, choices.indices.map { RectF(left + it * cell, top, left + (it + 1) * cell, top + h) }, owner)
        if (accessibilityManager.isEnabled) {
            accessibilityFocus = A_POPUP
            accessibilityEvent(A_POPUP, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        }
        owner?.let { it.consumed = true; it.glideEligible = false; stopTimers(it) }
        accessibilityEvent(View.NO_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
        invalidate()
        return true
    }

    private fun dismissPopup() {
        if (popup == null) return
        if (popup?.owner?.kind == K_STACK) swipe = null
        if (accessibilityFocus >= A_POPUP) clearAccessibilityFocus()
        if (hoveredView >= A_POPUP) updateHoveredView(A_NONE)
        popup = null
        accessibilityEvent(View.NO_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
        invalidate()
    }

    private fun drawPopup(canvas: Canvas) {
        val p = popup ?: return
        for (i in p.rects.indices) {
            val r = p.rects[i]
            fill.color = if (i == p.selected) palette.accent else palette.functionKey
            canvas.drawRoundRect(r, 6 * density, 6 * density, fill)
            stroke.color = palette.text
            stroke.strokeWidth = (if (highContrast) 2 else 1) * density
            canvas.drawRoundRect(r, 6 * density, 6 * density, stroke)
            drawLabel(canvas, r, p.choices[i], min(22 * density * labelScale, r.height() * 0.5f), if (i == p.selected) palette.onAccent else palette.text, 255)
        }
    }

    private inner class Tracker(val kind: Int, initial: Placed?) {
        var pk: Placed? = initial
            set(value) { field?.let { it.presses-- }; field = value; value?.let { it.presses++ } }
        init { initial?.let { it.presses++ } }
        var pressedText: String? = null
        var followingText = ""
        var pressChoices: List<String>? = null
        var languageDirection = 0
        var consumed = false
        var moved = false
        var x0 = 0f
        var y0 = 0f
        var tapX = 0f
        var tapY = 0f
        var lastX = 0f
        val joystick = CursorJoystick()
        var cursorZone: CursorZone? = null
        var lastCursorHaptic = Long.MIN_VALUE
        /** The keyboard-wide wave's position and velocity (px, px/s), sprung toward where the push points. */
        var waveX = 0f
        var waveV = 0f
        var waveTime = 0L
        var fx = 0f
        var fy = 0f
        var select = false
        var deferredHaptic = false
        var glideEligible = false
        var glideWidth = 1f
        val points = ArrayList<GestureDecoder.Point>()
        var trace: SwipeTrace? = null
        var glideX = 0f; var glideY = 0f
        var liveAt = Long.MIN_VALUE
        var publishedPoints = -1; var publishedHints = -1
        var glideTick: Runnable? = null
        var wheel: Wheel? = null
        val longPress = Runnable { onLongPress(this) }
        val repeat: Runnable = object : Runnable {
            override fun run() {
                emitKey(Keys.BACKSPACE)
                timers.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
    }

    private class Swipe(val item: GridKey) { var dy = 0f; var stepped = false }

    private val timers = Handler(Looper.getMainLooper())
    private val trackers = LinkedHashMap<Int, Tracker>()
    private var cursorTracker: Tracker? = null
    private var swipe: Swipe? = null
    private val lastKeyTaps = HashMap<Int, Long>()
    private val chordTouches = ChordTouches<Int>()
    private val clearChordHighlight = Runnable { chordLit.fill(false); invalidate() }
    private var glideId: Int? = null
    private var trail: List<GestureDecoder.Point> = emptyList()
    private var dwellDotLetter = ' '
    private var dwellDotAt = Long.MIN_VALUE

    private val gapPresses = GapPresses()
    private val cursorFrame = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            var again = false
            val t = cursorTracker
            if (t != null) {
                val oldX = t.waveX
                moveCursor(t, t.joystick.advance(now), now)
                if (cursorTracker === t) {
                    val waveMoving = cursorGlow && stepWave(t, now)
                    cursorPointer?.let { invalidatePointer(it); it.follow(t.fy) }
                    val pointerMoving = cursorPointer?.step(t.joystick.push.toFloat(),
                        (now - pointerTime) / 1000f, reducedMotion) == true
                    again = waveMoving || pointerMoving || t.joystick.speed != 0.0
                }
                invalidateWave(oldX, t.waveX, t.select)
            }
            pointerTime = now
            cursorPointer?.let { p ->
                invalidatePointer(p)
                if (cursorTracker == null && (!p.released || p.alpha(now, reducedMotion) == 0f)) cursorPointer = null
                else if (p.fading(now, reducedMotion)) again = true
            }
            if (again) postOnAnimation(this)
        }
    }

    private fun moveCursor(t: Tracker, steps: Int, now: Long) {
        if (steps == 0) return
        invalidatePressAccents()
        listener?.onCursor(steps, select = t.select)
        if (t.lastCursorHaptic == Long.MIN_VALUE || now - t.lastCursorHaptic >= 80) {
            haptic(HapticFeedbackConstants.CLOCK_TICK)
            t.lastCursorHaptic = now
        }
    }

    fun cancelTouches() {
        removeCallbacks(cursorFrame)
        cursorPointer = null
        gapPresses.clear()
        chordTouches.cancel()
        timers.removeCallbacks(clearChordHighlight)
        chordLit.fill(false)
        dismissPopup()
        trackers.values.forEach { stopTimers(it); it.pk?.let { pk -> pk.presses-- }; if (it.select) listener?.onSelectMode(false) }
        cursorTracker = null
        for (t in trackers.values) t.glideTick?.let { timers.removeCallbacks(it) }
        trackers.clear()
        if (glideId != null) listener?.onGlideCancel()
        glideId = null
        trail = emptyList()
        dwellDotAt = Long.MIN_VALUE
        swipe = null
        drag = null
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        ensureLayout()
        if (isSliding()) return true
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
                trackers[e.getPointerId(i)]?.let { it.tapX = e.getX(i); it.tapY = e.getY(i) }
                trackers[e.getPointerId(i)]?.let { recordGlide(e.getPointerId(i), it, e.getX(i), e.getY(i), e.eventTime) }
                trackers[e.getPointerId(i)]?.let { t ->
                    if (popup?.owner === t || t.kind == K_WHEEL || t.kind == K_CURSOR || t.pk?.item?.isSpace == true && multipleLanguages)
                        pointerMove(e.getPointerId(i), e.getX(i), e.getY(i), e.eventTime)
                }
                pointerUp(e.getPointerId(i), e.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float, time: Long) {
        if (glideId != null || isSliding()) return
        val zone = cursorZoneAt(x, y)
        val onControl = zone != null || gearRect.contains(x, y) || handleRect.contains(x, y) || wheels.any { it.rect.contains(x, y) } ||
            leftSpace?.contains(x, y) == true || rightSpace?.contains(x, y) == true
        val initialKey = if (onControl) null else findKey(x, y)
        if (initialKey != null && !acceptKeyTap(initialKey.item, time)) return
        val chordKey = if (chordsAllowed && state.layer == Layer.ABC && !editMode && popup == null &&
            zone == null && !gearRect.contains(x, y) && !handleRect.contains(x, y) && !wheels.any { it.rect.contains(x, y) } &&
            leftSpace?.contains(x, y) != true && rightSpace?.contains(x, y) != true) findKey(x, y)?.takeIf {
                !it.item.isStack && it.item.keys[0].let { k -> k.length == 1 && k[0].isLetter() }
            } else null
        for ((otherId, other) in trackers) chordTouches.update(otherId, other.pk?.item?.keys?.firstOrNull()?.singleOrNull(), other.consumed, other.tapX, other.tapY)
        val prepared = chordTouches.prepareDown(time, chordKey != null)
        for (otherId in prepared.rollover) trackers[otherId]?.let { stopTimers(it); it.consumed = true }
        finishChords(prepared.completions)
        // Chord members defer rollover; every other touch keeps the existing path.
        for ((otherId, t) in trackers.toMap()) {
            val code = t.pk?.item?.let { it.keys[shownIdx(it)] }
            if (!EditorRules.pendingCharacter(t.kind == K_KEY, t.kind == K_STACK, t.moved, t.consumed,
                    code != null && KeyText.isChar(code)) || chordTouches.contains(otherId)) continue
            stopTimers(t); t.consumed = true
            if (t.kind == K_STACK) emitKey(code!!, t.tapX, t.tapY) else commit(t)
        }
        if (gearRect.contains(x, y)) {
            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            trackers[id] = Tracker(K_GEAR, null)
            return
        }
        wheels.firstOrNull { it.rect.contains(x, y) && words(it).isNotEmpty() }?.let {
            trackers[id] = Tracker(K_WHEEL, null).also { t -> t.wheel = it; t.x0 = x; t.lastX = x; timers.postDelayed(t.longPress, settings.int(Prefs.LONG_PRESS_MS).toLong()) }
            return
        }
        if (handleRect.contains(x, y)) {
            trackers[id] = Tracker(K_HANDLE, null).also { it.x0 = x; it.lastX = x }
            return
        }
        if (switchRect?.contains(x, y) == true) {
            moveKeyboardToward(x, y)
            return
        }
        val hit = findKey(x, y, strict = true)
        if (hit == null && zone != null) {
            if (cursorTracker != null) return
            trackers[id] = Tracker(K_CURSOR, null).also {
                cursorTracker = it
                it.x0 = x; it.y0 = y; it.fx = x; it.fy = y
                it.cursorZone = zone
                it.waveX = x; it.waveTime = SystemClock.uptimeMillis()
                it.joystick.reach((x - CursorZone.edgeAt(zone.left, y)) / density, (CursorZone.edgeAt(zone.right, y) - x) / density)
                it.select = gapPresses.isSelection(time, x / density, y / density)
                if (it.select) {
                    // Selecting is 15% less sensitive, but the zone's edge still reaches full speed and the glow the end.
                    it.joystick.sensitivity = 0.85
                    listener?.onSelectMode(true)
                    haptic(HapticFeedbackConstants.LONG_PRESS)
                    it.lastCursorHaptic = SystemClock.uptimeMillis()
                }
                val now = SystemClock.uptimeMillis()
                pointerSelecting = it.select
                cursorPointer = CursorPointer(zone, x, y, (if (it.select) 11 else 10) * density, now)
                pointerTime = now
            }
            removeCallbacks(cursorFrame)
            if (!reducedMotion) postOnAnimation(cursorFrame)
            invalidate()
            return
        }
        if (hit == null && (centerZone?.contains(x, y) == true ||
            leftSpace?.contains(x, y) == true || rightSpace?.contains(x, y) == true)) return
        val pk = chordKey ?: findKey(x, y) ?: return
        if (chordKey == null) haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        if (pk.item.isStack) {
            trackers[id] = Tracker(K_STACK, pk).also { it.x0 = x; it.y0 = y; it.tapX = x; it.tapY = y; pressLetter(it); timers.postDelayed(it.longPress, settings.int(Prefs.LONG_PRESS_MS).toLong()) }
            swipe = Swipe(pk.item)
            invalidate()
            return
        }
        val t = Tracker(K_KEY, pk)
        t.tapX = x; t.tapY = y
        t.x0 = x; t.y0 = y; t.glideWidth = pk.rect.width()
        t.glideEligible = !settings.bool(Prefs.TYPE_ON_PRESS) && glideAllowed && settings.bool(Prefs.SWIPE_TYPING) && state.layer == Layer.ABC &&
            pk.item.keys[0].let { it.length == 1 && it[0].isLetter() }
        if (t.glideEligible) {
            t.glideX = x; t.glideY = y
            t.points.add(GestureDecoder.Point(x, y, time))
        }
        trackers[id] = t
        if (chordKey != null) {
            t.deferredHaptic = true
            chordTouches.down(id, pk.item.keys[0][0].lowercaseChar(), time, x, y)
            if (chordTouches.isCandidate(id)) trackers.filterKeys { chordTouches.isCandidate(it) }.values.forEach { stopTimers(it) }
        }
        if (pk.item.keys[0] == Keys.BACKSPACE) {
            t.consumed = true
            emitKey(Keys.BACKSPACE)
            timers.postDelayed(t.repeat, REPEAT_DELAY_MS)
        } else if (!chordTouches.isCandidate(id)) {
            pressLetter(t)
            timers.postDelayed(t.longPress, settings.int(Prefs.LONG_PRESS_MS).toLong())
        }
        invalidate()
    }

    private fun recordGlide(id: Int, t: Tracker, x: Float, y: Float, time: Long): Boolean {
        if (!t.glideEligible || t.consumed && glideId != id) return false
        if (glideId != null && glideId != id) return true
        t.points.add(GestureDecoder.Point(x, y, time))
        t.glideX = x; t.glideY = y
        t.trace?.sample(x, y, time, t.points.lastIndex)
        if (glideId == null && hypot(x - t.x0, y - t.y0) > 1.0f * t.glideWidth) {
            chordTouches.cancel()
            glideId = id
            val trace = SwipeTrace(letterCentres, letterWidth)
            t.trace = trace
            for (i in t.points.indices) { val p = t.points[i]; trace.sample(p.x, p.y, p.time, i) }
            listener?.onGlideStart()
            val tick = object : Runnable {
                override fun run() {
                    if (glideId != id || trackers[id] !== t) return
                    val now = SystemClock.uptimeMillis()
                    val oldDot = trace.dotAt
                    trace.sample(t.glideX, t.glideY, now, t.points.lastIndex)
                    publishGlide(t, now)
                    if (oldDot != trace.dotAt) invalidate()
                    timers.postDelayed(this, 20)
                }
            }
            t.glideTick = tick; timers.postDelayed(tick, 20)
            stopTimers(t)
            t.consumed = true
            // Existing held controls must not fire on release during this word gesture.
            for ((otherId, other) in trackers.toMap()) if (otherId != id) {
                stopTimers(other)
                if (other.select) listener?.onSelectMode(false)
                other.pk?.let { it.presses-- }
                if (cursorTracker === other) cursorTracker = null
                trackers.remove(otherId)
            }
            swipe = null
        }
        if (glideId == id) { trail = t.points; publishGlide(t, time); invalidate(); return true }
        return false
    }

    private fun publishGlide(t: Tracker, now: Long) {
        val trace = t.trace
        if (trace != null && trace.dotAt != dwellDotAt) {
            dwellDotLetter = trace.dotLetter; dwellDotAt = trace.dotAt
            if (reducedMotion && dwellDotAt != Long.MIN_VALUE) postInvalidateDelayed((250 - (SystemClock.uptimeMillis() - dwellDotAt)).coerceAtLeast(1))
        }
        if (t.liveAt != Long.MIN_VALUE && now - t.liveAt < 50) return
        if (t.publishedPoints == t.points.size && t.publishedHints == (trace?.hints?.size ?: 0)) return
        t.liveAt = now; t.publishedPoints = t.points.size; t.publishedHints = trace?.hints?.size ?: 0
        listener?.onGlideProgress(t.points, letterCentres, letterWidth, t.trace?.text().orEmpty(), t.trace?.hints.orEmpty())
    }

    private fun pointerMove(id: Int, x: Float, y: Float, time: Long) {
        val t = trackers[id] ?: return
        t.tapX = x; t.tapY = y
        popup?.takeIf { it.owner === t }?.let {
            it.selected = it.rects.indexOfFirst { r -> r.contains(x, y) }
            invalidate()
            return
        }
        if (t.pk?.item?.isSpace == true && !t.consumed && multipleLanguages) {
            val dx = x - t.x0
            if (abs(dx) > 8 * density) timers.removeCallbacks(t.longPress)
            if (abs(dx) > t.pk!!.rect.width() / t.pk!!.item.w && abs(y - t.y0) < unit) {
                t.languageDirection = if (dx > 0) 1 else -1
                t.consumed = true
            }
            return
        }
        if (recordGlide(id, t, x, y, time)) return
        if (chordTouches.isCandidate(id)) return
        when (t.kind) {
            K_WHEEL -> {
                t.lastX = x
                val dx = x - t.x0
                if (abs(dx) > 8 * density) { t.moved = true; timers.removeCallbacks(t.longPress) }
                if (t.moved) { clearArmedWord(); t.wheel!!.dragDx = dx; invalidateWheels() }
            }
            K_HANDLE -> { t.lastX = x; if (abs(x - t.x0) > 8 * density) t.moved = true }
            K_CURSOR -> {
                t.fx = x; t.fy = y
                val now = SystemClock.uptimeMillis()
                moveCursor(t, t.joystick.displace((x - t.x0) / density, now), now)
                cursorPointer?.let { invalidatePointer(it); it.follow(y) }
                if (reducedMotion) cursorPointer?.step(t.joystick.push.toFloat(), 0f, true)
                removeCallbacks(cursorFrame)
                postOnAnimation(cursorFrame)
                invalidateWave(t.waveX, t.waveX, t.select)
            }
            K_STACK -> {
                // One swipe moves exactly one face; the rest of the gesture is ignored.
                val sw = swipe ?: return
                if (t.consumed || sw.stepped) return
                val dy = y - t.y0
                if (abs(dy) > 5 * density) t.moved = true
                if (!t.moved) return
                timers.removeCallbacks(t.longPress)
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

    private fun pointerUp(id: Int, time: Long) {
        val t = trackers.remove(id) ?: return
        t.pk?.let { it.presses-- }
        if (cursorTracker === t) cursorTracker = null
        stopTimers(t)
        popup?.takeIf { it.owner === t }?.let {
            chordTouches.update(id, null, true)
            finishChords(chordTouches.release(id).completions)
            val choice = it.choices.getOrNull(it.selected)
            dismissPopup()
            choice?.let { value -> commitPopup(t, value) }
            return
        }
        if (t.languageDirection != 0) {
            listener?.onSwitchLanguage(t.languageDirection)
            haptic(HapticFeedbackConstants.CLOCK_TICK)
            return
        }
        if (glideId == id) {
            t.glideTick?.let { timers.removeCallbacks(it) }; t.glideTick = null
            glideId = null
            if (reducedMotion) trail = emptyList()
            listener?.onGlide(t.points, letterCentres, letterWidth, t.trace?.text().orEmpty(), t.trace?.hints.orEmpty())
            invalidate()
            return
        }
        chordTouches.update(id, t.pk?.item?.keys?.firstOrNull()?.singleOrNull(), t.consumed, t.tapX, t.tapY)
        val chordRelease = chordTouches.release(id)
        if (chordRelease.handled) {
            finishChords(chordRelease.completions)
            invalidate()
            return
        }
        when (t.kind) {
            K_WHEEL -> {
                if (t.consumed) return
                val wh = t.wheel!!
                val list = words(wh)
                if (t.moved && list.isNotEmpty()) {
                    // Settle on whichever word is nearest the middle.
                    var best = 0
                    var bestDist = Float.MAX_VALUE
                    for (k in wheelRange(list.size)) {
                        val dist = abs(offsetOf(wh, k) + wh.dragDx)
                        if (dist < bestDist) { bestDist = dist; best = k }
                    }
                    wh.index = ((wh.index + best) % list.size + list.size) % list.size
                    armWheel(wh)
                    accessibilityEvent(A_WHEEL_LEFT + wh.side, AccessibilityEvent.TYPE_VIEW_SELECTED)
                } else if (list.isNotEmpty()) {
                    invalidatePressAccents()
                    listener?.onSuggestion(wordAt(list, wheelTapIndex(wheelTargets(wh), t.lastX, wh.rect.left, wh.rect.right)))
                }
                wh.dragDx = 0f
            }
            K_HANDLE -> if (t.moved && abs(t.lastX - t.x0) > 36 * density) slideKeyboard(t.lastX < t.x0)
            K_STACK -> {
                if (swipe?.item === t.pk?.item) swipe = null
                if (!t.moved && !t.consumed) emitKey(t.pk!!.item.keys[shownIdx(t.pk!!.item)], t.tapX, t.tapY)
            }
            K_KEY -> if (!t.consumed) commit(t)
            K_GEAR -> listener?.onQuickOptions()
            K_CURSOR -> {
                removeCallbacks(cursorFrame)
                cursorPointer?.release(SystemClock.uptimeMillis(), reducedMotion)
                if (reducedMotion) cursorPointer = null else postOnAnimation(cursorFrame)
                gapPresses.lift(time, t.fx / density, t.fy / density)
                if (t.select) listener?.onSelectMode(false)
            }
        }
        invalidate()
    }

    private fun finishChords(completions: List<ChordTouches.Completion<Int>>) {
        for (completion in completions) {
            if (completion.candidate) finishChord(completion.entries)
            else for (entry in completion.entries) {
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                emitKey(entry.letter.toString(), entry.x, entry.y)
            }
        }
    }

    private fun finishChord(entries: List<ChordTouches.Entry<Int>>) {
        val letters = entries.map { it.letter }.joinToString("")
        val available = grid.items.filter { !it.isStack }.mapNotNull { it.keys[0].singleOrNull()?.takeIf(Char::isLetter)?.lowercaseChar() }.toSet()
        val chord = chordLibrary?.forLetters(available)?.firstOrNull { it.keys == Chords.letters(letters) }
        if (chord != null) {
            listener?.onChord(chord.word)
            haptic(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
            chordLit.fill(false)
            for (c in chord.keys) if (c.code < chordLit.size) chordLit[c.code] = true
            timers.removeCallbacks(clearChordHighlight)
            timers.postDelayed(clearChordHighlight, 150)
            announceForAccessibility(chord.word)
        } else {
            for (entry in entries) {
                haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                emitKey(entry.letter.toString())
            }
        }
    }

    private fun acceptKeyTap(item: GridKey, time: Long): Boolean {
        val previous = lastKeyTaps[item.id]
        if (previous != null && time - previous in 0 until settings.int(Prefs.KEY_REPEAT_GUARD).toLong()) return false
        lastKeyTaps[item.id] = time
        return true
    }

    private fun invalidatePressAccents() {
        for (id in 0..31) {
            val t = trackers[id] ?: continue
            if (t.pressedText != null) { t.pressedText = null; stopTimers(t) }
        }
    }

    private fun emitKey(code: String, tapX: Float = Float.NaN, tapY: Float = Float.NaN) {
        if (code == Keys.BACKSPACE && state.layer == Layer.ABC && trackers.values.any { it.pk?.item?.keys?.contains(Keys.ABC) == true }) {
            for (held in trackers.values) if (held.pk?.item?.keys?.contains(Keys.ABC) == true) { held.consumed = true; stopTimers(held) }
            listener?.onForwardDelete(); return
        }
        val output = KeyText.output(code, state)
        if (output == null) invalidatePressAccents()
        else for (t in trackers.values) if (t.pressedText != null) t.followingText += output
        if (!tapX.isNaN() && !tapY.isNaN() && output?.singleOrNull()?.isLetterOrDigit() == true) {
            tapDistances.fill(100f)
            for (pk in placed) {
                tapPoint[0] = tapX; tapPoint[1] = tapY
                // Each candidate's inverse removes its own tilt/curve transform.
                pk.inverse.mapPoints(tapPoint)
                val width = pk.rect.width().coerceAtLeast(1f)
                val dx = (tapPoint[0] - pk.rect.centerX()) / width
                val dy = (tapPoint[1] - pk.rect.centerY()) / width
                for (key in pk.item.keys) {
                    val letter = key.singleOrNull()?.lowercaseChar() ?: continue
                    if (letter.isLetter() && letter.code < ComposingTaps.LETTERS) {
                        tapDistances[letter.code] = minOf(tapDistances[letter.code], dx * dx + dy * dy)
                    }
                }
            }
            listener?.onTapKey(code, tapDistances)
        } else listener?.onKey(code)
    }

    private fun pressLetter(t: Tracker) {
        val item = t.pk?.item ?: return
        val code = item.keys[shownIdx(item)]
        if (!settings.bool(Prefs.TYPE_ON_PRESS) || state.layer != Layer.ABC || code.singleOrNull()?.isLetter() != true) return
        val output = KeyText.output(code, state) ?: return
        t.pressChoices = KeyText.alternatives(code, state, settings.bool(Prefs.SHOW_HINTS))
        emitKey(code, t.tapX, t.tapY)
        t.pressedText = output
        t.consumed = true
    }

    private fun commitPopup(t: Tracker?, choice: String) {
        val original = t?.pressedText
        if (original != null) listener?.onReplacePressText(original, t.followingText, choice)
        else listener?.onLongPressText(choice)
    }

    private fun commit(t: Tracker) {
        if (t.deferredHaptic) { t.deferredHaptic = false; haptic(HapticFeedbackConstants.KEYBOARD_TAP) }
        val item = t.pk?.item ?: return
        emitKey(item.keys[0], t.tapX, t.tapY)
    }

    /**
     * Holding a spacebar inserts the word centred in the suggestion wheel on its side (left spacebar → left wheel),
     * including a word chosen by spinning. Keyboard switching lives in the system navigation bar instead.
     */
    private fun pickSideSuggestion(item: GridKey): Boolean {
        val wheel = wheels[if (item.c < KeyGrid.CENTER) 0 else 1]
        val list = words(wheel)
        if (list.isEmpty()) return false
        listener?.onSuggestion(wordAt(list, wheel.index))
        return true
    }

    private fun onLongPress(t: Tracker) {
        if (t.kind == K_WHEEL && !t.moved) {
            val wheel = t.wheel ?: return
            val list = words(wheel)
            if (list.isEmpty()) return
            val word = wordAt(list, wheelTapIndex(wheelTargets(wheel), t.lastX, wheel.rect.left, wheel.rect.right))
            t.consumed = true
            android.widget.PopupMenu(context, this).apply {
                menu.add("Forget word").setOnMenuItemClickListener { listener?.onForgetWord(word); true }
                show()
            }
            return
        }
        val item = t.pk?.item ?: return
        val code = item.keys[shownIdx(item)]
        if (code == Keys.SPACE) {
            // Nothing to pick: let the lift type a normal space.
            if (!pickSideSuggestion(item)) return
        } else {
            if (!openPopup(t.pk ?: return, t)) return
        }
        t.consumed = true
        haptic(HapticFeedbackConstants.LONG_PRESS)
    }

    /** The key under a point. Gaps and edges belong to the nearest key, so taps never fall through. */
    private fun findKey(x: Float, y: Float, strict: Boolean = false, skip: GridKey? = null): Placed? {
        val pt = hitPoint
        val baseSlack = settings.int(Prefs.KEY_GAP) * density / 2 + 1
        val extraSlack = settings.int(Prefs.TOUCH_SLACK) * density
        var hit: Placed? = null
        // Existing targets win before extra slack: a neighbour must never swallow a key's center.
        for (pass in 0..1) {
            val slack = baseSlack + if (pass == 0) 0f else extraSlack
            for (i in placed.size - 1 downTo 0) {
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
            if (hit != null || extraSlack == 0f) break
        }
        fun isLetter(pk: Placed) = !pk.item.isStack && pk.item.keys[0].let { it.length == 1 && it[0].isLetter() }
        if (!strict && !editMode && state.layer == Layer.ABC && settings.bool(Prefs.SMART_TARGETS) &&
            keyWeights.isNotEmpty() && (hit == null || isLetter(hit))) {
            var best: Placed? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in placed.indices) {
                val pk = placed[i]
                if (pk.item === skip || !isLetter(pk)) continue
                pt[0] = x; pt[1] = y
                pk.inverse.mapPoints(pt)
                val r = pk.rect
                val insetX = r.width() * .15f; val insetY = r.height() * .15f
                if (pt[0] >= r.left + insetX && pt[0] < r.right - insetX && pt[1] >= r.top + insetY && pt[1] < r.bottom - insetY) return pk
                val expandX = r.width() * .35f; val expandY = r.height() * .35f
                if (pt[0] < r.left - expandX || pt[0] >= r.right + expandX || pt[1] < r.top - expandY || pt[1] >= r.bottom + expandY) continue
                val dx = ((pt[0] - r.centerX()) / r.width()).toDouble()
                val dy = ((pt[1] - r.centerY()) / r.height()).toDouble()
                val code = pk.item.keys[0][0].lowercaseChar().code
                val weight = if (code in letterWeights.indices) letterWeights[code] else 0.0
                val score = -(dx * dx + dy * dy) / (2 * 0.35 * 0.35) + ln(0.05 + weight)
                if (score > bestScore) { bestScore = score; best = pk }
            }
            if (best != null) return best
        }
        if (hit != null) return hit
        if (strict || y < stripH) return null
        var best: Placed? = null
        var bestDist = Float.MAX_VALUE
        for (i in placed.indices) {
            val pk = placed[i]
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
        settings.setAlignFor(isLandscape, align)
    }

    /** Dragging the bar under the keys: full width shrinks to one-hand size on that side; the other way restores it. */
    private fun slideKeyboard(left: Boolean) {
        val land = isLandscape
        val p = settings.profile(land)
        val change = handleChange(p.widthPercent, p.align, p.split, left, restoreWidth)
        restoreWidth = change.restore
        settings.setHandleFor(land, change.width, change.align)
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
        slideDistance = 0f
        super.onDetachedFromWindow()
    }

    // ---- Edit mode: drag and drop ----

    private class Target(val type: Int, val item: GridKey? = null, val before: Boolean = false, val r: Int = 0, val c: Int = 0, val pk: Placed? = null, val cell: Cell? = null)
    private class Drag(val item: GridKey, val w: Float, val h: Float, var x: Float, var y: Float, val offX: Float, val offY: Float) { var target: Target? = null }

    private var drag: Drag? = null
    private var editingKey: GridKey? = null

    private fun editTouch(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (y < stripH) {
                    when {
                        undoRect.contains(x, y) -> undo()
                        moreRect.contains(x, y) -> listener?.onOpenSettings()
                        doneRect.contains(x, y) -> finishEditing()
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
                dg.target = editTarget(x, y, dg)
                drag = null
                editHint = null
                val before = grid.toJson()
                applyDrop(dg)
                if (dg.target?.type == T_REMOVE && grid.toJson() == before) invalidate()
                else edited(before, normalize = dg.target?.type != T_REMOVE)
            }
            MotionEvent.ACTION_CANCEL -> { drag = null; editHint = null; invalidate() }
        }
        return true
    }

    /** Saves the layout as it was [before] an edit, so undo can return to it. Edits that changed nothing are skipped. */
    private fun edited(before: String, normalize: Boolean = true) {
        if (normalize) grid.normalize()
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
        if (!g.isSound()) return
        val before = grid.toJson()
        keepHistory = true
        grid = g
        keepHistory = false
        if (g.toJson() != before) {
            history += before
            listener?.onEdited()
        }
    }

    /** Offscreen settings thumbnails use the exact same rectangles, fan and split transforms. */
    internal data class ThumbnailKey(val path: Path, val bounds: RectF, val kind: Int)

    internal fun thumbnailKeys(width: Int, profile: SizeProfile): List<ThumbnailKey> {
        layoutFor(width, profile)
        return placed.map { key ->
            val radius = settings.int(Prefs.CORNER_RADIUS) * density
            val path = Path().apply { addRoundRect(key.rect, radius, radius, Path.Direction.CW); transform(key.matrix) }
            val bounds = RectF().also { path.computeBounds(it, true) }
            val face = key.item.keys.firstOrNull()
            ThumbnailKey(path, bounds, when (face) { Keys.ENTER -> 2; Keys.ABC, Keys.NUM, Keys.SYM, Keys.BACKSPACE -> 1; else -> 0 })
        }
    }

    private fun editTarget(x: Float, y: Float, dg: Drag): Target? {
        if (hintRect.contains(x, y)) return Target(T_REMOVE)
        findKey(x, y, strict = true, skip = dg.item)?.let { pk ->
            val item = pk.item
            if (item.isSpace && item.w > 1 && KeyGrid.region(item.c) != 1 && !dg.item.isSpace) return Target(T_SPACE, item, pk = pk)
            val pt = floatArrayOf(x, y)
            pk.inverse.mapPoints(pt)
            val rel = (pt[0] - pk.rect.left) / pk.rect.width()
            return if (rel > 0.25f && rel < 0.75f) Target(T_COMBINE, item, pk = pk) else Target(T_INSERT, item, before = rel <= 0.25f, pk = pk)
        }
        val pt = FloatArray(2)
        for (i in cells.indices) {
            val cell = cells[i]
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
        T_REMOVE -> drag?.item?.let { grid.removalHint(it) } ?: "Remove key"
        else -> null
    }

    private fun applyDrop(dg: Drag) {
        val t = dg.target ?: return
        when (t.type) {
            T_REMOVE -> {
                val hint = grid.removalHint(dg.item)
                if (!grid.remove(dg.item) && hint != null) {
                    editHint = hint
                    announceForAccessibility(hint)
                    postDelayed({ editHint = null; invalidate() }, 1800)
                }
            }
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
                val radius = cornerRadius
                canvas.drawRoundRect(r, radius, radius, stroke)
            }
        }
    }

    private fun drawGhost(canvas: Canvas, dg: Drag, radius: Float) {
        val rect = ghostRect
        rect.set(0f, 0f, dg.w, dg.h)
        canvas.save()
        canvas.translate(dg.x - dg.offX - dg.w / 2, dg.y - dg.offY - dg.h / 2)
        canvas.scale(1.1f, 1.1f, dg.w / 2, dg.h / 2)
        drawKey(canvas, dg.item, rect, radius, false, 0.92f)
        canvas.restore()
    }

    private companion object {
        val SHAPE_UNDO_HEAD = floatArrayOf(-0.17f, -0.17f, 0f, 0f, 0.17f, -0.17f)
        val SHAPE_RETURN_HEAD = floatArrayOf(-0.2f, -0.15f, -0.45f, 0.1f, -0.2f, 0.35f)
        val SHAPE_RETURN = floatArrayOf(0.45f, -0.4f, 0.45f, 0.1f, -0.45f, 0.1f)
        val SHAPE_DELETE_X2 = floatArrayOf(0f, 0.15f, 0.3f, -0.15f)
        val SHAPE_DELETE_X1 = floatArrayOf(0f, -0.15f, 0.3f, 0.15f)
        val SHAPE_DELETE = floatArrayOf(-0.55f, 0f, -0.22f, -0.38f, 0.55f, -0.38f, 0.55f, 0.38f, -0.22f, 0.38f)
        /** Bell-shaped opacity across the cursor glow, centre brightest. */
        val WAVE_STOPS = floatArrayOf(0f, 0.15f, 0.3f, 0.5f, 0.7f, 0.85f, 1f)
        val WAVE_FALLOFF = floatArrayOf(0f, 0.12f, 0.5f, 1f, 0.5f, 0.12f, 0f)
        const val A_POPUP = 20000
        const val A_EDIT = 0x01020000
        const val A_NONE = Int.MIN_VALUE
        const val A_WHEEL_LEFT = 10000
        const val A_WHEEL_RIGHT = 10001
        const val A_GEAR = 10002
        const val A_CURSOR = 11000
        const val A_SWITCH = 10007
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
        const val T_REMOVE = 4
        const val T_SPACE = 1
        const val T_COMBINE = 2
        const val T_INSERT = 3

        /** Column raise (fraction of a row) and fan angle (degrees) for each half, outer to inner on the left. */
        val RAISE = arrayOf(floatArrayOf(0f, .22f, .38f, .28f, .16f), floatArrayOf(.16f, .28f, .38f, .22f, 0f))
        val ANG = arrayOf(floatArrayOf(-9f, -4f, 0f, 3f, 6f), floatArrayOf(-6f, -3f, 0f, 4f, 9f))
    }
}
