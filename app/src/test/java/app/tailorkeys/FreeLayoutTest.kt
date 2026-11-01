package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Layer
import app.tailorkeys.layout.Template
import kotlin.math.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FreeLayoutTest {
    @Test fun unequalHalvesFitTheirVisibleBoundsAtEveryInsetAndTilt() {
        val solver = LayoutSolver()
        for (screen in listOf(320f, 800f)) for (rows in 4..5) for (curve in listOf(0f, 1f)) {
            val grid = KeyGrid.template(Template.SPLIT).apply { if (rows == 5) setNumberRow(true) }
            for (tilt in listOf(-10f, 0f, 10f)) for (offset in listOf(-20, 0, 20)) {
                val left = HalfShape(150, 140, offset)
                val right = HalfShape(70, 60, -offset)
                val result = solver.computeSplit(1f, rows, 5f, 40f, 14f, 4f, 0f,
                    300f, (screen - 8f) * .42f, screen - 8f, tilt, curve, grid, left, right, 28f, 420f)
                assertTrue(result.left.un > 5f && result.right.un > 5f)
                assertTrue(result.left.cw > 25f && result.right.cw > 25f)
                assertTrue(result.left.total <= 420.01f)
                assertTrue(result.right.total <= 420.01f)
                assertTrue(result.left.span + result.right.span <= screen - 16f + .01f)
                val room = (screen - 16f - result.left.span - result.right.span).coerceAtLeast(0f)
                for (inset in listOf(0, 200, 900)) {
                    val (l, r) = SplitPlacement.insets(inset, inset / 2, screen, room)
                    val leftEdge = 4f + l
                    val rightEdge = screen - 4f - r
                    assertTrue(leftEdge >= 4f)
                    assertTrue(rightEdge <= screen - 4f)
                    assertTrue(rightEdge - result.right.span - leftEdge - result.left.span >= 7.99f)
                    // Use the view's actual pivot transforms, rather than trusting the solver's reported span.
                    for ((side, shape, sol) in listOf(Triple(0, left, result.left), Triple(1, right, result.right))) {
                        val angle = Math.toRadians((tilt + shape.tilt).toDouble())
                        val pivot = if (side == 0) leftEdge + sol.pv else rightEdge - sol.pv
                        val bands = solver.rowBands(grid, sol.un, 28f)
                        for (row in 0 until rows) for (col in 0..4) for (edge in 0..3) {
                            val x = (col + if (edge == 1 || edge == 2) 1 else 0) * sol.cw / 5 +
                                if (edge == 1 || edge == 2) -2.5f else 2.5f
                            val raise = ErgoCurve.raise(col + .5f) * curve * sol.un
                            val y = bands.tops[row] + (if (edge >= 2) bands.heights[row] - 2.5f else 2.5f) - raise
                            val localX = if (side == 0) x - sol.cw else x
                            val a = if (side == 0) angle else -angle
                            val screenX = pivot + localX * cos(a) - (y - rows * sol.un - 28f) * sin(a)
                            assertTrue("half $side outside screen: $screenX; screen=$screen rows=$rows curve=$curve tilt=$tilt offset=$offset row=$row col=$col edge=$edge sol=$sol", screenX >= 3.99 && screenX <= screen - 3.99)
                            if (side == 0) assertTrue(screenX <= leftEdge + sol.span + .02)
                            else assertTrue(screenX >= rightEdge - sol.span - .02)
                        }
                    }
                }
            }
        }
    }

    @Test fun oversizedHalvesShareTheScreenAndKeepTheirMinimumGap() {
        val g = KeyGrid.template(Template.SPLIT)
        for (screen in listOf(320f, 800f)) for ((leftWidth, rightWidth) in listOf(150 to 150, 50 to 150, 150 to 50)) {
            val result = LayoutSolver().computeSplit(1f, 4, 5f, 40f, 14f, 4f, 0f,
                300f, (screen - 8) * .49f, screen - 8, 10f, 1f, g,
                HalfShape(leftWidth, 50, -20), HalfShape(rightWidth, 150, 20), 28f, 420f)
            assertTrue(result.left.span + result.right.span <= screen - 16 + .01f)
            assertTrue(result.left.total <= 420.01f && result.right.total <= 420.01f)
        }
    }

    @Test fun largerHalfHasLargerKeysAndBaseSlidersStillScaleBoth() {
        val g = KeyGrid.template(Template.SPLIT)
        fun solve(target: Float, half: Float) = LayoutSolver().computeSplit(1f, 4, 5f, 40f, 14f, 4f, 0f,
            target, half, 1000f, 0f, 0f, g, HalfShape(110, 120), HalfShape())
        val first = solve(300f, 220f)
        val wider = solve(300f, 240f)
        val taller = solve(330f, 220f)
        assertTrue(first.left.un > first.right.un)
        assertTrue(first.left.cw > first.right.cw)
        assertEquals(1.1f, first.left.cw / first.right.cw, .001f)
        assertEquals(wider.left.cw / first.left.cw, wider.right.cw / first.right.cw, .001f)
        assertTrue(taller.left.un > first.left.un)
        assertTrue(taller.right.un > first.right.un)
    }

    @Test fun weightedRowsFillEachHalfAndLeaveTheWholeTouchBarBand() {
        val g = KeyGrid.default().apply { setNumberRow(true); setRowScale(0, .5f); setRowScale(2, 2f); setRowScale(4, 1.4f) }
        for (un in listOf(28f, 70f)) for (bar in listOf(0f, 28f)) {
            val solver = LayoutSolver()
            val bands = solver.rowBands(g, un, bar)
            val solved = solver.compute(1f, 5, 5f, 40f, 14f, 4f, 0f, 350f, 280f, 720f,
                true, false, 7f, .7f, g, bar)
            assertEquals(solver.rowBands(g, solved.un, bar).tops.last(), solved.spaceY - solved.letterY, .001f)
            assertEquals(un * 5, bands.heights.sum(), .001f)
            assertEquals(4f, bands.heights[2] / bands.heights[0], .001f)
            assertEquals(bands.barTop + bar, bands.tops[4], .001f)
            assertEquals(un * 5 + bar, bands.tops[4] + bands.heights[4], .001f)
            for (r in 0..3) assertEquals(bands.tops[r] + bands.heights[r] + if (r == 3) bar else 0f, bands.tops[r + 1], .001f)
        }
    }

    @Test fun aRowResizeFollowsTheEdgeAndRedistributesTheRemainingHeight() {
        val grid = KeyGrid.default()
        val solver = LayoutSolver()
        val before = solver.rowBands(grid, 60f, 28f)
        val wanted = before.heights[1] + 20f
        grid.setRowScale(1, solver.rowScaleForHeight(grid, 1, 240f, wanted))
        val after = solver.rowBands(grid, 60f, 28f)
        assertEquals(wanted, after.heights[1], .001f)
        assertEquals(240f, after.heights.sum(), .001f)
        assertEquals(after.heights[0], after.heights[2], .001f)
        assertEquals(28f, after.tops[3] - after.barTop, .001f)
    }

    @Test fun rowHeightsSurviveCodesCopiesLayersLanguageAndNumberRowChanges() {
        val g = KeyGrid.default().apply { setRowScale(1, 1.8f); setRowScale(3, .7f) }
        val num = g.layer(Layer.NUM).apply { setRowScale(0, 1.3f); setRowScale(3, .6f) }
        g.setLayer(Layer.NUM, num)
        val restored = KeyGrid.decodeCode(g.toCode())!!.grid
        assertEquals(1.8f, restored.rowScale(1), .001f)
        assertEquals(.6f, restored.rowScale(3), .001f)
        assertEquals(1.3f, restored.layer(Layer.NUM).rowScale(0), .001f)
        assertEquals(.6f, restored.layer(Layer.SYM).rowScale(3), .001f)
        assertEquals(1.8f, restored.languageVariant("de").rowScale(1), .001f)
        val copy = restored.copy().apply { setRowScale(1, 2f); setNumberRow(true) }
        assertEquals(2f, copy.rowScale(2), .001f)
        assertEquals(1f, copy.rowScale(0), .001f)
        assertEquals(1.3f, copy.layer(Layer.NUM).rowScale(1), .001f)
        copy.setNumberRow(false)
        assertEquals(2f, copy.rowScale(1), .001f)
        assertEquals(1.8f, restored.rowScale(1), .001f)
        val old = KeyGrid.fromJson(KeyGrid.default().toJson())!!
        assertTrue((0 until old.rows).all { old.rowScale(it) == 1f })
        assertFalse(LayoutLibrary.sameGrid(g, KeyGrid.default()))
        val layerOnly = KeyGrid.default()
        layerOnly.setLayer(Layer.SYM, layerOnly.layer(Layer.SYM).apply { setRowScale(1, 1.5f) })
        assertFalse(LayoutLibrary.sameGrid(layerOnly, KeyGrid.default()))
        assertNull(KeyGrid.fromJson(JSONObject(old.toJson()).put("rows", Int.MAX_VALUE).toString()))
        val invalid = JSONObject(old.toJson()).put("rowHeights", JSONArray("[0,100,\"bad\"]"))
        val bounded = KeyGrid.fromJson(invalid.toString())!!
        assertEquals(.4f, bounded.rowScale(0), 0f)
        assertEquals(2.5f, bounded.rowScale(1), 0f)
        assertEquals(1f, bounded.rowScale(2), 0f)
        assertEquals(1f, bounded.rowScale(3), 0f)
    }

    @Test fun mirrorCopiesTheAdjustmentAndResetAndConstrainsBothPlacements() {
        val p = SizeProfile(44, 100, Align.CENTER, 0, true, 16, 3,
            leftShape = HalfShape(95, 90, -4), rightShape = HalfShape(105, 110, 7))
        val value = HalfShape(108, 115, 6)
        val single = p.shapeHalf(0, value, false)
        assertEquals(value, single.leftShape)
        assertEquals(p.rightShape, single.rightShape)
        for (side in 0..1) {
            val both = p.shapeHalf(side, value, true)
            assertEquals(value, both.leftShape); assertEquals(value, both.rightShape)
            val reset = both.shapeHalf(side, HalfShape(), true)
            assertEquals(HalfShape(), reset.leftShape); assertEquals(HalfShape(), reset.rightShape)
        }
        for (dx in listOf(-1000f, 10f, 1000f)) for (side in 0..1) {
            val (left, right) = SplitPlacement.mirroredDrag(side, dx, 10f, 20f, 100f, 108f, 30f)
            assertEquals(left - 10f, right - 20f, .001f)
            assertTrue(left >= 0 && right >= 0 && left + right <= 100.001f)
            assertTrue(108f - left - right >= 29.999f || min(left, right) >= 29.999f)
        }
    }
}
