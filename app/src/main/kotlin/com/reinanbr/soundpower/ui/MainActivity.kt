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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.reinanbr.soundpower.SoundPowerApplication
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.setup.SetupStep
import com.reinanbr.soundpower.ui.eq.EqRoute
import com.reinanbr.soundpower.ui.setup.EngineChoiceScreen
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

/** What the root shows: nothing for the few milliseconds before the preferences load. */
private enum class RootScreen { NONE, CHOICE, SETUP, EQ }

/** The engine preference once read; [mode] is null while the user has not chosen. */
private class EngineChoice(val mode: EngineMode?)

/**
 * The engine choice on first launch. With root: setup until everything is in place, then the
 * equalizer, with setup reachable from there. Without root: the equalizer straight away (nothing
 * to install, and root is never asked for).
 */
@Composable
private fun SoundPowerRoot(setup: SetupViewModel = viewModel(factory = SetupViewModel.Factory)) {
    val state by setup.state.collectAsStateWithLifecycle()
    var setupOpened by rememberSaveable { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    val ready = state.step == SetupStep.READY
    val prefs = (LocalContext.current.applicationContext as SoundPowerApplication).container.uiPrefs
    val scope = rememberCoroutineScope()
    val engineFlow = remember(prefs) { prefs.engine.map(::EngineChoice) }
    val engine by engineFlow.collectAsStateWithLifecycle(initialValue = null)
    val mode = engine?.mode
    // Null until the preferences are read; then whether the previous check was all clear.
    val setupDone by prefs.setupDone.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(mode) {
        // Keeps the mode that installs from before the choice get, and checks root only for root.
        if (mode != null) prefs.setEngine(mode)
        if (mode == EngineMode.ROOT) setup.check()
    }
    LaunchedEffect(state.step, mode) {
        if (mode == EngineMode.ROOT && state.step != SetupStep.CHECKING) prefs.setSetupDone(ready)
    }

    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val screen = when {
        engine == null -> RootScreen.NONE
        mode == null || choosing -> RootScreen.CHOICE
        mode == EngineMode.ROOTLESS -> RootScreen.EQ
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
        when (shown) {
            RootScreen.NONE -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            RootScreen.CHOICE -> EngineChoiceScreen(mode) { chosen ->
                scope.launch {
                    prefs.setEngine(chosen)
                    choosing = false
                    setupOpened = false
                }
            }
            RootScreen.EQ -> EqRoute(
                onOpenSetup = {
                    setupOpened = true
                    setup.check()
                },
                onChangeEngine = { choosing = true },
            )
            RootScreen.SETUP -> SetupScreen(
                state = state,
                onRetry = setup::check,
                onInstall = setup::install,
                onReboot = setup::reboot,
                onEnable = setup::enable,
                onOpenEq = { setupOpened = false },
                onChangeEngine = { choosing = true },
            )
        }
    }
    BackHandler(enabled = choosing && mode != null) { choosing = false }
    BackHandler(enabled = !choosing && ready && setupOpened) { setupOpened = false }
}
