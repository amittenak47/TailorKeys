package app.tailorkeys

import app.tailorkeys.layout.InputState
import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.KeyText
import app.tailorkeys.layout.Keys
import app.tailorkeys.layout.Layer
import app.tailorkeys.layout.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerGridTest {
    private val grid = KeyGrid.template(Template.SPLIT)
    private fun typed(g: KeyGrid, r: Int) = g.items.filter { it.r == r && !KeyGrid.isShared(it) }.sortedBy { it.c }
        .joinToString("") { k -> KeyText.output(k.keys[0], InputState())
            ?: when (k.keys[0]) { Keys.NUM -> "[123]"; Keys.SYM -> "[#+=]"; Keys.BACKSPACE -> "[bs]"; else -> k.keys[0] } }

    @Test fun layersAreTheIphonesKeysSplitFiveAndFive() {
        val num = grid.layer(Layer.NUM)
        assertEquals("1234567890", typed(num, 0))
        assertEquals("-/:;()$&@\"", typed(num, 1))
        assertEquals("[#+=].,?!'[bs]", typed(num, 2))
        val sym = grid.layer(Layer.SYM)
        assertEquals("[]{}#%^*+=", typed(sym, 0))
        assertEquals("_\\|~<>€£¥•", typed(sym, 1))
        assertEquals("[123].,?!'[bs]", typed(sym, 2))
        // Five a side in the top rows, and every key in a half's region.
        assertEquals(5, num.items.count { it.r == 0 && it.c < 5 })
        assertTrue(num.items.all { num.valid(it) })
    }

    @Test fun theBottomRowIsTheLettersWithItsLayerKeyGoingBack() {
        val standard = KeyGrid.default()
        val num = standard.layer(Layer.NUM)
        val bottom = num.items.filter { it.r == standard.rows - 1 }
        assertTrue(bottom.isNotEmpty() && bottom.all(KeyGrid::isShared))
        assertTrue(bottom.any { it.keys == listOf(Keys.ABC) })
        assertTrue(bottom.any { it.isSpace })
        // The letters' own rows don't show on the layer.
        assertFalse(num.items.any { it.keys == listOf("q") })
        // A swipe key with an abc face stays whole, so it switches between every layer in its own order.
        assertTrue(grid.layer(Layer.SYM).items.any { KeyGrid.isShared(it) && it.keys == listOf(Keys.ABC, Keys.NUM, Keys.SYM) })
    }

    @Test fun layerKeysGoOnlyWhileEveryLayerCanStillBeReachedAndLeft() {
        val g = KeyGrid.default()
        val switch = g.items.single { Keys.NUM in it.keys }
        switch.keys.clear(); switch.keys.add(Keys.NUM)
        // 123 holds the only way to #+=.
        var num = g.layer(Layer.NUM)
        assertTrue(g.routesOk())
        assertEquals("Keep a way to 123 and #+= and back", num.removalHint(num.items.single { it.keys == listOf(Keys.SYM) }))
        assertEquals("Keep a way to 123 and #+= and back", g.removalHint(switch))
        // One key that swipes abc, #+=, 123 on every layer frees both layers' own switch keys.
        switch.keys.clear(); switch.keys.addAll(listOf(Keys.ABC, Keys.SYM, Keys.NUM))
        num = g.layer(Layer.NUM)
        val toSym = num.items.single { it.keys == listOf(Keys.SYM) }
        assertEquals(null, num.removalHint(toSym))
        assertTrue(num.remove(toSym))
        g.setLayer(Layer.NUM, num)
        val sym = g.layer(Layer.SYM)
        assertTrue(sym.remove(sym.items.single { it.keys == listOf(Keys.NUM) }))
        g.setLayer(Layer.SYM, sym)
        assertTrue(g.routesOk())
        assertFalse(g.remove(switch))
        // The letters' own shift key was never a route anywhere.
        assertTrue(g.remove(g.items.single { it.keys == listOf(Keys.ABC) }))
    }

    @Test fun anEditedLayerIsKeptSavedAndSharedWhileTheLettersStay() {
        val num = grid.layer(Layer.NUM)
        val dollar = num.items.first { it.keys == listOf(KeyText.literal("$")) }
        assertTrue(num.remove(dollar))
        num.items.first { it.keys == listOf("d1") }.keys[0] = KeyText.literal("€")
        grid.setLayer(Layer.NUM, num)
        for (restored in listOf(KeyGrid.fromJson(grid.toJson())!!, KeyGrid.fromCode(grid.toCode())!!, grid.copy())) {
            val layer = restored.layer(Layer.NUM)
            assertFalse(layer.items.any { it.keys == listOf(KeyText.literal("$")) })
            assertEquals(listOf(KeyText.literal("$")), layer.tray.last())
            assertTrue(layer.items.any { it.keys == listOf(KeyText.literal("€")) && it.r == 0 })
            // #+= is untouched, and so are the letters.
            assertEquals("[]{}#%^*+=", typed(restored.layer(Layer.SYM), 0))
            assertEquals(grid.items.size, restored.items.size)
        }
    }

    @Test fun aNumberRowSitsAboveTheLayersRows() {
        val g = KeyGrid.template(Template.SPLIT).apply { setNumberRow(true) }
        val num = g.layer(Layer.NUM)
        assertEquals("1234567890", typed(num, 1))
        // 123 has its own digits, so the letters' number row isn't repeated above them.
        assertTrue(num.items.none { it.r == 0 })
    }

    @Test fun theBottomRowIsEditedFromAnyLayerAndSharedByAll() {
        val g = KeyGrid.template(Template.SPLIT)
        val num = g.layer(Layer.NUM)
        val stack = num.items.single { KeyGrid.isShared(it) && it.isStack }
        val toSym = num.items.single { it.keys == listOf(Keys.SYM) }
        // Dropping #+= onto the key showing 123 puts it ahead of 123, once.
        num.combine(toSym, stack, stack.keys.indexOf(Keys.NUM))
        g.setLayer(Layer.NUM, num)
        assertEquals(listOf(Keys.ABC, Keys.SYM, Keys.NUM), g.items.single { it.r == g.rows - 1 && it.isStack }.keys)
        assertEquals(listOf(Keys.ABC, Keys.SYM, Keys.NUM), g.layer(Layer.SYM).items.single { KeyGrid.isShared(it) && it.isStack }.keys)
        assertFalse(g.layer(Layer.NUM).items.any { it.keys == listOf(Keys.SYM) })
        assertTrue(g.isSound() && g.routesOk())
        // A plain 123 key shows as ABC on the layers but stays 123 on the letters when the layer is saved.
        val s = KeyGrid.default()
        val view = s.layer(Layer.SYM)
        assertTrue(view.items.any { KeyGrid.isShared(it) && it.keys == listOf(Keys.ABC) })
        view.remove(view.items.first { it.keys == listOf(KeyText.literal("[")) })
        s.setLayer(Layer.SYM, view)
        assertEquals(listOf(Keys.NUM, Keys.SYM), s.items.single { Keys.NUM in it.keys }.keys)
        assertTrue(s.isSound())
    }

    @Test fun aBrokenSavedLayerFallsBackToTheDefault() {
        val json = org.json.JSONObject(grid.toJson()).put("layers", org.json.JSONObject()
            .put("num", org.json.JSONObject().put("items", org.json.JSONArray().put(
                org.json.JSONObject().put("r", 7).put("c", 0).put("w", 1).put("k", org.json.JSONArray().put("=x"))))))
        val restored = KeyGrid.fromJson(json.toString())!!
        assertEquals("1234567890", typed(restored.layer(Layer.NUM), 0))
    }
}
