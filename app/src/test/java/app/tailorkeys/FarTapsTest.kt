package app.tailorkeys

import app.tailorkeys.layout.GridKey
import app.tailorkeys.layout.Keys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FarTapsTest {
    private fun key(vararg keys: String) = GridKey(0, 0, 0, 1, keys.toMutableList())

    @Test fun lettersAndTheSpaceBarCount() {
        assertTrue(FarTaps.counts(key("q")))
        assertTrue(FarTaps.counts(key(Keys.SPACE)))
    }

    @Test fun keysThatDoHarmByAccidentNeverCount() {
        for (code in listOf(Keys.ENTER, Keys.BACKSPACE, Keys.SHIFT)) assertFalse(code, FarTaps.counts(key(code)))
    }

    @Test fun punctuationNeverCounts() {
        // A stray period ends a word and capitalizes the next: past the keys, a letter is the safer guess.
        for (mark in listOf(".", ",", "'")) assertFalse(mark, FarTaps.counts(key(mark)))
    }

    @Test fun aSwipeKeyNeverCounts() {
        assertFalse(FarTaps.counts(key("a", "b")))
    }

    @Test fun aWheelTapMidTypingIsASlipAndAfterAPauseAChoice() {
        assertTrue(FarTaps.midTyping(120))
        assertFalse(FarTaps.midTyping(FarTaps.TYPING_MS))
        assertFalse(FarTaps.midTyping(5_000))
    }

    @Test fun anEmptyCellNeverCounts() {
        assertFalse(FarTaps.counts(key(Keys.GAP)))
    }
}
