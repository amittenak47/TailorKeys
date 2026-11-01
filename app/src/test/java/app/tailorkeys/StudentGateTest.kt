package app.tailorkeys

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * tools/lm's gate (teacher.gate_cases, one branch) decided by the keyboard's own path, StudentCorrector, so its counts
 * can be set beside Python's (tools/lm/export.py gate). Each sentence is typed word by word with the words written so
 * far on the left (the space bar), then every word is rechecked with both sides. The sentences, their written form
 * and context and each word's tap features come from tools/lm/app_fixtures.py gate.
 *
 * TK_MODEL_GATE=1 runs it (about an hour on the CPU); it needs the exported model in src/main/assets/model.
 */
class StudentGateTest {
    private class Sentence(val set: String, val slip: Int, val meant: List<String>, val typed: List<String>, val s: String,
                           val context: List<String>, val spans: List<IntArray>, val taps: List<List<FloatArray>>)

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    private fun load(file: File) = file.readLines().map { line ->
        val o = JSONObject(line)
        val spans = o.getJSONArray("spans").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> intArrayOf(p.getInt(0), p.getInt(1)) } } }
        val taps = o.getJSONArray("features").let { words -> (0 until words.length()).map { w ->
            words.getJSONArray(w).let { rows -> (0 until rows.length()).map { r -> rows.getJSONArray(r).let { x -> FloatArray(x.length()) { x.getDouble(it).toFloat() } } } } } }
        Sentence(o.getString("set"), o.optInt("slip", -1), strings(o.getJSONArray("meant")), strings(o.getJSONArray("typed")),
            o.getString("s"), strings(o.getJSONArray("c")), spans, taps)
    }

    /** The sentence's text from [start] to [end] with words [lo] until [hi] as [shown] (teacher.Slots.item's text). */
    private fun text(e: Sentence, shown: List<String?>, lo: Int, hi: Int, start: Int, end: Int): String {
        val out = StringBuilder()
        var at = start
        for (j in lo until hi) {
            val (x, y) = e.spans[j].let { it[0] to it[1] }
            out.append(e.s, at, x).append(shown[j]?.takeIf { it.isNotBlank() } ?: e.s.substring(x, y))
            at = y
        }
        return out.append(e.s, at, end).toString()
    }

    private fun left(e: Sentence, shown: List<String?>, k: Int) =
        (e.context + text(e, shown, 0, k, 0, e.spans[k][0]).trim()).joinToString(" ").trim()

    @Test fun gate() {
        assumeTrue(System.getenv("TK_MODEL_GATE") == "1")
        val data = File(System.getenv("TK_LM_DATA") ?: "../../tk-lm-data", "export/gate_examples.jsonl")
        val assets = File("src/main/assets")
        val open = { name: String -> File(assets, name).inputStream() }
        val sentences = load(data)
        val layout = TapSearch.Layout(SplitTaps.centres + ('\'' to (10.5f to 1.5f)))
        StudentModel(File(assets, "model/step.int8.onnx").path, threads = 6).use { model ->
            val heads = open("model/head_words.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
            val corrector = StudentCorrector(model, TapSearch(TapSearch.load(open, offensive = false)), StudentSlot(StudentTokenizer.load(open)), heads)
            corrector.layout = layout
            val start = System.currentTimeMillis()
            val atSpace = sentences.map { e ->
                val w = MutableList<String?>(e.meant.size) { null }
                for (k in e.meant.indices) {
                    val typed = e.typed[k]
                    if (e.taps[k].isEmpty() || typed.isBlank()) continue
                    w[k] = corrector.atSpace(left(e, w, k), "", typed, e.taps[k], first = k == 0)
                        ?: if (k == 0) typed.replaceFirstChar { it.uppercaseChar() } else typed
                }
                w
            }
            println("space bar done in ${(System.currentTimeMillis() - start) / 1000} s")
            val rechecked = sentences.zip(atSpace).map { (e, written) ->
                val out = written.toMutableList()
                for (k in e.meant.indices) {
                    val now = written[k] ?: continue
                    val right = text(e, written, k + 1, e.meant.size, e.spans[k][1], e.s.length).trim()
                    corrector.recheck(left(e, written, k), right, now, e.typed[k], e.taps[k], first = k == 0)?.let { out[k] = it }
                }
                out
            }
            for ((name, pass) in listOf("space bar" to atSpace, "recheck" to rechecked)) println("$name: " + count(sentences, pass))
        }
    }

    /** teacher.gate_cases's counts for one pass. */
    private fun count(sentences: List<Sentence>, out: List<List<String?>>): String {
        val v = LinkedHashMap<String, Int>()
        fun add(key: String, x: Boolean = true) { v[key] = (v[key] ?: 0) + if (x) 1 else 0 }
        fun same(a: String?, b: String) = (a ?: "").lowercase() == b.lowercase()
        for ((c, w) in sentences.zip(out)) for ((k, meant) in c.meant.withIndex()) {
            val typed = c.typed[k]
            when {
                c.set == "careful" -> {
                    if (typed == meant) { add("careful words"); add("careful words changed", !same(w[k], meant)) }
                    if (k > 0 && meant in listOf("i", "i'm", "i'll", "i've", "i'd")) {
                        add("mid-sentence i"); add("mid-sentence i capitalized", (w[k] ?: "").take(1) == "I")
                    }
                }
                c.set == "slipped" && k == c.slip -> {
                    add("real-word slips"); add("real-word slips fixed", same(w[k], meant))
                    add("real-word slips wrong word", !same(w[k], meant) && !same(w[k], typed))
                }
                c.set == "everyday" -> if (typed == meant) {
                    add("everyday words typed right"); add("everyday words typed right, changed", !same(w[k], meant))
                } else {
                    add("everyday typos"); add("everyday typos fixed", same(w[k], meant))
                    add("everyday typos wrong word", !same(w[k], meant) && !same(w[k], typed))
                }
            }
        }
        return v.entries.joinToString(", ") { "${it.key} ${it.value}" }
    }
}
