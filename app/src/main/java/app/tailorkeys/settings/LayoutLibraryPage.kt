package app.tailorkeys.settings

import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tailorkeys.R
import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayoutLibraryPage(state: SettingsState, listState: LazyListState, editing: Boolean,
    onEdit: () -> Unit, onTry: (LayoutEntry) -> Unit) {
    val entries = state.layoutEntries()
    var sheet by remember { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("Custom") }
    var pendingSource by rememberSaveable { mutableStateOf<String?>(null) }
    var delete by rememberSaveable { mutableStateOf<String?>(null) }
    var importPanel by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var importName by rememberSaveable { mutableStateOf("Imported layout") }
    val imported = remember(code) { if (code.length <= 100_000) KeyGrid.fromCode(code) else null }
    val newer = remember(code) { Regex("TK([0-9]+)-").find(code)?.groupValues?.get(1)?.toIntOrNull()?.let { it > 1 } == true }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(importPanel, code) {
        state.importing = importPanel
        state.importPreviewValid = imported != null || code.isBlank()
        if (importPanel && imported != null) state.preview.grid = imported
        else if (importPanel) state.showSelected()
    }
    fun closeImport() { importPanel = false; state.importing = false; state.showSelected() }
    BackHandler(importPanel) { closeImport() }
    LaunchedEffect(delete) {
        if (delete != null) listState.animateScrollToItem(1 + (entries.size + 2) / 2)
    }
    fun newDraft() {
        val selected = state.selectedLayout()
        val source = selected.json
        sheet = false
        if (state.library.requestNew() == NewLayoutFlow.SAVE_FIRST) {
            pendingSource = source
            name = "Custom"
            naming = "continue"
        } else {
            state.selectLayout(state.library.startDraft(selected.grid()))
            if (!editing) onEdit()
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (importPanel) item("import") {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Import layout", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(code, { code = it.take(100_000) }, label = { Text("Layout code") },
                            placeholder = { Text("Paste a TK1- code") }, minLines = 2, maxLines = 4,
                            isError = code.isNotBlank() && imported == null, modifier = Modifier.fillMaxWidth())
                        if (imported == null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("⚠", color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { contentDescription = "Invalid code" })
                                Text(if (code.isBlank()) "Paste a code someone shared with you." else if (newer)
                                    "This code is from a newer version of TailorKeys" else
                                    "This code can’t be read. Copy the whole TK1- code and paste it again.",
                                    color = if (code.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            }
                            Text("No preview for this code", style = MaterialTheme.typography.bodyMedium)
                            TextButton({ code = context.getSystemService(ClipboardManager::class.java).primaryClip
                                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty() }) { Text("Paste again") }
                        } else Text("✓ Valid TK1 code · ${imported.rows} rows · ${imported.items.size} keys", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (imported != null) LayoutThumbnail(LayoutEntry("import", importName, imported.toJson()), state.thumbnails,
                                Modifier.width(108.dp).height(64.dp).clip(RoundedCornerShape(12.dp)))
                            OutlinedTextField(importName, { importName = it.take(24) }, label = { Text("Name") },
                                singleLine = true, supportingText = { Text("${importName.length} / 24") }, modifier = Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(::closeImport) { Text("Cancel") }
                            Button({ imported?.let { state.selectLayout(state.library.add(importName, it)); importPanel = false } },
                                enabled = imported != null) { Text("Add layout") }
                        }
                    }
                }
            }
            if (!importPanel) {
            item("heading") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Layouts", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Text("Tap a layout to preview it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val cells = entries.map<LayoutEntry, LayoutEntry?> { it } + listOf(null)
            items(cells.chunked(2), key = { row -> row.firstOrNull()?.key ?: "add-row" }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { entry ->
                        if (entry == null) AddLayoutTile(Modifier.weight(1f), row.size == 1) { sheet = true }
                        else StateLayoutTile(entry, state, Modifier.weight(1f)) {
                            if (!editing) { closeImport(); delete = null; state.selectLayout(entry) }
                        }
                    }
                }
            }
            delete?.let { key -> item("delete") {
                val entry = entries.firstOrNull { it.key == key }
                if (entry != null) Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Delete “${entry.name}”?", fontWeight = FontWeight.Bold)
                        Text(if (key == state.inUseKey()) "Standard will be used instead. This layout will be removed." else "This layout will be removed.")
                        Row {
                            TextButton({ delete = null }) { Text("Keep layout") }
                            TextButton({ state.library.delete(key); state.selectLayout(state.library.entries().first { it.key == state.library.inUse }); delete = null }) { Text("Delete layout") }
                        }
                    }
                }
            } }
            }
        }
        if (!importPanel) SelectedLayoutActionBar(state, editing, onTry = { entry ->
            scope.launch {
                val row = 1 + entries.indexOfFirst { it.key == entry.key }.coerceAtLeast(0) / 2
                listState.animateScrollToItem(row)
                withFrameNanos { }
                onTry(entry)
            }
        }, onEdit = onEdit,
            onRename = { name = it.name; naming = it.key }, onDelete = { delete = it.key })
    }
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Add a layout", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
            Surface(onClick = ::newDraft, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                    Column(Modifier.weight(1f)) {
                        Text("New custom layout", fontWeight = FontWeight.Medium)
                        Text(if (state.library.hasDraft) "Save “Custom” first. You can keep one draft at a time." else
                            "Start from ${state.selectedLayout().name} and move the keys yourself.", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.library.hasDraft) Text("Save first", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            Surface(onClick = { sheet = false; importPanel = true; code = ""; listState.requestScrollToItem(0) }, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsIcon(R.drawable.ic_settings_import)
                    Column { Text("Import from code"); Text("Paste a TK1- code someone shared with you.", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
    naming?.let { flow -> AlertDialog(onDismissRequest = { naming = null; pendingSource = null },
        title = { Text(if (flow == "continue") "Save this layout first" else if (flow == "draft") "Save layout" else "Rename layout") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (flow == "continue") Text("You can have one Custom layout at a time. Save this one as a tile, then start a new one.")
            OutlinedTextField(name, { name = it.take(24) }, label = { Text("Layout name") }, singleLine = true,
                supportingText = { Text("Keep it or give it a name.  ${name.length} / 24") })
        } }, confirmButton = { TextButton({
            val entry = if (flow == "continue") state.library.saveAndContinue(name, requireNotNull(KeyGrid.fromJson(pendingSource)))
                else state.library.rename(flow, name)
            state.selectLayout(entry)
            naming = null; pendingSource = null
            if (flow == "continue" && !editing) onEdit()
        }) { Text(if (flow == "continue") "Save and continue" else "Save") } },
        dismissButton = { TextButton({ naming = null; pendingSource = null }) { Text("Cancel") } }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StateLayoutTile(entry: LayoutEntry, state: SettingsState, modifier: Modifier, onClick: () -> Unit) {
    val selected by remember(entry.key, state) { derivedStateOf { state.selectedKey == entry.key } }
    LayoutTile(entry, selected, state.inUseKey() == entry.key, state.thumbnails, modifier, onClick)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayoutTile(entry: LayoutEntry, selected: Boolean, inUse: Boolean, cache: LayoutThumbnails,
    modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    Column(modifier.layoutMorph(entry.key).clip(RoundedCornerShape(16.dp))
        .background(if (selected) colors.surfaceContainerHighest else colors.surfaceContainerHigh)
        .border(if (selected) 2.dp else 0.dp, if (selected) colors.primary else colors.surfaceContainerHigh, RoundedCornerShape(16.dp))
        .combinedClickable(onClick = onClick, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick() })
        .semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(entry.name, if (inUse) "in use" else null,
                if (selected) "selected" else null, if (entry.draft) "unsaved" else null).joinToString(", ")
            this.selected = selected
            role = Role.Button
        }.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            LayoutThumbnail(entry, cache, Modifier.widthIn(max = 266.dp).fillMaxWidth().aspectRatio(1.8f).clip(RoundedCornerShape(12.dp)))
            if (inUse) Surface(Modifier.align(Alignment.TopStart).padding(4.dp), shape = RoundedCornerShape(12.dp), color = colors.primary) {
                Text("✓ In use", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = colors.onPrimary, style = MaterialTheme.typography.labelMedium)
            }
            if (entry.draft) Surface(Modifier.align(if (inUse) Alignment.BottomEnd else Alignment.TopEnd).padding(4.dp), shape = RoundedCornerShape(12.dp), color = colors.tertiaryContainer) {
                Text("● Unsaved", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            if (selected) Text("✓", color = colors.primary)
        }
        Text(when (entry.preset) {
            Template.STANDARD -> if (entry.grid().rows == 5) "QWERTY · number row" else "QWERTY"
            Template.SPLIT -> "Split · slight fan"
            Template.THUMBS -> "Large bottom keys"
            null -> "${if (entry.draft) "Unsaved" else "Custom"} · ${entry.grid().rows} rows"
        }, maxLines = 1, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun AddLayoutTile(modifier: Modifier, fullRow: Boolean, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    OutlinedCard(onClick, modifier.heightIn(min = 72.dp).drawBehind {
        drawRoundRect(outline, cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
    }, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.background),
        border = BorderStroke(0.dp, androidx.compose.ui.graphics.Color.Transparent)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("+", style = MaterialTheme.typography.headlineMedium)
            Column { Text("Add a layout", fontWeight = FontWeight.Medium)
                Text(if (fullRow) "New custom layout, or import a TK1- code" else "New custom or import", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun SelectedLayoutActionBar(state: SettingsState, editing: Boolean, onTry: (LayoutEntry) -> Unit,
    onEdit: () -> Unit, onRename: (LayoutEntry) -> Unit, onDelete: (LayoutEntry) -> Unit) {
    val entry = state.selectedLayout()
    val inUse = state.inUseKey() == entry.key
    val context = LocalContext.current
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        BoxWithConstraints(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            val wide = maxWidth >= 600.dp
            val actionColumns = if (entry.custom && maxWidth < 288.dp) 3 else if (entry.custom) 6 else 3
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (wide) Column(Modifier.weight(1.5f).padding(start = 12.dp)) {
                    Text(entry.name, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(if (inUse) "✓ In use" else "Previewing, not in use", style = MaterialTheme.typography.labelMedium)
                }
                FlowRow(Modifier.weight(if (wide) (if (entry.custom) 6f else 3f) else 1f), maxItemsInEachRow = actionColumns) {
                LayoutAction(if (inUse) "In use" else "Use", R.drawable.ic_settings_check, entry.name, !inUse && !editing, Modifier.weight(1f)) { state.useLayout(entry) }
                LayoutAction("Try", R.drawable.ic_settings_keyboard, entry.name, !editing, Modifier.weight(1f)) { onTry(entry) }
                LayoutAction("Share", R.drawable.ic_settings_share, entry.name, true, Modifier.weight(1f)) {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "My TailorKeys layout: ${entry.grid().toCode()}"), "Share layout"))
                }
                if (entry.custom) {
                    LayoutAction(if (editing) "Done" else "Edit", R.drawable.ic_settings_edit, entry.name, true, Modifier.weight(1f), onEdit)
                    LayoutAction("Rename", R.drawable.ic_settings_rename, entry.name, !editing, Modifier.weight(1f)) { onRename(entry) }
                    LayoutAction("Delete", R.drawable.ic_settings_delete, entry.name, !editing, Modifier.weight(1f)) { onDelete(entry) }
                }
                }
            }
        }
    }
}

@Composable
private fun LayoutAction(label: String, icon: Int, name: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick, modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label $name" },
        enabled = enabled, contentPadding = PaddingValues(0.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SettingsIcon(icon, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
