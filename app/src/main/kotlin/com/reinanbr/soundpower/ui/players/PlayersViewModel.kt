// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.players

import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.reinanbr.soundpower.SoundPowerApplication
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.engine.RootlessOptions
import com.reinanbr.soundpower.engine.RootlessScope
import com.reinanbr.soundpower.players.Player
import com.reinanbr.soundpower.players.PlayerTracker
import com.reinanbr.soundpower.players.Players
import com.reinanbr.soundpower.players.ServiceDump
import com.reinanbr.soundpower.players.Tracking
import com.reinanbr.soundpower.rootless.RootlessEngine
import com.reinanbr.soundpower.ui.prefs.UiPrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayersUi(
    val mode: EngineMode? = null,
    val rootless: RootlessOptions = RootlessOptions(),
    val advanced: Boolean = false,
    val rootFallback: Boolean = false,
    val tracking: Tracking = Tracking.ANNOUNCED,
    val players: List<Player> = emptyList(),
    /** The engine attaches per player, so the switches apply. */
    val perPlayer: Boolean = false,
    /** The engine processes the whole output mix. */
    val everything: Boolean = false,
    /** App names by package. */
    val labels: Map<String, String> = emptyMap(),
    val grantCommand: String = "",
    val loaded: Boolean = false,
) {
    /** The players SoundPower processes while they play: what the equalizer page names. */
    val equalizedNow: List<Player> get() = players.filter { it.present && it.equalized == true && it.playing != false }

    /** Players that play without SoundPower. */
    val missedNow: List<Player> get() = players.filter { it.present && it.playing == true && it.equalized == false && it.enabled }
}

/**
 * The players screen and the equalizer page's player chip. Collecting the state keeps
 * AudioFlinger's dump being read, so it is only collected while one of them is visible.
 */
class PlayersViewModel(
    private val tracker: PlayerTracker,
    private val prefs: UiPrefsStore,
    rootless: RootlessEngine,
    private val packages: PackageManager,
    packageName: String,
) : ViewModel() {
    private val labels = HashMap<String, String?>()

    private val options = combine(prefs.engine, prefs.rootless, prefs.advancedTracking, prefs.rootFallback, tracker.tracking) { m, r, a, f, t ->
        PlayersUi(mode = m, rootless = r, advanced = a, rootFallback = f, tracking = t)
    }

    val state: StateFlow<PlayersUi> = combine(options, tracker.sessions, tracker.known, tracker.dump, rootless.status) { o, sessions, known, dump, engine ->
        val everything = o.mode == EngineMode.ROOTLESS && o.rootless.scope == RootlessScope.GLOBAL &&
            engine.attachments.any { it.session == RootlessEngine.GLOBAL_SESSION && it.active }
        val equalized = when (o.mode) {
            EngineMode.ROOT -> dump?.let(Players::equalized)
            EngineMode.ROOTLESS -> engine.attachments.filter { it.active }.mapTo(HashSet()) { it.session }
            null -> null
        }
        val list = Players.list(sessions, known, equalized, everything)
        o.copy(
            players = list,
            perPlayer = (o.mode == EngineMode.ROOTLESS && o.rootless.scope == RootlessScope.SESSIONS) || (o.mode == EngineMode.ROOT && o.rootFallback),
            everything = everything,
            labels = labelsFor(list.map { it.packageName }),
            grantCommand = ServiceDump.grantCommand(packageName),
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlayersUi())

    fun setEnabled(packageName: String, enabled: Boolean) = tracker.setEnabled(packageName, enabled)

    fun forget(packageName: String) = tracker.forget(packageName)

    fun setAdvanced(on: Boolean) {
        viewModelScope.launch { prefs.setAdvancedTracking(on) }
    }

    fun setRootFallback(on: Boolean) {
        viewModelScope.launch { prefs.setRootFallback(on) }
    }

    fun setScope(scope: RootlessScope) {
        viewModelScope.launch { prefs.setRootless(state.value.rootless.copy(scope = scope)) }
    }

    fun recheckPermission() = tracker.recheckPermission()

    private suspend fun labelsFor(names: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        names.mapNotNull { name -> label(name)?.let { name to it } }.toMap()
    }

    private fun label(name: String): String? {
        if (name in labels) return labels[name]
        val label = if (!name.contains('.')) {
            null
        } else {
            try {
                packages.getApplicationLabel(packages.getApplicationInfo(name, 0)).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
        labels[name] = label
        return label
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as SoundPowerApplication
                val c = app.container
                PlayersViewModel(c.players, c.uiPrefs, c.rootless, app.packageManager, app.packageName)
            }
        }
    }
}
