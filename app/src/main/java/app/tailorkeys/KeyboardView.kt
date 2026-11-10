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
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import app.tailorkeys.layout.CenterSide
import app.tailorkeys.layout.GridKey
import app.tailorkeys.layout.HintStyle
import app.tailorkeys.layout.HoldChoices
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
 * In [editMode] (Rearrange in quick options, or the settings screen) unselected keys wiggle, and
 * dragging a key moves it: onto an empty cell, onto another key's edge (insert and shift), onto its middle
 * (combine into a swipe key), or onto a spacebar (sit on its inner side while it shrinks). The suggestion bar
 * turns into a selection toolbar with undo and, inside the keyboard, settings and Done.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val settings: KeyboardSettings) : View(context) {

    interface Listener {
        fun onChord(word: String) {}
        fun onForwardDelete() {}
        fun onKey(code: String)
        /** A letter tap: [distances] to each letter (drift removed), and where it landed ([x], [y], view pixels). */
        fun onTapKey(code: String, distances: FloatArray, x: Float, y: Float) { onKey(code) }
        /**
         * The same tap's features (TapFeatures): squared distances to a to z, the apostrophe and the space bar, across
         * in key spacings and down in row spacings; just before [onTapKey]. The typing log keeps them (TypingLog).
         */
        fun onModelTap(features: FloatArray) {}
        /** A space tap's squared distance to the nearest letter, as in [onModelTap]; just before [onKey]. */
        fun onSpaceTap(nearestLetter: Float) {}
        fun onLetterLayout(spots: List<Pair<Char, KeySpot>>) {}
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
        fun onCursor(steps: Int, select: Boolean, words: Boolean = false) {}
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
        set(value) { field = value; layerGrids.fill(null); if (!keepHistory) history.clear(); clearSelection(); halfPreview = null; cancelTouches(); relayout() }
    var enterLabel: String? = null
        set(value) { field = value; refreshAccessibilityState(); invalidate() }
    var editMode = false
        set(value) { if (field != value) {
            // A resize or row drag still under the finger is put back, as a cancelled touch does: it never saved.
            shapeDrag?.let { sd -> shaping?.let { it.edges = sd.edges.copyOf() } }
            groupDrag?.let { gd -> selectedRow?.let { ed.setRowScale(it, gd.rowScale) } }
            field = value; if (value) { holdPickLayer = null; grid.offerExtras() }; clearArmedWord(); listener?.onChoiceInterrupted(); editingKey = null; shaping = null; shapeDrag = null; selectedHalf = null; selectedRow = null; groupDrag = null; halfPreview = null; editLayer = Layer.ABC; facePicker = null; sidePicker = null; cancelTouches(); relayout() } }
    /** True inside the keyboard itself: shows the gear, and settings and Done while rearranging. */
    var inKeyboard = false
        set(value) { field = value; relayout() }

    /** Layout edits optionally include a half-settings snapshot, without capturing the base sliders. */
    private data class EditState(val json: String, val halves: String? = null)
    private val history = ArrayList<EditState>()
    private var keepHistory = false
    /** The layer being rearranged. */
    private var editLayer = Layer.ABC
    /** The layer the keys show: the one being edited while rearranging, else the one being typed on. */
    private val shownLayer get() = if (editMode) editLayer else holdPickLayer ?: state.layer
    /** 123 and #+= as built from [grid] (see [KeyGrid.layer]), made when first needed and dropped when [grid] changes. */
    private val layerGrids = arrayOfNulls<KeyGrid>(3)
    private fun gridFor(layer: Layer): KeyGrid =
        if (layer == Layer.ABC) grid else layerGrids[layer.ordinal] ?: grid.layer(layer).also { layerGrids[layer.ordinal] = it }
    /** The grid being rearranged: the letters, or the 123 or #+= keys being edited. */
    private val ed get() = gridFor(editLayer)

    /**
     * The layer whose keys are being chosen for Keys with long-press (Chosen keys), or null while not choosing. The
     * preview then shows that layer: a tap turns a key's long-press on or off, the circle before a row the whole
     * row's, and a bar on top switches between abc, 123 and #+= and has All on, All off and Done.
     */
    var holdPickLayer: Layer? = null
        set(value) {
            if (field == value) return
            field = value
            if (value != null) editMode = false
            dismissPopup()
            cancelTouches()
            pickOff = settings.savedHoldOff()
            relayout()
            if (value != null) announceForAccessibility(when (value) { Layer.ABC -> "Choosing letters"; Layer.NUM -> "Choosing numbers"; Layer.SYM -> "Choosing symbols" })
        }
    /** Called by Done in the chooser's bar. */
    var onHoldPickDone: (() -> Unit)? = null
    /** The saved keys with their long-press off ([KeyText.holdId]), read whatever Keys with long-press is set to. */
    private var pickOff = emptySet<String>()
    /** Room on the left for the row circles while choosing: the keys are laid out that much narrower. */
    private val pickReserve get() = if (holdPickLayer != null) (36 * density).toInt() else 0
    private val layoutWidth get() = width - pickReserve
    /** The chooser bar's chips: abc, 123, #+=, All on, All off, Done. */
    private val pickChips = Array(6) { RectF() }
    private class PickRow(val cx: Float, val cy: Float, val ids: List<String>)
    /** The row circles as last drawn, with the keys each one switches. */
    private val pickRows = ArrayList<PickRow>()
    private var pickDownX = 0f
    private var pickDownY = 0f
    var editTitle: String? = null
    /** Bounds already resolved by layoutFor, including narrow/one-hand profiles. */
    internal var quickOptionsBounds = RectF()
        private set

    private fun finishEditing() { editMode = false; listener?.onEditDone() }

    /** The key being shaped while rearranging: tapped to select it, its corners dragged to resize, itself to nudge. */
    private var shaping: GridKey? = null
    /** A resize ([corner] 0 to 3 from the top left, clockwise) or a nudge ([corner] -1) of [shaping] in progress. */
    private class Shape(val corner: Int, val startX: Float, val startY: Float, val edges: FloatArray, val rect: RectF,
                        val column: Float, val rowHeight: Float, val inverse: Matrix, val before: String, var moved: Boolean = false)
    private var shapeDrag: Shape? = null
    private var lastShapeTap = 0L
    /** The buttons beside the shaped key: the other keys of its row, or of every row, close up around it. */
    private val compactRowChip = RectF()
    private val compactAllChip = RectF()

    /** The kind of object a tap selects; Key keeps the existing drag-and-drop gestures. */
    private enum class Selection { HALF, ROW, KEY }
    private var selection = Selection.KEY
    /** The selected split side (0 left, 1 right), or the logical row across all blocks. */
    private var selectedHalf: Int? = null
    private var selectedRow: Int? = null
    /** The row's touched block is used to measure vertical drags in its own coordinates. */
    private var rowRegion = 0
    /** Selection modes and actions occupy two compact toolbar bands. */
    private val selectionChips = Array(3) { RectF() }
    private val resetChip = RectF()
    private val mirrorChip = RectF()
    /** Live half drags stay in the view until release, then save and notify once. */
    private var halfPreview: SizeProfile? = null
    /** Frozen start geometry for a corner, tilt dot, row edge, or sideways half gesture. */
    private class GroupDrag(val handle: Int, val x: Float, val y: Float, val rect: RectF,
                            val inverse: Matrix, val profile: SizeProfile, val before: String,
                            val halves: String, val rowScale: Float, val rowHeight: Float, val blockHeight: Float,
                            val insets: FloatArray, val room: Float, val gap: Float, val center: Float,
                            var moved: Boolean = false)
    private var groupDrag: GroupDrag? = null

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

    /** [bowl]: how deep in its half's bowl the key sits (0 to 1), or -1 when it is drawn at its rect's size. */
    private class Placed(val item: GridKey, val rect: RectF, val matrix: Matrix, val inverse: Matrix, var bowl: Float = -1f) {
        var presses = 0
        /** The width of one column in the key's row, which its edges ([GridKey.edges]) are measured in. */
        var column = 0f
        /** The unshaped cell height, including its share of the row adjustment. */
        var rowHeight = 0f
    }
    private class Cell(val r: Int, val c: Int, val rect: RectF, val matrix: Matrix, val inverse: Matrix)
    private class Wheel(val side: Int) {
        val rect = RectF(); var index = 0; var dragDx = 0f
        var widths = FloatArray(0)
        var boldWidths = FloatArray(0)
        var prefixWidths = FloatArray(0)
    }

    /** Each layer's keys as placed; [placed] is the showing layer's, swapped in by [ensureLayout] when the layer changes. */
    private val placedBy = Array(3) { ArrayList<Placed>() }
    private var placed = placedBy[0]
    /** Each hand's drift, owned by the service so it outlives the view. Null leaves taps where they land. */
    var touchDrift: TouchDrift? = null
    /** Bumped whenever the keys are placed again, so taps from an older layout aren't learned from. */
    var layoutGeneration = 0
        private set
    private val letterWeights = DoubleArray(ComposingTaps.LETTERS)
    private val chordLit = BooleanArray(ComposingTaps.LETTERS)
    private val tapDistances = FloatArray(ComposingTaps.LETTERS)
    private val modelTap = FloatArray(TapFeatures.FEATURES)
    private val tapPoint = FloatArray(2)
    private val cells = ArrayList<Cell>()
    /** One block of columns (left half, center column, right half) and its transform. */
    private class Cont(val c0: Int, val cols: Int, val u: Float, val side: Int, val base: Matrix, val un: Float)
    /** Edit-mode blocks from the last layout, reused to place a preview grid without a full layout pass. */
    private var editConts = emptyList<Cont>()
    private var editGap = 0f
    /** Below the keys while rearranging: removed and unstacked keys wait here as mini keys. */
    private val trayRect = RectF()
    private val trayChips = ArrayList<RectF>()
    private var trayKeys = emptyList<GridKey>()
    private val wheels = listOf(Wheel(0), Wheel(1))
    private val handleRect = RectF()
    /** The split halves' grips (left, right), their outer insets (px) and how far in both may go together. */
    private val grips = arrayOf(RectF(), RectF())
    private val splitInsets = FloatArray(2)
    private var splitRoom = 0f
    /**
     * With center keys: the gap they need (px) in the center or outside each half, the gap with both halves at the
     * screen edges, and whether they sit outside now (0 when there are none).
     */
    private var centerNeed = 0f
    private var gapAtEdges = 0f
    private var centerOutside = false
    /** A half being dragged by its grip (-1 for none), how far so far, and the last grip tap for double-taps. */
    private var gripSide = -1
    private var gripDx = 0f
    private var gripTapAt = 0L
    private var gripTapSide = -1
    private var centerZone: CursorZone? = null
    private var cursorZones = emptyList<CursorZone>()
    /**
     * A touch bar: [zone] in its own coordinates (x along the bar), [toScreen] places it on the keyboard with its
     * half's tilt and curve, [screenZone] is its upright bounds. [side] is the split half (0, 1), or -1 across.
     */
    private class TouchBar(val zone: CursorZone, val toScreen: Matrix, val side: Int, val onSpace: Boolean = false) {
        val toLocal = Matrix().also { toScreen.invert(it) }
        val rect = zone.bounds.let { RectF(it.left, it.top, it.right, it.bottom) }
        val screenZone = RectF().also { toScreen.mapRect(it, rect) }.let {
            CursorZone(listOf(ZonePoint(it.left, it.top), ZonePoint(it.left, it.bottom)),
                listOf(ZonePoint(it.right, it.top), ZonePoint(it.right, it.bottom)), it.top, it.bottom)
        }
        private val pt = FloatArray(2)
        /** [x], [y] in the bar's coordinates (shared scratch: read before the next call). */
        fun local(x: Float, y: Float): FloatArray { pt[0] = x; pt[1] = y; toLocal.mapPoints(pt); return pt }
        fun contains(x: Float, y: Float) = local(x, y).let { zone.contains(it[0], it[1]) }
    }
    private var touchBars = emptyList<TouchBar>()
    /** The touch bar's height (with its share of the key gap), or 0 without one. */
    private var barH = 0f
    private var pointerBar: TouchBar? = null
    private val pointerDirty = RectF()
    private var outerCursorActive = false
    private var switchRect: RectF? = null
    /** Lock halves ([Prefs.SPLIT_LOCKED]), and when it last changed, for the grips' fade. */
    private var halvesLocked = false
    private var lockChangedAt = 0L
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
    var insertStripHeight = 0
        set(value) { if (field != value) { field = value; builtWidth = -1; requestLayout(); invalidate() } }
    private var stripH = 0f
    private var unit = 0f
    private var letterCentres = emptyMap<Char, GestureDecoder.Point>()
    private var letterWidth = 1f
    private var builtWidth = -1
    private var restoreWidth: Int? = null

    var previewPressed = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var previewPalette: Palette? = null
        set(value) { if (field != value) { field = value; reloadSettings(); invalidate() } }
    private var palette = previewPalette ?: Palette.of(context, settings)
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
    private var highContrast = previewPalette == null && settings.theme() == ThemeName.HIGH_CONTRAST
    private val systemTextScale get() = if (settings.bool(Prefs.FOLLOW_SYSTEM_TEXT)) resources.configuration.fontScale.coerceIn(0.85f, 1.6f) else 1f
    private var labelScale = settings.int(Prefs.TEXT_SCALE) / 100f * systemTextScale
    private var iconScale = settings.int(Prefs.TEXT_SCALE) / 100f
    private var cornerRadius = settings.int(Prefs.CORNER_RADIUS) * density
    private var tintKeys = settings.bool(Prefs.KEY_TINT)
    private var showHints = settings.bool(Prefs.SHOW_HINTS)
    /** Key preview, long-press and hint settings, read with the drawing cache. */
    private var previewStyle = settings.int(Prefs.KEY_PREVIEW)
    private var holdShows = settings.holdChoices()
    private var hintStyle = settings.hintStyle()
    private var holdOff = settings.holdOff()
    private var depth = settings.int(Prefs.DEPTH) / 100f
    private var keys3d = settings.bool(Prefs.KEYS_3D)
    /** A 3D key's top, inside its cell (see [drawKey]). */
    private val keyTop = RectF()
    // Unit gradients (0 to 1 down), scaled onto each key: the shadowed upper wall and the lit lower lip of a sunk key.
    private val chordsAllowed get() = settings.bool(Prefs.CHORDING) && !settings.bool(Prefs.TYPE_ON_PRESS)

    /** A selected layout previews its own split without changing the Size preferences. */
    var previewTargetHeight: Int? = null
        set(value) { if (field != value) { field = value; relayout() } }
    var previewSplit: Boolean? = null
        set(value) { if (field != value) { field = value; relayout() } }
    /**
     * The halves' shapes and placement of a layout shown but not in use (a [KeyboardSettings.halfGeometry]
     * snapshot from its saved settings): shown, and changed by half edits, Reset and Undo, instead of the settings
     * in use. Null for the layout in use.
     */
    var previewHalves: String? = null
        set(value) { if (field != value) { field = value; relayout() } }
    private fun displayProfile() = (halfPreview ?: settings.profile(isLandscape, previewHalves)).let { p -> previewSplit?.let { p.copy(split = it) } ?: p }
    private fun halves() = previewHalves ?: settings.halfGeometry()
    private fun saveHalves(profile: SizeProfile) {
        val shown = previewHalves
        if (shown != null) previewHalves = settings.halvesWith(shown, isLandscape, profile)
        else settings.setHalfGeometryFor(isLandscape, profile)
    }

    private data class GeometrySettings(
        val profile: SizeProfile, val curve: Int, val gap: Int, val labelScale: Float, val suggestionBar: Int, val locked: Boolean,
        val sameOrientations: Boolean, val cursor: CursorControl,
        val landscape: Boolean, val screenWidth: Int, val screenHeight: Int,
        // Compact depth moves keys, so it lays them out again.
        val compactDepth: Int?,
    )
    private fun geometrySettings() = GeometrySettings(displayProfile(),
        settings.int(Prefs.CURVE), settings.int(Prefs.KEY_GAP), labelScale, settings.int(Prefs.SUGGESTION_BAR), settings.bool(Prefs.SPLIT_LOCKED),
        settings.bool(Prefs.SAME_ORIENTATIONS), settings.cursorControl(),
        isLandscape, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels,
        if (settings.bool(Prefs.DEPTH_COMPACT)) settings.int(Prefs.DEPTH) else null)
    private var builtGeometry: GeometrySettings? = null
    /** Layer row heights also move touch bars and cursor boundaries. */
    private var builtLayer = Layer.ABC

    private data class DrawingSettings(
        val theme: String, val customThemes: String?, val preview: Palette?, val night: Int, val language: String, val multipleLanguages: Boolean,
        val textScale: Int, val labelScale: Float, val radius: Int, val tint: Boolean, val hints: Boolean, val depth: Int,
        val keys3d: Boolean, val keyPreview: Int, val holdShows: Int, val hintStyle: Int, val holdOff: Set<String>,
    )
    private fun drawingSettings() = DrawingSettings(settings.themeId(), settings.prefs.getString(Prefs.THEMES_CUSTOM, null), previewPalette, resources.configuration.uiMode,
        state.language, settings.languages().size > 1, settings.int(Prefs.TEXT_SCALE), labelScale,
        settings.int(Prefs.CORNER_RADIUS), settings.bool(Prefs.KEY_TINT), settings.bool(Prefs.SHOW_HINTS), settings.int(Prefs.DEPTH), settings.bool(Prefs.KEYS_3D),
        settings.int(Prefs.KEY_PREVIEW), settings.int(Prefs.LONG_PRESS_SHOWS), settings.int(Prefs.HINT_STYLE), settings.holdOff())
    private var builtDrawing: DrawingSettings? = null

    private fun refreshMotion() {
        reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
            !ValueAnimator.areAnimatorsEnabled()
        if (reducedMotion && glideId == null) trail = emptyList()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        refreshMotion()
        readNavigationInset()
        invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) { refreshMotion(); readNavigationInset(); invalidate() }
    }

    fun reloadSettings() {
        clearArmedWord()
        cancelTouches()
        refreshMotion()
        state.language = settings.currentLanguage()
        dismissPopup()
        palette = previewPalette ?: Palette.of(context, settings)
        highContrast = previewPalette == null && settings.theme() == ThemeName.HIGH_CONTRAST
        labelScale = settings.int(Prefs.TEXT_SCALE) / 100f * systemTextScale
        multipleLanguages = settings.languages().size > 1
        languageLabel = state.language.uppercase()
        cursorGlow = settings.bool(Prefs.CURSOR_GLOW)
        settings.bool(Prefs.SPLIT_LOCKED).let { locked ->
            // The first read shows the controls as they are; later changes fade.
            if (locked != halvesLocked) { halvesLocked = locked; lockChangedAt = if (isLaidOut) SystemClock.uptimeMillis() else 0L }
        }
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
        val lw = w - pickReserve
        setMeasuredDimension(w, if (builtWidth == lw) layoutHeight else layoutFor(lw))
    }

    /**
     * How far the navigation bar covers this view's bottom edge. From Android 15 the keyboard's window runs under the
     * navigation bar and its buttons are drawn over it, so in the keyboard the keys sit above the bar ([layoutFor]).
     */
    private var navigationInset = 0

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        takeNavigationInset(insets)
        return super.onApplyWindowInsets(insets)
    }

    /**
     * Android sends insets only when they change, so a keyboard view made after that never hears them: it also reads
     * the window's current insets whenever it is attached or shown.
     */
    private fun readNavigationInset() {
        if (!inKeyboard) return
        rootWindowInsets?.let(::takeNavigationInset)
        requestApplyInsets()
    }

    private fun takeNavigationInset(insets: WindowInsets) {
        val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom else 0
        if (bottom != navigationInset) {
            navigationInset = bottom
            builtWidth = -1
            requestLayout()
            invalidate()
        }
    }

    private fun ensureLayout() {
        if ((builtWidth != layoutWidth || builtLayer != shownLayer) && width > 0) layoutFor(layoutWidth)
        val shown = placedBy[shownLayer.ordinal]
        if (placed !== shown) { placed = shown; refreshAccessibilityState() }
    }

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
        val tilt = if (!split) 0f else p.tilt.toFloat()
        val n = grid.rows
        val gap = settings.int(Prefs.KEY_GAP) * d
        // The suggestion bar at its set height (Size), never too short for its words.
        stripH = max(max((if (screenH / d < 500) 34 else 40) * d, 22 * d * labelScale + 8 * d) * settings.int(Prefs.SUGGESTION_BAR) / 100f,
            16 * d * labelScale + 6 * d)
        if (editMode) stripH = max(stripH, 80 * d)
        if (insertStripHeight > 0 && !editMode) stripH = insertStripHeight.toFloat()
        // Locked halves have no grips to drag, so their band under the keys goes to the keys.
        val handleH = if (split && halvesLocked && !editMode) 0f else 14 * d
        val pad = 4 * d
        val trayH = if (editMode) 52 * d else 0f
        // The navigation bar's band is added on top of the set height, so keys keep the size chosen in the preview.
        val navigation = if (inKeyboard) navigationInset.toFloat() else 0f
        val bottomPad = p.bottomPaddingDp * d + trayH + navigation
        // "Same size when rotated": landscape keeps portrait's physical key size instead of applying the same
        // percentages to the shorter screen. Split halves keep their portrait width and sit at the edges.
        val dm = resources.displayMetrics
        val sameScale = landscape && settings.bool(Prefs.SAME_ORIENTATIONS)
        val portraitW = min(dm.widthPixels, dm.heightPixels).toFloat()
        val portraitH = max(dm.widthPixels, dm.heightPixels).toFloat()
        val target = (previewTargetHeight?.toFloat() ?: min((if (sameScale) portraitH else screenH) * p.heightPercent / 100f, screenH * 0.72f)) + navigation
        // Width past 100 (split only) keeps the halves at the edges and shrinks the gap instead ([SizeProfile.gap]).
        val widthPercent = min(p.widthPercent, 100)
        var areaW = if (sameScale && !split) min(width.toFloat(), portraitW * widthPercent / 100f) else width * widthPercent / 100f
        var areaLeft = when (p.align) { Align.LEFT -> 0f; Align.CENTER -> (width - areaW) / 2f; Align.RIGHT -> width - areaW }
        var innerW = areaW - 2 * pad
        var innerLeft = areaLeft + pad
        val half = when {
            !split -> innerW
            sameScale -> { val pInner = portraitW * widthPercent / 100f - 2 * pad; min((pInner - pInner * p.gap / 100f) / 2f, innerW / 2f) }
            else -> (innerW - innerW * p.gap / 100f) / 2f
        }
        val control = settings.cursorControl()
        barH = if (!editMode && n >= 2 && usesTouchBar(control, split)) 28 * d else 0f
        val hasCenterKeys = Layer.entries.any { l -> gridFor(l).let { g -> g.items.any { it.c == KeyGrid.CENTER && it.r < g.rows && !it.isGap } } }
        val solveStrip = stripH + if (editMode) 28 * d else 0f
        val minGap = 8 * d
        val pair = if (split) layoutSolver.computeSplit(d, n, gap, solveStrip, handleH, pad, bottomPad,
            target, half, width - 2 * pad, tilt, curve, grid, p.leftShape, p.rightShape, barH, screenH * .72f) else null
        val s = pair?.left ?: layoutSolver.compute(d, n, gap, solveStrip, handleH, pad, bottomPad,
            target, half, innerW, false, editMode, 0f, curve, grid, barH)
        val rs = pair?.right ?: s
        if (split) {
            // Insets use visible spans, so either half can grow without crossing its neighbour or the screen.
            splitRoom = max(0f, width - 2 * pad - s.span - rs.span - minGap)
            gapAtEdges = width - 2 * pad - s.span - rs.span
            centerNeed = if (hasCenterKeys) CenterOverflow.MIN_KEYS * min(s.cw, rs.cw) / 5 + 4 * d else 0f
            val (l, r) = SplitPlacement.insets(p.leftInset, p.rightInset, width.toFloat(), splitRoom)
            splitInsets[0] = l; splitInsets[1] = r
            areaLeft = 0f; areaW = width.toFloat()
            innerLeft = pad + l; innerW = width - 2 * pad - l - r
        }
        val keyGap = if (split) innerW - s.span - rs.span else 0f
        val slotLeft = if (split) min(s.span, (innerW - 36 * d).coerceAtLeast(0f) / 2) else 0f
        val slotRight = if (split) min(rs.span, (innerW - 36 * d).coerceAtLeast(0f) / 2) else 0f
        unit = min(s.un, rs.un)
        // The smaller half shares the larger half's bottom edge; each keeps its own row and key sizes.
        val total = if (split) max(s.total, rs.total) else max(target, s.total)
        val contentBottom = total - handleH - bottomPad
        val y0 = if (split) contentBottom - max(s.hc, rs.hc) else solveStrip + s.padTop + max(0f, target - s.total)
        val hc = if (split) max(s.hc, rs.hc) else s.hc

        val conts = ArrayList<Cont>()
        val showCenter = editMode || grid.items.any { it.c == KeyGrid.CENTER && it.r < n }
        if (!split) {
            val u = innerW / (if (showCenter) 11 else 10)
            conts += Cont(0, 5, u, -1, Matrix().apply { setTranslate(innerLeft, y0) }, s.un)
            if (showCenter) conts += Cont(5, 1, u, -1, Matrix().apply { setTranslate(innerLeft + 5 * u, y0) }, s.un)
            conts += Cont(6, 5, u, -1, Matrix().apply { setTranslate(innerLeft + (if (showCenter) 6 else 5) * u, y0) }, s.un)
        } else {
            fun base(sol: LayoutSolver.Solution, side: Int): Matrix {
                val x = if (side == 0) innerLeft + sol.pv - sol.cw else innerLeft + innerW - sol.pv
                val top = contentBottom - sol.hc + sol.letterY
                val angle = (tilt + (if (side == 0) p.leftShape else p.rightShape).tilt) * (if (side == 0) 1 else -1)
                return Matrix().apply {
                    setTranslate(x, top)
                    postRotate(angle, x + (if (side == 0) sol.cw else 0f), top + n * sol.un + barH)
                }
            }
            conts += Cont(0, 5, s.cw / 5, 0, base(s, 0), s.un)
            val cwc = max(10 * d, min(min(s.cw, rs.cw) / 5, keyGap - 4 * d))
            // The halves turn about their inner bottom corners, so the center column's rows end there. With negative
            // tilt those corners sit above the halves' shared bottom edge (the outer corners hang lower).
            fun pivotY(sol: LayoutSolver.Solution) = contentBottom - sol.hc + sol.letterY + n * sol.un + barH
            val centerTop = (pivotY(s) + pivotY(rs)) / 2 - n * unit - barH
            conts += Cont(5, 1, cwc, -1, Matrix().apply {
                setTranslate(innerLeft + s.span + (keyGap - cwc) / 2, centerTop)
            }, unit)
            conts += Cont(6, 5, rs.cw / 5, 1, base(rs, 1), rs.un)
        }

        for (l in Layer.entries) placeKeys(gridFor(l), conts, gap, curve, placedBy[l.ordinal])
        // A gap too narrow for the center column sends its keys to matching columns outside the halves.
        val outsideW = min(innerLeft, width - innerLeft - innerW)
        centerOutside = split && !editMode && centerNeed > 0f && keyGap < centerNeed && outsideW >= centerNeed
        if (centerOutside) {
            val colW = min(min(s.cw, rs.cw) / 5, outsideW - 4 * d)
            val xs = floatArrayOf((innerLeft - colW) / 2, innerLeft + innerW + (width - innerLeft - innerW - colW) / 2)
            for (l in Layer.entries) placeOutside(gridFor(l), placedBy[l.ordinal], xs, colW, contentBottom - n * unit - barH,
                layoutSolver.rowBands(gridFor(l), unit, barH).barTop, gap)
        }
        // Taps remember the generation they were typed in; a layout pass that leaves the letters where they were
        // (switching to 123 and back) keeps it, so a word's taps stay usable, for touch drift too.
        if (lettersMoved()) layoutGeneration++
        // Touch bars sit above the bottom row: one per split half, turning with it, or one across the keyboard.
        fun barZone(right: Float, top: Float) = CursorZone(
            listOf(ZonePoint(gap / 2, top + gap / 2), ZonePoint(gap / 2, top + barH - gap / 2)),
            listOf(ZonePoint(right - gap / 2, top + gap / 2), ZonePoint(right - gap / 2, top + barH - gap / 2)),
            top + gap / 2, top + barH - gap / 2)
        touchBars = when {
            barH <= 0f -> emptyList()
            split -> conts.filter { it.side >= 0 }.map { ct ->
                TouchBar(barZone(ct.u * ct.cols, layoutSolver.rowBands(gridFor(shownLayer), ct.un, barH).barTop), Matrix().apply {
                    setTranslate(0f, -ErgoCurve.raise(.5f) * curve * ct.un); postConcat(ct.base)
                }, ct.side)
            }
            else -> listOf(TouchBar(barZone(innerW, layoutSolver.rowBands(gridFor(shownLayer), s.un, barH).barTop),
                Matrix().apply { setTranslate(innerLeft, y0) }, -1))
        }
        placed = placedBy[0]
        cells.clear()
        // Empty-cell outlines follow each row's actual columns, so they line up with the keys after removals. On 123
        // and #+= the number row stays empty: the layer has its own digits.
        val g = ed
        if (editMode) for (ct in conts) for (r in 0 until n) {
            if (editLayer != Layer.ABC && r < n - 4) continue
            val rowUnit = layoutSolver.rowUnit(g, r, ct.c0, ct.u * ct.cols)
            val bands = layoutSolver.rowBands(g, ct.un, barH)
            for (c in 0 until g.rowColumns(r, ct.c0)) {
                if (g.itemAt(r, ct.c0 + c) != null) continue
                val rect = RectF(c * rowUnit + gap / 2, bands.tops[r] + gap / 2, (c + 1) * rowUnit - gap / 2, bands.tops[r] + bands.heights[r] - gap / 2)
                cells += Cell(r, ct.c0 + c, rect, ct.base, Matrix().also { inv -> ct.base.invert(inv) })
            }
        }
        editConts = conts; editGap = gap

        // The gear sits between the wheels: in the split gap, or in a small gap of its own.
        if (split) {
            wheels[0].rect.set(innerLeft, 0f, innerLeft + slotLeft, stripH)
            wheels[1].rect.set(innerLeft + innerW - slotRight, 0f, innerLeft + innerW, stripH)
            gearRect.set(innerLeft + slotLeft, 0f, innerLeft + innerW - slotRight, stripH)
        } else {
            val mid = innerLeft + innerW / 2
            val g = if (inKeyboard) 18 * d else 0f
            wheels[0].rect.set(innerLeft, 0f, mid - g, stripH)
            wheels[1].rect.set(mid + g, 0f, innerLeft + innerW, stripH)
            gearRect.set(mid - g, 0f, mid + g, stripH)
        }
        if (!inKeyboard) gearRect.setEmpty()
        val toolLeft = if (editMode) 4 * d else innerLeft
        val right = if (editMode) width - 4 * d else innerLeft + innerW
        val toolH = if (editMode) 40 * d else stripH
        undoRect.set(toolLeft, 0f, toolLeft + 44 * d, toolH)
        if (inKeyboard) {
            doneRect.set(right - 52 * d, 0f, right, toolH)
            moreRect.set(doneRect.left - 40 * d, 0f, doneRect.left, toolH)
        } else { doneRect.setEmpty(); moreRect.setEmpty() }
        val controlRight = if (inKeyboard) moreRect.left else right
        hintRect.set(undoRect.right, 0f, controlRight, stripH)
        resetChip.set(undoRect.right, 0f, undoRect.right + (controlRight - undoRect.right) * .45f, toolH)
        mirrorChip.set(resetChip.right, 0f, controlRight, toolH)
        selectionChips.forEach { it.setEmpty() }
        if (!split && selection == Selection.HALF) { clearSelection(); selection = Selection.KEY }
        val modes = if (split) Selection.entries else listOf(Selection.ROW, Selection.KEY)
        val chipW = (right - toolLeft) / modes.size
        for ((i, mode) in modes.withIndex()) selectionChips[mode.ordinal].set(toolLeft + i * chipW, toolH,
            toolLeft + (i + 1) * chipW, stripH - 4 * d)
        handleRect.set(areaLeft, y0 + hc + trayH, areaLeft + areaW, y0 + hc + trayH + handleH)
        // A grip under each split half moves it; while rearranging the halves stay put.
        if (split && !editMode) {
            grips[0].set(innerLeft, handleRect.top, innerLeft + slotLeft, handleRect.bottom)
            grips[1].set(innerLeft + innerW - slotRight, handleRect.top, innerLeft + innerW, handleRect.bottom)
        } else grips.forEach { it.setEmpty() }
        if (editMode) trayRect.set(innerLeft, y0 + hc + 4 * d, innerLeft + innerW, y0 + hc + trayH - 4 * d) else trayRect.setEmpty()
        layoutTray()
        // All cursor regions use the same row-edge geometry, including the shared tilt and fan.
        fun keyEdge(leftHalf: Boolean, inner: Boolean): List<ZonePoint> {
            val edgeRight = leftHalf == inner
            val pt = FloatArray(2)
            fun mapX(pk: Placed): Float {
                pt[0] = if (edgeRight) pk.rect.right else pk.rect.left; pt[1] = pk.rect.centerY()
                pk.matrix.mapPoints(pt); return pt[0]
            }
            val points = ArrayList<ZonePoint>()
            placedBy[shownLayer.ordinal].filter { !split || if (leftHalf) it.item.c < KeyGrid.CENTER else it.item.c > KeyGrid.CENTER }
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
        val outer = if (!editMode && !centerOutside) listOfNotNull(zone(vertical(areaLeft), keyEdge(true, false)),
            zone(keyEdge(false, false), vertical(areaLeft + areaW))) else emptyList()
        val oneHand = !split && p.widthPercent < 100 && p.align != Align.CENTER
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
        cursorZones = if (editMode) emptyList() else if (split) splitCursorZones(settings.cursorControl(), centerZone, outer, 32 * d)
        else when (plan.kind) {
            CursorZoneKind.EMPTY -> emptyZones
            CursorZoneKind.OUTER -> outer
            CursorZoneKind.CENTER -> listOfNotNull(centerZone)
            CursorZoneKind.NONE -> emptyList()
        }
        outerCursorActive = !editMode && (if (split) cursorZones.any { z -> outer.any { it === z } }
            else plan.kind == CursorZoneKind.OUTER || plan.kind == CursorZoneKind.EMPTY)
        val letters = placed.filter { !it.item.isStack && it.item.keys[0].let { k -> k.length == 1 && k[0].isLetter() } }
        letterCentres = letters.associate { pk ->
            val pt = floatArrayOf(pk.rect.centerX(), pk.rect.centerY())
            pk.matrix.mapPoints(pt)
            pk.item.keys[0][0].lowercaseChar() to GestureDecoder.Point(pt[0], pt[1])
        }
        letterWidth = letters.map { it.rect.width() }.sorted().let { if (it.isEmpty()) 1f else it[it.size / 2] }
        // Layout neighbours for corrections without taps: the drawn keys with tilt and curve undone (rects are
        // block-local) and the blocks pushed together, so the split gap and an empty center column don't count.
        if (profile == null) {
            val spots = ArrayList<Pair<Char, KeySpot>>()
            // Blocks before this one, in columns, at this block's own column width: halves of different sizes stay
            // side by side when positions are read in key widths (the model's layout divides by each key's width).
            var columns = 0
            for (ct in conts) {
                val inBlock = placed.filter { it.item.c >= ct.c0 && it.item.c < ct.c0 + ct.cols }
                for (pk in inBlock) {
                    val face = pk.item.keys.firstOrNull()?.singleOrNull()?.takeIf { it.isLetter() } ?: continue
                    spots += face.lowercaseChar() to KeySpot(columns * ct.u + pk.rect.centerX(), pk.rect.centerY(),
                        pk.rect.width() + gap, pk.rect.height() + gap)
                }
                if (inBlock.isNotEmpty()) columns += ct.cols
            }
            listener?.onLetterLayout(spots)
        }
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
        layoutHeight = ceil(total).toInt()
        quickOptionsBounds.set(areaLeft, 0f, areaLeft + areaW, layoutHeight.toFloat())
        builtWidth = width
        builtGeometry = geometrySettings()
        builtLayer = shownLayer
        placed = placedBy[shownLayer.ordinal]
        rebuildDrawingCache()
        accessibilityEvent(View.NO_ID, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE)
        return layoutHeight
    }

    /** The letters layer's placement at the last layout pass: each key's id, rect and transform. */
    private var lettersPlaced = FloatArray(0)
    private val matrixValues = FloatArray(9)

    private fun lettersMoved(): Boolean {
        val keys = placedBy[0]
        val now = FloatArray(keys.size * 14)
        var i = 0
        for (pk in keys) {
            now[i++] = pk.item.id.toFloat()
            now[i++] = pk.rect.left; now[i++] = pk.rect.top; now[i++] = pk.rect.right; now[i++] = pk.rect.bottom
            pk.matrix.getValues(matrixValues)
            matrixValues.copyInto(now, i); i += 9
        }
        if (now.contentEquals(lettersPlaced)) return false
        lettersPlaced = now
        return true
    }

    /** Places [g]'s keys in the given blocks. Shared by layout and the editor's live drop preview. */
    private fun placeKeys(g: KeyGrid, conts: List<Cont>, gap: Float, curve: Float, out: MutableList<Placed>) {
        out.clear()
        for (item in g.items) {
            // A gap holds its cell (the row's other keys keep their size) but only exists while rearranging.
            if (item.r >= g.rows || item.isGap && !editMode) continue
            for (ct in conts) {
                val a = max(item.c, ct.c0)
                val b = min(item.c + item.w, ct.c0 + ct.cols)
                if (b <= a) continue
                val lc = a - ct.c0
                val rowUnit = layoutSolver.rowUnit(g, item.r, ct.c0, ct.u * ct.cols)
                // The touch bar sits between the bottom row and the rows above it.
                val bands = layoutSolver.rowBands(g, ct.un, barH)
                val rowHeight = bands.heights[item.r]
                val rect = RectF(lc * rowUnit + gap / 2, bands.tops[item.r] + gap / 2,
                    (lc + b - a) * rowUnit - gap / 2, bands.tops[item.r] + rowHeight - gap / 2)
                // Resized or nudged in the editor: its edges move from the cell.
                if (item.shaped) item.edges.let { e -> rect.left += e[0] * rowUnit; rect.top += e[1] * rowHeight; rect.right += e[2] * rowUnit; rect.bottom += e[3] * rowHeight }
                val m = Matrix()
                // Ergonomic curve: each column rises to the row's arc, keys upright, so rows curve without any key
                // turning or overlapping. A wide key (the spacebar) spans columns at different heights: on the bottom
                // row it takes the lowest so it never rises into the row above, on the top row the highest, and
                // in between the average. Under a touch bar the bottom row stays level with the half's outer edge.
                if (ct.side >= 0 && curve > 0f) {
                    val span = b - a
                    var low = Float.MAX_VALUE; var high = 0f; var sum = 0f
                    for (i in 0 until span) {
                        val h = ErgoCurve.raise((lc + i + .5f) * rowUnit / ct.u)
                        low = min(low, h); high = max(high, h); sum += h
                    }
                    val raise = when (item.r) { g.rows - 1 -> if (barH > 0f) ErgoCurve.raise(.5f) else low; 0 -> high; else -> sum / span }
                    m.setTranslate(0f, -raise * curve * ct.un)
                } else if (ct.side < 0 && curve > 0f) {
                    // The center column rises with the inner columns on either side of it.
                    m.setTranslate(0f, -ErgoCurve.raise(4.5f) * curve * ct.un)
                }
                m.postConcat(ct.base)
                val bowl = if (ct.side >= 0) ErgoCurve.depth(item.r, g.rows - 3, rect.centerX() / ct.u) else -1f
                out += Placed(item, rect, m, Matrix().also { inv -> m.invert(inv) }, bowl).also { it.column = rowUnit; it.rowHeight = rowHeight }
            }
        }
        val d = settings.int(Prefs.DEPTH) / 100f
        if (!editMode && d != 0f && settings.bool(Prefs.DEPTH_COMPACT)) compact(g, conts, out, gap, d)
    }

    /** How big a key draws at [bowl] (see [ErgoCurve.depth]) for a [depth] from -1 (dome) to 1 (bowl). */
    private fun keyScale(bowl: Float, depth: Float) =
        1f - MAX_SINK * abs(depth) * (if (depth > 0f) bowl else 1f - bowl)

    /**
     * Compact depth: each half's letter keys take their [keyScale] size and close up, so the gaps between them stay
     * even and touches follow the keys as drawn. Each row keeps its center; the rows stack up from the bottom
     * letter row, each as tall as its average key, so the touch bar and the bottom row don't move.
     */
    private fun compact(g: KeyGrid, conts: List<Cont>, out: List<Placed>, gap: Float, depth: Float) {
        val last = g.rows - 2
        for (ct in conts) {
            if (ct.side < 0) continue
            val rows = out.filter { it.bowl >= 0f && it.item.r <= last && it.item.c >= ct.c0 && it.item.c < ct.c0 + ct.cols }
                .groupBy { it.item.r }
            val bands = layoutSolver.rowBands(g, ct.un, barH)
            var bottom = bands.barTop
            for (r in last downTo 0) {
                val row = rows[r]?.sortedBy { it.rect.left }
                if (row == null) { bottom -= bands.heights[r]; continue }
                val scales = row.map { keyScale(it.bowl, depth) }
                val h = bands.heights[r] * scales.average().toFloat()
                val cy = bottom - h / 2
                bottom -= h
                val widths = row.indices.map { row[it].rect.width() * scales[it] }
                val total = widths.sum() + gap * (row.size - 1)
                var x = (row.first().rect.left + row.last().rect.right - total) / 2
                for ((i, pk) in row.withIndex()) {
                    val kh = pk.rect.height() * scales[i]
                    pk.rect.set(x, cy - kh / 2, x + widths[i], cy + kh / 2)
                    x += widths[i] + gap
                    pk.bowl = -1f
                }
            }
        }
    }

    /**
     * Moves [g]'s center keys from the center column to a column outside each half, at [xs] (left, right). Both
     * columns have the same slots, at most a key and a half tall, centred down the letter rows ([top], [height]).
     */
    private fun placeOutside(g: KeyGrid, out: MutableList<Placed>, xs: FloatArray, colW: Float, top: Float, height: Float, gap: Float) {
        out.removeAll { it.item.c == KeyGrid.CENTER }
        val keys = g.items.filter { it.c == KeyGrid.CENTER && it.r < g.rows && !it.isGap }.sortedBy { it.r }
        val cols = CenterOverflow.columns(keys.map { it.side })
        if (cols.slots == 0) return
        val slotH = min(height / cols.slots, 1.5f * unit)
        val y = top + (height - slotH * cols.slots) / 2
        for ((side, list) in listOf(cols.left, cols.right).withIndex()) for ((k, i) in list.withIndex()) {
            val m = Matrix().apply { setTranslate(xs[side], y + k * slotH) }
            out += Placed(keys[i], RectF(gap / 2, gap / 2, colW - gap / 2, slotH - gap / 2), m, Matrix().also { m.invert(it) })
                .also { it.column = colW; it.rowHeight = slotH }
        }
    }


    /** Mini keys in the tray: centred, shrinking to fit when many are waiting. */
    private fun layoutTray() {
        // A gap first, always there to drag out; then the tray, newest on the left (see [trayIndex]).
        val tray = ed.tray
        trayKeys = listOf(GridKey(-1 - tray.size, 0, 0, 1, mutableListOf(Keys.GAP))) +
            tray.indices.reversed().map { i -> GridKey(-1 - i, 0, 0, 1, tray[i].toMutableList()) }
        trayChips.clear()
        if (trayRect.isEmpty) return
        val h = min(trayRect.height() - 8 * density, 36 * density)
        val gapX = 6 * density
        val count = trayKeys.size
        val w = min(h * 1.25f, (trayRect.width() - gapX * (count + 1)) / count).coerceAtLeast(12 * density)
        val top = trayRect.centerY() - h / 2
        var x = trayRect.left + gapX
        trayChips += RectF(x, top, x + w, top + h)
        val rest = trayRect.left + 2 * gapX + w
        x = rest + (trayRect.right - rest - ((count - 1) * w + (count - 2) * gapX)) / 2
        for (i in 1 until count) { trayChips += RectF(x, top, x + w, top + h); x += w + gapX }
    }

    /** The tray entry chip [chip] holds: the gap chip is one past the last entry (see [KeyGrid.trayFaces]). */
    private fun trayIndex(chip: Int) = ed.tray.size - chip

    private fun rebuildDrawingCache() {
        labelScale = settings.int(Prefs.TEXT_SCALE) / 100f * systemTextScale
        iconScale = settings.int(Prefs.TEXT_SCALE) / 100f
        cornerRadius = settings.int(Prefs.CORNER_RADIUS) * density
        tintKeys = settings.bool(Prefs.KEY_TINT)
        showHints = settings.bool(Prefs.SHOW_HINTS)
        previewStyle = settings.int(Prefs.KEY_PREVIEW)
        holdShows = settings.holdChoices()
        hintStyle = settings.hintStyle()
        holdOff = settings.holdOff()
        pickOff = settings.savedHoldOff()
        depth = settings.int(Prefs.DEPTH) / 100f
        keys3d = settings.bool(Prefs.KEYS_3D)
        highContrast = previewPalette == null && settings.theme() == ThemeName.HIGH_CONTRAST
        multipleLanguages = settings.languages().size > 1
        languageLabel = state.language.uppercase()
        faceLabels.clear(); faceHints.clear()
        val sample = InputState().apply { language = state.language }
        for (item in grid.items + gridFor(Layer.NUM).items + gridFor(Layer.SYM).items + trayKeys) for (code in item.keys) {
            val labels = Array(4) { mode ->
                sample.layer = when (mode) { 2 -> Layer.NUM; 3 -> Layer.SYM; else -> Layer.ABC }
                sample.shift = mode == 1
                KeyText.label(code, sample)
            }
            faceLabels[code] = labels
            sample.layer = Layer.ABC; sample.shift = false
            faceHints[code] = if (KeyText.holdId(Layer.ABC, code) in holdOff) null else KeyText.hint(code, sample, hintStyle, holdShows, grid.rows == 5)
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

    /** The face one swipe step away from the shown face, wrapping both ways. */
    private fun stackStep(item: GridKey, step: Int): Int {
        val shown = shownIdx(item)
        val code = item.keys[shown]
        return StackSwipe.next(shown, item.keys.size, step, !KeyText.isLayerKey(code) || KeyText.layerOf(code) == state.layer)
    }

    /** The face a swipe key shows: the one you swiped to, unless another key switched to a different layer. */
    private fun shownIdx(item: GridKey): Int {
        val n = item.keys.size
        val j = Math.floorMod(item.idx, n)
        val k = item.keys[j]
        if (n > 1 && KeyText.isLayerKey(k) && KeyText.layerOf(k) != shownLayer) {
            for (i in item.keys.indices) {
                val code = item.keys[i]
                if (KeyText.isLayerKey(code) && KeyText.layerOf(code) == shownLayer) return i
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
            Selection.entries.filter { !selectionChips[it.ordinal].isEmpty }.forEach { ids += A_SELECTION + it.ordinal }
            ids += A_RESET
            if (displayProfile().split) ids += A_MIRROR
            if (!moreRect.isEmpty) ids += A_SETTINGS
            if (!doneRect.isEmpty) ids += A_DONE
        } else {
            ids += A_WHEEL_LEFT; ids += A_WHEEL_RIGHT
            if (gearRect.width() >= 20 * density) ids += A_GEAR
            cursorZones.indices.forEach { ids += A_CURSOR + it }
            if (switchRect != null && !halvesLocked) ids += A_SWITCH
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
        A_RESET -> resetChip
        A_MIRROR -> mirrorChip
        in A_SELECTION..(A_SELECTION + 2) -> selectionChips[id - A_SELECTION]
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
        A_RESET -> "Reset selection"
        A_MIRROR -> "Mirror ${if (settings.bool(Prefs.MIRROR_HALVES)) "on" else "off"}"
        in A_SELECTION..(A_SELECTION + 2) -> Selection.entries[id - A_SELECTION].name.lowercase() + " selection"
        else -> {
            val item = placed[id].item
            val face = accessibilityValue(id)!!
            if (holdPickLayer != null) "$face, " + pickState(placed[id]) else if (editMode) "$face, row ${item.r + 1}, column ${item.c + 1}. Click to select; editing actions available" else if (item.isStack) "$face, swipe key, ${item.keys.size} options" else if (item.isSpace && multipleLanguages) "$face, ${state.language.uppercase()}, swipe to change language" else face
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
        if (accessibilityManager.isEnabled && accessibilityManager.isTouchExplorationEnabled) {
            // While choosing long-press keys the keys sit [pickReserve] to the right of where they were laid out.
            val shift = pickReserve.toFloat()
            event.offsetLocation(-shift, 0f)
            return onHoverEvent(event).also { event.offsetLocation(shift, 0f) }
        }
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
                        ?: if (!editMode && !halvesLocked && switchRect?.contains(x, y) == true) A_SWITCH
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
        if (holdPickLayer != null) return placed.getOrNull(id)?.let(::togglePickKey) ?: false
        if (!EditorRules.typesFromAccessibility(editMode) && (id in placed.indices || id >= A_POPUP)) {
            val item = placed.getOrNull(id)?.item ?: return false
            if (selection != Selection.KEY) {
                clearSelection()
                if (selection == Selection.ROW) { selectedRow = item.r; rowRegion = KeyGrid.region(item.c) }
                else if (displayProfile().split && item.c != KeyGrid.CENTER) selectedHalf = if (item.c < KeyGrid.CENTER) 0 else 1
                invalidate()
                return true
            }
            editingKey = if (editingKey === item) null else item
            shaping = editingKey
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
            A_RESET -> resetSelection()
            A_MIRROR -> settings.set(Prefs.MIRROR_HALVES, !settings.bool(Prefs.MIRROR_HALVES))
            in A_SELECTION..(A_SELECTION + 2) -> { clearSelection(); selection = Selection.entries[id - A_SELECTION] }
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
                item.idx = stackStep(item, step)
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
                if (id == A_MIRROR) { it.isCheckable = true; it.isChecked = settings.bool(Prefs.MIRROR_HALVES) }
                if (id in A_SELECTION..(A_SELECTION + 2)) it.isSelected = selection.ordinal == id - A_SELECTION
                it.isVisibleToUser = isShown && windowVisibility == VISIBLE
                it.isFocusable = true
                it.isAccessibilityFocused = accessibilityFocus == id
                val bounds = Rect()
                accessibilityRect(id).roundOut(bounds)
                bounds.offset(pickReserve, 0)
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
                if (!editMode && holdPickLayer == null && item != null && (item.isSpace || holdChoicesFor(item.keys[shownIdx(item)]).isNotEmpty())) {
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
                val changed = LayoutEditing.apply(ed, item, LayoutEditing.Action.entries[action - A_EDIT], editingKey)
                if (!changed && LayoutEditing.Action.entries[action - A_EDIT] == LayoutEditing.Action.REMOVE)
                    ed.removalHint(item)?.let { announceForAccessibility(it) }
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

    override fun onDraw(screen: Canvas) {
        // The panel is opaque. Do not record the keyboard or continue wiggle/trail animation loops.
        if (optionsCovered && alpha == 0f) return
        ensureLayout()
        // While the long-press row is open the rest of the keyboard sits blurred behind it (Android 12 and newer;
        // before that it only dims), and the held key and its row are drawn sharp on top.
        val behindRow = popup != null && !editMode
        val blur = behindRow && Build.VERSION.SDK_INT >= 31 && screen.isHardwareAccelerated
        if (blur) screen.drawColor(palette.background)
        val canvas = if (blur && Build.VERSION.SDK_INT >= 31) beginBlur() else screen
        canvas.drawColor(palette.background)
        canvas.save()
        val offset = currentSlideOffset() + pickReserve
        canvas.translate(offset, 0f)
        if (editMode) {
            drawToolbar(canvas)
            drawCells(canvas)
        } else if (holdPickLayer != null) {
            drawPickBar(canvas)
        } else {
            drawWheels(canvas)
            if (gearRect.width() >= 20 * density) drawGear(canvas, gearRect.centerX(), gearRect.centerY(), 20 * density, palette.gear)
        }
        val radius = cornerRadius
        // Under the keys, so it shows through the split and the spaces between keys.
        if (!editMode && cursorGlow) drawWave(canvas)
        drawTouchBars(canvas)
        val now = SystemClock.uptimeMillis()
        if (editMode) drawTray(canvas, radius)
        val dragging = drag
        if (editMode && dragging != null) {
            drawPreview(canvas, dragging, radius, now)
            drawHandle(canvas)
            drawGhost(canvas, dragging, radius)
            canvas.restore()
            postInvalidateOnAnimation()
            return
        }
        for (i in placed.indices) drawPlaced(canvas, placed[i], radius, now)
        if (holdPickLayer != null) drawPickMarks(canvas)
        drawHandle(canvas)
        drag?.let { drawGhost(canvas, it, radius) }
        if (editMode) drawShaping(canvas)
        facePicker?.let { drawFacePicker(canvas, it) }
        sidePicker?.let { drawSidePicker(canvas, it) }
        drawGlide(canvas, now)
        drawKeyPreviews(canvas)
        if (behindRow) {
            canvas.restore()
            if (blur && Build.VERSION.SDK_INT >= 31) endBlur(screen)
            screen.save()
            screen.translate(offset, 0f)
            fill.color = palette.background
            fill.alpha = if (blur) 51 else 90
            screen.drawRect(-offset, 0f, width - offset, height.toFloat(), fill)
            fill.alpha = 255
            popup?.pk?.let { drawPlaced(screen, it, radius, now) }
            drawPopup(screen)
        } else drawPopup(canvas)
        screen.restore()
        if (offset != pickReserve.toFloat() && !optionsCovered) postInvalidateOnAnimation()
        else if (!editMode) {
            switchRect?.let {
                val shown = lockedControlsAlpha(now)
                if (shown > 0f) {
                    if (shown < 1f) screen.saveLayerAlpha(it, (255 * shown).toInt())
                    fill.color = palette.key
                    screen.drawOval(it, fill)
                    drawMoveArrow(screen, it, pointsLeft = switchToLeft)
                    if (shown < 1f) screen.restore()
                }
            }
        }
        if (!editMode) drawCursorPointer(screen, now)
        if (editMode && !reducedMotion) postInvalidateOnAnimation()
    }

    /** One placed key with its tilt, wiggle and depth. */
    // ---- Choosing which keys have a long-press ----

    /** What holding [code] offers on the layer being chosen, whether or not its long-press is turned off. */
    private fun pickChoices(code: String): List<String> {
        val layer = holdPickLayer ?: return emptyList()
        val sample = InputState().also { it.language = state.language; it.layer = layer }
        return KeyText.alternatives(code, sample, holdShows, grid.rows == 5)
    }
    private fun pickCode(pk: Placed) = pk.item.keys[shownIdx(pk.item)]
    private fun pickId(code: String) = KeyText.holdId(holdPickLayer ?: Layer.ABC, code)
    private fun pickState(pk: Placed): String {
        val code = pickCode(pk)
        return when {
            pickChoices(code).isEmpty() -> "no long-press"
            pickId(code) in pickOff -> "long-press off. Double-tap to turn on"
            else -> "long-press on. Double-tap to turn off"
        }
    }

    /** The bar over the keys while choosing: the layer chips on the left, All on, All off and Done on the right. */
    private fun drawPickBar(canvas: Canvas) {
        val d = density
        val sp = resources.displayMetrics.scaledDensity
        val labels = arrayOf("abc", "123", "#+=", "All on", "All off", "Done")
        text.textSize = 13 * sp
        val h = min(stripH - 8 * d, 34 * d)
        val top = (stripH - h) / 2
        var x = -pickReserve + 8 * d
        for (i in 0..2) { val w = text.measureText(labels[i]) + 24 * d; pickChips[i].set(x, top, x + w, top + h); x += w + 4 * d }
        var right = layoutWidth - 8 * d
        for (i in 5 downTo 3) { val w = text.measureText(labels[i]) + 24 * d; pickChips[i].set(right - w, top, right, top + h); right -= w + 6 * d }
        for (i in labels.indices) {
            val r = pickChips[i]
            val active = i < 3 && holdPickLayer == Layer.entries[i]
            if (i < 5) {
                fill.color = if (active) palette.accent else palette.functionKey; fill.alpha = 255
                canvas.drawRoundRect(r, 7 * d, 7 * d, fill)
            }
            drawLabel(canvas, r, labels[i], (if (i == 5) 15 else 13) * sp,
                if (active) palette.onAccent else if (i == 5) palette.smallAccent else palette.text, 255)
        }
    }

    /**
     * Over the keys while choosing: a key with nothing to offer is dimmed; a key turned off is dimmed and struck
     * through; the rest show what holding them offers first. Then a circle before each row that has a long-press:
     * filled when all its keys are on, half filled when some are, an empty ring when none are.
     */
    private fun drawPickMarks(canvas: Canvas) {
        val d = density
        val left = HashMap<Int, FloatArray>()
        val ids = java.util.TreeMap<Int, MutableList<String>>()
        for (pk in placed) {
            val code = pickCode(pk)
            val choices = pickChoices(code)
            val off = choices.isNotEmpty() && pickId(code) in pickOff
            val r = pk.rect
            canvas.save()
            canvas.concat(pk.matrix)
            if (choices.isEmpty() || off) {
                fill.color = palette.background; fill.alpha = if (off) 110 else 150
                canvas.drawRoundRect(r, cornerRadius, cornerRadius, fill)
                fill.alpha = 255
            }
            if (off) {
                stroke.color = palette.text; stroke.alpha = 200; stroke.strokeWidth = 2 * d
                canvas.drawLine(r.left + r.width() * .2f, r.centerY(), r.right - r.width() * .2f, r.centerY(), stroke)
                stroke.alpha = 255
            } else if (choices.isNotEmpty()) {
                text.color = palette.hint
                text.isFakeBoldText = highContrast
                text.textSize = min(r.height() * 0.2f * labelScale, min(r.width() * 0.35f, r.height() * 0.32f))
                canvas.drawText(choices[0], r.right - text.textSize * 0.7f, r.top + text.textSize * 1.1f, text)
            }
            canvas.restore()
            val start = floatArrayOf(r.left, r.centerY())
            pk.matrix.mapPoints(start)
            if (left[pk.item.r]?.let { start[0] < it[0] } != false) left[pk.item.r] = start
            if (choices.isNotEmpty()) ids.getOrPut(pk.item.r) { mutableListOf() }.add(pickId(code))
        }
        pickRows.clear()
        val radius = 9 * d
        for ((row, codes) in ids) {
            val cy = left.getValue(row)[1]
            val cx = -pickReserve / 2f
            pickRows += PickRow(cx, cy, codes)
            val on = codes.count { it !in pickOff }
            stroke.color = if (on == 0) palette.hint else palette.accent; stroke.strokeWidth = 2 * d
            canvas.drawCircle(cx, cy, radius, stroke)
            if (on > 0) {
                fill.color = palette.accent; fill.alpha = 255
                canvas.save()
                if (on < codes.size) canvas.clipRect(cx - radius, cy - radius, cx, cy + radius)
                canvas.drawCircle(cx, cy, radius, fill)
                canvas.restore()
            }
        }
    }

    private fun pickTouch(e: MotionEvent): Boolean {
        val x = e.getX(e.actionIndex) - pickReserve
        val y = e.getY(e.actionIndex)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pickDownX = x; pickDownY = y }
            MotionEvent.ACTION_UP -> if (hypot(x - pickDownX, y - pickDownY) < ViewConfiguration.get(context).scaledTouchSlop) pickTap(x, y)
        }
        return true
    }

    private fun pickTap(x: Float, y: Float) {
        val chip = pickChips.indexOfFirst { it.contains(x, y) }
        when {
            chip in 0..2 -> holdPickLayer = Layer.entries[chip]
            chip == 3 || chip == 4 -> {
                val all = pickRows.flatMap { it.ids }.toSet()
                setPickOff(if (chip == 3) pickOff - all else pickOff + all, if (chip == 3) "All on" else "All off")
            }
            chip == 5 -> onHoldPickDone?.invoke()
            else -> {
                val row = pickRows.firstOrNull { hypot(x - it.cx, y - it.cy) < 20 * density }
                if (row != null) {
                    val allOn = row.ids.none { it in pickOff }
                    setPickOff(if (allOn) pickOff + row.ids else pickOff - row.ids.toSet(), if (allOn) "Row off" else "Row on")
                } else findKey(x, y, strict = true)?.let(::togglePickKey)
            }
        }
    }

    private fun togglePickKey(pk: Placed): Boolean {
        val code = pickCode(pk)
        if (pickChoices(code).isEmpty()) return false
        val id = pickId(code)
        val wasOff = id in pickOff
        setPickOff(if (wasOff) pickOff - id else pickOff + id,
            KeyText.spoken(code, state, null) + if (wasOff) ", long-press on" else ", long-press off")
        return true
    }

    private fun setPickOff(off: Set<String>, said: String) {
        pickOff = off
        settings.setHoldOff(off)
        announceForAccessibility(said)
        invalidate()
    }

    private fun drawPlaced(canvas: Canvas, pk: Placed, radius: Float, now: Long) {
        canvas.save()
        if (gripSide >= 0 && (if (gripSide == 0) pk.item.c < KeyGrid.CENTER else pk.item.c > KeyGrid.CENTER)) canvas.translate(gripDx, 0f)
        canvas.concat(pk.matrix)
        if (editMode && !reducedMotion && pk.item !== shaping && pk.item.r != selectedRow &&
            (selectedHalf == null || KeyGrid.region(pk.item.c) != selectedHalf!! * 2)) canvas.rotate(wiggle(pk.item, now), pk.rect.centerX(), pk.rect.centerY())
        // Depth: a bowl shrinks the keys deep in it, a dome the ones at its rim. A key only shrinks within its own
        // cell, so no key covers another, and it still answers touches in full. (Compact sizes the rects instead.)
        if (!editMode && depth != 0f && pk.bowl >= 0f) {
            val scale = keyScale(pk.bowl, depth)
            canvas.scale(scale, scale, pk.rect.centerX(), pk.rect.centerY())
        }
        drawKey(canvas, pk.item, pk.rect, radius, pk.presses > 0 || (previewPressed && pk.item === placed.firstOrNull()?.item), if (drag?.item === pk.item) 0.3f else 1f)
        drawDropMark(canvas, pk)
        canvas.restore()
    }

    /** Holds the keyboard drawn behind the long-press row (a RenderNode, Android 12 and newer). */
    private var blurNode: Any? = null

    @androidx.annotation.RequiresApi(31)
    private fun beginBlur(): Canvas {
        val node = blurNode as? RenderNode ?: RenderNode("behindLongPressRow").also { blurNode = it }
        node.setPosition(0, 0, width, height)
        val radius = 4 * density
        node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        return node.beginRecording(width, height)
    }

    @androidx.annotation.RequiresApi(31)
    private fun endBlur(screen: Canvas) {
        val node = blurNode as RenderNode
        node.endRecording()
        screen.drawRenderNode(node)
    }

    // ---- Key preview ----

    private val bubblePath = Path()
    private val frameValues = FloatArray(9)
    private val rowPoint = FloatArray(2)

    /** Maps a key's own frame (origin at its centre, before its tilt or curve) to the view, for KeyPreview.place. */
    private fun keyFrame(pk: Placed): FloatArray {
        pk.matrix.getValues(frameValues)
        val a = frameValues[Matrix.MSCALE_X]; val b = frameValues[Matrix.MSKEW_X]; val c = frameValues[Matrix.MTRANS_X]
        val d = frameValues[Matrix.MSKEW_Y]; val e = frameValues[Matrix.MSCALE_Y]; val f = frameValues[Matrix.MTRANS_Y]
        val cx = pk.rect.centerX(); val cy = pk.rect.centerY()
        return floatArrayOf(a, b, a * cx + b * cy + c, d, e, d * cx + e * cy + f)
    }

    /** What holding [code] offers now: Long-press shows, the number row and the keys chosen to have a long-press. */
    private fun holdChoicesFor(code: String): List<String> =
        if (KeyText.holdId(state.layer, code) in holdOff) emptyList() else KeyText.alternatives(code, state, holdShows, grid.rows == 5)

    /** The larger copy of each held key whose letter is not typed yet (KeyPreview.shows), oldest first. */
    private fun drawKeyPreviews(canvas: Canvas) {
        if (previewStyle == KeyPreview.OFF || editMode || glideId != null) return
        for (t in trackers.values) {
            if (t.kind != K_KEY) continue
            val pk = t.pk ?: continue
            val code = pk.item.keys[shownIdx(pk.item)]
            if (KeyPreview.shows(previewStyle, t.preview, t.consumed, t.pressedText != null, glideId != null, popup?.owner === t, editMode, code))
                // With Type on press the letter is already typed: show it as typed, before Shift let go.
                drawKeyPreview(canvas, pk, t.pressedText ?: faceLabel(code))
        }
    }

    private fun drawKeyPreview(canvas: Canvas, pk: Placed, label: String) {
        val w = pk.rect.width(); val h = pk.rect.height()
        val shape = KeyPreview.shape(previewStyle, w, h, 12 * density)
        val slid = KeyPreview.place(keyFrame(pk), shape.left, shape.top, shape.right, shape.bottom, width.toFloat(), height.toFloat(),
            4 * density, -shape.slide, shape.slide)
        val left = shape.left + slid[0]; val top = shape.top + slid[1]; val right = shape.right + slid[0]; val bottom = shape.bottom + slid[1]
        canvas.save()
        canvas.concat(pk.matrix)
        canvas.translate(pk.rect.centerX(), pk.rect.centerY())
        bubblePath.reset()
        val radius = bubbleRadius()
        if (previewStyle == KeyPreview.BALLOON) balloonPath(bubblePath, left, top, right, bottom, w, h, radius)
        else bubblePath.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
        paintBubble(canvas, bubblePath)
        faceRect.set(left, shape.labelTop + slid[1], right, shape.labelBottom + slid[1])
        drawLabel(canvas, faceRect, label, min(h * 0.42f, 22 * density) * labelScale * shape.letterScale, palette.text, 255)
        canvas.restore()
    }

    private fun bubbleRadius() = max(cornerRadius * 1.25f, 6 * density)

    /** A balloon: its top (left..right, top..bottom) joined by a curved neck to a stem as wide as the key, down to its bottom. */
    private fun balloonPath(p: Path, left: Float, top: Float, right: Float, bottom: Float, w: Float, h: Float, radius: Float) {
        val r = min(radius, min(right - left, bottom - top) / 2)
        val stemR = min(cornerRadius, w / 2)
        val sx0 = -w / 2; val sx1 = w / 2; val sb = h / 2
        val neck = max(0f, -h / 2 - bottom)
        p.moveTo(left + r, top)
        p.lineTo(right - r, top)
        p.arcTo(right - 2 * r, top, right, top + 2 * r, -90f, 90f, false)
        p.lineTo(right, bottom)
        p.cubicTo(right, bottom + neck / 2, sx1, bottom + neck / 2, sx1, bottom + neck)
        p.lineTo(sx1, sb - stemR)
        if (stemR > 0f) p.arcTo(sx1 - 2 * stemR, sb - 2 * stemR, sx1, sb, 0f, 90f, false)
        p.lineTo(sx0 + stemR, sb)
        if (stemR > 0f) p.arcTo(sx0, sb - 2 * stemR, sx0 + 2 * stemR, sb, 90f, 90f, false)
        p.lineTo(sx0, bottom + neck)
        p.cubicTo(sx0, bottom + neck / 2, left, bottom + neck / 2, left, bottom)
        p.lineTo(left, top + r)
        p.arcTo(left, top, left + 2 * r, top + 2 * r, 180f, 90f, false)
        p.close()
    }

    /** The key colour with a soft shadow and a thin outline in the text colour, so it stands out on light themes too. */
    private fun paintBubble(canvas: Canvas, p: Path) {
        if (!highContrast) {
            canvas.save()
            canvas.translate(0f, 2 * density)
            fill.color = Color.BLACK
            fill.alpha = if (Color.luminance(palette.background) < .5f) 102 else 41
            canvas.drawPath(p, fill)
            canvas.restore()
        }
        fill.color = palette.key
        fill.alpha = 255
        canvas.drawPath(p, fill)
        stroke.color = palette.text
        stroke.alpha = if (highContrast) 255 else 89
        stroke.strokeWidth = (if (highContrast) 2f else 1.5f) * density
        canvas.drawPath(p, stroke)
        stroke.alpha = 255
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

    private fun drawKey(canvas: Canvas, item: GridKey, cell: RectF, radius: Float, pressed: Boolean, alpha: Float) {
        if (item.isGap) {
            dash.color = palette.accent; dash.alpha = (220 * alpha).toInt(); dash.strokeWidth = 1.5f * density
            outlineRect.set(cell); outlineRect.inset(density, density)
            canvas.drawRoundRect(outlineRect, radius, radius, dash)
            drawLabel(canvas, cell, "gap", min(12 * resources.displayMetrics.scaledDensity, cell.height() * 0.3f), palette.hint, (255 * alpha).toInt())
            return
        }
        // 3D: the top sits an edge's height above the cell's bottom; pressed, it sinks onto the edge and covers it.
        val edge = if (keys3d) min(4 * density, cell.height() * .1f) else 0f
        val rect = if (edge == 0f) cell else keyTop.apply {
            set(cell); if (pressed) top += edge else bottom -= edge
        }
        val keyAlpha = if (highContrast) 1f else alpha
        val face = shownIdx(item)
        val code = item.keys[face]
        val letter = if (code.length == 1) code[0].lowercaseChar().code else -1
        val chord = letter in chordLit.indices && chordLit[letter]
        val weight = if (letter in letterWeights.indices) letterWeights[letter] else 0.0
        val shiftKey = if (grid.hasShift()) Keys.SHIFT else Keys.ABC
        val lock = code == shiftKey && state.layer == Layer.ABC && state.capsLock
        val oneShot = code == shiftKey && state.layer == Layer.ABC && state.shift && !state.capsLock
        val accentBg = code == Keys.ENTER || lock
        fill.color = when {
            code == Keys.INSERT && insertStripHeight > 0 -> palette.pressed
            highContrast -> palette.key
            pressed -> palette.pressed
            accentBg -> palette.accent
            KeyText.isLayerKey(code) || code == Keys.BACKSPACE || code == Keys.INSERT || code == Keys.SHIFT -> palette.functionKey
            else -> palette.key
        }
        fill.alpha = (255 * keyAlpha).toInt()
        if (edge > 0f && !pressed) {
            // The edge: the key's own color darkened, or the outline color on outlined (high contrast) themes.
            val c = fill.color
            fill.color = if (palette.keyOutline ushr 24 != 0) palette.keyOutline
                else Color.rgb(Color.red(c) * 6 / 10, Color.green(c) * 6 / 10, Color.blue(c) * 6 / 10)
            fill.alpha = (255 * keyAlpha).toInt()
            canvas.drawRoundRect(cell, radius, radius, fill)
            fill.color = c; fill.alpha = (255 * keyAlpha).toInt()
        }
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
        if (palette.keyOutline ushr 24 != 0) {
            stroke.color = palette.keyOutline; stroke.strokeWidth = 2 * density
            outlineRect.set(rect); outlineRect.inset(density, density)
            canvas.drawRoundRect(outlineRect, radius, radius, stroke)
        }
        if (code == Keys.INSERT && insertStripHeight > 0) {
            stroke.color = palette.accent; stroke.strokeWidth = 2 * density
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
        val dx = sw?.dx ?: 0f
        drawFace(canvas, code, rect, dx, fg, keyAlpha)
        if (item.isStack && dx != 0f) {
            drawFace(canvas, item.keys[stackStep(item, 1)], rect, rect.width() + dx, fg, keyAlpha)
            drawFace(canvas, item.keys[stackStep(item, -1)], rect, -rect.width() + dx, fg, keyAlpha)
        }
        canvas.restore()

        if (item.isStack) {
            // A row of dots along the bottom, one per face, like a page indicator: the swipe runs sideways.
            val n = item.keys.size
            val r = 1.5f * density
            val step = 5 * density
            val left = rect.centerX() - (n - 1) * step / 2
            for (i in 0 until n) {
                fill.color = fg
                fill.alpha = ((if (i == face) 230 else 80) * alpha).toInt()
                canvas.drawCircle(left + i * step, rect.bottom - 5 * density, r, fill)
            }
            fill.alpha = 255
        }
        if (!item.isStack && hintStyle != HintStyle.OFF && shownLayer == Layer.ABC && holdPickLayer == null) faceHints[code]?.let {
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

    private fun drawFace(canvas: Canvas, code: String, rect: RectF, offsetX: Float, color: Int, alpha: Float) {
        val r = faceRect
        r.set(rect); r.offset(offsetX, 0f)
        val scale = labelScale
        val icon = min(r.width(), r.height()) * 0.42f * iconScale
        val a = (255 * alpha).toInt()
        when (code) {
            Keys.INSERT -> {
                val cx = r.centerX(); val cy = r.centerY(); val radius = icon * .65f
                stroke.color = color; stroke.alpha = a; stroke.strokeWidth = 1.75f * density
                canvas.drawCircle(cx, cy, radius, stroke)
                canvas.drawPoint(cx - radius * .32f, cy - radius * .22f, stroke)
                canvas.drawPoint(cx + radius * .32f, cy - radius * .22f, stroke)
                canvas.drawArc(cx - radius * .42f, cy - radius * .25f, cx + radius * .42f, cy + radius * .55f, 20f, 140f, false, stroke)
            }
            Keys.BACKSPACE -> drawDelete(canvas, r, icon, color, a)
            Keys.SHIFT -> drawLabel(canvas, r, if (state.capsLock) "\u21EA" else "\u21E7", min(r.height() * 0.42f, 22 * density) * scale, color, a)
            Keys.ENTER -> enterLabel?.let { drawLabel(canvas, r, it, r.height() * 0.3f * scale, color, a) } ?: drawReturn(canvas, r, icon, color, a)
            Keys.SPACE -> if (multipleLanguages && !editMode) {
                drawLabel(canvas, r, languageLabel, min(12 * density, r.height() * 0.25f) * scale, palette.hint, a)
            }
            else -> {
                val label = faceLabel(code)
                val size = min(r.height() * 0.42f, 22 * density) * scale
                drawLabel(canvas, r, label, if (label.length > 1) size * 0.72f else size, color, a)
            }
        }
    }

    /** The text a key's face shows now. */
    private fun faceLabel(code: String): String =
        // With Shift placed, the layer key on its own layer shows where a tap goes (see tapLayerKey).
        if (grid.hasShift() && KeyText.isLayerKey(code) && KeyText.layerOf(code) == shownLayer) (if (shownLayer == Layer.ABC) "123" else "ABC")
        else if (code == Keys.ABC) KeyText.label(code, state) else if (KeyText.isLiteral(code)) code.substring(1) else
            faceLabels[code]?.get(if (shownLayer == Layer.NUM) 2 else if (shownLayer == Layer.SYM) 3 else if (state.upper) 1 else 0) ?: code

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
        if (drag != null && drag?.target?.type == T_FULL) {
            // Over a full row the strip says so instead of offering the trash; the key will snap back.
            drawLabel(canvas, hintRect, "Row is full", 14 * resources.displayMetrics.scaledDensity, palette.smallAccent, 255)
            return
        }
        if (drag != null) {
            val active = drag?.target?.type == T_REMOVE
            // Deleting a tray key is final, so that target fills with ink instead of the accent.
            fill.color = if (!active) palette.key else if ((drag?.tray ?: -1) >= 0) palette.text else palette.accent
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
        for (mode in Selection.entries) {
            val r = selectionChips[mode.ordinal]
            if (r.isEmpty) continue
            val active = selection == mode
            fill.color = if (active) palette.accent else palette.functionKey; fill.alpha = 255
            canvas.drawRoundRect(r, 7 * density, 7 * density, fill)
            drawLabel(canvas, r, mode.name.lowercase().replaceFirstChar { it.uppercaseChar() }, 13 * sp,
                if (active) palette.onAccent else palette.text, 255)
        }
        val selected = shaping != null || selectedHalf != null || selectedRow != null
        drawLabel(canvas, resetChip, "Reset", 13 * sp, palette.smallAccent, if (selected) 255 else 70)
        if (displayProfile().split) {
            val on = settings.bool(Prefs.MIRROR_HALVES)
            val r = RectF(mirrorChip.right - 30 * density, mirrorChip.centerY() - 8 * density,
                mirrorChip.right - 4 * density, mirrorChip.centerY() + 8 * density)
            fill.color = if (on) palette.accent else palette.hint; fill.alpha = 255
            canvas.drawRoundRect(r, 8 * density, 8 * density, fill)
            fill.color = if (on) palette.onAccent else palette.background
            canvas.drawCircle(if (on) r.right - 8 * density else r.left + 8 * density, r.centerY(), 6 * density, fill)
            drawLabel(canvas, RectF(mirrorChip.left, mirrorChip.top, r.left, mirrorChip.bottom), "Mirror", 12 * sp, palette.text, 255)
        }
        if (!doneRect.isEmpty) {
            drawGear(canvas, moreRect.centerX(), moreRect.centerY(), s, palette.gear)
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

    /** Each touch bar: a faint track with a short line in the middle, moving with its half while it is dragged. */
    private fun drawTouchBars(canvas: Canvas) {
        for (bar in touchBars) {
            canvas.save()
            if (gripSide >= 0 && bar.side == gripSide) canvas.translate(gripDx, 0f)
            canvas.concat(bar.toScreen)
            val r = bar.rect
            fill.color = palette.key
            fill.alpha = 110
            canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
            // Themes that outline their keys (high contrast) outline the bar too, fainter, or it's invisible.
            if (palette.keyOutline ushr 24 != 0) {
                stroke.color = palette.keyOutline; stroke.alpha = (palette.keyOutline ushr 24) / 2
                stroke.strokeWidth = 1.5f * density
                outlineRect.set(r); outlineRect.inset(density, density)
                canvas.drawRoundRect(outlineRect, outlineRect.height() / 2, outlineRect.height() / 2, stroke)
                stroke.alpha = 255
            }
            val w = min(24 * density, r.width() / 3)
            val h = 3 * density
            fill.color = palette.hint
            canvas.drawRoundRect(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2, h / 2, h / 2, fill)
            canvas.restore()
        }
    }

    /** Whether a held or slid space bar moves the cursor (Cursor control: Space bar), never while rearranging. */
    private fun spaceCursor() = !editMode && settings.cursorControl() == CursorControl.SPACE_BAR

    /**
     * The space bar held (as long as a long-press) or slid along: its touch becomes a cursor touch on a bar over that space key,
     * from where the finger is, and no space is typed. Left and right style: the left half's space bar moves left.
     */
    private fun spaceToCursor(id: Int, t: Tracker, x: Float, y: Float) {
        if (trackers[id] !== t || t.consumed || cursorTracker != null) return
        val pk = t.pk ?: return
        stopTimers(t)
        t.spaceHold = null
        t.consumed = true
        t.pk = null
        trackers.remove(id)
        val r = pk.rect
        val zone = CursorZone(listOf(ZonePoint(r.left, r.top), ZonePoint(r.left, r.bottom)),
            listOf(ZonePoint(r.right, r.top), ZonePoint(r.right, r.bottom)), r.top, r.bottom)
        val side = if (!displayProfile().split) -1 else if (pk.item.c < KeyGrid.CENTER) 0 else 1
        haptic(HapticFeedbackConstants.LONG_PRESS)
        startCursor(id, x, y, SystemClock.uptimeMillis(), null, TouchBar(zone, Matrix(pk.matrix), side, onSpace = true))
    }

    /** Draw last so keys cannot cover the disk. Paints, gradient and matrix survive every frame. */
    private fun drawCursorPointer(canvas: Canvas, now: Long) {
        val p = cursorPointer ?: return
        if (cursorTracker == null && !p.released) return
        val alpha = p.alpha(now, reducedMotion)
        if (alpha <= 0f) return
        // On a touch bar the disk lives in the bar's coordinates, so it turns with a tilted half.
        val bar = pointerBar
        if (bar != null) { canvas.save(); canvas.concat(bar.toScreen) }
        // On a space bar the joystick fades in over it: the key's face gives way to a plain track.
        if (bar?.onSpace == true) {
            fill.color = palette.key; fill.alpha = (235 * alpha).toInt()
            canvas.drawRoundRect(bar.rect, cornerRadius, cornerRadius, fill)
            fill.color = palette.hint; fill.alpha = (255 * alpha).toInt()
            val w = min(24 * density, bar.rect.width() / 3); val h = 3 * density
            canvas.drawRoundRect(bar.rect.centerX() - w / 2, bar.rect.centerY() - h / 2, bar.rect.centerX() + w / 2, bar.rect.centerY() + h / 2, h / 2, h / 2, fill)
            fill.alpha = 255
        }
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
        if (bar != null) canvas.restore()
    }

    private fun invalidatePointer(p: CursorPointer) {
        val inset = p.radius + 6 * density
        val r = pointerDirty
        r.set(p.minX - inset, p.y - inset, p.maxX + inset, p.y + inset)
        pointerBar?.toScreen?.mapRect(r)
        invalidate(kotlin.math.floor(r.left).toInt(), kotlin.math.floor(r.top).toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())
    }

    private fun drawHandle(canvas: Canvas) {
        val split = !grips[0].isEmpty
        // A split keyboard has a longer grip under each half, following its half (in the accent color) while dragged.
        val w = (if (split) 72 else 44) * density
        val h = (if (split) 5 else 4) * density
        val cy = handleRect.centerY()
        if (!split) {
            fill.color = palette.hint
            canvas.drawRoundRect(handleRect.centerX() - w / 2, cy - h / 2, handleRect.centerX() + w / 2, cy + h / 2, h / 2, h / 2, fill)
            return
        }
        val shown = if (editMode) 1f else lockedControlsAlpha(SystemClock.uptimeMillis())
        if (shown <= 0f) return
        for (side in 0..1) {
            val cx = grips[side].centerX() + if (side == gripSide) gripDx else 0f
            fill.color = if (side == gripSide) palette.accent else palette.hint
            fill.alpha = (Color.alpha(fill.color) * shown).toInt()
            canvas.drawRoundRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, h / 2, h / 2, fill)
        }
        fill.alpha = 255
    }

    /** How much of the grips and one-hand arrow shows: they fade over 200 ms (at once with reduced motion) as the halves lock or unlock. */
    private fun lockedControlsAlpha(now: Long): Float {
        val t = if (reducedMotion) 1f else ((now - lockChangedAt) / 200f).coerceIn(0f, 1f)
        if (t < 1f) postInvalidateOnAnimation()
        return if (halvesLocked) 1f - t else t
    }

    private fun drawCells(canvas: Canvas) {
        val radius = cornerRadius
        dash.strokeWidth = density
        // While a drop is previewed the keys make room themselves, so the old outlines fade away.
        val fade = if (drag == null) 1f else if (previewSig == null) previewProgress() else 1f - previewProgress()
        if (fade <= 0.01f) return
        for (i in cells.indices) {
            val cell = cells[i]
            dash.color = palette.hint
            dash.alpha = (120 * fade).toInt()
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

    /**
     * The long-press row over [pk]: its cells ([row], slid by [dx], [dy] in the key's frame) and their [rects] in the
     * view for accessibility. Like iOS the first choice is picked at once; the finger moves the pick along the row.
     */
    private class AccentPopup(val choices: List<String>, val rects: List<RectF>, val owner: Tracker?, val pk: Placed,
                              val row: KeyPreview.Row, val dx: Float, val dy: Float, val balloon: Boolean) {
        var selected = 0
        val centres = FloatArray(row.centres.size) { row.centres[it] + dx }
    }
    private var popup: AccentPopup? = null

    private fun openPopup(pk: Placed, owner: Tracker?): Boolean {
        val choices = owner?.pressChoices ?: holdChoicesFor(pk.item.keys[shownIdx(pk.item)])
        if (choices.isEmpty()) return false
        dismissPopup()
        val frame = keyFrame(pk)
        val balloon = previewStyle == KeyPreview.BALLOON
        // The first choice sits over the key and the row runs away from the nearer edge, as on iOS.
        val row = KeyPreview.row(choices.size, pk.rect.width(), pk.rect.height(), width.toFloat(), density, balloon, rightward = frame[2] < width / 2f)
        val slid = KeyPreview.place(frame, row.left, row.top, row.right, row.bottom, width.toFloat(), height.toFloat(), 4 * density,
            row.slideMin, row.slideMax)
        val cx = pk.rect.centerX() + slid[0]; val cy = pk.rect.centerY() + row.cellY + slid[1]
        val rects = row.centres.map { c ->
            RectF(cx + c - row.cellWidth / 2, cy - row.cellHeight / 2, cx + c + row.cellWidth / 2, cy + row.cellHeight / 2).also { pk.matrix.mapRect(it) }
        }
        clearAccessibilityFocus()
        popup = AccentPopup(choices, rects, owner, pk, row, slid[0], slid[1], balloon)
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
        val pk = p.pk
        val row = p.row
        canvas.save()
        canvas.concat(pk.matrix)
        canvas.translate(pk.rect.centerX(), pk.rect.centerY())
        bubblePath.reset()
        val left = row.left + p.dx; val top = row.top + p.dy; val right = row.right + p.dx; val bottom = row.bottom + p.dy
        val radius = bubbleRadius()
        if (p.balloon) balloonPath(bubblePath, left, top, right, bottom, pk.rect.width(), pk.rect.height(), radius)
        else bubblePath.addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
        paintBubble(canvas, bubblePath)
        val cy = row.cellY + p.dy
        val cellRadius = min(cornerRadius, row.cellHeight / 2)
        for (i in p.choices.indices) {
            val cx = p.centres[i]
            faceRect.set(cx - row.cellWidth / 2, cy - row.cellHeight / 2, cx + row.cellWidth / 2, cy + row.cellHeight / 2)
            val picked = i == p.selected
            if (picked) {
                fill.color = palette.accent
                fill.alpha = 255
                outlineRect.set(faceRect)
                outlineRect.inset(density, 0f)
                canvas.drawRoundRect(outlineRect, cellRadius, cellRadius, fill)
            }
            drawLabel(canvas, faceRect, p.choices[i], min(22 * density * labelScale, row.cellHeight * 0.5f), if (picked) palette.onAccent else palette.text, 255)
        }
        canvas.restore()
    }

    /** The row's cell nearest the finger at [x], [y] (KeyPreview.nearestCell), or -1 well below the key. */
    private fun popupCellAt(p: AccentPopup, x: Float, y: Float): Int {
        rowPoint[0] = x; rowPoint[1] = y
        p.pk.inverse.mapPoints(rowPoint)
        return KeyPreview.nearestCell(p.centres, rowPoint[0] - p.pk.rect.centerX(), rowPoint[1] - p.pk.rect.centerY(), p.pk.rect.height())
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
        /** Shows the key preview until the next finger lands (KeyPreview.shows). */
        var preview = false
        var moved = false
        var x0 = 0f
        var y0 = 0f
        var tapX = 0f
        var tapY = 0f
        var lastX = 0f
        /** The split half this handle touch moves (0 left, 1 right), or -1 for the whole keyboard's handle. */
        var grip = -1
        val joystick = CursorJoystick()
        /** Set on a Left and right touch bar, which moves the cursor its own way instead of by [joystick]. */
        var barRepeat: CursorRepeat? = null
        var cursorZone: CursorZone? = null
        var bar: TouchBar? = null
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
        /** The space bar held long enough to move the cursor (Cursor control: Space bar). */
        var spaceHold: Runnable? = null
        val repeat: Runnable = object : Runnable {
            override fun run() {
                emitKey(Keys.BACKSPACE)
                timers.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
    }

    private class Swipe(val item: GridKey, val downAt: Long) { var dx = 0f; var stepped = false }

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
                moveCursor(t, t.barRepeat?.advance(now) ?: t.joystick.advance(now), now)
                if (cursorTracker === t) {
                    val waveMoving = cursorGlow && stepWave(t, now)
                    cursorPointer?.let { invalidatePointer(it); it.follow(pointerY(t)) }
                    val pointerMoving = cursorPointer?.step(t.joystick.push.toFloat(),
                        (now - pointerTime) / 1000f, reducedMotion) == true
                    again = waveMoving || pointerMoving || t.joystick.speed != 0.0 || t.barRepeat != null
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

    /** The finger's height for the cursor disk, in its touch bar's coordinates when it has one. */
    private fun pointerY(t: Tracker) = t.bar?.local(t.fx, t.fy)?.get(1) ?: t.fy

    private fun moveCursor(t: Tracker, steps: Int, now: Long) {
        if (steps == 0) return
        invalidatePressAccents()
        listener?.onCursor(steps, select = t.select, words = t.joystick.byWord)
        if (t.lastCursorHaptic == Long.MIN_VALUE || now - t.lastCursorHaptic >= 80) {
            haptic(HapticFeedbackConstants.CLOCK_TICK)
            t.lastCursorHaptic = now
        }
    }

    fun cancelTouches() {
        removeCallbacks(cursorFrame)
        gripSide = -1; gripDx = 0f
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
        if (holdPickLayer != null) return pickTouch(e)
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

    private var lastTypedAt = 0L

    private fun pointerDown(id: Int, x: Float, y: Float, time: Long) {
        if (glideId != null || isSliding()) return
        // A key resized or moved over a control (a space bar made taller over the grip band, a top-row key raised into
        // the suggestion bar, backspace into the touch bar's band) takes the touches it covers. Only the gear stays on top.
        val shapedKey = findKey(x, y, strict = true)?.takeIf { it.item.shaped && !gearRect.contains(x, y) }
        val zone = if (shapedKey == null) cursorZoneAt(x, y) else null
        val bar = if (zone == null && shapedKey == null) touchBars.firstOrNull { it.contains(x, y) } else null
        // Mid-typing a wheel is like the gap: a hand that wandered up, so the tap types the nearest letter or space.
        val slipKey = if (shapedKey == null && zone == null && bar == null && !gearRect.contains(x, y) && FarTaps.midTyping(time - lastTypedAt) &&
            wheels.any { it.rect.contains(x, y) }) findKey(x, y, far = true, strip = true)?.takeIf { FarTaps.counts(it.item) } else null
        val onControl = shapedKey == null && (zone != null || bar != null || gearRect.contains(x, y) || handleRect.contains(x, y) ||
            wheels.any { it.rect.contains(x, y) } || leftSpace?.contains(x, y) == true || rightSpace?.contains(x, y) == true)
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
        // A new finger types the held letters (rollover below), so, as on iOS, their previews end.
        for (other in trackers.values) if (other.preview) { other.preview = false; invalidate() }
        // Chord members defer rollover; every other touch keeps the existing path.
        for ((otherId, t) in trackers.toMap()) {
            val code = t.pk?.item?.let { it.keys[shownIdx(it)] }
            if (!EditorRules.pendingCharacter(t.kind == K_KEY, t.kind == K_STACK, t.moved, t.consumed,
                    code != null && KeyText.isChar(code)) || chordTouches.contains(otherId)) continue
            stopTimers(t); t.consumed = true
            if (t.kind == K_STACK) emitKey(code!!, t.tapX, t.tapY) else commit(t)
        }
        if (shapedKey != null) {
            pressKey(id, x, y, time, chordKey)
            return
        }
        if (gearRect.contains(x, y)) {
            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            trackers[id] = Tracker(K_GEAR, null)
            return
        }
        wheels.firstOrNull { slipKey == null && it.rect.contains(x, y) && words(it).isNotEmpty() }?.let {
            trackers[id] = Tracker(K_WHEEL, null).also { t -> t.wheel = it; t.x0 = x; t.lastX = x; timers.postDelayed(t.longPress, settings.int(Prefs.LONG_PRESS_MS).toLong()) }
            return
        }
        // The bar and the padding under it, so a grip is easy to catch. Locked grips take nothing.
        if (y >= handleRect.top && x >= handleRect.left && x <= handleRect.right) {
            if (halvesLocked && !grips[0].isEmpty) return
            trackers[id] = Tracker(K_HANDLE, null).also {
                it.x0 = x; it.lastX = x
                if (!grips[0].isEmpty) it.grip = if (x < (grips[0].right + grips[1].left) / 2) 0 else 1
            }
            return
        }
        if (switchRect?.contains(x, y) == true) {
            if (!halvesLocked) moveKeyboardToward(x, y)
            return
        }
        val hit = findKey(x, y, strict = true)
        if (hit == null && (zone != null || bar != null)) {
            startCursor(id, x, y, time, zone, bar)
            return
        }
        if (hit == null && (leftSpace?.contains(x, y) == true || rightSpace?.contains(x, y) == true)) return
        // With no cursor in it the gap has no use of its own, so a hand that wandered in still types: a letter or a space.
        if (hit == null && centerZone?.contains(x, y) == true && findKey(x, y, far = true)?.item?.let(FarTaps::counts) != true) return
        pressKey(id, x, y, time, chordKey, slipKey)
    }

    /** A touch that moves the cursor: on a touch bar ([bar], in its own coordinates), a held space bar, or a [zone]. */
    private fun startCursor(id: Int, x: Float, y: Float, time: Long, zone: CursorZone?, bar: TouchBar?) {
            if (cursorTracker != null) return
            trackers[id] = Tracker(K_CURSOR, null).also {
                cursorTracker = it
                it.x0 = x; it.y0 = y; it.fx = x; it.fy = y
                // A touch bar works in its own coordinates (along the bar), the glow in the keyboard's.
                val area = bar?.zone ?: zone!!
                val (lx, ly) = bar?.local(x, y)?.let { p -> p[0] to p[1] } ?: (x to y)
                it.cursorZone = bar?.screenZone ?: zone
                it.bar = bar
                it.waveX = x; it.waveTime = SystemClock.uptimeMillis()
                it.joystick.reach((lx - CursorZone.edgeAt(area.left, ly)) / density, (CursorZone.edgeAt(area.right, ly) - lx) / density)
                val arrows = bar != null && settings.touchBarStyle() == TouchBarStyle.LEFT_RIGHT
                // On Left and right bars quick taps each move a character, so a double tap can't mean "select".
                it.select = !arrows && gapPresses.isSelection(time, x / density, y / density)
                // The user's sensitivity both bends the curve (small pushes move more or less) and scales its speed.
                val sensitivity = settings.int(Prefs.CURSOR_SENSITIVITY) / 100.0
                it.joystick.sensitivity = sensitivity
                it.joystick.speedScale = sensitivity
                if (it.select) {
                    // Selecting is 15% less sensitive, but the zone's edge still reaches full speed and the glow the end.
                    it.joystick.sensitivity = 0.85 * sensitivity
                    listener?.onSelectMode(true)
                    haptic(HapticFeedbackConstants.LONG_PRESS)
                    it.lastCursorHaptic = SystemClock.uptimeMillis()
                }
                val now = SystemClock.uptimeMillis()
                pointerSelecting = it.select
                cursorPointer = CursorPointer(area, lx, ly, (if (it.select) 11 else 10) * density, now)
                pointerBar = bar
                pointerTime = now
                if (arrows && bar != null) {
                    val direction = when (bar.side) { 0 -> -1; 1 -> 1; else -> if (lx < bar.rect.centerX()) -1 else 1 }
                    it.barRepeat = CursorRepeat(direction, now, sensitivity)
                    moveCursor(it, direction, now)
                }
            }
            removeCallbacks(cursorFrame)
            // A repeating bar needs its frames even with reduced motion: they move the cursor.
            if (!reducedMotion || trackers[id]?.barRepeat != null) postOnAnimation(cursorFrame)
            invalidate()
    }

    /** A key pressed (the rest of [pointerDown]). */
    private fun pressKey(id: Int, x: Float, y: Float, time: Long, chordKey: Placed?, slipKey: Placed? = null) {
        // Only here do far taps count, after the controls have had their touches; pointerDown's guard saw no key for one.
        val pk = chordKey ?: findKey(x, y) ?: (slipKey ?: findKey(x, y, far = true))?.takeIf { acceptKeyTap(it.item, time) } ?: return
        if (FarTaps.counts(pk.item)) lastTypedAt = time
        if (chordKey == null) haptic(HapticFeedbackConstants.KEYBOARD_TAP)
        if (pk.item.isStack) {
            trackers[id] = Tracker(K_STACK, pk).also { it.x0 = x; it.y0 = y; it.tapX = x; it.tapY = y; pressLetter(it); timers.postDelayed(it.longPress, settings.int(Prefs.LONG_PRESS_MS).toLong()) }
            swipe = Swipe(pk.item, time)
            invalidate()
            return
        }
        val t = Tracker(K_KEY, pk)
        t.tapX = x; t.tapY = y
        t.x0 = x; t.y0 = y; t.glideWidth = pk.rect.width()
        t.glideEligible = slipKey == null && !settings.bool(Prefs.TYPE_ON_PRESS) && glideAllowed && settings.bool(Prefs.SWIPE_TYPING) && state.layer == Layer.ABC &&
            pk.item.keys[0].let { it.length == 1 && it[0].isLetter() }
        if (t.glideEligible) {
            t.glideX = x; t.glideY = y
            t.points.add(GestureDecoder.Point(x, y, time))
        }
        trackers[id] = t
        t.preview = true
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
            // Space bar cursor (Cursor control): held as long as a long-press, the space bar moves the cursor instead.
            if (pk.item.isSpace && spaceCursor()) {
                t.spaceHold = Runnable { spaceToCursor(id, t, t.tapX, t.tapY) }
                timers.postDelayed(t.spaceHold!!, settings.int(Prefs.LONG_PRESS_MS).toLong())
            } else timers.postDelayed(t.longPress, settings.int(Prefs.LONG_PRESS_MS).toLong())
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
        // The space bar slid along by more than a letter key's width moves the cursor at once (Cursor control: Space
        // bar). A letter's width, not the bar's: a narrowed space bar would make finger drift start the cursor.
        if (t.spaceHold != null && !t.consumed && t.pk?.item?.isSpace == true && abs(x - t.x0) > letterWidth) {
            spaceToCursor(id, t, x, y)
            return
        }
        popup?.takeIf { it.owner === t }?.let {
            val cell = popupCellAt(it, x, y)
            if (cell != it.selected) { it.selected = cell; invalidate() }
            return
        }
        if (t.pk?.item?.isSpace == true && !t.consumed && multipleLanguages) {
            val dx = x - t.x0
            if (abs(dx) > 8 * density) timers.removeCallbacks(t.longPress)
            if (abs(dx) > t.pk!!.rect.width() / t.pk!!.item.w && abs(y - t.y0) < t.pk!!.rowHeight) {
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
            K_HANDLE -> {
                t.lastX = x
                if (abs(x - t.x0) > 8 * density) t.moved = true
                if (t.grip >= 0 && t.moved) {
                    gripSide = t.grip
                    gripDx = if (centerNeed > 0f) CenterOverflow.drag(t.grip, x - t.x0, splitInsets[0], splitInsets[1], splitRoom,
                        gapAtEdges, 4 * density, centerNeed)
                    else SplitPlacement.drag(t.grip, x - t.x0, splitInsets[0], splitInsets[1], splitRoom)
                    invalidate()
                }
            }
            K_CURSOR -> {
                t.fx = x; t.fy = y
                val now = SystemClock.uptimeMillis()
                if (t.barRepeat == null) moveCursor(t, t.joystick.displace((x - t.x0) / density, now), now)
                cursorPointer?.let { invalidatePointer(it); it.follow(pointerY(t)) }
                if (reducedMotion) cursorPointer?.step(t.joystick.push.toFloat(), 0f, true)
                removeCallbacks(cursorFrame)
                postOnAnimation(cursorFrame)
                invalidateWave(t.waveX, t.waveX, t.select)
            }
            K_STACK -> {
                // One swipe moves exactly one face; the rest of the gesture is ignored. Small wobbles stay a tap.
                val sw = swipe ?: return
                if (t.consumed || sw.stepped) return
                val dx = (x - t.x0) / density
                val dy = (y - t.y0) / density
                if (!t.moved && StackSwipe.moved(dx, dy)) { t.moved = true; timers.removeCallbacks(t.longPress) }
                if (!t.moved) return
                val w = t.pk!!.rect.width()
                sw.dx = (x - t.x0).coerceIn(-w, w)
                StackSwipe.direction(dx, dy, w / density).takeIf { it != 0 }?.let { stepStack(t, sw, it) }
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
            K_HANDLE -> if (t.grip >= 0) finishGrip(t, time) else if (t.moved && abs(t.lastX - t.x0) > 36 * density) slideKeyboard(t.lastX < t.x0)
            K_STACK -> {
                val sw = swipe?.takeIf { it.item === t.pk?.item }
                val dx = (t.tapX - t.x0) / density
                val dy = (t.tapY - t.y0) / density
                val moved = t.moved || StackSwipe.moved(dx, dy)
                // A quick flick can lift before the finger travels far, e.g. sideways off the edge of the keyboard.
                if (sw != null && moved && !sw.stepped && !t.consumed)
                    StackSwipe.flick(dx, dy, time - sw.downAt).takeIf { it != 0 }?.let { stepStack(t, sw, it) }
                if (sw != null) swipe = null
                if (!moved && !t.consumed) t.pk!!.item.let { emitKey(it.keys[shownIdx(it)], t.tapX, t.tapY) }
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
        if (!tapX.isNaN() && !tapY.isNaN() && output?.singleOrNull()?.let { it.isLetterOrDigit() || it == '\'' } == true) {
            tapFeatures(tapX, tapY, tapDistances, modelTap, drift = true)
            listener?.onModelTap(modelTap)
            if (output.single() == '\'') listener?.onKey(code) else listener?.onTapKey(code, tapDistances, tapX, tapY)
        } else {
            if (code == Keys.SPACE && !tapX.isNaN() && !tapY.isNaN()) {
                tapFeatures(tapX, tapY, tapDistances, modelTap, drift = true)
                listener?.onSpaceTap((0 until TapFeatures.SPACE - 1).minOf { modelTap[it] })
            }
            listener?.onKey(code)
        }
    }

    /** A tap's squared distances to each letter key ([distances], for the predictor) and its TapFeatures ([model]). */
    private fun tapFeatures(tapX: Float, tapY: Float, distances: FloatArray?, model: FloatArray, drift: Boolean) {
        val tapDistances = distances ?: FloatArray(ComposingTaps.LETTERS)
        tapDistances.fill(100f)
        model.fill(100f)
        for (pk in placed) {
            tapPoint[0] = tapX; tapPoint[1] = tapY
            // Each candidate's inverse removes its own tilt/curve transform.
            pk.inverse.mapPoints(tapPoint)
            if (drift) undrift(pk, tapPoint)
            if (pk.item.isSpace) {
                // How near the space bar the tap was, in letter widths from its edge (0 on it): a letter
                // tapped right beside it may have been a reach for space (tryn -> try).
                val pitch = (letterWidth + editGap).coerceAtLeast(1f)
                val dx = maxOf(pk.rect.left - tapPoint[0], 0f, tapPoint[0] - pk.rect.right) / pitch
                val dy = maxOf(pk.rect.top - tapPoint[1], 0f, tapPoint[1] - pk.rect.bottom) / pitch
                tapDistances[ComposingTaps.SPACE] = minOf(tapDistances[ComposingTaps.SPACE], dx * dx + dy * dy)
                val down = maxOf(pk.rect.top - tapPoint[1], 0f, tapPoint[1] - pk.rect.bottom) / (pk.rect.height() + editGap).coerceAtLeast(1f)
                model[TapFeatures.SPACE] = minOf(model[TapFeatures.SPACE], dx * dx + down * down)
                continue
            }
            // In key spacing (the drawn width plus the gap), as the predictor's costs assume: neighbouring
            // centres are one unit apart. The drawn width alone made every slip ~40% dearer on a gapped split.
            val width = (pk.rect.width() + editGap).coerceAtLeast(1f)
            val dx = (tapPoint[0] - pk.rect.centerX()) / width
            val dy = (tapPoint[1] - pk.rect.centerY()) / width
            val down = (tapPoint[1] - pk.rect.centerY()) / (pk.rect.height() + editGap).coerceAtLeast(1f)
            for (key in pk.item.keys) {
                val letter = key.singleOrNull()?.lowercaseChar() ?: continue
                if (letter.isLetter() && letter.code < ComposingTaps.LETTERS) {
                    tapDistances[letter.code] = minOf(tapDistances[letter.code], dx * dx + dy * dy)
                }
                val column = TapFeatures.LETTERS.indexOf(letter)
                if (column >= 0) model[column] = minOf(model[column], dx * dx + down * down)
            }
        }
    }

    private fun stepStack(t: Tracker, sw: Swipe, step: Int) {
        val item = sw.item
        item.idx = stackStep(item, step)
        sw.stepped = true
        sw.dx = 0f
        listener?.onStackFace(item.keys[item.idx])
        accessibilityEvent(placed.indexOf(t.pk), AccessibilityEvent.TYPE_VIEW_SELECTED)
        haptic(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun pressLetter(t: Tracker) {
        val item = t.pk?.item ?: return
        val code = item.keys[shownIdx(item)]
        if (!settings.bool(Prefs.TYPE_ON_PRESS) || state.layer != Layer.ABC || code.singleOrNull()?.isLetter() != true) return
        val output = KeyText.output(code, state) ?: return
        t.pressChoices = holdChoicesFor(code)
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
     * Holding a spacebar inserts the word centred in the suggestion wheel on its side (left spacebar â†’ left wheel),
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
    /** Which hand types [pk]: 0 for the left five columns, 1 for the right five, -1 for the center column. */
    private fun hand(pk: Placed): Int {
        val middle = pk.item.c + pk.item.w / 2f
        return if (middle < 5.5f) 0 else if (middle > 5.5f) 1 else -1
    }

    /** Moves a block-local tap point on [pk] back by how far its hand has drifted (TouchDrift), on the letter layer. */
    private fun undrift(pk: Placed, pt: FloatArray) {
        val drift = touchDrift ?: return
        if (editMode || state.layer != Layer.ABC || !settings.bool(Prefs.TOUCH_DRIFT)) return
        val hand = hand(pk)
        if (hand < 0) return
        val pitch = letterWidth + editGap
        pt[0] -= drift.x(hand) * pitch; pt[1] -= drift.y(hand) * pitch
    }

    /**
     * [word] has settled as it stands on screen: how far each of its taps landed from the key that word says it
     * meant teaches TouchDrift where that hand is now. Taps from before the keys were last placed are skipped.
     */
    fun learnDrift(taps: ComposingTaps, word: String) {
        val drift = touchDrift ?: return
        val letters = word.lowercase()
        if (editMode || !settings.bool(Prefs.TOUCH_DRIFT) || letters.length != taps.word.length) return
        val pitch = (letterWidth + editGap).coerceAtLeast(1f)
        for ((k, ch) in letters.withIndex()) {
            if (!taps.hasPoint(k) || taps.layout(k) != layoutGeneration) continue
            val pk = placed.firstOrNull { !it.item.isStack && it.item.keys[0].singleOrNull()?.lowercaseChar() == ch } ?: continue
            tapPoint[0] = taps.x(k); tapPoint[1] = taps.y(k)
            pk.inverse.mapPoints(tapPoint)
            drift.learn(hand(pk), (tapPoint[0] - pk.rect.centerX()) / pitch, (tapPoint[1] - pk.rect.centerY()) / pitch)
        }
    }

    private fun findKey(x: Float, y: Float, strict: Boolean = false, skip: GridKey? = null, far: Boolean = false, strip: Boolean = false): Placed? {
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
                if (!strict) undrift(pk, pt)
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
            // Topmost first, as drawn and as the hit test above: a key resized over a neighbour must not take the
            // neighbour's middle where the neighbour is drawn on top.
            for (i in placed.size - 1 downTo 0) {
                val pk = placed[i]
                if (pk.item === skip || !isLetter(pk)) continue
                pt[0] = x; pt[1] = y
                pk.inverse.mapPoints(pt)
                undrift(pk, pt)
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
        if (strict || y < stripH && !strip) return null
        var best: Placed? = null
        var bestDist = Float.MAX_VALUE
        var typing: Placed? = null
        var typingDist = Float.MAX_VALUE
        for (i in placed.indices) {
            val pk = placed[i]
            if (pk.item === skip) continue
            pt[0] = pk.rect.centerX(); pt[1] = pk.rect.centerY()
            pk.matrix.mapPoints(pt)
            val dist = hypot(pt[0] - x, pt[1] - y)
            if (dist < bestDist) { bestDist = dist; best = pk }
            if (dist < typingDist && FarTaps.counts(pk.item)) { typingDist = dist; typing = pk }
        }
        best?.takeIf { bestDist < max(it.rowHeight, unit) * 1.2f }?.let { return it }
        return typing?.takeIf { far && !editMode && state.layer == Layer.ABC && typingDist < max(it.rowHeight, unit) * FarTaps.REACH }
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
    /** A split half's grip lifted: a drag leaves the half there; a double-tap puts it back at its screen edge. */
    private fun finishGrip(t: Tracker, time: Long) {
        val side = t.grip
        val dx = gripDx
        gripSide = -1; gripDx = 0f
        val insets = splitInsets.copyOf()
        if (t.moved) {
            if (dx == 0f) { invalidate(); return }
            if (side == 0) insets[0] += dx else insets[1] -= dx
        } else if (gripTapSide == side && time - gripTapAt < ViewConfiguration.getDoubleTapTimeout()) {
            gripTapSide = -1
            insets[side] = 0f
        } else {
            gripTapSide = side; gripTapAt = time
            return
        }
        // Both are saved as drawn, so a stored inset that had to shrink to fit doesn't move the other half later.
        val w = width.toFloat()
        settings.setSplitInsetsFor(isLandscape, SplitPlacement.permille(insets[0], w), SplitPlacement.permille(insets[1], w))
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        relayout()
    }

    private fun slideKeyboard(left: Boolean) {
        val land = isLandscape
        val p = settings.profile(land)
        val change = handleChange(p.widthPercent, p.align, p.split, left, restoreWidth)
        restoreWidth = change.restore
        settings.setHandleFor(land, change.width, change.align)
    }

    private fun stopTimers(t: Tracker) {
        timers.removeCallbacks(t.longPress)
        t.spaceHold?.let { timers.removeCallbacks(it) }
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
    /** [tray] is the tray index when a mini key is being dragged, else -1. */
    private class Drag(val item: GridKey, val w: Float, val h: Float, var x: Float, var y: Float, val offX: Float, val offY: Float, val tray: Int = -1) {
        var target: Target? = null
        val startX = x; val startY = y
    }
    /** The symbol palette for one key's 123 or #+= text; [chips], [reset] and [panel] are laid out when drawn. */
    private class FacePicker(val item: GridKey) { val chips = ArrayList<RectF>(); val panel = RectF() }
    private var facePicker: FacePicker? = null
    /** Where a center key goes when the split gap is narrow; on 123 and #+= it also leads to the symbol palette. */
    private class SidePicker(val item: GridKey) { val chips = ArrayList<RectF>(); val symbol = RectF(); val panel = RectF() }
    private var sidePicker: SidePicker? = null

    private var drag: Drag? = null
    private var editingKey: GridKey? = null

    // Live drop preview: while a key is held over a target, the other keys glide to where the drop would put
    // them, so the drop itself needs no jump. Rects are in view coordinates, after each block's transform.
    private var previewSig: String? = null
    private val previewTo = HashMap<Int, RectF>()
    private val previewFrom = HashMap<Int, RectF>()
    private val shownRects = HashMap<Int, RectF>()
    private var previewSlot: RectF? = null
    private var previewStart = 0L

    private fun previewProgress(): Float {
        if (reducedMotion) return 1f
        val t = ((SystemClock.uptimeMillis() - previewStart) / PREVIEW_MS).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t) * (1f - t)
    }

    private fun resetPreview() { previewSig = null; previewTo.clear(); previewFrom.clear(); shownRects.clear(); previewSlot = null; fitSig = null }

    private fun screenRect(pk: Placed) = RectF(pk.rect).also { pk.matrix.mapRect(it) }

    /** Recomputes the preview only when the target changes; keys start from wherever they are drawn now. */
    private fun updatePreview(dg: Drag) {
        val t = dg.target
        val sig = t?.let { "${it.type}:${it.item?.id}:${it.before}:${it.r}:${it.c}" }
        if (sig == previewSig) return
        previewSig = sig
        previewFrom.clear(); shownRects.forEach { (id, r) -> previewFrom[id] = RectF(r) }
        previewTo.clear(); previewSlot = null
        previewStart = SystemClock.uptimeMillis()
        if (t != null) {
            val g = ed.copy()
            val ids = g.items.mapTo(HashSet()) { it.id }
            val item = if (dg.tray >= 0) null else g.items.firstOrNull { it.id == dg.item.id }
            applyDrop(g, dg, t, item, t.item?.let { ti -> g.items.firstOrNull { it.id == ti.id } }, live = false)
            if (t.type != T_REMOVE && t.type != T_TRAY) g.normalize()
            val slotId = if (dg.tray >= 0) g.items.firstOrNull { it.id !in ids }?.id else item?.id
            val out = ArrayList<Placed>()
            placeKeys(g, editConts, editGap, 0f, out)
            for (pk in out) { val r = screenRect(pk); if (pk.item.id == slotId) previewSlot = r else previewTo[pk.item.id] = r }
        }
        invalidate()
    }

    /** Edit-mode drawing while dragging: every key at its eased preview position, plus the opening slot. */
    private fun drawPreview(canvas: Canvas, dg: Drag, radius: Float, now: Long) {
        val p = previewProgress()
        for (pk in placed) {
            val id = pk.item.id
            val base = screenRect(pk)
            if (dg.tray < 0 && pk.item === dg.item) { shownRects[id] = base; continue }
            val from = previewFrom[id] ?: base
            val to = if (previewSig == null) base else previewTo[id]
            val rect = if (to == null) from else RectF(from.left + (to.left - from.left) * p, from.top + (to.top - from.top) * p,
                from.right + (to.right - from.right) * p, from.bottom + (to.bottom - from.bottom) * p)
            shownRects[id] = rect
            canvas.save()
            if (!reducedMotion) canvas.rotate(wiggle(pk.item, now), rect.centerX(), rect.centerY())
            val combining = dg.target?.type == T_COMBINE && dg.target?.item === pk.item
            if (combining) canvas.scale(1f + .08f * p, 1f + .08f * p, rect.centerX(), rect.centerY())
            // A key that the drop folds into another (combine) fades out instead of moving.
            drawKey(canvas, pk.item, rect, radius, false, if (to == null) 1f - p else 1f)
            if (combining) {
                stroke.color = palette.accent; stroke.alpha = 255; stroke.strokeWidth = 3 * density
                canvas.drawRoundRect(rect, radius, radius, stroke)
            }
            canvas.restore()
        }
        // Until the finger reaches a target, the key's home slot stays open.
        val slotRect = previewSlot ?: if (previewSig == null && dg.tray < 0) placed.firstOrNull { it.item === dg.item }?.let(::screenRect) else null
        slotRect?.let { slot ->
            fill.color = palette.accent; fill.alpha = (36 * p).toInt()
            canvas.drawRoundRect(slot, radius, radius, fill); fill.alpha = 255
            dash.color = palette.accent; dash.alpha = (255 * p).toInt(); dash.strokeWidth = 2 * density
            canvas.drawRoundRect(slot, radius, radius, dash)
        }
    }

    /** The tray band: mini keys waiting to be placed again. */
    private fun drawTray(canvas: Canvas, radius: Float) {
        if (trayRect.isEmpty) return
        val over = drag?.target?.type == T_TRAY
        fill.color = palette.functionKey; fill.alpha = if (over) 150 else 70
        canvas.drawRoundRect(trayRect, 12 * density, 12 * density, fill); fill.alpha = 255
        if (over) {
            stroke.color = palette.accent; stroke.alpha = 255; stroke.strokeWidth = 2 * density
            canvas.drawRoundRect(trayRect, 12 * density, 12 * density, stroke)
        }
        val sp = resources.displayMetrics.scaledDensity
        for (i in trayChips.indices) {
            val chip = trayChips[i]; val key = trayKeys.getOrNull(i) ?: continue
            val held = drag?.tray == trayIndex(i)
            drawKey(canvas, key, chip, radius * 0.7f, false, if (held) 0.3f else 1f)
            if (key.isSpace) drawLabel(canvas, chip, "space", min(11 * sp, chip.height() * 0.3f), palette.text, if (held) 80 else 255)
        }
    }

    /** The finger an editor gesture follows: a second finger never takes over a drag, and lifting the first ends it. */
    private var editPointer = -1

    private fun editTouch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) editPointer = e.getPointerId(0)
        val action = when {
            e.actionMasked == MotionEvent.ACTION_POINTER_UP && e.getPointerId(e.actionIndex) == editPointer -> MotionEvent.ACTION_UP
            e.actionMasked == MotionEvent.ACTION_POINTER_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_UP -> return true
            else -> e.actionMasked
        }
        val index = e.findPointerIndex(editPointer)
        if (index < 0) return true
        val x = e.getX(index)
        val y = e.getY(index)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                // The toolbar's chips; elsewhere in the bar a key raised into it can still be picked up and moved back.
                val onChip = undoRect.contains(x, y) || moreRect.contains(x, y) || doneRect.contains(x, y) || resetChip.contains(x, y) ||
                    mirrorChip.contains(x, y) || selectionChips.any { it.contains(x, y) }
                if (y < stripH && (onChip || findKey(x, y, strict = true) == null)) {
                    when {
                        undoRect.contains(x, y) -> undo()
                        moreRect.contains(x, y) -> listener?.onOpenSettings()
                        doneRect.contains(x, y) -> finishEditing()
                        resetChip.contains(x, y) -> resetSelection()
                        mirrorChip.contains(x, y) && displayProfile().split -> {
                            settings.set(Prefs.MIRROR_HALVES, !settings.bool(Prefs.MIRROR_HALVES)); invalidate()
                        }
                        else -> selectionChips.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }?.let {
                            clearSelection(); selection = Selection.entries[it]; invalidate()
                        }
                    }
                    return true
                }
                facePicker?.let { pickFace(it, x, y); return true }
                sidePicker?.let { pickSide(it, x, y); return true }
                val selected = shaping != null || selectedRow != null
                if (selected && compactRowChip.contains(x, y)) { compactShaped(all = false); return true }
                if (selected && compactAllChip.contains(x, y)) { compactShaped(all = true); return true }
                if (groupDown(x, y)) return true
                if (selection != Selection.KEY) {
                    // A nudged key still belongs to its saved half and row, wherever its edges now sit.
                    val hit = findKey(x, y, strict = true)
                    val ct = hit?.let { key -> editConts.firstOrNull { KeyGrid.region(it.c0) == KeyGrid.region(key.item.c) } }
                        ?: blockAt(x, y) ?: return true
                    if (selection == Selection.HALF) {
                        if (displayProfile().split && ct.side >= 0) selectedHalf = ct.side
                    } else {
                        val pt = floatArrayOf(x, y)
                        Matrix().also { ct.base.invert(it) }.mapPoints(pt)
                        val bands = layoutSolver.rowBands(ed, ct.un, barH)
                        selectedRow = hit?.item?.r ?: bands.tops.indices.firstOrNull {
                            pt[1] in bands.tops[it]..(bands.tops[it] + bands.heights[it])
                        }
                        rowRegion = KeyGrid.region(ct.c0)
                    }
                    invalidate()
                    return true
                }
                val wasShaping = shaping != null
                if (shapeDown(x, y)) return true
                // Tapping off the shaped key only finishes it: no other key is picked up or selected.
                if (wasShaping) return true
                if (trayRect.contains(x, y)) {
                    val i = trayChips.indexOfFirst { it.contains(x, y) }
                    if (i >= 0 && i < trayKeys.size) {
                        resetPreview()
                        drag = Drag(trayKeys[i], letterWidth, unit - editGap, x, y, 0f, 0f, tray = trayIndex(i))
                        invalidate()
                    }
                    return true
                }
                val pk = findKey(x, y, strict = true) ?: return true
                val pt = floatArrayOf(x, y)
                pk.inverse.mapPoints(pt)
                if (pk.item.isStack && hypot(pt[0] - (pk.rect.right - 8 * density), pt[1] - (pk.rect.top + 8 * density)) < 12 * density) {
                    val before = grid.toJson()
                    ed.separate(pk.item)
                    edited(before)
                    return true
                }
                resetPreview()
                drag = Drag(pk.item, pk.rect.width(), pk.rect.height(), x, y, pt[0] - pk.rect.centerX(), pt[1] - pk.rect.centerY())
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (groupDrag != null) { groupMove(x, y); return true }
                if (shapeDrag != null) { shapeMove(x, y); return true }
                val dg = drag ?: return true
                dg.x = x; dg.y = y
                dg.target = editTarget(x, y, dg)
                updatePreview(dg)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (groupDrag != null) { groupUp(); return true }
                if (shapeDrag != null) { shapeUp(e.eventTime); return true }
                val dg = drag ?: return true
                drag = null
                resetPreview()
                if (dg.tray < 0 && hypot(x - dg.startX, y - dg.startY) < ViewConfiguration.get(context).scaledTouchSlop) {
                    editorTap(dg.item)
                    return true
                }
                dg.target = editTarget(x, y, dg)
                val before = grid.toJson()
                val t = dg.target
                if (t != null) applyDrop(ed, dg, t, if (dg.tray >= 0) null else dg.item, t.item, live = true)
                val removal = t?.type == T_REMOVE || t?.type == T_TRAY
                edited(before, normalize = !removal)
            }
            MotionEvent.ACTION_CANCEL -> {
                groupUp(cancel = true)
                shapeDrag?.let { sd -> shaping?.let { it.edges = sd.edges.copyOf() }; shapeDrag = null; relayout() }
                drag = null; resetPreview(); invalidate()
            }
        }
        return true
    }

    /** Saves the layout as it was [before] an edit, so undo can return to it. Edits that changed nothing are skipped. */
    private fun edited(before: String, normalize: Boolean = true, beforeHalves: String? = null) {
        // Half gestures change settings only, so a symbol layer must not acquire an unrelated key edit.
        if (beforeHalves == null) {
            if (normalize) ed.normalize()
            if (editLayer != Layer.ABC) grid.setLayer(editLayer, ed)
        }
        val shapedId = shaping?.id
        layerGrids.fill(null)
        if (editLayer != Layer.ABC) shaping = ed.items.firstOrNull { it.id == shapedId }
        if (grid.toJson() != before || beforeHalves != null && halves() != beforeHalves) {
            history += EditState(before, beforeHalves)
            if (history.size > MAX_UNDO) history.removeAt(0)
            listener?.onEdited()
        }
        relayout()
    }

    /** Steps back one edit. */
    fun undo() {
        val prev = history.removeLastOrNull() ?: return
        val restored = KeyGrid.fromJson(prev.json) ?: return
        clearSelection()
        prev.halves?.let { if (previewHalves != null) previewHalves = it else settings.restoreHalfGeometry(it) }
        keepHistory = true
        grid = restored
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
            history += EditState(before)
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
            ThumbnailKey(path, bounds, when (face) { Keys.ENTER -> 2; Keys.ABC, Keys.NUM, Keys.SYM, Keys.BACKSPACE, Keys.INSERT, Keys.SHIFT -> 1; else -> 0 })
        }
    }

    private fun editTarget(x: Float, y: Float, dg: Drag): Target? {
        if (hintRect.contains(x, y)) return Target(T_REMOVE)
        if (trayRect.contains(x, y)) return if (dg.tray >= 0) null else Target(T_TRAY)
        findKey(x, y, strict = true, skip = dg.item)?.let { pk ->
            val item = pk.item
            // A key dropped on a gap takes its place.
            if (item.isGap) return Target(T_FILL, item, pk = pk)
            if (item.isSpace && item.w > 1 && KeyGrid.region(item.c) != 1 && !dg.item.isSpace) return Target(T_SPACE, item, pk = pk)
            val pt = floatArrayOf(x, y)
            pk.inverse.mapPoints(pt)
            val rel = (pt[0] - pk.rect.left) / pk.rect.width()
            // A gap never joins a swipe key: it goes beside the key instead.
            val before = if (dg.item.isGap) rel < 0.5f else rel <= 0.25f
            if (!dg.item.isGap && rel > 0.25f && rel < 0.75f) return Target(T_COMBINE, item, pk = pk)
            // A full row has no room: no slot opens and the key snaps back (it can still go to the tray).
            return if (insertFits(dg, item, before)) Target(T_INSERT, item, before = before, pk = pk) else Target(T_FULL, item, pk = pk)
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

    private var fitSig: String? = null
    private var fits = true

    /** Whether inserting beside [target] has room, tried on a copy; cached while the finger stays on the same spot. */
    private fun insertFits(dg: Drag, target: GridKey, before: Boolean): Boolean {
        val sig = "${dg.tray}:${dg.item.id}:${target.id}:$before"
        if (sig == fitSig) return fits
        val g = ed.copy()
        val key = if (dg.tray >= 0) g.trayFaces(dg.tray)?.let(g::detachedKey) else g.items.firstOrNull { it.id == dg.item.id }
        val other = g.items.firstOrNull { it.id == target.id }
        fits = key != null && other != null && g.insertShift(key, other, before)
        fitSig = sig
        return fits
    }

    /** What a key on 123 or #+= types, for the palette and hints. */
    private fun symbolOf(item: GridKey) = item.keys.getOrNull(shownIdx(item))?.let { KeyText.output(it, InputState()) }.orEmpty()

    /**
     * A tap while rearranging. A layer key edits the layer it leads to: the face after the shown one, so a swipe key
     * goes in the order its faces were arranged. On 123 or #+= a key that types opens the palette.
     */
    private fun editorTap(item: GridKey) {
        if (item.isGap) return
        val n = item.keys.size
        val next = (1..n).map { item.keys[(shownIdx(item) + it) % n] }
            .firstOrNull { KeyText.isLayerKey(it) && KeyText.layerOf(it) != editLayer }
        if (next != null) {
            clearSelection()
            editLayer = KeyText.layerOf(next)
            announceForAccessibility(when (editLayer) { Layer.ABC -> "Editing letters"; Layer.NUM -> "Editing numbers"; Layer.SYM -> "Editing symbols" })
            relayout()
        } else if (item.c == KeyGrid.CENTER && displayProfile().split) {
            sidePicker = SidePicker(item)
            invalidate()
        } else if (editLayer != Layer.ABC && symbolOf(item).isNotEmpty()) {
            facePicker = FacePicker(item)
            invalidate()
        } else {
            shaping = item
            announceForAccessibility("${KeyText.label(item.keys[shownIdx(item)], state)} selected")
            invalidate()
        }
    }

    /** Ends a selection without changing its saved geometry. */
    private fun clearSelection() {
        shaping = null; shapeDrag = null; selectedHalf = null; selectedRow = null; groupDrag = null
        lastShapeTap = 0L
        compactRowChip.setEmpty(); compactAllChip.setEmpty()
        invalidate()
    }

    /** The whole selected half or row band, in the touched block's coordinates. */
    private fun groupBounds(ct: Cont): RectF {
        val row = selectedRow
        if (row == null) return RectF(editGap / 2, editGap / 2, ct.u * ct.cols - editGap / 2, ct.un * ed.rows + barH - editGap / 2)
        val bands = layoutSolver.rowBands(ed, ct.un, barH)
        return RectF(0f, bands.tops[row], ct.u * ct.cols, bands.tops[row] + bands.heights[row])
    }

    /** The block under a tap, including empty cells when selecting a half. */
    private fun blockAt(x: Float, y: Float): Cont? = editConts.firstOrNull { ct ->
        val pt = floatArrayOf(x, y)
        Matrix().also { ct.base.invert(it) }.mapPoints(pt)
        RectF(0f, 0f, ct.u * ct.cols, ct.un * ed.rows + barH).contains(pt[0], pt[1])
    }

    /** Starts a half or row gesture, or consumes an outside tap to finish the selection. */
    private fun groupDown(x: Float, y: Float): Boolean {
        if (selectedHalf == null && selectedRow == null) return false
        val ct = if (selectedHalf != null) editConts.firstOrNull { it.side == selectedHalf }
        else editConts.firstOrNull { block ->
            val pt = floatArrayOf(x, y)
            Matrix().also { block.base.invert(it) }.mapPoints(pt)
            RectF(groupBounds(block)).apply { inset(-20 * density, -20 * density) }.contains(pt[0], pt[1])
        } ?: editConts.firstOrNull { KeyGrid.region(it.c0) == rowRegion }
        if (ct == null) { clearSelection(); return true }
        // Measure in the touched half, whose rows may have a different physical size.
        val inverse = Matrix().also { ct.base.invert(it) }
        val pt = floatArrayOf(x, y).also { inverse.mapPoints(it) }
        val r = groupBounds(ct)
        val radius = 20 * density
        val handle = if (selectedHalf != null) {
            val corners = listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom,
                r.centerX() to r.top - 20 * density)
            corners.indices.minByOrNull { hypot(pt[0] - corners[it].first, pt[1] - corners[it].second) }
                ?.takeIf { hypot(pt[0] - corners[it].first, pt[1] - corners[it].second) <= radius } ?: -1
        } else if (pt[0] in (r.left - radius)..(r.right + radius)) {
            when {
                abs(pt[1] - r.top) <= radius -> 0
                abs(pt[1] - r.bottom) <= radius -> 2
                else -> -1
            }
        } else -1
        if (handle < 0 && !r.contains(pt[0], pt[1])) { clearSelection(); return true }
        groupDrag = GroupDrag(handle, x, y, r, inverse, displayProfile(), grid.toJson(), halves(),
            selectedRow?.let(ed::rowScale) ?: 1f, selectedRow?.let { r.height() } ?: 0f, ct.un * ed.rows,
            splitInsets.copyOf(), splitRoom, gapAtEdges, centerNeed)
        return true
    }

    /** Applies a gesture against its initial geometry, avoiding feedback as the layout follows the finger. */
    private fun groupMove(x: Float, y: Float) {
        val gd = groupDrag ?: return
        if (selectedRow != null && gd.handle < 0) return
        if (!gd.moved && hypot(x - gd.x, y - gd.y) < ViewConfiguration.get(context).scaledTouchSlop) return
        gd.moved = true
        val points = floatArrayOf(gd.x, gd.y, x, y).also { gd.inverse.mapPoints(it) }
        val dx = points[2] - points[0]
        val dy = points[3] - points[1]
        val side = selectedHalf
        if (side != null) {
            val mirror = settings.bool(Prefs.MIRROR_HALVES)
            val old = if (side == 0) gd.profile.leftShape else gd.profile.rightShape
            halfPreview = when (gd.handle) {
                -1 -> {
                    val (left, right) = if (mirror) SplitPlacement.mirroredDrag(side, x - gd.x,
                        gd.insets[0], gd.insets[1], gd.room, gd.gap, gd.center)
                    else {
                        val delta = if (gd.center > 0f) CenterOverflow.drag(side, x - gd.x, gd.insets[0],
                            gd.insets[1], gd.room, gd.gap, 0f, gd.center)
                        else SplitPlacement.drag(side, x - gd.x, gd.insets[0], gd.insets[1], gd.room)
                        (gd.insets[0] + if (side == 0) delta else 0f) to
                            (gd.insets[1] - if (side == 1) delta else 0f)
                    }
                    gd.profile.copy(leftInset = SplitPlacement.permille(left, width.toFloat()),
                        rightInset = SplitPlacement.permille(right, width.toFloat()))
                }
                4 -> gd.profile.shapeHalf(side, old.copy(tilt = old.tilt +
                    ((x - gd.x) / (3 * density) * if (side == 0) 1 else -1).roundToInt()), mirror)
                else -> {
                    val sx = if (gd.handle == 0 || gd.handle == 3) -1 else 1
                    val sy = if (gd.handle < 2) -1 else 1
                    gd.profile.shapeHalf(side, old.copy(
                        width = (old.width * (1 + sx * dx / gd.rect.width().coerceAtLeast(1f))).roundToInt(),
                        height = (old.height * (1 + sy * dy / gd.rect.height().coerceAtLeast(1f))).roundToInt()), mirror)
                }
            }
        } else selectedRow?.let { row ->
            if (gd.handle >= 0) ed.setRowScale(row, layoutSolver.rowScaleForHeight(ed, row, gd.blockHeight,
                gd.rowHeight + if (gd.handle == 0) -dy else dy))
        }
        relayout()
    }

    /** Saves once on release; cancellation restores the initial row or discards the half preview. */
    private fun groupUp(cancel: Boolean = false) {
        val gd = groupDrag ?: return
        groupDrag = null
        if (cancel) {
            selectedRow?.let { ed.setRowScale(it, gd.rowScale) }
            halfPreview = null
            relayout()
            return
        }
        if (!gd.moved) return
        val next = halfPreview
        halfPreview = null
        if (next != null) saveHalves(next)
        edited(gd.before, beforeHalves = if (next != null) gd.halves else null)
    }

    /** Reset targets only the selected shape; a half returns to the shared sliders and its outside edge. */
    private fun resetSelection() {
        val before = grid.toJson()
        val halves = halves()
        val side = selectedHalf
        when {
            side != null -> {
                val mirror = settings.bool(Prefs.MIRROR_HALVES)
                // With Mirror on, both halves go back to the sliders entirely.
                val p = if (mirror) displayProfile().copy(leftShape = HalfShape(), rightShape = HalfShape())
                    else displayProfile().shapeHalf(side, HalfShape(), false)
                saveHalves(p.copy(
                    leftInset = if (side == 0 || mirror) 0 else p.leftInset,
                    rightInset = if (side == 1 || mirror) 0 else p.rightInset))
            }
            selectedRow != null -> ed.setRowScale(selectedRow!!, 1f)
            shaping != null -> shaping!!.edges = FloatArray(4)
            else -> return
        }
        edited(before, beforeHalves = if (side != null) halves else null)
    }

    /** Half outlines follow the block transform; row outlines include every key in that layout row. */
    private fun drawGroupSelection(canvas: Canvas) {
        stroke.color = palette.accent; stroke.alpha = 255; stroke.strokeWidth = 2 * density
        fill.color = palette.accent; fill.alpha = 255
        val row = selectedRow
        if (row != null) for (pk in placed.filter { it.item.r == row }) {
            canvas.save(); canvas.concat(pk.matrix)
            canvas.drawRoundRect(pk.rect, cornerRadius, cornerRadius, stroke)
            canvas.restore()
        }
        for (ct in editConts) {
            if (selectedHalf != null && ct.side != selectedHalf || selectedHalf == null && row == null) continue
            canvas.save(); canvas.concat(ct.base)
            val r = groupBounds(ct)
            if (selectedHalf != null) {
                canvas.drawRoundRect(r, cornerRadius, cornerRadius, stroke)
                for ((cx, cy) in listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom))
                    canvas.drawCircle(cx, cy, 6 * density, fill)
                canvas.drawLine(r.centerX(), r.top, r.centerX(), r.top - 20 * density, stroke)
                canvas.drawCircle(r.centerX(), r.top - 20 * density, 6 * density, fill)
            } else {
                for (y in listOf(r.top, r.bottom)) {
                    canvas.drawLine(r.left, y, r.right, y, stroke)
                    canvas.drawCircle(r.centerX(), y, 5 * density, fill)
                }
            }
            canvas.restore()
        }
        if (row != null && groupDrag == null) placed.firstOrNull { it.item.r == row }?.let { drawCompactChips(canvas, screenRect(it)) }
    }

    /**
     * A touch down while rearranging, on the selected key: near a corner it resizes, inside it moves the key. Anywhere
     * else it lets the key go (and the touch goes on as a rearranging one). True when the touch is the shaping's.
     */
    private fun shapeDown(x: Float, y: Float): Boolean {
        val item = shaping ?: return false
        val pk = placed.firstOrNull { it.item === item }
        if (pk == null) { shaping = null; invalidate(); return false }
        val pt = floatArrayOf(x, y)
        pk.inverse.mapPoints(pt)
        val r = pk.rect
        val corners = listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom)
        val corner = corners.indices.minByOrNull { hypot(pt[0] - corners[it].first, pt[1] - corners[it].second) }
            ?.takeIf { hypot(pt[0] - corners[it].first, pt[1] - corners[it].second) < 24 * density } ?: -1
        if (corner < 0 && !r.contains(pt[0], pt[1])) {
            shaping = null; invalidate()
            return false
        }
        shapeDrag = Shape(corner, pt[0], pt[1], item.edges.copyOf(), RectF(r), pk.column, pk.rowHeight, Matrix(pk.inverse), grid.toJson())
        return true
    }

    /** Resizes or moves [shaping] with the finger, in its row's key widths and rows, within MAX_EDGE and MIN_SIZE. */
    private fun shapeMove(x: Float, y: Float) {
        val sd = shapeDrag ?: return
        val item = shaping ?: return
        val pk = placed.firstOrNull { it.item === item } ?: return
        val pt = floatArrayOf(x, y)
        sd.inverse.mapPoints(pt)
        if (!sd.moved && hypot(pt[0] - sd.startX, pt[1] - sd.startY) < ViewConfiguration.get(context).scaledTouchSlop) return
        sd.moved = true
        val dx = (pt[0] - sd.startX) / sd.column.coerceAtLeast(1f)
        val dy = (pt[1] - sd.startY) / sd.rowHeight.coerceAtLeast(1f)
        val e = sd.edges.copyOf()
        val moves = when (sd.corner) {
            0 -> intArrayOf(0, 1); 1 -> intArrayOf(2, 1); 2 -> intArrayOf(2, 3); 3 -> intArrayOf(0, 3); else -> intArrayOf(0, 1, 2, 3)
        }
        for (i in moves) e[i] += if (i % 2 == 0) dx else dy
        val lim = KeyGrid.MAX_EDGE
        for (i in 0..3) e[i] = e[i].coerceIn(-lim, lim)
        // Never smaller than MIN_SIZE: the edge being dragged stops.
        val width = item.w + e[2] - e[0]
        if (width < KeyGrid.MIN_SIZE) { if (sd.corner == 0 || sd.corner == 3) e[0] = item.w + e[2] - KeyGrid.MIN_SIZE else e[2] = e[0] - item.w + KeyGrid.MIN_SIZE }
        val height = 1 + e[3] - e[1]
        if (height < KeyGrid.MIN_SIZE) { if (sd.corner == 0 || sd.corner == 1) e[1] = 1 + e[3] - KeyGrid.MIN_SIZE else e[3] = e[1] - 1 + KeyGrid.MIN_SIZE }
        item.edges = e
        // The rect follows at once; the whole layout is placed again when the finger lifts.
        val c = sd.column; val s = sd.edges
        pk.rect.set(sd.rect.left + (e[0] - s[0]) * c, sd.rect.top + (e[1] - s[1]) * sd.rowHeight, sd.rect.right + (e[2] - s[2]) * c, sd.rect.bottom + (e[3] - s[3]) * sd.rowHeight)
        invalidate()
    }

    /** The end of a shaping touch: saved for undo; a double-tap on the key puts it back in its cell. */
    private fun shapeUp(time: Long) {
        val sd = shapeDrag ?: return
        shapeDrag = null
        val item = shaping ?: return
        if (!sd.moved) {
            if (time - lastShapeTap < ViewConfiguration.getDoubleTapTimeout()) { item.edges = FloatArray(4); lastShapeTap = 0L }
            else { lastShapeTap = time; return }
        }
        edited(sd.before)
    }

    /** Closes up the shaped key's row, or every row, around it (KeyGrid.compactRow), as one undoable step. */
    private fun compactShaped(all: Boolean) {
        val item = shaping
        val row = selectedRow
        if (item == null && row == null) return
        val before = grid.toJson()
        val changed = if (all) ed.compactAll(item) else if (item != null) ed.compactRow(item.r, KeyGrid.region(item.c), item)
        else (0..2).map { ed.compactRow(row!!, it, null) }.any { it }
        if (changed) edited(before)
        announceForAccessibility(if (changed) "Keys closed up" else "Nothing to close up")
        invalidate()
    }

    /** The selected key's outline and corner dots, in the key's own coordinates, and its Compact buttons beside it. */
    private fun drawShaping(canvas: Canvas) {
        compactRowChip.setEmpty(); compactAllChip.setEmpty()
        val item = shaping ?: run { drawGroupSelection(canvas); return }
        val pk = placed.firstOrNull { it.item === item } ?: return
        canvas.save()
        canvas.concat(pk.matrix)
        val r = pk.rect
        stroke.color = palette.accent; stroke.alpha = 255; stroke.strokeWidth = 2 * density
        canvas.drawRoundRect(r, cornerRadius, cornerRadius, stroke)
        fill.color = palette.accent; fill.alpha = 255
        for ((cx, cy) in listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom)) canvas.drawCircle(cx, cy, 6 * density, fill)
        canvas.restore()
        if (shapeDrag != null) return
        drawCompactChips(canvas, screenRect(pk))
    }

    /** Compact actions stay beside the selected keys and inside the view. */
    private fun drawCompactChips(canvas: Canvas, sr: RectF) {
        val h = 30 * density
        val size = min(13 * density, h * 0.45f)
        text.textSize = size
        val labels = listOf("Compact row", "Compact all")
        val widths = labels.map { text.measureText(it) + 24 * density }
        val total = widths.sum() + 8 * density
        val top = (if (sr.top - h - 10 * density >= stripH) sr.top - h - 10 * density else sr.bottom + 10 * density)
            .coerceIn(stripH, max(stripH, height - h - 4 * density))
        var x = (sr.centerX() - total / 2).coerceIn(4 * density, (width - total - 4 * density).coerceAtLeast(4 * density))
        for ((i, label) in labels.withIndex()) {
            val chip = if (i == 0) compactRowChip else compactAllChip
            chip.set(x, top, x + widths[i], top + h)
            fill.color = palette.functionKey; fill.alpha = 255
            canvas.drawRoundRect(chip, h / 2, h / 2, fill)
            stroke.color = palette.accent; stroke.strokeWidth = 1.5f * density
            canvas.drawRoundRect(chip, h / 2, h / 2, stroke)
            drawLabel(canvas, chip, label, size, palette.accent, 255)
            x += widths[i] + 8 * density
        }
    }

    /** Applies a palette tap: a symbol for the key, or (outside the panel) closing it. */
    private fun pickFace(picker: FacePicker, x: Float, y: Float) {
        val before = grid.toJson()
        val i = picker.chips.indexOfFirst { it.contains(x, y) }
        when {
            i >= 0 -> {
                val choice = FACE_CHOICES[i]
                picker.item.keys[shownIdx(picker.item)] = if (choice.length == 1 && choice[0].isDigit()) "d$choice" else KeyText.literal(choice)
            }
            picker.panel.contains(x, y) -> return
        }
        facePicker = null
        edited(before)
    }

    /** Applies a side picker tap: a side, the symbol palette, or (outside the panel) closing it. */
    private fun pickSide(picker: SidePicker, x: Float, y: Float) {
        val i = picker.chips.indexOfFirst { it.contains(x, y) }
        when {
            i >= 0 -> {
                val before = grid.toJson()
                picker.item.side = CenterSide.entries[i]
                sidePicker = null
                edited(before)
                return
            }
            !picker.symbol.isEmpty && picker.symbol.contains(x, y) -> facePicker = FacePicker(picker.item)
            picker.panel.contains(x, y) -> return
        }
        sidePicker = null
        invalidate()
    }

    private fun drawSidePicker(canvas: Canvas, picker: SidePicker) {
        val d = density
        val sp = resources.displayMetrics.scaledDensity
        val header = 40 * d
        val chipH = 44 * d
        val withSymbol = editLayer != Layer.ABC && symbolOf(picker.item).isNotEmpty()
        val h = header + chipH + 12 * d + if (withSymbol) chipH + 8 * d else 0f
        val w = min(quickOptionsBounds.width() - 16 * d, 320 * d)
        val cx = quickOptionsBounds.centerX()
        val top = stripH + 8 * d
        val panel = picker.panel
        panel.set(cx - w / 2, top, cx + w / 2, top + h)
        fill.color = palette.background
        canvas.drawRoundRect(panel, 16 * d, 16 * d, fill)
        stroke.color = palette.hint; stroke.strokeWidth = d
        canvas.drawRoundRect(panel, 16 * d, 16 * d, stroke)
        drawLabel(canvas, RectF(panel.left + 12 * d, panel.top, panel.right - 12 * d, panel.top + header),
            "Center keys", 14 * sp, palette.text, 255)
        val gap = 6 * d
        val chipW = (w - 24 * d - 2 * gap) / 3
        picker.chips.clear()
        for ((i, side) in CenterSide.entries.withIndex()) {
            val x = panel.left + 12 * d + i * (chipW + gap)
            val r = RectF(x, panel.top + header, x + chipW, panel.top + header + chipH)
            picker.chips += r
            val selected = picker.item.side == side
            fill.color = if (selected) palette.accent else palette.key
            canvas.drawRoundRect(r, 6 * d, 6 * d, fill)
            if (!selected) canvas.drawRoundRect(r, 6 * d, 6 * d, stroke)
            val label = when (side) { CenterSide.AUTO -> "Auto"; CenterSide.LEFT -> "Left"; CenterSide.RIGHT -> "Right" }
            drawLabel(canvas, r, label, 14 * sp, if (selected) palette.onAccent else palette.text, 255)
        }
        if (withSymbol) {
            val y = panel.top + header + chipH + 8 * d
            picker.symbol.set(panel.left + 12 * d, y, panel.right - 12 * d, y + chipH)
            fill.color = palette.key
            canvas.drawRoundRect(picker.symbol, 6 * d, 6 * d, fill)
            canvas.drawRoundRect(picker.symbol, 6 * d, 6 * d, stroke)
            drawLabel(canvas, picker.symbol, "Change symbol", 14 * sp, palette.text, 255)
        } else picker.symbol.setEmpty()
    }

    private fun drawFacePicker(canvas: Canvas, picker: FacePicker) {
        val d = density
        val sp = resources.displayMetrics.scaledDensity
        val panel = picker.panel
        val bottom = if (trayRect.isEmpty) handleRect.top else trayRect.bottom
        panel.set(quickOptionsBounds.left + 8 * d, stripH + 4 * d, quickOptionsBounds.right - 8 * d, bottom - 4 * d)
        fill.color = palette.background
        canvas.drawRoundRect(panel, 16 * d, 16 * d, fill)
        stroke.color = palette.hint; stroke.strokeWidth = d
        canvas.drawRoundRect(panel, 16 * d, 16 * d, stroke)
        val header = 40 * d
        val current = symbolOf(picker.item)
        drawLabel(canvas, RectF(panel.left + 12 * d, panel.top, panel.right - 12 * d, panel.top + header),
            "Change the $current key to:", 14 * sp, palette.text, 255)
        // As many columns as fit, then shrink the chips until every row fits the panel.
        val gap = 6 * d
        val areaW = panel.width() - 2 * gap
        val areaH = panel.height() - header - gap
        var size = 44 * d
        var cols = ((areaW + gap) / (size + gap)).toInt().coerceAtLeast(1)
        while (size > 20 * d && ((FACE_CHOICES.size + cols - 1) / cols) * (size + gap) > areaH) {
            size -= 2 * d
            cols = ((areaW + gap) / (size + gap)).toInt().coerceAtLeast(1)
        }
        val left = panel.left + gap + (areaW - (cols * (size + gap) - gap)) / 2
        picker.chips.clear()
        FACE_CHOICES.forEachIndexed { i, choice ->
            val x = left + (i % cols) * (size + gap)
            val y = panel.top + header + (i / cols) * (size + gap)
            val r = RectF(x, y, x + size, y + size)
            picker.chips += r
            val selected = choice == current
            fill.color = if (selected) palette.accent else palette.key
            canvas.drawRoundRect(r, 6 * d, 6 * d, fill)
            // Outlined like the keys, so chips read as buttons in themes whose keys match the background.
            if (!selected) canvas.drawRoundRect(r, 6 * d, 6 * d, stroke)
            drawLabel(canvas, r, choice, min(20 * d * labelScale, size * 0.5f), if (selected) palette.onAccent else palette.text, 255)
        }
    }

    /**
     * Applies a drop to [g]: the live grid on release, or a copy for the preview. [item] is the dragged key in
     * [g] (null for a tray key) and [target] the target key in [g]. Removing a key sets it aside in the tray;
     * only a tray key dropped on the trash is deleted.
     */
    private fun applyDrop(g: KeyGrid, dg: Drag, t: Target, item: GridKey?, target: GridKey?, live: Boolean) {
        if (dg.tray >= 0) {
            when (t.type) {
                T_REMOVE -> if (dg.tray < g.tray.size) g.tray.removeAt(dg.tray)
                T_MOVE -> g.placeFromTray(dg.tray) { g.moveTo(it, t.r, t.c) }
                T_SPACE -> if (target != null) g.placeFromTray(dg.tray) { g.insertBesideSpace(it, target) }
                T_COMBINE -> if (target != null) g.combineFromTray(dg.tray, target, faceSlot(target))
                T_FILL -> if (target != null) g.placeFromTray(dg.tray) { g.fillGap(it, target) }
                T_INSERT -> if (target != null) g.placeFromTray(dg.tray) { g.insertShift(it, target, t.before) }
            }
            return
        }
        item ?: return
        when (t.type) {
            T_REMOVE, T_TRAY -> {
                val hint = g.removalHint(item)
                if (!g.remove(item) && hint != null && live) flashHint(hint)
            }
            T_MOVE -> g.moveTo(item, t.r, t.c)
            T_SPACE -> if (target != null) g.insertBesideSpace(item, target)
            T_COMBINE -> if (target != null) g.combine(item, target, faceSlot(target))
            T_FILL -> if (target != null) g.fillGap(item, target)
            T_INSERT -> if (target != null) g.insertShift(item, target, t.before)
        }
    }

    /**
     * Where faces dropped on [target] go: ahead of the face that switches to the layer being edited (so on 123,
     * dropping #+= onto a key showing 123 gives abc, #+=, 123), otherwise at the end.
     */
    private fun faceSlot(target: GridKey): Int? = target.keys.indexOfFirst {
        editLayer != Layer.ABC && KeyText.isLayerKey(it) && KeyText.layerOf(it) == editLayer
    }.takeIf { it >= 0 }

    /** Announces why a protected key could not be removed. */
    private fun flashHint(hint: String) { announceForAccessibility(hint) }

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
        // The floating key rides above the finger so the opening slot stays visible; the finger picks the target.
        canvas.translate(dg.x - dg.offX - dg.w / 2, dg.y - dg.offY - dg.h / 2 - GHOST_LIFT * density)
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
        const val A_SELECTION = 10010
        const val A_RESET = 10013
        const val A_MIRROR = 10014
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
        const val K_KEY = 0
        const val K_STACK = 1
        const val K_WHEEL = 2
        const val K_HANDLE = 3
        /** How much smaller the deepest key looks at full depth. */
        private const val MAX_SINK = .08f
        const val K_GEAR = 4
        const val K_CURSOR = 5
        const val MAX_UNDO = 50
        const val T_MOVE = 0
        const val T_REMOVE = 4
        const val T_SPACE = 1
        const val T_COMBINE = 2
        const val T_INSERT = 3
        const val T_TRAY = 5
        const val T_FULL = 6
        const val T_FILL = 7
        /** The symbol palette: digits, then punctuation, currency and common signs. */
        private val FACE_CHOICES = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "!", "@", "#", "$", "%", "^", "&", "*",
            "(", ")", "-", "_", "=", "+", "[", "]", "{", "}", "\\", "|", ";", ":", "'", "\"", ",", ".", "<", ">", "/", "?", "`", "~",
            "â‚¬", "Â£", "Â¥", "Â¢", "â‚¹", "â€¢", "Â°", "Â±", "Ã—", "Ã·", "Â§", "Â¶", "Â©", "Â®", "â„¢", "â€¦", "Â¿", "Â¡", "â€”", "â€“", "â‰ ", "â‰ˆ", "â‰¤", "â‰¥",
            "âˆš", "Ï€", "Âµ", "â†", "â†’", "â†‘", "â†“")
        const val PREVIEW_MS = 160f
        const val GHOST_LIFT = 50f

    }
}
