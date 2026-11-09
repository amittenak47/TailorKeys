package app.tailorkeys

import app.tailorkeys.layout.GridKey

/**
 * A tap past every key's reach or in the gap between split halves, from a hand that has wandered off the keys while
 * typing without looking. Dropping it loses a letter the model could have read, so it counts as the nearest letter or
 * space bar. Enter, backspace, shift and the rest stay out: hit by accident they do more harm than a wrong letter, which
 * the model can repair. The controls (grips, touch bars, cursor zones) keep their touches, and so do the suggestion
 * wheels unless the tap comes in the middle of typing.
 */
internal object FarTaps {
    /** From a key's centre, in key sizes. Past this a touch is a resting thumb or palm, not a tap. */
    const val REACH = 2.5f

    /** A wheel tap this soon after a typed key is a hand that wandered up. A pause first means a suggestion was chosen. */
    const val TYPING_MS = 250L

    fun counts(key: GridKey) = key.isSpace || !key.isStack && key.keys[0].let { it.length == 1 && it[0].isLetter() }

    fun midTyping(sinceKey: Long) = sinceKey in 0 until TYPING_MS
}
