package app.tailorkeys

import android.content.SharedPreferences
import app.tailorkeys.insert.*
import org.junit.Assert.*
import org.junit.Test

class InsertRepositoryTest {
    private fun preferences(): SharedPreferences {
        val values = HashMap<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putInt", "putString", "putBoolean" -> { values[args[0] as String] = args[1]; editor }
                "remove" -> { values.remove(args[0]); editor }
                "apply" -> null
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        return java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString", "getBoolean", "getInt" -> values[args[0]] ?: args[1]
                else -> error(method.name)
            }
        } as SharedPreferences
    }

    @Test fun tapsAndTonePicksPersistBeforeInsertionAndStayInTheirSection() {
        val prefs = preferences()
        val repository = InsertRepository(prefs)
        val emoji = EmojiCatalog.load(java.io.File("src/main/assets/insert/emoji.tsv").readLines().asSequence())
        val plain = emoji.variants("\uD83D\uDC4D").first()
        val toned = emoji.toned(plain, 3)
        for (entry in listOf(plain, toned)) repository.insertSymbol("Emoji", entry) { text ->
            assertEquals(entry.text, text)
            assertEquals(entry.text, InsertRepository(prefs).recent("Emoji").first())
        }
        val kaomoji = InsertSymbol("(^_^)", "happy", "Happy")
        repository.insertSymbol("Kaomoji", kaomoji) { assertEquals(kaomoji.text, it) }
        assertEquals(listOf(toned.text, plain.text), InsertRepository(prefs).recent("Emoji"))
        assertEquals(listOf(kaomoji.text), InsertRepository(prefs).recent("Kaomoji"))
        prefs.edit().putBoolean(InsertPrefs.RECENTS, false).apply()
        repository.insertSymbol("Emoji", plain) {}
        prefs.edit().putBoolean(InsertPrefs.RECENTS, true).apply()
        assertEquals(listOf(plain.text, toned.text), repository.recent("Emoji"))
        repeat(40) { repository.insertSymbol("Kaomoji", InsertSymbol("face$it", "face", "Happy")) {} }
        assertEquals(32, repository.recent("Kaomoji").size)
        assertEquals(2, repository.recent("Emoji").size)
        repository.clearRecents()
        assertTrue(repository.recent("Emoji").isEmpty()); assertTrue(repository.recent("Kaomoji").isEmpty())
    }

    @Test fun firstUseDoesNotReturnAfterClipsAreCleared() {
        val prefs = preferences(); val repository = InsertRepository(prefs)
        assertFalse(prefs.getBoolean(InsertPrefs.HAD_CLIPS, false))
        repository.change { it.capture("copied", true, false, false) }
        repository.change { it.clear() }
        assertTrue(prefs.getBoolean(InsertPrefs.HAD_CLIPS, false))
        assertEquals(ClipboardPanelState.EMPTY, clipboardPanelState(true, false, prefs.getBoolean(InsertPrefs.HAD_CLIPS, false), false))
    }
}
