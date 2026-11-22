#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 SoundPower contributors
"""Writes the protocol golden vectors (*.hex) next to this script.

The vectors are built with Python's struct module straight from docs/protocol.md, independently
of the C++ and Kotlin encoders, which must both reproduce them byte for byte. Every float here
is exactly representable in binary32, so no implementation can disagree on rounding.

Usage: python3 protocol/vectors/generate.py   (then commit the changed .hex files)
"""
import os
import struct

HERE = os.path.dirname(os.path.abspath(__file__))

MASTER = 0x0100
EQ_STATE = 0x0200
LIMITER = 0x0300
TONE = 0x0400
STEREO = 0x0500
COMPRESSOR = 0x0600
INPUT_VOLUME_SESSIONS = 0x0700

PEAKING, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS, NOTCH, BAND_PASS = range(7)


def master(bypass, auto_preamp, preamp_db, limiter_off=False, direct_volume=False):
    flags = ((1 if bypass else 0) | (2 if auto_preamp else 0) | (4 if limiter_off else 0) |
             (8 if direct_volume else 0))
    return struct.pack("<If", flags, preamp_db)


def tone(enabled, bass_db, bass_freq, treble_db, treble_freq):
    return struct.pack("<Iffff", 1 if enabled else 0, bass_db, bass_freq, treble_db, treble_freq)


def stereo(mono, balance, width):
    return struct.pack("<Iff", 1 if mono else 0, balance, width)


def compressor(enabled, threshold_db, ratio, attack_ms, release_ms, knee_db, makeup_db):
    return struct.pack("<Iffffff", 1 if enabled else 0, threshold_db, ratio, attack_ms,
                       release_ms, knee_db, makeup_db)


def eq_state(bands):
    """Bands are (type, enabled, freq, gain, q) or, since 1.1, (..., channel)."""
    out = struct.pack("<I", len(bands))
    for band in bands:
        band_type, enabled, freq, gain, q = band[:5]
        channel = band[5] if len(band) > 5 else ALL
        out += struct.pack("<BBBBfff", band_type, 1 if enabled else 0, channel, 0, freq, gain, q)
    return out


def limiter(ceiling_db, lookahead_ms, release_ms):
    return struct.pack("<fff", ceiling_db, lookahead_ms, release_ms)


def status(sample_rate, channels, fmt, state, cpu_avg, cpu_peak, instances, session, flags,
           limiter_gr, frames):
    return struct.pack("<IIIIffIiIfQ", sample_rate, channels, fmt, state, cpu_avg, cpu_peak,
                       instances, session, flags, limiter_gr, frames)


def input_volume_sessions(sessions):
    return struct.pack("<I" + "i" * len(sessions), len(sessions), *sessions)


def state_file(entries, major=1, minor=0):
    out = b"SPST" + struct.pack("<HHI", major, minor, len(entries))
    for entry_id, payload in entries:
        out += struct.pack("<II", entry_id, len(payload)) + payload
        out += b"\0" * ((4 - len(payload) % 4) % 4)
    return out


# Band channels (protocol 1.1).
ALL, LEFT, RIGHT, MID, SIDE = range(5)

ROUTED_BANDS = [
    (PEAKING, True, 250.0, 4.0, 1.0, LEFT),
    (PEAKING, True, 250.0, -2.0, 1.0, RIGHT),
    (HIGH_SHELF, True, 6000.0, 3.0, 0.7, SIDE),
    (LOW_PASS, True, 120.0, 0.0, 0.707, MID),
    (PEAKING, True, 3000.0, 1.5, 2.0, ALL),
]

THREE_BANDS = [
    (LOW_SHELF, True, 80.0, 6.0, 0.75),
    (PEAKING, True, 1000.0, -3.5, 1.5),
    (HIGH_PASS, False, 30.0, 0.0, 0.5),
]

VECTORS = {
    "master_default": (
        "MASTER (0x0100) defaults: bypass off, auto-preamp on, preamp 0 dB",
        master(False, True, 0.0)),
    "master_bypass": (
        "MASTER (0x0100): bypass on, auto-preamp off, preamp -6.5 dB",
        master(True, False, -6.5)),
    "eq_state_empty": (
        "EQ_STATE (0x0200) with no bands",
        eq_state([])),
    "eq_state_3band": (
        "EQ_STATE (0x0200): low shelf 80 Hz +6 dB Q 0.75 on; peaking 1 kHz -3.5 dB Q 1.5 on; "
        "high pass 30 Hz Q 0.5 off",
        eq_state(THREE_BANDS)),
    "eq_state_channels": (
        "EQ_STATE (0x0200), protocol 1.1 band channels: peaking 250 Hz +4 dB Q 1 left; "
        "peaking 250 Hz -2 dB Q 1 right; high shelf 6 kHz +3 dB Q 0.7 side; "
        "low pass 120 Hz Q 0.707 mid; peaking 3 kHz +1.5 dB Q 2 all",
        eq_state(ROUTED_BANDS)),
    "limiter_default": (
        "LIMITER (0x0300) defaults: ceiling -1 dB, lookahead 1.5 ms, release 50 ms",
        limiter(-1.0, 1.5, 50.0)),
    "limiter_custom": (
        "LIMITER (0x0300): ceiling -3 dB, lookahead 5 ms, release 250 ms",
        limiter(-3.0, 5.0, 250.0)),
    "master_limiter_off": (
        "MASTER (0x0100), protocol 1.1: auto-preamp on, limiter off, preamp 3.25 dB",
        master(False, True, 3.25, limiter_off=True)),
    "tone_default": (
        "TONE (0x0400) defaults: on, bass 0 dB at 100 Hz, treble 0 dB at 8 kHz",
        tone(True, 0.0, 100.0, 0.0, 8000.0)),
    "tone_custom": (
        "TONE (0x0400): off, bass +7.5 dB at 62.5 Hz, treble -3 dB at 12 kHz",
        tone(False, 7.5, 62.5, -3.0, 12000.0)),
    "stereo_default": (
        "STEREO (0x0500) defaults: not mono, balance 0, width 1",
        stereo(False, 0.0, 1.0)),
    "stereo_custom": (
        "STEREO (0x0500): mono, balance -0.25, width 1.5",
        stereo(True, -0.25, 1.5)),
    "compressor_default": (
        "COMPRESSOR (0x0600) defaults: off, threshold -18 dB, ratio 3, attack 10 ms, "
        "release 150 ms, knee 6 dB, makeup 0 dB",
        compressor(False, -18.0, 3.0, 10.0, 150.0, 6.0, 0.0)),
    "compressor_custom": (
        "COMPRESSOR (0x0600): on, threshold -24 dB, ratio 4.5, attack 2.5 ms, release 400 ms, "
        "knee 0 dB, makeup 6 dB",
        compressor(True, -24.0, 4.5, 2.5, 400.0, 0.0, 6.0)),
    "state_file_v1_1": (
        "state.bin, protocol 1.1: MASTER = master_limiter_off, EQ_STATE = eq_state_3band, "
        "LIMITER = limiter_custom, TONE = tone_custom, STEREO = stereo_custom, "
        "COMPRESSOR = compressor_custom",
        state_file([(MASTER, master(False, True, 3.25, limiter_off=True)),
                    (EQ_STATE, eq_state(THREE_BANDS)),
                    (LIMITER, limiter(-3.0, 5.0, 250.0)),
                    (TONE, tone(False, 7.5, 62.5, -3.0, 12000.0)),
                    (STEREO, stereo(True, -0.25, 1.5)),
                    (COMPRESSOR, compressor(True, -24.0, 4.5, 2.5, 400.0, 0.0, 6.0))], minor=1)),
    "master_direct_volume": (
        "MASTER (0x0100), protocol 1.2: auto-preamp on, direct volume control on, preamp -2.5 dB",
        master(False, True, -2.5, direct_volume=True)),
    "state_file_v1_2": (
        "state.bin, protocol 1.2: state_file_v1_1 with direct volume control on in MASTER "
        "(auto-preamp on, limiter off, direct volume, preamp 3.25 dB)",
        state_file([(MASTER, master(False, True, 3.25, limiter_off=True, direct_volume=True)),
                    (EQ_STATE, eq_state(THREE_BANDS)),
                    (LIMITER, limiter(-3.0, 5.0, 250.0)),
                    (TONE, tone(False, 7.5, 62.5, -3.0, 12000.0)),
                    (STEREO, stereo(True, -0.25, 1.5)),
                    (COMPRESSOR, compressor(True, -24.0, 4.5, 2.5, 400.0, 0.0, 6.0))], minor=2)),
    "state_file_v1_3": (
        "state.bin, protocol 1.3: state_file_v1_2 as a 1.3 writer stores it (INPUT_VOLUME_SESSIONS "
        "is never persisted)",
        state_file([(MASTER, master(False, True, 3.25, limiter_off=True, direct_volume=True)),
                    (EQ_STATE, eq_state(THREE_BANDS)),
                    (LIMITER, limiter(-3.0, 5.0, 250.0)),
                    (TONE, tone(False, 7.5, 62.5, -3.0, 12000.0)),
                    (STEREO, stereo(True, -0.25, 1.5)),
                    (COMPRESSOR, compressor(True, -24.0, 4.5, 2.5, 400.0, 0.0, 6.0))], minor=3)),
    "input_volume_sessions_example": (
        "INPUT_VOLUME_SESSIONS (0x0700), protocol 1.3: sessions 1449, 33001 and 7",
        input_volume_sessions([1449, 33001, 7])),
    "input_volume_sessions_empty": (
        "INPUT_VOLUME_SESSIONS (0x0700), protocol 1.3: no session",
        input_volume_sessions([])),
    "status_example": (
        "STATUS (0x0002): 48 kHz, 2 ch, float (0x5), active, cpu avg 1.25 % peak 4.5 %, "
        "3 instances, session -7, flags 0x5, limiter -2.75 dB, 123456789012 frames",
        status(48000, 2, 0x5, 2, 1.25, 4.5, 3, -7, 0x5, -2.75, 123456789012)),
    "state_file_example": (
        "state.bin: MASTER = master_bypass, EQ_STATE = eq_state_3band, LIMITER = limiter_custom",
        state_file([(MASTER, master(True, False, -6.5)),
                    (EQ_STATE, eq_state(THREE_BANDS)),
                    (LIMITER, limiter(-3.0, 5.0, 250.0))])),
    "state_file_unknown_entry": (
        "state.bin, protocol 1.7: state_file_example plus an entry with an unknown ID (0x7777, "
        "3 bytes + 1 pad byte) that readers must skip",
        state_file([(MASTER, master(True, False, -6.5)),
                    (0x7777, b"abc"),
                    (EQ_STATE, eq_state(THREE_BANDS)),
                    (LIMITER, limiter(-3.0, 5.0, 250.0))], minor=7)),
}


def to_hex(data):
    lines = []
    for i in range(0, len(data), 16):
        lines.append(" ".join(f"{b:02x}" for b in data[i:i + 16]))
    return "\n".join(lines)


def main():
    for name, (comment, data) in sorted(VECTORS.items()):
        path = os.path.join(HERE, name + ".hex")
        with open(path, "w", encoding="ascii") as f:
            f.write(f"# {comment}\n# {len(data)} bytes. Generated by generate.py; do not edit.\n")
            f.write(to_hex(data) + "\n")
        print(f"{path}: {len(data)} bytes")


if __name__ == "__main__":
    main()
