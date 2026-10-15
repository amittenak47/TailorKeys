package app.tailorkeys

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
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
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One keyboard preview stays pinned at the top and updates live. Under it, tabs (Layout, Size, Look, Typing)
 * each show their own settings. Setup lives in a small collapsible card above the preview.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: KeyboardSettings
    private lateinit var preview: KeyboardView
    private lateinit var previewHost: View
    private lateinit var tabsView: View
    private lateinit var status: TextView
    private lateinit var setupToggle: TextView
    private lateinit var setupPanel: View
    private lateinit var scroll: ScrollView
    private lateinit var tabs: Segmented
    private lateinit var editButton: TextView
    private lateinit var orientHeader: LinearLayout
    private lateinit var panel: SwipePanel
    private var pages = emptyList<View>()

    private var tab = 0
    private var setupOpen = false
    /** The orientation the size panel is showing. */
    private var page = false

    /** Each control re-reads its value from settings, so presets and resets show up in the UI. */
    private val refreshers = mutableListOf<() -> Unit>()
    private val panelRefreshers = mutableListOf<() -> Unit>()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        refreshers.forEach { it() }
        panelRefreshers.forEach { it() }
        // Layouts changed elsewhere (the keyboard's own editor, a reset) show up here; our own saves already are.
        if (key == null || key == Prefs.LAYOUT) settings.loadGrid().let { if (it.toJson() != preview.grid.toJson()) preview.grid = it }
        preview.reloadSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = KeyboardSettings(this)
        if (!settings.bool(Prefs.ONBOARDED)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        page = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        tab = savedInstanceState?.getInt(STATE_TAB, 0)?.coerceIn(0, TAB_NAMES.lastIndex) ?: 0
        setupOpen = savedInstanceState?.getBoolean(STATE_SETUP, false) ?: false
        drawEdgeToEdge()
        setContentView(buildUi())
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        if (savedInstanceState?.getBoolean(STATE_EDITING, false) == true) setEditing(true)
        selectTab(tab)
        applySetupOpen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
        outState.putBoolean(STATE_SETUP, setupOpen)
        outState.putBoolean(STATE_EDITING, ::preview.isInitialized && preview.editMode)
    }

    override fun onDestroy() {
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    // The keyboard picker is a dialog, so returning from it doesn't trigger onResume.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::status.isInitialized) refreshStatus()
    }

    // ---- Back: leave edit mode first, then return to the first tab ----

    private var backCallback: Any? = null
    private var backRegistered = false

    private fun handleBack(): Boolean {
        if (preview.editMode) { setEditing(false); return true }
        if (tab != 0) { selectTab(0); return true }
        return false
    }

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        val needed = tab != 0 || preview.editMode
        val cb = (backCallback ?: OnBackInvokedCallback { handleBack() }.also { backCallback = it }) as OnBackInvokedCallback
        if (needed && !backRegistered) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backRegistered = true
        } else if (!needed && backRegistered) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
            backRegistered = false
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (!handleBack()) super.onBackPressed()
    }

    // ---- Screen structure ----

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        refreshers.clear()

        preview = KeyboardView(this, settings).apply {
            grid = settings.loadGrid()
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
        val previewBox = FrameLayout(this).apply {
            addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (landscape) { background = rounded(0, 16f); clipToOutline = true }
        }

        val header = buildHeader()
        setupPanel = buildSetupPanel()
        tabs = Segmented(this, TAB_NAMES, tab) { selectTab(it) }
        tabsView = tabs.view
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
        buildPages()

        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val tabParams = { LinearLayout.LayoutParams(match, wrap).apply { setMargins(dp(16), dp(12), dp(16), dp(4)) } }
        val root = LinearLayout(this)
        if (landscape) {
            root.orientation = LinearLayout.HORIZONTAL
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(8), dp(16)) }
            left.addView(previewBox, LinearLayout.LayoutParams(match, wrap))
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            right.addView(header)
            right.addView(setupPanel, LinearLayout.LayoutParams(match, wrap).apply { setMargins(dp(8), 0, dp(16), dp(4)) })
            right.addView(tabsView, tabParams().apply { leftMargin = dp(8) })
            right.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
            root.addView(left, LinearLayout.LayoutParams(0, match, 1f))
            root.addView(right, LinearLayout.LayoutParams(0, match, 1f))
            previewHost = left
        } else {
            root.orientation = LinearLayout.VERTICAL
            root.addView(header)
            root.addView(setupPanel, LinearLayout.LayoutParams(match, wrap).apply { setMargins(dp(16), 0, dp(16), dp(10)) })
            root.addView(previewBox, LinearLayout.LayoutParams(match, wrap))
            root.addView(tabsView, tabParams())
            root.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))
            previewHost = previewBox
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = barInsets(insets)
            root.setPadding(bars.left, bars.top, bars.right, 0)
            scroll.setPadding(dp(16), dp(8), dp(16), dp(24) + bars.bottom)
            // While the system keyboard is up (typing in the try-it field) the preview and settings make room for it.
            val typing = Build.VERSION.SDK_INT >= 30 && insets.isVisible(WindowInsets.Type.ime())
            val v = if (typing) View.GONE else View.VISIBLE
            previewHost.visibility = v
            tabsView.visibility = v
            scroll.visibility = v
            insets
        }
        return root
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(12), dp(16), dp(12)) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(getColor(R.color.ink))
        })
        status = TextView(this).apply { textSize = 13f; setTextColor(getColor(R.color.ink2)) }
        titles.addView(status)
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setupToggle = TextView(this).apply {
            textSize = 14f
            typeface = mediumFont
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.accent))
            setPadding(dp(14), 0, dp(14), 0)
            minHeight = dp(36)
            background = rippled(rounded(getColor(R.color.track), 18f))
            setOnClickListener { setupOpen = !setupOpen; applySetupOpen() }
        }
        row.addView(setupToggle)
        return row
    }

    private fun applySetupOpen() {
        setupPanel.visibility = if (setupOpen) View.VISIBLE else View.GONE
        setupToggle.text = if (setupOpen) "Setup  ▴" else "Setup  ▾"
    }

    private fun buildSetupPanel(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(10)) }
        col.background = rounded(getColor(R.color.surface), 16f)
        col.addView(pillButton("Turn on or switch keyboard") {
            val imm = getSystemService(InputMethodManager::class.java)
            if (imm.enabledInputMethodList.any { it.packageName == packageName }) imm.showInputMethodPicker()
            else startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        col.addView(TextView(this).apply {
            text = "Show the guide"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.accent))
            minHeight = dp(48)
            setOnClickListener { startActivity(Intent(this@SettingsActivity, OnboardingActivity::class.java)) }
        })
        col.addView(EditText(this).apply {
            hint = "Try the keyboard here"
            minLines = 2
            gravity = Gravity.TOP
            textSize = 16f
            setTextColor(getColor(R.color.ink))
            setHintTextColor(getColor(R.color.ink2))
            background = rounded(getColor(R.color.bg), 12f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        })
        // Make room for the keyboard when this field is focused, whatever the window mode.
        return col
    }

    private fun selectTab(i: Int) {
        tab = i
        tabs.select(i)
        scroll.removeAllViews()
        scroll.addView(pages[i], ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll.scrollTo(0, 0)
        updateBack()
    }

    private fun setEditing(on: Boolean) {
        preview.editMode = on
        if (::editButton.isInitialized) {
            editButton.text = if (on) "Done" else "Edit layout"
            editButton.background = rippled(rounded(getColor(if (on) R.color.track else R.color.accent), 14f))
            editButton.setTextColor(getColor(if (on) R.color.ink else R.color.on_accent))
        }
        updateBack()
    }

    private fun buildPages() {
        pages = listOf(layoutPage(), sizePage(), lookPage(), typingPage())
    }

    private fun layoutPage(): View {
        editButton = pillButton("Edit layout") { setEditing(!preview.editMode) }
        return pageOf(
            editButton,
            card(
                actionRow("Start from…") { pickTemplate() },
                actionRow("Share") { shareLayout() },
                actionRow("Import") { importLayout() },
            ),
        )
    }

    private fun sizePage(): View {
        orientHeader = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        panel = SwipePanel(this) { forward -> if (!settings.bool(Prefs.SAME_ORIENTATIONS) && forward != page) flipTo(forward) }
        val same = card(switchRow("Same for portrait and landscape", { settings.bool(Prefs.SAME_ORIENTATIONS) }, { on ->
            if (on) settings.linkOrientations(from = page) else settings.set(Prefs.SAME_ORIENTATIONS, false)
            updateOrientHeader()
        }, refreshers))
        val out = pageOf(same, orientHeader, panel)
        updateOrientHeader()
        buildPanel()
        return out
    }

    private fun lookPage(): View = pageOf(
        card(dropdownRow("Theme", ThemeName.entries.map { it.title }, { settings.theme().ordinal }, { settings.setTheme(ThemeName.entries[it]) }, refreshers)),
        card(
            slider("Space between keys", Prefs.KEY_GAP, refreshers) { "$it dp" },
            slider("Corner roundness", Prefs.CORNER_RADIUS, refreshers) { "$it dp" },
            slider("Label size", Prefs.TEXT_SCALE, refreshers) { "$it%" },
        ),
        card(
            numberRowSwitch(),
            switchRow("Long-press hints", { settings.bool(Prefs.SHOW_HINTS) }, { settings.set(Prefs.SHOW_HINTS, it) }, refreshers),
        ),
    )

    private fun typingPage(): View = pageOf(
        card(
            switchRow("Capitalize sentences", { settings.bool(Prefs.AUTO_CAP) }, { settings.set(Prefs.AUTO_CAP, it) }, refreshers),
            switchRow("Double space types a period", { settings.bool(Prefs.DOUBLE_SPACE_PERIOD) }, { settings.set(Prefs.DOUBLE_SPACE_PERIOD, it) }, refreshers),
            switchRow("Vibrate on key press", { settings.bool(Prefs.HAPTICS) }, { settings.set(Prefs.HAPTICS, it) }, refreshers),
        ),
        card(slider("Long-press delay", Prefs.LONG_PRESS_MS, refreshers) { "$it ms" }),
        card(actionRow("Reset all settings", danger = true, chevron = false) {
            settings.resetAll()
            refreshers.clear()
            panelRefreshers.clear()
            setEditing(false)
            preview.grid = settings.loadGrid()
            preview.reloadSettings()
            buildPages()
            selectTab(tab)
            refreshStatus()
        }),
    )

    // ---- Orientation pages ----

    private fun updateOrientHeader() {
        orientHeader.removeAllViews()
        val pad = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (settings.bool(Prefs.SAME_ORIENTATIONS)) {
            orientHeader.addView(caption("Portrait and landscape"))
            return
        }
        val seg = Segmented(this, listOf("Portrait", "Landscape"), if (page) 1 else 0) { i -> if ((i == 1) != page) flipTo(i == 1) }
        orientHeader.addView(seg.view, pad)
        orientHeader.addView(caption("swipe to switch").apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun caption(s: String) = TextView(this).apply { text = s; textSize = 12f; setTextColor(getColor(R.color.ink2)); gravity = Gravity.CENTER }

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
        val gap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
        if (!settings.bool(Prefs.SAME_ORIENTATIONS) && land != (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)) {
            panel.addView(caption("Rotate your phone to see these change live.").apply { setPadding(0, dp(10), 0, 0) })
        }

        var ratio = 1f
        val height = Prefs.height(land)
        val width = Prefs.width(land)
        val heightRow = slider("Height", height, list,
            set = { v -> settings.setFor(land, Prefs::height, v); if (settings.bool(Prefs.LOCK_SIZE)) settings.setFor(land, Prefs::width, (v / ratio).roundToInt()) },
            onStart = { ratio = settings.int(height).toFloat() / settings.int(width) }) { "$it% of screen" }

        // Width carries the lock and, when narrower than the screen, the position.
        val lock = CheckBox(this).apply { text = "Lock"; textSize = 14f; setTextColor(getColor(R.color.ink2)) }
        val lockRefresh = { lock.isChecked = settings.bool(Prefs.LOCK_SIZE) }
        lockRefresh(); list += lockRefresh
        lock.setOnCheckedChangeListener { _, c -> if (c != settings.bool(Prefs.LOCK_SIZE)) settings.set(Prefs.LOCK_SIZE, c) }
        val widthRow = slider("Width", width, list,
            set = { v -> settings.setFor(land, Prefs::width, v); if (settings.bool(Prefs.LOCK_SIZE)) settings.setFor(land, Prefs::height, (v * ratio).roundToInt()) },
            onStart = { ratio = settings.int(height).toFloat() / settings.int(width) }, extra = lock) { "$it%" }

        val alignSeg = Segmented(this, Align.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }, settings.align(land).ordinal) { i ->
            Align.entries.getOrNull(i)?.let { if (it != settings.align(land)) settings.setAlignFor(land, it) }
        }
        val alignRow = rowWrap(FrameLayout(this).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(alignSeg.view)
        })
        val alignRefresh = {
            alignSeg.select(settings.align(land).ordinal)
            alignRow.visibility = if (settings.int(width) < 100) View.VISIBLE else View.GONE
        }
        alignRefresh(); list += alignRefresh

        val raiseRow = slider("Raise from bottom edge", Prefs.bottomPadding(land), list, set = { settings.setFor(land, Prefs::bottomPadding, it) }) { "$it dp" }
        panel.addView(card(presetDropdown(land), heightRow, widthRow, alignRow, raiseRow), gap)

        // Split: the switch sits on top, its settings only show while it's on.
        val splitBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        splitBody.addView(slider("Gap", Prefs.splitGap(land), list, set = { settings.setFor(land, Prefs::splitGap, it) }) { "$it%" })
        splitBody.addView(slider("Tilt", Prefs.tilt(land), list, set = { settings.setFor(land, Prefs::tilt, it) }) { (if (it > 0) "+" else "") + "$it°" })
        splitBody.addView(slider("Ergonomic curve", Prefs.CURVE, list) { "$it%" })
        val splitRefresh = { splitBody.visibility = if (settings.split(land)) View.VISIBLE else View.GONE }
        splitRefresh(); list += splitRefresh
        panel.addView(card(switchRow("Split", { settings.split(land) }, { settings.setSplitFor(land, it) }, list), splitBody), gap)
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
        return dropdownRow("Preset", names, ::current, { i ->
            val p = list.getOrNull(i - 1) ?: return@dropdownRow
            settings.setFor(land, Prefs::height, p.h)
            settings.setFor(land, Prefs::width, p.w)
            settings.setAlignFor(land, p.align)
            settings.setSplitFor(land, p.split)
            if (p.split) { settings.setFor(land, Prefs::splitGap, p.gap); settings.setFor(land, Prefs::tilt, 0) }
        }, panelRefreshers)
    }

    private fun numberRowSwitch(): View = switchRow("Number row", { settings.bool(Prefs.NUMBER_ROW) }, { on ->
        val g = settings.loadGrid()
        if (!g.setNumberRow(on)) {
            Toast.makeText(this, "Move your keys out of the number row first", Toast.LENGTH_SHORT).show()
            refreshers.forEach { it() }
            return@switchRow
        }
        preview.replaceGrid(g)
    }, refreshers)

    // ---- Layout templates and sharing ----

    private fun pickTemplate() {
        val list = Template.entries
        AlertDialog.Builder(this)
            .setTitle("Start from")
            .setItems(list.map { it.title }.toTypedArray()) { _, i ->
                val t = list[i]
                val g = KeyGrid.template(t)
                if (settings.bool(Prefs.NUMBER_ROW)) g.setNumberRow(true)
                preview.replaceGrid(g)
                if (t.split) settings.setSplitFor(page, true)
                Toast.makeText(this, "${t.title} layout. Tap Edit layout, then undo, to go back.", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun shareLayout() {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "My TailorKeys layout: ${preview.grid.toCode()}")
        startActivity(Intent.createChooser(send, "Share layout"))
    }

    /** Paste box for a layout code, filled in from the clipboard when it holds one. */
    private fun importLayout() {
        val box = EditText(this).apply { hint = "Paste a layout code (starts with TK1-)"; minLines = 2 }
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            ?.takeIf { KeyGrid.fromCode(it) != null }?.let { box.setText(it) }
        val frame = LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(this)
            .setTitle("Import layout")
            .setView(frame)
            .setPositiveButton("Import") { _, _ ->
                val g = KeyGrid.fromCode(box.text.toString())
                if (g == null) Toast.makeText(this, "That isn't a TailorKeys layout code", Toast.LENGTH_SHORT).show()
                else preview.replaceGrid(g)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshStatus() {
        val enabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        status.text = when {
            selected -> "✓ TailorKeys is on and selected."
            enabled -> "TailorKeys is turned on but not selected."
            else -> "TailorKeys isn't turned on yet."
        }
        status.setTextColor(getColor(if (selected) R.color.accent else R.color.ink2))
    }

    // ---- View builders ----

    private fun pageOf(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        children.forEachIndexed { i, v ->
            val lp = (v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.topMargin = dp(12)
            addView(v, lp)
        }
    }

    /** A rounded group of rows. Each row carries its own divider; the first one is hidden. */
    private fun card(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(getColor(R.color.surface), 16f)
        clipToOutline = true
        rows.forEachIndexed { i, r ->
            addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (i == 0) ((r as? ViewGroup)?.getChildAt(0))?.takeIf { it.tag == DIVIDER }?.visibility = View.GONE
        }
    }

    /** Puts a divider line above [content]. */
    private fun rowWrap(content: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(View(this@SettingsActivity).apply { tag = DIVIDER; setBackgroundColor(getColor(R.color.divider)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = dp(16) })
        addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun label(s: String) = TextView(this).apply { text = s; textSize = 16f; setTextColor(getColor(R.color.ink)) }

    private fun actionRow(label: String, danger: Boolean = false, chevron: Boolean = true, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), 0, dp(16), 0)
            background = rippled(rounded(Color.TRANSPARENT, 0f))
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply { text = label; textSize = 16f; setTextColor(getColor(if (danger) R.color.danger else R.color.accent)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (chevron) row.addView(TextView(this).apply { text = "›"; textSize = 22f; setTextColor(getColor(R.color.ink2)) })
        return rowWrap(row)
    }

    private fun switchRow(label: String, get: () -> Boolean, set: (Boolean) -> Unit, list: MutableList<() -> Unit>): View {
        @Suppress("DEPRECATION") // android.widget.Switch avoids pulling in a support library for one widget.
        val sw = Switch(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), 0, dp(16), 0)
            addView(label(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
        val refresh = { sw.isChecked = get() }
        refresh()
        list += refresh
        sw.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean -> if (checked != get()) set(checked) }
        return rowWrap(row)
    }

    private fun dropdownRow(label: String, items: List<String>, get: () -> Int, set: (Int) -> Unit, list: MutableList<() -> Unit>): View {
        val value = TextView(this).apply { textSize = 16f; setTextColor(getColor(R.color.accent)); gravity = Gravity.END }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(16), 0, dp(16), 0)
            background = rippled(rounded(Color.TRANSPARENT, 0f))
            addView(label(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
        }
        val refresh = { value.text = items.getOrElse(get()) { "" } + "  ▾" }
        refresh()
        list += refresh
        row.setOnClickListener {
            PopupMenu(this, value).apply {
                items.forEachIndexed { i, s -> menu.add(0, i, i, s) }
                setOnMenuItemClickListener { if (it.itemId != get()) set(it.itemId); refresh(); true }
            }.show()
        }
        return rowWrap(row)
    }

    private fun slider(
        label: String,
        pref: IntPref,
        list: MutableList<() -> Unit>,
        set: (Int) -> Unit = { settings.set(pref, it) },
        onStart: (() -> Unit)? = null,
        extra: View? = null,
        format: (Int) -> String,
    ): View {
        val value = TextView(this).apply { textSize = 15f; setTextColor(getColor(R.color.ink2)); setPadding(dp(10), 0, 0, 0) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(6)) }
        col.addView(header)
        col.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return rowWrap(col)
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

    /** Space taken by the status bar, navigation bar, cutout and keyboard. */
    private fun barInsets(insets: WindowInsets): Rect = if (Build.VERSION.SDK_INT >= 30) {
        val types = WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
        val i = insets.getInsets(types)
        Rect(i.left, i.top, i.right, i.bottom)
    } else {
        @Suppress("DEPRECATION")
        Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
    }

    private companion object {
        val TAB_NAMES = listOf("Layout", "Size", "Look", "Typing")
        const val STATE_TAB = "tab"
        const val STATE_SETUP = "setup"
        const val STATE_EDITING = "editing"
        const val DIVIDER = "divider"
    }
}
