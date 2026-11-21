// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Protocol 1.1 payloads against the golden vectors the effect-lib tests also use. */
class Protocol11Test {
    private val tone = ToneParams(enabled = false, bassDb = 7.5f, bassFreqHz = 62.5f, trebleDb = -3f, trebleFreqHz = 12000f)
    private val stereo = StereoParams(mono = true, balance = -0.25f, width = 1.5f)
    private val compressor = CompressorParams(
        enabled = true, thresholdDb = -24f, ratio = 4.5f, attackMs = 2.5f, releaseMs = 400f, kneeDb = 0f, makeupDb = 6f,
    )
    private val master = MasterParams(bypass = false, autoPreamp = true, preampDb = 3.25f, limiterEnabled = false)

    @Test
    fun masterLimiterOffMatchesTheGoldenVector() {
        assertContentEquals(GoldenVectors.bytes("master_limiter_off"), master.encode())
        assertEquals(master, MasterParams.decode(GoldenVectors.bytes("master_limiter_off")))
        // A 1.0 payload has no limiter-off bit: the limiter is on.
        assertEquals(true, MasterParams.decode(GoldenVectors.bytes("master_bypass")).limiterEnabled)
    }

    @Test
    fun toneMatchesTheGoldenVectors() {
        assertContentEquals(GoldenVectors.bytes("tone_default"), ToneParams().encode())
        assertContentEquals(GoldenVectors.bytes("tone_custom"), tone.encode())
        assertEquals(tone, ToneParams.decode(GoldenVectors.bytes("tone_custom")))
        assertEquals(ToneParams(), ToneParams.decode(GoldenVectors.bytes("tone_default")))
    }

    @Test
    fun stereoMatchesTheGoldenVectors() {
        assertContentEquals(GoldenVectors.bytes("stereo_default"), StereoParams().encode())
        assertContentEquals(GoldenVectors.bytes("stereo_custom"), stereo.encode())
        assertEquals(stereo, StereoParams.decode(GoldenVectors.bytes("stereo_custom")))
    }

    @Test
    fun compressorMatchesTheGoldenVectors() {
        assertContentEquals(GoldenVectors.bytes("compressor_default"), CompressorParams().encode())
        assertContentEquals(GoldenVectors.bytes("compressor_custom"), compressor.encode())
        assertEquals(compressor, CompressorParams.decode(GoldenVectors.bytes("compressor_custom")))
        assertFalse(CompressorParams.decode(GoldenVectors.bytes("compressor_default")).enabled)
    }

    @Test
    fun shortPayloadsAreRejectedAndTrailingBytesIgnored() {
        for ((name, decode) in listOf<Pair<String, (ByteArray) -> Any>>(
            "tone_custom" to ToneParams::decode,
            "stereo_custom" to StereoParams::decode,
            "compressor_custom" to CompressorParams::decode,
        )) {
            val full = GoldenVectors.bytes(name)
            for (n in full.indices) assertFailsWith<ProtocolException>(name) { decode(full.copyOf(n)) }
            assertEquals(decode(full), decode(full + byteArrayOf(9, 9, 9, 9)), name)
        }
    }

    @Test
    fun bandChannelsMatchTheGoldenVector() {
        val bands = EqState(
            listOf(
                Band(BandType.PEAKING, true, 250f, 4f, 1f, BandChannel.LEFT),
                Band(BandType.PEAKING, true, 250f, -2f, 1f, BandChannel.RIGHT),
                Band(BandType.HIGH_SHELF, true, 6000f, 3f, 0.7f, BandChannel.SIDE),
                Band(BandType.LOW_PASS, true, 120f, 0f, 0.707f, BandChannel.MID),
                Band(BandType.PEAKING, true, 3000f, 1.5f, 2f, BandChannel.ALL),
            ),
        )
        assertContentEquals(GoldenVectors.bytes("eq_state_channels"), bands.encode())
        assertEquals(bands, EqState.decode(GoldenVectors.bytes("eq_state_channels")))
        val bad = GoldenVectors.bytes("eq_state_channels").also { it[4 + 2] = 5 }
        assertFailsWith<ProtocolException> { EqState.decode(bad) }
    }

    @Test
    fun stateFileV11MatchesTheGoldenVector() {
        val bands = EqState(
            listOf(
                Band(BandType.LOW_SHELF, enabled = true, freqHz = 80f, gainDb = 6f, q = 0.75f),
                Band(BandType.PEAKING, enabled = true, freqHz = 1000f, gainDb = -3.5f, q = 1.5f),
                Band(BandType.HIGH_PASS, enabled = false, freqHz = 30f, gainDb = 0f, q = 0.5f),
            ),
        )
        val entries = listOf(
            StateFile.Entry(ParamId.MASTER, master.encode()),
            StateFile.Entry(ParamId.EQ_STATE, bands.encode()),
            StateFile.Entry(ParamId.LIMITER, LimiterParams(-3f, 5f, 250f).encode()),
            StateFile.Entry(ParamId.TONE, tone.encode()),
            StateFile.Entry(ParamId.STEREO, stereo.encode()),
            StateFile.Entry(ParamId.COMPRESSOR, compressor.encode()),
        )
        assertContentEquals(GoldenVectors.bytes("state_file_v1_1"), StateFile.encode(entries, minor = 1))
        val decoded = StateFile.decode(GoldenVectors.bytes("state_file_v1_1"))
        assertEquals(1, decoded.minor)
        assertEquals(entries, decoded.entries)
    }
}
