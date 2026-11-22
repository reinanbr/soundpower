// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.players

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.engine.RootlessScope
import com.reinanbr.soundpower.players.Player
import com.reinanbr.soundpower.players.Tracking
import com.reinanbr.soundpower.ui.components.Section
import com.reinanbr.soundpower.ui.components.SpIcons
import com.reinanbr.soundpower.ui.components.SwitchRow
import com.reinanbr.soundpower.ui.eq.RadioRow
import kotlinx.coroutines.launch

/**
 * The players: how SoundPower finds them in the current mode, those with a session now and those
 * seen before, with a switch for each where the engine attaches per player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayersScreen(onBack: () -> Unit, modifier: Modifier = Modifier, vm: PlayersViewModel = viewModel(factory = PlayersViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    // The permission may have been granted from a computer while the app was in the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.recheckPermission() } }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.players_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(SpIcons.Back, stringResource(R.string.action_back)) } },
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
            if (!state.loaded) return@Column
            HowSection(state, vm, snackbar)
            val now = state.players.filter { it.present }
            Section(stringResource(R.string.players_now)) {
                if (now.isEmpty()) {
                    Text(stringResource(R.string.players_now_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                now.forEach { PlayerRow(it, state, vm) }
                if (state.tracking == Tracking.ANNOUNCED || state.tracking == Tracking.NEEDS_PERMISSION) {
                    Note(stringResource(R.string.players_now_announced_only))
                }
            }
            val seen = state.players.filterNot { it.present }
            if (seen.isNotEmpty()) {
                Section(stringResource(R.string.players_seen)) {
                    seen.forEach { PlayerRow(it, state, vm) }
                }
            }
            if (!state.perPlayer) {
                Note(stringResource(if (state.mode == EngineMode.ROOT) R.string.players_root_note else R.string.players_global_note))
            }
            when (state.directVolume) {
                DirectVolumeChoice.AVAILABLE -> Note(stringResource(R.string.players_direct_volume_note))
                DirectVolumeChoice.NEEDS_UPDATE -> Note(stringResource(R.string.players_direct_volume_update))
                DirectVolumeChoice.NONE -> {}
            }
        }
    }
}

@Composable
private fun HowSection(state: PlayersUi, vm: PlayersViewModel, snackbar: SnackbarHostState) {
    Section(stringResource(R.string.players_how)) {
        when (state.mode) {
            EngineMode.ROOT -> {
                Text(stringResource(R.string.players_how_root), color = MaterialTheme.colorScheme.onSurfaceVariant)
                SwitchRow(
                    stringResource(R.string.players_root_fallback),
                    state.rootFallback,
                    vm::setRootFallback,
                    hint = stringResource(R.string.players_root_fallback_hint),
                )
            }
            EngineMode.ROOTLESS -> {
                RadioRow(stringResource(R.string.rootless_scope_global), stringResource(R.string.rootless_scope_global_hint), state.rootless.scope == RootlessScope.GLOBAL) {
                    vm.setScope(RootlessScope.GLOBAL)
                }
                RadioRow(stringResource(R.string.rootless_scope_sessions), stringResource(R.string.rootless_scope_sessions_hint), state.rootless.scope == RootlessScope.SESSIONS) {
                    vm.setScope(RootlessScope.SESSIONS)
                }
                SwitchRow(
                    stringResource(R.string.players_advanced),
                    state.advanced,
                    vm::setAdvanced,
                    hint = stringResource(R.string.players_advanced_hint),
                )
                when (state.tracking) {
                    Tracking.NEEDS_PERMISSION -> PermissionCard(state.grantCommand, vm, snackbar)
                    Tracking.PERMISSION -> Note(stringResource(R.string.players_permission_granted))
                    else -> {}
                }
            }
            null -> {}
        }
    }
}

@Composable
private fun PermissionCard(command: String, vm: PlayersViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.players_permission_copied)
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.players_permission_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.players_permission_body), style = MaterialTheme.typography.bodyMedium)
            Text(
                command,
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(command, command))
                        // Android 13 and later confirm a copy themselves.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
                    },
                ) {
                    Icon(SpIcons.Copy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.players_permission_copy))
                }
                OutlinedButton(onClick = vm::recheckPermission) { Text(stringResource(R.string.players_permission_check)) }
            }
        }
    }
}

@Composable
private fun PlayerRow(p: Player, state: PlayersUi, vm: PlayersViewModel) {
    val name = state.labels[p.packageName] ?: p.packageName
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val active = p.playing == true && p.equalized != false
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (active) scheme.primary else scheme.outlineVariant))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                status(p, state),
                style = MaterialTheme.typography.bodySmall,
                color = if (p.playing == true && p.equalized == false) scheme.error else scheme.onSurfaceVariant,
            )
            if (state.directVolume == DirectVolumeChoice.AVAILABLE) {
                val description = stringResource(R.string.players_direct_volume_description, name)
                FilterChip(
                    selected = p.directVolume,
                    onClick = { vm.setDirectVolume(p.packageName, !p.directVolume) },
                    label = { Text(stringResource(R.string.players_direct_volume)) },
                    leadingIcon = if (p.directVolume) {
                        { Icon(SpIcons.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                    modifier = Modifier.semantics { contentDescription = description },
                )
            }
        }
        if (!p.present) {
            IconButton(onClick = { vm.forget(p.packageName) }) {
                Icon(SpIcons.Close, stringResource(R.string.players_forget, name), Modifier.size(20.dp))
            }
        }
        if (state.perPlayer) {
            val label = stringResource(R.string.players_switch, name)
            Switch(
                checked = p.enabled,
                onCheckedChange = { vm.setEnabled(p.packageName, it) },
                modifier = Modifier.semantics { contentDescription = label },
            )
        }
    }
}

@Composable
private fun status(p: Player, state: PlayersUi): String = when {
    // Root mode: turning a player off removes the app's instance, not the module's.
    state.perPlayer && !p.enabled && p.present && p.equalized == true && state.mode == EngineMode.ROOT ->
        stringResource(R.string.players_state_off_module)
    state.perPlayer && !p.enabled -> stringResource(R.string.players_state_off)
    !p.present -> p.lastSeen?.takeIf { it > 0 }?.let { lastSeen(System.currentTimeMillis() - it) } ?: ""
    p.playing == true && p.equalized == true -> stringResource(R.string.players_state_playing_equalized)
    p.playing == true && p.equalized == false -> stringResource(R.string.players_state_playing_not_equalized)
    p.playing == true -> stringResource(R.string.players_state_playing)
    p.playing == false -> stringResource(R.string.players_state_paused)
    p.equalized == true -> stringResource(R.string.players_state_equalized)
    else -> stringResource(R.string.players_state_open)
}

/** In the app's language: the system's relative times follow the device's. */
@Composable
private fun lastSeen(ago: Long): String {
    val minutes = (ago / MINUTE_MS).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.players_last_seen_now)
        minutes < 60 -> pluralStringResource(R.plurals.players_last_seen_minutes, minutes, minutes)
        minutes < 24 * 60 -> pluralStringResource(R.plurals.players_last_seen_hours, minutes / 60, minutes / 60)
        else -> pluralStringResource(R.plurals.players_last_seen_days, minutes / (24 * 60), minutes / (24 * 60))
    }
}

private const val MINUTE_MS = 60_000L

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The equalizer page's chip: which players SoundPower equalizes now. A tap opens the players.
 * Collecting [vm] keeps AudioFlinger's dump being read while the page is visible.
 */
@Composable
fun PlayersChip(onClick: () -> Unit, modifier: Modifier = Modifier, vm: PlayersViewModel = viewModel(factory = PlayersViewModel.Factory)) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.loaded) return
    fun name(p: Player) = state.labels[p.packageName] ?: p.packageName
    val eq = state.equalizedNow
    val missed = state.missedNow
    val text = when {
        eq.size == 1 -> name(eq[0])
        eq.size > 1 -> stringResource(R.string.players_chip_more, name(eq[0]), eq.size - 1)
        state.everything -> stringResource(R.string.players_chip_all)
        missed.isNotEmpty() -> stringResource(R.string.players_chip_not_equalized, name(missed[0]))
        else -> stringResource(R.string.players_chip_none)
    }
    val warn = eq.isEmpty() && !state.everything && missed.isNotEmpty()
    val description = stringResource(R.string.players_chip_description, text)
    AssistChip(
        onClick = onClick,
        label = { Text(text, maxLines = 1) },
        leadingIcon = { Icon(SpIcons.Wave, null, Modifier.size(AssistChipDefaults.IconSize)) },
        colors = if (warn) {
            AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.error, leadingIconContentColor = MaterialTheme.colorScheme.error)
        } else {
            AssistChipDefaults.assistChipColors()
        },
        modifier = modifier.semantics { contentDescription = description },
    )
}
