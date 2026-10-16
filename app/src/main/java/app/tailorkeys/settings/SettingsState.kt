package app.tailorkeys.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.tailorkeys.Align
import app.tailorkeys.BoolPref
import app.tailorkeys.IntPref
import app.tailorkeys.KeyboardSettings
import app.tailorkeys.KeyboardView
import app.tailorkeys.Prefs
import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import org.json.JSONObject

/** Bridges preference notifications and the existing, undoable keyboard editor into Compose state. */
@Stable
internal class SettingsState(context: Context, val settings: KeyboardSettings) {
    private var revision by mutableIntStateOf(0)
    val preview = KeyboardView(context, settings).apply {
        grid = settings.loadGrid()
        state.language = settings.currentLanguage()
        setSuggestions(listOf("helpful", "helicopter", "helmet"), listOf("hello", "help", "held"), "hel")
        listener = object : KeyboardView.Listener {
            override fun onKey(code: String) = Unit
            override fun onLongPressText(text: String) = Unit
            override fun onStackFace(code: String) = Unit
            override fun onSuggestion(text: String) = Unit
            override fun onPickIme() = Unit
            override fun onEdited() = settings.saveGrid(grid)
        }
    }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == Prefs.LAYOUT || key == Prefs.CURRENT_LANGUAGE || key == Prefs.LANGUAGES) {
            val saved = settings.loadGrid()
            if (saved.toJson() != preview.grid.toJson()) preview.grid = saved
        }
        preview.state.language = settings.currentLanguage()
        preview.reloadSettings()
        revision++
    }

    fun observe() = settings.prefs.registerOnSharedPreferenceChangeListener(listener)
    fun dispose() = settings.prefs.unregisterOnSharedPreferenceChangeListener(listener)
    fun int(pref: IntPref): Int { revision; return settings.int(pref) }
    fun bool(pref: BoolPref): Boolean { revision; return settings.bool(pref) }
    fun profile(page: Boolean) = run { revision; settings.profile(page) }
    fun theme() = run { revision; settings.theme() }
    fun set(pref: IntPref, value: Int) = settings.set(pref, value)
    fun set(pref: BoolPref, value: Boolean) = settings.set(pref, value)
    fun setFor(page: Boolean, pref: (Boolean) -> IntPref, value: Int) = settings.setFor(page, pref, value)

    fun languages(): List<String> { revision; return settings.languages() }
    fun setLanguages(enabled: Set<String>) = settings.setLanguages(enabled)

    fun templateName(): String {
        revision
        val current = withoutSwipeIndex(preview.grid)
        return Template.entries.firstOrNull { template ->
            val candidate = KeyGrid.template(template, settings.currentLanguage())
            if (settings.bool(Prefs.NUMBER_ROW)) candidate.setNumberRow(true)
            withoutSwipeIndex(candidate) == current
        }?.title ?: "Custom"
    }

    fun applyTemplate(template: Template, landscape: Boolean) {
        val grid = KeyGrid.template(template, settings.currentLanguage())
        if (settings.bool(Prefs.NUMBER_ROW)) grid.setNumberRow(true)
        preview.replaceGrid(grid)
        if (template.split) settings.setSplitFor(landscape, true)
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
        preview.grid = settings.loadGrid()
        preview.state.language = settings.currentLanguage()
        preview.reloadSettings()
        revision++
    }
}

private fun withoutSwipeIndex(grid: KeyGrid): String {
    val json = JSONObject(grid.toJson())
    val items = json.getJSONArray("items")
    for (i in 0 until items.length()) items.getJSONObject(i).remove("i")
    return json.toString()
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
