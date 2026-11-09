package app.tailorkeys

enum class AutocorrectMode { OFF, CONFIDENT, AGGRESSIVE }

/** Complete-word policies; these run at a separator or explicit Tidy, never per frame. */
internal object CorrectionRules {
    fun allowed(textField: Boolean, password: Boolean, noSuggestions: Boolean, address: Boolean) =
        textField && !password && !noSuggestions && !address
    fun eligible(word: String) = word.length in 1..32 && word.all { it.isLetter() || it == '\'' } &&
        word.drop(1).none { it.isUpperCase() }
    fun sentenceStart(before: String, wordLength: Int): Boolean {
        var i = before.length - wordLength - 1
        while (i >= 0 && before[i] in " \t\"'([{ ") i--
        return i < 0 || before[i] in ".!?\n\r"
    }
    /**
     * Whether [word], ending [before], was capitalized on purpose: a capital away from the start of a sentence marks a
     * name or brand (Figma, Meta), which stays as typed. Only restoring an apostrophe (Im -> I'm) still applies.
     */
    fun named(before: String, word: String) = word.firstOrNull()?.isUpperCase() == true && !sentenceStart(before, word.length)
    fun apostropheOnly(word: String, fix: String) = fix.replace("'", "") == word.replace("'", "")
    fun transposition(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var i = 0
        while (i < a.length && a[i] == b[i]) i++
        if (i + 1 >= a.length || a[i] != b[i + 1] || a[i + 1] != b[i]) return false
        i += 2
        while (i < a.length) { if (a[i] != b[i]) return false; i++ }
        return true
    }
    /** Last two words, excluding the span being considered; sentence boundaries stop the scan. */
    fun context(before: String, span: Int): List<String> {
        var end = before.length - span
        val words = ArrayList<String>(2)
        repeat(2) {
            while (end > 0 && !before[end - 1].isLetter() && before[end - 1] != '\'') {
                if (before[end - 1] in ".!?\n\r") return words.reversed()
                end--
            }
            var start = end
            while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
            if (start == end) return words.reversed()
            words.add(before.substring(start, end).lowercase()); end = start
        }
        return words.reversed()
    }
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

/**
 * The word a separator is finishing, as the service saw it before typing the separator. [before] ends with [word];
 * [typed]: its letters were typed here (not pasted or reached by moving the cursor); [picked]: chosen from the
 * wheels; [undone]: a correction of it was just undone; [glide]: swiped; [learned]: one of the user's own words, or
 * typed on purpose before; [known]: in TailorKeys's dictionary.
 */
internal data class WordEndFacts(val word: String, val before: String, val after: String, val selected: Boolean,
    val letters: Boolean, val typed: Boolean, val picked: Boolean, val undone: Boolean, val glide: Boolean,
    val learned: Boolean, val known: Boolean)

/** Checks made before any automatic change to a finished word, by TailorKeys's own correction or the system spell checker. */
internal object WordEndRules {
    /**
     * Whether the finished word may be changed automatically at all: in a field that allows corrections, on the
     * letters, nothing selected, not in the middle of a word, not a word picked from the wheels, not one whose
     * correction was just undone, and not a swiped word.
     */
    fun mayChange(f: WordEndFacts, fieldAllowed: Boolean) = fieldAllowed && f.letters && !f.selected &&
        !f.picked && !f.undone && !f.glide && f.after.firstOrNull()?.let(TextInputRules::wordChar) != true

    /**
     * The edit that puts [replacement] in place of [original], the word before the cursor, followed by [separator]:
     * how many characters to delete before the cursor, and what to insert. [typed] is the separator text already
     * after the word (the system spell checker answers once it is typed); it is deleted and given back.
     */
    fun edit(original: String, replacement: String, separator: String, typed: String = "") =
        (original.length + typed.length) to replacement + separator
}

/** Shared by corrections, armed suggestions and Tidy; only a matching live editor can keep/undo it. */
internal class ReversibleCommit(val original: String, val inserted: String, val separator: String,
    val before: String?, val after: String?, val correction: Boolean = false,
    val insertedAfter: Int = 0, val originalTail: Int = 0, private val onKept: () -> Unit = {},
    /** The words a correction changed, typed to replaced, when it spans more than one word (look-back, re-decoding). */
    val changedWords: List<Pair<String, String>>? = null, private val learn: () -> Unit) {
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
        if (matches(before, after, selected)) { onKept(); if (learning) learn() }
    }
}

data class WordCorrection(val original: String, val replacement: String)
internal data class RejectedCorrection(val language: String, val original: String, val fix: String)

/** Explicit undo preferences are separate from passive vocabulary learning and never decay. */
internal class CorrectionHistory {
    private data class Entry(val original: String, val fix: String, var undos: Int = 0, var keeps: Int = 0)
    private val entries = LinkedHashMap<String, Entry>()
    private val undoTotals = HashMap<String, Int>()
    private val keepTotals = HashMap<String, Int>()
    private fun key(original: String, fix: String) = original.lowercase() + "\t" + fix.lowercase()
    fun blocked(original: String, fix: String) = (entries[key(original, fix)]?.undos ?: 0) >= 2
    fun reject(original: String, fix: String) {
        val e = entries.getOrPut(key(original, fix)) { Entry(original.lowercase(), fix) }
        e.undos = (e.undos + 1).coerceAtMost(1_000_000)
        undoTotals[fix.lowercase()] = (undoTotals[fix.lowercase()] ?: 0) + 1
    }
    fun kept(original: String, fix: String) {
        val e = entries.getOrPut(key(original, fix)) { Entry(original.lowercase(), fix) }
        e.keeps = (e.keeps + 1).coerceAtMost(1_000_000)
        keepTotals[fix.lowercase()] = (keepTotals[fix.lowercase()] ?: 0) + 1
    }
    fun typed(original: String): Boolean {
        var changed = false
        for (e in entries.values) if (e.original == original.lowercase() && e.undos == 1) { e.undos = 2; undoTotals[e.fix.lowercase()] = (undoTotals[e.fix.lowercase()] ?: 0) + 1; changed = true }
        return changed
    }
    fun penalty(fix: String): Double {
        val undos = (undoTotals[fix.lowercase()] ?: 0).toDouble()
        val keeps = (keepTotals[fix.lowercase()] ?: 0).toDouble()
        return (kotlin.math.ln(1.0 + undos) * undos / (undos + keeps + 1.0)).coerceAtMost(6.0)
    }
    fun banned(language: String) = entries.values.filter { it.undos >= 2 }.map { RejectedCorrection(language, it.original, it.fix) }
    fun remove(original: String, fix: String) { entries.remove(key(original, fix)); rebuildTotals() }
    fun clear() { entries.clear(); undoTotals.clear(); keepTotals.clear() }
    fun load(text: String) {
        entries.clear()
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            if (p.size != 4) continue
            val undos = p[2].toIntOrNull() ?: continue; val keeps = p[3].toIntOrNull() ?: continue
            if (undos < 0 || keeps < 0 || p[0].isBlank() || p[1].isBlank()) continue
            entries[key(p[0], p[1])] = Entry(p[0].lowercase(), p[1], undos.coerceAtMost(1_000_000), keeps.coerceAtMost(1_000_000))
        }
        rebuildTotals()
    }
    private fun rebuildTotals() {
        undoTotals.clear(); keepTotals.clear()
        for (e in entries.values) {
            val fix = e.fix.lowercase()
            undoTotals[fix] = (undoTotals[fix] ?: 0) + e.undos
            keepTotals[fix] = (keepTotals[fix] ?: 0) + e.keeps
        }
    }
    fun serialize() = buildString { for (e in entries.values) append("${e.original}\t${e.fix}\t${e.undos}\t${e.keeps}\n") }
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
