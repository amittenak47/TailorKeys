package app.tailorkeys

import org.junit.Assert.*
import org.junit.Test

class CustomThemeTest {
    @Test fun jsonRoundTripUsesPortableHexAndPreservesIdentity() {
        val themes = listOf(CustomTheme("sand", "Sand", ThemeColors.from(Palette.of(ThemeName.WARM))),
            CustomTheme("night", "Night", ThemeColors.from(Palette.of(ThemeName.MIDNIGHT))),
            CustomTheme("outlined", "Outlined", ThemeColors.from(Palette.of(ThemeName.HIGH_CONTRAST))),
            CustomTheme("faint", "Faint", ThemeColors.from(Palette.of(ThemeName.HIGH_CONTRAST)).copy(keyOutline = 0x40FFFFFF)))
        val json = CustomTheme.encode(themes)
        assertTrue(json.contains("#E9E2D6"))
        assertTrue(json.contains("#00000000"))
        assertTrue(json.contains("#40FFFFFF"))
        assertEquals(themes, CustomTheme.decode(json))
        assertEquals(emptyList<CustomTheme>(), CustomTheme.decode("broken"))
        assertEquals(themes, CustomTheme.decode(json.dropLast(1) + ",{\"id\":\"bad\"}]"))
    }
    @Test fun oldThemesDefaultToTransparentOutlines() {
        val original = CustomTheme("old", "Old", ThemeColors.from(Palette.of(ThemeName.WARM)))
        val array = org.json.JSONArray(CustomTheme.encode(listOf(original)))
        array.getJSONObject(0).getJSONObject("colors").remove("keyOutline")
        assertEquals(listOf(original), CustomTheme.decode(array.toString()))
    }
    @Test fun outlinesAreCopiedAndBlendWarningsUseVisibleContrast() {
        val high = ThemeColors.from(Palette.of(ThemeName.HIGH_CONTRAST))
        assertEquals(0xFFFFFFFF.toInt(), high.keyOutline)
        assertFalse(high.keysBlend)
        assertTrue(high.copy(keyOutline = 0).keysBlend)
        assertTrue(high.copy(keyOutline = 0xFF101010.toInt()).keysBlend)
        assertTrue(high.copy(keyOutline = 0x10FFFFFF).keysBlend)
        assertFalse(high.copy(key = 0xFFFFFFFF.toInt(), keyOutline = 0).keysBlend)
        ThemeName.entries.filter { it != ThemeName.HIGH_CONTRAST }.forEach {
            assertEquals(0, ThemeColors.from(Palette.of(it)).keyOutline)
        }
        assertEquals(high.keyOutline, high.palette().keyOutline)
    }
    @Test fun gearMeetsNonTextContrastForEveryBuiltInAndCustomFallback() {
        for (theme in ThemeName.entries) for (night in listOf(false, true)) {
            val palette = Palette.of(theme, night)
            assertTrue("$theme/$night", contrastRatio(palette.gear, palette.background) >= 3.0)
        }
        for (background in listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF777777.toInt())) {
            val palette = Palette.of(ThemeName.WARM).copy(background = background, text = background, hint = background)
            assertTrue(contrastRatio(palette.gear, background) >= 3.0)
        }
    }
    @Test fun wcagLinearizesSrgbAndIsSymmetric() {
        val black = 0xFF000000.toInt(); val white = 0xFFFFFFFF.toInt(); val gray = 0xFF777777.toInt()
        assertEquals(21.0, contrastRatio(black, white), .00001)
        assertEquals(1.0, contrastRatio(gray, gray), .00001)
        assertEquals(4.478, contrastRatio(gray, white), .001)
        assertEquals(contrastRatio(gray, white), contrastRatio(white, gray), .00001)
        assertTrue(ThemeColors.from(Palette.of(ThemeName.WARM)).checks().any { it.second < 4.5 })
    }
    @Test fun customResolutionAndDeletionFallback() {
        val theme = CustomTheme("sand", "Sand", ThemeColors.from(Palette.of(ThemeName.MIDNIGHT)))
        assertEquals(theme.colors.palette(), Palette.resolve("custom:sand", listOf(theme)))
        assertEquals(Palette.of(ThemeName.WARM), Palette.resolve("custom:sand", emptyList()))
        assertEquals(Palette.of(ThemeName.WARM), Palette.resolve("custom:missing", listOf(theme), night = true))
        assertEquals(Palette.of(ThemeName.HIGH_CONTRAST), Palette.resolve("HIGH_CONTRAST", listOf(theme)))
    }
    @Test fun prefsAndSavedLayoutKeepCustomIdsAndFallBackAfterDeletion() {
        val values = HashMap<String, Any?>()
        lateinit var editor: android.content.SharedPreferences.Editor
        editor = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(android.content.SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putInt", "putString", "putBoolean" -> { values[args[0] as String] = args[1]; editor }
                "remove" -> { values.remove(args[0]); editor }
                "apply" -> null
                "commit" -> true
                else -> error(method.name)
            }
        } as android.content.SharedPreferences.Editor
        val prefs = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getAll" -> values.toMap()
                "contains" -> values.containsKey(args[0])
                "getInt", "getString", "getBoolean" -> values[args[0]] ?: args[1]
                else -> error(method.name)
            }
        } as android.content.SharedPreferences
        val settings = KeyboardSettings(prefs, true, "en")
        val library = LayoutLibrary(settings).also { it.migrate() }
        val theme = CustomTheme("sand", "Sand", ThemeColors.from(Palette.of(ThemeName.WARM)))
        settings.saveTheme(theme)
        assertEquals("custom:sand", settings.themeId())
        settings.setTheme(ThemeName.HIGH_CONTRAST); settings.cycleQuickTheme()
        assertEquals(theme.selection, settings.themeId())
        settings.cycleQuickTheme(); assertEquals(ThemeName.SYSTEM.name, settings.themeId())
        settings.setThemeId(theme.selection)
        val saved = library.add("Desk", settings.loadGrid())
        assertEquals("custom:sand", org.json.JSONObject(requireNotNull(saved.settings)).getString("theme"))
        settings.setTheme(ThemeName.LIGHT)
        settings.deleteTheme(theme.id)
        assertEquals(ThemeName.LIGHT.name, settings.themeId())
        library.use(saved)
        assertEquals(ThemeName.WARM.name, settings.themeId())
        settings.saveTheme(theme); settings.deleteTheme(theme.id)
        assertEquals(ThemeName.WARM.name, settings.themeId())
        assertEquals(emptyList<CustomTheme>(), settings.customThemes())
        // Legacy built-in names still load, including High contrast.
        settings.applyLayoutSettings("""{"theme":"HIGH_CONTRAST"}""")
        assertEquals(ThemeName.HIGH_CONTRAST, settings.theme())
    }

}
