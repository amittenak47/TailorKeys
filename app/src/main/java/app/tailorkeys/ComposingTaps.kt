package app.tailorkeys

import kotlin.math.roundToInt

/** Reusable, bounded tap rows. No points, matrices or Gaussian work in the dictionary scan. */
class ComposingTaps {
    private val costs = ByteArray(MAX_LENGTH * LETTERS)
    private val known = BooleanArray(MAX_LENGTH)
    private val entered = BooleanArray(MAX_LENGTH)
    private var enteredCount = 0
    private var count = 0
    var word: String = ""
        private set
    var revision: Long = 0
        private set

    fun clear() { sync(""); revision++ }

    /** An independent copy, kept for look-back after the word is finished and these rows are reused. */
    fun copy() = ComposingTaps().also { c ->
        costs.copyInto(c.costs); known.copyInto(c.known); entered.copyInto(c.entered)
        c.count = count; c.enteredCount = enteredCount; c.word = word
    }

    fun sync(prefix: String) {
        if (word == prefix) return
        known.fill(false); entered.fill(false)
        count = 0; enteredCount = 0
        word = prefix
        revision++
    }

    fun append(prefix: String, letter: String, distances: FloatArray?) {
        sync(prefix)
        val index = word.length
        word += letter
        if (index < MAX_LENGTH && letter.length == 1) { entered[index] = true; enteredCount++ }
        if (index < MAX_LENGTH && letter.length == 1 && distances != null) {
            // -log Gaussian likelihood relative to the best centre (sigma = 0.5 widths).
            // Two tenths per nat => 4 * squared distance. Cap the boundary credit so
            // a tap far outside every letter cannot make an arbitrary letter free.
            val origin = minOf(0.25f, distances.minOrNull() ?: 0f)
            val offset = index * LETTERS
            for (c in 0 until LETTERS) {
                costs[offset + c] = (4f * (distances[c] - origin)).roundToInt().coerceIn(0, 10).toByte()
            }
            known[index] = true
            count++
        }
        revision++
    }

    fun backspace() {
        if (word.isEmpty()) return
        val index = word.lastIndex
        if (index < MAX_LENGTH && known[index]) { known[index] = false; count-- }
        if (index < MAX_LENGTH && entered[index]) { entered[index] = false; enteredCount-- }
        word = word.dropLast(1)
        revision++
    }

    fun hasInput(prefix: String) = word == prefix && enteredCount > 0
    // Taps are recorded as typed ("Fesr" after auto-capitalization) but the predictor looks words up lowercased.
    fun hasTaps(prefix: String) = word.equals(prefix, ignoreCase = true) && count > 0
    fun hasTap(index: Int) = index < MAX_LENGTH && known[index]
    fun cost(index: Int, letter: Char): Int =
        if (letter.code < LETTERS) costs[index * LETTERS + letter.code].toInt() else 10

    fun prefix(before: CharSequence?): String {
        if (before == null) return ""
        // Only an actively tapped word can include a slip onto the number row.
        if (word.any { it.isDigit() } && before.endsWith(word)) return word
        var start = before.length
        while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
        return before.subSequence(start, before.length).toString()
    }

    companion object {
        // The supported English, French, German and Spanish letter keys fit Latin-1.
        const val LETTERS = 256
        const val MAX_LENGTH = 64
    }
}
