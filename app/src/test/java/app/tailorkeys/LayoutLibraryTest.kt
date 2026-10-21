package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import app.tailorkeys.settings.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LayoutLibraryTest {
    @Test fun quickStripOrdersPresetsSavedAndDraftAndMarksOnlyAppliedTile() {
        val f = Fixture(); val store = f.store(); store.migrate()
        val saved = store.add("Desk", KeyGrid.default())
        val draft = store.startDraft(KeyGrid.template(Template.THUMBS))
        store.use(saved)
        val tiles = store.quickTiles()
        assertEquals(listOf("preset:STANDARD", "preset:SPLIT", "preset:THUMBS", saved.key, draft.key), tiles.map { it.entry.key })
        assertEquals(saved.key, tiles.single { it.inUse }.entry.key)
        assertTrue(tiles.single { it.inUse }.bold)
        assertEquals(2, tiles.single { it.inUse }.outlineDp)
        assertEquals("Desk, in use", tiles.single { it.inUse }.label)
        assertEquals("Custom, unsaved", tiles.last().label)
        store.use(draft)
        assertEquals("Custom, in use, unsaved", store.quickTiles().single { it.inUse }.label)
    }

    @Test fun quickNamesFillHolesAndSavedEditsKeepTheirIdentity() {
        val f = Fixture(); val store = f.store(); store.migrate()
        listOf("Custom 1", "Custom 3", "Imported 1", "Imported 3").forEach { store.add(it, KeyGrid.default()) }
        assertEquals("Custom 2", store.nextName("Custom"))
        assertEquals("Imported 2", store.nextName("Imported"))
        val saved = store.entries().first { it.custom }
        assertEquals(saved.key, store.quickEdit(saved, false).key)
        val changed = saved.grid().apply { items.first().keys[0] = "!" }
        store.edit(saved.key, changed)
        assertEquals(saved.key, store.inUse)
        assertEquals(changed.toJson(), store.entries().first { it.key == saved.key }.json)
        assertFalse(store.hasDraft)
    }

    @Test fun quickPresetEditForksAndExistingDraftMustBeSavedBeforeNewOrFork() {
        val f = Fixture(); val store = f.store(); store.migrate()
        val preset = store.entries().first()
        val draft = store.quickEdit(preset, false)
        assertEquals("draft", store.inUse)
        assertEquals(preset.json, draft.json)
        assertEquals(preset.json, store.entries().first().json)
        assertEquals(draft, store.quickEdit(draft, false))
        val before = f.values.toMap()
        assertThrows(IllegalStateException::class.java) { store.quickEdit(preset, false) }
        assertThrows(IllegalStateException::class.java) { store.quickEdit(draft, true) }
        assertEquals(before, f.values)
        store.saveDraft(store.nextName("Custom"))
        val next = store.quickEdit(preset, true)
        assertEquals("draft", next.key)
        assertEquals("Custom 1", store.entries().single { it.custom && !it.draft }.name)
    }

    @Test fun clipboardImportUsesBoundedDecoderAndOptionalNameWithoutApplying() {
        val f = Fixture(); val store = f.store(); store.migrate()
        assertEquals(ClipboardLayoutResult.Empty, store.importCode(null))
        assertEquals(ClipboardLayoutResult.Empty, store.importCode("  "))
        for (code in listOf("hello", "TK1-bad", "TK2-abc", "TK1-" + "A".repeat(140000))) {
            assertEquals(ClipboardLayoutResult.Invalid, store.importCode(code))
        }
        val grid = KeyGrid.template(Template.THUMBS)
        val added = store.importCode("Shared layout: " + grid.toCode()) as ClipboardLayoutResult.Added
        assertEquals("Imported 1", added.entry.name)
        assertEquals(grid.toJson(), added.entry.json)
        assertEquals("preset:STANDARD", store.inUse)
        assertEquals("Imported 2", (store.importCode(grid.toCode()) as ClipboardLayoutResult.Added).entry.name)
        val raw = JSONObject(grid.toJson()).put("name", "Travel").toString().toByteArray()
        val deflater = java.util.zip.Deflater(9, true)
        deflater.setInput(raw); deflater.finish()
        val buffer = ByteArray(raw.size + 64)
        val count = deflater.deflate(buffer); deflater.end()
        val code = "TK1-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.copyOf(count))
        assertEquals("Travel", (store.importCode(code) as ClipboardLayoutResult.Added).entry.name)
    }

    @Test fun creatingKeyboardLibraryDoesNotRecoverAnActiveTypingTrial() {
        val f = Fixture(); val store = f.store(); store.migrate()
        val trial = store.entries().first { it.key == "preset:SPLIT" }
        store.beginTrial(trial)
        val before = f.grid.toJson()
        f.store().migrate(recoverTrial = false)
        assertEquals(before, f.grid.toJson())
        assertNotNull(f.values[LayoutKeys.TRIAL])
        assertEquals("preset:STANDARD", store.inUse)
    }
    private class Fixture {
        val values = mutableMapOf<String, String>()
        var grid = KeyGrid.default()
        var id = 0
        val events = mutableListOf<String>()
        fun store() = LayoutLibrary(object : LayoutPreferences {
            override fun get(key: String) = values[key]
            override fun write(values: Map<String, String?>) {
                values.forEach { (key, value) -> if (value == null) this@Fixture.values.remove(key) else this@Fixture.values[key] = value }
                events += "write"
            }
        }, { grid }, { grid = it; events += "apply" }, { "en" }, { grid.rows == 5 }, { "id-${++id}" })
    }

    @Test fun migrationSelectsEveryPresetAndPreservesCustomExactlyOnce() {
        Template.entries.forEach { template ->
            val f = Fixture()
            f.grid = KeyGrid.template(template).apply { setNumberRow(true) }
            f.store().migrate()
            assertEquals("preset:${template.name}", f.values[LayoutKeys.SELECTED])
            assertNull(f.values[LayoutKeys.SAVED])
        }
        val f = Fixture()
        f.grid.items.first().keys[0] = "!"
        val original = f.grid.toJson()
        f.store().migrate()
        f.store().migrate()
        val saved = f.store().entries().filter { it.custom }
        assertEquals(1, saved.size)
        assertEquals("My layout", saved.single().name)
        assertEquals(original, saved.single().json)
        assertEquals(saved.single().key, f.values[LayoutKeys.SELECTED])
    }

    @Test fun saveRenameAndDeleteKeepSharedJsonFormatAndOrder() {
        val f = Fixture(); val store = f.store(); store.migrate()
        val first = store.add("Desk", KeyGrid.default())
        val second = store.add("Couch", KeyGrid.template(Template.THUMBS))
        store.rename(first.key, "Work")
        assertEquals(listOf("Work", "Couch"), store.entries().filter { it.custom }.map { it.name })
        val array = JSONArray(f.values[LayoutKeys.SAVED])
        assertEquals(setOf("id", "name", "grid"), array.getJSONObject(0).keys().asSequence().toSet())
        assertTrue(array.getJSONObject(0).get("grid") is JSONObject)
        store.delete(first.key)
        assertEquals(second.key, store.entries().single { it.custom }.key)
    }

    @Test fun deletingAppliedCustomSwitchesToStandardBeforeRemovingIt() {
        val f = Fixture(); val store = f.store(); store.migrate()
        val entry = store.add("Desk", KeyGrid.template(Template.THUMBS)); store.use(entry)
        store.delete(entry.key)
        assertEquals("preset:STANDARD", store.inUse)
        assertTrue(LayoutLibrary.sameGrid(KeyGrid.default(), f.grid))
        assertFalse(store.entries().any { it.key == entry.key })
    }

    @Test fun draftSaveFirstCancelAndContinueNeverOverwriteTheDraft() {
        val f = Fixture(); val store = f.store(); store.migrate()
        assertEquals(NewLayoutFlow.EDIT, store.requestNew())
        val original = store.startDraft(KeyGrid.template(Template.THUMBS))
        assertEquals(NewLayoutFlow.SAVE_FIRST, store.requestNew())
        val before = f.values.toMap()
        assertEquals(NewLayoutFlow.SAVE_FIRST, store.requestNew()) // opening then cancelling changes nothing
        assertEquals(before, f.values)
        assertThrows(IllegalStateException::class.java) { store.startDraft(KeyGrid.default()) }
        store.use(original)
        val next = store.saveAndContinue("Couch", KeyGrid.default())
        assertEquals("draft", next.key)
        assertEquals(1, store.entries().count { it.draft })
        val saved = store.entries().single { it.custom && !it.draft }
        assertEquals(original.json, saved.json)
        assertEquals(saved.key, store.inUse)
        assertTrue(LayoutLibrary.sameGrid(KeyGrid.template(Template.THUMBS), f.grid))
    }

    @Test fun editingUnusedLayoutDoesNotApplyAndEditingAppliedLayoutDoes() {
        val f = Fixture(); val store = f.store(); store.migrate()
        val draft = store.startDraft(KeyGrid.template(Template.THUMBS))
        val edited = draft.grid().apply { items.first().keys[0] = "!" }
        store.edit(draft.key, edited)
        assertTrue(LayoutLibrary.sameGrid(KeyGrid.default(), f.grid))
        store.use(store.entries().single { it.draft })
        edited.items.first().keys[0] = "?"
        store.edit(draft.key, edited)
        assertEquals("?", f.grid.items.first().keys[0])
    }

    @Test fun trialIsDurableBeforeApplyAndRestoresOnNextLaunch() {
        val f = Fixture(); val store = f.store(); store.migrate()
        val previous = f.grid.toJson()
        val trial = store.entries().first { it.key == "preset:THUMBS" }
        f.events.clear(); store.beginTrial(trial)
        assertEquals(listOf("write", "apply"), f.events)
        assertEquals(previous, f.values[LayoutKeys.TRIAL])
        assertEquals("preset:STANDARD", store.inUse)
        f.store().migrate() // simulates relaunch after the process disappeared
        assertEquals(previous, f.grid.toJson())
        assertNull(f.values[LayoutKeys.TRIAL])
        assertFalse(store.restoreTrial())
    }

    @Test fun acceptingTrialKeepsItButLeavingRestoresPreviousNumberRow() {
        val f = Fixture(); val store = f.store(); store.migrate()
        val trial = store.add("Numbers", KeyGrid.default().apply { setNumberRow(true) })
        store.beginTrial(trial); assertEquals(5, f.grid.rows)
        store.restoreTrial(); assertEquals(4, f.grid.rows)
        store.beginTrial(trial); store.use(trial); store.restoreTrial()
        assertEquals(5, f.grid.rows)
        assertEquals(trial.key, store.inUse)
        assertNull(f.values[LayoutKeys.TRIAL])
    }

    @Test fun resultsKeepLastTwentyPerLayoutAndFollowSavedDraft() {
        val f = Fixture(); val store = f.store(); store.migrate(); store.startDraft(KeyGrid.default())
        repeat(25) { store.record("draft", TypingResult(it.toDouble(), .9, it.toLong())) }
        store.record("preset:STANDARD", TypingResult(42.0, 1.0, 42))
        val saved = store.saveDraft("Desk")
        assertEquals((5..24).map { it.toDouble() }, store.results(saved.key).map { it.wpm })
        assertEquals(42.0, store.results("preset:STANDARD").single().wpm, 0.0)
        assertTrue(store.results("draft").isEmpty())
        assertEquals(14.5, store.results(saved.key).map { it.wpm }.average(), 0.0)
    }
}
