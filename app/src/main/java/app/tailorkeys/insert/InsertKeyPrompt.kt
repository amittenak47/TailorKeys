package app.tailorkeys.insert

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Keys

object InsertKeyPrompt {
    const val SEEN = "insert_key_prompt_v1"
    fun shouldShow(seen: Boolean, custom: Boolean, grid: KeyGrid) = !seen && custom && grid.items.none { Keys.INSERT in it.keys }
}
