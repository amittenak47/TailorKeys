package app.tailorkeys

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Small view helpers shared by the settings and guide screens: spacing, rounded shapes, buttons and tabs. */

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.dpf(v: Float) = v * resources.displayMetrics.density

fun Context.rounded(color: Int, radiusDp: Float): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = dpf(radiusDp) }

/** Adds a touch ripple on top of [shape]. */
fun Context.rippled(shape: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(0x28808080), shape, null)

val mediumFont: Typeface get() = Typeface.create("sans-serif-medium", Typeface.NORMAL)

/** A rounded button: accent-filled, or a quiet track-colored one. */
fun Context.pillButton(label: String, filled: Boolean = true, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 16f
    typeface = mediumFont
    gravity = Gravity.CENTER
    minHeight = dp(50)
    setPadding(dp(20), 0, dp(20), 0)
    setTextColor(getColor(if (filled) R.color.on_accent else R.color.ink))
    background = rippled(rounded(getColor(if (filled) R.color.accent else R.color.track), 14f))
    setOnClickListener { onClick() }
}

/** A row of labels where one is selected, like a segmented control. [select] moves the highlight without calling back. */
class Segmented(private val context: Context, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    private val items = ArrayList<TextView>()
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = context.rounded(context.getColor(R.color.track), 12f)
        setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
    }

    init {
        labels.forEachIndexed { i, label ->
            val t = TextView(context).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                setOnClickListener { onSelect(i) }
            }
            items += t
            view.addView(t, LinearLayout.LayoutParams(0, context.dp(34), 1f))
        }
        select(selected)
    }

    fun select(index: Int) {
        items.forEachIndexed { i, t ->
            val on = i == index
            t.typeface = if (on) mediumFont else Typeface.DEFAULT
            t.setTextColor(context.getColor(if (on) R.color.accent else R.color.ink2))
            t.background = if (on) context.rounded(context.getColor(R.color.pill), 9f) else null
        }
    }
}

/**
 * Shows [content] as a scaled-down picture: it is laid out 1/[scale] times wider than the box and shrunk to fit,
 * so a whole keyboard fits an illustration slot. Swallows touches, so the picture is not interactive.
 */
class MiniatureBox(context: Context, private val content: View, private val scale: Float, private val maxHeight: Int) : FrameLayout(context) {
    init {
        clipChildren = false
        addView(content, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.pivotX = 0f
        content.pivotY = 0f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        fun measureAt(s: Float): Int {
            content.measure(MeasureSpec.makeMeasureSpec((w / s).toInt(), MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
            return content.measuredHeight
        }
        var s = scale
        var h = measureAt(s)
        if (h * s > maxHeight) { s = maxHeight.toFloat() / h * s; h = measureAt(s) }
        content.scaleX = s
        content.scaleY = s
        setMeasuredDimension(w, (h * s).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
    }

    override fun onInterceptTouchEvent(e: MotionEvent) = true
}

/** Simple drawn pictures for the guide slides that don't show a keyboard. */
class Illustration(context: Context, private val kind: Kind) : View(context) {
    enum class Kind { TOGGLES, LIST, FIELD }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val accent = context.getColor(R.color.accent)
    private val bg = context.getColor(R.color.bg)
    private val line = context.getColor(R.color.divider)
    private val ink = context.getColor(R.color.ink2)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(190))
    }

    private fun box(l: Float, t: Float, rt: Float, b: Float, radius: Float, color: Int, c: Canvas) {
        paint.color = color
        r.set(l, t, rt, b)
        c.drawRoundRect(r, radius, radius, paint)
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val pw = minOf(w - 48 * d, 360 * d)
        val left = (w - pw) / 2
        val right = left + pw
        when (kind) {
            Kind.TOGGLES, Kind.LIST -> {
                val rowH = 44 * d
                val gap = 12 * d
                val top = (h - (3 * rowH + 2 * gap)) / 2
                for (i in 0 until 3) {
                    val y = top + i * (rowH + gap)
                    val hot = i == 1
                    box(left, y, right, y + rowH, 14 * d, bg, c)
                    box(left + 14 * d, y + rowH / 2 - 9 * d, left + 32 * d, y + rowH / 2 + 9 * d, 5 * d, if (hot) accent else line, c)
                    box(left + 44 * d, y + rowH / 2 - 4 * d, left + 44 * d + (if (hot) 96 else 70 + i * 24) * d, y + rowH / 2 + 4 * d, 4 * d, if (hot) ink else line, c)
                    if (kind == Kind.TOGGLES) {
                        val tl = right - 58 * d
                        box(tl, y + rowH / 2 - 11 * d, tl + 42 * d, y + rowH / 2 + 11 * d, 11 * d, if (hot) accent else line, c)
                        paint.color = if (hot) context.getColor(R.color.on_accent) else context.getColor(R.color.surface)
                        c.drawCircle(if (hot) tl + 42 * d - 11 * d else tl + 11 * d, y + rowH / 2, 8 * d, paint)
                    } else if (hot) {
                        paint.color = accent
                        c.drawCircle(right - 28 * d, y + rowH / 2, 10 * d, paint)
                        paint.color = context.getColor(R.color.on_accent)
                        paint.strokeWidth = 2.2f * d
                        paint.strokeCap = Paint.Cap.ROUND
                        c.drawLine(right - 33 * d, y + rowH / 2, right - 29 * d, y + rowH / 2 + 4 * d, paint)
                        c.drawLine(right - 29 * d, y + rowH / 2 + 4 * d, right - 23 * d, y + rowH / 2 - 4 * d, paint)
                    }
                }
            }
            Kind.FIELD -> {
                val fieldH = 56 * d
                val keyH = 22 * d
                val kg = 6 * d
                val total = fieldH + 18 * d + 3 * keyH + 2 * kg
                val top = (h - total) / 2
                box(left, top, right, top + fieldH, 14 * d, bg, c)
                box(left + 16 * d, top + fieldH / 2 - 4 * d, left + 120 * d, top + fieldH / 2 + 4 * d, 4 * d, line, c)
                box(left + 126 * d, top + fieldH / 2 - 12 * d, left + 129 * d, top + fieldH / 2 + 12 * d, 1.5f * d, accent, c)
                val kw = (pw - 7 * kg) / 8
                for (row in 0 until 3) for (col in 0 until 8) {
                    val x = left + col * (kw + kg)
                    val y = top + fieldH + 18 * d + row * (keyH + kg)
                    box(x, y, x + kw, y + keyH, 6 * d, if (row == 1 && col == 3) accent else bg, c)
                }
            }
        }
    }
}
