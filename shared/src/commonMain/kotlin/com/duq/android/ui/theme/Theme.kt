package com.duq.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Палитра DUQ — киберпанк + утиный жёлтый бренд. Чистые Compose-цвета (multiplatform).
 */
object DuqColors {
    val primary = Color(0xFFFFD60A)
    val primaryBright = Color(0xFFFFE84D)
    val primaryDim = Color(0xFFC9A800)
    val primaryDark = Color(0xFF8B7500)

    val accent = Color(0xFFFF8C00)
    val accentDim = Color(0xFFCC7000)
    val accentDark = Color(0xFF994D00)

    val background = Color(0xFF0A0A0A)
    val surface = Color(0xFF0F0F0F)
    val surfaceVariant = Color(0xFF141414)
    val surfaceElevated = Color(0xFF1A1A1A)

    val glowYellow = Color(0x15FFD60A)
    val glowYellowMedium = Color(0x26FFD60A)
    val glowYellowStrong = Color(0x40FFD60A)

    val glassSurface = Color(0x0DFFFFFF)
    val glassBorder = Color(0x1AFFFFFF)
    val glassHighlight = Color(0x33FFFFFF)

    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFA0A0A0)
    val textDim = Color(0xFFA0A0A0)
    val textTertiary = Color(0xFF666666)
    val textMuted = Color(0xFF666666)

    val success = Color(0xFF10B981)
    val warning = Color(0xFFF59E0B)
    val error = Color(0xFFEF4444)

    val idle = primary
    val listening = primaryBright
    val processing = accent
    val speaking = success
    val errorState = error

    val aiConfident = success
    val aiModerate = primary
    val aiUncertain = accent

    val cursorBlink = primary
}

private val DuqColorScheme = darkColorScheme(
    primary = DuqColors.primary,
    secondary = DuqColors.accent,
    tertiary = DuqColors.primaryBright,
    background = DuqColors.background,
    surface = DuqColors.surface,
    surfaceVariant = DuqColors.surfaceVariant,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onTertiary = Color.Black,
    onBackground = DuqColors.textPrimary,
    onSurface = DuqColors.textPrimary,
    error = DuqColors.error,
    outline = Color(0xFF2A2A2A)
)

/**
 * Кроссплатформенная тема DUQ. Системные бары (статус/навигация) настраиваются на стороне
 * платформы (androidApp edge-to-edge / iOS — системно), здесь только Material3 colorScheme.
 */
@Composable
fun DuqTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DuqColorScheme, content = content)
}
