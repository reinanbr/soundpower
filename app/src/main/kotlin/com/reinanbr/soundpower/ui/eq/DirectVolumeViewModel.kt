// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.eq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.reinanbr.soundpower.SoundPowerApplication
import com.reinanbr.soundpower.engine.MediaVolume
import com.reinanbr.soundpower.eq.EqRepository
import com.reinanbr.soundpower.root.ModuleManager
import com.reinanbr.soundpower.ui.prefs.UiPrefsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DirectVolumeUi(
    /** The preference; null until it is read. */
    val enabled: Boolean? = null,
    val restarting: Boolean = false,
    /** How the last restart went wrong, if it did. */
    val restartError: ModuleManager.AudioRestart? = null,
    /** The media volume step, and its range. */
    val volume: Int? = null,
    val volumeSteps: IntRange = 0..15,
)

/**
 * Direct volume control (root mode): the preference, the audio restart that applies it, and the
 * media volume knob.
 */
class DirectVolumeViewModel(
    private val eq: EqRepository,
    private val prefs: UiPrefsStore,
    private val media: MediaVolume,
) : ViewModel() {
    private val restart = MutableStateFlow(DirectVolumeUi())

    val state: StateFlow<DirectVolumeUi> = combine(prefs.directVolume, restart, media.level) { on, r, level ->
        r.copy(enabled = on, volume = level, volumeSteps = media.steps)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DirectVolumeUi())

    fun setEnabled(on: Boolean) {
        viewModelScope.launch { prefs.setDirectVolume(on) }
    }

    /** [step] comes from a knob: rounded to the nearest system step. */
    fun setVolume(step: Float) {
        val s = kotlin.math.round(step).toInt()
        if (s != state.value.volume) media.set(s)
    }

    fun restartAudio() {
        if (restart.value.restarting) return
        restart.value = DirectVolumeUi(restarting = true)
        viewModelScope.launch {
            val r = eq.restartAudio()
            restart.value = DirectVolumeUi(restartError = r.takeIf { it !is ModuleManager.AudioRestart.Restarted })
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as SoundPowerApplication
                DirectVolumeViewModel(app.container.eq, app.container.uiPrefs, MediaVolume(app))
            }
        }
    }
}
