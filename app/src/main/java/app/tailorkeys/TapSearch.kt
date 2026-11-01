package app.tailorkeys

import java.io.InputStream
import kotlin.math.ln
import kotlin.math.min

/**
 * Candidate words for a typed slot, from its taps: the words the taps could have meant. The student model ranks them;
 * this search only has to not miss the right word. A port of tools/lm/candidates.py, which the model was trained
 * with, so the keyboard offers the model the same candidates (TapSearchTest compares the two).
 *
 * Every word of nearby length is costed against the taps, with one alignment slip at most:
 *   same length   each tap's squared distance to the word's letter (wrong keys cost by how far the tap landed)
 *   swap          two neighbouring letters tapped in the wrong order
 *   skipped key   the word has one letter more, with no tap
 *   extra tap     the taps have one more, matched to nothing
 * A word with an apostrophe is also matched without it (dont -> don't), nearly free. Three lists are kept: the
 * cheapest by taps alone, then with a little and with more weight on how common the word is, and a fourth of words
 * with a skipped key. Two-word splits follow, where a tap near the space bar was the space. When no word fits the taps
 * well, a shape list comes last: words that fit once each hand's shared shift is taken out (typing without looking).
 *
 * Features are per tap: squared distances in key spacings to a to z, the apostrophe (100 when the layout lacks it),
 * then the space bar.
 */
internal class TapSearch(val words: List<String>) {
    private class Bucket(val ids: IntArray, val codes: ByteArray, val extra: FloatArray)

    /** Word ids by word. */
    val index: Map<String, Int> = HashMap<String, Int>(words.size * 2).apply { words.forEachIndexed { i, w -> put(w, i) } }
    private val logRank = FloatArray(words.size) { ln(1.0 + it).toFloat() }
    private val buckets: Map<Int, Bucket>
    /** Shape words the last search added at the end of its list: a caller that caps the list grows its cap by these. */
    var added = 0
        private set

    init {
        val forms = HashMap<Int, MutableList<Triple<Int, String, Float>>>()
        words.forEachIndexed { i, w ->
            forms.getOrPut(w.length) { ArrayList() }.add(Triple(i, w, 0f))
            if ('\'' in w) w.replace("'", "").let { forms.getOrPut(it.length) { ArrayList() }.add(Triple(i, it, APOSTROPHE)) }
        }
        buckets = forms.mapValues { (n, v) ->
            val codes = ByteArray(v.size * n)
            v.forEachIndexed { j, (_, form, _) -> for (c in 0 until n) codes[j * n + c] = LETTERS.indexOf(form[c]).toByte() }
            Bucket(IntArray(v.size) { v[it].first }, codes, FloatArray(v.size) { v[it].third })
        }
    }

    /** The layout for the shape list: each letter's key centre in key spacings, as the features measure them. */
    class Layout(val centres: Map<Char, Pair<Float, Float>>) {
        internal val x = FloatArray(LETTERS.length) { 50f }
        internal val y = FloatArray(LETTERS.length) { 50f }
        internal val hand = ByteArray(LETTERS.length)

        init {
            val xs = centres.values.map { it.first }
            val mid = ((xs.minOrNull() ?: 0f) + (xs.maxOrNull() ?: 0f)) / 2
            for ((ch, p) in centres) {
                val i = LETTERS.indexOf(ch)
                if (i < 0) continue
                x[i] = p.first; y[i] = p.second; hand[i] = if (p.first > mid) 1 else 0
            }
        }

        /** A tap's position back from its squared distances to the keys, by least squares (candidates.point_of). */
        fun pointOf(features: FloatArray): Pair<Float, Float> {
            val keys = LETTERS.indices.filter { LETTERS[it] in centres }
            val k0 = keys[0]
            var aa = 0.0; var ab = 0.0; var bb = 0.0; var ra = 0.0; var rb = 0.0
            for (i in keys.drop(1)) {
                val a = 2.0 * (x[i] - x[k0]); val b = 2.0 * (y[i] - y[k0])
                val r = features[k0].toDouble() - features[i] + x[i] * x[i] + y[i] * y[i] - x[k0] * x[k0] - y[k0] * y[k0]
                aa += a * a; ab += a * b; bb += b * b; ra += a * r; rb += b * r
            }
            val det = aa * bb - ab * ab
            return ((ra * bb - rb * ab) / det).toFloat() to ((rb * aa - ra * ab) / det).toFloat()
        }
    }

    /** Each word's cheapest cost against the taps (inf for no reading), and with one skipped key only. */
    fun costs(features: List<FloatArray>): Pair<FloatArray, FloatArray> {
        val n = features.size
        val l = LETTERS.length
        // f[t * l + c]: tap t's cost for letter c.
        val f = FloatArray(n * l).also { a -> for (t in 0 until n) for (c in 0 until l) a[t * l + c] = features[t][c] * TAP }
        val best = FloatArray(words.size) { Float.POSITIVE_INFINITY }
        val gap = FloatArray(words.size) { Float.POSITIVE_INFINITY }
        buckets[n]?.let { b ->
            val codes = b.codes
            for (j in b.ids.indices) {
                val at = j * n
                var plain = 0f
                for (t in 0 until n) plain += f[t * l + codes[at + t]]
                var c = plain
                // A swap changes only the two letters it swaps.
                for (s in 0 until n - 1) {
                    val x = codes[at + s].toInt(); val y = codes[at + s + 1].toInt()
                    c = min(c, plain - f[s * l + x] - f[(s + 1) * l + y] + f[s * l + y] + f[(s + 1) * l + x] + SWAP)
                }
                val id = b.ids[j]
                best[id] = min(best[id], c + b.extra[j])
            }
        }
        if (n >= 1) buckets[n + 1]?.let { b ->
            val m = n + 1
            val codes = b.codes
            for (j in b.ids.indices) {
                val at = j * m
                // Letter g skipped: taps before it read letters 0 until g, the rest read the letters after it.
                var before = 0f
                var after = 0f
                for (t in 0 until n) after += f[t * l + codes[at + t + 1]]
                var c = Float.POSITIVE_INFINITY
                for (g in 0 until m) {
                    c = min(c, before + after)
                    if (g < n) { before += f[g * l + codes[at + g]]; after -= f[g * l + codes[at + g + 1]] }
                }
                val id = b.ids[j]
                gap[id] = min(gap[id], c + SKIP + b.extra[j])
            }
            for (i in best.indices) best[i] = min(best[i], gap[i])
        }
        if (n >= 2) buckets[n - 1]?.let { b ->
            val m = n - 1
            val codes = b.codes
            for (j in b.ids.indices) {
                val at = j * m
                // Tap g extra: letters before it read taps 0 until g, the rest read the taps after it.
                var before = 0f
                var after = 0f
                for (t in 0 until m) after += f[(t + 1) * l + codes[at + t]]
                var c = Float.POSITIVE_INFINITY
                for (g in 0 until n) {
                    c = min(c, before + after)
                    if (g < m) { before += f[g * l + codes[at + g]]; after -= f[(g + 1) * l + codes[at + g]] }
                }
                val id = b.ids[j]
                best[id] = min(best[id], c + EXTRA + b.extra[j])
            }
        }
        return best to gap
    }

    /**
     * Up to 4k words: the k cheapest by taps, the k cheapest with each weight * log(rank) added, the k cheapest with
     * a skipped key (with the first weight), then two-word splits; given the layout, the shape list last when the best
     * word costs more than SHAPE_FIT per tap.
     */
    fun search(features: List<FloatArray>, layout: Layout? = null, k: Int = 8, common: FloatArray = COMMON): List<String> {
        added = 0
        if (features.isEmpty()) return emptyList()
        val (best, gap) = costs(features)
        val out = LinkedHashSet<String>()
        for (i in cheapest(best, 0f, k)) out.add(words[i])
        for (w in common) for (i in cheapest(best, w, k)) out.add(words[i])
        for (i in cheapest(gap, common[0], k)) out.add(words[i])
        for (p in splits(features)) out.add(p)
        val result = out.toMutableList()
        if (layout != null && (best.minOrNull() ?: Float.POSITIVE_INFINITY) > SHAPE_FIT * features.size) {
            val found = shapeWords(features, layout, out)
            added = found.size
            result += found
        }
        return result
    }

    /** The ids of the k cheapest finite scores of cost + weight * log(rank), cheapest first (ties by id). */
    private fun cheapest(cost: FloatArray, weight: Float, k: Int): List<Int> {
        val top = IntArray(k)
        val value = FloatArray(k)
        var count = 0
        for (i in cost.indices) {
            val c = cost[i]
            if (c == Float.POSITIVE_INFINITY) continue
            val s = c + weight * logRank[i]
            if (!s.isFinite() || count == k && s >= value[k - 1]) continue
            var at = if (count < k) count++ else k - 1
            while (at > 0 && value[at - 1] > s) { top[at] = top[at - 1]; value[at] = value[at - 1]; at-- }
            top[at] = i; value[at] = s
        }
        return top.take(count)
    }

    /**
     * Two words where a tap near the space bar was meant as the space: the best word for the taps on each side, at
     * the tries taps nearest the bar.
     */
    fun splits(features: List<FloatArray>, near: Float = 0.6f, tries: Int = 2): List<String> {
        val spots = (1 until features.size - 1).filter { features[it][SPACE] <= near }.sortedBy { features[it][SPACE] }.take(tries)
        val out = ArrayList<String>()
        for (i in spots) {
            val sides = listOf(features.subList(0, i), features.subList(i + 1, features.size)).map { side ->
                val best = costs(side).first
                cheapest(best, 0.35f, 2).map { (best[it] + 0.35f * logRank[it]) to words[it] }
            }
            val pairs = sides[0].flatMap { a -> sides[1].map { b -> (a.first + b.first) to "${a.second} ${b.second}" } }
                .sortedWith(compareBy({ it.first }, { it.second }))
            for ((_, p) in pairs.take(2)) if (p !in out) out.add(p)
        }
        return out
    }

    /** The words that fit best once each hand's shared shift is taken out, none of [exclude]. */
    fun shapeWords(features: List<FloatArray>, layout: Layout, exclude: Set<String> = emptySet()): List<String> {
        val points = features.map { layout.pointOf(it) }
        val take = if (points.size <= 3) SHAPE_SHORT else SHAPE_LONG
        val cost = shapeCosts(points, layout)
        return cheapest(cost, 0.35f, take + exclude.size)
            .map { words[it] }.filter { it !in exclude }.take(take)
    }

    /**
     * For a word, D = tap - key per letter, and a hand's shift is the mean of its D. The plain cost splits into
     * sum |D - shift|^2 (does the shape fit) plus per hand n * |shift|^2 (how far the hand is off); the shape cost keeps
     * the first and prices the second at SHAPE_PRICE, so a word shaped the same elsewhere on the keyboard loses.
     */
    fun shapeCosts(points: List<Pair<Float, Float>>, layout: Layout): FloatArray {
        val out = FloatArray(words.size) { Float.POSITIVE_INFINITY }
        val n = points.size
        val b = buckets[n] ?: return out
        val dx = FloatArray(n); val dy = FloatArray(n)
        for (j in b.ids.indices) {
            val at = j * n
            var cost = 0f
            for (hand in 0..1) {
                var count = 0; var sx = 0f; var sy = 0f
                for (t in 0 until n) {
                    val c = b.codes[at + t].toInt()
                    dx[t] = points[t].first - layout.x[c]; dy[t] = points[t].second - layout.y[c]
                    if (layout.hand[c].toInt() == hand) { count++; sx += dx[t]; sy += dy[t] }
                }
                if (count == 0) continue
                sx /= count; sy /= count
                cost += SHAPE_PRICE * count * (sx * sx + sy * sy)
                for (t in 0 until n) if (layout.hand[b.codes[at + t].toInt()].toInt() == hand) {
                    val ex = dx[t] - sx; val ey = dy[t] - sy
                    cost += ex * ex + ey * ey
                }
            }
            val id = b.ids[j]
            out[id] = min(out[id], TAP * cost + b.extra[j])
        }
        return out
    }

    /**
     * Which tap went with each character of [word], by the cheapest reading with the search's slips: as typed, one
     * swap, one skipped key or one extra tap, and a left-out apostrophe on top. Per character the tap's index or -1
     * for none, and the taps matched to nothing; null when no such reading exists. A space (two words) is matched
     * against the space bar.
     */
    fun align(features: List<FloatArray>, word: String): Alignment? = Companion.align(features, word)

    class Alignment(val taps: IntArray, val unmatched: IntArray)

    companion object {
        const val LETTERS = "abcdefghijklmnopqrstuvwxyz'"
        /** The space bar's column in the features. */
        const val SPACE = 27
        const val FEATURES = 28
        const val SKIP = 2.5f
        const val EXTRA = 2.5f
        const val SWAP = 1.5f
        const val APOSTROPHE = 0.3f
        /** Squared distance is scaled so the centre of the next key (1 spacing away) costs 2, a skip a little more. */
        const val TAP = 2f
        const val MAX_LEN = 24
        const val SHAPE_SHORT = 12
        const val SHAPE_LONG = 6
        const val SHAPE_PRICE = 0.1f
        /** The shape list only when the best word costs more than this per tap (on for 3.3% of normally typed words). */
        const val SHAPE_FIT = 0.5f
        private val COMMON = floatArrayOf(0.35f, 1f)

        /**
         * The words searched (candidates.load_words): words.txt, then the ranked lists merged by rank, words_extra.txt
         * and words_domains.txt, and with offensive their offensive lists too. Letters and apostrophes only.
         */
        fun load(openAsset: (String) -> InputStream, offensive: Boolean, folder: String = "model/"): List<String> {
            // As written: a capitalized line is dropped below, not lowercased, so every word keeps the rank it trained with.
            val words = openAsset("words.txt").bufferedReader().useLines { lines -> lines.map { it.trim() }.filter { it.isNotEmpty() }.toList() }
            val seen = HashSet(words)
            val names = listOf("words_extra.txt", "words_domains.txt") +
                if (offensive) listOf("words_offensive.txt", "words_domains_offensive.txt") else emptyList()
            val ranked = names.flatMap { name ->
                try {
                    openAsset(folder + name).bufferedReader().useLines { lines ->
                        lines.map { line -> line.split('\t').let { it[0].trim() to it.getOrNull(1)?.trim()?.toIntOrNull() } }.toList()
                    }
                } catch (_: java.io.IOException) { emptyList() }
            }.sortedBy { it.second ?: (1 shl 30) }
            val out = ArrayList(words)
            for ((w, _) in ranked) if (w.isNotEmpty() && seen.add(w)) out.add(w)
            return out.filter { w -> w.length in 1..MAX_LEN && w.all { it in LETTERS } }
        }

        fun align(features: List<FloatArray>, word: String): Alignment? {
            val n = features.size
            fun cost(t: Int, ch: Char): Float = when {
                ch == ' ' -> TAP * features[t][SPACE]
                ch in LETTERS -> TAP * features[t][LETTERS.indexOf(ch)]
                else -> TAP
            }
            val forms = mutableListOf(Triple(word, word.indices.toList(), 0f))
            if ('\'' in word) {
                val kept = word.indices.filter { word[it] != '\'' }
                forms.add(Triple(kept.map { word[it] }.joinToString(""), kept, APOSTROPHE))
            }
            var best: Triple<Float, IntArray, IntArray>? = null
            for ((form, where, extra) in forms) {
                val m = form.length
                val readings = ArrayList<Triple<Float, List<Int>, List<Int>>>()
                if (m == n) {
                    readings.add(Triple((0 until n).fold(0f) { s, i -> s + cost(i, form[i]) }, (0 until n).toList(), emptyList()))
                    for (j in 0 until n - 1) {
                        val taps = (0 until n).toMutableList().also { it[j] = j + 1; it[j + 1] = j }
                        readings.add(Triple((0 until n).fold(0f) { s, i -> s + cost(taps[i], form[i]) } + SWAP, taps, emptyList()))
                    }
                } else if (m == n + 1) {
                    for (g in 0 until m) {
                        val taps = (0 until m).map { if (it < g) it else it - 1 }.toMutableList().also { it[g] = -1 }
                        readings.add(Triple(taps.indices.fold(0f) { s, i -> if (taps[i] >= 0) s + cost(taps[i], form[i]) else s } + SKIP,
                            taps, emptyList()))
                    }
                } else if (m == n - 1 && n >= 2) {
                    for (g in 0 until n) {
                        val taps = (0 until m).map { if (it < g) it else it + 1 }
                        readings.add(Triple(taps.indices.fold(0f) { s, i -> s + cost(taps[i], form[i]) } + EXTRA, taps, listOf(g)))
                    }
                }
                for ((c, taps, unmatched) in readings) {
                    if (best == null || c + extra < best.first) {
                        val per = IntArray(word.length) { -1 }
                        for ((i, t) in where.zip(taps)) per[i] = t
                        best = Triple(c + extra, per, unmatched.toIntArray())
                    }
                }
            }
            return best?.let { Alignment(it.second, it.third) }
        }
    }
}
