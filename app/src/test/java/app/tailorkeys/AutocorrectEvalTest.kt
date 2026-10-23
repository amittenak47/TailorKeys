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
        val kinds = listOf("1 wrong key, 4 letters", "2 wrong keys, 4 letters", "2 wrong keys, 5+ letters", "skipped letter", "skipped + 1 wrong key")
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
            println("TK_AC_EVAL " + without.getValue(kind).line("no context:   $kind"))
        }
        times.sort()
        println("TK_AC_EVAL correction time: median %.2f ms, p95 %.2f ms over ${times.size} calls (desktop JVM)".format(times[times.size / 2] / 1e6, times[times.size * 95 / 100] / 1e6))
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
