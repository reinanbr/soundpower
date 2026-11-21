// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.reinanbr.soundpower.model.PresetLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The user's presets and their output assignments, as JSON. */
class PresetStore(file: File, scope: CoroutineScope) {
    private val store: DataStore<PresetLibrary> = DataStoreFactory.create(
        serializer = JsonSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { PresetLibrary.DEFAULT },
        scope = scope,
        produceFile = { file },
    )

    val library: Flow<PresetLibrary> = store.data

    /** Applies [transform] atomically: concurrent updates never lose each other's changes. */
    suspend fun update(transform: (PresetLibrary) -> PresetLibrary): PresetLibrary = store.updateData(transform)

    internal object JsonSerializer : Serializer<PresetLibrary> {
        override val defaultValue: PresetLibrary = PresetLibrary.DEFAULT

        override suspend fun readFrom(input: InputStream): PresetLibrary = try {
            PresetLibrary.fromJson(input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("not SoundPower presets", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("not SoundPower presets", e)
        }

        override suspend fun writeTo(t: PresetLibrary, output: OutputStream) {
            output.write(PresetLibrary.toJson(t).encodeToByteArray())
        }
    }
}
