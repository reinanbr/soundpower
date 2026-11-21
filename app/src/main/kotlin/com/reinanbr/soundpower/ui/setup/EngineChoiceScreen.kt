// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.setup

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.ui.components.SpIcons
import com.reinanbr.soundpower.ui.components.valueSpring

/** The first screen: how the app makes the sound. The menu can change it later. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineChoiceScreen(current: EngineMode?, onChoose: (EngineMode) -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(current ?: EngineMode.ROOT) }
    val context = LocalContext.current
    // Without root the equalizer runs with a notification (and its off button), which Android 13
    // and later hide until the app may post notifications. Either answer continues.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChoose(EngineMode.ROOTLESS) }
    fun choose() {
        val ask = chosen == EngineMode.ROOTLESS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (ask) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else onChoose(chosen)
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.engine_choice_title)) }) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.engine_choice_intro), style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EngineCard(
                    SpIcons.Shield, stringResource(R.string.engine_root), stringResource(R.string.engine_root_summary),
                    listOf(R.string.engine_root_point_features, R.string.engine_root_point_boot, R.string.engine_root_point_needs),
                    selected = chosen == EngineMode.ROOT,
                ) { chosen = EngineMode.ROOT }
                EngineCard(
                    SpIcons.Wave, stringResource(R.string.engine_rootless), stringResource(R.string.engine_rootless_summary),
                    listOf(R.string.engine_rootless_point_any, R.string.engine_rootless_point_features, R.string.engine_rootless_point_limits),
                    selected = chosen == EngineMode.ROOTLESS,
                ) { chosen = EngineMode.ROOTLESS }
            }
            Text(
                stringResource(R.string.engine_choice_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = ::choose, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_continue))
            }
        }
    }
}

@Composable
private fun EngineCard(icon: ImageVector, title: String, summary: String, points: List<Int>, selected: Boolean, onSelect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val border by animateColorAsState(if (selected) scheme.primary else scheme.outlineVariant, valueSpring(), label = "engine-border")
    val container by animateColorAsState(
        if (selected) scheme.secondaryContainer else scheme.surfaceContainer, valueSpring(), label = "engine-container",
    )
    Card(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(28.dp), tint = if (selected) scheme.primary else scheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                for (p in points) {
                    Text("• " + stringResource(p), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}
