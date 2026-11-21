// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.protocol.ProtocolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The settings as the app keeps them. The file holds exactly the bytes of state.bin, so what
 * the app stores and what the effect loads at boot cannot drift apart.
 */
class SettingsStore(file: File, scope: CoroutineScope) {
    private val store: DataStore<EqSettings> = DataStoreFactory.create(
        serializer = StateFileSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { EqSettings.DEFAULT },
        scope = scope,
        produceFile = { file },
    )

    val settings: Flow<EqSettings> = store.data

    suspend fun save(settings: EqSettings) {
        store.updateData { settings }
    }

    internal object StateFileSerializer : Serializer<EqSettings> {
        override val defaultValue: EqSettings = EqSettings.DEFAULT

        override suspend fun readFrom(input: InputStream): EqSettings = try {
            EqSettings.fromStateFile(input.readBytes())
        } catch (e: ProtocolException) {
            throw CorruptionException("not a SoundPower state file", e)
        }

        override suspend fun writeTo(t: EqSettings, output: OutputStream) {
            output.write(t.toStateFile())
        }
    }
}
