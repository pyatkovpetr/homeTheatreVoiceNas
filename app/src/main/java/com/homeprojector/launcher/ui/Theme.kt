package com.homeprojector.launcher.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Тёмная высококонтрастная палитра для проекции
val Bg = Color(0xFF0A0E14)
val Surface = Color(0xFF131A24)
val SurfaceVariant = Color(0xFF1C2634)
val Primary = Color(0xFF4FD1C5)
val Secondary = Color(0xFFECC94B)
val OnSurface = Color(0xFFE6EDF3)
val OnSurfaceDim = Color(0xFF9AA7B8)
val ErrorColor = Color(0xFFFC8181)
val SuccessColor = Color(0xFF68D391)

private val ColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color(0xFF06302C),
    secondary = Secondary,
    onSecondary = Color(0xFF3A2E00),
    background = Bg,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceDim,
    error = ErrorColor,
    onError = Color(0xFF3A0A0A),
    outline = Color(0xFF3A4656)
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Light, fontSize = 56.sp, lineHeight = 64.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 20.sp)
)

@Composable
fun ProjectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = AppTypography,
        content = content
    )
}