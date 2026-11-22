// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.root

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioFlingerDumpTest {
    private fun fixture(name: String): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/$name")) { "missing $name" }.bufferedReader().readLines()

    /** Galaxy S9, Android 12: a player with SoundPower and another app's effects on its session. */
    @Test
    fun android12() {
        val d = AudioFlingerDump.parse(fixture("audioflinger_android12.txt"))
        assertEquals(listOf("soundpower", "offload"), d.librariesLoaded)
        assertTrue(d.librariesNotLoaded.isEmpty())
        assertEquals("com.example.player", d.processes[11541])
        assertEquals("com.example.soundfx", d.processes[2925])
        assertEquals("audioserver", d.processes[24582])

        assertEquals(listOf(13, 21), d.threads.map { it.io })
        val main = d.threads[0]
        assertEquals("MIXER", main.type)
        assertEquals(48000, main.sampleRate)
        assertEquals("0x8 (AUDIO_DEVICE_OUT_WIRED_HEADPHONE)", main.devices)
        assertTrue(main.mixesInSoftware)
        assertEquals(listOf(AudioFlingerDump.Track(66, true, false, 11541, 1449, 3)), main.tracks)
        assertEquals(listOf(1569, 1449), main.chains.map { it.session })

        // The app's control instance: idle, with its own dump line.
        val control = main.chains[0].effects.single()
        assertTrue(control.isSoundPower)
        assertFalse(control.enabled)
        assertEquals(11543, control.clientPid)
        assertEquals("initialized", control.dump?.state)

        val player = main.chain(1449)!!
        assertEquals(
            listOf("Equalizer", "Dynamic Bass Boost", "Insert Preset Reverb", "SoundPower", "Virtualizer"),
            player.effects.map { it.name },
        )
        assertEquals(listOf(true, true, false, true, true), player.effects.map { it.enabled })
        assertEquals("NXP Software Ltd.", player.effects[0].implementor)
        assertEquals(2925, player.effects[0].clientPid)
        val sp = assertNotNull(player.soundPower)
        assertEquals(24582, sp.clientPid)
        assertEquals(0x250, sp.flags)
        assertTrue(sp.controlsVolume)
        assertEquals(1449, sp.dump?.sessionId)
        assertEquals(3.0f, sp.dump?.volume?.outputPeakDb)
        // The virtualizer comes after SoundPower and is enabled: it controls the volume.
        assertEquals("Virtualizer", player.volumeController?.name)

        assertEquals(2, d.soundPowerInstances.size)
        assertEquals(listOf(1569, 1449), d.soundPowerInstances.map { it.chain.session })

        // A static track on the fast output; it has no chain.
        val fast = d.threads[1]
        assertEquals(listOf(AudioFlingerDump.Track(55, false, false, 1693, 129, 1)), fast.tracks)
        assertTrue(fast.chains.isEmpty())
    }

    /** moto g35, Android 15: an "Internal" column, a vendor effect on session 0, no SoundPower. */
    @Test
    fun android15() {
        val d = AudioFlingerDump.parse(fixture("audioflinger_android15.txt"))
        assertEquals("com.example.player", d.processes[773])
        assertEquals("com.dolby.daxservice", d.processes[2836])
        assertEquals(listOf(13, 21, 117, 125), d.threads.map { it.io })
        assertEquals(listOf("MIXER", "MIXER", "MIXER", "DUPLICATING"), d.threads.map { it.type })
        assertNull(d.threads[0].devices)

        val a2dp = d.threads[2]
        assertEquals(44100, a2dp.sampleRate)
        assertEquals("0x80 (AUDIO_DEVICE_OUT_BLUETOOTH_A2DP)", a2dp.devices)
        assertEquals(listOf(853, 854), a2dp.tracks.map { it.id })
        assertEquals(listOf(13, 3), a2dp.tracks.map { it.streamType })
        assertTrue(a2dp.tracks[1].isMusic)
        assertFalse(a2dp.tracks[0].isMusic)
        val mix = a2dp.chain(0)!!
        assertEquals(listOf("DAP", "Dynamics Processing"), mix.effects.map { it.name })
        assertEquals(listOf(false, true), mix.effects.map { it.enabled })
        assertEquals(7991, mix.effects[1].clientPid)
        assertEquals("Dynamics Processing", mix.volumeController?.name)

        val listener = d.threads[1].chain(6161)!!.effects.single()
        assertEquals("Music Listener", listener.name)
        assertTrue(listener.enabled)
        assertFalse(listener.controlsVolume)
        assertTrue(d.soundPowerInstances.isEmpty())
    }

    @Test
    fun fastAndPatchTracks() {
        val header = "    Type     Id Active Client Session Port Id S  Flags"
        // Right-aligned under the header, as AudioFlinger prints them.
        fun row(type: String, id: Int, client: Int, session: Int) =
            type.padEnd(8) + "%7d%7s%7d%8d%8d A  0x000".format(id, "yes", client, session, 30)
        val d = AudioFlingerDump.parse(
            listOf(
                "Output thread 0x1, name AudioOut_15, tid 1, type 0 (MIXER):",
                "  Sample rate: 48000 Hz",
                "  3 Tracks of which 3 are active",
                header,
                row("  F1", 70, 4321, 2001),
                row("    P", 71, 678, 0),
                row("", 72, 4322, 2009),
                "  0 Effect Chains",
                "Output thread 0x2, name AudioOut_1d, tid 2, type 4 (OFFLOAD):",
            ),
        )
        val tracks = d.threads[0].tracks
        assertEquals(listOf(70, 72), tracks.map { it.id })
        assertEquals(listOf(true, false), tracks.map { it.fast })
        assertEquals(0x1d, d.threads[1].io)
        assertFalse(d.threads[1].mixesInSoftware)
    }

    @Test
    fun unparsableInput() {
        assertTrue(AudioFlingerDump.parse(emptyList()).threads.isEmpty())
        assertTrue(AudioFlingerDump.parse(listOf("Can't find service: media.audio_flinger")).threads.isEmpty())
    }
}
