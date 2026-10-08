package com.embydog.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF146B52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC0F0DA),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF765B00),
    onSecondary = Color.White,
    background = Color(0xFFF8FAF9),
    surface = Color(0xFFF8FAF9),
    surfaceVariant = Color(0xFFDDE5E0),
    onSurface = Color(0xFF191C1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA4D4BF),
    onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF00513D),
    onPrimaryContainer = Color(0xFFC0F0DA),
    secondary = Color(0xFFEAC33F),
    onSecondary = Color(0xFF3D2F00),
    background = Color(0xFF111412),
    surface = Color(0xFF111412),
    surfaceVariant = Color(0xFF404943),
    onSurface = Color(0xFFE1E3E0),
)

@Composable
fun EmbyDogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
