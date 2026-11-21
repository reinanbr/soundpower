// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.Band
import com.reinanbr.soundpower.protocol.BandChannel
import com.reinanbr.soundpower.protocol.BandType
import com.reinanbr.soundpower.protocol.CompressorParams
import com.reinanbr.soundpower.protocol.LimiterParams
import com.reinanbr.soundpower.protocol.StereoParams
import com.reinanbr.soundpower.protocol.ToneParams
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamicsPlanTest {
    private val precisions = listOf(10f, 20f, 40f, 80f)
    private val rates = listOf(44100, 48000, 96000)

    private fun peak(f: Float, g: Float, q: Float, ch: BandChannel = BandChannel.ALL) = Band(BandType.PEAKING, true, f, g, q, ch)

    /** How the effect maps a cutoff to its last bin (DPFrequency::computeBinStartStop). */
    private fun effectStop(cutoffHz: Float, layout: SpectralLayout, fs: Int) = (0.5 + cutoffHz.toDouble() * layout.blockSize / fs).toInt()

    @Test
    fun blockSizesFollowTheEffect() {
        assertEquals(512, SpectralLayout.blockSizeFor(10f, 48000))
        assertEquals(1024, SpectralLayout.blockSizeFor(20f, 48000))
        assertEquals(2048, SpectralLayout.blockSizeFor(40f, 48000))
        assertEquals(2048, SpectralLayout.blockSizeFor(40f, 44100))
        assertEquals(4096, SpectralLayout.blockSizeFor(80f, 48000))
        assertEquals(8, SpectralLayout.blockSizeFor(0.01f, 48000))
        assertEquals(16384, SpectralLayout.blockSizeFor(10000f, 48000))
        // Exactly a power of two stays.
        assertEquals(512, SpectralLayout.blockSizeFor(512f / 48f, 48000))
    }

    @Test
    fun bandsCoverEveryScaledBinOnce() {
        for (fs in rates) for (ms in precisions) {
            val l = SpectralLayout(fs, ms)
            assertEquals(0, l.start(0))
            for (b in 0 until l.bandCount) assertTrue(l.stops[b] >= l.start(b), "$l band $b is empty")
            assertEquals(l.blockSize / 2 - 1, l.stops.last(), "$l")
            assertTrue(l.bandCount <= 128, "$l has too many bands")
            // Low bins get a band each; no band is much wider than 1/12 octave.
            assertEquals(1, l.stops[1] - l.stops[0])
            for (b in 1 until l.bandCount - 1) {
                val width = l.stops[b] - l.start(b) + 1
                assertTrue(width == 1 || l.stops[b] <= l.start(b) * 1.07, "$l band $b is $width bins from ${l.start(b)}")
            }
        }
    }

    @Test
    fun cutoffsRoundBackToTheirBins() {
        for (fs in rates) for (ms in precisions) {
            val l = SpectralLayout(fs, ms)
            for (b in 0 until l.bandCount - 1) assertEquals(l.stops[b], effectStop(l.cutoffsHz[b], l, fs), "$l band $b")
            // The last band reaches the top at the design rate and at any rate up to 192 kHz.
            assertTrue(effectStop(l.cutoffsHz.last(), l, 192000) >= l.blockSize / 2)
            for (b in 1 until l.bandCount) assertTrue(l.cutoffsHz[b] > l.cutoffsHz[b - 1])
        }
    }

    @Test
    fun aFlatEqIsFlat() {
        val plan = DynamicsPlan.of(EqSettings(), SpectralLayout(48000, 40f))
        assertTrue(plan.enabled)
        for (ch in plan.bandGainDb) assertTrue(ch.all { it == 0f })
        assertContentEquals(floatArrayOf(0f, 0f), plan.inputGainDb)
    }

    @Test
    fun bandGainsFollowTheCurve() {
        val bands = listOf(
            peak(60f, 6f, 1f), peak(250f, -4f, 1.4f), peak(1000f, 3f, 1f), peak(4000f, -5f, 2f), peak(12000f, 4f, 0.8f),
        )
        val tone = ToneParams(bassDb = 3f, trebleDb = -2f)
        for (ms in listOf(20f, 40f, 80f)) {
            val l = SpectralLayout(48000, ms)
            val plan = DynamicsPlan.of(EqSettings(bands = bands, tone = tone), l)
            val curve = ResponseCurve(bands, Tone(bassDb = 3f, trebleDb = -2f), 48000.0)
            var worst = 0.0
            for (b in 0 until l.bandCount) for (k in l.start(b)..l.stops[b]) {
                if (k == 0) continue
                val want = curve.db(k * l.binHz)
                worst = max(worst, abs(want - plan.bandGainDb[0][b]))
            }
            assertTrue(worst < 0.4, "$l: worst bin error $worst dB")
            assertContentEquals(plan.bandGainDb[0], plan.bandGainDb[1])
        }
    }

    @Test
    fun leftAndRightBandsReachOnlyTheirChannelAndMidSideAreLeftOut() {
        val l = SpectralLayout(48000, 40f)
        val leftRight = listOf(peak(1000f, 6f, 2f, BandChannel.LEFT), peak(3000f, -6f, 2f, BandChannel.RIGHT), peak(200f, 2f, 1f))
        val midSide = listOf(peak(500f, 9f, 1f, BandChannel.MID), peak(8000f, 9f, 1f, BandChannel.SIDE))
        val plan = DynamicsPlan.of(EqSettings(bands = leftRight + midSide, autoPreamp = false), l)
        val without = DynamicsPlan.of(EqSettings(bands = leftRight, autoPreamp = false), l)
        for (ch in 0..1) assertContentEquals(without.bandGainDb[ch], plan.bandGainDb[ch])
        val curve = ResponseCurve(leftRight, Tone(), 48000.0)
        for ((ch, path) in listOf(BandChannel.LEFT, BandChannel.RIGHT).withIndex()) {
            for (b in 1 until l.bandCount) {
                if (l.start(b) != l.stops[b]) continue
                assertEquals(curve.db(l.stops[b] * l.binHz, path), plan.bandGainDb[ch][b].toDouble(), 1e-4, "$path band $b")
            }
        }
        fun bandAt(hz: Double) = (0 until l.bandCount).first { l.stops[it] * l.binHz >= hz }
        assertTrue(plan.bandGainDb[0][bandAt(1000.0)] > 5.5f && abs(plan.bandGainDb[1][bandAt(1000.0)]) < 0.5f)
        assertTrue(plan.bandGainDb[1][bandAt(3000.0)] < -5.5f && abs(plan.bandGainDb[0][bandAt(3000.0)]) < 0.5f)
    }

    @Test
    fun autoPreampCountsTheLouderSideOnly() {
        val l = SpectralLayout(48000, 40f)
        val bands = listOf(peak(1000f, 6f, 1f, BandChannel.LEFT), peak(1000f, 4f, 1f, BandChannel.RIGHT), peak(200f, 12f, 1f, BandChannel.MID))
        val plan = DynamicsPlan.of(EqSettings(bands = bands, autoPreamp = true, preampDb = 2f), l)
        assertEquals(2f - 6f, plan.preampDb, 0.05f)
        assertContentEquals(floatArrayOf(plan.preampDb, plan.preampDb), plan.inputGainDb)
    }

    @Test
    fun balanceUsesTheCoreLaw() {
        val l = SpectralLayout(48000, 40f)
        val right = DynamicsPlan.of(EqSettings(autoPreamp = false, stereo = StereoParams(balance = 0.5f)), l)
        assertEquals((20 * log10(0.5)).toFloat(), right.inputGainDb[0], 1e-4f)
        assertEquals(0f, right.inputGainDb[1])
        val left = DynamicsPlan.of(EqSettings(autoPreamp = false, preampDb = -3f, stereo = StereoParams(balance = -1f)), l)
        assertEquals(-3f, left.inputGainDb[0])
        assertEquals(-3f + DynamicsPlan.SILENT_DB, left.inputGainDb[1])
    }

    @Test
    fun dynamicsAndBypassMapToTheEffectStages() {
        val l = SpectralLayout(44100, 40f)
        val c = CompressorParams(enabled = true, thresholdDb = -20f, ratio = 4f, attackMs = 5f, releaseMs = 100f, kneeDb = 6f, makeupDb = 3f)
        val plan = DynamicsPlan.of(
            EqSettings(bypass = true, compressor = c, limiter = LimiterParams(ceilingDb = -2f, releaseMs = 80f), limiterEnabled = false),
            l,
        )
        assertFalse(plan.enabled)
        assertEquals(DynamicsCompressor(true, 19845f, -20f, 4f, 5f, 100f, 6f, 3f), plan.compressor)
        assertEquals(DynamicsLimiter(false, -2f, DynamicsLimiter.RATIO, DynamicsLimiter.ATTACK_MS, 80f), plan.limiter)
        // The compressor band never reaches the Nyquist bin, which the effect would overrun.
        for (fs in listOf(8000, 16000, 32000, 44100, 48000, 192000)) {
            assertTrue(DynamicsCompressor.of(c, fs).cutoffHz < fs / 2f)
        }
    }
}
