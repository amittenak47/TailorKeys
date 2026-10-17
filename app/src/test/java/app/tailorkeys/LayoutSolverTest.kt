package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutSolverTest {
    @Test fun sizeTiltAndRotationGeometryMatchesPreOptimization() {
        val solver = LayoutSolver()
        var hash = 0
        for (rows in 4..5) {
            val grid = KeyGrid.template(Template.SPLIT)
            if (rows == 5) grid.setNumberRow(true)
            repeat(100) { i ->
                val result = solver.compute(1.5f, rows, 7.5f, 60f, 21f, 6f, 0f,
                    300f + i % 20, 270f, 630f, i % 3 != 0, i % 7 == 0,
                    (i % 21 - 10).toFloat(), (10 - i % 21).toFloat(), (i % 11) / 10f, grid)
                hash = hash * 31 + result.hashCode()
            }
        }
        // Captured from the extracted, unmodified solver over all ten geometry outputs.
        assertEquals(199367361, hash)
    }
}
