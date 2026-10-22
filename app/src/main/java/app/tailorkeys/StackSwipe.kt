package app.tailorkeys

import kotlin.math.abs
import kotlin.math.hypot

/** Swipe keys: a tap types the shown face; a mostly vertical swipe steps one face, wrapping both ways. */
internal object StackSwipe {
    /** dp a tap may wobble and still count as a tap. */
    const val TAP_SLOP = 10f
    /** A release this soon after the press is a flick: past the tap slop is enough. */
    const val FLICK_MS = 250L

    /** Whether a finger this far (dp) from where it went down is no longer a tap. */
    fun moved(dx: Float, dy: Float) = hypot(dx, dy) > TAP_SLOP

    /** 1 for up, -1 for down, 0 while undecided. Up to about 60° off vertical still counts. */
    fun direction(dx: Float, dy: Float, keyHeight: Float): Int {
        val threshold = (keyHeight * .3f).coerceIn(14f, 22f)
        return if (abs(dy) >= threshold) vertical(dx, dy) else 0
    }

    /** A quick flick that lifted before [direction] decided. */
    fun flick(dx: Float, dy: Float, ms: Long): Int = if (ms <= FLICK_MS && moved(dx, dy)) vertical(dx, dy) else 0

    private fun vertical(dx: Float, dy: Float) = if (abs(dy) < abs(dx) * .6f) 0 else if (dy < 0) 1 else -1

    /**
     * The face a step lands on; faces wrap in both directions. When the shown face isn't the current
     * layer (123 / #+= while typing letters), a step up lands on the shown face itself, so a swipe
     * either way reaches every face.
     */
    fun next(shown: Int, size: Int, step: Int, shownIsCurrent: Boolean): Int =
        if (!shownIsCurrent && step > 0) shown else Math.floorMod(shown + step, size)
}
