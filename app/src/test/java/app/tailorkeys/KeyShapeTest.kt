package app.tailorkeys

import app.tailorkeys.layout.GridKey
import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Keys
import app.tailorkeys.layout.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KeyShapeTest {
    private fun left(k: GridKey) = k.c + k.edges[0]
    private fun right(k: GridKey) = k.c + k.w + k.edges[2]

    /** The row's keys in order, edge to edge from its first cell to its last. */
    private fun assertCloses(g: KeyGrid, r: Int, region: Int) {
        val row = g.items.filter { it.r == r && KeyGrid.region(it.c) == region }.sortedBy { left(it) }
        assertEquals(row.minOf { it.c }.toFloat(), left(row.first()), 1e-3f)
        assertEquals(row.maxOf { it.c + it.w }.toFloat(), right(row.last()), 1e-3f)
        for (i in 1 until row.size) assertEquals(right(row[i - 1]), left(row[i]), 1e-3f)
    }

    @Test fun edgesSurviveTheLayoutCode() {
        val g = KeyGrid.template(Template.SPLIT)
        val bs = g.items.first { Keys.BACKSPACE in it.keys }
        bs.edges = floatArrayOf(-0.25f, 0f, 0f, 0.5f)
        val back = KeyGrid.fromJson(g.toJson())!!.items.first { Keys.BACKSPACE in it.keys }
        assertTrue(back.edges.contentEquals(floatArrayOf(-0.25f, 0f, 0f, 0.5f)))
        assertTrue(g.copy().items.first { Keys.BACKSPACE in it.keys }.edges.contentEquals(bs.edges))
        // A key that fills its cell writes nothing extra.
        assertFalse(KeyGrid.template(Template.SPLIT).toJson().contains("\"e\":["))
    }

    @Test fun aShrunkKeysRowClosesUpAroundIt() {
        val g = KeyGrid.template(Template.SPLIT)
        val bs = g.items.first { Keys.BACKSPACE in it.keys }
        val region = KeyGrid.region(bs.c)
        bs.edges = floatArrayOf(0.4f, 0f, 0f, 0f)
        assertTrue(g.compactRow(bs.r, region, bs))
        assertCloses(g, bs.r, region)
        // The shrunk key kept its size and place; its neighbours took the room.
        assertTrue(bs.edges.contentEquals(floatArrayOf(0.4f, 0f, 0f, 0f)))
        // A second time nothing moves.
        assertFalse(g.compactRow(bs.r, region, bs))
    }

    @Test fun compactAllLeavesUntouchedRowsAlone() {
        val g = KeyGrid.template(Template.SPLIT)
        val key = g.items.first { it.r == 1 && KeyGrid.region(it.c) == 0 }
        key.edges = floatArrayOf(0f, 0f, 0.5f, 0f)
        g.compactAll(key)
        assertCloses(g, key.r, 0)
        assertTrue(g.items.filter { it.r != key.r }.all { k -> k.edges.all { abs(it) < 1e-4f } })
    }
}
