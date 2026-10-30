package app.tailorkeys

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchDriftTest {
    @Test fun settlesOnAHandsOffsetWithinAFewWords() {
        val drift = TouchDrift()
        repeat(20) { drift.learn(1, .4f, -.2f) }
        assertEquals(.4f, drift.x(1), .05f)
        assertEquals(-.2f, drift.y(1), .05f)
        // The other hand hasn't moved.
        assertEquals(0f, drift.x(0), 0f)
    }

    @Test fun tapsFarFromTheSettledKeyTeachNothing() {
        val drift = TouchDrift()
        drift.learn(0, 1.6f, 0f) // a skipped letter lines this tap up with the wrong key
        drift.learn(-1, .3f, 0f) // the center column belongs to neither hand
        assertEquals(0f, drift.x(0), 0f)
    }

    @Test fun neverMovesTapsMoreThanItsLimit() {
        val drift = TouchDrift()
        repeat(200) { drift.learn(0, .95f, .95f) }
        assertEquals(TouchDrift.LIMIT, drift.x(0), 0f)
        assertEquals(TouchDrift.LIMIT, drift.y(0), 0f)
    }
}
