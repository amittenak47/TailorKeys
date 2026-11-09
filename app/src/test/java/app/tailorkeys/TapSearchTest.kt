package app.tailorkeys

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin search against tools/lm/candidates.py, which the model was trained with, on words typed by simulated
 * typists on every training layout (tools/lm/app_fixtures.py search writes the cases), on listed misspellings
 * searched with their typed spelling, and on casual forms with the casual readings (the keyboard's own search). The
 * same candidates in the same order, the same words added at the end (shape words, casual readings, listed
 * corrections) and the same alignments; words tied on cost may swap places.
 */
class TapSearchTest {
    private val open = { name: String -> File("src/main/assets", name).inputStream() }
    private val search by lazy { TapSearch(TapSearch.load(open, offensive = false), TapSearch.loadMisspellings(open)) }
    /** As the keyboard searches: with the casual readings, for the casual cases. */
    private val casual by lazy { TapSearch(search.words, TapSearch.loadMisspellings(open), TapSearch.loadCasual(open)) }

    private fun cases() = File("src/test/resources/tap_search_cases.jsonl").readLines().map { JSONObject(it) }

    private fun features(case: JSONObject): List<FloatArray> {
        val rows = case.getJSONArray("features")
        return (0 until rows.length()).map { i -> rows.getJSONArray(i).let { r -> FloatArray(r.length()) { r.getDouble(it).toFloat() } } }
    }

    private fun layout(case: JSONObject): TapSearch.Layout {
        val c = case.getJSONObject("centres")
        return TapSearch.Layout(c.keys().asSequence().toList().associate { k: String -> k[0] to c.getJSONArray(k).let { it.getDouble(0).toFloat() to it.getDouble(1).toFloat() } })
    }

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    /**
     * Many words tie exactly on tap cost (every two-letter word with the tapped letter and a skipped key), and numpy
     * picks among ties in no set order, so the taps-only list may differ there; the lists weighted by rank never tie.
     * A difference passes when every word in it costs the same as the taps-only list's last word, or when two splits
     * hold the same two words in the other order (their sums differ only by float rounding).
     */
    @Test fun matchesThePythonSearch() {
        var same = 0
        var tied = 0
        val misses = ArrayList<String>()
        val cases = cases()
        for (case in cases) {
            val f = features(case)
            val want = strings(case.getJSONArray("found"))
            val search = if (case.getString("mode").startsWith("casual")) casual else search
            val got = search.search(f, layout(case), typed = if (case.has("typed")) case.getString("typed") else null)
            val added = search.added
            if (got == want) same++
            val onlyPython = want - got.toSet()
            val onlyKotlin = got - want.toSet()
            if (onlyPython.isEmpty() && onlyKotlin.isEmpty() && added == case.getInt("added")) continue
            val best = search.costs(f).first
            val edge = best.filter { it.isFinite() }.sorted().getOrElse(7) { Float.POSITIVE_INFINITY }
            fun tie(w: String) = ' ' !in w && search.index[w]?.let { kotlin.math.abs(best[it] - edge) < 1e-3f } == true
            fun swapped(w: String, other: List<String>) = ' ' in w && other.any { it.split(' ').sorted() == w.split(' ').sorted() }
            if (added == case.getInt("added") && onlyPython.all { tie(it) || swapped(it, onlyKotlin) } &&
                onlyKotlin.all { tie(it) || swapped(it, onlyPython) }) tied++
            else misses += "${case.getString("layout")} ${case.getString("word")} ${case.getString("mode")}: " +
                "python $onlyPython, kotlin $onlyKotlin, added at the end ${case.getInt("added")} / $added"
        }
        println("same order $same/${cases.size}, differing only by ties $tied\n" + misses.joinToString("\n"))
        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    @Test fun alignsAsPython() {
        var checked = 0
        for (case in cases()) {
            val f = features(case)
            val want = case.getJSONObject("align")
            for (word in want.keys().asSequence().toList()) {
                val expected = want.opt(word)
                val got = TapSearch.align(f, word)
                if (expected == null || expected == JSONObject.NULL) {
                    assertEquals(word, null, got)
                    continue
                }
                val pair = expected as JSONArray
                val taps = pair.getJSONArray(0).let { a -> IntArray(a.length()) { a.getInt(it) } }
                val unmatched = pair.getJSONArray(1).let { a -> IntArray(a.length()) { a.getInt(it) } }
                assertEquals(word, taps.toList(), got!!.taps.toList())
                assertEquals(word, unmatched.toList(), got.unmatched.toList())
                checked++
            }
        }
        assertTrue(checked > 1000)
    }
}
