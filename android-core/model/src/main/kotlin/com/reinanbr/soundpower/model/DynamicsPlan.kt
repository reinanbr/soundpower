// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.BandChannel
import com.reinanbr.soundpower.protocol.CompressorParams
import com.reinanbr.soundpower.protocol.LimiterParams
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How Android's Dynamics Processing effect splits the spectrum, for the mode without root.
 *
 * The AOSP implementation (frameworks/av/media/libeffects/dynamicsproc, frequency-domain variant)
 * runs one FFT of [blockSize] frames: the preferred frame duration at the output rate, rounded up
 * to a power of two. Every bin gets the flat gain of the band it falls in, and a band covers the
 * bins after the previous band's last one up to the bin nearest its cutoff. The Nyquist bin is
 * left alone.
 *
 * This layout gives every low bin a band of its own and groups the higher bins into bands of about
 * 1/[bandsPerOctave] octave, which keeps the band count near 100 at any precision.
 */
class SpectralLayout(
    /** The rate the layout is designed for; on another rate the bands keep their frequencies. */
    val sampleRate: Int,
    val frameMs: Float,
    val bandsPerOctave: Int = DEFAULT_BANDS_PER_OCTAVE,
) {
    init {
        require(sampleRate > 0 && frameMs > 0f && bandsPerOctave > 0)
    }

    val blockSize: Int = blockSizeFor(frameMs, sampleRate)

    /** Spacing of the FFT bins in Hz. */
    val binHz: Double = sampleRate.toDouble() / blockSize

    /** The last bin of each band, increasing; the last band reaches the last bin the effect scales. */
    val stops: IntArray = run {
        val last = blockSize / 2 - 1
        val ratio = 2.0.pow(1.0 / bandsPerOctave)
        val out = ArrayList<Int>()
        var first = 0
        while (first <= last) {
            val stop = min(max(first + 1, (first * ratio).roundToInt()) - 1, last)
            out += stop
            first = stop + 1
        }
        out.toIntArray()
    }

    val bandCount: Int get() = stops.size

    /** The first bin of [band]. */
    fun start(band: Int): Int = if (band == 0) 0 else stops[band - 1] + 1

    /**
     * The cutoff to send for each band: the frequency of its last bin, which the effect rounds back
     * to that bin. The last band's is past any Nyquist frequency, so it reaches the top of the
     * spectrum at whatever rate the effect runs (the effect bounds that loop).
     */
    val cutoffsHz: FloatArray = FloatArray(stops.size) { if (it == stops.lastIndex) TOP_CUTOFF_HZ else (stops[it] * binHz).toFloat() }

    /** The delay the effect adds: one block, in milliseconds. */
    val latencyMs: Double get() = blockSize * 1000.0 / sampleRate

    override fun equals(other: Any?): Boolean =
        other is SpectralLayout && other.sampleRate == sampleRate && other.frameMs == frameMs && other.bandsPerOctave == bandsPerOctave

    override fun hashCode(): Int = (sampleRate * 31 + frameMs.hashCode()) * 31 + bandsPerOctave

    override fun toString(): String = "SpectralLayout(fs=$sampleRate, ${frameMs}ms, N=$blockSize, ${stops.size} bands)"

    companion object {
        const val DEFAULT_BANDS_PER_OCTAVE = 12
        const val TOP_CUTOFF_HZ = 96000f
        private const val MIN_BLOCK = 8
        private const val MAX_BLOCK = 16384

        /** The effect's FFT size: `frameMs * fs / 1000` rounded up to a power of two, within 8..16384. */
        fun blockSizeFor(frameMs: Float, sampleRate: Int): Int {
            val desired = frameMs * sampleRate / 1000f
            var n = MIN_BLOCK
            while (n < desired && n < MAX_BLOCK) n *= 2
            return n
        }
    }
}

/** The effect's multiband compressor, used as one band over the audible range. */
data class DynamicsCompressor(
    val enabled: Boolean,
    val cutoffHz: Float,
    val thresholdDb: Float,
    val ratio: Float,
    val attackMs: Float,
    val releaseMs: Float,
    val kneeDb: Float,
    val postGainDb: Float,
) {
    companion object {
        /**
         * Highest cutoff of the compressor band. The effect does not bound the compressor's bins
         * (unlike the EQ's), so its cutoff must stay below the Nyquist frequency of any rate the
         * effect may run at; the few bins above it pass uncompressed.
         */
        const val TOP_HZ = 20000f

        /** Below every rate's Nyquist frequency, with margin. */
        const val NYQUIST_MARGIN = 0.45f

        fun of(c: CompressorParams, sampleRate: Int) = DynamicsCompressor(
            enabled = c.enabled,
            cutoffHz = min(TOP_HZ, NYQUIST_MARGIN * sampleRate),
            thresholdDb = c.thresholdDb,
            ratio = c.ratio,
            attackMs = c.attackMs,
            releaseMs = c.releaseMs,
            kneeDb = c.kneeDb,
            postGainDb = c.makeupDb,
        )
    }
}

/**
 * The effect's limiter: a compressor on the level of each block, linked across channels. It has no
 * lookahead, so it is a level limiter, not a peak one; auto-preamp is what prevents clipping.
 */
data class DynamicsLimiter(
    val enabled: Boolean,
    val thresholdDb: Float,
    val ratio: Float,
    val attackMs: Float,
    val releaseMs: Float,
) {
    companion object {
        const val RATIO = 10f
        const val ATTACK_MS = 1f

        fun of(l: LimiterParams, enabled: Boolean) = DynamicsLimiter(enabled, l.ceilingDb, RATIO, ATTACK_MS, l.releaseMs)
    }
}

/**
 * What Android's Dynamics Processing effect needs to sound like [EqSettings], as far as it can:
 * the EQ (bands routed to left or right included) and tone as band gains, the preamp and balance as
 * input gains, the compressor and a level limiter. Mid and side bands, stereo width and mono need
 * to mix channels, which the effect cannot do; they are left out.
 */
class DynamicsPlan(
    val layout: SpectralLayout,
    val enabled: Boolean,
    /** Input gain of the left and right channels, in dB: the preamp plus the balance. */
    val inputGainDb: FloatArray,
    /** Gain of each band of [layout], for the left and right channels, in dB. */
    val bandGainDb: List<FloatArray>,
    val compressor: DynamicsCompressor,
    val limiter: DynamicsLimiter,
    /** The preamp applied, auto-preamp included. */
    val preampDb: Float,
) {
    companion object {
        const val CHANNELS = 2

        /** Input gain of a silenced channel (full balance to the other side). */
        const val SILENT_DB = -100f

        private val PATHS = listOf(BandChannel.LEFT, BandChannel.RIGHT)

        fun of(settings: EqSettings, layout: SpectralLayout): DynamicsPlan {
            val t = settings.tone
            val curve = ResponseCurve(
                settings.bands.filter { it.channel != BandChannel.MID && it.channel != BandChannel.SIDE },
                Tone(t.enabled, t.bassDb, t.bassFreqHz, t.trebleDb, t.trebleFreqHz),
                layout.sampleRate.toDouble(),
            )
            val binFreqs = DoubleArray(layout.blockSize / 2) { it * layout.binHz }
            val bins = curve.db(binFreqs, PATHS)
            // The mean of the bins a band covers: the flat gain closest to the curve over that band.
            val gains = bins.map { db ->
                FloatArray(layout.bandCount) { b ->
                    val from = layout.start(b)
                    val to = layout.stops[b]
                    var sum = 0.0
                    for (k in from..to) sum += db[k]
                    (sum / (to - from + 1)).toFloat()
                }
            }
            val preamp = curve.effectivePreampDb(settings.preampDb, settings.autoPreamp)
            val balance = settings.stereo.balance
            val left = if (balance > 0f) 1f - balance else 1f
            val right = if (balance < 0f) 1f + balance else 1f
            return DynamicsPlan(
                layout = layout,
                enabled = !settings.bypass,
                inputGainDb = floatArrayOf(preamp + linearToDb(left), preamp + linearToDb(right)),
                bandGainDb = gains,
                compressor = DynamicsCompressor.of(settings.compressor, layout.sampleRate),
                limiter = DynamicsLimiter.of(settings.limiter, settings.limiterEnabled),
                preampDb = preamp,
            )
        }

        private fun linearToDb(g: Float): Float = if (g <= 0f) SILENT_DB else max(SILENT_DB, 20f * log10(g))
    }
}
