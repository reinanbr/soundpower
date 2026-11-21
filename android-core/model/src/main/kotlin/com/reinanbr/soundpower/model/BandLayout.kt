// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The band frequencies of the graphic equalizer: one of the fixed layouts, or a custom layout of
 * [MIN_BANDS]–[MAX_BANDS] log-spaced bands from [startHz] to [endHz].
 */
@Serializable
data class BandLayout(
    val kind: Kind = Kind.TEN,
    /** Custom layouts only. */
    val count: Int = 10,
    val startHz: Float = 31.5f,
    val endHz: Float = 16000f,
) {
    enum class Kind { FIVE, TEN, FIFTEEN, THIRTY_ONE, CUSTOM }

    /** Centre frequencies, ascending. */
    val frequencies: List<Float> by lazy {
        when (kind) {
            Kind.FIVE -> FIVE_HZ
            Kind.TEN -> TEN_HZ
            Kind.FIFTEEN -> FIFTEEN_HZ
            Kind.THIRTY_ONE -> THIRTY_ONE_HZ
            Kind.CUSTOM -> {
                val n = count.coerceIn(MIN_BANDS, MAX_BANDS)
                val (lo, hi) = customRange(startHz, endHz)
                List(n) { i -> roundFrequency(lo * (hi / lo).pow(i.toFloat() / (n - 1))) }
            }
        }
    }

    val size: Int get() = frequencies.size

    /**
     * Q of band [index]. The bandwidth is the average distance to the neighbouring bands in
     * octaves (the single neighbour at either end); with [overlap] it is doubled, so neighbouring
     * bands blend into broader, smoother curves.
     */
    fun q(index: Int, overlap: Boolean): Float {
        val f = frequencies
        val left = if (index > 0) octaves(f[index - 1], f[index]) else null
        val right = if (index < f.size - 1) octaves(f[index], f[index + 1]) else null
        val n = listOfNotNull(left, right).average() * (if (overlap) 2.0 else 1.0)
        val r = 2.0.pow(n)
        return (sqrt(r) / (r - 1.0)).toFloat()
    }

    companion object {
        const val MIN_BANDS = 5
        const val MAX_BANDS = 32
        const val MIN_FREQ_HZ = 20f
        const val MAX_FREQ_HZ = 20000f

        /** The custom range must span at least this ratio (two octaves). */
        const val MIN_SPAN = 4f

        val FIVE = BandLayout(Kind.FIVE)
        val TEN = BandLayout(Kind.TEN)
        val FIFTEEN = BandLayout(Kind.FIFTEEN)
        val THIRTY_ONE = BandLayout(Kind.THIRTY_ONE)
        val FIXED = listOf(FIVE, TEN, FIFTEEN, THIRTY_ONE)

        fun custom(count: Int, startHz: Float, endHz: Float): BandLayout {
            val (lo, hi) = customRange(startHz, endHz)
            return BandLayout(Kind.CUSTOM, count.coerceIn(MIN_BANDS, MAX_BANDS), lo, hi)
        }

        private val FIVE_HZ = listOf(60f, 230f, 910f, 3600f, 14000f)

        /** ISO 266 octave centres. */
        private val TEN_HZ = listOf(31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

        /** ISO 266 two-thirds-octave centres. */
        private val FIFTEEN_HZ = listOf(
            25f, 40f, 63f, 100f, 160f, 250f, 400f, 630f, 1000f, 1600f, 2500f, 4000f, 6300f, 10000f, 16000f,
        )

        /** ISO 266 third-octave centres, 20 Hz to 20 kHz. */
        private val THIRTY_ONE_HZ = listOf(
            20f, 25f, 31.5f, 40f, 50f, 63f, 80f, 100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f, 630f,
            800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f, 4000f, 5000f, 6300f, 8000f, 10000f, 12500f,
            16000f, 20000f,
        )

        private fun customRange(startHz: Float, endHz: Float): Pair<Float, Float> {
            val lo = startHz.coerceIn(MIN_FREQ_HZ, MAX_FREQ_HZ / MIN_SPAN)
            val hi = endHz.coerceIn(lo * MIN_SPAN, MAX_FREQ_HZ)
            return lo to hi
        }

        private fun octaves(lo: Float, hi: Float): Double = ln(hi.toDouble() / lo) / ln(2.0)

        /** Three significant digits (e.g. 1234.5 -> 1230), so labels stay short. */
        internal fun roundFrequency(f: Float): Float {
            var scale = 1f
            while (f / scale >= 1000f) scale *= 10f
            while (f / scale < 100f && scale > 0.01f) scale /= 10f
            return (f / scale).roundToInt() * scale
        }

        /** Linear interpolation of a curve given at [fromHz] (ascending), in log frequency. */
        internal fun interpolate(fromHz: List<Float>, values: List<Float>, atHz: Float): Float {
            if (fromHz.isEmpty()) return 0f
            if (atHz <= fromHz.first()) return values.first()
            if (atHz >= fromHz.last()) return values.last()
            val i = fromHz.indexOfFirst { it >= atHz }
            if (abs(fromHz[i] - atHz) < 1e-3f) return values[i]
            val x0 = ln(fromHz[i - 1].toDouble())
            val x1 = ln(fromHz[i].toDouble())
            val t = ((ln(atHz.toDouble()) - x0) / (x1 - x0)).toFloat()
            return values[i - 1] + t * (values[i] - values[i - 1])
        }
    }
}
