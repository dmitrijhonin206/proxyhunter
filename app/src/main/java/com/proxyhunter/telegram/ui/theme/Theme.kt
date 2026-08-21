package com.proxyhunter.telegram.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Primary,
    background = Background,
    surface = Surface,
    onSurface = OnSurface,
    outline = OutlineLight,
    tertiary = StatusWorking,   // используется StatusDot для "рабочий" — см. ProxyListScreen
    error = StatusFailed,
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    outline = OutlineDark,
    tertiary = StatusWorking,
    error = StatusFailed,
)

// isDarkTheme = null → следовать системной теме (по умолчанию согласно ТЗ "поддержка
// тёмной и светлой темы"); явное true/false — когда пользователь переопределил в настройках.
@Composable
fun ProxyHunterTheme(
    isDarkTheme: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val useDark = isDarkTheme ?: isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        typography = ProxyHunterTypography,
        content = content,
    )
}
