package app.tailorkeys

import android.content.SharedPreferences
import app.tailorkeys.layout.KeyGrid
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory

class SpellCheckControllerTest {
    /** A spell checker that records what it is asked and replies only when a test says so. */
    private class FakeChecker : SpellCheckProvider {
        var available = true
        var connected = true
        var attempts = 0; var opens = 0; var closes = 0; var cancels = 0
        class Asked(val word: String, val cookie: Int, val sequence: Int)
        val asked = ArrayList<Asked>()
        /** Each session's reply callback, oldest first. */
        val sessions = ArrayList<(List<SpellCheckResult>) -> Unit>()
        override fun open(onReply: (List<SpellCheckResult>) -> Unit): Boolean {
            attempts++
            if (!available) return false
            opens++; connected = true; sessions += onReply
            return true
        }
        override fun request(word: String, cookie: Int, sequence: Int, limit: Int): Boolean {
            if (!connected) return false
            asked += Asked(word, cookie, sequence)
            return true
        }
        override fun cancel() { cancels++ }
        override fun close() { closes++ }
        /** Replies to request [index] (the last by default) through session [session] (the newest by default). */
        fun reply(index: Int = asked.lastIndex, session: Int = sessions.lastIndex, results: (Asked) -> List<SpellCheckResult>) =
            sessions[session](results(asked[index]))
    }

    private class FakeClock : SpellCheckClock {
        var time = 1_000L
        private class Task(val at: Long, val run: () -> Unit) { var cancelled = false }
        private val tasks = ArrayList<Task>()
        override fun now() = time
        override fun post(delay: Long, task: () -> Unit): () -> Unit {
            val t = Task(time + delay, task)
            tasks += t
            return { t.cancelled = true }
        }
        fun advance(ms: Long) {
            val end = time + ms
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                time = maxOf(time, next.at)
                next.run()
            }
            time = end
        }
    }

    private class FakeHost : SpellCheckController.Host {
        var editor: SpellCheckEditor? = null
        val replaced = ArrayList<String>()
        var wheelChanges = 0
        val offensiveWords = mutableSetOf("damn")
        val rejectedPairs = mutableSetOf<Pair<String, String>>()
        override fun phase(block: () -> Unit) = block()
        override fun editor() = editor
        override fun offensive(word: String) = word.lowercase() in offensiveWords
        override fun rejected(original: String, fix: String) = (original to fix) in rejectedPairs
        override fun replace(word: String, replacement: String, tail: String) {
            replaced += "$word>$replacement|$tail"
            val e = editor!!
            editor = e.copy(before = e.before.dropLast(word.length + tail.length) + replacement + tail)
        }
        override fun wheelChanged() { wheelChanges++ }
    }

    private class Rig(mode: SpellCheckMode = SpellCheckMode.ADD, autocorrect: Boolean = true, eligible: Boolean = true,
                      language: String = "en", blockOffensive: Boolean = true) {
        val checker = FakeChecker()
        val clock = FakeClock()
        val host = FakeHost()
        val controller = SpellCheckController(checker, clock, host)
        val connection = Any()
        init {
            controller.configure(mode, autocorrect, blockOffensive, language)
            controller.startField(eligible)
        }
        fun type(text: String, generation: Long = 1) {
            host.editor = SpellCheckEditor(generation, connection, text.length, text.length, false, text.takeLast(64), "")
        }
        /** The service's call once [word] and its [separator] are in the text. */
        fun end(before: String, word: String, separator: String = " ") {
            type(before + word + separator)
            controller.wordEnded(word, host.editor!!)
        }
        val text get() = host.editor!!.before
    }

    private fun result(a: FakeChecker.Asked, vararg suggestions: String, typo: Boolean = true, recommended: Boolean = true,
                       inDictionary: Boolean = false, grammar: Boolean = false, hideUi: Boolean = false,
                       offset: Int = 0, length: Int = a.word.length) =
        listOf(SpellCheckResult(a.cookie, a.sequence, offset, length, inDictionary, typo, recommended, grammar, hideUi, suggestions.toList()))

    private fun flags(typo: Boolean = false, recommended: Boolean = false, inDictionary: Boolean = false, grammar: Boolean = false,
                      hideUi: Boolean = false, suggestions: List<String> = listOf("the", "tea"), offset: Int = 0, length: Int = 3) =
        SpellCheckResult(1, 1, offset, length, inDictionary, typo, recommended, grammar, hideUi, suggestions)

    private fun facts(word: String = "teh", before: String = "I saw $word", after: String = "", selected: Boolean = false,
                      letters: Boolean = true, typed: Boolean = true, picked: Boolean = false, undone: Boolean = false,
                      glide: Boolean = false, learned: Boolean = false, known: Boolean = false) =
        WordEndFacts(word, before, after, selected, letters, typed, picked, undone, glide, learned, known)

    // ---- Modes ----

    @Test fun offOpensNoSessionSendsNothingAndKeepsNothing() {
        val rig = Rig(SpellCheckMode.OFF)
        assertTrue(rig.controller.wheel("teh").isEmpty())
        rig.clock.advance(500)
        rig.end("I saw ", "teh")
        rig.clock.advance(500)
        assertFalse(rig.controller.replacesLocal)
        assertEquals(0, rig.checker.attempts)
        assertTrue(rig.checker.asked.isEmpty())
        assertTrue(rig.host.replaced.isEmpty())
        // Turned off after an answer: the answer is gone, and nothing more is asked.
        val on = Rig(SpellCheckMode.ADD)
        on.controller.wheel("teh"); on.clock.advance(150)
        on.checker.reply { result(it, "the") }
        assertEquals(listOf("the"), on.controller.wheel("teh"))
        assertTrue(on.controller.configure(SpellCheckMode.OFF, true, true, "en"))
        assertEquals(1, on.checker.closes)
        assertTrue(on.controller.wheel("teh").isEmpty())
        on.controller.configure(SpellCheckMode.ADD, true, true, "en")
        assertTrue(on.controller.wheel("teh").isEmpty())
    }

    @Test fun addFixesAWordTailorKeysLeftAndKeepsItsSeparator() {
        for (separator in listOf(" ", ",", ".", "?", "!", ";", ":")) {
            val rig = Rig(SpellCheckMode.ADD)
            assertFalse(rig.controller.replacesLocal)
            rig.end("I saw ", "teh", separator)
            assertEquals(listOf("teh"), rig.checker.asked.map { it.word })
            rig.clock.advance(40)
            rig.checker.reply { result(it, "the", "tea") }
            assertEquals(listOf("teh>the|$separator"), rig.host.replaced)
            assertEquals("I saw the$separator", rig.text)
        }
    }

    @Test fun insteadReplacesTailorKeysOnlyWhereTheCheckerCanBeUsed() {
        val rig = Rig(SpellCheckMode.INSTEAD)
        assertEquals(0, rig.checker.attempts)
        assertTrue(rig.controller.replacesLocal)
        assertEquals(1, rig.checker.opens)
        rig.end("I saw ", "teh")
        rig.checker.reply { result(it, "the") }
        assertEquals("I saw the ", rig.text)
        // An incognito field, another language or no checker: TailorKeys's own correction works as with Off.
        assertFalse(Rig(SpellCheckMode.INSTEAD, eligible = false).controller.replacesLocal)
        assertFalse(Rig(SpellCheckMode.INSTEAD, language = "de").controller.replacesLocal)
        val none = Rig(SpellCheckMode.INSTEAD).apply { checker.available = false }
        assertFalse(none.controller.replacesLocal)
        assertFalse(Rig(SpellCheckMode.ADD).controller.replacesLocal)
        // Switching to Instead is a change the service hears about (it stops a sentence re-decoding still running).
        val switching = Rig(SpellCheckMode.ADD)
        assertTrue(switching.controller.configure(SpellCheckMode.INSTEAD, true, true, "en"))
        assertFalse(switching.controller.configure(SpellCheckMode.INSTEAD, true, true, "en"))
    }

    @Test fun autocorrectOffMakesNoFixButKeepsWheelSuggestions() {
        for (mode in listOf(SpellCheckMode.ADD, SpellCheckMode.INSTEAD)) {
            val rig = Rig(mode, autocorrect = false)
            rig.end("I saw ", "teh")
            rig.clock.advance(300)
            assertTrue(rig.checker.asked.isEmpty())
            assertTrue(rig.controller.wheel("teh").isEmpty())
            rig.clock.advance(150)
            assertEquals(listOf("teh"), rig.checker.asked.map { it.word })
            rig.checker.reply { result(it, "the", "tea") }
            assertEquals(1, rig.host.wheelChanges)
            assertEquals(listOf("the", "tea"), rig.controller.wheel("teh"))
            assertTrue(rig.host.replaced.isEmpty())
        }
    }

    @Test fun anUnavailableCheckerBehavesAsOffAndIsTriedOncePerField() {
        val rig = Rig(SpellCheckMode.INSTEAD)
        rig.checker.available = false
        assertFalse(rig.controller.replacesLocal)
        assertTrue(rig.controller.wheel("teh").isEmpty())
        rig.clock.advance(200)
        rig.end("I saw ", "teh")
        rig.end("I saw teh ", "dgo")
        assertEquals(1, rig.checker.attempts)
        assertTrue(rig.checker.asked.isEmpty())
        rig.checker.available = true
        rig.controller.startField(true)
        assertTrue(rig.controller.replacesLocal)
    }

    @Test fun noEnglishMeansNoSession() {
        // Android gives no session when the selected checker isn't set up for English.
        val rig = Rig(SpellCheckMode.ADD).apply { checker.available = false }
        rig.end("I saw ", "teh")
        assertTrue(rig.checker.asked.isEmpty())
        // TailorKeys typing in another language never opens one.
        val german = Rig(SpellCheckMode.ADD, language = "de")
        german.controller.wheel("teh"); german.clock.advance(200)
        german.end("I saw ", "teh")
        assertEquals(0, german.checker.attempts)
        assertTrue(german.checker.asked.isEmpty())
    }

    @Test fun privateFieldsNeverSendAWord() {
        assertTrue(SpellCheckRules.eligibleField(true, false, false, false, false))
        val closed = listOf(
            SpellCheckRules.eligibleField(true, password = true, noSuggestions = false, noPersonalization = false, address = false),
            SpellCheckRules.eligibleField(true, password = false, noSuggestions = false, noPersonalization = true, address = false),
            SpellCheckRules.eligibleField(true, password = false, noSuggestions = true, noPersonalization = false, address = false),
            SpellCheckRules.eligibleField(true, password = false, noSuggestions = false, noPersonalization = false, address = true),
            SpellCheckRules.eligibleField(false, password = false, noSuggestions = false, noPersonalization = false, address = false))
        assertTrue(closed.none { it })
        for (mode in listOf(SpellCheckMode.ADD, SpellCheckMode.INSTEAD)) {
            val rig = Rig(mode, eligible = false)
            assertTrue(rig.controller.wheel("teh").isEmpty())
            rig.clock.advance(200)
            rig.end("I saw ", "teh")
            assertFalse(rig.controller.replacesLocal)
            assertEquals(0, rig.checker.attempts)
            assertTrue(rig.checker.asked.isEmpty())
        }
    }

    // ---- What a reply may do ----

    @Test fun onlyTypoWithRecommendedSuggestionsOutsideTheDictionaryReplaces() {
        fun fix(r: SpellCheckResult) = SpellCheckRules.automaticFix("teh", r)
        assertEquals("the", fix(flags(typo = true, recommended = true)))
        assertNull(fix(flags(typo = true)))
        assertNull(fix(flags(recommended = true)))
        assertNull(fix(flags(inDictionary = true)))
        assertNull(fix(flags()))
        assertNull(fix(flags(typo = true, recommended = true, inDictionary = true)))
        assertNull(fix(flags(typo = true, recommended = true, hideUi = true)))
        // Grammar alone never permits it; with the typo flag the typo rule decides.
        assertNull(fix(flags(grammar = true)))
        assertNull(fix(flags(grammar = true, recommended = true)))
        assertEquals("the", fix(flags(grammar = true, typo = true, recommended = true)))
        // Asked not to show suggestions: the wheels get none either.
        val rig = Rig()
        rig.controller.wheel("teh"); rig.clock.advance(150)
        rig.checker.reply { result(it, "the", hideUi = true) }
        assertTrue(rig.controller.wheel("teh").isEmpty())
    }

    @Test fun emptyMalformedPartialAndOffensiveSuggestionsNeverReplace() {
        fun fix(vararg s: String, offset: Int = 0, length: Int = 3) =
            SpellCheckRules.automaticFix("teh", flags(typo = true, recommended = true, suggestions = s.toList(), offset = offset, length = length))
        assertNull(fix())
        // The first suggestion decides; a bad one isn't replaced by the next.
        for (bad in listOf("", " the", "the ", "th3", "the\n", "a  lot", "-the", "the.", "x".repeat(49), "teh")) assertNull(bad, fix(bad, "the"))
        assertNull(fix("the", offset = 1, length = 2))
        assertNull(fix("the", length = 2))
        assertNull(fix("the", length = 4))
        assertEquals("a lot", SpellCheckRules.automaticFix("alot", flags(typo = true, recommended = true, suggestions = listOf("a lot"), length = 4)))
        assertEquals("I'm", SpellCheckRules.automaticFix("im", flags(typo = true, recommended = true, suggestions = listOf("I'm"), length = 2)))
        assertEquals("The", SpellCheckRules.automaticFix("Teh", flags(typo = true, recommended = true)))
        assertEquals("THE", SpellCheckRules.automaticFix("TEH", flags(typo = true, recommended = true)))
        // An offensive first suggestion is blocked while offensive words are, and the next isn't promoted.
        val blocked = Rig()
        blocked.end("oh ", "dawm")
        blocked.checker.reply { result(it, "damn", "dawn") }
        assertTrue(blocked.host.replaced.isEmpty())
        val allowed = Rig(blockOffensive = false)
        allowed.end("oh ", "dawm")
        allowed.checker.reply { result(it, "damn", "dawn") }
        assertEquals(listOf("dawm>damn| "), allowed.host.replaced)
        // On the wheels, offensive and malformed suggestions are dropped and the rest keep their order.
        val wheel = Rig()
        wheel.controller.wheel("dawm"); wheel.clock.advance(150)
        wheel.checker.reply { result(it, "damn", "da\u0000wn", "dawn", "Damn's", "drawn") }
        assertEquals(listOf("dawn", "drawn"), wheel.controller.wheel("dawm"))
    }

    @Test fun protectedWordsAreNeverAsked() {
        assertTrue(WordEndRules.mayChange(facts(), true))
        assertTrue(SpellCheckRules.mayAsk(facts()))
        // Learned words, dictionary words and names (capitalized away from the start of a sentence).
        assertFalse(SpellCheckRules.mayAsk(facts(learned = true)))
        assertFalse(SpellCheckRules.mayAsk(facts(known = true)))
        assertFalse(SpellCheckRules.mayAsk(facts("Figma", before = "I use Figma")))
        assertTrue(SpellCheckRules.mayAsk(facts("Teh", before = "Hi. Teh")))
        assertFalse(SpellCheckRules.mayAsk(facts(typed = false)))
        assertFalse(SpellCheckRules.mayAsk(facts("te4h")))
        // A word picked from the wheels, a correction just undone, a swiped word, a selection, the middle of a word,
        // another layer or a field without corrections: no source changes it.
        for (f in listOf(facts(picked = true), facts(undone = true), facts(glide = true), facts(selected = true),
            facts(after = "re"), facts(letters = false))) assertFalse(f.toString(), WordEndRules.mayChange(f, true))
        assertFalse(WordEndRules.mayChange(facts(), false))
        // A correction undone often enough is never made again, and the next suggestion doesn't stand in.
        val rig = Rig()
        rig.host.rejectedPairs += "teh" to "the"
        rig.end("I saw ", "teh")
        rig.checker.reply { result(it, "the", "tea") }
        assertTrue(rig.host.replaced.isEmpty())
    }

    // ---- Timing and staleness ----

    @Test fun aFixAppliesOnlyWithin250Milliseconds() {
        val onTime = Rig()
        onTime.end("I saw ", "teh")
        onTime.clock.advance(SpellCheckController.LATE_LIMIT)
        onTime.checker.reply { result(it, "the") }
        assertEquals("I saw the ", onTime.text)
        val late = Rig()
        late.end("I saw ", "teh")
        late.clock.advance(SpellCheckController.LATE_LIMIT + 1)
        late.checker.reply { result(it, "the") }
        assertEquals("I saw teh ", late.text)
    }

    @Test fun repeatedAndOutOfOrderRepliesAreIgnored() {
        val rig = Rig()
        rig.end("I saw ", "teh")
        rig.checker.reply { result(it, "the") }
        rig.checker.reply { result(it, "the") }
        assertEquals(listOf("teh>the| "), rig.host.replaced)
        // Wheel replies out of order: only the latest word's answer counts.
        val wheel = Rig()
        wheel.controller.wheel("te"); wheel.clock.advance(150)
        wheel.controller.wheel("teh"); wheel.clock.advance(150)
        assertEquals(listOf("te", "teh"), wheel.checker.asked.map { it.word })
        wheel.checker.reply(1) { result(it, "the") }
        wheel.checker.reply(0) { result(it, "ten") }
        assertEquals(1, wheel.host.wheelChanges)
        assertEquals(listOf("the"), wheel.controller.wheel("teh"))
        // A reply carrying another session's cookie or an unknown sequence is ignored.
        val other = Rig()
        other.end("I saw ", "teh")
        other.checker.reply { a -> listOf(SpellCheckResult(a.cookie + 1, a.sequence, 0, 3, looksLikeTypo = true, recommended = true, suggestions = listOf("the"))) }
        other.checker.reply { a -> listOf(SpellCheckResult(a.cookie, a.sequence + 7, 0, 3, looksLikeTypo = true, recommended = true, suggestions = listOf("the"))) }
        assertTrue(other.host.replaced.isEmpty())
    }

    @Test fun anythingThatChangedSinceTheWordEndedStopsTheFix() {
        fun stale(change: Rig.() -> Unit): Boolean {
            val rig = Rig()
            rig.end("I saw ", "teh")
            rig.change()
            if (rig.checker.sessions.isNotEmpty()) rig.checker.reply(0, 0) { result(it, "the") }
            return rig.host.replaced.isEmpty()
        }
        assertFalse("nothing changed", stale {})
        // Field switch: the session closes, so the old one's reply finds nothing.
        assertTrue("field", stale { controller.finishField(); controller.startField(true) })
        // Cursor moved, a new input session, another connection.
        assertTrue("cursor", stale { host.editor = host.editor!!.copy(start = 2, end = 2) })
        assertTrue("generation", stale { host.editor = host.editor!!.copy(generation = 2) })
        assertTrue("connection", stale { host.editor = host.editor!!.copy(connection = Any()) })
        assertTrue("selection", stale { host.editor = host.editor!!.copy(selected = true) })
        assertTrue("text", stale { host.editor = host.editor!!.copy(after = "x") })
        // Undo (backspace) and an edit put back: the text matches again, but a user action came first.
        assertTrue("undo", stale { controller.userAction() })
        assertTrue("edit and restore", stale { val e = host.editor; controller.userAction(); host.editor = e!!.copy(before = "I saw te"); host.editor = e })
        // A settings change closes the session.
        assertTrue("settings", stale { controller.configure(SpellCheckMode.ADD, true, false, "en") })
    }

    @Test fun aSessionThatWentAwayIsClosedAndANewOneOpened() {
        val rig = Rig()
        rig.end("I saw ", "teh")
        val first = rig.checker.asked.last()
        // The checker was changed in Android settings: the old session no longer answers requests.
        rig.checker.connected = false
        rig.end("I saw teh ", "dgo")
        assertEquals(1, rig.checker.closes)
        rig.end("I saw teh dgo ", "cta")
        assertEquals(2, rig.checker.opens)
        val second = rig.checker.asked.last()
        assertNotEquals(first.cookie, second.cookie)
        // A late reply from the old session is ignored; the new one's applies.
        rig.checker.sessions[0](result(first, "the"))
        assertTrue(rig.host.replaced.isEmpty())
        rig.checker.reply { result(it, "cat") }
        assertEquals(listOf("cta>cat| "), rig.host.replaced)
    }

    // ---- Undo and learning ----

    @Test fun theSeparatorStaysAndOneUndoGivesBackTheTypedWordUnlearned() {
        for (separator in listOf(" ", ",", ".", "!")) {
            val text = "I saw teh$separator"
            // The system's fix lands after the separator was typed: it is taken out and given back.
            val (delete, insert) = WordEndRules.edit("teh", "the", separator, typed = separator)
            val fixed = text.dropLast(delete) + insert
            assertEquals("I saw the$separator", fixed)
            var learned = 0
            val undo = ReversibleCommit("teh", insert, separator, fixed, "", correction = true) { learned++ }
            assertEquals("teh$separator", undo.undo(fixed, "", false))
            undo.keep(fixed, "", false, learning = true)
            val kept = ReversibleCommit("teh", insert, separator, fixed, "", correction = true) { learned++ }
            // Nothing is learned until the fix is kept, and an undone fix never is.
            assertEquals(0, learned)
            kept.keep(fixed, "", false, learning = true)
            assertEquals(1, learned)
        }
        // TailorKeys's own correction types its separator with the fix, as before.
        assertEquals(3 to "the ", WordEndRules.edit("teh", "the", " "))
    }

    // ---- Wheels ----

    @Test fun wheelRequestsWaitForAPauseAndOnlyTheLatestWordGoes() {
        val rig = Rig()
        assertTrue(rig.controller.wheel("t").isEmpty())
        rig.controller.wheel("te"); rig.clock.advance(100)
        rig.controller.wheel("teh"); rig.clock.advance(100)
        rig.controller.wheel("teh"); rig.controller.wheel("teh"); rig.clock.advance(49)
        assertTrue(rig.checker.asked.isEmpty())
        rig.clock.advance(1)
        assertEquals(listOf("teh"), rig.checker.asked.map { it.word })
        rig.controller.wheel("teh"); rig.clock.advance(500)
        assertEquals(1, rig.checker.asked.size)
        // The session opened only when the first word needed it.
        assertEquals(1, rig.checker.opens)
        // A word typed on before the reply: the older reply changes nothing.
        rig.controller.wheel("tehx"); rig.checker.reply(0) { result(it, "the") }
        assertEquals(0, rig.host.wheelChanges)
    }

    @Test fun wheelSuggestionsAreDeduplicatedAndKeepTheCheckersOrder() {
        val rig = Rig()
        rig.controller.wheel("Teh"); rig.clock.advance(150)
        rig.checker.reply { result(it, "the", "The", "teh", "tea", "ten") }
        assertEquals(listOf("The", "Tea", "Ten"), rig.controller.wheel("Teh"))
        assertEquals(listOf("the", "tea", "ten", "toe", "then", "there", "they"),
            SpellCheckRules.mergeWheel(listOf("the", "then", "there", "they"), listOf("The", "tea", "ten", "tea", "toe", "tee")))
        assertEquals(listOf("tea", "ten"), SpellCheckRules.mergeWheel(emptyList(), listOf("tea", "ten")))
        assertEquals(listOf("a", "b"), SpellCheckRules.mergeWheel(listOf("a", "b"), emptyList()))
        assertEquals(8, SpellCheckRules.mergeWheel((1..8).map { "w$it" }, listOf("x", "y")).size)
    }

    @Test fun aWordsEndReusesTheAnswerAlreadyAskedForIt() {
        val answered = Rig()
        answered.controller.wheel("teh"); answered.clock.advance(150)
        answered.checker.reply { result(it, "the") }
        answered.end("I saw ", "teh")
        answered.clock.advance(0)
        assertEquals(1, answered.checker.asked.size)
        assertEquals("I saw the ", answered.text)
        // Still on its way when space is pressed: that request's reply decides.
        val asking = Rig()
        asking.controller.wheel("teh"); asking.clock.advance(150)
        asking.end("I saw ", "teh")
        assertEquals(1, asking.checker.asked.size)
        asking.checker.reply { result(it, "the") }
        assertEquals("I saw the ", asking.text)
    }

    @Test fun insteadOfTailorKeysTheWheelsKeepCompletionsWithoutTypoAlternatives() {
        val p = Predictor({ File("src/main/assets", it).inputStream() }, Files.createTempDirectory("tk-spell").toFile(), enableModel = false)
            .apply { setLayout(KeyGrid.default()) }
        val typos = listOf("teh", "helo", "thw", "becuase")
        assertTrue(typos.any { prefix -> p.closest(prefix).any { !it.startsWith(prefix, ignoreCase = true) } })
        for (prefix in typos + "hel") {
            assertTrue(prefix, p.closest(prefix, completionsOnly = true).all { it.startsWith(prefix, ignoreCase = true) })
            assertTrue(prefix, p.explore(prefix, completionsOnly = true).all { it.startsWith(prefix, ignoreCase = true) })
        }
        // Completions stay, in the same order.
        val completions = p.closest("hel").filter { it.startsWith("hel") }
        assertTrue(completions.isNotEmpty())
        assertEquals(completions, p.closest("hel", completionsOnly = true).take(completions.size))
        // Next-word predictions are unchanged.
        assertEquals(p.closest("", "the"), p.closest("", "the", completionsOnly = true))
        assertTrue(p.isOffensive("shit"))
        assertFalse(p.isOffensive("hello"))
    }

    // ---- Settings ----

    @Test fun theNoteNamesTheCheckerOrSaysItWorksAsOff() {
        val ready = SpellCheckRules.note(SpellCheckStatus(SpellCheckStatus.State.READY, "Gboard"))
        assertEquals("Uses the spell checker selected in Android settings (currently Gboard). When enabled, words you type, " +
            "including unfinished words, are sent to that app. It may process them online under its own privacy settings. " +
            "Off disables this sharing.", ready)
        assertEquals("No spell checker is turned on in Android settings, so this works as Off.",
            SpellCheckRules.note(SpellCheckStatus(SpellCheckStatus.State.NOT_TURNED_ON)))
        assertTrue(SpellCheckRules.note(SpellCheckStatus(SpellCheckStatus.State.NO_ENGLISH, "Gboard")).endsWith("so this works as Off."))
        assertTrue(SpellCheckRules.note(SpellCheckStatus(SpellCheckStatus.State.NO_ENGLISH)).endsWith("so this works as Off."))
        assertTrue(SpellCheckRules.note(SpellCheckStatus(SpellCheckStatus.State.UNKNOWN)).endsWith("Off disables this sharing."))
        for (state in SpellCheckStatus.State.entries) assertFalse(SpellCheckRules.note(SpellCheckStatus(state, "Gboard")).contains('\u2014'))
    }

    private fun memory(): Pair<SharedPreferences, HashMap<String, Any?>> {
        val values = HashMap<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, m, a ->
            when (m.name) {
                "putInt", "putString", "putBoolean", "putLong" -> { values[a[0] as String] = a[1]; editor }
                "remove" -> { values.remove(a[0]); editor }
                "clear" -> { values.clear(); editor }
                "apply" -> null
                "commit" -> true
                else -> error(m.name)
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, m, a ->
            when (m.name) {
                "edit" -> editor
                "contains" -> values.containsKey(a[0])
                "getInt", "getString", "getBoolean", "getLong" -> values[a[0]] ?: a[1]
                else -> error(m.name)
            }
        } as SharedPreferences
        return prefs to values
    }

    @Test fun offByDefaultKeptOnTheDeviceAndResetToOff() {
        val key = Prefs.SYSTEM_SPELL_CHECK.key
        assertEquals(SpellCheckMode.OFF, SpellCheckMode.entries[Prefs.SYSTEM_SPELL_CHECK.default])
        assertTrue(Prefs.SYSTEM_SPELL_CHECK.device)
        val (prefs, backedUp) = memory()
        val (device, onDevice) = memory()
        // A value in the backed-up file can only have come from a restore: it is dropped, not moved.
        backedUp[key] = 2
        val settings = KeyboardSettings(prefs, false, "en", device)
        assertFalse(backedUp.containsKey(key))
        assertFalse(onDevice.containsKey(key))
        assertEquals(0, settings.int(Prefs.SYSTEM_SPELL_CHECK))
        settings.set(Prefs.SYSTEM_SPELL_CHECK, 1)
        assertEquals(1, onDevice[key])
        assertFalse(backedUp.containsKey(key))
        assertEquals(1, settings.int(Prefs.SYSTEM_SPELL_CHECK))
        settings.resetAll()
        assertEquals(0, settings.int(Prefs.SYSTEM_SPELL_CHECK))
        assertFalse(onDevice.containsKey(key))
        assertEquals(true, backedUp[Prefs.ONBOARDED.key])
        // Other settings still go to the backed-up file.
        settings.set(Prefs.AUTOCORRECT, 0)
        assertEquals(0, backedUp[Prefs.AUTOCORRECT.key])
    }

    @Test fun theDeviceOnlyFileIsLeftOutOfEveryBackup() {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        fun excluded(file: String, section: String): Boolean {
            val sections = builder.parse(File("src/main/res/xml/$file")).getElementsByTagName(section)
            if (sections.length == 0) return false
            return (0 until sections.length).all { i ->
                val excludes = (sections.item(i) as org.w3c.dom.Element).getElementsByTagName("exclude")
                (0 until excludes.length).any { j ->
                    val e = excludes.item(j) as org.w3c.dom.Element
                    e.getAttribute("domain") == "sharedpref" && e.getAttribute("path") == "device_private.xml"
                }
            }
        }
        assertTrue(excluded("backup_rules.xml", "full-backup-content"))
        assertTrue(excluded("data_extraction_rules.xml", "cloud-backup"))
        assertTrue(excluded("data_extraction_rules.xml", "device-transfer"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        // KeyboardSettings keeps device-only settings in that file.
        assertTrue(File("src/main/java/app/tailorkeys/KeyboardSettings.kt").readText().contains("getSharedPreferences(\"device_private\""))
    }
}
