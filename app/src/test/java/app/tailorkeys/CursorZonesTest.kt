package app.tailorkeys

import org.junit.Assert.*
import org.junit.Test

class CursorZonesTest {
    private fun vertical(x: Float) = listOf(ZonePoint(x, 0f), ZonePoint(x, 200f))
    private fun zone(left: Float, right: Float) = CursorZone(vertical(left), vertical(right), 0f, 200f)

    @Test fun autoRequiresRoomOnBothSidesAtEveryRow() {
        val enough = listOf(zone(0f, 32f), zone(268f, 300f))
        assertEquals(CursorZoneKind.OUTER, cursorZoneKind(CursorControl.AUTO, enough, 32f))
        val pinched = CursorZone(vertical(0f), listOf(ZonePoint(40f, 0f), ZonePoint(31f, 100f), ZonePoint(40f, 200f)), 0f, 200f)
        assertEquals(CursorZoneKind.CENTER, cursorZoneKind(CursorControl.AUTO, listOf(pinched, enough[1]), 32f))
        assertEquals(CursorZoneKind.CENTER, cursorZoneKind(CursorControl.AUTO, listOf(enough[0], zone(269f, 300f)), 32f))
        assertEquals(CursorZoneKind.CENTER, cursorZoneKind(CursorControl.AUTO, listOf(enough[0]), 32f))
    }

    @Test fun modesFallbackAndEmptySideOverride() {
        val narrow = listOf(zone(0f, 10f), zone(290f, 300f))
        assertEquals(CursorZoneKind.CENTER, cursorZoneKind(CursorControl.OUTER_EDGES, narrow, 32f))
        assertEquals(CursorZoneKind.OUTER, cursorZoneKind(CursorControl.OUTER_EDGES, listOf(zone(0f, 40f), zone(260f, 300f)), 32f))
        assertEquals(CursorZoneKind.OUTER, cursorZoneKind(CursorControl.AUTO, narrow, 32f, hasEmptySide = true))
        assertEquals(CursorZoneKind.CENTER, cursorZoneKind(CursorControl.CENTER_GAP, narrow, 32f, hasEmptySide = true))
        for (emptySide in listOf(false, true)) {
            assertEquals(CursorZoneKind.NONE, cursorZoneKind(CursorControl.OFF, narrow, 32f, emptySide))
        }
    }

    @Test fun outerHitTestingFollowsBothTiltedEdges() {
        val left = CursorZone(vertical(0f), listOf(ZonePoint(60f, 0f), ZonePoint(40f, 200f)), 0f, 200f)
        val right = CursorZone(listOf(ZonePoint(240f, 0f), ZonePoint(260f, 200f)), vertical(300f), 0f, 200f)
        assertTrue(left.contains(54f, 20f))
        assertFalse(left.contains(54f, 180f))
        assertFalse(left.contains(-1f, 20f))
        assertTrue(right.contains(246f, 20f))
        assertFalse(right.contains(246f, 180f))
        assertFalse(right.contains(301f, 20f))
        assertFalse(left.contains(10f, -1f))
        assertFalse(right.contains(280f, 200f))
        assertEquals(50f, CursorZone.edgeAt(left.right, 100f), 0.001f)
    }

    @Test fun emptySideExcludesTheSwitchRectangle() {
        val empty = CursorZone(vertical(0f), vertical(100f), 0f, 200f, ZoneRect(44f, 144f, 92f, 192f))
        assertTrue(empty.contains(70f, 140f))
        assertTrue(empty.contains(20f, 170f))
        assertFalse(empty.contains(70f, 170f))
        assertFalse(empty.contains(44f, 144f))
        assertTrue(empty.contains(93f, 170f))
    }

    @Test fun slideSettlesWithCubicEaseOut() {
        assertEquals(200f, slideOffset(200f, 0, false), 0f)
        assertEquals(25f, slideOffset(200f, 125, false), 0.001f)
        assertEquals(0f, slideOffset(200f, 250, false), 0f)
        assertEquals(0f, slideOffset(200f, 500, false), 0f)
        assertEquals(-200f, slideOffset(-200f, 0, false), 0f)
        val firstHalfTravel = 200f - slideOffset(200f, 125, false)
        val secondHalfTravel = slideOffset(200f, 125, false)
        assertTrue(firstHalfTravel > secondHalfTravel)
        for (elapsed in listOf(0L, 125L, 250L)) assertEquals(0f, slideOffset(200f, elapsed, true), 0f)
    }
}
