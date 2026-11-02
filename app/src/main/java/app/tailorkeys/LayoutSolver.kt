package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import kotlin.math.*

/** Pure size/tilt solver, shared by the view and opt-in JVM benchmarks. */
internal class LayoutSolver {
    /** Row bands fill the key height; the touch bar owns a fixed band before the bottom row. */
    data class RowBands(val tops: FloatArray, val heights: FloatArray, val barTop: Float)

    fun rowBands(grid: KeyGrid, un: Float, bar: Float = 0f): RowBands {
        val total = (0 until grid.rows).sumOf { grid.rowScale(it).toDouble() }.toFloat()
        val heights = FloatArray(grid.rows) { un * grid.rows * grid.rowScale(it) / total }
        val tops = FloatArray(grid.rows)
        var y = 0f
        var barTop = 0f
        for (r in tops.indices) {
            if (r == grid.rows - 1) { barTop = y; y += bar }
            tops[r] = y
            y += heights[r]
        }
        return RowBands(tops, heights, barTop)
    }

    /** Inverts normalization so a dragged edge follows the finger while the other rows share the remainder. */
    fun rowScaleForHeight(grid: KeyGrid, row: Int, blockHeight: Float, wanted: Float): Float {
        val height = wanted.coerceIn(1f, max(1f, blockHeight - 1f))
        val others = (0 until grid.rows).filter { it != row }.sumOf { grid.rowScale(it).toDouble() }.toFloat()
        return height * others / (blockHeight - height).coerceAtLeast(1f)
    }

    /** Each half solves its own keys; requested widths share the screen if their sum would close the gap. */
    data class SplitSolution(val left: Solution, val right: Solution)

    fun computeSplit(
        d: Float, n: Int, gap: Float, stripH: Float, handleH: Float, pad: Float, bottomPad: Float,
        target: Float, half: Float, innerW: Float, tilt: Float, curve: Float, grid: KeyGrid,
        left: HalfShape, right: HalfShape, bar: Float = 0f, maxHeight: Float = Float.MAX_VALUE,
    ): SplitSolution {
        val shapes = listOf(left.bounded(), right.bounded())
        val widths = shapes.map { half * it.width / 100f }
        val fit = min(1f, (innerW - 8 * d).coerceAtLeast(0f) / widths.sum().coerceAtLeast(1f))
        val overhead = stripH + handleH + pad + bottomPad
        fun side(i: Int): Solution {
            val h = min(maxHeight, overhead + (target - overhead).coerceAtLeast(0f) * shapes[i].height / 100f)
            return compute(d, n, gap, stripH, handleH, pad, bottomPad, h, widths[i] * fit, innerW,
                true, false, (tilt + shapes[i].tilt).toFloat(), curve, grid, bar)
        }
        return SplitSolution(side(0), side(1))
    }

    /** Row width stays anchored to its half; compacted columns share the available width. */
    fun rowUnit(grid: KeyGrid, row: Int, column: Int, halfWidth: Float): Float =
        halfWidth / grid.rowColumns(row, column)
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
    }
    private val work = Work()
    private var reservedRows = -1
    private var lastTarget = Float.NaN
    private var lastHalf = Float.NaN
    private var lastInner = Float.NaN
    private var lastGap = Float.NaN
    private var lastStrip = Float.NaN
    private var lastBottom = Float.NaN
    private var lastDensity = Float.NaN
    private var lastPad = Float.NaN
    private var lastHandle = Float.NaN
    private var lastBar = Float.NaN
    private var lastTilt = Float.NaN
    private var lastCurve = Float.NaN
    private var haveReserved = false
    private val reserved = FloatArray(2)
    private val boundsLetters = FloatArray(4)
    fun compute(
        d: Float, n: Int, gap: Float, stripH: Float, handleH: Float, pad: Float, bottomPad: Float,
        target: Float, half: Float, innerW: Float, split: Boolean, editMode: Boolean,
        tilt: Float, curve: Float, grid: KeyGrid, bar: Float = 0f,
    ): Solution {
        // [bar]: a touch bar's height above the bottom row, inside each block, paid for by shorter rows.
        val th = Math.toRadians(tilt.toDouble())
        val rowWeight = (0 until n).sumOf { grid.rowScale(it).toDouble() }.toFloat()
        fun bottomTop(un: Float) = un * n * (1 - grid.rowScale(n - 1) / rowWeight) + bar
        fun solution(un: Float, hc: Float, cw: Float, pv: Float, disp: Float, padTop: Float,
                     letterY: Float = 0f, spaceY: Float = hc, span: Float = cw) =
            work.set(un, hc, cw, pv, disp, padTop, letterY, spaceY, span, stripH + padTop + hc + handleH + bottomPad)

        // Fit each tilted half inside its side: outer edge on the screen edge, never past the center line.
        fun solve(un: Float, angle: Double, bend: Float, fixedWidth: Float? = null): Work {
            val hc = un * n + bar
            val raise = if (split) ErgoCurve.RISE * bend * un else 0f
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
            return solution(un, hc, cw, pv, disp, pad + disp + raise, spaceY = bottomTop(un))
        }

        // Fit tilt, curve and all rows within the selected height, keeping a pixel of room when shrinking.
        fun fit(angle: Double, bend: Float): Work {
            var s = solve(max(14 * d, (target - stripH - handleH - pad - bottomPad - bar) / n), angle, bend)
            for (i in 0 until 24) {
                if (s.total <= target) break
                s = solve(max(14 * d, s.un - (s.total - target + 1) / (n + 0.5f)), angle, bend)
            }
            return s
        }
        val reserve = split && !editMode
        // The reservation depends on the row count, not on where keys sit.
        if (reserve && reservedRows != n) { reservedRows = n; haveReserved = false }
        // The curve only moves columns up, keys upright, so the block's bounds grow by the arc's rise alone.
        fun blocks(un: Float, cw: Float, angle: Double, bend: Float): Work {
            val letterH = bottomTop(un)
            val blockH = n * un + bar
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
            // One transformed block: row spacing is preserved through the shared tilt.
            bounds(-cw + gap / 2, -blockH + gap / 2 - ErgoCurve.RISE * bend * un, -gap / 2, -gap / 2, angle, letters)
            val contentH = letters[3] - letters[1]
            val hc = max(blockH, contentH)
            val extra = (hc - contentH) / 2
            val top = -letters[1] - blockH + extra
            return solution(un, hc, cw, -letters[0], 0f, pad, top,
                top + letterH, letters[2] - letters[0])
        }
        val s = if (reserve) {
            // Fit the tilt and curve in use (both halves' mirrored angles): keys use the height the tilt leaves
            // instead of a band kept for the steepest tilt. They resize a little as Tilt or Curve changes. The
            // tallest rows that fit come first, then the widest keys at that height: scaling both by one factor
            // left a blank band and narrower keys (measured 2026-11-01).
            val angles = doubleArrayOf(-abs(th), abs(th))
            // 14 halvings resolve widths and heights far below a pixel.
            // The taller of the two mirrored blocks at this row height and key width (a copy: blocks reuses one Work).
            fun tallest(un: Float, cw: Float): Work {
                var tallest: Work? = null
                for (a in angles) {
                    val candidate = blocks(un, cw, a, curve)
                    if (tallest == null || candidate.total > tallest.total) tallest = Work().set(candidate.un, candidate.hc,
                        candidate.cw, candidate.pv, candidate.disp, candidate.padTop, candidate.letterY, candidate.spaceY, candidate.span, candidate.total)
                }
                return tallest!!
            }
            // The widest keys whose block stays within the side, and with [height] within the height set too.
            fun widest(un: Float, height: Boolean = false): Work {
                var lo = gap
                var hi = half
                repeat(14) {
                    val cw = (lo + hi) / 2
                    if (angles.all { a -> blocks(un, cw, a, curve).let { it.span <= half && (!height || it.total <= target) } }) lo = cw else hi = cw
                }
                return tallest(un, lo)
            }
            // Exact primitive cache keys: live tilt/curve reuse the reservation without strings or boxing.
            if (!haveReserved || lastTarget != target || lastHalf != half || lastInner != innerW ||
                lastGap != gap || lastStrip != stripH || lastBottom != bottomPad || lastDensity != d ||
                lastPad != pad || lastHandle != handleH || lastBar != bar || lastTilt != tilt || lastCurve != curve) {
                var lo = 14 * d
                var hi = max(lo, (target - stripH - handleH - pad - bottomPad - bar) / n)
                val upright = hi
                // Tallest rows that fit with the widest keys, unless a steep tilt would squeeze the keys narrower than
                // MIN_WIDTH of the half: then rows give way too (per-half tilt can reach 30 degrees).
                fun fits(un: Float) = widest(un).let { it.total <= target && it.span <= half + .5f && it.cw >= MIN_WIDTH * half }
                repeat(14) {
                    val un = (lo + hi) / 2
                    if (fits(un)) lo = un else hi = un
                }
                var w = widest(lo)
                // A steep tilt can cost the rows most of their height while the keys stay full width (a tilted block
                // is taller the wider it is): rows keep MIN_ROWS of their upright height and the keys narrow instead,
                // down to MIN_WIDTH; past that, rows give way at that width.
                if (lo < MIN_ROWS * upright) {
                    val narrow = widest(MIN_ROWS * upright, height = true)
                    w = if (narrow.cw >= MIN_WIDTH * half && narrow.total <= target) narrow else {
                        val cw = MIN_WIDTH * half
                        var short = 14 * d
                        var tall = MIN_ROWS * upright
                        var found = false
                        repeat(14) {
                            val un = (short + tall) / 2
                            if (angles.all { a -> blocks(un, cw, a, curve).let { it.total <= target && it.span <= half } }) {
                                short = un; found = true
                            } else tall = un
                        }
                        // Keys at MIN_WIDTH when they fit and keep taller rows, or when even the full-width answer runs
                        // past the height set. When 14 dp rows overflow even at MIN_WIDTH, the keys narrow further: the
                        // height set holds.
                        if (found && (short > lo || w.total > target)) tallest(short, cw)
                        else if (!found && w.total > target) widest(14 * d, height = true) else w
                    }
                }
                reserved[0] = w.un; reserved[1] = w.cw
                lastTarget = target; lastHalf = half; lastInner = innerW; lastGap = gap
                lastStrip = stripH; lastBottom = bottomPad; lastDensity = d; lastPad = pad; lastHandle = handleH; lastBar = bar
                lastTilt = tilt; lastCurve = curve
                haveReserved = true
            }
            blocks(reserved[0], reserved[1], th, curve)
        } else fit(th, curve)
        return Solution(s.un, s.hc, s.cw, s.pv, s.disp, s.padTop, s.letterY, s.spaceY, s.span, s.total)
    }
    private companion object {
        /** The narrowest a tilted half's keys get, as a share of the half's width, before its rows shorten. */
        const val MIN_WIDTH = .75f
        /**
         * The share of their upright height rows keep at a steep tilt before the keys narrow instead. 70% balances
         * width and height: phone landscape at tilt 10 keeps keys at 90% with rows 6% shorter than at 75% (2026-11-02).
         */
        const val MIN_ROWS = .70f
        val EXTREME_ANGLES = doubleArrayOf(Math.toRadians(-10.0), Math.toRadians(10.0))
        val EXTREME_COS = cos(EXTREME_ANGLES[1]).toFloat()
        val EXTREME_SIN = sin(EXTREME_ANGLES[1]).toFloat()
    }
}
