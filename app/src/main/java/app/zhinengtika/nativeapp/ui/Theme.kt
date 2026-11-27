package app.zhinengtika.nativeapp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.zhinengtika.nativeapp.data.UiPreferences

private val LightColors = lightColorScheme(
    primary = Color(0xFF226B53),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EADF),
    onPrimaryContainer = Color(0xFF103B2D),
    secondary = Color(0xFF356E8A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5EAF4),
    tertiary = Color(0xFFB5483C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF9DDD7),
    background = Color(0xFFF5F7F5),
    surface = Color(0xFFFCFDFC),
    surfaceVariant = Color(0xFFE6ECE8),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF91D2B5),
    onPrimary = Color(0xFF073A2B),
    secondary = Color(0xFF9BCDE5),
    tertiary = Color(0xFFFFB4A6),
)

@Composable
fun ZhiNengTiKaTheme(
    themeMode: UiPreferences.ThemeMode = UiPreferences.ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        UiPreferences.ThemeMode.SYSTEM -> isSystemInDarkTheme()
        UiPreferences.ThemeMode.LIGHT -> false
        UiPreferences.ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
