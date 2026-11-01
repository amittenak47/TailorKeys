package app.tailorkeys

import kotlin.math.min

/**
 * One typed word for the student model, as tools/lm/student.py encodes a slot: the reading (context, then the word's
 * taps) and a row per candidate.
 *
 *   reading     <bos> left context [<hole> the words after the slot] <fill> one <tap> per tap
 *   candidate   <cand> its word pieces <spell> its letters <extra> per extra tap <score>
 *
 * A letter carries the tap the search matched it to (that tap's position and how close it landed), or a gap marker
 * for a skipped key; an extra tap gets an <extra> token pointing at it.
 */
internal class StudentSlot(private val tokenizer: StudentTokenizer) {
    private val wordCache = object : LinkedHashMap<String, IntArray>(1024, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, IntArray>) = size > 20000
    }

    /** A candidate's word pieces: the word after a space, as it reads mid-sentence. */
    fun wordIds(word: String): IntArray = synchronized(wordCache) { wordCache.getOrPut(word) { tokenizer.encode(" $word") } }

    /** The context pieces before the slot (the last LEFT) and, when rechecking, after it (the first RIGHT). */
    fun context(left: String, right: String): Pair<IntArray, IntArray> {
        val l = if (left.isEmpty()) IntArray(0) else tokenizer.encode(left)
        val r = if (right.isEmpty()) IntArray(0) else tokenizer.encode(right)
        return l.copyOfRange(maxOf(0, l.size - LEFT), l.size) to r.copyOf(min(r.size, RIGHT))
    }

    /** The reading up to and including <fill>, in one row. */
    fun contextRow(left: IntArray, right: IntArray): StudentModel.Row {
        val ids = ArrayList<Int>()
        val seg = ArrayList<Int>()
        ids += BOS; seg += MARK
        for (p in left) { ids += p; seg += CONTEXT }
        if (right.isNotEmpty()) {
            ids += HOLE; seg += MARK
            for (p in right) { ids += p; seg += CONTEXT }
        }
        ids += FILL; seg += MARK
        return StudentModel.Row(ids.toIntArray(), seg.toIntArray())
    }

    /** Tap number [index] of the slot, as its own row. */
    fun tapRow(features: FloatArray, index: Int) = StudentModel.Row(intArrayOf(TAP), intArrayOf(TAPPED),
        tapx = arrayOf(StudentModel.tapInputs(features)), tapi = intArrayOf(index))

    /** The row that scores [word] against the slot's taps. */
    fun candidateRow(word: String, features: List<FloatArray>): StudentModel.Row {
        val w = wordIds(word)
        val al = TapSearch.align(features, word)
        val unmatched = al?.unmatched ?: IntArray(0)
        val n = 2 + w.size + word.length + unmatched.size + 1
        val ids = IntArray(n)
        val seg = IntArray(n)
        val alk = IntArray(n)
        val ali = IntArray(n)
        val alx = Array(n) { FloatArray(2) }
        var at = 0
        ids[at] = CAND; seg[at++] = MARK
        for (p in w) { ids[at] = p; seg[at++] = WORD }
        ids[at] = SPELL; seg[at++] = MARK
        for ((j, ch) in word.withIndex()) {
            val s = SPELLING.indexOf(ch)
            ids[at] = PIECES + if (s >= 0) s else SPELLING.length
            seg[at] = LETTER
            val t = al?.taps?.get(j)
            if (t != null) {
                if (t < 0) alk[at] = GAP
                else {
                    alk[at] = MATCHED; ali[at] = t
                    val col = if (ch == ' ') TapSearch.SPACE else TapSearch.LETTERS.indexOf(ch)
                    val d = if (col >= 0) features[t][col] else 9f
                    alx[at][0] = kotlin.math.exp(-d / .5f); alx[at][1] = min(d, 9f) / 9f
                }
            }
            at++
        }
        for (t in unmatched) { ids[at] = EXTRA; seg[at] = EXTRA_TAP; alk[at] = UNMATCHED; ali[at] = t; at++ }
        ids[at] = SCORE; seg[at] = MARK
        return StudentModel.Row(ids, seg, alk = alk, ali = ali, alx = alx)
    }

    companion object {
        const val PAD = 0
        const val BOS = 1
        const val HOLE = 2
        const val FILL = 3
        const val TAP = 4
        const val CAND = 5
        const val SPELL = 6
        const val EXTRA = 7
        const val SCORE = 8
        const val PIECES = 8192
        /** Letters have their own ids after the word pieces; then one id for any other character. */
        const val SPELLING = TapSearch.LETTERS + " "
        // Segments.
        const val CONTEXT = 0
        const val TAPPED = 1
        const val WORD = 2
        const val LETTER = 3
        const val EXTRA_TAP = 4
        const val MARK = 5
        // Alignment kinds.
        const val NO_ALIGN = 0
        const val MATCHED = 1
        const val GAP = 2
        const val UNMATCHED = 3
        /** Context kept: pieces before the slot, after it, and taps per word. */
        const val LEFT = 64
        const val RIGHT = 32
        const val MAX_TAPS = 24
        /** Candidates per slot before the shape list, as the gate. */
        const val LIMIT = 25

        /**
         * The candidates for a typed word (teacher.Slots.item at inference): what was typed first, then [also] (the
         * letters actually typed, when rechecking a word the keyboard changed), then the search's, capped at LIMIT,
         * then the shape words on top of the cap.
         */
        fun candidates(typed: String, found: List<String>, shapeAdded: Int, also: List<String> = emptyList()): List<String> {
            val keep = typed.lowercase()
            val shape = found.takeLast(shapeAdded)
            val rest = found.dropLast(shapeAdded)
            var cands = (listOf(keep) + (also + rest).filter { it != keep }).distinct()
            if (cands.size > LIMIT) {
                val must = setOf(keep) + also
                cands = cands.filter { it in must } + cands.filter { it !in must }.take(LIMIT - must.size)
            }
            return cands + shape.filter { it !in cands }
        }
    }
}
