// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reinanbr.soundpower.SoundPowerApplication
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Brings the equalizer of the mode without root back after a reboot or an app update, if it was
 * on. Both broadcasts allow starting a foreground service from the background.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as SoundPowerApplication).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (container.uiPrefs.engine.first() == EngineMode.ROOTLESS) {
                    container.eq.start()
                    if (container.eq.settings.filterNotNull().first().enabled) RootlessService.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
