// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.eq

import com.reinanbr.soundpower.data.SettingsStore
import com.reinanbr.soundpower.effect.EffectController
import com.reinanbr.soundpower.effect.EffectException
import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.protocol.EffectStatus
import com.reinanbr.soundpower.root.EffectDump
import com.reinanbr.soundpower.root.ModuleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** What the status card shows. */
data class EqStatus(
    /** STATUS of the control instance (null if unreachable). */
    val effect: EffectStatus?,
    /** The other instances, from dumpsys (null without root). */
    val streams: List<EffectDump>?,
)

/**
 * The live EQ settings. Every change goes to the effect at once (the latest value wins when
 * changes come faster than binder calls); once changes settle, they are saved in the app and
 * in the module's state.bin, which the effect loads at boot.
 */
class EqRepository(
    private val store: SettingsStore,
    private val effect: EffectController,
    private val modules: ModuleManager,
    /** A file the app writes and root copies to state.bin. */
    private val staging: File,
    private val scope: CoroutineScope,
) {
    private val _settings = MutableStateFlow<EqSettings?>(null)
    val settings: StateFlow<EqSettings?> = _settings.asStateFlow()

    private val _effectError = MutableStateFlow<String?>(null)
    val effectError: StateFlow<String?> = _effectError.asStateFlow()

    private val _persistError = MutableStateFlow<String?>(null)
    val persistError: StateFlow<String?> = _persistError.asStateFlow()

    private val started = AtomicBoolean(false)

    /** Loads the saved settings and starts syncing them; later calls do nothing. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val initial = store.settings.first()
            var saved = initial
            _settings.value = initial
            launch { _settings.filterNotNull().collect(::push) }
            launch {
                _settings.filterNotNull().debounce(PERSIST_DELAY_MS).collect {
                    if (it != saved) {
                        store.save(it)
                        saved = it
                        writeStateFile(it)
                    }
                }
            }
            // state.bin may be missing (fresh install) or older than the app's copy.
            writeStateFile(initial)
        }
    }

    fun update(transform: (EqSettings) -> EqSettings) {
        _settings.update { it?.let(transform) }
    }

    /** Sends the current settings again, e.g. after the effect was unreachable. */
    fun retry() {
        scope.launch { _settings.value?.let { push(it) } }
    }

    suspend fun readStatus(): EqStatus {
        val status = try {
            effect.status()
        } catch (_: EffectException) {
            null
        }
        val streams = modules.effectDumps()?.filter { status == null || it.sessionId != status.sessionId }
        return EqStatus(status, streams)
    }

    private suspend fun push(settings: EqSettings) {
        _effectError.value = try {
            effect.apply(settings)
            null
        } catch (e: EffectException) {
            e.message ?: "unknown error"
        }
    }

    private suspend fun writeStateFile(settings: EqSettings) {
        val result = modules.writeStateFile(settings.toStateFile(), staging)
        _persistError.value = (result as? ModuleManager.StateWrite.Failed)?.message
    }

    companion object {
        const val PERSIST_DELAY_MS = 500L
    }
}
