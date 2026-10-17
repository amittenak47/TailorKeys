package app.tailorkeys.settings

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template

/** Keep candidate grids across tab visits; comparing their keys needs no JSON or preference I/O. */
internal class TemplateMatcher {
    private var language: String? = null
    private var numberRow = false
    private var candidates = emptyList<Pair<Template, KeyGrid>>()

    fun name(grid: KeyGrid, language: String, numberRow: Boolean): String {
        if (this.language != language || this.numberRow != numberRow) {
            this.language = language
            this.numberRow = numberRow
            candidates = Template.entries.map { template ->
                template to KeyGrid.template(template, language).apply { if (numberRow) setNumberRow(true) }
            }
        }
        return candidates.firstOrNull { (_, candidate) ->
            grid.rows == candidate.rows && grid.items.size == candidate.items.size &&
                grid.items.indices.all { index ->
                    val a = grid.items[index]
                    val b = candidate.items[index]
                    a.r == b.r && a.c == b.c && a.w == b.w && a.keys == b.keys
                }
        }?.first?.title ?: "Custom"
    }
}
