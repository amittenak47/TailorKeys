package app.tailorkeys.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tailorkeys.CursorControl
import app.tailorkeys.Align
import app.tailorkeys.OnboardingActivity
import app.tailorkeys.RejectedCorrection
import app.tailorkeys.PersonalStore
import app.tailorkeys.Prefs
import app.tailorkeys.R
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.tailorkeys.Chords

internal fun LazyListScope.sizeOptions(state: SettingsState, page: Boolean, onPage: (Boolean) -> Unit) {
    item("orientations") {
        OptionGroup {
            val same = state.bool(Prefs.SAME_ORIENTATIONS)
            ToggleRow("Same size when rotated", same) {
                if (it) state.settings.linkOrientations(from = page) else state.set(Prefs.SAME_ORIENTATIONS, false)
            }
            if (same) {
                Text("Portrait and landscape", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SettingsSegments(listOf("Portrait", "Landscape"), if (page) 1 else 0, "Orientation",
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { onPage(it == 1) }
            }
        }
    }
    item("dimensions") { key(page) { SizeControls(state, page) } }
    item("split") {
        val p = state.profile(page)
        Column {
            GroupTitle("Split")
            OptionGroup {
                ToggleRow("Split keyboard", p.split) { state.settings.setSplitFor(page, it) }
                if (p.split) {
                    SettingSlider("Center gap", p.gap, Prefs.splitGap(page), "${p.gap}%") { state.setFor(page, Prefs::splitGap, it) }
                    SettingSlider("Tilt", p.tilt, Prefs.tilt(page), degrees(p.tilt)) { state.setFor(page, Prefs::tilt, it) }
                    val curve = state.int(Prefs.CURVE)
                    SettingSlider("Curve", curve, Prefs.CURVE, "$curve%") { state.setFor(page, { Prefs.CURVE }, it) }
                }
            }
        }
    }
}

private fun degrees(value: Int) = (if (value > 0) "+" else if (value < 0) "\u2212" else "") + "${kotlin.math.abs(value)}\u00B0"

@Composable
private fun SizeControls(state: SettingsState, page: Boolean) {
    val p = state.profile(page)
    var ratio by remember { mutableFloatStateOf(1f) }
    val captureRatio = { ratio = state.int(Prefs.height(page)).toFloat() / state.int(Prefs.width(page)) }
    val presets = remember(page) { sizePresets(page) }
    val current = presets.firstOrNull {
        it.height == p.heightPercent && it.width == p.widthPercent && it.align == p.align && it.split == p.split
    }?.name ?: "Custom"
    OptionGroup {
        SettingsDropdown("Size", current, presets.map { it.name },
            Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) { index ->
            presets.getOrNull(index)?.let {
                state.setFor(page, Prefs::height, it.height)
                state.setFor(page, Prefs::width, it.width)
                state.settings.setAlignFor(page, it.align)
                state.settings.setSplitFor(page, it.split)
                if (it.split) {
                    state.setFor(page, Prefs::splitGap, it.gap)
                    state.setFor(page, Prefs::tilt, 0)
                }
            }
        }
        SettingSlider("Height", p.heightPercent, Prefs.height(page), "${p.heightPercent}%", onStart = captureRatio) {
            state.setFor(page, Prefs::height, it)
            if (state.bool(Prefs.LOCK_SIZE)) state.setFor(page, Prefs::width, (it / ratio).roundToInt())
        }
        val locked = state.bool(Prefs.LOCK_SIZE)
        SettingSlider("Width", p.widthPercent, Prefs.width(page), "${p.widthPercent}%", onStart = captureRatio, trailing = {
            IconToggleButton(locked, { state.set(Prefs.LOCK_SIZE, it) }, Modifier.size(44.dp),
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                SettingsIcon(if (locked) R.drawable.ic_settings_lock else R.drawable.ic_settings_unlock,
                    if (locked) "Unlock size ratio" else "Lock size ratio", Modifier.size(20.dp))
            }
        }) {
            state.setFor(page, Prefs::width, it)
            if (state.bool(Prefs.LOCK_SIZE)) state.setFor(page, Prefs::height, (it * ratio).roundToInt())
        }
        if (p.widthPercent < 100) {
            Text("Position", Modifier.padding(start = 20.dp, top = 8.dp), style = MaterialTheme.typography.bodyLarge)
            SettingsSegments(listOf("Left", "Center", "Right"), p.align.ordinal, "Position",
                Modifier.padding(horizontal = 20.dp)) { state.settings.setAlignFor(page, Align.entries[it]) }
        }
        SettingSlider("Lift keyboard", p.bottomPaddingDp, Prefs.bottomPadding(page), "${p.bottomPaddingDp} dp") {
            state.setFor(page, Prefs::bottomPadding, it)
        }
    }
}

internal fun LazyListScope.lookOptions(state: SettingsState) {
    item("theme") { ThemeControl(state) }
    item("appearance") {
        OptionGroup {
            for ((label, pref) in listOf("Key spacing" to Prefs.KEY_GAP, "Corner radius" to Prefs.CORNER_RADIUS, "Text size" to Prefs.TEXT_SCALE)) {
                val value = state.int(pref)
                SettingSlider(label, value, pref, if (pref == Prefs.TEXT_SCALE) "$value%" else "$value") { state.set(pref, it) }
            }
            ToggleRow("Use system text size", state.bool(Prefs.FOLLOW_SYSTEM_TEXT)) { state.set(Prefs.FOLLOW_SYSTEM_TEXT, it) }
        }
    }
    item("labels") {
        val context = LocalContext.current
        OptionGroup {
            ToggleRow("Number row", state.bool(Prefs.NUMBER_ROW)) {
                if (!state.setNumberRow(it)) Toast.makeText(context, "Move your keys out of the number row first", Toast.LENGTH_SHORT).show()
            }
            ToggleRow("Key hints", state.bool(Prefs.SHOW_HINTS)) { state.set(Prefs.SHOW_HINTS, it) }
        }
    }
}

internal fun LazyListScope.typingOptions(state: SettingsState, onAbout: () -> Unit, onReset: () -> Unit, onPanel: (String) -> Unit) {
    item("languages") {
        var open by remember { mutableStateOf(false) }
        val enabled = state.languages()
        OptionGroup {
            ActionRow("Languages · " + enabled.joinToString(", ") { Prefs.LANGUAGE_NAMES.getValue(it) }) { open = true }
        }
        if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Languages") },
            text = {
                Column {
                    Prefs.LANGUAGE_NAMES.forEach { (code, name) ->
                        val checked = code in enabled
                        Row(Modifier.fillMaxWidth().selectable(checked, enabled = !checked || enabled.size > 1,
                            role = Role.Checkbox, onClick = { state.setLanguages(if (checked) enabled.toSet() - code else enabled.toSet() + code) }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = null, enabled = !checked || enabled.size > 1)
                            Text(name, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } })
    }
    item("typing") {
        OptionGroup {
            val mode = state.int(Prefs.AUTOCORRECT)
            val modes = listOf("Off", "Confident", "Aggressive")
            val details = listOf(
                "Never changes what you type. Suggestions still appear.",
                "Fixes clear typos: one wrong, missing or swapped letter (teh → the) and missing spaces (ofthe → of the). Real words stay as typed.",
                "Also fixes several slips onto nearby keys (watwe → water), real words that don't fit the sentence (are you form → from) and stray spaces (key board → keyboard).")
            SettingsDropdown("Autocorrect", modes[mode], modes, details = details) { state.set(Prefs.AUTOCORRECT, it) }
            Text(details[mode] + if (mode == 0) "" else " Fixes happen when you press space; backspace right after undoes one.",
                Modifier.padding(20.dp, 8.dp), style = MaterialTheme.typography.bodySmall)
            ToggleRow("Auto-capitalize", state.bool(Prefs.AUTO_CAP)) { state.set(Prefs.AUTO_CAP, it) }
            val vibrator = LocalContext.current.getSystemService(android.os.Vibrator::class.java)
            if (vibrator?.hasVibrator() == true) {
                ToggleRow("Vibrate on tap", state.bool(Prefs.HAPTICS)) { state.set(Prefs.HAPTICS, it) }
            }
            ToggleRow("Sound on keypress", state.bool(Prefs.SOUND)) { state.set(Prefs.SOUND, it) }
            if (state.bool(Prefs.SOUND)) {
                val volume = state.int(Prefs.SOUND_VOLUME)
                SettingSlider("Sound volume", volume, Prefs.SOUND_VOLUME, "$volume%") { state.set(Prefs.SOUND_VOLUME, it) }
            }
            ToggleRow("Automatic space", state.bool(Prefs.AUTOMATIC_SPACE), note = "Add a space after swiped and picked words.") { state.set(Prefs.AUTOMATIC_SPACE, it) }
            ToggleRow("Automatic punctuation", state.bool(Prefs.AUTOMATIC_PUNCTUATION), note = "Attach punctuation and close brackets and quotes.") { state.set(Prefs.AUTOMATIC_PUNCTUATION, it) }
            if (state.bool(Prefs.AUTOMATIC_PUNCTUATION)) {
                ToggleRow("Double-space period", state.bool(Prefs.DOUBLE_SPACE_PERIOD), note = "Tap space twice to end a sentence.") { state.set(Prefs.DOUBLE_SPACE_PERIOD, it) }
            }
        }
    }
    item("learning") {
        val context = LocalContext.current
        var query by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf(false) }
        var version by remember { mutableIntStateOf(0) }
        val words by produceState(emptyList<Pair<String, String>>(), version) {
            value = withContext(Dispatchers.IO) { PersonalStore.listWords(context.filesDir) }
        }
        val rejections by produceState(emptyList<RejectedCorrection>(), version) {
            value = withContext(Dispatchers.IO) { PersonalStore.listRejected(context.filesDir) }
        }
        fun forget(language: String? = null, word: String? = null) {
            PersonalStore.forget(context.filesDir, language, word)
            state.settings.prefs.edit().putLong(Prefs.LEARNING_REVISION, System.nanoTime()).apply()
            version++; confirm = false
        }
        OptionGroup {
            ToggleRow("Learn from my typing", state.bool(Prefs.LEARNING)) { state.set(Prefs.LEARNING, it) }
            ActionRow("Forget learned words", destructive = true) { confirm = true }
            if (confirm) {
                Text("Forget learned words, word sequences and blocked corrections in every language?", Modifier.padding(16.dp))
                Row {
                    TextButton(onClick = { confirm = false }) { Text("Cancel") }
                    TextButton(onClick = { forget() }) { Text("Forget all") }
                }
            }
            Text("Learned words (${words.size})", Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
            OutlinedTextField(query, { query = it }, label = { Text("Search learned words") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true)
            val matches = words.filter { it.second.contains(query, ignoreCase = true) }
            if (matches.isEmpty()) Text("No learned words", Modifier.padding(16.dp))
            else LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(matches, key = { it.first + ":" + it.second }) { (language, word) ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(word + " \u00b7 " + Prefs.LANGUAGE_NAMES.getValue(language), Modifier.weight(1f))
                        TextButton(onClick = { forget(language, word) }) { Text("Remove") }
                    }
                }
            }
            Text("Never corrected (${rejections.size})", Modifier.padding(16.dp), fontWeight = FontWeight.Bold)
            val blocked = rejections.filter { it.original.contains(query, true) || it.fix.contains(query, true) }
            if (blocked.isEmpty()) Text("No blocked corrections", Modifier.padding(16.dp))
            else LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(blocked, key = { it.language + ":" + it.original + ":" + it.fix }) { rejection ->
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(rejection.original + " \u2192 " + rejection.fix + " (" + rejection.language + ")", Modifier.weight(1f))
                        TextButton(onClick = {
                            PersonalStore.allowCorrection(context.filesDir, rejection.language, rejection.original, rejection.fix)
                            state.settings.prefs.edit().putLong(Prefs.LEARNING_REVISION, System.nanoTime()).apply()
                            version++
                        }) { Text("Remove") }
                    }
                }
            }
        }
    }
    item("longPress") {
        OptionGroup {
            val time = state.int(Prefs.LONG_PRESS_MS)
            SettingSlider("Long-press time", time, Prefs.LONG_PRESS_MS, "$time ms") { state.set(Prefs.LONG_PRESS_MS, it) }
        }
    }
    item("cursorControl") {
        Column {
            SettingsDropdown("Cursor control", state.cursorControl().title, CursorControl.entries.map { it.title }) {
                state.settings.setCursorControl(CursorControl.entries[it])
            }
            Text("Hold and push in the empty space to move the cursor.", Modifier.padding(16.dp, 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ToggleRow("Cursor glow", state.bool(Prefs.CURSOR_GLOW),
                note = "Show a soft glow across the keys when you move the cursor.") { state.set(Prefs.CURSOR_GLOW, it) }
        }
    }
    item("accessibility") {
        Column {
            GroupTitle("Accessibility")
            OptionGroup {
                ToggleRow("Type on press", state.bool(Prefs.TYPE_ON_PRESS)) { state.set(Prefs.TYPE_ON_PRESS, it) }
                val guard = state.int(Prefs.KEY_REPEAT_GUARD)
                SettingSlider("Ignore repeated taps", guard, Prefs.KEY_REPEAT_GUARD, if (guard == 0) "Off" else "$guard ms") { state.set(Prefs.KEY_REPEAT_GUARD, it) }
                val slack = state.int(Prefs.TOUCH_SLACK)
                SettingSlider("Extra tap area", slack, Prefs.TOUCH_SLACK, "$slack dp") { state.set(Prefs.TOUCH_SLACK, it) }
            }
        }
    }
    item("panels") {
        Column { GroupTitle("Panels"); InsertSettingsLinks(state.settings, onPanel) }
    }
    item("assist") {
        var open by remember { mutableStateOf(false) }
        val language = state.chordLanguage()
        val letters = state.chordLetters()
        if (open) {
            val chords by produceState<List<Chords.Chord>?>(null, language, letters) {
                value = null
                value = withContext(Dispatchers.Default) { state.chordList(language, letters) }
            }
            AlertDialog(onDismissRequest = { open = false }, title = { Text("Chord list · " + Prefs.LANGUAGE_NAMES.getValue(language)) },
                text = {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        if (chords == null) item { Text("Loading…") }
                        else if (chords!!.isEmpty()) item { Text("No chords for this layout.") }
                        else items(chords!!, key = { it.word }) { Text(it.label, Modifier.padding(vertical = 8.dp)) }
                    }
                }, confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } })
        }
        Column {
            GroupTitle("Assist")
            OptionGroup {
                for ((label, pref) in listOf("Space accepts chosen word" to Prefs.SPACE_ACCEPTS, "Swipe typing" to Prefs.SWIPE_TYPING, "Highlight likely keys" to Prefs.KEY_TINT, "Smart tap areas" to Prefs.SMART_TARGETS, "Vibrate on typos" to Prefs.TYPO_BUZZ, "Chords" to Prefs.CHORDING)) {
                    if (pref == Prefs.TYPO_BUZZ && LocalContext.current.getSystemService(android.os.Vibrator::class.java)?.hasVibrator() != true) continue
                    val blocked = state.bool(Prefs.TYPE_ON_PRESS) && (pref == Prefs.SWIPE_TYPING || pref == Prefs.CHORDING)
                    ToggleRow(label, !blocked && state.bool(pref), enabled = !blocked,
                        note = if (blocked) "Off while Type on press is on" else null) { state.set(pref, it) }
                }
                if (state.bool(Prefs.SWIPE_TYPING) && !state.bool(Prefs.TYPE_ON_PRESS)) {
                    SettingsDropdown("Swipe style", if (state.bool(Prefs.SWIPE_LETTERS)) "Type the letters" else "Guess the word",
                        listOf("Guess the word", "Type the letters")) { state.set(Prefs.SWIPE_LETTERS, it == 1) }
                    Text("Choose word guessing or the letters at turns and pauses.", Modifier.padding(20.dp, 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ActionRow("Chord list") { open = true }
            }
        }
    }
    item("more") {
        val context = LocalContext.current
        OptionGroup {
            ActionRow("Guide") { context.startActivity(Intent(context, OnboardingActivity::class.java)) }
            ActionRow("About", onClick = onAbout)
            ActionRow("Reset settings", destructive = true, onClick = onReset)
        }
    }
}
