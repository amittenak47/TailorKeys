package app.tailorkeys

/** Sorted prefix ranges and a shallow trie. Each subtree retains a dictionary range, not word copies. */
internal class CandidateIndex(words: Collection<String>) {
    private val words = words.sorted().toTypedArray()
    private class Node(val char: Char, val from: Int, val to: Int, val terminal: Boolean, val children: Array<Node>)
    private fun build(char: Char, from: Int, to: Int, depth: Int): Node {
        val terminal = words[from].length == depth
        val children = ArrayList<Node>()
        if (depth < DEPTH) {
            var i = from + if (terminal) 1 else 0
            while (i < to) {
                val c = words[i][depth]
                var end = i + 1
                while (end < to && words[end].length > depth && words[end][depth] == c) end++
                children.add(build(c, i, end, depth + 1))
                i = end
            }
        }
        return Node(char, from, to, terminal, children.toTypedArray())
    }
    private val root = if (this.words.isEmpty()) null else build('\u0000', 0, this.words.size, 0)
    private fun lowerBound(prefix: String): Int {
        var lo = 0; var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }
    fun hasPrefix(prefix: String): Boolean {
        val i = lowerBound(prefix)
        return i < words.size && words[i].startsWith(prefix)
    }
    fun visit(prefix: String, limit: Int, firstAllowed: (Char) -> Boolean,
              substitution: (Int, Char) -> Int, accept: (String) -> Unit) {
        // Literal completions bypass tap costs in the original scorer, even for a far-off tap.
        var completion = lowerBound(prefix)
        while (completion < words.size && words[completion].startsWith(prefix)) accept(words[completion++])
        if (prefix.length < 2) {
            return
        }
        fun correction(word: String) { if (!word.startsWith(prefix)) accept(word) }
        val band = limit / 8
        val rows = Array(DEPTH + 1) { IntArray(prefix.length + 1) { 1000 } }
        for (j in 0..minOf(prefix.length, band)) rows[0][j] = j * 8
        fun collect(node: Node) { for (i in node.from until node.to) correction(words[i]) }
        fun walk(node: Node, depth: Int, previousChar: Char, previousMin: Int) {
            if (depth == 1 && !firstAllowed(node.char) && node.char != prefix[1]) return
            val previous = rows[depth - 1]
            val current = rows[depth]
            current.fill(1000)
            current[0] = if (depth <= band) depth * 8 else 1000
            var rowMin = current[0]
            for (j in maxOf(1, depth - band)..minOf(prefix.length, depth + band)) {
                var value = minOf(previous[j] + 8, current[j - 1] + 8, previous[j - 1] + substitution(j - 1, node.char))
                if (depth > 1 && j > 1 && prefix[j - 1] == previousChar && prefix[j - 2] == node.char) {
                    value = minOf(value, rows[depth - 2][j - 2] + 6)
                }
                current[j] = value
                rowMin = minOf(rowMin, value)
            }
            // At this depth every descendant can use the same prefix match. Final scoring still
            // applies the original first-letter gate, extra cost and whole-word rules unchanged.
            // One level either side too: a skipped or extra key shifts the typed prefix (decl -> del).
            if (depth >= prefix.length - 1 && depth <= prefix.length + 1 && current[prefix.length] <= limit) { collect(node); return }
            if (node.terminal && current[prefix.length] <= limit) correction(words[node.from])
            if (rowMin > limit && previousMin > limit || depth >= prefix.length + band) return
            if (depth == DEPTH) {
                for (i in (node.from + if (node.terminal) 1 else 0) until node.to) correction(words[i])
                return
            }
            for (child in node.children) walk(child, depth + 1, node.char, rowMin)
        }
        root?.children?.forEach { walk(it, 1, '\u0000', 0) }
    }
    private companion object { const val DEPTH = 6 }
}
