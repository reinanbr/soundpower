// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.diagnostics

import com.reinanbr.soundpower.effect.EffectController
import com.reinanbr.soundpower.effect.EffectException
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.eq.EqRepository
import com.reinanbr.soundpower.root.ModuleManager
import com.reinanbr.soundpower.root.SystemCheck
import com.reinanbr.soundpower.rootless.RootlessEngine
import com.reinanbr.soundpower.setup.SetupReport
import com.reinanbr.soundpower.setup.SetupRepository
import com.reinanbr.soundpower.ui.prefs.UiPrefsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Reads what the diagnostics look at. [checks] are slow (they ask for root, read the logs) and
 * run when the screen opens or on request; [read] is quick enough to repeat every few seconds.
 */
class DiagnosticsRepository(
    private val setup: SetupRepository,
    private val modules: ModuleManager,
    private val effect: EffectController,
    private val eq: EqRepository,
    private val prefs: UiPrefsStore,
    private val rootless: RootlessEngine,
    /** SoundPower's own effect is registered (public API). */
    private val isModuleRegistered: () -> Boolean,
    /** Android may stop the app to save battery; null if unknown. */
    private val isBatteryOptimized: () -> Boolean?,
    /** The app's own log, newest last. */
    private val appLog: () -> List<String>,
    private val ownPid: Int,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The slow part: root, Magisk, the module, the connection and the system (root mode). */
    data class Checks(val setup: SetupReport? = null, val system: SystemCheck? = null)

    suspend fun checks(): Checks {
        if (prefs.engine.first() != EngineMode.ROOT) return Checks()
        val report = setup.check()
        val system = if (report.root == true) modules.systemCheck() else null
        return Checks(report, system)
    }

    /** Everything, with [checks] from an earlier call. */
    suspend fun read(checks: Checks): DiagnosticsInput {
        val engine = prefs.engine.first()
        val base = DiagnosticsInput(
            engine = engine,
            settings = eq.settings.value,
            directVolume = prefs.directVolume.first(),
            bundled = setup.bundled,
            ownPid = ownPid,
        )
        return when (engine) {
            EngineMode.ROOT -> base.copy(
                setup = checks.setup,
                status = try {
                    effect.status()
                } catch (_: EffectException) {
                    null
                },
                flinger = if (checks.setup?.root == true) modules.audioFlinger() else null,
                system = checks.system,
            )
            EngineMode.ROOTLESS -> base.copy(
                rootless = rootless.status.value,
                rootlessScope = prefs.rootless.first().scope,
                moduleRegistered = withContext(io) { runCatching(isModuleRegistered).getOrDefault(false) },
                batteryOptimized = isBatteryOptimized(),
            )
            null -> base
        }
    }

    /** The module's diag.sh output (root) and the app's log, for a report. */
    suspend fun extras(engine: EngineMode?): Pair<List<String>?, List<String>> = withContext(io) {
        val script = if (engine == EngineMode.ROOT) modules.diagnosticsScript() else null
        script to runCatching(appLog).getOrDefault(emptyList())
    }

    /** Restarts the audio HAL so that the library reloads (direct volume control). */
    suspend fun restartAudio(): ModuleManager.AudioRestart = eq.restartAudio()

    fun turnOnLimiter() = eq.update { it.copy(limiter = it.limiter.copy(enabled = true)) }

    fun turnOnEqualizer() = eq.update { it.copy(enabled = true) }

    fun retry() = eq.retry()
}
