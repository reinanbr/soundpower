// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.eq

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.SoundPowerApplication
import com.reinanbr.soundpower.ui.diagnostics.DiagnosticsActions
import com.reinanbr.soundpower.ui.diagnostics.DiagnosticsScreen
import com.reinanbr.soundpower.ui.presets.MessageEffect
import com.reinanbr.soundpower.ui.presets.PresetsScreen
import com.reinanbr.soundpower.ui.presets.PresetsUiState
import com.reinanbr.soundpower.ui.presets.PresetsViewModel
import com.reinanbr.soundpower.ui.presets.SavePresetDialog
import com.reinanbr.soundpower.ui.presets.outputOptions
import com.reinanbr.soundpower.ui.components.SpIcons
import com.reinanbr.soundpower.ui.components.ValueDialog
import com.reinanbr.soundpower.ui.components.ValueRequest
import com.reinanbr.soundpower.ui.components.valueSpring
import com.reinanbr.soundpower.ui.prefs.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The three pages of the main screen, in the order of the navigation bar. */
enum class EqTab(val label: Int, val icon: ImageVector) {
    EQUALIZER(R.string.tab_equalizer, SpIcons.Faders),
    EFFECTS(R.string.tab_effects, SpIcons.Dial),
    MENU(R.string.tab_menu, SpIcons.Menu),
}

/** Screens opened on top of the pages. */
enum class EqTool { BANDS, PRESETS, DIAGNOSTICS }

/** Everything the pages can ask of the shell. */
internal class EqActions(
    val requestValue: (ValueRequest) -> Unit,
    val reset: () -> Unit,
    val openTool: (EqTool) -> Unit,
    val openSetup: () -> Unit,
    val changeEngine: () -> Unit,
    /** The preset list, with a search if given. */
    val openPresets: (String?) -> Unit,
    val savePreset: () -> Unit,
    val showMessage: (String) -> Unit,
)

/** The equalizer, its effects and menu; [onOpenSetup] and [onChangeEngine] leave for the setup screens. */
@Composable
fun EqRoute(onOpenSetup: () -> Unit, onChangeEngine: () -> Unit, vm: EqViewModel = viewModel(factory = EqViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val presetsVm: PresetsViewModel = viewModel(factory = PresetsViewModel.Factory)
    val presets by presetsVm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(EqTab.EQUALIZER) }
    var tool by rememberSaveable { mutableStateOf<EqTool?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var request by remember { mutableStateOf<ValueRequest?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // How far a predictive back gesture has gone (0..1): the tool screen shrinks away with it.
    val back = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = tool != null) { events ->
        try {
            events.collect { back.snapTo(it.progress) }
            tool = null
        } catch (e: CancellationException) {
            scope.launch { back.animateTo(0f, valueSpring()) }
            throw e
        }
    }
    // A search sent by an AutoEQ suggestion's notification.
    val container = (LocalContext.current.applicationContext as SoundPowerApplication).container
    val search by container.presetSearch.collectAsStateWithLifecycle()
    LaunchedEffect(search) {
        val q = search ?: return@LaunchedEffect
        container.presetSearch.value = null
        presetsVm.search(q)
        back.snapTo(0f)
        tool = EqTool.PRESETS
    }
    // Back from another page returns to the equalizer before leaving the app.
    BackHandler(enabled = tool == null && tab != EqTab.EQUALIZER) { tab = EqTab.EQUALIZER }

    val resetDone = stringResource(R.string.eq_reset_done)
    val undo = stringResource(R.string.action_undo)
    val actions = EqActions(
        requestValue = { request = it },
        reset = {
            vm.resetEq()
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(resetDone, actionLabel = undo, duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) vm.undoReset()
            }
        },
        openTool = {
            scope.launch { back.snapTo(0f) }
            tool = it
        },
        openSetup = onOpenSetup,
        changeEngine = onChangeEngine,
        openPresets = { q ->
            if (q != null) presetsVm.search(q)
            scope.launch { back.snapTo(0f) }
            tool = EqTool.PRESETS
        },
        savePreset = { saving = true },
        showMessage = { m -> scope.launch { snackbar.showSnackbar(m) } },
    )

    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = tool,
        transitionSpec = {
            // Tools slide in from the side they are reached from, and back out the same way.
            val dir = if (targetState != null) 1 else -1
            (slideInHorizontally(spatial) { it / 3 * dir } + fadeIn(effects))
                .togetherWith(slideOutHorizontally(spatial) { -it / 3 * dir } + fadeOut(effects))
        },
        label = "eq-screens",
    ) { t ->
        when (t) {
            null -> Pages(tab, { tab = it }, state, theme, vm, snackbar, actions, presets, presetsVm)
            EqTool.DIAGNOSTICS -> DiagnosticsScreen(
                DiagnosticsActions(onBack = { tool = null }, onOpenSetup = onOpenSetup),
                modifier = Modifier.graphicsLayer {
                    val p = back.value
                    scaleX = 1f - 0.1f * p
                    scaleY = 1f - 0.1f * p
                    translationX = p * 24.dp.toPx()
                    shape = RoundedCornerShape(28.dp * p)
                    clip = p > 0f
                },
            )
            EqTool.PRESETS -> PresetsScreen(
                presetsVm,
                onBack = { tool = null },
                modifier = Modifier.graphicsLayer {
                    val p = back.value
                    scaleX = 1f - 0.1f * p
                    scaleY = 1f - 0.1f * p
                    translationX = p * 24.dp.toPx()
                    shape = RoundedCornerShape(28.dp * p)
                    clip = p > 0f
                },
            )
            else -> EqToolScreen(
                t, state, vm,
                onBack = { tool = null },
                onRequestValue = actions.requestValue,
                modifier = Modifier.graphicsLayer {
                    val p = back.value
                    scaleX = 1f - 0.1f * p
                    scaleY = 1f - 0.1f * p
                    translationX = p * 24.dp.toPx()
                    shape = RoundedCornerShape(28.dp * p)
                    clip = p > 0f
                },
            )
        }
    }
    request?.let { ValueDialog(it, onDismiss = { request = null }) }
    if (saving) {
        val current = presets.current?.preset
        val lib = presets.library
        val defaultName = when {
            current == null -> stringResource(R.string.preset_default_name)
            lib == null -> current.name
            else -> lib.uniqueName(current.name)
        }
        SavePresetDialog(
            initialName = defaultName,
            options = outputOptions(presets.output, emptyList()),
            onDismiss = { saving = false },
        ) { name, outputs ->
            saving = false
            presetsVm.save(name, outputs)
        }
    }
}

@Composable
private fun Pages(
    tab: EqTab,
    onTab: (EqTab) -> Unit,
    state: EqUiState,
    theme: ThemeMode,
    vm: EqViewModel,
    snackbar: SnackbarHostState,
    actions: EqActions,
    presets: PresetsUiState,
    presetsVm: PresetsViewModel,
) {
    MessageEffect(presetsVm, snackbar)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                for (t in EqTab.entries) {
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { onTab(t) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
        val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                // Pages slide towards the side of the tab that was picked.
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(spatial) { it / 4 * dir } + fadeIn(effects))
                    .togetherWith(slideOutHorizontally(spatial) { -it / 4 * dir } + fadeOut(effects))
            },
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            label = "eq-pages",
        ) { page ->
            when (page) {
                EqTab.EQUALIZER -> EqPage(state, vm, actions, presets, presetsVm)
                EqTab.EFFECTS -> EffectsPage(state, vm, actions)
                EqTab.MENU -> MenuPage(state, theme, vm, actions, presets, presetsVm)
            }
        }
    }
}
