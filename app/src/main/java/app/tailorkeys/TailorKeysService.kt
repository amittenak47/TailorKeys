package app.tailorkeys

import android.media.AudioManager
import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import app.tailorkeys.layout.InputState
import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.KeyText
import app.tailorkeys.layout.Keys
import app.tailorkeys.layout.Layer
import kotlin.math.abs

class TailorKeysService : InputMethodService(), KeyboardView.Listener {

    private lateinit var settings: KeyboardSettings
    private lateinit var predictor: Predictor
    private lateinit var gestureDecoder: GestureDecoder
    private data class LanguageModel(val predictor: Predictor, val decoder: GestureDecoder, val chords: Chords)
    private val models = HashMap<String, LanguageModel>()
    private val loading = HashSet<String>()
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var modelReady = false
    private var destroyed = false

    private fun makeModel(lang: String): LanguageModel {
        val p = Predictor(this, lang) { main.post { if (!destroyed && state.language == lang && modelReady) updateSuggestions() } }
        return LanguageModel(p, GestureDecoder(p.gestureWords()), Chords(p.chordWords()))
    }

    private fun activateLanguage() {
        val lang = settings.currentLanguage()
        val changed = state.language != lang
        state.language = lang
        if (changed) {
            clearTaps()
            onChoiceInterrupted()
            clearGlide()
            lastSpaceAt = 0L
            deadEnd = false
            keyboardView?.cancelTouches()
        }
        val g = settings.loadGrid()
        if (g.toJson() != grid.toJson()) { clearTaps(); grid = g; keyboardView?.grid = g }
        val model = models[lang]
        modelReady = model != null
        keyboardView?.chordLibrary = model?.chords
        if (model != null) {
            predictor = model.predictor
            gestureDecoder = model.decoder
            predictor.setLayout(grid, keySpots)
        } else if (loading.add(lang)) {
            loader.execute {
                val loaded = makeModel(lang)
                main.post {
                    if (!destroyed) {
                        loaded.predictor.refreshLearning()
                        models[lang] = loaded
                        loading.remove(lang)
                        if (state.language == lang) { activateLanguage(); updateSuggestions() }
                    }
                }
            }
        }
    }
    private val glideWorker = Executors.newSingleThreadExecutor()
    private var liveBusy = false
    private class GlideSession(val prefix: String, val before: String, val after: String, val context: String,
                               val letters: Boolean, val upper: Boolean, val capital: Boolean) {
        var shown: String? = null
        var expected = before
        var lifted = false
        var spaced = false
        var matches = emptyList<GestureDecoder.Candidate>()
        var scoring: Predictor.GestureScoring? = null
    }
    private var liveGlide: GlideSession? = null
    private var autoWord: String? = null
    private var autoBefore: String? = null
    private var autoAfter: String? = null
    private fun clearAutomaticSpace() { autoWord = null; autoBefore = null; autoAfter = null }
    private fun hasAutomaticSpace(): Boolean {
        if (autoBefore == null) return false
        val read = editor()
        if (!read.selected && read.before.takeLast(64) == autoBefore && read.after.take(64) == autoAfter) return true
        clearAutomaticSpace(); return false
    }
    private fun rememberAutomaticSpace(word: String?) {
        autoWord = word
        autoBefore = editor().before.takeLast(64); autoAfter = editor().after.take(64)
        lastSpaceAt = SystemClock.uptimeMillis()
    }
    private fun replacesAutomaticWord(text: String): Boolean = hasAutomaticSpace() && autoWord != null &&
        (text == autoWord || glideCandidates.contains(text) ||
            settings.bool(Prefs.AUTOMATIC_PUNCTUATION) && TextInputRules.wordForms(autoWord.orEmpty(), predictor::hasWord).contains(text))
    private fun automaticSpace(word: String): Boolean {
        if (!TextInputRules.addSpace(settings.bool(Prefs.AUTOMATIC_SPACE), editor().after, !suggest, settings.bool(Prefs.SWIPE_LETTERS))) return false
        val attached = editor().before.endsWith(word)
        currentInputConnection?.commitText(" ", 1); editorRead = null
        rememberAutomaticSpace(if (attached) word else null)
        return true
    }
    private var glideWord: String? = null
    private var glideBefore: String? = null
    private var glideCandidates = emptyList<String>()
    private var glideExplore = emptyList<String>()
    private var keyboardView: KeyboardView? = null
    private var quickOptions: QuickOptions? = null
    private var voiceConnection: android.view.inputmethod.InputConnection? = null
    private var insertPanel: app.tailorkeys.insert.InsertPanel? = null
    private var clipboardCapture: app.tailorkeys.insert.ClipboardCapture? = null
    private var editingLayoutKey: String? = null
    private val state = InputState()
    private var grid: KeyGrid = KeyGrid.default()
        set(value) { if (value !== field) keySpots = null; field = value }
    /** The letter keys as the view last drew them, until [grid] changes; the predictor's layout neighbours. */
    private var keySpots: List<Pair<Char, KeySpot>>? = null
    private var lastSpaceAt = 0L
    private var lastAbcTap = 0L
    private var selStart = 0
    private var selEnd = 0
    private var selectAnchor: Int? = null
    /** False only in password fields: suggestions show everywhere else, including fields that don't say what they hold. */
    private var suggest = true
    /** True only in ordinary text fields that allow suggestions: the only place words are learned. */
    private var learn = true
    private var inputSession = 0L
    private var correctAllowed = false
    private var protectedBefore: String? = null
    private var protectedAfter: String? = null
    private var suppressedBefore: String? = null
    private var suppressedAfter: String? = null
    private data class PendingLearning(val word: String, val prev: String?, val prev2: String?, val picked: Boolean,
        val before: String, val after: String, val model: Predictor)
    private var pendingLearning: PendingLearning? = null
    private fun canLearnNow() = learn && settings.bool(Prefs.LEARNING)
    private fun deferLearning(word: String, prev: String?, prev2: String?, picked: Boolean = false) {
        if (!canLearnNow() || !modelReady || word.isEmpty()) return
        pendingLearning = PendingLearning(word, prev, prev2, picked, editor().before.takeLast(64), editor().after.take(64), predictor)
    }
    private fun keepPendingLearning() {
        val pending = pendingLearning ?: return
        pendingLearning = null
        if (canLearnNow() && !editor().selected && pending.before == editor().before.takeLast(64) && pending.after == editor().after.take(64))
            pending.model.observeFinal(pending.word, pending.prev, pending.prev2, pending.picked)
    }
    private var deadEnd = false
    private val taps = ComposingTaps()
    private var tapBefore: String? = null
    private var tapAfter: String? = null
    private var tapCursor: Int? = null

    private fun clearTaps() { taps.clear(); tapBefore = null; tapAfter = null; tapCursor = null }

    private var sharedRead = false
    private var editorRead: EditorWindow? = null
    private var renderedEditor: EditorWindow? = null
    private var renderedLayer: Layer? = null
    private val extractRequest = ExtractedTextRequest().apply { hintMaxChars = 576; hintMaxLines = 0 }
    private val cursorRead = CursorRead()

    private fun readCursor(): CursorRead {
        val ic = currentInputConnection
        val surrounding = if (Build.VERSION.SDK_INT >= 31)
            ic?.getSurroundingText(EditorWindow.CURSOR_WINDOW, EditorWindow.CURSOR_WINDOW, 0) else null
        if (surrounding != null && surrounding.offset >= 0) return cursorRead.set(
            surrounding.text, surrounding.offset, surrounding.selectionStart, surrounding.selectionEnd)
        val extracted = ic?.getExtractedText(extractRequest, 0)
        if (extracted?.text != null && extracted.partialStartOffset < 0) return cursorRead.set(
            extracted.text, extracted.startOffset, extracted.selectionStart, extracted.selectionEnd)
        return cursorRead.set("", -1, -1, -1)
    }

    private inline fun <T> editorPhase(block: () -> T): T {
        val nested = sharedRead
        sharedRead = true
        try { return block() } finally { sharedRead = nested; if (!nested) editorRead = null }
    }

    /** One surrounding-text IPC per read-only phase on modern editors; compatibility fallback below. */
    private fun editor(): EditorWindow {
        if (sharedRead) editorRead?.let { return it }
        val ic = currentInputConnection
        val size = if (suggest && state.language == "en") 2_048 else 64
        val afterSize = 64
        val surrounding = if (Build.VERSION.SDK_INT >= 31) ic?.getSurroundingText(size, afterSize, 0) else null
        val result = if (surrounding != null) {
            val text = surrounding.text.toString()
            val a = surrounding.selectionStart
            val b = surrounding.selectionEnd
            val lo = minOf(a, b).coerceIn(0, text.length)
            val hi = maxOf(a, b).coerceIn(lo, text.length)
            val offset = surrounding.offset
            EditorWindow(text, offset, if (offset >= 0) offset + a else -1,
                if (offset >= 0) offset + b else -1, text.substring(0, lo), text.substring(hi), a != b)
        } else {
            val before = ic?.getTextBeforeCursor(size, 0)?.toString().orEmpty()
            val after = ic?.getTextAfterCursor(afterSize, 0)?.toString().orEmpty()
            val selected = !ic?.getSelectedText(0).isNullOrEmpty()
            EditorWindow(before + after, -1, -1, -1, before, after, selected)
        }
        if (sharedRead) editorRead = result
        return result
    }

    private fun rememberTapContext(cursor: Int) {
        val read = editor()
        tapBefore = read.before.takeLast(64)
        tapAfter = read.after.take(64)
        tapCursor = if (read.known) read.end else cursor
    }

    private fun validateTaps() {
        if (tapBefore == null) return
        val read = editor()
        if (read.before.takeLast(64) != tapBefore || read.after.take(64) != tapAfter || read.selected) clearTaps()
    }
    private var chosenBefore: String? = null
    private var chosenAfter: String? = null
    private var chosenSelection = 0 to 0
    private var armedUndo: ReversibleCommit? = null
    /** A word finished with a space: what was tapped, its taps, and the text ending with it, for look-back. */
    private class FinishedWord(val typed: String, val taps: ComposingTaps?, val tail: String)
    private var lastWord: FinishedWord? = null
    /** The tapped words of the sentence so far, in order, for re-deciding them together when it ends. */
    private val sentenceWords = ArrayList<FinishedWord>()
    /** Sentence re-decoding steps the model for tens of milliseconds; keep it off the keyboard's thread. */
    private val sentenceWorker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "TailorKeys-sentence").apply { isDaemon = true } }

    override fun onWheelChosen() = editorPhase { onWheelChosenPhase() }

    private fun onWheelChosenPhase() {
        if (currentInputConnection == null) return
        val read = editor()
        chosenBefore = read.before.takeLast(64)
        chosenAfter = read.after.take(64)
        chosenSelection = selStart to selEnd
    }

    private fun clearChoice() {
        keyboardView?.clearArmedWord()
        chosenBefore = null; chosenAfter = null
    }

    private fun finishArmedLearning() {
        val undo = armedUndo
        armedUndo = null
        undo?.keep(editor().before.takeLast(64), editor().after.take(64), editor().selected, canLearnNow())
    }

    override fun onChoiceInterrupted() { clearChoice(); armedUndo = null; pendingLearning = null }

    private fun validateChoice() {
        if (chosenBefore == null) return
        val read = editor()
        if (!settings.bool(Prefs.SPACE_ACCEPTS) || read.before.takeLast(64) != chosenBefore ||
            read.after.take(64) != chosenAfter || read.selected) clearChoice()
    }

    private fun undoArmedCommit(): Boolean {
        val undo = armedUndo ?: return false
        val ic = currentInputConnection ?: return false
        val restore = undo.undo(editor().before.takeLast(64), editor().after.take(64), editor().selected) ?: return false
        armedUndo = null // Reverting discards the deferred learning increment.
        lastWord = null
        sentenceWords.clear()
        clearTaps()
        clearChoice(); clearGlide()
        ic.beginBatchEdit()
        ic.deleteSurroundingText(undo.inserted.length - undo.insertedAfter, undo.insertedAfter)
        editorRead = null
        ic.commitText(restore, 1)
        editorRead = null
        if (undo.originalTail > 0) {
            val cursor = readCursor()
            if (cursor.known) ic.setSelection(cursor.end - undo.originalTail, cursor.end - undo.originalTail)
            editorRead = null
        }
        ic.endBatchEdit()
        if (undo.correction) {
            if (modelReady) predictor.recordRejection(undo.original, undo.inserted.removeSuffix(undo.separator))
            suppressedBefore = editor().before.removeSuffix(undo.separator).takeLast(64)
            suppressedAfter = editor().after.take(64)
            val context = editor().before.removeSuffix(undo.separator)
            val previous = CorrectionRules.context(context, undo.original.length)
            deferLearning(undo.original, previous.lastOrNull(), previous.getOrNull(previous.size - 2))
        }
        updateAutoCap()
        return true
    }

    private fun correctWord(separator: String): Boolean {
        if (!modelReady || !correctAllowed || state.layer != Layer.ABC || editor().selected) return false
        val read = editor()
        val before = read.before
        val word = before.takeLastWhile(TextInputRules::wordChar)
        val typed = taps.hasInput(word)
        if (typed) predictor.noteTyped(word)
        if (before.takeLast(64) == protectedBefore && read.after.take(64) == protectedAfter ||
            before.takeLast(64) == suppressedBefore && read.after.take(64) == suppressedAfter ||
            read.after.firstOrNull()?.let(TextInputRules::wordChar) == true || currentGlide()) return false
        val previousPrefix = before.dropLast(word.length).trimEnd(' ').takeLast(64)
        val pickedPrevious = protectedBefore?.trimEnd(' ') == previousPrefix && read.after.take(64) == protectedAfter
        val change = predictor.correction(before, AutocorrectMode.entries[settings.int(Prefs.AUTOCORRECT)], taps,
            userTyped = typed, allowJoin = !pickedPrevious) ?: return false
        val context = CorrectionRules.context(before, change.original.length)
        val prev = context.lastOrNull(); val prev2 = context.getOrNull(context.size - 2)
        val ic = currentInputConnection ?: return false
        val model = predictor
        val allowed = canLearnNow()
        clearTaps(); clearChoice(); clearGlide(); clearAutomaticSpace(); pendingLearning = null
        ic.beginBatchEdit()
        ic.deleteSurroundingText(change.original.length, 0)
        ic.commitText(change.replacement + separator, 1)
        editorRead = null
        ic.endBatchEdit()
        armedUndo = ReversibleCommit(change.original, change.replacement + separator, separator, editor().before.takeLast(64), editor().after.take(64), correction = true,
            onKept = { model.recordKept(change.original, change.replacement) }) {
            if (allowed && canLearnNow()) model.observeFinal(change.replacement, prev, prev2)
        }
        updateAutoCap()
        return true
    }

    private fun commitArmed(separator: String): Boolean {
        validateChoice()
        if (state.layer != Layer.ABC || !settings.bool(Prefs.SPACE_ACCEPTS)) return false
        val word = keyboardView?.armedWord ?: return false
        val ic = currentInputConnection ?: return false
        val read = editor()
        val before = read.before.takeLast(64)
        val spaced = replacesAutomaticWord(word)
        val original = if (spaced) autoWord.orEmpty() else if (currentGlide() && !hasAutomaticSpace()) glideWord!! else currentWord(before)
        val suffix = if (spaced || read.selected) 0 else TextInputRules.suffixLength(read.after)
        val originalSuffix = read.after.take(suffix)
        val model = if (modelReady) predictor else null
        val shouldLearn = canLearnNow() && modelReady
        val prev = previousWord(if (spaced) before.dropLast(1) else before)
        val prev2 = previousWord2(if (spaced) before.dropLast(1) else before)
        clearChoice(); clearGlide(); clearAutomaticSpace(); clearTaps()
        ic.beginBatchEdit()
        ic.finishComposingText()
        if (!read.selected) ic.deleteSurroundingText(original.length + if (spaced) 1 else 0, suffix)
        editorRead = null
        commit(word)
        var trailing = false
        if (separator == " ") {
            // This is an explicit space key; an existing separator after the cursor suffices.
            if (editor().after.firstOrNull()?.isWhitespace() != true) typeSpace(learnWord = false)
        } else {
            commit(separator)
            if (settings.bool(Prefs.AUTOMATIC_PUNCTUATION)) trailing = automaticSpace(word)
        }
        ic.endBatchEdit()
        val insertedSpace = separator == " " && editor().after.firstOrNull()?.isWhitespace() != true
        val inserted = word + if (separator == " ") (if (insertedSpace) " " else "") else separator + if (trailing) " " else ""
        armedUndo = ReversibleCommit(original + originalSuffix, inserted, separator,
            editor().before.takeLast(64), editor().after.take(64)) {
            if (EditorRules.deferredLearning(shouldLearn, canLearnNow())) model?.observe(word, prev, prev2, picked = true)
        }
        updateAutoCap()
        return true
    }

    // Any settings change, including from the settings screen while the keyboard is open, applies at once.
    // A layout the keyboard saved itself is already showing; reloading it would wipe the undo history.
    private var pendingLanguage = false
    private var pendingLayout = false
    private val applyPreferences = Runnable {
        if (destroyed) return@Runnable
        val languageChanged = pendingLanguage
        val layoutChanged = pendingLayout
        pendingLanguage = false; pendingLayout = false
        if (languageChanged) activateLanguage()
        if (layoutChanged) {
            val g = settings.loadGrid()
            if (g.toJson() != grid.toJson()) {
                clearTaps()
                grid = g
                if (modelReady) predictor.setLayout(g, keySpots)
                keyboardView?.grid = g
            }
        }
        keyboardView?.reloadSettings()
        quickOptions?.refresh()
        insertPanel?.refresh()
        updateSuggestions()
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.LEARNING_REVISION) {
            armedUndo = null; pendingLearning = null; liveGlide = null; clearGlide()
            models.values.forEach { it.predictor.reloadLearning() }
        }
        if (key == Prefs.LEARNING.key && !settings.bool(Prefs.LEARNING)) { armedUndo = null; pendingLearning = null }
        pendingLanguage = pendingLanguage || key == null || key == Prefs.LANGUAGES || key == Prefs.CURRENT_LANGUAGE
        pendingLayout = pendingLayout || key == null || key == Prefs.LAYOUT
        main.removeCallbacks(applyPreferences)
        main.post(applyPreferences)
    }

    override fun onCreate() {
        super.onCreate()
        settings = KeyboardSettings(this)
        clipboardCapture = app.tailorkeys.insert.ClipboardCapture(this, app.tailorkeys.insert.InsertRepository(this)) { insertPanel?.clipboardChanged() }
        state.language = settings.currentLanguage()
        grid = settings.loadGrid()
        activateLanguage()
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        sentenceWorker.shutdownNow()
        clipboardCapture?.close()
        insertPanel?.hide()
        onChoiceInterrupted()
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        models.values.forEach { it.predictor.save() }
        destroyed = true
        main.removeCallbacks(applyPreferences)
        loader.shutdown()
        glideWorker.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        insertPanel?.hide()
        closeQuickOptions()
        editingLayoutKey = null
        val keyboard = KeyboardView(this, settings).also {
            it.chordLibrary = models[state.language]?.chords
            it.listener = this
            it.state = state
            it.grid = grid
            it.inKeyboard = true
            it.glideAllowed = suggest
            keyboardView = it
        }
        // Only the keyboard determines height; the scrollable overlay must never enlarge the IME.
        return object : android.widget.FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                keyboard.measure(widthMeasureSpec, heightMeasureSpec)
                setMeasuredDimension(keyboard.measuredWidth, keyboard.measuredHeight)
                // Measure the invisible panel up front. INVISIBLE/VISIBLE never changes the layout;
                // unchanged keyboard measure specs use View's existing measurement cache.
                quickOptions?.updateStripHeight()
                for (i in 1 until childCount) getChildAt(i).measure(
                    View.MeasureSpec.makeMeasureSpec(keyboard.quickOptionsBounds.width().toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(measuredHeight, View.MeasureSpec.EXACTLY))
            }
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                keyboard.layout(0, 0, measuredWidth, measuredHeight)
                for (i in 1 until childCount) getChildAt(i).layout(keyboard.quickOptionsBounds.left.toInt(), 0,
                    keyboard.quickOptionsBounds.right.toInt(), measuredHeight)
            }
        }.also {
            it.addView(keyboard, android.widget.FrameLayout.LayoutParams(-1, -2))
            val panel = QuickOptions(this, settings,
                landscape = { resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE },
                stripHeight = { keyboard.suggestionStripHeight },
                close = { closeQuickOptions() },
                rearrange = { key, title ->
                    closeQuickOptions()
                    editingLayoutKey = key
                    keyboard.editTitle = title
                    grid = settings.loadGrid()
                    keyboard.grid = grid
                    if (modelReady) predictor.setLayout(grid, keySpots)
                    keyboard.editMode = true
                    keyboard.announceForAccessibility(title)
                },
                language = { onSwitchLanguage(1) },
                allSettings = { closeQuickOptions(); onOpenSettings() }, tidy = { showTidy() }).apply {
                visibility = View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            // The IME recreates its input view for configuration/density changes. Theme colours are
            // refreshed in place, so neither a gear tap nor a theme tap constructs a view hierarchy.
            quickOptions = panel
            it.addView(panel, android.widget.FrameLayout.LayoutParams(-1, -1))
            val repository = app.tailorkeys.insert.InsertRepository(this)
            val insert = app.tailorkeys.insert.InsertPanel(this, settings, repository, state,
                close = { closeInsert() }, insert = { text -> editorPhase { onLongPressTextPhase(text) } }, backspace = { onKey(Keys.BACKSPACE) },
                typing = { active, height ->
                    keyboard.setOptionsCovered(!active); keyboard.alpha = if (active) 1f else 0f
                    keyboard.importantForAccessibility = if (active) View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    keyboard.insertStripHeight = (height * resources.displayMetrics.density).toInt()
                    keyboard.grid = if (active && (resources.configuration.smallestScreenWidthDp < 600 || keyboard.width / resources.displayMetrics.density < 600))
                        KeyGrid.fromJson(grid.toJson())!!.apply { setNumberRow(false) } else grid
                }, pickIme = { pickVoiceIme() },
                composeVoice = { text -> editorPhase {
                    val connection = currentInputConnection
                    if (connection != null) {
                        if (voiceConnection == null) { onChoiceInterrupted(); clearTaps(); connection.finishComposingText() }
                        voiceConnection = connection; connection.setComposingText(text, 1); editorRead = null
                    }
                } },
                finishVoice = { text -> editorPhase {
                    val connection = voiceConnection ?: currentInputConnection
                    if (voiceConnection == null) connection?.finishComposingText()
                    connection?.commitText(text, 1); connection?.finishComposingText(); voiceConnection = null; editorRead = null; updateSuggestions()
                } },
                cancelVoice = { editorPhase {
                    voiceConnection?.let { it.setComposingText("", 1); it.finishComposingText() }; voiceConnection = null; editorRead = null
                } }).apply {
                visibility = View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            insertPanel = insert
            it.addView(insert, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
    }

    override fun onQuickOptions() {
        closeInsert()
        val keyboard = keyboardView ?: return
        val panel = quickOptions ?: return
        if (panel.visibility == View.VISIBLE) return
        onChoiceInterrupted()
        keyboard.cancelTouches()
        keyboard.setOptionsCovered(true)
        keyboard.animate().cancel()
        keyboard.alpha = 1f
        val scale = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) keyboard.animate().alpha(0f).setDuration(if (scale < 1f) 100 else 80).start()
        else keyboard.alpha = 0f
        keyboard.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        panel.refresh(resetScroll = true)
        panel.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        panel.visibility = View.VISIBLE
        panel.animateOpen()
        panel.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    private fun closeQuickOptions() {
        quickOptions?.let {
            it.animate().cancel(); it.alpha = 1f; it.translationY = 0f
            it.clearFocus()
            it.visibility = View.INVISIBLE
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        keyboardView?.setOptionsCovered(false)
        keyboardView?.animate()?.cancel()
        keyboardView?.alpha = 1f
        keyboardView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun openInsert() {
        closeQuickOptions(); onChoiceInterrupted()
        keyboardView?.let { it.cancelTouches(); it.setOptionsCovered(true); it.alpha = 0f; it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        insertPanel?.show()
    }
    private fun closeInsert() {
        insertPanel?.hide()
        keyboardView?.let { it.setOptionsCovered(false); it.alpha = 1f; it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
    }
    private fun insertKey(code: String): Boolean {
        if (code == Keys.INSERT) { openInsert(); return true }
        if (insertPanel?.acceptsText() == true && KeyText.isLayerKey(code)) {
            tapLayerKey(code); keyboardView?.refreshInputState(); return true
        }
        return (insertPanel?.key(code) == true).also { if (it) keyboardView?.refreshInputState() }
    }

    // Landscape "extract mode" covers the app with a full-screen text box. Keep the app visible instead.
    override fun onEvaluateFullscreenMode() = false

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        inputSession++
        closeInsert()
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val number = info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER
        clipboardCapture?.password = if (number) variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD else
            variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        armedUndo = null
        liveGlide = null
        clearAutomaticSpace()
        learn = false
        renderedEditor = null
        selectAnchor = null
        editorRead = null
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd
        onChoiceInterrupted()
        clearGlide()
        clearTaps()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) learn = false
        onChoiceInterrupted()
        lastWord = null
        sentenceWords.clear()
        clearTaps()
        models.values.forEach { it.predictor.resetContext() }
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        state.layer = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) Layer.NUM else Layer.ABC
        state.shift = false
        state.capsLock = false
        val password = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        clearGlide()
        liveGlide = null
        clearAutomaticSpace()
        suggest = !password
        clipboardCapture?.password = password
        keyboardView?.glideAllowed = suggest
        protectedBefore = null; protectedAfter = null; suppressedBefore = null; suppressedAfter = null
        correctAllowed = CorrectionRules.allowed(cls == InputType.TYPE_CLASS_TEXT, password,
            info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0,
            variation == InputType.TYPE_TEXT_VARIATION_URI || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        learn = EditorRules.canLearn(cls == InputType.TYPE_CLASS_TEXT, password,
            info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0,
            info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        lastSpaceAt = 0L
        deadEnd = false
        keyboardView?.let {
            it.enterLabel = enterLabel(info)
            it.reloadSettings()
        }
        updateAutoCap()
        updateSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        closeInsert()
        onGlideCancel()
        clearTaps()
        onChoiceInterrupted()
        closeQuickOptions()
        keyboardView?.let { it.cancelTouches(); it.editMode = false; it.editTitle = null }
        editingLayoutKey = null
        models.values.forEach { it.predictor.save() }
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) = editorPhase { selectionPhase(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd) }

    private fun selectionPhase(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                               candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Cursor moves are caught by validateTaps (the text around the cursor changes); comparing positions here
        // would wipe the taps during fast typing, when this callback trails the next letter.
        if (newSelStart != newSelEnd) clearTaps()
        if (keyboardView?.armedWord != null && chosenSelection != (newSelStart to newSelEnd)) clearChoice()
        armedUndo?.let { undo ->
            if (editor().before.takeLast(64) != undo.before ||
                editor().after.take(64) != undo.after) { armedUndo = null }
        }
        selStart = newSelStart
        selEnd = newSelEnd
        if (newSelStart != newSelEnd) clearGlide()
        updateAutoCap()
        // The commit phase has already rendered this exact live snapshot in the common case.
        if (editor() != renderedEditor || state.layer != renderedLayer) updateSuggestions()
    }

    // ---- KeyboardView.Listener ----

    override fun onChord(word: String) = editorPhase { if (insertPanel?.typeText(word) != true) onChordPhase(word) }

    private fun onChordPhase(word: String) {
        keepPendingLearning(); finishArmedLearning()
        clearTaps()
        onChoiceInterrupted()
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        val shifted = state.shift
        if (editor().before.takeLast(1).lastOrNull()?.isLetterOrDigit() == true && settings.bool(Prefs.AUTOMATIC_SPACE) && suggest) automaticSpace(currentWord(editor().before))
        updateAutoCap()
        state.shift = state.shift || shifted
        val allCaps = state.capsLock || (settings.bool(Prefs.AUTO_CAP) &&
            (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0)
        val text = when {
            allCaps -> word.uppercase()
            state.shift -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
        clearGlide()
        commit(text)
        automaticSpace(text)
        ic.endBatchEdit()
        updateAutoCap()
        updateSuggestions()
        keyboardView?.refreshInputState()
    }

    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private fun sound(code: String) {
        if (!settings.bool(Prefs.SOUND)) return
        val effect = when (code) {
            Keys.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            Keys.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
            Keys.ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        audio?.playSoundEffect(effect, settings.int(Prefs.SOUND_VOLUME) / 100f)
    }

    override fun onForwardDelete() = editorPhase {
        sound(Keys.BACKSPACE)
        armedUndo = null; pendingLearning = null
        clearTaps(); clearChoice(); clearGlide(); clearAutomaticSpace()
        val read = editor()
        if (read.selected) currentInputConnection?.commitText("", 1)
        else if (read.after.isNotEmpty()) {
            val length = Graphemes.move(read.after, 0, 1)
            // Do not split a grapheme clipped by the editor's bounded response.
            if (length < read.after.length || read.after.length < 64)
                currentInputConnection?.deleteSurroundingText(0, length)
        }
        editorRead = null
        updateSuggestions()
    }

    override fun onKey(code: String) { if (!insertKey(code)) handleKey(code, null) }

    override fun onTapKey(code: String, distances: FloatArray) { if (!insertKey(code)) handleKey(code, distances) }

    override fun onLetterLayout(spots: List<Pair<Char, KeySpot>>) {
        if (spots == keySpots) return
        keySpots = spots
        if (modelReady) predictor.setLayout(grid, spots)
    }

    private fun handleKey(code: String, distances: FloatArray?) = editorPhase { handleKeyPhase(code, distances) }

    private fun handleKeyPhase(code: String, distances: FloatArray?) {
        sound(code)
        liveGlide = null
        validateTaps()
        if (code == Keys.BACKSPACE && undoArmedCommit()) {
            updateSuggestions(); keyboardView?.refreshInputState(); return
        }
        if (code == Keys.BACKSPACE) { armedUndo = null; pendingLearning = null }
        else if (code == Keys.SPACE || code == Keys.ENTER || code.length == 1) { keepPendingLearning(); finishArmedLearning() }
        val separator = if (code == Keys.SPACE) " " else code.takeIf { it.length == 1 && it[0] in ".,?!;:" }
        // Taken before the word is corrected or picked, while its taps still describe it.
        val finishing = if (separator != null) finishingWord() else null
        val endsSentence = separator != null && separator in SENTENCE_ENDS
        if (separator != null && separator != " ") {
            lastWord = null
            // Re-decoding covers the stretch since the last comma; the earlier clause is context.
            if (!endsSentence) sentenceWords.clear()
        }
        if (separator != null && commitArmed(separator)) {
            lastWord = null; sentenceWords.clear()
            updateSuggestions(); keyboardView?.refreshInputState(); return
        }
        if (separator != null && correctWord(separator)) {
            if (endsSentence) endSentence(finishing) else lookBack(finishing)
            updateSuggestions(); keyboardView?.refreshInputState(); return
        }
        clearChoice()
        val endingGlide = if (separator != null && separator != " " && currentGlide()) glideWord else null
        val endedBefore = if (separator != null && separator != " ") {
            if (endingGlide != null && hasAutomaticSpace()) editor().before.dropLast(1) else editor().before
        } else null
        var letterCommitted = false
        when {
            code == Keys.BACKSPACE -> {
                clearGlide()
                val ic = currentInputConnection
                val cursor = (tapCursor ?: selEnd) - 1
                val tappedWord = taps.hasTaps(currentWord(editor().before.takeLast(64)))
                if (!editor().selected) {
                    taps.sync(currentWord(editor().before.takeLast(64)))
                    taps.backspace()
                } else clearTaps()
                // Delete a known composing letter directly; key events can be applied after
                // the selection callback and would discard the remaining tap rows.
                if (tappedWord && !editor().selected && currentInputEditorInfo?.inputType != InputType.TYPE_NULL) {
                    ic?.deleteSurroundingText(1, 0)
                    editorRead = null
                } else { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); editorRead = null }
                rememberTapContext(cursor)
            }
            code == Keys.SPACE -> typeSpace()
            code == Keys.ENTER -> pressEnter()
            KeyText.isLayerKey(code) -> tapLayerKey(code)
            else -> {
                if (state.layer == Layer.ABC && code.length == 1 && code[0].isLetter() && currentGlide()) {
                    val shifted = state.shift
                    finishGlideLearning()
                    if (!hasAutomaticSpace() && settings.bool(Prefs.AUTOMATIC_SPACE)) automaticSpace(glideWord!!)
                    clearGlide()
                    updateAutoCap()
                    state.shift = state.shift || shifted
                }
                KeyText.output(code, state)?.let {
                    commit(it, typing = true, distances = distances)
                    letterCommitted = it.length == 1 && it[0].isLetter()
                }
            }
        }
        if (endedBefore != null) deferLearning(endingGlide ?: currentWord(endedBefore), previousWord(endedBefore), previousWord2(endedBefore))
        if (separator == " ") {
            lookBack(finishing)
            // A double space may just have typed ". ", ending the sentence.
            if (editor().before.trimEnd(' ').lastOrNull()?.let { it in SENTENCE_ENDS } == true) endSentence(null)
        } else if (endsSentence) endSentence(finishing)
        updateSuggestions(letterCommitted)
        keyboardView?.refreshInputState()
    }

    private fun finishingWord(): FinishedWord? {
        if (state.layer != Layer.ABC || currentGlide()) return null
        val word = editor().before.takeLastWhile(TextInputRules::wordChar).takeIf { it.isNotEmpty() } ?: return null
        return FinishedWord(word, taps.takeIf { it.hasTaps(word) }?.copy(), "")
    }

    /**
     * Look-back: with the word just finished by a space, re-checks the one before it against both neighbours
     * (chair fire perfectly -> chair fits perfectly), then remembers this word for the next space. Only when the
     * text still ends exactly as both words were left; backspace right after undoes it like an autocorrection.
     */
    private fun lookBack(finished: FinishedWord?) {
        val previous = lastWord
        lastWord = null
        if (finished == null) return
        val read = editor()
        val before = read.before
        val word = before.dropLast(1).takeLastWhile(TextInputRules::wordChar)
        if (!before.endsWith(" ") || word.isEmpty() || read.selected) return
        val head = before.dropLast(word.length + 1)
        val old = head.dropLast(1).takeLastWhile(TextInputRules::wordChar)
        // The sentence's word list only continues while the text still ends as it was left.
        if (previous == null || !head.endsWith(previous.tail)) sentenceWords.clear()
        if (previous != null && modelReady && correctAllowed && old.isNotEmpty() && head.endsWith(previous.tail) &&
            read.after.firstOrNull()?.let(TextInputRules::wordChar) != true) {
            val fix = predictor.lookBack(head, previous.typed, previous.taps, word, AutocorrectMode.entries[settings.int(Prefs.AUTOCORRECT)])
            val ic = currentInputConnection
            if (fix != null && fix != old && ic != null) {
                val rest = " $word "
                ic.beginBatchEdit()
                ic.deleteSurroundingText(old.length + rest.length, 0)
                ic.commitText(fix + rest, 1)
                editorRead = null
                ic.endBatchEdit()
                armedUndo = ReversibleCommit("$old $word", fix + rest, " ", editor().before.takeLast(64), editor().after.take(64), correction = true) {}
            }
        }
        lastWord = FinishedWord(finished.typed, finished.taps, editor().before.takeLast(48))
        sentenceWords += lastWord!!
    }

    /**
     * Whole-sentence re-decoding: the sentence just ended, so its tapped words are re-decided together, using all
     * the words on both sides and the end of the sentence. The readings are gathered here; the model runs in the
     * background, and the result applies only if the text is still exactly as it was, with one undo for all of it.
     */
    private fun endSentence(finished: FinishedWord?) {
        val words = ArrayList(sentenceWords).apply { if (finished != null) add(finished) }
        sentenceWords.clear(); lastWord = null
        val read = editor()
        if (words.size < 2 || !modelReady || !correctAllowed || read.selected || read.after.firstOrNull()?.let(TextInputRules::wordChar) == true) return
        val before = read.before
        val body = before.trimEnd(' ')
        if (body.lastOrNull()?.let { it in SENTENCE_ENDS } != true) return
        val text = body.dropLast(1)
        val span = text.split(' ').takeLast(words.size)
        if (span.size != words.size || span.any { it.isEmpty() || !it.all(TextInputRules::wordChar) }) return
        val spanText = span.joinToString(" ")
        val head = text.dropLast(spanText.length)
        val trailing = before.substring(text.length)
        val decode = predictor.prepareRedecode(head, words.map { it.typed }, words.map { it.taps }, span,
            AutocorrectMode.entries[settings.int(Prefs.AUTOCORRECT)]) ?: return
        sentenceWorker.execute {
            val fixed = decode() ?: return@execute
            main.post {
                val ic = currentInputConnection ?: return@post
                val now = editor()
                if (destroyed || now.before != before || now.selected || now.after.take(64) != read.after.take(64)) return@post
                val replacement = fixed.joinToString(" ") + trailing
                ic.beginBatchEdit()
                ic.deleteSurroundingText(spanText.length + trailing.length, 0)
                ic.commitText(replacement, 1)
                editorRead = null
                ic.endBatchEdit()
                armedUndo = ReversibleCommit(spanText, replacement, trailing, editor().before.takeLast(64), editor().after.take(64), correction = true) {}
                updateSuggestions()
            }
        }
    }

    override fun onSelectMode(on: Boolean) {
        clearTaps()
        onChoiceInterrupted()
        val live = if (on) readCursor() else null
        selectAnchor = live?.takeIf { it.known }?.start
    }

    override fun onCursor(steps: Int, select: Boolean) {
        if (insertPanel?.cursor(steps, select) == true) return
        if (steps == 0) return
        onGlideCancel()
        clearAutomaticSpace()
        clearTaps()
        onChoiceInterrupted()
        clearGlide()
        val ic = currentInputConnection ?: return
        val live = readCursor()
        if (!live.known || currentInputEditorInfo?.inputType == InputType.TYPE_NULL) {
            if (!select) {
                val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                repeat(abs(steps.toLong()).coerceAtMost(64).toInt()) { sendDownUpKeyEvents(code) }
            }
            return
        }
        if (select && selectAnchor == null) return
        val position = live.target(steps, select)
        if (position < 0) return
        val anchor = if (select) live.start else position
        ic.setSelection(anchor, position)
        editorRead = null
        selStart = anchor; selEnd = position
    }

    override fun onReplacePressText(original: String, following: String, replacement: String) {
        clearTaps()
        onChoiceInterrupted()
        val ic = currentInputConnection ?: return
        val expected = original + following
        if (ic.getTextBeforeCursor(expected.length, 0)?.toString() != expected) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(expected.length, 0)
        editorRead = null
        ic.commitText(replacement + following, 1)
        editorRead = null
        ic.endBatchEdit()
        updateAutoCap()
        updateSuggestions(true)
        keyboardView?.refreshInputState()
    }

    override fun onLongPressText(text: String) = editorPhase { if (insertPanel?.typeText(text) != true) onLongPressTextPhase(text) }

    private fun onLongPressTextPhase(text: String) {
        onChoiceInterrupted()
        commit(text, typing = true)
        updateSuggestions(text.any { it.isLetter() })
        keyboardView?.refreshInputState()
    }

    override fun onSwitchLanguage(direction: Int) {
        onChoiceInterrupted()
        val enabled = settings.languages()
        if (enabled.size < 2) return
        settings.cycleLanguage(direction)
        keyboardView?.announceForAccessibility(Prefs.LANGUAGE_NAMES.getValue(settings.currentLanguage()))
    }

    /** A swipe key landed on a new face. Layer faces switch the layout straight away. */
    override fun onStackFace(code: String) {
        if (KeyText.isLayerKey(code)) {
            onChoiceInterrupted()
            state.layer = KeyText.layerOf(code)
            state.shift = false
            updateSuggestions()
        }
        keyboardView?.refreshInputState()
    }

    /** A word from a wheel replaces the word being typed. A symbol is just inserted. */
    override fun onForgetWord(word: String) {
        armedUndo = null; pendingLearning = null
        models.values.forEach { it.predictor.save()?.get() }
        PersonalStore.forget(filesDir, state.language, word)
        settings.prefs.edit().putLong(Prefs.LEARNING_REVISION, System.nanoTime()).apply()
        updateSuggestions()
    }

    private fun showTidy() = editorPhase {
        if (!correctAllowed || !modelReady || editor().selected) {
            quickOptions?.showTidy(emptyList()) {}; return@editorPhase
        }
        val session = inputSession
        val connection = currentInputConnection ?: return@editorPhase
        val before = connection.getTextBeforeCursor(1024, 0)?.toString() ?: return@editorPhase
        val start = TidyText.windowStart(before)
        val left = before.substring(start)
        val right = TidyText.afterWindow(connection.getTextAfterCursor(768, 0)?.toString() ?: return@editorPhase)
        val startsSentence = if (start == 0) before.length < 1024 else before.substring(0, start).trimEnd().lastOrNull() in listOf('.', '!', '?', '\n')
        val text = left + right
        val fixes = TidyText.propose(text, startsSentence) { word, context ->
            predictor.updateContext(context + word, word)
            predictor.autocorrect(word, AutocorrectMode.AGGRESSIVE, previousWord(context + word), previousWord2(context + word))
        }
        quickOptions?.showTidy(fixes) { chosen -> editorPhase apply@ {
            if (chosen.isEmpty() || session != inputSession || !correctAllowed) return@apply
            val live = editor()
            if (live.selected || connection.getTextBeforeCursor(left.length, 0)?.toString().orEmpty() != left ||
                connection.getTextAfterCursor(right.length, 0)?.toString().orEmpty() != right) return@apply
            val replacement = TidyText.apply(text, chosen)
            val ic = currentInputConnection ?: return@apply
            armedUndo = null; pendingLearning = null
            clearTaps(); clearChoice(); clearGlide(); clearAutomaticSpace()
            ic.beginBatchEdit()
            ic.deleteSurroundingText(left.length, right.length)
            ic.commitText(replacement, 1); editorRead = null
            val cursor = readCursor()
            val tail = if (cursor.known) replacement.length - TidyText.cursor(left.length, chosen) else 0
            if (tail > 0) ic.setSelection(cursor.end - tail, cursor.end - tail)
            editorRead = null
            ic.endBatchEdit()
            armedUndo = ReversibleCommit(text, replacement, "", editor().before.takeLast(64), editor().after.take(64),
                insertedAfter = tail, originalTail = if (cursor.known) right.length else 0) {}
            updateSuggestions()
        } }
    }

    override fun onSuggestion(text: String) = editorPhase { suggestionPhase(text) }

    private fun suggestionPhase(text: String) {
        if (armedUndo?.correction == true && armedUndo?.original == text && undoArmedCommit()) {
            updateSuggestions(); return
        }
        if (currentWord(editor().before).isEmpty()) { keepPendingLearning(); finishArmedLearning() }
        armedUndo = null; pendingLearning = null; clearChoice()
        liveGlide = null
        val ic = currentInputConnection ?: return
        ic.finishComposingText()
        if (state.layer != Layer.ABC) { commit(text); updateSuggestions(); return }
        val read = editor()
        val spaced = replacesAutomaticWord(text)
        val before = read.before.takeLast(64)
        val original = if (spaced) autoWord.orEmpty() else if (currentGlide() && !hasAutomaticSpace()) glideWord.orEmpty() else currentWord(before)
        val prev = previousWord(if (spaced) before.dropLast(1) else before)
        val prev2 = previousWord2(if (spaced) before.dropLast(1) else before)
        val plan = TextInputRules.replacement(original.length, read.after, settings.bool(Prefs.AUTOMATIC_SPACE) && !settings.bool(Prefs.SWIPE_LETTERS) && suggest,
            read.selected, if (spaced) original.length else 0)
        clearTaps(); clearGlide(); clearAutomaticSpace()
        ic.beginBatchEdit()
        if (!read.selected) ic.deleteSurroundingText(plan.before, plan.after)
        editorRead = null
        ic.commitText(text, 1); editorRead = null
        if (plan.space) automaticSpace(text)
        ic.endBatchEdit()
        protectedBefore = editor().before.takeLast(64); protectedAfter = editor().after.take(64)
        deferLearning(text, prev, prev2, picked = true)
        updateAutoCap(); updateSuggestions()
    }

    override fun onGlideStart() = editorPhase {
        if (insertPanel?.acceptsText() == true) return@editorPhase
        keepPendingLearning(); finishArmedLearning()
        onChoiceInterrupted()
        val read = editor()
        val prefix = if (currentGlide() || read.selected) "" else currentWord(read.before.takeLast(64))
        clearGlide(); clearTaps()
        liveGlide = GlideSession(prefix, read.before.takeLast(64), read.after.take(64), read.before,
            settings.bool(Prefs.SWIPE_LETTERS), state.capsLock || (settings.bool(Prefs.AUTO_CAP) &&
                (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0), state.shift)
    }

    override fun onGlideCancel() {
        if (insertPanel?.acceptsText() == true) return
        liveGlide = null
        currentInputConnection?.finishComposingText()
    }

    private fun glideCase(session: GlideSession, word: String): String = when {
        session.upper -> word.uppercase()
        session.prefix.isNotEmpty() -> session.prefix + word.drop(session.prefix.length)
        session.capital -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    private fun showRaw(session: GlideSession, raw: String) {
        val ic = currentInputConnection ?: return
        if (editor().before.takeLast(64) != session.expected || editor().after.take(64) != session.after || editor().selected) {
            liveGlide = null; ic.finishComposingText(); return
        }
        val text = glideCase(session, SwipeTrace.appendToPrefix(session.prefix, raw))
        if (text == session.shown) return
        if (session.shown == null) {
            ic.finishComposingText()
            if (session.prefix.isNotEmpty()) ic.deleteSurroundingText(session.prefix.length, 0)
        }
        ic.setComposingText(text, 1); editorRead = null
        session.shown = text; session.expected = editor().before.takeLast(64)
    }

    override fun onGlideProgress(points: List<GestureDecoder.Point>, keys: Map<Char, GestureDecoder.Point>, keyWidth: Float,
                                 raw: String, hints: List<GestureDecoder.DoubleHint>) = editorPhase {
        if (insertPanel?.acceptsText() == true) return@editorPhase
        val session = liveGlide ?: return@editorPhase
        if (session.letters) showRaw(session, raw)
        if (modelReady && suggest && !liveBusy) decodeGlide(session, points, keys, keyWidth, raw, hints, false)
    }

    override fun onGlide(points: List<GestureDecoder.Point>, keys: Map<Char, GestureDecoder.Point>, keyWidth: Float,
                         raw: String, hints: List<GestureDecoder.DoubleHint>) = editorPhase {
        if (insertGlide(points, keys, keyWidth, raw, hints)) return@editorPhase
        val session = liveGlide ?: return@editorPhase
        if (!suggest || !settings.bool(Prefs.SWIPE_TYPING) || state.layer != Layer.ABC) { onGlideCancel(); return@editorPhase }
        if (editor().before.takeLast(64) != session.expected || editor().after.take(64) != session.after || editor().selected) {
            onGlideCancel(); return@editorPhase
        }
        session.lifted = true
        if (session.letters) {
            showRaw(session, raw)
            currentInputConnection?.finishComposingText()
            if (liveGlide !== session) return@editorPhase
            glideWord = session.shown
            glideBefore = editor().before.takeLast(64)
        }
        if (!session.letters) {
            // Keep fast follow-up taps from outrunning an asynchronous final decode.
            val provisional = session.matches.filter { it.word.lastOrNull() == raw.lastOrNull() }
            finishGuess(session, provisional, raw, pending = modelReady)
        }
        if (modelReady) decodeGlide(session, points, keys, keyWidth, raw, hints, true)
        updateSuggestions()
    }

    /** Panel typing never creates a composing session in the host editor. */
    private fun insertGlide(points: List<GestureDecoder.Point>, keys: Map<Char, GestureDecoder.Point>, width: Float,
        raw: String, hints: List<GestureDecoder.DoubleHint>): Boolean {
        val panel = insertPanel ?: return false
        if (!panel.acceptsText()) return false
        if (!modelReady || settings.bool(Prefs.SWIPE_LETTERS)) { panel.typeText(raw); return true }
        val session = panel.session
        val scoring = predictor.gestureScoring(); val path = points.toList(); val map = keys.toMap(); val doubles = hints.toList()
        glideWorker.execute {
            val decoder = GestureDecoder(scoring.words.toList()); decoder.setKeys(map, width)
            val word = decoder.decode(path, null, scoring.frequency, { _, _ -> 1.0 }, doubles).firstOrNull()?.word ?: raw
            main.post { if (insertPanel === panel && panel.session == session && panel.acceptsText()) panel.typeText(word) }
        }
        return true
    }

    /** One live job at a time, plus a final job. No decoder work or path copies in drawGlide. */
    private fun decodeGlide(session: GlideSession, points: List<GestureDecoder.Point>, keys: Map<Char, GestureDecoder.Point>,
                            width: Float, raw: String, hints: List<GestureDecoder.DoubleHint>, final: Boolean) {
        val decoder = gestureDecoder
        val model = predictor
        val path = points.toList(); val doubles = hints.toList()
        val scoring = session.scoring ?: run {
            model.updateContext(session.context, session.prefix)
            model.gestureScoring().also { session.scoring = it }
        }
        val prev = previousWord(session.before)
        if (!final) liveBusy = true
        glideWorker.execute {
            decoder.setUserWords(scoring.words); decoder.setKeys(keys, width)
            val matches = decoder.decode(path, prev, scoring.frequency, scoring.context, doubles, session.prefix, partial = !final)
            main.post {
                if (!final) liveBusy = false
                if (destroyed || liveGlide !== session || predictor !== model || (!final && session.lifted)) return@post
                editorPhase {
                    if (!suggest || editor().before.takeLast(64) != session.expected || editor().after.take(64) != session.after || editor().selected) {
                        liveGlide = null; return@editorPhase
                    }
                    val ranked = matches
                    session.matches = ranked
                    val prefix = if (session.letters) SwipeTrace.appendToPrefix(session.prefix, raw) else ranked.firstOrNull()?.prefix ?: SwipeTrace.appendToPrefix(session.prefix, raw)
                    keyboardView?.keyWeights = if (settings.bool(Prefs.KEY_TINT) || settings.bool(Prefs.SMART_TARGETS)) model.nextLetters(prefix, prev) else emptyMap()
                    val words = ranked.map { glideCase(session, it.word) }
                    glideCandidates = (words + if (session.letters) model.closest(prefix, prev) else emptyList()).distinct()
                    glideExplore = if (session.letters) model.explore(prefix, prev) else words.drop(8)
                    if (final) {
                        if (!session.letters) finishGuess(session, ranked, raw)
                        else { liveGlide = null; updateSuggestions() }
                    } else keyboardView?.setSuggestions(glideExplore, glideCandidates.take(8), if (session.letters) prefix else "")
                }
            }
        }
    }

    private fun finishGuess(session: GlideSession, matches: List<GestureDecoder.Candidate>, raw: String, pending: Boolean = false) {
        val ic = currentInputConnection ?: return
        val word = glideCase(session, matches.firstOrNull()?.word ?: SwipeTrace.appendToPrefix(session.prefix, raw))
        ic.beginBatchEdit()
        ic.finishComposingText()
        val remove = session.shown?.let { it.length + if (session.spaced) 1 else 0 } ?: session.prefix.length
        if (remove > 0) ic.deleteSurroundingText(remove, 0)
        editorRead = null
        ic.commitText(word, 1); editorRead = null
        clearAutomaticSpace()
        session.spaced = automaticSpace(word)
        glideWord = word; glideBefore = editor().before.takeLast(64)
        session.shown = word; session.expected = glideBefore.orEmpty()
        if (!pending) liveGlide = null
        if (state.shift && !state.capsLock) state.shift = false
        ic.endBatchEdit()
        updateAutoCap(); updateSuggestions(); keyboardView?.refreshInputState()
    }

    private fun clearGlide() { glideWord = null; glideBefore = null; glideCandidates = emptyList(); glideExplore = emptyList() }

    private fun currentGlide(): Boolean {
        if (glideWord == null) return false
        if (currentInputConnection == null) return false
        if (!editor().selected && editor().before.takeLast(64) == glideBefore) return true
        clearGlide()
        return false
    }

    private fun finishGlideLearning() {
        if (!currentGlide() || !canLearnNow() || !modelReady) return
        val before = editor().before.takeLast(64)
        val context = if (hasAutomaticSpace()) before.dropLast(1) else before
        predictor.observe(glideWord!!, previousWord(context), previousWord2(context))
    }

    private fun pickVoiceIme() {
        onChoiceInterrupted()
        val manager = getSystemService(InputMethodManager::class.java)
        if (Build.VERSION.SDK_INT >= 28) {
            for (ime in manager.enabledInputMethodList) {
                val subtype = manager.getEnabledInputMethodSubtypeList(ime, true).firstOrNull { it.mode == "voice" } ?: continue
                switchInputMethod(ime.id, subtype); return
            }
        }
        manager.showInputMethodPicker()
    }

    override fun onPickIme() {
        onChoiceInterrupted()
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    /** A drag in the keyboard's own editor (the gear) changed the layout. */
    override fun onEdited() {
        clearTaps()
        grid = keyboardView?.grid ?: return
        if (modelReady) predictor.setLayout(grid, keySpots)
        val library = LayoutLibrary(settings) { resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        val key = editingLayoutKey?.takeIf { it == library.inUse }
        if (key != null) library.edit(key, grid) else settings.saveGrid(grid)
    }

    override fun onEditDone() {
        if (editingLayoutKey == null) return
        onEdited()
        editingLayoutKey = null
        keyboardView?.editTitle = null
        onQuickOptions()
    }

    override fun onOpenSettings() {
        onChoiceInterrupted()
        requestHideSelf(0)
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- Typing ----

    private fun commit(text: String, typing: Boolean = false, distances: FloatArray? = null) {
        val ic = currentInputConnection ?: return
        if (currentGlide()) {
            if (text.firstOrNull()?.isLetter() == true && !hasAutomaticSpace()) automaticSpace(glideWord!!)
        }
        clearGlide()
        val auto = hasAutomaticSpace()
        val edit = TextInputRules.punctuation(text, editor().before, editor().after, auto,
            settings.bool(Prefs.AUTOMATIC_PUNCTUATION), !suggest, settings.bool(Prefs.AUTOMATIC_SPACE) && !settings.bool(Prefs.SWIPE_LETTERS))
        clearAutomaticSpace()
        if (edit != null && !editor().selected) {
            clearTaps()
            ic.beginBatchEdit()
            if (edit.deleteBefore > 0) ic.deleteSurroundingText(edit.deleteBefore, 0)
            if (edit.insert.isNotEmpty()) ic.commitText(edit.insert, 1)
            editorRead = null
            if (edit.move != 0) {
                val live = readCursor()
                if (live.known) ic.setSelection(live.end + edit.move, live.end + edit.move)
                else if (edit.move < 0) sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
                else sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                editorRead = null
            }
            ic.endBatchEdit()
            if (edit.insert.endsWith(" ")) rememberAutomaticSpace(null)
            return
        }
        val prefix = currentWord(editor().before.takeLast(64))
        val cursor = tapCursor ?: selEnd
        val ch = text.singleOrNull()
        val track = typing && ch != null && (ch.isLetter() || ch.isDigit() && prefix.isNotEmpty()) &&
            !editor().selected
        if (track) taps.append(prefix, text, distances) else clearTaps()
        ic.commitText(text, 1)
        editorRead = null
        if (track) rememberTapContext(cursor + text.length)
        if (state.shift && !state.capsLock) state.shift = false
    }

    /** On ABC: tap for a one-shot capital, double-tap for caps lock, tap again to clear. Elsewhere: switch layer. */
    private fun tapLayerKey(code: String) {
        if (code == Keys.ABC && state.layer == Layer.ABC) {
            val now = SystemClock.uptimeMillis()
            when {
                state.capsLock -> { state.capsLock = false; state.shift = false }
                state.shift && now - lastAbcTap < DOUBLE_TAP_MS -> { state.capsLock = true; state.shift = false }
                else -> state.shift = !state.shift
            }
            lastAbcTap = now
        } else {
            state.layer = KeyText.layerOf(code)
            state.shift = false
        }
    }

    private fun typeSpace(learnWord: Boolean = true) {
        val ic = currentInputConnection ?: return
        val before = editor().before.takeLast(64)
        val word = currentWord(before)
        val picked = before == protectedBefore && editor().after.take(64) == protectedAfter
        val endedWord = if (currentGlide()) glideWord.orEmpty() else word
        val learningContext = if (hasAutomaticSpace()) before.dropLast(1) else before
        clearTaps()
        clearGlide()
        val now = SystemClock.uptimeMillis()
        clearAutomaticSpace()
        if (state.layer == Layer.ABC && TextInputRules.doubleSpace(settings.bool(Prefs.AUTOMATIC_PUNCTUATION) && settings.bool(Prefs.DOUBLE_SPACE_PERIOD), before, now - lastSpaceAt, !suggest)) {
            val last2 = editor().before.takeLast(2)
            if (last2.length == 2 && last2[1] == ' ' && last2[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                editorRead = null
                ic.commitText(". ", 1)
                editorRead = null
                lastSpaceAt = 0L
                return
            }
        }
        ic.commitText(" ", 1)
        editorRead = null
        lastSpaceAt = now
        if (learnWord) deferLearning(endedWord, previousWord(learningContext), previousWord2(learningContext), picked)
    }

    private fun pressEnter() {
        clearTaps()
        pendingLearning = null
        clearGlide()
        val info = currentInputEditorInfo ?: return
        val action = editorAction(info)
        if (action == null) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) else currentInputConnection?.performEditorAction(action)
        editorRead = null
    }

    /** The field's action for the enter key (Go, Send…), or null when enter should just be enter. */
    private fun editorAction(info: EditorInfo): Int? {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return null
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        return if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) null else action
    }

    private fun enterLabel(info: EditorInfo) = when (editorAction(info)) {
        EditorInfo.IME_ACTION_GO -> "Go"
        EditorInfo.IME_ACTION_SEARCH -> "Search"
        EditorInfo.IME_ACTION_SEND -> "Send"
        EditorInfo.IME_ACTION_NEXT -> "Next"
        EditorInfo.IME_ACTION_DONE -> "Done"
        EditorInfo.IME_ACTION_PREVIOUS -> "Back"
        else -> null
    }

    private fun currentWord(before: CharSequence?): String = taps.prefix(before)

    private fun previousWord(before: CharSequence?): String? {
        if (before == null) return null
        var end = before.length - currentWord(before).length
        while (end > 0) {
            val ch = before[end - 1]
            if (ch == '.' || ch == '!' || ch == '?' || ch == '\n' || ch == '\r') return null
            if (ch.isLetter() || ch == '\'') break
            end--
        }
        var start = end
        while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
        if (start == end || start == 0 && before.length == 64) return null
        return before.subSequence(start, end).toString().lowercase()
    }

    private fun previousWord2(before: CharSequence?): String? {
        if (before == null) return null
        var end = before.length - currentWord(before).length
        repeat(2) { index ->
            while (end > 0) {
                val ch = before[end - 1]
                if (ch == '.' || ch == '!' || ch == '?' || ch == '\n' || ch == '\r') return null
                if (ch.isLetter() || ch == '\'') break
                end--
            }
            var start = end
            while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
            if (start == end || start == 0 && before.length == 64) return null
            if (index == 1) return before.subSequence(start, end).toString().lowercase()
            end = start
        }
        return null
    }

    private fun updateSuggestions(letterCommitted: Boolean = false) = editorPhase { suggestionsPhase(letterCommitted) }

    private fun suggestionsPhase(letterCommitted: Boolean) {
        if (!suggest || !modelReady) {
            deadEnd = false
            keyboardView?.keyWeights = emptyMap()
            keyboardView?.setSuggestions(emptyList(), emptyList(), "")
            return
        }
        validateTaps()
        validateChoice()
        if (!settings.bool(Prefs.SWIPE_TYPING)) clearGlide()
        val read = editor()
        renderedEditor = read; renderedLayer = state.layer
        val before = read.before.takeLast(64)
        val prefix = currentWord(before)
        taps.sync(prefix)
        val isDeadEnd = predictor.isDeadEnd(prefix)
        if (letterCommitted && suggest && settings.bool(Prefs.TYPO_BUZZ) && settings.bool(Prefs.HAPTICS) && isDeadEnd && !deadEnd) {
            keyboardView?.typoBuzz()
        }
        deadEnd = isDeadEnd
        val view = keyboardView ?: return
        if (state.layer != Layer.ABC) {
            view.keyWeights = emptyMap()
            val (explore, closest) = predictor.symbols(before.lastOrNull())
            view.setSuggestions(explore, closest, "")
            return
        }
        val undo = armedUndo
        if (undo?.correction == true && read.before.takeLast(64) == undo.before && read.after.take(64) == undo.after) {
            view.keyWeights = emptyMap()
            view.setSuggestions(emptyList(), listOf(undo.original) + predictor.closest("", currentWord(read.before.trimEnd())), "")
            return
        }
        if (hasAutomaticSpace() && autoWord != null && settings.bool(Prefs.AUTOMATIC_PUNCTUATION)) {
            predictor.updateContext(editor().before, "")
            view.keyWeights = if (settings.bool(Prefs.KEY_TINT) || settings.bool(Prefs.SMART_TARGETS)) predictor.nextLetters("", autoWord?.lowercase()) else emptyMap()
            val forms = TextInputRules.wordForms(autoWord.orEmpty(), predictor::hasWord)
            view.setSuggestions(predictor.explore("", autoWord?.lowercase()), (forms + glideCandidates + predictor.closest("", autoWord?.lowercase())).distinct().take(8), "")
            return
        }
        if (currentGlide()) {
            view.keyWeights = if (settings.bool(Prefs.KEY_TINT) || settings.bool(Prefs.SMART_TARGETS)) predictor.nextLetters(glideWord.orEmpty(), previousWord(before)) else emptyMap()
            predictor.updateContext(editor().before, "")
            view.setSuggestions(glideExplore, glideCandidates.take(8), "")
            return
        }
        val prev = previousWord(before)
        val prev2 = previousWord2(before)
        predictor.updateContext(if (predictor.language == "en") editor().before else before, prefix)
        view.keyWeights = if (settings.bool(Prefs.KEY_TINT) || settings.bool(Prefs.SMART_TARGETS)) predictor.nextLetters(prefix, prev, prev2, taps) else emptyMap()
        view.setSuggestions(predictor.explore(prefix, prev, prev2, taps = taps), predictor.closest(prefix, prev, prev2, taps = taps), prefix)
    }

    /** Turns on a one-shot capital at the start of a sentence, when the field asks for it. */
    private fun updateAutoCap() {
        if (!suggest || !settings.bool(Prefs.AUTO_CAP) || state.layer != Layer.ABC || state.capsLock) return
        val info = currentInputEditorInfo ?: return
        val caps = if (info.inputType != InputType.TYPE_NULL) currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0 else 0
        val shift = caps != 0
        if (state.shift != shift) { state.shift = shift; keyboardView?.refreshInputState() }
    }

    private companion object {
        const val DOUBLE_SPACE_MS = 600L
        const val DOUBLE_TAP_MS = 350L
    }
}

/** Punctuation that ends a sentence and triggers whole-sentence re-decoding. */
private const val SENTENCE_ENDS = ".?!"
