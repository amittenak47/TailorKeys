package app.tailorkeys

import org.junit.Assert.*
import org.junit.Test

class CursorPointerTest {
    @Test fun followsFingerVerticallyWithTheSameSpringAndClampsAtEdges() {
        val p = pointer()
        p.follow(500f)
        p.step(0.5f, .016f, false)
        assertTrue(p.y > 100f && p.y < 190f)
        repeat(180) { p.step(.5f, .016f, false) }
        assertEquals(190f, p.y, .001f)
        p.follow(-100f)
        repeat(180) {
            val old = p.y
            p.step(-.5f, .016f, false)
            assertTrue(p.y in 10f..old)
        }
        assertEquals(10f, p.y, .001f)
        p.follow(80f)
        assertFalse(p.step(1f, 0f, true))
        assertEquals(80f, p.y, 0f)
    }

    @Test fun movingVerticallyRefitsAgainstTiltAndTheSwitch() {
        val z = CursorZone(vertical(0f), listOf(ZonePoint(100f, 0f), ZonePoint(35f, 100f), ZonePoint(100f, 200f)),
            0f, 200f, ZoneRect(30f, 140f, 90f, 195f))
        val p = pointer(20f, 30f, z)
        for (y in listOf(100f, 180f, 20f)) {
            p.follow(y)
            repeat(180) {
                p.step(1f, .016f, false)
                for (i in 0..36) {
                    val a = Math.toRadians(i * 10.0)
                    assertTrue(z.contains(p.x + p.radius * kotlin.math.cos(a).toFloat(), p.y + p.radius * kotlin.math.sin(a).toFloat()))
                }
            }
        }
    }
    private fun vertical(x: Float) = listOf(ZonePoint(x, 0f), ZonePoint(x, 200f))
    private val zone = CursorZone(vertical(0f), vertical(100f), 0f, 200f)
    private fun pointer(x: Float = 50f, y: Float = 100f, zone: CursorZone = this.zone) =
        CursorPointer(zone, x, y, 10f, 1000)

    @Test fun zeroPushIsCenteredAtThePressPoint() {
        val p = pointer(36f, 74f)
        assertEquals(36f, p.target(0f), 0f)
        p.step(0f, 0.016f, false)
        assertEquals(36f, p.x, 0f)
        assertEquals(74f, p.y, 0f)
    }

    @Test fun pushesMirrorAndInterpolateToInsetEdges() {
        val p = pointer()
        for (push in listOf(0f, 0.25f, 0.5f, 1f)) {
            assertEquals(100f, p.target(push) + p.target(-push), 0.0001f)
            assertEquals(50f + 40f * push, p.target(push), 0.0001f)
        }
        assertEquals(10f, p.target(-2f), 0f)
        assertEquals(90f, p.target(2f), 0f)
    }

    @Test fun diskClampsAtAllEdgesAndFitsNarrowZones() {
        val p = pointer(1f, 1f)
        assertEquals(10f, p.x, 0f)
        assertEquals(10f, p.y, 0f)
        val narrow = pointer(zone = CursorZone(vertical(0f), vertical(8f), 0f, 200f))
        assertTrue(narrow.radius <= 4f)
        assertTrue(narrow.x - narrow.radius >= 0f)
        assertTrue(narrow.x + narrow.radius <= 8f)
        assertEquals(190f, pointer(99f, 199f).y, 0f)
    }

    @Test fun diskAvoidsTiltedRowEdgesAndExcludedSwitch() {
        val tilted = CursorZone(vertical(0f), listOf(ZonePoint(100f, 0f), ZonePoint(55f, 100f),
            ZonePoint(100f, 200f)), 0f, 200f)
        val p = pointer(30f, 95f, tilted)
        p.step(1f, 0f, true)
        for (i in 0..360) {
            val angle = Math.toRadians(i.toDouble())
            assertTrue(tilted.contains(p.x + p.radius * kotlin.math.cos(angle).toFloat(),
                p.y + p.radius * kotlin.math.sin(angle).toFloat()))
        }
        val excluded = CursorZone(vertical(0f), vertical(100f), 0f, 200f, ZoneRect(44f, 144f, 92f, 192f))
        val left = pointer(20f, 170f, excluded)
        left.step(1f, 0f, true)
        assertTrue(left.x + left.radius <= 44f)
        val right = pointer(98f, 170f, excluded)
        right.step(-1f, 0f, true)
        assertTrue(right.x - right.radius >= 92f)
    }

    @Test fun springNeverOvershootsEvenWhenPushReverses() {
        val p = pointer()
        repeat(12) { p.step(1f, 0.016f, false) }
        assertTrue(p.x > 50f && p.x < 90f)
        for (push in listOf(-1f, 0.25f, 0f, 1f)) {
            repeat(180) {
                val old = p.x
                val target = p.target(push)
                p.step(push, 0.04f, false)
                assertTrue(p.x >= minOf(old, target) && p.x <= maxOf(old, target))
            }
            assertEquals(p.target(push), p.x, 0.001f)
            assertEquals(0f, p.velocity, 0f)
        }
    }

    @Test fun reducedMotionSnapsAndSkipsFades() {
        val p = pointer()
        p.step(1f, 0.016f, false)
        assertFalse(p.step(-1f, 0f, true))
        assertEquals(10f, p.x, 0f)
        assertEquals(0f, p.velocity, 0f)
        assertEquals(1f, p.alpha(1000, true), 0f)
        assertFalse(p.fading(1000, true))
        p.release(1000, true)
        assertEquals(0f, p.alpha(1000, true), 0f)
    }

    @Test fun fadesInAndOutWithoutJumpingOnAnEarlyLift() {
        val p = pointer()
        assertEquals(0f, p.alpha(1000, false), 0f)
        assertEquals(0.5f, p.alpha(1060, false), 0f)
        assertEquals(1f, p.alpha(1120, false), 0f)
        p.release(1060, false)
        assertEquals(0.5f, p.alpha(1060, false), 0f)
        assertEquals(0.25f, p.alpha(1135, false), 0f)
        assertEquals(0f, p.alpha(1210, false), 0f)
        assertFalse(p.fading(1210, false))
    }
}
