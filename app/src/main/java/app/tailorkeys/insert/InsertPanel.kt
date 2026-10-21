package app.tailorkeys.insert

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import app.tailorkeys.KeyboardSettings
import app.tailorkeys.CustomTheme
import app.tailorkeys.Palette
import app.tailorkeys.Prefs
import app.tailorkeys.SettingsActivity
import app.tailorkeys.layout.InputState
import app.tailorkeys.layout.KeyText
import app.tailorkeys.layout.Keys

/** Attached once beside QuickOptions. In editor mode only the strip receives touches. */
class InsertPanel(context: Context, private val settings: KeyboardSettings,
    private val repository: InsertRepository, private val inputState: InputState,
    private val close: () -> Unit, private val insert: (String) -> Unit, private val backspace: () -> Unit,
    private val typing: (Boolean, Int) -> Unit, private val pickIme: () -> Unit) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(n: Int) = (n * d).toInt()
    private val tablet get() = resources.configuration.smallestScreenWidthDp >= 600 && (width == 0 || width / d >= 600)
    private var palette = Palette.of(context, settings)
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val categories = LinearLayout(context)
    private val pages = FrameLayout(context)
    private val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val sectionButtons = mutableMapOf<String, TextView>()
    private val categoryButtons = mutableMapOf<String, TextView>()
    private var section = repository.prefs.getString(InsertPrefs.SECTION, "Emoji").takeIf { it in sections } ?: "Emoji"
    private var activeCategory = "Recent"
    private var notes = false
    private var lastStyle = ""
    private var savedLayer: app.tailorkeys.layout.Layer? = null
    private var savedShift = false
    private var savedCaps = false
    private var editor: String? = null
    var session = 0
        private set
    private var renderedEditor = false
    private var editingItem: ClipItem? = null
    private var draftTitle = ""
    private var draftText = ""
    private val noteDrafts = HashMap<String, Pair<String, String>>()
    private var query = ""
    private var field: EditText? = null
    private var titleField: EditText? = null
    private var textField: EditText? = null
    private val grid = GridView(context).apply { verticalSpacing = 0; horizontalSpacing = 0; stretchMode = GridView.STRETCH_COLUMN_WIDTH; isVerticalScrollBarEnabled = true }
    private var symbols = emptyList<InsertSymbol>()
    private val emoji by lazy { InsertCatalogs.emoji(context) }
    private val kaomoji by lazy { InsertCatalogs.kaomoji(context) }
    private val glyph = Paint().apply { textSize = dp(28).toFloat() }
    private val glyphs = HashMap<String, Boolean>()
    private val cachedEmoji by lazy { emoji.primary.filter { supported(it.text) } }
    private val emojiByText by lazy { emoji.entries.associateBy { it.text } }
    private val kaomojiByText by lazy { kaomoji.associateBy { it.text } }
    private val toneLayer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = GONE; elevation = dp(12).toFloat() }
    private val voiceText = TextView(context).apply { textSize = 20f; accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE }
    private val voiceMode = TextView(context)
    private val level = VoiceLevelView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var listening = false
    private var voiceButton: TextView? = null
    private val voice = VoiceInput(context, repository, { text, mode, rms, running ->
        if (text.isNotEmpty()) voiceText.text = text
        voiceMode.text = mode; listening = running
        level.update(rms, palette.accent, running)
        voiceButton?.text = if (running) "■  Stop" else "Start"
        voiceButton?.contentDescription = if (running) "Stop listening" else "Start voice recognition"
    }, insert)
    private val adapter = object : BaseAdapter() {
        override fun getCount() = symbols.size
        override fun getItem(position: Int) = symbols[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = symbols[position]
            return (convertView as? TextView ?: label("", 28f)).apply {
                if (entry.text.isEmpty()) {
                    text = entry.name; textSize = 12f; minimumHeight = 0; gravity = Gravity.BOTTOM; isFocusable = false
                    contentDescription = entry.name; importantForAccessibility = if (entry.name.isEmpty()) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
                    layoutParams = AbsListView.LayoutParams(-1, dp(32)); background = null; setTextColor(palette.hint)
                    setOnClickListener(null); setOnLongClickListener(null); return@apply
                }
                textSize = 28f; minimumHeight = dp(48); isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                text = defaultSymbol(entry).text
                contentDescription = defaultSymbol(entry).name; gravity = Gravity.CENTER
                layoutParams = AbsListView.LayoutParams(-1, dp(if (tablet) 56 else 48))
                setTextColor(palette.text); background = null
                setOnTouchListener(null)
                setOnClickListener { use(defaultSymbol(entry)) }
                setOnLongClickListener { showTones(this, entry) }
            }
        }
    }
    init {
        isFocusable = false; setPadding(dp(8), dp(4), dp(8), 0)
        addView(body, LayoutParams(-1, -1)); addView(toneLayer, LayoutParams(-2, -2))
        grid.adapter = adapter
        // Parse the emoji file and run the glyph checks off the UI thread, so the first Emoji open does not stall.
        Thread({ runCatching { cachedEmoji; emojiByText } }, "insert-emoji-warmup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView?, first: Int, visible: Int, total: Int) {
                if (editor == null && section == "Emoji") symbols.getOrNull(first)?.category?.let { current ->
                    val category = current
                    if (category != activeCategory) { activeCategory = category; markCategories() }
                }
            }
        })
    }
    // Paint and the HashMap are shared with the warm-up thread, so checks are serialised on the Paint.
    private fun supported(text: String) = synchronized(glyph) { glyphs.getOrPut(text) { glyph.hasGlyph(text) } }
    private fun defaultSymbol(entry: InsertSymbol): InsertSymbol {
        if (entry.text.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF }) return entry
        val toned = emoji.toned(entry, repository.prefs.getInt(InsertPrefs.TONE, 0))
        return if (supported(toned.text)) toned else entry
    }
    private fun label(text: String, size: Float = 14f) = TextView(context).apply { this.text = text; textSize = size; setTextColor(palette.text); gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48) }
    private fun shape(color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(settings.int(Prefs.CORNER_RADIUS)).toFloat()
        setStroke(dp(if (stroke) 2 else 1), if (stroke) palette.accent else palette.keyOutline)
    }
    private fun button(text: String, description: String = text, selected: Boolean = false, action: () -> Unit) = label(text).apply {
        contentDescription = description; gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), 0)
        minimumWidth = dp(48)
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        background = shape(if (selected) palette.key else palette.functionKey, selected)
        setTextColor(if (selected) palette.smallAccent else palette.text)
        val icon = when { description in categoryIcons -> categoryIcons[description]; description in listOf("Emoji", "Clipboard", "Voice", "Recent", "Backspace") -> description; description.startsWith("Search ") -> "Search"; else -> null }
        if (icon != null) {
            val drawable = InsertIcon(icon, if (selected) palette.smallAccent else palette.text).apply { setBounds(0, 0, dp(20), dp(20)) }
            if (!tablet || icon == "Backspace" || icon == "Recent" || description in categoryIcons) this.text = ""
            else this.text = if (icon == "Search") description else description
            compoundDrawablePadding = dp(6); setCompoundDrawables(drawable, null, null, null)
        }
        isSelected = selected; isFocusable = true; setOnClickListener { action() }
    }
    private fun tabShape(selected: Boolean): android.graphics.drawable.Drawable {
        if (!selected) return android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        return android.graphics.drawable.LayerDrawable(arrayOf(shape(palette.key), shape(palette.accent))).apply {
            setLayerHeight(1, dp(3)); setLayerGravity(1, Gravity.BOTTOM); setLayerInset(1, dp(8), 0, dp(8), 0)
        }
    }
    fun show() {
        if (editor == "Note") retainDraft(); restoreKeys(); if (editor != null) typing(false, 0); editor = null; field = null; session++
        visibility = VISIBLE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        refresh(renderedEditor)
        if (section in listOf("Emoji", "Kaomoji") && editor == null) {
            activeCategory = "Recent"
            pages.removeAllViews(); categories.removeAllViews(); categoryButtons.clear(); renderSymbols()
            if (section == "Emoji") grid.setSelection(0)
        }
        if (section == "Clipboard") clipboardChanged()
        if (section == "Voice") { pages.removeAllViews(); renderVoice() }
    }
    fun hide() { voice.cancel(); listening = false; session++; toneLayer.visibility = GONE; if (editor == "Note") retainDraft(); restoreKeys(); editor = null; field = null; typing(false, 0); visibility = INVISIBLE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
    fun refresh(force: Boolean = false) {
        if (visibility != VISIBLE) return
        val style = "${settings.themeId()}:${CustomTheme.encode(settings.customThemes())}:${resources.configuration.uiMode}:${settings.int(Prefs.CORNER_RADIUS)}:" +
            listOf(InsertPrefs.TONE, InsertPrefs.HISTORY, InsertPrefs.RECENTS, InsertPrefs.KEEP, InsertPrefs.VOICE, InsertPrefs.DEVICE_ONLY).joinToString { repository.prefs.all[it].toString() }
        if (!force && lastStyle == style) return
        lastStyle = style; palette = Palette.of(context, settings)
        if (editor != null) renderEditor() else renderPanel()
    }
    private fun clearBody() { body.removeAllViews(); top.removeAllViews(); categories.removeAllViews(); pages.removeAllViews(); footer.removeAllViews(); sectionButtons.clear(); categoryButtons.clear(); toneLayer.visibility = GONE }
    private fun renderPanel() {
        renderedEditor = false
        if (section == "Voice" && !repository.prefs.getBoolean(InsertPrefs.VOICE, true)) { section = "Emoji"; activeCategory = "Recent" }
        isClickable = true
        clearBody(); setBackgroundColor(palette.background)
        body.layoutParams.height = -1
        top.addView(button("ABC", "Back to keys") { close() }, LinearLayout.LayoutParams(dp(if (tablet) 72 else 48), dp(48)))
        if (tablet && section != "Voice") top.addView(button("⌕  Search", "Search ${section.lowercase()}") { startSearch() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        if (tablet && section == "Voice") top.addView(label(if (voice.onDevice) "On-device" else "Online recognition", 12f), LinearLayout.LayoutParams(0, dp(48), 1f))
        val switch = LinearLayout(context).apply { setPadding(dp(4), 0, dp(4), 0); background = shape(palette.functionKey) }
        sections.forEachIndexed { i, name ->
            if (name == "Voice" && !repository.prefs.getBoolean(InsertPrefs.VOICE, true)) return@forEachIndexed
            val text = if (tablet) listOf("☺ Emoji", "^_^ Kaomoji", "▤ Clipboard", "♩ Voice")[i] else listOf("☺", "^_^", "▤", "♩")[i]
            val b = button(text, name, section == name) { voice.cancel(); listening = false; section = name; activeCategory = "Recent"; repository.prefs.edit().putString(InsertPrefs.SECTION, name).apply(); renderPanel() }
            b.background = tabShape(section == name)
            sectionButtons[name] = b; switch.addView(b, LinearLayout.LayoutParams(if (tablet) -2 else 0, dp(48), if (tablet) 0f else 1f))
        }
        top.addView(switch, LinearLayout.LayoutParams(if (tablet) -2 else 0, dp(48), if (tablet) 0f else 1f))
        top.addView(button("⌫", "Backspace") { backspace() }, LinearLayout.LayoutParams(dp(if (tablet) 72 else 48), dp(48)))
        body.addView(top, LinearLayout.LayoutParams(-1, dp(56)))
        body.addView(categories, LinearLayout.LayoutParams(-1, -2))
        body.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(footer, LinearLayout.LayoutParams(-1, -2))
        when (section) { "Emoji", "Kaomoji" -> renderSymbols(); "Clipboard" -> renderClipboard(); "Voice" -> renderVoice() }
    }
    private fun recentSymbols(which: String): List<InsertSymbol> {
        if (!repository.prefs.getBoolean(InsertPrefs.RECENTS, true)) return emptyList()
        val byText = if (which == "Emoji") emojiByText else kaomojiByText
        return repository.recent(which).mapNotNull { byText[it] }.filter { which != "Emoji" || supported(it.text) }
    }
    private fun renderSymbols() {
        val all = if (section == "Emoji") cachedEmoji else kaomoji
        val cats = listOf("Recent") + all.map { it.category }.distinct()
        val tabs = LinearLayout(context)
        if (!tablet) tabs.addView(button("⌕", "Search ${section.lowercase()}") { startSearch() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        cats.forEach { cat ->
            val names = listOf("Recent", "Smileys & Emotion", "People & Body", "Animals & Nature", "Food & Drink", "Travel & Places", "Activities", "Objects", "Symbols", "Flags")
            val icon = listOf("◷", "☺", "♙", "♧", "♨", "▱", "⚽", "♧", "♡", "⚑").getOrElse(names.indexOf(cat)) { cat }
            val b = button(if (section == "Emoji") icon else cat, cat, cat == activeCategory) {
                activeCategory = cat; markCategories()
                if (section == "Emoji") { val index = if (cat == "Recent") 0 else symbols.indexOfFirst { it.category == cat }.coerceAtLeast(0); grid.setSelection(index) }
                else showKaomoji(if (cat == "Recent") kaomojiWithRecents() else all.filter { it.category == cat })
            }
            categoryButtons[cat] = b; tabs.addView(b, LinearLayout.LayoutParams(-2, dp(48)))
            b.background = tabShape(cat == activeCategory)
        }
        categories.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, LinearLayout.LayoutParams(-1, dp(48)))
        if (section == "Emoji") {
            symbols = emojiCells()
            (grid.parent as? ViewGroup)?.removeView(grid)
            grid.numColumns = if (tablet) 14 else 8; grid.columnWidth = dp(if (tablet) 56 else 48)
            // Directly in the page at full width: a HorizontalScrollView measures with an unbounded width,
            // which collapses STRETCH_COLUMN_WIDTH columns to nothing.
            pages.addView(grid, LayoutParams(-1, -1)); adapter.notifyDataSetChanged()
        } else showKaomoji(if (activeCategory == "Recent") kaomojiWithRecents() else all.filter { it.category == activeCategory })
    }
    private fun emojiCells(): List<InsertSymbol> {
        val cols = if (tablet) 14 else 8
        val out = ArrayList<InsertSymbol>()
        fun group(category: String, entries: List<InsertSymbol>) {
            if (entries.isEmpty()) return
            while (out.size % cols != 0) out.add(InsertSymbol("", "", out.last().category))
            val title = when (category) { "Smileys & Emotion" -> "Smileys"; "People & Body" -> "People"; "Animals & Nature" -> "Animals"; "Food & Drink" -> "Food"; "Travel & Places" -> "Travel"; else -> category }
            repeat(cols) { out.add(InsertSymbol("", if (it == 0) title else "", category)) }
            out.addAll(entries.map { it.copy(category = category) })
        }
        group("Recent", recentSymbols("Emoji"))
        cachedEmoji.groupBy { it.category }.forEach { (cat, entries) -> group(cat, entries) }
        return out
    }
    private fun markCategories() { categoryButtons.forEach { (name, b) -> b.isSelected = name == activeCategory; b.background = tabShape(b.isSelected); b.setTextColor(if (b.isSelected) palette.smallAccent else palette.text) } }
    private fun kaomojiWithRecents(): List<InsertSymbol> {
        val recent = recentSymbols("Kaomoji")
        return recent + kaomoji.filter { entry -> recent.none { it.text == entry.text } }
    }
    private fun showKaomoji(items: List<InsertSymbol>) {
        pages.removeAllViews()
        val flow = ChipFlow(context, dp(4))
        items.forEach { entry -> flow.addView(button(entry.text, entry.name) { use(entry) }.apply { textSize = 16f; minimumWidth = dp(64); background = shape(palette.key) }, ViewGroup.LayoutParams(-2, dp(48))) }
        pages.addView(ScrollView(context).apply { addView(flow) }, LayoutParams(-1, -1))
    }
    private fun use(entry: InsertSymbol) {
        val which = section
        repository.insertSymbol(which, entry, insert)
        if (editor != null || section != which) return
        activeCategory = "Recent"; markCategories()
        if (which == "Emoji") {
            symbols = emojiCells(); adapter.notifyDataSetChanged(); grid.setSelection(0)
        } else if (which == "Kaomoji") showKaomoji(kaomojiWithRecents())
    }
    private fun showTones(anchor: View, entry: InsertSymbol): Boolean {
        val variants = emoji.variants(entry.text).withIndex().filter { supported(it.value.text) }
        if (variants.size < 2) return false
        toneLayer.removeAllViews(); toneLayer.background = shape(palette.key); val default = repository.prefs.getInt(InsertPrefs.TONE, 0)
        toneLayer.addView(label("Skin tone: ${InsertPrefs.toneNames.getOrElse(default) { "Default" }} (your default)", 12f))
        val row = LinearLayout(context); var chosen = -1
        variants.forEach { (index, e) -> row.addView(button(e.text + if (index == default) " ✓" else "", "${e.name}${if (index == default) ", your default, checked" else ""}") { use(e); toneLayer.visibility = GONE }, LinearLayout.LayoutParams(dp(48), dp(48))) }
        toneLayer.addView(row)
        val point = IntArray(2); val origin = IntArray(2); anchor.getLocationOnScreen(point); getLocationOnScreen(origin)
        (toneLayer.layoutParams as LayoutParams).apply { width = dp(48 * variants.size); leftMargin = (point[0] - origin[0] - dp(96)).coerceIn(0, (this@InsertPanel.width - width).coerceAtLeast(0)); topMargin = (point[1] - origin[1] - dp(96)).coerceAtLeast(dp(56)) }
        toneLayer.visibility = VISIBLE
        anchor.parent.requestDisallowInterceptTouchEvent(true)
        anchor.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) {
                val loc = IntArray(2); row.getLocationOnScreen(loc)
                chosen = if (event.rawY >= loc[1] && event.rawY < loc[1] + row.height && event.rawX >= loc[0] && event.rawX < loc[0] + row.width) ((event.rawX - loc[0]) / dp(48)).toInt().takeIf { it in variants.indices } ?: -1 else -1
                for (i in 0 until row.childCount) row.getChildAt(i).background = shape(if (i == chosen) palette.pressed else palette.key, variants[i].index == default)
                if (event.actionMasked == MotionEvent.ACTION_UP) { if (chosen >= 0) use(variants[chosen].value); toneLayer.visibility = GONE; anchor.setOnTouchListener(null); anchor.parent.requestDisallowInterceptTouchEvent(false) }
            } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) { toneLayer.visibility = GONE; anchor.setOnTouchListener(null); anchor.parent.requestDisallowInterceptTouchEvent(false) }
            true
        }
        return true
    }
    private fun prepareKeys() {
        if (savedLayer == null) { savedLayer = inputState.layer; savedShift = inputState.shift; savedCaps = inputState.capsLock }
        inputState.layer = app.tailorkeys.layout.Layer.ABC; inputState.shift = false; inputState.capsLock = false
    }
    private fun restoreKeys() {
        savedLayer?.let { inputState.layer = it; inputState.shift = savedShift; inputState.capsLock = savedCaps }
        savedLayer = null; session++
    }
    private fun startSearch() { prepareKeys(); editor = "Search"; session++; renderEditor() }
    private fun edit(item: ClipItem? = null, asNote: Boolean = true) {
        prepareKeys(); editingItem = item; editor = "Note"; session++
        val draft = noteDrafts[item?.id ?: "new"]
        draftTitle = draft?.first ?: item?.let { if (asNote && !it.note) it.text.take(40) else it.title }.orEmpty()
        draftText = draft?.second ?: item?.text.orEmpty()
        renderEditor()
    }
    private fun input(text: String, hint: String, changed: (String) -> Unit): EditText = EditText(context).apply {
        setText(text); setSelection(length()); this.hint = hint; textSize = 15f; setTextColor(palette.text); setHintTextColor(palette.hint)
        showSoftInputOnFocus = false; background = shape(palette.key, true); setPadding(dp(12), 0, dp(8), 0); minimumHeight = dp(48)
        addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s.toString()) }; override fun afterTextChanged(s: Editable?) = Unit })
        setOnFocusChangeListener { _, focus -> if (focus) { field = this; session++ } }
    }
    private fun renderEditor() {
        renderedEditor = true
        isClickable = false
        clearBody(); setBackgroundColor(android.graphics.Color.TRANSPARENT); body.layoutParams.height = -2
        val stripHeight = if (editor == "Note") (if (tablet) 104 else 152) else if (tablet) 56 else 104
        typing(true, stripHeight)
        val sheet = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.background) }
        body.addView(sheet, LinearLayout.LayoutParams(-1, dp(stripHeight)))
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }; sheet.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
        row.addView(button(if (editor == "Search") "‹" else "Cancel", "Back to ${section.lowercase()}") { if (editor == "Note") retainDraft(); restoreKeys(); editor = null; field = null; typing(false, 0); renderPanel() }, LinearLayout.LayoutParams(dp(if (editor == "Search") 48 else 72), dp(48)))
        if (editor == "Search") {
            val search = input(query, "Search ${section.lowercase()}") { query = it; renderResults(sheet) }.apply { isSingleLine = true; contentDescription = "Search ${section.lowercase()}" }
            row.addView(search, LinearLayout.LayoutParams(0, dp(48), 1f)); field = search
            row.addView(button("×", "Clear search") { search.setText("") }, LinearLayout.LayoutParams(dp(48), dp(48)))
            renderResults(sheet); search.requestFocus()
        } else {
            row.addView(label(if (editingItem == null) "New note" else "Edit ${if (editingItem?.note == true) "note" else "clip"}"), LinearLayout.LayoutParams(0, -1, 1f))
            row.addView(button("✓ Save", "Save note", true) {
                retainDraft(); repository.change { s -> val item = editingItem; if (item != null) s.edit(item.id, draftText, draftTitle) else s.note(draftTitle, draftText) }
                noteDrafts.remove(editingItem?.id ?: "new"); draftText = ""; draftTitle = ""; editingItem = null; restoreKeys(); editor = null; field = null; typing(false, 0); renderPanel()
            }, LinearLayout.LayoutParams(dp(80), dp(48)))
            titleField = input(draftTitle, "Title (${ClipboardStore.TITLE_MAX} characters)") { draftTitle = it.take(ClipboardStore.TITLE_MAX) }.apply { filters = arrayOf(android.text.InputFilter.LengthFilter(ClipboardStore.TITLE_MAX)); contentDescription = "Note title"; isSingleLine = true }
            textField = input(draftText, "Text (%,d characters)".format(ClipboardStore.NOTE_MAX)) { draftText = it }.apply { filters = arrayOf(android.text.InputFilter.LengthFilter(ClipboardStore.NOTE_MAX)); contentDescription = "Note text" }
            if (tablet) {
                val fields = LinearLayout(context)
                fields.addView(titleField, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(4) })
                fields.addView(textField, LinearLayout.LayoutParams(0, dp(56), 2f))
                sheet.addView(fields, LinearLayout.LayoutParams(-1, dp(56)))
            } else {
                sheet.addView(titleField, LinearLayout.LayoutParams(-1, dp(48))); sheet.addView(textField, LinearLayout.LayoutParams(-1, dp(56)))
            }
            field = textField; textField?.requestFocus()
        }
    }
    private fun retainDraft() { draftTitle = titleField?.text?.toString().orEmpty(); draftText = textField?.text?.toString().orEmpty(); noteDrafts[editingItem?.id ?: "new"] = draftTitle to draftText }
    private fun renderResults(sheet: LinearLayout) {
        if (sheet.childCount > 1) sheet.removeViewAt(1)
        val row = LinearLayout(context)
        val result = when (section) { "Emoji" -> emoji.search(query).filter { supported(it.text) }; "Kaomoji" -> kaomoji.filter { (it.name + it.category).contains(query, true) }; else -> emptyList() }.take(40)
        if (section == "Clipboard") {
            repository.searchableItems().filter { (it.title + it.text).contains(query, true) }.take(20).forEach { clip -> row.addView(button(clip.title.ifBlank { clip.text.take(30) }, clip.text) { insert(clip.text) }) }
        } else result.forEach { e -> val shown = if (section == "Emoji") defaultSymbol(e) else e; row.addView(button(shown.text, shown.name) { use(shown) }.apply { textSize = if (section == "Emoji") 28f else 16f }, LinearLayout.LayoutParams(if (section == "Emoji") dp(48) else -2, dp(48))) }
        if (row.childCount == 0) row.addView(label("No ${section.lowercase()} for “$query”", 13f))
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        // Tablets share one row; phones place results beneath the field.
        if (tablet && editor == "Search") { val header = sheet.getChildAt(0) as LinearLayout; if (header.childCount > 3) header.removeViewAt(3); header.addView(scroll, LinearLayout.LayoutParams(0, dp(48), 2f)) }
        else sheet.addView(scroll, LinearLayout.LayoutParams(-1, dp(48)))
    }
    fun acceptsText() = editor != null && visibility == VISIBLE
    fun cursor(steps: Int, select: Boolean): Boolean {
        if (!acceptsText()) return false
        val target = field ?: return true
        val at = target.selectionEnd.coerceAtLeast(0)
        val count = if (steps < 0) -minOf(-steps.toLong(), Character.codePointCount(target.text, 0, at).toLong()).toInt() else minOf(steps, Character.codePointCount(target.text, at, target.length()))
        val next = Character.offsetByCodePoints(target.text, at, count)
        target.setSelection(if (select) target.selectionStart.coerceAtLeast(0) else next, next)
        return true
    }
    fun typeText(text: String): Boolean {
        if (!acceptsText()) return false
        val target = field ?: return true
        val start = target.selectionStart.coerceAtLeast(0); val end = target.selectionEnd.coerceAtLeast(0)
        target.text.replace(minOf(start, end), maxOf(start, end), text); return true
    }
    fun key(code: String): Boolean {
        if (!acceptsText()) return false
        if (KeyText.isLayerKey(code)) return false
        when (code) {
            Keys.BACKSPACE -> { val target = field ?: return true; val a = target.selectionStart.coerceAtLeast(0); val b = target.selectionEnd.coerceAtLeast(0); if (a != b) target.text.delete(minOf(a,b), maxOf(a,b)) else if (a > 0) target.text.delete(Character.offsetByCodePoints(target.text, a, -1), a) }
            Keys.SPACE -> typeText(" ")
            Keys.ENTER -> if (editor == "Note") typeText("\n")
            else -> KeyText.output(code, inputState)?.let { typeText(it); if (!inputState.capsLock) inputState.shift = false }
        }
        return true
    }
    fun clipboardChanged() { if (visibility == VISIBLE && section == "Clipboard" && editor == null) { pages.removeAllViews(); footer.removeAllViews(); categories.removeAllViews(); renderClipboard() } }
    private fun renderClipboard() {
        val store = repository.store(); repository.save(store)
        if (!tablet) {
            categories.addView(button("⌕", "Search clips") { startSearch() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            categories.addView(button("Clipboard ${store.clips().size}", selected = !notes) { notes = false; clipboardChanged() }, LinearLayout.LayoutParams(0, dp(48), 1f))
            categories.addView(button("Notes ${store.notes().size}", selected = notes) { notes = true; clipboardChanged() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        val columns = LinearLayout(context)
        fun column(isNotes: Boolean): View {
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val actions = LinearLayout(context)
            actions.addView(label(if (isNotes) "Notes" else "Clipboard"), LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(button(if (isNotes) "+ New" else "Clear", if (isNotes) "Create note" else "Clear unpinned clipboard history") { if (isNotes) edit() else { repository.change { it.clear() }; clipboardChanged() } })
            col.addView(actions)
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val enabled = repository.prefs.getBoolean(InsertPrefs.HISTORY, false)
            val items = if (isNotes) store.notes() else if (enabled) store.clips() else emptyList()
            if (isNotes && items.isEmpty()) list.addView(label("No notes yet\nWrite one with + New, or save a clip as a note."))
            if (!isNotes) when (clipboardPanelState(enabled, items.isNotEmpty(), repository.prefs.getBoolean(InsertPrefs.HAD_CLIPS, false), repository.prefs.getBoolean("insert_first_use", false))) {
                ClipboardPanelState.HISTORY_OFF -> {
                    list.addView(label("Clipboard history is off\nTurn it on to keep what you copy for a while. Notes still work.").apply { gravity = Gravity.CENTER; setPadding(dp(12), dp(16), dp(12), dp(16)) })
                    list.addView(button("Turn on") { repository.prefs.edit().putBoolean(InsertPrefs.HISTORY, true).apply(); clipboardChanged() })
                }
                ClipboardPanelState.FIRST_USE -> list.addView(button("History stays on this device. Got it", "History stays on this device and clears after ${repository.keep.title}. Got it") { repository.prefs.edit().putBoolean("insert_first_use", true).apply(); clipboardChanged() })
                ClipboardPanelState.EMPTY -> list.addView(label("Nothing copied yet\nCopy text and it shows up here."))
                else -> Unit
            }
            var pinned: Boolean? = null
            items.forEach { clip ->
                if (!isNotes && pinned != clip.pinned) { pinned = clip.pinned; list.addView(label(if (clip.pinned) "Pinned" else "Recent", 12f)) }
                val age = android.text.format.DateUtils.getRelativeTimeSpanString(clip.at, System.currentTimeMillis(), 60_000).toString()
                val card = button((if (clip.pinned) "⌖ " else "") + (if (isNotes) clip.title.ifBlank { "Note" } + "\n" + clip.text else clip.text + "\n" + age), "${if (clip.pinned) "Pinned, " else ""}${clip.title}, ${clip.text}, $age") { insert(clip.text) }.apply {
                    gravity = Gravity.CENTER_VERTICAL; maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; background = shape(palette.key); setPadding(dp(12), dp(6), dp(12), dp(6))
                    setOnLongClickListener { actions(this, clip); true }
                }
                list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            }
            col.addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f)); return col
        }
        if (tablet) { columns.addView(column(false), LinearLayout.LayoutParams(0, -1, 1f)); columns.addView(column(true), LinearLayout.LayoutParams(0, -1, 1f)) }
        else columns.addView(column(notes), LinearLayout.LayoutParams(-1, -1))
        pages.addView(columns, LayoutParams(-1, -1))
        footer.addView(label("Items stay on this device and clear after ${repository.keep.title}.\nPins and notes stay until you delete them.", 11f), LinearLayout.LayoutParams(0, dp(48), 1f))
        footer.addView(button("Settings") { openSettings() })
    }
    private fun actions(anchor: View, item: ClipItem) {
        PopupMenu(context, anchor).apply {
            listOf(if (item.pinned) "Unpin" else "Pin", "Edit", if (item.note) "Delete" else "Save as note", if (item.note) null else "Delete").filterNotNull().forEach { menu.add(it) }
            setOnMenuItemClickListener { action ->
                when (action.title.toString()) {
                    "Pin", "Unpin" -> repository.change { it.pin(item.id) }
                    "Edit" -> edit(item)
                    "Save as note" -> repository.change { it.saveAsNote(item.id) }
                    "Delete" -> {
                        val store = repository.store(); store.delete(item.id); repository.save(store); clipboardChanged()
                        val undo = button("${if (item.note) "Note" else "Clip"} deleted · Undo") { if (store.undo()) repository.change { it.restore(item) }; clipboardChanged() }
                        footer.addView(undo); postDelayed({ footer.removeView(undo) }, 6_000); return@setOnMenuItemClickListener true
                    }
                }
                if (editor == null) clipboardChanged(); true
            }; show()
        }
    }
    private fun renderVoice() {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        (voiceText.parent as? ViewGroup)?.removeView(voiceText); (voiceMode.parent as? ViewGroup)?.removeView(voiceMode); (level.parent as? ViewGroup)?.removeView(level)
        voiceMode.text = ""; voiceMode.setTextColor(palette.hint)
        voiceText.setTextColor(palette.text); voiceText.background = shape(palette.key); voiceText.setPadding(dp(12), dp(8), dp(12), dp(8))
        voiceText.maxLines = 5; voiceText.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(voiceMode, LinearLayout.LayoutParams(-1, dp(if (tablet) 48 else 24))); col.addView(voiceText, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!voice.permitted) {
            voiceText.text = "Allow microphone in TailorKeys\nAn Android keyboard cannot show the permission dialog."
            col.addView(button("Open TailorKeys", "Allow microphone in TailorKeys", true) { openSettings(requestMicrophone = true) })
        } else if (!voice.available) {
            voiceText.text = "No speech recognizer is installed. Switch to the system voice keyboard."
            col.addView(button("Choose voice keyboard") { pickIme() })
        } else if (voice.deviceOnly && !voice.onDevice) {
            voiceMode.text = "On-device only"
            voiceText.text = "On-device recognition is unavailable. Change Voice settings to allow online recognition, or use the system voice keyboard."
            col.addView(button("Voice settings") { openSettings() })
            col.addView(button("Choose voice keyboard") { pickIme() })
        } else {
            voiceMode.text = if (voice.onDevice) "On-device" else "Online recognition"
            voiceText.text = "Tap Start and speak. Stop keeps what you said. Cancel discards it."
            level.update(0f, palette.accent, false)
            col.addView(level, LinearLayout.LayoutParams(-1, dp(40)))
            val buttons = LinearLayout(context)
            buttons.addView(button("Cancel") { voice.cancel(); listening = false; level.update(0f, palette.accent, false); voiceText.text = "Cancelled"; voiceButton?.text = "Start" }, LinearLayout.LayoutParams(0, dp(48), 1f))
            voiceButton = button("Start", "Start or stop voice recognition", true) { if (listening) voice.stop() else voice.start(settings.currentLanguage()) }.apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.accent) }
                setTextColor(palette.onAccent)
            }
            buttons.gravity = Gravity.CENTER_VERTICAL
            buttons.addView(voiceButton, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginStart = dp(12); marginEnd = dp(12) })
            buttons.addView(button("Settings") { voice.cancel(); openSettings() }, LinearLayout.LayoutParams(0, dp(48), 1f)); col.addView(buttons)
        }
        pages.addView(col, LayoutParams(-1, -1))
        if (voice.permitted && voice.available && (!voice.deviceOnly || voice.onDevice)) post { if (visibility == VISIBLE && section == "Voice" && editor == null && !listening) voice.start(settings.currentLanguage()) }
    }
    private fun openSettings(requestMicrophone: Boolean = false) {
        val page = when (section) { "Voice" -> "Voice typing"; "Clipboard" -> "Clipboard and notes"; else -> "Emoji" }
        context.startActivity(Intent(context, SettingsActivity::class.java).putExtra("insert_settings_page", page)
            .putExtra("request_microphone", requestMicrophone).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
    companion object {
        private val sections = listOf("Emoji", "Kaomoji", "Clipboard", "Voice")
        private val categoryIcons = mapOf("Smileys & Emotion" to "Emoji", "People & Body" to "People", "Animals & Nature" to "Animals", "Food & Drink" to "Food", "Travel & Places" to "Travel", "Activities" to "Activities", "Objects" to "Objects", "Symbols" to "Symbols", "Flags" to "Flags")
    }
}

/** Small variable-width chip layout; kaomoji retain their full text at 48dp height. */
private class ChipFlow(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec); var x = 0; var y = 0; var row = 0
        for (i in 0 until childCount) { val child = getChildAt(i); measureChild(child, widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)); if (x > 0 && x + child.measuredWidth > width) { x = 0; y += row + gap; row = 0 }; x += child.measuredWidth + gap; row = maxOf(row, child.measuredHeight) }
        setMeasuredDimension(width, y + row)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = 0; var y = 0; var row = 0
        for (i in 0 until childCount) { val child = getChildAt(i); if (x > 0 && x + child.measuredWidth > width) { x = 0; y += row + gap; row = 0 }; child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight); x += child.measuredWidth + gap; row = maxOf(row, child.measuredHeight) }
    }
}
