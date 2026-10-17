package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import kotlin.math.*

/** Pure size/tilt solver, shared by the view and opt-in JVM benchmarks. */
internal class LayoutSolver {
    data class Solution(val un: Float, val hc: Float, val cw: Float, val pv: Float, val disp: Float,
                        val padTop: Float, val letterY: Float, val spaceY: Float, val span: Float, val total: Float)
    private class Work {
        var un = 0f; var hc = 0f; var cw = 0f; var pv = 0f; var disp = 0f
        var padTop = 0f; var letterY = 0f; var spaceY = 0f; var span = 0f; var total = 0f
        fun set(un: Float, hc: Float, cw: Float, pv: Float, disp: Float, padTop: Float,
                letterY: Float, spaceY: Float, span: Float, total: Float): Work {
            this.un = un; this.hc = hc; this.cw = cw; this.pv = pv; this.disp = disp
            this.padTop = padTop; this.letterY = letterY; this.spaceY = spaceY; this.span = span; this.total = total
            return this
        }
        fun copy(other: Work) = set(other.un, other.hc, other.cw, other.pv, other.disp,
            other.padTop, other.letterY, other.spaceY, other.span, other.total)
    }
    private val work = Work()
    private val worstWork = Work()
    private val fullFans = FloatArray(6)
    private val liveFans = FloatArray(6)
    private val topology = IntArray(55 * 3)
    private var topologySize = -1
    private var topologyRows = -1
    private var fanCurve = Float.NaN
    private var lastTarget = Float.NaN
    private var lastHalf = Float.NaN
    private var lastInner = Float.NaN
    private var lastGap = Float.NaN
    private var lastStrip = Float.NaN
    private var lastBottom = Float.NaN
    private var lastDensity = Float.NaN
    private var lastPad = Float.NaN
    private var lastHandle = Float.NaN
    private var haveReserved = false
    private val reserved = FloatArray(3)
    private val boundsLetters = FloatArray(4)
    private val boundsSpace = FloatArray(4)
    fun compute(
        d: Float, n: Int, gap: Float, stripH: Float, handleH: Float, pad: Float, bottomPad: Float,
        target: Float, half: Float, innerW: Float, split: Boolean, editMode: Boolean,
        tilt: Float, spaceTilt: Float, curve: Float, grid: KeyGrid,
    ): Solution {
        val th = Math.toRadians(tilt.toDouble())
        fun solution(un: Float, hc: Float, cw: Float, pv: Float, disp: Float, padTop: Float,
                     letterY: Float = 0f, spaceY: Float = hc, span: Float = cw) =
            work.set(un, hc, cw, pv, disp, padTop, letterY, spaceY, span, stripH + padTop + hc + handleH + bottomPad)

        // Fit each tilted half inside its side: outer edge on the screen edge, never past the center line.
        fun solve(un: Float, angle: Double, bend: Float, fixedWidth: Float? = null): Work {
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
            return solution(un, hc, cw, pv, disp, pad + disp + raise)
        }

        // Fit tilt, curve and all rows within the selected height, keeping a pixel of room when shrinking.
        fun fit(angle: Double, bend: Float): Work {
            var s = solve(max(14 * d, (target - stripH - handleH - pad - bottomPad) / n), angle, bend)
            for (i in 0 until 24) {
                if (s.total <= target) break
                s = solve(max(14 * d, s.un - (s.total - target + 1) / (n + 0.5f)), angle, bend)
            }
            return s
        }
        // Equal-width keys need only the largest sine. This is the same maximum as walking
        // every key in every binary-search trial, including wide/custom keys.
        fun fans(bend: Float, sines: FloatArray) {
            sines.fill(0f)
            for (item in grid.items) if (item.r < n - 1 && item.c != KeyGrid.CENTER) {
                val side = if (item.c < KeyGrid.CENTER) 0 else 1
                val lc = if (side == 0) item.c else item.c - 6
                val value = sin(Math.toRadians((abs(ANG[side][lc]) * bend).toDouble())).toFloat()
                sines[item.w] = max(sines[item.w], value)
            }
        }
        val reserve = split && !editMode
        if (reserve) {
            var changed = topologyRows != n || topologySize != grid.items.size
            for (i in grid.items.indices) {
                val item = grid.items[i]
                val j = i * 3
                if (topology[j] != item.r || topology[j + 1] != item.c || topology[j + 2] != item.w) changed = true
                topology[j] = item.r; topology[j + 1] = item.c; topology[j + 2] = item.w
            }
            if (changed) {
                topologyRows = n; topologySize = grid.items.size
                fans(1f, fullFans)
                fanCurve = Float.NaN
                haveReserved = false
            }
            if (fanCurve != curve) { fans(curve, liveFans); fanCurve = curve }
        }
        // Bounds include every key's fan rotation, even wide keys, at any smaller curve.
        fun blocks(un: Float, cw: Float, angle: Double, spaceAngle: Double, bend: Float): Work {
            val letterH = un * (n - 1)
            var fanX = 0f
            var fanY = 0f
            val sines = if (bend == 1f) fullFans else liveFans
            for (w in 1..5) {
                if (sines[w] == 0f) continue
                fanX = max(fanX, max(0f, un - gap) * sines[w] / 2)
                fanY = max(fanY, max(0f, w.toFloat() * cw / 5 - gap) * sines[w] / 2)
            }
            // Axis-aligned bounds of a rotated rectangle, written into [out] as left, top, right, bottom.
            // Allocation-free: this runs hundreds of times per layout.
            fun bounds(x0: Float, y0: Float, x1: Float, y1: Float, a: Double, out: FloatArray) {
                val c = if (a == EXTREME_ANGLES[0] || a == EXTREME_ANGLES[1]) EXTREME_COS else cos(a).toFloat()
                val s = if (a == EXTREME_ANGLES[0]) -EXTREME_SIN else if (a == EXTREME_ANGLES[1]) EXTREME_SIN else sin(a).toFloat()
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
            return solution(un, hc, cw, -left, 0f, pad, -letters[1] - letterH + extra,
                lettersH + gap - space[1] + extra, right - left)
        }
        val s = if (reserve) {
            // Fit both ranges together at maximum curve; neither live angle changes key dimensions.
            val angles = EXTREME_ANGLES
            val spaceAngles = EXTREME_ANGLES
            // 14 halvings resolve widths and heights far below a pixel.
            fun worst(un: Float): Work {
                var lo = gap
                var hi = half
                repeat(14) {
                    val cw = (lo + hi) / 2
                    var fits = true
                    for (a in angles) {
                        for (b in spaceAngles) if (blocks(un, cw, a, b, 1f).span > half) { fits = false; break }
                        if (!fits) break
                    }
                    if (fits) lo = cw else hi = cw
                }
                worstWork.total = Float.NEGATIVE_INFINITY
                for (a in angles) for (b in spaceAngles) {
                    val candidate = blocks(un, lo, a, b, 1f)
                    if (candidate.total > worstWork.total) worstWork.copy(candidate)
                }
                return worstWork
            }
            // Exact primitive cache keys: live tilt/curve reuse the reservation without strings or boxing.
            if (!haveReserved || lastTarget != target || lastHalf != half || lastInner != innerW ||
                lastGap != gap || lastStrip != stripH || lastBottom != bottomPad || lastDensity != d ||
                lastPad != pad || lastHandle != handleH) {
                var lo = 14 * d
                var hi = max(lo, (target - stripH - handleH - pad - bottomPad) / n)
                repeat(14) {
                    val un = (lo + hi) / 2
                    if (worst(un).total <= target) lo = un else hi = un
                }
                val w = worst(lo)
                reserved[0] = w.un; reserved[1] = w.cw; reserved[2] = w.total
                lastTarget = target; lastHalf = half; lastInner = innerW; lastGap = gap
                lastStrip = stripH; lastBottom = bottomPad; lastDensity = d; lastPad = pad; lastHandle = handleH
                haveReserved = true
            }
            blocks(reserved[0], reserved[1], th, Math.toRadians(spaceTilt.toDouble()), curve)
        } else fit(th, curve)
        return Solution(s.un, s.hc, s.cw, s.pv, s.disp, s.padTop, s.letterY, s.spaceY, s.span, s.total)
    }
    private companion object {
        val EXTREME_ANGLES = doubleArrayOf(Math.toRadians(-10.0), Math.toRadians(10.0))
        val EXTREME_COS = cos(EXTREME_ANGLES[1]).toFloat()
        val EXTREME_SIN = sin(EXTREME_ANGLES[1]).toFloat()
        val ANG = arrayOf(floatArrayOf(-9f, -4f, 0f, 3f, 6f), floatArrayOf(-6f, -3f, 0f, 4f, 9f))
    }
}
