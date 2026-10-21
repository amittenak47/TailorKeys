package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Keys
import app.tailorkeys.layout.Template
import org.junit.Assert.*
import org.junit.Test

class KeyRemovalTest {
    @Test fun removalCompactsOnlyItsRowAndHalfAndRoundTrips() {
        val grid = KeyGrid.default()
        val before = grid.toJson()
        val unchanged = grid.items.filter { it.r != 0 || it.c >= 5 }.map { Triple(it.r, it.c, it.w) }
        assertTrue(grid.remove(grid.items.first { it.keys == listOf("e") }))
        assertEquals(listOf("q", "w", "r", "t"), grid.items.filter { it.r == 0 && it.c < 5 }.sortedBy { it.c }.map { it.keys.single() })
        assertEquals(listOf(0, 1, 2, 3), grid.items.filter { it.r == 0 && it.c < 5 }.map { it.c })
        assertEquals(unchanged, grid.items.filter { it.r != 0 || it.c >= 5 }.map { Triple(it.r, it.c, it.w) })
        assertTrue(grid.isSound())
        assertEquals(grid.toJson(), KeyGrid.fromCode(grid.toCode())!!.toJson())
        val unit = LayoutSolver().rowUnit(grid, 0, 0, 250f)
        assertEquals(62.5f, unit, 0f)
        assertEquals(250f, unit * 4, 0f)
        assertEquals(50f, LayoutSolver().rowUnit(grid, 0, 6, 250f), 0f)
        assertEquals(before, KeyGrid.fromJson(before)!!.toJson()) // editor history restores the whole grid
    }

    @Test fun lastRequiredFacesAreProtectedIncludingStacks() {
        for (template in Template.entries) for (code in listOf(Keys.ABC, Keys.NUM, Keys.SYM, Keys.BACKSPACE, Keys.ENTER)) {
            val grid = KeyGrid.template(template)
            val key = grid.items.single { code in it.keys }
            val before = grid.toJson()
            assertNotNull(grid.removalHint(key))
            assertFalse(grid.remove(key))
            assertEquals(before, grid.toJson())
        }
        val grid = KeyGrid.default()
        assertTrue(grid.remove(grid.items.first { it.isSpace }))
        assertFalse(grid.remove(grid.items.single { it.isSpace }))
        assertEquals("Space can't be removed", grid.removalHint(grid.items.single { it.isSpace }))
    }

    @Test fun punctuationAndDuplicateRequiredKeysCanBeRemoved() {
        val grid = KeyGrid.template(Template.SPLIT)
        for (code in listOf(",", ".")) assertTrue(grid.remove(grid.items.single { code in it.keys }))
        val extra = grid.items.first { it.keys == listOf("q") }.apply { keys[0] = Keys.BACKSPACE }
        assertTrue(grid.remove(extra))
        assertTrue(grid.isSound())
    }
}
