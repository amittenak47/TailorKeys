package app.tailorkeys.settings

import android.graphics.Color as AndroidColor
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.tailorkeys.*
import java.util.Locale
import java.util.UUID

@Composable
internal fun ThemeControl(state: SettingsState) {
    val context = LocalContext.current
    val selected = state.themeId()
    val customs = state.customThemes()
    var editor by remember { mutableStateOf<CustomTheme?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val custom = customs.firstOrNull { it.selection == selected }
    OptionGroup {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Theme")
            Text(custom?.name ?: state.settings.theme().title, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 8.dp)) {
            ThemeName.entries.forEach { theme ->
                ThemeTile(if (theme == ThemeName.SYSTEM) "System" else theme.title, Palette.of(context, theme), selected == theme.name, Modifier.weight(1f)) {
                    state.settings.setTheme(theme); deleting = false
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Your themes", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("${customs.size} saved", style = MaterialTheme.typography.labelMedium)
        }
        FlowRow(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            customs.forEach { theme -> ThemeTile(theme.name, theme.colors.palette(), selected == theme.selection) {
                state.settings.setThemeId(theme.selection); deleting = false
            } }
            TextButton({ creating = true; editor = null }, Modifier.widthIn(min = 112.dp).heightIn(min = 88.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(44.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Text("+") }
                    Text("Create theme", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (custom != null) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(custom.name, fontWeight = FontWeight.Bold); Text("Your theme", style = MaterialTheme.typography.labelSmall) }
                FilledTonalButton({ editor = custom; creating = true }) { Text("Edit") }
                TextButton({ deleting = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
            if (deleting) Surface(Modifier.padding(12.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Delete “${custom.name}”?", style = MaterialTheme.typography.titleMedium)
                    Text("This theme is removed from this device. The keyboard switches back to Warm.")
                    Row(Modifier.align(Alignment.End)) {
                        TextButton({ deleting = false }) { Text("Cancel") }
                        Button({ state.settings.deleteTheme(custom.id); deleting = false }) { Text("Delete") }
                    }
                }
            }
        }
    }
    if (creating) ThemeEditor(state, editor) { creating = false }
}

@Composable
private fun ThemeTile(name: String, palette: Palette, selected: Boolean, modifier: Modifier = Modifier.width(84.dp), select: () -> Unit) {
    Column(modifier.heightIn(min = 88.dp).selectable(selected, role = Role.RadioButton, onClick = select)
        .padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            RoundedCornerShape(18.dp)).padding(4.dp)) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color(palette.background))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))) {
                for (i in 0..1) {
                    val offset = Offset(size.width * (.14f + i * .42f), size.height * .28f)
                    val keySize = Size(size.width * .3f, size.height * .44f)
                    drawRoundRect(Color(if (i == 0) palette.key else palette.functionKey), offset, keySize, CornerRadius(3.dp.toPx()))
                    if (palette.keyOutline ushr 24 != 0) drawRoundRect(Color(palette.keyOutline), offset, keySize, CornerRadius(3.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                }
            }
            if (selected) Text("✓", Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)).padding(horizontal = 3.dp),
                color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
        }
        Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeEditor(state: SettingsState, existing: CustomTheme?, close: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var colors by remember { mutableStateOf(existing?.colors ?: ThemeColors.from(Palette.of(context, state.settings))) }
    var preset by remember { mutableStateOf(existing?.selection) }
    var channel by remember { mutableIntStateOf(3) }
    var picker by remember { mutableStateOf(false) }
    var pickerOriginal by remember { mutableIntStateOf(colors.text) }
    val preview = remember { KeyboardView(context, state.settings).apply { grid = state.preview.grid; setOnTouchListener { _, _ -> true }
        setSuggestions(listOf("helpful", "helicopter", "helmet"), listOf("hello", "help", "held"), "hel") } }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val wide = maxWidth >= 600.dp
                val previewHeight = with(LocalDensity.current) { (maxHeight * .4f).roundToPx() }
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(close) { Text("✕", Modifier.semantics { contentDescription = "Close theme editor" }) }
                        Text(if (existing == null) "Create theme" else "Edit theme", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        Button({ state.settings.saveTheme(CustomTheme(existing?.id ?: UUID.randomUUID().toString(), name, colors)); close() }, enabled = name.isNotBlank()) { Text("✓ Save theme") }
                    }
                    AndroidView(factory = { preview }, update = { it.previewTargetHeight = previewHeight; it.previewPressed = channel == 5; it.previewPalette = colors.palette() },
                        modifier = Modifier.fillMaxWidth().heightIn(max = this@BoxWithConstraints.maxHeight * .4f).clip(RoundedCornerShape(24.dp)))
                    Text("Live preview. It changes as you pick colours.", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(name, { name = it.take(24) }, label = { Text("Theme name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text("${name.length} / 24") })
                            Text("Start from", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (ThemeName.entries.map { it.name to it.title } + state.customThemes().map { it.selection to it.name }).forEach { (id, title) ->
                                    FilterChip(preset == id, { preset = id; colors = ThemeColors.from(Palette.resolve(id, state.settings.customThemes(), context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)); pickerOriginal = colors.values()[channel] }, label = { Text(title) })
                                }
                            }
                            Text("Colours", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            ThemeColors.labels.forEachIndexed { i, label ->
                                val open = channel == i && (wide || picker)
                                val low = colors.checks().any { !it.passes && i in it.channels }
                                Surface(onClick = { if (!open) pickerOriginal = colors.values()[i]; channel = i; picker = true }, shape = RoundedCornerShape(14.dp),
                                    color = if (open) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Box(Modifier.size(28.dp).background(Color(colors.values()[i]), RoundedCornerShape(20.dp)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp)))
                                        Column(Modifier.weight(1f)) {
                                            Text(label, fontWeight = if (open) FontWeight.Bold else FontWeight.Normal)
                                            if (low) Text("⚠ Low contrast", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                        }
                                        Text(ThemeColors.hex(colors.values()[i]), style = MaterialTheme.typography.labelMedium)
                                        Text(if (open) "⌄" else "›")
                                    }
                                }
                            }
                            if (!wide) Readability(colors) { colors = it }
                        }
                        if (wide) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            key(channel) { ThemeColorPicker(ThemeColors.labels[channel], colors.values()[channel], pickerOriginal, colors.pickerSuggestion(channel), channel == 6) { colors = colors.replacing(channel, it) } }
                            Readability(colors) { colors = it }
                        }
                    }
                }
                if (picker && !wide) ModalBottomSheet(onDismissRequest = { picker = false }) {
                    Column(Modifier.heightIn(max = this@BoxWithConstraints.maxHeight * .65f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        key(channel) { ThemeColorPicker(ThemeColors.labels[channel], colors.values()[channel], pickerOriginal, colors.pickerSuggestion(channel), channel == 6) { colors = colors.replacing(channel, it) } }
                        Readability(colors) { colors = it }
                        Button({ picker = false }, Modifier.align(Alignment.End)) { Text("✓ Done") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Readability(colors: ThemeColors, change: (ThemeColors) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Readability", fontWeight = FontWeight.Bold)
            colors.checks().forEach { check ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (check.passes) "✓" else "⚠", color = if (check.passes) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Column(Modifier.weight(1f)) {
                        Text(check.label, style = MaterialTheme.typography.labelLarge)
                        if (!check.passes) {
                            Text(check.warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            val suggestion = remember(colors, check) { closestReadable(colors.values()[check.fixChannel], check.background, check.target) }
                            TextButton({ change(colors.replacing(check.fixChannel, suggestion)) }) { Text("Use ${ThemeColors.hex(suggestion)}") }
                        }
                    }
                    Text(String.format(Locale.ROOT, "%.1f : 1", check.ratio), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("You can save with warnings. They are advice, not rules.", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ThemeColorPicker(label: String, color: Int, resetColor: Int, suggestion: Pair<Int, Double>?, allowTransparent: Boolean = false, change: (Int) -> Unit) {
    val initial = remember { FloatArray(3).also { AndroidColor.colorToHSV(color, it) } }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var value by remember { mutableFloatStateOf(initial[2]) }
    val hsv = floatArrayOf(hue, saturation, value)
    var hex by remember(color) { mutableStateOf(ThemeColors.hex(color)) }
    LaunchedEffect(color) {
        if (AndroidColor.HSVToColor(hsv) != color) {
            val next = FloatArray(3).also { AndroidColor.colorToHSV(color, it) }
            hue = next[0]; saturation = next[1]; value = next[2]
        }
    }
    fun update(h: Float = hue, s: Float = saturation, v: Float = value) {
        hue = h; saturation = s; value = v
        change(AndroidColor.HSVToColor(floatArrayOf(h, s, v)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label colour", fontWeight = FontWeight.Bold)
        if (allowTransparent) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show key outline", Modifier.weight(1f))
            Switch(color ushr 24 != 0, { change(if (it) AndroidColor.HSVToColor(hsv) else 0) })
        }
        OutlinedTextField(hex, { hex = it; ThemeColors.parseHex(it, allowTransparent)?.let(change) }, label = { Text("Hex colour") },
            isError = ThemeColors.parseHex(hex, allowTransparent) == null, singleLine = true, modifier = Modifier.fillMaxWidth())
        Canvas(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(14.dp))
            .pointerInput(hsv[0]) { detectTapGestures { update(s = (it.x / size.width).coerceIn(0f, 1f), v = (1 - it.y / size.height).coerceIn(0f, 1f)) } }
            .pointerInput(hsv[0]) { detectDragGestures { point, _ -> point.consume(); update(s = (point.position.x / size.width).coerceIn(0f, 1f), v = (1 - point.position.y / size.height).coerceIn(0f, 1f)) } }) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color(AndroidColor.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            drawCircle(Color.White, 6.dp.toPx(), Offset(hsv[1] * size.width, (1 - hsv[2]) * size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        listOf("Hue", "Saturation", "Value").forEachIndexed { i, title ->
            Text(title, style = MaterialTheme.typography.labelMedium)
            Slider(hsv[i], { value -> when (i) { 0 -> update(h = value); 1 -> update(s = value); else -> update(v = value) } },
                valueRange = 0f..(if (i == 0) 360f else 1f), modifier = Modifier.semantics { contentDescription = title })
        }
        suggestion?.let { (readable, ratio) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Closest readable colour ${ThemeColors.hex(readable)} reads at ${String.format(Locale.ROOT, "%.1f", ratio)} : 1", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                TextButton({ change(readable) }) { Text("Use") }
            }
        }
        TextButton({ change(resetColor) }, Modifier.align(Alignment.End)) { Text("↻ Reset") }
    }
}

@Composable
private fun ThemeColors.pickerSuggestion(channel: Int): Pair<Int, Double>? = remember(this, channel) {
    checks().firstOrNull { !it.passes && channel in it.channels }?.let { check ->
        val against = if (channel == check.fixChannel || channel == 6) check.background else values()[check.fixChannel]
        val suggestion = closestReadable(values()[channel], against, if (channel == 6) 3.0 else check.target)
        suggestion to contrastRatio(suggestion, against)
    }
}
