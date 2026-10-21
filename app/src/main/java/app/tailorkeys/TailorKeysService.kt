package app.tailorkeys

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
            predictor.setLayout(grid)
        } else if (loading.add(lang)) {
            loader.execute {
                val loaded = makeModel(lang)
                main.post {
                    if (!destroyed) {
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
        if (!TextInputRules.addSpace(settings.bool(Prefs.AUTOMATIC_SPACE), editor().after, !suggest)) return false
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
    private var editingLayoutKey: String? = null
    private val state = InputState()
    private var grid: KeyGrid = KeyGrid.default()
    private var lastSpaceAt = 0L
    private var lastAbcTap = 0L
    private var selStart = 0
    private var selEnd = 0
    private var selectAnchor: Int? = null
    /** False only in password fields: suggestions show everywhere else, including fields that don't say what they hold. */
    private var suggest = true
    /** True only in ordinary text fields that allow suggestions: the only place words are learned. */
    private var learn = true
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
    private data class ArmedUndo(val original: String, val inserted: String, val separator: String,
        val before: String?, val after: String?, val learn: () -> Unit)
    private var armedUndo: ArmedUndo? = null

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
        if (learn) undo?.learn?.invoke()
    }

    override fun onChoiceInterrupted() { clearChoice(); finishArmedLearning() }

    private fun validateChoice() {
        if (chosenBefore == null) return
        val read = editor()
        if (!settings.bool(Prefs.SPACE_ACCEPTS) || read.before.takeLast(64) != chosenBefore ||
            read.after.take(64) != chosenAfter || read.selected) clearChoice()
    }

    private fun undoArmedCommit(): Boolean {
        val undo = armedUndo ?: return false
        val ic = currentInputConnection ?: return false
        if (editor().before.takeLast(64) != undo.before ||
            editor().after.take(64) != undo.after || editor().selected) return false
        armedUndo = null // Reverting discards the deferred learning increment.
        clearTaps()
        clearChoice(); clearGlide()
        ic.beginBatchEdit()
        ic.deleteSurroundingText(undo.inserted.length, 0)
        editorRead = null
        ic.commitText(undo.original + undo.separator, 1)
        editorRead = null
        ic.endBatchEdit()
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
        val shouldLearn = learn && modelReady
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
        armedUndo = ArmedUndo(original + originalSuffix, inserted, separator,
            editor().before.takeLast(64), editor().after.take(64)) {
            if (EditorRules.deferredLearning(shouldLearn, learn)) model?.learn(word, prev, prev2)
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
                if (modelReady) predictor.setLayout(g)
                keyboardView?.grid = g
            }
        }
        keyboardView?.reloadSettings()
        quickOptions?.refresh()
        updateSuggestions()
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        pendingLanguage = pendingLanguage || key == null || key == Prefs.LANGUAGES || key == Prefs.CURRENT_LANGUAGE
        pendingLayout = pendingLayout || key == null || key == Prefs.LAYOUT
        main.removeCallbacks(applyPreferences)
        main.post(applyPreferences)
    }

    override fun onCreate() {
        super.onCreate()
        settings = KeyboardSettings(this)
        state.language = settings.currentLanguage()
        grid = settings.loadGrid()
        activateLanguage()
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
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
                    if (modelReady) predictor.setLayout(grid)
                    keyboard.editMode = true
                    keyboard.announceForAccessibility(title)
                },
                language = { onSwitchLanguage(1) },
                allSettings = { closeQuickOptions(); onOpenSettings() }).apply {
                visibility = View.INVISIBLE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            // The IME recreates its input view for configuration/density changes. Theme colours are
            // refreshed in place, so neither a gear tap nor a theme tap constructs a view hierarchy.
            quickOptions = panel
            it.addView(panel, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
    }

    override fun onQuickOptions() {
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

    // Landscape "extract mode" covers the app with a full-screen text box. Keep the app visible instead.
    override fun onEvaluateFullscreenMode() = false

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
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
        keyboardView?.glideAllowed = suggest
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
                editor().after.take(64) != undo.after) finishArmedLearning()
        }
        selStart = newSelStart
        selEnd = newSelEnd
        if (newSelStart != newSelEnd) clearGlide()
        updateAutoCap()
        // The commit phase has already rendered this exact live snapshot in the common case.
        if (editor() != renderedEditor || state.layer != renderedLayer) updateSuggestions()
    }

    // ---- KeyboardView.Listener ----

    override fun onChord(word: String) = editorPhase { onChordPhase(word) }

    private fun onChordPhase(word: String) {
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

    override fun onKey(code: String) { handleKey(code, null) }

    override fun onTapKey(code: String, distances: FloatArray) { handleKey(code, distances) }

    private fun handleKey(code: String, distances: FloatArray?) = editorPhase { handleKeyPhase(code, distances) }

    private fun handleKeyPhase(code: String, distances: FloatArray?) {
        liveGlide = null
        validateTaps()
        if (code == Keys.BACKSPACE && undoArmedCommit()) {
            updateSuggestions(); keyboardView?.refreshInputState(); return
        }
        finishArmedLearning()
        val separator = if (code == Keys.SPACE) " " else code.takeIf { it.length == 1 && it[0] in ".,?!;:" }
        if (separator != null && commitArmed(separator)) {
            updateSuggestions(); keyboardView?.refreshInputState(); return
        }
        clearChoice()
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
        updateSuggestions(letterCommitted)
        keyboardView?.refreshInputState()
    }

    override fun onSelectMode(on: Boolean) {
        clearTaps()
        onChoiceInterrupted()
        val live = if (on) readCursor() else null
        selectAnchor = live?.takeIf { it.known }?.start
    }

    override fun onCursor(steps: Int, select: Boolean) {
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

    override fun onLongPressText(text: String) = editorPhase { onLongPressTextPhase(text) }

    private fun onLongPressTextPhase(text: String) {
        onChoiceInterrupted()
        commit(text)
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
    override fun onSuggestion(text: String) = editorPhase { suggestionPhase(text) }

    private fun suggestionPhase(text: String) {
        onChoiceInterrupted()
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
        val plan = TextInputRules.replacement(original.length, read.after, settings.bool(Prefs.AUTOMATIC_SPACE) && suggest,
            read.selected, if (spaced) original.length else 0)
        clearTaps(); clearGlide(); clearAutomaticSpace()
        ic.beginBatchEdit()
        if (!read.selected) ic.deleteSurroundingText(plan.before, plan.after)
        editorRead = null
        ic.commitText(text, 1); editorRead = null
        if (plan.space) automaticSpace(text)
        ic.endBatchEdit()
        if (learn && modelReady) predictor.learn(text, prev, prev2)
        updateAutoCap(); updateSuggestions()
    }

    override fun onGlideStart() = editorPhase {
        onChoiceInterrupted()
        val read = editor()
        val prefix = if (currentGlide() || read.selected) "" else currentWord(read.before.takeLast(64))
        clearGlide(); clearTaps()
        liveGlide = GlideSession(prefix, read.before.takeLast(64), read.after.take(64), read.before,
            settings.bool(Prefs.SWIPE_LETTERS), state.capsLock || (settings.bool(Prefs.AUTO_CAP) &&
                (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0), state.shift)
    }

    override fun onGlideCancel() {
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
        val session = liveGlide ?: return@editorPhase
        if (session.letters) showRaw(session, raw)
        if (modelReady && suggest && !liveBusy) decodeGlide(session, points, keys, keyWidth, raw, hints, false)
    }

    override fun onGlide(points: List<GestureDecoder.Point>, keys: Map<Char, GestureDecoder.Point>, keyWidth: Float,
                         raw: String, hints: List<GestureDecoder.DoubleHint>) = editorPhase {
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
            decoder.addWords(scoring.words); decoder.setKeys(keys, width)
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
        if (!currentGlide() || !learn || !modelReady) return
        val before = editor().before.takeLast(64)
        val context = if (hasAutomaticSpace()) before.dropLast(1) else before
        predictor.learn(glideWord!!, previousWord(context), previousWord2(context))
    }

    override fun onPickIme() {
        onChoiceInterrupted()
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    /** A drag in the keyboard's own editor (the gear) changed the layout. */
    override fun onEdited() {
        clearTaps()
        grid = keyboardView?.grid ?: return
        if (modelReady) predictor.setLayout(grid)
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
            else if (text.any { !it.isLetterOrDigit() && it != '\'' } && learn && modelReady) {
                finishGlideLearning()
            }
        }
        clearGlide()
        val auto = hasAutomaticSpace()
        val edit = TextInputRules.punctuation(text, editor().before, editor().after, auto,
            settings.bool(Prefs.AUTOMATIC_PUNCTUATION), !suggest)
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
        if (learnWord && currentGlide()) finishGlideLearning()
        else if (learnWord && learn && modelReady && word.length > 1) predictor.learn(word, previousWord(before), previousWord2(before))
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
    }

    private fun pressEnter() {
        clearTaps()
        finishGlideLearning()
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
