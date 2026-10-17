package app.tailorkeys

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.system.measureNanoTime

/** Opt in with TK_LM_EVAL=1. Streams evenly spaced, original held-out records; never trains.
 * Includes OOV misses and short fully known words. Both scorers use the real candidate builder,
 * split layout, correction budget and closest() filtering, with empty personal dictionaries.
 */
class PredictorIntegratedEvaluationTest {
    private data class Counts(var words: Int = 0, val top1: IntArray = IntArray(3), val top3: IntArray = IntArray(3))
    private data class Record(val tokens: List<String>, val history: List<List<String>>)
    private val quoted = Regex("\"([^\"]*)\"")
    private fun strings(text: String) = quoted.findAll(text).map { it.groupValues[1] }.toList()
    private fun record(line: String): Record {
        // prepare.py emits only normalized lexical/special tokens: no quote/backslash escapes.
        val end = line.indexOf(']')
        val current = strings(line.substring(line.indexOf('[') + 1, end))
        val history = Regex("\\[([^\\[\\]]*)]").findAll(line.substring(end + 1)).map { strings(it.groupValues[1]) }.toList()
        return Record(current, history)
    }
    private fun reader(file: File) = GZIPInputStream(file.inputStream()).bufferedReader(Charsets.UTF_8)
    private fun sha(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun text(tokens: List<String>) = tokens.joinToString(" ") {
        when (it) { "<num>" -> "123"; "<,>" -> ","; else -> it }
    }
    private fun percentile(times: List<Double>, p: Double) = times.sorted()[((times.size - 1) * p).toInt()]

    @Test fun integratedKeyboard() {
        assumeTrue(System.getenv("TK_LM_EVAL") == "1")
        val data = File(System.getenv("TK_LM_DATA") ?: "../../tk-lm-data")
        val samples = (System.getenv("TK_LM_EVAL_SENTENCES") ?: "256").toInt()
        val tuning = System.getenv("TK_LM_EVAL_TUNE") == "1"
        val assets = File("src/main/assets")
        val dir = Files.createTempDirectory("integrated-lm-eval").toFile()
        val report = StringBuilder()
        val selectedHash = MessageDigest.getInstance("SHA-256")
        val started = System.nanoTime()
        try {
            val old = Predictor({ File(assets, it).inputStream() }, dir, enableModel = false).apply { setLayout(SplitTaps.grid) }
            val combined = Predictor({ File(assets, it).inputStream() }, dir).apply { setLayout(SplitTaps.grid) }
            val overall = Array(3) { Counts() }
            val keys = ArrayList<Double>(); val boundaries = ArrayList<Double>(); val recomputes = ArrayList<Double>()
            var checksum = 0
            var mismatches = 0
            report.appendLine("asset_sha256=${sha(File(assets, "lm_en.bin"))}; sentences_per_source=$samples; typo_weight=${Predictor.MODEL_TYPO_WEIGHT}; oov_dictionary_share=${EnglishContext.OOV_DICTIONARY_SHARE}; tuning=$tuning")
            report.appendLine("source,scorer,words,prefix,top1,top3,top1_pct,top3_pct")
            for (source in listOf("common_voice", "tatoeba", "soda")) {
                val file = File(data, "processed/$source.eval.jsonl.gz")
                require(file.isFile) { "Missing documented held-out data: $file" }
                val n = reader(file).use { it.lineSequence().count() }
                val calibration = (0 until minOf(n, 256)).map { ((it + 0.5) * n / minOf(n, 256)).toInt() }.toHashSet()
                val available = (0 until n).filter { tuning || it !in calibration }
                val selected = (0 until minOf(available.size, samples)).map {
                    available[((it + 0.5) * available.size / minOf(available.size, samples)).toInt()]
                }.toHashSet()
                report.appendLine("# $source file_sha256=${sha(file)}; records=$n; selected=${selected.size}")
                val counts = Array(3) { Counts() }
                reader(file).use { input -> input.lineSequence().forEachIndexed { index, line ->
                    if (index !in selected) return@forEachIndexed
                    selectedHash.update("$source:$index:$line\n".toByteArray(Charsets.UTF_8))
                    val row = record(line)
                    val before = StringBuilder(row.history.joinToString("") { text(it) + ". " })
                    var prev: String? = null; var prev2: String? = null
                    combined.resetContext()
                    val recomputeTime = measureNanoTime { combined.updateContext(before, "") } / 1e6
                    recomputes.add(recomputeTime)
                    for (word in row.tokens) {
                        if (!word.startsWith("<")) {
                            counts.forEach { it.words++ }; overall.forEach { it.words++ }
                            for (letters in 0..2) {
                                val prefix = word.take(letters)
                                val oldWords = old.closest(prefix, prev, prev2, 3)
                                val keyTime = measureNanoTime {
                                    combined.updateContext(before.toString() + prefix, prefix)
                                    checksum += combined.nextLetters(prefix, prev, prev2).size
                                    checksum += combined.explore(prefix, prev, prev2).hashCode()
                                    checksum += combined.closest(prefix, prev, prev2, 3).hashCode()
                                } / 1e6
                                if (letters > 0) keys.add(keyTime)
                                val newWords = combined.closest(prefix, prev, prev2, 3)
                                // Independent model-only exact-prefix ranking; no dictionary prior or typo score.
                                val modelWords = combined.english!!.withScores { s ->
                                    val top = IntArray(3) { -1 }
                                    for (id in 5 until s!!.model.vocabularySize) {
                                        // Like the keyboard, never offer the already typed word back.
                                        if (!s.model.vocabulary[id].startsWith(prefix) || s.model.vocabulary[id] == prefix) continue
                                        var at = 2
                                        if (top[at] >= 0 && s.probabilities[id] <= s.probabilities[top[at]]) continue
                                        while (at > 0 && (top[at - 1] < 0 || s.probabilities[id] > s.probabilities[top[at - 1]])) {
                                            top[at] = top[at - 1]; at--
                                        }
                                        top[at] = id
                                    }
                                    top.filter { it >= 0 }.map { s.model.vocabulary[it] }
                                }
                                if (tuning && word in modelWords && word !in newWords && mismatches++ < 10) {
                                    report.appendLine("# displaced source=$source record=$index prefix=$prefix target=$word combined=$newWords model=$modelWords")
                                }
                                for ((scorer, words) in listOf(oldWords, newWords, modelWords).withIndex()) {
                                    for (c in listOf(counts[scorer], overall[scorer])) {
                                        if (word == words.firstOrNull()) c.top1[letters]++
                                        if (word in words) c.top3[letters]++
                                    }
                                }
                            }
                            prev2 = prev; prev = word
                        }
                        before.append(text(listOf(word))).append(' ')
                        boundaries.add(measureNanoTime { combined.updateContext(before, "") } / 1e6)
                    }
                } }
                appendCounts(report, source, counts)
            }
            appendCounts(report, "overall", overall)
            report.appendLine("selected_sha256=" + selectedHash.digest().joinToString("") { "%02x".format(it) })
            report.appendLine("keystroke_ms median=${percentile(keys, 0.5)} p95=${percentile(keys, 0.95)} n=${keys.size}")
            report.appendLine("boundary_ms median=${percentile(boundaries, 0.5)} p95=${percentile(boundaries, 0.95)} n=${boundaries.size}")
            report.appendLine("recompute_ms median=${percentile(recomputes, 0.5)} p95=${percentile(recomputes, 0.95)} n=${recomputes.size}")
            report.appendLine("elapsed_seconds=${(System.nanoTime() - started) / 1e9}; checksum=$checksum; java=${System.getProperty("java.version")}")
            File("build/reports/integrated-lm-evaluation.txt").apply { parentFile.mkdirs(); writeText(report.toString()) }
            println(report)
            for (prefix in 0..2) {
                assertTrue("Combined top1 must meet model alone for prefix $prefix", overall[1].top1[prefix] >= overall[2].top1[prefix])
                assertTrue("Combined top3 must meet model alone for prefix $prefix", overall[1].top3[prefix] >= overall[2].top3[prefix])
            }
        } finally { dir.deleteRecursively() }
    }

    private fun appendCounts(report: StringBuilder, source: String, counts: Array<Counts>) {
        for ((i, scorer) in listOf("old", "combined", "model_only").withIndex()) for (prefix in 0..2) {
            val c = counts[i]
            report.appendLine("$source,$scorer,${c.words},$prefix,${c.top1[prefix]},${c.top3[prefix]},${100.0 * c.top1[prefix] / c.words},${100.0 * c.top3[prefix] / c.words}")
        }
    }
}
