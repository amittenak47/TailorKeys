package app.tailorkeys.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tailorkeys.Prefs

/**
 * Keys with long-press: every key, or only the chosen ones. With Chosen keys, a button turns the keyboard preview at
 * the top into the chooser (KeyboardView.holdPickLayer): tap a key, or the circle before a row, on abc, 123 and #+=.
 */
@Composable
internal fun LongPressKeysRow(state: SettingsState) {
    val chosen = state.int(Prefs.LONG_PRESS_KEYS) == 1
    SegmentedRow("Keys with long-press", listOf("All keys", "Chosen keys"), if (chosen) 1 else 0,
        note = if (chosen) "Choose on the keyboard above: tap a key, or the circle before a row. Letters, 123 and #+= each have their own." else null) {
        state.set(Prefs.LONG_PRESS_KEYS, it)
        if (it == 0) state.pick(false)
    }
    if (!chosen) return
    FilledTonalButton({ state.pick(!state.picking) }, Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 12.dp)) {
        Text(if (state.picking) "Done choosing" else "Choose keys on the keyboard")
    }
}
