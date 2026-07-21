package com.contextbubble.app.ui.theme

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

val Ink = Color(0xFF111827)
val Night = Color(0xFF182234)
val Cloud = Color(0xFFF7F8FC)
val Paper = Color(0xFFFFFFFF)
val Cobalt = Color(0xFF5C64F4)
val Cyan = Color(0xFF62D4E7)
val Coral = Color(0xFFFF7369)
val Mint = Color(0xFF62D5A5)
val Mist = Color(0xFFE7EAF2)
val Slate = Color(0xFF667085)

private val LightColors = lightColorScheme(
    primary = Cobalt,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E9FF),
    onPrimaryContainer = Ink,
    secondary = Color(0xFF237D8D),
    secondaryContainer = Color(0xFFD9F7FB),
    tertiary = Coral,
    error = Color(0xFFC63D3D),
    background = Cloud,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFEEF0F6),
    onSurfaceVariant = Slate,
    outline = Color(0xFFCBD0DC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEB3FF),
    onPrimary = Color(0xFF202778),
    secondary = Cyan,
    tertiary = Color(0xFFFFB0A9),
    background = Color(0xFF0E1420),
    onBackground = Color(0xFFF0F2F8),
    surface = Night,
    onSurface = Color(0xFFF0F2F8),
    surfaceVariant = Color(0xFF263247),
    onSurfaceVariant = Color(0xFFC9D0DE),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        lineHeight = 36.sp,
        letterSpacing = (-1.1).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 23.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp,
    ),
)

@Composable
fun ContextBubbleTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

