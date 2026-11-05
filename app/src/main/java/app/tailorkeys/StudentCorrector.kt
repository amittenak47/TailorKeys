package app.tailorkeys

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * The autocorrect model's decisions, as tools/lm's gate makes them (docs/implementation/autocorrect-rebuild.md):
 *   at the space bar   the candidates the taps could have meant are scored with the text before the word; the best
 *                      one is written (rule 2), with a capital when the model gives it one
 *   recheck            once words follow, the written word is scored again with both sides, the letters typed
 *                      offered too; it changes only when more likely wrong than right (rule 3)
 *   as you type        after each tap, the same space-bar decision for the word so far, and the wheel's words: what
 *                      the word is likely to be (live); the space bar then finds its answer ready when the taps match
 * Not thread-safe: one caller at a time, off the UI thread. Each thing is read once: the longest context read so far
 * is kept, so a sentence typed word by word reads only its new pieces, and a shorter start of it (the recheck's text
 * before the word before, the wheel's before a word) is its first rows, cut rather than read again; the reading
 * through <fill> and the taps is kept too, so each tap reads only itself and the space bar reuses the last tap's.
 */
internal class StudentCorrector(private val model: StudentModel, private val search: TapSearch, private val slot: StudentSlot,
                                private val heads: List<String>) {
    /** The layout's key centres for the shape list (typing without looking); none leaves it out. */
    @Volatile var layout: TapSearch.Layout? = null
    /** Letters on the other half than usual (MovedKeys): a tap also counts as near where they usually are. */
    @Volatile var moved: MovedKeys? = null

    private var lastWheel = FloatArray(0)
    private var seenLayout: TapSearch.Layout? = null
    private var seenMoved: MovedKeys? = null
    /** Whether the last [read] ran the model (false: all it needed was kept). */
    private var readRan = false
    /** The wheel's index of each of its words (index 0 is any other word). */
    private val headIndex = heads.withIndex().associate { (i, w) -> w to i + 1 }
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
    private val searched = ArrayDeque<Triple<String, List<FloatArray>, Pair<List<String>, Int>>>()
    private var cachedIds = IntArray(0)
    private var cached = StudentModel.Reading.EMPTY
    /** A reading through [<hole> right] <fill> and taps on top of a context, and the wheel after it. */
    private class Rest(val context: IntArray, val right: IntArray, val taps: List<FloatArray>, val reading: StudentModel.Reading,
                       val wheel: FloatArray)
    /** The last two such readings, newest first: the word being typed, and a recheck between its taps. */
    private val rests = ArrayDeque<Rest>()

    /** One scored word: its candidates, each one's probability and capital logit, and which is the written word. */
    class Scored(val candidates: List<String>, val probability: FloatArray, val capital: FloatArray, val keep: Int) {
        val best: Int get() = probability.indices.maxBy { probability[it] }
    }

    /**
     * Scores the word written as [written] (what the text holds, typed as [taps]) between [left] and [right] (empty at
     * the space bar). [also] adds candidates, as the letters actually typed when rechecking a word already changed.
     * [typed], the letters as typed, is what the search looks up in the misspelling list.
     */
    fun score(left: String, right: String, written: String, taps: List<FloatArray>, also: List<String> = emptyList(),
              typed: String = written): Scored? {
        if (taps.isEmpty() || written.isBlank()) return null
        geometry()
        val f = (moved?.apply(taps) ?: taps).take(StudentSlot.MAX_TAPS)
        // A word is searched as it is typed, at its space and again when rechecked: the same taps give the same words.
        // A new search runs on its own thread while the model reads.
        val key = typed.ifBlank { written }.lowercase()
        val hit = searched.firstOrNull { it.first == key && sameTaps(it.second, taps) }
        val searching = if (hit == null) SEARCHER.submit(Callable { search.search(f, layout, typed = key) to search.added }) else null
        val reading = read(left, right, f)
        val (found, added) = hit?.third ?: try { searching!!.get() } catch (e: ExecutionException) { throw e.cause ?: e }.also {
            searched.addFirst(Triple(key, taps.map { t -> t.copyOf() }, it))
            // A recheck comes up to a few dozen words later and finds its word's search here.
            if (searched.size > SEARCHED) searched.removeLast()
        }
        val cands = StudentSlot.candidates(written, found, added, also)
        val out = model.run(reading, cands.map { slot.candidateRow(it, f) }, stoppable)
        return Scored(cands, StudentModel.softmax(out.score), out.capital, 0)
    }

    /**
     * The reading: <bos> and the left context, then [<hole> right] <fill> and the taps. Kept from earlier calls where
     * they share it: the same context, right side and taps are not read again, and taps added since are read alone.
     */
    private fun read(left: String, right: String, taps: List<FloatArray>): StudentModel.Reading {
        val (l, r) = slot.context(left, right)
        val ids = IntArray(l.size + 1).also { it[0] = StudentSlot.BOS; l.copyInto(it, 1) }
        val kept = rests.firstOrNull { it.context.contentEquals(ids) && it.right.contentEquals(r) && it.taps.size <= taps.size &&
            sameTaps(it.taps, taps.subList(0, it.taps.size)) }
        if (kept != null && kept.taps.size == taps.size) { lastWheel = kept.wheel; readRan = false; return kept.reading }
        readRan = true
        val restIds = ArrayList<Int>()
        val restSeg = ArrayList<Int>()
        val firstTap = kept?.taps?.size ?: 0
        if (kept == null) {
            if (r.isNotEmpty()) { restIds += StudentSlot.HOLE; restSeg += StudentSlot.MARK; for (p in r) { restIds += p; restSeg += StudentSlot.CONTEXT } }
            restIds += StudentSlot.FILL; restSeg += StudentSlot.MARK
        }
        val tapAt = restIds.size
        repeat(taps.size - firstTap) { restIds += StudentSlot.TAP; restSeg += StudentSlot.TAPPED }
        val tapx = Array(restIds.size) { j -> if (j >= tapAt) StudentModel.tapInputs(taps[firstTap + j - tapAt]) else FloatArray(StudentModel.TAP_INPUTS) }
        val tapi = IntArray(restIds.size) { j -> if (j >= tapAt) firstTap + j - tapAt else 0 }
        val base = kept?.reading ?: context(ids)
        val out = model.run(base, listOf(StudentModel.Row(restIds.toIntArray(), restSeg.toIntArray(), tapx = tapx, tapi = tapi)), stoppable)
        lastWheel = out.wheel ?: FloatArray(0)
        rests.addFirst(Rest(ids, r, taps.map { it.copyOf() }, out.reading!!, lastWheel))
        if (rests.size > 2) rests.removeLast()
        return out.reading!!
    }

    /**
     * The context's reading: the kept one when it is the same or continues it; its first rows when the context is a
     * shorter start of it, keeping the longer for the next word; else read from where the two part.
     */
    private fun context(ids: IntArray): StudentModel.Reading {
        var n = 0
        while (n < ids.size && n < cachedIds.size && ids[n] == cachedIds[n]) n++
        if (n == ids.size) return cached.prefix(n)
        val row = StudentModel.Row(ids.copyOfRange(n, ids.size), IntArray(ids.size - n) { if (n + it == 0) StudentSlot.MARK else StudentSlot.CONTEXT })
        cached = model.run(cached.prefix(n), listOf(row), stoppable).reading!!
        cachedIds = ids
        return cached
    }

    /**
     * The space bar: what to write for the word typed as [typed] after [left] (and before [right], when it was typed
     * into the middle of the text), or null to leave it. [first] is a sentence start, written with a capital.
     */
    fun atSpace(left: String, right: String, typed: String, taps: List<FloatArray>, first: Boolean): String? = live(left, right, typed, taps, first)?.fix

    /** The word being typed, as the space bar would decide it now: [fix] (null to leave it), [scored], and the wheel. */
    class Live(val fix: String?, val scored: Scored, val wheel: FloatArray)
    private class Asked(val left: String, val right: String, val typed: String, val taps: List<FloatArray>, val first: Boolean, val live: Live,
                        val source: Source = Source.SCORED)

    /**
     * [atSpace] with its scores; asked again with the same word and taps (at the space, after its last tap) it is kept.
     * [stoppable] lets [cancelLive] stop it, throwing, for a word typed on since.
     */
    fun live(left: String, right: String, typed: String, taps: List<FloatArray>, first: Boolean, stoppable: Boolean = false): Live? {
        geometry()
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
        val fix = if (p == s.keep) word.takeIf { it != typed && typed == typed.lowercase() }
            else word.takeIf { slipsFit(typed, it, taps) }?.let { casedAs(typed, it) }
        return Live(fix, s, StudentModel.softmax(lastWheel)).also { asked = Asked(left, right, typed, taps.map { it.copyOf() }, first, it) }
    }

    /** Where a space-bar decision came from: the full scoring of every candidate, or the wheel alone ([decide]). */
    enum class Source { SCORED, WHEEL_KEEP }
    /** A space-bar decision, where it came from, and whether the wheel for the word's last tap was already read. */
    class Decision(val live: Live, val source: Source, val wheelReady: Boolean)

    /**
     * The space bar's decision. When the wheel after the word's last tap (the suggestion strip's own reading, usually
     * kept already) gives the word as typed at least [FAST_KEEP], the word is kept without scoring every candidate:
     * only its own row is scored, for its capital. Otherwise, or when the last tap's [live] answer is already kept, as
     * [live]. Measured on the gate's 16,553 words (2026-11-02, today's model, PyTorch, left side only): at 0.95 a
     * third of words are kept this way, and every count is the same as scoring them all.
     */
    fun decide(left: String, right: String, typed: String, taps: List<FloatArray>, first: Boolean): Decision? {
        if (taps.isEmpty() || typed.isBlank()) return null
        geometry()
        // Asked again (after the word's last tap, or a repeat): as decided then, with where that came from.
        asked?.takeIf { it.left == left && it.right == right && it.typed == typed && it.first == first && sameTaps(it.taps, taps) }
            ?.let { reused = true; return Decision(it.live, it.source, true) }
        reused = false
        val f = (moved?.apply(taps) ?: taps).take(StudentSlot.MAX_TAPS)
        val reading = read(left, right, f)
        val ready = !readRan
        val wheel = StudentModel.softmax(lastWheel)
        val keep = typed.lowercase()
        val id = headIndex[keep]
        if (id == null || id >= wheel.size || wheel[id] < FAST_KEEP) return live(left, right, typed, taps, first)?.let { Decision(it, Source.SCORED, ready) }
        val cands = listOf(keep)
        val out = model.run(reading, listOf(slot.candidateRow(keep, f)), stoppable, scoring = true)
        val s = Scored(cands, floatArrayOf(1f), floatArrayOf(out.capital[0]), 0)
        // As live() writes a kept word: a capital mid-sentence only for a known word (a name), not a typo.
        val word = written(keep, if (!first && keep !in search.index) -1f else s.capital[0], first)
        val fix = word.takeIf { it != typed && typed == typed.lowercase() }
        val l = Live(fix, s, wheel)
        asked = Asked(left, right, typed, taps.map { it.copyOf() }, first, l, Source.WHEEL_KEEP)
        return Decision(l, Source.WHEEL_KEEP, ready)
    }

    /** A new layout or moved keys change what the taps spell: the searches and the answer kept for them go. */
    private fun geometry() {
        if (layout === seenLayout && moved === seenMoved) return
        seenLayout = layout; seenMoved = moved
        searched.clear(); asked = null
    }

    /** Stops a stoppable [live] in progress (from any thread): the word it is for has changed. */
    fun cancelLive() { synchronized(stopLock) { try { stoppable?.setTerminate(true) } catch (e: Throwable) {} } }

    /** The wheel after the taps so far, without scoring candidates: quick enough for every tap. */
    fun typing(left: String, right: String, taps: List<FloatArray>): FloatArray {
        read(left, right, (moved?.apply(taps) ?: taps).take(StudentSlot.MAX_TAPS))
        return StudentModel.softmax(lastWheel)
    }

    /** The wheel before a word is typed: what comes after [left]. Its reading through <fill> starts the word's taps. */
    fun next(left: String): FloatArray {
        nextAsked?.takeIf { it.first == left }?.let { return it.second }
        read(left, "", emptyList())
        val wheel = StudentModel.softmax(lastWheel)
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
    fun recheck(left: String, right: String, written: String, typed: String, taps: List<FloatArray>, first: Boolean,
                keepWords: Boolean = RECHECK_KEEPS_WORDS): String? = rechecked(left, right, written, typed, taps, first, keepWords).fix

    /** A recheck's answer ([recheck]) with the scores it came from, for the dropdown of a word it changes. */
    class Rechecked(val fix: String?, val scored: Scored?)

    fun rechecked(left: String, right: String, written: String, typed: String, taps: List<FloatArray>, first: Boolean,
                  keepWords: Boolean = RECHECK_KEEPS_WORDS): Rechecked {
        val also = if (typed.isNotBlank() && !typed.equals(written, ignoreCase = true)) listOf(typed.lowercase()) else emptyList()
        val s = score(left, right, written, taps, also, typed) ?: return Rechecked(null, null)
        val p = s.best
        if (p == s.keep || s.probability[s.keep] >= .5f) return Rechecked(null, s)
        // The letters as typed are offered so a wrong fix can be undone, but never back into a word in none of the
        // lists: the space bar's late for lste stays ([RECHECK_KEEPS_WORDS]).
        if (keepWords && also.isNotEmpty() && s.candidates[p] == also[0] && also[0] !in search.index) return Rechecked(null, s)
        return Rechecked(written(s.candidates[p], s.capital[p], first).takeIf { slipsFit(typed, it, taps) }?.let { casedAs(typed, it) }, s)
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

    internal companion object {
        /**
         * A fix in the case it was typed in: a word typed in capitals (TEH, caps lock) is fixed in capitals (THE); one
         * with other capitals after its first letter (iPhnoe) is left as typed, as the keyboard always has.
         */
        fun casedAs(typed: String, fix: String): String? {
            val letters = typed.filter { it.isLetter() }
            return when {
                letters.length > 1 && letters.all { it.isUpperCase() } -> fix.uppercase()
                letters.drop(1).any { it.isUpperCase() } -> null
                else -> fix
            }
        }

        /**
         * The wheel's share for the word as typed from which the space bar keeps it without scoring every candidate.
         * Set for today's model (student-50m best.pt); the long run's model agrees with full scoring from 0.9, and a
         * new model needs its own value measured (tools: scratchpad diag/wheel_agree.py, 2026-11-02).
         */
        const val FAST_KEEP = 0.95f

        /**
         * A recheck never changes a word back to the letters as typed when they are in none of the word lists. On the
         * tablet (2026-11-02) "it is lste." was fixed to late at the space bar and changed back to lste by its recheck;
         * on the gate the guard changes no count for either model. Chosen to keep obvious typos fixed: for the user to
         * confirm.
         */
        const val RECHECK_KEEPS_WORDS = true
        /** How far (squared, in key spacings) a stray digit's tap may be from the letter it becomes: about a row. */
        private const val SLIP_REACH = 1.6f
        /** Words whose search is kept, for their space bar, previews and recheck. */
        private const val SEARCHED = 64
        /** The candidate search's own thread, so it runs while the model reads (one search at a time). */
        private val SEARCHER = Executors.newSingleThreadExecutor { r -> Thread(r, "TailorKeys-Search").apply { isDaemon = true } }
        /** Other candidates shown on the right wheel from this probability. */
        private const val ALSO_SHOWN = .05f
    }

    private fun written(word: String, capital: Float, first: Boolean) =
        if ((capital > 0 || first) && word.isNotEmpty()) word[0].uppercaseChar() + word.substring(1) else word
}
