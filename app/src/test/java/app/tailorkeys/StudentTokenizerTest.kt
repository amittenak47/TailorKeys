package app.tailorkeys

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The Kotlin word pieces against the student's Python tokenizer (tools/lm/app_fixtures.py tokenizer writes the cases). */
class StudentTokenizerTest {
    @Test fun matchesThePythonTokenizer() {
        val tok = StudentTokenizer.load({ File("src/main/assets", it).inputStream() })
        val cases = File("src/test/resources/student_tokenizer_cases.jsonl").readLines().map { JSONObject(it) }
        for (case in cases) {
            val want = case.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getInt(it) } }
            assertEquals(case.getString("text"), want, tok.encode(case.getString("text")).toList())
        }
    }
}
