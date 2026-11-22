// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.players

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.IOException
import java.lang.reflect.Method
import kotlin.concurrent.thread

/**
 * Reads a system service's dump without root, as `dumpsys` does: the app asks the service to
 * write it into a pipe. AudioFlinger only answers apps that hold `android.permission.DUMP`, which
 * the user grants once from a computer (`adb shell pm grant <package> android.permission.DUMP`).
 */
object ServiceDump {
    const val PERMISSION = "android.permission.DUMP"
    const val AUDIO_FLINGER = "media.audio_flinger"

    fun granted(context: Context): Boolean = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** The adb command that grants the permission to [packageName]. */
    fun grantCommand(packageName: String): String = "adb shell pm grant $packageName $PERMISSION"

    /**
     * The lines [service] prints, or null when it does not exist, refuses (no permission) or
     * takes longer than [timeoutMs]. Blocks: call it off the main thread.
     */
    fun read(service: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<String>? {
        val binder = binder(service) ?: return null
        val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
        // Filled by the reader thread; read here only after join(), which orders the two.
        val lines = ArrayList<String>()
        val reader = thread(name = "dump-$service", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(readEnd).bufferedReader().useLines { seq -> seq.forEach { lines += it } }
            } catch (_: IOException) {
                // Closed on timeout.
            }
        }
        try {
            // One-way: the service writes on its own threads and closes its copy when done.
            binder.dumpAsync(writeEnd.fileDescriptor, emptyArray())
        } catch (_: Exception) {
            writeEnd.close()
            readEnd.close()
            return null
        }
        writeEnd.close()
        reader.join(timeoutMs)
        if (reader.isAlive) {
            readEnd.close()
            return null
        }
        // "Permission Denial: can't dump AudioFlinger from pid=..., uid=..."
        if (lines.firstOrNull()?.startsWith("Permission Denial") == true) return null
        return lines
    }

    /** `ServiceManager.checkService` is hidden; the binder it returns is public API. */
    private fun binder(service: String): IBinder? = try {
        checkService.invoke(null, service) as IBinder?
    } catch (_: ReflectiveOperationException) {
        null
    }

    private val checkService by lazy { lookUpCheckService() }

    // No public API returns a system service's binder by name; HiddenApiBypass exempts the class,
    // and a missing method only turns advanced tracking off (read() returns null).
    @SuppressLint("PrivateApi")
    private fun lookUpCheckService(): Method {
        HiddenApiBypass.addHiddenApiExemptions("Landroid/os/ServiceManager;")
        return Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java)
    }

    private const val DEFAULT_TIMEOUT_MS = 3_000L
}
