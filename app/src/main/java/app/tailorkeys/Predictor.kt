package app.tailorkeys

import android.content.Context
import app.tailorkeys.layout.KeyGrid
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Suggestions for the two wheels, from a bundled list of common words plus words you type.
 *
 * Closest (right wheel): completions and layout-aware corrections, ranked with the previous word.
 * Explore (left wheel): a weighted random draw from further down the same completions, so less obvious
 * words show up. The draw is seeded by the prefix and context, so it stays steady on redraw.
 */
class Predictor(context: Context) {
    private val base: List<String>
    private val rank = HashMap<String, Int>()
    private val user = HashMap<String, Int>()
    private val seedPairs = HashMap<String, MutableMap<String, Int>>()
    private val userPairs = HashMap<String, MutableMap<String, Int>>()
    private val neighbours = HashMap<Char, MutableSet<Char>>()
    private val userFile = File(context.filesDir, "user_words.txt")
    private val pairFile = File(context.filesDir, "user_bigrams.txt")
    private var dirty = false
    private var cachedPrefix: String? = null
    private var cachedPrev: String? = null
    private var cachedCandidates: List<Candidate>? = null

    init {
        val words = context.assets.open("words.txt").bufferedReader().use { it.readLines() }
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        words.forEachIndexed { i, w -> rank.putIfAbsent(w, i) }
        base = rank.keys.sortedBy { rank[it] }
        context.assets.open("bigrams.txt").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val parts = line.split(' ')
                if (parts.size == 3 && rank.containsKey(parts[0]) && rank.containsKey(parts[1])) {
                    parts[2].toIntOrNull()?.let { seedPairs.getOrPut(parts[0]) { HashMap() }[parts[1]] = it }
                }
            }
        }
        if (userFile.exists()) userFile.readLines().forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 2) parts[1].toIntOrNull()?.let { user[parts[0]] = it }
        }
        if (pairFile.exists()) pairFile.readLines().forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 3) parts[2].toIntOrNull()?.let { userPairs.getOrPut(parts[0]) { HashMap() }[parts[1]] = it }
        }
    }

    fun setLayout(grid: KeyGrid) {
        cachedCandidates = null
        neighbours.clear()
        val letters = grid.items.filter { it.keys.firstOrNull()?.let { k -> k.length == 1 && k[0].isLetter() } == true }
        for (a in letters) for (b in letters) {
            if (a === b || abs(a.r - b.r) > 1) continue
            val nearColumns = a.c <= b.c + b.w && b.c <= a.c + a.w
            val acrossCenter = a.c + a.w - 1 == 4 && b.c == 6 || b.c + b.w - 1 == 4 && a.c == 6
            if (nearColumns || acrossCenter) {
                neighbours.getOrPut(a.keys[0][0].lowercaseChar()) { HashSet() }.add(b.keys[0][0].lowercaseChar())
            }
        }
    }

    private fun frequency(w: String) = 1.0 / ((rank[w] ?: 5000) + 10) + (user[w] ?: 0) * 0.05
    private fun pairCount(prev: String?, w: String) = (seedPairs[prev]?.get(w) ?: 0) + 3.0 * (userPairs[prev]?.get(w) ?: 0)
    private data class Candidate(val word: String, val score: Double, val correction: Boolean)

    // Costs are tenths. Three bounded rows are reused for every dictionary entry in a lookup.
    private fun cost(p: String, w: String, limit: Int, rows: Array<IntArray>): Int {
        if (w.isEmpty()) return 1000
        val transposed = w.length > 1 && p[0] == w[1] && p[1] == w[0]
        if (p[0] != w[0] && neighbours[p[0]]?.contains(w[0]) != true && !transposed) return 1000
        val extra = if (p[0] == w[0]) 0 else 3
        val bound = limit - extra
        val band = bound / 8
        val prefixLength = minOf(p.length, w.length)
        if (p.length - prefixLength > band) return 1000
        val whole = abs(w.length - p.length) <= 2
        val end = minOf(if (whole) w.length else prefixLength, p.length + band)
        var older = rows[0]
        var previous = rows[1]
        var current = rows[2]
        older.fill(1000)
        previous.fill(1000)
        current.fill(1000)
        for (j in 0..minOf(p.length, band)) previous[j] = j * 8
        var previousMin = 0
        var best = 1000
        for (i in 1..end) {
            val from = maxOf(1, i - band)
            val to = minOf(p.length, i + band)
            current[0] = if (i <= band) i * 8 else 1000
            if (from > 1) current[from - 1] = 1000
            if (to < p.length) current[to + 1] = 1000
            var rowMin = current[0]
            for (j in from..to) {
                val substitution = when {
                    p[j - 1] == w[i - 1] -> 0
                    neighbours[p[j - 1]]?.contains(w[i - 1]) == true -> 4
                    else -> 10
                }
                var value = minOf(previous[j] + 8, current[j - 1] + 8, previous[j - 1] + substitution)
                if (i > 1 && j > 1 && p[j - 1] == w[i - 2] && p[j - 2] == w[i - 1]) {
                    value = minOf(value, older[j - 2] + 6)
                }
                current[j] = value
                rowMin = minOf(rowMin, value)
            }
            if (i == prefixLength || whole && i == w.length) best = minOf(best, current[p.length])
            // A transposition can jump over one over-budget row, but never two.
            if (rowMin > bound && previousMin > bound) break
            previousMin = rowMin
            val spare = older
            older = previous
            previous = current
            current = spare
        }
        return best + extra
    }

    private fun candidates(prefix: String, prev: String?): List<Candidate> {
        val p = prefix.lowercase()
        cachedCandidates?.let { if (cachedPrefix == p && cachedPrev == prev) return it }
        val limit = if (p.length <= 4) 9 else 13
        val rows = Array(3) { IntArray(p.length + 1) }
        val all = ArrayList<Candidate>()
        fun add(w: String) {
            if (w == p) return
            val completion = w.startsWith(p)
            val cost = if (completion) 0 else if (p.length >= 2) cost(p, w, limit, rows) else return
            if (cost > limit) return
            val score = ln(frequency(w)) + 1.5 * ln(1 + pairCount(prev, w)) - 2.5 * cost / 10.0
            all.add(Candidate(w, score, !completion))
        }
        for (w in user.keys) add(w)
        for (w in base) if (!user.containsKey(w)) add(w)
        val result = if (p.isEmpty() && prev != null) {
            all.sortedWith(compareByDescending<Candidate> { pairCount(prev, it.word) }.thenByDescending { it.score }.thenBy { it.word })
        } else {
            all.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.word })
        }
        cachedPrefix = p
        cachedPrev = prev
        cachedCandidates = result
        return result
    }

    fun nextLetters(prefix: String, prev: String?): Map<Char, Double> {
        val weights = HashMap<Char, Double>()
        for (candidate in candidates(prefix, prev)) {
            if (candidate.correction || candidate.word.length <= prefix.length) continue
            val letter = candidate.word[prefix.length]
            weights[letter] = (weights[letter] ?: 0.0) + exp(candidate.score)
        }
        val max = weights.values.maxOrNull() ?: return emptyMap()
        return weights.mapValues { it.value / max }
    }

    fun isDeadEnd(prefix: String): Boolean {
        val p = prefix.lowercase()
        return prefix.length >= 2 && user.keys.none { it.startsWith(p) } && base.none { it.startsWith(p) }
    }

    fun closest(prefix: String, prev: String? = null, n: Int = 8): List<String> {
        val c = candidates(prefix, prev)
        val maxCorrections = if (c.count { !it.correction } < 5) n else 3
        var corrections = 0
        return c.asSequence().filter { !it.correction || corrections++ < maxCorrections }
            .take(n).map { matchCase(it.word, prefix) }.toList()
    }

    fun explore(prefix: String, prev: String? = null, n: Int = 8): List<String> {
        val c = candidates(prefix, prev)
        val seed = prefix.lowercase() + (prev?.let { "\t$it" } ?: "")
        val rnd = Random(seed.hashCode())
        // Weighted sampling without replacement. Temperature 2.5 flattens the ranking.
        return c.drop(1).take(300)
            .map { it.word to rnd.nextDouble().pow(1.0 / exp(it.score / 2.5)) }
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
    fun learn(word: String, prev: String? = null) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { it.isLetter() || it == '\'' }) return
        user[w] = (user[w] ?: 0) + 1
        if (prev != null) {
            val pairs = userPairs.getOrPut(prev) { HashMap() }
            pairs[w] = (pairs[w] ?: 0) + 1
        }
        cachedCandidates = null
        dirty = true
    }

    fun save() {
        if (!dirty) return
        userFile.writeText(user.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        pairFile.writeText(userPairs.entries.flatMap { (prev, pairs) ->
            pairs.map { (word, count) -> "$prev\t$word\t$count" }
        }.joinToString("\n"))
        dirty = false
    }

    private fun matchCase(w: String, prefix: String) = when {
        prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase()
        prefix.isNotEmpty() && prefix[0].isUpperCase() -> w.replaceFirstChar { it.uppercase() }
        else -> w
    }

    // Read-only gesture inputs; tap ranking and its caches remain unchanged.
    fun gestureWords(): List<String> = base
    fun gestureUserWords(): Set<String> = user.keys.toSet()
    fun gestureFrequency(word: String): Double = frequency(word)
    fun contextScore(prev: String?, word: String): Double = 1.5 * ln(1 + pairCount(prev, word))
}
