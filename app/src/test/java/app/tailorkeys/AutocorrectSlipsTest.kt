package app.tailorkeys

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AutocorrectSlipsTest {
    private val predictor = Predictor({ File("src/main/assets", it).inputStream() },
        Files.createTempDirectory("tk-slips2").toFile()).apply { setLayout(SplitTaps.grid) }

    /** Each typed letter leans 45% toward the key that was meant ('-' is a clean tap). */
    private fun taps(typed: String, meant: String) = ComposingTaps().also { t ->
        for ((i, ch) in typed.withIndex()) {
            val (x, y) = SplitTaps.centres.getValue(ch.lowercaseChar())
            val (tx, ty) = meant.getOrNull(i)?.takeIf { it != '-' }?.let(SplitTaps.centres::getValue) ?: (x to y)
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

    @Test fun aSkippedKeyPlusASlipShiftsToTheWord() {
        assertEquals("between", fix("betwrn", "----e-"))
        assertEquals("between", fix("bwtwen", "-e----"))
        assertEquals("please", fix("plwse", "--e--"))
    }
}
