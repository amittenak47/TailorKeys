package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GapKeyTest {
    private fun left(g: KeyGrid, r: Int) = g.items.filter { it.r == r && it.c < 5 }.sortedBy { it.c }.map { it.keys.single() }

    @Test fun gapsHoldTheirCellsSoTheRowsKeysKeepTheirSize() {
        val g = KeyGrid.default()
        // Remove t: the row closes up and its four keys widen.
        assertTrue(g.remove(g.items.single { it.keys == listOf("t") }))
        assertEquals(4, g.rowColumns(0, 0))
        // A gap after r takes the fifth cell back, so q w e r are their usual size again.
        val r = g.items.single { it.keys == listOf("r") }
        assertTrue(g.placeFromTray(g.tray.size) { g.insertShift(it, r, before = false) })
        assertEquals(listOf("q", "w", "e", "r", Keys.GAP), left(g, 0))
        assertEquals(5, g.rowColumns(0, 0))
        // The gap chip is endless: placing one leaves the tray as it was.
        assertEquals(listOf(listOf("t")), g.tray)
        val restored = KeyGrid.fromJson(g.toJson())!!
        assertEquals(5, restored.rowColumns(0, 0))
        assertTrue(restored.isSound())
    }

    @Test fun aKeyDroppedOnAGapTakesItsPlaceAndRemovingAGapClosesTheRow() {
        val g = KeyGrid.default()
        assertTrue(g.remove(g.items.single { it.keys == listOf("t") }))
        val w = g.items.single { it.keys == listOf("w") }
        assertTrue(g.placeFromTray(g.tray.size) { g.insertShift(it, w, before = true) })
        assertEquals(listOf("q", Keys.GAP, "w", "e", "r"), left(g, 0))
        // A gap never joins a swipe key.
        val gap = g.items.single { it.isGap }
        g.combine(g.items.single { it.keys == listOf("e") }, gap)
        assertEquals(listOf("q", Keys.GAP, "w", "e", "r"), left(g, 0))
        assertTrue(g.fillGap(g.items.single { it.keys == listOf("r") }, gap))
        assertEquals(listOf("q", "r", "w", "e"), left(g, 0))
        // A gap removed is gone, not set aside, and the row closes up.
        assertTrue(g.placeFromTray(g.tray.size) { g.insertShift(it, g.items.single { k -> k.keys == listOf("q") }, before = true) })
        val trayBefore = g.tray.toList()
        assertTrue(g.remove(g.items.single { it.isGap }))
        assertEquals(trayBefore, g.tray)
        assertEquals(listOf("q", "r", "w", "e"), left(g, 0))
    }
}
