// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Teal = Color(0xFF2E7D74)
private val TealLight = Color(0xFF7FD1C7)

/** Material 3, with the wallpaper colours on Android 12+. */
@Composable
fun SoundPowerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme(primary = TealLight, secondary = TealLight)
        else -> lightColorScheme(primary = Teal, secondary = Teal)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
