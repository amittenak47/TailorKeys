package app.tailorkeys

import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import app.tailorkeys.layout.InputState
import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.KeyText
import app.tailorkeys.layout.Keys
import app.tailorkeys.layout.Layer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    private var glideWord: String? = null
    private var glideBefore: String? = null
    private var glideCandidates = emptyList<String>()
    private var glideExplore = emptyList<String>()
    private var keyboardView: KeyboardView? = null
    private var keyboardContainer: android.widget.FrameLayout? = null
    private var quickOptions: QuickOptions? = null
    private val state = InputState()
    private var grid: KeyGrid = KeyGrid.default()
    private var lastSpaceAt = 0L
    private var lastAbcTap = 0L
    private var selStart = 0
    private var selEnd = 0
    private var selectAnchor: Int? = null
    private var selectFocus = 0
    private var selectTotal = 0
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

    private fun rememberTapContext(cursor: Int) {
        tapBefore = currentInputConnection?.getTextBeforeCursor(64, 0)?.toString()
        tapAfter = currentInputConnection?.getTextAfterCursor(64, 0)?.toString()
        tapCursor = cursor
    }

    private fun validateTaps() {
        val ic = currentInputConnection
        if (tapBefore != null && (ic?.getTextBeforeCursor(64, 0)?.toString() != tapBefore ||
            ic?.getTextAfterCursor(64, 0)?.toString() != tapAfter || !ic?.getSelectedText(0).isNullOrEmpty())) clearTaps()
    }
    private var chosenBefore: String? = null
    private var chosenAfter: String? = null
    private var chosenSelection = 0 to 0
    private data class ArmedUndo(val original: String, val inserted: String, val separator: String,
        val before: String?, val after: String?, val learn: () -> Unit)
    private var armedUndo: ArmedUndo? = null

    override fun onWheelChosen() {
        val ic = currentInputConnection ?: return
        chosenBefore = ic.getTextBeforeCursor(64, 0)?.toString()
        chosenAfter = ic.getTextAfterCursor(64, 0)?.toString()
        chosenSelection = selStart to selEnd
    }

    private fun clearChoice() {
        keyboardView?.clearArmedWord()
        chosenBefore = null; chosenAfter = null
    }

    private fun finishArmedLearning() {
        val undo = armedUndo
        armedUndo = null
        undo?.learn?.invoke()
    }

    override fun onChoiceInterrupted() { clearChoice(); finishArmedLearning() }

    private fun validateChoice() {
        val ic = currentInputConnection
        if (ic == null || !settings.bool(Prefs.SPACE_ACCEPTS) ||
            ic.getTextBeforeCursor(64, 0)?.toString() != chosenBefore ||
            ic.getTextAfterCursor(64, 0)?.toString() != chosenAfter || !ic.getSelectedText(0).isNullOrEmpty()) clearChoice()
    }

    private fun undoArmedCommit(): Boolean {
        val undo = armedUndo ?: return false
        val ic = currentInputConnection ?: return false
        if (ic.getTextBeforeCursor(64, 0)?.toString() != undo.before ||
            ic.getTextAfterCursor(64, 0)?.toString() != undo.after || !ic.getSelectedText(0).isNullOrEmpty()) return false
        armedUndo = null // Reverting discards the deferred learning increment.
        clearTaps()
        clearChoice(); clearGlide()
        ic.beginBatchEdit()
        ic.deleteSurroundingText(undo.inserted.length, 0)
        ic.commitText(undo.original + undo.separator, 1)
        ic.endBatchEdit()
        updateAutoCap()
        return true
    }

    private fun commitArmed(separator: String): Boolean {
        validateChoice()
        if (state.layer != Layer.ABC || !settings.bool(Prefs.SPACE_ACCEPTS)) return false
        val word = keyboardView?.armedWord ?: return false
        val ic = currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(64, 0)?.toString()
        val original = if (currentGlide()) glideWord!! else currentWord(before)
        val model = predictor
        val shouldLearn = learn && modelReady
        val prev = previousWord(before)
        val prev2 = previousWord2(before)
        clearChoice(); clearGlide()
        ic.beginBatchEdit()
        if (original.isNotEmpty()) ic.deleteSurroundingText(original.length, 0)
        commit(word)
        if (separator == " ") typeSpace(learnWord = false) else commit(separator)
        ic.endBatchEdit()
        armedUndo = ArmedUndo(original, word + separator, separator,
            ic.getTextBeforeCursor(64, 0)?.toString(), ic.getTextAfterCursor(64, 0)?.toString()) {
            if (shouldLearn) model.learn(word, prev, prev2)
        }
        updateAutoCap()
        return true
    }

    // Any settings change, including from the settings screen while the keyboard is open, applies at once.
    // A layout the keyboard saved itself is already showing; reloading it would wipe the undo history.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.LANGUAGES || key == Prefs.CURRENT_LANGUAGE || key == null) activateLanguage()
        if (key == null || key == Prefs.LAYOUT) {
            val g = settings.loadGrid()
            if (g.toJson() != grid.toJson()) {
                clearTaps()
                grid = g
                predictor.setLayout(g)
                keyboardView?.grid = g
            }
        }
        keyboardView?.reloadSettings()
        quickOptions?.refresh()
        updateSuggestions()
    }

    override fun onCreate() {
        super.onCreate()
        settings = KeyboardSettings(this)
        state.language = settings.currentLanguage()
        val initial = makeModel(state.language)
        models[state.language] = initial
        predictor = initial.predictor
        gestureDecoder = initial.decoder
        modelReady = true
        grid = settings.loadGrid()
        predictor.setLayout(grid)
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        onChoiceInterrupted()
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        models.values.forEach { it.predictor.save() }
        destroyed = true
        loader.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        closeQuickOptions()
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
                for (i in 1 until childCount) getChildAt(i).measure(
                    View.MeasureSpec.makeMeasureSpec(measuredWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(measuredHeight, View.MeasureSpec.EXACTLY))
            }
        }.also {
            keyboardContainer = it
            it.addView(keyboard, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
    }

    override fun onQuickOptions() {
        val container = keyboardContainer ?: return
        val keyboard = keyboardView ?: return
        if (quickOptions != null) return
        onChoiceInterrupted()
        keyboard.cancelTouches()
        keyboard.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val panel = QuickOptions(this, settings,
            landscape = { resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE },
            stripHeight = { keyboard.suggestionStripHeight },
            close = { closeQuickOptions() },
            rearrange = { closeQuickOptions(); keyboard.editMode = true },
            language = { onSwitchLanguage(1) },
            allSettings = { closeQuickOptions(); onOpenSettings() })
        quickOptions = panel
        container.addView(panel, android.widget.FrameLayout.LayoutParams(-1, -1))
        panel.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    private fun closeQuickOptions() {
        quickOptions?.let { keyboardContainer?.removeView(it) }
        quickOptions = null
        keyboardView?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // Landscape "extract mode" covers the app with a full-screen text box. Keep the app visible instead.
    override fun onEvaluateFullscreenMode() = false

    override fun onStartInput(info: EditorInfo, restarting: Boolean) {
        super.onStartInput(info, restarting)
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd
        onChoiceInterrupted()
        clearGlide()
        clearTaps()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        onChoiceInterrupted()
        clearTaps()
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
        suggest = !password
        keyboardView?.glideAllowed = suggest
        learn = cls == InputType.TYPE_CLASS_TEXT && !password && info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
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
        clearTaps()
        onChoiceInterrupted()
        closeQuickOptions()
        keyboardView?.let { it.cancelTouches(); it.editMode = false }
        models.values.forEach { it.predictor.save() }
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (newSelStart != newSelEnd || tapCursor != newSelEnd) clearTaps()
        if (keyboardView?.armedWord != null && chosenSelection != (newSelStart to newSelEnd)) clearChoice()
        armedUndo?.let { undo ->
            val ic = currentInputConnection
            if (ic?.getTextBeforeCursor(64, 0)?.toString() != undo.before ||
                ic?.getTextAfterCursor(64, 0)?.toString() != undo.after) finishArmedLearning()
        }
        selStart = newSelStart
        selEnd = newSelEnd
        if (newSelStart != newSelEnd) clearGlide()
        updateAutoCap()
        updateSuggestions()
    }

    // ---- KeyboardView.Listener ----

    override fun onChord(word: String) {
        clearTaps()
        onChoiceInterrupted()
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        val shifted = state.shift
        if (ic.getTextBeforeCursor(1, 0)?.lastOrNull()?.isLetterOrDigit() == true) typeSpace()
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
        typeSpace()
        ic.endBatchEdit()
        updateAutoCap()
        updateSuggestions()
        keyboardView?.invalidate()
    }

    override fun onKey(code: String) { handleKey(code, null) }

    override fun onTapKey(code: String, distances: FloatArray) { handleKey(code, distances) }

    private fun handleKey(code: String, distances: FloatArray?) {
        validateTaps()
        if (code == Keys.BACKSPACE && undoArmedCommit()) {
            updateSuggestions(); keyboardView?.invalidate(); return
        }
        finishArmedLearning()
        val separator = if (code == Keys.SPACE) " " else code.takeIf { it.length == 1 && it[0] in ".,?!;:" }
        if (separator != null && commitArmed(separator)) {
            updateSuggestions(); keyboardView?.invalidate(); return
        }
        clearChoice()
        var letterCommitted = false
        when {
            code == Keys.BACKSPACE -> {
                clearGlide()
                val ic = currentInputConnection
                val cursor = (tapCursor ?: selEnd) - 1
                val tappedWord = taps.hasTaps(currentWord(ic?.getTextBeforeCursor(64, 0)))
                if (selStart == selEnd) {
                    taps.sync(currentWord(ic?.getTextBeforeCursor(64, 0)))
                    taps.backspace()
                } else clearTaps()
                // Delete a known composing letter directly; key events can be applied after
                // the selection callback and would discard the remaining tap rows.
                if (tappedWord && selStart == selEnd && currentInputEditorInfo?.inputType != InputType.TYPE_NULL) {
                    ic?.deleteSurroundingText(1, 0)
                } else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                rememberTapContext(cursor)
            }
            code == Keys.SPACE -> typeSpace()
            code == Keys.ENTER -> pressEnter()
            KeyText.isLayerKey(code) -> tapLayerKey(code)
            else -> {
                if (state.layer == Layer.ABC && code.length == 1 && code[0].isLetter() && currentGlide()) {
                    val shifted = state.shift
                    typeSpace()
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
        keyboardView?.invalidate()
    }

    override fun onSelectMode(on: Boolean) {
        clearTaps()
        onChoiceInterrupted()
        if (on) {
            selectAnchor = selStart
            selectFocus = selEnd
            selectTotal = selEnd + (currentInputConnection?.getTextAfterCursor(10000, 0)?.length ?: 0)
        } else {
            selectAnchor = null
        }
    }

    override fun onCursor(steps: Int, select: Boolean) {
        clearTaps()
        onChoiceInterrupted()
        clearGlide()
        if (select) {
            val anchor = selectAnchor ?: return
            selectFocus = (selectFocus + steps).coerceIn(0, selectTotal)
            currentInputConnection?.setSelection(anchor, selectFocus)
            return
        }
        val ic = currentInputConnection ?: return
        // Set the position directly, clamped to the text. Arrow keys at either end move focus to the next view,
        // which closes the keyboard. Only fields that hide their text (raw key input) or an unknown cursor fall back.
        if (currentInputEditorInfo?.inputType == InputType.TYPE_NULL || selStart < 0 || selEnd < 0) {
            val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(abs(steps)) { sendDownUpKeyEvents(code) }
            return
        }
        val lo = min(selStart, selEnd)
        val hi = max(selStart, selEnd)
        val total = hi + (ic.getTextAfterCursor(10_000, 0)?.length ?: 0)
        // A selection collapses toward the direction of travel, like arrow keys do.
        val pos = ((if (steps < 0) lo else hi) + steps).coerceIn(0, total)
        ic.setSelection(pos, pos)
        selStart = pos; selEnd = pos // until onUpdateSelection confirms, so fast steps build on each other
    }

    override fun onReplacePressText(original: String, following: String, replacement: String) {
        clearTaps()
        onChoiceInterrupted()
        val ic = currentInputConnection ?: return
        val expected = original + following
        if (ic.getTextBeforeCursor(expected.length, 0)?.toString() != expected) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(expected.length, 0)
        ic.commitText(replacement + following, 1)
        ic.endBatchEdit()
        updateAutoCap()
        updateSuggestions(true)
        keyboardView?.invalidate()
    }

    override fun onLongPressText(text: String) {
        onChoiceInterrupted()
        commit(text)
        updateSuggestions(text.any { it.isLetter() })
        keyboardView?.invalidate()
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
        keyboardView?.invalidate()
    }

    /** A word from a wheel replaces the word being typed. A symbol is just inserted. */
    override fun onSuggestion(text: String) {
        onChoiceInterrupted()
        val ic = currentInputConnection ?: return
        if (state.layer == Layer.ABC && currentGlide()) {
            clearTaps()
            ic.beginBatchEdit()
            ic.deleteSurroundingText(glideWord!!.length, 0)
            ic.commitText(text, 1)
            glideWord = text
            glideBefore = ic.getTextBeforeCursor(64, 0)?.toString()
            ic.endBatchEdit()
            updateSuggestions()
            return
        }
        if (state.layer != Layer.ABC) {
            commit(text)
            updateSuggestions()
            return
        }
        clearGlide()
        val before = ic.getTextBeforeCursor(64, 0)
        val prefix = currentWord(before)
        val prev = previousWord(before)
        val prev2 = previousWord2(before)
        clearTaps()
        if (prefix.isNotEmpty()) ic.deleteSurroundingText(prefix.length, 0)
        ic.commitText("$text ", 1)
        if (learn && modelReady) predictor.learn(text, prev, prev2)
    }

    override fun onGlide(points: List<GestureDecoder.Point>, keys: Map<Char, GestureDecoder.Point>, keyWidth: Float) {
        clearTaps()
        onChoiceInterrupted()
        if (!modelReady || !suggest || !settings.bool(Prefs.SWIPE_TYPING) || state.layer != Layer.ABC) return
        val ic = currentInputConnection ?: return
        gestureDecoder.addWords(predictor.gestureUserWords())
        gestureDecoder.setKeys(keys, keyWidth)
        val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val prev = previousWord(if (before.lastOrNull()?.isLetterOrDigit() == true) "$before " else before)
        val matches = gestureDecoder.decode(points, prev, predictor::gestureFrequency, predictor::contextScore)
        if (matches.isEmpty()) return
        ic.beginBatchEdit()
        // Automatic separators must not consume a manually armed one-shot shift.
        val shifted = state.shift
        if (before.lastOrNull()?.isLetterOrDigit() == true) typeSpace()
        updateAutoCap()
        state.shift = state.shift || shifted
        val allCaps = state.capsLock || (settings.bool(Prefs.AUTO_CAP) &&
            (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0)
        val candidates = matches.map { match ->
            when {
                allCaps -> match.word.uppercase()
                state.shift -> match.word.replaceFirstChar { it.uppercase() }
                else -> match.word
            }
        }
        clearGlide()
        commit(candidates.first())
        glideWord = candidates.first()
        glideCandidates = candidates
        val rnd = kotlin.random.Random(candidates.first().hashCode())
        glideExplore = matches.indices.drop(8).map { i ->
            val u = rnd.nextDouble().coerceAtLeast(1e-12)
            candidates[i] to (matches[i].score / 2.5 - kotlin.math.ln(-kotlin.math.ln(u)))
        }.sortedByDescending { it.second }.map { it.first }
        glideBefore = ic.getTextBeforeCursor(64, 0)?.toString()
        lastSpaceAt = 0L
        ic.endBatchEdit()
        updateSuggestions()
        keyboardView?.invalidate()
    }

    private fun clearGlide() { glideWord = null; glideBefore = null; glideCandidates = emptyList(); glideExplore = emptyList() }

    private fun currentGlide(): Boolean {
        if (glideWord == null) return false
        val ic = currentInputConnection ?: return false
        if (ic.getSelectedText(0).isNullOrEmpty() && ic.getTextBeforeCursor(64, 0)?.toString() == glideBefore) return true
        clearGlide()
        return false
    }

    override fun onPickIme() {
        onChoiceInterrupted()
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    /** A drag in the keyboard's own editor (the gear) changed the layout. */
    override fun onEdited() {
        clearTaps()
        grid = keyboardView?.grid ?: return
        predictor.setLayout(grid)
        settings.saveGrid(grid)
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
            if (text.firstOrNull()?.isLetter() == true) typeSpace()
            else if (text.any { !it.isLetterOrDigit() && it != '\'' } && learn && modelReady) {
                predictor.learn(glideWord!!, previousWord(ic.getTextBeforeCursor(64, 0)))
            }
        }
        clearGlide()
        val prefix = currentWord(ic.getTextBeforeCursor(64, 0))
        val cursor = tapCursor ?: selEnd
        val ch = text.singleOrNull()
        val track = typing && ch != null && (ch.isLetter() || ch.isDigit() && prefix.isNotEmpty()) &&
            ic.getSelectedText(0).isNullOrEmpty()
        if (track) taps.append(prefix, text, distances) else clearTaps()
        ic.commitText(text, 1)
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
            updateSuggestions()
        }
    }

    private fun typeSpace(learnWord: Boolean = true) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)
        val word = currentWord(before)
        if (learnWord && learn && modelReady && word.length > 1) predictor.learn(word, previousWord(before), previousWord2(before))
        clearTaps()
        clearGlide()
        val now = SystemClock.uptimeMillis()
        if (settings.bool(Prefs.DOUBLE_SPACE_PERIOD) && state.layer == Layer.ABC && now - lastSpaceAt < DOUBLE_SPACE_MS) {
            val last2 = ic.getTextBeforeCursor(2, 0)
            if (last2 != null && last2.length == 2 && last2[1] == ' ' && last2[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceAt = 0L
                return
            }
        }
        ic.commitText(" ", 1)
        lastSpaceAt = now
    }

    private fun pressEnter() {
        clearTaps()
        if (currentGlide() && learn && modelReady) predictor.learn(glideWord!!, previousWord(currentInputConnection?.getTextBeforeCursor(64, 0)))
        clearGlide()
        val info = currentInputEditorInfo ?: return
        val action = editorAction(info)
        if (action == null) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER) else currentInputConnection?.performEditorAction(action)
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

    private fun updateSuggestions(letterCommitted: Boolean = false) {
        validateTaps()
        validateChoice()
        if (!modelReady) {
            keyboardView?.keyWeights = emptyMap()
            keyboardView?.setSuggestions(emptyList(), emptyList(), "")
            return
        }
        if (!settings.bool(Prefs.SWIPE_TYPING)) clearGlide()
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)
        val prefix = currentWord(before)
        taps.sync(prefix)
        val isDeadEnd = predictor.isDeadEnd(prefix)
        if (letterCommitted && suggest && settings.bool(Prefs.TYPO_BUZZ) && settings.bool(Prefs.HAPTICS) && isDeadEnd && !deadEnd) {
            keyboardView?.typoBuzz()
        }
        deadEnd = isDeadEnd
        val view = keyboardView ?: return
        if (!suggest) {
            view.keyWeights = emptyMap()
            view.setSuggestions(emptyList(), emptyList(), "")
            return
        }
        if (state.layer != Layer.ABC) {
            view.keyWeights = emptyMap()
            val (explore, closest) = predictor.symbols(before?.lastOrNull())
            view.setSuggestions(explore, closest, "")
            return
        }
        if (currentGlide()) {
            view.keyWeights = emptyMap()
            view.setSuggestions(glideExplore, glideCandidates.take(8), "")
            return
        }
        val prev = previousWord(before)
        val prev2 = previousWord2(before)
        view.keyWeights = if (settings.bool(Prefs.KEY_TINT) || settings.bool(Prefs.SMART_TARGETS)) predictor.nextLetters(prefix, prev, prev2, taps) else emptyMap()
        view.setSuggestions(predictor.explore(prefix, prev, prev2, taps = taps), predictor.closest(prefix, prev, prev2, taps = taps), prefix)
    }

    /** Turns on a one-shot capital at the start of a sentence, when the field asks for it. */
    private fun updateAutoCap() {
        if (!settings.bool(Prefs.AUTO_CAP) || state.layer != Layer.ABC || state.capsLock) return
        val info = currentInputEditorInfo ?: return
        val caps = if (info.inputType != InputType.TYPE_NULL) currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0 else 0
        state.shift = caps != 0
        keyboardView?.invalidate()
    }

    private companion object {
        const val DOUBLE_SPACE_MS = 600L
        const val DOUBLE_TAP_MS = 350L
    }
}
