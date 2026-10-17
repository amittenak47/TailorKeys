package app.tailorkeys

import org.junit.Assert.*
import org.junit.Test

class ChordTapTest {
    @Test fun singleLetterKeepsItsPointWhenChordingIsEnabled() {
        val touches = ChordTouches<Int>()
        touches.down(1, 'a', 0, 1f, 1.5f)
        touches.update(1, 's', false, 1.1f, 1.5f)
        val completion = touches.release(1).completions.single()
        assertFalse(completion.candidate)
        assertEquals('s', completion.entries.single().letter)
        assertEquals(1.1f, completion.entries.single().x)
        assertEquals(1.5f, completion.entries.single().y)
    }

    @Test fun rolloverKeepsTheEarlierLetterPoint() {
        val touches = ChordTouches<Int>()
        touches.down(1, 'a', 0, 1f, 1.5f)
        val completion = touches.prepareDown(100, true).completions.single()
        assertFalse(completion.candidate)
        assertEquals(1f, completion.entries.single().x)
        assertEquals(1.5f, completion.entries.single().y)
    }
}
