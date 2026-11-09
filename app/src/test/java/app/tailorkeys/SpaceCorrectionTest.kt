package app.tailorkeys

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SpaceCorrectionTest {
    private fun model(dir: File = Files.createTempDirectory("tk-space").toFile(), neural: Boolean = true) =
        Predictor({ File("src/main/assets", it).inputStream() }, dir, enableModel = neural).apply { setLayout(SplitTaps.grid) }
    @Test fun missingSpacesBeatKeepingAndSingleWordFixes() {
        for (neural in listOf(true, false)) for (mode in listOf(AutocorrectMode.CONFIDENT, AutocorrectMode.AGGRESSIVE)) {
            val p = model(neural = neural)
            for ((word, fix) in listOf("thequick" to "the quick", "ofthe" to "of the", "inthe" to "in the")) {
                assertEquals("$neural $mode $word", WordCorrection(word, fix), p.correction(word, mode))
            }
            assertNull(p.correction("keyboard", mode))
        }
    }
    @Test fun accidentalSpacesNeedAggressiveAndStrongEvidence() {
        val p = model()
        for ((text, original, fix) in listOf(Triple("press the key board", "key board", "keyboard"), Triple("th e", "th e", "the"))) {
            assertNull(p.correction(text, AutocorrectMode.CONFIDENT))
            assertEquals(text, WordCorrection(original, fix), p.correction(text, AutocorrectMode.AGGRESSIVE))
        }
        for (text in listOf("a lot", "hand the form in to", "log in to", "the key  board", "th\ne"))
            assertNull(text, p.correction(text, AutocorrectMode.AGGRESSIVE))
    }
    @Test fun spaceEditsUndoOriginalSpanAndSeparator() {
        for ((original, fix) in listOf("thequick" to "the quick", "key board" to "keyboard", "th e" to "the")) {
            for (separator in listOf(" ", ".")) {
                val edit = ReversibleCommit(original, fix + separator, separator, fix + separator, "", correction = true) {}
                assertEquals(original + separator, edit.undo(fix + separator, "", false))
            }
        }
    }
    @Test fun shortRealWordsNeverChangeEvenWithStrongContext() {
        val p = model()
        for (word in listOf("as", "is", "it", "of", "on", "to", "in", "an", "am", "or", "me", "my", "we", "he")) {
            for (mode in listOf(AutocorrectMode.CONFIDENT, AutocorrectMode.AGGRESSIVE)) {
                p.updateContext("i want to $word", word)
                assertNull("$mode $word", p.autocorrect(word, mode, "to", "want"))
            }
        }
        assertEquals(WordCorrection("i", "I"), p.correction("i", AutocorrectMode.CONFIDENT))
        assertEquals(WordCorrection("i", "I"), p.correction("hello. i", AutocorrectMode.AGGRESSIVE))
        assertNull(p.correction("you and i", AutocorrectMode.AGGRESSIVE))
        for (word in listOf("th", "zz", "tge", "tht", "abc")) assertNull(word, p.correction(word, AutocorrectMode.AGGRESSIVE))
        assertEquals(WordCorrection("teh", "the"), p.correction("teh", AutocorrectMode.CONFIDENT))
    }
    @Test fun retypingIsTrackedWithoutSpatialDataAndUndoSyncIsNotRetyping() {
        val taps = ComposingTaps()
        taps.sync("teh")
        assertFalse(taps.hasInput("teh"))
        taps.append("teh", "x", null)
        assertTrue(taps.hasInput("tehx"))
        assertFalse(taps.hasTaps("tehx"))
        taps.backspace()
        assertFalse(taps.hasInput("teh"))
        taps.clear()
        for (ch in "teh") taps.append(taps.word, ch.toString(), null)
        assertTrue(taps.hasInput("teh"))
        val p = model()
        p.recordRejection("teh", "the")?.get()
        assertNull(p.correction("teh", AutocorrectMode.CONFIDENT, taps, userTyped = taps.hasInput("teh")))
    }
    @Test fun shortAdjacentSlipRequiresBothSpatialAndStrongLanguageEvidence() {
        fun trace() = ComposingTaps().also { taps ->
            for ((index, ch) in "tge".withIndex()) {
                val intended = "the"[index]
                val point = SplitTaps.centres.getValue(intended)
                val typed = SplitTaps.centres.getValue(ch)
                val x = (point.first + typed.first) / 2
                val y = (point.second + typed.second) / 2
                taps.append(taps.word, ch.toString(), SplitTaps.distances(x, y))
            }
        }
        val p = model()
        p.updateContext("he went to tge", "tge")
        assertEquals("the", p.autocorrect("tge", AutocorrectMode.CONFIDENT, "to", "went", trace()))
        assertNull(p.autocorrect("tge", AutocorrectMode.AGGRESSIVE, "to", "went"))
        val fallback = model(neural = false)
        assertNull(fallback.autocorrect("tge", AutocorrectMode.AGGRESSIVE, "to", "went", trace()))
        assertNull(p.autocorrect("to", AutocorrectMode.AGGRESSIVE, "to", "went", trace()))
    }
    @Test fun phraseScoringDoesNotAdvanceTheTypingModel() {
        val p = model()
        p.updateContext("press the key board", "board")
        val context = p.english!!
        val steps = context.steps; val softmaxes = context.softmaxes
        val before = context.withScores { it!!.probabilities.copyOf() }
        val actual = context.withScores { scores ->
            val s = scores!!
            val rankThe = p.gestureWords().indexOf("the")
            val rankQuick = p.gestureWords().indexOf("quick")
            val parent = s.preceding("the", rankThe)
            val following = s.following("the", "quick", rankQuick)
            parent to following
        }
        val model = EnglishModel.load { File("src/main/assets", it).inputStream() }!!
        val state = model.initialState()
        val probabilities = FloatArray(model.vocabularySize)
        for (word in EnglishTokens.context("press the ")) model.step(state, model.idOf(word))
        model.logProbs(state, probabilities)
        assertEquals(probabilities[model.idOf("the")].toDouble(), actual.first, 1e-5)
        model.step(state, model.idOf("key")); model.step(state, model.idOf("the")); model.logProbs(state, probabilities)
        assertEquals(probabilities[model.idOf("quick")].toDouble(), actual.second, 1e-5)
        assertArrayEquals(before, context.withScores { it!!.probabilities }, 0f)
        assertEquals(steps, context.steps); assertEquals(softmaxes, context.softmaxes)
    }
    @Test fun confidentSplitsAndJoinFieldGuards() {
        val p = model()
        assertNull(p.correction("thequick", AutocorrectMode.OFF))
        assertNull(p.correction("thequick", AutocorrectMode.AGGRESSIVE, allowed = false))
        assertNull(p.correction("th e", AutocorrectMode.AGGRESSIVE, allowJoin = false))
        assertNull(p.correction("alot", AutocorrectMode.CONFIDENT))
        assertNull(p.correction("theQuick", AutocorrectMode.AGGRESSIVE))
        assertNull(p.correction("thequick2", AutocorrectMode.AGGRESSIVE))
    }
    @Test fun repeatedRejectionsPersistAndForgetClearsThem() {
        val dir = Files.createTempDirectory("tk-rejections").toFile()
        val p = model(dir)
        p.recordRejection("teh", "the")?.get()
        assertNotNull(p.correction("teh", AutocorrectMode.CONFIDENT))
        p.recordRejection("teh", "the")?.get()
        assertNull(p.correction("teh", AutocorrectMode.CONFIDENT))
        assertNull(model(dir).correction("teh", AutocorrectMode.AGGRESSIVE))
        assertEquals(1, PersonalStore.listRejected(dir).size)
        PersonalStore.forget(dir)
        assertNotNull(model(dir).correction("teh", AutocorrectMode.CONFIDENT))
        assertTrue(PersonalStore.listRejected(dir).isEmpty())
    }
    @Test fun privateFieldUndosCountThereOnlyAndAreNeverSaved() {
        val dir = Files.createTempDirectory("tk-private").toFile()
        val p = model(dir)
        p.recordRejection("teh", "the", persist = false)
        p.recordRejection("teh", "the", persist = false)
        p.recordKept("recieve", "receive", persist = false)
        p.recordRejection("wierd", "weird", persist = false)
        p.noteTyped("wierd", persist = false)
        // Undone twice, or undone and typed again, in this private field: not redone here.
        assertNull(p.correction("teh", AutocorrectMode.CONFIDENT))
        assertNotEquals("weird", p.correction("wierd", AutocorrectMode.CONFIDENT)?.replacement)
        PersonalStore.submit {}.get()
        assertTrue(PersonalStore.listRejected(dir).isEmpty())
        // A later save from an ordinary field writes its own undo and none of the private ones.
        p.recordRejection("freind", "friend")?.get()
        PersonalStore.submit {}.get()
        val saved = dir.listFiles().orEmpty().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertTrue(saved.contains("freind"))
        for (word in listOf("teh", "recieve", "wierd")) assertFalse(word, saved.contains(word))
        // The next field forgets them, and a fresh start never knew them.
        p.forgetPrivate()
        assertEquals(WordCorrection("teh", "the"), p.correction("teh", AutocorrectMode.CONFIDENT))
        assertEquals(WordCorrection("teh", "the"), model(dir).correction("teh", AutocorrectMode.CONFIDENT))
    }
    @Test fun undoThenRetypeBlocksTheExactPairAndIndividualRemovalRestoresIt() {
        val dir = Files.createTempDirectory("tk-retype").toFile()
        val p = model(dir)
        p.recordRejection("recieve", "receive")?.get()
        p.noteTyped("recieve")
        PersonalStore.submit {}.get()
        assertTrue(PersonalStore.listRejected(dir).any { it.original == "recieve" })
        assertNotEquals("receive", model(dir).correction("recieve", AutocorrectMode.CONFIDENT)?.replacement)
        PersonalStore.allowCorrection(dir, "en", "recieve", "receive")
        assertEquals("receive", model(dir).correction("recieve", AutocorrectMode.CONFIDENT)?.replacement)
    }
    @Test fun rejectionsArePerLanguageAndDoNotDecay() {
        val dir = Files.createTempDirectory("tk-language-rejections").toFile()
        val en = model(dir)
        en.recordRejection("teh", "the")?.get(); en.recordRejection("teh", "the")?.get()
        val de = Predictor({ File("src/main/assets", it).inputStream() }, dir, "de", enableModel = false)
        de.recordRejection("derr", "der")?.get(); de.recordRejection("derr", "der")?.get()
        File(dir, "user_learning.txt").writeText("@decay\t0\n")
        assertNull(model(dir).correction("teh", AutocorrectMode.CONFIDENT))
        assertEquals(setOf("en", "de"), PersonalStore.listRejected(dir).map { it.language }.toSet())
        PersonalStore.forget(dir, "en")
        assertEquals(listOf("de"), PersonalStore.listRejected(dir).map { it.language })
    }
    @Test fun keepingACorrectionRecordsPreferenceOnceEvenWhenVocabularyLearningIsPaused() {
        val history = CorrectionHistory()
        val edit = ReversibleCommit("teh", "the ", " ", "the ", "", true,
            onKept = { history.kept("teh", "the") }) { fail("paused vocabulary learned") }
        edit.keep("the ", "", false, false)
        edit.keep("the ", "", false, false)
        assertEquals("teh\tthe\t0\t1\n", history.serialize())
    }
    @Test fun frequentlyUndoneCandidatesGetAPenalty() {
        val p = model()
        val initial = p.rejectionPenalty("the")
        for (word in listOf("teh", "hte", "thw", "tge")) p.recordRejection(word, "the")?.get()
        assertTrue(p.rejectionPenalty("the") > initial + 1.0)
        repeat(20) { p.recordKept("thw", "the") }
        assertTrue(p.rejectionPenalty("the") < 1.0)
    }
}
