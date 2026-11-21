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
    }

    @Test
    fun quoting() {
        assertEquals("'/data/a b'", shQuote("/data/a b"))
        assertEquals("'it'\\''s'", shQuote("it's"))
    }
}
