package app.tailorkeys

/** Adjustments relative to the Size sliders; tilt is in degrees before the right half is mirrored. */
data class HalfShape(val width: Int = 100, val height: Int = 100, val tilt: Int = 0) {
    /** Bounds also apply to imported layouts and live corner drags. */
    fun bounded() = HalfShape(width.coerceIn(50, 150), height.coerceIn(50, 150), tilt.coerceIn(-20, 20))
}

/** A half edit replaces the chosen adjustment, and both adjustments when Mirror is on. */
internal fun SizeProfile.shapeHalf(side: Int, shape: HalfShape, mirror: Boolean): SizeProfile {
    val value = shape.bounded()
    return copy(leftShape = if (side == 0 || mirror) value else leftShape,
        rightShape = if (side == 1 || mirror) value else rightShape)
}
