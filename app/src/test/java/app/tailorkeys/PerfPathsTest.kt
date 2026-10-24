package app.tailorkeys

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * TK_PERF=1: times every path the keyboard runs while typing held-out sentences (tk-lm-data eval splits) with the
 * English model, as the service calls them: each keystroke (context, suggestions, key hints), autocorrect at each
 * space, look-back, and whole-sentence re-decoding. Desktop JVM, after warm-up; medians and 95th percentiles.
 * TK_PERF_BASE=1 skips look-back and re-decoding, so the same file can time older commits that lack them.
 */
class PerfPathsTest {
    private val centres = SplitTaps.centres
    private val letters = centres.keys.toList()

    private fun gauss(r: Random): Float { val u = 1 - r.nextDouble(); return (sqrt(-2 * ln(u)) * cos(2 * Math.PI * r.nextDouble())).toFloat() }

    @Test fun paths() {
        assumeTrue(System.getenv("TK_PERF") == "1")
        val base = System.getenv("TK_PERF_BASE") == "1"
        val data = File(System.getenv("TK_LM_DATA") ?: "../../tk-lm-data")
        val p = Predictor({ File("src/main/assets", it).inputStream() }, Files.createTempDirectory("tk-perf-paths").toFile()).apply { setLayout(SplitTaps.grid) }
        val tokens = Regex(""""t":\[([^\]]*)]""")
        val sentences = listOf("soda", "tatoeba", "common_voice").flatMap { source ->
            GZIPInputStream(File(data, "processed/$source.eval.jsonl.gz").inputStream()).bufferedReader().useLines { lines ->
                lines.mapNotNull { line -> tokens.find(line)?.groupValues?.get(1)?.split(',')?.map { it.trim().trim('"') } }
                    .filter { it.size in 4..16 && it.all { t -> t.isNotEmpty() && t.all { c -> c in centres } } }.take(120).toList()
            }
        }
        val r = Random(5)
        val keystroke = ArrayList<Long>(); val space = ArrayList<Long>(); val lookBack = ArrayList<Long>(); val sentence = ArrayList<Long>()
        var checksum = 0
        fun typeAll(record: Boolean) {
            for (words in sentences) {
                val shown = ArrayList<String>(); val typed = ArrayList<String>(); val tapList = ArrayList<ComposingTaps>()
                for (word in words) {
                    val before = shown.joinToString("") { "$it " }
                    val taps = ComposingTaps(); var text = ""
                    for (ch in word) {
                        val (cx, cy) = centres.getValue(ch)
                        val x = cx + .3f * gauss(r); val y = cy + .3f * gauss(r)
                        val key = letters.minBy { val (a, b) = centres.getValue(it); (x - a) * (x - a) + (y - b) * (y - b) }
                        taps.append(text, key.toString(), SplitTaps.distances(x, y)); text += key
                        // What the service does after every letter: context, both wheels and the key hints.
                        val t0 = System.nanoTime()
                        p.updateContext(before + text, text)
                        checksum += p.closest(text, shown.lastOrNull(), shown.getOrNull(shown.size - 2), taps = taps).size
                        checksum += p.explore(text, shown.lastOrNull(), shown.getOrNull(shown.size - 2), taps = taps).size
                        checksum += p.nextLetters(text, shown.lastOrNull(), shown.getOrNull(shown.size - 2), taps).size
                        if (record) keystroke += System.nanoTime() - t0
                    }
                    val t1 = System.nanoTime()
                    val word2 = p.correction(before + text, AutocorrectMode.AGGRESSIVE, taps, allowJoin = false)?.replacement ?: text
                    if (record) space += System.nanoTime() - t1
                    if (!base && shown.isNotEmpty()) {
                        val t2 = System.nanoTime()
                        p.lookBack(before, typed.last(), tapList.last(), word2, AutocorrectMode.AGGRESSIVE)?.let { shown[shown.size - 1] = it }
                        if (record) lookBack += System.nanoTime() - t2
                    }
                    shown += word2; typed += text; tapList += taps
                }
                if (!base) {
                    val t3 = System.nanoTime()
                    checksum += p.redecode("", typed, tapList, shown, AutocorrectMode.AGGRESSIVE)?.size ?: 0
                    if (record) sentence += System.nanoTime() - t3
                }
            }
        }
        typeAll(false)
        typeAll(true)
        fun line(name: String, times: List<Long>) {
            if (times.isEmpty()) return
            val sorted = times.sorted()
            println("TK_PERF %-22s n=%5d  median %6.2f ms  p95 %6.2f ms  max %7.2f ms".format(name, sorted.size,
                sorted[sorted.size / 2] / 1e6, sorted[sorted.size * 95 / 100] / 1e6, sorted.last() / 1e6))
        }
        line("keystroke", keystroke); line("space (autocorrect)", space); line("look-back", lookBack); line("sentence re-decoding", sentence)
        println("TK_PERF checksum=$checksum")
    }
}
