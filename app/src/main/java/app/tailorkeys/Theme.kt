package app.tailorkeys

import android.content.Context
import android.content.res.Configuration

enum class ThemeName(val title: String) {
    SYSTEM("Match system"), LIGHT("Light"), DARK("Dark"), MIDNIGHT("Midnight"), WARM("Warm"), HIGH_CONTRAST("High contrast")
}

class Palette(
    val background: Int,
    val key: Int,
    val functionKey: Int,
    val pressed: Int,
    val text: Int,
    val hint: Int,
    val accent: Int,
    val onAccent: Int,
) {
    companion object {
        fun of(context: Context, name: ThemeName): Palette {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
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
