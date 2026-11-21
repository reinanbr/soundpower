// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.ui.presets

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Preset and settings files: sharing, and reading and writing what the system file picker returns. */
internal object PresetFiles {
    /** Larger files are not presets or settings. */
    private const val MAX_BYTES = 2 * 1024 * 1024

    const val JSON = "application/json"

    /** What the import picker offers: JSON, and the text files AutoEQ writes. */
    val IMPORT_TYPES = arrayOf(JSON, "text/*", "application/octet-stream")

    /** "Rock" -> "Rock.json", without characters file systems refuse. */
    fun fileName(name: String): String = name.replace(Regex("""[^\p{L}\p{N} ._()-]"""), "_").trim().take(80).ifEmpty { "preset" } + ".json"

    /** Offers [text] as a file named [fileName] to other apps; false if none can take it. */
    suspend fun share(context: Context, fileName: String, text: String, title: String): Boolean {
        val uri = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            // Earlier shares: the receiving apps have read them by now.
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, fileName)
            f.writeText(text)
            FileProvider.getUriForFile(context, context.packageName + ".files", f)
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(JSON)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(send, title))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** The text of [uri] and its display name; null if it cannot be read or is too large. */
    suspend fun read(context: Context, uri: Uri): Pair<String, String?>? = withContext(Dispatchers.IO) {
        try {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return@withContext null
                }
                out.toByteArray()
            } ?: return@withContext null
            bytes.decodeToString() to name
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** False if [uri] cannot be written. */
    suspend fun write(context: Context, uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) } != null
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
