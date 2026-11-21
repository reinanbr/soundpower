// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.protocol

import java.io.File

/** Reads protocol/vectors/<name>.hex: `#` comment lines, then hex bytes. */
object GoldenVectors {
    private val dir: File by lazy {
        val path = System.getProperty("soundpower.vectors") ?: error("soundpower.vectors is not set")
        File(path)
    }

    fun bytes(name: String): ByteArray {
        val hex = File(dir, "$name.hex").readLines()
            .filterNot { it.startsWith("#") }
            .joinToString(" ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        return ByteArray(hex.size) { hex[it].toInt(16).toByte() }
    }
}
