package app.tailorkeys

enum class AutocorrectMode { OFF, CONFIDENT, AGGRESSIVE }

/** Complete-word policies; these run at a separator or explicit Tidy, never per frame. */
internal object CorrectionRules {
    fun allowed(textField: Boolean, password: Boolean, noSuggestions: Boolean, address: Boolean) =
        textField && !password && !noSuggestions && !address
    fun eligible(word: String) = word.length in 3..32 && word.all { it.isLetter() || it == '\'' } &&
        word.drop(1).none { it.isUpperCase() }
    fun smallEdit(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        val rows = Array(3) { IntArray(b.length + 1) }
        for (j in 0..b.length) rows[0][j] = j
        var previous = rows[0]; var older = rows[1]; var current = rows[2]
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) current[j] = minOf(current[j], older[j - 2] + 1)
            }
            val spare = older; older = previous; previous = current; current = spare
        }
        return previous[b.length] <= if (a.length >= 7) 2 else 1
    }
}

/** Shared by corrections, armed suggestions and Tidy; only a matching live editor can keep/undo it. */
internal class ReversibleCommit(val original: String, val inserted: String, val separator: String,
    val before: String?, val after: String?, val correction: Boolean = false,
    val insertedAfter: Int = 0, val originalTail: Int = 0, private val learn: () -> Unit) {
    private var pending = true
    fun matches(before: String, after: String, selected: Boolean) = !selected && this.before == before && this.after == after
    fun undo(before: String, after: String, selected: Boolean): String? {
        if (!pending || !matches(before, after, selected)) return null
        pending = false
        return original + separator
    }
    fun keep(before: String, after: String, selected: Boolean, learning: Boolean) {
        if (!pending) return
        pending = false
        if (learning && matches(before, after, selected)) learn()
    }
}

internal data class TidyFix(val start: Int, val end: Int, val original: String, val replacement: String)
internal object TidyText {
    fun cursor(cursor: Int, fixes: List<TidyFix>): Int {
        var result = cursor
        for (fix in fixes) {
            if (fix.end <= cursor) result += fix.replacement.length - (fix.end - fix.start)
            else if (fix.start < cursor) result += minOf(fix.replacement.length, cursor - fix.start) - (cursor - fix.start)
        }
        return result
    }
    fun afterWindow(after: String): String {
        for (i in after.indices) if (after[i] in ".!?\n" && (i == after.lastIndex || after[i + 1].isWhitespace())) return after.substring(0, i + 1)
        if (after.length < 768) return after
        var end = after.length
        while (end > 0 && TextInputRules.wordChar(after[end - 1])) end--
        return after.substring(0, end)
    }
    fun windowStart(before: String): Int {
        var start = maxOf(0, before.length - 768)
        var sentences = 0
        var i = before.length - 2
        while (i >= start) {
            if (before[i] in ".!?\n" && (i == 0 || before[i - 1] !in ".!?\n")) {
                sentences++
                if (sentences == 2) return i + 1
            }
            i--
        }
        if (start > 0) { while (start < before.length && !before[start].isWhitespace()) start++ }
        return start
    }
    /** Offsets refer to the captured text; apply only if that exact snapshot still exists. */
    fun propose(text: String, startsSentence: Boolean = true, correct: (String, String) -> String?): List<TidyFix> {
        val fixes = ArrayList<TidyFix>()
        var i = 0; var sentenceStart = startsSentence
        while (i < text.length) {
            if (text[i] == ' ') {
                val start = i
                while (i < text.length && text[i] == ' ') i++
                if (i - start > 1) fixes.add(TidyFix(start, i, text.substring(start, i), " "))
            } else if (text[i].isLetterOrDigit()) {
                val start = i
                while (i < text.length && TextInputRules.wordChar(text[i])) i++
                val word = text.substring(start, i)
                val addressToken = text.substring(0, start).takeLastWhile { !it.isWhitespace() } + word + text.substring(i).takeWhile { !it.isWhitespace() }
                val address = '@' in addressToken || "://" in addressToken || addressToken.trimEnd('.', ',', '!', '?', ';', ':').contains('.') || addressToken.contains('/')
                var replacement = if (address) word else correct(word, text.substring(0, start)) ?: word
                if (!address && sentenceStart && replacement.firstOrNull()?.isLowerCase() == true) replacement = replacement.replaceFirstChar { it.uppercase() }
                if (replacement != word) fixes.add(TidyFix(start, i, word, replacement))
                sentenceStart = false
            } else {
                if (text[i] in ".!?\n") sentenceStart = true
                i++
            }
        }
        return fixes
    }
    fun apply(text: String, fixes: List<TidyFix>): String {
        val result = StringBuilder(text)
        for (fix in fixes.sortedByDescending { it.start }) {
            require(text.substring(fix.start, fix.end) == fix.original)
            result.replace(fix.start, fix.end, fix.replacement)
        }
        return result.toString()
    }
}
