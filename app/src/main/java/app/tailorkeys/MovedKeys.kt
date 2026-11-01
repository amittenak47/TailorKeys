package app.tailorkeys

import kotlin.math.pow

/**
 * Habit slips from moved keys. A letter you moved from where it usually is (g to the right half, say) still gets
 * reached for in its usual place: f tapped for g. So the usual layout is laid over yours, through the letters that
 * didn't move: each tap is placed on the usual layout from its distances to the nearest of them, and its distance to
 * each moved letter (its model feature, StudentModel) becomes the lesser of the real one and the distance to that
 * letter's usual place, plus [PENALTY]. The search then offers the word and the model weighs it like a near miss.
 * The usual layout is the one you'd be used to: the trained split for a split keyboard, staggered QWERTY otherwise.
 * On the tablet (2026-11-01; b, f, g, v counted as moved): brifht, railinf, mornonf and raufht became bright, railing,
 * morning and taught; none of 105 other words typed changed.
 */
internal class MovedKeys private constructor(private val moved: IntArray, private val anchors: IntArray,
                                             private val usual: Map<Char, Pair<Float, Float>>) {
    val any get() = moved.isNotEmpty()

    fun apply(taps: List<FloatArray>): List<FloatArray> {
        if (moved.isEmpty() || anchors.size < 3) return taps
        return taps.map { t ->
            // A tap right on a key that didn't move is where that key usually is; others are placed from the nearest
            // unmoved keys (a resize can bend their distances, so the hit key, when there is one, is surer).
            var hit = 0
            for (i in 0 until N) if (t[i] < t[hit]) hit = i
            val (px, py) = if (hit in anchors && t[hit] < ON_KEY) usual.getValue(TapSearch.LETTERS[hit]) else {
                val near = anchors.sortedBy { t[it] }.take(ANCHORS)
                TapSearch.Layout(near.associate { TapSearch.LETTERS[it] to usual.getValue(TapSearch.LETTERS[it]) }).pointOf(t)
            }
            if (px.isNaN() || py.isNaN()) return@map t
            val out = t.copyOf()
            for (m in moved) {
                val (mx, my) = usual.getValue(TapSearch.LETTERS[m])
                out[m] = minOf(out[m], (px - mx).pow(2) + (py - my).pow(2) + PENALTY)
            }
            out
        }
    }

    companion object {
        const val PENALTY = 0.5f
        /** How far (squared, in key widths) a letter sits from its usual neighbours, as a median, to count as moved. */
        const val AWAY = 4f
        /** The unmoved letters nearest a tap that place it on the usual layout. */
        private const val ANCHORS = 6
        /** Within this (squared, in key widths) of a key's centre, a tap counts as on that key. */
        private const val ON_KEY = .25f
        private const val N = 27 // a to z and the apostrophe

        private fun grid(rows: List<Triple<Int, Float, String>>) =
            rows.flatMap { (r, c0, keys) -> keys.mapIndexed { i, ch -> ch to (c0 + i + .5f to r + .5f) } }.toMap()
        /** Template.SPLIT, as the model was trained on it (tools/lm/taps.py split): whole cells, five and five. */
        private val SPLIT = grid(listOf(Triple(0, 0f, "qwert"), Triple(0, 6f, "yuiop"), Triple(1, 0f, "asdfg"),
            Triple(1, 6f, "hjkl'"), Triple(2, 0f, "zxcvb"), Triple(2, 6f, "nm")))
        /** A phone's staggered QWERTY (tools/lm/taps.py staggered). */
        private val STAGGERED = grid(listOf(Triple(0, 0f, "qwertyuiop"), Triple(1, .5f, "asdfghjkl"), Triple(2, 1.5f, "zxcvbnm")))

        private fun d2(p: Pair<Float, Float>, q: Pair<Float, Float>) = (p.first - q.first).pow(2) + (p.second - q.second).pow(2)

        /**
         * The letters moved from where they usually are, from each letter's centre tap here (KeyboardView.centreTap):
         * clearly away from their usual neighbours (moved within a half), or on a split keyboard on the other half.
         * Resized and nudged keys stay near their neighbours: their real distances already say where they are.
         */
        fun of(split: Boolean, centre: (Char) -> FloatArray?): MovedKeys {
            val usual = if (split) SPLIT else STAGGERED
            val here = (0 until N).associateWith { centre(TapSearch.LETTERS[it]) }
            val a = TapSearch.LETTERS.indexOf('a')
            val l = TapSearch.LETTERS.indexOf('l')
            val mid = usual.values.map { it.first }.let { (it.min() + it.max()) / 2 }
            val known = (0 until N).filter { TapSearch.LETTERS[it] in usual && here[it] != null }
            // First the letters on the other half; then letters away from their usual neighbours that stayed, so a key
            // left behind by its moved neighbours (f, when g went right) doesn't count as moved.
            val sided = known.filter { i ->
                val ch = TapSearch.LETTERS[i]
                split && ch.isLetter() && (here.getValue(i)!![a] < here.getValue(i)!![l]) != (usual.getValue(ch).first < mid)
            }.toSet()
            val moved = known.filter { i ->
                val ch = TapSearch.LETTERS[i]
                if (i in sided) return@filter true
                if (!ch.isLetter()) return@filter false
                val f = here.getValue(i)!!
                val near = known.filter { j -> j != i && j !in sided && TapSearch.LETTERS[j].isLetter() &&
                    d2(usual.getValue(ch), usual.getValue(TapSearch.LETTERS[j])) <= 2f }.map { f[it] }.sorted()
                near.isNotEmpty() && near[near.size / 2] > AWAY
            }
            return MovedKeys(moved.toIntArray(), (known - moved.toSet()).toIntArray(), usual)
        }
    }
}
