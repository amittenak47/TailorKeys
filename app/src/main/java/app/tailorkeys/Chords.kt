package app.tailorkeys

/** Stable dictionary ranks, independent of learned words and suggestion caches. */
class Chords(rankedWords: List<String>) {
    data class Chord(val keys: String, val word: String) {
        val label get() = keys.toList().joinToString(" ") + " → " + word
    }

    private val candidates: List<Chord>
    init {
        val words = rankedWords.take(20_000).distinct()
        val sets = words.map { letters(it) }
        val counts = sets.groupingBy { it }.eachCount()
        candidates = words.zip(sets).filter { it.second.length >= 3 }.take(400)
            .filter { (_, keys) -> keys.length in 3..6 && counts[keys] == 1 }
            .map { (word, keys) -> Chord(keys, word) }
    }

    fun forLetters(keys: Set<Char>): List<Chord> = candidates.filter { chord -> chord.keys.all { it in keys } }.take(250)

    companion object {
        fun letters(word: String): String = word.lowercase().filter { it.isLetter() }.toSortedSet().joinToString("")
    }
}

/** Touch IDs stay in press order. Admission closes on the first lift or after 60 ms. */
class ChordTouches<ID> {
    data class Entry<ID>(val id: ID, val letter: Char)
    data class Completion<ID>(val entries: List<Entry<ID>>, val candidate: Boolean)
    data class Prepared<ID>(val rollover: List<ID>, val completions: List<Completion<ID>>)
    data class Release<ID>(val handled: Boolean, val completions: List<Completion<ID>> = emptyList())
    private class Group<ID>(val time: Long) {
        val entries = ArrayList<Entry<ID>>()
        val held = HashSet<ID>()
        var lifted = false
        var complete = false
        var suppressed = false
    }
    private val groups = ArrayList<Group<ID>>()

    // Expiring a singleton performs ordinary rollover. Completed later groups wait for earlier
    // candidates, so a finger lifting out of order cannot reorder normal fast typing.
    fun prepareDown(time: Long, eligible: Boolean): Prepared<ID> {
        val rollover = ArrayList<ID>()
        for (group in groups) if (!group.complete && group.entries.size == 1 && (!eligible || time - group.time > WINDOW_MS)) {
            group.complete = true
            rollover.addAll(group.held)
        }
        return Prepared(rollover, drain())
    }

    fun down(id: ID, letter: Char, time: Long) {
        val group = groups.lastOrNull()?.takeIf { !it.complete && !it.lifted && time - it.time <= WINDOW_MS }
            ?: Group<ID>(time).also { groups.add(it) }
        group.entries.add(Entry(id, letter))
        group.held.add(id)
    }

    fun contains(id: ID): Boolean = groups.any { id in it.held }
    fun isCandidate(id: ID): Boolean = groups.any { id in it.held && !it.complete && it.entries.size >= 2 }
    fun update(id: ID, letter: Char?, consumed: Boolean) {
        val group = groups.firstOrNull { id in it.held } ?: return
        if (group.entries.size == 1 && !group.complete) {
            if (letter != null) group.entries[0] = Entry(id, letter)
            group.suppressed = consumed
        }
    }
    fun release(id: ID): Release<ID> {
        val group = groups.firstOrNull { id in it.held } ?: return Release(false)
        group.held.remove(id)
        group.lifted = true
        if (group.held.isEmpty()) group.complete = true
        return Release(true, drain())
    }
    private fun drain(): List<Completion<ID>> {
        val result = ArrayList<Completion<ID>>()
        while (groups.firstOrNull()?.complete == true) {
            val group = groups.removeAt(0)
            if (!group.suppressed) result.add(Completion(group.entries.toList(), group.entries.size >= 2))
        }
        return result
    }
    fun cancel() { groups.clear() }

    companion object { const val WINDOW_MS = 60L }
}
