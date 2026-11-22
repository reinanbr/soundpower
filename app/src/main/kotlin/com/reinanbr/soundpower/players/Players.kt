// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.players

import com.reinanbr.soundpower.root.AudioFlingerDump

/** A session a player plays on, and what the app knows about it. */
data class PlayerSession(
    val session: Int,
    /** The player's package, when known. */
    val packageName: String?,
    /** The player announced it (`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`). */
    val announced: Boolean,
    /** AudioFlinger has an active track on it; null without tracking. */
    val playing: Boolean?,
)

/** A player as the screens show it: present now, known from before, or both. */
data class Player(
    val packageName: String,
    val sessions: List<Int>,
    /** A track of it plays now; null when the app cannot tell. */
    val playing: Boolean?,
    val announced: Boolean,
    /** The user lets SoundPower attach to it (the modes that attach per player). */
    val enabled: Boolean,
    /** SoundPower processes one of its sessions; null when the app cannot tell. */
    val equalized: Boolean?,
    val lastSeen: Long?,
) {
    val present: Boolean get() = sessions.isNotEmpty()
}

/** A player the app has seen, remembered with the user's choice for it. */
data class KnownPlayer(val packageName: String, val enabled: Boolean = true, val lastSeen: Long = 0)

/** The remembered players, newest first, kept as one line in the app's preferences. */
data class KnownPlayers(val players: List<KnownPlayer> = emptyList()) {
    val disabled: Set<String> get() = players.filterNot { it.enabled }.mapTo(HashSet()) { it.packageName }

    operator fun get(packageName: String): KnownPlayer? = players.firstOrNull { it.packageName == packageName }

    /** Records [packages] as seen at [now]; null when nothing changed enough to be saved. */
    fun seen(packages: Collection<String>, now: Long): KnownPlayers? {
        if (packages.isEmpty()) return null
        val stale = packages.filter { p -> (get(p)?.lastSeen ?: Long.MIN_VALUE) < now - SEEN_RESOLUTION_MS }
        if (stale.isEmpty()) return null
        val updated = players.associateBy { it.packageName }.toMutableMap()
        for (p in stale) updated[p] = (updated[p] ?: KnownPlayer(p)).copy(lastSeen = now)
        return KnownPlayers(updated.values.sortedByDescending { it.lastSeen }.take(MAX_PLAYERS))
    }

    fun setEnabled(packageName: String, enabled: Boolean): KnownPlayers {
        val known = get(packageName)
        return if (known == null) {
            KnownPlayers(players + KnownPlayer(packageName, enabled))
        } else {
            KnownPlayers(players.map { if (it.packageName == packageName) it.copy(enabled = enabled) else it })
        }
    }

    fun forget(packageName: String): KnownPlayers = KnownPlayers(players.filterNot { it.packageName == packageName })

    fun encode(): String = players.joinToString(";") { "${it.packageName},${if (it.enabled) 1 else 0},${it.lastSeen}" }

    companion object {
        const val MAX_PLAYERS = 50

        /** A player seen again within this time is not saved again. */
        const val SEEN_RESOLUTION_MS = 60_000L

        fun decode(s: String?): KnownPlayers = KnownPlayers(
            s.orEmpty().split(';').mapNotNull { item ->
                val f = item.split(',')
                val name = f.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                KnownPlayer(name, f.getOrNull(1) != "0", f.getOrNull(2)?.toLongOrNull() ?: 0)
            },
        )
    }
}

object Players {
    /** Processes of the system itself, which play sounds but are not players. */
    val SYSTEM_PROCESSES = setOf("audioserver", "media", "android.uid.system", "system_server")

    /** "com.example.player:service" belongs to the package "com.example.player". */
    fun packageOf(process: String?): String? = process?.substringBefore(':')?.takeIf { it.isNotEmpty() }

    /**
     * The sessions of other apps' music tracks in [dump], with whether one of their tracks is
     * active. The system's own sounds, the app's own tracks and other streams are left out.
     */
    fun tracked(dump: AudioFlingerDump, ownPid: Int): List<PlayerSession> =
        dump.threads.flatMap { it.tracks }
            .filter { it.isMusic && it.session > 0 && it.clientPid != ownPid }
            .filter { dump.processes[it.clientPid] !in SYSTEM_PROCESSES }
            .groupBy { it.session }
            .map { (session, tracks) ->
                PlayerSession(session, packageOf(dump.processes[tracks.first().clientPid]), announced = false, playing = tracks.any { it.active })
            }
            .sortedBy { it.session }

    /**
     * The sessions SoundPower's own effect processes in [dump]: those with an enabled instance in
     * their chain, and every session of a thread with one on its output mix (session 0).
     */
    fun equalized(dump: AudioFlingerDump): Set<Int> = buildSet {
        for (thread in dump.threads) {
            val chains = thread.chains.filter { c -> c.effects.any { it.isSoundPower && it.enabled } }
            if (chains.any { it.session == 0 }) thread.tracks.mapTo(this) { it.session }
            chains.mapTo(this) { it.session }
        }
    }

    /** Announced sessions and tracked ones in one list; tracking says which play. */
    fun merge(announced: Map<Int, String?>, tracked: List<PlayerSession>?): List<PlayerSession> {
        val byTrack = tracked.orEmpty().associateBy { it.session }
        val fromAnnounce = announced.map { (session, pkg) ->
            val t = byTrack[session]
            PlayerSession(session, pkg ?: t?.packageName, announced = true, playing = if (tracked == null) null else t?.playing == true)
        }
        return (fromAnnounce + tracked.orEmpty().filter { it.session !in announced }).sortedBy { it.session }
    }

    /** The sessions an engine that attaches per player uses: every session of a player not turned off. */
    fun targets(sessions: List<PlayerSession>, disabled: Set<String>): Map<Int, String?> =
        sessions.filter { it.packageName == null || it.packageName !in disabled }.associate { it.session to it.packageName }

    /**
     * The players to list: those with sessions now (playing first), then the remembered ones.
     * [equalized] is the set of processed sessions, or null when the app cannot tell; [everything]
     * means the engine processes the whole output mix.
     */
    fun list(sessions: List<PlayerSession>, known: KnownPlayers, equalized: Set<Int>?, everything: Boolean): List<Player> {
        val present = sessions.filter { it.packageName != null }.groupBy { it.packageName!! }.map { (pkg, list) ->
            val playing = list.mapNotNull { it.playing }.takeIf { it.size == list.size }?.any { it }
            Player(
                packageName = pkg,
                sessions = list.map { it.session },
                playing = playing,
                announced = list.any { it.announced },
                enabled = known[pkg]?.enabled ?: true,
                equalized = when {
                    everything -> true
                    equalized == null -> null
                    else -> list.any { it.session in equalized }
                },
                lastSeen = known[pkg]?.lastSeen,
            )
        }.sortedWith(compareByDescending<Player> { it.playing == true }.thenBy { it.packageName })
        val names = present.mapTo(HashSet()) { it.packageName }
        val absent = known.players.filter { it.packageName !in names }.map {
            Player(it.packageName, emptyList(), playing = false, announced = false, enabled = it.enabled, equalized = false, lastSeen = it.lastSeen)
        }
        return present + absent
    }
}
