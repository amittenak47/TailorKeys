package app.tailorkeys

import android.content.Context
import android.os.Build
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SpellCheckerSubtype
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.util.Locale

/**
 * The spell checker selected in Android settings, reached through TextServicesManager: Android lets no keyboard use
 * another keyboard's autocorrect, and this service is the only shared piece. It sees each word as text only, never
 * where it was tapped. A checker is never bound directly; Android picks it, and only if it is turned on.
 * SpellCheckController makes every decision; this only converts Android's results into [SpellCheckResult]s.
 */
internal class SystemSpellChecker(private val context: Context) : SpellCheckProvider {
    private var session: SpellCheckerSession? = null

    override fun open(onReply: (List<SpellCheckResult>) -> Unit): Boolean {
        close()
        val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
            // Only getSentenceSuggestions is used: getSuggestions is deprecated, so this never comes.
            override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit
            override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) = onReply(convert(results))
        }
        session = try { newSession(context, listener) } catch (_: RuntimeException) { null }
        return session != null
    }

    override fun request(word: String, cookie: Int, sequence: Int, limit: Int): Boolean {
        val s = session ?: return false
        return try {
            if (s.isSessionDisconnected) return false
            // The word alone, as one text, even though it is a single word: getSuggestions is deprecated.
            s.getSentenceSuggestions(arrayOf(TextInfo(word, cookie, sequence)), limit)
            true
        } catch (_: RuntimeException) { false }
    }

    override fun cancel() {
        try { session?.cancel() } catch (_: RuntimeException) {}
    }

    override fun close() {
        val s = session ?: return
        session = null
        try { s.close() } catch (_: RuntimeException) {}
    }

    companion object {
        /**
         * A session for English that follows Android's spell-checker language setting, or null when no checker is
         * turned on, or the one selected isn't set up for English. Replies come on the main thread.
         */
        private fun newSession(context: Context, listener: SpellCheckerSession.SpellCheckerSessionListener): SpellCheckerSession? {
            val manager = context.getSystemService(TextServicesManager::class.java) ?: return null
            return if (Build.VERSION.SDK_INT >= 31) {
                if (!manager.isSpellCheckerEnabled) return null
                val params = SpellCheckerSession.SpellCheckerSessionParams.Builder()
                    .setLocale(Locale.ENGLISH)
                    .setShouldReferToSpellCheckerLanguageSettings(true)
                    .setSupportedAttributes(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY or SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or
                        SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS or SuggestionsInfo.RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS)
                    .build()
                manager.newSpellCheckerSession(params, context.mainExecutor, listener)
            } else {
                // Android 8 to 11: the older public overload. Created on the main thread, it replies there.
                manager.newSpellCheckerSession(null, Locale.ENGLISH, listener, true)
            }
        }

        fun convert(results: Array<out SentenceSuggestionsInfo?>?): List<SpellCheckResult> {
            val out = ArrayList<SpellCheckResult>()
            for (sentence in results ?: return out) {
                if (sentence == null) continue
                for (i in 0 until sentence.suggestionsCount) {
                    val info = sentence.getSuggestionsInfoAt(i) ?: continue
                    val flags = info.suggestionsAttributes
                    val words = (0 until info.suggestionsCount).mapNotNull { info.getSuggestionAt(it) }
                    out += SpellCheckResult(info.cookie, info.sequence, sentence.getOffsetAt(i), sentence.getLengthAt(i),
                        inDictionary = flags and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0,
                        looksLikeTypo = flags and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0,
                        recommended = flags and SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS != 0,
                        grammar = flags and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_GRAMMAR_ERROR != 0,
                        hideUi = flags and SuggestionsInfo.RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS != 0,
                        suggestions = words)
                }
            }
            return out
        }

        /**
         * Which checker Android would use, for the settings note. From Android 12 Android says so directly; [probe]
         * (only while the setting is on) also opens a session and closes it at once, sending nothing, to see whether it
         * is set up for English. Before Android 12 only a session shows it, so without [probe] it is unknown.
         */
        fun status(context: Context, probe: Boolean): SpellCheckStatus = try {
            val manager = context.getSystemService(TextServicesManager::class.java)
            if (manager == null) SpellCheckStatus(SpellCheckStatus.State.NOT_TURNED_ON)
            else if (Build.VERSION.SDK_INT >= 31) {
                val info = if (manager.isSpellCheckerEnabled) manager.currentSpellCheckerInfo else null
                if (info == null) SpellCheckStatus(SpellCheckStatus.State.NOT_TURNED_ON)
                else {
                    val name = info.loadLabel(context.packageManager)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    val english = (0 until info.subtypeCount).any { language(info.getSubtypeAt(it)) == "en" }
                    SpellCheckStatus(if (english && (!probe || opens(context))) SpellCheckStatus.State.READY
                        else SpellCheckStatus.State.NO_ENGLISH, name)
                }
            } else if (!probe) SpellCheckStatus(SpellCheckStatus.State.UNKNOWN)
            else {
                val session = newSession(context, NO_REPLIES)
                val name = session?.spellChecker?.loadLabel(context.packageManager)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                session?.close()
                if (session == null) SpellCheckStatus(SpellCheckStatus.State.NO_ENGLISH)
                else SpellCheckStatus(SpellCheckStatus.State.READY, name)
            }
        } catch (_: RuntimeException) { SpellCheckStatus(SpellCheckStatus.State.UNKNOWN) }

        private fun opens(context: Context): Boolean {
            val session = newSession(context, NO_REPLIES) ?: return false
            session.close()
            return true
        }

        private fun language(subtype: SpellCheckerSubtype): String {
            val tag = subtype.languageTag
            @Suppress("DEPRECATION")
            return (if (tag.isNotEmpty()) Locale.forLanguageTag(tag).language else subtype.locale.substringBefore('_')).lowercase()
        }

        private val NO_REPLIES = object : SpellCheckerSession.SpellCheckerSessionListener {
            override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) = Unit
            override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) = Unit
        }
    }
}
