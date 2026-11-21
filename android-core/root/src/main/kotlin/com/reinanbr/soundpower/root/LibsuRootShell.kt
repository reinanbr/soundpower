// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.root

import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/** [RootShell] on libsu's process-wide main shell. Call [configure] once, before any shell exists. */
class LibsuRootShell : RootShell {
    override suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        // libsu caches the first shell it builds. If root was denied then, that shell is a plain
        // one; drop it so that a grant made since then is picked up.
        Shell.getCachedShell()?.takeIf { !it.isRoot }?.close()
        runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    override suspend fun run(script: String, onLine: ((String) -> Unit)?): ShellResult = withContext(Dispatchers.IO) {
        val shell = runCatching { Shell.getShell() }.getOrNull()
        if (shell == null || !shell.isRoot) return@withContext ShellResult(ShellResult.NO_ROOT, emptyList(), listOf("no root shell"))
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val sink: MutableList<String> = if (onLine == null) out else LineCallback(out, onLine)
        val result = shell.newJob().add(script).to(sink, err).exec()
        ShellResult(result.code, out.toList(), err.toList())
    }

    /** Collects into [base] and hands every line to [onLine] on libsu's reader thread. */
    private class LineCallback(base: MutableList<String>, private val onLine: (String) -> Unit) :
        CallbackList<String>(DIRECT, base) {
        override fun onAddElement(e: String) = onLine(e)
    }

    companion object {
        private val DIRECT = Executor { it.run() }

        /** Sets libsu's shell options; it must run before the first shell is built. */
        fun configure() {
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    // Magisk mounts modules in the global mount namespace; see what the HAL sees.
                    .setFlags(Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(15),
            )
        }
    }
}
