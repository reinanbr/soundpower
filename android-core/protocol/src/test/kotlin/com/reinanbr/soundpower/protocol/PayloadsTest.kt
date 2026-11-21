// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The same golden vectors as the effect-lib tests: both sides must produce identical bytes. */
class PayloadsTest {
    private val threeBands = EqState(
        listOf(
            Band(BandType.LOW_SHELF, enabled = true, freqHz = 80f, gainDb = 6f, q = 0.75f),
            Band(BandType.PEAKING, enabled = true, freqHz = 1000f, gainDb = -3.5f, q = 1.5f),
            Band(BandType.HIGH_PASS, enabled = false, freqHz = 30f, gainDb = 0f, q = 0.5f),
        ),
    )

    @Test
    fun masterMatchesTheGoldenVectors() {
        assertContentEquals(GoldenVectors.bytes("master_default"), MasterParams().encode())
        val bypass = MasterParams(bypass = true, autoPreamp = false, preampDb = -6.5f)
        assertContentEquals(GoldenVectors.bytes("master_bypass"), bypass.encode())
        assertEquals(bypass, MasterParams.decode(GoldenVectors.bytes("master_bypass")))
        assertEquals(MasterParams(), MasterParams.decode(GoldenVectors.bytes("master_default")))
    }

    @Test
    fun eqStateMatchesTheGoldenVectors() {
        assertContentEquals(GoldenVectors.bytes("eq_state_empty"), EqState(emptyList()).encode())
        assertContentEquals(GoldenVectors.bytes("eq_state_3band"), threeBands.encode())
        assertEquals(threeBands, EqState.decode(GoldenVectors.bytes("eq_state_3band")))
        assertEquals(EqState(emptyList()), EqState.decode(GoldenVectors.bytes("eq_state_empty")))
    }

    @Test
    fun limiterMatchesTheGoldenVectors() {
        assertContentEquals(GoldenVectors.bytes("limiter_default"), LimiterParams().encode())
        val custom = LimiterParams(ceilingDb = -3f, lookaheadMs = 5f, releaseMs = 250f)
        assertContentEquals(GoldenVectors.bytes("limiter_custom"), custom.encode())
        assertEquals(custom, LimiterParams.decode(GoldenVectors.bytes("limiter_custom")))
    }

    @Test
    fun statusDecodesTheGoldenVector() {
        val s = EffectStatus.decode(GoldenVectors.bytes("status_example"))
        assertEquals(
            EffectStatus(
                sampleRate = 48000, channels = 2, format = 0x5, state = EffectStatus.STATE_ACTIVE,
                cpuAvgPercent = 1.25f, cpuPeakPercent = 4.5f, instances = 3, sessionId = -7, flags = 0x5,
                limiterGainReductionDb = -2.75f, framesProcessed = 123456789012L,
            ),
            s,
        )
        assertTrue(s.bypassed)
    }

    @Test
    fun protocolInfoDecodes() {
        val bytes = Wire.writer(ProtocolInfo.SIZE)
            .putShort(1).putShort(0).putInt((0 shl 16) or (1 shl 8) or 0).putInt(32).putInt(3).array()
        val info = ProtocolInfo.decode(bytes)
        assertEquals(ProtocolInfo(1, 0, 0x100, 32, 3), info)
        assertEquals("0.1.0", info.libVersionName)
        assertTrue(info.isCompatible)
        assertFalse(info.copy(major = 2).isCompatible)
    }

    @Test
    fun parameterBytesAreTheLittleEndianId() {
        assertContentEquals(byteArrayOf(0x00, 0x02, 0x00, 0x00), ParamId.bytes(ParamId.EQ_STATE))
    }

    @Test
    fun longerPayloadsAndUnknownFlagsAreAccepted() {
        val longer = GoldenVectors.bytes("master_bypass") + byteArrayOf(1, 2, 3, 4)
        assertEquals(MasterParams(true, false, -6.5f), MasterParams.decode(longer))
        val unknownFlags = MasterParams().encode().also { it[3] = 0x40 }
        assertEquals(MasterParams(), MasterParams.decode(unknownFlags))
    }

    @Test
    fun malformedPayloadsAreRejected() {
        assertFailsWith<ProtocolException> { MasterParams.decode(ByteArray(7)) }
        assertFailsWith<ProtocolException> { LimiterParams.decode(ByteArray(11)) }
        assertFailsWith<ProtocolException> { EffectStatus.decode(ByteArray(47)) }
        val threeBandBytes = GoldenVectors.bytes("eq_state_3band")
        assertFailsWith<ProtocolException> { EqState.decode(threeBandBytes.copyOf(threeBandBytes.size - 1)) }
        val tooMany = Wire.writer(4).putInt(Protocol.MAX_BANDS + 1).array()
        assertFailsWith<ProtocolException> { EqState.decode(tooMany) }
        val unknownType = threeBandBytes.copyOf().also { it[4] = 7 }
        assertFailsWith<ProtocolException> { EqState.decode(unknownType) }
    }

    @Test
    fun thirtyTwoBandsRoundTrip() {
        val bands = List(Protocol.MAX_BANDS) { Band(BandType.PEAKING, it % 2 == 0, 20f * (it + 1), it - 16f, 1f) }
        val eq = EqState(bands)
        assertEquals(eq, EqState.decode(eq.encode()))
        assertEquals(4 + 16 * 32, eq.encode().size)
    }
}
