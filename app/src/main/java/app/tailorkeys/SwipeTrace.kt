package app.tailorkeys

import kotlin.math.hypot

/** Incremental, dictionary-free extraction. Samples and frames reuse this state. */
class SwipeTrace(keys: Map<Char, GestureDecoder.Point>, private val width: Float) {
    private val keyChars = CharArray(keys.size)
    private val keyX = FloatArray(keys.size)
    private val keyY = FloatArray(keys.size)
    init { var i = 0; for ((ch, p) in keys) { keyChars[i] = ch; keyX[i] = p.x; keyY[i] = p.y; i++ } }
    private val letters = StringBuilder(32)
    val hints = ArrayList<GestureDecoder.DoubleHint>(4)
    private var first = true
    private var anchorX = 0f; private var anchorY = 0f
    private var pivotX = 0f; private var pivotY = 0f
    private var dwellX = 0f; private var dwellY = 0f
    private var dwellAt = 0L; private var dwellLetter = ' '
    private var paused = false
    private var pivotAt = 0L
    private var fired = false
    private var travel = 0f; private var lastX = 0f; private var lastY = 0f
    private var end = ' '
    var dotLetter = ' '; private set
    var dotAt = Long.MIN_VALUE; private set
    private fun nearest(x: Float, y: Float): Int {
        var best = Float.MAX_VALUE; var index = -1
        for (i in keyChars.indices) {
            val dx = x - keyX[i]; val dy = y - keyY[i]; val d = dx * dx + dy * dy
            if (d < best) { best = d; index = i }
        }
        return index
    }
    private fun append(ch: Char) {
        if (ch != ' ' && letters.lastOrNull() != ch) { letters.append(ch) }
    }
    fun sample(x: Float, y: Float, time: Long, index: Int) {
        val key = nearest(x, y)
        if (key < 0) return
        val ch = keyChars[key]
        if (first) {
            first = false; append(ch)
            anchorX = x; anchorY = y; pivotX = x; pivotY = y
            dwellX = x; dwellY = y; dwellAt = time; dwellLetter = ch
            lastX = x; lastY = y; pivotAt = time
        }
        end = ch
        val step = hypot(x - lastX, y - lastY)
        travel += step; lastX = x; lastY = y
        // A slow pass travels out of the anchor or exceeds the motion budget before 210 ms.
        val drift = hypot(x - dwellX, y - dwellY)
        if (ch != dwellLetter || drift > width * .35f || (!fired && (travel > width * .22f || drift > width * .12f))) {
            dwellX = x; dwellY = y; dwellAt = time; dwellLetter = ch; fired = false; paused = false; travel = 0f
        }
        if (!paused && time - dwellAt >= 110 && drift <= width * .12f) { append(ch); paused = true }
        if (!fired && time - dwellAt >= 210 && hypot(x - keyX[key], y - keyY[key]) <= width * .55f) {
            append(ch); letters.append(ch)
            hints.add(GestureDecoder.DoubleHint(ch, index))
            fired = true; dotLetter = ch; dotAt = time
        }
        val ax = pivotX - anchorX; val ay = pivotY - anchorY
        val bx = x - pivotX; val by = y - pivotY
        val a = hypot(ax, ay); val b = hypot(bx, by)
        val speed = b / (time - pivotAt).coerceAtLeast(1) / width
        val span = width * if (speed > .025f) .38f else .28f
        // Fast motion needs a larger, sharper turn; straight crossings never emit a key.
        val cosine = if (speed > .025f) .30f else .58f
        if (b >= span) {
            if (a >= width * .28f && (ax * bx + ay * by) / (a * b) < cosine) append(keyChars[nearest(pivotX, pivotY)])
            anchorX = pivotX; anchorY = pivotY; pivotX = x; pivotY = y; pivotAt = time
        }
    }
    /** Only called at the 50 ms live-output boundary, or on lift. */
    fun text(): String = if (end == ' ' || letters.lastOrNull() == end) letters.toString() else letters.toString() + end

    companion object {
        fun appendToPrefix(prefix: String, raw: String): String =
            prefix + if (prefix.isNotEmpty() && raw.firstOrNull()?.lowercaseChar() == prefix.last().lowercaseChar()) raw.drop(1) else raw
    }
}
