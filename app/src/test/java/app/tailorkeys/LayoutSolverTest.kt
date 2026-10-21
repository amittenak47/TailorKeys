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
}
