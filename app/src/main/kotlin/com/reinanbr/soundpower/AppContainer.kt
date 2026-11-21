// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower

import android.content.Context
import android.media.AudioManager
import com.reinanbr.soundpower.data.PresetStore
import com.reinanbr.soundpower.data.SettingsStore
import com.reinanbr.soundpower.devices.OutputMonitor
import com.reinanbr.soundpower.effect.AudioEffectTransport
import com.reinanbr.soundpower.effect.EffectController
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.engine.EqService
import com.reinanbr.soundpower.eq.EqRepository
import com.reinanbr.soundpower.model.AutoEq
import com.reinanbr.soundpower.model.AutoEqIndex
import com.reinanbr.soundpower.presets.PresetRepository
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** The equalizer without root, attached while [EqService] runs. */
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

    /** The output music plays on. */
    val output = OutputMonitor(app, appScope)

    val presets = PresetRepository(
        store = PresetStore(File(app.filesDir, "presets.json"), ioScope),
        eq = eq,
        output = output.output,
        loadAutoEq = ::loadAutoEq,
        scope = scope,
    )

    /** An activity of the app is on screen. */
    val visible = MutableStateFlow(false)

    /** A preset search to open, from a notification; the equalizer screen takes it. */
    val presetSearch = MutableStateFlow<String?>(null)

    /**
     * Whether [EqService] should run: without root while the equalizer is on (it holds Android's
     * effect), and in either mode while presets switch by output.
     */
    val serviceWanted: Flow<Boolean> = combine(
        uiPrefs.engine,
        eq.settings.filterNotNull().map { it.enabled },
        presets.library.filterNotNull(),
    ) { mode, on, lib -> (mode == EngineMode.ROOTLESS && on) || (lib.switchByOutput && lib.assignments.isNotEmpty()) }
        .distinctUntilChanged()

    init {
        presets.start()
        // Start the service whenever it should run (it stops itself when it should not).
        appScope.launch {
            serviceWanted.collect { if (it) EqService.start(app) }
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

    private suspend fun loadAutoEq(): AutoEqIndex = withContext(Dispatchers.IO) {
        app.assets.open(AUTOEQ_ASSET).bufferedReader().useLines { AutoEqIndex(AutoEq.readDatabase(it)) }
    }

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

        /** Written by tools/autoeq/build_db.py. */
        const val AUTOEQ_ASSET = "autoeq.tsv"
    }
}
