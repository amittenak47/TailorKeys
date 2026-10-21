package app.tailorkeys

import org.junit.Assert.*
import org.junit.Test

class QuickOptionsStateTest {
    private val initial = QuickOptionsState(ThemeName.SYSTEM, 16, 8, false, Align.CENTER,
        44, "en", false, true)

    private fun changedTiles(next: QuickOptionsState): List<Int> {
        val before = initial.tiles()
        return next.tiles().mapIndexedNotNull { index, tile -> index.takeIf { tile != before[index] } }
    }

    @Test fun unchangedRefreshAndAppearanceChangesKeepTileContent() {
        assertEquals(initial, initial.copy())
        assertTrue(changedTiles(initial.copy()).isEmpty())
        assertTrue(changedTiles(initial.copy(radius = 20)).isEmpty())
        assertTrue(changedTiles(initial.copy(nightMode = 32)).isEmpty())
        assertEquals(listOf(3), changedTiles(initial.copy(theme = ThemeName.WARM)))
    }

    @Test fun eachQuickActionChangesOnlyItsOwnTile() {
        assertEquals(listOf(0), changedTiles(initial.copy(split = true)))
        assertEquals(listOf(1), changedTiles(initial.copy(oneHand = Align.LEFT)))
        assertEquals(listOf(1), changedTiles(initial.copy(oneHand = Align.RIGHT)))
        assertEquals(listOf(2), changedTiles(initial.copy(height = 46)))
        assertEquals(listOf(4), changedTiles(initial.copy(language = "de")))
        assertEquals(listOf(4), changedTiles(initial.copy(multipleLanguages = true)))
        assertEquals(listOf(5), changedTiles(initial.copy(swipe = false)))
    }

    @Test fun labelsEnabledStateAndAccessibilityDescriptionsStayInSync() {
        val off = initial.tiles()
        assertEquals("Split, Off", off[0].description)
        assertFalse(off[0].on)
        assertEquals("Off", off[1].state)
        assertEquals("Size", off[2].label)
        assertEquals("44%", off[2].state)
        assertEquals("Size, 44 percent", off[2].description)
        assertEquals("System", off[3].state)
        assertEquals("EN", off[4].glyph)
        assertEquals("Language, Only one", off[4].description)
        assertFalse(off[4].enabled)
        assertNull(off[6].description)

        val on = initial.copy(split = true, oneHand = Align.RIGHT, language = "fr",
            multipleLanguages = true, swipe = false, theme = ThemeName.HIGH_CONTRAST).tiles()
        assertEquals("Split, On", on[0].description)
        assertTrue(on[0].on)
        assertEquals("Right", on[1].state)
        assertTrue(on[1].on)
        assertEquals("High contrast", on[3].state)
        assertEquals("FR", on[4].glyph)
        assertEquals("Language, Tap to switch", on[4].description)
        assertTrue(on[4].enabled)
        assertEquals("Swipe, Off", on[5].description)
        assertFalse(on[5].on)
    }
}
