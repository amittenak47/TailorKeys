package app.tailorkeys

import kotlin.math.abs
import kotlin.math.hypot

/** Swipe keys: a tap types the shown face; a mostly sideways swipe steps one face, wrapping both ways. */
internal object StackSwipe {
    /** dp a tap may wobble and still count as a tap. */
    const val TAP_SLOP = 10f
    /** A release this soon after the press is a flick: past the tap slop is enough. */
    const val FLICK_MS = 250L

    /** Whether a finger this far (dp) from where it went down is no longer a tap. */
    fun moved(dx: Float, dy: Float) = hypot(dx, dy) > TAP_SLOP

    /**
     * 1 for left (the next face slides in from the right), -1 for right, 0 while undecided. Up to about 60° off
     * horizontal still counts. Sideways because a thumb or a floating finger rolls left and right more easily than
     * it pulls down, and a downward swipe on the bottom row runs off the keyboard.
     */
    fun direction(dx: Float, dy: Float, keyWidth: Float): Int {
        val threshold = (keyWidth * .3f).coerceIn(14f, 22f)
        return if (abs(dx) >= threshold) sideways(dx, dy) else 0
    }

    /** A quick flick that lifted before [direction] decided. */
    fun flick(dx: Float, dy: Float, ms: Long): Int = if (ms <= FLICK_MS && moved(dx, dy)) sideways(dx, dy) else 0

    private fun sideways(dx: Float, dy: Float) = if (abs(dx) < abs(dy) * .6f) 0 else if (dx < 0) 1 else -1

    /**
     * The face a step lands on; faces wrap in both directions. When the shown face isn't the current
     * layer (123 / #+= while typing letters), a step left lands on the shown face itself, so a swipe
     * either way reaches every face.
     */
    fun next(shown: Int, size: Int, step: Int, shownIsCurrent: Boolean): Int =
        if (!shownIsCurrent && step > 0) shown else Math.floorMod(shown + step, size)
}
