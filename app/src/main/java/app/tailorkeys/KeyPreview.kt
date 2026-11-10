package app.tailorkeys

import app.tailorkeys.layout.KeyText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The larger copy of a held key (Prefs.KEY_PREVIEW) and where the long-press row goes. Plain geometry in a key's own
 * frame: the origin at the key's centre, x along its row and y down, before the key's tilt or curve is applied.
 */
object KeyPreview {
    const val OFF = 0
    /** The key grows around its own centre. */
    const val IN_PLACE = 1
    /** The key grows upward from its bottom edge, so its letter shows above the finger. */
    const val UPWARD = 2
    /** A balloon joined to the key by a neck, like iOS. */
    const val BALLOON = 3

    /** Keys that type the character they show: letters, digits, punctuation and symbols. */
    fun previewable(code: String?) = code != null && (KeyText.isChar(code) || KeyText.isLiteral(code))

    /**
     * Whether a held key shows its preview. Like iOS it lasts while the key is held and its letter is not typed yet:
     * the next finger landing types it (rollover) and so ends it, as do the long-press row, swipe typing and the lift.
     * With Type on press the letter is typed at once, so there the preview lasts until the next finger lands or the lift.
     */
    fun shows(style: Int, held: Boolean, consumed: Boolean, typedOnPress: Boolean, gliding: Boolean, ownsPopup: Boolean,
              editMode: Boolean, code: String?) =
        style != OFF && held && (!consumed || typedOnPress) && !gliding && !ownsPopup && !editMode && previewable(code)

    /**
     * A preview's box in the key's frame, where its letter goes (between [labelTop] and [labelBottom]), its letter size
     * as a multiple of the key's, and how far it may slide sideways at the screen's edges while staying over its key.
     */
    class Shape(val left: Float, val top: Float, val right: Float, val bottom: Float,
                val labelTop: Float, val labelBottom: Float, val letterScale: Float, val slide: Float)

    /** The preview of a key [w] wide and [h] tall; [gap] is the extra sideways slide allowed (12 dp). */
    fun shape(style: Int, w: Float, h: Float, gap: Float): Shape = when (style) {
        UPWARD -> {
            val top = -h / 2 - 1.1f * h
            Shape(-.65f * w, top, .65f * w, h / 2, top, -h / 2, 1.7f, .15f * w + gap)
        }
        BALLOON -> {
            val bottom = -h / 2 - .18f * h
            val top = bottom - 1.15f * h
            Shape(-.75f * w, top, .75f * w, bottom, top, bottom, 1.7f, .25f * w + gap)
        }
        else -> Shape(-.7f * w, -.7f * h, .7f * w, .7f * h, -.7f * h, .7f * h, 1.6f, .2f * w + gap)
    }

    /**
     * How far to slide a box (in a key's frame) so it stays [margin] inside a view [width] by [height]. [m] maps the
     * key's frame to the view: x' = m0 x + m1 y + m2 and y' = m3 x + m4 y + m5. Sideways slides stop within
     * [slideMin]..[slideMax]; slides down at the top (and up at the bottom) are free. Returns (dx, dy) in the key's frame.
     */
    fun place(m: FloatArray, left: Float, top: Float, right: Float, bottom: Float, width: Float, height: Float, margin: Float,
              slideMin: Float, slideMax: Float): FloatArray {
        var dx = 0f
        var dy = 0f
        val det = m[0] * m[4] - m[1] * m[3]
        if (det == 0f) return floatArrayOf(0f, 0f)
        val xs = floatArrayOf(left, right, right, left)
        val ys = floatArrayOf(top, top, bottom, bottom)
        repeat(4) {
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (i in 0 until 4) {
                val x = xs[i] + dx; val y = ys[i] + dy
                val vx = m[0] * x + m[1] * y + m[2]; val vy = m[3] * x + m[4] * y + m[5]
                minX = min(minX, vx); maxX = max(maxX, vx); minY = min(minY, vy); maxY = max(maxY, vy)
            }
            val ox = if (minX < margin) margin - minX else if (maxX > width - margin) width - margin - maxX else 0f
            val oy = if (minY < margin) margin - minY else if (maxY > height - margin) height - margin - maxY else 0f
            if (abs(ox) < .01f && abs(oy) < .01f) return floatArrayOf(dx, dy)
            // The view's shift (ox, oy) in the key's frame.
            dx = (dx + (m[4] * ox - m[1] * oy) / det).coerceIn(slideMin, slideMax)
            dy += (-m[3] * ox + m[0] * oy) / det
        }
        return floatArrayOf(dx, dy)
    }

    /**
     * The long-press row in a key's frame, before any slide: one cell per choice, the first over the key, the rest
     * running away from the nearer screen edge ([rightward]). [slideMin]..[slideMax] keep a balloon's top over its neck.
     */
    class Row(val centres: FloatArray, val cellWidth: Float, val cellHeight: Float, val cellY: Float,
              val left: Float, val top: Float, val right: Float, val bottom: Float, val slideMin: Float, val slideMax: Float)

    fun row(count: Int, w: Float, h: Float, viewWidth: Float, density: Float, balloon: Boolean, rightward: Boolean): Row {
        val pad = 4 * density
        val cell = min(max(36 * density, w), (viewWidth - 8 * density - 2 * pad) / count.coerceAtLeast(1))
        val dir = if (rightward) 1 else -1
        val centres = FloatArray(count) { dir * it * cell }
        val left = min(0f, centres.lastOrNull() ?: 0f) - cell / 2 - pad
        val right = max(0f, centres.lastOrNull() ?: 0f) + cell / 2 + pad
        val cellHeight = min(.9f * h, 48 * density)
        val bottom = if (balloon) -h / 2 - .18f * h else -h / 2 - 4 * density
        val top = if (balloon) bottom - max(1.15f * h, cellHeight + 2 * pad) else bottom - cellHeight - 2 * pad
        val keep = 12 * density
        return Row(centres, cell, cellHeight, (top + bottom) / 2, left, top, right, bottom,
            if (balloon) -w / 2 + keep - right else -1e6f, if (balloon) w / 2 - keep - left else 1e6f)
    }

    /**
     * The cell nearest the finger along the row ([x], [y] in the key's frame; [centres] already slid), however high or
     * low the finger is, or none (-1) once it is more than a key height below the key.
     */
    fun nearestCell(centres: FloatArray, x: Float, y: Float, h: Float): Int {
        if (y > h / 2 + h) return -1
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (i in centres.indices) {
            val d = abs(x - centres[i])
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        return best
    }
}
