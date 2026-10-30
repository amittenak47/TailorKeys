package app.tailorkeys

import org.junit.Assert.assertEquals
import org.junit.Test

class CursorRepeatTest {
    @Test fun nothingRepeatsBeforeTheDelay() {
        val r = CursorRepeat(-1, 1000L, 1.0)
        assertEquals(0, r.advance(1100L))
        assertEquals(0, r.advance(1000L + CursorRepeat.DELAY))
    }

    @Test fun heldItRepeatsTheBarsWayAndSpeedsUp() {
        val r = CursorRepeat(1, 0L, 1.0)
        // The first second after the delay averages a bit over the minimum speed.
        var moved = 0
        for (t in CursorRepeat.DELAY..CursorRepeat.DELAY + 1000 step 16) moved += r.advance(t)
        assertEquals(true, moved in 9..16)
        // At full speed a second moves the maximum.
        val late = CursorRepeat(-1, 0L, 1.0)
        late.advance(5000L)
        var fast = 0
        for (t in 5010L..6000L step 10) fast += late.advance(t)
        assertEquals(-CursorRepeat.MAX_SPEED.toInt(), fast)
    }

    @Test fun sensitivityScalesTheSpeed() {
        val r = CursorRepeat(1, 0L, 0.5)
        r.advance(5000L)
        var moved = 0
        for (t in 5010L..6000L step 10) moved += r.advance(t)
        assertEquals(CursorRepeat.MAX_SPEED.toInt() / 2, moved)
    }
}
