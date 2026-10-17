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
        assertEquals(listOf(4), changedTiles(initial.copy(theme = ThemeName.WARM)))
    }

    @Test fun eachQuickActionChangesOnlyItsOwnTile() {
        assertEquals(listOf(1), changedTiles(initial.copy(split = true)))
        assertEquals(listOf(2), changedTiles(initial.copy(oneHand = Align.LEFT)))
        assertEquals(listOf(2), changedTiles(initial.copy(oneHand = Align.RIGHT)))
        assertEquals(listOf(3), changedTiles(initial.copy(height = 46)))
        assertEquals(listOf(5), changedTiles(initial.copy(language = "de")))
        assertEquals(listOf(5), changedTiles(initial.copy(multipleLanguages = true)))
        assertEquals(listOf(6), changedTiles(initial.copy(swipe = false)))
    }

    @Test fun labelsEnabledStateAndAccessibilityDescriptionsStayInSync() {
        val off = initial.tiles()
        assertEquals("Split, Off", off[1].description)
        assertFalse(off[1].on)
        assertEquals("Off", off[2].state)
        assertEquals("Size 44%", off[3].label)
        assertEquals("Size, 44 percent", off[3].description)
        assertEquals("System", off[4].state)
        assertEquals("EN", off[5].glyph)
        assertEquals("Language, Only one", off[5].description)
        assertFalse(off[5].enabled)
        assertNull(off[0].description)
        assertNull(off[7].description)

        val on = initial.copy(split = true, oneHand = Align.RIGHT, language = "fr",
            multipleLanguages = true, swipe = false, theme = ThemeName.HIGH_CONTRAST).tiles()
        assertEquals("Split, On", on[1].description)
        assertTrue(on[1].on)
        assertEquals("Right", on[2].state)
        assertTrue(on[2].on)
        assertEquals("High contrast", on[4].state)
        assertEquals("FR", on[5].glyph)
        assertEquals("Language, Tap to switch", on[5].description)
        assertTrue(on[5].enabled)
        assertEquals("Swipe, Off", on[6].description)
        assertFalse(on[6].on)
    }
}
