package app.tailorkeys

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The keyboard's whole path for one word against Python's (tools/lm/app_fixtures.py slots): the same candidates from
 * the same taps (but for words tied on cost), and the same int8 model scores (within the drift between ONNX
 * Runtime versions: Python's 1.23, the tests' 1.17), reading the context at once and then each tap on its own, as the
 * keyboard will. Needs the exported model in src/main/assets/model (tools/lm/export.py).
 */
class StudentSlotTest {
    private val assets = File("src/main/assets")
    private val model = File(assets, "model/step.int8.onnx")

    private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test fun scoresAsPython() {
        assumeTrue("no exported model", model.exists())
        val open = { name: String -> File(assets, name).inputStream() }
        val search = TapSearch(TapSearch.load(open, offensive = false))
        val slot = StudentSlot(StudentTokenizer.load(open))
        val cases = File("src/test/resources/student_slot_cases.jsonl").readLines().map { JSONObject(it) }
        var sameCands = 0
        var sameChoice = 0
        var sameWheel = 0
        var worst = 0f
        StudentModel(model.path, threads = 1).use { student ->
            for (case in cases) {
                val f = case.getJSONArray("features").let { rows -> (0 until rows.length()).map { floats(rows.getJSONArray(it)) } }
                val c = case.getJSONObject("centres")
                val layout = TapSearch.Layout(c.keys().asSequence().toList().associate { k: String ->
                    k[0] to c.getJSONArray(k).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } })
                val want = strings(case.getJSONArray("cands"))
                val found = search.search(f, layout)
                // Order never matters (each candidate is scored on its own); words tied on cost may differ (TapSearchTest).
                val mine = StudentSlot.candidates(case.getString("typed"), found, search.added)
                val best = search.costs(f).first
                val edge = best.filter { it.isFinite() }.sorted().getOrElse(7) { Float.POSITIVE_INFINITY }
                val tied = (mine.toSet() xor want.toSet()).all { w -> search.index[w]?.let { abs(best[it] - edge) < 1e-3f } == true }
                if (tied) sameCands++ else println("${case.getString("typed")}: python ${want - mine.toSet()}, kotlin ${mine - want.toSet()}")
                // Score Python's own list, so a tie the searches broke apart doesn't hide a scoring difference.
                val (left, right) = slot.context(case.getString("left"), case.getString("right"))
                var out = student.run(StudentModel.Reading.EMPTY, listOf(slot.contextRow(left, right)))
                val wheel = out.wheel!!
                for ((i, tap) in f.withIndex()) out = student.run(out.reading!!, listOf(slot.tapRow(tap, i)))
                val scored = student.run(out.reading!!, want.map { slot.candidateRow(it, f) })
                val score = floats(case.getJSONArray("score"))
                for (i in score.indices) worst = maxOf(worst, abs(score[i] - scored.score[i]))
                if (scored.score.indices.maxBy { scored.score[it] } == score.indices.maxBy { score[it] }) sameChoice++
                if (wheel.indices.maxBy { wheel[it] } == case.getJSONArray("wheel").getInt(0)) sameWheel++
            }
        }
        println("${cases.size} slots: same candidates $sameCands, same choice $sameChoice, same top wheel word $sameWheel, " +
            "largest score difference $worst")
        assertTrue(sameChoice == cases.size && worst < 0.1f && sameCands == cases.size)
    }

    private infix fun <T> Set<T>.xor(other: Set<T>) = (this - other) + (other - this)
}
