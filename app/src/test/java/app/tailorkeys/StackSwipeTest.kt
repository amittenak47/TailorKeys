package app.tailorkeys

import org.junit.Assert.*
import org.junit.Test

class StackSwipeTest {
    @Test fun tapsWobbleSwipesNeedOnlyAShortMostlySidewaysMove() {
        for ((dx, dy) in listOf(0f to 0f, 6f to 6f, -3f to 9f)) assertFalse("$dx,$dy", StackSwipe.moved(dx, dy))
        assertTrue(StackSwipe.moved(11f, 0f))
        // A 60 dp key decides at 18 dp; wide keys stop at 22 dp, narrow ones start at 14 dp.
        assertEquals(1, StackSwipe.direction(-18f, 0f, 60f))
        assertEquals(-1, StackSwipe.direction(18f, 0f, 60f))
        assertEquals(0, StackSwipe.direction(-17f, 0f, 60f))
        assertEquals(1, StackSwipe.direction(-22f, 0f, 200f))
        assertEquals(-1, StackSwipe.direction(14f, 0f, 30f))
        // Diagonal is fine; mostly up or down is not a swipe.
        assertEquals(1, StackSwipe.direction(-20f, 25f, 60f))
        assertEquals(0, StackSwipe.direction(-20f, 40f, 60f))
    }
    @Test fun quickFlicksCountPastTheTapSlop() {
        assertEquals(-1, StackSwipe.flick(12f, 2f, 120))
        assertEquals(1, StackSwipe.flick(-11f, -4f, 200))
        assertEquals(0, StackSwipe.flick(12f, 0f, 400))
        assertEquals(0, StackSwipe.flick(8f, 0f, 100))
        assertEquals(0, StackSwipe.flick(5f, 15f, 100))
    }
    @Test fun facesWrapBothWays() {
        // abc / 123 / #+= showing its current layer: left and right both wrap.
        assertEquals(1, StackSwipe.next(0, 3, 1, true))
        assertEquals(2, StackSwipe.next(0, 3, -1, true))
        assertEquals(0, StackSwipe.next(2, 3, 1, true))
        assertEquals(1, StackSwipe.next(2, 3, -1, true))
        // 123 / #+= while typing letters: left reaches 123, right reaches #+=.
        assertEquals(0, StackSwipe.next(0, 2, 1, false))
        assertEquals(1, StackSwipe.next(0, 2, -1, false))
    }
}
