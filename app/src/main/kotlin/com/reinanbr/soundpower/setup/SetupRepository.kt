// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.setup

import com.reinanbr.soundpower.effect.EffectController
import com.reinanbr.soundpower.effect.EffectController.Connection
import com.reinanbr.soundpower.root.EffectDump
import com.reinanbr.soundpower.root.MagiskInfo
import com.reinanbr.soundpower.root.ModuleManager
import com.reinanbr.soundpower.root.ModuleProp
import com.reinanbr.soundpower.root.ModuleState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Where the device is on the way to a working effect. */
enum class SetupStep { CHECKING, NO_ROOT, NO_MAGISK, INSTALL, UPDATE, REBOOT, DISABLED, NOT_LOADED, INCOMPATIBLE, READY }

/** What one check found; null fields were not reached. */
data class SetupReport(
    val root: Boolean? = null,
    val magisk: MagiskInfo? = null,
    val module: ModuleState? = null,
    /** `AudioEffect.queryEffects()` lists the SoundPower implementation. */
    val registered: Boolean = false,
    val connection: Connection? = null,
    val dumps: List<EffectDump>? = null,
) {
    fun step(bundled: ModuleProp): SetupStep {
        if (root == null) return SetupStep.CHECKING
        // Controlling a loaded effect needs no root; installing and persisting do.
        if (!root) return if (connection is Connection.Connected) SetupStep.READY else SetupStep.NO_ROOT
        if (magisk == null || !magisk.isSupported) return SetupStep.NO_MAGISK
        val m = module ?: return SetupStep.CHECKING
        val next = m.nextVersion ?: return SetupStep.INSTALL
        return when {
            next.versionCode < bundled.versionCode -> SetupStep.UPDATE
            m.rebootPending -> SetupStep.REBOOT
            m.disabled -> SetupStep.DISABLED
            connection is Connection.Connected -> SetupStep.READY
            connection is Connection.Incompatible -> SetupStep.INCOMPATIBLE
            else -> SetupStep.NOT_LOADED
        }
    }
}

class SetupRepository(
    private val modules: ModuleManager,
    private val effect: EffectController,
    private val isRegistered: () -> Boolean,
    private val moduleZip: suspend () -> File,
    /** The module bundled in the APK. */
    val bundled: ModuleProp,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun check(): SetupReport {
        val root = modules.requestRoot()
        val registered = withContext(io) { runCatching(isRegistered).getOrDefault(false) }
        val connection = if (registered) effect.connect() else null
        if (!root) return SetupReport(root = false, registered = registered, connection = connection)
        val magisk = modules.magisk()
        val module = modules.module()
        // The control instance shows up in dumpsys too: proof that the HAL runs the library.
        val dumps = if (connection is Connection.Connected) modules.effectDumps() else null
        return SetupReport(true, magisk, module, registered, connection, dumps)
    }

    suspend fun install(onLine: (String) -> Unit): Boolean = modules.install(moduleZip(), onLine)

    suspend fun enable(): Boolean = modules.enable()

    suspend fun reboot() = modules.reboot()
}
