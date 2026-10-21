package app.tailorkeys.settings

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.tailorkeys.R
import app.tailorkeys.KeyboardSettings
import app.tailorkeys.SettingsActivity
import app.tailorkeys.insert.*
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal fun LazyListScope.insertPanelOptions(settings: KeyboardSettings, page: String, back: () -> Unit) {
    item("panel-header") { TextButton(back, Modifier.heightIn(min = 48.dp)) { Text("‹  $page") } }
    item("panel-controls") { InsertSettingsControls(settings, page) }
}

@Composable
internal fun InsertSettingsLinks(open: (String) -> Unit) {
    val context = LocalContext.current
    val repository = remember { InsertRepository(context) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(repository) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        repository.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { repository.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    @Suppress("UNUSED_VARIABLE") val observed = revision
    val prefs = repository.prefs
    val summaries = listOf(
        "Emoji" to "${InsertPrefs.toneNames.getOrElse(prefs.getInt(InsertPrefs.TONE, 0)) { "Default" }} skin tone, recents ${if (prefs.getBoolean(InsertPrefs.RECENTS, true)) "on" else "off"}",
        "Clipboard and notes" to "History ${if (prefs.getBoolean(InsertPrefs.HISTORY, false)) "on" else "off"}, keeps ${repository.keep.title}, ${repository.store().notes().size} notes",
        "Voice typing" to if (!prefs.getBoolean(InsertPrefs.VOICE, true)) "Off" else if (prefs.getBoolean(InsertPrefs.DEVICE_ONLY, true)) "On-device only" else "Online recognition allowed")
    OptionGroup { summaries.forEach { (title, summary) ->
        TextButton({ open(title) }, Modifier.fillMaxWidth().heightIn(min = 64.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            SettingsIcon(R.drawable.ic_settings_chevron)
        }
    } }
}

@Composable
private fun InsertSettingsControls(settings: KeyboardSettings, page: String) {
    val context = LocalContext.current
    val repository = remember(settings) { InsertRepository(context) }
    var revision by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key?.startsWith("insert_") == true) revision++ }
        repository.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { repository.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    @Suppress("UNUSED_VARIABLE") val observed = revision
    val prefs = repository.prefs
    var managing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (page) {
            "Emoji" -> {
                OptionGroup {
                    Text("Default skin tone", Modifier.padding(16.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                        val colors = listOf(0xFFFFCD39, 0xFFF8DCC7, 0xFFEFD09B, 0xFFBE9873, 0xFFA4754D, 0xFF6F4934)
                        InsertPrefs.toneNames.forEachIndexed { index, name ->
                            val selected = prefs.getInt(InsertPrefs.TONE, 0) == index
                            Column(Modifier.width(56.dp).heightIn(min = 80.dp).selectable(selected, role = Role.RadioButton,
                                onClick = { prefs.edit().putInt(InsertPrefs.TONE, index).apply() }), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.padding(4.dp).size(36.dp).background(Color(colors[index]), CircleShape)
                                    .border(if (selected) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                                    if (selected) Text("✓", color = MaterialTheme.colorScheme.onSurface)
                                }
                                Text(name, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text("Applies to hands and people. Hold an emoji in the panel to pick another.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Preview", style = MaterialTheme.typography.labelMedium)
                        val catalog = remember { InsertCatalogs.emoji(context) }
                        listOf("👋", "👍", "🙌", "🤝", "💪").forEach { text ->
                            catalog.primary.firstOrNull { it.text == text }?.let { entry ->
                                val shown = catalog.toned(entry, prefs.getInt(InsertPrefs.TONE, 0))
                                Text(shown.text, Modifier.padding(8.dp).semantics { contentDescription = shown.name }, style = MaterialTheme.typography.headlineMedium)
                            }
                        }
                    }
                }
                OptionGroup {
                    ToggleRow("Show recents", prefs.getBoolean(InsertPrefs.RECENTS, true)) { prefs.edit().putBoolean(InsertPrefs.RECENTS, it).apply() }
                    ActionRow("Clear emoji and kaomoji recents") { repository.clearRecents() }
                }
            }
            "Clipboard and notes" -> {
                OptionGroup { ToggleRow("Clipboard history", prefs.getBoolean(InsertPrefs.HISTORY, false), note = "Keep what you copy so you can paste it again.") { prefs.edit().putBoolean(InsertPrefs.HISTORY, it).apply() } }
                GroupTitle("Keep items for")
                OptionGroup { ClipKeep.entries.forEachIndexed { index, keep ->
                    TextButton({ prefs.edit().putInt(InsertPrefs.KEEP, index).apply(); repository.change { } }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text((if (repository.keep == keep) "◉  " else "○  ") + keep.title) }
                } }
                OptionGroup {
                    ActionRow("Clear clipboard history now", destructive = true) { repository.change { it.clear() } }
                    Text("Pinned items stay until you delete them.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                    ActionRow("Manage notes · ${repository.store().notes().size}") { managing = true }
                }
                Text("Items stay on this device and clear after ${repository.keep.title}. Notes and pins stay until you delete them. Sensitive clips and text copied in password fields are never saved.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                OptionGroup {
                    ToggleRow("Voice typing", prefs.getBoolean(InsertPrefs.VOICE, true)) { prefs.edit().putBoolean(InsertPrefs.VOICE, it).apply() }
                    ToggleRow("Use on-device only", prefs.getBoolean(InsertPrefs.DEVICE_ONLY, true), note = "Audio stays on this device and works offline. Turn off to allow online recognition.") { prefs.edit().putBoolean(InsertPrefs.DEVICE_ONLY, it).apply() }
                }
                val allowed = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                OptionGroup { ActionRow("Microphone · ${if (allowed) "Allowed" else "Not allowed"}") {
                    if (!allowed) context.startActivity(Intent(context, SettingsActivity::class.java).putExtra("request_microphone", true))
                    else context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                } }
            }
        }
    }
    if (managing) NotesManager(repository) { managing = false }
}

@Composable
private fun NotesManager(repository: InsertRepository, close: () -> Unit) {
    var notes by remember { mutableStateOf(repository.store().notes()) }
    var edit by remember { mutableStateOf<ClipItem?>(null) }
    var writing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }; var text by remember { mutableStateOf("") }
    var deleted by remember { mutableStateOf<ClipboardStore?>(null) }
    var deletedItem by remember { mutableStateOf<ClipItem?>(null) }
    LaunchedEffect(deleted) { if (deleted != null) { delay(6_000); deleted = null } }
    AlertDialog(onDismissRequest = close, title = { Text("Manage notes") }, text = {
        Column {
            TextButton({ edit = null; title = ""; text = ""; writing = true }) { Text("+ New note") }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) { items(notes, key = { it.id }) { note ->
                Row {
                    TextButton({ edit = note; title = note.title; text = note.text; writing = true }, Modifier.weight(1f)) { Text(note.title.ifBlank { note.text.take(40) }) }
                    TextButton({ val store = repository.store(); store.delete(note.id); repository.save(store); deleted = store; deletedItem = note; notes = repository.store().notes() }) { Text("Delete") }
                }
            } }
            deleted?.let { store -> TextButton({ if (store.undo()) deletedItem?.let { item -> repository.change { it.restore(item) } }; deleted = null; notes = repository.store().notes() }) { Text("Note deleted · Undo") } }
        }
    }, confirmButton = { TextButton(close) { Text("Done") } })
    if (writing) AlertDialog(onDismissRequest = { writing = false }, title = { Text(if (edit == null) "New note" else "Edit note") },
        text = { Column { OutlinedTextField(title, { title = it.take(40) }, label = { Text("Title") }); OutlinedTextField(text, { text = it.take(20_000) }, label = { Text("Text") }, modifier = Modifier.heightIn(min = 120.dp, max = 320.dp)) } },
        confirmButton = { TextButton({ repository.change { store -> edit?.let { store.edit(it.id, text, title) } ?: store.note(title, text) }; writing = false; notes = repository.store().notes() }) { Text("Save") } },
        dismissButton = { TextButton({ writing = false }) { Text("Cancel") } })
}
