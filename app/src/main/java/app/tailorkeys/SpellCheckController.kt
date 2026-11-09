package app.tailorkeys

/** The system spell checker's part (Prefs.SYSTEM_SPELL_CHECK): none, added to TailorKeys's own correction, or instead of it. */
enum class SpellCheckMode { OFF, ADD, INSTEAD }

/**
 * One result from the system spell checker in this app's own terms; SystemSpellChecker converts Android's
 * SentenceSuggestionsInfo into these. [offset] and [length] are UTF-16 positions in the word that was sent. The flags
 * are Android's result attributes; they carry no calibrated confidence.
 */
internal data class SpellCheckResult(
    val cookie: Int, val sequence: Int, val offset: Int, val length: Int,
    val inDictionary: Boolean = false, val looksLikeTypo: Boolean = false, val recommended: Boolean = false,
    val grammar: Boolean = false, val hideUi: Boolean = false, val suggestions: List<String> = emptyList(),
)

/** The spell checker selected in Android settings (SystemSpellChecker), or a fake one in tests. */
internal interface SpellCheckProvider {
    /**
     * Opens a session for English with the checker selected in Android settings. False when there is none to use:
     * none is turned on, or it isn't set up for English. Replies arrive through [onReply] on the keyboard's thread.
     */
    fun open(onReply: (List<SpellCheckResult>) -> Unit): Boolean
    /** Asks about [word] alone, never the text around it. False when the session has gone (the checker changed or stopped). */
    fun request(word: String, cookie: Int, sequence: Int, limit: Int): Boolean
    /** Drops the requests still waiting for a reply. */
    fun cancel()
    fun close()
}

/** Time and delayed work on the keyboard's thread; a fake one in tests. */
internal interface SpellCheckClock {
    fun now(): Long
    /** Runs [task] after [delay] milliseconds; the function returned cancels it. */
    fun post(delay: Long, task: () -> Unit): () -> Unit
}

/**
 * The editor around the cursor, as the service read it: the input session (a new field or restart changes it), the
 * connection, the selection (absolute, -1 when the editor doesn't say) and the text on both sides.
 */
internal data class SpellCheckEditor(val generation: Long, val connection: Any?, val start: Int, val end: Int,
    val selected: Boolean, val before: String, val after: String)

/** What Android's settings allow the system spell checker to do here, for the settings note (SystemSpellChecker.status). */
internal data class SpellCheckStatus(val state: State, val name: String? = null) {
    enum class State { READY, NOT_TURNED_ON, NO_ENGLISH, UNKNOWN }
}

/** The system spell checker's rules, kept apart from Android so they can be tested on their own. */
internal object SpellCheckRules {
    /**
     * Fields the checker may ever see a word from: those TailorKeys may learn from (text, not a password, suggestions
     * allowed, no incognito flag) that aren't addresses (web, email or postal).
     */
    fun eligibleField(text: Boolean, password: Boolean, noSuggestions: Boolean, noPersonalization: Boolean, address: Boolean) =
        EditorRules.canLearn(text, password, noSuggestions, noPersonalization) && !address

    /**
     * Whether a finished word may go to the checker for an automatic fix, once [WordEndRules.mayChange] allows any
     * change: typed here, an ordinary word, not a name (capitalized away from the start of a sentence), not one of the
     * user's own words, and not a word TailorKeys's dictionary knows. The checker never sees learned words, so it
     * can't know to leave them.
     */
    fun mayAsk(f: WordEndFacts) = f.typed && CorrectionRules.eligible(f.word) &&
        !CorrectionRules.named(f.before, f.word) && !f.learned && !f.known

    /**
     * The checker's fix for the whole of [word], or null. Only when it says the word looks like a typo, has a
     * recommended suggestion and isn't in its dictionary; never when it asks for no suggestions to be shown, never for
     * grammar alone, and only its first suggestion: the flags carry no confidence to choose another by.
     */
    fun automaticFix(word: String, result: SpellCheckResult): String? {
        if (result.hideUi || result.offset != 0 || result.length != word.length) return null
        if (!result.looksLikeTypo || !result.recommended || result.inDictionary) return null
        val fix = clean(result.suggestions.firstOrNull() ?: return null) ?: return null
        return matchCase(fix, word).takeIf { it != word }
    }

    /**
     * [suggestion] when it is fit to put in the text: letters, with apostrophes, hyphens or single spaces between
     * them, at most 48 characters; otherwise null.
     */
    fun clean(suggestion: String): String? {
        if (suggestion.isEmpty() || suggestion.length > 48 || "  " in suggestion) return null
        if (!suggestion.first().isLetter() || !(suggestion.last().isLetter() || suggestion.last() == '\'' || suggestion.last() == '\u2019')) return null
        if (suggestion.any { !(it.isLetter() || it == '\'' || it == '\u2019' || it == '-' || it == ' ') }) return null
        return suggestion
    }

    /** The words in a suggestion, each checked against the offensive list on its own. */
    fun words(suggestion: String) = suggestion.split(' ', '-').filter { it.isNotEmpty() }

    /** A capital typed at the start (or all capitals) carries over; capitals in the suggestion itself stay (i -> I). */
    fun matchCase(suggestion: String, word: String) = when {
        word.length > 1 && word.all { !it.isLetter() || it.isUpperCase() } -> suggestion.uppercase()
        word.firstOrNull()?.isUpperCase() == true -> suggestion.replaceFirstChar { it.uppercase() }
        else -> suggestion
    }

    /**
     * The closest wheel with the checker's suggestions: TailorKeys's first word stays in the middle, the checker's
     * follow it in the checker's own order, then the rest of TailorKeys's. The two are never ranked against each
     * other, and a word already on the wheel isn't added again.
     */
    fun mergeWheel(local: List<String>, system: List<String>, n: Int = 8): List<String> {
        val added = system.filter { s -> local.none { it.equals(s, ignoreCase = true) } }.distinctBy { it.lowercase() }
        if (added.isEmpty()) return local.take(n)
        return (local.take(1) + added.take(SYSTEM_SLOTS) + local.drop(1)).take(n)
    }

    /** The note under the setting, naming the checker when Android says which it is. */
    fun note(status: SpellCheckStatus): String {
        val sharing = "When enabled, words you type, including unfinished words, are sent to that app. " +
            "It may process them online under its own privacy settings. Off disables this sharing."
        return when (status.state) {
            SpellCheckStatus.State.READY -> "Uses the spell checker selected in Android settings" +
                (status.name?.let { " (currently $it)" } ?: "") + ". " + sharing
            SpellCheckStatus.State.UNKNOWN -> "Uses the spell checker selected in Android settings. $sharing"
            SpellCheckStatus.State.NOT_TURNED_ON -> "No spell checker is turned on in Android settings, so this works as Off."
            SpellCheckStatus.State.NO_ENGLISH -> if (status.name != null)
                "The spell checker selected in Android settings (${status.name}) isn't set up for English, so this works as Off."
                else "No spell checker set up for English is turned on in Android settings, so this works as Off."
        }
    }

    /** How many of the checker's suggestions join the closest wheel. */
    const val SYSTEM_SLOTS = 3
}

/**
 * The system spell checker's decisions, in plain Kotlin behind [SpellCheckProvider], so they can be tested with a fake
 * checker. The service tells it about fields, settings, user actions and finished words; it asks the checker, checks
 * every reply, and hands back fixes and wheel suggestions through [Host]. Everything runs on the keyboard's thread.
 *
 * Off, in a field it may not see, for a language other than English, or with no checker to use: no session, no
 * requests, nothing kept. Otherwise a session opens the first time it is needed, and closes when the field or the
 * keyboard goes, or the settings or the checker change.
 */
internal class SpellCheckController(private val provider: SpellCheckProvider, private val clock: SpellCheckClock,
                                    private val host: Host) {
    interface Host {
        /** Runs [block] as one editor phase with a fresh read of the editor. */
        fun phase(block: () -> Unit)
        /** The editor as it is now, or null without a connection. */
        fun editor(): SpellCheckEditor?
        fun offensive(word: String): Boolean
        /** Whether undoing [original] to [fix] has told TailorKeys never to make that change. */
        fun rejected(original: String, fix: String): Boolean
        /** Puts [replacement] in place of [word], which is followed by [tail], the separator already typed; the tail stays. */
        fun replace(word: String, replacement: String, tail: String)
        /** Suggestions for the word being typed have arrived. */
        fun wheelChanged()
    }

    var mode = SpellCheckMode.OFF
        private set
    private var autocorrect = true
    private var blockOffensive = true
    private var language = "en"
    private var fieldEligible = false
    /** No checker could be opened in this field; it behaves as Off until the next field or settings change. */
    private var unavailable = false
    private var destroyed = false
    private var open = false
    private var cookie = 0
    private var sequence = 0
    /** Bumped by every user action and every reset: a late fix applies only while it is unchanged. */
    private var revision = 0L
    private var settings = 0

    private class Request(val word: String, val sequence: Int)
    /** The one wheel request in flight; a newer word replaces it, and the older reply is then ignored. */
    private var wheelRequest: Request? = null
    private var scheduled: String? = null
    private var cancelScheduled: (() -> Unit)? = null
    private class Answer(val word: String, val cookie: Int, val result: SpellCheckResult?)
    /** The last whole-word answer from this session, for the wheels and for that word's end. */
    private var answer: Answer? = null
    private class PendingFix(val word: String, val tail: String, val editor: SpellCheckEditor, val revision: Long,
                             val settings: Int, val endedAt: Long, val sequence: Int)
    private var pendingFix: PendingFix? = null
    private var cancelDeadline: (() -> Unit)? = null

    /** The settings the checker depends on. A change closes the session and drops what it said; true when something changed. */
    fun configure(mode: SpellCheckMode, autocorrect: Boolean, blockOffensive: Boolean, language: String): Boolean {
        if (mode == this.mode && autocorrect == this.autocorrect && blockOffensive == this.blockOffensive && language == this.language) return false
        this.mode = mode; this.autocorrect = autocorrect; this.blockOffensive = blockOffensive; this.language = language
        settings++
        reset()
        unavailable = false
        return true
    }

    /** A field is showing the keyboard; [eligible]: SpellCheckRules.eligibleField. No session opens yet. */
    fun startField(eligible: Boolean) {
        reset()
        fieldEligible = eligible
        unavailable = false
    }

    /** The field was left or the keyboard hidden. */
    fun finishField() {
        reset()
        fieldEligible = false
    }

    fun destroy() {
        reset()
        fieldEligible = false
        destroyed = true
    }

    /** Any key, gesture, pick or cursor move: a fix still on its way no longer applies. */
    fun userAction() {
        revision++
        dropPendingFix()
    }

    /** Instead of TailorKeys, with a checker this field can use: TailorKeys makes no automatic typo fix of its own. */
    val replacesLocal: Boolean get() = mode == SpellCheckMode.INSTEAD && usable()

    /**
     * The checker's suggestions for [word], the word being typed (empty for none), in the checker's order. Returns
     * what has arrived; when nothing has for this word, it is asked once typing pauses for [WHEEL_DELAY], and only for
     * the latest word.
     */
    fun wheel(word: String): List<String> {
        if (word.length < 2 || !CorrectionRules.eligible(word) || !usable()) { cancelWheel(); return emptyList() }
        answer?.takeIf { it.word == word && it.cookie == cookie }?.let { return suggestions(word, it.result) }
        if (scheduled != word && wheelRequest?.word != word) {
            cancelWheel()
            scheduled = word
            cancelScheduled = clock.post(WHEEL_DELAY) {
                scheduled = null; cancelScheduled = null
                sendWheel(word)
            }
        }
        return emptyList()
    }

    /**
     * [word] was just finished by its separator, already in the text: [editor] is the editor right after it. Only
     * called for a word nothing else changed and the protection rules allow (WordEndRules, SpellCheckRules.mayAsk).
     * The checker is asked without waiting; its fix may apply within [LATE_LIMIT] of now, while everything is as it is.
     */
    fun wordEnded(word: String, editor: SpellCheckEditor) {
        val inFlight = wheelRequest?.takeIf { it.word == word }
        cancelWheel()
        dropPendingFix()
        if (!autocorrect || !CorrectionRules.eligible(word) || editor.selected || !usable()) return
        val tail = editor.before.takeLastWhile { !TextInputRules.wordChar(it) }
        val head = editor.before.dropLast(tail.length)
        if (tail.isEmpty() || tail.any { it !in SEPARATORS } || !head.endsWith(word) ||
            head.dropLast(word.length).lastOrNull()?.let(TextInputRules::wordChar) == true) return
        val known = answer?.takeIf { it.word == word && it.cookie == cookie }
        val fix = PendingFix(word, tail, editor, revision, settings, clock.now(),
            if (known != null) -1 else inFlight?.sequence ?: ++sequence)
        pendingFix = fix
        cancelDeadline = clock.post(LATE_LIMIT + 1) { if (pendingFix === fix) dropPendingFix() }
        if (known != null) {
            // Asked about this exact word while it was typed: that answer decides, as a reply would.
            clock.post(0) { if (pendingFix === fix) { dropPendingFix(); host.phase { apply(fix, known.result) } } }
        } else if (inFlight == null && !provider.request(word, cookie, fix.sequence, LIMIT)) lost()
    }

    /** The checker is on for this field, opening its session the first time it is needed. */
    private fun usable(): Boolean {
        if (destroyed || mode == SpellCheckMode.OFF || !fieldEligible || language != "en" || unavailable) return false
        if (open) return true
        if (!provider.open { reply -> host.phase { onReply(reply) } }) { unavailable = true; return false }
        open = true
        cookie++
        return true
    }

    private fun sendWheel(word: String) {
        if (!usable()) return
        val request = Request(word, ++sequence)
        wheelRequest = request
        if (!provider.request(word, cookie, request.sequence, LIMIT)) lost()
    }

    /** The session went away (the checker changed or stopped): close it; the next word opens a new one. */
    private fun lost() = reset()

    private fun onReply(reply: List<SpellCheckResult>) {
        if (!open) return
        // Replies to an older session, or to requests no longer waiting (replaced, repeated or out of order), are ignored.
        val mine = reply.filter { it.cookie == cookie }
        for (number in mine.map { it.sequence }.distinct()) {
            val results = mine.filter { it.sequence == number }
            val wheel = wheelRequest
            if (wheel != null && wheel.sequence == number) {
                wheelRequest = null
                answer = Answer(wheel.word, cookie, whole(wheel.word, results))
                host.wheelChanged()
            }
            val fix = pendingFix
            if (fix != null && fix.sequence == number) {
                dropPendingFix()
                val result = whole(fix.word, results)
                answer = Answer(fix.word, cookie, result)
                apply(fix, result)
            }
        }
    }

    /** The result for the whole word that was sent; one for part of it never decides anything. */
    private fun whole(word: String, results: List<SpellCheckResult>) = results.firstOrNull { it.offset == 0 && it.length == word.length }

    /** Applies the checker's fix if it's still on time and nothing has changed since the word ended. */
    private fun apply(fix: PendingFix, result: SpellCheckResult?) {
        if (result == null || !autocorrect || destroyed) return
        if (clock.now() - fix.endedAt > LATE_LIMIT || fix.revision != revision || fix.settings != settings) return
        val replacement = SpellCheckRules.automaticFix(fix.word, result) ?: return
        // A first suggestion that fails these is not replaced by the next one.
        if (blockOffensive && SpellCheckRules.words(replacement).any(::offensive) || host.rejected(fix.word, replacement)) return
        // Text alone could have been edited and put back; the session, revision, connection and selection say it wasn't.
        if (host.editor() != fix.editor) return
        host.replace(fix.word, replacement, fix.tail)
    }

    private fun offensive(word: String) = host.offensive(word) || host.offensive(word.substringBefore('\'').substringBefore('\u2019'))

    private fun suggestions(word: String, result: SpellCheckResult?): List<String> {
        if (result == null || result.hideUi) return emptyList()
        return result.suggestions.asSequence().mapNotNull(SpellCheckRules::clean)
            .filter { !(blockOffensive && SpellCheckRules.words(it).any(::offensive)) }
            .map { SpellCheckRules.matchCase(it, word) }.filter { it != word }
            .distinctBy { it.lowercase() }.take(LIMIT).toList()
    }

    private fun cancelWheel() {
        cancelScheduled?.invoke()
        cancelScheduled = null
        scheduled = null
        wheelRequest = null
    }

    private fun dropPendingFix() {
        cancelDeadline?.invoke()
        cancelDeadline = null
        pendingFix = null
    }

    /** Cancels and forgets every request, drops every answer and closes the session. */
    private fun reset() {
        revision++
        cancelWheel()
        dropPendingFix()
        answer = null
        if (open) {
            open = false
            provider.cancel()
            provider.close()
        }
    }

    companion object {
        /** A wheel request goes out once typing pauses this long, in milliseconds. */
        const val WHEEL_DELAY = 150L
        /** A fix may apply at most this long after its word ended, in milliseconds. */
        const val LATE_LIMIT = 250L
        /** Suggestions asked for, and shown, per word. */
        const val LIMIT = 5
        /** What may follow a finished word when a fix lands. */
        private const val SEPARATORS = " .,?!;:"
    }
}
