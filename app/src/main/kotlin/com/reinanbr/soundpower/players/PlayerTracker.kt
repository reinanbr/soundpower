// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.players

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import androidx.core.content.ContextCompat
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.root.AudioFlingerDump
import com.reinanbr.soundpower.root.ModuleManager
import com.reinanbr.soundpower.ui.prefs.UiPrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How the app finds the players. */
enum class Tracking {
    /** Only the sessions players announce. */
    ANNOUNCED,

    /** AudioFlinger's dump read as root. */
    ROOT,

    /** AudioFlinger's dump read with the DUMP permission. */
    PERMISSION,

    /** Advanced tracking is on but the DUMP permission is missing. */
    NEEDS_PERMISSION,
}

/**
 * Which players play, from two sources: the sessions they announce
 * (`ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION`), heard while the app's process lives, and
 * AudioFlinger's dump, read every few seconds while something needs it: as root in the root mode,
 * with the DUMP permission without root when advanced tracking is on. Players seen are remembered
 * with the user's choice for each.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerTracker(
    private val app: Context,
    private val modules: ModuleManager,
    engine: Flow<EngineMode?>,
    private val prefs: UiPrefsStore,
    private val ownPid: Int,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _announced = MutableStateFlow<Map<Int, String?>>(emptyMap())

    /** Sessions players opened and did not close yet, with their package. */
    val announced: StateFlow<Map<Int, String?>> = _announced.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            if (session <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION ->
                    _announced.update { it + (session to intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)) }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> _announced.update { it - session }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        // Players are other apps.
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /** Bumped to read the DUMP permission again after the user granted it. */
    private val permissionCheck = MutableStateFlow(0)

    val tracking: Flow<Tracking> = combine(engine, prefs.advancedTracking, permissionCheck) { mode, advanced, _ ->
        when {
            mode == EngineMode.ROOT -> Tracking.ROOT
            mode == EngineMode.ROOTLESS && advanced -> if (ServiceDump.granted(app)) Tracking.PERMISSION else Tracking.NEEDS_PERMISSION
            else -> Tracking.ANNOUNCED
        }
    }.distinctUntilChanged()

    /** AudioFlinger's dump every [INTERVAL_MS] while collected, or null when the app cannot read it. */
    val dump: Flow<AudioFlingerDump?> = tracking.flatMapLatest { t ->
        when (t) {
            Tracking.ROOT -> poll { modules.audioFlinger() }
            Tracking.PERMISSION -> poll { withContext(Dispatchers.IO) { ServiceDump.read(ServiceDump.AUDIO_FLINGER)?.let(AudioFlingerDump::parse) } }
            Tracking.ANNOUNCED, Tracking.NEEDS_PERMISSION -> flowOf(null)
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    /** Every player session the app knows of; collecting it keeps the dump being read. */
    val sessions: Flow<List<PlayerSession>> = combine(announced, dump) { a, d -> Players.merge(a, d?.let { Players.tracked(it, ownPid) }) }
        .distinctUntilChanged()
        .onEach { list -> remember(list) }
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    val known: Flow<KnownPlayers> = prefs.knownPlayers

    fun recheckPermission() = permissionCheck.update { it + 1 }

    fun setEnabled(packageName: String, enabled: Boolean) {
        scope.launch { prefs.updateKnownPlayers { it.setEnabled(packageName, enabled) } }
    }

    fun forget(packageName: String) {
        scope.launch { prefs.updateKnownPlayers { it.forget(packageName) } }
    }

    private suspend fun remember(list: List<PlayerSession>) {
        val names = list.mapNotNull { it.packageName }.filter { it != app.packageName }
        if (names.isNotEmpty()) prefs.updateKnownPlayers { it.seen(names, clock()) }
    }

    private fun poll(read: suspend () -> AudioFlingerDump?): Flow<AudioFlingerDump?> = flow {
        while (true) {
            emit(read())
            delay(INTERVAL_MS)
        }
    }

    private companion object {
        const val INTERVAL_MS = 3_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
