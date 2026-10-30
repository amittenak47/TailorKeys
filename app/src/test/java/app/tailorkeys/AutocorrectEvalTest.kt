package app.tailorkeys

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * TK_AC_EVAL=1: types common words on the split layout with simulated finger error and reports what Aggressive
 * autocorrect and the suggestion strip do with them. Report only; the asserting cases live in TypingCoreTest.
 * TK_AC_SEED picks another word sample, to check a change on words it wasn't tuned on.
 */
class AutocorrectEvalTest {
    private val assets = File("src/main/assets")
    private val centres = SplitTaps.centres
    private val letters = centres.keys.toList()

    private class Typed(val text: String, val taps: ComposingTaps, val points: List<Pair<Float, Float>>)
    private class Case(val kind: String, val word: String, val typed: Typed)
    private class Tally { var fixed = 0; var wrong = 0; var none = 0; var strip = 0; var n = 0; var early = 0; var prefixes = 0
        fun line(name: String) = "%-34s n=%4d  fixed %5.1f%%  wrong %4.1f%%  untouched %5.1f%%  in strip %5.1f%%  mid-word %5.1f%%".format(
            name, n, 100.0 * fixed / n, 100.0 * wrong / n, 100.0 * none / n, 100.0 * strip / n, 100.0 * early / maxOf(1, prefixes)) }

    private fun gauss(r: Random): Float { val u = 1 - r.nextDouble(); return (sqrt(-2 * ln(u)) * cos(2 * Math.PI * r.nextDouble())).toFloat() }
    private fun nearest(x: Float, y: Float) = letters.minBy { val (cx, cy) = centres.getValue(it); (x - cx) * (x - cx) + (y - cy) * (y - cy) }

    /** One tap per point; the typed letter is the key the point landed on, as on the device. */
    private fun type(points: List<Pair<Float, Float>>, capital: Boolean = false): Typed {
        val taps = ComposingTaps(); var text = ""
        for ((x, y) in points) { val ch = nearest(x, y).let { if (capital && text.isEmpty()) it.uppercaseChar() else it }
            taps.append(text, ch.toString(), SplitTaps.distances(x, y)); text += ch }
        return Typed(text, taps, points)
    }
    private fun centre(ch: Char) = centres.getValue(ch)
    private fun noisy(word: String, sigma: Float, r: Random) = word.map { val (x, y) = centre(it); x + sigma * gauss(r) to y + sigma * gauss(r) }

    private fun cases(words: List<String>, r: Random): Pair<List<Case>, List<Typed>> {
        val cases = ArrayList<Case>(); val clean = ArrayList<Typed>()
        fun add(kind: String, word: String, typed: Typed) { if (!typed.text.equals(word, true)) cases += Case(kind, word, typed) }
        for (word in words) {
            // Sloppy but honest taps: count how many keys the finger actually missed.
            repeat(3) {
                val typed = type(noisy(word, .42f, r))
                if (typed.text == word) clean += typed else {
                    val slips = word.indices.count { i -> typed.text.getOrNull(i) != word[i] }
                    add("noise: ${minOf(slips, 3)}${if (slips >= 3) "+" else ""} wrong keys, ${if (word.length == 4) "4 letters" else "5+ letters"}", word, typed)
                }
            }
            // Sentence start: the same sloppy taps, auto-capitalized.
            add("noise, capitalized", word, type(noisy(word, .42f, r), capital = true))
            // Alignment errors: a skipped key, or an extra tap on a neighbour, with otherwise tidy taps.
            val i = 1 + r.nextInt(word.length - 2)
            add("skipped letter", word, type(noisy(word.removeRange(i, i + 1), .15f, r)))
            val (nx, ny) = centre(word[i])
            add("extra neighbour tap", word, type(noisy(word, .15f, r).toMutableList().apply { add(i, nx + .9f to ny + .1f) }))
            add("skipped + 1 wrong key", word, type(noisy(word.removeRange(i, i + 1), .15f, r).toMutableList().also { p ->
                val j = if (i + 1 < p.size) i + 1 else 0; p[j] = p[j].first + .85f to p[j].second }))
            // Whole hand drifted most of a key sideways: every tap lands near the neighbour's edge.
            add("hand drift (0.6 key)", word, type(word.map { val (x, y) = centre(it); x + .6f to y }))
        }
        return cases to clean
    }

    /** Taps for [word] with exactly [slips] wrong keys, or null if the noise wouldn't produce that. */
    private fun slipped(word: String, slips: Int, r: Random): Typed? {
        repeat(60) {
            val typed = type(noisy(word, .42f, r))
            if (typed.text.length == word.length && word.indices.count { typed.text[it] != word[it] } == slips) return typed
        }
        return null
    }

    /**
     * TK_AC_EVAL=1 TK_AC_CONTEXT=1: the same finger errors on words inside held-out conversational sentences
     * (tk-lm-data eval splits), corrected the way the keyboard does it: with the sentence so far, and without it.
     */
    @Test fun contextReport() {
        assumeTrue(System.getenv("TK_AC_EVAL") == "1" && System.getenv("TK_AC_CONTEXT") == "1")
        val data = File(System.getenv("TK_LM_DATA") ?: "../../tk-lm-data")
        val predictor = Predictor({ File(assets, it).inputStream() }, Files.createTempDirectory("tk-ac-context").toFile()).apply { setLayout(SplitTaps.grid) }
        val r = Random(System.getenv("TK_AC_SEED")?.toInt() ?: 11)
        val tokens = Regex(""""t":\[([^\]]*)]""")
        val sentences = listOf("soda", "tatoeba", "common_voice").flatMap { source ->
            java.util.zip.GZIPInputStream(File(data, "processed/$source.eval.jsonl.gz").inputStream()).bufferedReader().useLines { lines ->
                lines.mapNotNull { line -> tokens.find(line)?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('"') } }
                    .filter { it.size in 4..20 && it.none { t -> t.startsWith("<") } }.take(400).toList()
            }
        }
        val kinds = listOf("1 wrong key, 4 letters", "2 wrong keys, 4 letters", "2 wrong keys, 5+ letters", "skipped letter", "skipped + 1 wrong key",
            "reach for space (extra n/b)", "space landed on n/b", "real word ending in n/m/b/v")
        val withContext = kinds.associateWith { Tally() }; val without = kinds.associateWith { Tally() }
        val times = ArrayList<Long>()
        for (sentence in sentences) {
            val index = sentence.indices.filter { i -> i > 0 && sentence[i].length in 4..10 && sentence[i].all { it in centres } }.randomOrNull(r) ?: continue
            val word = sentence[index]; val before = sentence.take(index).joinToString(" ") + " "
            val cases = listOfNotNull(
                (if (word.length == 4) "1 wrong key, 4 letters" else null)?.let { k -> slipped(word, 1, r)?.let { k to it } },
                (if (word.length == 4) "2 wrong keys, 4 letters" else "2 wrong keys, 5+ letters").let { k -> slipped(word, 2, r)?.let { k to it } },
                (1 + r.nextInt(word.length - 2)).let { i -> "skipped letter" to type(noisy(word.removeRange(i, i + 1), .15f, r)) },
                (1 + r.nextInt(word.length - 2)).let { i -> "skipped + 1 wrong key" to type(noisy(word.removeRange(i, i + 1), .15f, r).toMutableList().also { p ->
                    val j = if (i + 1 < p.size) i + 1 else 0; p[j] = p[j].first + .85f to p[j].second }) })
            // The thumb catches the letter above the space bar on its way down: just below that letter's centre.
            val bar = if (r.nextBoolean()) 'n' else 'b'
            val (bx, by) = centre(bar)
            val reach = noisy(word, .15f, r) + (bx + .15f * gauss(r) to by + .3f)
            val extraCases = mutableListOf("reach for space (extra n/b)" to type(reach))
            val next = sentence.getOrNull(index + 1)?.takeIf { it.length >= 2 && it.all { c -> c in centres } }
            if (next != null) extraCases += "space landed on n/b" to type(reach + noisy(next, .15f, r))
            for ((kind, typed) in extraCases) {
                val tally = withContext.getValue(kind)
                val expected = if (kind.startsWith("space landed")) "$word $next" else word
                val fix = predictor.correction(before + typed.text, AutocorrectMode.AGGRESSIVE, typed.taps, allowJoin = false)?.replacement?.lowercase()
                tally.n++
                when (fix) { expected -> tally.fixed++; null -> tally.none++; else -> tally.wrong++ }
            }
            // Words that really end in a letter beside the bar must survive careful typing.
            if (word.last() in "nmbv") {
                val tally = withContext.getValue("real word ending in n/m/b/v")
                val fix = predictor.correction(before + word, AutocorrectMode.AGGRESSIVE, type(noisy(word, .15f, r)).taps, allowJoin = false)
                tally.n++
                if (fix == null) tally.fixed++ else tally.wrong++
            }
            for ((kind, typed) in cases) {
                if (typed.text == word) continue
                for ((tally, text) in listOf(withContext.getValue(kind) to before + typed.text, without.getValue(kind) to typed.text)) {
                    val start = System.nanoTime()
                    val fix = predictor.correction(text, AutocorrectMode.AGGRESSIVE, typed.taps, allowJoin = false)?.replacement?.lowercase()
                    times += System.nanoTime() - start
                    tally.n++
                    when (fix) { word -> tally.fixed++; null -> tally.none++; else -> tally.wrong++ }
                    if (tally === withContext[kind] && fix != word && kind.startsWith("skipped") && tally.n % 7 == 0)
                        println("TK_AC_EX $kind: ...${before.takeLast(25)}[${typed.text}] -> ${fix ?: "-"} ($word)${if (predictor.hasWord(typed.text)) " typed-is-a-word" else ""}")
                }
            }
        }
        for (kind in kinds) {
            println("TK_AC_EVAL " + withContext.getValue(kind).line("with context: $kind"))
            if (without.getValue(kind).n > 0) println("TK_AC_EVAL " + without.getValue(kind).line("no context:   $kind"))
        }
        times.sort()
        println("TK_AC_EVAL correction time: median %.2f ms, p95 %.2f ms over ${times.size} calls (desktop JVM)".format(times[times.size / 2] / 1e6, times[times.size * 95 / 100] / 1e6))
    }

    /**
     * TK_AC_EVAL=1 TK_AC_SENTENCES=1: held-out sentences typed word by word as the keyboard does it: each word corrected
     * at its space, looked back on once the next word is finished, and the sentence re-decided at its end. It reports
     * by what was written:
     *  - intended words (every tap on the right key): any change of their letters is wrong, by the step that made it;
     *  - typos (a tap landed on another key): fixed, left as typed, or turned into a third word;
     *  - the keyboard's own corrections at the space bar: right or wrong, and whether a later step put them right or
     *    broke them;
     *  - the pronoun I (i, i'm, i'll, i've, i'd): capitalized or not, at a sentence start and mid-sentence.
     * Letters are compared ignoring case, except for I. Real-word slips are chosen against words.txt alone, so every
     * dictionary faces the same cases. TK_AC_DUMP=1 prints every case behind the totals.
     */
    @Test fun sentenceReport() {
        assumeTrue(System.getenv("TK_AC_EVAL") == "1" && System.getenv("TK_AC_SENTENCES") == "1")
        val data = File(System.getenv("TK_LM_DATA") ?: "../../tk-lm-data")
        val predictor = Predictor({ File(assets, it).inputStream() }, Files.createTempDirectory("tk-ac-sentences").toFile()).apply { setLayout(SplitTaps.grid) }
        val realWords = DictionaryWords.read({ File(assets, it).inputStream() }, "en").toHashSet()
        val r = Random(System.getenv("TK_AC_SEED")?.toInt() ?: 11)
        val tokens = Regex(""""t":\[([^\]]*)]""")
        val sentences = listOf("soda", "tatoeba", "common_voice").flatMap { source ->
            java.util.zip.GZIPInputStream(File(data, "processed/$source.eval.jsonl.gz").inputStream()).bufferedReader().useLines { lines ->
                lines.mapNotNull { line -> tokens.find(line)?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('"') } }
                    .filter { it.size in 4..16 && it.all { t -> t.isNotEmpty() && t.all { c -> c in centres } } }.take(250).toList()
            }
        }
        val dump = System.getenv("TK_AC_DUMP") == "1"
        val pronounI = setOf("i", "i'm", "i'll", "i've", "i'd")
        class Outcome(val final: List<String>, val atSpace: List<String>, val steps: Map<Int, String>, val traces: Map<Int, String>)
        fun typeSentence(typed: List<Typed>): Outcome {
            val words = ArrayList<String>(); val atSpace = ArrayList<String>(); val steps = HashMap<Int, String>()
            val traces = HashMap<Int, String>()
            fun traceTo(i: Int) { predictor.trace = if (dump) { line -> traces[i] = listOfNotNull(traces[i], line).joinToString(" || ") } else null }
            for (i in typed.indices) {
                val before = words.joinToString("") { "$it " }
                traceTo(i)
                val word = predictor.correction(before + typed[i].text, AutocorrectMode.AGGRESSIVE, typed[i].taps, allowJoin = false)?.replacement ?: typed[i].text
                traceTo(i - 1)
                if (i > 0) predictor.lookBack(before, typed[i - 1].text, typed[i - 1].taps, word, AutocorrectMode.AGGRESSIVE)?.let {
                    words[i - 1] = it; steps[i - 1] = "look-back"
                }
                words += word; atSpace += word
            }
            predictor.trace = null
            val redone = predictor.redecode("", typed.map { it.text }, typed.map { it.taps }, words, AutocorrectMode.AGGRESSIVE)
            if (redone == null || redone.size != words.size) return Outcome(words, atSpace, steps, traces)
            redone.indices.filter { redone[it] != words[it] }.forEach { steps[it] = "sentence end" }
            return Outcome(redone, atSpace, steps, traces)
        }
        fun same(a: String, b: String) = a.equals(b, ignoreCase = true)

        val intendedChanged = sortedMapOf("space" to 0, "look-back" to 0, "sentence end" to 0)
        var intended = 0; var changedThenRestored = 0
        val typos = sortedMapOf<String, IntArray>() // kind -> fixed, left, wrong
        var own = 0; var ownRight = 0; var laterPutRight = 0; var laterBroke = 0
        val iCapital = intArrayOf(0, 0, 0, 0) // sentence start: capitalized, total; mid-sentence: capitalized, total
        fun tally(kind: String, sentence: List<String>, typed: List<Typed>, out: Outcome, k: Int) {
            val meant = sentence[k]; val text = typed[k].text; val space = out.atSpace[k]; val final = out.final[k]
            val step = out.steps[k] ?: if (!same(space, text)) "space" else "-"
            if (text == meant) {
                intended++
                if (!same(final, meant)) intendedChanged[step] = intendedChanged.getValue(step) + 1
                else if (!same(space, text)) changedThenRestored++
            } else {
                val row = typos.getOrPut(if (text in realWords) "$kind, real-word slip" else "$kind, non-word slip") { IntArray(3) }
                row[if (same(final, meant)) 0 else if (same(final, text)) 1 else 2]++
            }
            if (!same(space, text)) {
                own++
                if (same(space, meant)) { ownRight++; if (!same(final, meant)) laterBroke++ } else if (same(final, meant)) laterPutRight++
            }
            if (meant in pronounI) { val at = if (k == 0) 0 else 2; iCapital[at + 1]++; if (final.firstOrNull() == 'I') iCapital[at]++ }
            if (dump && (!same(final, meant) || !same(space, text) || text != meant || meant in pronounI && final.firstOrNull() != 'I'))
                println("TK_AC_ALL $kind\t${sentence.joinToString(" ")}\t$meant\t$text\t$space\t$final\t$step\t${out.traces[k] ?: ""}")
        }
        var slipSentences = 0
        for (sentence in sentences) {
            // Careful typing: every tap near its own key.
            val careful = sentence.map { type(noisy(it, .12f, r)) }
            val out = typeSentence(careful)
            for (k in sentence.indices) tally("careful", sentence, careful, out, k)
            // One word slips onto another real word, the taps leaning back toward the meant keys.
            val i = sentence.indices.filter { it < sentence.lastIndex && sentence[it].length in 3..10 }.randomOrNull(r) ?: continue
            val meant = sentence[i]
            val slip = (0 until 200).firstNotNullOfOrNull {
                val points = noisy(meant, .42f, r)
                val text = points.joinToString("") { (x, y) -> nearest(x, y).toString() }
                if (text != meant && text in realWords) points.mapIndexed { k, (x, y) ->
                    val (cx, cy) = centre(meant[k]); val (tx, ty) = centre(text[k])
                    // Land 45% of the way from the typed key back toward the meant one.
                    if (text[k] == meant[k]) x to y else tx + .45f * (cx - tx) to ty + .45f * (cy - ty)
                } else null
            } ?: continue
            val typed = sentence.mapIndexed { k, w -> if (k == i) type(slip) else type(noisy(w, .12f, r)) }
            slipSentences++
            tally("slipped", sentence, typed, typeSentence(typed), i)
        }
        println("TK_AC_EVAL intended words changed: ${intendedChanged.values.sum()} of $intended (" +
            intendedChanged.entries.joinToString { "${it.key} ${it.value}" } + "); changed at the space bar and later put back: $changedThenRestored")
        for ((kind, row) in typos) println("TK_AC_EVAL typos, %-28s n=%4d  fixed %3d  left %3d  wrong word %3d".format(kind, row.sum(), row[0], row[1], row[2]))
        println("TK_AC_EVAL keyboard's own corrections at the space bar: $own, right $ownRight, wrong ${own - ownRight}; " +
            "later put right $laterPutRight, later broken $laterBroke")
        println("TK_AC_EVAL pronoun I capitalized: sentence start ${iCapital[0]} of ${iCapital[1]}, mid-sentence ${iCapital[2]} of ${iCapital[3]}")
        println("TK_AC_EVAL sentences: ${sentences.size} careful, $slipSentences with one real-word slip")
    }

    /** A held-out sentence as typed: its words, the taps for each, and which word slipped onto another real word. */
    private class TypedSentence(val words: List<String>, val typed: List<Typed>, val slip: Int = -1)
    /** One typo inside a sentence, corrected at its space with the sentence so far. */
    private class TypoCase(val kind: String, val before: String, val typed: Typed, val expected: String)
    private class SentenceCases(val careful: List<TypedSentence>, val slipped: List<TypedSentence>,
                                val everyday: List<TypedSentence>, val typos: List<TypoCase>)

    /** Up to [size] held-out sentences from each source, every letter on the layout. */
    private fun loadSentences(size: Int): List<List<String>> {
        val data = File(System.getenv("TK_LM_DATA") ?: "../../tk-lm-data")
        val tokens = Regex(""""t":\[([^\]]*)]""")
        return listOf("soda", "tatoeba", "common_voice").flatMap { source ->
            java.util.zip.GZIPInputStream(File(data, "processed/$source.eval.jsonl.gz").inputStream()).bufferedReader().useLines { lines ->
                lines.mapNotNull { line -> tokens.find(line)?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('"') } }
                    .filter { it.size in 4..16 && it.all { t -> t.isNotEmpty() && t.all { c -> c in centres } } }.take(size).toList()
            }
        }
    }

    /**
     * Every case built once, from the random stream alone, so any predictor or setting sees identical taps:
     *  - careful: every tap near its own key;
     *  - slipped: the same with one word slipped onto another real word of words.txt, its taps leaning back;
     *  - typos: one typo of each kind inside a sentence (wrong keys, skipped key, extra tap, reach for space...);
     *  - everyday: every word typed with moderate scatter, now and then a skipped or an extra key, so typos and
     *    correctly typed words meet in the same sentences.
     */
    private fun sentenceCases(sentences: List<List<String>>, realWords: Set<String>, r: Random): SentenceCases {
        val careful = ArrayList<TypedSentence>(); val slipped = ArrayList<TypedSentence>()
        for (sentence in sentences) {
            careful += TypedSentence(sentence, sentence.map { type(noisy(it, .12f, r)) })
            val i = sentence.indices.filter { it < sentence.lastIndex && sentence[it].length in 3..10 }.randomOrNull(r) ?: continue
            val meant = sentence[i]
            val slip = (0 until 200).firstNotNullOfOrNull {
                val points = noisy(meant, .42f, r)
                val text = points.joinToString("") { (x, y) -> nearest(x, y).toString() }
                if (text != meant && text in realWords) points.mapIndexed { k, (x, y) ->
                    val (cx, cy) = centre(meant[k]); val (tx, ty) = centre(text[k])
                    // Land 45% of the way from the typed key back toward the meant one.
                    if (text[k] == meant[k]) x to y else tx + .45f * (cx - tx) to ty + .45f * (cy - ty)
                } else null
            } ?: continue
            slipped += TypedSentence(sentence, sentence.mapIndexed { k, w -> if (k == i) type(slip) else type(noisy(w, .12f, r)) }, i)
        }
        val typos = ArrayList<TypoCase>()
        for (sentence in sentences) {
            val index = sentence.indices.filter { i -> i > 0 && sentence[i].length in 4..10 }.randomOrNull(r) ?: continue
            val word = sentence[index]; val before = sentence.take(index).joinToString(" ") + " "
            fun add(kind: String, typed: Typed?, expected: String = word) { if (typed != null && typed.text != expected) typos += TypoCase(kind, before, typed, expected) }
            if (word.length == 4) add("1 wrong key, 4 letters", slipped(word, 1, r))
            add(if (word.length == 4) "2 wrong keys, 4 letters" else "2 wrong keys, 5+ letters", slipped(word, 2, r))
            val i = 1 + r.nextInt(word.length - 2)
            add("skipped letter", type(noisy(word.removeRange(i, i + 1), .15f, r)))
            add("skipped + 1 wrong key", type(noisy(word.removeRange(i, i + 1), .15f, r).toMutableList().also { p ->
                val j = if (i + 1 < p.size) i + 1 else 0; p[j] = p[j].first + .85f to p[j].second }))
            val (nx, ny) = centre(word[i])
            add("extra neighbour tap", type(noisy(word, .15f, r).toMutableList().apply { add(i, nx + .9f to ny + .1f) }))
            val bar = if (r.nextBoolean()) 'n' else 'b'
            val (bx, by) = centre(bar)
            val reach = noisy(word, .15f, r) + (bx + .15f * gauss(r) to by + .3f)
            add("reach for space", type(reach))
            sentence.getOrNull(index + 1)?.takeIf { it.length >= 2 }?.let { next -> add("space landed on n/b", type(reach + noisy(next, .15f, r)), "$word $next") }
        }
        val everyday = sentences.map { sentence ->
            TypedSentence(sentence, sentence.map { w ->
                val roll = r.nextDouble()
                val i = if (w.length >= 4) 1 + r.nextInt(w.length - 2) else -1
                when {
                    i > 0 && roll < .03 -> type(noisy(w.removeRange(i, i + 1), .22f, r))
                    i > 0 && roll < .05 -> { val (nx, ny) = centre(w[i]); type(noisy(w, .22f, r).toMutableList().apply { add(i, nx + .9f to ny + .1f) }) }
                    else -> type(noisy(w, .22f, r))
                }
            })
        }
        return SentenceCases(careful, slipped, everyday, typos)
    }

    /** Types a sentence as the keyboard does: each word at its space, look-back one word later, re-decoding at the end. */
    private fun typeSentence(predictor: Predictor, typed: List<Typed>): List<String> {
        val words = ArrayList<String>()
        for (i in typed.indices) {
            val before = words.joinToString("") { "$it " }
            val word = predictor.correction(before + typed[i].text, AutocorrectMode.AGGRESSIVE, typed[i].taps, allowJoin = false)?.replacement ?: typed[i].text
            if (i > 0) predictor.lookBack(before, typed[i - 1].text, typed[i - 1].taps, word, AutocorrectMode.AGGRESSIVE)?.let { words[i - 1] = it }
            words += word
        }
        val redone = predictor.redecode("", typed.map { it.text }, typed.map { it.taps }, words, AutocorrectMode.AGGRESSIVE)
        return if (redone != null && redone.size == words.size) redone else words
    }

    /** What [measure] counts; typo kinds map to n, fixed, wrong. Letters compare ignoring case, except for I. */
    private class Measured(val values: LinkedHashMap<String, Int>, val kinds: Map<String, IntArray>)

    private fun measure(predictor: Predictor, cases: SentenceCases): Measured {
        fun same(a: String, b: String) = a.equals(b, ignoreCase = true)
        val pronounI = setOf("i", "i'm", "i'll", "i've", "i'd")
        val v = LinkedHashMap<String, Int>()
        for (key in listOf("careful words", "careful words changed", "mid-sentence i", "mid-sentence i capitalized", "real-word slips",
                "real-word slips fixed", "real-word slips wrong word", "everyday words typed right", "everyday words typed right, changed",
                "everyday typos", "everyday typos fixed", "everyday typos wrong word")) v[key] = 0
        fun add(key: String, n: Int = 1) { v[key] = (v[key] ?: 0) + n }
        for (s in cases.careful) {
            val out = typeSentence(predictor, s.typed)
            for (k in s.words.indices) {
                if (s.typed[k].text == s.words[k]) { add("careful words"); if (!same(out[k], s.words[k])) add("careful words changed") }
                if (k > 0 && s.words[k] in pronounI) { add("mid-sentence i"); if (out[k].firstOrNull() == 'I') add("mid-sentence i capitalized") }
            }
        }
        for (s in cases.slipped) {
            val got = typeSentence(predictor, s.typed)[s.slip]
            add("real-word slips")
            if (same(got, s.words[s.slip])) add("real-word slips fixed") else if (!same(got, s.typed[s.slip].text)) add("real-word slips wrong word")
        }
        for (s in cases.everyday) {
            val out = typeSentence(predictor, s.typed)
            for (k in s.words.indices) {
                val text = s.typed[k].text; val meant = s.words[k]
                if (text == meant) { add("everyday words typed right"); if (!same(out[k], meant)) add("everyday words typed right, changed") }
                else { add("everyday typos"); if (same(out[k], meant)) add("everyday typos fixed") else if (!same(out[k], text)) add("everyday typos wrong word") }
            }
        }
        val kinds = cases.typos.groupBy { it.kind }.mapValues { (_, list) ->
            val row = intArrayOf(list.size, 0, 0)
            for (c in list) {
                val got = predictor.correction(c.before + c.typed.text, AutocorrectMode.AGGRESSIVE, c.typed.taps, allowJoin = false)?.replacement?.lowercase()
                if (got == c.expected) row[1]++ else if (got != null) row[2]++
            }
            row
        }
        return Measured(v, kinds)
    }

    /**
     * TK_AC_EVAL=1 TK_AC_SWEEP=1: the shared sentence cases scored under several [SlipWeights] and [Experiment]s, every
     * row on identical taps. TK_AC_SWEEP_SENTENCES sets the sample per source; TK_AC_SWEEP_GRID lists settings as
     * tap:skip:extra[:flags], flags G (frequency cutoffs off), S (cutoffs only at a sentence start), R (short-word
     * reach), T (re-decoding by taps).
     */
    @Test fun slipSweep() {
        assumeTrue(System.getenv("TK_AC_EVAL") == "1" && System.getenv("TK_AC_SWEEP") == "1")
        val predictor = Predictor({ File(assets, it).inputStream() }, Files.createTempDirectory("tk-ac-sweep").toFile()).apply { setLayout(SplitTaps.grid) }
        val realWords = DictionaryWords.read({ File(assets, it).inputStream() }, "en").toHashSet()
        val cases = sentenceCases(loadSentences(System.getenv("TK_AC_SWEEP_SENTENCES")?.toInt() ?: 130), realWords,
            Random(System.getenv("TK_AC_SEED")?.toInt() ?: 11))
        val grid = (System.getenv("TK_AC_SWEEP_GRID") ?: "0.4:4.8:4.8 0.8:4.8:4.8 1.6:4.8:4.8 1.6:4.8:4.8:R").split(' ').map { it.split(':') }
        for (setting in grid) {
            val (tap, skip, extra) = setting.take(3).map(String::toDouble)
            val flags = setting.getOrElse(3) { "" }
            predictor.slipWeights = SlipWeights(tap = tap, skip = skip, extra = extra)
            predictor.experiment = Experiment(rankGates = 'G' !in flags, gatesAtStartOnly = 'S' in flags, shortReach = 'R' in flags, redecodeByTaps = 'T' in flags)
            val m = measure(predictor, cases)
            println("TK_AC_EVAL %.1f %.1f %.1f %-4s | ".format(tap, skip, extra, flags.ifEmpty { "-" }) +
                m.values.entries.joinToString { "${it.key} ${it.value}" } + " | " +
                m.kinds.entries.joinToString(" | ") { (k, row) -> "%s %.0f/%.0f".format(k, 100.0 * row[1] / row[0], 100.0 * row[2] / row[0]) })
        }
        predictor.slipWeights = SlipWeights(); predictor.experiment = Experiment()
    }

    /**
     * TK_AC_GATE=1: fails when autocorrect gets worse than the baseline recorded in src/test/resources/autocorrect_gate.txt,
     * on the shared sentence cases (250 sentences per source). TK_AC_GATE_RECORD=1 writes the current numbers as the new
     * baseline instead. Counts of wrong changes may not rise; counts and rates of fixes may not fall by more than a
     * point. It also prints how far the keyboard is from the targets: careful words changed in single digits, at least
     * 94% of real-word slips fixed, and I capitalized mid-sentence every time.
     */
    @Test fun gate() {
        assumeTrue(System.getenv("TK_AC_GATE") == "1")
        val predictor = Predictor({ File(assets, it).inputStream() }, Files.createTempDirectory("tk-ac-gate").toFile()).apply { setLayout(SplitTaps.grid) }
        val realWords = DictionaryWords.read({ File(assets, it).inputStream() }, "en").toHashSet()
        val m = measure(predictor, sentenceCases(loadSentences(250), realWords, Random(11)))
        val now = LinkedHashMap<String, Double>()
        m.values.forEach { (k, v) -> now[k] = v.toDouble() }
        m.kinds.forEach { (k, row) -> now["$k fixed %"] = 100.0 * row[1] / row[0]; now["$k wrong %"] = 100.0 * row[2] / row[0] }
        now.forEach { (k, v) -> println("TK_AC_EVAL gate %-48s %.1f".format(k, v)) }
        val file = File("src/test/resources/autocorrect_gate.txt")
        if (System.getenv("TK_AC_GATE_RECORD") == "1") {
            file.writeText(now.entries.joinToString("") { "${it.key}=${"%.1f".format(java.util.Locale.ROOT, it.value)}\n" })
            return
        }
        val base = file.readLines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').toDouble() }
        // Wrong changes must not rise; fixes must not fall, allowing a point of rounding on percentages. Totals only
        // describe the sample.
        val totals = setOf("careful words", "mid-sentence i", "real-word slips", "everyday words typed right", "everyday typos")
        val worse = base.filterKeys { it !in totals }.mapNotNull { (key, was) ->
            val value = now[key] ?: return@mapNotNull "$key missing"
            val slack = if (key.endsWith("%")) 1.0 else 0.0
            val lowerIsBetter = "changed" in key || "wrong" in key
            if (if (lowerIsBetter) value > was + slack else value < was - slack) "$key $was -> $value" else null
        }
        val slips = (now["real-word slips fixed"] ?: 0.0) / (now["real-word slips"] ?: 1.0)
        println("TK_AC_EVAL gate targets: careful words changed ${now["careful words changed"]} (target under 10); " +
            "real-word slips fixed %.0f%% (target 94%%); mid-sentence I %.0f of %.0f".format(100 * slips,
                now["mid-sentence i capitalized"] ?: 0.0, now["mid-sentence i"] ?: 0.0))
        org.junit.Assert.assertTrue("worse than the recorded baseline: $worse", worse.isEmpty())
    }

    /**
     * TK_AC_EXPORT=file.jsonl: writes the gate's cases (same sentences, same taps) so the autocorrect model in
     * tools/lm can be scored on exactly what the keyboard is gated on. Points are in key cells on the split layout.
     */
    @Test fun exportGateCases() {
        val path = System.getenv("TK_AC_EXPORT") ?: return
        val realWords = DictionaryWords.read({ File(assets, it).inputStream() }, "en").toHashSet()
        val cases = sentenceCases(loadSentences(250), realWords, Random(11))
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun points(t: Typed) = t.points.joinToString(",", "[", "]") { (x, y) -> "[%.4f,%.4f]".format(java.util.Locale.ROOT, x, y) }
        fun sentence(set: String, s: TypedSentence) = "{\"set\":${q(set)},\"words\":${s.words.joinToString(",", "[", "]") { q(it) }}," +
            "\"typed\":${s.typed.joinToString(",", "[", "]") { q(it.text) }},\"points\":${s.typed.joinToString(",", "[", "]") { points(it) }},\"slip\":${s.slip}}"
        File(path).bufferedWriter().use { out ->
            cases.careful.forEach { out.write(sentence("careful", it) + "\n") }
            cases.slipped.forEach { out.write(sentence("slipped", it) + "\n") }
            cases.everyday.forEach { out.write(sentence("everyday", it) + "\n") }
            cases.typos.forEach { c -> out.write("{\"set\":\"typo\",\"kind\":${q(c.kind)},\"before\":${q(c.before)},\"typed\":${q(c.typed.text)}," +
                "\"points\":${points(c.typed)},\"expected\":${q(c.expected)}}\n") }
        }
    }

    @Test fun report() {
        assumeTrue(System.getenv("TK_AC_EVAL") == "1")
        val predictor = Predictor({ File(assets, it).inputStream() }, Files.createTempDirectory("tk-ac-eval").toFile(),
            enableModel = System.getenv("TK_AC_EVAL_MODEL") != "0").apply { setLayout(SplitTaps.grid) }
        val r = Random(System.getenv("TK_AC_SEED")?.toInt() ?: 7)
        val words = DictionaryWords.read({ File(assets, it).inputStream() }, "en", 3000, unique = true)
            .filter { w -> w.length in 4..10 && w.all { it in centres } }.shuffled(r).take(System.getenv("TK_AC_WORDS")?.toInt() ?: 500)
        val (cases, clean) = cases(words, r)
        // Real words the dictionary may not know (names, brands, slang), typed carefully: these must survive.
        val unknown = listOf("kaito", "anya", "reddit", "lyft", "zara", "tomas", "selin", "yeet", "brexit", "vape",
            "nikolai", "ottawa", "pixar", "ramen", "sushi", "matcha", "gonna", "wanna", "kinda", "lol", "tiktok",
            "netflix", "zoom", "emoji", "selfie", "vlog", "goku", "zelda", "mario", "luigi", "dior", "gucci", "nike",
            "ikea", "lego", "uber", "covid", "bruh", "nah", "yup").filter { w -> w.all { it in centres } }
            .map { w -> type(noisy(w, .12f, r)) }.filter { it.text.length > 1 }

        fun run(detail: Boolean): Triple<Double, Int, Int> {
            val tallies = LinkedHashMap<String, Tally>(); val examples = LinkedHashMap<String, MutableList<String>>()
            for (c in cases) {
                val t = tallies.getOrPut(c.kind) { Tally() }
                val fix = predictor.autocorrect(c.typed.text, AutocorrectMode.AGGRESSIVE, taps = c.typed.taps)
                t.n++
                when (fix?.lowercase()) { c.word -> t.fixed++; null -> t.none++; else -> t.wrong++ }
                if (!detail) continue
                if (predictor.closest(c.typed.text, n = 8, taps = c.typed.taps).any { it.equals(c.word, true) }) t.strip++
                // Mid-word: after the first slip, with at least three letters typed, is the word already offered?
                val firstSlip = c.typed.text.indices.firstOrNull { !c.typed.text[it].equals(c.word.getOrNull(it) ?: ' ', true) } ?: 0
                for (k in maxOf(3, firstSlip + 1) until c.typed.text.length) {
                    val partial = type(c.typed.points.take(k), c.typed.text[0].isUpperCase())
                    t.prefixes++
                    if (predictor.closest(partial.text, n = 8, taps = partial.taps).any { it.equals(c.word, true) }) t.early++
                }
                val list = examples.getOrPut(c.kind) { ArrayList() }
                if (fix?.lowercase() != c.word && list.size < 8) list += "${c.typed.text}->${fix ?: "-"} (${c.word})"
            }
            val kept = unknown.count { predictor.autocorrect(it.text, AutocorrectMode.AGGRESSIVE, taps = it.taps) == null }
            if (detail && System.getenv("TK_AC_DUMP") == "1") for (u in unknown)
                println("TK_AC_ALL unknown	${u.text}	${predictor.autocorrect(u.text, AutocorrectMode.AGGRESSIVE, taps = u.taps) ?: "-"}	${predictor.hasWord(u.text)}")
            val changed = clean.count { predictor.autocorrect(it.text, AutocorrectMode.AGGRESSIVE, taps = it.taps) != null }
            // A wrong fix costs twice what a missed one does: you have to notice it and undo it.
            val score = tallies.values.map { 100.0 * (it.fixed - 2 * it.wrong) / it.n }.average()
            if (detail) {
                println("TK_AC_EVAL score %.1f; careful unknown words kept: $kept of ${unknown.size}; clean words changed: $changed of ${clean.size}".format(score))
                for ((name, t) in tallies.toSortedMap()) println("TK_AC_EVAL " + t.line(name))
                for ((name, list) in examples.toSortedMap()) println("TK_AC_EVAL   $name: ${list.joinToString(", ")}")
            }
            return Triple(score, kept, changed)
        }

        run(true)
    }
}
