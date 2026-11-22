// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.players

import android.util.Log
import com.reinanbr.soundpower.effect.EffectController
import com.reinanbr.soundpower.effect.EffectException
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.ui.prefs.UiPrefsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * Keeps the effect's `INPUT_VOLUME_SESSIONS` (protocol 1.3) in step with the players the user left
 * out of direct volume control: the effect applies their volume at the chain's input, as the
 * mixer would. Root mode only. Following the players' sessions keeps AudioFlinger's dump being
 * read, so it happens only while a player is left out; otherwise the list is emptied.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectVolumeSessions(
    private val effect: EffectController,
    players: PlayerTracker,
    prefs: UiPrefsStore,
) {
    /** A player is left out where it matters: the root mode, with direct volume control on. */
    val wanted: Flow<Boolean> = combine(prefs.engine, prefs.directVolume, prefs.knownPlayers) { mode, on, known ->
        mode == EngineMode.ROOT && on && known.withoutDirectVolume.isNotEmpty()
    }.distinctUntilChanged()

    private val lists: Flow<List<Int>> = combine(prefs.engine, wanted) { mode, w -> mode to w }
        .distinctUntilChanged()
        .flatMapLatest { (mode, w) ->
            when {
                w -> combine(players.sessions, players.known) { s, k -> Players.inputVolumeSessions(s, k.withoutDirectVolume) }
                // Clears a list left by an earlier run of the app (the effect keeps it).
                mode == EngineMode.ROOT -> flowOf(emptyList())
                else -> emptyFlow()
            }
        }
        .distinctUntilChanged()

    /** Runs until cancelled. */
    suspend fun run() {
        lists.collect { sessions ->
            try {
                effect.setInputVolumeSessions(sessions)
            } catch (e: EffectException) {
                // Tried again with the next change, and sent with the settings on a reconnect.
                Log.w(TAG, "cannot set the sessions left out of direct volume control: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "SoundPower"
    }
}
