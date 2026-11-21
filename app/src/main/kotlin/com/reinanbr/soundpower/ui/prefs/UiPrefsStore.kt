// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Built-in skins. A documented skin format comes later (docs/roadmap.md, phase 9). */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Preferences of the app's own UI (not audio): one `key=value` per line. */
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
    }
}
