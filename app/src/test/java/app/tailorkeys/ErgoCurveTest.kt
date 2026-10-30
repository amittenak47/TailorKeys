package app.tailorkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErgoCurveTest {
    @Test fun rowsArcHighestUnderTheMiddleFingerAndMirror() {
        val centres = (0..4).map { ErgoCurve.raise(it + .5f) }
        assertEquals(ErgoCurve.RISE, centres[2], 1e-6f)
        // Smooth: rising to the apex, falling after it, and the same on both sides.
        assertTrue(centres[0] < centres[1] && centres[1] < centres[2] && centres[2] > centres[3] && centres[3] > centres[4])
        assertEquals(centres[0], centres[4], 1e-6f)
        assertEquals(centres[1], centres[3], 1e-6f)
        assertEquals(0f, ErgoCurve.raise(0f), 1e-6f)
    }

    @Test fun theBowlIsDeepestAtTheHomeRowUnderTheMiddleFinger() {
        val home = 1
        assertEquals(1f, ErgoCurve.depth(home, home, 2.5f), 1e-6f)
        assertTrue(ErgoCurve.depth(home, home, .5f) < ErgoCurve.depth(home, home, 1.5f))
        assertTrue(ErgoCurve.depth(home + 2, home, 2.5f) < ErgoCurve.depth(home + 1, home, 2.5f))
        assertEquals(ErgoCurve.depth(home - 1, home, 2.5f), ErgoCurve.depth(home + 1, home, 2.5f), 1e-6f)
    }
}
