package app.tailorkeys

import kotlin.math.abs

/**
 * How far each hand has drifted off the keys, learned from the words you keep. On a tablet the hands float with
 * nothing to rest against, so a hand's taps slide off their keys together; the keyboard moves each tap back by its
 * hand's drift before choosing the key and before autocorrect reads it. In key spacings, per half of the keyboard
 * (the left five columns and the right five: each hand on a split tablet, each thumb on a phone).
 *
 * DriftEvalTest: on simulated drifting hands, words right went from 88-90% to 98.6% (75-80% to 94-95% for sloppier
 * taps, 64-68% to 91-92% for fast drift), and steady hands were unchanged. Calibrating from known text first added
 * nothing: this learns a fixed habit within a couple of words anyway.
 */
class TouchDrift {
    /** Left x, left y, right x, right y. */
    private val offset = FloatArray(4)

    fun x(hand: Int) = offset[hand * 2]
    fun y(hand: Int) = offset[hand * 2 + 1]

    /** One tap of a settled word: [dx], [dy] from the centre of the key that word says it meant. */
    fun learn(hand: Int, dx: Float, dy: Float) {
        // More than a key away is another kind of slip (a skipped or doubled letter), not drift.
        if (hand !in 0..1 || abs(dx) > 1f || abs(dy) > 1f) return
        val i = hand * 2
        offset[i] = (offset[i] + RATE * (dx - offset[i])).coerceIn(-LIMIT, LIMIT)
        offset[i + 1] = (offset[i + 1] + RATE * (dy - offset[i + 1])).coerceIn(-LIMIT, LIMIT)
    }

    fun reset() = offset.fill(0f)

    companion object {
        /**
         * How much each tap moves the estimate: it settles in about ten taps, two words. 0.03-0.25 were tried; slower
         * lagged a wandering hand, faster chased each tap's own scatter.
         */
        const val RATE = .1f
        /** Never move a tap more than this, so a run of odd words can't walk the keys away. */
        const val LIMIT = .6f
    }
}
