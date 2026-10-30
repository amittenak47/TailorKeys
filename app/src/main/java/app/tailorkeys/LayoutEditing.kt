package app.tailorkeys

import app.tailorkeys.layout.GridKey
import app.tailorkeys.layout.KeyGrid

/** Accessible equivalents of rearrangement gestures. Click selects a source key. */
internal object LayoutEditing {
    enum class Action(val label: String) {
        LEFT("Move left"), RIGHT("Move right"), UP("Move up"), DOWN("Move down"),
        MOVE("Swap with selected key"), COMBINE("Combine selected key here"), SEPARATE("Separate swipe key"), REMOVE("Remove key")
    }
    fun apply(grid: KeyGrid, key: GridKey, action: Action, source: GridKey? = null): Boolean = when (action) {
        Action.LEFT -> grid.moveTo(key, key.r, key.c - 1)
        Action.RIGHT -> grid.moveTo(key, key.r, key.c + 1)
        Action.UP -> grid.moveTo(key, key.r - 1, key.c)
        Action.DOWN -> grid.moveTo(key, key.r + 1, key.c)
        Action.MOVE -> source != null && source !== key && source in grid.items && grid.swap(source, key)
        Action.COMBINE -> if (source != null && source !== key && !source.isGap && !key.isGap && source in grid.items) { grid.combine(source, key); true } else false
        Action.SEPARATE -> if (key.isStack) { grid.separate(key); true } else false
        Action.REMOVE -> grid.remove(key)
    }
}
