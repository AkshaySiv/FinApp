package com.compound.finance.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class Palette(
    val pos: Color, val neg: Color, val muted: Color, val faint: Color, val track: Color,
    val series: List<Color>, val warn: Color,
)

private val LightPalette = Palette(
    pos = Color(0xFF0E6B5C), neg = Color(0xFFB23A3A), muted = Color(0xFF4A5A66), faint = Color(0xFF6F7D86),
    track = Color(0xFFE3E8E6), warn = Color(0xFFB7791F),
    series = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100),
        Color(0xFFE87BA4), Color(0xFF008300), Color(0xFF6250D6), Color(0xFFE34948)),
)
private val DarkPalette = Palette(
    pos = Color(0xFF4FB8A3), neg = Color(0xFFE66767), muted = Color(0xFFA9B6BD), faint = Color(0xFF85939B),
    track = Color(0xFF24313A), warn = Color(0xFFE0A93B),
    series = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500),
        Color(0xFFD55181), Color(0xFF008300), Color(0xFF9085E9), Color(0xFFE66767)),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** Serif for money figures, sans for everything else. */
val FigureFont = FontFamily.Serif

@Composable
fun CompoundTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFE6ECEA), onPrimary = Color(0xFF11181D),
        secondary = Color(0xFF4FB8A3), onSecondary = Color(0xFF0B1A17),
        background = Color(0xFF11181D), onBackground = Color(0xFFE6ECEA),
        surface = Color(0xFF172128), onSurface = Color(0xFFE6ECEA),
        surfaceVariant = Color(0xFF1D2930), onSurfaceVariant = Color(0xFFA9B6BD),
        surfaceContainer = Color(0xFF172128), surfaceContainerHigh = Color(0xFF1D2930),
        outline = Color(0xFF2A3740), outlineVariant = Color(0xFF2A3740),
        error = Color(0xFFE66767),
    ) else lightColorScheme(
        primary = Color(0xFF14212B), onPrimary = Color(0xFFF3F5F4),
        secondary = Color(0xFF0E6B5C), onSecondary = Color.White,
        background = Color(0xFFF3F5F4), onBackground = Color(0xFF14212B),
        surface = Color(0xFFFFFFFF), onSurface = Color(0xFF14212B),
        surfaceVariant = Color(0xFFE8ECEA), onSurfaceVariant = Color(0xFF4A5A66),
        surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF3F5F4),
        outline = Color(0xFFD5DCDA), outlineVariant = Color(0xFFD5DCDA),
        error = Color(0xFFB23A3A),
    )
    val base = Typography()
    val type = base.copy(
        displaySmall = TextStyle(fontFamily = FigureFont, fontWeight = FontWeight.Normal, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp),
        headlineSmall = TextStyle(fontFamily = FigureFont, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 20.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
