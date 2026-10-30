package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import org.junit.Assert.*
import org.junit.Test

class LayoutSolverTest {
    @Test fun allRowsShareTheSameTransformWithoutResizingAcrossTiltAndCurve() {
        val solver = LayoutSolver()
        for (rows in 4..5) for (template in Template.entries) {
            val grid = KeyGrid.template(template).apply { if (rows == 5) setNumberRow(true) }
            var previous: LayoutSolver.Solution? = null
            for (tilt in -10..10) for (curve in listOf(0f, .7f, 1f)) {
                val result = solver.compute(1.5f, rows, 7.5f, 60f, 21f, 6f, 0f,
                    360f, 270f, 630f, true, false, tilt.toFloat(), curve, grid)
                assertEquals(result.letterY + result.un * (rows - 1), result.spaceY, .0001f)
                assertTrue(result.span <= 270.01f)
                assertTrue(result.total <= 360.01f)
                previous?.let {
                    assertEquals(it.un, result.un, 0f)
                    assertEquals(it.cw, result.cw, 0f)
                }
                previous = result
            }
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
