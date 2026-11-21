// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.reinanbr.soundpower.model.AudioSettings
import com.reinanbr.soundpower.model.EqSettings
import com.reinanbr.soundpower.protocol.ProtocolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The app's audio settings, as JSON. state.bin is derived from them (see
 * [AudioSettings.effect]) whenever they are saved.
 *
 * [legacyFile] is the store of the first app version (the bytes of a state.bin). If it exists,
 * its settings are imported once and the file is deleted.
 */
class SettingsStore(file: File, scope: CoroutineScope, legacyFile: File? = null) {
    private val store: DataStore<AudioSettings> = DataStoreFactory.create(
        serializer = JsonSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { AudioSettings.DEFAULT },
        migrations = listOfNotNull(legacyFile?.let(::LegacyMigration)),
        scope = scope,
        produceFile = { file },
    )

    val settings: Flow<AudioSettings> = store.data

    suspend fun save(settings: AudioSettings) {
        store.updateData { settings }
    }

    internal object JsonSerializer : Serializer<AudioSettings> {
        override val defaultValue: AudioSettings = AudioSettings.DEFAULT

        override suspend fun readFrom(input: InputStream): AudioSettings = try {
            AudioSettings.fromJson(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("not SoundPower settings", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("not SoundPower settings", e)
        }

        override suspend fun writeTo(t: AudioSettings, output: OutputStream) {
            output.write(t.toJson().encodeToByteArray())
        }
    }

    private class LegacyMigration(private val file: File) : DataMigration<AudioSettings> {
        override suspend fun shouldMigrate(currentData: AudioSettings): Boolean = file.exists()

        override suspend fun migrate(currentData: AudioSettings): AudioSettings = try {
            AudioSettings.fromLegacy(EqSettings.fromStateFile(file.readBytes()))
        } catch (_: ProtocolException) {
            currentData
        }

        override suspend fun cleanUp() {
            file.delete()
        }
    }
}
