package app.tailorkeys

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
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

    fun displace(dx: Float, now: Long): Int {
        var steps = advance(now)
        val magnitude = abs(dx.toDouble())
        val next = if (magnitude <= 6) 0.0 else min(45.0, 4.0 + 1.2 * (magnitude - 6)) * if (dx < 0) -1 else 1
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

/** Native, scrollable tiles keep the full panel reachable even at the minimum keyboard height. */
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
    private val title = TextView(context)
    private val header = LinearLayout(context)
    private val closeButton = Button(context)
    private val buttons = ArrayList<Button>()
    private val split: Button
    private val oneHand: Button
    private val sizeLabel = TextView(context)
    private val smaller: Button
    private val larger: Button
    private val theme: Button
    private val lang: Button
    private val swipe: Button

    init {
        orientation = VERTICAL
        isClickable = true
        title.text = "Quick options"
        title.textSize = 16f
        title.gravity = Gravity.CENTER_VERTICAL
        title.setPadding(dp(12), 0, 0, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) title.isAccessibilityHeading = true
        closeButton.text = "✕"
        closeButton.contentDescription = "Close quick options"
        closeButton.setOnClickListener { close() }
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(title, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        header.addView(closeButton, LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, stripHeight()))
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val rows = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(6)) }
        scroll.addView(rows)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        fun row(): LinearLayout = LinearLayout(context).also {
            rows.addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        fun tile(parent: LinearLayout, label: String, action: () -> Unit): Button = Button(context).also {
            it.text = label
            it.textSize = 14f
            it.isAllCaps = false
            it.minimumHeight = dp(56)
            it.minHeight = dp(56)
            it.setPadding(dp(4), dp(4), dp(4), dp(4))
            parent.addView(it, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
            it.setOnClickListener { action(); refresh() }
            buttons += it
        }
        val first = row()
        tile(first, "↔  Rearrange") { rearrange() }
        split = tile(first, "") { val l = landscape(); settings.setSplitFor(l, !settings.split(l)) }
        val second = row()
        oneHand = tile(second, "") { settings.cycleQuickOneHand(landscape()) }
        val size = LinearLayout(context).apply { orientation = VERTICAL }
        second.addView(size, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        sizeLabel.gravity = Gravity.CENTER
        sizeLabel.textSize = 13f
        size.addView(sizeLabel)
        val sizeButtons = LinearLayout(context)
        size.addView(sizeButtons)
        smaller = tile(sizeButtons, "−") { settings.adjustQuickHeight(landscape(), -2) }
        larger = tile(sizeButtons, "+") { settings.adjustQuickHeight(landscape(), 2) }
        val third = row()
        theme = tile(third, "") { settings.cycleQuickTheme() }
        lang = tile(third, "") { language() }
        val fourth = row()
        swipe = tile(fourth, "") { settings.set(Prefs.SWIPE_TYPING, !settings.bool(Prefs.SWIPE_TYPING)) }
        tile(fourth, "⚙  All settings") { allSettings() }
        refresh()
    }

    fun refresh() {
        val l = landscape()
        val p = Palette.of(context, settings.theme())
        setBackgroundColor(p.background)
        title.setTextColor(p.text)
        closeButton.setTextColor(p.accent)
        closeButton.background = GradientDrawable().apply { setColor(p.background) }
        header.layoutParams = header.layoutParams.apply { height = stripHeight() }
        fun state(button: Button, icon: String, label: String, value: String, on: Boolean = false) {
            button.text = "$icon  $label\n$value"
            button.contentDescription = "$label, $value"
            button.isSelected = on
        }
        state(split, "◫", "Split", if (settings.split(l)) "On" else "Off", settings.split(l))
        val hand = when (settings.quickOneHand(l)) { Align.CENTER -> "Off"; Align.LEFT -> "Left"; Align.RIGHT -> "Right" }
        state(oneHand, "☝", "One hand", hand, hand != "Off")
        val height = settings.int(Prefs.height(l))
        sizeLabel.text = "↕  Size $height%"
        sizeLabel.setTextColor(p.text)
        smaller.contentDescription = "Size, decrease, $height percent"
        larger.contentDescription = "Size, increase, $height percent"
        smaller.isEnabled = height > Prefs.height(l).min
        larger.isEnabled = height < Prefs.height(l).max
        state(theme, "◐", "Theme", if (settings.theme() == ThemeName.SYSTEM) "System" else settings.theme().title)
        state(lang, "◎", "Language", settings.currentLanguage().uppercase())
        lang.isEnabled = settings.languages().size > 1
        state(swipe, "〰", "Swipe typing", if (settings.bool(Prefs.SWIPE_TYPING)) "On" else "Off", settings.bool(Prefs.SWIPE_TYPING))
        for (b in buttons) {
            b.alpha = if (b.isEnabled) 1f else 0.4f
            b.setTextColor(if (b.isSelected) p.onAccent else p.text)
            b.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (b.isSelected) p.accent else p.key)
                setStroke(dp(1).coerceAtLeast(1), if (b.isSelected) p.accent else p.hint)
            }
        }
    }
}
