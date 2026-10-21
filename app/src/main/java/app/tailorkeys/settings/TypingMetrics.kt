package app.tailorkeys.settings

import app.tailorkeys.TypingResult

internal enum class TypingMode { FREE, TEST }
internal data class SpeedSample(val elapsed: Long, val wpm: Double, val mistake: Boolean)
internal data class TypingMetrics(val wpm: Double, val accuracy: Double, val mistakes: Int, val elapsed: Long)

/** Counts edits to the committed text, including replacements and multi-character IME commits. */
internal class TypingSession(val mode: TypingMode, val sentence: String = "") {
    var text = ""; private set
    var inserted = 0; private set
    var corrections = 0; private set
    var startedAt: Long? = null; private set
    var finishedAt: Long? = null; private set
    private val wrongPositions = mutableSetOf<Int>()
    private var wrongKeystrokes = 0
    private val history = ArrayList<SpeedSample>()
    val samples: List<SpeedSample> get() = history
    val complete get() = mode == TypingMode.TEST && text.length >= sentence.length && sentence.isNotEmpty()
    val slips: Set<Int> get() = wrongPositions
    val progress: Float get() = if (mode == TypingMode.TEST && sentence.isNotEmpty())
        (text.length.toFloat() / sentence.length).coerceIn(0f, 1f) else 0f
    private var recorded = false

    /** Free typing and abandoned sentences never contribute to a layout's average. */
    fun takeResult(at: Long): TypingResult? {
        val end = finishedAt ?: return null
        if (mode != TypingMode.TEST || !complete || recorded || metrics(end).elapsed <= 0) return null
        recorded = true
        return metrics(end).let { TypingResult(it.wpm, it.accuracy, at) }
    }

    fun startTyping(now: Long) { if (startedAt == null) startedAt = now }

    fun update(next: String, now: Long) {
        if (finishedAt != null || next == text) return
        if (next.isNotEmpty()) startTyping(now)
        var prefix = 0
        while (prefix < minOf(text.length, next.length) && text[prefix] == next[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(text.length, next.length) - prefix && text[text.lastIndex - suffix] == next[next.lastIndex - suffix]) suffix++
        val removed = text.length - prefix - suffix
        val added = next.length - prefix - suffix
        corrections += removed
        inserted += added
        var mistake = removed > 0
        if (mode == TypingMode.TEST) for (i in prefix until next.length - suffix) {
            if (i >= sentence.length || !next[i].equals(sentence[i], ignoreCase = true)) {
                wrongPositions += i
                wrongKeystrokes++
                mistake = true
            }
        }
        text = next
        if (complete) finishedAt = now
        sample(now, mistake)
    }

    fun metrics(now: Long): TypingMetrics {
        val elapsed = startedAt?.let { ((finishedAt ?: now) - it).coerceAtLeast(0) } ?: 0
        val wrong = if (mode == TypingMode.TEST) text.indices.count { it >= sentence.length || !text[it].equals(sentence[it], true) } else 0
        val accuracy = if (mode == TypingMode.TEST) {
            if (text.isEmpty()) 1.0 else (text.length - wrong).toDouble() / text.length
        } else if (inserted == 0) 1.0 else (inserted - corrections).coerceAtLeast(0).toDouble() / inserted
        return TypingMetrics(wpm(text.length, elapsed), accuracy, if (mode == TypingMode.TEST) wrongKeystrokes else corrections, elapsed)
    }

    fun sample(now: Long, mistake: Boolean = false) {
        if (startedAt == null) return
        val metrics = metrics(now)
        // Preserve mistake dots; cap time samples for a long free-typing session.
        if (history.size >= 600) history.removeAt(0)
        history += SpeedSample(metrics.elapsed, metrics.wpm, mistake)
    }

    companion object {
        fun wpm(characters: Int, elapsedMillis: Long) = if (elapsedMillis <= 0) 0.0 else characters.coerceAtLeast(0) * 12_000.0 / elapsedMillis
    }
}

internal object SentenceBank {
    val sentences = listOf(
        "Please leave the blue umbrella beside the front door.",
        "We can walk to the market after breakfast tomorrow.",
        "The little dog fell asleep beneath the kitchen table.",
        "I packed some fresh apples for our afternoon picnic.",
        "My sister keeps her favorite recipes in a notebook.",
        "A warm cup of tea makes rainy mornings better.",
        "Let us take the quiet path along the river.",
        "The bus arrived just as we reached the corner.",
        "Could you water the plants before you leave today?",
        "Our neighbors brought homemade bread to share with everyone.",
        "I found my missing keys inside an old jacket.",
        "We watched the clouds turn pink above the rooftops.",
        "This small wooden chair fits perfectly beside the window.",
        "Remember to bring a sweater for the evening breeze.",
        "The children built a tiny village from empty boxes.",
        "She reads a few pages before going to sleep.",
        "There is enough soup left for lunch tomorrow afternoon.",
        "The garden looks brighter after a night of rain.",
        "He fixed the loose handle with a small screwdriver.",
        "We always stop for coffee on the way home.",
        "Your clean towels are on the shelf near the bathroom.",
        "A bright yellow bird landed on our balcony railing.",
        "The new bakery opens early every morning except Sunday.",
        "I wrote the shopping list on the fridge door.",
        "Please save a seat for me near the aisle.",
        "They planted three young trees along the school fence.",
        "Our train passes a field full of white flowers.",
        "The cat follows every patch of sunlight across the floor.",
        "We made room for another book on the shelf.",
        "I like listening to music while folding clean clothes.",
        "Her birthday card arrived with a lovely handwritten message.",
        "The last slice of cake is yours to enjoy.",
        "We should check the weather before planning our hike.",
        "A gentle knock at the door interrupted our conversation.",
        "My brother taught me how to mend a bicycle.",
        "The street lights came on as the sky darkened.",
        "I put the extra blankets in the hallway cupboard.",
        "Our friends stayed late to help wash the dishes.",
        "The fresh paint on the gate has finally dried.",
        "Tomorrow we will try a different route to work.",
    )
}
