package app.tailorkeys

enum class CursorControl(val title: String) {
    AUTO("Auto"), CENTER_GAP("Center gap"), OUTER_EDGES("Outer edges"), OFF("Off")
}

internal data class ZonePoint(val x: Float, val y: Float)
internal data class ZoneRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

/** A visible empty region, bounded by row edges in keyboard coordinates. No Android dependencies. */
internal data class CursorZone(
    val left: List<ZonePoint>,
    val right: List<ZonePoint>,
    val top: Float,
    val bottom: Float,
    val excluded: ZoneRect? = null,
) {
    val bounds = ZoneRect(left.minOf { it.x }, top, right.maxOf { it.x }, bottom)

    fun contains(x: Float, y: Float) = bounds.contains(x, y) && excluded?.contains(x, y) != true &&
        x >= edgeAt(left, y) && x <= edgeAt(right, y)

    // Width varies linearly between vertices, so checking every vertex checks every row (and between rows).
    fun hasRoom(minWidth: Float) = (left + right).all {
        edgeAt(right, it.y) - edgeAt(left, it.y) >= minWidth
    }

    companion object {
        fun edgeAt(side: List<ZonePoint>, y: Float): Float {
            if (y <= side.first().y) return side.first().x
            for (i in 1 until side.size) {
                val a = side[i - 1]; val b = side[i]
                if (y <= b.y) return if (b.y - a.y < 0.5f) b.x else a.x + (b.x - a.x) * (y - a.y) / (b.y - a.y)
            }
            return side.last().x
        }
    }
}

internal enum class CursorZoneKind { CENTER, OUTER, EMPTY, NONE }

/** Selects the center gap or outer padding for layouts that are not in one-hand mode. */
internal fun cursorZoneKind(control: CursorControl, outer: List<CursorZone>, minWidth: Float): CursorZoneKind = when (control) {
    CursorControl.OFF -> CursorZoneKind.NONE
    CursorControl.CENTER_GAP -> CursorZoneKind.CENTER
    CursorControl.AUTO, CursorControl.OUTER_EDGES ->
        if (outer.size == 2 && outer.all { it.hasRoom(minWidth) }) CursorZoneKind.OUTER else CursorZoneKind.CENTER
}

internal data class CursorZonePlan(val kind: CursorZoneKind, val showSwitch: Boolean)

/** One-hand mode reserves only the big empty side; its switch remains available with cursor control off. */
internal fun cursorZonePlan(oneHand: Boolean, split: Boolean, control: CursorControl,
    outer: List<CursorZone>, minWidth: Float): CursorZonePlan {
    if (oneHand) return CursorZonePlan(if (control == CursorControl.OFF) CursorZoneKind.NONE else CursorZoneKind.EMPTY, true)
    val kind = cursorZoneKind(control, outer, minWidth)
    return CursorZonePlan(if (kind == CursorZoneKind.CENTER && !split) CursorZoneKind.NONE else kind, false)
}

/** Cubic ease-out: the remaining translation starts at the full distance and settles in 250 ms. */
internal fun slideOffset(distance: Float, elapsedMs: Long, reducedMotion: Boolean): Float {
    if (reducedMotion) return 0f
    val remaining = 1f - (elapsedMs / 250f).coerceIn(0f, 1f)
    return distance * remaining * remaining * remaining
}
