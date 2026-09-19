package com.hackmit.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    secondary = TealLight,
    tertiary = Amber,
    error = Coral,
    background = SurfaceLight,
    surface = androidx.compose.ui.graphics.Color.White,
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = androidx.compose.ui.graphics.Color.Black,
    secondary = Teal,
    tertiary = Amber,
    error = Coral,
    background = SurfaceDark,
    surface = androidx.compose.ui.graphics.Color(0xFF1B2428),
)

@Composable
fun StrokeSenseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
