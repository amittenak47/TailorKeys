package app.tailorkeys

import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

internal object SplitTaps {
    val grid = KeyGrid.template(Template.SPLIT)
    val centres = grid.items.mapNotNull { key ->
        key.keys[0].singleOrNull()?.takeIf { it.isLetter() }?.let { it to (key.c + 0.5f to key.r + 0.5f) }
    }.toMap()

    /** Space bars as cell rectangles: left, top, right, bottom. */
    private val spaces = grid.items.filter { it.isSpace }.map { floatArrayOf(it.c.toFloat(), it.r.toFloat(), (it.c + it.w).toFloat(), it.r + 1f) }

    fun distances(x: Float, y: Float): FloatArray = FloatArray(ComposingTaps.LETTERS) { 100f }.also { row ->
        for ((ch, point) in centres) row[ch.code] = (x - point.first) * (x - point.first) + (y - point.second) * (y - point.second)
        // Like the keyboard: distance to the nearest space bar's edge, zero on it.
        row[ComposingTaps.SPACE] = spaces.minOf { (l, t, r, b) ->
            val dx = maxOf(l - x, 0f, x - r); val dy = maxOf(t - y, 0f, y - b); dx * dx + dy * dy }
    }

    fun word(word: String, slips: Boolean = false): ComposingTaps = ComposingTaps().also { taps ->
        for ((i, ch) in word.withIndex()) {
            val point = when {
                slips && ch == 'a' && i == 0 -> 1f to 1.5f // a/s boundary
                slips && ch == 'w' -> 2f to 0.5f // w/e boundary
                ch == '2' -> 2f to -0.05f // bottom edge of 2 above w/e
                else -> centres.getValue(ch)
            }
            taps.append(taps.word, ch.toString(), distances(point.first, point.second))
        }
    }
}

class PredictorSpatialTest {
    companion object {
        // Keep the frozen geometry/fallback suite independent of model readiness.
        // PredictorModelTest also checks these near-miss taps with an active English context.
        private val dir = Files.createTempDirectory("predictor-test").toFile().apply { deleteOnExit() }
        private val predictor = Predictor({ File("src/main/assets", it).inputStream() }, dir, enableModel = false).apply { setLayout(SplitTaps.grid) }
    }

    @Test fun sevenNearMissesRankInTopThree() {
        for (word in listOf("awvwn", "aivwn", "siv2n", "aeven")) {
            val result = predictor.closest(word, "six", "five", taps = SplitTaps.word(word, slips = true))
            assertTrue("$word: $result", "seven" in result.take(3))
        }
    }

    @Test fun centreTappedPrefixesStillLeadToTheIntendedWord() {
        // The exact typed word is never offered back; a correctly typed prefix must still complete first.
        for ((prefix, word) in listOf("th" to "the", "seve" to "seven", "becau" to "because", "peopl" to "people", "keyboar" to "keyboard")) {
            val result = predictor.closest(prefix, "six", "five", taps = SplitTaps.word(prefix))
            assertTrue("$prefix: $result", word in result.take(3) && result.first().startsWith(prefix))
        }
    }

    @Test fun threeNearKeyCentreSlipsFitTheLengthBudget() {
        val result = predictor.closest("awvwn", "six", "five", taps = SplitTaps.word("awvwn"))
        assertTrue(result.toString(), "seven" in result.take(3))
    }

    @Test fun missingTapsPreserveLegacyRankingsExactly() {
        // Frozen from the original predictor over the same assets and split layout; aivwn re-frozen when the extended
        // English lists added aiden and aidan's.
        val cases = mapOf(
            "awvwn" to listOf("advanced", "advantage", "advance", "adventure", "adventures", "advances", "advancing", "advantages"),
            "aivwn" to listOf("aiden", "albany", "avian", "albanian", "albania", "aidan", "aidan's", "albans"),
            "aeven" to listOf("seven", "adventure", "adventures", "seventh", "advent", "seventeen", "argentina", "adventurer"),
            "teh" to listOf("the", "their", "they", "then", "there", "them", "these", "ten"),
            "sevem" to listOf("seven", "several", "seventh", "seen", "december", "seventeen", "seem", "seventy"),
            "sev" to listOf("seven", "several", "second", "section", "development", "severe", "seventh", "severely"),
            "zzzzz" to emptyList(),
        )
        for ((word, expected) in cases) {
            assertEquals(word, expected, predictor.closest(word, "six", "five"))
            val unknown = ComposingTaps().apply { sync(word) }
            assertEquals(word, expected, predictor.closest(word, "six", "five", taps = unknown))
        }
    }

    @Test fun transpositionsAndMissingLettersStillWorkWithTaps() {
        for (word in listOf("teh", "hte", "sevn")) {
            val result = predictor.closest(word, "six", "five", taps = SplitTaps.word(word))
            val intended = if (word == "sevn") "seven" else "the"
            assertTrue("$word: $result", intended in result.take(3))
        }
    }

    @Test fun unrelatedCentredTapsExcludeSeven() {
        assertFalse("seven" in predictor.closest("zzzzz", "six", "five", 100, SplitTaps.word("zzzzz")))
    }

    @Test fun boundaryAndCentreCostsUseGaussianDistances() {
        val centre = ComposingTaps().apply { append("", "a", SplitTaps.distances(0.5f, 1.5f)) }
        assertEquals(0, centre.cost(0, 'a'))
        assertEquals(4, centre.cost(0, 's'))
        assertEquals(10, centre.cost(0, 'i'))
        val boundary = ComposingTaps().apply { append("", "a", SplitTaps.distances(1f, 1.5f)) }
        assertEquals(0, boundary.cost(0, 's'))
        assertEquals(0, boundary.cost(0, 'a'))
    }

    @Test fun mutableTapRevisionInvalidatesAllSuggestionCaches() {
        val taps = SplitTaps.word("aeven", slips = true)
        val spatial = predictor.closest("aeven", "six", "five", taps = taps)
        predictor.nextLetters("aeven", "six", "five", taps)
        predictor.explore("aeven", "six", "five", taps = taps)
        taps.clear()
        taps.sync("aeven")
        val fallback = predictor.closest("aeven", "six", "five", taps = taps)
        assertEquals(predictor.closest("aeven", "six", "five"), fallback)
        assertNotEquals(spatial, fallback)
        taps.clear()
        for (ch in "aeven") {
            val point = if (taps.word.isEmpty()) 1f to 1.5f else SplitTaps.centres.getValue(ch)
            taps.append(taps.word, ch.toString(), SplitTaps.distances(point.first, point.second))
        }
        assertEquals(spatial, predictor.closest("aeven", "six", "five", taps = taps))
    }

    @Test fun backspaceKeepsEarlierTapsAndExternalEditsClearThem() {
        val taps = SplitTaps.word("aeven", slips = true)
        taps.backspace()
        assertEquals("aeve", taps.word)
        assertTrue(taps.hasTap(0))
        assertEquals(0, taps.cost(0, 's'))
        assertFalse(taps.hasTap(4))
        taps.append("aeve", "n", null) // Pasted/unknown letter has exactly the legacy cost.
        assertFalse(taps.hasTap(4))
        assertTrue(taps.hasTap(0))
        taps.sync("changed")
        assertFalse(taps.hasTaps("changed"))
        assertFalse(taps.hasTap(0))
    }

    @Test fun oversizedInputAndMismatchedWordFallBackSafely() {
        val taps = SplitTaps.word("a".repeat(80))
        assertFalse(taps.hasTap(70))
        assertEquals(predictor.closest("aeven", "six", "five"), predictor.closest("aeven", "six", "five", taps = taps))
    }

    @Test fun numberRowSlipStaysInTheComposingWordUntilAnExternalChange() {
        val taps = SplitTaps.word("siv2n", slips = true)
        assertEquals("siv2n", taps.prefix("five six siv2n"))
        taps.backspace()
        assertEquals("siv2", taps.prefix("five six siv2"))
        taps.clear() // Cursor movement, paste, chord or suggestion replacement.
        assertEquals("n", taps.prefix("five six siv2n"))
        assertEquals("", taps.prefix("five six siv2"))
    }
}
