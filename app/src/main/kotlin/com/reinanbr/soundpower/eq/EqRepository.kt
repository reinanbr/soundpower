// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.eq

import com.reinanbr.soundpower.data.SettingsStore
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.effect.EffectController
import com.reinanbr.soundpower.effect.EffectException
import com.reinanbr.soundpower.model.AudioSettings
import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.protocol.EffectStatus
import com.reinanbr.soundpower.root.EffectDump
import com.reinanbr.soundpower.root.ModuleManager
import com.reinanbr.soundpower.rootless.RootlessEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What the status card shows. */
data class EqStatus(
    /** STATUS of the control instance (null if unreachable, or without root). */
    val effect: EffectStatus?,
    /** The other instances, from dumpsys (null without root). */
    val streams: List<EffectDump>?,
    /** `PROTOCOL_INFO` capabilities of the effect last reached (null before). */
    val capabilities: Int? = null,
    /** The engine of the mode without root, when that is the mode. */
    val rootless: RootlessEngine.Status? = null,
    /** SoundPower's own effect is registered: the Magisk module is active (it may process audio too). */
    val moduleActive: Boolean = false,
)

/**
 * The live audio settings. Every change goes to the engine of the current [engine] mode at once
 * (the latest value wins when changes come faster than binder calls, and only what changed is
 * sent). With root, once changes settle they are saved in the app and in the module's state.bin,
 * which the effect loads at boot, and everything is sent once more in case a new stream reloaded
 * an older state.bin in the meantime; without root they are only saved in the app, from where
 * the foreground service applies them again after a reboot.
 */
class EqRepository(
    private val store: SettingsStore,
    private val effect: EffectController,
    private val rootless: RootlessEngine,
    private val modules: ModuleManager,
    engine: Flow<EngineMode?>,
    /** True if SoundPower's own effect is registered (public API, no root). */
    private val isModuleActive: () -> Boolean,
    /** A file the app writes and root copies to state.bin. */
    private val staging: File,
    private val scope: CoroutineScope,
) {
    private val _settings = MutableStateFlow<AudioSettings?>(null)
    val settings: StateFlow<AudioSettings?> = _settings.asStateFlow()

    private val _effectError = MutableStateFlow<String?>(null)
    val effectError: StateFlow<String?> = _effectError.asStateFlow()

    private val _persistError = MutableStateFlow<String?>(null)
    val persistError: StateFlow<String?> = _persistError.asStateFlow()

    /** Null until the preference is read, and while the user has not chosen. */
    private val engine: StateFlow<EngineMode?> = engine.stateIn(scope, SharingStarted.Eagerly, null)

    private val started = AtomicBoolean(false)

    /** Loads the saved settings and starts syncing them; later calls do nothing. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val initial = store.settings.first()
            var saved = initial
            _settings.value = initial
            launch { combine(_settings.filterNotNull(), engine) { s, m -> s to m }.collect { (s, m) -> push(s, m) } }
            launch {
                _settings.filterNotNull().debounce(PERSIST_DELAY_MS).collect {
                    if (it != saved) {
                        store.save(it)
                        saved = it
                        if (engine.value == EngineMode.ROOT) {
                            writeStateFile(it)
                            push(it, EngineMode.ROOT, force = true)
                        }
                    }
                }
            }
            // state.bin may be missing (fresh install) or older than the app's copy.
            if (engine.first { it != null } == EngineMode.ROOT) writeStateFile(initial)
        }
    }

    fun update(transform: (AudioSettings) -> AudioSettings) {
        _settings.update { it?.let(transform) }
    }

    /** Sends the current settings again, e.g. after the effect was unreachable. */
    fun retry() {
        scope.launch {
            val s = _settings.value ?: return@launch
            when (engine.value) {
                EngineMode.ROOT -> push(s, EngineMode.ROOT, force = true)
                EngineMode.ROOTLESS -> rootless.retry()
                null -> {}
            }
        }
    }

    suspend fun readStatus(): EqStatus {
        if (engine.value == EngineMode.ROOTLESS) {
            return EqStatus(null, null, rootless = rootless.status.value, moduleActive = runCatching(isModuleActive).getOrDefault(false))
        }
        val status = try {
            effect.status()
        } catch (_: EffectException) {
            null
        }
        val streams = modules.effectDumps()?.filter { status == null || it.sessionId != status.sessionId }
        return EqStatus(status, streams, effect.lastCapabilities)
    }

    private suspend fun push(settings: AudioSettings, mode: EngineMode?, force: Boolean = false) {
        when (mode) {
            EngineMode.ROOT -> _effectError.value = try {
                effect.apply(settings.effect(), force)
                null
            } catch (e: EffectException) {
                e.message ?: "unknown error"
            }
            // The engine reports its own errors per session, in its status.
            EngineMode.ROOTLESS -> {
                _effectError.value = null
                rootless.update(settings.effect())
            }
            null -> {}
        }
    }

    private suspend fun writeStateFile(settings: AudioSettings) {
        // Only what the installed effect understands (all of it until the effect was reached).
        val capabilities = effect.lastCapabilities ?: EqSettings.ALL_CAPABILITIES
        val result = modules.writeStateFile(settings.effect().toStateFile(capabilities), staging)
        _persistError.value = (result as? ModuleManager.StateWrite.Failed)?.message
    }

    companion object {
        const val PERSIST_DELAY_MS = 500L
    }
}
