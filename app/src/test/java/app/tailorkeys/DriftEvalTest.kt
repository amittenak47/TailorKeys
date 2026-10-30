package app.tailorkeys

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * TK_DRIFT=1: typists whose hands wander type held-out sentences on the split layout the way the keyboard does it
 * (smart tap areas, autocorrect at each space, look-back, re-decoding at the end), with and without TouchDrift,
 * on the same taps. Report only. TK_DRIFT_VARIANTS / TK_DRIFT_POPS pick rows; TK_DRIFT_TYPISTS, TK_DRIFT_SENTENCES
 * and TK_DRIFT_SEED set the sample.
 */
class DriftEvalTest {
    private val assets = File("src/main/assets")
    private val centres = SplitTaps.centres
    private val letters = centres.keys.toList()

    private fun gauss(r: Random): Float { val u = 1 - r.nextDouble(); return (sqrt(-2 * ln(u)) * cos(2 * Math.PI * r.nextDouble())).toFloat() }
    private fun d2(ch: Char, x: Float, y: Float) = centres.getValue(ch).let { (cx, cy) -> (x - cx) * (x - cx) + (y - cy) * (y - cy) }
    private fun nearest(x: Float, y: Float) = letters.minBy { d2(it, x, y) }
    /** The split layout's halves: columns 0-4 for the left hand, 6-10 for the right. */
    private fun half(x: Float) = if (x < 5.5f) 0 else 1

    /** [bias]: spread of each half's fixed habit; [wander]: per-word step of its slow drift; [scatter]: per tap. */
    private class Population(val name: String, val bias: Float, val wander: Float, val scatter: Float)

    private class Variant(val name: String, val track: Boolean = false, val shiftKeys: Boolean = false, val resize: Boolean = false)

    /** Each half's offset in key widths: a fixed habit, plus a wander that slowly returns toward it. */
    private inner class Hands(val r: Random, val pop: Population) {
        private val habit = Array(2) { floatArrayOf(pop.bias * gauss(r), pop.bias * gauss(r)) }
        private val spread = pop.wander / sqrt(1 - RHO * RHO)
        val now = Array(2) { FloatArray(2) }
        init { session() }
        /** A new sitting: the hands land somewhere new around their habit. */
        fun session() { for (h in 0..1) for (a in 0..1) now[h][a] = habit[h][a] + spread * gauss(r) }
        fun word() { for (h in 0..1) for (a in 0..1) now[h][a] = habit[h][a] + RHO * (now[h][a] - habit[h][a]) + pop.wander * gauss(r) }
        fun tap(ch: Char): Pair<Float, Float> {
            val (cx, cy) = centres.getValue(ch); val h = half(cx)
            return cx + now[h][0] + pop.scatter * gauss(r) to cy + now[h][1] + pop.scatter * gauss(r)
        }
    }

    /** TouchDrift, as the keyboard uses it: taps move back by their hand's drift, settled words teach it. */
    private inner class Drift {
        val real = TouchDrift()
        fun shift(x: Float, y: Float): Pair<Float, Float> { val h = half(x); return x - real.x(h) to y - real.y(h) }
        fun learn(points: List<Pair<Float, Float>>, word: String) {
            if (word.length != points.size) return
            for ((k, p) in points.withIndex()) {
                val (cx, cy) = centres[word[k]] ?: return
                real.learn(half(cx), p.first - cx, p.second - cy)
            }
        }
        fun error(hands: Hands) = (0..1).map { h -> sqrt((real.x(h) - hands.now[h][0]).let { it * it } + (real.y(h) - hands.now[h][1]).let { it * it }) }.average()
    }

    private class Typed(val text: String, val taps: ComposingTaps, val points: List<Pair<Float, Float>>)

    private class Tally {
        var keys = 0; var keysRight = 0; var words = 0; var right = 0
        var typos = 0; var fixed = 0; var wrong = 0; var clean = 0; var damaged = 0
        var offsetError = 0.0; var offsetSamples = 0; var namesAltered = 0; var names = 0
        fun line(name: String) = "%-28s keys right %5.1f%%  words right %5.1f%%  | typo words %5.1f%%: fixed %5.1f%% wrong %4.1f%% left %5.1f%%  | clean words broken %4.2f%%  | offset err %.2f%s".format(
            name, 100.0 * keysRight / keys, 100.0 * right / words, 100.0 * typos / words, 100.0 * fixed / maxOf(1, typos),
            100.0 * wrong / maxOf(1, typos), 100.0 * (typos - fixed - wrong) / maxOf(1, typos), 100.0 * damaged / maxOf(1, clean),
            offsetError / maxOf(1, offsetSamples), if (names > 0) "  | names altered $namesAltered/$names" else "")
    }

    @Test fun report() {
        assumeTrue(System.getenv("TK_DRIFT") == "1")
        val data = File(System.getenv("TK_LM_DATA") ?: "../../tk-lm-data")
        val predictor = Predictor({ File(assets, it).inputStream() }, Files.createTempDirectory("tk-drift").toFile()).apply { setLayout(SplitTaps.grid) }
        val tokens = Regex(""""t":\[([^\]]*)]""")
        val pool = listOf("soda", "tatoeba", "common_voice").flatMap { source ->
            GZIPInputStream(File(data, "processed/$source.eval.jsonl.gz").inputStream()).bufferedReader().useLines { lines ->
                lines.mapNotNull { line -> tokens.find(line)?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('"') } }
                    .filter { it.size in 4..16 && it.all { t -> t.isNotEmpty() && t.all { c -> c in centres } } }.drop(300).take(200).toList()
            }
        }
        val typists = System.getenv("TK_DRIFT_TYPISTS")?.toInt() ?: 10
        val perTypist = System.getenv("TK_DRIFT_SENTENCES")?.toInt() ?: 30
        val seed = System.getenv("TK_DRIFT_SEED")?.toInt() ?: 0
        val core = System.getenv("TK_DRIFT_CORE")?.toFloat() ?: .35f
        val sigma = System.getenv("TK_DRIFT_SIGMA")?.toDouble() ?: .35
        val populations = listOf(
            Population("steady hands", 0f, 0f, .22f),
            Population("drifting hands", .2f, .06f, .22f),
            Population("drifting, sloppier", .2f, .06f, .32f),
            Population("slight drift", .1f, .03f, .22f),
            Population("fast drift", .2f, .1f, .22f),
            Population("fixed habit, no wander", .3f, 0f, .22f))
        val onlyPops = System.getenv("TK_DRIFT_POPS")?.split(',')?.map { it.trim() }
        // The keyboard ships with smart tap areas on, so "as it is" resizes keys; "targets off" is the plain nearest key.
        val all = listOf(
            Variant("targets off"),
            Variant("as it is", resize = true),
            Variant("track (scores only)", track = true, resize = true),
            Variant("track", track = true, shiftKeys = true, resize = true),
            Variant("track, targets off", track = true, shiftKeys = true))
        val wanted = System.getenv("TK_DRIFT_VARIANTS")?.split(',')?.map { it.trim() }
        val variants = if (wanted == null) all else all.filter { it.name in wanted }
        val names = listOf("kaito", "anya", "reddit", "lyft", "zara", "tomas", "selin", "yeet", "brexit", "vape", "nikolai", "ottawa",
            "pixar", "ramen", "matcha", "tiktok", "netflix", "goku", "zelda", "dior", "gucci", "ikea", "lego", "bruh")

        /**
         * KeyboardView.findKey with smart tap areas: the middle of a key is always that key; otherwise keys within 35%
         * of their width beyond their edge compete on tap distance (sigma 0.35 widths) plus ln(0.05 + next-letter weight).
         * Distances there are in drawn widths, here [DRAWN] of the key spacing.
         */
        fun resized(x: Float, y: Float, weights: Map<Char, Double>): Char {
            val near = nearest(x, y)
            if (weights.isEmpty()) return near
            var best = near; var bestScore = Double.NEGATIVE_INFINITY
            for (ch in letters) {
                val (cx, cy) = centres.getValue(ch)
                val dx = (x - cx) / DRAWN; val dy = (y - cy) / DRAWN
                if (abs(dx) < core && abs(dy) < core) return ch
                if (abs(dx) >= .85f || abs(dy) >= .85f) continue
                val score = -(dx * dx + dy * dy) / (2 * sigma * sigma) + ln(.05 + (weights[ch] ?: 0.0))
                if (score > bestScore) { bestScore = score; best = ch }
            }
            return best
        }

        fun typeWord(word: String, before: String, prev: String?, prev2: String?, hands: Hands, drift: Drift?, v: Variant, tally: Tally): Typed {
            val taps = ComposingTaps(); var text = ""; val points = ArrayList<Pair<Float, Float>>()
            for (ch in word) {
                val raw = hands.tap(ch); points += raw
                val shifted = drift?.shift(raw.first, raw.second) ?: raw
                val (ex, ey) = if (v.shiftKeys) shifted else raw
                var key = nearest(ex, ey)
                if (v.resize) {
                    predictor.updateContext(before + text, text)
                    key = resized(ex, ey, predictor.nextLetters(text, prev, prev2, taps))
                }
                taps.append(text, key.toString(), SplitTaps.distances(shifted.first, shifted.second)); text += key
                tally.keys++; if (key == ch) tally.keysRight++
            }
            return Typed(text, taps, points)
        }

        fun typeSentence(sentence: List<String>, hands: Hands, drift: Drift?, v: Variant, tally: Tally) {
            val words = ArrayList<String>(); val typed = ArrayList<Typed>(); val found = ArrayList<List<String>?>()
            for ((i, meant) in sentence.withIndex()) {
                hands.word()
                if (drift != null) { tally.offsetError += drift.error(hands); tally.offsetSamples++ }
                else { tally.offsetError += (0..1).map { h -> sqrt(hands.now[h][0] * hands.now[h][0] + hands.now[h][1] * hands.now[h][1]) }.average(); tally.offsetSamples++ }
                val before = words.joinToString("") { "$it " }
                val t = typeWord(meant, before, words.lastOrNull(), words.getOrNull(words.size - 2), hands, drift, v, tally)
                val word = predictor.correction(before + t.text, AutocorrectMode.AGGRESSIVE, t.taps, allowJoin = false)?.replacement ?: t.text
                found += predictor.candidatesFor(t.text)
                if (i > 0) {
                    predictor.lookBack(before, typed[i - 1].text, typed[i - 1].taps, word, AutocorrectMode.AGGRESSIVE, found[i - 1])?.let { words[i - 1] = it }
                    if (predictor.hasWord(words[i - 1])) drift?.learn(typed[i - 1].points, words[i - 1].lowercase())
                }
                words += word; typed += t
            }
            val final = predictor.redecode("", typed.map { it.text }, typed.map { it.taps }, words, AutocorrectMode.AGGRESSIVE) ?: words
            // Like the keyboard, the last word isn't learned from: a period follows it, not the next word.
            for ((i, meant) in sentence.withIndex()) {
                val got = final[i].lowercase(); val tappedRight = typed[i].text == meant
                tally.words++; if (got == meant) tally.right++
                if (tappedRight) { tally.clean++; if (got != meant) tally.damaged++ }
                else { tally.typos++; if (got == meant) tally.fixed++ else if (got != typed[i].text) tally.wrong++ }
            }
        }

        for (pop in populations) {
            if (onlyPops != null && pop.name !in onlyPops) continue
            for (v in variants) {
                val tally = Tally()
                val start = System.nanoTime()
                for (typist in 0 until typists) {
                    // The same hands and the same taps for every variant: only what the keyboard does with them changes.
                    val r = Random(1000 * (populations.indexOf(pop) + 1) + typist + 100_000 * seed)
                    val hands = Hands(r, pop)
                    val drift = if (v.track) Drift() else null
                    // A few sentences typed earlier, then a later sitting where the hands land somewhere new.
                    for (s in pool.subList(0, 6)) for (w in s) { hands.word(); for (ch in w) hands.tap(ch) }
                    hands.session()
                    val mine = (0 until perTypist).map { pool[6 + (typist * perTypist + it + 97 * seed) % (pool.size - 6)] }
                    for (s in mine) typeSentence(s, hands, drift, v, tally)
                    if (pop.wander == 0f) for (name in names) {
                        tally.names++
                        if (typeWord(name, "", null, null, hands, drift, v, Tally()).text != name) tally.namesAltered++
                    }
                }
                println("TK_DRIFT " + tally.line("${pop.name}: ${v.name}") + "  (%.0fs)".format((System.nanoTime() - start) / 1e9))
            }
        }
    }

    private companion object {
        /** How much of the wander is kept from one word to the next: about 30 words to settle back. */
        const val RHO = .97f
        /** The drawn key as a share of the key spacing; the rest is the gap. */
        const val DRAWN = .85f
    }
}
