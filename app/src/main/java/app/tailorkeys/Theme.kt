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
    val keyOutline: Int = 0,
) {
    /** The strip uses background. Even an unreadable custom text colour must leave the gear visible. */
    val gear: Int get() = listOf(hint, text, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        .map { it or 0xFF000000.toInt() }.first { contrastRatio(it, background) >= 3.0 }
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
                ThemeName.HIGH_CONTRAST -> Palette(c(0xFF000000), c(0xFF000000), c(0xFF000000), c(0xFF000000), c(0xFFFFFFFF), c(0xFFFFFFFF), c(0xFFFFD400), c(0xFF000000), c(0xFFFFFFFF))
                ThemeName.DARK -> Palette(c(0xFF1B1C1F), c(0xFF3A3B40), c(0xFF2A2B30), c(0xFF5A5C63), c(0xFFF1F1F3), c(0xFF9A9BA2), c(0xFF5B8DEF), c(0xFFFFFFFF))
                ThemeName.MIDNIGHT -> Palette(c(0xFF000000), c(0xFF17181B), c(0xFF0E0F11), c(0xFF34363C), c(0xFFEDEDEF), c(0xFF7C7E86), c(0xFF7AA2FF), c(0xFF000000))
                ThemeName.WARM -> Palette(c(0xFFE9E2D6), c(0xFFFBF8F2), c(0xFFD3C9B8), c(0xFFC7BBA6), c(0xFF2B2620), c(0xFF7A6F5F), c(0xFFC2562B), c(0xFFFFFFFF))
                else -> Palette(c(0xFFE4E6EB), c(0xFFFFFFFF), c(0xFFC9CCD3), c(0xFFB0B4BD), c(0xFF1B1C1F), c(0xFF6B6E76), c(0xFF2F6BDB), c(0xFFFFFFFF))
            }
        }

        private fun c(v: Long) = v.toInt()
    }
}


/** Opaque sRGB fills plus an optional ARGB key outline, shared with iOS. */
data class ThemeColors(val background: Int, val key: Int, val functionKey: Int, val text: Int, val accent: Int, val pressed: Int, val keyOutline: Int = 0) {
    fun palette() = Palette(background, key, functionKey, pressed, text, text, accent,
        if (contrastRatio(0xFF000000.toInt(), accent) >= contrastRatio(0xFFFFFFFF.toInt(), accent)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), keyOutline)
    fun values() = listOf(background, key, functionKey, text, accent, pressed, keyOutline)
    fun replacing(index: Int, value: Int): ThemeColors {
        val v = values().toMutableList().apply { this[index] = value }
        return ThemeColors(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
    }
    fun checks(): List<ThemeCheck> {
        val outline = contrastRatio(compositeOn(keyOutline, background), background)
        val outlinePasses = contrastRatio(key, background) < 1.2 && outline >= 3.0
        return listOf(
            ThemeCheck("Text on keys", contrastRatio(text, key), 4.5, listOf(3, 1), 3, key),
            ThemeCheck("Text on function keys", contrastRatio(text, functionKey), 4.5, listOf(3, 2), 3, functionKey),
            ThemeCheck("Enter key label", contrastRatio(palette().onAccent, accent), 4.5, listOf(4), 4, palette().onAccent),
            ThemeCheck(if (outlinePasses) "Keys against background (outline)" else "Keys against background",
                if (outlinePasses) outline else contrastRatio(key, background), if (outlinePasses) 3.0 else 1.2, listOf(1, 0, 6), 1, background),
            ThemeCheck("Pressed against keys", contrastRatio(pressed, key), 1.2, listOf(5, 1), 5, key))
    }
    val keysBlend: Boolean get() = !checks()[3].passes
    companion object {
        val labels = listOf("Background", "Keys", "Function keys", "Text", "Accent", "Pressed", "Key outline")
        val keys = listOf("background", "key", "functionKey", "text", "accent", "pressed", "keyOutline")
        fun from(p: Palette) = ThemeColors(p.background, p.key, p.functionKey, p.text, p.accent, p.pressed, p.keyOutline)
        fun parseHex(value: String, alpha: Boolean = false): Int? {
            val hex = value.removePrefix("#")
            if (!hex.matches(Regex(if (alpha) "(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})" else "[0-9a-fA-F]{6}"))) return null
            return hex.toLong(16).toInt().let { if (hex.length == 6) it or 0xFF000000.toInt() else it }
        }
        fun hex(value: Int) = if (value ushr 24 == 255) "#%06X".format(java.util.Locale.ROOT, value and 0xFFFFFF)
            else "#%08X".format(java.util.Locale.ROOT, value)
    }
}

data class ThemeCheck(val label: String, val ratio: Double, val target: Double, val channels: List<Int>, val fixChannel: Int, val background: Int) {
    val passes get() = ratio >= target
    val warning get() = when {
        target == 4.5 -> "Hard to read. Aim for 4.5 : 1 or more."
        fixChannel == 5 -> "Pressed keys look the same. Aim for 1.2 : 1 or more."
        else -> "Keys blend into the background. Aim for 1.2 : 1, or a 3 : 1 outline."
    }
}

/** HSL hue in degrees, saturation and lightness. Pure sRGB math for portable suggestions. */
fun themeHsl(color: Int): DoubleArray {
    val r = ((color ushr 16) and 255) / 255.0; val g = ((color ushr 8) and 255) / 255.0; val b = (color and 255) / 255.0
    val high = maxOf(r, g, b); val low = minOf(r, g, b); val delta = high - low; val light = (high + low) / 2
    val hue = if (delta == 0.0) 0.0 else when (high) { r -> ((g - b) / delta + 6) % 6; g -> (b - r) / delta + 2; else -> (r - g) / delta + 4 } * 60
    return doubleArrayOf(hue, if (delta == 0.0) 0.0 else delta / (1 - kotlin.math.abs(2 * light - 1)), light)
}

private fun themeRgb(hue: Double, saturation: Double, light: Double): Int {
    val chroma = (1 - kotlin.math.abs(2 * light - 1)) * saturation
    val x = chroma * (1 - kotlin.math.abs((hue / 60) % 2 - 1)); val m = light - chroma / 2
    val rgb = when ((hue / 60).toInt()) { 0 -> doubleArrayOf(chroma, x, 0.0); 1 -> doubleArrayOf(x, chroma, 0.0); 2 -> doubleArrayOf(0.0, chroma, x); 3 -> doubleArrayOf(0.0, x, chroma); 4 -> doubleArrayOf(x, 0.0, chroma); else -> doubleArrayOf(chroma, 0.0, x) }
    fun channel(i: Int) = kotlin.math.floor((rgb[i] + m) * 255 + .5).toInt().coerceIn(0, 255)
    return 0xFF000000.toInt() or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
}

/** Nearest lightness step meeting the target without shifting the quantized hue. */
fun closestReadable(fg: Int, bg: Int, target: Double): Int {
    if (contrastRatio(fg, bg) >= target) return fg
    val hsl = themeHsl(fg)
    for (step in 1..10_000) for (direction in listOf(-1, 1)) {
        val light = hsl[2] + direction * step / 10_000.0
        if (light !in 0.0..1.0) continue
        val candidate = themeRgb(hsl[0], hsl[1], light)
        val diff = kotlin.math.abs(themeHsl(candidate)[0] - hsl[0])
        if (contrastRatio(candidate, bg) >= target && minOf(diff, 360 - diff) <= 1) return candidate
    }
    return listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()).maxBy { contrastRatio(it, bg) }
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
                val v = ThemeColors.keys.map { key -> if (key == "keyOutline" && !colors.has(key)) 0
                    else requireNotNull(ThemeColors.parseHex(colors.getString(key), alpha = key == "keyOutline")) }
                val id = item.getString("id").also { require(it.isNotBlank()) }
                CustomTheme(id, item.getString("name").trim().take(24).ifEmpty { "Custom" }, ThemeColors(v[0], v[1], v[2], v[3], v[4], v[5], v[6]))
            }.getOrNull() }.distinctBy { it.id }
        }.getOrDefault(emptyList())
    }
}

/** Composite a possibly transparent outline before measuring its visible contrast. */
fun compositeOn(color: Int, background: Int): Int {
    val alpha = (color ushr 24) / 255.0
    fun channel(shift: Int) = ((((color ushr shift) and 255) * alpha + ((background ushr shift) and 255) * (1 - alpha)).toInt()) shl shift
    return 0xFF000000.toInt() or channel(16) or channel(8) or channel(0)
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
