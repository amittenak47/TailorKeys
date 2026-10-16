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
        // A letter set belongs to its most frequent word, unless another common word (top 2,000) shares it:
        // "the" keeps t+h+e even though rare "thee" and "teeth" use the same letters.
        val common = sets.take(2_000).groupingBy { it }.eachCount()
        val first = HashMap<String, Int>().also { m -> sets.forEachIndexed { i, s -> m.putIfAbsent(s, i) } }
        candidates = words.zip(sets).withIndex().filter { it.value.second.length >= 3 }.take(400)
            .filter { (i, pair) -> pair.second.length in 3..6 && first[pair.second] == i && (common[pair.second] ?: 0) <= 1 }
            .map { (_, pair) -> Chord(pair.second, pair.first) }
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
