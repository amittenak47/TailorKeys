package app.tailorkeys

import android.content.SharedPreferences
import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class LayoutSplitTest {
    private fun settings(): KeyboardSettings {
        val values = HashMap<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putInt", "putString", "putBoolean" -> { values[args[0] as String] = args[1]; editor }
                "remove" -> { values.remove(args[0]); editor }
                "apply" -> null
                "commit" -> true
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args[0])
                "getInt", "getString", "getBoolean" -> values[args[0]] ?: args[1]
                else -> error(method.name)
            }
        } as SharedPreferences
        return KeyboardSettings(prefs, true, "en")
    }

    @Test fun trialProfilesOverrideBothOrientationsAndRecoverWithoutChangingSize() {
        val settings = settings()
        settings.setSplit(false, true)
        settings.setSplit(true, false)
        val library = LayoutLibrary(settings).also { it.migrate() }
        val previous = settings.loadGrid().toJson()
        library.beginTrial(library.entries().first { it.preset == Template.STANDARD })
        assertFalse(settings.profile(false).split)
        assertFalse(settings.profile(true).split)
        assertTrue(settings.split(false))
        assertFalse(settings.split(true))
        LayoutLibrary(settings).migrate() // recovery after process death
        assertEquals(previous, settings.loadGrid().toJson())
        assertTrue(settings.profile(false).split)
        assertFalse(settings.profile(true).split)
        assertFalse(settings.prefs.contains(LayoutKeys.TRIAL_SPLIT))
    }

    @Test fun useAndAcceptTrialApplyStoredSplitInTheCurrentOrLinkedOrientations() {
        val settings = settings()
        settings.setSplit(false, false)
        settings.setSplit(true, false)
        val library = LayoutLibrary(settings) { true }.also { it.migrate() }
        val standard = library.entries().first { it.preset == Template.STANDARD }
        library.use(library.entries().first { it.preset == Template.SPLIT })
        assertTrue(settings.split(true))
        assertFalse(settings.split(false))
        val saved = library.add("Desk", KeyGrid.default())
        assertTrue(saved.split)
        library.use(standard)
        assertFalse(settings.split(true))
        val restored = LayoutLibrary(settings) { true }.entries().first { it.key == saved.key }
        library.beginTrial(restored)
        assertTrue(settings.profile(true).split)
        assertFalse(settings.split(true))
        settings.set(Prefs.SAME_ORIENTATIONS, true)
        library.use(restored)
        library.restoreTrial()
        assertTrue(settings.profile(false).split)
        assertTrue(settings.profile(true).split)
        library.use(standard)
        assertFalse(settings.profile(false).split)
        assertFalse(settings.profile(true).split)
    }
}
