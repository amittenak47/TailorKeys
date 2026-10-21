package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Keys
import app.tailorkeys.layout.Template
import org.junit.Assert.*
import org.junit.Test

class EditorTrayTest {
    @Test fun removedKeysWaitInTheTrayAndRoundTrip() {
        val grid = KeyGrid.template(Template.SPLIT)
        val e = grid.items.single { it.keys == listOf("e") }
        assertTrue(grid.remove(e))
        assertEquals(listOf(listOf("e")), grid.tray)
        val restored = KeyGrid.fromJson(grid.toJson())!!
        assertEquals(grid.tray, restored.tray)
        assertEquals(grid.toJson(), KeyGrid.fromCode(grid.toCode())!!.toJson())
        // Protected keys still cannot leave the grid, not even for the tray.
        assertFalse(grid.remove(grid.items.single { Keys.ENTER in it.keys }))
        assertEquals(1, grid.tray.size)
    }

    @Test fun separatedFacesGoToTheTrayInsteadOfHiddenCells() {
        val grid = KeyGrid.template(Template.SPLIT)
        val q = grid.items.single { it.keys == listOf("q") }
        val w = grid.items.single { it.keys == listOf("w") }
        grid.combine(w, q)
        val count = grid.items.size
        grid.separate(q)
        assertEquals(listOf("q"), q.keys)
        assertEquals(count, grid.items.size)
        assertEquals(listOf(listOf("w")), grid.tray)
        assertTrue(grid.isSound())
    }

    @Test fun trayKeysCanBePlacedAgainOrCombined() {
        val grid = KeyGrid.template(Template.SPLIT)
        val e = grid.items.single { it.keys == listOf("e") }
        assertTrue(grid.remove(e))
        val r = grid.items.single { it.keys == listOf("r") }
        // Insert before r: the row reopens and e sits back between w and r.
        assertTrue(grid.placeFromTray(0) { grid.insertShift(it, r, before = true) })
        assertTrue(grid.tray.isEmpty())
        assertEquals(listOf("q", "w", "e", "r", "t"), grid.items.filter { it.r == 0 && it.c < 5 }.sortedBy { it.c }.map { it.keys.single() })
        assertTrue(grid.isSound())
        val x = grid.items.single { it.keys == listOf("x") }
        assertTrue(grid.remove(grid.items.single { it.keys == listOf(",") }))
        assertTrue(grid.combineFromTray(0, x))
        assertEquals(listOf("x", ","), x.keys)
        assertTrue(grid.tray.isEmpty())
        // A failed placement leaves the tray entry where it was.
        assertTrue(grid.remove(grid.items.single { it.keys == listOf(".") }))
        assertFalse(grid.placeFromTray(0) { false })
        assertEquals(listOf(listOf(".")), grid.tray)
    }

    @Test fun copiesKeepIdsAndTheTrayIsBounded() {
        val grid = KeyGrid.template(Template.SPLIT)
        assertTrue(grid.remove(grid.items.single { it.keys == listOf("t") }))
        val copy = grid.copy()
        assertEquals(grid.items.map { it.id }, copy.items.map { it.id })
        assertEquals(grid.tray, copy.tray)
        copy.items.first().keys[0] = "changed"; copy.tray.clear()
        assertNotEquals("changed", grid.items.first().keys[0])
        assertEquals(1, grid.tray.size)
        val json = """{"rows":4,"items":${org.json.JSONObject(grid.toJson()).getJSONArray("items")},"tray":[["a"],[],"bad",[""],["b","c"]]}"""
        assertEquals(listOf(listOf("a"), listOf("b", "c")), KeyGrid.fromJson(json)!!.tray)
    }

    @Test fun fullRowsRefuseInsertsButStillReorderWithinTheRow() {
        val grid = KeyGrid.template(Template.SPLIT)
        val q = grid.items.single { it.keys == listOf("q") }
        val t = grid.items.single { it.keys == listOf("t") }
        val a = grid.items.single { it.keys == listOf("a") }
        // The top-left row has five keys: a key from another row has no room there.
        assertFalse(grid.copy().let { g -> g.insertShift(g.items.single { it.id == a.id }, g.items.single { it.id == q.id }, before = true) })
        // Moving a key within its own full row only reorders it.
        assertTrue(grid.insertShift(t, q, before = true))
        assertEquals(listOf("t", "q", "w", "e", "r"), grid.items.filter { it.r == 0 && it.c < 5 }.sortedBy { it.c }.map { it.keys.single() })
        assertTrue(grid.isSound())
    }
}
