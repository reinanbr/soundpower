// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.Band
import com.reinanbr.soundpower.protocol.BandType
import kotlin.math.roundToInt

/**
 * The 10-band graphic EQ: peaking filters one octave wide at the ISO 266 octave centres. It is
 * just a set of parametric bands, so a parametric editor (phase 7) can take over the same data.
 */
object GraphicEq {
    val FREQUENCIES_HZ = listOf(31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    /** Q of a one-octave peaking band: sqrt(2) / (2 - 1). */
    const val Q = 1.41f

    const val GAIN_MIN_DB = -12f
    const val GAIN_MAX_DB = 12f
    const val GAIN_STEP_DB = 0.5f
    const val PREAMP_MIN_DB = -12f
    const val PREAMP_MAX_DB = 12f

    fun flatBands(): List<Band> = FREQUENCIES_HZ.map { Band(BandType.PEAKING, enabled = true, freqHz = it, gainDb = 0f, q = Q) }

    /** True if [bands] are this layout (any gains), so the graphic editor can show them. */
    fun matches(bands: List<Band>): Boolean =
        bands.size == FREQUENCIES_HZ.size &&
            bands.zip(FREQUENCIES_HZ).all { (b, f) -> b.type == BandType.PEAKING && b.freqHz == f && b.q == Q }

    /** [bands] with band [index] set to [gainDb], clamped to the graphic range and snapped to the step. */
    fun withGain(bands: List<Band>, index: Int, gainDb: Float): List<Band> {
        require(index in bands.indices) { "no band $index" }
        val gain = snap(gainDb, GAIN_MIN_DB, GAIN_MAX_DB)
        return bands.mapIndexed { i, b -> if (i == index) b.copy(gainDb = gain, enabled = true) else b }
    }

    fun snapPreamp(db: Float): Float = snap(db, PREAMP_MIN_DB, PREAMP_MAX_DB)

    /** "31.5", "63", ..., "1k", "16k". */
    fun label(freqHz: Float): String = when {
        freqHz >= 1000f -> {
            val k = freqHz / 1000f
            if (k == k.roundToInt().toFloat()) "${k.roundToInt()}k" else "${k}k"
        }
        freqHz == freqHz.roundToInt().toFloat() -> "${freqHz.roundToInt()}"
        else -> "$freqHz"
    }

    private fun snap(value: Float, min: Float, max: Float): Float {
        val clamped = if (value.isNaN()) 0f else value.coerceIn(min, max)
        return (clamped / GAIN_STEP_DB).roundToInt() * GAIN_STEP_DB
    }
}
