# SoundPower parameter protocol — version 1.0

This document is the contract between `libsoundpower.so` and anything that controls it (the app, tests,
the host harness). Its machine-readable counterpart is
[`protocol/include/soundpower/protocol.h`](../protocol/include/soundpower/protocol.h). If the two
disagree, the header is authoritative and this document has a bug.

## Effect identity

| | UUID |
|---|---|
| Effect **type** | `69dc09f7-12a9-471d-89a1-603608c452c9` |
| Effect **implementation** | `23e79ffb-ab65-4cd7-a768-b493ded3fabe` |

Both are random (version 4) UUIDs and never change across releases. The library name is `soundpower`,
the shared object is `libsoundpower.so`, and the log tag is `SoundPower`.

Descriptor flags: `EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST | EFFECT_FLAG_VOLUME_IND |
EFFECT_FLAG_DEVICE_IND`. The effect does not claim volume control and does not support offload.

## Transport

Parameters travel in the standard Android `effect_param_t` structure, through
`EFFECT_CMD_SET_PARAM` / `EFFECT_CMD_GET_PARAM` (from the app: the hidden
`AudioEffect.setParameter(byte[], byte[])` / `getParameter(byte[], byte[])`).

```
effect_param_t:  int32 status | uint32 psize | uint32 vsize | param[psize] | pad to 4 | value[vsize]
```

- **Param** is `uint32 id` (`psize = 4`), or `uint32 id, uint32 index` (`psize = 8`) for indexed items.
  No v1 parameter is indexed. The indexed form is reserved.
- **Value** is a packed little-endian byte sequence with the layout given below. Implementations must
  (de)serialize with `memcpy`, field by field. They must never cast the buffer to a struct, because
  `effect_param_t` buffers are only guaranteed 4-byte alignment.
- Every message is at most 1 KiB.
- Large data (impulse responses) never goes through `SET_PARAM`. The app copies the file into
  `/data/vendor/soundpower/ir/` as root, then sends its relative path (phase 6).

## Versioning rules

- `PROTOCOL_INFO` reports `major.minor`. A controller **must not** send `SET_PARAM` if the major version
  differs from its own. The app then shows "update the module" (or "update the app").
- A minor version may only **append** fields to existing payloads or **add** new IDs.
- On `SET_PARAM`, the effect accepts `vsize >= ` the size it knows and ignores trailing bytes. This
  lets a newer app talk to an older effect. A smaller `vsize` is an error.
- On `GET_PARAM`, the effect writes the full payload it knows and sets `vsize` accordingly. If the
  caller's reply buffer is too small, it returns `-EINVAL`.

## Status codes

`effect_param_t.status` and the `int32` reply of `SET_PARAM` use Android `status_t` values.

| Value | Meaning |
|---|---|
| `0` | OK |
| `-22` (`-EINVAL`) | Unknown ID, malformed sizes, or wrong direction (`GET` on a write-only ID or vice versa) |

Out-of-range numeric values are **clamped**, not rejected. `GET_PARAM` returns the clamped values.

## Parameters

| ID | Name | Direction | Payload size |
|---|---|---|---|
| `0x0001` | `PROTOCOL_INFO` | GET | 16 |
| `0x0002` | `STATUS` | GET | 48 |
| `0x0003` | `SPECTRUM` | GET (phase 6) | 4 + 4·n |
| `0x0100` | `MASTER` | SET / GET | 8 |
| `0x0200` | `EQ_STATE` | SET / GET | 4 + 16·count |
| `0x0300` | `LIMITER` | SET / GET | 12 |
| `0x1000`–`0x1FFF` | reserved for phase 6 modules (convolver, multiband compressor, bass, loudness, width, crossfeed) | | |

### `0x0001 PROTOCOL_INFO` (GET)

| Offset | Type | Field |
|---|---|---|
| 0 | u16 | `major` (1) |
| 2 | u16 | `minor` (0) |
| 4 | u32 | `lib_version`: `(major << 16) \| (minor << 8) \| patch` of `VERSION` |
| 8 | u32 | `max_bands` (32) |
| 12 | u32 | `capabilities`: bitmask, see `SP_CAP_*` |

### `0x0002 STATUS` (GET)

Describes the instance that answers, plus process-wide counters.

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | `sample_rate` (Hz, 0 before `SET_CONFIG`) |
| 4 | u32 | `channels` |
| 8 | u32 | `format`: Android `audio_format_t` (`0x1` PCM 16-bit, `0x5` PCM float) |
| 12 | u32 | `state`: 0 uninitialized, 1 initialized, 2 active, 3 disabling |
| 16 | f32 | `cpu_avg_percent`: `process()` time ÷ buffer duration, exponential average |
| 20 | f32 | `cpu_peak_percent`: maximum since last read |
| 24 | u32 | `instances`: live instances in the process |
| 28 | i32 | `session_id` |
| 32 | u32 | `flags`: bit 0 bypassed, bit 1 duplicate-session guard active, bit 2 non-finite input detected |
| 36 | f32 | `limiter_gr_db`: current limiter gain reduction (≤ 0) |
| 40 | u64 | `frames_processed` |

### `0x0003 SPECTRUM` (GET, phase 6)

`u32 bin_count`, then `bin_count` × `f32` magnitudes in dBFS, log-spaced from 20 Hz to
min(20 kHz, fs/2). The FFT runs inside the `GET_PARAM` handler, on a binder thread, over the latest
samples from a lock-free ring buffer that `process()` fills.

### `0x0100 MASTER` (SET / GET)

| Offset | Type | Field | Range / default |
|---|---|---|---|
| 0 | u32 | `flags`: bit 0 bypass (A/B), bit 1 auto-preamp | default `0x2` |
| 4 | f32 | `preamp_db` | −30…+30, default 0 |

With auto-preamp on, the effect adds `−max(0, peak of the EQ curve in dB)` to `preamp_db`, so the
EQ alone cannot push a full-scale signal over 0 dBFS.

### `0x0200 EQ_STATE` (SET / GET)

Replaces the whole EQ atomically. The new curve crossfades in over ~10 ms.

| Offset | Type | Field |
|---|---|---|
| 0 | u32 | `count` (0…32) |
| 4 + 16·i | band | band `i` |

Each band is 16 bytes:

| Offset | Type | Field | Range |
|---|---|---|---|
| 0 | u8 | `type` | see below |
| 1 | u8 | `enabled` | 0 or 1 |
| 2 | u16 | reserved | must be 0 |
| 4 | f32 | `freq_hz` | 10 … 0.49·fs (clamped per instance) |
| 8 | f32 | `gain_db` | −30 … +30 (ignored by pass, notch and band-pass types) |
| 12 | f32 | `q` | 0.05 … 40 |

Band types (RBJ Audio EQ Cookbook designs):

| Value | Type |
|---|---|
| 0 | peaking |
| 1 | low shelf |
| 2 | high shelf |
| 3 | low pass (2nd order) |
| 4 | high pass (2nd order) |
| 5 | notch |
| 6 | band pass (0 dB peak) |

### `0x0300 LIMITER` (SET / GET)

The limiter is always active. Only its settings change.

| Offset | Type | Field | Range / default |
|---|---|---|---|
| 0 | f32 | `ceiling_db` | −24 … 0, default −1 |
| 4 | f32 | `lookahead_ms` | 0.5 … 10, default 1.5 |
| 8 | f32 | `release_ms` | 1 … 1000, default 50 |

Changing `lookahead_ms` changes the effect's latency. The output fades out and back in over about 2 ms
around the change.

## Persisted state file (`state.bin`)

Path: `/data/vendor/soundpower/state.bin`, written by the app as root, read-only for the HAL.
The file uses the same payloads as `SET_PARAM`:

```
header:  char magic[4] = "SPST" | u16 major | u16 minor | u32 entry_count
entry:   u32 id | u32 size | payload[size] | pad to 4
```

The library applies the entries in file order when the first instance is created. A file with a
different major version is ignored and logged.

## Golden vectors

`protocol/vectors/*.hex` will hold reference encodings of every message, starting in phase 3. The C++
tests and the Kotlin tests serialize the same values and must produce identical bytes.
