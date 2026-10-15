package app.tailorkeys

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import app.tailorkeys.layout.KeyGrid
import kotlin.math.abs
import kotlin.math.roundToInt

/** Setup, a live keyboard editor, and the size, split, look and typing options. */
class SettingsActivity : Activity() {

    private lateinit var settings: KeyboardSettings
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var editor: KeyboardView
    private lateinit var editHint: TextView
    private lateinit var orientHeader: LinearLayout
    private lateinit var panel: SwipePanel
    /** The orientation the size panel is showing. */
    private var page = false

    /** Each control re-reads its value from settings, so presets and resets show up in the UI. */
    private val refreshers = mutableListOf<() -> Unit>()
    private val panelRefreshers = mutableListOf<() -> Unit>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        refreshers.forEach { it() }
        panelRefreshers.forEach { it() }
        if (key == null) editor.grid = settings.loadGrid()
        editor.reloadSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = KeyboardSettings(this)
        page = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
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
        status = body("")
        content.addView(Button(this).apply {
            text = "Turn on or switch keyboard"
            isAllCaps = false
            setOnClickListener {
                val imm = getSystemService(InputMethodManager::class.java)
                if (imm.enabledInputMethodList.any { it.packageName == packageName }) imm.showInputMethodPicker()
                else startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })
        content.addView(EditText(this).apply {
            hint = "Try the keyboard here. Changes below apply live."
            minLines = 2
        })

        // ---- Arrange keys ----
        section("Arrange keys")
        body("Drag a key to an empty cell, onto another key's edge to insert it, onto its middle to combine them into a swipe key, or onto a spacebar to sit beside it. Tap the x on a swipe key to separate it.")
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
        content.addView(linkButton("Reset layout") {
            val g = KeyGrid.default()
            if (settings.bool(Prefs.NUMBER_ROW)) g.setNumberRow(true)
            settings.saveGrid(g)
            editor.grid = g
        })

        // ---- Size and split, one orientation at a time ----
        section("Size")
        content.addView(switchRow("Same for portrait and landscape", { settings.bool(Prefs.SAME_ORIENTATIONS) }, { on ->
            if (on) settings.linkOrientations(from = page) else settings.set(Prefs.SAME_ORIENTATIONS, false)
            updateOrientHeader()
        }, refreshers))
        orientHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) }
        content.addView(orientHeader)
        panel = SwipePanel(this) { forward -> if (!settings.bool(Prefs.SAME_ORIENTATIONS) && forward != page) flipTo(forward) }
        content.addView(panel)
        updateOrientHeader()
        buildPanel()

        // ---- Look ----
        section("Look")
        content.addView(dropdown("Theme", ThemeName.entries.map { it.title }, { settings.theme().ordinal }, { settings.setTheme(ThemeName.entries[it]) }, refreshers))
        slider(content, "Space between keys", Prefs.KEY_GAP, refreshers) { "$it dp" }
        slider(content, "Corner roundness", Prefs.CORNER_RADIUS, refreshers) { "$it dp" }
        slider(content, "Label size", Prefs.TEXT_SCALE, refreshers) { "$it%" }
        numberRowSwitch()
        content.addView(switchRow("Long-press hints", { settings.bool(Prefs.SHOW_HINTS) }, { settings.set(Prefs.SHOW_HINTS, it) }, refreshers))

        // ---- Typing ----
        section("Typing")
        content.addView(switchRow("Capitalize sentences", { settings.bool(Prefs.AUTO_CAP) }, { settings.set(Prefs.AUTO_CAP, it) }, refreshers))
        content.addView(switchRow("Double space types a period", { settings.bool(Prefs.DOUBLE_SPACE_PERIOD) }, { settings.set(Prefs.DOUBLE_SPACE_PERIOD, it) }, refreshers))
        content.addView(switchRow("Vibrate on key press", { settings.bool(Prefs.HAPTICS) }, { settings.set(Prefs.HAPTICS, it) }, refreshers))
        slider(content, "Long-press delay", Prefs.LONG_PRESS_MS, refreshers) { "$it ms" }

        content.addView(linkButton("Reset all settings") {
            settings.resetAll()
            build()
            refreshStatus()
        })
    }

    // ---- Orientation pages ----

    private fun updateOrientHeader() {
        orientHeader.removeAllViews()
        if (settings.bool(Prefs.SAME_ORIENTATIONS)) {
            orientHeader.addView(TextView(this).apply { text = "Portrait and landscape"; textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
            return
        }
        for (land in listOf(false, true)) {
            val active = land == page
            orientHeader.addView(TextView(this).apply {
                text = if (land) "Landscape" else "Portrait"
                textSize = 15f
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setTypeface(typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (active) getColor(R.color.accent) else currentTextColor)
                alpha = if (active) 1f else 0.6f
                setOnClickListener { if (land != page) flipTo(land) }
            })
        }
        orientHeader.addView(TextView(this).apply { text = "swipe to switch"; textSize = 12f; alpha = 0.5f })
    }

    private fun flipTo(landscape: Boolean) {
        val dir = if (landscape) -1 else 1
        val w = panel.width.toFloat().coerceAtLeast(1f)
        panel.animate().translationX(dir * w).alpha(0f).setDuration(140).withEndAction {
            page = landscape
            updateOrientHeader()
            buildPanel()
            panel.translationX = -dir * w
            panel.animate().translationX(0f).alpha(1f).setDuration(160).start()
        }.start()
    }

    private fun buildPanel() {
        panel.removeAllViews()
        panelRefreshers.clear()
        val land = page
        val list = panelRefreshers
        if (!settings.bool(Prefs.SAME_ORIENTATIONS) && land) panel.addView(body("Rotate your phone to see these change live.", attach = false))

        panel.addView(presetDropdown(land))
        var ratio = 1f
        val height = Prefs.height(land)
        val width = Prefs.width(land)
        slider(panel, "Height", height, list,
            set = { v -> settings.setFor(land, Prefs::height, v); if (settings.bool(Prefs.LOCK_SIZE)) settings.setFor(land, Prefs::width, (v / ratio).roundToInt()) },
            onStart = { ratio = settings.int(height).toFloat() / settings.int(width) }) { "$it% of screen" }

        // Width carries the lock and, when narrower than the screen, the position.
        val lock = CheckBox(this).apply { text = "Lock"; textSize = 14f }
        val lockRefresh = { lock.isChecked = settings.bool(Prefs.LOCK_SIZE) }
        lockRefresh(); list += lockRefresh
        lock.setOnCheckedChangeListener { _, c -> if (c != settings.bool(Prefs.LOCK_SIZE)) settings.set(Prefs.LOCK_SIZE, c) }
        slider(panel, "Width", width, list,
            set = { v -> settings.setFor(land, Prefs::width, v); if (settings.bool(Prefs.LOCK_SIZE)) settings.setFor(land, Prefs::height, (v * ratio).roundToInt()) },
            onStart = { ratio = settings.int(height).toFloat() / settings.int(width) }, extra = lock) { "$it%" }
        val align = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        Align.entries.forEach { a -> align.addView(RadioButton(this).apply { id = 100 + a.ordinal; text = a.name.lowercase().replaceFirstChar { it.uppercase() } }) }
        val alignRefresh = {
            align.check(100 + settings.align(land).ordinal)
            align.visibility = if (settings.int(width) < 100) View.VISIBLE else View.GONE
        }
        alignRefresh(); list += alignRefresh
        align.setOnCheckedChangeListener { _, id -> Align.entries.getOrNull(id - 100)?.let { if (it != settings.align(land)) settings.setAlignFor(land, it) } }
        panel.addView(align)

        slider(panel, "Raise from bottom edge", Prefs.bottomPadding(land), list, set = { settings.setFor(land, Prefs::bottomPadding, it) }) { "$it dp" }

        // Split: the switch lives in the header, its settings only show while it's on.
        val splitBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val splitSwitch = switchRow("Split", { settings.split(land) }, { settings.setSplitFor(land, it) }, list)
        panel.addView(splitSwitch)
        slider(splitBody, "Gap", Prefs.splitGap(land), list, set = { settings.setFor(land, Prefs::splitGap, it) }) { "$it%" }
        slider(splitBody, "Tilt", Prefs.tilt(land), list, set = { settings.setFor(land, Prefs::tilt, it) }) { (if (it > 0) "+" else "") + "$it°" }
        slider(splitBody, "Ergonomic curve", Prefs.CURVE, list) { "$it%" }
        val splitRefresh = { splitBody.visibility = if (settings.split(land)) View.VISIBLE else View.GONE }
        splitRefresh(); list += splitRefresh
        panel.addView(splitBody)
    }

    private class Preset(val name: String, val h: Int, val w: Int, val align: Align, val split: Boolean, val gap: Int = 0)

    private fun presets(landscape: Boolean) = if (landscape) listOf(
        Preset("Small", 44, 85, Align.CENTER, false), Preset("Default", 52, 100, Align.CENTER, false),
        Preset("Large", 62, 100, Align.CENTER, false), Preset("Left hand", 52, 55, Align.LEFT, false),
        Preset("Right hand", 52, 55, Align.RIGHT, false), Preset("Split", 52, 100, Align.CENTER, true, 22),
    ) else listOf(
        Preset("Small", 32, 90, Align.CENTER, false), Preset("Default", 38, 100, Align.CENTER, false),
        Preset("Large", 46, 100, Align.CENTER, false), Preset("Left hand", 40, 78, Align.LEFT, false),
        Preset("Right hand", 40, 78, Align.RIGHT, false), Preset("Split", 38, 100, Align.CENTER, true, 16),
    )

    private fun presetDropdown(land: Boolean): View {
        val list = presets(land)
        val names = listOf("Custom") + list.map { it.name }
        fun current(): Int {
            val p = settings.profile(land)
            val i = list.indexOfFirst { it.h == p.heightPercent && it.w == p.widthPercent && it.align == p.align && it.split == p.split }
            return i + 1
        }
        return dropdown("Preset", names, ::current, { i ->
            val p = list.getOrNull(i - 1) ?: return@dropdown
            settings.setFor(land, Prefs::height, p.h)
            settings.setFor(land, Prefs::width, p.w)
            settings.setAlignFor(land, p.align)
            settings.setSplitFor(land, p.split)
            if (p.split) { settings.setFor(land, Prefs::splitGap, p.gap); settings.setFor(land, Prefs::tilt, 0) }
        }, panelRefreshers)
    }

    private fun numberRowSwitch() {
        content.addView(switchRow("Number row", { settings.bool(Prefs.NUMBER_ROW) }, { on ->
            val g = settings.loadGrid()
            if (!g.setNumberRow(on)) {
                Toast.makeText(this, "Move your keys out of the number row first", Toast.LENGTH_SHORT).show()
                refreshers.forEach { it() }
                return@switchRow
            }
            settings.saveGrid(g)
            settings.set(Prefs.NUMBER_ROW, on)
            editor.grid = g
        }, refreshers))
    }

    private fun refreshStatus() {
        val enabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        status.text = when {
            selected -> "✓ TailorKeys is on and selected."
            enabled -> "TailorKeys is turned on. Tap the button to switch to it."
            else -> "TailorKeys isn't turned on yet. Tap the button, turn it on, then come back and switch to it. " +
                "Android warns that keyboards can collect what you type. TailorKeys has no internet permission, so nothing you type can leave your phone."
        }
    }

    // ---- View builders ----

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

    private fun body(s: String, attach: Boolean = true) = TextView(this).apply {
        text = s
        textSize = 15f
        setPadding(0, dp(4), 0, dp(4))
        if (attach) content.addView(this)
    }

    private fun linkButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(getColor(R.color.accent))
        setPadding(0, dp(12), 0, dp(12))
        setOnClickListener { onClick() }
    }

    private fun switchRow(label: String, get: () -> Boolean, set: (Boolean) -> Unit, list: MutableList<() -> Unit>): View {
        @Suppress("DEPRECATION") // android.widget.Switch avoids pulling in a support library for one widget.
        val sw = Switch(this).apply { text = label; textSize = 16f; setPadding(0, dp(10), 0, dp(10)) }
        val refresh = { sw.isChecked = get() }
        refresh()
        list += refresh
        sw.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean -> if (checked != get()) set(checked) }
        return sw
    }

    private fun dropdown(label: String, items: List<String>, get: () -> Int, set: (Int) -> Unit, list: MutableList<() -> Unit>): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
        row.addView(TextView(this).apply { text = label; textSize = 16f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        var updating = false
        val refresh = { updating = true; spinner.setSelection(get(), false); updating = false }
        refresh()
        list += refresh
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (!updating && position != get()) set(position) }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        row.addView(spinner)
        return row
    }

    private fun slider(
        parent: LinearLayout,
        label: String,
        pref: IntPref,
        list: MutableList<() -> Unit>,
        set: (Int) -> Unit = { settings.set(pref, it) },
        onStart: (() -> Unit)? = null,
        extra: View? = null,
        format: (Int) -> String,
    ) {
        val value = TextView(this).apply { textSize = 14f; setPadding(dp(8), 0, 0, 0) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(TextView(this@SettingsActivity).apply { text = label; textSize = 16f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            extra?.let { addView(it) }
            addView(value)
        }
        val bar = SeekBar(this).apply {
            max = pref.max // Set max before min: setMin clamps to the current max.
            min = pref.min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    set(progress)
                    value.text = format(progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar) { onStart?.invoke() }
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        val refresh = { val v = settings.int(pref); bar.progress = v; value.text = format(v) }
        refresh()
        list += refresh
        parent.addView(header)
        parent.addView(bar)
    }

    /**
     * Vertical container that turns a sideways swipe into a page change. Swipes that start on a slider are
     * left to the slider.
     */
    private class SwipePanel(context: Context, val onSwipe: (forward: Boolean) -> Unit) : LinearLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var x0 = 0f
        private var y0 = 0f
        private var onSlider = false

        init { orientation = VERTICAL }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; onSlider = touchesSlider(this, e.rawX.toInt(), e.rawY.toInt()) }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0
                    if (!onSlider && abs(dx) > slop * 2 && abs(dx) > abs(e.rawY - y0) * 1.5f) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val dx = e.rawX - x0
                if (abs(dx) > slop * 4) onSwipe(dx < 0)
            }
            return true
        }

        private fun touchesSlider(v: View, x: Int, y: Int): Boolean {
            if (v is SeekBar) { val r = Rect(); return v.getGlobalVisibleRect(r) && r.contains(x, y) }
            if (v is ViewGroup) for (i in 0 until v.childCount) if (touchesSlider(v.getChildAt(i), x, y)) return true
            return false
        }
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
