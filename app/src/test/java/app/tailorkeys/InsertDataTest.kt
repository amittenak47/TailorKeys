package app.tailorkeys

import app.tailorkeys.insert.*
import app.tailorkeys.layout.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class InsertDataTest {
    private fun asset(name: String) = File("src/main/assets/insert/$name")
    private fun catalog() = asset("emoji.tsv").bufferedReader().use { EmojiCatalog.load(it.lineSequence()) }
    @Test fun unicodeDataAndKeywordSearch() {
        val data = catalog()
        assertTrue(data.entries.size > 3_900)
        assertTrue(data.search("grinning face").any { it.text == "😀" })
        assertTrue(data.search("happy").any { it.text == "😀" })
        assertTrue(data.search("doctor").any { it.name.contains("health worker") })
        assertEquals(data.search("happy face"), data.search("  HAPPY   FACE  "))
        assertEquals(data.entries.size, data.entries.map { it.text }.distinct().size)
        assertTrue(data.search("no-such-emoji-xyz").isEmpty())
        assertEquals(9, data.primary.map { it.category }.distinct().size)
        assertEquals(150, asset("kaomoji.tsv").readLines().size)
        assertTrue(asset("kaomoji.tsv").readText().any { it.code > 127 })
    }
    @Test fun tonesPreserveUnicodeSequences() {
        val data = catalog()
        for (text in listOf("👍", "👩‍💻", "🧑‍⚕️", "✌️", "☝️", "✍️")) {
            val family = data.variants(text)
            assertEquals(text, family.first().text)
            assertEquals(6, family.size)
            family.drop(1).forEachIndexed { index, entry -> assertTrue(entry.text.codePoints().anyMatch { it == 0x1F3FB + index }) }
            assertEquals(family[3], data.toned(family[0], 3))
            assertEquals(family[3], data.toned(family[3], 5))
            assertEquals(family, data.variants(family[3].text))
        }
        assertEquals(1, data.variants("😀").size)
    }
    @Test fun recentOrderingAndCap() {
        val recents = InsertRecents()
        (0..40).forEach { recents.use("$it") }; recents.use("12")
        assertEquals(32, recents.values().size)
        assertEquals("12", recents.values().first())
        assertEquals(1, recents.values().count { it == "12" })
        assertEquals("40", recents.values()[1])
    }
    @Test fun clipboardChoosesExactlyOneStateAndHistoryOffWins() {
        for (has in listOf(false, true)) for (had in listOf(false, true)) for (dismissed in listOf(false, true)) {
            assertEquals(ClipboardPanelState.HISTORY_OFF, clipboardPanelState(false, has, had, dismissed))
            assertEquals(ClipboardPanelState.HISTORY_OFF, clipboardPanelState(false, has, had, dismissed, false))
            assertEquals(ClipboardPanelState.ACCESS_REQUIRED, clipboardPanelState(true, has, had, dismissed, false))
        }
        assertEquals(ClipboardPanelState.FIRST_USE, clipboardPanelState(true, false, false, false))
        assertEquals(ClipboardPanelState.EMPTY, clipboardPanelState(true, false, true, false))
        assertEquals(ClipboardPanelState.EMPTY, clipboardPanelState(true, false, false, true))
        assertEquals(ClipboardPanelState.ITEMS, clipboardPanelState(true, true, false, false))
    }
    @Test fun capturePrivacyExpiryPinsAndRoundTrip() {
        var now = 0L
        val s = ClipboardStore(clock = { now })
        assertFalse(s.capture("password", true, false, true))
        assertFalse(s.capture("sensitive", true, true, false))
        assertFalse(s.capture("off", false, false, false))
        assertFalse(s.capture("   ", true, false, false))
        assertTrue(s.capture("keep", true, false, false)); s.pin(s.clips().first().id)
        now++
        s.capture("expire", true, false, false)
        val note = s.note("address", "text")
        now += ClipKeep.HOUR.millis
        s.expire(ClipKeep.HOUR)
        assertEquals(listOf("keep"), s.clips().map { it.text })
        assertEquals(note, s.notes().first())
        s.pin(s.clips().first().id); s.expire(ClipKeep.HOUR)
        assertTrue(s.clips().isEmpty())
        val decoded = ClipboardStore.decode(s.encode())
        assertEquals(s.notes(), decoded.notes())
    }
    @Test fun notesEditDeleteUndoAndClear() {
        var now = 100L; val s = ClipboardStore(clock = { now })
        s.capture("clip", true, false, false)
        val note = s.saveAsNote(s.clips().first().id)!!
        s.edit(note.id, "new", "title"); s.pin(note.id)
        assertEquals("new", s.notes().single().text); assertEquals("title", s.notes().single().title)
        s.delete(note.id); assertTrue(s.notes().isEmpty()); assertTrue(s.undo()); assertTrue(s.notes().single().pinned)
        s.delete(note.id); now += 6_001; assertFalse(s.undo())
        s.note("survivor", "note"); s.clear(); assertTrue(s.clips().isEmpty()); assertEquals(1, s.notes().size)
        s.capture("pin", true, false, false); s.pin(s.clips().first().id); s.clear(); assertEquals(1, s.clips().size)
    }
    @Test fun promptIsExplicitAndInsertIsRemovable() {
        for (template in Template.entries) {
            val grid = KeyGrid.template(template)
            assertTrue(grid.isSound()); assertFalse(grid.addInsert())
            val insert = grid.items.single { Keys.INSERT in it.keys }
            assertNull(grid.removalHint(insert)); assertTrue(grid.remove(insert))
            val before = grid.toJson()
            assertTrue(InsertKeyPrompt.shouldShow(false, true, grid))
            assertFalse(InsertKeyPrompt.shouldShow(true, true, grid)); assertFalse(InsertKeyPrompt.shouldShow(false, false, grid))
            assertEquals(before, grid.toJson())
            assertTrue(grid.addInsert()); assertTrue(grid.isSound())
            assertFalse(InsertKeyPrompt.shouldShow(false, true, grid))
            val num = grid.items.single { Keys.NUM in it.keys }
            assertEquals(num.c + num.w, grid.items.single { Keys.INSERT in it.keys }.c)
        }
    }
    @Test fun savedLayoutsAndDraftChangeOnlyAfterExplicitAdd() {
        val storage = mutableMapOf<String, String>()
        val prefs = object : LayoutPreferences {
            override fun get(key: String) = storage[key]
            override fun write(values: Map<String, String?>) {
                values.forEach { (key, value) -> if (value == null) storage.remove(key) else storage.put(key, value) }
            }
        }
        val legacy = KeyGrid.default().apply { remove(items.single { Keys.INSERT in it.keys }); normalize() }
        val original = legacy.toJson()
        var applied = legacy
        val library = LayoutLibrary(prefs, { applied }, { applied = it }, { "en" }, { false }, newId = { "legacy" })
        library.migrate()
        val saved = library.entries().single { it.custom }
        val draft = library.startDraft(legacy)
        assertEquals(original, saved.json)
        assertEquals(original, draft.json)
        assertEquals(original, KeyGrid.savedOrDefault(KeyGrid.fromJson(original), "en", true).toJson())
        assertTrue(InsertKeyPrompt.shouldShow(false, saved.custom, saved.grid()))
        assertEquals(original, library.entries().single { it.key == saved.key }.json)
        val changed = saved.grid().apply { assertTrue(addInsert()) }
        library.edit(saved.key, changed)
        assertEquals(changed.toJson(), applied.toJson())
        assertEquals(original, library.entries().single { it.draft }.json)
    }
    @Test fun allRetentionPeriodsAndCaptureCap() {
        for (keep in ClipKeep.entries) {
            var now = 0L
            val store = ClipboardStore(clock = { now })
            store.capture("clip", true, false, false)
            now = if (keep == ClipKeep.FOREVER) Long.MAX_VALUE / 2 else keep.millis - 1
            store.expire(keep); assertEquals(1, store.clips().size)
            if (keep != ClipKeep.FOREVER) { now++; store.expire(keep); assertTrue(store.clips().isEmpty()) }
        }
        var now = 0L
        val store = ClipboardStore(clock = { now++ })
        repeat(110) { store.capture("clip$it", true, false, false) }
        assertEquals(100, store.clips().size)
        val pinned = store.clips().last(); store.pin(pinned.id)
        store.capture(pinned.text, true, false, false)
        assertEquals(1, store.clips().count { it.text == pinned.text })
        store.capture("clip109", true, false, false)
        assertEquals("clip109", store.clips().first { !it.pinned }.text)
    }
}
