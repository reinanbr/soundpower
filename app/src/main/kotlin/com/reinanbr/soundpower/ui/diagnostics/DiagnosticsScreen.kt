// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.diagnostics

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.diagnostics.Diagnosis
import com.reinanbr.soundpower.diagnostics.DiagnosticsInput
import com.reinanbr.soundpower.diagnostics.Finding
import com.reinanbr.soundpower.diagnostics.Severity
import com.reinanbr.soundpower.diagnostics.Stream
import com.reinanbr.soundpower.diagnostics.Unprocessed
import com.reinanbr.soundpower.diagnostics.Verdict
import com.reinanbr.soundpower.effect.EffectController
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.root.ModuleManager
import com.reinanbr.soundpower.rootless.RootlessEngine
import com.reinanbr.soundpower.setup.SetupStep
import com.reinanbr.soundpower.ui.components.Section
import com.reinanbr.soundpower.ui.components.SpIcons
import com.reinanbr.soundpower.ui.components.formatDb
import com.reinanbr.soundpower.ui.components.formatNumber
import com.reinanbr.soundpower.ui.components.valueSpring
import com.reinanbr.soundpower.ui.presets.PresetFiles
import kotlinx.coroutines.launch

/** What the screen can ask of the app around it. */
class DiagnosticsActions(val onBack: () -> Unit, val onOpenSetup: () -> Unit)

/**
 * The diagnostics: a verdict, what to check (with the action that fixes it where there is one),
 * the players SoundPower processes live, the effect and the system, general tips, and a report
 * to share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    actions: DiagnosticsActions,
    modifier: Modifier = Modifier,
    vm: DiagnosticsViewModel = viewModel(factory = DiagnosticsViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var toSave by remember { mutableStateOf<ReportFile?>(null) }
    val saved = stringResource(R.string.diag_saved)
    val saveFailed = stringResource(R.string.diag_save_failed)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(TEXT)) { uri ->
        val report = toSave
        toSave = null
        if (uri != null && report != null) {
            scope.launch { snackbar.showSnackbar(if (PresetFiles.write(context, uri, report.text)) saved else saveFailed) }
        }
    }
    val shareTitle = stringResource(R.string.diag_share_title)
    val noShareApp = stringResource(R.string.diag_no_share_app)
    val copied = stringResource(R.string.diag_copied)
    val clipLabel = stringResource(R.string.diag_clip_label)

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = { IconButton(onClick = actions.onBack) { Icon(SpIcons.Back, stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = vm::recheck, enabled = !state.checking) { Icon(SpIcons.Refresh, stringResource(R.string.diag_recheck)) }
                    IconButton(
                        onClick = {
                            scope.launch {
                                val r = vm.report() ?: return@launch
                                if (!PresetFiles.share(context, r.name, r.text, shareTitle, TEXT)) snackbar.showSnackbar(noShareApp)
                            }
                        },
                        enabled = state.input != null,
                    ) { Icon(SpIcons.Share, stringResource(R.string.diag_share)) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = state.input != null) { Icon(SpIcons.More, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diag_copy)) },
                                leadingIcon = { Icon(SpIcons.Copy, null) },
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        val r = vm.report() ?: return@launch
                                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(clipLabel, r.text))
                                        // Android 13 and later confirm a copy themselves.
                                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) snackbar.showSnackbar(copied)
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diag_save)) },
                                leadingIcon = { Icon(SpIcons.Save, null) },
                                onClick = {
                                    menu = false
                                    scope.launch {
                                        val r = vm.report() ?: return@launch
                                        toSave = r
                                        save.launch(r.name)
                                    }
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val names = Names(state.labels, stringResource(R.string.diag_unknown_app))
            VerdictCard(state)
            Findings(state, names, vm, actions)
            val input = state.input
            if (input?.engine == EngineMode.ROOT) {
                if (input.flinger != null) Players(state.diagnosis, names)
                EffectSection(input)
                input.system?.let { SystemSection(input) }
            }
            if (input?.engine == EngineMode.ROOTLESS) RootlessSection(input, names)
            Tips(input?.engine)
        }
    }
}

/** App names for package names, where the system knows them. */
private class Names(private val labels: Map<String, String>, private val unknown: String) {
    operator fun invoke(name: String?): String = name?.let { labels[it] ?: it } ?: unknown
}

@Composable
private fun VerdictCard(state: DiagnosticsUi) {
    val scheme = MaterialTheme.colorScheme
    val verdict = state.diagnosis.verdict
    val (container, content) = when (verdict) {
        Verdict.Broken -> scheme.errorContainer to scheme.onErrorContainer
        is Verdict.Working -> scheme.primaryContainer to scheme.onPrimaryContainer
        Verdict.Off -> scheme.surfaceContainerHighest to scheme.onSurface
        else -> scheme.secondaryContainer to scheme.onSecondaryContainer
    }
    val bg by animateColorAsState(container, valueSpring(), label = "verdict-container")
    val fg by animateColorAsState(content, valueSpring(), label = "verdict-content")
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AnimatedContent(verdict, transitionSpec = { fadeIn().togetherWith(fadeOut()) }, label = "verdict") { v ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (v) {
                        Verdict.Checking -> CircularProgressIndicator(Modifier.size(28.dp), color = fg, strokeWidth = 3.dp)
                        Verdict.Broken -> Icon(SpIcons.Alert, null, Modifier.size(28.dp))
                        is Verdict.Working -> Icon(SpIcons.Check, null, Modifier.size(28.dp))
                        else -> Icon(SpIcons.Info, null, Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when (v) {
                            Verdict.Checking -> stringResource(R.string.diag_verdict_checking)
                            Verdict.Broken -> stringResource(R.string.diag_verdict_broken)
                            Verdict.Off -> stringResource(R.string.diag_verdict_off)
                            Verdict.Idle -> stringResource(R.string.diag_verdict_idle)
                            is Verdict.Working -> if (v.streams == 0) {
                                stringResource(R.string.diag_verdict_working_global)
                            } else {
                                pluralStringResource(R.plurals.diag_verdict_working, v.streams, v.streams)
                            }
                        },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            val hint = when (verdict) {
                Verdict.Broken -> stringResource(R.string.diag_verdict_broken_hint)
                Verdict.Off -> stringResource(R.string.diag_verdict_off_hint)
                Verdict.Idle -> stringResource(R.string.diag_verdict_idle_hint)
                is Verdict.Working -> stringResource(R.string.diag_verdict_working_hint)
                Verdict.Checking -> null
            }
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 40.dp))
            if (state.checking && verdict != Verdict.Checking) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = fg, trackColor = fg.copy(alpha = 0.2f))
            }
        }
    }
}

@Composable
private fun Findings(state: DiagnosticsUi, names: Names, vm: DiagnosticsViewModel, actions: DiagnosticsActions) {
    val findings = state.diagnosis.findings
    if (state.diagnosis.verdict == Verdict.Checking) return
    Section(stringResource(R.string.diag_findings)) {
        if (findings.isEmpty()) {
            Text(stringResource(R.string.diag_findings_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        findings.forEachIndexed { i, f ->
            if (i > 0) Spacer(Modifier.size(4.dp))
            FindingItem(f, state, names, vm, actions)
        }
    }
}

@Composable
private fun FindingItem(f: Finding, state: DiagnosticsUi, names: Names, vm: DiagnosticsViewModel, actions: DiagnosticsActions) {
    val context = LocalContext.current
    val text = findingText(f, names)
    val scheme = MaterialTheme.colorScheme
    val (icon, tint, label) = when (f.severity) {
        Severity.ERROR -> Triple(SpIcons.Alert, scheme.error, stringResource(R.string.diag_severity_error))
        Severity.WARNING -> Triple(SpIcons.Alert, scheme.tertiary, stringResource(R.string.diag_severity_warning))
        Severity.INFO -> Triple(SpIcons.Info, scheme.onSurfaceVariant, stringResource(R.string.diag_severity_info))
    }
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
        Icon(icon, label, Modifier.padding(top = 2.dp).size(20.dp), tint = tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text.first, style = MaterialTheme.typography.titleSmall)
            Text(text.second, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            val action: Pair<String, () -> Unit>? = when (f) {
                is Finding.Setup -> stringResource(R.string.diag_open_setup) to actions.onOpenSetup
                Finding.EqOff -> stringResource(R.string.diag_turn_on) to vm::turnOnEqualizer
                is Finding.Clipping -> stringResource(R.string.diag_limiter_on) to vm::turnOnLimiter
                is Finding.VolumeRestart -> stringResource(R.string.diag_restart_audio) to vm::restartAudio
                Finding.RootlessIdle, is Finding.RootlessFailed, is Finding.RootlessTaken -> stringResource(R.string.action_retry) to vm::retry
                Finding.BatteryOptimized -> stringResource(R.string.diag_battery_settings) to { open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                is Finding.OtherEffects -> f.byApps.firstOrNull()?.let { app -> stringResource(R.string.diag_app_info, names(app)) to { openAppInfo(context, app) } }
                is Finding.VolumeTaken -> f.owner?.takeIf { f.stream.others.any { o -> o.owner == it && o.byApp } }?.let { app ->
                    stringResource(R.string.diag_app_info, names(app)) to { openAppInfo(context, app) }
                }
                else -> null
            }
            if (action != null) {
                val busy = f is Finding.VolumeRestart && state.restarting
                FilledTonalButton(onClick = action.second, enabled = !busy, modifier = Modifier.padding(top = 4.dp)) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(action.first)
                }
            }
            if (f is Finding.VolumeRestart) state.restartError?.let { RestartError(it) }
        }
    }
}

@Composable
private fun RestartError(e: ModuleManager.AudioRestart) {
    val text = when (e) {
        is ModuleManager.AudioRestart.Failed -> stringResource(R.string.direct_volume_failed, e.message)
        ModuleManager.AudioRestart.NotLoaded -> stringResource(R.string.direct_volume_not_loaded)
        is ModuleManager.AudioRestart.Restarted -> return
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** Title and explanation of [f]. */
@Composable
private fun findingText(f: Finding, names: Names): Pair<String, String> = when (f) {
    is Finding.Setup -> stringResource(R.string.diag_setup_title) to stringResource(
        when (f.step) {
            SetupStep.NO_ROOT -> R.string.diag_setup_no_root
            SetupStep.NO_MAGISK -> R.string.diag_setup_no_magisk
            SetupStep.INSTALL -> R.string.diag_setup_install
            SetupStep.UPDATE -> R.string.diag_setup_update
            SetupStep.REBOOT -> R.string.diag_setup_reboot
            SetupStep.DISABLED -> R.string.diag_setup_disabled
            SetupStep.INCOMPATIBLE -> R.string.diag_setup_incompatible
            else -> R.string.diag_setup_not_loaded
        },
    )
    Finding.NoRoot -> stringResource(R.string.diag_no_root_title) to stringResource(R.string.diag_no_root_body)
    is Finding.ConfigNotPatched -> stringResource(R.string.diag_config_title) to stringResource(R.string.diag_config_body, f.paths.joinToString())
    Finding.LibraryRejected -> stringResource(R.string.diag_rejected_title) to stringResource(R.string.diag_rejected_body)
    Finding.LibraryNotLoaded -> stringResource(R.string.diag_not_loaded_title) to stringResource(R.string.diag_not_loaded_body)
    Finding.PolicyMissing -> stringResource(R.string.diag_policy_title) to stringResource(R.string.diag_policy_body)
    is Finding.Denials -> stringResource(R.string.diag_denials_title) to pluralStringResource(R.plurals.diag_denials_body, f.lines.size, f.lines.size)
    Finding.EqOff -> stringResource(R.string.diag_eq_off_title) to stringResource(R.string.diag_eq_off_body)
    Finding.NothingPlaying -> stringResource(R.string.diag_nothing_title) to stringResource(R.string.diag_nothing_body)
    is Finding.NotProcessed -> {
        val u = f.item
        stringResource(R.string.diag_unprocessed_title, names(u.player)) to when (u.reason) {
            Unprocessed.Reason.FAST -> stringResource(R.string.diag_unprocessed_fast)
            Unprocessed.Reason.OUTPUT -> stringResource(R.string.diag_unprocessed_output, u.thread.type.lowercase())
            Unprocessed.Reason.NOT_ATTACHED -> stringResource(R.string.diag_unprocessed_not_attached)
        }
    }
    is Finding.OtherEffects -> {
        val effects = f.effects.joinToString { it.effect.name.ifEmpty { "#${it.effect.id}" } }
        stringResource(R.string.diag_others_title, streamName(f.stream, names)) to if (f.byApps.isEmpty()) {
            stringResource(R.string.diag_others_system, effects)
        } else {
            stringResource(R.string.diag_others_apps, f.byApps.joinToString { names(it) }, effects)
        }
    }
    is Finding.VolumeTaken -> {
        val byApp = f.stream.others.firstOrNull { it.effect.id == f.controller.id }?.byApp == true
        stringResource(R.string.diag_volume_taken_title, streamName(f.stream, names)) to if (byApp && f.owner != null) {
            stringResource(R.string.diag_volume_taken_app, f.controller.name, names(f.owner))
        } else {
            stringResource(R.string.diag_volume_taken_body, f.controller.name)
        }
    }
    is Finding.VolumeRestart -> stringResource(R.string.diag_volume_restart_title) to
        stringResource(if (f.wanted) R.string.diag_volume_restart_on else R.string.diag_volume_restart_off)
    is Finding.Clipping -> stringResource(R.string.diag_clipping_title, streamName(f.stream, names)) to
        stringResource(R.string.diag_clipping_body, formatDb(f.peakDb))
    is Finding.HighCpu -> stringResource(R.string.diag_cpu_title, streamName(f.stream, names)) to
        stringResource(R.string.diag_cpu_body, formatNumber(f.averagePercent, 1), formatNumber(f.peakPercent, 0))
    is Finding.InvalidSamples -> stringResource(R.string.diag_invalid_title, streamName(f.stream, names)) to stringResource(R.string.diag_invalid_body)
    Finding.RootlessIdle -> stringResource(R.string.diag_rootless_idle_title) to stringResource(R.string.diag_rootless_idle_body)
    Finding.RootlessWaiting -> stringResource(R.string.diag_rootless_waiting_title) to stringResource(R.string.diag_rootless_waiting_body)
    is Finding.RootlessFailed -> stringResource(R.string.diag_rootless_failed_title, attachmentName(f.label, f.session, names)) to
        stringResource(R.string.diag_rootless_failed_body, f.error)
    is Finding.RootlessTaken -> stringResource(R.string.diag_rootless_taken_title, attachmentName(f.label, f.session, names)) to
        stringResource(R.string.diag_rootless_taken_body)
    Finding.BothEngines -> stringResource(R.string.diag_both_title) to stringResource(R.string.rootless_status_module_active)
    Finding.BatteryOptimized -> stringResource(R.string.diag_battery_title) to stringResource(R.string.diag_battery_body)
}

@Composable
private fun streamName(s: Stream, names: Names): String =
    s.players.firstOrNull()?.let { names(it) } ?: stringResource(R.string.diag_session, s.session)

@Composable
private fun attachmentName(label: String?, session: Int, names: Names): String = when {
    session == RootlessEngine.GLOBAL_SESSION -> stringResource(R.string.rootless_status_global)
    label != null -> names(label)
    else -> stringResource(R.string.diag_session, session)
}

@Composable
private fun Players(diagnosis: Diagnosis, names: Names) {
    Section(stringResource(R.string.diag_streams)) {
        if (diagnosis.streams.isEmpty()) {
            Text(stringResource(R.string.diag_streams_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        diagnosis.streams.forEachIndexed { i, s ->
            if (i > 0) Spacer(Modifier.size(8.dp))
            StreamCard(s, names)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamCard(s: Stream, names: Names) {
    val scheme = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (s.playing) scheme.primary else scheme.outline))
                Spacer(Modifier.width(10.dp))
                Text(streamName(s, names), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(if (s.playing) R.string.diag_stream_playing else R.string.diag_stream_idle),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (s.playing) scheme.primary else scheme.onSurfaceVariant,
                )
            }
            val d = s.effect.dump
            val rate = d?.sampleRate?.takeIf { it > 0 } ?: s.thread.sampleRate
            Text(
                stringResource(
                    R.string.diag_stream_format,
                    rate?.let { khz(it) } ?: "?",
                    channels(d?.channels),
                    formatName(d?.format),
                ) + (outputName(s.thread.devices)?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            if (d != null) {
                Metric(stringResource(R.string.diag_stream_cpu), stringResource(R.string.diag_stream_cpu_value, formatNumber(d.cpuAvgPercent, 1), formatNumber(d.cpuPeakPercent, 0)))
                d.volume?.let { v ->
                    val silence = stringResource(R.string.diag_stream_silence)
                    fun peak(db: Float) = if (db <= -900f) silence else formatDb(db)
                    Metric(stringResource(R.string.diag_stream_peaks), stringResource(R.string.diag_stream_peaks_value, peak(v.inputPeakDb), peak(v.outputPeakDb)))
                    val controller = s.chain.volumeController
                    Metric(
                        stringResource(R.string.diag_stream_volume),
                        when {
                            v.applied -> stringResource(R.string.diag_stream_volume_applied, formatDb(maxOf(v.leftDb, v.rightDb)))
                            controller != null && controller.id != s.effect.id -> stringResource(R.string.diag_stream_volume_other, controller.name)
                            else -> stringResource(R.string.diag_stream_volume_mixer)
                        },
                    )
                }
                if (d.limiterDb < -0.05f) Metric(stringResource(R.string.diag_stream_limiter), "${formatDb(d.limiterDb)} dB")
            }
            Text(stringResource(R.string.diag_stream_chain), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (e in s.chain.effects) {
                    val mine = e.id == s.effect.id
                    val other = s.others.firstOrNull { it.effect.id == e.id }
                    EffectChip(
                        name = if (mine) stringResource(R.string.app_name) else e.name.ifEmpty { "#${e.id}" },
                        owner = other?.takeIf { it.byApp }?.owner?.let { names(it) },
                        enabled = e.enabled,
                        highlight = mine,
                        volume = s.chain.volumeController?.id == e.id,
                    )
                }
            }
            val global = s.others.filter { it.global }
            if (global.isNotEmpty()) {
                Text(stringResource(R.string.diag_stream_output_chain), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (o in global) {
                        EffectChip(o.effect.name.ifEmpty { "#${o.effect.id}" }, o.takeIf { it.byApp }?.owner?.let { names(it) }, o.effect.enabled, highlight = false, volume = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun EffectChip(name: String, owner: String?, enabled: Boolean, highlight: Boolean, volume: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        highlight -> scheme.primary
        enabled -> scheme.secondaryContainer
        else -> scheme.surfaceContainerHighest
    }
    val fg = when {
        highlight -> scheme.onPrimary
        enabled -> scheme.onSecondaryContainer
        else -> scheme.onSurfaceVariant
    }
    val label = if (enabled) name else stringResource(R.string.diag_effect_off, name)
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = if (highlight) FontWeight.SemiBold else null)
            if (owner != null) Text(owner, style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.8f))
        }
        if (volume) {
            Spacer(Modifier.width(6.dp))
            Icon(SpIcons.Dial, stringResource(R.string.diag_stream_volume), Modifier.size(14.dp), tint = fg)
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(112.dp).padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EffectSection(input: DiagnosticsInput) {
    val setup = input.setup ?: return
    Section(stringResource(R.string.diag_effect)) {
        val connected = setup.connection as? EffectController.Connection.Connected
        val yes = stringResource(R.string.diag_yes)
        val no = stringResource(R.string.diag_no)
        val loaded = connected != null || input.flinger?.soundPowerInstances?.isNotEmpty() == true
        Metric(stringResource(R.string.diag_effect_loaded), if (loaded) yes else no)
        connected?.let {
            Metric(stringResource(R.string.diag_effect_library), stringResource(R.string.diag_effect_library_value, it.info.libVersionName, it.info.major, it.info.minor))
        }
        val bundled = input.bundled?.version.orEmpty()
        val installed = setup.module?.installed
        Metric(
            stringResource(R.string.diag_effect_module),
            if (installed == null) stringResource(R.string.diag_effect_module_none, bundled) else stringResource(R.string.diag_effect_module_value, installed.version, bundled),
        )
        input.status?.let { s ->
            Metric(stringResource(R.string.diag_effect_instances), s.instances.toString())
            val on = stringResource(R.string.diag_on)
            val off = stringResource(R.string.diag_off)
            Metric(
                stringResource(R.string.diag_effect_dvc),
                stringResource(R.string.diag_effect_dvc_value, if (input.directVolume) on else off, if (s.volumeControlClaimed) on else off),
            )
        }
    }
}

@Composable
private fun SystemSection(input: DiagnosticsInput) {
    val sys = input.system ?: return
    Section(stringResource(R.string.diag_system)) {
        Metric(stringResource(R.string.diag_system_device), "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        Metric(
            stringResource(R.string.diag_system_hal),
            sys.loadedIn.joinToString("\n") { p -> "${p.command.substringBefore(' ').substringAfterLast('/')} (${p.pid})" }
                .ifEmpty { stringResource(R.string.diag_system_hal_none) },
        )
        if (sys.configs.isNotEmpty()) {
            val patched = stringResource(R.string.diag_system_patched)
            val notPatched = stringResource(R.string.diag_system_not_patched)
            Metric(
                stringResource(R.string.diag_system_configs),
                sys.configs.entries.joinToString("\n") { (path, ok) -> "${path.substringAfterLast('/')}: ${if (ok) patched else notPatched}" },
            )
        }
        sys.policyLoaded?.let {
            Metric(stringResource(R.string.diag_system_policy), stringResource(if (it) R.string.diag_system_policy_loaded else R.string.diag_system_policy_missing))
        }
        Metric(stringResource(R.string.diag_system_denials), sys.denials.size.toString())
        sys.bootLog.lastOrNull { it.contains("watchdog") }?.let { Metric(stringResource(R.string.diag_system_boot), it.substringAfter("watchdog: ")) }
    }
}

@Composable
private fun RootlessSection(input: DiagnosticsInput, names: Names) {
    val status = input.rootless ?: return
    Section(stringResource(R.string.diag_rootless)) {
        status.layout?.let { l ->
            Metric(stringResource(R.string.diag_rootless_output), khz(l.sampleRate))
            Text(
                pluralStringResource(R.plurals.rootless_status_layout, l.bandCount, l.bandCount, kotlin.math.round(l.binHz).toInt(), kotlin.math.round(l.latencyMs).toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (a in status.attachments) {
            Metric(
                attachmentName(a.label, a.session, names),
                when {
                    a.active -> stringResource(R.string.rootless_status_active)
                    a.error == RootlessEngine.LOST_CONTROL -> stringResource(R.string.rootless_status_taken)
                    a.error != null -> stringResource(R.string.rootless_status_failed, a.error.orEmpty())
                    else -> stringResource(R.string.setup_checking)
                },
            )
        }
    }
}

@Composable
private fun Tip(text: String) {
    Row {
        Text("•", Modifier.width(16.dp), color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Tips(engine: EngineMode?) {
    Section(stringResource(R.string.diag_tips)) {
        if (engine == EngineMode.ROOTLESS) {
            Tip(stringResource(R.string.diag_tip_rootless_global))
            Tip(stringResource(R.string.diag_tip_rootless_other))
        } else {
            Tip(stringResource(R.string.diag_tip_restart_playback))
        }
        Tip(stringResource(R.string.diag_tip_other_apps))
        Tip(stringResource(R.string.diag_tip_player_options))
        Tip(stringResource(R.string.diag_tip_bluetooth))
        Tip(stringResource(R.string.diag_tip_report))
    }
}

/** 48000 as "48 kHz", 44100 as "44.1 kHz". */
@Composable
private fun khz(rate: Int): String = stringResource(R.string.diag_khz, formatNumber(rate / 1000f, if (rate % 1000 == 0) 0 else 1))

@Composable
private fun channels(n: Int?): String = when (n) {
    null, 0 -> "?"
    1 -> stringResource(R.string.diag_mono)
    2 -> stringResource(R.string.diag_stereo)
    else -> pluralStringResource(R.plurals.diag_channels, n, n)
}

/** `audio_format_t` values the mixer feeds effects. */
private fun formatName(format: Int?): String = when (format) {
    0x1 -> "16-bit"
    0x2 -> "8-bit"
    0x3 -> "32-bit"
    0x4 -> "8.24"
    0x5 -> "float"
    0x6 -> "24-bit"
    null -> "?"
    else -> "0x%x".format(format)
}

/** "0x8 (AUDIO_DEVICE_OUT_WIRED_HEADPHONE)" as the user knows it. */
@Composable
private fun outputName(devices: String?): String? {
    val d = devices ?: return null
    return when {
        "BLUETOOTH" in d || "BLE_" in d -> stringResource(R.string.output_bluetooth)
        "WIRED" in d || "LINE" in d -> stringResource(R.string.output_wired)
        "USB" in d -> stringResource(R.string.output_usb)
        "SPEAKER" in d -> stringResource(R.string.output_speaker)
        else -> stringResource(R.string.output_other)
    }
}

private fun openAppInfo(context: Context, name: String) {
    open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", name.substringBefore(':'), null)))
}

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

private const val TEXT = "text/plain"
