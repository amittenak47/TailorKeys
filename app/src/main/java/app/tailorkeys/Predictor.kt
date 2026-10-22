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
    enableModel: Boolean = true,
    modelLoader: () -> NextWordModel? = { EnglishModel.load(openAsset) },
    private val indexed: Boolean = true,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, language: String = "en", onReady: () -> Unit = {}) :
        this({ context.assets.open(it) }, context.filesDir, language, true, onReady)
    private val base: List<String>
    private val candidateIndex: CandidateIndex
    private val rank = HashMap<String, Int>()
    private val user = HashMap<String, Int>()
    private val learnedWords = java.util.TreeSet<String>()
    // Context IDs -> sorted packed (word ID, count) rows. No retained n-gram strings.
    private data class SeedTables(val pairs: Map<Long, LongArray>, val triples: Map<Long, LongArray>,
                                  val frequencies: DoubleArray = doubleArrayOf(), val common: List<String> = emptyList())
    @Volatile private var seedTables: SeedTables? = null
    private var cachedTables: SeedTables? = null
    private var topWords: List<String> = emptyList()
    private val userPairs = HashMap<String, MutableMap<String, Int>>()
    private val userTriples = HashMap<String, MutableMap<String, Int>>()
    private val neighbours = HashMap<Char, MutableSet<Char>>()
    private val gridCosts = ByteArray(ComposingTaps.LETTERS * ComposingTaps.LETTERS) { 10 }
    private val suffix = if (language == "en") "" else "_$language"
    private val userFile = File(filesDir, "user_words${suffix}.txt")
    private val pairFile = File(filesDir, "user_bigrams${suffix}.txt")
    private val tripleFile = File(filesDir, "user_trigrams${suffix}.txt")
    private val history = CorrectionHistory()
    private val correctionFile = File(filesDir, "user_corrections${suffix}.txt")
    private val metadataFile = File(filesDir, "user_learning${suffix}.txt")
    private val directory = filesDir
    private var generation = PersonalStore.generation(filesDir)
    private val observed = HashMap<String, Int>()
    private val lastUse = HashMap<String, Long>()
    private var decayedAt = now()
    private var dirty = false
    @Volatile private var saveFailed = false
    private var cachedPrefix: String? = null
    private var cachedPrev: String? = null
    private var cachedPrev2: String? = null
    private var cachedCandidates: List<Candidate>? = null
    private var cachedTaps: ComposingTaps? = null
    private var cachedTapRevision = -1L
    private var cachedScores: EnglishContext.Scores? = null
    private var cachedScoreVersion = -1L
    internal val english: EnglishContext?

    init {
        val words = DictionaryWords.read(openAsset, language)
        words.forEachIndexed { i, w -> rank.putIfAbsent(w, i) }
        base = words.distinct()
        candidateIndex = CandidateIndex(base)
        english = if (language == "en" && enableModel) EnglishContext(base,
            modelLoader, asynchronous, onReady) else null
        reloadLearning()
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

    /** Exclude the composing word; repeated letters reuse the distribution without model work. */
    fun updateContext(before: CharSequence, prefix: String) { english?.update(before, prefix.length) }
    fun resetContext() { english?.reset() }

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
                                 val pairCount: Int, val tripleCount: Int, val distance: Int = 0)

    // Costs are tenths. Three bounded rows are reused for every dictionary entry in a lookup.
    private fun cost(p: String, w: String, limit: Int, rows: Array<IntArray>, taps: ComposingTaps?, complete: Boolean = false): Int {
        if (w.isEmpty()) return 1000
        val transposed = w.length > 1 && p[0] == w[1] && p[1] == w[0]
        val firstSpatial = taps?.hasTap(0) == true
        val firstCost = if (firstSpatial) taps!!.cost(0, w[0]) else 0
        if (!transposed && if (firstSpatial) firstCost > 6
            else p[0] != w[0] && neighbours[p[0]]?.contains(w[0]) != true) return 1000
        val extra = if (complete && transposed) 0 else if (firstSpatial) (if (transposed) minOf(3, firstCost) else firstCost) else if (p[0] == w[0]) 0 else 3
        val bound = limit - extra
        val edit = if (complete) 12 else 8
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
        for (j in 0..minOf(p.length, band)) previous[j] = j * edit
        var previousMin = 0
        var best = 1000
        for (i in 1..end) {
            val from = maxOf(1, i - band)
            val to = minOf(p.length, i + band)
            current[0] = if (i <= band) i * edit else 1000
            if (from > 1) current[from - 1] = 1000
            if (to < p.length) current[to + 1] = 1000
            var rowMin = current[0]
            for (j in from..to) {
                val substitution = when {
                    taps?.hasTap(j - 1) == true -> taps.cost(j - 1, w[i - 1])
                    else -> gridCost(p[j - 1], w[i - 1])
                }
                var value = minOf(previous[j] + edit, current[j - 1] + edit, previous[j - 1] + substitution)
                if (i > 1 && j > 1 && p[j - 1] == w[i - 2] && p[j - 2] == w[i - 1]) {
                    value = minOf(value, older[j - 2] + 6)
                }
                current[j] = value
                rowMin = minOf(rowMin, value)
            }
            if ((!complete && i == prefixLength) || whole && i == w.length) best = minOf(best, current[p.length])
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
        return english?.withScores { candidates(prefix, prev, prev2, input, it) }
            ?: candidates(prefix, prev, prev2, input, null)
    }

    private fun candidates(prefix: String, prev: String?, prev2: String?, input: ComposingTaps?, scores: EnglishContext.Scores?): List<Candidate> {
        val p = prefix.lowercase()
        val taps = input?.takeIf { it.hasTaps(prefix) }
        val tapRevision = taps?.revision ?: -1L
        val tables = seedTables
        cachedCandidates?.let {
            if (cachedPrefix == p && cachedPrev == prev && cachedPrev2 == prev2 && cachedTables === tables &&
                cachedTaps === taps && cachedTapRevision == tapRevision && cachedScores === scores &&
                cachedScoreVersion == (scores?.version ?: -1L)) return it
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
            if (w.startsWith("<") || w == p) return
            val completion = w.startsWith(p)
            val cost = if (completion) 0 else if (p.length >= 2) cost(p, w, limit, rows, taps) else return
            if (cost > limit) return
            val id = rank[w]
            val seedPair = if (scores == null) count(pairs, id) else 0
            val userPair = learnedPairs?.get(w) ?: 0
            val seedTriple = if (scores == null) count(triples, id) else 0
            val userTriple = learnedTriples?.get(w) ?: 0
            val score = if (scores != null) {
                scores.probability(w, id) + personalScore(w, userPair, userTriple) - MODEL_TYPO_WEIGHT * cost / 10.0
            } else {
                val context = ln(1.0 + seedPair) + 1.5 * ln(1.0 + userPair) +
                    1.2 * (ln(1.0 + seedTriple) + 1.5 * ln(1.0 + userTriple))
                ln(frequency(w, tables)) + 1.5 * context - 2.5 * cost / 10.0
            }
            all.add(Candidate(w, score, !completion, seedPair + userPair, seedTriple + userTriple, cost))
        }
        if (p.isEmpty()) {
            // No dictionary scan: only this context's followers plus the load-time top 300.
            val pool = LinkedHashSet<String>()
            if (scores != null) scores.top.forEach { pool.add(scores.model.vocabulary[it]) }
            else {
                triples?.forEach { pool.add(base[(it ushr 32).toInt()]) }
                pairs?.forEach { pool.add(base[(it ushr 32).toInt()]) }
                pool.addAll(topWords)
                tables?.common?.let { pool.addAll(it) }
            }
            learnedTriples?.keys?.let { pool.addAll(it) }
            learnedPairs?.keys?.let { pool.addAll(it) }
            for (w in pool) add(w)
        } else {
            for (w in user.keys) add(w)
            if (indexed) candidateIndex.visit(p, limit,
                firstAllowed = { c -> if (taps?.hasTap(0) == true) taps.cost(0, c) <= 6
                    else p[0] == c || neighbours[p[0]]?.contains(c) == true },
                substitution = { i, c -> if (taps?.hasTap(i) == true) taps.cost(i, c) else gridCost(p[i], c) },
                accept = { w -> if (!user.containsKey(w)) add(w) })
            else for (w in base) if (!user.containsKey(w)) add(w)
        }
        val result = if (scores == null && p.isEmpty() && prev != null) {
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
        cachedScores = scores
        cachedScoreVersion = scores?.version ?: -1L
        cachedCandidates = result
        return result
    }

    private fun wordScore(w: String, prev: String?, prev2: String?, scores: EnglishContext.Scores?): Double {
        val pair = userPairs[prev]?.get(w) ?: 0
        val triple = if (prev != null && prev2 != null) userTriples["$prev2\t$prev"]?.get(w) ?: 0 else 0
        if (scores != null) return scores.probability(w, rank[w]) + personalScore(w, pair, triple)
        val tables = seedTables
        val seedPair = count(rank[prev]?.let { tables?.pairs?.get(it.toLong()) }, rank[w])
        val seedTriple = if (prev != null && prev2 != null) count(rank[prev2]?.let { a -> rank[prev]?.let { b -> tables?.triples?.get(contextId(a, b)) } }, rank[w]) else 0
        return ln(frequency(w)) + 1.5 * (ln(1.0 + seedPair) + 1.5 * ln(1.0 + pair) + 1.2 * (ln(1.0 + seedTriple) + 1.5 * ln(1.0 + triple)))
    }
    private fun common(word: String) = (rank[word] ?: Int.MAX_VALUE) < 5000
    private fun pairCount(first: String, second: String) = count(rank[first]?.let { seedTables?.pairs?.get(it.toLong()) }, rank[second]) + (userPairs[first]?.get(second) ?: 0)

    fun recordRejection(original: String, fix: String): java.util.concurrent.Future<*>? { history.reject(original, fix); dirty = true; return save() }
    fun recordKept(original: String, fix: String) { history.kept(original, fix); dirty = true }
    fun noteTyped(original: String) { if (history.typed(original)) { dirty = true; save() } }
    internal fun rejectionPenalty(fix: String) = history.penalty(fix)

    fun autocorrect(word: String, mode: AutocorrectMode, prev: String? = null, prev2: String? = null,
                    taps: ComposingTaps? = null, allowed: Boolean = true, sentenceStart: Boolean = false): String? {
        if (!allowed || mode == AutocorrectMode.OFF || !CorrectionRules.eligible(word)) return null
        val p = word.lowercase()
        if (language == "en" && word == "i" && sentenceStart)
            return "I".takeUnless { history.blocked(word, it) }
        val known = rank.containsKey(p)
        // Short dictionary words are intentional, even in Aggressive mode.
        if (p.length < 2 || known && (p.length <= 3 || mode == AutocorrectMode.CONFIDENT) ||
            isLearned(p) && !known || typedTwice(p)) return null
        fun decide(scores: EnglishContext.Scores?): String? {
            val rows = Array(3) { IntArray(p.length + 1) }
            val spatial = taps?.takeIf { it.hasTaps(word) }
            // Unknown words can have several wrong keys, judged by where each tap landed: a tap near the edge
            // of the intended key costs almost nothing, one in the middle of a neighbouring key about 4.
            // Confident only follows taps that point at the word; Aggressive allows about one full slip per
            // two letters (watwe -> water), using layout neighbours when there are no taps. Common words only.
            val slips = when {
                known || p.length < 5 -> -1
                mode == AutocorrectMode.AGGRESSIVE -> 4 * (p.length / 2)
                spatial != null -> p.length
                else -> -1
            }
            val ranked = candidates(p, prev, prev2, taps, scores).asSequence()
                .filter { !history.blocked(word, it.word) && kotlin.math.abs(it.word.length - p.length) <= 1 &&
                    (CorrectionRules.smallEdit(p, it.word) || it.word.length == p.length && common(it.word) &&
                        cost(p, it.word, 22, rows, spatial, complete = true) <= slips) }
                .filter {
                    if (p.length > 3) true
                    else if (p.length == 3 && common(it.word) && CorrectionRules.transposition(p, it.word)) true
                    else spatial != null && scores != null && it.word.length == p.length &&
                        p.indices.all { index -> gridCost(p[index], it.word[index]) <= 4 } &&
                        cost(p, it.word, 22, rows, spatial, complete = true) <= 6 &&
                        scores.probability(it.word, rank[it.word]) - scores.probability(p, rank[p]) >= 5.0
                }
                .map { it.word to (it.score + (if (scores != null) MODEL_TYPO_WEIGHT else 2.5) * it.distance / 10.0 -
                    2.5 * cost(p, it.word, 22, rows, spatial, complete = true) / 10.0 - history.penalty(it.word)) }
                .sortedByDescending { it.second }.toList()
            val keep = wordScore(p, prev, prev2, scores) - if (known) 0.0 else 4.6
            val margin = if (mode == AutocorrectMode.CONFIDENT) 1.2 else .8
            var result = ranked.firstOrNull()?.takeIf { best -> best.second - keep >= margin &&
                (ranked.getOrNull(1)?.let { best.second - it.second >= if (p.length <= 3 && !CorrectionRules.transposition(p, best.first)) 3.0 else margin } != false) }
            // A split pays for both words plus a boundary edit; it must beat every single-word candidate.
            if (!known && p.length >= 4) {
                val splits = ArrayList<Pair<String, Double>>()
                for (i in 1 until p.length) {
                    val first = p.substring(0, i); val second = p.substring(i)
                    if (!rank.containsKey(first) || !rank.containsKey(second)) continue
                    if (mode == AutocorrectMode.CONFIDENT && (i < 2 || second.length < 2 || !common(first) || !common(second))) continue
                    val split = "$first $second"
                    if (history.blocked(word, split) || pairCount(first, second) < 3) continue
                    val following = if (scores != null) scores.following(first, second, rank[second]) + personalScore(second, first, prev)
                        else wordScore(second, first, prev, null)
                    if (scores != null && following < -12.0) continue
                    splits.add(split to (wordScore(first, prev, prev2, scores) + following - .9 - history.penalty(split)))
                }
                splits.sortByDescending { it.second }
                splits.firstOrNull()?.let { best ->
                    if (best.second - maxOf(keep, ranked.firstOrNull()?.second ?: Double.NEGATIVE_INFINITY) >= 2.0 &&
                        (splits.getOrNull(1)?.let { best.second - it.second >= 1.5 } != false)) result = best
                }
            }
            return result?.let { matchCase(it.first, word) }
        }
        return if (english != null) english.withScores { decide(it) } else decide(null)
    }

    /** A bounded editor span, used by both IMEs so joins share ordinary correction undo. */
    fun correction(before: String, mode: AutocorrectMode, taps: ComposingTaps? = null,
                   allowed: Boolean = true, userTyped: Boolean = false, allowJoin: Boolean = true): WordCorrection? {
        if (!allowed) return null
        val word = before.takeLastWhile(TextInputRules::wordChar)
        if (userTyped) noteTyped(word)
        updateContext(before, word)
        val context = CorrectionRules.context(before, word.length)
        val fix = autocorrect(word, mode, context.lastOrNull(), context.getOrNull(context.size - 2), taps,
            sentenceStart = CorrectionRules.sentenceStart(before, word.length))
        if (fix != null) return WordCorrection(word, fix)
        if (mode != AutocorrectMode.AGGRESSIVE || !allowJoin || word.isEmpty() || !CorrectionRules.eligible(word)) return null
        val prefix = before.dropLast(word.length)
        if (!prefix.endsWith(" ") || prefix.endsWith("  ")) return null
        val first = prefix.dropLast(1).takeLastWhile(TextInputRules::wordChar)
        if (!CorrectionRules.eligible(first) || word.any { it.isUpperCase() } || typedTwice(first) || typedTwice(word)) return null
        val original = "$first $word"; val joined = first.lowercase() + word.lowercase()
        if (userTyped) noteTyped(original)
        if (!common(joined) || history.blocked(original, joined)) return null
        // "Rare" includes contextually rare pairs (key board); established phrases (in to) stay intact.
        if (pairCount(first.lowercase(), word.lowercase()) >= 3) return null
        val preceding = CorrectionRules.context(before, original.length)
        val prev = preceding.lastOrNull(); val prev2 = preceding.getOrNull(preceding.size - 2)
        fun join(scores: EnglishContext.Scores?): WordCorrection? {
            val neural = scores?.takeIf { it.lastWord == first.lowercase() }
            val joinedScore = (neural?.preceding(joined, rank[joined])?.plus(personalScore(joined, prev, prev2))
                ?: wordScore(joined, prev, prev2, null)) - .9 - history.penalty(joined)
            val firstScore = neural?.preceding(first.lowercase(), rank[first.lowercase()]) ?: wordScore(first.lowercase(), prev, prev2, null)
            val secondScore = wordScore(word.lowercase(), first.lowercase(), prev, neural)
            val keep = firstScore + secondScore - (if (rank.containsKey(first.lowercase())) 0.0 else 4.6) - (if (rank.containsKey(word.lowercase())) 0.0 else 4.6) - 2.5
            // A pair absent from the corpus pays a boundary-error prior as well as LM likelihood.
            return if (joinedScore - keep >= 4.0) WordCorrection(original, matchCase(joined, first)) else null
        }
        return if (english != null) english.withScores { join(it) } else join(null)
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
        return prefix.length >= 2 && !candidateIndex.hasPrefix(p) && learnedWords.ceiling(p)?.startsWith(p) != true
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
        observed.putIfAbsent(w, 2)
        lastUse[w] = now()
        user[w] = ((user[w] ?: 0) + 1).coerceAtMost(1_000_000)
        learnedWords.add(w)
        if (prev != null) {
            val pairs = userPairs.getOrPut(prev) { HashMap() }
            pairs[w] = ((pairs[w] ?: 0) + 1).coerceAtMost(1_000_000)
            if (prev2 != null) {
                val triples = userTriples.getOrPut("$prev2\t$prev") { HashMap() }
                triples[w] = ((triples[w] ?: 0) + 1).coerceAtMost(1_000_000)
            }
        }
        trimLearning()
        cachedCandidates = null
        dirty = true
    }

    /** Called only when the committed word survived the next edit. */
    fun observe(word: String, prev: String? = null, prev2: String? = null, picked: Boolean = false) {
        val w = word.lowercase()
        if (w.length !in 2..64 || !w.all { it.isLetter() || it == '\'' }) return
        observed[w] = ((observed[w] ?: 0) + if (picked) 2 else 1).coerceAtMost(2)
        lastUse[w] = now()
        dirty = true
        if (rank.containsKey(w) || observed.getValue(w) >= 2) learn(w, prev, prev2)
        trimLearning()
    }
    fun observeFinal(text: String, prev: String?, prev2: String?, picked: Boolean = false) {
        var previous = prev; var older = prev2
        for (word in text.split(' ')) { observe(word, previous, older, picked); older = previous; previous = word.lowercase() }
    }
    fun typedTwice(word: String) = (observed[word.lowercase()] ?: 0) >= 2
    fun isLearned(word: String) = user.containsKey(word.lowercase())

    fun refreshLearning() { if (generation != PersonalStore.generation(directory)) reloadLearning() }

    fun reloadLearning() {
        user.clear(); learnedWords.clear(); userPairs.clear(); userTriples.clear(); observed.clear(); lastUse.clear()
        generation = PersonalStore.generation(directory)
        history.load(if (correctionFile.exists()) correctionFile.readText() else "")
        if (userFile.exists()) userFile.forEachLine { line ->
            val p = line.split('\t')
            if (p.size == 2) p[1].toIntOrNull()?.takeIf { it > 0 }?.let { user[p[0]] = it }
        }
        fun load(file: File, n: Int, rows: MutableMap<String, MutableMap<String, Int>>) {
            if (file.exists()) file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == n + 1) p.last().toIntOrNull()?.takeIf { it > 0 }?.let {
                    rows.getOrPut(p.take(n - 1).joinToString("\t")) { HashMap() }[p[n - 1]] = it
                }
            }
        }
        load(pairFile, 2, userPairs); load(tripleFile, 3, userTriples)
        decayedAt = now()
        if (metadataFile.exists()) metadataFile.forEachLine { line ->
            val p = line.split('\t')
            if (p.size == 2 && p[0] == "@decay") decayedAt = p[1].toLongOrNull() ?: now()
            else if (p.size == 3) {
                observed[p[0]] = p[1].toIntOrNull() ?: 0
                lastUse[p[0]] = p[2].toLongOrNull() ?: 0
            }
        }
        for (word in user.keys) { observed.putIfAbsent(word, 2); lastUse.putIfAbsent(word, now()) }
        learnedWords.addAll(user.keys)
        dirty = !metadataFile.exists() && user.isNotEmpty(); cachedCandidates = null
        decay(); trimLearning()
        topWords = (base.take(300) + user.keys).distinct().sortedWith(compareByDescending<String> { frequency(it) }.thenBy { it }).take(300)
    }
    private fun decay() {
        val period = 30L * 24 * 60 * 60 * 1000
        val periods = ((now() - decayedAt).coerceAtLeast(0) / period).coerceAtMost(31).toInt()
        if (periods == 0) return
        fun halve(row: MutableMap<String, Int>) {
            val entries = row.entries.iterator()
            while (entries.hasNext()) { val e = entries.next(); val count = e.value ushr periods
                if (count == 0) entries.remove() else e.setValue(count) }
        }
        halve(user)
        for (row in userPairs.values) halve(row)
        for (row in userTriples.values) halve(row)
        userPairs.entries.removeAll { it.value.isEmpty() }; userTriples.entries.removeAll { it.value.isEmpty() }
        learnedWords.retainAll(user.keys)
        decayedAt += periods * period
        cachedCandidates = null; dirty = true
    }
    private fun trimLearning() {
        if (observed.size <= 5000) return
        dirty = true; cachedCandidates = null
        val evicted = lastUse.keys.sortedWith(compareBy<String> { lastUse[it] ?: 0 }.thenBy { it }).take(observed.size - 5000)
        for (word in evicted) {
            user.remove(word); observed.remove(word); lastUse.remove(word); learnedWords.remove(word)
            userPairs.entries.removeAll { it.key == word }
            userTriples.entries.removeAll { it.key.split('\t').contains(word) }
            for (row in userPairs.values) row.remove(word)
            for (row in userTriples.values) row.remove(word)
        }
    }

    fun save(): java.util.concurrent.Future<*>? {
        decay()
        if (!dirty && !saveFailed) return null
        val metadata = buildString {
            append("@decay\t$decayedAt\n")
            for ((word, seen) in observed) append("$word\t$seen\t${lastUse[word] ?: 0}\n")
        }
        val snapshot = PersonalStore.Snapshot(user, userPairs, userTriples, metadata, history.serialize())
        val savedGeneration = generation
        dirty = false
        saveFailed = false
        return PersonalStore.submit {
            try { if (PersonalStore.generation(directory) == savedGeneration) snapshot.write(userFile, pairFile, tripleFile, metadataFile, correctionFile) }
            catch (e: java.io.IOException) { saveFailed = true; throw e }
        }
    }

    private fun matchCase(w: String, prefix: String) = when {
        prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase()
        prefix.isNotEmpty() && prefix[0].isUpperCase() -> w.replaceFirstChar { it.uppercase() }
        else -> w
    }

    /** Bundled dictionary in frequency-rank order; callers receive a bounded copy. */
    fun chordWords(): List<String> = base.take(20_000)

    // Read-only gesture inputs; tap ranking and its caches remain unchanged.
    fun hasWord(word: String): Boolean = rank.containsKey(word.lowercase()) || user.containsKey(word.lowercase())
    fun gestureWords(): List<String> = base
    fun gestureUserWords(): Set<String> = user.keys.toSet()
    class GestureScoring(val words: Set<String>, val frequency: (String) -> Double,
                         val context: (String?, String) -> Double)
    /** Capture mutable learning once per gesture; decoder workers never read tap caches or live maps. */
    fun gestureScoring(): GestureScoring {
        val words = user.toMap()
        val pairs = userPairs.mapValues { it.value.toMap() }
        val triples = userTriples.mapValues { it.value.toMap() }
        val tables = seedTables
        fun personal(word: String, prev: String?, prev2: String?): Double {
            val pair = pairs[prev]?.get(word) ?: 0
            val triple = if (prev != null && prev2 != null) triples["$prev2\t$prev"]?.get(word) ?: 0 else 0
            return ln(1.0 + (words[word] ?: 0) * .05 * ((rank[word] ?: 5000) + 10)) +
                2.25 * ln(1.0 + pair) + 2.7 * ln(1.0 + triple)
        }
        return GestureScoring(words.keys, { word ->
            val corpus = rank[word]?.let { tables?.frequencies?.getOrNull(it) } ?: 0.0
            1.0 / ((rank[word] ?: 5000) + 10) + .2 * corpus + (words[word] ?: 0) * .05
        }, { prev, word ->
            english?.withScores { scores ->
                scores?.let { it.probability(word, rank[word]) + personal(word, prev, it.previous2) }
            } ?: run {
                val row = rank[prev]?.let { tables?.pairs?.get(it.toLong()) }
                1.5 * (ln(1.0 + count(row, rank[word])) + 1.5 * ln(1.0 + (pairs[prev]?.get(word) ?: 0)))
            }
        })
    }
    fun gestureFrequency(word: String): Double = frequency(word)
    /** The previous-word part of the suggestion score, for ranking swiped words the same way. */
    fun contextScore(prev: String?, word: String): Double {
        english?.withScores { scores ->
            if (scores != null) return@withScores scores.probability(word, rank[word]) + personalScore(word, prev, scores.previous2)
            null
        }?.let { return it }
        val pairs = rank[prev]?.let { seedTables?.pairs?.get(it.toLong()) }
        val seedPair = count(pairs, rank[word])
        val userPair = userPairs[prev]?.get(word) ?: 0
        return 1.5 * (ln(1.0 + seedPair) + 1.5 * ln(1.0 + userPair))
    }

    private fun personalScore(word: String, prev: String?, prev2: String?): Double {
        val triple = if (prev2 != null && prev != null) userTriples["$prev2\t$prev"]?.get(word) ?: 0 else 0
        return personalScore(word, userPairs[prev]?.get(word) ?: 0, triple)
    }

    private fun personalScore(word: String, pair: Int, triple: Int): Double {
        val wordBoost = ln(1.0 + (user[word] ?: 0) * 0.05 * ((rank[word] ?: 5000) + 10))
        return wordBoost + 2.25 * ln(1.0 + pair) + 2.7 * ln(1.0 + triple)
    }

    internal companion object {
        // Calibrated with real candidate generation; spatial tap costs remain in tenths.
        const val MODEL_TYPO_WEIGHT = 32.0
    }

    internal fun rankedCandidates(prefix: String, prev: String?, prev2: String?, taps: ComposingTaps?) =
        candidates(prefix, prev, prev2, taps).map { Triple(it.word, it.score, it.correction) }
}
