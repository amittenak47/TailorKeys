package app.tailorkeys

import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
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

class TailorKeysService : InputMethodService(), KeyboardView.Listener {

    private lateinit var settings: KeyboardSettings
    private lateinit var predictor: Predictor
    private var keyboardView: KeyboardView? = null
    private val state = InputState()
    private var grid: KeyGrid = KeyGrid.default()
    private var lastSpaceAt = 0L
    private var lastAbcTap = 0L
    /** False in password and no-suggestion fields: nothing is suggested or learned there. */
    private var learn = true

    // Any settings change, including from the settings screen while the keyboard is open, applies at once.
    // A layout the keyboard saved itself is already showing; reloading it would wipe the undo history.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == Prefs.LAYOUT) {
            val g = settings.loadGrid()
            if (g.toJson() != grid.toJson()) {
                grid = g
                keyboardView?.grid = g
            }
        }
        keyboardView?.reloadSettings()
    }

    override fun onCreate() {
        super.onCreate()
        settings = KeyboardSettings(this)
        predictor = Predictor(this)
        grid = settings.loadGrid()
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDestroy() {
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        predictor.save()
        super.onDestroy()
    }

    override fun onCreateInputView(): View = KeyboardView(this, settings).also {
        it.listener = this
        it.state = state
        it.grid = grid
        it.inKeyboard = true
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
        learn = cls == InputType.TYPE_CLASS_TEXT && !password && info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
        lastSpaceAt = 0L
        keyboardView?.let {
            it.enterLabel = enterLabel(info)
            it.reloadSettings()
        }
        updateAutoCap()
        updateSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyboardView?.let { it.cancelTouches(); it.editMode = false }
        predictor.save()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoCap()
        updateSuggestions()
    }

    // ---- KeyboardView.Listener ----

    override fun onKey(code: String) {
        when {
            code == Keys.BACKSPACE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            code == Keys.SPACE -> typeSpace()
            code == Keys.ENTER -> pressEnter()
            KeyText.isLayerKey(code) -> tapLayerKey(code)
            else -> KeyText.output(code, state)?.let { commit(it) }
        }
        keyboardView?.invalidate()
    }

    override fun onLongPressText(text: String) = commit(text)

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
        if (state.layer != Layer.ABC) {
            ic.commitText(text, 1)
            return
        }
        val prefix = currentWord(ic.getTextBeforeCursor(48, 0))
        if (prefix.isNotEmpty()) ic.deleteSurroundingText(prefix.length, 0)
        ic.commitText("$text ", 1)
        if (learn) predictor.learn(text)
    }

    override fun onPickIme() {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    /** A drag in the keyboard's own editor (the gear) changed the layout. */
    override fun onEdited() {
        grid = keyboardView?.grid ?: return
        settings.saveGrid(grid)
    }

    override fun onOpenSettings() {
        requestHideSelf(0)
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---- Typing ----

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1) ?: return
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
        val before = ic.getTextBeforeCursor(48, 0)
        val word = currentWord(before)
        if (learn && word.length > 1) predictor.learn(word)
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

    private fun updateSuggestions() {
        val view = keyboardView ?: return
        if (!learn) { view.setSuggestions(emptyList(), emptyList(), ""); return }
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)
        if (state.layer != Layer.ABC) {
            val (explore, closest) = predictor.symbols(before?.lastOrNull())
            view.setSuggestions(explore, closest, "")
            return
        }
        val prefix = currentWord(before)
        view.setSuggestions(predictor.explore(prefix), predictor.closest(prefix), prefix)
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
