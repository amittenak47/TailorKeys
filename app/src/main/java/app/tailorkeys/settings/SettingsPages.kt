package app.tailorkeys.settings

import android.content.Intent
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.tailorkeys.Align
import app.tailorkeys.OnboardingActivity
import app.tailorkeys.Prefs
import app.tailorkeys.R
import app.tailorkeys.ThemeName
import app.tailorkeys.layout.Template
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.tailorkeys.Chords

internal fun LazyListScope.layoutOptions(state: SettingsState, editing: Boolean, spacerHeight: androidx.compose.ui.unit.Dp,
    onEdit: () -> Unit, onShare: () -> Unit, onImport: () -> Unit) {
    item("preset") {
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        SettingsDropdown("Preset", state.templateName(), Template.entries.map { it.title } + "Custom") { index ->
            Template.entries.getOrNull(index)?.let { state.applyTemplate(it, landscape) }
        }
    }
    item("edit") {
        Button(onEdit, Modifier.fillMaxWidth().height(52.dp), contentPadding = PaddingValues(horizontal = 24.dp)) {
            SettingsIcon(if (editing) R.drawable.ic_settings_check else R.drawable.ic_settings_edit, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (editing) "Done" else "Edit layout", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
    item("space") { Spacer(Modifier.height(spacerHeight)) }
    item("sharing") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onShare, contentPadding = PaddingValues(horizontal = 16.dp)) {
                SettingsIcon(R.drawable.ic_settings_share, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Share")
            }
            TextButton(onImport, contentPadding = PaddingValues(horizontal = 16.dp)) {
                SettingsIcon(R.drawable.ic_settings_import, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Import")
            }
        }
    }
}

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
                    SettingSlider("Space row tilt", p.spaceTilt, Prefs.spaceTilt(page), degrees(p.spaceTilt)) { state.setFor(page, Prefs::spaceTilt, it) }
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
    val presets = sizePresets(page)
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

@Composable
private fun ThemeControl(state: SettingsState) {
    val selected = state.theme()
    var expanded by remember { mutableStateOf(false) }
    OptionGroup {
        Box {
            TextButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 56.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 16.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                Text("Theme", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(selected.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp)); SettingsIcon(R.drawable.ic_settings_expand)
            }
            DropdownMenu(expanded, { expanded = false }) {
                ThemeName.entries.forEach { theme ->
                    DropdownMenuItem(text = { Text(theme.title) }, onClick = { expanded = false; state.settings.setTheme(theme) },
                        trailingIcon = if (theme == selected) { { SettingsIcon(R.drawable.ic_settings_check) } } else null)
                }
            }
        }
        Row(Modifier.fillMaxWidth().selectableGroup().padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
            ThemeName.entries.forEach { theme ->
                Column(Modifier.weight(1f).heightIn(min = 76.dp)
                    .selectable(theme == selected, role = Role.RadioButton, onClick = { state.settings.setTheme(theme) })
                    .padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                    val swatch = Modifier.size(52.dp).then(if (theme == selected) Modifier
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)) else Modifier).padding(4.dp)
                    ThemeSwatch(theme, swatch)
                    Text(if (theme == ThemeName.SYSTEM) "System" else theme.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (theme == selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(theme: ThemeName, modifier: Modifier) {
    val background = when (theme) {
        ThemeName.SYSTEM -> Color(0xFFF7F4EC); ThemeName.LIGHT -> Color(0xFFCFCABD)
        ThemeName.DARK -> Color(0xFF2B2B2E); ThemeName.MIDNIGHT -> Color(0xFF0B1230); ThemeName.WARM -> Color(0xFFDDD2BC); ThemeName.HIGH_CONTRAST -> Color.Black
    }
    val key = when (theme) {
        ThemeName.DARK -> Color(0xFF4A4A4F); ThemeName.MIDNIGHT -> Color(0xFF26367A)
        ThemeName.HIGH_CONTRAST -> Color.Black
        ThemeName.WARM -> Color(0xFFFFFCF5); else -> Color.White
    }
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(background)) {
        if (theme == ThemeName.SYSTEM) {
            drawPath(Path().apply { moveTo(size.width, 0f); lineTo(size.width, size.height); lineTo(0f, size.height); close() }, Color(0xFF2B2B2E))
        } else {
            val w = size.width * 14 / 44; val h = size.height * 16 / 44
            for (i in 0..1) {
                val offset = Offset(size.width * (6 + i * 18) / 44, (size.height - h) / 2)
                drawRoundRect(key, offset, Size(w, h), CornerRadius(3.dp.toPx()))
                if (theme == ThemeName.HIGH_CONTRAST) drawRoundRect(if (i == 0) Color.White else Color(0xFFFFD400),
                    offset, Size(w, h), CornerRadius(3.dp.toPx()), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

internal fun LazyListScope.typingOptions(state: SettingsState, onAbout: () -> Unit, onReset: () -> Unit) {
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
            for ((label, pref) in listOf("Auto-capitalize" to Prefs.AUTO_CAP, "Double-space period" to Prefs.DOUBLE_SPACE_PERIOD, "Vibrate on tap" to Prefs.HAPTICS)) {
                ToggleRow(label, state.bool(pref)) { state.set(pref, it) }
            }
        }
    }
    item("longPress") {
        OptionGroup {
            val time = state.int(Prefs.LONG_PRESS_MS)
            SettingSlider("Long-press time", time, Prefs.LONG_PRESS_MS, "$time ms") { state.set(Prefs.LONG_PRESS_MS, it) }
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
                for ((label, pref) in listOf("Swipe typing" to Prefs.SWIPE_TYPING, "Highlight likely keys" to Prefs.KEY_TINT, "Smart tap areas" to Prefs.SMART_TARGETS, "Vibrate on typos" to Prefs.TYPO_BUZZ, "Chords" to Prefs.CHORDING)) {
                    val blocked = state.bool(Prefs.TYPE_ON_PRESS) && (pref == Prefs.SWIPE_TYPING || pref == Prefs.CHORDING)
                    ToggleRow(label, !blocked && state.bool(pref), enabled = !blocked,
                        note = if (blocked) "Off while Type on press is on" else null) { state.set(pref, it) }
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
