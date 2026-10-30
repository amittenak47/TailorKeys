package app.tailorkeys

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the split halves sit, as dragged by their grips: each half's outer edge some way in from its side of the
 * screen, stored in thousandths of the screen width so a rotation keeps the same look. The halves keep their size;
 * [room] is how far in both may move together and still leave the minimum gap between them.
 */
internal object SplitPlacement {
    /** Insets (px) for stored thousandths of [width]; when they don't both fit in [room] they shrink together. */
    fun insets(left: Int, right: Int, width: Float, room: Float): Pair<Float, Float> {
        if (room <= 0f) return 0f to 0f
        val l = left / 1000f * width
        val r = right / 1000f * width
        val total = l + r
        return if (total <= room) l to r else l * room / total to r * room / total
    }

    /** How far a drag of [dx] may move [side]'s half (0 left, 1 right): never off the screen or into the other. */
    fun drag(side: Int, dx: Float, left: Float, right: Float, room: Float): Float =
        if (side == 0) dx.coerceIn(-left, max(-left, room - right - left))
        else dx.coerceIn(min(right, right - (room - left)), right)

    fun permille(px: Float, width: Float) = if (width <= 0f) 0 else (px / width * 1000).roundToInt().coerceIn(0, 1000)
}
