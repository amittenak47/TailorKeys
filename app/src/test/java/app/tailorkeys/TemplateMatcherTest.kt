package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import app.tailorkeys.settings.TemplateMatcher
import org.junit.Assert.*
import org.junit.Test

class TemplateMatcherTest {
    @Test fun allPresetsMatchAcrossLanguagesAndNumberRowsWithOneCache() {
        val matcher = TemplateMatcher()
        // Revisit both configurations to exercise cache replacement as well as reuse.
        repeat(2) {
            for (language in listOf("en", "es", "de", "fr")) for (numberRow in listOf(false, true)) {
                for (template in Template.entries) {
                    val grid = KeyGrid.template(template, language).apply { if (numberRow) setNumberRow(true) }
                    assertEquals(template.title, matcher.name(grid, language, numberRow))
                    assertEquals(template.title, matcher.name(grid, language, numberRow))
                }
            }
        }
    }

    @Test fun swipeFacesAndIdsDoNotAffectMatchingButEditsToTheSameGridDo() {
        val matcher = TemplateMatcher()
        val grid = KeyGrid.template(Template.STANDARD)
        grid.items.forEach { it.idx = it.keys.lastIndex }
        assertEquals(Template.STANDARD.title, matcher.name(grid, "en", false))

        val key = grid.items.first { it.keys.size == 1 && it.keys[0] == "q" }
        key.keys[0] = "!"
        assertEquals("Custom", matcher.name(grid, "en", false))
        key.keys[0] = "q"
        assertEquals(Template.STANDARD.title, matcher.name(grid, "en", false))

        val original = grid.items[0]
        grid.items[0] = app.tailorkeys.layout.GridKey(999, original.r, original.c, original.w, original.keys)
        assertEquals(Template.STANDARD.title, matcher.name(grid, "en", false))
        grid.items[0].w++
        assertEquals("Custom", matcher.name(grid, "en", false))
    }

    @Test fun mismatchedLanguageNumberRowAndKeyOrderRemainCustom() {
        val matcher = TemplateMatcher()
        val grid = KeyGrid.template(Template.STANDARD, "fr")
        assertEquals("Custom", matcher.name(grid, "en", false))
        assertEquals("Custom", matcher.name(grid, "fr", true))
        assertEquals(Template.STANDARD.title, matcher.name(grid, "fr", false))
        java.util.Collections.swap(grid.items, 0, 1)
        assertEquals("Custom", matcher.name(grid, "fr", false))
    }
}
