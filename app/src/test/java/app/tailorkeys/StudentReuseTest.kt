package app.tailorkeys

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The corrector's kept readings decide as reading everything afresh: the wheel before a word, each tap, the space
 * bar, and a recheck's shorter context, against a new corrector per question. Only int8 rounding may differ (it
 * depends on how tokens are grouped into calls). Needs the exported model in src/main/assets/model.
 */
class StudentReuseTest {
    private val assets = File("src/main/assets")
    private val model = File(assets, "model/step.int8.onnx")

    @Test fun keptReadingsDecideAsFresh() {
        assumeTrue("no exported model", model.exists())
        val open = { name: String -> File(assets, name).inputStream() }
        val search = TapSearch(TapSearch.load(open, offensive = false), TapSearch.loadMisspellings(open), TapSearch.loadCasual(open))
        val slot = StudentSlot(StudentTokenizer.load(open))
        val heads = open("model/head_words.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val cases = File("src/test/resources/student_slot_cases.jsonl").readLines().map { JSONObject(it) }.take(60)
        var worst = 0f
        var same = 0
        var asked = 0
        StudentModel(model.path, threads = 1).use { m ->
            val kept = StudentCorrector(m, search, slot, heads)
            fun fresh() = StudentCorrector(m, search, slot, heads).also { it.layout = kept.layout }
            fun compare(a: StudentCorrector.Scored, b: StudentCorrector.Scored) {
                asked++
                if (a.candidates == b.candidates && a.best == b.best) same++
                for (i in a.probability.indices) worst = maxOf(worst, abs(a.probability[i] - b.probability[i]))
            }
            for (case in cases) {
                val f = case.getJSONArray("features").let { rows -> (0 until rows.length()).map { r -> rows.getJSONArray(r).let { x -> FloatArray(x.length()) { x.getDouble(it).toFloat() } } } }
                val c = case.getJSONObject("centres")
                kept.layout = TapSearch.Layout(c.keys().asSequence().toList().associate { k: String ->
                    k[0] to c.getJSONArray(k).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } })
                val left = case.getString("left")
                val right = case.getString("right")
                val typed = case.getString("typed")
                // As the keyboard asks: the wheel before the word, after each tap, then the space bar.
                val before = kept.next(left)
                val wheel = fresh().next(left)
                for (i in before.indices) worst = maxOf(worst, abs(before[i] - wheel[i]))
                for (n in 1..f.size) kept.typing(left, right, f.take(n))
                compare(kept.score(left, right, typed, f)!!, fresh().score(left, right, typed, f)!!)
                // A recheck's context: a shorter start of the one just read, with words on the right.
                val shorter = left.substringBeforeLast(' ', "")
                val after = (left.substringAfterLast(' ', left) + " " + right).trim()
                compare(kept.score(shorter, after, typed, f)!!, fresh().score(shorter, after, typed, f)!!)
            }
        }
        println("$asked decisions: same candidates and choice $same, largest probability difference $worst")
        assertTrue(same == asked && worst < 0.05f)
    }
}
