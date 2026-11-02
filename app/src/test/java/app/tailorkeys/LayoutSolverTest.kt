package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import org.junit.Assert.*
import org.junit.Test

class LayoutSolverTest {
    /** Keys are sized for the tilt and curve in use: they always fit, and a gentler tilt never makes them smaller. */
    @Test fun allRowsShareTheSameTransformAndFitTheTiltInUse() {
        val solver = LayoutSolver()
        for (rows in 4..5) for (template in Template.entries) {
            val grid = KeyGrid.template(template).apply { if (rows == 5) setNumberRow(true) }
            for (curve in listOf(0f, .7f, 1f)) {
                var steeper: LayoutSolver.Solution? = null
                for (tilt in 10 downTo 0) for (sign in listOf(1, -1)) {
                    val result = solver.compute(1.5f, rows, 7.5f, 60f, 21f, 6f, 0f,
                        360f, 270f, 630f, true, false, (sign * tilt).toFloat(), curve, grid)
                    assertEquals(result.letterY + result.un * (rows - 1), result.spaceY, .0001f)
                    assertTrue(result.span <= 270.01f)
                    assertTrue(result.total <= 360.01f)
                    steeper?.let { assertTrue(result.un >= it.un - .01f) }
                    if (sign == -1) steeper = result
                }
            }
        }
    }

    /** A steep tilt (a half's own tilt on top of the shared one) narrows the keys before it flattens the rows. */
    @Test fun aSteepTiltNarrowsKeysBeforeFlatteningRows() {
        val grid = KeyGrid.template(Template.SPLIT)
        val upright = LayoutSolver().compute(1.5f, 4, 7.5f, 60f, 21f, 6f, 0f, 360f, 270f, 630f, true, false, 0f, 0f, grid)
        for (tilt in listOf(20f, -20f, 30f, -30f)) {
            val steep = LayoutSolver().compute(1.5f, 4, 7.5f, 60f, 21f, 6f, 0f, 360f, 270f, 630f, true, false, tilt, 0f, grid)
            assertTrue(steep.total <= 360.01f)
            // Rows keep 70% of their upright height, or the keys are already at their narrowest.
            assertTrue("tilt $tilt: rows ${steep.un} keys ${steep.cw}", steep.un >= .70f * upright.un - .5f || steep.cw <= .75f * 270f + .5f)
        }
    }

    /** Wide, short halves at a steep tilt: when 14 dp rows overflow even at the narrowest keys, the keys narrow further. */
    @Test fun keysNarrowPastTheirMinimumBeforeTheHeightIsExceeded() {
        val grid = KeyGrid.template(Template.SPLIT)
        for (tilt in listOf(20f, -20f)) {
            val steep = LayoutSolver().compute(2.625f, 4, 7.5f, 100f, 21f, 6f, 0f, 380f, 1000f, 2300f, true, false, tilt, .7f, grid)
            assertTrue("tilt $tilt: total ${steep.total}", steep.total <= 380.01f)
            assertTrue(steep.cw < .75f * 1000f)
        }
    }

    @Test fun aTouchBarComesOutOfTheRowsNotTheKeyboardsHeight() {
        val grid = KeyGrid.template(Template.SPLIT)
        for (split in listOf(true, false)) for (tilt in listOf(-10f, 0f, 10f)) {
            fun solve(bar: Float) = LayoutSolver().compute(1.5f, 4, 7.5f, 60f, 21f, 6f, 0f,
                360f, 270f, 630f, split, false, tilt, 1f, grid, bar)
            val plain = solve(0f)
            val withBar = solve(42f)
            assertTrue(withBar.total <= 360.01f)
            assertTrue(withBar.un < plain.un)
            if (split) assertEquals(withBar.letterY + withBar.un * 3 + 42f, withBar.spaceY, .0001f)
        }
    }
}
