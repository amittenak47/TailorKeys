package app.tailorkeys

import app.tailorkeys.layout.InputState
import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.KeyText
import app.tailorkeys.layout.Keys
import app.tailorkeys.layout.Layer
import app.tailorkeys.layout.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayerFacesTest {
    private val grid = KeyGrid.template(Template.SPLIT)
    private fun key(code: String) = grid.items.first { code in it.keys }
    private fun state(layer: Layer) = InputState().apply { this.layer = layer }

    @Test fun keysKeepTheirLetterDefaultsUntilChanged() {
        assertEquals("1", KeyText.layerFace(key("q"), Layer.NUM, "en"))
        assertEquals("[", KeyText.layerFace(key("q"), Layer.SYM, "en"))
        assertEquals("q", KeyText.layerCode(key("q"), "q", Layer.NUM))
    }

    @Test fun aKeysOwnTextIsTypedLiterallyOnItsLayerOnly() {
        grid.setLayerFace(key("q"), Layer.NUM, "€", "en")
        val code = KeyText.layerCode(key("q"), "q", Layer.NUM)
        assertEquals("€", KeyText.output(code, state(Layer.NUM)))
        assertEquals("€", KeyText.label(code, state(Layer.NUM)))
        // Letters and the other layer are untouched; a letter as layer text isn't mapped to a digit.
        assertEquals("q", KeyText.layerCode(key("q"), "q", Layer.ABC))
        assertEquals("[", KeyText.layerFace(key("q"), Layer.SYM, "en"))
        grid.setLayerFace(key("w"), Layer.NUM, "x", "en")
        assertEquals("x", KeyText.output(KeyText.layerCode(key("w"), "w", Layer.NUM), state(Layer.NUM)))
    }

    @Test fun choosingTheDefaultClearsTheOverride() {
        grid.setLayerFace(key("q"), Layer.NUM, "€", "en")
        grid.setLayerFace(key("q"), Layer.NUM, "1", "en")
        assertNull(key("q").num)
        grid.setLayerFace(key("q"), Layer.NUM, "€", "en")
        grid.setLayerFace(key("q"), Layer.NUM, null, "en")
        assertNull(key("q").num)
    }

    @Test fun swappingExchangesTextButNotKeys() {
        val q = key("q"); val p = key("p")
        val (qr, qc) = q.r to q.c
        grid.swapLayerFaces(q, p, Layer.NUM, "en")
        assertEquals("0", KeyText.layerFace(q, Layer.NUM, "en"))
        assertEquals("1", KeyText.layerFace(p, Layer.NUM, "en"))
        assertEquals(qr to qc, q.r to q.c)
        // Swapping back lands on the defaults, so nothing is stored.
        grid.swapLayerFaces(q, p, Layer.NUM, "en")
        assertNull(q.num); assertNull(p.num)
    }

    @Test fun functionKeysHaveNoLayerText() {
        val space = grid.items.first { Keys.SPACE in it.keys }
        grid.setLayerFace(space, Layer.NUM, "€", "en")
        assertNull(space.num)
        assertEquals(Keys.SPACE, KeyText.layerCode(space, Keys.SPACE, Layer.NUM))
    }

    @Test fun layerTextSurvivesSavingSharingAndCopying() {
        grid.setLayerFace(key("q"), Layer.NUM, "€", "en")
        grid.setLayerFace(key("a"), Layer.SYM, ":)", "en")
        for (restored in listOf(KeyGrid.fromJson(grid.toJson())!!, KeyGrid.fromCode(grid.toCode())!!, grid.copy())) {
            assertEquals("€", restored.items.first { "q" in it.keys }.num)
            assertEquals(":)", restored.items.first { "a" in it.keys }.sym)
        }
        // Old saved layouts without layer text still load with the defaults.
        val json = org.json.JSONObject(grid.toJson()).apply {
            val items = getJSONArray("items")
            for (i in 0 until items.length()) items.getJSONObject(i).apply { remove("n"); remove("s") }
        }
        val old = KeyGrid.fromJson(json.toString())!!
        assertNull(old.items.first { "q" in it.keys }.num)
    }
}
