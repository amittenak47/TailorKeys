package app.tailorkeys

import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors

/** One immutable weight set per process, including a remembered load failure. Never called on UI. */
internal object EnglishModel {
    val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "TailorKeys-English").apply { isDaemon = true } }
    private var attempted = false
    private var model: NextWordModel? = null

    @Synchronized fun load(openAsset: (String) -> InputStream): NextWordModel? {
        if (!attempted) {
            attempted = true
            model = try { openAsset("lm_en.bin").use { NextWordModel.load(it) } } catch (_: Exception) { null }
        }
        return model
    }
}

/** Tokenization from tools/lm/prepare.py. Empty/repeated sentence terminators add no sentences. */
internal object EnglishTokens {
    private val token = Regex("[\\p{L}\\p{Nl}\\p{No}]+(?:'[\\p{L}\\p{Nl}\\p{No}]+)*|\\p{Nd}+(?:[.,]\\p{Nd}+)*|[,.!?]")
    // Python str.isdigit() also recognizes these Numeric_Type=Digit (non-decimal) scalars.
    private val digits = intArrayOf(0xb2, 0xb3, 0xb9, 0xb9, 0x1369, 0x1371, 0x19da, 0x19da,
        0x2070, 0x2070, 0x2074, 0x2079, 0x2080, 0x2089, 0x2460, 0x2468, 0x2474, 0x247c,
        0x2488, 0x2490, 0x24ea, 0x24ea, 0x24f5, 0x24fd, 0x24ff, 0x24ff, 0x2776, 0x277e,
        0x2780, 0x2788, 0x278a, 0x2792, 0x10a40, 0x10a43, 0x10e60, 0x10e68, 0x11052, 0x1105a, 0x1f100, 0x1f10a)
    private fun digit(cp: Int): Boolean {
        if (Character.isDigit(cp)) return true
        for (i in digits.indices step 2) if (cp in digits[i]..digits[i + 1]) return true
        return false
    }

    fun context(text: String): List<String> {
        val sentences = ArrayList<MutableList<String>>()
        var current = ArrayList<String>()
        val normalized = text.lowercase(Locale.ROOT).replace('\u2019', '\'').replace('\u2018', '\'')
        for (match in token.findAll(normalized)) {
            val t = match.value
            if (t == "." || t == "!" || t == "?") {
                if (current.isNotEmpty()) {
                    sentences.add(current)
                    current = ArrayList()
                }
            } else current.add(if (digit(t.codePointAt(0))) "<num>" else if (t == ",") "<,>" else t)
        }
        val result = ArrayList<String>()
        for (sentence in sentences.takeLast(2)) {
            result.add("<s>"); result.addAll(sentence); result.add("</s>")
        }
        result.add("<s>"); result.addAll(current)
        return result
    }
}

/** UI requests are coalesced; recurrence, tokenization and top-300 selection stay on the worker.
 * Readers hold lock only while scoring. Publication swaps two reusable buffers under that lock,
 * so a worker can never overwrite probabilities still in use by a suggestion/swipe lookup.
 */
internal class EnglishContext(
    private val words: List<String>,
    private val load: () -> NextWordModel?,
    private val asynchronous: Boolean,
    private val onReady: () -> Unit,
    private val execute: (() -> Unit) -> Unit = { EnglishModel.worker.execute(it) },
) {
    internal class Scores(val model: NextWordModel, val ids: IntArray, val oovMass: Double) {
        val probabilities = FloatArray(model.vocabularySize)
        val top = IntArray(300.coerceAtMost(model.vocabularySize - 5))
        var version = 0L
        var previous2: String? = null
        var lastWord: String? = null
        private val currentState = model.initialState()
        private val parentState = model.initialState()
        private val probe = model.initialState()
        private val probeProbabilities = FloatArray(model.vocabularySize)
        private var probeKey: String? = null
        internal fun capture(current: NextWordModel.State, parent: NextWordModel.State, last: String?) {
            model.copyState(current, currentState); model.copyState(parent, parentState)
            lastWord = last; probeKey = null
        }
        private fun branch(first: String?) {
            val key = first ?: ""
            if (probeKey == key) return
            model.copyState(if (first == null) parentState else currentState, probe)
            if (first != null) model.step(probe, model.normalizedIdOf(first))
            model.logProbs(probe, probeProbabilities)
            probeKey = key
        }
        fun following(first: String, word: String, rank: Int?): Double {
            branch(first); return probability(word, rank, probeProbabilities)
        }
        fun preceding(word: String, rank: Int?): Double {
            branch(null); return probability(word, rank, probeProbabilities)
        }

        fun probability(word: String, rank: Int?): Double = probability(word, rank, probabilities)
        private fun probability(word: String, rank: Int?, values: FloatArray): Double {
            val id = rank?.let { ids[it] } ?: model.normalizedIdOf(word)
            return if (id != 2) values[id].toDouble()
            // <unk> also covers words absent from the keyboard dictionary. Reserve their mass.
            else values[2] + kotlin.math.ln(OOV_DICTIONARY_SHARE * (1.0 / ((rank ?: 5000) + 10)) / oovMass)
        }
    }

    private val lock = Any()
    private var requested: String? = null
    private var generation = 0L
    private var epoch = 0L
    private var workerEpoch = -1L
    private var failed = false
    private var running = false
    private var published: Scores? = null
    // Everything below belongs exclusively to the serial worker (or synchronous JVM caller), except that sentence
    // re-decoding reads the loaded model and vocabulary maps (both then fixed) from the keyboard thread.
    @Volatile private var model: NextWordModel? = null
    private var state: NextWordModel.State? = null
    private var parentState: NextWordModel.State? = null
    private var tokens: List<String> = emptyList()
    @Volatile private var front: Scores? = null
    private var back: Scores? = null
    private var computed: Scores? = null
    internal var steps = 0; private set
    internal var softmaxes = 0; private set
    internal var resets = 0; private set

    fun reset() = synchronized(lock) {
        requested = null
        generation++
        epoch++
        published = null
    }

    fun update(before: CharSequence, prefixLength: Int) {
        val end = (before.length - prefixLength).coerceAtLeast(0)
        val schedule = synchronized(lock) {
            if (failed) return
            val old = requested
            // No substring, tokenizer, token list or model array allocation while typing a word.
            if (old != null && matches(old, before, end)) return
            requested = before.subSequence(0, end).toString()
            generation++
            published = null // A different context must never borrow a stale distribution.
            if (running) false else { running = true; true }
        }
        if (schedule) {
            if (asynchronous) execute { drain() } else drain()
        }
    }

    fun <T> withScores(read: (Scores?) -> T): T = synchronized(lock) { read(published) }

    /** One way a word of the sentence could read, with what the taps and dictionary say about it (log scale). */
    class Option(val word: String, val rank: Int?, val bonus: Double)
    class Decoded(val picks: IntArray, val score: Double, val original: Double)

    /**
     * Re-decides a finished sentence: [options] lists each word's readings (index 0 is what's on screen). A small
     * beam steps the model through them after the text [before], ending with the end-of-sentence probability, and
     * returns the best path next to the score of the path on screen. Runs on the caller's thread with its own states
     * (the weights are read-only), so it never disturbs the suggestion worker. Null until the model has loaded.
     */
    fun decodeSentence(before: String, options: List<List<Option>>, width: Int): Decoded? {
        val m = model ?: return null
        val vocabulary = front ?: return null
        val start = m.initialState()
        for (token in EnglishTokens.context(before)) m.step(start, m.idOf(token))
        val probs = FloatArray(m.vocabularySize)
        fun logProb(o: Option): Double {
            val id = m.idOf(o.word)
            return if (id != 2) probs[id].toDouble()
            else probs[2] + kotlin.math.ln(OOV_DICTIONARY_SHARE * (1.0 / ((o.rank ?: 5000) + 10)) / vocabulary.oovMass)
        }
        class Path(val state: NextWordModel.State, val picks: IntArray, val score: Double)
        fun run(choices: List<List<Option>>, keep: Int): Path {
            var beam = listOf(Path(start, IntArray(0), 0.0))
            for (options in choices) {
                val next = ArrayList<Triple<Path, Int, Double>>()
                for (path in beam) {
                    m.logProbs(path.state, probs)
                    options.forEachIndexed { k, o -> next += Triple(path, k, path.score + logProb(o) + o.bonus) }
                }
                beam = next.sortedByDescending { it.third }.take(keep).map { (path, k, score) ->
                    val state = m.initialState().also { m.copyState(path.state, it); m.step(it, m.idOf(options[k].word)) }
                    Path(state, path.picks + k, score)
                }
            }
            // Finish each path with "the sentence ends here".
            return beam.map { path -> m.logProbs(path.state, probs); Path(path.state, path.picks, path.score + probs[1]) }.maxBy { it.score }
        }
        val original = run(options.map { listOf(it.first()) }, 1)
        val best = run(options, width)
        return Decoded(best.picks, best.score, original.score)
    }

    private fun matches(old: String, before: CharSequence, end: Int): Boolean {
        if (old.length != end) return false
        var i = 0
        while (i < end) { if (old[i] != before[i]) return false; i++ }
        return true
    }

    private fun drain() {
        if (model == null) {
            model = load()
            model?.let { m ->
                state = m.initialState(); parentState = m.initialState()
                val ids = IntArray(words.size) { m.normalizedIdOf(words[it]) }
                var mass = 0.0
                for (i in ids.indices) if (ids[i] == 2) mass += 1.0 / (i + 10)
                front = Scores(m, ids, mass.coerceAtLeast(1e-12))
                back = Scores(m, ids, mass.coerceAtLeast(1e-12))
            }
        }
        val m = model
        if (m == null) { synchronized(lock) { running = false; failed = true }; return }
        while (true) {
            val (text, version, resetEpoch) = synchronized(lock) { Triple(requested, generation, epoch) }
            val next = EnglishTokens.context(text.orEmpty())
            val unchanged = workerEpoch == resetEpoch && next == tokens && computed != null
            var start = tokens.size
            if (workerEpoch != resetEpoch || start > next.size || tokens.indices.any { tokens[it] != next[it] }) {
                state!!.reset(); resets++; start = 0
            }
            workerEpoch = resetEpoch
            for (i in start until next.size) {
                if (i == next.lastIndex) m.copyState(state!!, parentState!!)
                m.step(state!!, m.idOf(next[i])); steps++
            }
            tokens = next
            val output = if (unchanged) computed!! else back!!
            output.version = version
            output.previous2 = next.drop(next.indexOfLast { it == "<s>" } + 1).filter { !it.startsWith("<") }.dropLast(1).lastOrNull()
            if (!unchanged) {
                output.capture(state!!, parentState!!, next.lastOrNull()?.takeUnless { it.startsWith("<") })
                m.logProbs(state!!, output.probabilities); softmaxes++
                fillTop(output)
                computed = output
            }
            val done = synchronized(lock) {
                if (version != generation) false else {
                    if (front !== output) { val spare = front; front = output; back = spare }
                    published = if (text == null) null else output
                    running = false
                    true
                }
            }
            if (done) { if (text != null) onReady(); return }
        }
    }

    private fun fillTop(output: Scores) {
        val m = output.model
        // Bounded insertion avoids sorting/boxing the full vocabulary at every boundary.
        output.top.fill(-1)
        for (id in 5 until m.vocabularySize) {
            var at = output.top.size - 1
            val last = output.top[at]
            if (last >= 0 && output.probabilities[id] <= output.probabilities[last]) continue
            while (at > 0) {
                val previous = output.top[at - 1]
                if (previous >= 0 && output.probabilities[previous] >= output.probabilities[id]) break
                output.top[at] = previous; at--
            }
            output.top[at] = id
        }
    }

    internal companion object {
        const val OOV_DICTIONARY_SHARE = 0.1
    }
}
