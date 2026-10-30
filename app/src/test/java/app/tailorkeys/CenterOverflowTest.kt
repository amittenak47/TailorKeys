package app.tailorkeys

import app.tailorkeys.layout.CenterSide.AUTO
import app.tailorkeys.layout.CenterSide.LEFT
import app.tailorkeys.layout.CenterSide.RIGHT
import app.tailorkeys.layout.GridKey
import app.tailorkeys.layout.KeyGrid
import org.junit.Assert.assertEquals
import org.junit.Test

class CenterOverflowTest {
    @Test fun autoKeysFillTheLeftFirstAndBothSidesGetTheSameSlots() {
        assertEquals(OutsideColumns(listOf(0, 1), listOf(2)), CenterOverflow.columns(listOf(AUTO, AUTO, AUTO)))
        assertEquals(2, CenterOverflow.columns(listOf(AUTO, AUTO, AUTO)).slots)
        assertEquals(OutsideColumns(listOf(0, 1), listOf(2, 3)), CenterOverflow.columns(listOf(AUTO, AUTO, AUTO, AUTO)))
        assertEquals(OutsideColumns(listOf(0), emptyList()), CenterOverflow.columns(listOf(AUTO)))
        assertEquals(0, CenterOverflow.columns(emptyList()).slots)
    }

    @Test fun chosenSidesAreKeptAndAutoKeysEvenTheRest() {
        // Key 1 on the right, key 3 on the left: key 2 (Auto) joins key 3.
        assertEquals(OutsideColumns(listOf(1, 2), listOf(0)), CenterOverflow.columns(listOf(RIGHT, AUTO, LEFT)))
        assertEquals(OutsideColumns(listOf(0, 1), listOf(2, 3)), CenterOverflow.columns(listOf(LEFT, LEFT, AUTO, AUTO)))
        // More chosen for one side: both columns grow to its count.
        val lopsided = CenterOverflow.columns(listOf(RIGHT, RIGHT, RIGHT, AUTO))
        assertEquals(OutsideColumns(listOf(3), listOf(0, 1, 2)), lopsided)
        assertEquals(3, lopsided.slots)
    }

    // 4 px outside each half before any inset, a 400 px gap with both halves at the edges (392 px of room to move
    // in, keeping 8 px between them), and center keys needing 60 px.
    private fun drag(side: Int, dx: Float, left: Float, right: Float) =
        CenterOverflow.drag(side, dx, left, right, room = 392f, gapAtEdges = 400f, outside = 4f, need = 60f)

    @Test fun withoutRoomOutsideTheGapStopsWhereTheCenterColumnStillFits() {
        // The right half is at the screen edge, so the keys can't go outside: the left half stops at a 60 px gap.
        assertEquals(340f, drag(0, 500f, 0f, 0f), 0f)
        assertEquals(100f, drag(0, 100f, 0f, 0f), 0f)
    }

    @Test fun withRoomOutsideBothHalvesTheGapCanClose() {
        // Both halves 100 px in: 104 px outside each. The left half can go all the way in, to an 8 px gap.
        assertEquals(192f, drag(0, 500f, 100f, 100f), 0f)
        // Left half 320 px in, right half at its edge: insets from 20 to 56 px fit neither the center column nor
        // the outside one, so the right half snaps to the nearer end of that band.
        assertEquals(-56f, drag(1, -50f, 320f, 0f), 0f)
        assertEquals(-20f, drag(1, -30f, 320f, 0f), 0f)
        assertEquals(-60f, drag(1, -60f, 320f, 0f), 0f)
    }

    @Test fun movingOutIsAlwaysAllowed() {
        assertEquals(-50f, drag(0, -50f, 200f, 100f), 0f)
    }

    @Test fun aChosenSideIsSavedWithTheLayoutAndSurvivesCopies() {
        val grid = KeyGrid.default()
        val key = GridKey(99, 0, KeyGrid.CENTER, 1, mutableListOf("-"))
        grid.items += key
        key.side = RIGHT
        val reloaded = KeyGrid.fromJson(grid.toJson())!!
        assertEquals(RIGHT, reloaded.items.first { it.r == key.r && it.c == key.c }.side)
        assertEquals(RIGHT, grid.copy().items.first { it.id == key.id }.side)
        assertEquals(AUTO, GridKey(0, 0, 0, 1, mutableListOf("a")).side)
    }
}
