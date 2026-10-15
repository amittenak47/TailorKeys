package app.tailorkeys.layout

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

object Keys {
    const val SPACE = "space"
    const val BACKSPACE = "bs"
    const val ENTER = "en"
    const val ABC = "abc"
    const val NUM = "num"
    const val SYM = "sym"
}

/** One key on the grid. A key with more than one entry in [keys] is a swipe key that cycles through them. */
class GridKey(val id: Int, var r: Int, var c: Int, var w: Int, val keys: MutableList<String>, var idx: Int = 0) {
    val isStack get() = keys.size > 1
    val isSpace get() = keys.size == 1 && keys[0] == Keys.SPACE
}

/**
 * The keyboard is a grid of five columns on the left (0-4), a center column (5) and five on the right (6-10).
 * A key never crosses between those three regions. Spacebars stretch toward the center over empty cells.
 */
class KeyGrid private constructor(var rows: Int, val items: MutableList<GridKey>, private var nextId: Int) {

    fun itemAt(r: Int, c: Int, skip: GridKey? = null) =
        items.firstOrNull { it !== skip && it.r == r && c >= it.c && c < it.c + it.w }

    private fun occupied(r: Int, c: Int, skip: GridKey?) = itemAt(r, c, skip) != null

    fun valid(key: GridKey): Boolean {
        if (key.c < 0 || key.c + key.w > COLS || key.r < 0 || key.r >= rows) return false
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

    /** Drops [key] onto [target], making [target] a swipe key that also holds [key]'s keys. */
    fun combine(key: GridKey, target: GridKey) {
        if (key === target) return
        target.keys.addAll(key.keys)
        items.remove(key)
    }

    /** Splits a swipe key back into single keys, placed in the nearest empty cells. */
    fun separate(key: GridKey) {
        val rest = key.keys.drop(1)
        while (key.keys.size > 1) key.keys.removeAt(key.keys.lastIndex)
        key.idx = 0
        for (k in rest) {
            val spot = findEmpty(key.r, key.c)
            if (spot == null) key.keys.add(k) else items.add(GridKey(nextId++, spot.first, spot.second, 1, mutableListOf(k)))
        }
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

    fun toJson(): String {
        val a = JSONArray()
        items.forEach {
            a.put(JSONObject().put("r", it.r).put("c", it.c).put("w", it.w).put("k", JSONArray(it.keys)).put("i", it.idx))
        }
        return JSONObject().put("rows", rows).put("items", a).toString()
    }

    companion object {
        const val COLS = 11
        const val CENTER = 5

        fun region(c: Int) = when { c < 5 -> 0; c == 5 -> 1; else -> 2 }
        fun bounds(c: Int) = when (region(c)) { 0 -> 0..4; 1 -> 5..5; else -> 6..10 }

        fun default(): KeyGrid {
            val items = mutableListOf<GridKey>()
            fun add(r: Int, c: Int, keys: List<String>, w: Int = 1) { items.add(GridKey(items.size, r, c, w, keys.toMutableList())) }
            "qwertyuiop".forEachIndexed { i, ch -> add(0, if (i < 5) i else i + 1, listOf(ch.toString())) }
            "asdfghjkl'".forEachIndexed { i, ch -> add(1, if (i < 5) i else i + 1, listOf(ch.toString())) }
            "zxcv".forEachIndexed { i, ch -> add(2, i, listOf(ch.toString())) }
            "bnm".forEachIndexed { i, ch -> add(2, 7 + i, listOf(ch.toString())) }
            add(2, 10, listOf(Keys.BACKSPACE))
            add(3, 0, listOf(Keys.ABC, Keys.NUM, Keys.SYM))
            add(3, 1, listOf(Keys.SPACE), 4)
            add(3, 6, listOf(Keys.SPACE), 4)
            add(3, 10, listOf(Keys.ENTER))
            return KeyGrid(4, items, items.size)
        }

        fun fromJson(s: String?): KeyGrid? {
            if (s.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(s)
                val a = o.getJSONArray("items")
                val items = mutableListOf<GridKey>()
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    val ks = j.getJSONArray("k")
                    items.add(GridKey(i, j.getInt("r"), j.getInt("c"), j.getInt("w"), MutableList(ks.length()) { ks.getString(it) }, j.optInt("i")))
                }
                KeyGrid(o.getInt("rows"), items, items.size)
            } catch (e: Exception) {
                null
            }
        }
    }
}
