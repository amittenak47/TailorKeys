package app.tailorkeys

import org.junit.Assert.assertEquals
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

    @Test fun theDefaultIsUnchanged() {
        val stick = CursorJoystick().apply { reach(60f, 60f) }
        stick.displace(60f, 0)
        assertEquals(CursorJoystick.MAX_SPEED, stick.speed, 1e-9)
    }
}
