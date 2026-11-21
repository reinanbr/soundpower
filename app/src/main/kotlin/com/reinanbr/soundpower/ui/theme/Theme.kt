// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.reinanbr.soundpower.ui.prefs.ThemeMode

// SoundPower's own palette: graphite surfaces with a mint signal colour for boosts and the
// curve, and a coral counterpart for cuts.
private val Mint = Color(0xFF3DDBB4)
private val MintDeep = Color(0xFF00876F)
private val Coral = Color(0xFFFF8A65)
private val CoralDeep = Color(0xFFD8572F)

private val DarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF0E4D40),
    onPrimaryContainer = Color(0xFFB4F5E2),
    secondary = Color(0xFF9CB8C8),
    onSecondary = Color(0xFF0F2430),
    secondaryContainer = Color(0xFF233A47),
    onSecondaryContainer = Color(0xFFD3E6F1),
    tertiary = Coral,
    onTertiary = Color(0xFF4A1504),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6ECF1),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFE6ECF1),
    surfaceVariant = Color(0xFF1C252F),
    onSurfaceVariant = Color(0xFFA9B6C2),
    surfaceContainerLowest = Color(0xFF070A0E),
    surfaceContainerLow = Color(0xFF10161D),
    surfaceContainer = Color(0xFF141C24),
    surfaceContainerHigh = Color(0xFF1A232D),
    surfaceContainerHighest = Color(0xFF212C37),
    outline = Color(0xFF3A4652),
    outlineVariant = Color(0xFF26313C),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = MintDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F5E2),
    onPrimaryContainer = Color(0xFF00382C),
    secondary = Color(0xFF3F6273),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E6F1),
    onSecondaryContainer = Color(0xFF0F2430),
    tertiary = CoralDeep,
    onTertiary = Color.White,
    background = Color(0xFFF4F7F9),
    onBackground = Color(0xFF10171D),
    surface = Color(0xFFF4F7F9),
    onSurface = Color(0xFF10171D),
    surfaceVariant = Color(0xFFDDE5EB),
    onSurfaceVariant = Color(0xFF46535E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEEF2F5),
    surfaceContainer = Color(0xFFE8EDF1),
    surfaceContainerHigh = Color(0xFFE2E8ED),
    surfaceContainerHighest = Color(0xFFDCE3E9),
    outline = Color(0xFF8A98A4),
    outlineVariant = Color(0xFFC3CDD5),
    error = Color(0xFFBA1A1A),
)

// Parametric band colours: evenly spread hues, brighter on dark surfaces than on light ones.
private val BandsDark = listOf(
    Color(0xFF3DDBB4), Color(0xFF5AB8FF), Color(0xFFB58CFF), Color(0xFFFF8AC2),
    Color(0xFFFFB04D), Color(0xFFC6E05A), Color(0xFF4FD6E8), Color(0xFFFF8A65),
)
private val BandsLight = listOf(
    Color(0xFF00876F), Color(0xFF1C6FC2), Color(0xFF7448D0), Color(0xFFC23C82),
    Color(0xFFB86A00), Color(0xFF5E7F00), Color(0xFF00869A), Color(0xFFD8572F),
)

/** Colours the equalizer controls use beyond the Material scheme. */
@Immutable
data class EqColors(
    val boost: Color,
    val cut: Color,
    val grid: Color,
    val track: Color,
    /** Parametric band colours, indexed by [com.reinanbr.soundpower.model.ParametricBand.color]. */
    val bands: List<Color> = BandsDark,
    /** Curves of single channels: left, right, mid, side. */
    val left: Color = BandsDark[1],
    val right: Color = BandsDark[3],
    val mid: Color = BandsDark[4],
    val side: Color = BandsDark[2],
) {
    /** The colour of band [index] whose chosen colour is [chosen] (null picks one by position). */
    fun band(index: Int, chosen: Int?): Color = bands[(chosen ?: index).mod(bands.size)]
}

val LocalEqColors = staticCompositionLocalOf { EqColors(Mint, Coral, Color.Gray, Color.DarkGray) }

@Composable
fun SoundPowerTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    val bands = if (dark) BandsDark else BandsLight
    val eq = EqColors(
        boost = colors.primary,
        cut = colors.tertiary,
        grid = colors.outlineVariant,
        track = colors.surfaceContainerHighest,
        bands = bands,
        left = bands[1],
        right = bands[3],
        mid = bands[4],
        side = bands[2],
    )
    CompositionLocalProvider(LocalEqColors provides eq) {
        // Expressive motion: the Material components (switches, segmented buttons, sliders) move on springs.
        MaterialTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), content = content)
    }
}
