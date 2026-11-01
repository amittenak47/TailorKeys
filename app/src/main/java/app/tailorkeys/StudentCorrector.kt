package app.tailorkeys

/**
 * The autocorrect model's decisions, as tools/lm's gate makes them (docs/implementation/autocorrect-rebuild.md):
 *   at the space bar   the candidates the taps could have meant are scored with the text before the word; the best
 *                      one is written (rule 2), with a capital when the model gives it one
 *   recheck            once words follow, the written word is scored again with both sides, the letters typed
 *                      offered too; it changes only when more likely wrong than right (rule 3)
 *   as you type        after each tap, the same space-bar decision for the word so far, and the wheel's words: what
 *                      the word is likely to be (live); the space bar then finds its answer ready when the taps match
 * Not thread-safe: one caller at a time, off the UI thread. Keeps the last context's reading, so a sentence typed
 * word by word reads only its new pieces.
 */
internal class StudentCorrector(private val model: StudentModel, private val search: TapSearch, private val slot: StudentSlot,
                                private val heads: List<String>) {
    /** The layout's key centres for the shape list (typing without looking); none leaves it out. */
    @Volatile var layout: TapSearch.Layout? = null
    /** Letters on the other half than usual (MovedKeys): a tap also counts as near where they usually are. */
    @Volatile var moved: MovedKeys? = null

    private var lastWheel = FloatArray(0)
    /** The runs of a [live] that a newer tap may stop ([cancelLive]); null outside one. Set and cleared under [stopLock]. */
    @Volatile private var stoppable: ai.onnxruntime.OrtSession.RunOptions? = null
    /**
     * Held while [cancelLive] calls setTerminate and while the worker sets or clears [stoppable]. The worker closes the
     * RunOptions (freeing it natively) only after clearing it, so a cancel from the UI thread never reaches freed memory:
     * RunOptions.close frees before it marks itself closed, and its closed check isn't synchronized.
     */
    private val stopLock = Any()
    /** Whether the last [live] answer was kept from the word's last tap (for DecisionLog). */
    @Volatile var reused = false
    private var asked: Asked? = null
    private var nextAsked: Pair<String, FloatArray>? = null
    private val searched = ArrayDeque<Pair<List<FloatArray>, Pair<List<String>, Int>>>()
    private var cachedIds = IntArray(0)
    private var cached = StudentModel.Reading.EMPTY

    /** One scored word: its candidates, each one's probability and capital logit, and which is the written word. */
    class Scored(val candidates: List<String>, val probability: FloatArray, val capital: FloatArray, val keep: Int) {
        val best: Int get() = probability.indices.maxBy { probability[it] }
    }

    /**
     * Scores the word written as [written] (what the text holds, typed as [taps]) between [left] and [right] (empty at
     * the space bar). [also] adds candidates, as the letters actually typed when rechecking a word already changed.
     */
    fun score(left: String, right: String, written: String, taps: List<FloatArray>, also: List<String> = emptyList()): Scored? {
        if (taps.isEmpty() || written.isBlank()) return null
        val f = (moved?.apply(taps) ?: taps).take(StudentSlot.MAX_TAPS)
        // A word is searched as it is typed, at its space and again when rechecked: the same taps give the same words.
        val hit = searched.firstOrNull { sameTaps(it.first, taps) }
        val (found, added) = hit?.second ?: (search.search(f, layout) to search.added).also {
            searched.addFirst(taps.map { t -> t.copyOf() } to it)
            if (searched.size > 4) searched.removeLast()
        }
        val cands = StudentSlot.candidates(written, found, added, also)
        val out = model.run(read(left, right, f), cands.map { slot.candidateRow(it, f) }, stoppable)
        return Scored(cands, StudentModel.softmax(out.score), out.capital, 0)
    }

    /** The reading: <bos> and the left context (kept from the last call when it continues it), then the rest at once. */
    private fun read(left: String, right: String, taps: List<FloatArray>): StudentModel.Reading {
        val (l, r) = slot.context(left, right)
        val ids = IntArray(l.size + 1).also { it[0] = StudentSlot.BOS; l.copyInto(it, 1) }
        val shared = ids.size >= cachedIds.size && cachedIds.indices.all { ids[it] == cachedIds[it] }
        val base = if (shared) cached else StudentModel.Reading.EMPTY
        val from = if (shared) cachedIds.size else 0
        if (from < ids.size) {
            val row = StudentModel.Row(ids.copyOfRange(from, ids.size), IntArray(ids.size - from) { if (from + it == 0) StudentSlot.MARK else StudentSlot.CONTEXT })
            cached = model.run(base, listOf(row), stoppable).reading!!
            cachedIds = ids
        }
        // [<hole> right] <fill> and the taps, in one row on top.
        val restIds = ArrayList<Int>()
        val restSeg = ArrayList<Int>()
        if (r.isNotEmpty()) { restIds += StudentSlot.HOLE; restSeg += StudentSlot.MARK; for (p in r) { restIds += p; restSeg += StudentSlot.CONTEXT } }
        restIds += StudentSlot.FILL; restSeg += StudentSlot.MARK
        val tapAt = restIds.size
        repeat(taps.size) { restIds += StudentSlot.TAP; restSeg += StudentSlot.TAPPED }
        val tapx = Array(restIds.size) { j -> if (j >= tapAt) StudentModel.tapInputs(taps[j - tapAt]) else FloatArray(StudentModel.TAP_INPUTS) }
        val tapi = IntArray(restIds.size) { j -> if (j >= tapAt) j - tapAt else 0 }
        val out = model.run(cached, listOf(StudentModel.Row(restIds.toIntArray(), restSeg.toIntArray(), tapx = tapx, tapi = tapi)), stoppable)
        lastWheel = out.wheel ?: FloatArray(0)
        return out.reading!!
    }

    /**
     * The space bar: what to write for the word typed as [typed] after [left] (and before [right], when it was typed
     * into the middle of the text), or null to leave it. [first] is a sentence start, written with a capital.
     */
    fun atSpace(left: String, right: String, typed: String, taps: List<FloatArray>, first: Boolean): String? = live(left, right, typed, taps, first)?.fix

    /** The word being typed, as the space bar would decide it now: [fix] (null to leave it), [scored], and the wheel. */
    class Live(val fix: String?, val scored: Scored, val wheel: FloatArray)
    private class Asked(val left: String, val right: String, val typed: String, val taps: List<FloatArray>, val first: Boolean, val live: Live)

    /**
     * [atSpace] with its scores; asked again with the same word and taps (at the space, after its last tap) it is kept.
     * [stoppable] lets [cancelLive] stop it, throwing, for a word typed on since.
     */
    fun live(left: String, right: String, typed: String, taps: List<FloatArray>, first: Boolean, stoppable: Boolean = false): Live? {
        reused = true
        asked?.takeIf { it.left == left && it.right == right && it.typed == typed && it.first == first && sameTaps(it.taps, taps) }?.let { return it.live }
        reused = false
        val s = if (!stoppable) score(left, right, typed, taps) ?: return null else ai.onnxruntime.OrtSession.RunOptions().use { o ->
            synchronized(stopLock) { this.stoppable = o }
            try { score(left, right, typed, taps) } finally { synchronized(stopLock) { this.stoppable = null } }
        } ?: return null
        val p = s.best
        // A word left as typed gets a capital mid-sentence only when it is a word (a name), not a typo (mornonf).
        val word = written(s.candidates[p], if (p == s.keep && !first && s.candidates[p].lowercase() !in search.index) -1f else s.capital[p], first)
        val fix = if (p == s.keep) word.takeIf { it != typed && typed == typed.lowercase() } else word.takeIf { slipsFit(typed, it, taps) }
        return Live(fix, s, StudentModel.softmax(lastWheel)).also { asked = Asked(left, right, typed, taps.map { it.copyOf() }, first, it) }
    }

    /** Stops a stoppable [live] in progress (from any thread): the word it is for has changed. */
    fun cancelLive() { synchronized(stopLock) { try { stoppable?.setTerminate(true) } catch (e: Throwable) {} } }

    /** The wheel after the taps so far, without scoring candidates: quick enough for every tap. */
    fun typing(left: String, right: String, taps: List<FloatArray>): FloatArray {
        read(left, right, (moved?.apply(taps) ?: taps).take(StudentSlot.MAX_TAPS))
        return StudentModel.softmax(lastWheel)
    }

    /** The wheel before a word is typed: what comes after [left]. */
    fun next(left: String): FloatArray {
        nextAsked?.takeIf { it.first == left }?.let { return it.second }
        val (l, _) = slot.context(left, "")
        val ids = IntArray(l.size + 2).also { it[0] = StudentSlot.BOS; l.copyInto(it, 1); it[it.size - 1] = StudentSlot.FILL }
        val seg = IntArray(ids.size) { if (it == 0 || it == ids.size - 1) StudentSlot.MARK else StudentSlot.CONTEXT }
        val wheel = StudentModel.softmax(model.run(StudentModel.Reading.EMPTY, listOf(StudentModel.Row(ids, seg))).wheel!!)
        nextAsked = left to wheel
        return wheel
    }

    /**
     * The two wheels from the model, the way the LSTM filled them: the right one the likeliest words ([live]'s choice
     * first, then the letters as typed when it differs and the next best candidates, then the wheel's), the left one a
     * draw at temperature 2.5 from the wheel's other words, seeded by the word and context so it holds still.
     */
    fun wheels(wheel: FloatArray, live: Live?, typed: String, seed: String, n: Int = 8): Pair<List<String>, List<String>> {
        val order = (1 until minOf(wheel.size, heads.size + 1)).sortedByDescending { wheel[it] }
        val right = ArrayList<String>()
        val shown = HashSet<String>()
        fun add(w: String) { if (right.size < n && shown.add(w.lowercase())) right += w }
        if (live != null) {
            val s = live.scored
            add(live.fix ?: typed)
            add(typed)
            s.candidates.indices.filter { it != s.keep && s.probability[it] >= ALSO_SHOWN }.sortedByDescending { s.probability[it] }
                .take(2).forEach { add(matchCase(s.candidates[it], typed)) }
        }
        for (i in order) { if (right.size >= n) break; add(matchCase(heads[i - 1], typed)) }
        val rnd = java.util.Random(seed.hashCode().toLong())
        // Weighted sampling without replacement, as the LSTM's: rnd^(1 / p^(1/2.5)).
        val left = order.take(300).filter { heads[it - 1] !in shown }
            .map { it to Math.pow(rnd.nextDouble(), 1.0 / Math.pow(wheel[it].toDouble(), 1 / 2.5)) }
            .sortedByDescending { it.second }.take(n).map { matchCase(heads[it.first - 1], typed) }
        return left to right
    }

    private fun sameTaps(a: List<FloatArray>, b: List<FloatArray>) = a.size == b.size && a.indices.all { a[it].contentEquals(b[it]) }

    private fun matchCase(w: String, prefix: String) = when {
        prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase()
        prefix.isNotEmpty() && prefix[0].isUpperCase() -> w.replaceFirstChar { it.uppercase() }
        else -> w
    }

    /**
     * The recheck: what to write instead of [written] (typed as [typed]) now that [right] follows, or null to leave it.
     * Only when the model finds the written word more likely wrong than right.
     */
    fun recheck(left: String, right: String, written: String, typed: String, taps: List<FloatArray>, first: Boolean): String? {
        val also = if (typed.isNotBlank() && !typed.equals(written, ignoreCase = true)) listOf(typed.lowercase()) else emptyList()
        val s = score(left, right, written, taps, also) ?: return null
        val p = s.best
        if (p == s.keep || s.probability[s.keep] >= .5f) return null
        return written(s.candidates[p], s.capital[p], first).takeIf { slipsFit(typed, it, taps) }
    }

    /**
     * A digit or symbol typed inside a word (the number row is right above the letters) is changed only into a letter
     * whose key the tap landed beside: hel4o can become hello, while mp3 and covid19 stay unless their taps point at
     * letters. Needs one tap per character typed.
     */
    private fun slipsFit(typed: String, fix: String, taps: List<FloatArray>): Boolean {
        val odd = typed.indices.filter { typed[it].lowercaseChar() !in TapSearch.LETTERS }
        if (odd.isEmpty()) return true
        if (taps.size != typed.length || odd.size * 2 > typed.length) return false
        val al = TapSearch.align(taps, fix.lowercase()) ?: return false
        return odd.all { t ->
            val i = al.taps.indexOf(t)
            val col = if (i < 0) -1 else TapSearch.LETTERS.indexOf(fix[i].lowercaseChar())
            col >= 0 && taps[t][col] <= SLIP_REACH
        }
    }

    private companion object {
        /** How far (squared, in key spacings) a stray digit's tap may be from the letter it becomes: about a row. */
        const val SLIP_REACH = 1.6f
        /** Other candidates shown on the right wheel from this probability. */
        const val ALSO_SHOWN = .05f
    }

    private fun written(word: String, capital: Float, first: Boolean) =
        if ((capital > 0 || first) && word.isNotEmpty()) word[0].uppercaseChar() + word.substring(1) else word
}
