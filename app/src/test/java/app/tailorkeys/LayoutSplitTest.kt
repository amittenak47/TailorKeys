package app.tailorkeys

import android.content.SharedPreferences
import app.tailorkeys.layout.KeyGrid
import app.tailorkeys.layout.Template
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class LayoutSplitTest {
    @Test fun customLayoutsCaptureBothOrientationsAndLookAndStayInSync() {
        val s = settings()
        s.setFor(false, Prefs::height, 52); s.setFor(true, Prefs::height, 66)
        s.setFor(true, Prefs::width, 73); s.setAlignFor(true, Align.RIGHT)
        s.setFor(false, Prefs::bottomPadding, 17); s.setFor(true, Prefs::splitGap, 29)
        s.setFor(true, Prefs::tilt, -7); s.set(Prefs.CURVE, 91)
        s.set(Prefs.KEY_GAP, 9); s.setTheme(ThemeName.WARM)
        s.set(Prefs.CORNER_RADIUS, 14); s.set(Prefs.TEXT_SCALE, 130)
        s.set(Prefs.SHOW_HINTS, false); s.set(Prefs.FOLLOW_SYSTEM_TEXT, false)
        val library = LayoutLibrary(s).also { it.migrate() }
        val expected = s.layoutSettings()
        val entry = library.add("Desk", s.loadGrid())
        assertTrue(LayoutLibrary.sameSettings(expected, entry.settings!!))
        s.setFor(false, Prefs::height, 30); s.setTheme(ThemeName.DARK)
        library.use(entry)
        assertTrue(LayoutLibrary.sameSettings(expected, s.layoutSettings()))
        s.setTheme(ThemeName.MIDNIGHT); s.setFor(true, Prefs::width, 80)
        val updated = library.entries().single { it.key == entry.key }
        assertEquals("MIDNIGHT", JSONObject(updated.settings!!).getString("theme"))
        assertEquals(80, JSONObject(updated.settings).getInt("width_l"))
        library.use(library.entries().first { it.preset == Template.STANDARD })
        assertEquals(ThemeName.MIDNIGHT, s.theme())
        assertEquals(80, s.int(Prefs.width(true)))
        library.use(updated)
        assertEquals(80, s.int(Prefs.width(true)))
    }

    @Test fun trialsRestoreSettingsAfterProcessDeathAndDoNotOverwriteThePreviousLayout() {
        val s = settings(); val library = LayoutLibrary(s).also { it.migrate() }
        val a = library.add("A", s.loadGrid()); library.use(a)
        s.setTheme(ThemeName.WARM); s.setFor(true, Prefs::height, 64)
        val previous = s.layoutSettings()
        val b = library.add("B", s.loadGrid(), settings = JSONObject(previous).put("theme", "DARK").put("height_l", 35).toString())
        library.beginTrial(b)
        assertEquals(ThemeName.DARK, s.theme()); assertEquals(35, s.int(Prefs.height(true)))
        s.setTheme(ThemeName.LIGHT)
        assertEquals("WARM", JSONObject(library.entries().first { it.key == a.key }.settings!!).getString("theme"))
        LayoutLibrary(s).migrate()
        assertTrue(LayoutLibrary.sameSettings(previous, s.layoutSettings()))
        assertNull(s.prefs.getString(LayoutKeys.TRIAL_SETTINGS, null))
        library.beginTrial(b); library.use(b); library.restoreTrial()
        assertEquals(ThemeName.DARK, s.theme())
    }

    @Test fun draftAndCodesKeepSettingsAndOldEntriesUseCurrentSettings() {
        val s = settings(); val library = LayoutLibrary(s).also { it.migrate() }
        val draft = library.startDraft(s.loadGrid()); library.use(draft)
        s.set(Prefs.CORNER_RADIUS, 18); s.setFor(true, Prefs::tilt, 8)
        val saved = library.saveDraft("Tablet")
        assertEquals(18, JSONObject(saved.settings!!).getInt("corner_radius"))
        val decoded = KeyGrid.decodeCode(saved.code())!!
        assertEquals(saved.json, decoded.grid.toJson())
        assertTrue(LayoutLibrary.sameSettings(saved.settings, decoded.settings!!))
        assertNull(KeyGrid.decodeCode(saved.grid().toCode())!!.settings)
        val wire = JSONArray(s.prefs.getString(LayoutKeys.SAVED, null))
        wire.getJSONObject(0).remove("settings")
        s.prefs.edit().putString(LayoutKeys.SAVED, wire.toString()).commit()
        s.setTheme(ThemeName.DARK) // active custom acquires a snapshot on the first change
        assertEquals("DARK", JSONObject(library.entries().first { it.key == saved.key }.settings!!).getString("theme"))
        wire.getJSONObject(0).remove("settings")
        s.prefs.edit().putString(LayoutKeys.SAVED, wire.toString()).commit()
        val old = library.entries().first { it.key == saved.key }
        assertNull(old.settings)
        library.use(old)
        assertEquals(ThemeName.DARK, s.theme())
        assertEquals(18, s.int(Prefs.CORNER_RADIUS))
    }

    @Test fun importedSettingsAreBoundedAndCannotChangeTypingPreferences() {
        val s = settings()
        s.applyLayoutSettings("""{"height_p":999999999999,"text_scale":5,"show_hints":false,"theme":"UNKNOWN","swipe_typing":false,"auto_cap":false}""")
        assertEquals(70, s.int(Prefs.height(false)))
        assertEquals(60, s.int(Prefs.TEXT_SCALE))
        assertFalse(s.bool(Prefs.SHOW_HINTS))
        assertTrue(s.bool(Prefs.SWIPE_TYPING)); assertTrue(s.bool(Prefs.AUTO_CAP))
        assertEquals(ThemeName.SYSTEM, s.theme())
    }

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
        assertTrue(settings.split(true)) // custom trials apply their complete settings snapshot
        settings.set(Prefs.SAME_ORIENTATIONS, true)
        library.use(restored)
        library.restoreTrial()
        assertFalse(settings.profile(false).split) // the saved portrait settings remain independent
        assertTrue(settings.profile(true).split)
        library.use(standard)
        assertFalse(settings.profile(false).split)
        assertFalse(settings.profile(true).split)
    }
}
