package app.tailorkeys

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The space bar's instant keep ([StudentCorrector.decide]) writes what scoring every candidate writes: for each
 * fixture word typed as the keyboard types it (each tap, then the space), mid-sentence and starting one, the decision
 * through [StudentCorrector.decide] against [StudentCorrector.live] on a corrector of its own, with the misspelling
 * list as the keyboard searches with it. Asked again, a decision comes back as it was, from where it came; after a new
 * layout it is decided afresh. Needs the exported model in src/main/assets/model.
 */
class StudentFastKeepTest {
    private val assets = File("src/main/assets")
    private val model = File(assets, "model/step.int8.onnx")

    @Test fun keepsWhatFullScoringWrites() {
        assumeTrue("no exported model", model.exists())
        val open = { name: String -> File(assets, name).inputStream() }
        val search = TapSearch(TapSearch.load(open, offensive = false), TapSearch.loadMisspellings(open), TapSearch.loadCasual(open))
        val slot = StudentSlot(StudentTokenizer.load(open))
        val heads = open("model/head_words.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val cases = File("src/test/resources/student_slot_cases.jsonl").readLines().map { JSONObject(it) }
        var kept = 0
        var same = 0
        var again = 0
        val differ = ArrayList<String>()
        StudentModel(model.path, threads = 1).use { m ->
            for (case in cases) {
                val f = case.getJSONArray("features").let { rows -> (0 until rows.length()).map { r -> rows.getJSONArray(r).let { x -> FloatArray(x.length()) { x.getDouble(it).toFloat() } } } }
                val c = case.getJSONObject("centres")
                val layout = TapSearch.Layout(c.keys().asSequence().toList().associate { k: String ->
                    k[0] to c.getJSONArray(k).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } })
                val left = case.getString("left")
                val typed = case.getString("typed")
                for (first in listOf(false, true)) {
                    val fast = StudentCorrector(m, search, slot, heads).also { it.layout = layout }
                    for (n in 1..f.size) fast.typing(left, "", f.take(n))
                    val d = fast.decide(left, "", typed, f, first) ?: continue
                    // Asked again, as the keyboard may: the same decision, from the same source, without the model.
                    val repeat = fast.decide(left, "", typed, f, first)
                    assertTrue(repeat?.source == d.source && repeat.live === d.live && fast.reused)
                    if (d.source != StudentCorrector.Source.WHEEL_KEEP) continue
                    kept++
                    // A new layout changes what the taps spell: decided afresh.
                    fast.layout = TapSearch.Layout(layout.centres)
                    fast.decide(left, "", typed, f, first)
                    if (!fast.reused) again++
                    val full = StudentCorrector(m, search, slot, heads).also { it.layout = layout }.live(left, "", typed, f, first)
                    if (full?.fix == d.live.fix) same++ else differ += "$typed${if (first) " (first)" else ""}: full ${full?.fix}, wheel keep ${d.live.fix}"
                }
            }
        }
        println("${cases.size} words, twice: kept by the wheel $kept, the same as full scoring $same, decided afresh after a new layout $again" +
            if (differ.isEmpty()) "" else "; differ: $differ")
        assertTrue(kept > 0 && same == kept && again == kept)
    }
}
