package app.tailorkeys

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.exp
import kotlin.math.roundToInt

/** One row of a changed word's dropdown: a word, the model's probability for it, and whether it is the letters as typed. */
internal class FixChoice(val word: String, val probability: Float, val typed: Boolean)

/**
 * A word the model changed in this field: what the text shows for it, the letters as typed, its dropdown's rows, the
 * words the model wrote for it (undone together when the typed letters are picked), and where it is: [at] once
 * [editsAt] of the model's edits were written, or, after edits of your own before it, right after [left].
 */
internal class MarkedFix(var shown: String, val typed: String, val choices: List<FixChoice>, val fixes: List<String>,
                         var at: Int, var editsAt: Int, val left: String, var picked: Boolean = false)

internal object FixChoices {
    /** Rows a dropdown shows at most: the word shown, the letters as typed, and the likeliest others. */
    const val ROWS = 4
    /** Other words are listed from this probability. */
    const val LISTED_FROM = 0.01f

    /**
     * The rows for a word the model wrote as [shown] (typed as [typed]), from the scores it was decided with, likeliest
     * first; the word shown and the letters as typed are always there. A change of case only (tiptoe to Tiptoe) splits
     * the word's probability by its capital, except at a sentence start ([first]), where the capital is always written.
     */
    fun of(scored: StudentCorrector.Scored, shown: String, typed: String, first: Boolean): List<FixChoice> {
        val key = shown.lowercase()
        val typedKey = typed.lowercase()
        val out = ArrayList<FixChoice>()
        for (i in scored.candidates.indices) {
            val c = scored.candidates[i]
            val p = scored.probability[i]
            when {
                c == key && c == typedKey && shown != typed -> {
                    val capital = if (first) 1f else (1 / (1 + exp(-scored.capital[i])))
                    out += FixChoice(shown, p * capital, false)
                    out += FixChoice(typed, p * (1 - capital), true)
                }
                c == key -> out += FixChoice(shown, p, shown == typed)
                c == typedKey -> out += FixChoice(typed, p, true)
                else -> out += FixChoice(cased(c, shown), p, false)
            }
        }
        if (out.none { it.word == shown }) out += FixChoice(shown, 0f, shown == typed)
        if (out.none { it.typed }) out += FixChoice(typed, 0f, true)
        val must = out.filter { it.word == shown || it.typed }
        val others = out.filter { it !in must && it.probability >= LISTED_FROM && must.none { m -> m.word.equals(it.word, true) } }
            .sortedByDescending { it.probability }
        return (must + others.take(maxOf(0, ROWS - must.size))).sortedByDescending { it.probability }
    }

    /** A probability as the dropdown writes it: whole percents, under one as <1%. */
    fun percent(p: Float) = if (p < 0.005f) "<1%" else "${(p * 100).roundToInt()}%"

    /** A candidate (lower case) in the case of the word shown: all capitals, a first capital, or as it is. */
    private fun cased(word: String, shown: String) = when {
        shown.length > 1 && shown.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        shown.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }
}

/**
 * The small list under a word the model changed, opened by tapping into the word: each choice with its probability,
 * the word shown marked; a tap on a row writes that word ([pick]). A window of the keyboard's own, placed over the
 * app's text at a point on the screen.
 */
internal class FixDropdown(private val context: Context, private val pick: (FixChoice) -> Unit) {
    private var popup: PopupWindow? = null
    val showing: Boolean get() = popup?.isShowing == true

    /**
     * Shows [choices] with its top left corner at ([x], [y]) on the screen, or ending at [aboveY] when it would reach
     * past [limitY] (the keyboard's top): under the word if it fits, else over it. [anchor] is any view of the keyboard.
     */
    fun show(anchor: View, palette: Palette, choices: List<FixChoice>, current: String, x: Int, y: Int, aboveY: Int, limitY: Int) {
        dismiss()
        if (anchor.windowToken == null) return
        val dp = context.resources.displayMetrics.density
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(palette.key)
                cornerRadius = 8 * dp
                setStroke(maxOf(1, dp.roundToInt()), if (palette.keyOutline != 0) palette.keyOutline else palette.functionKey)
            }
            clipToOutline = true
            minimumWidth = (150 * dp).roundToInt()
        }
        for (c in choices) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (40 * dp).roundToInt()
                setPadding((12 * dp).roundToInt(), 0, (12 * dp).roundToInt(), 0)
                if (c.word == current) setBackgroundColor(palette.accent and 0x00FFFFFF or 0x33000000)
                contentDescription = "${c.word}, ${FixChoices.percent(c.probability)}" + if (c.typed) ", as typed" else ""
                setOnClickListener { dismiss(); pick(c) }
            }
            row.addView(TextView(context).apply {
                text = c.word
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(if (c.typed && c.word != current) palette.hint else palette.text)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(context).apply {
                text = FixChoices.percent(c.probability)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(palette.hint)
                setPadding((16 * dp).roundToInt(), 0, 0, 0)
            })
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = list.measuredWidth
        val h = list.measuredHeight
        val screen = context.resources.displayMetrics.widthPixels
        val left = x.coerceIn((8 * dp).roundToInt(), maxOf((8 * dp).roundToInt(), screen - w - (8 * dp).roundToInt()))
        val top = if (y + h <= limitY) y else maxOf(0, aboveY - h)
        // The popup's position is counted from the keyboard window's corner, which sits low on the screen.
        val window = IntArray(2).also { anchor.rootView.getLocationOnScreen(it) }
        popup = PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isClippingEnabled = false
            isOutsideTouchable = true
            isTouchable = true
            elevation = 6 * dp
            showAtLocation(anchor, Gravity.TOP or Gravity.START, left - window[0], top - window[1])
        }
    }

    fun dismiss() {
        popup?.let { if (it.isShowing) try { it.dismiss() } catch (_: Exception) {} }
        popup = null
    }
}
