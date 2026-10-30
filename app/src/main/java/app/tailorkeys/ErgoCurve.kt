package app.tailorkeys

/**
 * The shape of a split half, like a Glove80 seen from above. Curve: every row follows the same smooth arc, highest
 * under the middle finger, by moving whole columns up; keys stay upright and keep their size, so none ever overlaps
 * another. Depth: the keys sit in a bowl, deepest at the home row under the middle finger, and are shaded inside
 * their own outlines to look sunk in. Positions are in columns from a half's left edge (0 to 5); both halves
 * mirror, so the apex is mid-half on either side.
 */
internal object ErgoCurve {
    /** How far the apex rises above the half's edges at full curve, in rows. */
    const val RISE = .38f
    private const val APEX = 2.5f

    /** How far above the edges the arc sits [x] columns into the half, in rows. */
    fun raise(x: Float): Float { val t = (x - APEX) / APEX; return (RISE * (1 - t * t)).coerceAtLeast(0f) }

    /** How deep in the bowl a key centred [x] columns in on [row] sits: 1 at the bottom, 0 at the rim. */
    fun depth(row: Int, home: Int, x: Float): Float {
        val dx = (x - APEX) / 3f
        val dy = (row - home) / 2.5f
        return (1 - dx * dx - dy * dy).coerceIn(0f, 1f)
    }
}
