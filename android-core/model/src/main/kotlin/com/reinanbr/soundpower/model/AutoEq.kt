// SPDX-License-Identifier: GPL-3.0-or-later
package com.reinanbr.soundpower.model

import com.reinanbr.soundpower.protocol.BandType

/**
 * One headphone of the AutoEQ database bundled with the app (github.com/jaakkopasanen/AutoEq, MIT):
 * a parametric correction towards AutoEQ's target, from one measurement.
 */
data class AutoEqEntry(
    val name: String,
    /** Who measured it, e.g. "oratory1990". */
    val source: String,
    /** The measurement rig or form factor, e.g. "over-ear", "711 in-ear". */
    val rig: String,
    /** AutoEQ's recommended result for this headphone (the most accurate of its sources). */
    val recommended: Boolean,
    val bands: List<ParametricBand>,
) {
    val id: String get() = "$PREFIX$source/$rig/$name"

    /** Shown under the name. */
    val sourceLabel: String get() = "$source · $rig"

    /** Auto-preamp takes the place of AutoEQ's preamp: both are the peak of the correction. */
    fun preset(): Preset = Preset(
        id = id,
        name = name,
        eq = EqProfile(mode = EqMode.PARAMETRIC, parametric = bands),
        kind = PresetKind.AUTOEQ,
        source = sourceLabel,
    )

    companion object {
        const val PREFIX = "autoeq:"
    }
}

/** Reading AutoEQ's files and the bundled database. */
object AutoEq {
    private val FILTER = Regex(
        """^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Z]+)\s+Fc\s+([-\d.]+)\s*Hz(?:\s+Gain\s+([-\d.]+)\s*dB)?(?:\s+Q\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE,
    )

    /** Q when a file gives none (Equalizer APO allows that for shelves and passes). */
    private const val DEFAULT_Q = 0.7071f

    private val TYPES = mapOf(
        "PK" to BandType.PEAKING, "PEQ" to BandType.PEAKING,
        "LSC" to BandType.LOW_SHELF, "LS" to BandType.LOW_SHELF,
        "HSC" to BandType.HIGH_SHELF, "HS" to BandType.HIGH_SHELF,
        "LPQ" to BandType.LOW_PASS, "LP" to BandType.LOW_PASS,
        "HPQ" to BandType.HIGH_PASS, "HP" to BandType.HIGH_PASS,
        "NO" to BandType.NOTCH, "BP" to BandType.BAND_PASS,
    )

    /**
     * The filters of a `ParametricEQ.txt` (Equalizer APO's format, which AutoEQ writes), as
     * parametric bands within the app's ranges. The preamp line is ignored: auto-preamp does that.
     */
    fun parseParametric(text: String): List<ParametricBand> = text.lineSequence().mapNotNull { line ->
        val m = FILTER.find(line) ?: return@mapNotNull null
        val type = TYPES[m.groupValues[2].uppercase()] ?: return@mapNotNull null
        ParametricBand(
            type = type,
            freqHz = m.groupValues[3].toFloatOrNull() ?: return@mapNotNull null,
            gainDb = m.groupValues[4].toFloatOrNull() ?: 0f,
            q = m.groupValues[5].toFloatOrNull() ?: DEFAULT_Q,
            enabled = m.groupValues[1].equals("ON", ignoreCase = true),
        ).normalized()
    }.take(EqProfile.MAX_PARAMETRIC_BANDS).toList()

    /**
     * A `GraphicEQ.txt` (`GraphicEQ: 20 -2.3; 21 -2.2; ...`) as gains on the 31-band layout,
     * which applying the preset resamples onto the user's layout. Null if [text] has no curve.
     */
    fun parseGraphic(text: String): GraphicBands? {
        val body = text.substringAfter("GraphicEQ:", "").ifEmpty { return null }
        val points = body.split(';').mapNotNull { p ->
            val parts = p.trim().split(Regex("\\s+"))
            val f = parts.getOrNull(0)?.toFloatOrNull()
            val g = parts.getOrNull(1)?.toFloatOrNull()
            if (f != null && g != null && f > 0f) f to g else null
        }.sortedBy { it.first }
        if (points.isEmpty()) return null
        val layout = BandLayout.THIRTY_ONE
        val hz = points.map { it.first }
        val db = points.map { it.second }
        return GraphicBands(layout, layout.frequencies.map { Ranges.snapDb(BandLayout.interpolate(hz, db, it), Ranges.BAND_GAIN_DB) })
    }

    /** An EQ from either AutoEQ file, or null if [text] is neither. */
    fun parse(text: String): EqProfile? {
        parseGraphic(text)?.let { return EqProfile(mode = EqMode.GRAPHIC, graphic = it) }
        val bands = parseParametric(text)
        return if (bands.isEmpty()) null else EqProfile(mode = EqMode.PARAMETRIC, parametric = bands)
    }

    /*
     * The bundled database (tools/autoeq/build_db.py writes it): one headphone per line,
     * `name TAB source TAB rig TAB recommended(0|1) TAB filters`, filters separated by spaces as
     * `<type><freq>:<gain>:<q>` with type L (low shelf), H (high shelf) or P (peaking).
     * Lines starting with `#` are comments.
     */

    fun readDatabase(lines: Sequence<String>): List<AutoEqEntry> = lines.mapNotNull(::readEntry).toList()

    internal fun readEntry(line: String): AutoEqEntry? {
        if (line.isBlank() || line.startsWith('#')) return null
        val f = line.split('\t')
        if (f.size != 5) return null
        val bands = f[4].split(' ').mapNotNull { token ->
            val type = when (token.firstOrNull()) {
                'L' -> BandType.LOW_SHELF
                'H' -> BandType.HIGH_SHELF
                'P' -> BandType.PEAKING
                else -> return@mapNotNull null
            }
            val v = token.substring(1).split(':').map { it.toFloatOrNull() ?: return@mapNotNull null }
            if (v.size != 3) return@mapNotNull null
            ParametricBand(type, v[0], v[1], v[2]).normalized()
        }
        if (bands.isEmpty()) return null
        return AutoEqEntry(f[0], f[1], f[2], f[3] == "1", bands)
    }

    fun writeEntry(e: AutoEqEntry): String {
        val filters = e.bands.joinToString(" ") { b ->
            val t = when (b.type) {
                BandType.LOW_SHELF -> 'L'
                BandType.HIGH_SHELF -> 'H'
                else -> 'P'
            }
            "$t${fmt(b.freqHz)}:${fmt(b.gainDb)}:${fmt(b.q)}"
        }
        return listOf(e.name, e.source, e.rig, if (e.recommended) "1" else "0", filters).joinToString("\t")
    }

    private fun fmt(v: Float): String = v.toString().removeSuffix(".0")
}

/** Search over the AutoEQ database, and the headphones a device name probably is. */
class AutoEqIndex(val entries: List<AutoEqEntry>) {
    private val keys = entries.map { key(it.name) }
    private val byId = entries.associateBy { it.id }

    /** The entry whose preset has [id] ([AutoEqEntry.id]). */
    fun find(id: String): AutoEqEntry? = byId[id]

    /** Recommended results first, then by name. */
    private val order = Comparator<Int> { a, b ->
        compareValuesBy(entries[a], entries[b], { !it.recommended }, { it.name.lowercase() }, { it.source })
    }

    /**
     * Entries whose name contains every word of [query], ignoring case, spaces and punctuation.
     * Names where the query starts at a word come first ("hd 600" and "hd600" find "HD 600"
     * before "AH-D600"), then those with more of its words at a word start; ties go by [order].
     */
    fun search(query: String, limit: Int = Int.MAX_VALUE): List<AutoEqEntry> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        val joined = words.joinToString("")
        val hits = entries.indices.filter { i -> words.all { keys[i].contains(it) } }
        val score = hits.associateWith { i ->
            val tokens = words(entries[i].name)
            val phrase = tokens.indices.any { j -> tokens.subList(j, tokens.size).joinToString("").startsWith(joined) }
            if (phrase) words.size + 1 else words.count { w -> tokens.any { it.startsWith(w) } }
        }
        return hits.sortedWith(compareByDescending<Int> { score.getValue(it) }.then(order)).take(limit).map { entries[it] }
    }

    /**
     * Headphones that [deviceName] (what a Bluetooth or USB device calls itself) probably is: the
     * entries whose name contains the device's name or the other way round, ignoring case, spaces
     * and punctuation, the closest in length first. What devices add to their model name is left
     * out: a bracketed suffix ("Buds2 Pro (1A2B)") and the "LE-" of Bluetooth LE audio. Names
     * shorter than [MIN_KEY] match nothing.
     */
    fun match(deviceName: String, limit: Int = 10): List<AutoEqEntry> {
        val device = key(deviceName.replace(BRACKETED, " ").trim().removePrefix("LE-"))
        if (device.length < MIN_KEY) return emptyList()
        val hits = entries.indices.filter { i ->
            val k = keys[i]
            k.length >= MIN_KEY && (k.contains(device) || device.contains(k))
        }
        return hits.sortedWith(compareBy<Int> { kotlin.math.abs(keys[it].length - device.length) }.then(order))
            .take(limit).map { entries[it] }
    }

    companion object {
        const val MIN_KEY = 4
        private val BRACKETED = Regex("""\([^)]*\)|\[[^]]*]""")

        internal fun key(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

        private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

        private fun words(text: String): List<String> = text.lowercase().split(SEPARATORS).filter { it.isNotEmpty() }
    }
}
