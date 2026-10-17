package app.tailorkeys

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

internal data class WheelTapTarget(val index: Int, val center: Float, val width: Float)

/** Only words intersecting the wheel's clip are tappable; gaps choose the nearest visible centre. */
internal fun wheelTapIndex(targets: List<WheelTapTarget>, x: Float, left: Float, right: Float): Int {
    val visible = targets.filter { it.center + it.width / 2 > left && it.center - it.width / 2 < right }
    return (visible.firstOrNull { abs(x - it.center) <= it.width / 2 }
        ?: visible.minByOrNull { abs(x - it.center) } ?: targets.first()).index
}

/** Time is monotonic milliseconds, displacement is in dp from the fixed press origin. */
internal class CursorJoystick {
    var speed = 0.0
        private set
    private var fraction = 0.0
    private var lastTime: Long? = null

    private var smoothed = 0.0

    /**
     * [dx] is the finger's distance from where it pressed, in dp. Speed follows a gentle curve: about 2 chars/s just
     * outside the 8 dp dead zone (precise), ~8 at 58 dp, up to 28 at 108 dp and beyond. The distance is smoothed so
     * finger jitter doesn't make the speed jump.
     */
    fun displace(dx: Float, now: Long): Int {
        var steps = advance(now)
        smoothed += (dx - smoothed) * 0.35
        val magnitude = abs(smoothed)
        val u = ((magnitude - 8) / 100).coerceIn(0.0, 1.0)
        val next = if (magnitude <= 8) 0.0 else (2.0 + 26.0 * u * u) * if (smoothed < 0) -1 else 1
        if (next == 0.0 || next * speed < 0) fraction = 0.0
        if (next != 0.0 && speed == 0.0) steps += if (next < 0) -1 else 1
        speed = next
        return steps
    }

    fun advance(now: Long): Int {
        val previous = lastTime
        lastTime = now
        if (previous == null) return 0
        fraction += speed * (now - previous).coerceAtLeast(0) / 1000.0
        // Avoid losing a whole character to floating-point error at an exact boundary.
        val steps = (fraction + if (fraction > 0) 1e-9 else -1e-9).toInt()
        fraction -= steps
        return steps
    }
}

internal class GapPresses {
    private var liftedAt: Long? = null
    private var x = 0f
    private var y = 0f
    fun isSelection(now: Long, x: Float, y: Float): Boolean =
        liftedAt?.let { now - it in 0..350 && hypot(x - this.x, y - this.y) <= 40 } ?: false
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

    /** One grid cell: icon over label over a muted state line. */
    private inner class Tile(val icon: Int?, val glyph: String?, val label: String) {
        val box = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true }
        val iconView = ImageView(context)
        val glyphView = TextView(context).apply { textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD) }
        val labelView = TextView(context).apply { textSize = 12f; gravity = Gravity.CENTER; maxLines = 1 }
        val stateView = TextView(context).apply { textSize = 11f; gravity = Gravity.CENTER; maxLines = 1 }
        var on = false
        init {
            if (icon != null) box.addView(iconView, LayoutParams(dp(20), dp(20)).apply { bottomMargin = dp(2) })
            else box.addView(glyphView, LayoutParams(LayoutParams.WRAP_CONTENT, dp(20)).apply { bottomMargin = dp(2) })
            labelView.text = label
            box.addView(labelView)
            box.addView(stateView)
        }
    }

    private val tiles = ArrayList<Tile>()
    private val split: Tile
    private val oneHand: Tile
    private val size: Tile
    private val smaller = TextView(context)
    private val larger = TextView(context)
    private val theme: Tile
    private val lang: Tile
    private val swipe: Tile

    init {
        orientation = VERTICAL
        isClickable = true
        // The strip holds just a back button; the tiles below say what this is.
        closeButton.text = "‹  Back"
        closeButton.textSize = 15f
        closeButton.gravity = Gravity.CENTER_VERTICAL
        closeButton.setPadding(dp(14), 0, dp(14), 0)
        closeButton.contentDescription = "Back to keyboard"
        closeButton.setOnClickListener { close() }
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(closeButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, stripHeight()))
        val scroll = ScrollView(context).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
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
        split = tile(first, R.drawable.ic_settings_layout, null, "Split") { val l = landscape(); settings.setSplitFor(l, !settings.split(l)) }
        oneHand = tile(first, R.drawable.ic_settings_expand, null, "One hand") { settings.cycleQuickOneHand(landscape()) }
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
        theme = tile(second, R.drawable.ic_settings_look, null, "Theme") { settings.cycleQuickTheme() }
        lang = tile(second, null, "", "Language") { language() }
        swipe = tile(second, R.drawable.ic_settings_typing, null, "Swipe") { settings.set(Prefs.SWIPE_TYPING, !settings.bool(Prefs.SWIPE_TYPING)) }
        tile(second, null, "⚙", "Settings") { allSettings() }
        refresh()
    }

    fun refresh() {
        val l = landscape()
        val p = Palette.of(context, settings.theme())
        val radius = settings.int(Prefs.CORNER_RADIUS) * d
        setBackgroundColor(p.background)
        closeButton.setTextColor(p.accent)
        closeButton.setTypeface(closeButton.typeface, Typeface.BOLD)
        header.layoutParams = header.layoutParams.apply { height = stripHeight() }
        fun state(tile: Tile, value: String, on: Boolean = false, enabled: Boolean = true) {
            tile.stateView.text = value
            tile.on = on
            tile.box.isEnabled = enabled
            tile.box.contentDescription = "${tile.labelView.text}, $value"
        }
        state(split, if (settings.split(l)) "On" else "Off", settings.split(l))
        val hand = when (settings.quickOneHand(l)) { Align.CENTER -> "Off"; Align.LEFT -> "Left"; Align.RIGHT -> "Right" }
        state(oneHand, hand, hand != "Off")
        val height = settings.int(Prefs.height(l))
        size.labelView.text = "Size $height%"
        size.box.contentDescription = "Size, $height percent"
        smaller.contentDescription = "Size, decrease, $height percent"
        larger.contentDescription = "Size, increase, $height percent"
        smaller.isEnabled = height > Prefs.height(l).min
        larger.isEnabled = height < Prefs.height(l).max
        state(theme, if (settings.theme() == ThemeName.SYSTEM) "System" else settings.theme().title)
        lang.glyphView.text = settings.currentLanguage().uppercase()
        state(lang, if (settings.languages().size > 1) "Tap to switch" else "Only one", enabled = settings.languages().size > 1)
        state(swipe, if (settings.bool(Prefs.SWIPE_TYPING)) "On" else "Off", settings.bool(Prefs.SWIPE_TYPING))
        tiles.last().stateView.text = "All"
        for (t in tiles) {
            t.box.alpha = if (t.box.isEnabled) 1f else 0.45f
            t.box.background = GradientDrawable().apply { cornerRadius = radius; setColor(p.key) }
            val ink = if (t.on) p.accent else p.text
            t.iconView.setImageResource(t.icon ?: 0)
            t.iconView.setColorFilter(ink)
            t.glyphView.setTextColor(ink)
            t.labelView.setTextColor(ink)
            t.stateView.setTextColor(if (t.on) p.accent else p.hint)
        }
        for (v in listOf(smaller, larger)) { v.setTextColor(p.text); v.alpha = if (v.isEnabled) 1f else 0.35f }
    }
}
