package app.tailorkeys

import app.tailorkeys.layout.CenterSide
import kotlin.math.abs
import kotlin.math.max

/**
 * The center keys outside the halves, as indexes into the center keys (top to bottom). Both columns get [slots]
 * slots, so the keys on either side are the same size; the shorter column leaves its last slots empty.
 */
internal data class OutsideColumns(val left: List<Int>, val right: List<Int>) {
    val slots get() = max(left.size, right.size)
}

internal object CenterOverflow {
    /** How wide the gap (or each outside space) must be to hold center keys, in key widths. */
    const val MIN_KEYS = 0.8f

    /**
     * Keys set to a side go there; Auto keys fill the left first, top to bottom, until the sides are as even as
     * the fixed ones allow, so three Auto keys put the top two on the left and the third on the right.
     */
    fun columns(sides: List<CenterSide>): OutsideColumns {
        val fixedLeft = sides.count { it == CenterSide.LEFT }
        val fixedRight = sides.count { it == CenterSide.RIGHT }
        val auto = sides.size - fixedLeft - fixedRight
        // The left side's share of the Auto keys that evens the columns, the extra key going left.
        var toLeft = ((sides.size + 1) / 2 - fixedLeft).coerceIn(0, auto)
        val left = ArrayList<Int>()
        val right = ArrayList<Int>()
        for ((i, side) in sides.withIndex()) when (side) {
            CenterSide.LEFT -> left += i
            CenterSide.RIGHT -> right += i
            CenterSide.AUTO -> if (toLeft > 0) { left += i; toLeft-- } else right += i
        }
        return OutsideColumns(left, right)
    }

    /**
     * How far a grip drag of [dx] may move [side]'s half (0 left, 1 right) when the layout has center keys. They
     * need [need] px either in the gap between the halves or outside each half, so the drag stops where neither
     * would hold, and snaps to the nearer of the two edges in between: past it the keys fit outside. [left] and
     * [right] are the insets, [room] how far both may move in together (see [SplitPlacement]), [gapAtEdges] the
     * gap with both halves at the screen edges, and [outside] the room beyond each half before any inset.
     */
    fun drag(side: Int, dx: Float, left: Float, right: Float, room: Float, gapAtEdges: Float, outside: Float, need: Float): Float {
        val free = SplitPlacement.drag(side, dx, left, right, room)
        // Work on the moving half's inset: it grows as the half moves in.
        val inset = if (side == 0) left else right
        val other = if (side == 0) right else left
        val wanted = if (side == 0) inset + free else inset - free
        // Insets up to gapLimit keep the gap wide enough; from outLimit on, both outside spaces are (when the
        // other half's is). In between, neither holds.
        val gapLimit = gapAtEdges - other - need
        val outLimit = if (outside + other >= need) need - outside else Float.POSITIVE_INFINITY
        val maxInset = max(0f, room - other)
        val allowed = if (wanted <= gapLimit || wanted >= outLimit) wanted
        else listOfNotNull(gapLimit.takeIf { it >= 0f }, outLimit.takeIf { it <= maxInset })
            .minByOrNull { abs(it - wanted) } ?: inset
        return if (side == 0) allowed - inset else inset - allowed
    }
}
