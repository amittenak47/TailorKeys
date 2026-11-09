package app.tailorkeys

/** Adjustments relative to the Size sliders; tilt is in degrees before the right half is mirrored. */
data class HalfShape(val width: Int = 100, val height: Int = 100, val tilt: Int = 0) {
    /** Bounds also apply to imported layouts and live corner drags. */
    fun bounded() = HalfShape(width.coerceIn(50, 150), height.coerceIn(50, 150), tilt.coerceIn(-20, 20))
}

/**
 * A half edit replaces the chosen adjustment. With Mirror on, the other half takes the same value only for what this
 * edit changed (a tilt drag matches the tilts): what it was given before Mirror was turned on stays its own.
 */
internal fun SizeProfile.shapeHalf(side: Int, shape: HalfShape, mirror: Boolean): SizeProfile {
    val value = shape.bounded()
    val old = if (side == 0) leftShape else rightShape
    val other = if (side == 0) rightShape else leftShape
    val mirrored = if (!mirror) other else HalfShape(
        if (value.width != old.width) value.width else other.width,
        if (value.height != old.height) value.height else other.height,
        if (value.tilt != old.tilt) value.tilt else other.tilt)
    return if (side == 0) copy(leftShape = value, rightShape = mirrored) else copy(leftShape = mirrored, rightShape = value)
}
