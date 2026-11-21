// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.Band
import com.reinanbr.soundpower.protocol.BandChannel
import com.reinanbr.soundpower.protocol.BandType
import com.reinanbr.soundpower.protocol.Capability
import com.reinanbr.soundpower.protocol.EqState
import com.reinanbr.soundpower.protocol.CompressorParams
import com.reinanbr.soundpower.protocol.LimiterParams
import com.reinanbr.soundpower.protocol.ParamId
import com.reinanbr.soundpower.protocol.Protocol
import com.reinanbr.soundpower.protocol.ProtocolException
import com.reinanbr.soundpower.protocol.StateFile
import com.reinanbr.soundpower.protocol.StereoParams
import com.reinanbr.soundpower.protocol.ToneParams
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EqSettingsTest {
    private fun vector(name: String): ByteArray {
        val hex = File(System.getProperty("soundpower.vectors"), "$name.hex").readLines()
            .filterNot { it.startsWith("#") }.joinToString(" ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        return ByteArray(hex.size) { hex[it].toInt(16).toByte() }
    }

    @Test
    fun readsTheGoldenStateFile() {
        val s = EqSettings.fromStateFile(vector("state_file_example"))
        assertTrue(s.bypass)
        assertFalse(s.autoPreamp)
        assertEquals(-6.5f, s.preampDb)
        assertEquals(listOf(BandType.LOW_SHELF, BandType.PEAKING, BandType.HIGH_PASS), s.bands.map { it.type })
        assertEquals(LimiterParams(-3f, 5f, 250f), s.limiter)
        // Written back as 1.0 (no 1.1 entries), it is the same file.
        assertContentEquals(vector("state_file_example"), StateFile.encode(s.messages(0), minor = 0))
    }

    @Test
    fun readsTheProtocol11StateFile() {
        val bytes = vector("state_file_v1_1")
        val s = EqSettings.fromStateFile(bytes)
        assertFalse(s.limiterEnabled)
        assertEquals(ToneParams(false, 7.5f, 62.5f, -3f, 12000f), s.tone)
        assertEquals(StereoParams(true, -0.25f, 1.5f), s.stereo)
        assertEquals(CompressorParams(true, -24f, 4.5f, 2.5f, 400f, 0f, 6f), s.compressor)
        // Written back with the same entries, under the current protocol version.
        val written = StateFile.decode(s.toStateFile())
        assertEquals(Protocol.VERSION_MINOR, written.minor)
        assertEquals(StateFile.decode(bytes).entries, written.entries)
    }

    @Test
    fun skipsUnknownEntries() {
        assertEquals(
            EqSettings.fromStateFile(vector("state_file_example")),
            EqSettings.fromStateFile(vector("state_file_unknown_entry")),
        )
    }

    @Test
    fun missingEntriesKeepTheDefaults() {
        val onlyLimiter = StateFile.encode(listOf(StateFile.Entry(ParamId.LIMITER, LimiterParams(-2f).encode())))
        assertEquals(EqSettings.DEFAULT.copy(limiter = LimiterParams(-2f)), EqSettings.fromStateFile(onlyLimiter))
    }

    @Test
    fun rejectsWhatTheEffectWouldReject() {
        val bad = vector("state_file_example").let { it.copyOf(it.size - 1) }
        assertFailsWith<ProtocolException> { EqSettings.fromStateFile(bad) }
    }

    @Test
    fun routedBandsAreSentDisabledToAnEffectThatCannotRouteThem() {
        val left = Band(BandType.PEAKING, true, 1000f, 6f, 1f, BandChannel.LEFT)
        val all = Band(BandType.LOW_SHELF, true, 100f, 3f, 0.7f)
        val s = EqSettings(bands = listOf(left, all))
        fun sentBands(caps: Int) = EqState.decode(s.messages(caps).first { it.id == ParamId.EQ_STATE }.payload).bands
        assertEquals(listOf(left, all), sentBands(EqSettings.ALL_CAPABILITIES))
        assertEquals(listOf(left.copy(enabled = false, channel = BandChannel.ALL), all), sentBands(Capability.TONE))
        // state.bin follows the same rule.
        val file = EqSettings.fromStateFile(s.toStateFile(Capability.TONE))
        assertFalse(file.bands[0].enabled)
        assertEquals(left, EqSettings.fromStateFile(s.toStateFile()).bands[0])
    }

    @Test
    fun defaultsRoundTrip() {
        assertEquals(EqSettings.DEFAULT, EqSettings.fromStateFile(EqSettings.DEFAULT.toStateFile()))
        assertEquals(listOf(ParamId.MASTER, ParamId.EQ_STATE, ParamId.LIMITER), EqSettings.DEFAULT.messages(0).map { it.id })
    }
}
