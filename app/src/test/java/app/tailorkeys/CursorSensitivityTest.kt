package app.tailorkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorSensitivityTest {
    /** Characters moved in one second holding a [push] dp push, with the gap reaching 60 dp each way. */
    private fun charsPerSecond(push: Float, sensitivity: Double): Int {
        val stick = CursorJoystick().apply { reach(60f, 60f); this.sensitivity = sensitivity; speedScale = sensitivity }
        var steps = stick.displace(push, 0)
        for (t in 16L..1000L step 16) steps += stick.advance(t)
        return steps
    }

    @Test fun higherSensitivityMovesFurtherForTheSamePush() {
        for (push in listOf(12f, 30f, 60f)) {
            val low = charsPerSecond(push, 0.5); val normal = charsPerSecond(push, 1.0); val high = charsPerSecond(push, 2.0)
            assertTrue("push $push: $low < $normal < $high", low < normal && normal < high)
        }
    }

    @Test fun pushesNearThePressCreepAndOnlyTheFarEndsJumpByWords() {
        val stick = CursorJoystick().apply { reach(60f, 60f) }
        val speeds = listOf(8f, 20f, 35f, 45f).map { stick.displace(it, 0); assertFalse(stick.byWord); stick.speed }
        assertTrue("$speeds", speeds.zipWithNext().all { (a, b) -> a < b } && speeds[0] < 8)
        stick.displace(-60f, 0)
        assertTrue(stick.byWord)
        assertTrue(stick.speed < 0)
    }

    @Test fun aNarrowGapStillLeavesRoomBeforeWords() {
        // Pressed 10 dp from each edge, the push still has to travel well past them to jump by words.
        val stick = CursorJoystick().apply { reach(10f, 10f) }
        stick.displace(12f, 0)
        assertFalse(stick.byWord)
        stick.displace(32f, 0)
        assertTrue(stick.byWord)
    }

    @Test fun wordStepsGoToWordEndsForwardAndStartsBack() {
        val text = "one two, three"
        fun from(at: Int, steps: Int) = CursorRead().set(text, 0, at, at).target(steps, false, words = true)
        assertEquals(3, from(0, 1))
        assertEquals(7, from(3, 1))
        assertEquals(14, from(7, 1))
        assertEquals(9, from(14, -1))
        assertEquals(4, from(9, -1))
        assertEquals(0, from(9, -2))
    }
}
