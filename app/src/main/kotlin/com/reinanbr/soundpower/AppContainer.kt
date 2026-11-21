// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower

import android.content.Context
import android.media.AudioManager
import com.reinanbr.soundpower.data.SettingsStore
import com.reinanbr.soundpower.effect.AudioEffectTransport
import com.reinanbr.soundpower.effect.EffectController
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.engine.RootlessService
import com.reinanbr.soundpower.eq.EqRepository
import com.reinanbr.soundpower.root.LibsuRootShell
import com.reinanbr.soundpower.root.ModuleManager
import com.reinanbr.soundpower.root.ModuleProp
import com.reinanbr.soundpower.rootless.AndroidDynamicsEffect
import com.reinanbr.soundpower.rootless.RootlessEngine
import com.reinanbr.soundpower.setup.SetupRepository
import com.reinanbr.soundpower.ui.prefs.UiPrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The app's object graph, built by hand: it is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    /** Work that outlives screens and the service. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val scope = appScope
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val modules = ModuleManager(LibsuRootShell())
    private val effect = EffectController(open = { AudioEffectTransport.open(app) })

    val uiPrefs = UiPrefsStore(File(app.filesDir, "ui_prefs.json"), ioScope)

    /** The equalizer without root, attached while [RootlessService] runs. */
    val rootless = RootlessEngine(AndroidDynamicsEffect.FACTORY, ::outputSampleRate)

    val eq = EqRepository(
        store = SettingsStore(
            file = File(app.filesDir, "audio_settings.json"),
            scope = ioScope,
            legacyFile = File(app.filesDir, "eq_settings.bin"),
        ),
        effect = effect,
        rootless = rootless,
        modules = modules,
        engine = uiPrefs.engine,
        isModuleActive = AudioEffectTransport::isRegistered,
        staging = File(app.cacheDir, "state.bin"),
        scope = scope,
    )

    init {
        // Without root the equalizer lives in a foreground service: start it whenever it should
        // run (the service stops itself when it should not).
        appScope.launch {
            combine(uiPrefs.engine, eq.settings.filterNotNull().map { it.enabled }) { mode, on -> mode == EngineMode.ROOTLESS && on }
                .distinctUntilChanged()
                .collect { if (it) RootlessService.start(app) }
        }
    }

    val setup = SetupRepository(
        modules = modules,
        effect = effect,
        isRegistered = AudioEffectTransport::isRegistered,
        moduleZip = ::copyModuleZip,
        bundled = ModuleProp(BuildConfig.MODULE_VERSION, BuildConfig.MODULE_VERSION_CODE),
    )

    /** The rate of the primary output, which the mixer (and so the global effect) runs at. */
    private fun outputSampleRate(): Int =
        app.getSystemService(AudioManager::class.java)?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE

    /** The module zip from the APK's assets, where root can read it. */
    private suspend fun copyModuleZip(): File = withContext(Dispatchers.IO) {
        val out = File(app.cacheDir, MODULE_ASSET)
        app.assets.open(MODULE_ASSET).use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }

    private companion object {
        /** Put there by the bundleMagiskModule task (app/build.gradle.kts). */
        const val MODULE_ASSET = "soundpower-module.zip"
        const val DEFAULT_SAMPLE_RATE = 48000
    }
}
