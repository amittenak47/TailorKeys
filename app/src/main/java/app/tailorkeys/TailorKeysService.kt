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
            clearGlide()
            lastSpaceAt = 0L
            deadEnd = false
            keyboardView?.cancelTouches()
        }
        val g = settings.loadGrid()
        if (g.toJson() != grid.toJson()) { grid = g; keyboardView?.grid = g }
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

    // Any settings change, including from the settings screen while the keyboard is open, applies at once.
    // A layout the keyboard saved itself is already showing; reloading it would wipe the undo history.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.LANGUAGES || key == Prefs.CURRENT_LANGUAGE || key == null) activateLanguage()
        if (key == null || key == Prefs.LAYOUT) {
            val g = settings.loadGrid()
            if (g.toJson() != grid.toJson()) {
                grid = g
                predictor.setLayout(g)
                keyboardView?.grid = g
            }
        }
        keyboardView?.reloadSettings()
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
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        models.values.forEach { it.predictor.save() }
        destroyed = true
        loader.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View = KeyboardView(this, settings).also {
        it.chordLibrary = models[state.language]?.chords
        it.listener = this
        it.state = state
        it.grid = grid
        it.inKeyboard = true
        it.glideAllowed = suggest
        keyboardView = it
    }

    // Landscape "extract mode" covers the app with a full-screen text box. Keep the app visible instead.
    override fun onEvaluateFullscreenMode() = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
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
        keyboardView?.let { it.cancelTouches(); it.editMode = false }
        models.values.forEach { it.predictor.save() }
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        if (newSelStart != newSelEnd) clearGlide()
        updateAutoCap()
        updateSuggestions()
    }

    // ---- KeyboardView.Listener ----

    override fun onChord(word: String) {
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

    override fun onKey(code: String) {
        var letterCommitted = false
        when {
            code == Keys.BACKSPACE -> { clearGlide(); sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
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
                    commit(it)
                    letterCommitted = it.length == 1 && it[0].isLetter()
                }
            }
        }
        updateSuggestions(letterCommitted)
        keyboardView?.invalidate()
    }

    override fun onSelectMode(on: Boolean) {
        if (on) {
            selectAnchor = selStart
            selectFocus = selEnd
            selectTotal = selEnd + (currentInputConnection?.getTextAfterCursor(10000, 0)?.length ?: 0)
        } else {
            selectAnchor = null
        }
    }

    override fun onCursor(steps: Int, select: Boolean) {
        clearGlide()
        if (select) {
            val anchor = selectAnchor ?: return
            selectFocus = (selectFocus + steps).coerceIn(0, selectTotal)
            currentInputConnection?.setSelection(anchor, selectFocus)
            return
        }
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun onReplacePressText(original: String, following: String, replacement: String) {
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
        commit(text)
        updateSuggestions(text.any { it.isLetter() })
        keyboardView?.invalidate()
    }

    override fun onSwitchLanguage(direction: Int) {
        val enabled = settings.languages()
        if (enabled.size < 2) return
        val index = enabled.indexOf(settings.currentLanguage())
        settings.setCurrentLanguage(enabled[(index + direction + enabled.size) % enabled.size])
        keyboardView?.announceForAccessibility(Prefs.LANGUAGE_NAMES.getValue(settings.currentLanguage()))
    }

    /** A swipe key landed on a new face. Layer faces switch the layout straight away. */
    override fun onStackFace(code: String) {
        if (KeyText.isLayerKey(code)) {
            state.layer = KeyText.layerOf(code)
            state.shift = false
            updateSuggestions()
        }
        keyboardView?.invalidate()
    }

    /** A word from a wheel replaces the word being typed. A symbol is just inserted. */
    override fun onSuggestion(text: String) {
        val ic = currentInputConnection ?: return
        if (state.layer == Layer.ABC && currentGlide()) {
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
        if (prefix.isNotEmpty()) ic.deleteSurroundingText(prefix.length, 0)
        ic.commitText("$text ", 1)
        if (learn && modelReady) predictor.learn(text, prev, previousWord2(before))
    }

    override fun onGlide(points: List<GestureDecoder.Point>, keys: Map<Char, GestureDecoder.Point>, keyWidth: Float) {
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
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    /** A drag in the keyboard's own editor (the gear) changed the layout. */
    override fun onEdited() {
        grid = keyboardView?.grid ?: return
        predictor.setLayout(grid)
        settings.saveGrid(grid)
    }

    override fun onOpenSettings() {
        requestHideSelf(0)
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- Typing ----

    private fun commit(text: String) {
        val ic = currentInputConnection ?: return
        if (currentGlide()) {
            if (text.firstOrNull()?.isLetter() == true) typeSpace()
            else if (text.any { !it.isLetterOrDigit() && it != '\'' } && learn && modelReady) {
                predictor.learn(glideWord!!, previousWord(ic.getTextBeforeCursor(64, 0)))
            }
        }
        clearGlide()
        ic.commitText(text, 1)
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

    private fun typeSpace() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)
        val word = currentWord(before)
        if (learn && modelReady && word.length > 1) predictor.learn(word, previousWord(before), previousWord2(before))
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

    private fun currentWord(before: CharSequence?): String {
        if (before == null) return ""
        var i = before.length
        while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'')) i--
        return before.subSequence(i, before.length).toString()
    }

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
        if (!modelReady) {
            keyboardView?.keyWeights = emptyMap()
            keyboardView?.setSuggestions(emptyList(), emptyList(), "")
            return
        }
        if (!settings.bool(Prefs.SWIPE_TYPING)) clearGlide()
        val before = currentInputConnection?.getTextBeforeCursor(64, 0)
        val prefix = currentWord(before)
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
        view.keyWeights = if (settings.bool(Prefs.KEY_TINT) || settings.bool(Prefs.SMART_TARGETS)) predictor.nextLetters(prefix, prev, prev2) else emptyMap()
        view.setSuggestions(predictor.explore(prefix, prev, prev2), predictor.closest(prefix, prev, prev2), prefix)
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
