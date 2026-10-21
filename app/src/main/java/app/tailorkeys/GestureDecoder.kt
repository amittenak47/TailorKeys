package app.tailorkeys

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/** SHARK2-style, on-device matcher. No Android dependencies, so the same code runs in the JVM harness. */
class GestureDecoder(words: List<String>) {
    data class Point(val x: Float, val y: Float, val time: Long = 0)
    data class Candidate(val word: String, val score: Double, val prefix: String = word)
    data class DoubleHint(val letter: Char, val pathIndex: Int)
    private class Entry(val word: String, val letters: String) {
        var template: FloatArray? = null
        var shape: FloatArray? = null
    }
    private val buckets = HashMap<Pair<Char, Char>, ArrayList<Entry>>()
    private val known = HashSet<String>()
    private val learned = HashSet<String>()
    private var keys = emptyMap<Char, Point>()
    private var keyWidth = 1f
    private val cached = ArrayList<Entry>()

    init { addWords(words) }

    /** Only newly learned words touch the index; dictionary ranks/frequencies remain owned by Predictor. */
    fun addWords(words: Collection<String>) {
        for (word in words) {
            if (word.isEmpty() || !word.all { it.isLetter() || it == '\'' } || !known.add(word)) continue
            val letters = word.filter { it.isLetter() }.fold(StringBuilder()) { b, ch ->
                if (b.lastOrNull() != ch) b.append(ch)
                b
            }.toString()
            if (letters.isEmpty()) continue
            buckets.getOrPut(letters.first() to letters.last()) { ArrayList() }.add(Entry(word, letters))
        }
    }

    fun setUserWords(words: Set<String>) {
        val removed = learned.filter { it !in words }
        if (removed.isNotEmpty()) {
            clearTemplates()
            for (word in removed) { known.remove(word); learned.remove(word) }
            for (row in buckets.values) row.removeAll { it.word in removed }
            buckets.entries.removeAll { it.value.isEmpty() }
        }
        for (word in words) if (word !in known) learned.add(word)
        addWords(words)
    }

    /** Centres are already transformed into view coordinates. Invalidate lazy templates only on geometry changes. */
    fun setKeys(map: Map<Char, Point>, width: Float) {
        if (keys == map && keyWidth == width) return
        keys = map.toMap(); keyWidth = max(1f, width)
        clearTemplates()
    }

    private fun clearTemplates() {
        for (entry in cached) { entry.template = null; entry.shape = null }
        cached.clear()
    }

    /**
     * N=50; score = -12 * (shape + 1.5 * location) + 0.6 * ln(freq) + context.
     * Shape is centroid/maximum-span normalized, location is divided by key width.
     * The JVM jitter harness tunes k=12: geometry wins over frequency on neighbouring paths.
     * Endpoints allow 1.1 key widths; length allows 1/8..2x visited keys (+2), including double letters.
     */
    fun decode(path: List<Point>, previous: String?, frequency: (String) -> Double,
               context: (String?, String) -> Double, hints: List<DoubleHint> = emptyList(),
               prefix: String = "", partial: Boolean = false): List<Candidate> {
        if (path.size < 2 || keys.isEmpty()) return emptyList()
        if (cached.size >= 2048) clearTemplates()
        val input = resample(path)
        val shape = normalize(input)
        val start = path.first(); val end = path.last()
        var observedLength = 0f
        for (i in 1 until path.size) observedLength += distance(path[i - 1], path[i])
        val typed = prefix.lowercase()
        val first = if (typed.isNotEmpty()) listOf(typed.first()) else
            keys.filter { it.key.isLetter() && distance(it.value, start) <= keyWidth * 1.1f }.keys
        val last = if (partial) keys.keys else
            keys.filter { it.key.isLetter() && distance(it.value, end) <= keyWidth * 1.1f }.keys
        var count = 0
        var prior: Char? = null
        for (i in 0 until N) {
            val nearest = keys.minByOrNull { (_, p) -> hypot(p.x - input[2*i], p.y - input[2*i+1]) }?.key
            if (nearest != prior) { count++; prior = nearest }
        }
        val top = ArrayList<Candidate>(13)
        for (a in first) for (b in last) for (entry in buckets[a to b].orEmpty()) {
            if (!entry.word.startsWith(typed)) continue
            var letters = entry.letters
            var consumed = entry.word.length
            var offset = 0
            if (typed.isNotEmpty()) {
                val overlap = keys[typed.last()]?.let { distance(it, start) <= keyWidth * .55f } == true
                offset = (typed.length - if (overlap) 1 else 0).coerceAtLeast(0)
                letters = collapse(entry.word.substring(offset))
                if (letters.isEmpty()) continue
                if (keys[letters.first()]?.let { distance(it, start) > keyWidth * 1.1f } != false) continue
            }
            if (partial) {
                // Match the observed portion of a word, rather than treating a mid-word key as its end.
                var stop = -1; var best = Float.MAX_VALUE; var length = 0f
                for (i in letters.indices) {
                    val point = keys[letters[i]] ?: continue
                    if (i > 0) length += keys[letters[i - 1]]?.let { distance(it, point) } ?: 0f
                    val endpoint = distance(point, end)
                    if (endpoint > keyWidth * 1.1f) continue
                    val cost = endpoint + .35f * kotlin.math.abs(length - observedLength)
                    if (cost < best) { best = cost; stop = i }
                }
                if (stop < 0) continue
                letters = letters.substring(0, stop + 1)
                var groups = 0; var cursor = offset
                while (cursor < entry.word.length && groups < letters.length) {
                    val ch = entry.word[cursor++]
                    if (!ch.isLetter()) continue
                    while (cursor < entry.word.length && entry.word[cursor] == ch) cursor++
                    groups++
                }
                consumed = cursor
            }
            if (letters.length !in max(1, count / 8)..max(4, count * 2 + 2)) continue
            val useCache = typed.isEmpty() && !partial
            var template = if (useCache) entry.template else null
            var templateShape = if (useCache) entry.shape else null
            if (template == null) {
                val ideal = ArrayList<Point>(letters.length)
                for (ch in letters) { val point = keys[ch] ?: break; ideal.add(point) }
                if (ideal.size != letters.length) continue
                template = resample(ideal); templateShape = normalize(template)
                if (useCache) { entry.template = template; entry.shape = templateShape; cached.add(entry) }
            }
            val loc = meanDistance(input, template) / keyWidth
            val sh = meanDistance(shape, templateShape!!)
            val hintScore = doubleScore(entry.word.substring(offset), letters, path, hints)
            val score = -12.0 * (sh + 1.5 * loc) + 0.6 * ln(max(1e-12, frequency(entry.word))) + context(previous, entry.word) + hintScore
            val candidate = Candidate(entry.word, score, entry.word.take(consumed))
            val at = top.indexOfFirst { it.score < score || it.score == score && it.word > entry.word }
            if (at >= 0) top.add(at, candidate) else if (top.size < 12) top.add(candidate)
            if (top.size > 12) top.removeAt(12)
        }
        return top
    }

    private fun collapse(word: String): String {
        val result = StringBuilder(word.length)
        for (ch in word) if (ch.isLetter() && result.lastOrNull() != ch) result.append(ch)
        return result.toString()
    }

    private fun doubleScore(word: String, letters: String, path: List<Point>, hints: List<DoubleHint>): Double {
        if (hints.isEmpty()) return 0.0
        var total = 0f
        for (i in 1 until path.size) total += distance(path[i - 1], path[i])
        var idealTotal = 0f
        for (i in 1 until letters.length) idealTotal += distance(keys.getValue(letters[i - 1]), keys.getValue(letters[i]))
        var score = 0.0
        for (hint in hints) {
            var travelled = 0f
            for (i in 1..hint.pathIndex.coerceIn(0, path.lastIndex)) travelled += distance(path[i - 1], path[i])
            val position = if (total > 0) travelled / total else 0f
            var ideal = 0f; var matched = false
            for (i in letters.indices) {
                if (i > 0) ideal += distance(keys.getValue(letters[i - 1]), keys.getValue(letters[i]))
                val fraction = if (idealTotal > 0) ideal / idealTotal else 0f
                if (letters[i] == hint.letter && kotlin.math.abs(fraction - position) < .22f) {
                    // Resolve this occurrence, so a double elsewhere in the word cannot satisfy the hint.
                    var occurrence = 0
                    for (j in 0..i) if (letters[j] == hint.letter) occurrence++
                    var seen = 0; var j = 0
                    while (j < word.length) {
                        val ch = word[j]; var end = j + 1
                        while (end < word.length && word[end] == ch) end++
                        if (ch == hint.letter && ++seen == occurrence && end - j >= 2) matched = true
                        j = end
                    }
                }
            }
            score += if (matched) 5.0 else -1.5
        }
        return score
    }

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
    private fun meanDistance(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in 0 until N) sum += hypot(a[2*i] - b[2*i], a[2*i+1] - b[2*i+1])
        return sum / N
    }
    private fun normalize(points: FloatArray): FloatArray {
        var cx = 0f; var cy = 0f
        var minX = Float.POSITIVE_INFINITY; var minY = minX
        var maxX = Float.NEGATIVE_INFINITY; var maxY = maxX
        for (i in 0 until N) {
            val x = points[2*i]; val y = points[2*i+1]
            cx += x; cy += y
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        cx /= N; cy /= N
        val scale = max(keyWidth * 0.5f, max(maxX - minX, maxY - minY))
        return FloatArray(2 * N) { i -> (points[i] - if (i % 2 == 0) cx else cy) / scale }
    }
    private fun resample(points: List<Point>): FloatArray {
        val cumulative = FloatArray(points.size)
        for (i in 1 until points.size) cumulative[i] = cumulative[i-1] + distance(points[i-1], points[i])
        val result = FloatArray(2 * N)
        var segment = 1
        for (i in 0 until N) {
            val d = cumulative.last() * i / (N - 1)
            while (segment < points.lastIndex && cumulative[segment] < d) segment++
            val hi = segment.coerceAtMost(points.lastIndex)
            val lo = max(0, hi - 1)
            val span = cumulative[hi] - cumulative[lo]
            val f = if (span > 0f) (d - cumulative[lo]) / span else 0f
            result[2*i] = points[lo].x + (points[hi].x - points[lo].x) * f
            result[2*i+1] = points[lo].y + (points[hi].y - points[lo].y) * f
        }
        return result
    }
    private companion object { const val N = 50 }
}
