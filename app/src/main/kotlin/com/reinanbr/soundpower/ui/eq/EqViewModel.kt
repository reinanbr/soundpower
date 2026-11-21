// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.eq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.reinanbr.soundpower.SoundPowerApplication
import com.reinanbr.soundpower.eq.EqRepository
import com.reinanbr.soundpower.eq.EqStatus
import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.model.GraphicEq
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

data class EqUiState(
    val settings: EqSettings? = null,
    val effectError: String? = null,
    val persistError: String? = null,
    /** Null until the first poll returns. */
    val status: EqStatus? = null,
)

class EqViewModel(private val eq: EqRepository) : ViewModel() {
    init {
        eq.start()
    }

    private val status = flow<EqStatus?> {
        emit(null)
        while (true) {
            emit(eq.readStatus())
            delay(STATUS_INTERVAL_MS)
        }
    }

    val state: StateFlow<EqUiState> =
        combine(eq.settings, eq.effectError, eq.persistError, status, ::EqUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EqUiState())

    fun setEnabled(enabled: Boolean) = eq.update { it.copy(bypass = !enabled) }

    fun setPreamp(db: Float) = eq.update { it.copy(preampDb = GraphicEq.snapPreamp(db)) }

    fun setAutoPreamp(on: Boolean) = eq.update { it.copy(autoPreamp = on) }

    fun setBandGain(index: Int, db: Float) = eq.update { it.copy(bands = GraphicEq.withGain(it.bands, index, db)) }

    /** Flat 10-band curve, preamp 0 dB, default limiter; keeps the on/off switch. */
    fun reset() = eq.update { EqSettings.DEFAULT.copy(bypass = it.bypass) }

    fun retry() = eq.retry()

    companion object {
        const val STATUS_INTERVAL_MS = 3_000L

        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as SoundPowerApplication
                EqViewModel(app.container.eq)
            }
        }
    }
}
