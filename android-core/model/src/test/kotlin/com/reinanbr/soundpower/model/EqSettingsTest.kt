// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.BandType
import com.reinanbr.soundpower.protocol.LimiterParams
import com.reinanbr.soundpower.protocol.ParamId
import com.reinanbr.soundpower.protocol.ProtocolException
import com.reinanbr.soundpower.protocol.StateFile
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
        // Written back, it is the same file.
        assertContentEquals(vector("state_file_example"), s.toStateFile())
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
    fun defaultsRoundTrip() {
        assertEquals(EqSettings.DEFAULT, EqSettings.fromStateFile(EqSettings.DEFAULT.toStateFile()))
        assertEquals(listOf(ParamId.MASTER, ParamId.EQ_STATE, ParamId.LIMITER), EqSettings.DEFAULT.messages().map { it.id })
    }
}
