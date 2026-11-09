package app.tailorkeys.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.tailorkeys.IntPref
import app.tailorkeys.R
import kotlin.math.roundToInt

@Composable
internal fun SettingsIcon(@DrawableRes icon: Int, description: String? = null, modifier: Modifier = Modifier.size(24.dp)) {
    Icon(painterResource(icon), description, modifier)
}

@Composable
internal fun OptionGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
internal fun GroupTitle(title: String) {
    Text(title, Modifier.padding(start = 12.dp, bottom = 8.dp), color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge)
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, note: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .heightIn(min = 56.dp).padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled, thumbContent = if (checked) {
            { SettingsIcon(R.drawable.ic_settings_check, modifier = Modifier.size(16.dp)) }
        } else null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDropdown(label: String, value: String, choices: List<String>, modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh, details: List<String>? = null, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }, modifier.fillMaxWidth()) {
        TextField(value, {}, readOnly = true, singleLine = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = containerColor,
                unfocusedContainerColor = containerColor,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(expanded, { expanded = false }, shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            choices.forEachIndexed { index, choice ->
                DropdownMenuItem(text = {
                        val detail = details?.getOrNull(index)
                        if (detail == null) Text(choice) else Column(Modifier.widthIn(max = 360.dp).padding(vertical = 6.dp)) {
                            Text(choice)
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { expanded = false; onSelect(index) },
                    trailingIcon = if (value == choice) { { SettingsIcon(R.drawable.ic_settings_check) } } else null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingSlider(label: String, value: Int, pref: IntPref, valueText: String,
    onStart: () -> Unit = {}, trailing: (@Composable () -> Unit)? = null, max: Int = pref.max, onChange: (Int) -> Unit) {
    var changing by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueText, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Slider(value = value.toFloat(), onValueChange = {
                if (!changing) { onStart(); changing = true }
                val rounded = it.roundToInt()
                if (rounded != value) onChange(rounded)
            }, onValueChangeFinished = { changing = false },
                valueRange = pref.min.toFloat()..max.toFloat(),
                modifier = Modifier.weight(1f).height(44.dp).semantics {
                    contentDescription = label
                    stateDescription = valueText
                },
                thumb = { Box(Modifier.size(20.dp).background(colors.primary, CircleShape)) },
                track = { state ->
                    Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                        val fraction = ((state.value - pref.min) / (pref.max - pref.min)).coerceIn(0f, 1f)
                        drawRoundRect(colors.outlineVariant, cornerRadius = CornerRadius(size.height / 2))
                        drawRoundRect(colors.primary, size = Size(size.width * fraction, size.height),
                            cornerRadius = CornerRadius(size.height / 2))
                    }
                })
            trailing?.invoke()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSegments(labels: List<String>, selected: Int, description: String, modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = description }, space = 2.dp) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(selected = index == selected, onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                border = SegmentedButtonDefaults.borderStroke(Color.Transparent),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                ), label = { Text(label) })
        }
    }
}

/** A choice of a few options ([SettingsSegments]) under its label, with the paddings and text sizes of [ToggleRow]. */
@Composable
internal fun SegmentedRow(label: String, choices: List<String>, selected: Int, note: String? = null, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        SettingsSegments(choices, selected, label, onSelect = onSelect)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ActionRow(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick, Modifier.fillMaxWidth().heightIn(min = 56.dp),
        contentPadding = PaddingValues(start = 20.dp, end = 16.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (!destructive) SettingsIcon(R.drawable.ic_settings_chevron)
    }
}
