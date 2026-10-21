package app.tailorkeys

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal data class WheelTapTarget(val index: Int, val center: Float, val width: Float)

/** Only words intersecting the wheel's clip are tappable; gaps choose the nearest visible centre. */
internal fun wheelTapIndex(targets: List<WheelTapTarget>, x: Float, left: Float, right: Float): Int {
    val visible = targets.filter { it.center + it.width / 2 > left && it.center - it.width / 2 < right }
    return (visible.firstOrNull { abs(x - it.center) <= it.width / 2 }
        ?: visible.minByOrNull { abs(x - it.center) } ?: targets.first()).index
}

/** Time is monotonic milliseconds, displacement is in dp from the fixed press origin. */
internal class CursorJoystick {
    /** Current signed speed in characters per second (also drives the gap's direction gradient). */
    var speed = 0.0
        private set
    /** How far the push reaches toward the gap's edge, signed, from -1 to 1; drives the keyboard-wide wave. */
    var push = 0.0
        private set
    private var fraction = 0.0
    private var lastTime = 0L
    private var hasTime = false
    private var reachLeft = MIN_REACH
    /**
     * Below 1, the same push moves the cursor (and the glow) less, yet reaching the gap's edge still gives top speed:
     * the response curve bends rather than being cut short.
     */
    var sensitivity = 1.0
    private var reachRight = MIN_REACH

    /** How far the gap extends from the press point on each side, in dp. Pushing to that edge gives top speed. */
    fun reach(left: Float, right: Float) {
        reachLeft = max(MIN_REACH, left.toDouble())
        reachRight = max(MIN_REACH, right.toDouble())
    }

    /**
     * Like a thumb stick: [dx] is how far the finger has pushed from where it pressed, in dp, and the cursor keeps
     * moving that way until it comes back. Speed runs from 3 chars/s just past the dead zone to [MAX_SPEED] at the
     * gap's edge, easing in so small pushes stay precise; the finger never has to leave the gap.
     */
    fun displace(dx: Float, now: Long): Int {
        var steps = advance(now)
        val magnitude = abs(dx.toDouble())
        val reach = if (dx < 0) reachLeft else reachRight
        val bend = 1 / sensitivity
        push = min(1.0, magnitude / reach).pow(bend) * if (dx < 0) -1 else 1
        val u = ((magnitude - DEAD_ZONE) / (reach - DEAD_ZONE)).coerceIn(0.0, 1.0).pow(bend)
        val next = if (magnitude <= DEAD_ZONE) 0.0 else (MIN_SPEED + (MAX_SPEED - MIN_SPEED) * u * Math.sqrt(u)) * if (dx < 0) -1 else 1
        if (next == 0.0 || next * speed < 0) fraction = 0.0
        if (next != 0.0 && speed == 0.0) steps += if (next < 0) -1 else 1
        speed = next
        return steps
    }

    fun advance(now: Long): Int {
        val previous = lastTime
        lastTime = now
        if (!hasTime) { hasTime = true; return 0 }
        fraction += speed * (now - previous).coerceAtLeast(0) / 1000.0
        // Avoid losing a whole character to floating-point error at an exact boundary.
        val steps = (fraction + if (fraction > 0) 1e-9 else -1e-9).toInt()
        fraction -= steps
        return steps
    }

    companion object {
        const val MAX_SPEED = 30.0
        private const val MIN_SPEED = 3.0
        private const val DEAD_ZONE = 4.0
        /** A press right beside the gap's edge still gets this much room before top speed. */
        private const val MIN_REACH = 8.0
    }
}

internal class GapPresses {
    private var liftedAt: Long? = null
    private var x = 0f
    private var y = 0f
    fun isSelection(now: Long, x: Float, y: Float): Boolean =
        liftedAt?.let { now - it in 0..600 && hypot(x - this.x, y - this.y) <= 60 } ?: false
    fun lift(now: Long, x: Float, y: Float) { liftedAt = now; this.x = x; this.y = y }
    fun clear() { liftedAt = null }
}

/** Retained native panel. Preference refreshes update existing cards; opening never lays out keys. */
class QuickOptions(
    context: Context,
    private val settings: KeyboardSettings,
    private val landscape: () -> Boolean,
    private val stripHeight: () -> Int,
    private val close: () -> Unit,
    private val rearrange: (String, String) -> Unit,
    private val language: () -> Unit,
    private val allSettings: () -> Unit,
    private val tidy: () -> Unit,
) : LinearLayout(context) {
    private var review: View? = null
    private val d = resources.displayMetrics.density
    private fun dp(n: Int) = (n * d).toInt()
    private val library = LayoutLibrary(settings, landscape).also { it.migrate(recoverTrial = false) }
    private val thumbnails = LayoutThumbnailCache(context, settings)
    private val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val back = button("\u2039  Back") { if (confirm.visibility == View.VISIBLE) hideConfirm() else if (showingAll) showAll(false) else close() }
    private val heading = TextView(context).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD) }
    private val trailing = button("All layouts \u203a") { if (showingAll) edit(false) else showAll(true) }
    private val pages = FrameLayout(context)
    private val main = LinearLayout(context).apply { orientation = VERTICAL }
    private val mainScroll = ScrollView(context).apply {
        isFillViewport = true; isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(18))
    }
    private val stripRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val actions = LinearLayout(context).apply { orientation = VERTICAL }
    private val editButton = button("Edit") { edit(false) }
    private val newButton = button("+\nNew") {
        if (library.inUse == "draft") { library.saveDraft(library.nextName("Custom")); refresh() } else edit(true)
    }
    private val strip = object : HorizontalScrollView(context) {
        override fun getLeftFadingEdgeStrength() = 0f
    }.apply {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(36))
    }
    private val stripCards = LinearLayout(context)
    private val tileScroll = ScrollView(context).apply {
        isFillViewport = true; isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(18))
    }
    private val tileGrid = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.TOP }
    private val allScroll = ScrollView(context).apply { isVerticalScrollBarEnabled = true; visibility = View.INVISIBLE }
    private val allGrid = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(8), 0, dp(8), dp(8)) }
    private val message = TextView(context).apply { textSize = 12f; setPadding(dp(12), 0, dp(12), dp(4)); visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val confirm = FrameLayout(context).apply { visibility = View.GONE; isClickable = true; isFocusable = true }
    private val confirmBox = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
    private val confirmTitle = TextView(context).apply { text = "Save your unsaved Custom first?"; textSize = 16f; setTypeface(typeface, Typeface.BOLD) }
    private val confirmText = TextView(context).apply { text = "There is room for one draft. Save this one before starting another."; textSize = 13f; setPadding(0, dp(6), 0, dp(10)) }
    private val cancel = button("Keep Custom") { hideConfirm() }
    private val saveContinue = button("Save and continue") { continueEdit() }
    private var pendingCopy: Pair<LayoutEntry, Boolean>? = null
    private var showingAll = false
    private var rendered: QuickOptionsState? = null
    private var renderedEntries: List<LayoutEntry>? = null
    private var renderedSelection: String? = null
    private var palette: Palette? = null
    private var columns = 0
    private var cardWidth = 124
    private var cardHeight = 100
    private var gridCardHeight = 108
    private var narrowGrid = false
    private var renderedGeometry: String? = null
    private var renderedStyles: String? = null
    private val revealInUse = object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            viewTreeObserver.removeOnPreDrawListener(this)
            scrollInUse()
            return true
        }
    }
    private val stripViews = linkedMapOf<String, LayoutCard>()
    private val gridViews = linkedMapOf<String, LayoutCard>()
    private val newLayout = button("+\nNew layout") { edit(true) }
    private val paste = button("Paste code") { pasteCode() }
    private val tiles = ArrayList<Tile>()
    private lateinit var size: Tile
    private val smaller = button("\u2212") { settings.adjustQuickHeight(landscape(), -2); refresh() }
    private val larger = button("+") { settings.adjustQuickHeight(landscape(), 2); refresh() }

    private fun button(title: String, action: () -> Unit) = TextView(context).apply {
        text = title; textSize = 14f; gravity = Gravity.CENTER
        minWidth = dp(48); minHeight = dp(48)
        isClickable = true; isFocusable = true
        setOnClickListener { action() }
    }

    private inner class Tile(icon: Int?, title: String) {
        val box = FrameLayout(context).apply { isClickable = true; isFocusable = true }
        val background = GradientDrawable()
        val iconView = ImageView(context).apply { if (icon != null) setImageResource(icon) }
        val glyphView = TextView(context).apply { textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
        val labelView = TextView(context).apply { text = title; textSize = 12f; gravity = Gravity.CENTER; maxLines = 1 }
        val stateView = TextView(context).apply { textSize = 11f; gravity = Gravity.CENTER; maxLines = 1 }
        var rendered: QuickTileState? = null
        init {
            box.background = background
            val stack = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
            stack.addView(if (icon != null) iconView else glyphView, LayoutParams(dp(20), dp(20)))
            stack.addView(labelView, LayoutParams(-1, -2))
            stack.addView(stateView, LayoutParams(-1, -2))
            box.addView(stack, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
    }

    private inner class LayoutCard(var entry: LayoutEntry, val fullName: Boolean) {
        val box = LinearLayout(context).apply {
            orientation = VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(4))
            isClickable = true; isFocusable = true
        }
        val bg = GradientDrawable()
        val preview = FrameLayout(context)
        val drawing = LayoutThumbnailView(context)
        val check = TextView(context).apply { text = "\u2713"; textSize = 12f; gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val unsaved = TextView(context).apply { text = "Unsaved"; textSize = 10f; gravity = Gravity.CENTER; setPadding(dp(4), 0, dp(4), 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val name = TextView(context).apply {
            textSize = 12f; maxLines = if (fullName) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private var keys: List<KeyboardView.ThumbnailKey>? = null
        private var tabletSized: Boolean? = null
        init {
            box.background = bg
            preview.addView(drawing, FrameLayout.LayoutParams(-1, -1))
            preview.addView(check, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.START))
            preview.addView(unsaved, FrameLayout.LayoutParams(-2, dp(20), Gravity.TOP or Gravity.END))
            box.addView(preview, LayoutParams(-1, 0, 1f))
            box.addView(name, LayoutParams(-1, if (fullName) dp(34) else dp(22)))
            box.setOnClickListener {
                library.use(entry)
                refresh()
            }
        }
        fun bind(state: QuickLayoutTileState, p: Palette, radius: Float) {
            entry = state.entry
            name.text = entry.name
            name.setTypeface(null, if (state.bold) Typeface.BOLD else Typeface.NORMAL)
            name.setTextColor(p.text)
            box.contentDescription = state.label
            box.isSelected = state.inUse
            bg.setColor(p.key); bg.cornerRadius = radius
            bg.setStroke(dp(state.outlineDp), if (state.inUse) p.accent else p.keyOutline)
            check.visibility = if (state.inUse) View.VISIBLE else View.INVISIBLE
            check.setTextColor(p.onAccent)
            check.background = rounded(p.accent, dp(12).toFloat())
            unsaved.visibility = if (entry.draft) View.VISIBLE else View.GONE
            unsaved.setTextColor(p.text)
            unsaved.background = rounded(p.functionKey, dp(10).toFloat())
            val nextKeys = thumbnails.keys(entry)
            if (keys !== nextKeys) { keys = nextKeys; drawing.artwork = ThumbnailArtwork(nextKeys) }
            drawing.palette = p; drawing.invalidate()
        }
        fun sizeFor(tablet: Boolean) {
            if (tabletSized == tablet) return
            tabletSized = tablet
            check.text = if (tablet) "\u2713 In use" else "\u2713"
            check.layoutParams.width = dp(if (tablet) 70 else 24)
            name.textSize = if (tablet) 14f else 12f
        }
    }

    init {
        orientation = VERTICAL; isClickable = true
        back.contentDescription = "Back to keyboard"
        back.setTypeface(null, Typeface.BOLD)
        back.textSize = 15f
        editButton.textSize = 12f; newButton.textSize = 12f; newLayout.textSize = 12f
        back.setPadding(dp(14), 0, dp(14), 0)
        trailing.setPadding(dp(8), 0, dp(8), 0)
        editButton.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_settings_edit, 0, 0)
        header.addView(back, LayoutParams(-2, -1))
        header.addView(heading, LayoutParams(0, -2, 1f))
        header.addView(trailing, LayoutParams(-2, -1))
        addView(header, LayoutParams(-1, dp(48)))
        addView(message, LayoutParams(-1, -2))
        addView(pages, LayoutParams(-1, 0, 1f))
        mainScroll.addView(main, FrameLayout.LayoutParams(-1, -2))
        pages.addView(mainScroll, FrameLayout.LayoutParams(-1, -1))
        pages.addView(allScroll, FrameLayout.LayoutParams(-1, -1))
        stripRow.setPadding(dp(8), 0, dp(8), 0)
        actions.addView(editButton, LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(4) })
        actions.addView(newButton, LayoutParams(-1, 0, 1f))
        stripRow.addView(actions, LayoutParams(dp(58), -1).apply { marginEnd = dp(8) })
        strip.addView(stripCards)
        stripRow.addView(strip, LayoutParams(0, -1, 1f))
        main.addView(stripRow, LayoutParams(-1, dp(100)))
        tileScroll.addView(tileGrid)
        main.addView(tileScroll, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        allScroll.addView(allGrid)
        fun row() = LinearLayout(context).also { tileGrid.addView(it, LayoutParams(-1, -2)) }
        fun tile(row: LinearLayout, title: String, icon: Int?, weight: Float = 1f, action: (() -> Unit)? = null): Tile = Tile(icon, title).also { t ->
            row.addView(t.box, LayoutParams(0, dp(64), weight).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            if (action != null) t.box.setOnClickListener { action(); refresh() }
            tiles += t
        }
        tileGrid.setPadding(dp(5), 0, dp(5), dp(4))
        val first = row()
        tile(first, "Split", R.drawable.ic_settings_layout) { val l = landscape(); settings.setSplitFor(l, !settings.split(l)) }
        tile(first, "One hand", R.drawable.ic_settings_expand) { settings.cycleQuickOneHand(landscape()) }
        size = tile(first, "Size", R.drawable.ic_settings_size, 2f)
        size.box.isClickable = false; size.box.isFocusable = false
        size.box.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        size.box.addView(smaller, FrameLayout.LayoutParams(dp(48), -1, Gravity.START))
        size.box.addView(larger, FrameLayout.LayoutParams(dp(48), -1, Gravity.END))
        val second = row()
        tile(second, "Theme", R.drawable.ic_settings_look) { settings.cycleQuickTheme() }
        tile(second, "Swipe", R.drawable.ic_settings_typing) { settings.set(Prefs.SWIPE_TYPING, !settings.bool(Prefs.SWIPE_TYPING)) }
        tile(second, "Language", null) { language() }
        tile(second, "Settings", R.drawable.ic_settings_tune) { allSettings() }
        val third = row()
        tile(third, "Pause learning", R.drawable.ic_settings_pause) { settings.set(Prefs.LEARNING, !settings.bool(Prefs.LEARNING)) }
        tile(third, "Tidy", R.drawable.ic_settings_tidy) { tidy() }
        confirmBox.addView(confirmTitle); confirmBox.addView(confirmText)
        val buttons = LinearLayout(context)
        buttons.addView(cancel, LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        buttons.addView(saveContinue, LayoutParams(0, dp(48), 1f))
        confirmBox.addView(buttons)
        val confirmScroll = ScrollView(context).apply { addView(confirmBox) }
        confirm.addView(confirmScroll, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(16), dp(4), dp(16), dp(4)) })
        pages.addView(confirm, FrameLayout.LayoutParams(-1, -1))
        refresh()
    }

    internal fun showTidy(fixes: List<TidyFix>, apply: (List<TidyFix>) -> Unit) {
        review?.let { pages.removeView(it) }
        val palette = palette ?: Palette.of(context, settings)
        val content = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundColor(palette.background) }
        val checks = fixes.map { fix -> android.widget.CheckBox(context).apply {
            text = "${fix.original} \u2192 ${fix.replacement}"; isChecked = true; setTextColor(palette.text)
            content.addView(this, LayoutParams(-1, -2))
        } }
        content.addView(TextView(context).apply {
            text = if (fixes.isEmpty()) "No fixes found. Tap to return." else "Apply selected fixes"
            setTextColor(palette.smallAccent); setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener {
                apply(fixes.filterIndexed { index, _ -> checks[index].isChecked })
                review?.let { pages.removeView(it) }; review = null
            }
        })
        review = ScrollView(context).apply { addView(content) }
        pages.addView(review, FrameLayout.LayoutParams(-1, -1))
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        setStroke(dp(1), palette?.keyOutline ?: 0)
    }

    fun updateStripHeight() {
        val h = max(dp(if (measuredWidth / d >= 600) 56 else 48), stripHeight())
        if (header.layoutParams.height != h) header.layoutParams = header.layoutParams.apply { height = h }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec) / d
        val h = View.MeasureSpec.getSize(heightMeasureSpec) / d
        val tablet = w >= 600
        header.layoutParams.height = max(dp(if (tablet) 56 else 48), stripHeight())
        val minimumTile = max(60, kotlin.math.ceil(28 + 32 * resources.displayMetrics.scaledDensity / d).toInt())
        val geometry = QuickPanelGeometry.fit(w.toInt(), h.toInt(), kotlin.math.ceil(header.layoutParams.height / d).toInt(), minimumTile)
        val rowHeight = geometry.strip
        val nextWidth = if (tablet) 188 else if (w < 340) 108 else 124
        val nextHeight = rowHeight
        val nextColumns = if (tablet) 4 else if (w < 340) 2 else 3
        val nextGridHeight = if (tablet) 162 else 112
        val tileHeight = geometry.tile
        stripViews.values.forEach { it.sizeFor(tablet) }
        gridViews.values.forEach { it.sizeFor(tablet) }
        if (stripRow.layoutParams.height != dp(rowHeight)) stripRow.layoutParams.height = dp(rowHeight)
        val compactStrip = rowHeight < 100
        actions.orientation = if (compactStrip) HORIZONTAL else VERTICAL
        actions.layoutParams.width = dp(if (compactStrip) 104 else if (tablet) 96 else 58)
        for (b in listOf(editButton, newButton)) {
            val params = b.layoutParams as LayoutParams
            val actionWidth = if (compactStrip) 0 else -1
            val actionHeight = if (compactStrip) -1 else 0
            if (params.width != actionWidth || params.height != actionHeight) b.layoutParams = LayoutParams(actionWidth, actionHeight, 1f)
        }
        val gap = geometry.gap
        (tileScroll.layoutParams as LayoutParams).topMargin = dp(gap)
        val narrow = w < 260
        if (narrowGrid != narrow) {
            narrowGrid = narrow
            tileGrid.removeAllViews()
            tiles.forEach { (it.box.parent as? android.view.ViewGroup)?.removeView(it.box) }
            val rows = if (narrow) listOf(listOf(0, 1), listOf(2), listOf(3, 4), listOf(5, 6), listOf(7, 8))
                else listOf(listOf(0, 1, 2), listOf(3, 4, 5, 6), listOf(7, 8))
            rows.forEach { indices ->
                val row = LinearLayout(context)
                tileGrid.addView(row, LayoutParams(-1, -2))
                indices.forEach { index -> row.addView(tiles[index].box, LayoutParams(0, dp(tileHeight), if (index == 2) 2f else 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
            }
        }
        tiles.forEach { if (it.box.layoutParams.height != dp(tileHeight)) it.box.layoutParams.height = dp(tileHeight) }
        tileScroll.layoutParams.height = dp(geometry.viewport)
        main.setPadding(0, dp(geometry.top), 0, 0)
        if (cardWidth != nextWidth || cardHeight != nextHeight) {
            cardWidth = nextWidth; cardHeight = nextHeight
            stripViews.values.forEach { it.box.layoutParams = stripParams() }
        }
        if (columns != nextColumns || gridCardHeight != nextGridHeight) {
            columns = nextColumns; gridCardHeight = nextGridHeight
            rebuildGrid()
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun stripParams() = LayoutParams(dp(cardWidth), dp(cardHeight)).apply { marginEnd = dp(8); gravity = Gravity.CENTER_VERTICAL }
    private fun rebuildGrid() {
        if (columns == 0) return
        allGrid.removeAllViews()
        var row: LinearLayout? = null
        val views = library.entries().mapNotNull { gridViews[it.key]?.box } + newLayout
        views.forEachIndexed { index, view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            if (index % columns == 0) row = LinearLayout(context).also { allGrid.addView(it, LayoutParams(-1, -2)) }
            row!!.addView(view, LayoutParams(0, dp(gridCardHeight), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        repeat((columns - views.size % columns) % columns) { row?.addView(View(context), LayoutParams(0, 1, 1f)) }
        (paste.parent as? android.view.ViewGroup)?.removeView(paste)
        allGrid.addView(paste, LayoutParams(-1, dp(48)))
    }

    fun refresh(resetScroll: Boolean = false) {
        updateStripHeight()
        if (resetScroll) {
            review?.let { pages.removeView(it) }; review = null
            showAll(false, animate = false); hideConfirm(); message.visibility = View.GONE
            tileScroll.scrollTo(0, 0)
            mainScroll.scrollTo(0, 0)
            // Done can have just added a draft. Reveal it after its first measure, before drawing.
            viewTreeObserver.removeOnPreDrawListener(revealInUse)
            viewTreeObserver.addOnPreDrawListener(revealInUse)
        }
        val l = landscape()
        val next = QuickOptionsState(settings.theme(), resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
            settings.int(Prefs.CORNER_RADIUS), settings.split(l), settings.quickOneHand(l), settings.int(Prefs.height(l)),
            settings.currentLanguage(), settings.languages().size > 1, settings.bool(Prefs.SWIPE_TYPING), settings.bool(Prefs.LEARNING), settings.themeId() + settings.prefs.getString(Prefs.THEMES_CUSTOM, ""),
            settings.customThemes().firstOrNull { it.selection == settings.themeId() }?.name ?: settings.theme().title)
        val entries = library.entries()
        val geometry = "${settings.profile(l)}|${settings.int(Prefs.CURVE)}|${settings.int(Prefs.KEY_GAP)}|${settings.bool(Prefs.SAME_ORIENTATIONS)}|${resources.displayMetrics}"
        if (rendered == next && renderedEntries == entries && renderedSelection == library.inUse && renderedGeometry == geometry) return
        val p = Palette.of(context, settings); palette = p
        val ink = p.smallAccent
        setBackgroundColor(p.background)
        for (b in listOf(back, trailing, paste)) b.setTextColor(ink)
        editButton.setTextColor(p.text)
        editButton.compoundDrawables.filterNotNull().forEach { it.setTint(p.text) }
        for (b in listOf(editButton, newButton, cancel)) b.background = rounded(p.key, next.radius * d)
        heading.setTextColor(p.text); message.setTextColor(p.text)
        confirm.setBackgroundColor(0x66000000)
        confirmBox.background = rounded(p.key, next.radius * d)
        confirmTitle.setTextColor(p.text); confirmText.setTextColor(p.hint); cancel.setTextColor(p.text)
        saveContinue.setTextColor(p.onAccent); saveContinue.background = rounded(p.accent, next.radius * d)
        newLayout.setTextColor(p.text)
        newLayout.background = GradientDrawable().apply { setColor(p.background); cornerRadius = next.radius * d; setStroke(dp(2), p.hint, dp(5).toFloat(), dp(3).toFloat()) }
        if (renderedEntries != entries) {
            val keys = entries.map { it.key }.toSet()
            for (key in stripViews.keys.toList()) if (key !in keys) { stripCards.removeView(stripViews.remove(key)!!.box); gridViews.remove(key) }
            for (entry in entries) {
                if (entry.key !in stripViews) {
                    val card = LayoutCard(entry, false); stripViews[entry.key] = card; stripCards.addView(card.box, stripParams())
                    gridViews[entry.key] = LayoutCard(entry, true)
                }
            }
            stripCards.removeAllViews()
            entries.forEach { stripCards.addView(stripViews.getValue(it.key).box, stripParams()) }
            rebuildGrid()
        }
        library.quickTiles().forEach { state ->
            stripViews[state.entry.key]?.bind(state, p, next.radius * d)
            gridViews[state.entry.key]?.bind(state, p, next.radius * d)
        }
        val current = entries.firstOrNull { it.key == library.inUse } ?: entries.first()
        newButton.setTextColor(if (current.draft) ink else p.text)
        editButton.contentDescription = "Edit ${current.name}"
        newButton.text = if (current.draft) "Save" else "New"
        val style = "${current.draft}|${p.accent}"
        if (style != renderedStyles) {
            newButton.setCompoundDrawablesWithIntrinsicBounds(0, if (current.draft) R.drawable.ic_settings_save else R.drawable.ic_settings_add, 0, 0)
            newButton.compoundDrawables.filterNotNull().forEach { it.setTint(if (current.draft) ink else p.text) }
            renderedStyles = style
        }
        newButton.contentDescription = if (current.draft) "Save Custom as ${library.nextName("Custom")}" else "New custom layout"
        val states = next.tiles()
        for ((index, t) in tiles.withIndex()) {
            val value = states[index]
            if (t.rendered != value) {
                t.labelView.text = value.label; t.stateView.text = value.state; t.glyphView.text = value.glyph
                t.box.contentDescription = value.description ?: value.label
                t.box.isEnabled = value.enabled; t.box.alpha = if (value.enabled) 1f else .45f
                t.rendered = value
            }
            t.background.setColor(p.key); t.background.cornerRadius = next.radius * d
            t.background.setStroke(dp(1), p.keyOutline)
            val color = if (value.on) ink else p.text
            t.iconView.setColorFilter(color); t.glyphView.setTextColor(color); t.labelView.setTextColor(color)
            t.stateView.setTextColor(if (value.on) ink else p.hint)
        }
        smaller.contentDescription = "Size, decrease, ${next.height} percent"
        larger.contentDescription = "Size, increase, ${next.height} percent"
        smaller.setTextColor(p.text); larger.setTextColor(p.text)
        smaller.isEnabled = next.height > Prefs.height(l).min; larger.isEnabled = next.height < Prefs.height(l).max
        rendered = next; renderedEntries = entries; renderedSelection = library.inUse; renderedGeometry = geometry
    }

    private fun scrollInUse() {
        stripViews[library.inUse]?.box?.let { strip.requestChildRectangleOnScreen(it, android.graphics.Rect(0, 0, it.width + dp(12), it.height), true) }
    }

    private fun showAll(all: Boolean, animate: Boolean = true) {
        if (showingAll == all) return
        showingAll = all
        val incoming = if (all) allScroll else mainScroll
        val outgoing = if (all) mainScroll else allScroll
        outgoing.animate().cancel(); incoming.animate().cancel()
        outgoing.visibility = View.INVISIBLE; outgoing.alpha = 1f; outgoing.translationX = 0f
        incoming.visibility = View.VISIBLE
        heading.text = if (all) "Layouts" else ""
        trailing.text = if (all) "Edit" else "All layouts \u203a"
        back.contentDescription = if (all) "Back to Quick options" else "Back to keyboard"
        if (animate && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            incoming.alpha = 0f; incoming.translationX = if (reducedMotion()) 0f else dp(if (all) 16 else -16).toFloat()
            incoming.animate().alpha(1f).translationX(0f).setDuration(if (reducedMotion()) 100 else if (all) 200 else 160)
                .setInterpolator(android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start()
        }
        if (animate) back.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    private fun reducedMotion(): Boolean = android.provider.Settings.Global.getFloat(context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) < 1f

    fun animateOpen() {
        animate().cancel(); alpha = 1f; translationY = 0f
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return
        alpha = 0f; translationY = if (reducedMotion()) 0f else dp(8).toFloat()
        animate().alpha(1f).translationY(0f).setStartDelay(if (reducedMotion()) 0 else 40).setDuration(if (reducedMotion()) 100 else 160)
            .setInterpolator(android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)).start()
    }

    private fun edit(newCopy: Boolean) {
        val current = library.entries().firstOrNull { it.key == library.inUse } ?: return
        if ((newCopy || !current.custom) && library.requestNew() == NewLayoutFlow.SAVE_FIRST) {
            pendingCopy = current to newCopy
            confirm.visibility = View.VISIBLE
            header.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            main.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            allScroll.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            cancel.requestFocus(); confirmTitle.announceForAccessibility(confirmTitle.text)
        } else openEditor(current, newCopy)
    }

    private fun hideConfirm() {
        confirm.visibility = View.GONE; pendingCopy = null
        header.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        main.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        allScroll.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }

    private fun continueEdit() {
        val (source, copy) = pendingCopy ?: return
        library.saveDraft(library.nextName("Custom"))
        hideConfirm()
        openEditor(source, copy)
    }

    private fun openEditor(source: LayoutEntry, copy: Boolean) {
        val entry = library.quickEdit(source, copy)
        val title = if (!source.custom) "${source.name} is a preset; editing a copy named Custom"
            else if (copy) "New layout, copied from ${source.name}" else "Editing ${source.name}"
        rearrange(entry.key, title)
    }

    private fun pasteCode() {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
        val result = library.importCode(text?.takeIf { it.length <= 128 * 1024 }?.toString()
            ?: if (text == null) null else "invalid")
        message.text = when (result) {
            ClipboardLayoutResult.Empty -> "No layout code on the clipboard"
            ClipboardLayoutResult.Invalid -> "This code isn't a TailorKeys layout"
            is ClipboardLayoutResult.Added -> "Added ${result.entry.name}"
        }
        message.visibility = View.VISIBLE
        refresh()
    }
}
