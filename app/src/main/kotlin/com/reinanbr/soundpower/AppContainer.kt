// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower

import android.content.Context
import com.reinanbr.soundpower.data.SettingsStore
import com.reinanbr.soundpower.effect.AudioEffectTransport
import com.reinanbr.soundpower.effect.EffectController
import com.reinanbr.soundpower.eq.EqRepository
import com.reinanbr.soundpower.root.LibsuRootShell
import com.reinanbr.soundpower.root.ModuleManager
import com.reinanbr.soundpower.root.ModuleProp
import com.reinanbr.soundpower.setup.SetupRepository
import com.reinanbr.soundpower.ui.prefs.UiPrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

/** The app's object graph, built by hand: it is small enough not to need a DI framework. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val modules = ModuleManager(LibsuRootShell())
    private val effect = EffectController(open = { AudioEffectTransport.open(app) })

    val eq = EqRepository(
        store = SettingsStore(
            file = File(app.filesDir, "audio_settings.json"),
            scope = ioScope,
            legacyFile = File(app.filesDir, "eq_settings.bin"),
        ),
        effect = effect,
        modules = modules,
        staging = File(app.cacheDir, "state.bin"),
        scope = scope,
    )

    val uiPrefs = UiPrefsStore(File(app.filesDir, "ui_prefs.json"), ioScope)

    val setup = SetupRepository(
        modules = modules,
        effect = effect,
        isRegistered = AudioEffectTransport::isRegistered,
        moduleZip = ::copyModuleZip,
        bundled = ModuleProp(BuildConfig.MODULE_VERSION, BuildConfig.MODULE_VERSION_CODE),
    )

    /** The module zip from the APK's assets, where root can read it. */
    private suspend fun copyModuleZip(): File = withContext(Dispatchers.IO) {
        val out = File(app.cacheDir, MODULE_ASSET)
        app.assets.open(MODULE_ASSET).use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }

    private companion object {
        /** Put there by the bundleMagiskModule task (app/build.gradle.kts). */
        const val MODULE_ASSET = "soundpower-module.zip"
    }
}
