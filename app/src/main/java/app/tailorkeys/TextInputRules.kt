package app.tailorkeys

/** Pure decisions shared by the IME's suggestion and punctuation paths. */
object TextInputRules {
    fun wordChar(ch: Char) = ch.isLetterOrDigit() || ch == '\'' || ch == '\u2019'
    fun suffixLength(after: CharSequence): Int {
        var n = 0
        while (n < after.length && wordChar(after[n])) n++
        return n
    }
    fun addSpace(enabled: Boolean, after: CharSequence, password: Boolean = false, letters: Boolean = false): Boolean =
        enabled && !password && !letters && (after.isEmpty() || after[0].isLetterOrDigit())

    data class Replacement(val before: Int, val after: Int, val space: Boolean)
    fun replacement(prefixLength: Int, after: String, automaticSpace: Boolean, selected: Boolean = false,
                    spacedWordLength: Int = 0): Replacement {
        val suffix = if (selected || spacedWordLength > 0) 0 else suffixLength(after)
        return Replacement(if (selected) 0 else prefixLength + if (spacedWordLength > 0) 1 else 0,
            suffix, addSpace(automaticSpace, after.subSequence(suffix, after.length)))
    }

    data class Edit(val deleteBefore: Int = 0, val insert: String = "", val move: Int = 0)
    /** Null is the ordinary insertion path: no allocation per typed letter. */
    fun punctuation(text: String, before: CharSequence, after: CharSequence, automaticSpace: Boolean,
                    enabled: Boolean, password: Boolean = false, trailingSpace: Boolean = true): Edit? {
        if (!enabled || password || text.length != 1) return null
        val ch = text[0]
        if (ch in ")]}\"" && after.firstOrNull() == ch) return Edit(move = 1)
        if (automaticSpace && before.lastOrNull() == ' ') {
            if (ch in ".,?!:;") return Edit(1, ch.toString() + if (trailingSpace) " " else "")
            if (ch == '\'') return Edit(1, "'")
        }
        // A quote only opens at the start of a word, so 5" (inches) and mid-word quotes stay single.
        val opensQuote = before.isEmpty() || before.last().isWhitespace() || before.last() in "([{"
        val closer = when (ch) { '(' -> ')'; '[' -> ']'; '{' -> '}'; '"' -> if (opensQuote) '"' else null; else -> null }
        return closer?.let { Edit(insert = "$ch$it", move = -1) }
    }
    fun doubleSpace(enabled: Boolean, before: CharSequence, elapsed: Long, password: Boolean = false): Boolean =
        enabled && !password && elapsed in 0..599 && before.length >= 2 && before.last() == ' ' && before[before.length - 2].isLetterOrDigit()

    fun wordForms(word: String, exists: (String) -> Boolean): List<String> {
        if (word.isEmpty() || !word.all { it.isLetter() }) return emptyList()
        val lower = word.lowercase()
        val forms = ArrayList<String>(3)
        val plural = when {
            lower.endsWith("y") && lower.length > 1 && lower[lower.lastIndex - 1] !in "aeiou" -> lower.dropLast(1) + "ies"
            lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z") || lower.endsWith("ch") || lower.endsWith("sh") -> lower + "es"
            else -> lower + "s"
        }
        if (exists(plural)) forms.add(if (word[0].isUpperCase()) plural.replaceFirstChar { it.uppercase() } else plural)
        // A committed alphabetic word always permits the regular possessive, including learned names.
        forms.add(word + "'s")
        return forms
    }
}
