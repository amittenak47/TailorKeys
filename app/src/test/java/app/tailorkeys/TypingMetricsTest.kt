package app.tailorkeys

import app.tailorkeys.settings.*
import org.junit.Assert.*
import org.junit.Test

class TypingMetricsTest {
    @Test fun wpmUsesFiveCharactersAndElapsedMinutes() {
        assertEquals(60.0, TypingSession.wpm(300, 60_000), 0.0)
        assertEquals(60.0, TypingSession.wpm(75, 15_000), 0.0)
        assertEquals(0.0, TypingSession.wpm(75, 0), 0.0)
    }
    @Test fun freeModeCountsOnlyExplicitDeletionsAsCorrections() {
        val s = TypingSession(TypingMode.FREE)
        s.update("hellp", 1_000); s.update("hello", 2_000)
        assertEquals(6, s.inserted)
        assertEquals(0, s.metrics(3_000).mistakes)
        assertEquals(1.0, s.metrics(3_000).accuracy, .0001)
        s.update("hel", 4_000)
        assertEquals(2, s.metrics(5_000).mistakes)
        assertEquals(4.0 / 6, s.metrics(5_000).accuracy, .0001)
    }
    @Test fun testAccuracyComparesCurrentTextAndMistakesIncludeCorrectedErrors() {
        val s = TypingSession(TypingMode.TEST, "hello world")
        s.update("hellp", 1_000)
        assertEquals(.8, s.metrics(2_000).accuracy, .0001)
        s.update("hello", 2_000)
        assertEquals(1.0, s.metrics(3_000).accuracy, 0.0)
        assertEquals(1, s.metrics(3_000).mistakes)
        s.update("hellp", 3_000)
        assertEquals(2, s.metrics(4_000).mistakes)
        s.update("hello world", 5_000)
        assertTrue(s.complete)
        assertEquals(4_000, s.metrics(8_000).elapsed)
        assertEquals(33.0, s.metrics(8_000).wpm, .0001)
    }
    @Test fun replacementsInMiddleAndMultiCharacterCommitsCountOnlyTheEdit() {
        val s = TypingSession(TypingMode.FREE)
        s.update("one bat", 0); s.update("one cat", 1_000)
        assertEquals(8, s.inserted)
        assertEquals(0, s.corrections)
        assertFalse(s.samples.last().mistake)
    }
    @Test fun autocorrectSuggestionSwipeAndUndoAreNormalTyping() {
        val s = TypingSession(TypingMode.FREE)
        listOf("teh", "the ", "teh ", "the ", "the keyboard ", "the keyboard swiping ").forEachIndexed { i, text -> s.update(text, i * 1_000L) }
        assertEquals(0, s.metrics(6_000).mistakes)
        assertEquals(1.0, s.metrics(6_000).accuracy, 0.0)
        assertTrue(s.samples.none { it.mistake })
        s.update("the keyboard swipin ", 7_000) // Backspace within the word, retaining a suffix.
        assertEquals(1, s.metrics(7_000).mistakes)
        val punctuation = TypingSession(TypingMode.FREE)
        punctuation.update("dont ", 0); punctuation.update("don't ", 1_000); punctuation.update("dont ", 2_000)
        assertEquals(0, punctuation.corrections)
    }
    @Test fun peakWaitsForFiveSecondsAndTenCharactersAndUsesARollingWindow() {
        val commit = TypingSession(TypingMode.FREE)
        commit.startTyping(0); commit.update("keyboardword", 100)
        assertEquals(0.0, commit.peakWpm, 0.0)
        commit.sample(4_999); assertEquals(0.0, commit.peakWpm, 0.0)
        commit.sample(5_000); assertEquals(28.8, commit.peakWpm, .0001)
        val slow = TypingSession(TypingMode.FREE)
        slow.update("hello", 0); slow.sample(10_000)
        assertEquals(0.0, slow.peakWpm, 0.0)
        slow.update("hello worl", 20_000)
        assertEquals(6.0, slow.peakWpm, .0001) // Expand beyond five seconds to reach ten characters.
        slow.sample(60_000); slow.update("hello worl more text", 65_000)
        assertEquals(24.0, slow.samples.last().wpm, .0001) // Ten new characters in five seconds, after an idle period.
        assertEquals(24.0, slow.peakWpm, .0001)
        repeat(700) { slow.sample(70_000 + it * 1_000L) }
        assertEquals(24.0, slow.peakWpm, .0001) // Capping chart history does not erase the peak.
    }
    @Test fun emptyAndSelectionOnlyChangesDoNotStartTimer() {
        val s = TypingSession(TypingMode.FREE)
        s.update("", 5_000)
        assertNull(s.startedAt)
        assertEquals(1.0, s.metrics(10_000).accuracy, 0.0)
        s.update("a", 10_000); s.update("a", 20_000)
        assertEquals(1, s.inserted)
    }
    @Test fun imeCompositionStartsTimeBeforeTheWordIsCommitted() {
        val s = TypingSession(TypingMode.FREE)
        s.startTyping(1_000)
        s.startTyping(2_000)
        s.update("hello", 3_000)
        assertEquals(2_000L, s.metrics(3_000).elapsed)
        assertEquals(30.0, s.metrics(3_000).wpm, 0.0)
    }
    @Test fun onlyCompletedTimedSentencesProduceOneResult() {
        val free = TypingSession(TypingMode.FREE, "hello world")
        free.update("deliberate typo", 1_000)
        assertNull(free.takeResult(10_000)) // switching mode, clearing or leaving
        val test = TypingSession(TypingMode.TEST, "hello world")
        test.update("hello", 1_000)
        assertNull(test.takeResult(2_000))
        test.update("hello world", 5_000)
        assertEquals(33.0, test.takeResult(9_000)!!.wpm, 0.0)
        assertNull(test.takeResult(10_000))
        val paste = TypingSession(TypingMode.TEST, "hello")
        paste.update("hello", 1_000)
        assertNull(paste.takeResult(2_000))
    }
    @Test fun sentenceProgressStartsEmptyAndIsBounded() {
        val s = TypingSession(TypingMode.TEST, "hello")
        assertEquals(0f, s.progress, 0f)
        s.update("he", 0)
        assertEquals(.4f, s.progress, .0001f)
        s.update("hello extra", 1_000)
        assertEquals(1f, s.progress, 0f)
        assertEquals(0f, TypingSession(TypingMode.TEST).progress, 0f)
        assertEquals(0f, TypingSession(TypingMode.FREE).progress, 0f)
    }
    @Test fun sentenceBankHasFortyUniqueOriginalShortSentences() {
        assertEquals(40, SentenceBank.sentences.size)
        assertEquals(40, SentenceBank.sentences.map { it.lowercase() }.toSet().size)
        SentenceBank.sentences.forEach { sentence -> assertTrue("Wrong word count: $sentence", sentence.split(Regex("\\s+")).size in 8..14) }
    }
}
