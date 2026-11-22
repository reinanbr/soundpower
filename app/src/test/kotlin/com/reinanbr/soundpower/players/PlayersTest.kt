// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.players

import com.reinanbr.soundpower.protocol.Protocol
import com.reinanbr.soundpower.root.AudioFlingerDump
import com.reinanbr.soundpower.root.AudioFlingerDump.ChainEffect
import com.reinanbr.soundpower.root.AudioFlingerDump.EffectChain
import com.reinanbr.soundpower.root.AudioFlingerDump.OutputThread
import com.reinanbr.soundpower.root.AudioFlingerDump.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayersTest {
    private fun track(session: Int, pid: Int, active: Boolean = true, stream: Int? = 3) = Track(session % 100, active, false, pid, session, stream)

    private fun sp(session: Int, enabled: Boolean = true) = EffectChain(
        session,
        listOf(ChainEffect(session, "SoundPower", "SoundPower contributors", AudioFlingerDump.SOUNDPOWER_UUID, enabled, 0x250, AUDIOSERVER)),
    )

    private fun thread(io: Int, tracks: List<Track>, chains: List<EffectChain> = emptyList()) =
        OutputThread(io, "out$io", "MIXER", 48000, "0x2 (AUDIO_DEVICE_OUT_SPEAKER)", tracks, chains)

    private fun dump(vararg threads: OutputThread) = AudioFlingerDump(
        threads.toList(),
        mapOf(AUDIOSERVER to "audioserver", PLAYER to "com.example.player", SERVICE to "com.example.radio:playback", OWN to "com.reinanbr.soundpower", GAME to "com.example.game"),
        emptyList(),
        emptyList(),
    )

    @Test
    fun tracksOtherAppsMusic() {
        val d = dump(
            thread(13, listOf(track(201, PLAYER), track(201, PLAYER, active = false), track(209, SERVICE, active = false), track(217, AUDIOSERVER), track(225, OWN))),
            thread(21, listOf(track(233, GAME, stream = 1), track(241, 9999))),
        )
        assertEquals(
            listOf(
                PlayerSession(201, "com.example.player", announced = false, playing = true),
                PlayerSession(209, "com.example.radio", announced = false, playing = false),
                // A process the dump does not name is still a player.
                PlayerSession(241, null, announced = false, playing = true),
            ),
            Players.tracked(d, OWN),
        )
    }

    @Test
    fun equalizedSessions() {
        val d = dump(
            thread(13, listOf(track(201, PLAYER), track(209, SERVICE)), listOf(sp(201), sp(209, enabled = false))),
            // SoundPower on the output mix covers every track of the thread.
            thread(21, listOf(track(233, GAME), track(241, PLAYER)), listOf(sp(0))),
        )
        assertEquals(setOf(201, 0, 233, 241), Players.equalized(d))
    }

    @Test
    fun mergesAnnouncedAndTracked() {
        val tracked = listOf(PlayerSession(201, "com.example.player", false, true), PlayerSession(209, "com.example.radio", false, false))
        assertEquals(
            listOf(
                PlayerSession(201, "com.example.player", announced = true, playing = true),
                PlayerSession(209, "com.example.radio", announced = false, playing = false),
                // Announced but not in the dump: no track yet.
                PlayerSession(301, "com.example.app", announced = true, playing = false),
            ),
            Players.merge(mapOf(201 to null, 301 to "com.example.app"), tracked),
        )
        // Without tracking, the app cannot tell whether a session plays.
        assertNull(Players.merge(mapOf(201 to "com.example.player"), null).single().playing)
    }

    @Test
    fun targetsLeaveOutPlayersTurnedOff() {
        val sessions = listOf(
            PlayerSession(201, "com.example.player", true, true),
            PlayerSession(209, "com.example.radio", false, false),
            PlayerSession(241, null, false, true),
        )
        assertEquals(mapOf(201 to "com.example.player", 241 to null), Players.targets(sessions, setOf("com.example.radio")))
    }

    @Test
    fun listsPresentPlayersThenRememberedOnes() {
        val sessions = listOf(
            PlayerSession(201, "com.example.radio", false, false),
            PlayerSession(209, "com.example.player", true, true),
            PlayerSession(217, "com.example.player", false, false),
            PlayerSession(241, null, false, true),
        )
        val known = KnownPlayers(listOf(KnownPlayer("com.example.player", enabled = false, lastSeen = 5), KnownPlayer("com.example.old", lastSeen = 3)))
        val list = Players.list(sessions, known, equalized = setOf(209), everything = false)
        assertEquals(listOf("com.example.player", "com.example.radio", "com.example.old"), list.map { it.packageName })
        val player = list[0]
        assertEquals(listOf(209, 217), player.sessions)
        assertEquals(true, player.playing)
        assertEquals(true, player.equalized)
        assertEquals(false, player.enabled)
        assertEquals(false, list[1].equalized)
        assertEquals(false, list[2].present)

        // The whole output mix: everyone present is equalized; unknown without a way to tell.
        assertEquals(listOf(true, true, false), Players.list(sessions, known, null, everything = true).map { it.equalized })
        assertEquals(listOf(null, null, false), Players.list(sessions, known, null, everything = false).map { it.equalized })
    }

    @Test
    fun sessionsLeftOutOfDirectVolumeControl() {
        val sessions = listOf(
            PlayerSession(201, "com.example.player", true, true),
            PlayerSession(209, "com.example.radio", false, false),
            PlayerSession(217, "com.example.player", false, false),
            PlayerSession(241, null, false, true),
        )
        assertEquals(listOf(201, 217), Players.inputVolumeSessions(sessions, setOf("com.example.player")))
        assertEquals(emptyList(), Players.inputVolumeSessions(sessions, emptySet()))
        // Past the protocol's limit, playing sessions are kept first.
        val many = (1..40).map { PlayerSession(it, "com.example.p", false, playing = it == 40) }
        val kept = Players.inputVolumeSessions(many, setOf("com.example.p"))
        assertEquals(Protocol.MAX_INPUT_VOLUME_SESSIONS, kept.size)
        assertTrue(40 in kept)
    }

    @Test
    fun knownPlayersKeepTheDirectVolumeChoice() {
        // A line from before the choice existed leaves direct volume control on.
        val old = KnownPlayers.decode("com.example.a,1,100")
        assertEquals(true, old["com.example.a"]?.directVolume)
        val off = old.setDirectVolume("com.example.a", false).setDirectVolume("com.example.new", false)
        assertEquals(setOf("com.example.a", "com.example.new"), off.withoutDirectVolume)
        assertEquals("com.example.a,1,100,0;com.example.new,1,0,0", off.encode())
        assertEquals(off, KnownPlayers.decode(off.encode()))
        // Turning a player off for the per-player modes keeps its volume choice, and back.
        assertEquals(false, off.setEnabled("com.example.a", false)["com.example.a"]?.directVolume)
        val list = Players.list(listOf(PlayerSession(5, "com.example.a", true, true)), off, null, false)
        assertEquals(listOf(false, false), list.map { it.directVolume })
    }

    @Test
    fun knownPlayers() {
        val start = KnownPlayers.decode("com.example.a,1,100;com.example.b,0,50;;bad")
        assertEquals(listOf(KnownPlayer("com.example.a", true, 100), KnownPlayer("com.example.b", false, 50), KnownPlayer("bad", true, 0)), start.players)
        assertEquals(setOf("com.example.b"), start.disabled)
        assertEquals(start, KnownPlayers.decode(start.encode()))

        // Seen again within a minute: nothing to save.
        assertNull(start.seen(listOf("com.example.a"), 100 + 1_000))
        val seen = start.seen(listOf("com.example.b", "com.example.c"), 100_000)!!
        assertEquals(listOf("com.example.b", "com.example.c", "com.example.a", "bad"), seen.players.map { it.packageName })
        assertEquals(false, seen["com.example.b"]?.enabled)

        val toggled = seen.setEnabled("com.example.a", false).setEnabled("com.example.new", false)
        assertEquals(setOf("com.example.a", "com.example.b", "com.example.new"), toggled.disabled)
        assertEquals(listOf("com.example.b", "com.example.c", "bad", "com.example.new"), toggled.forget("com.example.a").players.map { it.packageName })

        val many = (1..60).fold(KnownPlayers()) { k, i -> k.seen(listOf("com.example.p$i"), i * 100_000L)!! }
        assertEquals(KnownPlayers.MAX_PLAYERS, many.players.size)
        assertEquals("com.example.p60", many.players.first().packageName)
    }

    private companion object {
        const val AUDIOSERVER = 1000
        const val PLAYER = 2000
        const val SERVICE = 2100
        const val OWN = 3000
        const val GAME = 4000
    }
}
