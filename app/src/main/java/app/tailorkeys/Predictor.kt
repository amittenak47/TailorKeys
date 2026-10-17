package app.tailorkeys

import android.content.Context
import app.tailorkeys.layout.KeyGrid
import java.io.File
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.random.Random

/**
 * Suggestions for the two wheels, from a bundled list of common words plus words you type.
 *
 * Closest (right wheel): completions and layout-aware corrections, ranked with two previous words.
 * Explore (left wheel): a weighted random draw from further down the same completions, so less obvious
 * words show up. The draw is seeded by the prefix and context, so it stays steady on redraw.
 */
class Predictor internal constructor(
    private val openAsset: (String) -> InputStream,
    filesDir: File,
    val language: String = "en",
    asynchronous: Boolean = false,
    onReady: () -> Unit = {},
) {
    constructor(context: Context, language: String = "en", onReady: () -> Unit = {}) :
        this({ context.assets.open(it) }, context.filesDir, language, true, onReady)
    private val base: List<String>
    private val rank = HashMap<String, Int>()
    private val user = HashMap<String, Int>()
    // Context IDs -> sorted packed (word ID, count) rows. No retained n-gram strings.
    private data class SeedTables(val pairs: Map<Long, LongArray>, val triples: Map<Long, LongArray>,
                                  val frequencies: DoubleArray = doubleArrayOf(), val common: List<String> = emptyList())
    @Volatile private var seedTables: SeedTables? = null
    private var cachedTables: SeedTables? = null
    private val topWords: List<String>
    private val userPairs = HashMap<String, MutableMap<String, Int>>()
    private val userTriples = HashMap<String, MutableMap<String, Int>>()
    private val neighbours = HashMap<Char, MutableSet<Char>>()
    private val gridCosts = ByteArray(ComposingTaps.LETTERS * ComposingTaps.LETTERS) { 10 }
    private val suffix = if (language == "en") "" else "_$language"
    private val userFile = File(filesDir, "user_words${suffix}.txt")
    private val pairFile = File(filesDir, "user_bigrams${suffix}.txt")
    private val tripleFile = File(filesDir, "user_trigrams${suffix}.txt")
    private var dirty = false
    private var cachedPrefix: String? = null
    private var cachedPrev: String? = null
    private var cachedPrev2: String? = null
    private var cachedCandidates: List<Candidate>? = null
    private var cachedTaps: ComposingTaps? = null
    private var cachedTapRevision = -1L

    init {
        val words = openAsset("words${suffix}.txt").bufferedReader().use { it.readLines() }
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        words.forEachIndexed { i, w -> rank.putIfAbsent(w, i) }
        base = words.distinct()
        if (userFile.exists()) userFile.readLines().forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 2) parts[1].toIntOrNull()?.let { user[parts[0]] = it }
        }
        if (pairFile.exists()) pairFile.readLines().forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 3) parts[2].toIntOrNull()?.let { userPairs.getOrPut(parts[0]) { HashMap() }[parts[1]] = it }
        }
        if (tripleFile.exists()) tripleFile.useLines { lines -> lines.forEach { line ->
            val parts = line.split('\t')
            if (parts.size == 4) parts[3].toIntOrNull()?.let {
                userTriples.getOrPut("${parts[0]}\t${parts[1]}") { HashMap() }[parts[2]] = it
            }
        } }
        // Only these base words and learned words can enter the frequency top 300.
        topWords = (base.take(300) + user.keys).distinct()
            .sortedWith(compareByDescending<String> { frequency(it) }.thenBy { it }).take(300)
        val loadTables = {
            val tables = try {
                val pairs = loadCounts("bigrams${suffix}.txt", 2)
                val triples = loadCounts("trigrams${suffix}.txt", 3)
                // Conversation counts complement AOSP ranks in the new languages (e.g. danke).
                // English retains its original ranking exactly.
                val frequencies = if (language == "en") doubleArrayOf() else DoubleArray(base.size).apply {
                    pairs.forEach { (context, row) -> row.forEach {
                        val count = (it and 0xffffffffL).toDouble()
                        this[context.toInt()] += count
                        this[(it ushr 32).toInt()] += count
                    } }
                    val max = maxOrNull() ?: 0.0
                    if (max > 0) indices.forEach { this[it] /= max }
                }
                val common = frequencies.indices.sortedByDescending { frequencies[it] }.take(300).map { base[it] }
                SeedTables(pairs, triples, frequencies, common)
            } catch (_: java.io.IOException) {
                SeedTables(emptyMap(), emptyMap())
            }
            // Publish both immutable tables together. UI-owned caches notice the new snapshot.
            seedTables = tables
            onReady()
        }
        if (asynchronous) Thread(loadTables, "TailorKeys-ngrams").apply { isDaemon = true; start() }
        else loadTables()
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
        gridCosts.fill(10)
        for (c in 0 until ComposingTaps.LETTERS) gridCosts[c * ComposingTaps.LETTERS + c] = 0
        for ((a, adjacent) in neighbours) for (b in adjacent) {
            if (a.code < ComposingTaps.LETTERS && b.code < ComposingTaps.LETTERS) {
                gridCosts[a.code * ComposingTaps.LETTERS + b.code] = 4
            }
        }
    }

    private fun gridCost(a: Char, b: Char): Int = when {
        a == b -> 0
        a.code < ComposingTaps.LETTERS && b.code < ComposingTaps.LETTERS -> gridCosts[a.code * ComposingTaps.LETTERS + b.code].toInt()
        neighbours[a]?.contains(b) == true -> 4
        else -> 10
    }

    private fun frequency(w: String, tables: SeedTables? = seedTables): Double {
        val corpus = rank[w]?.let { tables?.frequencies?.getOrNull(it) } ?: 0.0
        return 1.0 / ((rank[w] ?: 5000) + 10) + 0.2 * corpus + (user[w] ?: 0) * 0.05
    }
    private fun contextId(first: Int, second: Int) = (first.toLong() shl 32) or second.toLong()

    private fun loadCounts(name: String, n: Int): Map<Long, LongArray> {
        val rows = HashMap<Long, MutableList<Long>>()
        openAsset(name).bufferedReader().useLines { lines -> lines.forEach { line ->
            val parts = line.split(' ')
            if (parts.size != n + 1) return@forEach
            val first = rank[parts[0]] ?: return@forEach
            val second = rank[parts[1]] ?: return@forEach
            val word = rank[parts[n - 1]] ?: return@forEach
            val count = parts[n].toIntOrNull() ?: return@forEach
            if (count <= 0) return@forEach
            val key = if (n == 2) first.toLong() else contextId(first, second)
            rows.getOrPut(key) { ArrayList() }.add((word.toLong() shl 32) or count.toLong())
        } }
        return rows.mapValues { (_, values) -> values.toLongArray().apply { sort() } }
    }

    private fun count(row: LongArray?, word: Int?): Int {
        if (row == null || word == null) return 0
        var low = 0
        var high = row.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val id = (row[mid] ushr 32).toInt()
            if (id == word) return row[mid].toInt()
            if (id < word) low = mid + 1 else high = mid - 1
        }
        return 0
    }

    private data class Candidate(val word: String, val score: Double, val correction: Boolean,
                                 val pairCount: Int, val tripleCount: Int)

    // Costs are tenths. Three bounded rows are reused for every dictionary entry in a lookup.
    private fun cost(p: String, w: String, limit: Int, rows: Array<IntArray>, taps: ComposingTaps?): Int {
        if (w.isEmpty()) return 1000
        val transposed = w.length > 1 && p[0] == w[1] && p[1] == w[0]
        val firstSpatial = taps?.hasTap(0) == true
        val firstCost = if (firstSpatial) taps!!.cost(0, w[0]) else 0
        if (!transposed && if (firstSpatial) firstCost > 6
            else p[0] != w[0] && neighbours[p[0]]?.contains(w[0]) != true) return 1000
        val extra = if (firstSpatial) (if (transposed) minOf(3, firstCost) else firstCost) else if (p[0] == w[0]) 0 else 3
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
                    taps?.hasTap(j - 1) == true -> taps.cost(j - 1, w[i - 1])
                    else -> gridCost(p[j - 1], w[i - 1])
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

    private fun candidates(prefix: String, prev: String?, prev2: String?, input: ComposingTaps?): List<Candidate> {
        val p = prefix.lowercase()
        val taps = input?.takeIf { it.hasTaps(prefix) }
        val tapRevision = taps?.revision ?: -1L
        val tables = seedTables
        cachedCandidates?.let {
            if (cachedPrefix == p && cachedPrev == prev && cachedPrev2 == prev2 && cachedTables === tables &&
                cachedTaps === taps && cachedTapRevision == tapRevision) return it
        }
        val prevId = rank[prev]
        val prev2Id = rank[prev2]
        val pairs = prevId?.let { tables?.pairs?.get(it.toLong()) }
        val triples = if (prevId != null && prev2Id != null) tables?.triples?.get(contextId(prev2Id, prevId)) else null
        val learnedPairs = userPairs[prev]
        val learnedTriples = if (prev2 != null && prev != null) userTriples["$prev2\t$prev"] else null
        val legacyLimit = if (p.length <= 4) 9 else 13
        val limit = if (taps != null) minOf(22, maxOf(legacyLimit, 3 * p.length + 3)) else legacyLimit
        val rows = Array(3) { IntArray(p.length + 1) }
        val all = ArrayList<Candidate>()
        fun add(w: String) {
            if (w == p) return
            val completion = w.startsWith(p)
            val cost = if (completion) 0 else if (p.length >= 2) cost(p, w, limit, rows, taps) else return
            if (cost > limit) return
            val id = rank[w]
            val seedPair = count(pairs, id)
            val userPair = learnedPairs?.get(w) ?: 0
            val seedTriple = count(triples, id)
            val userTriple = learnedTriples?.get(w) ?: 0
            // User triples receive the same logarithmic personal weighting as pairs.
            val context = ln(1.0 + seedPair) + 1.5 * ln(1.0 + userPair) +
                1.2 * (ln(1.0 + seedTriple) + 1.5 * ln(1.0 + userTriple))
            val score = ln(frequency(w, tables)) + 1.5 * context - 2.5 * cost / 10.0
            all.add(Candidate(w, score, !completion, seedPair + userPair, seedTriple + userTriple))
        }
        if (p.isEmpty()) {
            // No dictionary scan: only this context's followers plus the load-time top 300.
            val pool = LinkedHashSet<String>()
            triples?.forEach { pool.add(base[(it ushr 32).toInt()]) }
            pairs?.forEach { pool.add(base[(it ushr 32).toInt()]) }
            learnedTriples?.keys?.let { pool.addAll(it) }
            learnedPairs?.keys?.let { pool.addAll(it) }
            pool.addAll(topWords)
            tables?.common?.let { pool.addAll(it) }
            for (w in pool) add(w)
        } else {
            for (w in user.keys) add(w)
            for (w in base) if (!user.containsKey(w)) add(w)
        }
        val result = if (p.isEmpty() && prev != null) {
            // Trigram followers, then remaining bigram followers, then frequency fallback.
            all.sortedWith(compareByDescending<Candidate> { if (it.tripleCount > 0) 2 else if (it.pairCount > 0) 1 else 0 }
                .thenByDescending { if (it.tripleCount > 0) it.tripleCount else it.pairCount }
                .thenByDescending { it.score }.thenBy { it.word })
        } else {
            all.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.word })
        }
        cachedPrefix = p
        cachedPrev = prev
        cachedPrev2 = prev2
        cachedTables = tables
        cachedTaps = taps
        cachedTapRevision = tapRevision
        cachedCandidates = result
        return result
    }

    fun nextLetters(prefix: String, prev: String?, prev2: String? = null, taps: ComposingTaps? = null): Map<Char, Double> {
        val weights = HashMap<Char, Double>()
        for (candidate in candidates(prefix, prev, prev2, taps)) {
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

    fun closest(prefix: String, prev: String? = null, prev2: String? = null, n: Int = 8, taps: ComposingTaps? = null): List<String> {
        val c = candidates(prefix, prev, prev2, taps)
        val maxCorrections = if (c.count { !it.correction } < 5) n else 3
        var corrections = 0
        return c.asSequence().filter { !it.correction || corrections++ < maxCorrections }
            .take(n).map { matchCase(it.word, prefix) }.toList()
    }

    fun explore(prefix: String, prev: String? = null, prev2: String? = null, n: Int = 8, taps: ComposingTaps? = null): List<String> {
        val c = candidates(prefix, prev, prev2, taps)
        val seed = prefix.lowercase() + "\t${prev.orEmpty()}\t${prev2.orEmpty()}"
        val rnd = Random(seed.hashCode())
        // Weighted sampling without replacement. Temperature 2.5 flattens the ranking.
        val pool = if (prefix.isEmpty()) c.drop(1) else c.drop(1).take(300)
        return pool.map { it.word to rnd.nextDouble().pow(1.0 / exp(it.score / 2.5)) }
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
    fun learn(word: String, prev: String? = null, prev2: String? = null) {
        val w = word.lowercase()
        if (w.length < 2 || !w.all { it.isLetter() || it == '\'' }) return
        user[w] = (user[w] ?: 0) + 1
        if (prev != null) {
            val pairs = userPairs.getOrPut(prev) { HashMap() }
            pairs[w] = (pairs[w] ?: 0) + 1
            if (prev2 != null) {
                val triples = userTriples.getOrPut("$prev2\t$prev") { HashMap() }
                triples[w] = (triples[w] ?: 0) + 1
            }
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
        tripleFile.writeText(userTriples.entries.flatMap { (context, triples) ->
            triples.map { (word, count) -> "$context\t$word\t$count" }
        }.joinToString("\n"))
        dirty = false
    }

    private fun matchCase(w: String, prefix: String) = when {
        prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase()
        prefix.isNotEmpty() && prefix[0].isUpperCase() -> w.replaceFirstChar { it.uppercase() }
        else -> w
    }

    /** Bundled dictionary in frequency-rank order; callers receive a bounded copy. */
    fun chordWords(): List<String> = base.take(20_000)

    // Read-only gesture inputs; tap ranking and its caches remain unchanged.
    fun gestureWords(): List<String> = base
    fun gestureUserWords(): Set<String> = user.keys.toSet()
    fun gestureFrequency(word: String): Double = frequency(word)
    /** The previous-word part of the suggestion score, for ranking swiped words the same way. */
    fun contextScore(prev: String?, word: String): Double {
        val pairs = rank[prev]?.let { seedTables?.pairs?.get(it.toLong()) }
        val seedPair = count(pairs, rank[word])
        val userPair = userPairs[prev]?.get(word) ?: 0
        return 1.5 * (ln(1.0 + seedPair) + 1.5 * ln(1.0 + userPair))
    }
}
