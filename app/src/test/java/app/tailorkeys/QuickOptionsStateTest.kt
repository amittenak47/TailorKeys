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
        assertEquals(listOf(3), changedTiles(initial.copy(theme = ThemeName.WARM, themeLabel = ThemeName.WARM.title)))
    }

    @Test fun eachQuickActionChangesOnlyItsOwnTile() {
        assertEquals(listOf(0), changedTiles(initial.copy(split = true)))
        assertEquals(listOf(1), changedTiles(initial.copy(oneHand = Align.LEFT)))
        assertEquals(listOf(1), changedTiles(initial.copy(oneHand = Align.RIGHT)))
        assertEquals(listOf(2), changedTiles(initial.copy(height = 46)))
        assertEquals(listOf(5), changedTiles(initial.copy(language = "de")))
        assertEquals(listOf(5), changedTiles(initial.copy(multipleLanguages = true)))
        assertEquals(listOf(4), changedTiles(initial.copy(swipe = false)))
        assertEquals(listOf(7), changedTiles(initial.copy(learning = false)))
    }

    @Test fun labelsEnabledStateAndAccessibilityDescriptionsStayInSync() {
        val off = initial.tiles()
        assertEquals("Split, Off", off[0].description)
        assertFalse(off[0].on)
        assertEquals("Off", off[1].state)
        assertEquals("Size", off[2].label)
        assertEquals("44%", off[2].state)
        assertEquals("Size, 44 percent", off[2].description)
        assertEquals("Match system", off[3].state)
        assertEquals("On", off[4].state)
        assertEquals("EN", off[5].glyph)
        assertEquals("Language, Only one", off[5].description)
        assertFalse(off[5].enabled)
        assertNull(off[6].description)
        assertEquals("Pause learning, Off", off[7].description)
        assertFalse(off[7].on)
        assertEquals(listOf("Split", "One hand", "Size", "Theme", "Swipe", "Language", "Settings", "Pause learning", "Tidy"), off.map { it.label })

        val on = initial.copy(split = true, oneHand = Align.RIGHT, language = "fr",
            multipleLanguages = true, swipe = false, learning = false, theme = ThemeName.HIGH_CONTRAST).tiles()
        assertEquals("Split, On", on[0].description)
        assertTrue(on[0].on)
        assertEquals("Right", on[1].state)
        assertTrue(on[1].on)
        assertEquals("Off", on[4].state)
        assertEquals("FR", on[5].glyph)
        assertEquals("Language, Tap to switch", on[5].description)
        assertTrue(on[5].enabled)
        assertEquals("Pause learning, On", on[7].description)
        assertTrue(on[7].on)
        assertEquals("Tidy, Review text", on[8].description)
        assertFalse(on[8].on)
        assertEquals("My theme", initial.copy(themeLabel = "My theme").tiles()[3].state)
    }

    @Test fun completeRowsOrAnArtworkPeekFitPhoneTabletAndMinimumHeight() {
        // Includes 1200x1920 at mdpi/xhdpi, phone widths, a narrow one-hand panel and 24% heights.
        for (width in listOf(240, 320, 390, 600, 800, 1200)) for (height in listOf(160, 192, 230, 307, 384, 460, 600, 845)) {
            val header = if (width >= 600) 56 else 48
            val g = QuickPanelGeometry.fit(width, height, header)
            val rows = if (width < 260) 5 else 3
            val content = rows * (g.tile + 6) + 4
            assertTrue(g.tile >= 60)
            assertTrue(g.strip >= 56)
            if (g.viewport < content) {
                assertEquals(18, g.viewport % (g.tile + 6))
                assertTrue(g.viewport >= g.tile + 6 + 18)
                assertTrue(header + g.top + g.strip + g.gap + g.viewport <= height)
            } else assertEquals(content, g.viewport) // Outer scrolling can reveal every row even at 160dp.
        }
        val tall = QuickPanelGeometry.fit(800, 600, 56)
        assertEquals(72, tall.tile)
        assertEquals(138, tall.strip)
        val largeText = QuickPanelGeometry.fit(390, 192, 48, 92)
        assertEquals(92, largeText.tile)
        assertEquals(3 * 98 + 4, largeText.viewport)
    }
}
