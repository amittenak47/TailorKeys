package app.tailorkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovedKeysTest {
    private val split = listOf(Triple(0, 0f, "qwert"), Triple(0, 6f, "yuiop"), Triple(1, 0f, "asdfg"), Triple(1, 6f, "hjkl'"),
        Triple(2, 0f, "zxcvb"), Triple(2, 6f, "nm"))
    private val staggered = listOf(Triple(0, 0f, "qwertyuiop"), Triple(1, .5f, "asdfghjkl"), Triple(2, 1.5f, "zxcvbnm"))

    /** A layout from [rows], with [moved] letters placed elsewhere: each letter's centre tap as squared distances. */
    private fun layout(rows: List<Triple<Int, Float, String>>, moved: Map<Char, Pair<Float, Float>> = emptyMap()): (Char) -> FloatArray? {
        val centres = rows.flatMap { (r, c0, keys) -> keys.mapIndexed { i, ch -> ch to (c0 + i + .5f to r + .5f) } }.toMap() + moved
        return { ch -> centres[ch]?.let { (x, y) -> FloatArray(TapSearch.FEATURES) { i ->
            if (i == TapSearch.SPACE) 4f else centres[TapSearch.LETTERS[i]]?.let { (cx, cy) -> (x - cx) * (x - cx) + (y - cy) * (y - cy) } ?: 100f } } }
    }
    private fun at(c: Char) = TapSearch.LETTERS.indexOf(c)

    @Test fun theUsualLayoutsMoveNothing() {
        assertFalse(MovedKeys.of(true, layout(split)).any)
        assertFalse(MovedKeys.of(false, layout(staggered)).any)
    }

    @Test fun aTapOnFIsAlsoNearAGMovedRight() {
        val centre = layout(split, mapOf('g' to (6.5f to 1.5f), 'h' to (7.5f to 1.5f)))
        val moved = MovedKeys.of(true, centre)
        assertTrue(moved.any)
        val f = centre('f')!!
        assertTrue(f[at('g')] > 4f)
        val seen = moved.apply(listOf(f)).single()
        // f and g are neighbours on the usual split (1 key apart, squared), plus the penalty.
        assertEquals(1f + MovedKeys.PENALTY, seen[at('g')], 1e-3f)
        // Letters that didn't move keep their real distances.
        assertEquals(f[at('d')], seen[at('d')], 0f)
    }

    @Test fun aKeyMovedWithinItsHalfCounts() {
        // q and t swapped on the left half: a tap on t, where q usually is, is also near q.
        val centre = layout(split, mapOf('q' to (4.5f to .5f), 't' to (.5f to .5f)))
        val seen = MovedKeys.of(true, centre).apply(listOf(centre('t')!!)).single()
        assertEquals(MovedKeys.PENALTY, seen[at('q')], 1e-3f)
    }

    @Test fun anUnsplitKeyboardComparesWithStaggeredQwerty() {
        // Not split, g swapped with p: a tap on p, where g usually is, is also near g.
        val centre = layout(staggered, mapOf('g' to (9.5f to .5f), 'p' to (5f to 1.5f)))
        val moved = MovedKeys.of(false, centre)
        assertTrue(moved.any)
        assertEquals(MovedKeys.PENALTY, moved.apply(listOf(centre('p')!!)).single()[at('g')], 1e-3f)
    }
}
