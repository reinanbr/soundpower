// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reinanbr.soundpower.SoundPowerApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Starts [EqService] after a reboot or an app update if it should run (the equalizer without root,
 * or presets that switch by output). Both broadcasts allow starting a foreground service from the
 * background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as SoundPowerApplication).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (container.serviceWanted.first()) EqService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
