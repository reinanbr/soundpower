// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Protocol 1.2 (direct volume control) against the golden vectors the effect-lib tests also use. */
class Protocol12Test {
    @Test
    fun masterDirectVolumeMatchesTheGoldenVector() {
        val master = MasterParams(autoPreamp = true, preampDb = -2.5f, directVolume = true)
        assertContentEquals(GoldenVectors.bytes("master_direct_volume"), master.encode())
        assertEquals(master, MasterParams.decode(GoldenVectors.bytes("master_direct_volume")))
        // A 1.1 payload has no direct-volume bit: off.
        assertFalse(MasterParams.decode(GoldenVectors.bytes("master_limiter_off")).directVolume)
    }

    @Test
    fun stateFile12MatchesTheGoldenVector() {
        val v11 = StateFile.decode(GoldenVectors.bytes("state_file_v1_1")).entries
        val master = MasterParams(autoPreamp = true, preampDb = 3.25f, limiterEnabled = false, directVolume = true)
        val entries = listOf(StateFile.Entry(ParamId.MASTER, master.encode())) + v11.drop(1)
        assertContentEquals(GoldenVectors.bytes("state_file_v1_2"), StateFile.encode(entries, minor = 2))
        val decoded = StateFile.decode(GoldenVectors.bytes("state_file_v1_2"))
        assertEquals(2, decoded.minor)
        assertEquals(entries, decoded.entries)
    }

    @Test
    fun statusFlagsReportTheVolumeControl() {
        fun status(flags: Int) = EffectStatus(48000, 2, 5, EffectStatus.STATE_ACTIVE, 0f, 0f, 1, 7, flags, 0f, 0L)
        assertFalse(status(0x0).volumeControlClaimed)
        assertTrue(status(0x8).volumeControlClaimed)
        assertFalse(status(0x8).volumeApplied)
        assertTrue(status(0x18).volumeApplied)
    }
}
