package app.tailorkeys

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TypingCoreTest {
    private fun model(dir: File = Files.createTempDirectory("tk-core").toFile(), now: () -> Long = System::currentTimeMillis) =
        Predictor({ File("src/main/assets", it).inputStream() }, dir, now = now).apply { setLayout(SplitTaps.grid) }

    @Test fun commonTyposAndConfidentRealWords() {
        val p = model()
        for ((typed, expected) in listOf("teh" to "the", "recieve" to "receive", "hte" to "the")) {
            p.updateContext(typed, typed)
            assertEquals(typed, expected, p.autocorrect(typed, AutocorrectMode.CONFIDENT))
        }
        for (word in listOf("form", "from", "there")) {
            p.updateContext("i came $word", word)
            assertNull(p.autocorrect(word, AutocorrectMode.CONFIDENT, "came", "i"))
        }
    }
    @Test fun commonTyposAlsoWorkBeforeTheModelIsReady() {
        val p = Predictor({ File("src/main/assets", it).inputStream() }, Files.createTempDirectory("tk-fallback").toFile(), enableModel = false).apply { setLayout(SplitTaps.grid) }
        for ((typed, expected) in listOf("teh" to "the", "recieve" to "receive", "hte" to "the"))
            assertEquals(expected, p.autocorrect(typed, AutocorrectMode.CONFIDENT))
    }
    @Test fun aggressiveUsesStrongContext() {
        val p = model()
        val word = "form"
        p.updateContext("i am from new york and you are form", word)
        assertEquals("from", p.autocorrect(word, AutocorrectMode.AGGRESSIVE, "are", "you"))
    }
    @Test fun aggressiveFixesSeveralNeighbouringKeySlips() {
        for (neural in listOf(true, false)) {
            val p = Predictor({ File("src/main/assets", it).inputStream() }, Files.createTempDirectory("tk-slips").toFile(), enableModel = neural).apply { setLayout(SplitTaps.grid) }
            for ((text, original, fix) in listOf(Triple("watwe", "watwe", "water"), Triple("I need to watwe", "watwe", "water"),
                    Triple("watwe the plabge", "plabge", "plants"))) {
                assertEquals("$neural $text", WordCorrection(original, fix), p.correction(text, AutocorrectMode.AGGRESSIVE))
                assertEquals("$neural $text taps", WordCorrection(original, fix), p.correction(text, AutocorrectMode.AGGRESSIVE, SplitTaps.word(original)))
                assertNull("$neural $text", p.correction(text, AutocorrectMode.CONFIDENT))
            }
            // Several slips only land on common words: rare look-alikes stay as typed.
            for (word in listOf("gotta", "covid")) assertNull("$neural $word", p.correction("I said $word", AutocorrectMode.AGGRESSIVE))
        }
    }
    @Test fun protectedInputsAndLearning() {
        val p = model()
        assertFalse(CorrectionRules.allowed(true, true, false, false))
        assertFalse(CorrectionRules.allowed(true, false, true, false))
        assertFalse(CorrectionRules.allowed(true, false, false, true))
        assertNull(p.autocorrect("teh", AutocorrectMode.CONFIDENT, allowed = false))
        for (word in listOf("teH", "teh2", "TEH")) assertNull(p.autocorrect(word, AutocorrectMode.AGGRESSIVE))
        assertNull(p.autocorrect("teh", AutocorrectMode.OFF))
        p.observe("from")
        assertFalse(p.typedTwice("from"))
        p.observe("from")
        assertTrue(p.typedTwice("from"))
        p.observe("teh")
        assertFalse(p.hasWord("teh"))
        p.observe("teh")
        assertTrue(p.hasWord("teh"))
        assertTrue(p.typedTwice("teh"))
        assertNull(p.autocorrect("teh", AutocorrectMode.AGGRESSIVE))
        p.observe("tailorkeyszz", picked = true)
        assertTrue(p.hasWord("tailorkeyszz"))
    }
    @Test fun lazyDecayForgetAndSaveCannotResurrectWords() {
        val dir = Files.createTempDirectory("tk-decay").toFile()
        var time = 1_000_000L
        val p = model(dir) { time }
        repeat(8) { p.observe("tailorkeyszz", "hello", "say", picked = true) }
        p.save()!!.get()
        time += 30L * 24 * 60 * 60 * 1000
        val reloaded = model(dir) { time }
        reloaded.save()!!.get()
        assertTrue(File(dir, "user_words.txt").readText().contains("tailorkeyszz\t4"))
        PersonalStore.forget(dir, "en", "tailorkeyszz")
        p.observe("tailorkeyszz", picked = true)
        p.save()!!.get()
        assertFalse(File(dir, "user_words.txt").readText().contains("tailorkeyszz"))
        assertFalse(File(dir, "user_bigrams.txt").readText().contains("tailorkeyszz"))
        assertFalse(File(dir, "user_trigrams.txt").readText().contains("tailorkeyszz"))
    }
    @Test fun correctionUndoRestoresSeparatorAndCancelsLearning() {
        val p = model()
        p.updateContext("teh", "teh")
        val fix = p.autocorrect("teh", AutocorrectMode.CONFIDENT)!!
        var learned = 0
        for (separator in listOf(" ", ".", ",")) {
            val edit = ReversibleCommit("teh", fix + separator, separator, fix + separator, "next", true) { learned++ }
            assertEquals(0, learned)
            assertNull(edit.undo("different", "next", false))
            assertNull(edit.undo(fix + separator, "next", true))
            assertEquals("teh$separator", edit.undo(fix + separator, "next", false))
            edit.keep(fix + separator, "next", false, true)
            assertEquals(0, learned)
        }
        val kept = ReversibleCommit("teh", "the ", " ", "the ", "") { learned++ }
        kept.keep("the ", "", false, true)
        kept.keep("the ", "", false, true)
        assertEquals(1, learned)
        val paused = ReversibleCommit("teh", "the ", " ", "the ", "") { learned++ }
        paused.keep("the ", "", false, false)
        assertEquals(1, learned)
    }
    @Test fun tidyMapsCursorAcrossChosenFixes() {
        val fixes = listOf(TidyFix(0, 3, "hte", "the"), TidyFix(5, 7, "  ", " "), TidyFix(9, 16, "recieve", "receive"))
        assertEquals(7, TidyText.cursor(8, fixes))
        assertEquals(0, TidyText.cursor(0, fixes))
        assertEquals(11, TidyText.cursor(12, fixes))
        val edit = ReversibleCommit("hte.  recieve", "the. receive", "", "the. ", "receive", insertedAfter = 7, originalTail = 7) {}
        assertEquals("hte.  recieve", edit.undo("the. ", "receive", false))
    }
    @Test fun learnedWordsAreCappedByRecency() {
        val dir = Files.createTempDirectory("tk-cap").toFile()
        var time = 1L
        val p = model(dir) { time++ }
        fun name(i: Int) = "zorb" + ('a' + i / 676) + ('a' + i / 26 % 26) + ('a' + i % 26)
        for (i in 0..5001) p.observe(name(i), picked = true)
        assertFalse(p.hasWord(name(0)))
        assertTrue(p.hasWord(name(5001)))
        p.save()!!.get()
        assertEquals(5000, PersonalStore.listWords(dir).size)
    }
    @Test fun tidyKeepsAddressesAndOnlyUsesRecentSentences() {
        val source = "visit https://example.com and teh@example.com. teh."
        val fixes = TidyText.propose(source) { word, _ -> if (word == "teh") "the" else null }
        assertEquals("Visit https://example.com and teh@example.com. The.", TidyText.apply(source, fixes))
        val before = "Old text. First sentence. Second sentence is teh"
        assertEquals(" First sentence. Second sentence is teh", before.substring(TidyText.windowStart(before)))
    }
    @Test fun tidyProposalsAreSelectableAndPreserveOtherText() {
        val source = "teh cat.  hello there"
        val fixes = TidyText.propose(source) { word, _ -> if (word == "teh") "the" else null }
        assertEquals("The cat. Hello there", TidyText.apply(source, fixes))
        assertEquals("teh cat.  Hello there", TidyText.apply(source, fixes.filter { it.original == "hello" }))
        assertEquals(source, TidyText.apply(source, emptyList()))
    }
}
