// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.Band
import com.reinanbr.soundpower.protocol.BandChannel
import com.reinanbr.soundpower.protocol.BandType
import com.reinanbr.soundpower.protocol.Protocol
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Normalized biquad coefficients (a0 = 1). */
data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    /** |H| at [freqHz], in dB. */
    fun magnitudeDb(freqHz: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * freqHz / sampleRate
        val c1 = cos(w)
        val c2 = cos(2.0 * w)
        val num = b0 * b0 + b1 * b1 + b2 * b2 + 2.0 * (b0 * b1 + b1 * b2) * c1 + 2.0 * b0 * b2 * c2
        val den = 1.0 + a1 * a1 + a2 * a2 + 2.0 * (a1 + a1 * a2) * c1 + 2.0 * a2 * c2
        return 10.0 * log10(max(num, 1e-300) / max(den, 1e-300))
    }

    companion object {
        /**
         * The RBJ "Audio EQ Cookbook" designs, the same ones the effect uses (dsp-core/src/rbj.cpp),
         * so the curve the app draws is the one the effect applies.
         */
        fun design(type: BandType, sampleRate: Double, freqHz: Double, gainDb: Double, q: Double): Biquad {
            val w0 = 2.0 * PI * freqHz / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a = 10.0.pow(gainDb / 40.0)
            return when (type) {
                BandType.LOW_PASS -> norm((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
                BandType.HIGH_PASS -> norm((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
                BandType.BAND_PASS -> norm(alpha, 0.0, -alpha, 1 + alpha, -2 * cw, 1 - alpha)
                BandType.NOTCH -> norm(1.0, -2 * cw, 1.0, 1 + alpha, -2 * cw, 1 - alpha)
                BandType.PEAKING -> norm(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
                BandType.LOW_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    norm(
                        a * ((a + 1) - (a - 1) * cw + k), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - k),
                        (a + 1) + (a - 1) * cw + k, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - k,
                    )
                }
                BandType.HIGH_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    norm(
                        a * ((a + 1) + (a - 1) * cw + k), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - k),
                        (a + 1) - (a - 1) * cw + k, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - k,
                    )
                }
            }
        }

        private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        /** Peaking and shelf filters at 0 dB are exact identities; the effect skips them. */
        fun isIdentity(type: BandType, gainDb: Float): Boolean = gainDb == 0f &&
            (type == BandType.PEAKING || type == BandType.LOW_SHELF || type == BandType.HIGH_SHELF)

        /** True if [type] uses its gain. */
        fun usesGain(type: BandType): Boolean =
            type == BandType.PEAKING || type == BandType.LOW_SHELF || type == BandType.HIGH_SHELF
    }
}

/**
 * The EQ curve of a set of bands plus tone shelves, as the effect computes it: the sum in dB of
 * every active section. All the work happens here, outside the UI.
 *
 * Bands routed to one channel ([Band.channel]) only show on that channel's curve: [db] with
 * [BandChannel.ALL] is what every channel gets, with another channel that plus its own bands.
 */
class ResponseCurve(
    bands: List<Band>,
    tone: Tone,
    private val sampleRate: Double = DEFAULT_SAMPLE_RATE,
) {
    private class Section(val filter: Biquad, val freqHz: Double, val channel: BandChannel)

    private val sections: List<Section> = buildList {
        val fMax = Protocol.FREQ_MAX_FS_RATIO * sampleRate
        fun add(type: BandType, freq: Float, gain: Float, q: Float, channel: BandChannel) {
            if (Biquad.isIdentity(type, gain)) return
            val f = freq.toDouble().coerceIn(Protocol.FREQ_MIN_HZ.toDouble(), fMax)
            val qq = q.toDouble().coerceIn(Protocol.Q_MIN.toDouble(), Protocol.Q_MAX.toDouble())
            val g = gain.toDouble().coerceIn(Protocol.GAIN_MIN_DB.toDouble(), Protocol.GAIN_MAX_DB.toDouble())
            add(Section(Biquad.design(type, sampleRate, f, g, qq), f, channel))
        }
        for (b in bands.take(Protocol.MAX_BANDS)) if (b.enabled) add(b.type, b.freqHz, b.gainDb, b.q, b.channel)
        if (tone.enabled) {
            add(BandType.LOW_SHELF, tone.bassFreqHz, tone.bassDb, Protocol.TONE_SHELF_Q, BandChannel.ALL)
            add(BandType.HIGH_SHELF, tone.trebleFreqHz, tone.trebleDb, Protocol.TONE_SHELF_Q, BandChannel.ALL)
        }
    }

    /** True if some band filters only some of the channels. */
    val isRouted: Boolean = sections.any { it.channel != BandChannel.ALL }

    /** The channels that have bands of their own. */
    val routedChannels: Set<BandChannel> = sections.mapNotNullTo(sortedSetOf()) { s -> s.channel.takeIf { it != BandChannel.ALL } }

    /** dB sum of the sections at [freqHz], per channel they are routed to (indexed by code). */
    private fun routedDb(freqHz: Double): DoubleArray {
        val db = DoubleArray(BandChannel.entries.size)
        for (s in sections) db[s.channel.code] += s.filter.magnitudeDb(freqHz, sampleRate)
        return db
    }

    /** The curve of [path] in dB at [freqHz]. */
    fun db(freqHz: Double, path: BandChannel = BandChannel.ALL): Double {
        val db = routedDb(freqHz)
        val all = db[BandChannel.ALL.code]
        return if (path == BandChannel.ALL) all else all + db[path.code]
    }

    /** The curve of [path] at each of [frequencies]. */
    fun db(frequencies: DoubleArray, path: BandChannel = BandChannel.ALL): FloatArray =
        FloatArray(frequencies.size) { db(frequencies[it], path).toFloat() }

    /** The gain auto-preamp counts at [freqHz]: all + max(left, right) + max(mid, side). */
    private fun peakAt(freqHz: Double): Double {
        val db = routedDb(freqHz)
        return db[BandChannel.ALL.code] + max(db[BandChannel.LEFT.code], db[BandChannel.RIGHT.code]) +
            max(db[BandChannel.MID.code], db[BandChannel.SIDE.code])
    }

    /**
     * Highest gain of the curve, searched like the effect's auto-preamp: a 512-point log grid from
     * 10 Hz to 0.49 fs plus every section's frequency, counting one side of each channel pair
     * (see docs/protocol.md). 0 for a flat curve.
     */
    fun peakDb(): Double {
        if (sections.isEmpty()) return 0.0
        val lo = Protocol.FREQ_MIN_HZ.toDouble()
        val hi = Protocol.FREQ_MAX_FS_RATIO * sampleRate
        var peak = Double.NEGATIVE_INFINITY
        for (i in 0 until PEAK_GRID) peak = max(peak, peakAt(lo * (hi / lo).pow(i.toDouble() / (PEAK_GRID - 1))))
        for (s in sections) peak = max(peak, peakAt(s.freqHz))
        return peak
    }

    /** The preamp the effect applies: [preampDb], minus the curve's positive peak with auto-preamp. */
    fun effectivePreampDb(preampDb: Float, autoPreamp: Boolean): Float {
        val p = if (autoPreamp) preampDb - max(0.0, peakDb()).toFloat() else preampDb
        return min(max(p, Protocol.PREAMP_MIN_DB), Protocol.PREAMP_MAX_DB)
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48000.0
        private const val PEAK_GRID = 512

        /** [count] log-spaced frequencies from [loHz] to [hiHz], both included. */
        fun logFrequencies(count: Int, loHz: Double = 20.0, hiHz: Double = 20000.0): DoubleArray =
            DoubleArray(count) { loHz * (hiHz / loHz).pow(it.toDouble() / (count - 1)) }
    }
}
