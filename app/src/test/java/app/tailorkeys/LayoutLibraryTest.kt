package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import app.tailorkeys.settings.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LayoutLibraryTest {
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
