package app.tailorkeys

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The recheck never turns a fix back into a non-word ([StudentCorrector.RECHECK_KEEPS_WORDS]): the tablet's own
 * "it is lste." (its logged taps, as the model read them; 2026-11-02), fixed to late at the space bar, rechecked with
 * the period after it. Needs the exported model in src/main/assets/model.
 */
class StudentRecheckGuardTest {
    private val assets = File("src/main/assets")
    private val model = File(assets, "model/step.int8.onnx")

    @Test fun neverBackToANonWord() {
        assumeTrue("no exported model", model.exists())
        val open = { name: String -> File(assets, name).inputStream() }
        val search = TapSearch(TapSearch.load(open, offensive = false), TapSearch.loadMisspellings(open), TapSearch.loadCasual(open))
        val slot = StudentSlot(StudentTokenizer.load(open))
        val heads = open("model/head_words.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val case = JSONObject(File("src/test/resources/recheck_nonword_case.json").readText())
        val taps = case.getJSONArray("taps").let { rows -> (0 until rows.length()).map { r -> rows.getJSONArray(r).let { x -> FloatArray(x.length()) { x.getDouble(it).toFloat() } } } }
        val left = case.getString("left")
        val typed = case.getString("typed")
        StudentModel(model.path, threads = 1).use { m ->
            val corrector = StudentCorrector(m, search, slot, heads)
            assertTrue(corrector.atSpace(left, "", typed, taps, false) == "late")
            val open_ = corrector.recheck(left, ".", "late", typed, taps, false, keepWords = false)
            val guarded = corrector.recheck(left, ".", "late", typed, taps, false)
            println("recheck of late (typed lste) with the period after it: unguarded $open_, guarded $guarded")
            assertTrue(guarded == null || guarded.lowercase() in search.index)
        }
    }
}
