// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reinanbr.soundpower.setup.SetupStep
import com.reinanbr.soundpower.ui.eq.EqRoute
import com.reinanbr.soundpower.ui.setup.SetupScreen
import com.reinanbr.soundpower.ui.setup.SetupViewModel
import com.reinanbr.soundpower.ui.theme.SoundPowerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SoundPowerTheme {
                SoundPowerRoot()
            }
        }
    }
}

/** Setup until everything is in place, then the equalizer; setup stays reachable from there. */
@Composable
private fun SoundPowerRoot(setup: SetupViewModel = viewModel(factory = SetupViewModel.Factory)) {
    val state by setup.state.collectAsStateWithLifecycle()
    var setupOpened by rememberSaveable { mutableStateOf(false) }
    val ready = state.step == SetupStep.READY

    if (ready && !setupOpened) {
        EqRoute(onOpenSetup = {
            setupOpened = true
            setup.check()
        })
    } else {
        SetupScreen(
            state = state,
            onRetry = setup::check,
            onInstall = setup::install,
            onReboot = setup::reboot,
            onEnable = setup::enable,
            onOpenEq = { setupOpened = false },
        )
    }
    BackHandler(enabled = ready && setupOpened) { setupOpened = false }
}
