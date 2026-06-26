package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MetroColorScheme = darkColorScheme(
    primary = MetroYellow,
    secondary = MetroYellow,
    tertiary = MetroRed,
    background = MetroDarkBackground,
    surface = MetroDarkSurface,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = MetroLightText,
    onSurface = MetroLightText
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force Metro Dark Mode always for authentic Windows Phone feel
    dynamicColor: Boolean = false, // Disable dynamic colors to preserve Metro yellow accent
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MetroColorScheme,
        typography = Typography,
        content = content
    )
}
