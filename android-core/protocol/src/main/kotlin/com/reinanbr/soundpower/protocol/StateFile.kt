// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.protocol

/**
 * `state.bin` (docs/protocol.md, "Persisted state file"):
 *
 *     header: "SPST" | u16 major | u16 minor | u32 entry_count
 *     entry:  u32 id | u32 size | payload[size] | pad to 4
 *
 * Decoding is all-or-nothing, with the effect's rules, so the app never keeps a file the effect
 * would reject.
 */
object StateFile {
    const val HEADER_SIZE = 12
    private const val ENTRY_HEADER_SIZE = 8

    class Entry(val id: Int, val payload: ByteArray) {
        override fun equals(other: Any?) = other is Entry && id == other.id && payload.contentEquals(other.payload)
        override fun hashCode() = 31 * id + payload.contentHashCode()
        override fun toString() = "Entry(0x%04x, %d bytes)".format(id, payload.size)
    }

    class Contents(val major: Int, val minor: Int, val entries: List<Entry>)

    fun encode(
        entries: List<Entry>,
        major: Int = Protocol.VERSION_MAJOR,
        minor: Int = Protocol.VERSION_MINOR,
    ): ByteArray {
        val size = HEADER_SIZE + entries.sumOf { ENTRY_HEADER_SIZE + pad4(it.payload.size) }
        require(size <= Protocol.STATE_FILE_MAX_BYTES) { "state file of $size bytes" }
        val w = Wire.writer(size)
        w.put(Protocol.STATE_FILE_MAGIC.toByteArray(Charsets.US_ASCII))
        w.putShort(major.toShort()).putShort(minor.toShort()).putInt(entries.size)
        for (e in entries) {
            require(e.payload.size <= Protocol.MAX_MESSAGE_BYTES) { "entry 0x%04x is too large".format(e.id) }
            w.putInt(e.id).putInt(e.payload.size).put(e.payload)
            repeat(pad4(e.payload.size) - e.payload.size) { w.put(0) }
        }
        return w.array()
    }

    fun decode(bytes: ByteArray): Contents {
        if (bytes.size > Protocol.STATE_FILE_MAX_BYTES) throw ProtocolException("state file of ${bytes.size} bytes")
        val r = Wire.reader(bytes, HEADER_SIZE, "state file header")
        val magic = ByteArray(4).also { r.get(it) }
        if (!magic.contentEquals(Protocol.STATE_FILE_MAGIC.toByteArray(Charsets.US_ASCII))) {
            throw ProtocolException("not a state file")
        }
        val major = r.getShort().toInt() and 0xffff
        val minor = r.getShort().toInt() and 0xffff
        if (major != Protocol.VERSION_MAJOR) throw ProtocolException("state file has protocol major $major")
        val count = r.getInt()
        if (count < 0) throw ProtocolException("state file has $count entries")
        val entries = ArrayList<Entry>()
        repeat(count) { i ->
            if (r.remaining() < ENTRY_HEADER_SIZE) throw ProtocolException("entry $i is truncated")
            val id = r.getInt()
            val size = r.getInt()
            if (size < 0 || size > Protocol.MAX_MESSAGE_BYTES) throw ProtocolException("entry $i has $size bytes")
            if (r.remaining() < pad4(size)) throw ProtocolException("entry $i is truncated")
            val payload = ByteArray(size).also { r.get(it) }
            r.position(r.position() + pad4(size) - size)
            entries += Entry(id, payload)
        }
        if (r.hasRemaining()) throw ProtocolException("${r.remaining()} bytes after the last entry")
        return Contents(major, minor, entries)
    }

    private fun pad4(n: Int) = (n + 3) and 3.inv()
}
