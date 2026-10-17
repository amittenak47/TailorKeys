package app.tailorkeys

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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

/**
 * Compact quick options over the keys: a 4 × 2 grid of key-styled tiles (icon, label, state) that fits the keyboard
 * without scrolling; a scroll view remains only as a fallback for very small keyboards.
 */
class QuickOptions(
    context: Context,
    private val settings: KeyboardSettings,
    private val landscape: () -> Boolean,
    private val stripHeight: () -> Int,
    private val close: () -> Unit,
    private val rearrange: () -> Unit,
    private val language: () -> Unit,
    private val allSettings: () -> Unit,
) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(n: Int) = (n * d).toInt()
    private val header = LinearLayout(context)
    private val closeButton = TextView(context)
    private val scroll = ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
    private var rendered: QuickOptionsState? = null
    private var palette: Palette? = null

    /** One grid cell: icon over label over a muted state line. */
    private inner class Tile(val icon: Int?, val glyph: String?, val label: String) {
        val box = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true }
        val iconView = ImageView(context)
        val glyphView = TextView(context).apply { textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
        val labelView = TextView(context).apply { textSize = 12f; gravity = Gravity.CENTER; maxLines = 1 }
        val stateView = TextView(context).apply { textSize = 11f; gravity = Gravity.CENTER; maxLines = 1 }
        val tileBackground = GradientDrawable()
        var rendered: QuickTileState? = null
        init {
            box.background = tileBackground
            if (icon != null) iconView.setImageResource(icon)
            if (icon != null) box.addView(iconView, LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(2) })
            else box.addView(glyphView, LayoutParams(LayoutParams.MATCH_PARENT, dp(20)).apply { bottomMargin = dp(2) })
            labelView.text = label
            // A fixed width lets TextView replace a single line without relaying out the grid.
            box.addView(labelView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            box.addView(stateView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private val tiles = ArrayList<Tile>()
    private val size: Tile
    private val smaller = TextView(context)
    private val larger = TextView(context)

    init {
        orientation = VERTICAL
        isClickable = true
        // The strip holds just a back button; the tiles below say what this is.
        closeButton.text = "‹  Back"
        closeButton.textSize = 15f
        closeButton.setTypeface(closeButton.typeface, Typeface.BOLD)
        closeButton.gravity = Gravity.CENTER_VERTICAL
        closeButton.setPadding(dp(14), 0, dp(14), 0)
        closeButton.contentDescription = "Back to keyboard"
        closeButton.setOnClickListener { close() }
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(closeButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, stripHeight()))
        // Centred vertically in whatever height the keyboard has, instead of leaving a gap at the bottom.
        val grid = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(2), dp(6), dp(6)) }
        scroll.addView(grid)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        fun row(): LinearLayout = LinearLayout(context).also {
            grid.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        fun tile(parent: LinearLayout, icon: Int?, glyph: String?, label: String, action: (() -> Unit)?): Tile =
            Tile(icon, glyph, label).also { t ->
                t.glyphView.text = glyph
                parent.addView(t.box, LayoutParams(0, dp(64), 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                if (action != null) t.box.setOnClickListener { action(); refresh() }
                tiles += t
            }
        val first = row()
        tile(first, R.drawable.ic_settings_edit, null, "Rearrange") { rearrange() }
        tile(first, R.drawable.ic_settings_layout, null, "Split") { val l = landscape(); settings.setSplitFor(l, !settings.split(l)) }
        tile(first, R.drawable.ic_settings_expand, null, "One hand") { settings.cycleQuickOneHand(landscape()) }
        size = tile(first, R.drawable.ic_settings_size, null, "Size", null)
        // Size keeps its − / + as two halves of its state line.
        size.box.removeView(size.stateView)
        val steps = LinearLayout(context).apply { gravity = Gravity.CENTER }
        for ((view, text, delta) in listOf(Triple(smaller, "−", -2), Triple(larger, "+", 2))) {
            view.text = text
            view.textSize = 18f
            view.gravity = Gravity.CENTER
            view.setOnClickListener { settings.adjustQuickHeight(landscape(), delta); refresh() }
            steps.addView(view, LayoutParams(dp(36), dp(24)))
        }
        size.box.addView(steps)
        val second = row()
        tile(second, R.drawable.ic_settings_look, null, "Theme") { settings.cycleQuickTheme() }
        tile(second, null, "", "Language") { language() }
        tile(second, R.drawable.ic_settings_typing, null, "Swipe") { settings.set(Prefs.SWIPE_TYPING, !settings.bool(Prefs.SWIPE_TYPING)) }
        tile(second, null, "⚙", "Settings") { allSettings() }
        refresh()
    }

    /** Called after measuring the keyboard, including while this attached panel is invisible. */
    fun updateStripHeight() {
        val height = stripHeight()
        if (header.layoutParams.height != height) {
            header.layoutParams = header.layoutParams.apply { this.height = height }
        }
    }

    fun refresh(resetScroll: Boolean = false) {
        if (resetScroll) scroll.scrollTo(0, 0)
        updateStripHeight()
        val l = landscape()
        val next = QuickOptionsState(settings.theme(),
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
            settings.int(Prefs.CORNER_RADIUS), settings.split(l), settings.quickOneHand(l),
            settings.int(Prefs.height(l)), settings.currentLanguage(), settings.languages().size > 1,
            settings.bool(Prefs.SWIPE_TYPING))
        val previous = rendered
        if (next == previous) return
        val paletteChanged = previous == null || previous.theme != next.theme || previous.nightMode != next.nightMode
        val radiusChanged = previous == null || previous.radius != next.radius
        if (paletteChanged) {
            palette = Palette.of(context, next.theme)
            setBackgroundColor(palette!!.background)
            closeButton.setTextColor(palette!!.accent)
        }
        val p = palette!!
        val states = next.tiles()
        for ((index, t) in tiles.withIndex()) {
            if (paletteChanged) t.tileBackground.setColor(p.key)
            if (radiusChanged) t.tileBackground.cornerRadius = next.radius * d
            val value = states[index]
            val old = t.rendered
            if (value != old) {
                if (value.label != old?.label) t.labelView.text = value.label
                if (value.state != old?.state) t.stateView.text = value.state
                if (value.glyph != old?.glyph) t.glyphView.text = value.glyph
                if (value.description != old?.description) t.box.contentDescription = value.description
                if (value.enabled != old?.enabled) {
                    t.box.isEnabled = value.enabled
                    t.box.alpha = if (value.enabled) 1f else 0.45f
                }
                t.rendered = value
            }
            if (paletteChanged || value.on != old?.on) {
                val ink = if (value.on) p.accent else p.text
                if (t.icon != null) t.iconView.setColorFilter(ink)
                t.glyphView.setTextColor(ink)
                t.labelView.setTextColor(ink)
                t.stateView.setTextColor(if (value.on) p.accent else p.hint)
            }
        }
        if (previous == null || previous.height != next.height) {
            smaller.contentDescription = "Size, decrease, ${next.height} percent"
            larger.contentDescription = "Size, increase, ${next.height} percent"
        }
        fun step(view: TextView, enabled: Boolean) {
            if (paletteChanged) view.setTextColor(p.text)
            if (previous == null || view.isEnabled != enabled) {
                view.isEnabled = enabled
                view.alpha = if (enabled) 1f else 0.35f
            }
        }
        step(smaller, next.height > Prefs.height(l).min)
        step(larger, next.height < Prefs.height(l).max)
        rendered = next
    }
}
