package app.tailorkeys.settings

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf

/** Each control observes just its own preferences, including while its tab is retained offscreen. */
internal class PreferenceRevisions {
    private val revisions = HashMap<String, MutableIntState>()

    fun read(vararg keys: String) {
        for (key in keys) revisions.getOrPut(key) { mutableIntStateOf(0) }.intValue
    }

    fun changed(key: String?) {
        if (key == null) revisions.values.forEach { it.intValue++ }
        else revisions[key]?.let { it.intValue++ }
    }
}
