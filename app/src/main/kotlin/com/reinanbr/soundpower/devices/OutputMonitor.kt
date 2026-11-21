// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.devices

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.reinanbr.soundpower.model.AudioOutput
import com.reinanbr.soundpower.model.OutputKey
import com.reinanbr.soundpower.model.OutputKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The output music plays on. Android 13 and later say where media goes; before that, the output
 * is the one the default routing prefers among those connected (Bluetooth, then USB, then wired,
 * then the speaker). It is read again when a device comes or goes.
 */
class OutputMonitor(context: Context, private val scope: CoroutineScope) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val _output = MutableStateFlow(read())
    val output: StateFlow<AudioOutput> = _output.asStateFlow()
    private var pending: Job? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    init {
        audio?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    /** Routing settles a moment after a device comes or goes: read now, and again shortly after. */
    private fun refresh() {
        pending?.cancel()
        pending = scope.launch {
            for (wait in SETTLE_MS) {
                delay(wait)
                _output.value = read()
            }
        }
    }

    private fun read(): AudioOutput {
        val am = audio ?: return SPEAKER
        val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                am.getAudioDevicesForAttributes(MEDIA)
            } catch (_: RuntimeException) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val candidates = routed.ifEmpty { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
        val best = candidates.map(::outputOf).filter { it.key.kind != OutputKind.OTHER || routed.isNotEmpty() }
            .minByOrNull { RANK.indexOf(it.key.kind) }
        return best ?: SPEAKER
    }

    companion object {
        private val MEDIA = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        private val SPEAKER = AudioOutput(OutputKey(OutputKind.SPEAKER), null)
        private val SETTLE_MS = listOf(0L, 300L, 1200L)
        private val RANK = listOf(OutputKind.BLUETOOTH, OutputKind.USB, OutputKind.WIRED, OutputKind.SPEAKER, OutputKind.OTHER)

        /** Comparing [AudioDeviceInfo.getType] with constants newer than the device is harmless: they are plain ints. */
        @SuppressLint("InlinedApi")
        fun kindOf(type: Int): OutputKind = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputKind.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG -> OutputKind.WIRED
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID,
            -> OutputKind.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.USB
            else -> OutputKind.OTHER
        }

        /** Bluetooth devices by address, USB devices by name; the speaker and wired outputs are one each. */
        private fun outputOf(d: AudioDeviceInfo): AudioOutput {
            val kind = kindOf(d.type)
            val name = d.productName?.toString()?.trim()?.ifEmpty { null }
            val address = when (kind) {
                OutputKind.BLUETOOTH -> d.address.ifEmpty { null } ?: name
                OutputKind.USB -> name
                else -> null
            }
            return AudioOutput(OutputKey(kind, address), if (kind == OutputKind.SPEAKER) null else name)
        }
    }
}
