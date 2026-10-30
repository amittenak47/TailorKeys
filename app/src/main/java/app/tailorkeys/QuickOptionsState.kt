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
    val themeLabel: String = theme.title,
    val locked: Boolean = false,
) {
    fun tiles(): List<QuickTileState> = listOf(
        QuickTileState("Split", if (split) "On" else "Off", on = split),
        QuickTileState("One hand", when (oneHand) {
            Align.CENTER -> "Off"; Align.LEFT -> "Left"; Align.RIGHT -> "Right"
        }, on = oneHand != Align.CENTER),
        QuickTileState("Size", "$height%", description = "Size, $height percent"),
        QuickTileState("Theme", themeLabel),
        QuickTileState("Swipe", if (swipe) "On" else "Off", on = swipe),
        QuickTileState("Language", if (multipleLanguages) "Tap to switch" else "Only one",
            glyph = language.uppercase(), enabled = multipleLanguages),
        QuickTileState("Settings", "All", description = null),
        QuickTileState("Pause learning", if (learning) "Off" else "On", on = !learning),
        QuickTileState("Tidy", "Review text"),
        QuickTileState("Lock halves", if (locked) "On" else "Off", on = locked),
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

/** Dp geometry: keep labels inside complete rows, then peek into the next row's artwork. */
internal data class QuickPanelGeometry(val strip: Int, val tile: Int, val viewport: Int, val gap: Int, val top: Int) {
    companion object {
        fun fit(width: Int, height: Int, header: Int, minimumTile: Int = 60): QuickPanelGeometry {
            val rows = if (width < 260) 5 else 3
            val body = (height - header).coerceAtLeast(0)
            val preferredTile = maxOf(minimumTile, if (width >= 600) 72 else 60)
            val preferredStrip = if (width >= 600 && height >= 250) 138 else 100
            val gap = if (body >= preferredStrip + 24 + rows * (preferredTile + 6) + 4) 24 else 8
            val strip = (body - gap - rows * (minimumTile + 6) - 4).coerceIn(56, preferredStrip)
            val tile = ((body - strip - gap - 4) / rows - 6).coerceIn(minimumTile, preferredTile)
            val grid = rows * (tile + 6) + 4
            val available = body - strip - gap
            // At extreme heights the outer scroller includes the layout strip as well.
            val viewport = if (available >= grid || available < tile + 6 + 18) grid
                else ((available - 18) / (tile + 6)) * (tile + 6) + 18
            return QuickPanelGeometry(strip, tile, viewport, gap, maxOf(0, (body - strip - gap - viewport) / 2))
        }
    }
}
