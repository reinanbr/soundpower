// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.root

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsersTest {
    @Test
    fun magiskVersion() {
        // Galaxy S9, Magisk 31.1.
        assertEquals(MagiskInfo("31.1", 31100), MagiskInfo.parse(listOf("31.1:MAGISK:R", "31100")))
        assertTrue(MagiskInfo("31.1", 31100).isSupported)
        assertFalse(MagiskInfo("23.0", 23000).isSupported)
        assertNull(MagiskInfo.parse(listOf("31.1:MAGISK:R")))
        assertNull(MagiskInfo.parse(listOf("sh: magisk: not found", "")))
    }

    @Test
    fun moduleState() {
        val s = ModuleState.parse(
            listOf(
                "installed.id=soundpower",
                "installed.version=v0.1.0\r",
                "installed.versionCode=100",
                "staged.version=v0.2.0",
                "staged.versionCode=200",
                "flag.update",
            ),
        )
        assertEquals(ModuleProp("v0.1.0", 100), s.installed)
        assertEquals(ModuleProp("v0.2.0", 200), s.staged)
        assertTrue(s.updateFlag)
        assertTrue(s.rebootPending)
        assertEquals(ModuleProp("v0.2.0", 200), s.nextVersion)
        assertFalse(s.disabled)

        assertEquals(ModuleState(), ModuleState.parse(emptyList()))
        assertFalse(ModuleState().rebootPending)
        val removing = ModuleState.parse(listOf("installed.version=v0.1.0", "installed.versionCode=100", "flag.remove", "flag.disable"))
        assertTrue(removing.removing && removing.disabled && removing.rebootPending)
        assertNull(removing.nextVersion)
    }

    @Test
    fun effectDumpLines() {
        val lines = listOf(
            "  Effect ID 41:",
            "SoundPower 0.1.0 (protocol 1.0): session 81 io 13, active, 48000 Hz, 2 ch, format 0x5, " +
                "cpu avg 1.53% peak 4.40%, limiter -0.25 dB, 1234567 frames, flags 0x0, 2 instances",
            "    SoundPower 0.1.0 (protocol 1.0): session -3 io 13, initialized, 48000 Hz, 2 ch, format 0x5, " +
                "cpu avg 0.00% peak 0.00%, limiter 0.00 dB, 0 frames, flags 0x1, 2 instances",
            "  name: SoundPower",
        )
        val dumps = EffectDump.parse(lines)
        assertEquals(2, dumps.size)
        assertEquals(
            EffectDump("0.1.0", 81, 13, "active", 48000, 2, 0x5, 1.53f, 4.4f, -0.25f, 1234567L, 0, 2),
            dumps[0],
        )
        assertTrue(dumps[0].isActive)
        assertEquals(-3, dumps[1].sessionId)
        assertFalse(dumps[1].isActive)
        assertEquals(1, dumps[1].flags)
        assertNull(dumps[0].volume)
    }

    @Test
    fun effectDumpLinesWithTheStreamVolume() {
        val line = "SoundPower 0.2.0 (protocol 1.2): session 94321 io 13, active, 48000 Hz, 2 ch, format 0x5, " +
            "cpu avg 10.88% peak 15.93%, limiter 0.00 dB, 112320 frames, flags 0x18, 1 instances, " +
            "volume -14.4/-14.4 dB applied (stage -14.4 dB), peak in -40.0 out -66.2 dBFS"
        val dump = EffectDump.parse(listOf(line)).single()
        assertEquals(0x18, dump.flags)
        assertEquals(EffectDump.StreamVolume(-14.4f, -14.4f, true, -14.4f, -40f, -66.2f), dump.volume)
        val byMixer = line.replace("applied (stage -14.4 dB)", "by mixer (stage 0.0 dB)").replace("-40.0", "-999.0")
        val v = EffectDump.parse(listOf(byMixer)).single().volume
        assertEquals(false, v?.applied)
        assertEquals(-999f, v?.inputPeakDb)
    }

    @Test
    fun effectDumpLinesWithTheVolumeStage() {
        val line = "SoundPower 0.6.0 (protocol 1.3): session 689 io 13, active, 48000 Hz, 2 ch, format 0x5, " +
            "cpu avg 9.24% peak 11.99%, limiter 0.00 dB, 2751360 frames, flags 0x18, 2 instances, " +
            "volume -11.5/-11.5 dB applied (stage -11.5 dB), peak in -45.0 out -68.4 dBFS, volume stage input"
        assertEquals(true, EffectDump.parse(listOf(line)).single().volume?.atInput)
        assertEquals(false, EffectDump.parse(listOf(line.replace("stage input", "stage output"))).single().volume?.atInput)
        // A 1.2 line has no stage: before the limiter.
        assertEquals(false, EffectDump.parse(listOf(line.substringBefore(", volume stage"))).single().volume?.atInput)
    }

    @Test
    fun quoting() {
        assertEquals("'/data/a b'", shQuote("/data/a b"))
        assertEquals("'it'\\''s'", shQuote("it's"))
    }
}
