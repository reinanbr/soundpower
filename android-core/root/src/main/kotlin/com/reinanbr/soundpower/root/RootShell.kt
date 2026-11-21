// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.root

/** A root shell. [LibsuRootShell] is the real one; tests use fakes. */
interface RootShell {
    /** Asks for root; the first call makes the root manager show its prompt. */
    suspend fun requestRoot(): Boolean

    /**
     * Runs [script] in the root shell. Each stdout line also goes to [onLine] as it arrives.
     * The script runs in a long-lived shell: it must not `exit` outside a subshell.
     */
    suspend fun run(script: String, onLine: ((String) -> Unit)? = null): ShellResult
}

data class ShellResult(val code: Int, val out: List<String>, val err: List<String> = emptyList()) {
    val isSuccess: Boolean get() = code == 0

    companion object {
        /** `code` when there is no root shell to run the script in. */
        const val NO_ROOT = -1
    }
}

/** [s] as one single-quoted sh word. */
fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
