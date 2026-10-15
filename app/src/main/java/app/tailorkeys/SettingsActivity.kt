package app.tailorkeys

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.tailorkeys.layout.KeyGrid
import kotlin.math.roundToInt

/** Setup steps, a live keyboard editor, and every size, split, look and typing option. */
class SettingsActivity : Activity() {

    private lateinit var settings: KeyboardSettings
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var editor: KeyboardView
    private lateinit var editHint: TextView
    /** Re-read each control from the saved settings, so presets and resets show up in the UI. */
    private val refreshers = mutableListOf<() -> Unit>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        refreshers.forEach { it() }
        if (key == null) editor.grid = settings.loadGrid()
        editor.reloadSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = KeyboardSettings(this)
        drawEdgeToEdge()
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = barInsets(insets)
            content.setPadding(dp(20), dp(16) + top, dp(20), dp(24) + bottom)
            insets
        }
        setContentView(scroll)
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        build()
    }

    override fun onDestroy() {
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // The keyboard picker is a dialog, so returning from it doesn't trigger onResume.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus()
    }

    private fun build() {
        content.removeAllViews()
        refreshers.clear()

        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        body("A keyboard you can size, split and arrange to fit your hands.")

        section("Setup")
        status = body("")
        button("1. Turn on TailorKeys") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        button("2. Switch to TailorKeys") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }

        section("Try it")
        content.addView(EditText(this).apply {
            hint = "Tap here to try the keyboard. Every change below applies live."
            minLines = 2
        })

        section("Arrange keys")
        body("Drag any key to any empty cell, including the center column. Drop it on the edge of another key to insert it there and shift the rest over. Drop it on the middle of a key to combine them into one swipe key, or on a spacebar to sit beside it. Tap the x on a swipe key to separate it.")
        editHint = TextView(this).apply { setTextColor(getColor(R.color.accent)); minHeight = dp(22); textSize = 14f }
        content.addView(editHint)
        editor = KeyboardView(this, settings).apply {
            editMode = true
            grid = settings.loadGrid()
            setSuggestions(listOf("helpful", "helicopter", "helmet"), listOf("hello", "help", "held"), "hel")
            listener = object : KeyboardView.Listener {
                override fun onKey(code: String) = Unit
                override fun onLongPressText(text: String) = Unit
                override fun onStackFace(code: String) = Unit
                override fun onSuggestion(text: String) = Unit
                override fun onPickIme() = Unit
                override fun onEdited() = settings.saveGrid(grid)
                override fun onEditHint(text: String?) { editHint.text = text ?: "" }
            }
        }
        content.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        button("Reset layout") {
            val g = KeyGrid.default()
            if (settings.bool(Prefs.NUMBER_ROW)) g.setNumberRow(true)
            settings.saveGrid(g)
            editor.grid = g
        }

        sizeControls(landscape = false)
        sizeControls(landscape = true)

        section("Split layout")
        body("Split puts the halves against the screen edges with a gap in the middle. Tilt leans the halves: positive leans the tops together, negative leans them apart. Ergonomic curve raises the middle fingers' columns and fans the keys.")
        for (land in listOf(false, true)) {
            val name = if (land) "landscape" else "portrait"
            splitToggle("Split in $name", land)
            slider("Gap ($name)", Prefs.splitGap(land)) { "$it%" }
            slider("Tilt ($name)", Prefs.tilt(land)) { (if (it > 0) "+" else "") + "$it°" }
        }
        slider("Ergonomic curve", Prefs.CURVE) { "$it%" }

        section("Look")
        themePicker()
        slider("Space between keys", Prefs.KEY_GAP) { "$it dp" }
        slider("Corner roundness", Prefs.CORNER_RADIUS) { "$it dp" }
        slider("Label size", Prefs.TEXT_SCALE) { "$it%" }
        numberRowToggle()
        toggle("Show long-press hints", Prefs.SHOW_HINTS)

        section("Typing")
        toggle("Capitalize sentences", Prefs.AUTO_CAP)
        toggle("Double space types a period", Prefs.DOUBLE_SPACE_PERIOD)
        toggle("Vibrate on key press", Prefs.HAPTICS)
        slider("Long-press delay", Prefs.LONG_PRESS_MS) { "$it ms" }

        section("")
        button("Reset all settings") {
            settings.resetAll()
            build()
            refreshStatus()
        }
    }

    private fun sizeControls(landscape: Boolean) {
        section(if (landscape) "Size: landscape" else "Size: portrait")
        if (landscape) body("Rotate your phone to see these change live.")
        presetRow(landscape)
        var ratio = 1f
        val height = Prefs.height(landscape)
        val width = Prefs.width(landscape)
        slider("Height", height, { ratio = settings.int(height).toFloat() / settings.int(width) }, { v ->
            if (settings.bool(Prefs.LOCK_SIZE)) settings.set(width, (v / ratio).roundToInt())
        }) { "$it% of screen" }
        slider("Width", width, { ratio = settings.int(height).toFloat() / settings.int(width) }, { v ->
            if (settings.bool(Prefs.LOCK_SIZE)) settings.set(height, (v * ratio).roundToInt())
        }) { "$it%" }
        toggle("Lock height and width together", Prefs.LOCK_SIZE)
        alignPicker(landscape)
        slider("Raise from bottom edge", Prefs.bottomPadding(landscape)) { "$it dp" }
    }

    private class Preset(val name: String, val h: Int, val w: Int, val align: Align, val split: Boolean, val gap: Int = 0)

    private fun presetRow(landscape: Boolean) {
        val presets = if (landscape) listOf(
            Preset("Small", 44, 85, Align.CENTER, false), Preset("Default", 52, 100, Align.CENTER, false),
            Preset("Large", 62, 100, Align.CENTER, false), Preset("Left hand", 52, 55, Align.LEFT, false),
            Preset("Right hand", 52, 55, Align.RIGHT, false), Preset("Split", 52, 100, Align.CENTER, true, 22),
        ) else listOf(
            Preset("Small", 32, 90, Align.CENTER, false), Preset("Default", 38, 100, Align.CENTER, false),
            Preset("Large", 46, 100, Align.CENTER, false), Preset("Left hand", 40, 78, Align.LEFT, false),
            Preset("Right hand", 40, 78, Align.RIGHT, false), Preset("Split", 38, 100, Align.CENTER, true, 16),
        )
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        presets.chunked(3).forEach { chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { p ->
                row.addView(Button(this).apply {
                    text = p.name
                    isAllCaps = false
                    setOnClickListener {
                        val e = settings.prefs.edit()
                            .putInt(Prefs.height(landscape).key, p.h)
                            .putInt(Prefs.width(landscape).key, p.w)
                            .putString(Prefs.alignKey(landscape), p.align.name)
                            .putBoolean(Prefs.splitKey(landscape), p.split)
                        if (p.split) e.putInt(Prefs.splitGap(landscape).key, p.gap).putInt(Prefs.tilt(landscape).key, 0)
                        e.apply()
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            rows.addView(row)
        }
        content.addView(rows)
    }

    private fun alignPicker(landscape: Boolean) {
        body("Position when narrower than the screen")
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val base = if (landscape) 200 else 100 // Unique ids so saved view state can't cross between pickers.
        for (align in Align.entries) {
            group.addView(RadioButton(this).apply { id = base + align.ordinal; text = align.name.lowercase().replaceFirstChar { it.uppercase() } })
        }
        val refresh = { group.check(base + settings.align(landscape).ordinal) }
        refresh()
        refreshers += refresh
        group.setOnCheckedChangeListener { _, id -> Align.entries.getOrNull(id - base)?.let { if (it != settings.align(landscape)) settings.setAlign(landscape, it) } }
        content.addView(group)
    }

    private fun themePicker() {
        body("Theme")
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        for (t in ThemeName.entries) group.addView(RadioButton(this).apply { id = 300 + t.ordinal; text = t.title })
        val refresh = { group.check(300 + settings.theme().ordinal) }
        refresh()
        refreshers += refresh
        group.setOnCheckedChangeListener { _, id -> ThemeName.entries.getOrNull(id - 300)?.let { if (it != settings.theme()) settings.setTheme(it) } }
        content.addView(group)
    }

    private fun splitToggle(label: String, landscape: Boolean) {
        @Suppress("DEPRECATION")
        val sw = Switch(this).apply { text = label; textSize = 16f; setPadding(0, dp(10), 0, dp(10)) }
        val refresh = { sw.isChecked = settings.split(landscape) }
        refresh()
        refreshers += refresh
        sw.setOnCheckedChangeListener { _, checked -> if (checked != settings.split(landscape)) settings.setSplit(landscape, checked) }
        content.addView(sw)
    }

    private fun numberRowToggle() {
        @Suppress("DEPRECATION")
        val sw = Switch(this).apply { text = "Number row"; textSize = 16f; setPadding(0, dp(10), 0, dp(10)) }
        val refresh = { sw.isChecked = settings.bool(Prefs.NUMBER_ROW) }
        refresh()
        refreshers += refresh
        sw.setOnCheckedChangeListener { _, checked ->
            if (checked == settings.bool(Prefs.NUMBER_ROW)) return@setOnCheckedChangeListener
            val g = settings.loadGrid()
            if (!g.setNumberRow(checked)) {
                Toast.makeText(this, "Move your keys out of the number row first", Toast.LENGTH_SHORT).show()
                sw.isChecked = true
                return@setOnCheckedChangeListener
            }
            settings.saveGrid(g)
            settings.set(Prefs.NUMBER_ROW, checked)
            editor.grid = g
        }
        content.addView(sw)
    }

    private fun refreshStatus() {
        val enabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        status.text = when {
            selected -> "✓ TailorKeys is on and selected."
            enabled -> "TailorKeys is turned on. Now do step 2 to switch to it."
            else -> "TailorKeys isn't turned on yet. Do step 1, then step 2.\n\n" +
                "Android will warn that keyboards can collect what you type. TailorKeys has no internet " +
                "permission, so nothing you type can leave your phone."
        }
    }

    // ---- Small view builders ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun section(title: String) {
        content.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 13f
            letterSpacing = 0.08f
            setTextColor(getColor(R.color.accent))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(28), 0, dp(4))
        })
    }

    private fun body(s: String) = TextView(this).apply {
        text = s
        textSize = 15f
        setPadding(0, dp(4), 0, dp(4))
        content.addView(this)
    }

    private fun button(label: String, onClick: () -> Unit) {
        content.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        })
    }

    private fun toggle(label: String, pref: BoolPref) {
        @Suppress("DEPRECATION") // android.widget.Switch avoids pulling in a support library for one widget.
        val sw = Switch(this).apply { text = label; textSize = 16f; setPadding(0, dp(10), 0, dp(10)) }
        val refresh = { sw.isChecked = settings.bool(pref) }
        refresh()
        refreshers += refresh
        sw.setOnCheckedChangeListener { _, checked -> if (checked != settings.bool(pref)) settings.set(pref, checked) }
        content.addView(sw)
    }

    private fun slider(
        label: String,
        pref: IntPref,
        onStart: (() -> Unit)? = null,
        onChange: ((Int) -> Unit)? = null,
        format: (Int) -> String,
    ) {
        val value = TextView(this)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(TextView(this@SettingsActivity).apply { text = label; textSize = 16f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
        }
        val bar = SeekBar(this).apply {
            max = pref.max // Set max before min: setMin clamps to the current max.
            min = pref.min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    settings.set(pref, progress)
                    value.text = format(progress)
                    onChange?.invoke(progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar) { onStart?.invoke() }
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        val refresh = { val v = settings.int(pref); bar.progress = v; value.text = format(v) }
        refresh()
        refreshers += refresh
        content.addView(header)
        content.addView(bar)
    }

    // ---- Window insets ----

    @Suppress("DEPRECATION")
    private fun drawEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.navigationBarColor = Color.TRANSPARENT
    }

    /** Top and bottom space taken by the status bar, navigation bar and keyboard. */
    private fun barInsets(insets: WindowInsets): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= 30) {
        val types = WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
        val i = insets.getInsets(types)
        i.top to i.bottom
    } else {
        @Suppress("DEPRECATION")
        (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
    }
}
