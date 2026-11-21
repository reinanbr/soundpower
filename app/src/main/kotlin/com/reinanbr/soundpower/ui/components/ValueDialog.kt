// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.reinanbr.soundpower.R

/** What a long press opens: type an exact value for a knob or slider. */
data class ValueRequest(
    val title: String,
    val value: Float,
    val range: ClosedFloatingPointRange<Float>,
    val unit: String,
    val decimals: Int,
    val onConfirm: (Float) -> Unit,
)

/** Numbers in this dialog use the typographic minus, like the rest of the interface. */
private fun signed(v: Float, decimals: Int) = formatNumber(v, decimals).replace('-', '−')

/**
 * Asks for an exact value. The field opens focused with its text selected, so typing replaces
 * it; the keyboard's done key confirms.
 */
@Composable
fun ValueDialog(request: ValueRequest, onDismiss: () -> Unit) {
    var field by rememberSaveable(request.title, stateSaver = TextFieldValue.Saver) {
        val text = formatNumber(request.value, request.decimals)
        mutableStateOf(TextFieldValue(text, TextRange(0, text.length)))
    }
    // Both minus signs are accepted, and a comma or a point as the decimal separator.
    val parsed = parseNumber(field.text.replace('−', '-'))
    val valid = parsed != null && parsed in request.range
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    fun confirm() {
        if (!valid) return
        request.onConfirm(parsed)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(request.title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                modifier = Modifier.focusRequester(focus),
                singleLine = true,
                isError = !valid,
                suffix = { Text(request.unit) },
                supportingText = {
                    Text(
                        stringResource(
                            R.string.value_range,
                            signed(request.range.start, request.decimals),
                            signed(request.range.endInclusive, request.decimals),
                            request.unit,
                        ),
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
            )
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = ::confirm) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
