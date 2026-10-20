package com.deepseek.dshmobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Palette mirrors ds-bot desktop (--bt-* variables). */
@Immutable
data class DsColors(
    val bg: Color,
    val surface: Color,
    val card: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val bubble: Color,
    val user: Color,
    val userInk: Color,
    val accent: Color,
    val danger: Color,
    val green: Color,
    val tagBg: Color,
    val dark: Boolean,
)

val LightDs = DsColors(
    bg = Color(0xFFFCFCFC), surface = Color(0xFFF7F7F7), card = Color(0xFFFFFFFF),
    ink = Color(0xFF141414), ink2 = Color(0x99141414), ink3 = Color(0x5C141414),
    line = Color(0x14141414), bubble = Color(0xFFEEEEEE), user = Color(0xFF070707),
    userInk = Color(0xFFFCFCFC), accent = Color(0xFF4D6BFE), danger = Color(0xFFE02135),
    green = Color(0xFF00C972), tagBg = Color(0x0F141414), dark = false,
)

val DarkDs = DsColors(
    bg = Color(0xFF141414), surface = Color(0xFF1B1B1B), card = Color(0xFF1F1F1F),
    ink = Color(0xFFF2F2F2), ink2 = Color(0x99F2F2F2), ink3 = Color(0x5CF2F2F2),
    line = Color(0x14FFFFFF), bubble = Color(0xFF262626), user = Color(0xFFF2F2F2),
    userInk = Color(0xFF141414), accent = Color(0xFF6B85FF), danger = Color(0xFFFF5566),
    green = Color(0xFF00C972), tagBg = Color(0x17FFFFFF), dark = true,
)

val LocalDs = staticCompositionLocalOf { LightDs }

@Composable
fun DsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkDs else LightDs
    val scheme = if (dark) darkColorScheme(
        primary = c.ink, onPrimary = c.bg, background = c.bg, onBackground = c.ink,
        surface = c.bg, onSurface = c.ink, surfaceVariant = c.bubble, onSurfaceVariant = c.ink2,
        outline = c.line, error = c.danger, secondary = c.accent,
    ) else lightColorScheme(
        primary = c.ink, onPrimary = c.bg, background = c.bg, onBackground = c.ink,
        surface = c.bg, onSurface = c.ink, surfaceVariant = c.bubble, onSurfaceVariant = c.ink2,
        outline = c.line, error = c.danger, secondary = c.accent,
    )
    CompositionLocalProvider(LocalDs provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
