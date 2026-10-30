package app.tailorkeys.layout

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.abs

object Keys {
    const val SPACE = "space"
    const val BACKSPACE = "bs"
    const val ENTER = "en"
    const val ABC = "abc"
    const val NUM = "num"
    const val SYM = "sym"
    const val INSERT = "insert"
    /** An empty cell that holds its place: shown only while rearranging; typing skips it. */
    const val GAP = "gap"
}

/** Starting layouts offered in the editor. [split] also turns on the split keyboard. */
enum class Template(val title: String, val split: Boolean) {
    STANDARD("Standard", false),
    SPLIT("Ergonomic split", true),
    THUMBS("Thumb keys", false),
}

/** Which outside column a center key moves to when the split gap is too narrow for the center column. */
enum class CenterSide { AUTO, LEFT, RIGHT }

/** One key on the grid. A key with more than one entry in [keys] is a swipe key that cycles through them. */
class GridKey(val id: Int, var r: Int, var c: Int, var w: Int, val keys: MutableList<String>, var idx: Int = 0) {
    val isStack get() = keys.size > 1
    val isSpace get() = keys.size == 1 && keys[0] == Keys.SPACE
    val isGap get() = keys.size == 1 && keys[0] == Keys.GAP
    /** Only center keys use it. */
    var side = CenterSide.AUTO
    fun withSide(from: GridKey) = also { side = from.side }
}

/**
 * The keyboard is a grid of five columns on the left (0-4), a center column (5) and five on the right (6-10).
 * A key never crosses between those three regions. Spacebars stretch toward the center over empty cells.
 */
class KeyGrid private constructor(var rows: Int, val items: MutableList<GridKey>, private var nextId: Int) {

    /**
     * Keys set aside in the editor: removed keys and faces taken off a swipe key wait here (newest last)
     * until they are placed again or deleted. Typing ignores the tray.
     */
    val tray = mutableListOf<MutableList<String>>()

    /**
     * The 123 and #+= layers' own keys, laid out like the iPhone's: three rows each, stored as rows 0-2 of a
     * four-row grid with its own tray. A layer without an entry uses [defaultLayer]. See [layer].
     */
    private val layers = HashMap<Layer, KeyGrid>()

    /** Deep copy with the same key ids, so a preview can be matched key for key with the live grid. */
    fun copy(): KeyGrid = KeyGrid(rows, items.mapTo(mutableListOf()) { GridKey(it.id, it.r, it.c, it.w, it.keys.toMutableList(), it.idx).withSide(it) }, nextId)
        .also { copy -> tray.forEach { copy.tray.add(it.toMutableList()) }; layers.forEach { (l, g) -> copy.layers[l] = g.copy() }; copy.source = source }

    /** The first of a layer's three rows: below the number row when there is one. */
    private val layerTop get() = rows - 4

    /** The layout and layer a [layer] view was made from, so removing a key there can check every layer's routes. */
    private var source: Pair<KeyGrid, Layer>? = null

    /**
     * [layer]'s keyboard, to show or to edit: its own three rows, and this layout's bottom row, which every layer
     * shares (see [isShared]). The letters' number row isn't repeated: 123 has its own digits. A plain layer key in
     * the bottom row goes back to the letters there, as the iPhone's ABC key does; a swipe key that already has an
     * abc face stays as it is, so one key can switch between every layer in its order.
     */
    fun layer(layer: Layer): KeyGrid {
        if (layer == Layer.ABC) return this
        val own = layers[layer] ?: defaultLayer(layer)
        val shown = own.items.mapTo(mutableListOf()) { GridKey(it.id, it.r + layerTop, it.c, it.w, it.keys.toMutableList(), it.idx).withSide(it) }
        for (key in items) if (key.r == rows - 1) {
            val keys = sharedKeys(key)
            shown += GridKey(SHARED_ID + key.id, key.r, key.c, key.w, keys, if (keys == key.keys) key.idx else 0).withSide(key)
        }
        return KeyGrid(rows, shown, own.nextId).also { g -> own.tray.forEach { g.tray.add(it.toMutableList()) }; g.source = this to layer }
    }

    /** What a letters' bottom-row key shows on 123 and #+=. */
    private fun sharedKeys(key: GridKey) =
        if (key.keys.any(KeyText::isLayerKey) && Keys.ABC !in key.keys) mutableListOf(Keys.ABC) else key.keys.toMutableList()

    /** True when the letters reach 123 and #+= (directly or through each other), and both can get back. */
    fun routesOk(): Boolean {
        val next = Layer.entries.associateWith { l ->
            layer(l).items.flatMap { it.keys }.filter(KeyText::isLayerKey).map(KeyText::layerOf).filter { it != l }.toSet()
        }
        fun reach(start: Layer): Set<Layer> {
            val seen = mutableSetOf(start)
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) for (n in next.getValue(queue.removeFirst())) if (seen.add(n)) queue.add(n)
            return seen
        }
        return reach(Layer.ABC).size == Layer.entries.size && Layer.entries.all { Layer.ABC in reach(it) }
    }

    /**
     * Keeps an edited [layer] view: its own rows and tray as the layer's keys, and its bottom row as the letters'
     * bottom row, which every layer shares. A shared key left as the layer showed it keeps the letters' own faces.
     */
    fun setLayer(layer: Layer, edited: KeyGrid) {
        if (layer == Layer.ABC) return
        val bottom = rows - 1
        var next = maxOf(edited.nextId, edited.items.filter { !isShared(it) }.maxOfOrNull { it.id + 1 } ?: 0)
        val own = edited.items.filter { it.r in layerTop until bottom }.mapTo(mutableListOf()) {
            GridKey(if (isShared(it)) next++ else it.id, it.r - layerTop, it.c, it.w, it.keys.toMutableList(), it.idx).withSide(it)
        }
        layers[layer] = KeyGrid(4, own, next).also { g -> edited.tray.forEach { g.tray.add(it.toMutableList()) } }
        val before = items.filter { it.r == bottom }.associateBy { it.id }
        items.removeAll { it.r == bottom }
        for (key in edited.items) if (key.r == bottom) {
            val original = if (isShared(key)) before[key.id - SHARED_ID] else null
            val unchanged = original != null && sharedKeys(original) == key.keys
            items += GridKey(original?.id ?: nextId++, bottom, key.c, key.w,
                if (unchanged) original!!.keys else key.keys.toMutableList(), if (unchanged) original!!.idx else key.idx).withSide(key)
        }
    }

    /** A new key for faces coming out of the tray. It joins [items] only once a placement succeeds. */
    fun detachedKey(faces: List<String>) = GridKey(nextId++, 0, 0, 1, faces.toMutableList())

    /** Places a tray entry with [place]; on success the key joins the grid and leaves the tray. */
    fun placeFromTray(index: Int, place: (GridKey) -> Boolean): Boolean {
        val faces = trayFaces(index) ?: return false
        val key = detachedKey(faces)
        if (!place(key)) return false
        if (key !in items) items.add(key)
        if (index < tray.size) tray.removeAt(index)
        return true
    }

    /** The faces at tray [index]; one past the last entry is the editor's endless supply of gaps. */
    fun trayFaces(index: Int): List<String>? = if (index == tray.size) listOf(Keys.GAP) else tray.getOrNull(index)

    /** Puts [key] where [gap] is, in its place. */
    fun fillGap(key: GridKey, gap: GridKey): Boolean {
        if (!gap.isGap || key === gap || gap !in items) return false
        items.remove(gap)
        if (moveTo(key, gap.r, gap.c)) return true
        items.add(gap)
        return false
    }

    /** Puts a tray entry into an existing key, making it a swipe key. [at] is as for [combine]. */
    fun combineFromTray(index: Int, target: GridKey, at: Int? = null): Boolean {
        val faces = tray.getOrNull(index) ?: return false
        if (target.isGap) return false
        addFaces(target, faces, at); tray.removeAt(index)
        return true
    }

    /**
     * Adds [faces] to [target]: at the end, or before face [at]. A face the key already has moves instead of
     * repeating, so dropping #+= onto a key showing 123 puts #+= ahead of it.
     */
    private fun addFaces(target: GridKey, faces: List<String>, at: Int?) {
        val shown = target.keys.getOrNull(target.idx)
        val keep = target.keys.filter { it !in faces }
        val anchor = at?.let { target.keys.getOrNull(it) }
        target.keys.clear(); target.keys.addAll(keep)
        val added = faces.filter { it !in target.keys }
        val i = anchor?.let { target.keys.indexOf(it) }?.takeIf { it >= 0 } ?: target.keys.size
        target.keys.addAll(i, added)
        target.idx = shown?.let { target.keys.indexOf(it) }?.takeIf { it >= 0 } ?: 0
    }

    private fun mapLetters(lang: String) {
        val from = "qwertyuiopasdfghjkl'zxcvbnm"
        val to = when (lang) {
            "es" -> "qwertyuiopasdfghjklñzxcvbnm"
            "de" -> "qwertzuiopasdfghjklßyxcvbnm"
            "fr" -> "azertyuiopqsdfghjklmwxcvbn'"
            else -> return
        }
        items.forEach { key -> key.keys.indices.forEach { i ->
            val code = key.keys[i]
            val index = if (code.length == 1) from.indexOf(code) else -1
            if (index >= 0) key.keys[i] = to[index].toString()
        } }
    }

    /** Recognized presets follow the active language in memory; custom layouts keep every key. */
    fun languageVariant(lang: String): KeyGrid {
        for (t in Template.entries) for (source in listOf("en", "es", "de", "fr")) {
            val candidate = template(t, source)
            if (rows == 5) candidate.setNumberRow(true)
            if (rows == candidate.rows && items.size == candidate.items.size && items.zip(candidate.items).all { (a, b) ->
                    a.r == b.r && a.c == b.c && a.w == b.w && a.keys == b.keys }) {
                val result = template(t, lang)
                if (rows == 5) result.setNumberRow(true)
                result.items.zip(items).forEach { (a, b) -> a.idx = b.idx }
                tray.forEach { result.tray.add(it.toMutableList()) }
                layers.forEach { (l, g) -> result.layers[l] = g.copy() }
                return result
            }
        }
        return this
    }

    fun itemAt(r: Int, c: Int, skip: GridKey? = null) =
        items.firstOrNull { it !== skip && it.r == r && c >= it.c && c < it.c + it.w }

    private fun occupied(r: Int, c: Int, skip: GridKey?) = itemAt(r, c, skip) != null

    /**
     * Why [key] can't be removed, or null. Backspace, enter and space must stay. Layer keys may go as long as every
     * layer can still be reached from the letters and left again, counting the other layers' keys too.
     */
    fun removalHint(key: GridKey): String? {
        val protected = linkedMapOf(Keys.BACKSPACE to "Backspace", Keys.ENTER to "Enter", Keys.SPACE to "Space")
        protected.entries.firstOrNull { (code, _) -> code in key.keys && items.none { it !== key && code in it.keys } }
            ?.let { return "${it.value} can't be removed" }
        if (key.keys.none(KeyText::isLayerKey)) return null
        val (letters, layer) = source ?: (this to Layer.ABC)
        val without = copy().also { c -> c.items.removeAll { it.id == key.id } }
        val after = if (layer == Layer.ABC) without else letters.copy().also { it.setLayer(layer, without) }
        return if (letters.routesOk() && !after.routesOk()) "Keep a way to 123 and #+= and back" else null
    }

    /** Removing sets the key aside in the [tray]; nothing is lost until it is deleted from there. A gap just goes. */
    fun remove(key: GridKey): Boolean {
        if (key !in items || removalHint(key) != null) return false
        items.remove(key)
        compactRow(key.r, key.c)
        if (key.isGap) return true
        tray.add(key.keys.toMutableList())
        if (tray.size > MAX_TRAY) tray.removeAt(0)
        return true
    }

    /** Preserve key order and widths within this half; geometry stretches its remaining columns evenly. */
    fun compactRow(row: Int, column: Int) {
        var c = bounds(column).first
        items.filter { it.r == row && region(it.c) == region(column) }.sortedBy { it.c }.forEach {
            it.c = c; c += it.w
        }
    }

    fun rowColumns(row: Int, column: Int): Int {
        val start = bounds(column).first
        var span = 0
        val side = region(column)
        for (key in items) if (key.r == row && region(key.c) == side) span = maxOf(span, key.c + key.w - start)
        return if (span > 0) span else bounds(column).count()
    }

    fun valid(key: GridKey): Boolean {
        // Check each operand before adding: imported Int.MAX_VALUE widths must not wrap.
        if (rows !in 4..5 || key.c !in 0 until COLS || key.w !in 1..COLS ||
            key.w > COLS - key.c || key.r !in 0 until rows || key.keys.isEmpty() || key.keys.any { it.isEmpty() } ||
            key.idx !in key.keys.indices) return false
        if (region(key.c) != region(key.c + key.w - 1)) return false
        return (0 until key.w).none { occupied(key.r, key.c + it, key) }
    }

    fun normalize() {
        for (key in items) {
            if (!key.isSpace) continue
            when (region(key.c)) {
                0 -> while (key.c + key.w <= 4 && !occupied(key.r, key.c + key.w, key)) key.w++
                2 -> while (key.c - 1 >= 6 && !occupied(key.r, key.c - 1, key)) { key.c--; key.w++ }
            }
        }
    }

    /** Moves a key to an empty cell. Wide keys shrink to fit the region they land in. */
    fun moveTo(key: GridKey, r: Int, c: Int): Boolean {
        val r0 = key.r; val c0 = key.c; val w0 = key.w
        key.r = r; key.c = c; key.w = minOf(key.w, bounds(c).last - c + 1)
        while (!valid(key) && key.w > 1) key.w--
        if (valid(key)) return true
        key.r = r0; key.c = c0; key.w = w0
        return false
    }

    /** Puts [key] on the inner side of a spacebar, which gives up one cell toward the outside. */
    fun insertBesideSpace(key: GridKey, space: GridKey): Boolean {
        if (space.w < 2 || region(space.c) == 1 || key.isSpace) return false
        key.r = space.r; key.w = 1
        if (region(space.c) == 0) { space.w--; key.c = space.c + space.w } else { key.c = space.c; space.c++; space.w-- }
        return true
    }

    /**
     * Inserts [key] beside [target] and shifts the neighbours over by one. Walks outward from the
     * insertion point on each side, collecting the keys in the way, until it reaches an empty cell or a
     * spacebar (which shrinks by one instead). The side that moves fewer keys wins.
     */
    fun insertShift(key: GridKey, target: GridKey, before: Boolean): Boolean {
        if (key.isSpace) return false
        val r = target.r
        val range = bounds(target.c)
        val b = if (before) target.c else target.c + target.w

        class Plan(val dir: Int, val chain: List<GridKey>, val absorb: GridKey?)

        fun plan(dir: Int): Plan? {
            var x = if (dir > 0) b else b - 1
            if (x !in range) return null
            val chain = mutableListOf<GridKey>()
            while (x in range) {
                val o = itemAt(r, x, key) ?: return Plan(dir, chain, null)
                if (o.isSpace && o.w > 1) return Plan(dir, chain, o)
                chain += o
                x = if (dir > 0) o.c + o.w else o.c - 1
            }
            return null
        }

        val right = plan(1)
        val left = plan(-1)
        val p = (if (right != null && left != null) (if (left.chain.size < right.chain.size) left else right) else right ?: left)
            ?: return false
        p.chain.forEach { it.c += p.dir }
        p.absorb?.let { s -> if (p.dir > 0) s.c++; s.w-- }
        key.r = r; key.w = 1; key.c = if (p.dir > 0) b else b - 1
        return true
    }

    fun swap(a: GridKey, b: GridKey): Boolean {
        val ar = a.r; val ac = a.c; val br = b.r; val bc = b.c
        a.r = br; a.c = bc; b.r = ar; b.c = ac
        if (valid(a) && valid(b)) return true
        a.r = ar; a.c = ac; b.r = br; b.c = bc
        return false
    }

    /** Drops [key] onto [target], making [target] a swipe key that also holds [key]'s keys (before face [at], if given). */
    fun combine(key: GridKey, target: GridKey, at: Int? = null) {
        if (key === target || key.isGap || target.isGap) return
        addFaces(target, key.keys, at)
        items.remove(key)
    }

    /**
     * Splits a swipe key back into single keys. The first face stays; the others go to the [tray] as their
     * own keys, so they never land in a hidden cell and can be placed exactly where they belong.
     */
    fun separate(key: GridKey) {
        val rest = key.keys.drop(1)
        while (key.keys.size > 1) key.keys.removeAt(key.keys.lastIndex)
        key.idx = 0
        rest.forEach { tray.add(mutableListOf(it)) }
        while (tray.size > MAX_TRAY) tray.removeAt(0)
    }

    private fun findEmpty(r0: Int, c0: Int): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestDist = Int.MAX_VALUE
        for (r in 0 until rows) for (c in 0 until COLS) {
            if (occupied(r, c, null)) continue
            val d = abs(r - r0) * 10 + abs(c - c0)
            if (d < bestDist) { bestDist = d; best = r to c }
        }
        return best
    }

    /** Explicit editor action only. Persisted grids are never silently upgraded. */
    fun addInsert(): Boolean {
        if (items.any { Keys.INSERT in it.keys }) return false
        val key = GridKey(nextId++, rows - 1, 1, 1, mutableListOf(Keys.INSERT))
        val num = items.firstOrNull { Keys.NUM in it.keys }
        if (num != null && insertShift(key, num, before = false)) { items.add(key); return true }
        val space = items.firstOrNull { it.isSpace && it.w > 1 }
        if (space != null && insertBesideSpace(key, space)) { items.add(key); return true }
        val spot = findEmpty(rows - 1, 1) ?: return false
        key.r = spot.first; key.c = spot.second; items.add(key); return true
    }

    /** Adds or removes a top row of digit keys. Removing fails while other keys sit in that row. */
    fun setNumberRow(on: Boolean): Boolean {
        if (on && rows == 4) {
            items.forEach { it.r++ }
            "1234567890".forEachIndexed { i, d -> items.add(GridKey(nextId++, 0, if (i < 5) i else i + 1, 1, mutableListOf("d$d"))) }
            rows = 5
        } else if (!on && rows == 5) {
            items.removeAll { it.r == 0 && it.keys.all(KeyText::isDigit) }
            if (items.any { it.r == 0 }) return false
            items.forEach { it.r-- }
            rows = 4
        }
        return true
    }

    /** True when every key fits on the grid without overlapping. Imported layouts must pass this. */
    fun isSound() = rows in 4..5 && items.size in 1..(rows * COLS) && items.all { valid(it) }

    private fun itemsJson() = JSONArray().apply {
        items.forEach {
            put(JSONObject().put("r", it.r).put("c", it.c).put("w", it.w).put("k", JSONArray(it.keys)).put("i", it.idx)
                .apply { if (it.side != CenterSide.AUTO) put("s", it.side.name) })
        }
    }
    private fun trayJson() = JSONArray().apply { tray.forEach { put(JSONArray(it)) } }

    fun toJson(): String {
        val json = JSONObject().put("rows", rows).put("items", itemsJson())
        if (tray.isNotEmpty()) json.put("tray", trayJson())
        // Edited 123 and #+= layers ride along; layouts without them (and older apps) use the defaults.
        if (layers.isNotEmpty()) json.put("layers", JSONObject().apply {
            for (l in listOf(Layer.NUM, Layer.SYM)) layers[l]?.let { put(l.name.lowercase(), JSONObject().put("items", it.itemsJson()).put("tray", it.trayJson())) }
        })
        return json.toString()
    }

    /** A short text code for sharing the layout: the JSON, compressed and base64url-encoded. iOS reads the same codes. */
    fun toCode(settings: String? = null): String {
        val raw = JSONObject(toJson()).apply { settings?.let { put("settings", JSONObject(it)) } }.toString().toByteArray()
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        d.setInput(raw)
        d.finish()
        val buf = ByteArray(raw.size + 64)
        val n = d.deflate(buf)
        d.end()
        return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(buf.copyOf(n))
    }

    companion object {
        const val COLS = 11
        const val CENTER = 5
        const val MAX_TRAY = 24
        /** Ids of a layer view's shared keys (see [layer]) start here, clear of any id a layout gives its own keys. */
        const val SHARED_ID = 100_000

        /** A key shown on a 123 or #+= layer that comes from the letters' bottom row. */
        fun isShared(key: GridKey) = key.id >= SHARED_ID

        /** The iPhone's 123 and #+= keys, split five and five like the letters, with a key to the other layer. */
        fun defaultLayer(layer: Layer): KeyGrid {
            val items = mutableListOf<GridKey>()
            fun add(r: Int, c: Int, code: String) { items.add(GridKey(items.size, r, c, 1, mutableListOf(code))) }
            fun code(ch: Char) = if (ch.isDigit()) "d$ch" else KeyText.literal(ch.toString())
            fun row(r: Int, text: String) = text.forEachIndexed { i, ch -> add(r, if (i < 5) i else i + 1, code(ch)) }
            if (layer == Layer.NUM) { row(0, "1234567890"); row(1, "-/:;()$&@\"") } else { row(0, "[]{}#%^*+="); row(1, "_\\|~<>€£¥•") }
            add(2, 0, if (layer == Layer.NUM) Keys.SYM else Keys.NUM)
            add(2, 1, code('.')); add(2, 2, code(','))
            add(2, 6, code('?')); add(2, 7, code('!')); add(2, 8, code('\''))
            add(2, 9, Keys.BACKSPACE)
            return KeyGrid(4, items, items.size)
        }

        private fun parseItems(a: JSONArray): MutableList<GridKey> {
            fun integer(o: JSONObject, key: String) = o.get(key).toString().toInt()
            val items = mutableListOf<GridKey>()
            for (i in 0 until a.length()) {
                val j = a.getJSONObject(i)
                val ks = j.getJSONArray("k")
                items.add(GridKey(i, integer(j, "r"), integer(j, "c"), integer(j, "w"), MutableList(ks.length()) { ks.getString(it) },
                    if (j.has("i")) integer(j, "i") else 0).also { k ->
                        CenterSide.entries.firstOrNull { it.name == j.optString("s") }?.let { k.side = it }
                    })
            }
            return items
        }

        // The tray is optional and never makes a layout invalid; malformed entries are dropped.
        private fun parseTray(t: JSONArray?, grid: KeyGrid) {
            t ?: return
            for (i in 0 until minOf(t.length(), MAX_TRAY)) {
                val faces = t.optJSONArray(i) ?: continue
                val list = MutableList(faces.length()) { faces.optString(it) }.filter { it.isNotEmpty() && it.length <= 16 }
                if (list.isNotEmpty()) grid.tray.add(list.toMutableList())
            }
        }
        private const val CODE_PREFIX = "TK1-"
        private val CODE = Regex("TK1-([A-Za-z0-9_-]+)")

        fun region(c: Int) = when { c < 5 -> 0; c == 5 -> 1; else -> 2 }
        fun bounds(c: Int) = when (region(c)) { 0 -> 0..4; 1 -> 5..5; else -> 6..10 }

        /** Finds a layout code anywhere in [text], so a whole pasted message works. Null if there's none or it's broken. */
        data class DecodedLayout(val grid: KeyGrid, val name: String?, val settings: String? = null)

        fun fromCode(text: String): KeyGrid? = decodeCode(text)?.grid

        /** Bound clipboard input as well as inflated output. Optional names share the same decode. */
        fun decodeCode(text: String): DecodedLayout? {
            if (text.length > 128 * 1024) return null
            val m = CODE.find(text) ?: return null
            return try {
                val inf = Inflater(true)
                try {
                    inf.setInput(Base64.getUrlDecoder().decode(m.groupValues[1]) + 0)
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(1024)
                    while (!inf.finished()) {
                        val n = inf.inflate(buf)
                        if (n == 0 || out.size() + n > 64 * 1024) return null
                        out.write(buf, 0, n)
                    }
                    val json = out.toString("UTF-8")
                    val grid = fromJson(json) ?: return null
                    val name = JSONObject(json).opt("name") as? String
                    DecodedLayout(grid, name?.trim()?.take(24)?.takeIf { it.isNotEmpty() }, JSONObject(json).optJSONObject("settings")?.toString())
                } finally {
                    inf.end()
                }
            } catch (e: Exception) {
                null
            }
        }

        /** Starting layouts. Standard is [default]; the others put B on the left hand like ergonomic keyboards. */
        fun template(t: Template, lang: String = "en"): KeyGrid {
            if (t == Template.STANDARD) return default(lang)
            val items = mutableListOf<GridKey>()
            fun add(r: Int, c: Int, keys: List<String>, w: Int = 1) { items.add(GridKey(items.size, r, c, w, keys.toMutableList())) }
            fun row(r: Int, c0: Int, s: String) = s.forEachIndexed { i, ch -> add(r, c0 + i, listOf(ch.toString())) }
            row(0, 0, "qwert"); row(0, 6, "yuiop")
            row(1, 0, "asdfg"); row(1, 6, "hjkl'")
            row(2, 0, "zxcvb"); row(2, 6, "nm,.")
            add(3, 0, listOf(Keys.ABC, Keys.NUM, Keys.SYM))
            if (t == Template.SPLIT) {
                add(2, 10, listOf(Keys.BACKSPACE))
                add(3, 1, listOf(Keys.SPACE), 4)
                add(3, 6, listOf(Keys.SPACE), 4)
                add(3, 10, listOf(Keys.ENTER))
            } else {
                // Backspace and enter sit at the inner ends of the spacebars, under the thumbs.
                add(2, 10, listOf("?"))
                add(3, 1, listOf(Keys.SPACE), 3)
                add(3, 4, listOf(Keys.BACKSPACE))
                add(3, 6, listOf(Keys.ENTER))
                add(3, 7, listOf(Keys.SPACE), 4)
            }
            return KeyGrid(4, items, items.size).also { it.mapLetters(lang); it.addInsert() }
        }

        fun default(lang: String = "en"): KeyGrid {
            val items = mutableListOf<GridKey>()
            fun add(r: Int, c: Int, keys: List<String>, w: Int = 1) { items.add(GridKey(items.size, r, c, w, keys.toMutableList())) }
            "qwertyuiop".forEachIndexed { i, ch -> add(0, if (i < 5) i else i + 1, listOf(ch.toString())) }
            "asdfghjkl'".forEachIndexed { i, ch -> add(1, if (i < 5) i else i + 1, listOf(ch.toString())) }
            // iPhone arrangement: shift (abc) starts the bottom letter row, a wide backspace ends it,
            // and 123 (swipe for #+=) sits bottom left.
            add(2, 0, listOf(Keys.ABC))
            "zxcv".forEachIndexed { i, ch -> add(2, 1 + i, listOf(ch.toString())) }
            "bnm".forEachIndexed { i, ch -> add(2, 6 + i, listOf(ch.toString())) }
            add(2, 9, listOf(Keys.BACKSPACE), 2)
            add(3, 0, listOf(Keys.NUM, Keys.SYM))
            add(3, 1, listOf(Keys.SPACE), 4)
            add(3, 6, listOf(Keys.SPACE), 4)
            add(3, 10, listOf(Keys.ENTER))
            return KeyGrid(4, items, items.size).also { it.mapLetters(lang); it.addInsert() }
        }

        fun fromJson(s: String?): KeyGrid? {
            if (s.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(s)
                val a = o.getJSONArray("items")
                if (a.length() !in 1..55) return null
                val items = parseItems(a)
                KeyGrid(o.get("rows").toString().toInt(), items, items.size).takeIf { it.isSound() }?.also { grid ->
                    parseTray(o.optJSONArray("tray"), grid)
                    // An edited layer is kept only if every key fits its three rows; otherwise it falls back to the default.
                    o.optJSONObject("layers")?.let { ls ->
                        for (l in listOf(Layer.NUM, Layer.SYM)) {
                            val lj = ls.optJSONObject(l.name.lowercase()) ?: continue
                            val parsed = parseItems(lj.getJSONArray("items"))
                            val own = KeyGrid(4, parsed, parsed.size)
                            if (parsed.size > 3 * COLS || !parsed.all { own.valid(it) && it.r < 3 }) continue
                            parseTray(lj.optJSONArray("tray"), own)
                            grid.layers[l] = own
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        /** Shared recovery for persisted layouts, including old malformed saved state. */
        fun savedOrDefault(saved: KeyGrid?, lang: String, numberRow: Boolean): KeyGrid =
            saved?.takeIf { it.isSound() }?.languageVariant(lang)
                ?: default(lang).also { if (numberRow) it.setNumberRow(true) }
    }
}
