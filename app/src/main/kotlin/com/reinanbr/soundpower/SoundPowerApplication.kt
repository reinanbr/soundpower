// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower

import android.app.Application
import com.reinanbr.soundpower.root.LibsuRootShell

class SoundPowerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        LibsuRootShell.configure()
        container = AppContainer(this)
    }
}
