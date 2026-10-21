package app.tailorkeys

/** Values that affect the panel. Equality also makes the preference listener's second refresh free. */
internal data class QuickOptionsState(
    val theme: ThemeName,
    val nightMode: Int,
    val radius: Int,
    val split: Boolean,
    val oneHand: Align,
    val height: Int,
    val language: String,
    val multipleLanguages: Boolean,
    val swipe: Boolean,
    val learning: Boolean = true,
    val themeContent: String = "",
) {
    fun tiles(): List<QuickTileState> = listOf(
        QuickTileState("Split", if (split) "On" else "Off", on = split),
        QuickTileState("One hand", when (oneHand) {
            Align.CENTER -> "Off"; Align.LEFT -> "Left"; Align.RIGHT -> "Right"
        }, on = oneHand != Align.CENTER),
        QuickTileState("Size", "$height%", description = "Size, $height percent"),
        QuickTileState("Learning", if (learning) "On" else "Paused", on = learning),
        QuickTileState("Language", if (multipleLanguages) "Tap to switch" else "Only one",
            glyph = language.uppercase(), enabled = multipleLanguages),
        QuickTileState("Tidy", "Review text"),
        QuickTileState("Settings", "All", description = null),
    )
}

internal data class QuickTileState(
    val label: String,
    val state: String = "",
    val glyph: String? = null,
    val on: Boolean = false,
    val enabled: Boolean = true,
    val description: String? = if (state.isEmpty()) null else "$label, $state",
)

internal data class QuickLayoutTileState(val entry: LayoutEntry, val inUse: Boolean) {
    val label get() = entry.name + (if (inUse) ", in use" else "") + (if (entry.draft) ", unsaved" else "")
    val bold get() = inUse
    val outlineDp get() = if (inUse) 2 else 1
}

internal fun LayoutLibrary.quickTiles() = entries().map { QuickLayoutTileState(it, it.key == inUse) }
