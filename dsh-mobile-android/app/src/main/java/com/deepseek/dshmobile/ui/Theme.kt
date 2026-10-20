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
import androidx.compose.ui.unit.dp

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
    val input: Color,
    val secondary: Color,
    /** Grouped cards (settings, sheets), quote chip. */
    val group: Color,
    /** Code blocks and table header. */
    val code: Color,
    /** Links only. */
    val link: Color,
    val dark: Boolean,
)

val LightDs = DsColors(
    bg = Color(0xFFFAFAFA), surface = Color(0xFFF5F5F5), card = Color(0xFFFFFFFF),
    ink = Color(0xFF0B0B0B), ink2 = Color(0xFF6E6E73), ink3 = Color(0xFFA8A8AD),
    line = Color(0x140B0B0B), bubble = Color(0xFFF0F0F0), user = Color(0xFF0B0B0B),
    userInk = Color(0xFFFFFFFF), accent = Color(0xFF4D6BFE), danger = Color(0xFFE02135),
    green = Color(0xFF00C972), tagBg = Color(0x0F0B0B0B), input = Color(0xFFFFFFFF),
    secondary = Color(0xFFE6E7E6), group = Color(0xFFF0F0F0), code = Color(0xFFE4E4E4),
    link = Color(0xFF1A73E8), dark = false,
)

val DarkDs = DsColors(
    bg = Color(0xFF0E0E0E), surface = Color(0xFF1A1A1A), card = Color(0xFF2A2A2A),
    ink = Color(0xFFF2F2F2), ink2 = Color(0xFFA1A1A6), ink3 = Color(0xFF6E6E73),
    line = Color(0x1AFFFFFF), bubble = Color(0xFF222222), user = Color(0xFFF2F2F2),
    userInk = Color(0xFF0B0B0B), accent = Color(0xFF6B85FF), danger = Color(0xFFFF5566),
    green = Color(0xFF00C972), tagBg = Color(0x17FFFFFF), input = Color(0xFF1C1C1C),
    secondary = Color(0xFF2C2C2C), group = Color(0xFF1C1C1C), code = Color(0xFF2E2E2E),
    link = Color(0xFF6AA8FF), dark = true,
)

val LocalDs = staticCompositionLocalOf { LightDs }

@Composable
fun DsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkDs else LightDs
    val scheme = if (dark) darkColorScheme(
        primary = c.ink, onPrimary = c.bg, background = c.bg, onBackground = c.ink,
        surface = c.bg, onSurface = c.ink, surfaceVariant = c.bubble, onSurfaceVariant = c.ink2,
        outline = c.line, error = c.danger, secondary = c.accent, surfaceContainer = c.card,
    ) else lightColorScheme(
        primary = c.ink, onPrimary = c.bg, background = c.bg, onBackground = c.ink,
        surface = c.bg, onSurface = c.ink, surfaceVariant = c.bubble, onSurfaceVariant = c.ink2,
        outline = c.line, error = c.danger, secondary = c.accent, surfaceContainer = c.card,
    )
    CompositionLocalProvider(LocalDs provides c) {
        MaterialTheme(
            colorScheme = scheme,
            // Popovers and menus: white rounded cards.
            shapes = androidx.compose.material3.Shapes(extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
            content = content,
        )
    }
}

/** ds-bot ink colors (src/client/inks.js): light value, dark value. */
private val INKS = mapOf(
    "deepseek" to (0xFF4D6BFE to 0xFF6A84FF), "charcoal" to (0xFF141416 to 0xFFF1F2F4), "graphite" to (0xFF2D2D2D to 0xFFD6D6DA),
    "rose" to (0xFFEC2F6B to 0xFFFF5A7A), "sky" to (0xFF0AA8D6 to 0xFF2EE6E0), "tangerine" to (0xFFFF6900 to 0xFFFF8A2E),
    "cobalt" to (0xFF082DFF to 0xFF5C78FF), "black" to (0xFF000000 to 0xFFFFFFFF), "brown" to (0xFFA27952 to 0xFF855C36),
    "red" to (0xFFFF3E51 to 0xFFE02135), "orange" to (0xFFFF781C to 0xFFFF6700), "yellow" to (0xFFFFAF38 to 0xFFFF9800),
    "green" to (0xFF00C972 to 0xFF009957), "cyan" to (0xFF1CC3B0 to 0xFF00A592), "blue" to (0xFF2A92FE to 0xFF0E74E0),
    "violet" to (0xFFA97EFE to 0xFF804EE0), "magenta" to (0xFFFF5EB1 to 0xFFE02A88), "gray" to (0xFF959595 to 0xFF777777),
    "aurora" to (0xFF00B8D4 to 0xFF2EE6E0),
)
private val LEGACY = mapOf("kimi" to "charcoal", "zai" to "graphite", "minimax" to "rose", "stepfun" to "sky", "mimo" to "tangerine", "qwen" to "cobalt")

/** Pure: a ds-bot color id or #rrggbb as ARGB, or null when unknown. */
fun inkArgb(color: String?, dark: Boolean = false): Long? {
    if (color.isNullOrBlank()) return null
    if (Regex("^#[0-9a-fA-F]{6}$").matches(color)) return 0xFF000000L or color.substring(1).toLong(16)
    val pair = INKS[color] ?: INKS[LEGACY[color]] ?: return null
    return if (dark) pair.second else pair.first
}

@androidx.compose.runtime.Composable
fun inkColor(color: String?): Color? = inkArgb(color, LocalDs.current.dark)?.let { Color(it) }
