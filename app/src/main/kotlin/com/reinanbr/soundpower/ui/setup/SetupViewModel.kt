// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.reinanbr.soundpower.SoundPowerApplication
import com.reinanbr.soundpower.root.ModuleProp
import com.reinanbr.soundpower.setup.SetupReport
import com.reinanbr.soundpower.setup.SetupRepository
import com.reinanbr.soundpower.setup.SetupStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetupUiState(
    val bundled: ModuleProp,
    val report: SetupReport = SetupReport(),
    val busy: Boolean = false,
    val installLog: List<String> = emptyList(),
    /** Null until an install ran in this session. */
    val installSucceeded: Boolean? = null,
) {
    val step: SetupStep get() = report.step(bundled)
}

class SetupViewModel(private val setup: SetupRepository) : ViewModel() {
    private val _state = MutableStateFlow(SetupUiState(bundled = setup.bundled))
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        check()
    }

    fun check() = work { recheck() }

    fun install() = work {
        _state.update { it.copy(installLog = emptyList(), installSucceeded = null) }
        val ok = setup.install { line -> _state.update { s -> s.copy(installLog = s.installLog + line) } }
        _state.update { it.copy(installSucceeded = ok) }
        recheck()
    }

    fun enable() = work {
        setup.enable()
        recheck()
    }

    fun reboot() = work { setup.reboot() }

    private suspend fun recheck() {
        val report = setup.check()
        _state.update { it.copy(report = report) }
    }

    /** Runs [block] unless something else is running; the screen shows progress meanwhile. */
    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) as SoundPowerApplication
                SetupViewModel(app.container.setup)
            }
        }
    }
}
