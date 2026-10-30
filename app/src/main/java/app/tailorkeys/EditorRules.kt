package app.tailorkeys

/** A single, live editor read. Positions are absolute; text may be a bounded window. */
internal data class EditorWindow(
    val text: String, val offset: Int, val start: Int, val end: Int,
    val before: String, val after: String, val selected: Boolean,
) {
    val known get() = offset >= 0 && start >= offset && end >= offset &&
        start.toLong() <= offset.toLong() + text.length && end.toLong() <= offset.toLong() + text.length
    fun cursorTarget(steps: Int, selecting: Boolean): Int? =
        CursorMotion.target(text, offset, start, end, after.length, steps, selecting).takeIf { it >= 0 }
    companion object { const val CURSOR_WINDOW = 256 }
}

/** Reused for joystick steps: keep editor-owned text and primitive positions without copying it. */
internal class CursorRead {
    var text: CharSequence = ""
        private set
    var offset = -1
        private set
    var start = -1
        private set
    var end = -1
        private set
    var afterLength = 0
        private set
    val known get() = offset >= 0 && start >= offset && end >= offset &&
        start.toLong() <= offset.toLong() + text.length && end.toLong() <= offset.toLong() + text.length
    fun set(text: CharSequence, offset: Int, start: Int, end: Int): CursorRead {
        this.text = text; this.offset = offset
        this.start = if (offset >= 0 && offset.toLong() + start <= Int.MAX_VALUE) offset + start else -1
        this.end = if (offset >= 0 && offset.toLong() + end <= Int.MAX_VALUE) offset + end else -1
        afterLength = (text.length - maxOf(start, end)).coerceAtLeast(0)
        return this
    }
    fun target(steps: Int, selecting: Boolean, words: Boolean = false): Int =
        if (words) CursorMotion.wordTarget(text, offset, start, end, afterLength, steps, selecting)
        else CursorMotion.target(text, offset, start, end, afterLength, steps, selecting)
}

internal object CursorMotion {
    private fun isWord(c: Char) = Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '’'

    /**
     * Like [target], but each step goes to the end of the next word (forward) or the start of the previous one
     * (back), as Ctrl+arrow does. -1 when a word runs past the known text.
     */
    fun wordTarget(text: CharSequence, offset: Int, start: Int, end: Int, afterLength: Int,
                   steps: Int, selecting: Boolean): Int {
        if (offset < 0 || start < offset || end < offset ||
            start.toLong() > offset.toLong() + text.length || end.toLong() > offset.toLong() + text.length) return -1
        var i = (if (selecting) end else if (steps < 0) minOf(start, end) else maxOf(start, end)) - offset
        val first = maxOf(0, i - EditorWindow.CURSOR_WINDOW)
        val last = minOf(text.length.toLong(), i.toLong() + EditorWindow.CURSOR_WINDOW).toInt()
        repeat(kotlin.math.abs(steps)) {
            if (steps > 0) {
                while (i < last && !isWord(text[i])) i++
                while (i < last && isWord(text[i])) i++
            } else {
                while (i > first && !isWord(text[i - 1])) i--
                while (i > first && isWord(text[i - 1])) i--
            }
        }
        if (i == first && (first > 0 || offset > 0) ||
            i == last && (last < text.length || afterLength >= EditorWindow.CURSOR_WINDOW)) return -1
        return offset + i
    }

    /** -1 means that the position or a clipped grapheme boundary is unknown. */
    fun target(text: CharSequence, offset: Int, start: Int, end: Int, afterLength: Int,
               steps: Int, selecting: Boolean): Int {
        if (offset < 0 || start < offset || end < offset ||
            start.toLong() > offset.toLong() + text.length || end.toLong() > offset.toLong() + text.length) return -1
        val from = (if (selecting) end else if (steps < 0) minOf(start, end) else maxOf(start, end)) - offset
        // The editor can return an arbitrarily long selection. Scan only around the moving endpoint.
        var first = maxOf(0, from - EditorWindow.CURSOR_WINDOW)
        if (first > 0 && first < text.length && Character.isLowSurrogate(text[first])) first--
        val last = minOf(text.length.toLong(), from.toLong() + EditorWindow.CURSOR_WINDOW).toInt()
        val local = Graphemes.move(text, from, steps, first, last)
        if (local == first && (first > 0 || offset > 0) ||
            local == last && (last < text.length || afterLength >= EditorWindow.CURSOR_WINDOW)) return -1
        if ((first > 0 || offset > 0) && first < text.length) {
            var edge = first
            // A clipped flag run has unknown pairing parity, including a clipped surrogate half.
            if (Character.isLowSurrogate(text[edge])) edge++
            while (edge < last && Character.codePointAt(text, edge) in 0x1f1e6..0x1f1ff) edge += 2
            if (local < edge) return -1
        }
        return offset + local
    }
}

/** UTF-16 boundaries for combining marks, emoji modifiers, flags, tags and ZWJ sequences. */
internal object Graphemes {
    private fun regional(cp: Int) = cp in 0x1f1e6..0x1f1ff
    private fun extend(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() || cp in 0x1f3fb..0x1f3ff ||
            cp in 0xe0020..0xe007f || cp in 0xfe00..0xfe0f || cp in 0xe0100..0xe01ef
    }
    private fun next(text: CharSequence, from: Int, limit: Int): Int {
        var i = from
        if (i >= limit) return limit
        var cp = Character.codePointAt(text, i)
        i += Character.charCount(cp)
        if (cp == 0x200d && i < limit) i += Character.charCount(Character.codePointAt(text, i))
        if (cp == 13 && i < limit && text[i] == '\n') return i + 1
        if (regional(cp) && i < limit && regional(Character.codePointAt(text, i))) i += Character.charCount(Character.codePointAt(text, i))
        while (i < limit) {
            cp = Character.codePointAt(text, i)
            if (extend(cp)) i += Character.charCount(cp)
            else if (cp == 0x200d) {
                i++
                if (i < limit) i += Character.charCount(Character.codePointAt(text, i))
            } else break
        }
        return minOf(i, limit)
    }
    fun move(text: CharSequence, from: Int, steps: Int, first: Int = 0, last: Int = text.length): Int {
        var pos = from.coerceIn(first, last)
        var remaining = kotlin.math.abs(steps.toLong()).coerceAtMost((last - first).toLong()).toInt()
        if (steps > 0) {
            // Scan from the window's start even if the editor supplied an interior UTF-16 position.
            var boundary = first
            while (remaining > 0 && boundary < last) {
                boundary = next(text, boundary, last)
                if (boundary > pos) { pos = boundary; remaining-- }
            }
        } else while (remaining-- > 0 && pos > first) {
            var boundary = first
            var previous = first
            while (boundary < pos) { previous = boundary; boundary = next(text, boundary, last) }
            pos = previous
        }
        return pos
    }
}

internal object EditorRules {
    fun replacementLength(prefixLength: Int, selected: Boolean) = if (selected) 0 else prefixLength
    fun canLearn(text: Boolean, password: Boolean, noSuggestions: Boolean, noPersonalization: Boolean) =
        text && !password && !noSuggestions && !noPersonalization
    fun deferredLearning(armedAllowed: Boolean, currentAllowed: Boolean) = armedAllowed && currentAllowed
    fun pendingCharacter(key: Boolean, stack: Boolean, moved: Boolean, consumed: Boolean, character: Boolean) =
        !consumed && (key || stack && !moved && character)
    fun typesFromAccessibility(editMode: Boolean) = !editMode
}

internal data class HandleChange(val width: Int, val align: Align, val restore: Int?)
internal fun handleChange(width: Int, align: Align, split: Boolean, left: Boolean, restore: Int?): HandleChange {
    val direction = if (left) Align.LEFT else Align.RIGHT
    return when {
        width >= 100 -> HandleChange(if (split) 70 else 78, direction, 100)
        align == Align.CENTER -> HandleChange(width, direction, restore)
        align != direction -> HandleChange(restore ?: width, Align.CENTER, null)
        else -> HandleChange(width, align, restore)
    }
}
