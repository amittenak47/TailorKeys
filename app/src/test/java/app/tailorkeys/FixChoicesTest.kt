package app.tailorkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A changed word's dropdown rows ([FixChoices.of]): what is listed, in what case, in what order, with what share. */
class FixChoicesTest {
    private fun scored(vararg c: Pair<String, Float>, capital: Float = -3f) =
        StudentCorrector.Scored(c.map { it.first }, c.map { it.second }.toFloatArray(), FloatArray(c.size) { capital }, 0)

    private fun rows(list: List<FixChoice>) = list.map { "${it.word} ${FixChoices.percent(it.probability)}" + if (it.typed) " typed" else "" }

    @Test fun listsTheFixTheTypedLettersAndTheLikeliestOthers() {
        // At the space bar the letters as typed are the kept candidate (index 0).
        val s = scored("memvers" to .06f, "members" to .9f, "memories" to .02f, "mergers" to .016f, "meters" to .005f)
        assertEquals(listOf("members 90%", "memvers 6% typed", "memories 2%", "mergers 2%"), rows(FixChoices.of(s, "members", "memvers", false)))
    }

    @Test fun keepsTheTypedLettersEvenWhenUnlikely() {
        val s = scored("lste" to .001f, "late" to .97f, "lane" to .02f, "list" to .009f)
        val r = FixChoices.of(s, "late", "lste", false)
        assertEquals(listOf("late 97%", "lane 2%", "lste <1% typed"), rows(r))
        assertTrue(r.size <= FixChoices.ROWS)
    }

    @Test fun writesOtherWordsInTheCaseOfTheWordShown() {
        val s = scored("teh" to .02f, "the" to .95f, "ten" to .03f)
        assertEquals(listOf("The 95%", "Ten 3%", "teh 2% typed"), rows(FixChoices.of(s, "The", "teh", true)))
        assertEquals(listOf("THE 95%", "TEN 3%", "TEH 2% typed"), rows(FixChoices.of(s, "THE", "TEH", false)))
    }

    @Test fun splitsACapitalOnlyChangeByTheCapital() {
        // tiptoe written Tiptoe mid-sentence with a capital logit of 1.6: 83% capital.
        val s = scored("tiptoe" to 1f, capital = 1.6f)
        assertEquals(listOf("Tiptoe 83%", "tiptoe 17% typed"), rows(FixChoices.of(s, "Tiptoe", "tiptoe", false)))
        // At a sentence start the capital is always written.
        assertEquals(listOf("Hello 100%", "hello <1% typed"), rows(FixChoices.of(scored("hello" to 1f), "Hello", "hello", true)))
    }

    @Test fun aRecheckBackToTheTypedLettersIsOneRow() {
        // The recheck's kept candidate is the word it changes (late); the letters as typed come as an extra one.
        val s = scored("late" to .3f, "lste" to .6f, "lane" to .1f)
        assertEquals(listOf("lste 60% typed", "late 30%", "lane 10%"), rows(FixChoices.of(s, "lste", "lste", false)))
    }

    @Test fun percents() {
        assertEquals("<1%", FixChoices.percent(.004f))
        assertEquals("1%", FixChoices.percent(.006f))
        assertEquals("100%", FixChoices.percent(.999f))
    }
}
