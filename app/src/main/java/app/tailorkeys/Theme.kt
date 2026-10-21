package app.tailorkeys

import android.content.Context
import android.content.res.Configuration

enum class ThemeName(val title: String) {
    SYSTEM("Match system"), LIGHT("Light"), DARK("Dark"), MIDNIGHT("Midnight"), WARM("Warm"), HIGH_CONTRAST("High contrast")
}

data class Palette(
    val background: Int,
    val key: Int,
    val functionKey: Int,
    val pressed: Int,
    val text: Int,
    val hint: Int,
    val accent: Int,
    val onAccent: Int,
) {
    /** Text uses a contrast-safe variant; fills and selection outlines retain the real accent. */
    val smallAccent: Int get() = when (accent) {
        0xFFC2562B.toInt() -> 0xFF9C4A28.toInt()
        0xFF5B8DEF.toInt() -> 0xFF8DB1FF.toInt()
        else -> accent
    }
    companion object {
        fun of(context: Context, name: ThemeName): Palette {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return of(name, night)
        }

        fun of(context: Context, settings: KeyboardSettings): Palette = resolve(settings.themeId(), settings.customThemes(),
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)

        fun resolve(id: String, themes: List<CustomTheme>, night: Boolean = false): Palette {
            if (id.startsWith("custom:")) return themes.firstOrNull { it.selection == id }?.colors?.palette() ?: of(ThemeName.WARM, night)
            return of(ThemeName.entries.firstOrNull { it.name == id } ?: ThemeName.SYSTEM, night)
        }

        fun of(name: ThemeName, night: Boolean = false): Palette {
            return when (if (name == ThemeName.SYSTEM) (if (night) ThemeName.DARK else ThemeName.LIGHT) else name) {
                ThemeName.HIGH_CONTRAST -> Palette(c(0xFF000000), c(0xFF000000), c(0xFF000000), c(0xFF000000), c(0xFFFFFFFF), c(0xFFFFFFFF), c(0xFFFFD400), c(0xFF000000))
                ThemeName.DARK -> Palette(c(0xFF1B1C1F), c(0xFF3A3B40), c(0xFF2A2B30), c(0xFF5A5C63), c(0xFFF1F1F3), c(0xFF9A9BA2), c(0xFF5B8DEF), c(0xFFFFFFFF))
                ThemeName.MIDNIGHT -> Palette(c(0xFF000000), c(0xFF17181B), c(0xFF0E0F11), c(0xFF34363C), c(0xFFEDEDEF), c(0xFF7C7E86), c(0xFF7AA2FF), c(0xFF000000))
                ThemeName.WARM -> Palette(c(0xFFE9E2D6), c(0xFFFBF8F2), c(0xFFD3C9B8), c(0xFFC7BBA6), c(0xFF2B2620), c(0xFF7A6F5F), c(0xFFC2562B), c(0xFFFFFFFF))
                else -> Palette(c(0xFFE4E6EB), c(0xFFFFFFFF), c(0xFFC9CCD3), c(0xFFB0B4BD), c(0xFF1B1C1F), c(0xFF6B6E76), c(0xFF2F6BDB), c(0xFFFFFFFF))
            }
        }

        private fun c(v: Long) = v.toInt()
    }
}


/** Six opaque sRGB colours. Hex strings keep the JSON identical on Android and iOS. */
data class ThemeColors(val background: Int, val key: Int, val functionKey: Int, val text: Int, val accent: Int, val pressed: Int) {
    fun palette() = Palette(background, key, functionKey, pressed, text, text, accent,
        if (contrastRatio(0xFF000000.toInt(), accent) >= contrastRatio(0xFFFFFFFF.toInt(), accent)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    fun values() = listOf(background, key, functionKey, text, accent, pressed)
    fun replacing(index: Int, value: Int): ThemeColors {
        val v = values().toMutableList().apply { this[index] = value }
        return ThemeColors(v[0], v[1], v[2], v[3], v[4], v[5])
    }
    fun checks() = listOf("Text on keys" to contrastRatio(text, key), "Text on background" to contrastRatio(text, background),
        "Text on function keys" to contrastRatio(text, functionKey), "Accent on keys" to contrastRatio(accent, key))
    companion object {
        val labels = listOf("Background", "Keys", "Function keys", "Text", "Accent", "Pressed")
        val keys = listOf("background", "key", "functionKey", "text", "accent", "pressed")
        fun from(p: Palette) = ThemeColors(p.background, p.key, p.functionKey, p.text, p.accent, p.pressed)
        fun parseHex(value: String): Int? = value.removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLongOrNull(16)?.toInt()?.or(0xFF000000.toInt())
        fun hex(value: Int) = "#%06X".format(java.util.Locale.ROOT, value and 0xFFFFFF)
    }
}

data class CustomTheme(val id: String, val name: String, val colors: ThemeColors) {
    val selection get() = "custom:$id"
    companion object {
        fun encode(themes: List<CustomTheme>): String = org.json.JSONArray().apply {
            themes.forEach { theme -> put(org.json.JSONObject().put("id", theme.id).put("name", theme.name).put("colors",
                org.json.JSONObject().apply { ThemeColors.keys.zip(theme.colors.values()).forEach { (k, v) -> put(k, ThemeColors.hex(v)) } })) }
        }.toString()
        fun decode(json: String?): List<CustomTheme> = runCatching {
            val array = org.json.JSONArray(json ?: "[]")
            (0 until array.length()).mapNotNull { i -> runCatching {
                val item = array.getJSONObject(i); val colors = item.getJSONObject("colors")
                val v = ThemeColors.keys.map { requireNotNull(ThemeColors.parseHex(colors.getString(it))) }
                val id = item.getString("id").also { require(it.isNotBlank()) }
                CustomTheme(id, item.getString("name").trim().take(24).ifEmpty { "Custom" }, ThemeColors(v[0], v[1], v[2], v[3], v[4], v[5]))
            }.getOrNull() }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }
}

/** WCAG 2 relative luminance after sRGB linearisation; opaque colours only. */
fun contrastRatio(first: Int, second: Int): Double {
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color ushr shift) and 255) / 255.0
            return if (c <= .04045) c / 12.92 else Math.pow((c + .055) / 1.055, 2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    val a = luminance(first); val b = luminance(second)
    return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
}
