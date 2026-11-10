package app.tailorkeys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CorrectionMarksTest {
    @Test
    fun rowsShowTheWordShownThenTheLettersAsTypedThenOthers() {
        val rows = CorrectionMarks.choices("teh", "the", listOf("the", "tea", "ten", "tech"))
        assertEquals(listOf("the", "teh", "tea", "ten"), rows.map { it.word })
        assertEquals(listOf(false, true, false, false), rows.map { it.typed })
    }

    @Test
    fun rowsNeverExceedFourAndSkipRepeatsInAnyCase() {
        val rows = CorrectionMarks.choices("teh", "the", listOf("THE", "Teh", "tea", "ten", "toe"))
        assertEquals(CorrectionMarks.ROWS, rows.size)
        assertEquals(listOf("the", "teh", "tea", "ten"), rows.map { it.word })
    }

    @Test
    fun otherWordsFollowTheCaseOfTheWordShown() {
        assertEquals(listOf("The", "Teh", "Tea"), CorrectionMarks.choices("Teh", "The", listOf("tea")).map { it.word })
        assertEquals(listOf("THE", "TEH", "TEA"), CorrectionMarks.choices("TEH", "THE", listOf("tea")).map { it.word })
    }

    @Test
    fun theLettersAsTypedAreThereWithNoOtherCandidates() {
        val rows = CorrectionMarks.choices("adviced", "advised", emptyList())
        assertEquals(listOf("advised", "adviced"), rows.map { it.word })
        assertTrue(rows[1].typed)
    }

    @Test
    fun aChangedWordIsFoundByItselfAndTheTextBeforeIt() {
        val marks = CorrectionMarks()
        marks.mark("teh", "the", listOf("tea"), "I saw ")
        val found = marks.find("the", "Yesterday I saw ")
        assertNotNull(found)
        assertEquals("teh", found!!.typed)
        assertNull(marks.find("the", "Yesterday we saw "))
        assertNull(marks.find("tea", "Yesterday I saw "))
    }

    @Test
    fun aWordAtTheStartOfTheFieldNeedsNothingBeforeIt() {
        val marks = CorrectionMarks()
        marks.mark("Teh", "The", emptyList(), "")
        assertNotNull(marks.find("The", ""))
        assertNull(marks.find("The", "And "))
    }

    @Test
    fun onlyTheLastSixteenCharactersBeforeTheWordIdentifyIt() {
        val marks = CorrectionMarks()
        marks.mark("teh", "the", emptyList(), "a very long sentence comes before ")
        assertNotNull(marks.find("the", "something else, sentence comes before "))
    }

    @Test
    fun theSameWordInTheSamePlaceIsMarkedOnce() {
        val marks = CorrectionMarks()
        marks.mark("teh", "the", emptyList(), "I saw ")
        marks.mark("hte", "the", emptyList(), "I saw ")
        assertEquals(1, marks.size)
        assertEquals("hte", marks.find("the", "I saw ")!!.typed)
    }

    @Test
    fun noMarkWhenNothingChanged() {
        val marks = CorrectionMarks()
        marks.mark("the", "the", emptyList(), "I saw ")
        assertEquals(0, marks.size)
    }

    @Test
    fun theOldestMarksGoPastTheLimit() {
        val marks = CorrectionMarks()
        for (i in 0 until CorrectionMarks.MAX + 5) marks.mark("w$i", "word$i", emptyList(), "before $i ")
        assertEquals(CorrectionMarks.MAX, marks.size)
        assertNull(marks.find("word0", "before 0 "))
        assertNotNull(marks.find("word${CorrectionMarks.MAX + 4}", "before ${CorrectionMarks.MAX + 4} "))
    }

    @Test
    fun aPickedWordIsFoundUnderItsNewSpelling() {
        val marks = CorrectionMarks()
        marks.mark("castt", "cast", emptyList(), "I ")
        val m = marks.find("cast", "I ")!!
        m.shown = "castt"; m.picked = true
        assertNotNull(marks.find("castt", "I "))
        assertNull(marks.find("cast", "I "))
    }

    @Test
    fun aCursorMoveWithTheSameTextIsATap() {
        val a = SeenText("I saw the cat", 0, 13, 13, true)
        val b = SeenText("I saw the cat", 0, 8, 8, true)
        assertTrue(a.sameText(b))
    }

    @Test
    fun aLetterTypedAtTheEndIsNotATap() {
        val a = SeenText("I saw the cat", 0, 13, 13, true)
        val b = SeenText("I saw the cats", 0, 14, 14, true)
        assertFalse(a.sameText(b))
    }

    @Test
    fun windowsThatOverlapCompareOnlyWhereBothReach() {
        val a = SeenText("abcdefgh", 10, 18, 18, true)
        val b = SeenText("cdefgh", 12, 14, 14, true)
        assertTrue(a.sameText(b))
        val c = SeenText("cdXfgh", 12, 14, 14, true)
        assertFalse(a.sameText(c))
    }
}
