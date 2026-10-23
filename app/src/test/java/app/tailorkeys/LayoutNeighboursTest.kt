package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LayoutNeighboursTest {
    private val predictor = Predictor({ File("src/main/assets", it).inputStream() },
        Files.createTempDirectory("tk-neighbours").toFile(), enableModel = false)

    private fun KeyGrid.letter(ch: Char) = items.first { it.keys[0] == ch.toString() }

    @Test fun splitHalvesMeetAcrossTheGap() {
        predictor.setLayout(KeyGrid.template(Template.SPLIT))
        for ((a, b) in listOf('t' to 'y', 'g' to 'h', 'b' to 'n', 't' to 'h', 'r' to 't', 't' to 'g')) {
            assertTrue("$a-$b", predictor.isNeighbour(a, b) && predictor.isNeighbour(b, a))
        }
        assertFalse(predictor.isNeighbour('e', 't'))
        assertFalse(predictor.isNeighbour('t', 'b'))
    }

    @Test fun standardLayoutJoinsVAndB() {
        predictor.setLayout(KeyGrid.default())
        assertTrue(predictor.isNeighbour('v', 'b'))
        assertFalse(predictor.isNeighbour('q', 'p'))
    }

    @Test fun movedKeysTakeTheirNeighboursWithThem() {
        val grid = KeyGrid.template(Template.SPLIT)
        assertTrue(grid.swap(grid.letter('q'), grid.letter('p')))
        predictor.setLayout(grid)
        assertTrue(predictor.isNeighbour('q', 'o'))
        assertTrue(predictor.isNeighbour('p', 'w'))
        assertFalse(predictor.isNeighbour('q', 'w'))
    }

    @Test fun aKeyInTheCenterColumnSitsBetweenTheHalves() {
        val grid = KeyGrid.template(Template.SPLIT)
        assertTrue(grid.moveTo(grid.letter('p'), 0, KeyGrid.CENTER))
        predictor.setLayout(grid)
        assertTrue(predictor.isNeighbour('t', 'p'))
        assertTrue(predictor.isNeighbour('p', 'y'))
        assertFalse(predictor.isNeighbour('t', 'y'))
    }

    @Test fun drawnPositionsOverrideTheGrid() {
        // Wider keys on the right: y is now two of t's widths away.
        val spots = listOf('t' to KeySpot(4.5f, .5f, 1f, 1f), 'y' to KeySpot(7f, .5f, 2f, 1f), 'u' to KeySpot(9f, .5f, 2f, 1f))
        predictor.setLayout(KeyGrid.template(Template.SPLIT), spots)
        assertFalse(predictor.isNeighbour('t', 'y'))
        assertTrue(predictor.isNeighbour('y', 'u'))
    }
}
