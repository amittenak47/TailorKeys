package app.tailorkeys.insert

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Canvas
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle
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
    private val typing: (Boolean, Int) -> Unit, private val pickIme: () -> Unit,
    private val composeVoice: (String) -> Unit, private val finishVoice: (String) -> Unit, private val cancelVoice: () -> Unit) : FrameLayout(context) {
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
    private var gridPosition = 0
    private var field: EditText? = null
    private var titleField: EditText? = null
    private var textField: EditText? = null
    private var titleCounter: TextView? = null
    private var textCounter: TextView? = null
    private var undoToast: View? = null
    private val grid = GridView(context).apply { verticalSpacing = 0; horizontalSpacing = 0; stretchMode = GridView.STRETCH_SPACING_UNIFORM; isVerticalScrollBarEnabled = true; clipChildren = false }
    private var symbols = emptyList<InsertSymbol>()
    private var emoji: EmojiCatalog? = null
    private val kaomoji by lazy { InsertCatalogs.kaomoji(context) }
    private val glyph = Paint().apply { textSize = dp(28).toFloat() }
    private val glyphs = HashMap<String, Boolean>()
    private var cachedEmoji = emptyList<InsertSymbol>()
    private var emojiByText = emptyMap<String, InsertSymbol>()
    private val kaomojiByText by lazy { kaomoji.associateBy { it.text } }
    private val dim = View(context).apply { visibility = GONE; isClickable = true; setOnClickListener { dismissOverlay() } }
    private var heldView: View? = null
    private var heldBackground: android.graphics.drawable.Drawable? = null
    private val toneLayer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = GONE; elevation = dp(12).toFloat() }
    private val voiceText = TextView(context).apply { textSize = 20f; accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE }
    private val voiceMode = TextView(context)
    private val level = VoiceLevelView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var listening = false
    private var voiceButton: VoiceStopView? = null
    private var startVoiceOnRender = false
    private var previousVoiceText = ""
    private val voice = VoiceInput(context, repository, { text, mode, rms, running ->
        if (text.isNotEmpty()) {
            val display = android.text.SpannableString(text)
            if (running && text != "Listening…") {
                val settled = settledVoicePrefix(previousVoiceText,text)
                display.setSpan(android.text.style.ForegroundColorSpan(sub),settled,text.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                previousVoiceText=text
            }
            voiceText.text=display
        }
        voiceMode.text=mode; listening=running
        level.update(rms,palette.accent,running); voiceButton?.update(rms,palette.accent,palette.onAccent,running)
        voiceButton?.contentDescription=if (running) "Stop listening" else "Start voice recognition"
    }, composeVoice, finishVoice, cancelVoice)
    private val adapter = object : BaseAdapter() {
        override fun getCount() = symbols.size
        override fun getItem(position: Int) = symbols[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = symbols[position]
            if (entry.text.isEmpty()) return HeaderLabel(context).apply {
                text = entry.name; setTextColor(sub); textSize = 12f
                contentDescription = entry.name; importantForAccessibility = if (entry.name.isEmpty()) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_YES
                layoutParams = AbsListView.LayoutParams(-1, dp(if (entry.keywords == "spacer") (if (tablet) 52 else 46) else 26))
            }
            val view = (convertView as? TextView)?.takeUnless { it is HeaderLabel } ?: label("")
            return view.apply {
                textSize = if (tablet) 32f else 28f; minimumHeight = dp(44); isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
                text = defaultSymbol(entry).text
                contentDescription = defaultSymbol(entry).name; gravity = Gravity.CENTER
                layoutParams = AbsListView.LayoutParams(-1, dp(if (tablet) 52 else 46))
                setTextColor(palette.text); background = null
                setOnTouchListener(null)
                setOnClickListener { use(defaultSymbol(entry)) }
                setOnLongClickListener { showTones(this, entry) }
            }
        }
    }
    init {
        isFocusable = false
        addView(body, LayoutParams(-1, -1)); addView(dim, LayoutParams(-1, -1)); addView(toneLayer, LayoutParams(-2, -2))
        grid.adapter = adapter
        // Parse the emoji file and run the glyph checks off the UI thread, so the first Emoji open does not stall.
        Thread({ runCatching {
            val catalog = InsertCatalogs.emoji(context)
            val supported = catalog.primary.filter { supported(it.text) }
            val index = catalog.entries.associateBy { it.text }
            post {
                emoji = catalog; cachedEmoji = supported; emojiByText = index
                if (visibility == VISIBLE && section == "Emoji") {
                    if (editor == null) renderPanel() else if (editor == "Search") renderEditor()
                }
            }
        } }, "insert-emoji-warmup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
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
        val toned = emoji?.toned(entry, repository.prefs.getInt(InsertPrefs.TONE, 0)) ?: return entry
        return if (supported(toned.text)) toned else entry
    }
    private val fill get() = mix(palette.background, palette.text, .07f)
    private val fillHi get() = mix(palette.background, palette.text, .12f)
    private val hair get() = mix(palette.key, palette.text, .12f)
    private val sub get() = readable(palette.hint, fill)
    private fun readable(color: Int, surface: Int, toward: Int = palette.text): Int {
        for (step in 0..100) { val c = mix(color, toward, step / 100f); if (app.tailorkeys.contrastRatio(c, surface) >= 4.5) return c }
        return toward
    }
    private fun label(text: String, size: Float = 14f) = TextView(context).apply { this.text = text; textSize = size; setTextColor(palette.text); gravity = Gravity.CENTER_VERTICAL }
    private fun shape(color: Int, stroke: Boolean = false, radius: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        setStroke(if (stroke) dp(2) else dp(1), if (stroke) palette.accent else palette.keyOutline)
    }
    private fun button(text: String, description: String = text, selected: Boolean = false, action: () -> Unit) = label(text).apply {
        contentDescription = description; gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
        minimumWidth = dp(44); minimumHeight = dp(44); textSize = 14f
        setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        background = if (selected) shape(palette.key, radius = 9) else null
        setTextColor(palette.text); isSelected = selected; isFocusable = true; tooltipText = description
        setOnClickListener { action() }
    }
    private fun icon(view: TextView, kind: String, color: Int, leading: Boolean = false) {
        view.foreground = object : android.graphics.drawable.Drawable() {
            private val drawable = InsertIcon(kind,color)
            override fun draw(canvas: Canvas) { val size=dp(20); val x=if (leading) dp(18) else bounds.centerX(); val y=bounds.centerY(); drawable.setBounds(x-size/2,y-size/2,x+size/2,y+size/2); drawable.draw(canvas) }
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(filter: android.graphics.ColorFilter?) = Unit
            @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }
    private fun categoryShape(selected: Boolean): android.graphics.drawable.Drawable? = if (selected) android.graphics.drawable.InsetDrawable(shape(fillHi, radius = 17), dp(if (section == "Emoji") (if (tablet) 7 else 5) else 0), dp(5), dp(if (section == "Emoji") (if (tablet) 7 else 5) else 0), dp(5)) else null
    private fun bottomScroll(content: View) = object : HorizontalScrollView(context) { override fun getLeftFadingEdgeStrength() = 0f }.apply {
        isHorizontalScrollBarEnabled = false; isFillViewport = tablet; isHorizontalFadingEdgeEnabled = !tablet; setFadingEdgeLength(dp(24)); addView(content)
    }
    private fun dismissOverlay() {
        toneLayer.visibility = GONE; dim.visibility = GONE
        heldView?.background = heldBackground; heldView = null; heldBackground = null
    }
    private fun showDim(anchor: View? = null) {
        dismissOverlay()
        dim.setBackgroundColor(if (app.tailorkeys.contrastRatio(palette.background, Color.BLACK) < 4.5) 0x66000000 else 0x2E000000)
        dim.visibility = VISIBLE
        if (anchor != null) {
            heldView = anchor; heldBackground = anchor.background
            val at = IntArray(2); val origin = IntArray(2); anchor.getLocationOnScreen(at); getLocationOnScreen(origin)
            val rect = android.graphics.RectF((at[0]-origin[0]).toFloat(), (at[1]-origin[1]).toFloat(), (at[0]-origin[0]+anchor.width).toFloat(), (at[1]-origin[1]+anchor.height).toFloat())
            dim.background = object : android.graphics.drawable.Drawable() {
                private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun draw(canvas: Canvas) {
                    val path = android.graphics.Path().apply { addRoundRect(rect,dp(12).toFloat(),dp(12).toFloat(),android.graphics.Path.Direction.CW) }
                    canvas.save(); canvas.clipOutPath(path); canvas.drawColor(if (app.tailorkeys.contrastRatio(palette.background,Color.BLACK)<4.5) 0x66000000 else 0x2E000000); canvas.restore()
                    paint.style=Paint.Style.STROKE; paint.strokeWidth=dp(2).toFloat(); paint.color=palette.accent; val inset=android.graphics.RectF(rect).apply { inset(d,d) }; canvas.drawRoundRect(inset,dp(12).toFloat(),dp(12).toFloat(),paint)
                }
                override fun setAlpha(alpha: Int) = Unit
                override fun setColorFilter(filter: android.graphics.ColorFilter?) = Unit
                @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); repository.prefs.registerOnSharedPreferenceChangeListener(prefListener) }
    override fun onDetachedFromWindow() { repository.prefs.unregisterOnSharedPreferenceChangeListener(prefListener); voice.cancel(); super.onDetachedFromWindow() }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in listOf(InsertPrefs.KAOMOJI, InsertPrefs.VOICE, InsertPrefs.KEEP, InsertPrefs.HISTORY, InsertPrefs.TONE)) post { refresh(true) }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w,h,oldw,oldh); if (w != oldw && w > 0) post { refresh(true) } }
    fun show() {
        if (editor == "Note") retainDraft(); restoreKeys(); if (editor != null) typing(false, 0); editor = null; field = null; session++
        startVoiceOnRender = false; visibility = VISIBLE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        refresh(renderedEditor)
        if (section in listOf("Emoji", "Kaomoji") && editor == null) {
            activeCategory = "Recent"
            pages.removeAllViews(); categories.removeAllViews(); categoryButtons.clear(); renderSymbols()
            if (section == "Emoji") grid.setSelection(0)
        }
        if (section == "Clipboard") clipboardChanged()
        if (section == "Voice") { pages.removeAllViews(); renderVoice() }
    }
    fun hide() { voice.cancel(); listening = false; session++; dismissOverlay(); if (editor == "Note") retainDraft(); restoreKeys(); editor = null; field = null; typing(false, 0); visibility = INVISIBLE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
    fun refresh(force: Boolean = false) {
        if (visibility != VISIBLE) return
        val style = "${settings.themeId()}:${CustomTheme.encode(settings.customThemes())}:${resources.configuration.uiMode}:${settings.int(Prefs.CORNER_RADIUS)}:" +
            listOf(InsertPrefs.KAOMOJI, InsertPrefs.TONE, InsertPrefs.HISTORY, InsertPrefs.RECENTS, InsertPrefs.KEEP, InsertPrefs.VOICE, InsertPrefs.DEVICE_ONLY).joinToString { repository.prefs.all[it].toString() }
        if (!force && lastStyle == style) return
        if (section == "Kaomoji" && !repository.prefs.getBoolean(InsertPrefs.KAOMOJI, true)) { restoreKeys(); editor = null; field = null; typing(false, 0) }
        if (section == "Voice" && !repository.prefs.getBoolean(InsertPrefs.VOICE,true)) { voice.cancel(); listening=false }
        lastStyle = style; palette = Palette.of(context, settings)
        if (editor != null) renderEditor() else renderPanel()
    }
    private fun clearBody() { body.removeAllViews(); top.removeAllViews(); categories.removeAllViews(); pages.removeAllViews(); footer.removeAllViews(); sectionButtons.clear(); categoryButtons.clear(); dismissOverlay() }
    private fun renderPanel() {
        renderedEditor = false
        if ((section == "Voice" && !repository.prefs.getBoolean(InsertPrefs.VOICE, true)) || (section == "Kaomoji" && !repository.prefs.getBoolean(InsertPrefs.KAOMOJI, true))) { section = "Emoji"; activeCategory = "Recent" }
        isClickable = true; clearBody(); setBackgroundColor(palette.background)
        body.setPadding(dp(if (tablet) 12 else 4), dp(if (tablet) 10 else 6), dp(if (tablet) 12 else 4), dp(if (tablet) 4 else 2))
        body.layoutParams.height = -1
        val searchName = when (section) { "Kaomoji" -> "Search kaomoji"; "Clipboard" -> "Search clips and notes"; else -> "Search emoji" }
        top.addView(button(searchName, searchName) { if (section == "Voice") { voice.cancel(); listening=false; section = "Emoji" }; startSearch() }.apply { background = shape(fill); gravity = Gravity.CENTER_VERTICAL; setPadding(dp(36),0,dp(12),0); icon(this,"Search",sub,true); setTextColor(sub); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        val switch = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; background = shape(fill) }
        sections.filter { it != "Voice" || repository.prefs.getBoolean(InsertPrefs.VOICE, true) }.filter { it != "Kaomoji" || repository.prefs.getBoolean(InsertPrefs.KAOMOJI, true) }.forEach { name ->
            val b = button(if (name == "Kaomoji") "^_^" else "", name, section == name) {
                voice.cancel(); listening = false; startVoiceOnRender = name == "Voice"; section = name; activeCategory = "Recent"; repository.prefs.edit().putString(InsertPrefs.SECTION, name).apply(); renderPanel()
            }
            b.minimumHeight = 0; b.background = if (section == name) android.graphics.drawable.InsetDrawable(shape(palette.key, radius = 9), dp(2), dp(3), dp(2), dp(3)) else null
            b.elevation = if (section == name) d else 0f
            if (name != "Kaomoji") { b.setPadding(0,0,0,0); icon(b,name,palette.text) }
            sectionButtons[name] = b; switch.addView(b, LinearLayout.LayoutParams(dp(if (tablet) 56 else 44), dp(44)))
        }
        top.addView(switch, LinearLayout.LayoutParams(-2, dp(44)))
        body.addView(top, LinearLayout.LayoutParams(-1, dp(44)))
        body.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(if (tablet) 6 else 4); bottomMargin = dp(if (tablet) 6 else 4) })
        footer.addView(button("ABC", "Back to keys") { close() }, LinearLayout.LayoutParams(dp(if (tablet) 64 else 52), dp(44)))
        footer.addView(categories, LinearLayout.LayoutParams(0, dp(44), 1f))
        footer.addView(button("", "Backspace") { backspace() }.apply { icon(this,"Backspace",palette.text) }, LinearLayout.LayoutParams(dp(if (tablet) 64 else 52), dp(44)))
        body.addView(footer, LinearLayout.LayoutParams(-1, dp(44)))
        when (section) { "Emoji", "Kaomoji" -> renderSymbols(); "Clipboard" -> renderClipboard(); "Voice" -> renderVoice() }
    }
    private fun recentSymbols(which: String): List<InsertSymbol> {
        if (!repository.prefs.getBoolean(InsertPrefs.RECENTS, true)) return emptyList()
        val byText = if (which == "Emoji") emojiByText else kaomojiByText
        return repository.recent(which).mapNotNull { byText[it] ?: if (which == "Emoji" && emoji == null) InsertSymbol(it, "Recent emoji", "Recent") else null }.filter { which != "Emoji" || emoji == null || supported(it.text) }
    }
    private fun renderSymbols() {
        val all = if (section == "Emoji") cachedEmoji else kaomoji
        val cats = listOf("Recent") + if (section == "Emoji") categoryIcons.keys.toList() else all.map { it.category }.distinct()
        val tabs = LinearLayout(context).apply { gravity=Gravity.CENTER }
        cats.forEach { cat ->
            val names = listOf("Recent", "Smileys & Emotion", "People & Body", "Animals & Nature", "Food & Drink", "Travel & Places", "Activities", "Objects", "Symbols", "Flags")
            val icon = listOf("◷", "☺", "♙", "♧", "♨", "▱", "⚽", "♧", "♡", "⚑").getOrElse(names.indexOf(cat)) { cat }
            val b = button(if (section == "Emoji") icon else cat, cat, cat == activeCategory) {
                activeCategory = cat; markCategories()
                if (section == "Emoji") { val index = if (cat == "Recent") 0 else symbols.indexOfFirst { it.category == cat }.coerceAtLeast(0); grid.setSelection(index) }
                else showKaomoji(if (cat == "Recent") kaomojiWithRecents() else all.filter { it.category == cat })
            }
            categoryButtons[cat] = b; tabs.addView(b, LinearLayout.LayoutParams(if (section == "Emoji") dp(if (tablet) 48 else 44) else -2, dp(44)))
            b.background = categoryShape(cat == activeCategory)
            if (section == "Emoji") { b.text=""; b.setPadding(0,0,0,0); icon(b,if (cat == "Recent") "Recent" else categoryIcons.getValue(cat),if (cat == activeCategory) palette.text else sub) }
        }
        categories.addView(bottomScroll(tabs), LinearLayout.LayoutParams(-1, dp(44)))
        if (section == "Emoji") {
            symbols = emojiCells()
            (grid.parent as? ViewGroup)?.removeView(grid)
            grid.numColumns = emojiColumns(availableWidth(), tablet); grid.columnWidth = dp(if (tablet) 52 else 44)
            // Directly in the page at full width: a HorizontalScrollView measures with an unbounded width,
            // so fixed-width cells retain their touch target.
            pages.addView(grid, LayoutParams(-1, -1)); adapter.notifyDataSetChanged()
        } else showKaomoji(if (activeCategory == "Recent") kaomojiWithRecents() else all.filter { it.category == activeCategory })
    }
    private fun emojiCells(): List<InsertSymbol> {
        val cols = emojiColumns(availableWidth(), tablet)
        val out = ArrayList<InsertSymbol>()
        fun group(category: String, entries: List<InsertSymbol>) {
            if (entries.isEmpty()) return
            while (out.size % cols != 0) out.add(InsertSymbol("", "", out.last().category, "spacer"))
            val title = when (category) { "Recent" -> "Frequently used"; "Smileys & Emotion" -> "Smileys & people"; "People & Body" -> "People"; "Animals & Nature" -> "Animals"; "Food & Drink" -> "Food"; "Travel & Places" -> "Travel"; else -> category }
            repeat(cols) { out.add(InsertSymbol("", if (it == 0) title else "", category)) }
            out.addAll(entries.map { it.copy(category = category) })
        }
        group("Recent", recentSymbols("Emoji"))
        cachedEmoji.groupBy { it.category }.forEach { (cat, entries) -> group(cat, entries) }
        return out
    }
    private fun availableWidth() = ((width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) - body.paddingLeft - body.paddingRight) / d
    private fun markCategories() { categoryButtons.forEach { (name, b) ->
        b.isSelected = name == activeCategory
        if (b.isSelected && b.width > 0) b.requestRectangleOnScreen(android.graphics.Rect(0,0,b.width,b.height),true)
        // setTypeface(typeface, NORMAL) keeps an already-bold typeface bold, so set the face itself.
        b.background = categoryShape(b.isSelected); b.setTextColor(if (b.isSelected) palette.text else sub); b.typeface = if (b.isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        if (section == "Emoji") icon(b,if (name == "Recent") "Recent" else categoryIcons.getValue(name),if (b.isSelected) palette.text else sub)
    } }
    /** Pinned faces first, then recents. The Recent tab shows only these, like every other category. */
    private fun kaomojiWithRecents(): List<InsertSymbol> =
        (repository.pinned("Kaomoji").mapNotNull { kaomojiByText[it] } + recentSymbols("Kaomoji")).distinctBy { it.text }
    private fun showKaomoji(items: List<InsertSymbol>) {
        pages.removeAllViews()
        val flow = ChipFlow(context, dp(8))
        items.forEach { entry ->
            val actions = listOf((if (entry.text in repository.pinned("Kaomoji")) "Unpin from Recent" else "Pin to Recent") to {
                repository.togglePin("Kaomoji", entry.text); activeCategory = "Recent"; markCategories(); showKaomoji(kaomojiWithRecents())
            }, "Copy" to { context.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Kaomoji", entry.text)) })
            flow.addView(button(entry.text, entry.name) { use(entry) }.apply {
                textSize = if (tablet) 17f else 16f; minimumWidth = dp(64); setPadding(dp(14),0,dp(14),0); background = shape(palette.key)
                setOnLongClickListener { showMenu(this, actions); true }; accessibleActions(this, actions)
            }, ViewGroup.LayoutParams(-2, dp(44)))
        }
        val page=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        page.addView(sectionHeader(activeCategory),LinearLayout.LayoutParams(-1,dp(26)))
        if (items.isEmpty()) page.addView(label("Faces you use or pin show up here.").apply { setTextColor(sub) },LinearLayout.LayoutParams(-1,dp(44)))
        page.addView(ScrollView(context).apply { addView(flow) },LinearLayout.LayoutParams(-1,0,1f))
        pages.addView(page,LayoutParams(-1,-1))
    }
    private fun accessibleActions(view: View, actions: List<Pair<String, () -> Unit>>) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) { super.onInitializeAccessibilityNodeInfo(host, info); actions.forEachIndexed { i, a -> info.addAction(AccessibilityNodeInfo.AccessibilityAction(0x01000000+i, a.first)) } }
            override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean {
                val index = action-0x01000000
                if (index in actions.indices) { dismissOverlay(); actions[index].second(); return true }; return super.performAccessibilityAction(host, action, arguments)
            }
        }
    }
    private fun showMenu(anchor: View, actions: List<Pair<String, () -> Unit>>) {
        showDim(anchor); toneLayer.removeAllViews(); toneLayer.background = shape(palette.key, radius = 14)
        actions.forEachIndexed { i, action ->
            if (i > 0) toneLayer.addView(View(context).apply { setBackgroundColor(hair) }, LinearLayout.LayoutParams(-1,1).apply { marginStart = dp(14) })
            toneLayer.addView(button(action.first) { dismissOverlay(); action.second() }.apply { gravity = Gravity.CENTER_VERTICAL; if (action.first == "Delete") setTextColor(danger) }, LinearLayout.LayoutParams(-1,dp(44)))
        }
        val point = IntArray(2); val origin = IntArray(2); anchor.getLocationOnScreen(point); getLocationOnScreen(origin)
        val menuWidth = minOf(dp(220), width-dp(8)); val menuHeight = dp(44*actions.size)+actions.size-1
        (toneLayer.layoutParams as LayoutParams).apply {
            width = menuWidth; height = -2; leftMargin = (point[0]-origin[0]).coerceIn(0,(this@InsertPanel.width-menuWidth).coerceAtLeast(0))
            val below = point[1]-origin[1]+anchor.height+dp(6)
            topMargin = (if (below+menuHeight <= this@InsertPanel.height) below else point[1]-origin[1]-menuHeight-dp(6)).coerceAtLeast(0)
        }
        toneLayer.visibility = VISIBLE
    }
    private val danger get() = readable(if (app.tailorkeys.contrastRatio(palette.background, Color.BLACK) < 4.5) 0xFFFF7777.toInt() else 0xFFB3261E.toInt(), palette.key)
    private fun use(entry: InsertSymbol) {
        val which = section
        repository.insertSymbol(which, entry, insert)
        if (editor != null || section != which) return
        // Refresh recents in place only while they are on screen; elsewhere the grid stays where the
        // user is picking, and recents are rebuilt the next time the panel opens.
        if (which == "Emoji" && symbols.getOrNull(grid.firstVisiblePosition)?.category == "Recent") {
            symbols = emojiCells(); adapter.notifyDataSetChanged(); grid.setSelection(0)
        } else if (which == "Kaomoji" && activeCategory == "Recent") showKaomoji(kaomojiWithRecents())
    }
    private fun showTones(anchor: View, entry: InsertSymbol): Boolean {
        val variants = (emoji ?: return false).variants(entry.text).withIndex().filter { supported(it.value.text) }
        if (variants.size < 2) return false
        showDim(); toneLayer.removeAllViews(); toneLayer.background = shape(palette.key, radius = 16)
        val default = repository.prefs.getInt(InsertPrefs.TONE, 0)
        val row = LinearLayout(context)
        val cell = dp(52).coerceAtMost((width - dp(8)) / variants.size)
        variants.forEach { (index, e) -> row.addView(button(e.text, "${e.name}, ${InsertPrefs.toneNames[index]}${if (index == default) ", default" else ""}") { use(e); dismissOverlay() }.apply {
            textSize = 32f; setPadding(0,0,0,0); background = if (index == default) shape(fillHi, true) else null
        }, LinearLayout.LayoutParams(cell, dp(52))) }
        toneLayer.addView(row)
        toneLayer.addView(label("✓  ${InsertPrefs.toneNames.getOrElse(default) { "Default" }} is your default · change it in Settings", 12f).apply { setTextColor(sub); setPadding(dp(10),0,dp(10),0) }, LinearLayout.LayoutParams(-1,dp(32)))
        val point = IntArray(2); val origin = IntArray(2); anchor.getLocationOnScreen(point); getLocationOnScreen(origin)
        (toneLayer.layoutParams as LayoutParams).apply { width = cell * variants.size; leftMargin = (point[0]-origin[0]-width/2+anchor.width/2).coerceIn(dp(8),(this@InsertPanel.width-width-dp(8)).coerceAtLeast(dp(8))); topMargin = (point[1]-origin[1]-dp(90)).coerceAtLeast(0) }
        toneLayer.visibility = VISIBLE
        anchor.parent?.requestDisallowInterceptTouchEvent(true)
        // Lifting before the finger reaches the popover inserts the held emoji as shown (the default
        // tone); lifting after leaving the popover cancels.
        var entered = false
        anchor.setOnTouchListener { _, event ->
            if (event.actionMasked in listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
                val loc = IntArray(2); row.getLocationOnScreen(loc)
                val chosen = if (event.rawY >= loc[1] && event.rawY < loc[1]+row.height && event.rawX >= loc[0] && event.rawX < loc[0]+row.width) ((event.rawX-loc[0])/cell).toInt().takeIf { it in variants.indices } ?: -1 else -1
                if (chosen >= 0) entered = true
                for (i in 0 until row.childCount) row.getChildAt(i).background = if (i == chosen || variants[i].index == default) shape(fillHi, variants[i].index == default) else null
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (chosen >= 0) use(variants[chosen].value) else if (!entered) use(defaultSymbol(entry))
                    dismissOverlay(); anchor.setOnTouchListener(null); anchor.parent?.requestDisallowInterceptTouchEvent(false)
                }
            } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) { dismissOverlay(); anchor.setOnTouchListener(null); anchor.parent?.requestDisallowInterceptTouchEvent(false) }
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
    private fun startSearch() { voice.cancel(); listening=false; if (section == "Emoji") gridPosition = grid.firstVisiblePosition; prepareKeys(); editor = "Search"; session++; renderEditor() }
    private fun edit(item: ClipItem? = null) {
        prepareKeys(); editingItem = item; editor = "Note"; session++
        val draft = noteDrafts[item?.id ?: "new"]
        draftTitle = draft?.first ?: item?.title.orEmpty()
        draftText = draft?.second ?: item?.text.orEmpty()
        renderEditor()
    }
    private fun input(text: String, hint: String, changed: (String) -> Unit): EditText = EditText(context).apply {
        setText(text); setSelection(length()); this.hint = hint; textSize = 15f; setTextColor(palette.text); setHintTextColor(palette.hint)
        showSoftInputOnFocus = false; background = null; setPadding(0, 0, 0, 0); minimumHeight = dp(44); if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable?.setTint(palette.accent)
        addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { changed(s.toString()) }; override fun afterTextChanged(s: Editable?) = Unit })
        setOnFocusChangeListener { _, focus -> if (focus) { field = this; session++ } }
    }
    private fun renderEditor() {
        renderedEditor = true
        isClickable = false
        clearBody(); setBackgroundColor(android.graphics.Color.TRANSPARENT); body.layoutParams.height = -2
        val stripHeight = 94
        body.setPadding(dp(if (tablet) 12 else 4),0,dp(if (tablet) 12 else 4),0)
        typing(true, stripHeight)
        val sheet = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.background) }
        body.addView(sheet, LinearLayout.LayoutParams(-1, dp(stripHeight)))
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }; sheet.addView(row, LinearLayout.LayoutParams(-1, dp(44)))
        row.addView(button("Cancel", "Back to ${section.lowercase()}") {
            val search = editor == "Search"
            if (editor == "Note") retainDraft(); restoreKeys(); editor = null; field = null; typing(false, 0); renderPanel()
            // setSelectionFromTop is ignored by GridView in touch mode; setSelection restores the row.
            if (search && section == "Emoji") grid.post { grid.setSelection(gridPosition) }
        }.apply { setTextColor(palette.smallAccent) }, LinearLayout.LayoutParams(dp(72), dp(44)))
        if (editor == "Search") {
            val pill = FrameLayout(context).apply { background = shape(fill) }
            val search = input(query, "Search ${section.lowercase()}") { query = it; renderResults(sheet) }.apply {
                isSingleLine = true; contentDescription = "Search ${section.lowercase()}"; background = null; minimumHeight = dp(44); setPadding(dp(36),0,dp(44),0)
                if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable?.setTint(palette.accent)
            }
            pill.addView(search, LayoutParams(-1, dp(44)))
            pill.addView(label("⌕", 20f).apply { gravity = Gravity.CENTER; setTextColor(sub); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LayoutParams(dp(36), dp(44), Gravity.START))
            pill.addView(button("×", "Clear search") { search.setText("") }, LayoutParams(dp(44), dp(44), Gravity.END))
            row.addView(pill, LinearLayout.LayoutParams(0, dp(44), 1f)); field = search
            renderResults(sheet); search.requestFocus()
        } else {
            row.addView(label(if (editingItem == null) "New note" else "Edit ${if (editingItem?.note != false) "note" else "clip"}",15f).apply { gravity=Gravity.CENTER; setTypeface(typeface,Typeface.BOLD) }, LinearLayout.LayoutParams(0,dp(44),1f))
            row.addView(primary("Save") {
                retainDraft(); repository.change { store -> val item=editingItem; if (item != null) store.edit(item.id,draftText,draftTitle) else store.note(draftTitle,draftText) }
                noteDrafts.remove(editingItem?.id ?: "new"); draftText=""; draftTitle=""; editingItem=null; titleField=null; textField=null
                restoreKeys(); editor=null; field=null; typing(false,0); renderPanel()
            }.apply { background=android.graphics.drawable.InsetDrawable(shape(palette.accent),0,dp(4),0,dp(4)) }, LinearLayout.LayoutParams(dp(72),dp(44)))
            titleField=null; textField=null
            val fields=LinearLayout(context)
            fun noteField(title: Boolean): View {
                val limit=if (title) ClipboardStore.TITLE_MAX else editorLimit
                val container=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),0,dp(12),0); background=shape(fill) }
                val caption=label(if (title) "Title" else "Text",12f).apply { setTextColor(sub) }
                val counter=label("",12f).apply { setTextColor(sub); gravity=Gravity.CENTER_VERTICAL or Gravity.END }
                if (title) titleCounter=counter else textCounter=counter
                val value=input((if (title) draftTitle else draftText).take(limit),"") { if (title) draftTitle=it else draftText=it; updateCounters() }.apply {
                    filters=arrayOf(android.text.InputFilter.LengthFilter(limit)); contentDescription=if (title) "Note title" else "Note text"; isSingleLine=true
                    setOnFocusChangeListener { _, focused ->
                        container.background=shape(fill,focused); if (focused) { field=this; session++ }; updateCounters()
                    }
                }
                if (title) titleField=value else textField=value
                container.addView(caption,LinearLayout.LayoutParams(-2,-1).apply { marginEnd=dp(8) })
                container.addView(value,LinearLayout.LayoutParams(0,dp(44),1f)); container.addView(counter,LinearLayout.LayoutParams(-2,-1).apply { marginStart=dp(8) })
                return container
            }
            if (tablet) fields.addView(noteField(true),LinearLayout.LayoutParams(0,dp(44),1f).apply { marginEnd=dp(6) })
            fields.addView(noteField(false),LinearLayout.LayoutParams(0,dp(44),if (tablet) 2f else 1f))
            sheet.addView(fields,LinearLayout.LayoutParams(-1,dp(44)).apply { topMargin=dp(6) }); field=textField; textField?.requestFocus(); updateCounters()
        }
    }
    private val editorLimit get() = if (editingItem?.note == false) ClipboardStore.CLIP_MAX else ClipboardStore.NOTE_MAX
    private fun updateCounters() {
        titleCounter?.text = if (titleField?.hasFocus() == true) "${draftTitle.length}/40" else ""
        textCounter?.text = "${java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(draftText.length)}/${java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(editorLimit)}"
        textCounter?.setTextColor(if (draftText.length >= editorLimit) readable(danger,fill) else sub)
    }
    private fun retainDraft() {
        draftTitle=titleField?.text?.toString() ?: draftTitle; draftText=textField?.text?.toString() ?: draftText
        noteDrafts[editingItem?.id ?: "new"]=draftTitle to draftText
    }
    private fun renderResults(sheet: LinearLayout) {
        if (sheet.childCount > 1) sheet.removeViewAt(1)
        val row = LinearLayout(context)
        val result = when (section) { "Emoji" -> emoji?.search(query).orEmpty().filter { supported(it.text) }; "Kaomoji" -> kaomoji.filter { (it.name + it.category).contains(query, true) }; else -> emptyList() }.take(40)
        if (section == "Clipboard") {
            repository.searchableItems().filter { (it.title + it.text).contains(query, true) }.take(20).forEach { clip -> row.addView(button(clip.title.ifBlank { clip.text.take(30) }, clip.text) { insert(clip.text) }) }
        } else result.forEach { e -> val shown = if (section == "Emoji") defaultSymbol(e) else e; row.addView(button(shown.text, shown.name) { use(shown) }.apply { textSize = if (section == "Emoji") 28f else 16f }, LinearLayout.LayoutParams(if (section == "Emoji") dp(48) else -2, dp(44))) }
        if (row.childCount == 0) {
            sheet.addView(label("No ${section.lowercase()} for “$query”", 14f).apply { gravity = Gravity.CENTER; setTextColor(sub) }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) }); return
        }
        val scroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        sheet.addView(scroll, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
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
    fun clipboardChanged() { if (visibility == VISIBLE && section == "Clipboard" && editor == null) renderPanel() }
    private fun sectionHeader(title: String, newNote: Boolean = false): View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title.uppercase(), 12f).apply { setTextColor(sub); letterSpacing = .033f; setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, dp(30), 1f))
        if (newNote) addView(button("+ New", "Create note") { edit() }.apply { setTextColor(palette.smallAccent) }, LinearLayout.LayoutParams(-2,dp(44)))
    }
    private fun primary(text: String, action: () -> Unit) = button(text, action = action).apply { background = shape(palette.accent); setTextColor(palette.onAccent); setTypeface(typeface, Typeface.BOLD) }
    private fun stateView(iconName: String, title: String, message: String, actions: List<Pair<String, () -> Unit>> = emptyList()): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(16),dp(8),dp(16),dp(8))
        addView(label("", 20f).apply { gravity=Gravity.CENTER; background=shape(fill,radius=22); icon(this,iconName,palette.text); importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(44),dp(44)))
        addView(label(title,17f).apply { gravity = Gravity.CENTER; setTypeface(typeface,Typeface.BOLD) }, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(10); bottomMargin = dp(8) })
        addView(label(message,14f).apply { gravity = Gravity.CENTER; setTextColor(sub) }, LinearLayout.LayoutParams(-1,-2))
        val buttons = LinearLayout(context).apply { gravity = Gravity.CENTER }
        actions.forEachIndexed { i, a -> buttons.addView(if (i == 0) primary(a.first, a.second) else button(a.first, action = a.second).apply { background = shape(fill) }, LinearLayout.LayoutParams(-2,dp(44)).apply { marginStart = dp(4); marginEnd = dp(4) }) }
        addView(buttons, LinearLayout.LayoutParams(-1,dp(44)).apply { topMargin = dp(12) })
    }
    private fun renderClipboard() {
        val store = repository.store(); repository.save(store)
        val columns = LinearLayout(context)
        fun column(isNotes: Boolean): View {
            val enabled = repository.prefs.getBoolean(InsertPrefs.HISTORY, false)
            val items = if (isNotes) store.notes() else if (enabled) store.clips() else emptyList()
            if (!isNotes) {
                val state = clipboardPanelState(enabled, items.isNotEmpty(), repository.prefs.getBoolean(InsertPrefs.HAD_CLIPS, false), repository.prefs.getBoolean("insert_first_use", false))
                if (state == ClipboardPanelState.HISTORY_OFF) return stateView("Clipboard", "Clipboard history is off", "Notes still work: they are your own text, not history.", listOf("Turn on" to { repository.prefs.edit().putBoolean(InsertPrefs.HISTORY,true).apply(); clipboardChanged() }, "Settings" to { openSettings() }))
                if (state == ClipboardPanelState.FIRST_USE) return stateView("Lock", "Your clipboard, kept on this device", "Copied text shows here and clears after ${repository.keep.title}. Pins and notes stay until you remove them. Password fields are never kept.", listOf("Got it" to { repository.prefs.edit().putBoolean("insert_first_use",true).apply(); clipboardChanged() }))
                if (state == ClipboardPanelState.EMPTY) return stateView("Clipboard", "Nothing copied yet", "Copy text and it shows up here.")
            }
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            fun group(title: String, entries: List<ClipItem>, newNote: Boolean = false) {
                list.addView(sectionHeader(title,newNote))
                val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = shape(palette.key); clipToOutline = true }
                entries.forEachIndexed { index, item ->
                    if (index > 0) group.addView(View(context).apply { setBackgroundColor(hair) }, LinearLayout.LayoutParams(-1,1).apply { marginStart = dp(14) })
                    val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14),0,dp(14),0); isFocusable = true; isClickable = true }
                    if (item.pinned) row.addView(ImageView(context).apply { setImageDrawable(InsertIcon("Pin",palette.text)); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(16),dp(16)).apply { marginEnd = dp(8) })
                    if (isNotes && item.title.isNotBlank()) row.addView(label(item.title,15f).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; setTypeface(typeface,Typeface.BOLD); maxWidth=dp(if (tablet) 170 else 130) }, LinearLayout.LayoutParams(-2,-1).apply { marginEnd=dp(6) })
                    row.addView(label(item.text.replace('\n',' '),15f).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END; if (isNotes) setTextColor(readable(palette.hint,palette.key)) },LinearLayout.LayoutParams(0,-1,1f))
                    val age = android.text.format.DateUtils.getRelativeTimeSpanString(item.at,System.currentTimeMillis(),60_000).toString()
                    if (!isNotes) row.addView(label(age,13f).apply { setTextColor(readable(palette.hint,palette.key)); maxLines=1 }, LinearLayout.LayoutParams(-2,-1).apply { marginStart=dp(8) })
                    row.contentDescription = "${if (item.pinned) "Pinned, " else ""}${item.title}, ${item.text}, $age"
                    row.setOnClickListener { insert(item.text) }
                    val actions = clipActions(item); row.setOnLongClickListener { showMenu(row,actions); true }; accessibleActions(row,actions)
                    group.addView(row,LinearLayout.LayoutParams(-1,dp(if (tablet) 48 else 44)))
                }
                list.addView(group,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
            }
            if (isNotes) { group("Notes",items,true); if (items.isEmpty()) list.addView(label("No notes yet. Write one with + New, or save a clip as a note.").apply { setTextColor(sub) }) }
            else { if (items.any { it.pinned }) group("Pinned",items.filter { it.pinned }); if (items.any { !it.pinned }) group("Recent",items.filter { !it.pinned }) }
            return ScrollView(context).apply { addView(list) }
        }
        if (tablet) { columns.addView(column(false),LinearLayout.LayoutParams(0,-1,1f).apply { marginEnd=dp(14) }); columns.addView(column(true),LinearLayout.LayoutParams(0,-1,1f)) }
        else columns.addView(column(notes),LinearLayout.LayoutParams(-1,-1))
        pages.addView(columns,LayoutParams(-1,-1))
        if (tablet) categories.addView(label("",12f).apply {
            text = if (repository.keep == ClipKeep.FOREVER) "On this device · kept until you clear it" else "On this device · clears after ${repository.keep.title}"
            setTextColor(sub); gravity=Gravity.CENTER; compoundDrawablePadding=dp(6); setCompoundDrawables(InsertIcon("Lock",sub).apply { setBounds(0,0,dp(14),dp(14)) },null,null,null)
        }.also { categories.gravity=Gravity.CENTER },LinearLayout.LayoutParams(-2,dp(44)))
        else {
            val switch = LinearLayout(context).apply { background=shape(fill); gravity=Gravity.CENTER; setPadding(dp(2),0,dp(2),0) }
            listOf("Clips","Notes").forEachIndexed { i,name -> switch.addView(button(name,selected=notes == (i == 1)) { notes=i == 1; clipboardChanged() }.apply { background=if (notes == (i == 1)) android.graphics.drawable.InsetDrawable(shape(palette.key,radius=9),0,dp(5),0,dp(5)) else null },LinearLayout.LayoutParams(dp(72),dp(44))) }
            categories.gravity=Gravity.CENTER; categories.addView(switch,LinearLayout.LayoutParams(-2,dp(44)))
        }
    }
    private fun clipActions(item: ClipItem): List<Pair<String, () -> Unit>> =
        (listOf(if (item.pinned) "Unpin" else "Pin", "Edit") + (if (item.note) emptyList() else listOf("Save as note")) + "Delete").map { title -> title to { performClip(title,item) } }
    private fun performClip(action: String,item: ClipItem) {
        when (action) {
            "Pin","Unpin" -> repository.change { it.pin(item.id) }
            "Edit" -> edit(item)
            "Save as note" -> repository.change { it.saveAsNote(item.id) }
            "Delete" -> {
                val store=repository.store(); store.delete(item.id); repository.save(store); clipboardChanged()
                showUndo(item,store); return
            }
        }
        if (editor == null) clipboardChanged()
    }
    private fun showUndo(item: ClipItem,store: ClipboardStore) {
        undoToast?.let { removeView(it) }
        val toast=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL; background=shape(palette.text,radius=14); setPadding(dp(16),0,dp(4),0); elevation=dp(8).toFloat() }
        toast.addView(label("${if (item.note) "Note" else "Clip"} deleted",15f).apply { setTextColor(palette.key) },LinearLayout.LayoutParams(0,dp(48),1f))
        toast.addView(button("Undo") { if (store.undo()) repository.change { it.restore(item) }; removeView(toast); if (undoToast === toast) undoToast=null; clipboardChanged() }.apply { setTextColor(readable(palette.accent,palette.text,palette.key)); setTypeface(typeface,Typeface.BOLD) },LinearLayout.LayoutParams(-2,dp(48)))
        undoToast=toast; addView(toast,LayoutParams(minOf(width-dp(16),dp(400)),dp(48),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=dp(54) })
        postDelayed({ removeView(toast); if (undoToast === toast) undoToast=null },6_000)
    }
    private fun renderVoice() {
        val col=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(12),0,dp(12),0) }
        if (!voice.permitted) col.addView(stateView("Voice","Allow the microphone","Keyboards cannot ask for it here. TailorKeys opens and asks once.",listOf("Allow in TailorKeys" to { openSettings(requestMicrophone=true) })),LinearLayout.LayoutParams(-1,-1))
        else if (!voice.available) col.addView(stateView("Voice","No speech recognizer","This device has no recognizer TailorKeys can use. Switch to a voice keyboard instead.",listOf("Use voice keyboard" to { pickIme() })),LinearLayout.LayoutParams(-1,-1))
        else if (voice.deviceOnly && !voice.onDevice) col.addView(stateView("Voice","On-device recognition unavailable","Change Voice settings to allow online recognition, or use a voice keyboard.",listOf("Voice settings" to { openSettings() },"Use voice keyboard" to { pickIme() })),LinearLayout.LayoutParams(-1,-1))
        else {
            (voiceText.parent as? ViewGroup)?.removeView(voiceText); (voiceMode.parent as? ViewGroup)?.removeView(voiceMode); (level.parent as? ViewGroup)?.removeView(level)
            voiceMode.text=if (voice.onDevice) "On device" else "Online"; voiceMode.textSize=12f; voiceMode.setTextColor(sub); voiceMode.gravity=Gravity.CENTER; voiceMode.background=shape(fill,radius=13); voiceMode.setPadding(dp(12),0,dp(12),0); voiceMode.compoundDrawablePadding=dp(6)
            voiceMode.setCompoundDrawables(InsertIcon(if (voice.onDevice) "Device" else "Cloud",sub).apply { setBounds(0,0,dp(14),dp(14)) },null,null,null)
            col.addView(voiceMode,LinearLayout.LayoutParams(-2,dp(26)))
            voiceText.textSize=22f; voiceText.gravity=Gravity.CENTER; voiceText.setTextColor(palette.text); voiceText.background=null; voiceText.setPadding(0,0,0,0); voiceText.maxLines=4; voiceText.ellipsize=android.text.TextUtils.TruncateAt.END
            if (!listening) { voiceText.text="Tap Start and speak."; previousVoiceText="" }
            col.addView(voiceText,LinearLayout.LayoutParams(minOf(dp(560),(availableWidth()*d).toInt()-dp(24)),-2).apply { topMargin=dp(16); bottomMargin=dp(12) })
            col.addView(level,LinearLayout.LayoutParams(minOf(dp(240),(availableWidth()*d).toInt()-dp(24)),dp(44)))
            val controls=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
            controls.addView(button("Cancel") { voice.cancel(); listening=false; level.update(0f,palette.accent,false); voiceButton?.update(0f,palette.accent,palette.onAccent,false); voiceText.text="Cancelled" },LinearLayout.LayoutParams(dp(88),dp(44)))
            voiceButton=VoiceStopView(context).apply { isFocusable=true; contentDescription=if (listening) "Stop listening" else "Start voice recognition"; setOnClickListener { if (listening) voice.stop() else voice.start(settings.currentLanguage()) }; update(0f,palette.accent,palette.onAccent,listening) }
            controls.addView(voiceButton,LinearLayout.LayoutParams(dp(96),dp(96)))
            controls.addView(View(context),LinearLayout.LayoutParams(dp(88),dp(1))); col.addView(controls,LinearLayout.LayoutParams(-2,dp(96)))
        }
        pages.addView(ScrollView(context).apply { isFillViewport=true; addView(col) },LayoutParams(-1,-1))
        val start=startVoiceOnRender; val token=session; startVoiceOnRender=false
        if (start && voice.permitted && voice.available && (!voice.deviceOnly || voice.onDevice)) post { if (session == token && visibility == VISIBLE && section == "Voice" && editor == null && !listening) voice.start(settings.currentLanguage()) }
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

/** Variable-width chips retain their full text and 44dp touch height. */
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

private fun mix(a: Int, b: Int, fraction: Float): Int {
    fun c(shift: Int) = (((a ushr shift and 255) * (1-fraction) + (b ushr shift and 255) * fraction).toInt()) shl shift
    return 0xFF000000.toInt() or c(16) or c(8) or c(0)
}
/** Empty header cells leave space for a single uninterrupted section title. */
private class HeaderLabel(context: Context) : TextView(context) {
    override fun onDraw(canvas: Canvas) { paint.color = currentTextColor; paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); var x=0f; for (character in text.toString().uppercase()) { val glyph=character.toString(); canvas.drawText(glyph,x,height-6*resources.displayMetrics.density,paint); x+=paint.measureText(glyph)+.4f*resources.displayMetrics.scaledDensity } }
}
