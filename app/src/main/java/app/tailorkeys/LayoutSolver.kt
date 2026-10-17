package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import kotlin.math.*

/** Pure size/tilt solver, shared by the view and opt-in JVM benchmarks. */
internal class LayoutSolver {
    data class Solution(val un: Float, val hc: Float, val cw: Float, val pv: Float, val disp: Float,
                        val padTop: Float, val letterY: Float, val spaceY: Float, val span: Float, val total: Float)
    private var reservedCache: Pair<List<Any>, FloatArray>? = null
    private val boundsLetters = FloatArray(4)
    private val boundsSpace = FloatArray(4)
    private val angleRange = Prefs.tilt(false)
    fun compute(
        d: Float, n: Int, gap: Float, stripH: Float, handleH: Float, pad: Float, bottomPad: Float,
        target: Float, half: Float, innerW: Float, split: Boolean, editMode: Boolean,
        tilt: Float, spaceTilt: Float, curve: Float, grid: KeyGrid,
    ): Solution {
        val th = Math.toRadians(tilt.toDouble())
        class Sol(val un: Float, val hc: Float, val cw: Float, val pv: Float, val disp: Float, val padTop: Float,
                  val letterY: Float = 0f, val spaceY: Float = hc, val span: Float = cw) {
            val total get() = stripH + padTop + hc + handleH + bottomPad
        }

        // Fit each tilted half inside its side: outer edge on the screen edge, never past the center line.
        fun solve(un: Float, angle: Double, bend: Float, fixedWidth: Float? = null): Sol {
            val hc = un * n
            val raise = if (split) 0.38f * bend * un else 0f
            val hf = hc + raise
            var cw = fixedWidth ?: half
            var pv = half
            if (split) {
                fun rot(w: Float): Pair<Float, Float> {
                    var mn = 0.0
                    var mx = 0.0
                    for ((px, py) in listOf(-w to 0f, -w to -hf, 0f to -hf, 0f to 0f)) {
                        val x = px * cos(angle) - py * sin(angle)
                        mn = min(mn, x); mx = max(mx, x)
                    }
                    return (-mn).toFloat() to mx.toFloat()
                }
                if (fixedWidth == null) for (i in 0 until 2000) {
                    val (a, b) = rot(cw)
                    if (a <= half + 0.5f && a + b <= innerW / 2 - 3 * d) break
                    cw -= 1f
                    if (cw < half * 0.2f) break
                }
                pv = rot(cw).first
            }
            val disp = if (split) ceil(cw * abs(sin(angle))).toFloat() else 0f
            return Sol(un, hc, cw, pv, disp, pad + disp + raise)
        }

        // Fit tilt, curve and all rows within the selected height, keeping a pixel of room when shrinking.
        fun fit(angle: Double, bend: Float): Sol {
            var s = solve(max(14 * d, (target - stripH - handleH - pad - bottomPad) / n), angle, bend)
            for (i in 0 until 24) {
                if (s.total <= target) break
                s = solve(max(14 * d, s.un - (s.total - target + 1) / (n + 0.5f)), angle, bend)
            }
            return s
        }
        // Each letter key's fan angle depends only on its column and the curve, so work it out once per layout
        // instead of in every trial below (this search runs hundreds of times while a slider moves).
        val fanCache = HashMap<Float, Pair<FloatArray, FloatArray>>()
        fun fans(bend: Float) = fanCache.getOrPut(bend) {
            val keys = grid.items.filter { it.r < n - 1 && it.c != KeyGrid.CENTER }
            val widths = FloatArray(keys.size)
            val sines = FloatArray(keys.size)
            keys.forEachIndexed { i, item ->
                val side = if (item.c < KeyGrid.CENTER) 0 else 1
                val lc = if (side == 0) item.c else item.c - 6
                widths[i] = item.w.toFloat()
                sines[i] = sin(Math.toRadians((abs(ANG[side][lc]) * bend).toDouble())).toFloat()
            }
            widths to sines
        }
        // Bounds include every key's fan rotation, even wide keys, at any smaller curve.
        fun blocks(un: Float, cw: Float, angle: Double, spaceAngle: Double, bend: Float): Sol {
            val letterH = un * (n - 1)
            var fanX = 0f
            var fanY = 0f
            val (widths, sines) = fans(bend)
            for (i in widths.indices) {
                fanX = max(fanX, max(0f, un - gap) * sines[i] / 2)
                fanY = max(fanY, max(0f, widths[i] * cw / 5 - gap) * sines[i] / 2)
            }
            // Axis-aligned bounds of a rotated rectangle, written into [out] as left, top, right, bottom.
            // Allocation-free: this runs hundreds of times per layout.
            fun bounds(x0: Float, y0: Float, x1: Float, y1: Float, a: Double, out: FloatArray) {
                val c = cos(a).toFloat()
                val s = sin(a).toFloat()
                val ax = x0 * c - y0 * s; val bx = x0 * c - y1 * s; val cx = x1 * c - y0 * s; val dx = x1 * c - y1 * s
                val ay = x0 * s + y0 * c; val by = x0 * s + y1 * c; val cy = x1 * s + y0 * c; val dy = x1 * s + y1 * c
                out[0] = min(min(ax, bx), min(cx, dx)); out[2] = max(max(ax, bx), max(cx, dx))
                out[1] = min(min(ay, by), min(cy, dy)); out[3] = max(max(ay, by), max(cy, dy))
            }
            val letters = boundsLetters
            val space = boundsSpace
            bounds(-cw + gap / 2 - fanX, -letterH + gap / 2 - 0.38f * bend * un - fanY, -gap / 2 + fanX, -gap / 2 + fanY, angle, letters)
            bounds(-cw + gap / 2, gap / 2, -gap / 2, un - gap / 2, spaceAngle, space)
            // Separate the complete rotated bounds by the normal gap, including opposing tilts.
            val lettersH = letters[3] - letters[1]
            val contentH = lettersH + gap + (space[3] - space[1])
            val hc = max(n * un, contentH)
            val extra = (hc - contentH) / 2
            val left = min(letters[0], space[0])
            val right = max(letters[2], space[2])
            return Sol(un, hc, cw, -left, 0f, pad, -letters[1] - letterH + extra,
                lettersH + gap - space[1] + extra, right - left)
        }
        val reserve = split && !editMode
        val s = if (reserve) {
            // Fit both ranges together at maximum curve; neither live angle changes key dimensions.
            val range = angleRange
            val spaceRange = angleRange
            val angles = listOf(range.min, range.max).map { Math.toRadians(it.toDouble()) }
            val spaceAngles = listOf(spaceRange.min, spaceRange.max).map { Math.toRadians(it.toDouble()) }
            // 14 halvings resolve widths and heights far below a pixel.
            fun worst(un: Float): Sol {
                var lo = gap
                var hi = half
                repeat(14) {
                    val cw = (lo + hi) / 2
                    if (angles.all { a -> spaceAngles.all { b -> blocks(un, cw, a, b, 1f).span <= half } }) lo = cw else hi = cw
                }
                return angles.flatMap { a -> spaceAngles.map { b -> blocks(un, lo, a, b, 1f) } }.maxBy { it.total }
            }
            // The worst case doesn't depend on the live tilt or curve, so moving those sliders reuses it.
            val key = listOf(n, target, half, innerW, gap, stripH, bottomPad, range.min, range.max, spaceRange.min, spaceRange.max,
                grid.items.filter { it.r < n - 1 }.joinToString { "${it.c}:${it.w}" })
            // [row unit, half width, total height] of the worst case.
            val reserved = reservedCache?.takeIf { it.first == key }?.second ?: run {
                var lo = 14 * d
                var hi = max(lo, (target - stripH - handleH - pad - bottomPad) / n)
                repeat(14) {
                    val un = (lo + hi) / 2
                    if (worst(un).total <= target) lo = un else hi = un
                }
                val w = worst(lo)
                floatArrayOf(w.un, w.cw, w.total).also { reservedCache = key to it }
            }
            blocks(reserved[0], reserved[1], th, Math.toRadians(spaceTilt.toDouble()), curve)
        } else fit(th, curve)
        return Solution(s.un, s.hc, s.cw, s.pv, s.disp, s.padTop, s.letterY, s.spaceY, s.span, s.total)
    }
    private companion object {
        val ANG = arrayOf(floatArrayOf(-9f, -4f, 0f, 3f, 6f), floatArrayOf(-6f, -3f, 0f, 4f, 9f))
    }
}
