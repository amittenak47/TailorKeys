package app.tailorkeys

import org.junit.Assert.*
import org.junit.Test

class StackSwipeTest {
    @Test fun tapsWobbleSwipesNeedOnlyAShortMostlyVerticalMove() {
        for ((dx, dy) in listOf(0f to 0f, 6f to 6f, 9f to -3f)) assertFalse("$dx,$dy", StackSwipe.moved(dx, dy))
        assertTrue(StackSwipe.moved(0f, 11f))
        // A 60 dp key decides at 18 dp; tall keys stop at 22 dp, short ones start at 14 dp.
        assertEquals(1, StackSwipe.direction(0f, -18f, 60f))
        assertEquals(-1, StackSwipe.direction(0f, 18f, 60f))
        assertEquals(0, StackSwipe.direction(0f, -17f, 60f))
        assertEquals(1, StackSwipe.direction(0f, -22f, 200f))
        assertEquals(-1, StackSwipe.direction(0f, 14f, 30f))
        // Diagonal is fine; mostly sideways is not a swipe.
        assertEquals(1, StackSwipe.direction(25f, -20f, 60f))
        assertEquals(0, StackSwipe.direction(40f, -20f, 60f))
    }
    @Test fun quickFlicksCountPastTheTapSlop() {
        assertEquals(-1, StackSwipe.flick(2f, 12f, 120))
        assertEquals(1, StackSwipe.flick(-4f, -11f, 200))
        assertEquals(0, StackSwipe.flick(0f, 12f, 400))
        assertEquals(0, StackSwipe.flick(0f, 8f, 100))
        assertEquals(0, StackSwipe.flick(15f, 5f, 100))
    }
    @Test fun facesWrapBothWays() {
        // abc / 123 / #+= showing its current layer: up and down both wrap.
        assertEquals(1, StackSwipe.next(0, 3, 1, true))
        assertEquals(2, StackSwipe.next(0, 3, -1, true))
        assertEquals(0, StackSwipe.next(2, 3, 1, true))
        assertEquals(1, StackSwipe.next(2, 3, -1, true))
        // 123 / #+= while typing letters: up reaches 123, down reaches #+=.
        assertEquals(0, StackSwipe.next(0, 2, 1, false))
        assertEquals(1, StackSwipe.next(0, 2, -1, false))
    }
}
