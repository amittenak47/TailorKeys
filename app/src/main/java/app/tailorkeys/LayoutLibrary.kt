package app.tailorkeys

import app.tailorkeys.KeyboardSettings
import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The wire format is shared with iOS. Only the applied tile is persisted as layout_selected. */
internal object LayoutKeys {
    const val SAVED = "layouts_saved"
    const val DRAFT = "layout_draft"
    const val SELECTED = "layout_selected"
    const val TRIAL = "layout_trial_previous"
    const val DRAFT_SPLIT = "layout_draft_split"
    const val TRIAL_SPLIT = "layout_trial_split"
    const val TRIAL_SETTINGS = "layout_trial_settings"
    const val RESULTS = "typing_results"
}

internal interface LayoutPreferences {
    fun get(key: String): String?
    /** Must finish durably before returning: trial recovery must survive process death. */
    fun write(values: Map<String, String?>)
}

internal data class LayoutEntry(val key: String, val name: String, val json: String, val preset: Template? = null, val split: Boolean = preset?.split ?: false, val settings: String? = null) {
    val custom get() = preset == null
    val draft get() = key == "draft"
    fun grid(): KeyGrid = requireNotNull(KeyGrid.fromJson(json))
    fun code() = grid().toCode(settings)
    fun splitFor(landscape: Boolean) = preset?.split ?: settings?.let {
        runCatching { JSONObject(it).opt(Prefs.splitKey(landscape)) as? Boolean }.getOrNull()
    } ?: split
}

internal data class TypingResult(val wpm: Double, val accuracy: Double, val at: Long)
internal enum class NewLayoutFlow { EDIT, SAVE_FIRST }

internal class LayoutLibrary(
    private val prefs: LayoutPreferences,
    private val loadGrid: () -> KeyGrid,
    private val saveGrid: (KeyGrid) -> Unit,
    private val language: () -> String,
    private val numberRow: () -> Boolean,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val loadSplit: () -> Boolean = { false },
    private val saveSplit: (Boolean) -> Unit = {},
    private val loadSettings: () -> String = { "{}" },
    private val saveSettings: (String) -> Unit = {},
) {
    constructor(settings: KeyboardSettings, landscape: () -> Boolean = { false }) : this(object : LayoutPreferences {
        override fun get(key: String) = settings.prefs.getString(key, null)
        override fun write(values: Map<String, String?>) {
            val editor = settings.prefs.edit()
            values.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
            check(editor.commit()) { "Could not save layouts" }
        }
    }, settings::loadGrid, settings::saveGrid, settings::currentLanguage, { settings.bool(app.tailorkeys.Prefs.NUMBER_ROW) },
        loadSplit = { settings.split(landscape()) }, saveSplit = { settings.applyLayoutSplit(landscape(), it) },
        loadSettings = settings::layoutSettings, saveSettings = settings::applyLayoutSettings)

    private var cachedInputs: List<String?>? = null
    private var cachedEntries = emptyList<LayoutEntry>()
    val inUse: String get() = prefs.get(LayoutKeys.SELECTED) ?: "preset:STANDARD"
    val hasDraft get() = prefs.get(LayoutKeys.DRAFT) != null

    fun entries(): List<LayoutEntry> {
        val inputs = listOf(prefs.get(LayoutKeys.SAVED), prefs.get(LayoutKeys.DRAFT), language(), numberRow().toString(), prefs.get(LayoutKeys.DRAFT_SPLIT), loadSplit().toString())
        if (inputs == cachedInputs) return cachedEntries
        val presets = Template.entries.map { t ->
            val grid = KeyGrid.template(t, language()).apply { if (numberRow()) setNumberRow(true) }
            LayoutEntry("preset:${t.name}", t.title, grid.toJson(), t)
        }
        cachedEntries = presets + saved() + listOfNotNull(prefs.get(LayoutKeys.DRAFT)?.let { json ->
            KeyGrid.fromJson(json)?.let { LayoutEntry("draft", "Custom", it.toJson(), split = prefs.get(LayoutKeys.DRAFT_SPLIT)?.toBooleanStrictOrNull() ?: loadSplit(),
                settings = runCatching { JSONObject(json).optJSONObject("settings")?.toString() }.getOrNull()) }
        })
        cachedInputs = inputs
        return cachedEntries
    }

    private fun saved(): List<LayoutEntry> = runCatching {
        val array = JSONArray(prefs.get(LayoutKeys.SAVED) ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            KeyGrid.fromJson(item.getJSONObject("grid").toString())?.let {
                LayoutEntry("saved:$id", item.getString("name"), it.toJson(), split = item.optBoolean("split", loadSplit()), settings = item.optJSONObject("settings")?.toString())
            }
        }.distinctBy { it.key }
    }.getOrDefault(emptyList())

    private fun encode(entries: List<LayoutEntry>): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put("id", it.key.removePrefix("saved:")).put("name", it.name)
            .put("grid", JSONObject(it.json)).put("split", it.split).apply { it.settings?.let { value -> put("settings", JSONObject(value)) } }) }
    }.toString()

    fun migrate(recoverTrial: Boolean = true) {
        if (recoverTrial) restoreTrial()
        // Give pre-split saved layouts a stable value once, before browsing other layouts.
        prefs.get(LayoutKeys.SAVED)?.let { json ->
            if (runCatching { val a = JSONArray(json); (0 until a.length()).any { !a.getJSONObject(it).has("split") } }.getOrDefault(false))
                prefs.write(mapOf(LayoutKeys.SAVED to encode(saved())))
        }
        if (hasDraft && prefs.get(LayoutKeys.DRAFT_SPLIT) == null)
            prefs.write(mapOf(LayoutKeys.DRAFT_SPLIT to loadSplit().toString()))
        if (prefs.get(LayoutKeys.SELECTED) != null) return
        val grid = loadGrid()
        val preset = entries().firstOrNull { it.preset != null && sameGrid(it.grid(), grid) }
        if (preset != null) prefs.write(mapOf(LayoutKeys.SELECTED to preset.key))
        else {
            val entry = LayoutEntry("saved:${newId()}", "My layout", grid.toJson(), split = loadSplit(), settings = loadSettings())
            prefs.write(mapOf(LayoutKeys.SAVED to encode(saved() + entry), LayoutKeys.SELECTED to entry.key))
        }
    }

    fun add(name: String, grid: KeyGrid, split: Boolean = loadSplit(), settings: String? = loadSettings()): LayoutEntry {
        require(grid.isSound())
        val entry = LayoutEntry("saved:${newId()}", cleanName(name), grid.toJson(), split = split, settings = settings)
        prefs.write(mapOf(LayoutKeys.SAVED to encode(saved() + entry)))
        return entry
    }

    fun requestNew(): NewLayoutFlow = if (hasDraft) NewLayoutFlow.SAVE_FIRST else NewLayoutFlow.EDIT

    fun nextName(prefix: String): String {
        val names = saved().map { it.name.lowercase(java.util.Locale.ROOT) }.toSet()
        return generateSequence(1) { it + 1 }.map { "$prefix $it" }
            .first { it.lowercase(java.util.Locale.ROOT) !in names }
    }

    /** Both entry points preserve the sole draft. The caller presents Save first before retrying. */
    fun quickEdit(source: LayoutEntry, newCopy: Boolean): LayoutEntry {
        val entry = if (newCopy || !source.custom) startDraft(source.grid(), source.split, source.settings) else source
        use(entry)
        return entry
    }

    fun importCode(text: String?): ClipboardLayoutResult {
        if (text.isNullOrBlank()) return ClipboardLayoutResult.Empty
        val decoded = KeyGrid.decodeCode(text) ?: return ClipboardLayoutResult.Invalid
        return ClipboardLayoutResult.Added(add(decoded.name ?: nextName("Imported"), decoded.grid, settings = decoded.settings ?: loadSettings()))
    }

    fun startDraft(source: KeyGrid, split: Boolean = loadSplit(), settings: String? = null): LayoutEntry {
        check(!hasDraft) { "Save the existing draft first" }
        val snapshot = JSONObject(settings ?: loadSettings()).apply {
            if (settings == null) { put(Prefs.splitKey(false), split); put(Prefs.splitKey(true), split) }
        }.toString()
        prefs.write(mapOf(LayoutKeys.DRAFT to draftJson(source.toJson(), snapshot), LayoutKeys.DRAFT_SPLIT to split.toString()))
        return LayoutEntry("draft", "Custom", source.toJson(), split = split, settings = snapshot)
    }

    fun saveDraft(name: String): LayoutEntry {
        val grid = requireNotNull(KeyGrid.fromJson(prefs.get(LayoutKeys.DRAFT)))
        val snapshot = JSONObject(requireNotNull(prefs.get(LayoutKeys.DRAFT))).optJSONObject("settings")?.toString() ?: loadSettings()
        val entry = LayoutEntry("saved:${newId()}", cleanName(name), grid.toJson(), split = prefs.get(LayoutKeys.DRAFT_SPLIT)?.toBooleanStrictOrNull() ?: loadSplit(), settings = snapshot)
        val changes = mutableMapOf<String, String?>(LayoutKeys.SAVED to encode(saved() + entry), LayoutKeys.DRAFT to null, LayoutKeys.DRAFT_SPLIT to null)
        val results = resultsObject()
        results.optJSONArray("draft")?.let {
            results.put(entry.key, it)
            results.remove("draft")
            changes[LayoutKeys.RESULTS] = results.toString()
        }
        if (inUse == "draft") changes[LayoutKeys.SELECTED] = entry.key
        prefs.write(changes)
        return entry
    }

    /** Saves the old draft before making the next one; Cancel never calls this. */
    fun saveAndContinue(name: String, source: KeyGrid, split: Boolean = loadSplit(), settings: String? = null): LayoutEntry {
        saveDraft(name)
        return startDraft(source, split, settings)
    }

    fun rename(key: String, name: String): LayoutEntry {
        if (key == "draft") return saveDraft(name)
        val entries = saved()
        val changed = entries.first { it.key == key }.copy(name = cleanName(name))
        prefs.write(mapOf(LayoutKeys.SAVED to encode(entries.map { if (it.key == key) changed else it })))
        return changed
    }

    fun edit(key: String, grid: KeyGrid) {
        require(grid.isSound())
        if (inUse == key) saveGrid(grid)
        if (key == "draft") prefs.write(mapOf(LayoutKeys.DRAFT to draftJson(grid.toJson(),
            if (inUse == key) loadSettings() else entries().firstOrNull { it.key == key }?.settings)))
        else {
            check(saved().any { it.key == key })
            prefs.write(mapOf(LayoutKeys.SAVED to encode(saved().map { if (it.key == key) it.copy(json = grid.toJson(), settings = if (inUse == key) loadSettings() else it.settings) else it })))
        }
    }

    private fun draftJson(grid: String, settings: String?) = JSONObject(grid).apply { settings?.let { put("settings", JSONObject(it)) } }.toString()

    /** Called by Size/Look setters, including the keyboard's quick controls. Trials never alter the owner's snapshot. */
    fun syncSettings() {
        if (prefs.get(LayoutKeys.TRIAL) != null || (inUse != "draft" && !inUse.startsWith("saved:"))) return
        val snapshot = loadSettings()
        if (inUse == "draft" && hasDraft) {
            prefs.write(mapOf(LayoutKeys.DRAFT to draftJson(requireNotNull(prefs.get(LayoutKeys.DRAFT)), snapshot), LayoutKeys.DRAFT_SPLIT to loadSplit().toString()))
        } else if (inUse.startsWith("saved:")) {
            val entries = saved()
            if (entries.any { it.key == inUse }) prefs.write(mapOf(LayoutKeys.SAVED to encode(entries.map {
                if (it.key == inUse) it.copy(settings = snapshot, split = loadSplit()) else it
            })))
        }
    }

    fun use(entry: LayoutEntry) {
        if (entry.custom && entry.settings != null) saveSettings(entry.settings)
        if (!hasSplitSettings(entry)) saveSplit(entry.split)
        saveGrid(entry.grid())
        prefs.write(mapOf(LayoutKeys.SELECTED to entry.key, LayoutKeys.TRIAL to null, LayoutKeys.TRIAL_SPLIT to null, LayoutKeys.TRIAL_SETTINGS to null))
    }

    fun delete(key: String) {
        require(key == "draft" || key.startsWith("saved:"))
        if (inUse == key) use(entries().first { it.key == "preset:STANDARD" })
        val results = resultsObject().apply { remove(key) }
        prefs.write(mapOf(
            (if (key == "draft") LayoutKeys.DRAFT else LayoutKeys.SAVED) to
                (if (key == "draft") null else encode(saved().filterNot { it.key == key })),
            LayoutKeys.RESULTS to results.toString(),
            LayoutKeys.DRAFT_SPLIT to if (key == "draft") null else prefs.get(LayoutKeys.DRAFT_SPLIT),
        ))
    }

    fun beginTrial(entry: LayoutEntry) {
        if (prefs.get(LayoutKeys.TRIAL) != null) restoreTrial()
        if (entry.key == inUse && sameGrid(entry.grid(), loadGrid()) && entry.split == loadSplit() &&
            (entry.settings == null || sameSettings(entry.settings, loadSettings()))) return
        // Presets and legacy entries use a split override; custom settings restore from the durable snapshot.
        prefs.write(mapOf(LayoutKeys.TRIAL to loadGrid().toJson(), LayoutKeys.TRIAL_SPLIT to if (!hasSplitSettings(entry)) entry.split.toString() else null,
            LayoutKeys.TRIAL_SETTINGS to loadSettings()))
        if (entry.custom) entry.settings?.let(saveSettings)
        saveGrid(entry.grid())
    }

    fun restoreTrial(): Boolean {
        val json = prefs.get(LayoutKeys.TRIAL) ?: return false
        prefs.get(LayoutKeys.TRIAL_SETTINGS)?.let(saveSettings)
        KeyGrid.fromJson(json)?.let(saveGrid)
        prefs.write(mapOf(LayoutKeys.TRIAL to null, LayoutKeys.TRIAL_SPLIT to null, LayoutKeys.TRIAL_SETTINGS to null))
        return true
    }

    private fun hasSplitSettings(entry: LayoutEntry) = entry.custom && entry.settings?.let {
        val o = JSONObject(it); o.opt("split_p") is Boolean && o.opt("split_l") is Boolean
    } == true

    private fun resultsObject() = runCatching { JSONObject(prefs.get(LayoutKeys.RESULTS) ?: "{}") }.getOrElse { JSONObject() }
    fun results(key: String): List<TypingResult> = runCatching {
        val array = resultsObject().optJSONArray(key) ?: return emptyList()
        (0 until array.length()).map { i -> array.getJSONObject(i).let {
            TypingResult(it.getDouble("wpm"), it.getDouble("accuracy"), it.getLong("at"))
        } }.filter { it.wpm.isFinite() && it.wpm >= 0 && it.accuracy in 0.0..1.0 }.takeLast(20)
    }.getOrDefault(emptyList())

    fun record(key: String, result: TypingResult) {
        require(result.wpm.isFinite() && result.wpm >= 0 && result.accuracy in 0.0..1.0)
        val array = JSONArray()
        (results(key) + result).takeLast(20).forEach {
            array.put(JSONObject().put("wpm", it.wpm).put("accuracy", it.accuracy).put("at", it.at))
        }
        prefs.write(mapOf(LayoutKeys.RESULTS to resultsObject().put(key, array).toString()))
    }

    companion object {
        fun sameSettings(a: String, b: String): Boolean {
            val left = JSONObject(a); val right = JSONObject(b)
            return left.length() == right.length() && left.keys().asSequence().all { right.has(it) && left.opt(it) == right.opt(it) }
        }
        fun cleanName(name: String) = name.trim().take(24).ifEmpty { "Custom" }
        fun sameGrid(a: KeyGrid, b: KeyGrid): Boolean = a.rows == b.rows && a.items.size == b.items.size &&
            a.items.sortedWith(compareBy({ it.r }, { it.c })).zip(b.items.sortedWith(compareBy({ it.r }, { it.c }))).all { (x, y) ->
                x.r == y.r && x.c == y.c && x.w == y.w && x.keys == y.keys
            }
    }
}

internal sealed interface ClipboardLayoutResult {
    data object Empty : ClipboardLayoutResult
    data object Invalid : ClipboardLayoutResult
    data class Added(val entry: LayoutEntry) : ClipboardLayoutResult
}
