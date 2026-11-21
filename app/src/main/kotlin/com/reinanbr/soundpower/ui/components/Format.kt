// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.components

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Short frequency labels: 31.5, 250, 1k, 1.6k, 16k. */
fun formatHz(hz: Float): String {
    val l = Locale.getDefault()
    return when {
        hz >= 10000f -> String.format(l, "%dk", (hz / 1000f).roundToInt())
        hz >= 1000f -> {
            val k = hz / 1000f
            if (abs(k - k.roundToInt()) < 0.05f) String.format(l, "%dk", k.roundToInt()) else String.format(l, "%.1fk", k)
        }
        hz < 100f && abs(hz - hz.roundToInt()) > 0.05f -> String.format(l, "%.1f", hz)
        else -> String.format(l, "%d", hz.roundToInt())
    }
}

/** Signed gain with one decimal: +3.5, −2.0, 0.0. */
fun formatDb(db: Float): String {
    if (abs(db) < 0.05f) return String.format(Locale.getDefault(), "%.1f", 0f)
    return String.format(Locale.getDefault(), "%+.1f", db).replace('-', '−')
}

fun formatNumber(value: Float, decimals: Int): String = String.format(Locale.getDefault(), "%.${decimals}f", value)

/** Parses user input with either decimal separator. Null if it is not a number. */
fun parseNumber(text: String): Float? =
    text.trim().replace('−', '-').replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }
