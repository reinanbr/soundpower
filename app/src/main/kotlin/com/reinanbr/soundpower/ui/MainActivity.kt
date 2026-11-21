// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reinanbr.soundpower.SoundPowerApplication
import com.reinanbr.soundpower.setup.SetupStep
import com.reinanbr.soundpower.ui.eq.EqRoute
import com.reinanbr.soundpower.ui.prefs.ThemeMode
import com.reinanbr.soundpower.ui.setup.SetupScreen
import com.reinanbr.soundpower.ui.setup.SetupViewModel
import com.reinanbr.soundpower.ui.theme.SoundPowerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = (application as SoundPowerApplication).container.uiPrefs
        setContent {
            val theme by prefs.theme.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            SoundPowerTheme(theme) {
                SoundPowerRoot()
            }
        }
    }
}

/** Setup until everything is in place, then the equalizer; setup stays reachable from there. */
/** What the root shows: nothing for the few milliseconds before the preferences load. */
private enum class RootScreen { NONE, SETUP, EQ }

@Composable
private fun SoundPowerRoot(setup: SetupViewModel = viewModel(factory = SetupViewModel.Factory)) {
    val state by setup.state.collectAsStateWithLifecycle()
    var setupOpened by rememberSaveable { mutableStateOf(false) }
    val ready = state.step == SetupStep.READY
    val prefs = (LocalContext.current.applicationContext as SoundPowerApplication).container.uiPrefs
    // Null until the preferences are read; then whether the previous check was all clear.
    val setupDone by prefs.setupDone.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(state.step) {
        if (state.step != SetupStep.CHECKING) prefs.setSetupDone(ready)
    }

    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val screen = when {
        setupOpened -> RootScreen.SETUP
        ready -> RootScreen.EQ
        state.step == SetupStep.CHECKING -> when (setupDone) {
            null -> RootScreen.NONE
            true -> RootScreen.EQ
            false -> RootScreen.SETUP
        }
        else -> RootScreen.SETUP
    }
    AnimatedContent(
        targetState = screen,
        transitionSpec = { fadeIn(effects).togetherWith(fadeOut(effects)) },
        label = "root",
    ) { shown ->
        if (shown == RootScreen.NONE) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        } else if (shown == RootScreen.EQ) {
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
    }
    BackHandler(enabled = ready && setupOpened) { setupOpened = false }
}
