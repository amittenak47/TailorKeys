package app.tailorkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPlacementTest {
    @Test fun storedInsetsShrinkTogetherWhenTheyNoLongerFit() {
        // 1000 px wide with 300 px of room: 100 + 100 fits; 300 + 100 shrinks to 225 + 75.
        assertEquals(100f to 100f, SplitPlacement.insets(100, 100, 1000f, 300f))
        assertEquals(225f to 75f, SplitPlacement.insets(300, 100, 1000f, 300f))
        assertEquals(0f to 0f, SplitPlacement.insets(300, 100, 1000f, 0f))
    }

    @Test fun aDraggedHalfStopsAtItsScreenEdgeAndAtTheOtherHalf() {
        // Left half at 50 px in, right half at 100 px in, 300 px of room.
        assertEquals(-50f, SplitPlacement.drag(0, -80f, 50f, 100f, 300f), 0f)
        assertEquals(150f, SplitPlacement.drag(0, 400f, 50f, 100f, 300f), 0f)
        assertEquals(30f, SplitPlacement.drag(0, 30f, 50f, 100f, 300f), 0f)
        // The right half moves left by growing its inset: dx = -150 leaves it at 250, the most beside 50.
        assertEquals(-150f, SplitPlacement.drag(1, -400f, 50f, 100f, 300f), 0f)
        assertEquals(100f, SplitPlacement.drag(1, 300f, 50f, 100f, 300f), 0f)
    }

    @Test fun positionsAreStoredInThousandthsOfTheWidth() {
        assertEquals(125, SplitPlacement.permille(150f, 1200f))
        assertEquals(0, SplitPlacement.permille(-5f, 1200f))
    }

    @Test fun everyWideEnoughSpaceTheSettingAllowsHoldsTheCursor() {
        fun vertical(x: Float) = listOf(ZonePoint(x, 0f), ZonePoint(x, 200f))
        fun zone(left: Float, right: Float) = CursorZone(vertical(left), vertical(right), 0f, 200f)
        val leftEdge = zone(0f, 60f); val rightEdge = zone(290f, 300f); val center = zone(120f, 180f)
        val outer = listOf(leftEdge, rightEdge)
        // The right edge is too narrow; the left edge and the center are wide enough.
        assertEquals(listOf(leftEdge), splitCursorZones(CursorControl.OUTER_EDGES, center, outer, 32f))
        assertEquals(listOf(center), splitCursorZones(CursorControl.CENTER_GAP, center, outer, 32f))
        assertEquals(emptyList<CursorZone>(), splitCursorZones(CursorControl.OFF, center, outer, 32f))
        // Halves dragged close together leave no center cursor.
        assertEquals(emptyList<CursorZone>(), splitCursorZones(CursorControl.CENTER_GAP, zone(140f, 160f), outer, 32f))
        // Auto and Touch bar use the touch bars instead.
        assertEquals(emptyList<CursorZone>(), splitCursorZones(CursorControl.AUTO, center, outer, 32f))
        assertEquals(emptyList<CursorZone>(), splitCursorZones(CursorControl.TOUCH_BAR, center, outer, 32f))
    }

    @Test fun touchBarsAreTheSplitDefaultAndAnOptionElsewhere() {
        assertTrue(usesTouchBar(CursorControl.AUTO, split = true))
        assertFalse(usesTouchBar(CursorControl.AUTO, split = false))
        assertTrue(usesTouchBar(CursorControl.TOUCH_BAR, split = false))
        for (control in listOf(CursorControl.CENTER_GAP, CursorControl.OUTER_EDGES, CursorControl.OFF))
            for (split in listOf(false, true)) assertFalse(usesTouchBar(control, split))
    }
}
