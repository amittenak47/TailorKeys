package app.tailorkeys

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AutocorrectSlipsTest {
    private val predictor = Predictor({ File("src/main/assets", it).inputStream() },
        Files.createTempDirectory("tk-slips2").toFile()).apply { setLayout(SplitTaps.grid) }

    /**
     * Each typed letter leans 45% toward the key that was meant ('-' is a clean tap; '_' is a reach for the space
     * bar, landing on the lower part of the key just above it).
     */
    private fun taps(typed: String, meant: String) = ComposingTaps().also { t ->
        for ((i, ch) in typed.withIndex()) {
            val (x, y) = SplitTaps.centres.getValue(ch.lowercaseChar())
            val m = meant.getOrNull(i)
            val (tx, ty) = when (m) { null, '-' -> x to y; '_' -> x to y + .67f; else -> SplitTaps.centres.getValue(m) }
            t.append(t.word, ch.toString(), SplitTaps.distances(x + .45f * (tx - x), y + .45f * (ty - y)))
        }
    }
    private fun fix(typed: String, meant: String) = predictor.autocorrect(typed, AutocorrectMode.AGGRESSIVE, taps = taps(typed, meant))

    @Test fun fourLetterWordsFollowTwoSlipsWhenTheTapsAgree() {
        assertEquals("fear", fix("dewr", "f-a-"))
        assertEquals("note", fix("nptw", "--te"))
    }

    @Test fun capitalizedWordsKeepTheirTaps() {
        // The first word of a sentence is auto-capitalized; its taps used to be dropped from the search.
        assertEquals("Fear", fix("Dewr", "f-a-"))
        assertEquals("Between", fix("Bwtwen", "-e----"))
    }

    @Test fun threeTappedLettersBecomeTheFourLetterWordTheSentenceWants() {
        assertEquals(WordCorrection("knw", "know"), predictor.correction("i don't knw", AutocorrectMode.AGGRESSIVE, taps("knw", "---")))
        // Without a sentence that wants it, three letters stay as typed.
        assertEquals(null, predictor.correction("knw", AutocorrectMode.AGGRESSIVE, taps("knw", "---")))
    }

    @Test fun lookBackRevisesARealWordOnceTheNextWordShowsItIsWrong() {
        // "fire" is a real word, so it survives its own space; "perfectly" after it gives it away.
        val fire = taps("fire", "--ts")
        assertEquals(null, predictor.correction("this small wooden chair fire", AutocorrectMode.AGGRESSIVE, fire))
        assertEquals("fits", predictor.lookBack("this small wooden chair fire ", "fire", fire, "perfectly", AutocorrectMode.AGGRESSIVE))
        // A word that fits both sides stays.
        assertEquals(null, predictor.lookBack("we sat by the fire ", "fire", taps("fire", "----"), "tonight", AutocorrectMode.AGGRESSIVE))
        assertEquals(null, predictor.lookBack("this small wooden chair fire ", "fire", fire, "perfectly", AutocorrectMode.OFF))
    }

    @Test fun aLetterBesideTheSpaceBarCanBeAMissedSpace() {
        // n sits right above the space bar: rryn is "try" plus a reach for space, not a word of its own.
        assertEquals(WordCorrection("rryn", "try"), predictor.correction("tomorrow we will rryn", AutocorrectMode.AGGRESSIVE, taps("rryn", "t--_")))
        assertEquals(WordCorrection("withn", "with"), predictor.correction("a field filled withn", AutocorrectMode.AGGRESSIVE, taps("withn", "----_")))
        // The space itself landed on n.
        assertEquals(WordCorrection("withnflowers", "with flowers"),
            predictor.correction("a field filled withnflowers", AutocorrectMode.AGGRESSIVE, taps("withnflowers", "----_-------")))
        // A tap in the middle of n is an n.
        assertEquals(null, predictor.correction("tomorrow we will rryn", AutocorrectMode.AGGRESSIVE, taps("rryn", "t---")))
        // A last letter far from the bar is a letter: dropping it still needs real evidence.
        assertEquals(null, predictor.correction("run the teadt", AutocorrectMode.AGGRESSIVE, taps("teadt", "--st-")))
    }

    @Test fun theEndOfTheSentenceSettlesItsLastWord() {
        // The keyboard has loaded the model by the end of a sentence; a test has to ask for it.
        predictor.updateContext("", "")
        // "wor" is in the dictionary, so it survives its own key; a sentence ending there wants "work".
        val typed = "tomorrow we will try a different route to wor".split(" ")
        val taps = typed.map { taps(it, "-".repeat(it.length)) }
        assertEquals(typed.dropLast(1) + "work", predictor.redecode("", typed, taps, typed, AutocorrectMode.AGGRESSIVE))
        // A sentence that already reads well stays.
        val fine = "we can walk to the market after breakfast tomorrow".split(" ")
        assertEquals(null, predictor.redecode("", fine, fine.map { taps(it, "-".repeat(it.length)) }, fine, AutocorrectMode.AGGRESSIVE))
        // Untapped words (pasted, picked) are never re-decided.
        assertEquals(null, predictor.redecode("", typed, typed.map { null }, typed, AutocorrectMode.AGGRESSIVE))
    }

    @Test fun aSkippedKeyPlusASlipShiftsToTheWord() {
        assertEquals("between", fix("betwrn", "----e-"))
        assertEquals("between", fix("bwtwen", "-e----"))
        assertEquals("please", fix("plwse", "--e--"))
    }
}
