package app.tailorkeys

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/** SHARK2-style, on-device matcher. No Android dependencies, so the same code runs in the JVM harness. */
class GestureDecoder(words: List<String>) {
    data class Point(val x: Float, val y: Float, val time: Long = 0)
    data class Candidate(val word: String, val score: Double)
    private class Entry(val word: String, val letters: String) {
        var template: FloatArray? = null
        var shape: FloatArray? = null
    }
    private val buckets = HashMap<Pair<Char, Char>, ArrayList<Entry>>()
    private val known = HashSet<String>()
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
               context: (String?, String) -> Double): List<Candidate> {
        if (path.size < 2 || keys.isEmpty()) return emptyList()
        // Bound retained templates to a few MB, matching the iOS extension's memory budget.
        if (cached.size >= 2048) clearTemplates()
        val input = resample(path)
        val shape = normalize(input)
        val start = path.first(); val end = path.last()
        val first = keys.filter { it.key.isLetter() && distance(it.value, start) <= keyWidth * 1.1f }.keys
        val last = keys.filter { it.key.isLetter() && distance(it.value, end) <= keyWidth * 1.1f }.keys
        var count = 0
        var prior: Char? = null
        for (i in 0 until N) {
            val nearest = keys.minByOrNull { (_, p) -> hypot(p.x - input[2*i], p.y - input[2*i+1]) }?.key
            if (nearest != prior) { count++; prior = nearest }
        }
        val top = ArrayList<Candidate>(13)
        for (a in first) for (b in last) for (entry in buckets[a to b].orEmpty()) {
            if (entry.word.length !in max(1, count / 8)..max(4, count * 2 + 2)) continue
            var template = entry.template
            if (template == null) {
                val ideal = ArrayList<Point>(entry.letters.length)
                for (ch in entry.letters) { val p = keys[ch] ?: break; ideal.add(p) }
                if (ideal.size != entry.letters.length) continue
                template = resample(ideal)
                entry.template = template
                entry.shape = normalize(template)
                cached.add(entry)
            }
            val loc = meanDistance(input, template) / keyWidth
            val sh = meanDistance(shape, entry.shape!!)
            val score = -12.0 * (sh + 1.5 * loc) + 0.6 * ln(max(1e-12, frequency(entry.word))) + context(previous, entry.word)
            val candidate = Candidate(entry.word, score)
            val at = top.indexOfFirst { it.score < score || it.score == score && it.word > entry.word }
            if (at >= 0) top.add(at, candidate) else if (top.size < 12) top.add(candidate)
            if (top.size > 12) top.removeAt(12)
        }
        return top
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
