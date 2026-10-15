package app.tailorkeys

import android.content.Context
import java.io.File
import kotlin.math.pow
import kotlin.random.Random

/**
 * Suggestions for the two wheels, from a bundled list of common words plus words you type.
 *
 * Closest (right wheel): completions of what you're typing, most likely first. Words you use count most.
 * Explore (left wheel): a weighted random draw from further down the same completions, so less obvious
 * words show up. The draw is seeded by the prefix, so the wheel doesn't reshuffle on every redraw.
 */
class Predictor(context: Context) {
    private val base: List<String>
    private val rank = HashMap<String, Int>()
    private val user = HashMap<String, Int>()
    private val userFile = File(context.filesDir, "user_words.txt")
    private var dirty = false

    init {
        base = context.assets.open("words.txt").bufferedReader().readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        base.forEachIndexed { i, w -> rank.putIfAbsent(w, i) }
        if (userFile.exists()) userFile.readLines().forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 2) parts[1].toIntOrNull()?.let { user[parts[0]] = it }
        }
    }

    private fun score(w: String) = 1.0 / ((rank[w] ?: 5000) + 10) + (user[w] ?: 0) * 0.05

    private fun candidates(prefix: String): List<String> {
        val p = prefix.lowercase()
        val all = LinkedHashSet<String>()
        user.keys.filterTo(all) { it.startsWith(p) && it != p }
        base.filterTo(all) { it.startsWith(p) && it != p }
        return all.sortedByDescending(::score)
    }

    fun closest(prefix: String, n: Int = 8) = candidates(prefix).take(n).map { matchCase(it, prefix) }

    fun explore(prefix: String, n: Int = 8): List<String> {
        val c = candidates(prefix)
        if (c.size <= 2) return c.map { matchCase(it, prefix) }
        val rnd = Random(prefix.lowercase().hashCode())
        // Weighted sampling without replacement. Temperature 2.5 flattens the ranking.
        return c.drop(1).take(300)
            .map { it to rnd.nextDouble().pow(1.0 / score(it).pow(1 / 2.5)) }
            .sortedByDescending { it.second }
            .take(n)
            .map { matchCase(it.first, prefix) }
    }

    /** Symbol suggestions for the 123 and #+= layers, based on the character before the cursor. */
    fun symbols(before: Char?): Pair<List<String>, List<String>> {
        val closers = mapOf('(' to ")", '[' to "]", '{' to "}", '<' to ">", '"' to "\"", '\'' to "'")
        val closest = when {
            before != null && closers.containsKey(before) -> listOf(closers.getValue(before), " ", ".", ",")
            before != null && before.isDigit() -> listOf(".", ",", "%", "$", ":", "-", "/")
            else -> listOf(".", ",", "?", "!", "'", "\"", ":", ";")
        }
        val explore = listOf("…", "->", "=>", "—", "≠", "±", "°", "×", "÷", "≈", "✓", ":)", "<3", "©", "™", "§")
        return explore to closest
    }

    /** Remembers a word you typed. Never call this for password fields. */
    fun learn(word: String) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { it.isLetter() || it == '\'' }) return
        user[w] = (user[w] ?: 0) + 1
        dirty = true
    }

    fun save() {
        if (!dirty) return
        userFile.writeText(user.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        dirty = false
    }

    private fun matchCase(w: String, prefix: String) = when {
        prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase()
        prefix.isNotEmpty() && prefix[0].isUpperCase() -> w.replaceFirstChar { it.uppercase() }
        else -> w
    }
}
