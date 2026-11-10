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
import kotlin.math.roundToInt

/**
 * The dropdown on a changed word (Amit's sketch B, first built 2026-11-03 for the prototype model, restored for today's
 * corrector): tapping into a word the keyboard changed opens a small list under it with the word shown, the letters as
 * typed and the likeliest other words; a tap on a row writes that word. Today's corrector has no calibrated
 * probabilities, so the rows carry no percentages.
 */
internal class FixChoice(val word: String, val typed: Boolean)

/** A word the keyboard changed in this field: what the text shows, the letters as typed, its rows, and the text just before it. */
internal class MarkedFix(var shown: String, val typed: String, val choices: List<FixChoice>, val left: String, var picked: Boolean = false)

/** The words the keyboard changed in the current field, oldest first, found again by the word and the text before it. */
internal class CorrectionMarks {
    private val marks = ArrayList<MarkedFix>()
    val size get() = marks.size

    /**
     * Remembers that [typed] was changed to [shown], with [before] the text just before the word. [others] are other words
     * the corrector considered (best first); a mark already there for the same word and place is replaced.
     */
    fun mark(typed: String, shown: String, others: List<String>, before: String) {
        if (typed == shown || shown.isEmpty()) return
        val left = before.takeLast(LEFT)
        marks.removeAll { it.shown == shown && it.left == left }
        marks += MarkedFix(shown, typed, choices(typed, shown, others), left)
        while (marks.size > MAX) marks.removeAt(0)
    }

    /** The changed word [word] with [before] the text before it, or null when it isn't one the keyboard changed. */
    fun find(word: String, before: String): MarkedFix? = marks.lastOrNull { m ->
        m.shown == word && (if (m.left.isEmpty()) before.isEmpty() else before.endsWith(m.left))
    }

    fun clear() = marks.clear()

    companion object {
        /** Rows a dropdown shows at most: the word shown, the letters as typed, and the likeliest others. */
        const val ROWS = 4
        /** How much of the text before a changed word identifies it again. */
        const val LEFT = 16
        /** Changed words remembered per field. */
        const val MAX = 64

        /** The rows for [typed] changed to [shown]: the word shown first, the letters as typed always there, then others. */
        fun choices(typed: String, shown: String, others: List<String>): List<FixChoice> {
            val out = arrayListOf(FixChoice(shown, false), FixChoice(typed, true))
            for (o in others) {
                if (out.size >= ROWS) break
                val word = cased(o, shown)
                if (out.none { it.word.equals(word, ignoreCase = true) }) out += FixChoice(word, false)
            }
            return out
        }

        /** A candidate in the case of the word shown: all capitals, a first capital, or as it is. */
        fun cased(word: String, shown: String) = when {
            shown.length > 1 && shown.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            shown.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}

/**
 * The text around the cursor at a selection change: where it starts in the field ([offset]), the selection, and whether
 * it reaches the end of the text. A move with the text the same and no key typed is a tap.
 */
internal class SeenText(val text: String, val offset: Int, val start: Int, val end: Int, val toEnd: Boolean) {
    /** Whether [other] reads the same text as this where both reach: only the cursor may have moved. */
    fun sameText(other: SeenText): Boolean {
        val from = maxOf(offset, other.offset)
        val to = minOf(offset + text.length, other.offset + other.text.length)
        if (to < from) return false
        // Where both reach the end of the text, it ends at the same place (a letter typed at the end moves it).
        if (toEnd && other.toEnd && offset + text.length != other.offset + other.text.length) return false
        return text.regionMatches(from - offset, other.text, from - other.offset, to - from)
    }
}

/**
 * The small list under a changed word: each choice, the word shown marked, the letters as typed in the hint colour; a
 * tap on a row writes that word ([pick]). A window of the keyboard's own, placed over the app's text at a point on the screen.
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
                contentDescription = c.word + if (c.typed) ", as typed" else ""
                setOnClickListener { dismiss(); pick(c) }
            }
            row.addView(TextView(context).apply {
                text = c.word
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(if (c.typed && c.word != current) palette.hint else palette.text)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (c.typed) row.addView(TextView(context).apply {
                text = "as typed"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(palette.hint)
                setPadding((16 * dp).roundToInt(), 0, 0, 0)
            })
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = list.measuredWidth
        val h = list.measuredHeight
        val screen = context.resources.displayMetrics.widthPixels
        val margin = (8 * dp).roundToInt()
        val left = x.coerceIn(margin, maxOf(margin, screen - w - margin))
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
