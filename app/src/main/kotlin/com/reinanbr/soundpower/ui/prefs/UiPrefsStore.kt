// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.reinanbr.soundpower.engine.EngineMode
import com.reinanbr.soundpower.engine.Precision
import com.reinanbr.soundpower.engine.RootlessOptions
import com.reinanbr.soundpower.engine.RootlessScope
import com.reinanbr.soundpower.players.KnownPlayers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Built-in skins. A documented skin format comes later (docs/roadmap.md, phase 9). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Preferences of the app itself, not the audio settings: one `key=value` per line. */
class UiPrefsStore(file: File, scope: CoroutineScope) {
    private val store: DataStore<Map<String, String>> = DataStoreFactory.create(
        serializer = LinesSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { emptyMap() },
        scope = scope,
        produceFile = { file },
    )

    val theme: Flow<ThemeMode> = store.data.map { prefs ->
        ThemeMode.entries.firstOrNull { it.name == prefs[KEY_THEME] } ?: ThemeMode.SYSTEM
    }

    suspend fun setTheme(mode: ThemeMode) {
        store.updateData { it + (KEY_THEME to mode.name) }
    }

    /**
     * Whether the last setup check found everything in place. While a new check runs at launch,
     * the app opens straight on the equalizer instead of flashing the setup screen.
     */
    val setupDone: Flow<Boolean> = store.data.map { it[KEY_SETUP_DONE] == "1" }

    suspend fun setSetupDone(done: Boolean) {
        store.updateData { if ((it[KEY_SETUP_DONE] == "1") == done) it else it + (KEY_SETUP_DONE to if (done) "1" else "0") }
    }

    /**
     * How the app makes the sound; null until the user chose. Installs from before there was a
     * choice were set up with root.
     */
    val engine: Flow<EngineMode?> = store.data.map { prefs ->
        EngineMode.entries.firstOrNull { it.name == prefs[KEY_ENGINE] } ?: if (prefs[KEY_SETUP_DONE] == "1") EngineMode.ROOT else null
    }

    suspend fun setEngine(mode: EngineMode) {
        store.updateData { if (it[KEY_ENGINE] == mode.name) it else it + (KEY_ENGINE to mode.name) }
    }

    val rootless: Flow<RootlessOptions> = store.data.map { prefs ->
        RootlessOptions(
            scope = RootlessScope.entries.firstOrNull { it.name == prefs[KEY_ROOTLESS_SCOPE] } ?: RootlessScope.GLOBAL,
            precision = Precision.entries.firstOrNull { it.name == prefs[KEY_PRECISION] } ?: Precision.BALANCED,
        )
    }

    suspend fun setRootless(options: RootlessOptions) {
        store.updateData { it + (KEY_ROOTLESS_SCOPE to options.scope.name) + (KEY_PRECISION to options.precision.name) }
    }

    /**
     * Direct volume control (root mode): the effect applies the stream volume itself. An app-wide
     * choice, not part of the audio settings or presets; the effect reads it when it is loaded.
     */
    val directVolume: Flow<Boolean> = store.data.map { it[KEY_DIRECT_VOLUME] == "1" }.distinctUntilChanged()

    suspend fun setDirectVolume(on: Boolean) {
        store.updateData { it + (KEY_DIRECT_VOLUME to if (on) "1" else "0") }
    }

    /**
     * Without root: also find the players that do not announce their sessions, from AudioFlinger's
     * dump (needs the DUMP permission, granted from a computer).
     */
    val advancedTracking: Flow<Boolean> = store.data.map { it[KEY_ADVANCED_TRACKING] == "1" }.distinctUntilChanged()

    suspend fun setAdvancedTracking(on: Boolean) {
        store.updateData { it + (KEY_ADVANCED_TRACKING to if (on) "1" else "0") }
    }

    /**
     * With root: the app attaches SoundPower to players' sessions itself, for devices where the
     * system does not attach the module's effect to music.
     */
    val rootFallback: Flow<Boolean> = store.data.map { it[KEY_ROOT_FALLBACK] == "1" }.distinctUntilChanged()

    suspend fun setRootFallback(on: Boolean) {
        store.updateData { it + (KEY_ROOT_FALLBACK to if (on) "1" else "0") }
    }

    /** The players the app has seen, with the user's choice for each. */
    val knownPlayers: Flow<KnownPlayers> = store.data.map { KnownPlayers.decode(it[KEY_PLAYERS]) }.distinctUntilChanged()

    /** Applies [change] to the remembered players; a null result leaves them as they are. */
    suspend fun updateKnownPlayers(change: (KnownPlayers) -> KnownPlayers?) {
        store.updateData { prefs ->
            val next = change(KnownPlayers.decode(prefs[KEY_PLAYERS])) ?: return@updateData prefs
            prefs + (KEY_PLAYERS to next.encode())
        }
    }

    private object LinesSerializer : Serializer<Map<String, String>> {
        override val defaultValue: Map<String, String> = emptyMap()

        override suspend fun readFrom(input: InputStream): Map<String, String> =
            input.readBytes().decodeToString().lines().mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq > 0) line.substring(0, eq) to line.substring(eq + 1) else null
            }.toMap()

        override suspend fun writeTo(t: Map<String, String>, output: OutputStream) {
            output.write(t.entries.joinToString("\n") { "${it.key}=${it.value}" }.encodeToByteArray())
        }
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_SETUP_DONE = "setup_done"
        const val KEY_ENGINE = "engine"
        const val KEY_ROOTLESS_SCOPE = "rootless_scope"
        const val KEY_PRECISION = "rootless_precision"
        const val KEY_DIRECT_VOLUME = "direct_volume"
        const val KEY_ADVANCED_TRACKING = "advanced_tracking"
        const val KEY_ROOT_FALLBACK = "root_fallback"
        const val KEY_PLAYERS = "players"
    }
}
