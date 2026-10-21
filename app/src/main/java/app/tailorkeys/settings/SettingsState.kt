package app.tailorkeys.settings

import app.tailorkeys.LayoutEntry
import app.tailorkeys.LayoutLibrary
import app.tailorkeys.LayoutKeys

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import app.tailorkeys.Chords
import app.tailorkeys.DictionaryWords
import app.tailorkeys.Align
import app.tailorkeys.BoolPref
import app.tailorkeys.IntPref
import app.tailorkeys.KeyboardSettings
import app.tailorkeys.KeyboardView
import app.tailorkeys.Prefs
import app.tailorkeys.SizeProfile
import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template

/** Bridges preference notifications and the existing, undoable keyboard editor into Compose state. */
@Stable
internal class SettingsState(context: Context, val settings: KeyboardSettings) {
    private val appContext = context.applicationContext
    private val chordLibraries = HashMap<String, Chords>()
    private val templateMatcher = TemplateMatcher()
    private val revisions = PreferenceRevisions()
    val library = LayoutLibrary(settings) { appContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }.also { it.migrate() }
    var selectedKey by mutableStateOf(library.inUse)
        private set
    var importing by mutableStateOf(false)
    var importPreviewValid by mutableStateOf(false)
    private var previewingSelected = true
    val thumbnails = LayoutThumbnails(context, settings, revisions)

    fun layoutEntries(): List<LayoutEntry> {
        revisions.read(LayoutKeys.SAVED, LayoutKeys.DRAFT, LayoutKeys.DRAFT_SPLIT, Prefs.CURRENT_LANGUAGE, Prefs.NUMBER_ROW.key)
        return library.entries()
    }
    fun inUseKey(): String { revisions.read(LayoutKeys.SELECTED); return library.inUse }
    fun selectedLayout(): LayoutEntry = layoutEntries().firstOrNull { it.key == selectedKey } ?: library.entries().first()
    fun selectLayout(entry: LayoutEntry) {
        selectedKey = entry.key
        previewingSelected = true
        importing = false
        preview.previewSplit = entry.split
        preview.grid = entry.grid()
    }
    fun useLayout(entry: LayoutEntry) {
        library.use(entry)
        selectLayout(entry)
    }
    fun showSelected() { previewingSelected = true; preview.previewSplit = selectedLayout().split; preview.grid = selectedLayout().grid() }
    fun showApplied() { previewingSelected = false; preview.previewSplit = null; preview.grid = settings.loadGrid() }
    fun chordLanguage(): String { revisions.read(Prefs.CURRENT_LANGUAGE, Prefs.LANGUAGES); return settings.currentLanguage() }
    fun chordLetters(): Set<Char> {
        revisions.read(Prefs.LAYOUT, Prefs.CURRENT_LANGUAGE, Prefs.LANGUAGES)
        return preview.grid.items.filter { !it.isStack }.mapNotNull { it.keys[0].singleOrNull()?.takeIf(Char::isLetter)?.lowercaseChar() }.toSet()
    }
    /** Called on a worker thread by the list dialog. */
    @Synchronized fun chordList(language: String, letters: Set<Char>): List<Chords.Chord> =
        chordLibraries.getOrPut(language) {
            Chords(DictionaryWords.read({ appContext.assets.open(it) }, language, 20_000, unique = true))
        }.forLetters(letters)

    val preview = KeyboardView(context, settings).apply {
        previewSplit = selectedLayout().split
        grid = selectedLayout().grid()
        state.language = settings.currentLanguage()
        setSuggestions(listOf("helpful", "helicopter", "helmet"), listOf("hello", "help", "held"), "hel")
        listener = object : KeyboardView.Listener {
            override fun onKey(code: String) = Unit
            override fun onLongPressText(text: String) = Unit
            override fun onStackFace(code: String) = Unit
            override fun onSuggestion(text: String) = Unit
            override fun onPickIme() = Unit
            override fun onEdited() {
                val entry = library.entries().firstOrNull { it.key == if (previewingSelected) selectedKey else library.inUse }
                if (entry?.custom == true) library.edit(entry.key, grid) else settings.saveGrid(grid)
            }
        }
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == Prefs.CURRENT_LANGUAGE || key == Prefs.LANGUAGES || key == Prefs.NUMBER_ROW.key) {
            if (!importing && !preview.editMode) {
                val selected = if (previewingSelected) library.entries().firstOrNull { it.key == selectedKey }?.grid() ?: settings.loadGrid() else settings.loadGrid()
                if (selected.toJson() != preview.grid.toJson()) preview.grid = selected
            }
        }
        preview.state.language = settings.currentLanguage()
        preview.reloadSettings()
        revisions.changed(key)
    }

    fun observe() = settings.prefs.registerOnSharedPreferenceChangeListener(listener)
    fun dispose() = settings.prefs.unregisterOnSharedPreferenceChangeListener(listener)
    fun int(pref: IntPref): Int { revisions.read(pref.key); return settings.int(pref) }
    fun bool(pref: BoolPref): Boolean { revisions.read(pref.key); return settings.bool(pref) }
    fun profile(page: Boolean): SizeProfile {
        revisions.read(Prefs.height(page).key, Prefs.width(page).key, Prefs.alignKey(page),
            Prefs.bottomPadding(page).key, Prefs.splitKey(page), Prefs.splitGap(page).key,
            Prefs.tilt(page).key)
        return settings.profile(page)
    }
    fun cursorControl() = run { revisions.read(Prefs.CURSOR_CONTROL); settings.cursorControl() }
    fun theme() = run { revisions.read(Prefs.THEME); settings.theme() }
    fun set(pref: IntPref, value: Int) = settings.set(pref, value)
    fun set(pref: BoolPref, value: Boolean) = settings.set(pref, value)
    fun setFor(page: Boolean, pref: (Boolean) -> IntPref, value: Int) = settings.setFor(page, pref, value)

    fun languages(): List<String> { revisions.read(Prefs.LANGUAGES); return settings.languages() }
    fun setLanguages(enabled: Set<String>) = settings.setLanguages(enabled)

    fun templateName(): String {
        revisions.read(Prefs.LAYOUT, Prefs.CURRENT_LANGUAGE, Prefs.LANGUAGES, Prefs.NUMBER_ROW.key)
        return templateMatcher.name(preview.grid, settings.currentLanguage(), settings.bool(Prefs.NUMBER_ROW))
    }

    fun applyTemplate(template: Template, landscape: Boolean) {
        val grid = KeyGrid.template(template, settings.currentLanguage())
        if (settings.bool(Prefs.NUMBER_ROW)) grid.setNumberRow(true)
        preview.replaceGrid(grid)
        settings.setSplitFor(landscape, template.split)
    }

    fun setNumberRow(on: Boolean): Boolean {
        // setNumberRow can partly mutate a grid before reporting failure; edit an independent copy.
        val copy = KeyGrid.fromJson(preview.grid.toJson()) ?: return false
        if (!copy.setNumberRow(on)) return false
        preview.replaceGrid(copy)
        return true
    }

    fun reset() {
        preview.editMode = false
        settings.resetAll()
        library.migrate()
        selectedKey = library.inUse
        preview.previewSplit = selectedLayout().split
        preview.grid = settings.loadGrid()
        preview.state.language = settings.currentLanguage()
        preview.reloadSettings()
        revisions.changed(null)
    }
}

internal data class SizePreset(
    val name: String, val height: Int, val width: Int, val align: Align,
    val split: Boolean = false, val gap: Int = 0,
)
internal fun sizePresets(landscape: Boolean) = if (landscape) listOf(
    SizePreset("Small", 49, 85, Align.CENTER), SizePreset("Default", 58, 100, Align.CENTER),
    SizePreset("Large", 69, 100, Align.CENTER), SizePreset("Left hand", 58, 55, Align.LEFT),
    SizePreset("Right hand", 58, 55, Align.RIGHT), SizePreset("Split", 58, 100, Align.CENTER, true, 22),
) else listOf(
    SizePreset("Small", 37, 90, Align.CENTER), SizePreset("Default", 44, 100, Align.CENTER),
    SizePreset("Large", 53, 100, Align.CENTER), SizePreset("Left hand", 46, 78, Align.LEFT),
    SizePreset("Right hand", 46, 78, Align.RIGHT), SizePreset("Split", 44, 100, Align.CENTER, true, 16),
)
