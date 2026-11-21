// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.presets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reinanbr.soundpower.R
import com.reinanbr.soundpower.presets.AutoEqSuggestion
import com.reinanbr.soundpower.presets.CurrentPreset
import com.reinanbr.soundpower.ui.components.SpIcons

/** The equalizer's title: the current preset, marked when changed since; a tap opens the list. */
@Composable
internal fun PresetTitle(current: CurrentPreset?, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = stringResource(R.string.presets_title), onClick = onClick)
            .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(current?.preset?.name ?: stringResource(R.string.preset_none), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (current?.edited == true) {
                Text(stringResource(R.string.preset_edited), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(SpIcons.ExpandMore, null, Modifier.padding(start = 4.dp))
    }
}

/** AutoEQ has profiles for the headphones just connected. */
@Composable
internal fun SuggestionBanner(s: AutoEqSuggestion, onShow: () -> Unit, onDismiss: () -> Unit) {
    val name = s.output.name.orEmpty()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(SpIcons.Headphones, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.suggestion_title, name), style = MaterialTheme.typography.titleSmall)
                Text(
                    pluralStringResource(R.plurals.suggestion_text, s.entries.size, s.entries.size),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
            TextButton(onClick = onShow) { Text(stringResource(R.string.suggestion_show)) }
        }
    }
}
