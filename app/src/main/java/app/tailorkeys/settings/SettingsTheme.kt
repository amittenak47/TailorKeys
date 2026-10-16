package app.tailorkeys.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF2753E0), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3FF), onPrimaryContainer = Color(0xFF0B1F6B),
    secondary = Color(0xFF2753E0), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE3FF), onSecondaryContainer = Color(0xFF0B1F6B),
    background = Color(0xFFFDF9F2), onBackground = Color(0xFF1D1B16),
    surface = Color(0xFFFDF9F2), onSurface = Color(0xFF1D1B16),
    surfaceVariant = Color(0xFFE6DECE), onSurfaceVariant = Color(0xFF4D4639),
    surfaceContainer = Color(0xFFF0EADF), surfaceContainerLow = Color(0xFFF3EDE2),
    surfaceContainerHigh = Color(0xFFEBE4D6), surfaceContainerHighest = Color(0xFFE6DECE),
    outline = Color(0xFF7F7667), outlineVariant = Color(0xFFD5CCB9),
    error = Color(0xFFB3261E), onError = Color.White,
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFB6C4FF), onPrimary = Color(0xFF00277F),
    primaryContainer = Color(0xFF2443A1), onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFFB6C4FF), onSecondary = Color(0xFF00277F),
    secondaryContainer = Color(0xFF2443A1), onSecondaryContainer = Color(0xFFDCE3FF),
    background = Color(0xFF161410), onBackground = Color(0xFFEAE2D5),
    surface = Color(0xFF161410), onSurface = Color(0xFFEAE2D5),
    surfaceVariant = Color(0xFF494338), onSurfaceVariant = Color(0xFFD0C6B5),
    surfaceContainer = Color(0xFF231F19), surfaceContainerLow = Color(0xFF201C16),
    surfaceContainerHigh = Color(0xFF2D281F), surfaceContainerHighest = Color(0xFF383127),
    outline = Color(0xFF998F7F), outlineVariant = Color(0xFF494338),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)
private val SettingsTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

@Composable
fun SettingsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = SettingsTypography, content = content)
}
